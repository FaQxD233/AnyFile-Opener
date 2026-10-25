package com.anyfile.x.files

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/** Writes a copy of a file into a document the user created with the system picker. */
object FileExporter {

    /** Copies [source] into [target]; a partly written target is deleted on failure. */
    suspend fun export(
        context: Context,
        source: Uri,
        target: Uri,
        size: Long?,
        onProgress: (Long, Long?) -> Unit
    ): Long = withContext(Dispatchers.IO) {
        val resolver = context.applicationContext.contentResolver
        try {
            val input = resolver.openInputStream(source) ?: throw IOException("Cannot read the file")
            input.use { stream ->
                val output = resolver.openOutputStream(target, "w")
                    ?: throw IOException("Cannot write the copy")
                output.use { sink ->
                    StreamPump.pump(stream, size, onProgress) { buffer, length ->
                        sink.write(buffer, 0, length)
                    }
                }
            }
        } catch (e: Throwable) {
            discard(resolver, target)
            throw e
        }
    }

    /** Deletes a created document that will not receive content. */
    fun discard(resolver: ContentResolver, target: Uri) {
        try {
            DocumentsContract.deleteDocument(resolver, target)
        } catch (_: Exception) {
            // The provider may not support deletion; an empty file is left behind.
        }
    }
}
