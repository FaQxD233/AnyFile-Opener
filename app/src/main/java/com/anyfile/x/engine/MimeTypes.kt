package com.anyfile.x.engine

import android.webkit.MimeTypeMap
import com.anyfile.x.engine.MimeDetector.FileType
import java.util.Locale

/** MIME constants, normalization and file-extension knowledge shared across the app. */
object MimeTypes {

    const val ANY = "*/*"
    const val OCTET_STREAM = "application/octet-stream"
    const val APK = "application/vnd.android.package-archive"
    const val XAPK = "application/xapk-package-archive"
    const val APKM = "application/vnd.apkm"

    /** Split-APK sets (.apks) have no registered type; installers match the file name. */
    const val APKS = OCTET_STREAM
    const val ZIP = "application/zip"
    const val JAR = "application/java-archive"
    const val CBZ = "application/x-cbz"
    const val EPUB = "application/epub+zip"
    const val DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    const val XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    const val PPTX = "application/vnd.openxmlformats-officedocument.presentationml.presentation"
    const val MSWORD = "application/msword"
    const val MS_EXCEL = "application/vnd.ms-excel"
    const val MS_POWERPOINT = "application/vnd.ms-powerpoint"
    const val MS_OUTLOOK = "application/vnd.ms-outlook"
    const val VISIO = "application/vnd.visio"
    const val OLE_STORAGE = "application/x-ole-storage"
    const val CHM = "application/x-chm"
    const val MOBI = "application/x-mobipocket-ebook"
    const val HLS = "application/vnd.apple.mpegurl"

    /** Type implied by an extension that Android's own map lacks or gets wrong. */
    data class KnownType(
        val mime: String,
        val fileType: FileType,
        val zipBased: Boolean = false,
        val ole2: Boolean = false
    )

    private val MIME_PATTERN = Regex("^[a-z0-9!#&^_.+-]+/[a-z0-9!#&^_.+*-]+$")

    private val DOCUMENT_MIMES = setOf(
        "application/pdf",
        "application/rtf",
        EPUB,
        MSWORD,
        MS_EXCEL,
        MS_POWERPOINT,
        MS_OUTLOOK,
        VISIO,
        OLE_STORAGE,
        CHM,
        MOBI
    )

    private val KNOWN_EXTENSIONS: Map<String, KnownType> = buildMap<String, KnownType> {
        fun add(
            type: FileType,
            mime: String,
            vararg extensions: String,
            zip: Boolean = false,
            ole2: Boolean = false
        ) {
            for (extension in extensions) put(extension, KnownType(mime, type, zip, ole2))
        }

        add(FileType.APK, APK, "apk", zip = true)
        add(FileType.APK, XAPK, "xapk", zip = true)
        add(FileType.APK, APKM, "apkm", zip = true)
        add(FileType.APK, APKS, "apks", zip = true)
        add(FileType.ARCHIVE, JAR, "jar", zip = true)
        add(FileType.ARCHIVE, CBZ, "cbz", zip = true)
        add(FileType.DOCUMENT, EPUB, "epub", zip = true)
        add(FileType.DOCUMENT, "application/vnd.oasis.opendocument.text", "odt", zip = true)
        add(FileType.DOCUMENT, "application/vnd.oasis.opendocument.spreadsheet", "ods", zip = true)
        add(FileType.DOCUMENT, "application/vnd.oasis.opendocument.presentation", "odp", zip = true)
        add(FileType.DOCUMENT, "application/vnd.oasis.opendocument.graphics", "odg", zip = true)
        add(FileType.DOCUMENT, DOCX, "docx", "docm", "dotx", zip = true)
        add(FileType.DOCUMENT, XLSX, "xlsx", "xlsm", "xltx", zip = true)
        add(FileType.DOCUMENT, PPTX, "pptx", "pptm", "potx", "ppsx", zip = true)
        add(FileType.DOCUMENT, MSWORD, "doc", "dot", ole2 = true)
        add(FileType.DOCUMENT, MS_EXCEL, "xls", "xlt", ole2 = true)
        add(FileType.DOCUMENT, MS_POWERPOINT, "ppt", "pps", "pot", ole2 = true)
        add(FileType.DOCUMENT, MS_OUTLOOK, "msg", ole2 = true)
        add(FileType.DOCUMENT, VISIO, "vsd", ole2 = true)
        add(FileType.DOCUMENT, CHM, "chm")
        add(FileType.DOCUMENT, MOBI, "mobi", "azw", "azw3")
        add(FileType.TEXT, "text/markdown", "md", "markdown")
        add(FileType.TEXT, "application/json", "json")
        add(FileType.TEXT, "text/x-vcard", "vcf")
        add(FileType.TEXT, "text/calendar", "ics")
        add(FileType.AUDIO, "audio/x-mpegurl", "m3u")
        add(FileType.VIDEO, HLS, "m3u8")
        add(FileType.AUDIO, "audio/amr", "amr")
        add(FileType.AUDIO, "audio/x-ape", "ape")
        add(FileType.AUDIO, "audio/midi", "mid", "midi")
        add(FileType.AUDIO, "audio/x-aiff", "aif", "aiff", "aifc")
        add(FileType.AUDIO, "audio/x-ms-wma", "wma")
        add(FileType.VIDEO, "video/x-ms-wmv", "wmv")
        add(FileType.UNKNOWN, "application/x-bittorrent", "torrent")
        add(FileType.UNKNOWN, "application/x-sqlite3", "sqlite", "sqlite3", "db3")
    }

    /** Strips parameters and lower-cases a MIME type; blank values become [ANY]. */
    fun normalize(mime: String?): String =
        mime.orEmpty().substringBefore(';').trim().lowercase(Locale.ROOT).ifBlank { ANY }

    /** Validates a normalized MIME type. */
    fun isValid(mime: String): Boolean = mime == ANY || MIME_PATTERN.matches(mime)

    /** Lower-case extension of [fileName], or "" when it has no plausible one. */
    fun extensionOf(fileName: String?): String {
        val name = fileName.orEmpty().substringAfterLast('/').substringAfterLast('\\')
        val dot = name.lastIndexOf('.')
        if (dot < 0 || dot == name.length - 1) return ""
        val extension = name.substring(dot + 1).lowercase(Locale.ROOT)
        val plausible = extension.length <= 10 &&
            extension.all { it in 'a'..'z' || it in '0'..'9' }
        return if (plausible) extension else ""
    }

    fun known(extension: String): KnownType? = KNOWN_EXTENSIONS[extension]

    fun zipFlavor(extension: String): KnownType? = known(extension)?.takeIf { it.zipBased }

    fun ole2Flavor(extension: String): KnownType? = known(extension)?.takeIf { it.ole2 }

    /** Android's built-in extension map. Blank extensions are never looked up. */
    fun systemMimeFor(extension: String): String? {
        if (extension.isBlank()) return null
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)?.let(::normalize)
    }

    fun mimeForExtension(extension: String): String? =
        known(extension)?.mime ?: systemMimeFor(extension)

    fun fileTypeFor(mime: String): FileType {
        val type = normalize(mime)
        return when {
            type == APK || type == XAPK || type == APKM -> FileType.APK
            type == HLS || type == "application/x-mpegurl" -> FileType.VIDEO
            type.startsWith("video/") -> FileType.VIDEO
            type.startsWith("audio/") -> FileType.AUDIO
            type.startsWith("image/") -> FileType.IMAGE
            type.startsWith("font/") -> FileType.FONT
            type.startsWith("text/") || type.endsWith("/json") || type.endsWith("+json") ||
                type.endsWith("/xml") || type.endsWith("+xml") || type.contains("javascript") ->
                FileType.TEXT
            type in DOCUMENT_MIMES || type.contains("document") || type.contains("sheet") ||
                type.contains("presentation") -> FileType.DOCUMENT
            type == CBZ || type.contains("zip") || type.contains("archive") ||
                type.contains("tar") || type.contains("rar") || type.contains("7z") ||
                type.contains("gzip") -> FileType.ARCHIVE
            else -> FileType.UNKNOWN
        }
    }
}
