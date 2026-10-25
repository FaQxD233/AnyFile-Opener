package com.anyfile.x.engine

import com.anyfile.x.engine.MimeDetector.DetectionConfidence
import com.anyfile.x.engine.MimeDetector.DetectionResult
import com.anyfile.x.engine.MimeDetector.DetectionSource
import java.util.Locale

/**
 * Compares a file name with content-based detection and proposes a corrected name
 * when the extension does not fit the content.
 */
object NameCheck {

    data class Suggestion(
        /** Current extension, or null when the name has none. */
        val currentExtension: String?,
        /** Short format name shown to the user, e.g. "PNG". */
        val formatLabel: String,
        val suggestedName: String
    ) {
        val message: String
            get() = if (currentExtension == null) {
                "Name has no extension; content is $formatLabel"
            } else {
                "Name says .$currentExtension, content is $formatLabel"
            }
    }

    /**
     * Extensions that legitimately carry each detected type; the first one is used
     * for corrected names. Families that share a container are listed together so
     * that, for example, a .m4a with an mp4 brand or a camera RAW file is not flagged.
     */
    private val COMPATIBLE_EXTENSIONS: Map<String, List<String>> = mapOf(
        "image/jpeg" to listOf("jpg", "jpeg", "jpe", "jfif"),
        "image/png" to listOf("png", "apng"),
        "image/gif" to listOf("gif"),
        "image/webp" to listOf("webp"),
        "image/bmp" to listOf("bmp", "dib"),
        "image/tiff" to listOf(
            "tif", "tiff", "dng", "cr2", "nef", "nrw", "arw", "srf", "sr2",
            "orf", "pef", "rw2", "raf", "3fr", "erf", "kdc", "dcr", "mos"
        ),
        "image/heic" to listOf("heic", "heif", "hif", "avif"),
        "image/avif" to listOf("avif", "heic", "heif"),
        "video/mp4" to listOf("mp4", "m4v", "m4a", "m4b", "m4p", "f4v", "mov", "3gp"),
        "audio/mp4" to listOf("m4a", "m4b", "m4p", "mp4", "aac"),
        "video/quicktime" to listOf("mov", "qt", "mp4"),
        "video/3gpp" to listOf("3gp", "3gpp", "3g2", "mp4"),
        "video/x-matroska" to listOf("mkv", "mka", "mk3d", "webm"),
        "video/webm" to listOf("webm", "weba", "mkv"),
        "video/x-msvideo" to listOf("avi"),
        "video/x-flv" to listOf("flv"),
        "video/x-ms-wmv" to listOf("wmv", "asf", "wma"),
        "audio/x-ms-wma" to listOf("wma", "asf", "wmv"),
        "video/mpeg" to listOf("mpg", "mpeg", "vob", "m1v", "m2v", "mpe"),
        "video/mp2t" to listOf("ts", "m2ts", "mts", "tp", "trp"),
        "audio/mpeg" to listOf("mp3", "mp2", "mpga", "mp1"),
        "audio/aac" to listOf("aac", "adts"),
        "audio/flac" to listOf("flac"),
        "audio/ogg" to listOf("ogg", "oga", "opus", "spx"),
        "video/ogg" to listOf("ogv", "ogg"),
        "audio/wav" to listOf("wav", "wave"),
        "audio/amr" to listOf("amr"),
        "audio/amr-wb" to listOf("awb", "amr"),
        "audio/midi" to listOf("mid", "midi", "kar", "rmi"),
        "audio/x-aiff" to listOf("aiff", "aif", "aifc"),
        "audio/x-ape" to listOf("ape"),
        "application/pdf" to listOf("pdf", "ai"),
        "application/rtf" to listOf("rtf", "doc"),
        MimeTypes.MSWORD to listOf("doc", "dot", "wps"),
        MimeTypes.MS_EXCEL to listOf("xls", "xlt", "xla", "et"),
        MimeTypes.MS_POWERPOINT to listOf("ppt", "pps", "pot", "dps"),
        MimeTypes.MS_OUTLOOK to listOf("msg"),
        MimeTypes.VISIO to listOf("vsd", "vss", "vst"),
        MimeTypes.DOCX to listOf("docx", "docm", "dotx", "dotm"),
        MimeTypes.XLSX to listOf("xlsx", "xlsm", "xltx", "xltm", "xlsb"),
        MimeTypes.PPTX to listOf("pptx", "pptm", "potx", "potm", "ppsx", "ppsm"),
        "application/vnd.oasis.opendocument.text" to listOf("odt", "ott"),
        "application/vnd.oasis.opendocument.spreadsheet" to listOf("ods", "ots"),
        "application/vnd.oasis.opendocument.presentation" to listOf("odp", "otp"),
        "application/vnd.oasis.opendocument.graphics" to listOf("odg", "otg"),
        MimeTypes.EPUB to listOf("epub", "kepub"),
        MimeTypes.CHM to listOf("chm"),
        MimeTypes.MOBI to listOf("mobi", "azw", "azw3", "prc"),
        MimeTypes.APK to listOf("apk"),
        MimeTypes.XAPK to listOf("xapk"),
        MimeTypes.APKM to listOf("apkm"),
        MimeTypes.JAR to listOf("jar"),
        "application/vnd.rar" to listOf("rar", "cbr"),
        "application/x-7z-compressed" to listOf("7z", "cb7"),
        "application/gzip" to listOf("gz", "tgz", "svgz", "gzip"),
        "application/x-xz" to listOf("xz", "txz"),
        "application/x-bzip2" to listOf("bz2", "tbz", "tbz2"),
        "application/x-tar" to listOf("tar", "cbt"),
        "application/x-iso9660-image" to listOf("iso", "img"),
        "font/ttf" to listOf("ttf", "otf", "ttc"),
        "font/otf" to listOf("otf", "ttf"),
        "font/woff" to listOf("woff"),
        "font/woff2" to listOf("woff2"),
        "application/x-sqlite3" to listOf("db", "sqlite", "sqlite3", "db3", "s3db", "sl3"),
        "application/x-bittorrent" to listOf("torrent")
    )

    /**
     * Returns a suggestion only for high-confidence, content-based results with a
     * specific type whose expected extensions do not include the current one.
     */
    fun check(
        fileName: String?,
        result: DetectionResult,
        systemMime: (String) -> String? = MimeTypes::systemMimeFor
    ): Suggestion? {
        if (result.confidence != DetectionConfidence.HIGH) return null
        if (result.source != DetectionSource.MAGIC_BYTES &&
            result.source != DetectionSource.CONTAINER_CONTENT
        ) return null
        val compatible = result.extensionHint?.let { listOf(it) }
            ?: COMPATIBLE_EXTENSIONS[result.mime]
            ?: return null
        val current = MimeTypes.extensionOf(fileName).ifEmpty { null }
        if (current != null && (current in compatible || systemMime(current) == result.mime)) {
            return null
        }
        val preferred = compatible.first()
        return Suggestion(
            currentExtension = current,
            formatLabel = preferred.uppercase(Locale.ROOT),
            suggestedName = correctedName(fileName, preferred, compatible)
        )
    }

    /**
     * Replaces a wrong trailing extension, or appends [preferred]. A name such as
     * "report.pdf.1" keeps its inner extension when that one already fits.
     */
    fun correctedName(fileName: String?, preferred: String, compatible: List<String>): String {
        val name = fileName.orEmpty()
            .substringAfterLast('/')
            .trim()
            .trimEnd('.')
            .ifBlank { "file" }
        val current = MimeTypes.extensionOf(name)
        if (current.isEmpty()) return "$name.$preferred"
        val base = name.substring(0, name.length - current.length - 1).trimEnd('.')
        if (MimeTypes.extensionOf(base) in compatible) return base
        return "${base.ifBlank { "file" }}.$preferred"
    }
}
