package com.anyfile.x.files

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/** MD5 and SHA-256 of a whole file, computed in a single pass. */
object FileHasher {

    data class Hashes(val md5: String, val sha256: String)

    private const val HEX_DIGITS = "0123456789abcdef"

    suspend fun hash(
        context: Context,
        uri: Uri,
        size: Long?,
        onProgress: (Long, Long?) -> Unit
    ): Hashes = withContext(Dispatchers.IO) {
        val input = context.applicationContext.contentResolver.openInputStream(uri)
            ?: throw IOException("Cannot read the file")
        input.use { hash(it, size, onProgress) }
    }

    suspend fun hash(
        input: InputStream,
        size: Long?,
        onProgress: (Long, Long?) -> Unit = { _, _ -> }
    ): Hashes {
        val md5 = MessageDigest.getInstance("MD5")
        val sha256 = MessageDigest.getInstance("SHA-256")
        StreamPump.pump(input, size, onProgress) { buffer, length ->
            md5.update(buffer, 0, length)
            sha256.update(buffer, 0, length)
        }
        return Hashes(toHex(md5.digest()), toHex(sha256.digest()))
    }

    fun toHex(bytes: ByteArray): String {
        val hex = StringBuilder(bytes.size * 2)
        for (byte in bytes) {
            val value = byte.toInt() and 0xFF
            hex.append(HEX_DIGITS[value ushr 4]).append(HEX_DIGITS[value and 0x0F])
        }
        return hex.toString()
    }
}
