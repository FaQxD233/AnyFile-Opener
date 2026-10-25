package com.anyfile.x

import com.anyfile.x.engine.MimeDetector.DetectionConfidence
import com.anyfile.x.engine.MimeDetector.DetectionResult
import com.anyfile.x.engine.MimeDetector.DetectionSource
import com.anyfile.x.engine.MimeDetector.FileType
import com.anyfile.x.engine.NameCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NameCheckTest {

    private val noSystemMap: (String) -> String? = { null }

    @Test
    fun suggestsCorrectedNames() {
        val png = magic(FileType.IMAGE, "image/png")
        val renamed = NameCheck.check("photo.JPG", png, noSystemMap)!!
        assertEquals("jpg", renamed.currentExtension)
        assertEquals("PNG", renamed.formatLabel)
        assertEquals("photo.png", renamed.suggestedName)

        val pdf = magic(FileType.DOCUMENT, "application/pdf")
        assertEquals("report.pdf", NameCheck.check("report.pdf.1", pdf, noSystemMap)!!.suggestedName)
        val bare = NameCheck.check("download", pdf, noSystemMap)!!
        assertNull(bare.currentExtension)
        assertEquals("download.pdf", bare.suggestedName)
        assertEquals("Mr. Smith.pdf", NameCheck.check("Mr. Smith", pdf, noSystemMap)!!.suggestedName)
        assertEquals("archive.tar.pdf", NameCheck.correctedName("archive.tar.gz", "pdf", listOf("pdf")))
    }

    @Test
    fun acceptsCompatibleExtensions() {
        assertNull(NameCheck.check("IMG_0001.DNG", magic(FileType.IMAGE, "image/tiff"), noSystemMap))
        assertNull(NameCheck.check("song.m4a", magic(FileType.VIDEO, "video/mp4"), noSystemMap))
        assertNull(NameCheck.check("logo.ai", magic(FileType.DOCUMENT, "application/pdf"), noSystemMap))
        // Synonyms from the system map count as compatible too.
        assertNull(NameCheck.check("clip.mpeg4", magic(FileType.VIDEO, "video/mp4")) { "video/mp4" })
    }

    @Test
    fun ignoresWeakOrGenericResults() {
        val lowConfidence = DetectionResult(
            FileType.IMAGE,
            "image/png",
            DetectionConfidence.MEDIUM,
            DetectionSource.MAGIC_BYTES
        )
        assertNull(NameCheck.check("a.jpg", lowConfidence, noSystemMap))
        assertNull(NameCheck.check("a.jpg", magic(FileType.ARCHIVE, "application/zip"), noSystemMap))
        val byExtension = DetectionResult(
            FileType.DOCUMENT,
            "application/pdf",
            DetectionConfidence.HIGH,
            DetectionSource.FILE_EXTENSION
        )
        assertNull(NameCheck.check("a.txt", byExtension, noSystemMap))
    }

    @Test
    fun usesExtensionHintForGenericBundleTypes() {
        val apks = DetectionResult(
            FileType.APK,
            "application/octet-stream",
            DetectionConfidence.HIGH,
            DetectionSource.CONTAINER_CONTENT,
            extensionHint = "apks"
        )
        assertEquals("app.apks", NameCheck.check("app.zip", apks, noSystemMap)!!.suggestedName)
        assertNull(NameCheck.check("app.apks", apks, noSystemMap))
    }

    private fun magic(type: FileType, mime: String) = DetectionResult(
        type,
        mime,
        DetectionConfidence.HIGH,
        DetectionSource.MAGIC_BYTES
    )
}
