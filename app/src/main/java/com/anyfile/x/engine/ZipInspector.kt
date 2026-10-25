package com.anyfile.x.engine

import com.anyfile.x.engine.MimeDetector.DetectionConfidence
import com.anyfile.x.engine.MimeDetector.DetectionResult
import com.anyfile.x.engine.MimeDetector.DetectionSource
import com.anyfile.x.engine.MimeDetector.FileType
import com.anyfile.x.engine.utils.ByteReader
import com.anyfile.x.engine.utils.ByteSearch
import com.anyfile.x.engine.utils.LittleEndian.u16
import com.anyfile.x.engine.utils.LittleEndian.u32
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.Locale

/**
 * Identifies ZIP-based formats from entry names. The central directory is read when
 * the file is seekable; otherwise local headers are walked. Entry data is never
 * inflated, so large entries cost nothing.
 */
internal object ZipInspector {

    data class Listing(val names: List<String>, val complete: Boolean)

    private const val LOCAL_HEADER = 0x04034b50L
    private const val CENTRAL_HEADER = 0x02014b50L
    private const val END_OF_DIRECTORY = 0x06054b50L
    private const val DATA_DESCRIPTOR = 0x08074b50L
    private const val LOCAL_HEADER_SIZE = 30
    private const val CENTRAL_HEADER_SIZE = 46
    private const val END_RECORD_SIZE = 22
    private const val MAX_COMMENT_SIZE = 0xFFFF
    private const val MAX_DIRECTORY_BYTES = 8 * 1024 * 1024
    private const val MAX_LISTED_ENTRIES = 20_000
    private const val MAX_WALKED_ENTRIES = 4096
    private const val MAX_SKIPPED_BYTES = 64L * 1024 * 1024
    private const val MAX_MIMETYPE_BYTES = 128
    private const val DATA_DESCRIPTOR_FLAG = 0x0008
    private const val UTF8_NAME_FLAG = 0x0800

    fun inspect(header: ByteArray, extension: String, access: ContainerAccess?): DetectionResult {
        firstEntryMimetype(header)?.let { mime ->
            return DetectionResult(
                MimeTypes.fileTypeFor(mime),
                mime,
                DetectionConfidence.HIGH,
                DetectionSource.CONTAINER_CONTENT,
                "ZIP mimetype entry declares $mime"
            )
        }

        val listing = list(header, access)
        classify(listing, extension)?.let { return it }

        return DetectionResult(
            FileType.ARCHIVE,
            MimeTypes.ZIP,
            if (listing.complete) DetectionConfidence.HIGH else DetectionConfidence.MEDIUM,
            DetectionSource.MAGIC_BYTES,
            if (listing.complete) {
                "ZIP archive with ${listing.names.size} entries"
            } else {
                "ZIP local-file signature; entry list incomplete"
            }
        )
    }

    /** Value of the stored `mimetype` entry that EPUB and OpenDocument place first. */
    fun firstEntryMimetype(header: ByteArray): String? {
        if (u32(header, 0) != LOCAL_HEADER || u16(header, 8) != 0) return null
        val size = u32(header, 18)
        val nameLength = u16(header, 26)
        val extraLength = u16(header, 28)
        if (nameLength != 8 || size <= 0 || size > MAX_MIMETYPE_BYTES) return null
        if (!ByteSearch.matchesAscii(header, LOCAL_HEADER_SIZE, "mimetype")) return null
        val start = LOCAL_HEADER_SIZE + nameLength + extraLength
        if (start + size > header.size) return null
        val value = String(header, start, size.toInt(), Charsets.US_ASCII)
            .trim()
            .lowercase(Locale.ROOT)
        return value.takeIf { it != MimeTypes.ANY && MimeTypes.isValid(it) }
    }

    /** Lists entry names as completely as the source allows. */
    fun list(header: ByteArray, access: ContainerAccess?): Listing {
        access?.randomAccess()?.let { source ->
            centralDirectory(source)?.let { return it }
        }
        access?.openStream()?.use { stream ->
            return localHeaders(stream)
        }
        return localHeaders(ByteArrayInputStream(header))
    }

    fun centralDirectory(source: RandomAccessSource): Listing? {
        val fileSize = source.size
        if (fileSize < END_RECORD_SIZE) return null
        val tailLength = minOf(fileSize, (END_RECORD_SIZE + MAX_COMMENT_SIZE).toLong()).toInt()
        val tailStart = fileSize - tailLength
        val tail = source.read(tailStart, tailLength) ?: return null

        var end = tail.size - END_RECORD_SIZE
        while (end >= 0 && u32(tail, end) != END_OF_DIRECTORY) end--
        if (end < 0) return null

        val totalEntries = u16(tail, end + 10)
        val directorySize = u32(tail, end + 12)
        val directoryOffset = u32(tail, end + 16)
        if (totalEntries == 0) return Listing(emptyList(), complete = true)
        // ZIP64 keeps the real values elsewhere; the local-header walk covers it.
        if (totalEntries == 0xFFFF || directorySize == 0xFFFFFFFFL ||
            directoryOffset == 0xFFFFFFFFL
        ) return null
        if (directorySize <= 0 || directorySize > MAX_DIRECTORY_BYTES) return null
        if (directoryOffset + directorySize > tailStart + end) return null
        val directory = source.read(directoryOffset, directorySize.toInt()) ?: return null

        val names = ArrayList<String>(minOf(totalEntries, MAX_LISTED_ENTRIES))
        var offset = 0
        while (names.size < totalEntries && names.size < MAX_LISTED_ENTRIES) {
            if (offset + CENTRAL_HEADER_SIZE > directory.size ||
                u32(directory, offset) != CENTRAL_HEADER
            ) break
            val flags = u16(directory, offset + 8)
            val nameLength = u16(directory, offset + 28)
            val extraLength = u16(directory, offset + 30)
            val commentLength = u16(directory, offset + 32)
            val nameStart = offset + CENTRAL_HEADER_SIZE
            if (nameStart + nameLength > directory.size) break
            names += decodeName(directory, nameStart, nameLength, flags)
            offset = nameStart + nameLength + extraLength + commentLength
        }
        if (names.isEmpty()) return null
        return Listing(names, complete = names.size == totalEntries)
    }

    /**
     * Walks local file headers, skipping entry data. Stops when an entry's size is
     * only known after its data (data descriptor) or lives in a ZIP64 field.
     */
    fun localHeaders(input: InputStream): Listing {
        val names = ArrayList<String>()
        val fixed = ByteArray(LOCAL_HEADER_SIZE)
        var skipped = 0L
        try {
            while (names.size < MAX_WALKED_ENTRIES) {
                if (!ByteReader.readFully(input, fixed, 0, 4)) return Listing(names, false)
                when (u32(fixed, 0)) {
                    LOCAL_HEADER -> Unit
                    CENTRAL_HEADER, END_OF_DIRECTORY -> return Listing(names, true)
                    else -> return Listing(names, false)
                }
                if (!ByteReader.readFully(input, fixed, 4, LOCAL_HEADER_SIZE - 4)) {
                    return Listing(names, false)
                }
                val flags = u16(fixed, 6)
                val compressedSize = u32(fixed, 18)
                val nameLength = u16(fixed, 26)
                val extraLength = u16(fixed, 28)
                val nameBytes = ByteArray(nameLength)
                if (!ByteReader.readFully(input, nameBytes)) return Listing(names, false)
                names += decodeName(nameBytes, 0, nameLength, flags)

                val hasDescriptor = (flags and DATA_DESCRIPTOR_FLAG) != 0
                if ((hasDescriptor && compressedSize == 0L) || compressedSize == 0xFFFFFFFFL) {
                    return Listing(names, false)
                }
                skipped += compressedSize
                if (skipped > MAX_SKIPPED_BYTES) return Listing(names, false)
                if (!ByteReader.skipFully(input, extraLength + compressedSize)) {
                    return Listing(names, false)
                }
                if (hasDescriptor && !skipDataDescriptor(input)) return Listing(names, false)
            }
        } catch (_: Exception) {
            return Listing(names, false)
        }
        return Listing(names, false)
    }

    fun classify(listing: Listing, extension: String): DetectionResult? {
        val names = listing.names.toHashSet()
        if ("AndroidManifest.xml" in names) {
            val marker = listOf("classes.dex", "resources.arsc").firstOrNull { it in names }
            if (marker != null) {
                return container(
                    FileType.APK,
                    MimeTypes.APK,
                    "ZIP contains AndroidManifest.xml and $marker"
                )
            }
        }
        splitApkBundle(listing.names, names, extension)?.let { return it }
        officeOpenXml(names)?.let { return it }
        if ("META-INF/MANIFEST.MF" in names) {
            return container(FileType.ARCHIVE, MimeTypes.JAR, "ZIP contains META-INF/MANIFEST.MF")
        }
        val known = MimeTypes.zipFlavor(extension) ?: return null
        return DetectionResult(
            known.fileType,
            known.mime,
            DetectionConfidence.MEDIUM,
            DetectionSource.FILE_EXTENSION,
            "ZIP container with .$extension extension"
        )
    }

    private fun splitApkBundle(
        entries: List<String>,
        names: Set<String>,
        extension: String
    ): DetectionResult? {
        val apkCount = entries.count { it.endsWith(".apk", ignoreCase = true) }
        if (apkCount == 0) return null
        val (mime, label) = when {
            "manifest.json" in names -> MimeTypes.XAPK to "XAPK"
            "info.json" in names -> MimeTypes.APKM to "APKM"
            "toc.pb" in names || "meta.sai_v2.json" in names || "meta.sai_v1.json" in names ->
                MimeTypes.APKS to "APKS"
            extension == "xapk" -> MimeTypes.XAPK to "XAPK"
            extension == "apkm" -> MimeTypes.APKM to "APKM"
            extension == "apks" -> MimeTypes.APKS to "APKS"
            else -> return null
        }
        return container(FileType.APK, mime, "$label bundle with $apkCount APK files")
    }

    private fun officeOpenXml(names: Set<String>): DetectionResult? {
        if ("[Content_Types].xml" !in names) return null
        val (mime, label) = when {
            names.any { it.startsWith("word/") } -> MimeTypes.DOCX to "Word"
            names.any { it.startsWith("xl/") } -> MimeTypes.XLSX to "Excel"
            names.any { it.startsWith("ppt/") } -> MimeTypes.PPTX to "PowerPoint"
            else -> return null
        }
        return container(FileType.DOCUMENT, mime, "OpenXML $label parts in ZIP")
    }

    private fun container(type: FileType, mime: String, evidence: String): DetectionResult =
        DetectionResult(
            type,
            mime,
            DetectionConfidence.HIGH,
            DetectionSource.CONTAINER_CONTENT,
            evidence
        )

    /** Skips a data descriptor, whose leading signature is optional. */
    private fun skipDataDescriptor(input: InputStream): Boolean {
        val word = ByteArray(4)
        if (!ByteReader.readFully(input, word)) return false
        val remaining = if (u32(word, 0) == DATA_DESCRIPTOR) 12L else 8L
        return ByteReader.skipFully(input, remaining)
    }

    private fun decodeName(bytes: ByteArray, offset: Int, length: Int, flags: Int): String {
        val charset = if ((flags and UTF8_NAME_FLAG) != 0) Charsets.UTF_8 else Charsets.ISO_8859_1
        return String(bytes, offset, length, charset).replace('\\', '/')
    }
}
