package com.anyfile.x.engine

import android.content.ContentResolver
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.FileInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/** Positioned reads over a seekable file. */
interface RandomAccessSource {
    val size: Long

    /** Reads exactly [length] bytes at [position], or returns null when unavailable. */
    fun read(position: Long, length: Int): ByteArray?
}

/** Access to the whole file, opened only when a container needs a deeper look. */
interface ContainerAccess {
    /** Seekable view of the file, or null for pipes and other streaming sources. */
    fun randomAccess(): RandomAccessSource?

    /** A fresh stream from the start of the file; the caller closes it. */
    fun openStream(): InputStream?
}

class FileChannelSource(private val channel: FileChannel) : RandomAccessSource {

    override val size: Long = try {
        channel.size()
    } catch (_: Exception) {
        0L
    }

    override fun read(position: Long, length: Int): ByteArray? {
        if (position < 0 || length < 0 || position + length > size) return null
        val buffer = ByteBuffer.allocate(length)
        var offset = position
        try {
            while (buffer.hasRemaining()) {
                val read = channel.read(buffer, offset)
                if (read <= 0) return null
                offset += read
            }
        } catch (_: Exception) {
            return null
        }
        return buffer.array()
    }
}

/** [ContainerAccess] for a content or file URI; the descriptor opens on first use. */
class ContentContainerAccess(
    private val resolver: ContentResolver,
    private val uri: Uri
) : ContainerAccess, Closeable {

    private var descriptor: ParcelFileDescriptor? = null
    private var source: RandomAccessSource? = null
    private var opened = false

    override fun randomAccess(): RandomAccessSource? {
        if (!opened) {
            opened = true
            source = openSeekable()
        }
        return source
    }

    override fun openStream(): InputStream? = try {
        resolver.openInputStream(uri)
    } catch (_: Exception) {
        null
    }

    private fun openSeekable(): RandomAccessSource? {
        val pfd = try {
            resolver.openFileDescriptor(uri, "r")
        } catch (_: Exception) {
            null
        } ?: return null
        descriptor = pfd
        val candidate = FileChannelSource(FileInputStream(pfd.fileDescriptor).channel)
        // Pipes report size 0 and reject positioned reads.
        return candidate.takeIf { it.size > 0 && it.read(0, 1) != null }
    }

    override fun close() {
        try {
            descriptor?.close()
        } catch (_: Exception) {
            // Already closed.
        }
        descriptor = null
        source = null
    }
}
