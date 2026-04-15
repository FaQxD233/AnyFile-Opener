package com.openbridge.utils

import android.content.ContentResolver
import android.net.Uri
import android.util.Log

object ByteReader {

    private const val TAG = "ByteReader"

    /**
     * Reads up to [count] bytes from a URI using ContentResolver.
     * Returns a ByteArray containing only the bytes actually read.
     * Returns an empty array on failure; never throws.
     */
    fun readHeader(contentResolver: ContentResolver, uri: Uri, count: Int = 32): ByteArray {
        return try {
            contentResolver.openInputStream(uri)?.use { stream ->
                val buffer = ByteArray(count)
                val bytesRead = stream.read(buffer)
                if (bytesRead > 0) {
                    if (bytesRead == count) buffer else buffer.copyOf(bytesRead)
                } else {
                    ByteArray(0)
                }
            } ?: ByteArray(0)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read header from $uri", e)
            ByteArray(0)
        }
    }
}
