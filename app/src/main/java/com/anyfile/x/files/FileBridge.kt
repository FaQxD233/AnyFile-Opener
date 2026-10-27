package com.anyfile.x.files

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.anyfile.x.engine.MimeTypes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

/**
 * Copies a file into the app cache and serves it through FileProvider. Used to
 * forward a file under a corrected name, or to forward a legacy file:// source.
 * Copies are kept for a day because the receiving app may read them later.
 */
object FileBridge {

    private const val DIRECTORY = "bridge"
    private const val MAX_AGE_MS = 24L * 60 * 60 * 1000
    private const val FREE_SPACE_MARGIN = 16L * 1024 * 1024
    private const val MAX_NAME_LENGTH = 120
    private val UNSAFE_NAME_CHARS = Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]")

    /** Copies [source] as [displayName] and returns a content URI for the copy. */
    suspend fun copy(
        context: Context,
        source: Uri,
        displayName: String,
        size: Long?,
        onProgress: (Long, Long?) -> Unit
    ): Uri = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val root = File(appContext.cacheDir, DIRECTORY)
        prune(root)
        if (size != null && appContext.cacheDir.usableSpace < size + FREE_SPACE_MARGIN) {
            throw IOException("Not enough free space to copy the file")
        }

        val folder = File(root, UUID.randomUUID().toString())
        if (!folder.mkdirs()) throw IOException("Could not create a cache folder")
        val target = File(folder, safeName(displayName))
        try {
            val input = appContext.contentResolver.openInputStream(source)
                ?: throw IOException("Cannot read the file")
            input.use { stream ->
                FileOutputStream(target).use { output ->
                    StreamPump.pump(stream, size, onProgress) { buffer, length ->
                        output.write(buffer, 0, length)
                    }
                }
            }
        } catch (e: Throwable) {
            folder.deleteRecursively()
            throw e
        }
        FileProvider.getUriForFile(appContext, "${appContext.packageName}.files", target)
    }

    /** Deletes copies older than a day; called at startup and before each new copy. */
    fun pruneExpired(context: Context) {
        prune(File(context.applicationContext.cacheDir, DIRECTORY))
    }

    private fun prune(root: File) {
        val cutoff = System.currentTimeMillis() - MAX_AGE_MS
        root.listFiles()?.forEach { folder ->
            if (folder.lastModified() < cutoff) folder.deleteRecursively()
        }
    }

    /** Keeps the extension when trimming, and blocks path separators and hidden names. */
    private fun safeName(displayName: String): String {
        val cleaned = displayName.replace(UNSAFE_NAME_CHARS, "_").trim().trimStart('.')
        if (cleaned.isEmpty()) return "file"
        if (cleaned.length <= MAX_NAME_LENGTH) return cleaned
        val extension = MimeTypes.extensionOf(cleaned)
        if (extension.isEmpty()) return cleaned.take(MAX_NAME_LENGTH)
        val base = cleaned.dropLast(extension.length + 1).take(MAX_NAME_LENGTH - extension.length - 1)
        return "$base.$extension"
    }
}
