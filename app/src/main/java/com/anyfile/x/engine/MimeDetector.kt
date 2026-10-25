package com.anyfile.x.engine

import android.content.ContentResolver
import android.net.Uri
import com.anyfile.x.engine.utils.ByteReader
import com.anyfile.x.engine.utils.ByteSearch
import java.util.Locale

/** Magic-byte MIME detector with bounded container inspection. */
object MimeDetector {

    private const val ALL_MIME = MimeTypes.ANY
    private const val HEADER_BYTES = 33_000
    private const val TEXT_SAMPLE_BYTES = 2048
    private val PDF_MARKER = "%PDF-".toByteArray(Charsets.US_ASCII)
    private val TORRENT_INFO = "4:infod".toByteArray(Charsets.US_ASCII)
    private val ASF_HEADER = intArrayOf(
        0x30, 0x26, 0xB2, 0x75, 0x8E, 0x66, 0xCF, 0x11,
        0xA6, 0xD9, 0x00, 0xAA, 0x00, 0x62, 0xCE, 0x6C
    )
    private val YAML_LINE = Regex("""(?m)^\w+:\s+.+""")

    /** Types a source app reports when it knows nothing about the content. */
    private val GENERIC_PROVIDER_MIMES = setOf(
        MimeTypes.ANY,
        MimeTypes.OCTET_STREAM,
        "binary/octet-stream",
        "application/unknown",
        "application/force-download",
        "text/plain",
        MimeTypes.ZIP,
        "application/x-zip-compressed"
    )

    enum class FileType(val label: String, val emoji: String, val category: String) {
        VIDEO("Video", "🎬", "video"),
        AUDIO("Audio", "🎵", "audio"),
        IMAGE("Image", "🖼", "image"),
        DOCUMENT("Document", "📄", "document"),
        ARCHIVE("Archive", "📦", "archive"),
        APK("APK", "⚙", "apk"),
        TEXT("Text", "📃", "text"),
        FONT("Font", "🔤", "font"),
        UNKNOWN("Unknown", "?", "any")
    }

    enum class DetectionConfidence(val label: String) {
        HIGH("High"),
        MEDIUM("Medium"),
        LOW("Low")
    }

    enum class DetectionSource(val label: String) {
        MAGIC_BYTES("Magic bytes"),
        CONTAINER_CONTENT("Container content"),
        FILE_EXTENSION("File extension"),
        PROVIDER("Source app type"),
        TEXT_HEURISTIC("Text heuristic"),
        USER_OVERRIDE("Saved value"),
        FALLBACK("Fallback")
    }

    data class DetectionResult(
        val fileType: FileType,
        val mime: String,
        val confidence: DetectionConfidence = DetectionConfidence.LOW,
        val source: DetectionSource = DetectionSource.FALLBACK,
        val evidence: String = "No known signature was found",
        /** Extension implied by the content when the MIME type alone is too generic. */
        val extensionHint: String? = null
    )

    fun mimeOf(type: FileType, fallback: String = ALL_MIME): String = when (type.category) {
        "video" -> "video/*"
        "audio" -> "audio/*"
        "image" -> "image/*"
        "font" -> "font/*"
        "document" -> "application/*"
        "archive" -> MimeTypes.ZIP
        "apk" -> MimeTypes.APK
        "text" -> "text/plain"
        else -> MimeTypes.normalize(fallback)
    }

    fun detect(
        contentResolver: ContentResolver,
        uri: Uri,
        fileName: String?,
        fallbackMime: String = ALL_MIME
    ): DetectionResult {
        // ISO9660 uses a signature at byte 32769; all ordinary detection stays bounded.
        val header = ByteReader.readHeader(contentResolver, uri, HEADER_BYTES)
        val providerMime = try {
            contentResolver.getType(uri)
        } catch (_: Exception) {
            null
        }
        return ContentContainerAccess(contentResolver, uri).use { access ->
            detectFromHeader(header, fileName.orEmpty(), fallbackMime, providerMime, access)
        }
    }

    /** Detection from the file name alone, for content that could not be read in time. */
    fun detectFromName(fileName: String?, fallbackMime: String = ALL_MIME): DetectionResult =
        extensionFallback(fileName.orEmpty().lowercase(Locale.ROOT), ByteArray(0), fallbackMime, null)

    fun detectFromHeader(
        header: ByteArray,
        name: String = "",
        fallbackMime: String = ALL_MIME,
        providerMime: String? = null,
        access: ContainerAccess? = null
    ): DetectionResult {
        val lowerName = name.lowercase(Locale.ROOT)
        if (header.isEmpty()) {
            return extensionFallback(lowerName, header, fallbackMime, providerMime)
        }

        val extension = MimeTypes.extensionOf(lowerName)
        val pdfOffset = ByteSearch.indexOf(header, PDF_MARKER, maxOffset = 1024)
        return when {
            // A text byte-order mark comes first: FF FE also looks like an MPEG frame sync.
            TextEncoding.bomLength(header) > 0 -> bomText(header, lowerName)

            // Video containers
            matchBytes(header, 0x1A, 0x45, 0xDF, 0xA3) -> handleEbml(header, lowerName)
            ascii(header, 4, "ftyp") && header.size >= 12 -> handleFtyp(header)
            ascii(header, 0, "RIFF") && ascii(header, 8, "AVI ") ->
                magic(FileType.VIDEO, "video/x-msvideo", "RIFF/AVI signature")
            ascii(header, 0, "FLV") ->
                magic(FileType.VIDEO, "video/x-flv", "FLV signature")
            matchBytes(header, *ASF_HEADER) -> handleAsf(extension)
            matchBytes(header, 0x00, 0x00, 0x01, 0xBA) ->
                magic(FileType.VIDEO, "video/mpeg", "MPEG program stream pack header")
            matchBytes(header, 0x00, 0x00, 0x01, 0xB3) ->
                magic(FileType.VIDEO, "video/mpeg", "MPEG video sequence header")

            // Audio
            ascii(header, 0, "ID3") ->
                magic(FileType.AUDIO, "audio/mpeg", "ID3 tag")
            isAacAdts(header) ->
                magic(FileType.AUDIO, "audio/aac", "AAC ADTS sync word")
            isMpegAudioFrame(header) ->
                magic(FileType.AUDIO, "audio/mpeg", "MPEG audio frame header")
            ascii(header, 0, "fLaC") ->
                magic(FileType.AUDIO, "audio/flac", "fLaC signature")
            ascii(header, 0, "OggS") -> handleOgg(header)
            ascii(header, 0, "RIFF") && ascii(header, 8, "WAVE") ->
                magic(FileType.AUDIO, "audio/wav", "RIFF/WAVE signature")
            ascii(header, 0, "#!AMR-WB\n") ->
                magic(FileType.AUDIO, "audio/amr-wb", "AMR-WB header")
            ascii(header, 0, "#!AMR\n") ->
                magic(FileType.AUDIO, "audio/amr", "AMR header")
            ascii(header, 0, "MThd") ->
                magic(FileType.AUDIO, "audio/midi", "MIDI header chunk")
            ascii(header, 0, "FORM") &&
                (ascii(header, 8, "AIFF") || ascii(header, 8, "AIFC")) ->
                magic(FileType.AUDIO, "audio/x-aiff", "IFF/AIFF signature")
            ascii(header, 0, "MAC ") ->
                magic(FileType.AUDIO, "audio/x-ape", "Monkey's Audio signature")

            // E-books mark offset 60, so check before short image signatures such as BM.
            ascii(header, 60, "BOOKMOBI") ->
                magic(FileType.DOCUMENT, MimeTypes.MOBI, "BOOKMOBI marker at offset 60")

            // Images
            matchBytes(header, 0xFF, 0xD8, 0xFF) ->
                magic(FileType.IMAGE, "image/jpeg", "JPEG start-of-image marker")
            matchBytes(header, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) ->
                magic(FileType.IMAGE, "image/png", "PNG signature")
            ascii(header, 0, "GIF8") ->
                magic(FileType.IMAGE, "image/gif", "GIF87a/GIF89a prefix")
            ascii(header, 0, "RIFF") && ascii(header, 8, "WEBP") ->
                magic(FileType.IMAGE, "image/webp", "RIFF/WEBP signature")
            ascii(header, 0, "BM") ->
                magic(FileType.IMAGE, "image/bmp", "BM signature")
            matchBytes(header, 0x49, 0x49, 0x2A, 0x00) ||
                matchBytes(header, 0x4D, 0x4D, 0x00, 0x2A) ->
                magic(FileType.IMAGE, "image/tiff", "TIFF byte-order signature")

            // Transport streams start with 0x47 ('G'), so they come after GIF.
            isMpegTransportStream(header) ->
                magic(FileType.VIDEO, "video/mp2t", "MPEG transport stream sync bytes")

            // Documents
            pdfOffset >= 0 ->
                magic(FileType.DOCUMENT, "application/pdf", "%PDF- at offset $pdfOffset")
            ascii(header, 0, "{\\rtf") ->
                magic(FileType.DOCUMENT, "application/rtf", "{\\rtf signature")
            Ole2Inspector.matches(header) -> Ole2Inspector.inspect(header, extension, access)
            ascii(header, 0, "ITSF") ->
                magic(FileType.DOCUMENT, MimeTypes.CHM, "ITSF (CHM) signature")

            // Fonts
            matchBytes(header, 0x00, 0x01, 0x00, 0x00) ->
                magic(FileType.FONT, "font/ttf", "TrueType sfnt signature")
            ascii(header, 0, "OTTO") ->
                magic(FileType.FONT, "font/otf", "OpenType OTTO signature")
            ascii(header, 0, "wOFF") ->
                magic(FileType.FONT, "font/woff", "WOFF signature")
            ascii(header, 0, "wOF2") ->
                magic(FileType.FONT, "font/woff2", "WOFF2 signature")

            // Data files
            ascii(header, 0, "SQLite format 3\u0000") ->
                magic(FileType.UNKNOWN, "application/x-sqlite3", "SQLite 3 header")
            isTorrent(header) ->
                magic(
                    FileType.UNKNOWN,
                    "application/x-bittorrent",
                    "Bencoded dictionary with an info section"
                )

            // ZIP and ZIP-based formats, including empty/spanned archives.
            matchBytes(header, 0x50, 0x4B, 0x03, 0x04) ->
                ZipInspector.inspect(header, extension, access)
            matchBytes(header, 0x50, 0x4B, 0x05, 0x06) ||
                matchBytes(header, 0x50, 0x4B, 0x07, 0x08) ->
                magic(FileType.ARCHIVE, MimeTypes.ZIP, "ZIP end/spanned signature")

            // Other archives
            matchBytes(header, 0x52, 0x61, 0x72, 0x21, 0x1A, 0x07) ->
                magic(FileType.ARCHIVE, "application/vnd.rar", "RAR signature")
            matchBytes(header, 0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C) ->
                magic(FileType.ARCHIVE, "application/x-7z-compressed", "7-Zip signature")
            matchBytes(header, 0x1F, 0x8B) ->
                magic(FileType.ARCHIVE, "application/gzip", "GZIP signature")
            matchBytes(header, 0xFD, 0x37, 0x7A, 0x58, 0x5A, 0x00) ->
                magic(FileType.ARCHIVE, "application/x-xz", "XZ signature")
            ascii(header, 0, "BZh") ->
                magic(FileType.ARCHIVE, "application/x-bzip2", "BZip2 signature")
            ascii(header, 257, "ustar") ->
                magic(FileType.ARCHIVE, "application/x-tar", "ustar marker at offset 257")
            ascii(header, 32769, "CD001") ->
                magic(FileType.ARCHIVE, "application/x-iso9660-image", "ISO9660 CD001 marker")

            else -> textSignature(header)
                ?: extensionFallback(lowerName, header, fallbackMime, providerMime)
        }
    }

    private fun handleEbml(header: ByteArray, name: String): DetectionResult {
        val sample = String(header, 0, minOf(header.size, 4096), Charsets.ISO_8859_1)
            .lowercase(Locale.ROOT)
        return when {
            sample.contains("webm") ->
                magic(FileType.VIDEO, "video/webm", "EBML container with webm DocType")
            sample.contains("matroska") || name.endsWith(".mkv") ->
                magic(FileType.VIDEO, "video/x-matroska", "EBML/Matroska container")
            name.endsWith(".mka") -> result(
                FileType.AUDIO,
                "audio/x-matroska",
                DetectionConfidence.MEDIUM,
                DetectionSource.MAGIC_BYTES,
                "EBML signature plus .mka extension"
            )
            else -> result(
                FileType.VIDEO,
                "video/x-matroska",
                DetectionConfidence.MEDIUM,
                DetectionSource.MAGIC_BYTES,
                "EBML signature; DocType was not present in the header sample"
            )
        }
    }

    private fun handleOgg(header: ByteArray): DetectionResult = when {
        containsAscii(header, "OpusHead", 4096) ->
            magic(FileType.AUDIO, "audio/ogg", "Ogg container with OpusHead")
        containsAscii(header, "theora", 4096, ignoreCase = true) ->
            magic(FileType.VIDEO, "video/ogg", "Ogg container with Theora header")
        containsAscii(header, "vorbis", 4096, ignoreCase = true) ->
            magic(FileType.AUDIO, "audio/ogg", "Ogg container with Vorbis header")
        else -> result(
            FileType.AUDIO,
            "audio/ogg",
            DetectionConfidence.MEDIUM,
            DetectionSource.MAGIC_BYTES,
            "OggS container signature; codec was not identified"
        )
    }

    private fun handleFtyp(header: ByteArray): DetectionResult {
        val brand = String(header, 8, minOf(4, header.size - 8), Charsets.ISO_8859_1)
        val normalizedBrand = brand.lowercase(Locale.ROOT)
        val (type, mime) = when (normalizedBrand) {
            "heic", "heix", "hevc", "hevx", "mif1", "msf1" -> FileType.IMAGE to "image/heic"
            "avif", "avis" -> FileType.IMAGE to "image/avif"
            "m4a ", "m4b ", "m4p " -> FileType.AUDIO to "audio/mp4"
            "qt  " -> FileType.VIDEO to "video/quicktime"
            else -> if (normalizedBrand.startsWith("3g")) {
                FileType.VIDEO to "video/3gpp"
            } else {
                FileType.VIDEO to "video/mp4"
            }
        }
        return magic(type, mime, "ISO BMFF ftyp brand '${brand.trim()}'")
    }

    /** ASF holds both WMV and WMA; only the extension tells them apart cheaply. */
    private fun handleAsf(extension: String): DetectionResult =
        if (extension == "wma") {
            magic(FileType.AUDIO, "audio/x-ms-wma", "ASF header object plus .wma extension")
        } else {
            magic(FileType.VIDEO, "video/x-ms-wmv", "ASF header object")
        }

    /** Text with a byte-order mark. The extension may refine the text subtype only. */
    private fun bomText(header: ByteArray, name: String): DetectionResult {
        val encoding = TextEncoding.detect(header)
        val extension = MimeTypes.extensionOf(name)
        val extensionMime = MimeTypes.mimeForExtension(extension)
        if (extensionMime != null && MimeTypes.fileTypeFor(extensionMime) == FileType.TEXT) {
            return result(
                FileType.TEXT,
                extensionMime,
                DetectionConfidence.MEDIUM,
                DetectionSource.TEXT_HEURISTIC,
                "${encoding.label} byte-order mark plus .$extension extension"
            )
        }
        val sample = textSample(header, encoding)
        textSignature(sample)?.let { return it }
        val mime = structuredTextMime(sample) ?: "text/plain"
        return result(
            MimeTypes.fileTypeFor(mime),
            mime,
            DetectionConfidence.MEDIUM,
            DetectionSource.TEXT_HEURISTIC,
            "${encoding.label} byte-order mark"
        )
    }

    private fun extensionFallback(
        name: String,
        header: ByteArray,
        fallbackMime: String,
        providerMime: String?
    ): DetectionResult {
        val extension = MimeTypes.extensionOf(name)
        MimeTypes.known(extension)?.let { known ->
            return extensionResult(known.fileType, known.mime, "Known extension .$extension")
        }

        val systemMime = MimeTypes.systemMimeFor(extension)
        if (systemMime != null) {
            val type = MimeTypes.fileTypeFor(systemMime)
            if (type != FileType.UNKNOWN) {
                return extensionResult(type, systemMime, "Android MIME map for .$extension")
            }
        }

        providerResult(providerMime)?.let { return it }

        if (header.isNotEmpty()) {
            detectScript(header, name)?.let { return it }
            if (TextEncoding.looksLikeText(header)) {
                val encoding = TextEncoding.detect(header)
                val mime = structuredTextMime(textSample(header, encoding)) ?: "text/plain"
                return result(
                    MimeTypes.fileTypeFor(mime),
                    mime,
                    DetectionConfidence.MEDIUM,
                    DetectionSource.TEXT_HEURISTIC,
                    "Text structure; encoding ${encoding.label}"
                )
            }
        }

        // A specific type outside the known categories still beats the generic fallback.
        if (systemMime != null) {
            return extensionResult(FileType.UNKNOWN, systemMime, "Android MIME map for .$extension")
        }

        return result(
            FileType.UNKNOWN,
            MimeTypes.normalize(fallbackMime),
            DetectionConfidence.LOW,
            DetectionSource.FALLBACK,
            if (extension.isBlank()) {
                "No known signature or extension"
            } else {
                "No known signature or MIME mapping for .$extension"
            }
        )
    }

    private fun providerResult(providerMime: String?): DetectionResult? {
        val mime = MimeTypes.normalize(providerMime ?: return null)
        if (mime in GENERIC_PROVIDER_MIMES || mime.endsWith("/*") || !MimeTypes.isValid(mime)) {
            return null
        }
        return result(
            MimeTypes.fileTypeFor(mime),
            mime,
            DetectionConfidence.LOW,
            DetectionSource.PROVIDER,
            "Type reported by the source app"
        )
    }

    private fun textSample(header: ByteArray, encoding: TextEncoding.Detection): String =
        TextEncoding.decode(
            header,
            encoding.charset,
            encoding.bomLength,
            minOf(header.size - encoding.bomLength, TEXT_SAMPLE_BYTES),
            endOfInput = false
        )

    private fun textSignature(header: ByteArray): DetectionResult? =
        textSignature(String(header, 0, minOf(header.size, TEXT_SAMPLE_BYTES), Charsets.ISO_8859_1))

    /** Text formats whose first line identifies them regardless of the file name. */
    private fun textSignature(text: String): DetectionResult? {
        val content = text.trimStart()
        return when {
            content.startsWith("BEGIN:VCARD", ignoreCase = true) ->
                textMatch(FileType.TEXT, "text/x-vcard", "BEGIN:VCARD header")
            content.startsWith("BEGIN:VCALENDAR", ignoreCase = true) ->
                textMatch(FileType.TEXT, "text/calendar", "BEGIN:VCALENDAR header")
            content.startsWith("#EXTM3U") && content.contains("#EXT-X-") ->
                textMatch(FileType.VIDEO, MimeTypes.HLS, "#EXTM3U playlist with HLS tags")
            content.startsWith("#EXTM3U") ->
                textMatch(FileType.AUDIO, "audio/x-mpegurl", "#EXTM3U playlist header")
            else -> null
        }
    }

    private fun detectScript(header: ByteArray, name: String): DetectionResult? {
        if (header.size < 2) return null
        val content = String(header, 0, minOf(header.size, 1024), Charsets.UTF_8)

        if (header[0] == '#'.code.toByte() && header[1] == '!'.code.toByte()) {
            val (mime, language) = when {
                content.contains("python") -> "text/x-python" to "Python"
                content.contains("/bin/sh") || content.contains("/bin/bash") ->
                    "text/x-shellscript" to "shell"
                content.contains("node") -> "application/javascript" to "Node.js"
                content.contains("kotlin") -> "text/x-kotlin" to "Kotlin"
                else -> return null
            }
            return result(
                FileType.TEXT,
                mime,
                DetectionConfidence.HIGH,
                DetectionSource.TEXT_HEURISTIC,
                "$language shebang"
            )
        }

        return when {
            name.endsWith(".kt") && (content.startsWith("fun ") || content.startsWith("import ")) ->
                textHeuristic("text/x-kotlin", "Kotlin syntax plus .kt extension")
            name.endsWith(".py") && (content.startsWith("def ") || content.startsWith("import ") ||
                content.startsWith("from ")) ->
                textHeuristic("text/x-python", "Python syntax plus .py extension")
            else -> null
        }
    }

    private fun structuredTextMime(sample: String): String? {
        val content = sample.trim()
        if (content.isEmpty()) return null
        val lower = content.lowercase(Locale.ROOT)
        return when {
            content.startsWith("{") || content.startsWith("[") -> "application/json"
            isSvg(lower) -> "image/svg+xml"
            lower.startsWith("<?xml") -> "text/xml"
            content.startsWith("<") -> {
                if (lower.startsWith("<!doctype html") || lower.contains("<html")) {
                    "text/html"
                } else {
                    "text/xml"
                }
            }
            content.startsWith("---") || YAML_LINE.containsMatchIn(content) -> "text/yaml"
            content.startsWith("# ") || content.contains("**") || content.contains("- [ ]") ->
                "text/markdown"
            isCsv(content) -> "text/csv"
            else -> null
        }
    }

    /** SVG when the first element is svg, possibly after an XML prolog or comments. */
    private fun isSvg(lower: String): Boolean {
        if (lower.startsWith("<svg")) return true
        if (!lower.startsWith("<?xml") && !lower.startsWith("<!--") &&
            !lower.startsWith("<!doctype svg")
        ) return false
        val svg = lower.indexOf("<svg")
        val html = lower.indexOf("<html")
        return svg >= 0 && (html < 0 || svg < html)
    }

    private fun isCsv(content: String): Boolean {
        val lines = content.lines().filter { it.isNotBlank() }.take(5)
        if (lines.size < 2) return false
        val columnCounts = lines.map { it.split(',').size }
        return columnCounts.all { it > 1 && it == columnCounts.first() }
    }

    private fun isAacAdts(header: ByteArray): Boolean {
        if (header.size < 2) return false
        val first = header[0].toInt() and 0xFF
        val second = header[1].toInt() and 0xFF
        return first == 0xFF && (second and 0xF6) == 0xF0
    }

    /** Frame sync plus valid version, layer, bitrate and sample-rate fields. */
    private fun isMpegAudioFrame(header: ByteArray): Boolean {
        if (header.size < 3) return false
        val first = header[0].toInt() and 0xFF
        val second = header[1].toInt() and 0xFF
        val third = header[2].toInt() and 0xFF
        if (first != 0xFF || (second and 0xE0) != 0xE0 || isAacAdts(header)) return false
        val version = (second shr 3) and 0x03
        val layer = (second shr 1) and 0x03
        val bitrate = (third shr 4) and 0x0F
        val sampleRate = (third shr 2) and 0x03
        return version != 0x01 && layer != 0x00 && bitrate != 0x0F && sampleRate != 0x03
    }

    /** 188-byte packets, or 192-byte M2TS packets with a 4-byte timestamp prefix. */
    private fun isMpegTransportStream(header: ByteArray): Boolean =
        hasSyncBytes(header, 0, 188) || hasSyncBytes(header, 4, 192)

    private fun hasSyncBytes(header: ByteArray, start: Int, packetSize: Int): Boolean =
        header.size > start + packetSize * 3 &&
            (0..3).all { header[start + it * packetSize] == 0x47.toByte() }

    private fun isTorrent(header: ByteArray): Boolean {
        if (header.size < 12 || header[0] != 'd'.code.toByte()) return false
        val keyLength = header[1].toInt()
        if (keyLength !in '1'.code..'9'.code) return false
        return ByteSearch.indexOf(header, TORRENT_INFO) >= 0
    }

    private fun containsAscii(
        bytes: ByteArray,
        text: String,
        limit: Int,
        ignoreCase: Boolean = false
    ): Boolean {
        val sample = String(bytes, 0, minOf(bytes.size, limit), Charsets.ISO_8859_1)
        return sample.contains(text, ignoreCase)
    }

    private fun ascii(header: ByteArray, offset: Int, text: String): Boolean =
        ByteSearch.matchesAscii(header, offset, text)

    private fun matchBytes(header: ByteArray, vararg bytes: Int): Boolean =
        header.size >= bytes.size && bytes.indices.all {
            header[it].toInt() and 0xFF == bytes[it]
        }

    private fun magic(type: FileType, mime: String, evidence: String): DetectionResult =
        result(
            type,
            mime,
            DetectionConfidence.HIGH,
            DetectionSource.MAGIC_BYTES,
            evidence
        )

    private fun extensionResult(
        type: FileType,
        mime: String,
        evidence: String,
        confidence: DetectionConfidence = DetectionConfidence.LOW
    ): DetectionResult = result(
        type,
        mime,
        confidence,
        DetectionSource.FILE_EXTENSION,
        evidence
    )

    private fun textHeuristic(mime: String, evidence: String): DetectionResult = result(
        FileType.TEXT,
        mime,
        DetectionConfidence.MEDIUM,
        DetectionSource.TEXT_HEURISTIC,
        evidence
    )

    private fun textMatch(type: FileType, mime: String, evidence: String): DetectionResult =
        result(type, mime, DetectionConfidence.HIGH, DetectionSource.TEXT_HEURISTIC, evidence)

    private fun result(
        type: FileType,
        mime: String,
        confidence: DetectionConfidence,
        source: DetectionSource,
        evidence: String
    ): DetectionResult = DetectionResult(
        type,
        MimeTypes.normalize(mime),
        confidence,
        source,
        evidence
    )
}
