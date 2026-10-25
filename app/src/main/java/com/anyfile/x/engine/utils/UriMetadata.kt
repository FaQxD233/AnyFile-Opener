package com.anyfile.x.engine.utils

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

/** Display name and size reported for a content or file URI. */
object UriMetadata {

    data class Metadata(val displayName: String?, val size: Long?)

    private val UNKNOWN = Metadata(null, null)

    fun query(resolver: ContentResolver, uri: Uri): Metadata {
        if (uri.scheme == ContentResolver.SCHEME_FILE) {
            val file = uri.path?.let(::File) ?: return UNKNOWN
            return Metadata(file.name, file.length().takeIf { file.isFile })
        }
        return try {
            resolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use UNKNOWN
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                Metadata(
                    displayName = if (nameIndex >= 0) cursor.getString(nameIndex) else null,
                    size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                        cursor.getLong(sizeIndex).takeIf { it >= 0 }
                    } else {
                        null
                    }
                )
            } ?: UNKNOWN
        } catch (_: Exception) {
            UNKNOWN
        }
    }
}
