package com.anyfile.x

import com.anyfile.x.engine.MimeDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset

class MimeDetectorTest {

    @Test
    fun testVideoFormats() {
        // MKV
        assertMime(byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte()), "video/x-matroska")
        // MP4 (ftyp)
        assertMime(createFtypHeader("mp42"), "video/mp4")
        // AVI (RIFF ... AVI )
        val avi = ByteArray(12)
        "RIFF".toByteArray().copyInto(avi)
        "AVI ".toByteArray().copyInto(avi, 8)
        assertMime(avi, "video/x-msvideo")
        // FLV
        assertMime("FLV".toByteArray(), "video/x-flv")
    }

    @Test
    fun testAudioFormats() {
        // MP3 (ID3)
        assertMime("ID3".toByteArray(), "audio/mpeg")
        // FLAC
        assertMime("fLaC".toByteArray(), "audio/flac")
        // OGG
        assertMime("OggS".toByteArray(), "audio/ogg")
        // WAV
        val wav = ByteArray(12)
        "RIFF".toByteArray().copyInto(wav)
        "WAVE".toByteArray().copyInto(wav, 8)
        assertMime(wav, "audio/wav")
    }

    @Test
    fun testImageFormats() {
        // JPEG
        assertMime(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()), "image/jpeg")
        // PNG
        assertMime(
            byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A),
            "image/png"
        )
        // GIF
        assertMime("GIF8".toByteArray(), "image/gif")
        // WEBP
        val webp = ByteArray(12)
        "RIFF".toByteArray().copyInto(webp)
        "WEBP".toByteArray().copyInto(webp, 8)
        assertMime(webp, "image/webp")
        // BMP
        assertMime("BM".toByteArray(), "image/bmp")
        // TIFF LE
        assertMime(byteArrayOf(0x49, 0x49, 0x2A, 0x00), "image/tiff")
        // TIFF BE
        assertMime(byteArrayOf(0x4D, 0x4D, 0x00, 0x2A), "image/tiff")
    }

    @Test
    fun testDocumentAndArchiveFormats() {
        // PDF
        assertMime("%PDF-1.7".toByteArray(), "application/pdf")
        // RAR
        assertMime(
            byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07),
            "application/vnd.rar"
        )
        // 7Z
        assertMime(
            byteArrayOf(0x37, 0x7A, 0xBC.toByte(), 0xAF.toByte(), 0x27, 0x1C),
            "application/x-7z-compressed"
        )
        // GZIP
        assertMime(byteArrayOf(0x1F.toByte(), 0x8B.toByte()), "application/gzip")
        // XZ
        assertMime(byteArrayOf(0xFD.toByte(), 0x37, 0x7A, 0x58, 0x5A, 0x00), "application/x-xz")
    }

    @Test
    fun testZipBasedFormats() {
        val zipMagic = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
        
        // Generic ZIP
        assertMime(zipMagic, "application/zip")
        
        // APK (by extension)
        assertEquals("application/vnd.android.package-archive", 
            MimeDetector.detectFromHeader(zipMagic, "test.apk").mime)
            
        // EPUB (mimetype entry)
        val epub = ByteArray(40)
        zipMagic.copyInto(epub)
        "mimetype".toByteArray().copyInto(epub, 30)
        assertEquals(
            "application/epub+zip",
            MimeDetector.detectFromHeader(epub, "book.epub").mime
        )
        
        // DOCX ([Content_Types].xml)
        val docx = ByteArray(50)
        zipMagic.copyInto(docx)
        "[Content_Types].xml".toByteArray().copyInto(docx, 30)
        assertEquals("application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            MimeDetector.detectFromHeader(docx, "doc.docx").mime)
    }

    @Test
    fun testDetectionExplanationAndConfidence() {
        val magic = MimeDetector.detectFromHeader("%PDF-1.7".toByteArray(), "wrong.bin")
        assertEquals(MimeDetector.DetectionConfidence.HIGH, magic.confidence)
        assertEquals(MimeDetector.DetectionSource.MAGIC_BYTES, magic.source)
        assertTrue(magic.evidence.contains("%PDF-"))

        val extension = MimeDetector.detectFromHeader(ByteArray(0), "notes.md")
        assertEquals(MimeDetector.DetectionConfidence.LOW, extension.confidence)
        assertEquals(MimeDetector.DetectionSource.FILE_EXTENSION, extension.source)
    }

    @Test
    fun textWithByteOrderMarkIsNotAudio() {
        val utf16 = MimeDetector.detectFromHeader("﻿Hello, world".toByteArray(Charsets.UTF_16LE))
        assertEquals("text/plain", utf16.mime)
        assertEquals(MimeDetector.FileType.TEXT, utf16.fileType)

        val utf32 = "﻿Hi there".toByteArray(Charset.forName("UTF-32LE"))
        assertEquals("text/plain", MimeDetector.detectFromHeader(utf32).mime)
    }

    @Test
    fun validatesMpegAudioFrameFields() {
        assertMime(bytes(0xFF, 0xFB, 0x90, 0x64), "audio/mpeg")
        // Reserved sample-rate index: frame sync alone is not enough.
        val invalid = MimeDetector.detectFromHeader(bytes(0xFF, 0xFB, 0x9C, 0x00, 0x01))
        assertEquals(MimeDetector.FileType.UNKNOWN, invalid.fileType)
    }

    @Test
    fun rtfIsNotJson() {
        assertMime("{\\rtf1\\ansi\\deff0 Hello}".toByteArray(), "application/rtf")
    }

    @Test
    fun detectsAdditionalBinarySignatures() {
        assertMime("MThd\u0000\u0000\u0000\u0006".toByteArray(Charsets.ISO_8859_1), "audio/midi")
        assertMime("#!AMR\n".toByteArray(), "audio/amr")
        assertMime("#!AMR-WB\n".toByteArray(), "audio/amr-wb")
        assertMime(asciiAt(12, 0 to "FORM", 8 to "AIFF"), "audio/x-aiff")
        assertMime("MAC \u0096\u000F".toByteArray(Charsets.ISO_8859_1), "audio/x-ape")
        assertMime("SQLite format 3\u0000".toByteArray(), "application/x-sqlite3")
        assertMime("ITSF\u0003\u0000".toByteArray(Charsets.ISO_8859_1), "application/x-chm")
        assertMime(asciiAt(80, 60 to "BOOKMOBI"), "application/x-mobipocket-ebook")
        assertMime(bytes(0x00, 0x00, 0x01, 0xBA, 0x44), "video/mpeg")
        assertMime(transportStream(), "video/mp2t")
        assertMime(
            "d8:announce30:udp://tracker.example:80/annc4:infod6:lengthi1ee".toByteArray(),
            "application/x-bittorrent"
        )
    }

    @Test
    fun asfUsesExtensionToSeparateAudio() {
        val asf = bytes(
            0x30, 0x26, 0xB2, 0x75, 0x8E, 0x66, 0xCF, 0x11,
            0xA6, 0xD9, 0x00, 0xAA, 0x00, 0x62, 0xCE, 0x6C
        )
        assertEquals("video/x-ms-wmv", MimeDetector.detectFromHeader(asf).mime)
        assertEquals("audio/x-ms-wma", MimeDetector.detectFromHeader(asf, "song.wma").mime)
    }

    @Test
    fun detectsTextSignatures() {
        assertMime("BEGIN:VCARD\r\nVERSION:3.0\r\nEND:VCARD".toByteArray(), "text/x-vcard")
        assertMime("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nEND:VCALENDAR".toByteArray(), "text/calendar")
        assertMime("#EXTM3U\n#EXTINF:123,Song\nsong.mp3".toByteArray(), "audio/x-mpegurl")
        assertMime(
            "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:10".toByteArray(),
            "application/vnd.apple.mpegurl"
        )
    }

    @Test
    fun detectsSvgAndNonAsciiText() {
        val svg = "<?xml version=\"1.0\"?>\n<svg xmlns=\"http://www.w3.org/2000/svg\"/>"
        val svgResult = MimeDetector.detectFromHeader(svg.toByteArray())
        assertEquals("image/svg+xml", svgResult.mime)
        assertEquals(MimeDetector.FileType.IMAGE, svgResult.fileType)

        val chinese = "这是一个中文文本文件。"
        assertEquals("text/plain", MimeDetector.detectFromHeader(chinese.toByteArray()).mime)
        assertEquals(
            "text/plain",
            MimeDetector.detectFromHeader(chinese.toByteArray(Charset.forName("GBK"))).mime
        )
    }

    @Test
    fun providerTypeIsUsedOnlyWhenSpecific() {
        val binary = bytes(0x01, 0x02, 0x03, 0x04, 0x05, 0x06)
        val provider = MimeDetector.detectFromHeader(binary, providerMime = "application/x-foo")
        assertEquals("application/x-foo", provider.mime)
        assertEquals(MimeDetector.DetectionSource.PROVIDER, provider.source)

        val generic = MimeDetector.detectFromHeader(binary, providerMime = "application/octet-stream")
        assertEquals(MimeDetector.DetectionSource.FALLBACK, generic.source)
    }

    @Test
    fun identifiesOle2DocumentsByDirectoryEntry() {
        val header = ByteArray(1024)
        ole2Signature().copyInto(header)
        header[0x1E] = 9
        val name = "WordDocument".toByteArray(Charsets.UTF_16LE)
        name.copyInto(header, 640)
        header[640 + 64] = (name.size + 2).toByte()
        val word = MimeDetector.detectFromHeader(header)
        assertEquals("application/msword", word.mime)
        assertEquals(MimeDetector.DetectionConfidence.HIGH, word.confidence)

        // The same name away from a 128-byte entry boundary is document text, not a stream.
        val shifted = ByteArray(1024)
        ole2Signature().copyInto(shifted)
        name.copyInto(shifted, 650)
        assertEquals("application/x-ole-storage", MimeDetector.detectFromHeader(shifted).mime)

        val bare = ByteArray(512)
        ole2Signature().copyInto(bare)
        assertEquals("application/vnd.ms-excel", MimeDetector.detectFromHeader(bare, "sheet.xls").mime)
    }

    private fun assertMime(header: ByteArray, expectedMime: String) {
        val result = MimeDetector.detectFromHeader(header)
        assertEquals(expectedMime, result.mime)
    }

    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

    private fun ole2Signature(): ByteArray = bytes(0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1)

    private fun asciiAt(size: Int, vararg parts: Pair<Int, String>): ByteArray {
        val header = ByteArray(size)
        for ((offset, text) in parts) text.toByteArray(Charsets.US_ASCII).copyInto(header, offset)
        return header
    }

    private fun transportStream(): ByteArray {
        val stream = ByteArray(600)
        for (packet in 0..3) stream[packet * 188] = 0x47
        return stream
    }

    private fun createFtypHeader(brand: String): ByteArray {
        val header = ByteArray(12)
        "ftyp".toByteArray().copyInto(header, 4)
        brand.toByteArray().copyInto(header, 8)
        return header
    }
}
