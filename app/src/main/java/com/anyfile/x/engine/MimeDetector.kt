package com.anyfile.x.engine

import android.content.ContentResolver
import android.net.Uri
import android.webkit.MimeTypeMap
import com.anyfile.x.engine.utils.ByteReader
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.zip.ZipInputStream

/** Magic-byte MIME detector with bounded container inspection. */
object MimeDetector {

    private const val ALL_MIME = "*/*"
    private const val ZIP_ENTRY_LIMIT = 12
    private const val ZIP_ENTRY_BYTE_LIMIT = 64 * 1024
    private const val ZIP_TOTAL_BYTE_LIMIT = 256 * 1024

    enum class FileType(val label: String, val emoji: String, val category: String) {
        VIDEO("Video", "\uD83C\uDFAC", "video"),
        AUDIO("Audio", "\uD83C\uDFB5", "audio"),
        IMAGE("Image", "\uD83D\uDDBC", "image"),
        DOCUMENT("Document", "\uD83D\uDCC4", "document"),
        ARCHIVE("Archive", "\uD83D\uDCE6", "archive"),
        APK("APK", "\u2699", "apk"),
        TEXT("Text", "\uD83D\uDCC3", "text"),
        FONT("Font", "\uD83D\uDD24", "font"),
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
        TEXT_HEURISTIC("Text heuristic"),
        USER_OVERRIDE("Saved value"),
        FALLBACK("Fallback")
    }

    data class DetectionResult(
        val fileType: FileType,
        val mime: String,
        val confidence: DetectionConfidence = DetectionConfidence.LOW,
        val source: DetectionSource = DetectionSource.FALLBACK,
        val evidence: String = "No known signature was found"
    )

    fun mimeOf(type: FileType, fallback: String = ALL_MIME): String = when (type.category) {
        "video" -> "video/*"
        "audio" -> "audio/*"
        "image" -> "image/*"
        "font" -> "font/*"
        "document" -> "application/*"
        "archive" -> "application/zip"
        "apk" -> "application/vnd.android.package-archive"
        "text" -> "text/plain"
        else -> fallback.substringBefore(';').trim().ifBlank { ALL_MIME }
    }

    fun detect(
        contentResolver: ContentResolver,
        uri: Uri,
        fileName: String?,
        fallbackMime: String = ALL_MIME
    ): DetectionResult {
        // ISO9660 uses a signature at byte 32769; all ordinary detection stays bounded.
        val header = ByteReader.readHeader(contentResolver, uri, 33_000)
        return detectFromHeader(
            header,
            fileName?.lowercase(Locale.ROOT).orEmpty(),
            fallbackMime,
            contentResolver,
            uri
        )
    }

    fun detectFromHeader(
        header: ByteArray,
        name: String = "",
        fallbackMime: String = ALL_MIME,
        contentResolver: ContentResolver? = null,
        uri: Uri? = null
    ): DetectionResult {
        if (header.isEmpty()) {
            return extensionFallback(name, header, fallbackMime)
        }

        val pdfOffset = indexOfBytes(header, "%PDF-".toByteArray(), 1024)
        return when {
            // Video containers
            matchBytes(header, 0x1A, 0x45, 0xDF, 0xA3) -> handleEbml(header, name)
            header.size >= 12 && matchBytesAt(header, 4, 0x66, 0x74, 0x79, 0x70) ->
                handleFtyp(header)
            matchBytes(header, 0x52, 0x49, 0x46, 0x46) &&
                matchBytesAt(header, 8, 0x41, 0x56, 0x49, 0x20) ->
                magic(FileType.VIDEO, "video/x-msvideo", "RIFF/AVI signature")
            matchBytes(header, 0x46, 0x4C, 0x56) ->
                magic(FileType.VIDEO, "video/x-flv", "FLV signature")

            // Audio
            matchBytes(header, 0x49, 0x44, 0x33) ->
                magic(FileType.AUDIO, "audio/mpeg", "ID3 tag")
            isAacAdts(header) ->
                magic(FileType.AUDIO, "audio/aac", "AAC ADTS sync word")
            isMpegAudioFrame(header) ->
                magic(FileType.AUDIO, "audio/mpeg", "MPEG audio frame sync")
            matchBytes(header, 0x66, 0x4C, 0x61, 0x43) ->
                magic(FileType.AUDIO, "audio/flac", "fLaC signature")
            matchBytes(header, 0x4F, 0x67, 0x67, 0x53) -> handleOgg(header)
            matchBytes(header, 0x52, 0x49, 0x46, 0x46) &&
                matchBytesAt(header, 8, 0x57, 0x41, 0x56, 0x45) ->
                magic(FileType.AUDIO, "audio/wav", "RIFF/WAVE signature")

            // Images
            matchBytes(header, 0xFF, 0xD8, 0xFF) ->
                magic(FileType.IMAGE, "image/jpeg", "JPEG start-of-image marker")
            matchBytes(header, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) ->
                magic(FileType.IMAGE, "image/png", "PNG signature")
            matchBytes(header, 0x47, 0x49, 0x46, 0x38) ->
                magic(FileType.IMAGE, "image/gif", "GIF87a/GIF89a prefix")
            matchBytes(header, 0x52, 0x49, 0x46, 0x46) &&
                matchBytesAt(header, 8, 0x57, 0x45, 0x42, 0x50) ->
                magic(FileType.IMAGE, "image/webp", "RIFF/WEBP signature")
            matchBytes(header, 0x42, 0x4D) ->
                magic(FileType.IMAGE, "image/bmp", "BM signature")
            matchBytes(header, 0x49, 0x49, 0x2A, 0x00) ||
                matchBytes(header, 0x4D, 0x4D, 0x00, 0x2A) ->
                magic(FileType.IMAGE, "image/tiff", "TIFF byte-order signature")

            // Documents
            pdfOffset >= 0 ->
                magic(FileType.DOCUMENT, "application/pdf", "%PDF- at offset $pdfOffset")

            // Fonts
            matchBytes(header, 0x00, 0x01, 0x00, 0x00) ->
                magic(FileType.FONT, "font/ttf", "TrueType sfnt signature")
            matchBytes(header, 0x4F, 0x54, 0x54, 0x4F) ->
                magic(FileType.FONT, "font/otf", "OpenType OTTO signature")
            matchBytes(header, 0x77, 0x4F, 0x46, 0x46) ->
                magic(FileType.FONT, "font/woff", "WOFF signature")
            matchBytes(header, 0x77, 0x4F, 0x46, 0x32) ->
                magic(FileType.FONT, "font/woff2", "WOFF2 signature")

            // ZIP and ZIP-based formats, including empty/spanned archives.
            matchBytes(header, 0x50, 0x4B, 0x03, 0x04) ->
                handleZipBased(header, name, contentResolver, uri)
            matchBytes(header, 0x50, 0x4B, 0x05, 0x06) ||
                matchBytes(header, 0x50, 0x4B, 0x07, 0x08) ->
                magic(FileType.ARCHIVE, "application/zip", "ZIP end/spanned signature")

            // Other archives
            matchBytes(header, 0x52, 0x61, 0x72, 0x21, 0x1A, 0x07) ->
                magic(FileType.ARCHIVE, "application/vnd.rar", "RAR signature")
            matchBytes(header, 0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C) ->
                magic(FileType.ARCHIVE, "application/x-7z-compressed", "7-Zip signature")
            matchBytes(header, 0x1F, 0x8B) ->
                magic(FileType.ARCHIVE, "application/gzip", "GZIP signature")
            matchBytes(header, 0xFD, 0x37, 0x7A, 0x58, 0x5A, 0x00) ->
                magic(FileType.ARCHIVE, "application/x-xz", "XZ signature")
            matchBytes(header, 0x42, 0x5A, 0x68) ->
                magic(FileType.ARCHIVE, "application/x-bzip2", "BZip2 signature")
            header.size >= 262 && matchBytesAt(header, 257, 0x75, 0x73, 0x74, 0x61, 0x72) ->
                magic(FileType.ARCHIVE, "application/x-tar", "ustar marker at offset 257")
            header.size >= 32774 &&
                matchBytesAt(header, 32769, 0x43, 0x44, 0x30, 0x30, 0x31) ->
                magic(FileType.ARCHIVE, "application/x-iso9660-image", "ISO9660 CD001 marker")

            else -> extensionFallback(name, header, fallbackMime)
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

    private fun handleZipBased(
        header: ByteArray,
        name: String,
        contentResolver: ContentResolver?,
        uri: Uri?
    ): DetectionResult {
        if (contentResolver != null && uri != null) {
            verifyZipContent(contentResolver, uri)?.let { return it }
        }

        val headerText = String(header, Charsets.ISO_8859_1)
        val hasContentTypes = headerText.contains("[Content_Types].xml")
        return when {
            headerText.contains("AndroidManifest.xml") &&
                (headerText.contains("classes.dex") || headerText.contains("resources.arsc")) ->
                result(
                    FileType.APK,
                    "application/vnd.android.package-archive",
                    DetectionConfidence.MEDIUM,
                    DetectionSource.CONTAINER_CONTENT,
                    "APK marker names found in the bounded ZIP header"
                )
            headerText.contains("mimetype") && name.endsWith(".epub") ->
                result(
                    FileType.DOCUMENT,
                    "application/epub+zip",
                    DetectionConfidence.MEDIUM,
                    DetectionSource.CONTAINER_CONTENT,
                    "EPUB mimetype entry name plus .epub extension"
                )
            hasContentTypes -> officeByName(name, DetectionSource.CONTAINER_CONTENT)
            name.endsWith(".apk") -> extensionResult(
                FileType.APK,
                "application/vnd.android.package-archive",
                "Extension .apk on a ZIP container",
                DetectionConfidence.MEDIUM
            )
            name.endsWith(".epub") -> extensionResult(
                FileType.DOCUMENT,
                "application/epub+zip",
                "Extension .epub on a ZIP container",
                DetectionConfidence.MEDIUM
            )
            name.endsWith(".docx") || name.endsWith(".xlsx") || name.endsWith(".pptx") ->
                officeByName(name, DetectionSource.FILE_EXTENSION)
            else -> magic(FileType.ARCHIVE, "application/zip", "ZIP local-file signature")
        }
    }

    /**
     * Reads only a small number of small entries. If advancing would require
     * decompressing a large entry, inspection stops and detection falls back to the
     * already-bounded header/extension path.
     */
    private fun verifyZipContent(
        contentResolver: ContentResolver,
        uri: Uri
    ): DetectionResult? = try {
        contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input.buffered()).use { zip ->
                var entryCount = 0
                var totalRead = 0
                var foundManifest = false

                while (entryCount < ZIP_ENTRY_LIMIT && totalRead <= ZIP_TOTAL_BYTE_LIMIT) {
                    val entry = zip.nextEntry ?: break
                    val entryName = entry.name.replace('\\', '/')

                    if (entryName == "AndroidManifest.xml") {
                        foundManifest = true
                    }
                    if (foundManifest &&
                        (entryName == "classes.dex" || entryName == "resources.arsc")
                    ) {
                        return result(
                            FileType.APK,
                            "application/vnd.android.package-archive",
                            DetectionConfidence.HIGH,
                            DetectionSource.CONTAINER_CONTENT,
                            "ZIP contains AndroidManifest.xml and $entryName"
                        )
                    }

                    val bytes = readZipEntryBounded(zip, ZIP_ENTRY_BYTE_LIMIT) ?: return null
                    totalRead += bytes.size
                    when (entryName) {
                        "mimetype" -> {
                            val value = bytes.toString(Charsets.US_ASCII).trim()
                            if (value == "application/epub+zip") {
                                return result(
                                    FileType.DOCUMENT,
                                    "application/epub+zip",
                                    DetectionConfidence.HIGH,
                                    DetectionSource.CONTAINER_CONTENT,
                                    "ZIP mimetype entry contains application/epub+zip"
                                )
                            }
                        }
                        "[Content_Types].xml" -> {
                            val xml = bytes.toString(Charsets.UTF_8).lowercase(Locale.ROOT)
                            val office = when {
                                xml.contains("wordprocessingml") ->
                                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                                xml.contains("spreadsheetml") ->
                                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                                xml.contains("presentationml") ->
                                    "application/vnd.openxmlformats-officedocument.presentationml.presentation"
                                else -> null
                            }
                            if (office != null) {
                                return result(
                                    FileType.DOCUMENT,
                                    office,
                                    DetectionConfidence.HIGH,
                                    DetectionSource.CONTAINER_CONTENT,
                                    "Office family identified from [Content_Types].xml"
                                )
                            }
                        }
                    }
                    entryCount++
                }
                null
            }
        }
    } catch (_: Exception) {
        null
    }

    private fun readZipEntryBounded(zip: ZipInputStream, limit: Int): ByteArray? {
        val output = ByteArrayOutputStream(minOf(limit, 8192))
        val buffer = ByteArray(4096)
        var total = 0
        while (true) {
            val read = zip.read(buffer)
            if (read < 0) return output.toByteArray()
            if (read == 0) continue
            total += read
            if (total > limit) return null
            output.write(buffer, 0, read)
        }
    }

    private fun officeByName(name: String, source: DetectionSource): DetectionResult {
        val (mime, detail) = when {
            name.endsWith(".docx") ->
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document" to "DOCX"
            name.endsWith(".xlsx") ->
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" to "XLSX"
            name.endsWith(".pptx") ->
                "application/vnd.openxmlformats-officedocument.presentationml.presentation" to "PPTX"
            else -> "application/zip" to "generic OpenXML ZIP"
        }
        val confidence = if (source == DetectionSource.CONTAINER_CONTENT && mime != "application/zip") {
            DetectionConfidence.HIGH
        } else {
            DetectionConfidence.MEDIUM
        }
        return result(
            if (mime == "application/zip") FileType.ARCHIVE else FileType.DOCUMENT,
            mime,
            confidence,
            source,
            "$detail inferred from ZIP markers and file name"
        )
    }

    private fun extensionFallback(
        name: String,
        header: ByteArray = ByteArray(0),
        fallbackMime: String = ALL_MIME
    ): DetectionResult {
        val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        when (extension) {
            "apk" -> return extensionResult(
                FileType.APK,
                "application/vnd.android.package-archive",
                "Extension .apk"
            )
            "md" -> return extensionResult(FileType.TEXT, "text/markdown", "Extension .md")
            "json" -> return extensionResult(FileType.TEXT, "application/json", "Extension .json")
        }

        val systemMime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
        if (systemMime != null) {
            val type = fileTypeForMime(systemMime)
            if (type != FileType.UNKNOWN) {
                return extensionResult(type, systemMime, "Android MIME map for .$extension")
            }
        }

        if (header.isNotEmpty()) {
            detectScript(header, name)?.let { return it }
            if (isLikelyText(header)) {
                val structuredMime = detectStructuredText(header) ?: "text/plain"
                val charset = getCharsetName(header)
                return result(
                    FileType.TEXT,
                    structuredMime,
                    DetectionConfidence.MEDIUM,
                    DetectionSource.TEXT_HEURISTIC,
                    "Printable-text ratio and structure; probable charset $charset"
                )
            }
        }

        val normalizedFallback = fallbackMime.substringBefore(';').trim().ifBlank { ALL_MIME }
        return result(
            FileType.UNKNOWN,
            normalizedFallback,
            DetectionConfidence.LOW,
            DetectionSource.FALLBACK,
            if (extension.isBlank()) {
                "No known signature or extension"
            } else {
                "No known signature or MIME mapping for .$extension"
            }
        )
    }

    private fun fileTypeForMime(mime: String): FileType = when {
        mime == "application/vnd.android.package-archive" -> FileType.APK
        mime.startsWith("video/") -> FileType.VIDEO
        mime.startsWith("audio/") -> FileType.AUDIO
        mime.startsWith("image/") -> FileType.IMAGE
        mime.startsWith("font/") -> FileType.FONT
        mime.startsWith("text/") || mime.contains("json") || mime.contains("xml") ||
            mime.contains("javascript") -> FileType.TEXT
        mime == "application/pdf" || mime.contains("document") || mime.contains("sheet") ||
            mime.contains("presentation") || mime == "application/rtf" ||
            mime == "application/epub+zip" -> FileType.DOCUMENT
        mime.contains("zip") || mime.contains("archive") || mime.contains("tar") ||
            mime.contains("rar") || mime.contains("7z") || mime.contains("gzip") -> FileType.ARCHIVE
        else -> FileType.UNKNOWN
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

    private fun detectStructuredText(header: ByteArray): String? {
        val charset = when {
            header.size >= 3 && matchBytes(header, 0xEF, 0xBB, 0xBF) -> Charsets.UTF_8
            header.size >= 2 && matchBytes(header, 0xFF, 0xFE) -> Charsets.UTF_16LE
            header.size >= 2 && matchBytes(header, 0xFE, 0xFF) -> Charsets.UTF_16BE
            else -> Charsets.UTF_8
        }
        val bomSize = when {
            header.size >= 3 && matchBytes(header, 0xEF, 0xBB, 0xBF) -> 3
            header.size >= 2 && (matchBytes(header, 0xFF, 0xFE) || matchBytes(header, 0xFE, 0xFF)) -> 2
            else -> 0
        }
        val sampleSize = minOf(header.size - bomSize, 512)
        if (sampleSize <= 0) return null

        val content = runCatching {
            String(header, bomSize, sampleSize, charset).trim()
        }.getOrDefault("")
        if (content.isEmpty()) return null

        return when {
            content.startsWith("{") || content.startsWith("[") -> "application/json"
            content.startsWith("<?xml", ignoreCase = true) -> "text/xml"
            content.startsWith("<") -> {
                val lower = content.lowercase(Locale.ROOT)
                if (lower.startsWith("<!doctype html") || lower.contains("<html")) {
                    "text/html"
                } else {
                    "text/xml"
                }
            }
            content.startsWith("---") || """(?m)^\w+:\s+.+""".toRegex().containsMatchIn(content) ->
                "text/yaml"
            content.startsWith("# ") || content.contains("**") || content.contains("- [ ]") ->
                "text/markdown"
            isCsv(content) -> "text/csv"
            else -> null
        }
    }

    private fun isCsv(content: String): Boolean {
        val lines = content.lines().filter { it.isNotBlank() }.take(5)
        if (lines.size < 2) return false
        val columnCounts = lines.map { it.split(',').size }
        return columnCounts.all { it > 1 && it == columnCounts.first() }
    }

    private fun getCharsetName(header: ByteArray): String {
        if (header.size >= 3 && matchBytes(header, 0xEF, 0xBB, 0xBF)) return "UTF-8"
        if (header.size >= 2 && matchBytes(header, 0xFF, 0xFE)) return "UTF-16LE"
        if (header.size >= 2 && matchBytes(header, 0xFE, 0xFF)) return "UTF-16BE"
        val sampleSize = minOf(header.size, 1024)
        val allAscii = (0 until sampleSize).all { (header[it].toInt() and 0xFF) <= 0x7F }
        return if (allAscii) "ASCII" else "UTF-8"
    }

    private fun isLikelyText(header: ByteArray): Boolean {
        if (header.size >= 3 && matchBytes(header, 0xEF, 0xBB, 0xBF)) return true
        if (header.size >= 2 &&
            (matchBytes(header, 0xFF, 0xFE) || matchBytes(header, 0xFE, 0xFF))
        ) return true
        val sampleSize = minOf(header.size, 1024)
        if (sampleSize == 0) return false
        val printable = (0 until sampleSize).count {
            val value = header[it].toInt() and 0xFF
            value in 9..13 || value in 32..126
        }
        return printable.toDouble() / sampleSize > 0.8
    }

    private fun isAacAdts(header: ByteArray): Boolean {
        if (header.size < 2) return false
        val first = header[0].toInt() and 0xFF
        val second = header[1].toInt() and 0xFF
        return first == 0xFF && (second and 0xF6) == 0xF0
    }

    private fun isMpegAudioFrame(header: ByteArray): Boolean {
        if (header.size < 2) return false
        val first = header[0].toInt() and 0xFF
        val second = header[1].toInt() and 0xFF
        val hasSync = first == 0xFF && (second and 0xE0) == 0xE0
        val validLayer = (second and 0x06) != 0
        return hasSync && validLayer && !isAacAdts(header)
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

    private fun indexOfBytes(header: ByteArray, needle: ByteArray, maxOffset: Int): Int {
        if (needle.isEmpty() || header.size < needle.size) return -1
        val last = minOf(header.size - needle.size, maxOffset)
        for (offset in 0..last) {
            if (needle.indices.all { header[offset + it] == needle[it] }) return offset
        }
        return -1
    }

    private fun matchBytes(header: ByteArray, vararg bytes: Int): Boolean =
        header.size >= bytes.size && bytes.indices.all {
            header[it].toInt() and 0xFF == bytes[it]
        }

    private fun matchBytesAt(header: ByteArray, offset: Int, vararg bytes: Int): Boolean =
        offset >= 0 && header.size >= offset + bytes.size && bytes.indices.all {
            header[offset + it].toInt() and 0xFF == bytes[it]
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

    private fun result(
        type: FileType,
        mime: String,
        confidence: DetectionConfidence,
        source: DetectionSource,
        evidence: String
    ): DetectionResult = DetectionResult(
        type,
        mime.substringBefore(';').trim().ifBlank { ALL_MIME },
        confidence,
        source,
        evidence
    )
}
