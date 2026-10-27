package com.anyfile.x.engine.utils

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

/** Display name, size, modification time and legacy path reported for a content or file URI. */
object UriMetadata {

    data class Metadata(
        val displayName: String?,
        val size: Long?,
        /** Epoch milliseconds, when the provider reports a modification time. */
        val lastModified: Long? = null,
        /** File-system path from the legacy `_data` column, when the provider still fills it. */
        val dataPath: String? = null
    )

    private val UNKNOWN = Metadata(null, null)

    /** DocumentsContract.Document.COLUMN_LAST_MODIFIED, in milliseconds. */
    private const val LAST_MODIFIED = "last_modified"

    /** MediaStore.MediaColumns.DATE_MODIFIED, in seconds. */
    private const val DATE_MODIFIED = "date_modified"

    /** MediaStore.MediaColumns.DATA, deprecated but still filled by many providers. */
    private const val DATA = "_data"

    private val BASIC_COLUMNS = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)

    fun query(resolver: ContentResolver, uri: Uri): Metadata {
        if (uri.scheme == ContentResolver.SCHEME_FILE) {
            val file = uri.path?.let(::File) ?: return UNKNOWN
            return Metadata(
                displayName = file.name,
                size = file.length().takeIf { file.isFile },
                lastModified = file.lastModified().takeIf { it > 0 },
                dataPath = file.path
            )
        }
        // Every column, so modification time and path come along where they exist; providers
        // that reject an unknown column in a projection still answer this form.
        return read(resolver, uri, null) ?: read(resolver, uri, BASIC_COLUMNS) ?: UNKNOWN
    }

    private fun read(resolver: ContentResolver, uri: Uri, projection: Array<String>?): Metadata? = try {
        resolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use UNKNOWN
            Metadata(
                displayName = cursor.string(OpenableColumns.DISPLAY_NAME),
                size = cursor.long(OpenableColumns.SIZE)?.takeIf { it >= 0 },
                lastModified = cursor.long(LAST_MODIFIED)?.takeIf { it > 0 }
                    ?: cursor.long(DATE_MODIFIED)?.takeIf { it > 0 }?.times(1000),
                dataPath = cursor.string(DATA)?.takeIf { it.startsWith("/") }
            )
        }
    } catch (_: Exception) {
        null
    }

    private fun Cursor.string(column: String): String? {
        val index = getColumnIndex(column)
        return if (index >= 0 && !isNull(index)) runCatching { getString(index) }.getOrNull() else null
    }

    private fun Cursor.long(column: String): Long? {
        val index = getColumnIndex(column)
        return if (index >= 0 && !isNull(index)) runCatching { getLong(index) }.getOrNull() else null
    }
}
