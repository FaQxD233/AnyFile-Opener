package com.anyfile.x.files

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract

/** Where a file lives, for showing and copying: a file-system path when one can be derived. */
object FileLocation {

    private const val EXTERNAL_STORAGE = "com.android.externalstorage.documents"
    private const val DOWNLOADS = "com.android.providers.downloads.documents"
    private const val RAW_DOWNLOAD = "raw:"

    /**
     * A path from the URI itself (file://, storage and download documents) or from the
     * provider's legacy `_data` column; null when neither tells one.
     */
    fun path(context: Context, uri: Uri, dataPath: String?): String? {
        if (uri.scheme == ContentResolver.SCHEME_FILE) return uri.path
        return documentPath(context, uri) ?: dataPath
    }

    /** The URI in readable form, for files that have no path. */
    fun readableUri(uri: Uri): String = Uri.decode(uri.toString())

    private fun documentPath(context: Context, uri: Uri): String? {
        val documentId = try {
            if (!DocumentsContract.isDocumentUri(context, uri)) return null
            DocumentsContract.getDocumentId(uri)
        } catch (_: Exception) {
            return null
        }
        return when (uri.authority) {
            EXTERNAL_STORAGE -> {
                // "primary:Download/a.bin", "1234-5678:Music/b.mp3" or "home:c.pdf".
                val root = documentId.substringBefore(':')
                val relative = documentId.substringAfter(':', "")
                @Suppress("DEPRECATION")
                val primary = Environment.getExternalStorageDirectory().path
                val base = when (root) {
                    "primary" -> primary
                    "home" -> "$primary/${Environment.DIRECTORY_DOCUMENTS}"
                    else -> "/storage/$root"
                }
                if (relative.isEmpty()) base else "$base/$relative"
            }
            DOWNLOADS -> documentId.takeIf { it.startsWith(RAW_DOWNLOAD) }?.removePrefix(RAW_DOWNLOAD)
            else -> null
        }
    }
}
