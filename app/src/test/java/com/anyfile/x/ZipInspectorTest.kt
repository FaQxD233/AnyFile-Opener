package com.anyfile.x

import com.anyfile.x.engine.ContainerAccess
import com.anyfile.x.engine.FileChannelSource
import com.anyfile.x.engine.MimeDetector
import com.anyfile.x.engine.RandomAccessSource
import com.anyfile.x.engine.ZipInspector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.InputStream
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random

class ZipInspectorTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun apkIsFoundEvenWhenLargeDexComesFirst() {
        val file = zip(
            "classes.dex" to Random(1).nextBytes(100_000),
            "AndroidManifest.xml" to ByteArray(512),
            "resources.arsc" to ByteArray(256)
        )
        val result = detect(file, "download.bin")
        assertEquals("application/vnd.android.package-archive", result.mime)
        assertEquals(MimeDetector.DetectionConfidence.HIGH, result.confidence)
    }

    @Test
    fun mimetypeEntryIdentifiesEpubAndOpenDocument() {
        val epub = zip(
            "mimetype" to "application/epub+zip".toByteArray(),
            "META-INF/container.xml" to ByteArray(16),
            stored = true
        )
        assertEquals("application/epub+zip", detect(epub).mime)

        val odt = zip(
            "mimetype" to "application/vnd.oasis.opendocument.text".toByteArray(),
            "content.xml" to ByteArray(16),
            stored = true
        )
        val result = detect(odt, "report.zip")
        assertEquals("application/vnd.oasis.opendocument.text", result.mime)
        assertEquals(MimeDetector.FileType.DOCUMENT, result.fileType)
    }

    @Test
    fun officeOpenXmlFamilyComesFromPartNames() {
        assertEquals(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            detect(officeZip("word/document.xml")).mime
        )
        assertEquals(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            detect(officeZip("xl/workbook.xml")).mime
        )
        assertEquals(
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            detect(officeZip("ppt/presentation.xml")).mime
        )
    }

    @Test
    fun splitApkBundles() {
        val xapk = detect(zip("manifest.json" to ByteArray(8), "com.example.apk" to ByteArray(8)))
        assertEquals("application/xapk-package-archive", xapk.mime)
        assertEquals(MimeDetector.FileType.APK, xapk.fileType)

        val apkm = detect(zip("info.json" to ByteArray(8), "base.apk" to ByteArray(8)))
        assertEquals("application/vnd.apkm", apkm.mime)

        val apks = detect(zip("toc.pb" to ByteArray(8), "splits/base-master.apk" to ByteArray(8)))
        assertEquals("application/octet-stream", apks.mime)
        assertEquals(MimeDetector.FileType.APK, apks.fileType)

        val plain = zip("base.apk" to ByteArray(8), "split.apk" to ByteArray(8))
        assertEquals("application/octet-stream", detect(plain, "set.apks").mime)
        assertEquals("application/zip", detect(plain, "apps.zip").mime)
    }

    @Test
    fun jarAndGenericZip() {
        val jar = zip("META-INF/MANIFEST.MF" to "Manifest-Version: 1.0\n".toByteArray(), "a/B.class" to ByteArray(8))
        assertEquals("application/java-archive", detect(jar).mime)

        val generic = detect(zip("a.txt" to ByteArray(8), "b.txt" to ByteArray(8)))
        assertEquals("application/zip", generic.mime)
        assertEquals(MimeDetector.DetectionConfidence.HIGH, generic.confidence)
    }

    @Test
    fun centralDirectoryListsEveryEntry() {
        val file = zip("one" to ByteArray(4), "two/three" to ByteArray(4), "four" to ByteArray(4))
        FileChannel.open(file.toPath(), StandardOpenOption.READ).use { channel ->
            val listing = ZipInspector.centralDirectory(FileChannelSource(channel))!!
            assertEquals(listOf("one", "two/three", "four"), listing.names)
            assertTrue(listing.complete)
        }
    }

    @Test
    fun localHeaderWalkWorksWithoutSeeking() {
        val stored = zip(
            "AndroidManifest.xml" to ByteArray(64),
            "classes.dex" to ByteArray(64),
            stored = true
        )
        assertEquals("application/vnd.android.package-archive", detect(stored, seekable = false).mime)

        // Deflated entries written by ZipOutputStream carry their sizes after the data.
        val deflated = zip("a.txt" to ByteArray(64), "AndroidManifest.xml" to ByteArray(64))
        val listing = deflated.inputStream().use { ZipInspector.localHeaders(it) }
        assertEquals(listOf("a.txt"), listing.names)
        assertFalse(listing.complete)
        assertEquals(MimeDetector.DetectionConfidence.MEDIUM, detect(deflated, seekable = false).confidence)
    }

    @Test
    fun headerAloneIsEnoughForSmallStoredArchives() {
        val file = zip(
            "AndroidManifest.xml" to ByteArray(100),
            "classes.dex" to ByteArray(100),
            stored = true
        )
        val result = MimeDetector.detectFromHeader(file.readBytes(), "unknown")
        assertEquals("application/vnd.android.package-archive", result.mime)
    }

    private fun officeZip(part: String): File = zip(
        "[Content_Types].xml" to ByteArray(16),
        "_rels/.rels" to ByteArray(16),
        part to ByteArray(16)
    )

    private fun zip(vararg entries: Pair<String, ByteArray>, stored: Boolean = false): File {
        val file = temp.newFile()
        ZipOutputStream(file.outputStream()).use { output ->
            for ((name, data) in entries) {
                val entry = ZipEntry(name)
                if (stored) {
                    entry.method = ZipEntry.STORED
                    entry.size = data.size.toLong()
                    entry.compressedSize = data.size.toLong()
                    entry.crc = CRC32().apply { update(data) }.value
                }
                output.putNextEntry(entry)
                output.write(data)
                output.closeEntry()
            }
        }
        return file
    }

    private fun detect(
        file: File,
        name: String = "",
        seekable: Boolean = true
    ): MimeDetector.DetectionResult {
        val bytes = file.readBytes()
        val header = bytes.copyOf(minOf(bytes.size, 33_000))
        FileChannel.open(file.toPath(), StandardOpenOption.READ).use { channel ->
            val access = object : ContainerAccess {
                override fun randomAccess(): RandomAccessSource? =
                    if (seekable) FileChannelSource(channel) else null

                override fun openStream(): InputStream = file.inputStream()
            }
            return MimeDetector.detectFromHeader(header, name, access = access)
        }
    }
}
