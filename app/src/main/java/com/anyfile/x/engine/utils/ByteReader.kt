package com.anyfile.x.engine.utils

import android.content.ContentResolver
import android.net.Uri
import android.util.Log
import java.io.InputStream

object ByteReader {

    private const val TAG = "ByteReader"

    /**
     * Reads up to [count] bytes from a URI starting from [offset].
     * Returns a ByteArray containing only the bytes actually read.
     * Returns an empty array on failure; never throws.
     */
    fun readBytes(contentResolver: ContentResolver, uri: Uri, offset: Long, count: Int): ByteArray {
        if (offset < 0 || count <= 0) return ByteArray(0)
        return try {
            contentResolver.openInputStream(uri)?.use { stream ->
                if (!skipFully(stream, offset)) return@use ByteArray(0)
                val buffer = ByteArray(count)
                var totalRead = 0
                while (totalRead < count) {
                    val bytesRead = stream.read(buffer, totalRead, count - totalRead)
                    if (bytesRead == -1) break
                    if (bytesRead == 0) {
                        val singleByte = stream.read()
                        if (singleByte == -1) break
                        buffer[totalRead++] = singleByte.toByte()
                    } else {
                        totalRead += bytesRead
                    }
                }
                if (totalRead > 0) {
                    if (totalRead == count) buffer else buffer.copyOf(totalRead)
                } else {
                    ByteArray(0)
                }
            } ?: ByteArray(0)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read bytes from $uri at offset $offset", e)
            ByteArray(0)
        }
    }

    private fun skipFully(stream: InputStream, n: Long): Boolean {
        var remaining = n
        while (remaining > 0) {
            val skipped = stream.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
            } else {
                // Some ContentProvider streams do not implement skip(). Reading one
                // byte guarantees progress without returning duplicate pages.
                if (stream.read() == -1) return false
                remaining--
            }
        }
        return true
    }

    fun readHeader(contentResolver: ContentResolver, uri: Uri, count: Int = 256): ByteArray {
        return readBytes(contentResolver, uri, 0, count)
    }
}
