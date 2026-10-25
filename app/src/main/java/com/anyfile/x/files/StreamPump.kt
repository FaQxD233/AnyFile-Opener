package com.anyfile.x.files

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.InputStream

/** Read loop shared by copying and hashing: progress reports plus cancellation checks. */
object StreamPump {

    private const val BUFFER_BYTES = 64 * 1024
    private const val PROGRESS_STEP_BYTES = 512L * 1024

    /**
     * Reads [input] to the end and hands each chunk to [sink]. [onProgress] receives
     * (bytes done, total bytes) at most every 512 KB and once at the end; it is called
     * on the reading thread. Returns the number of bytes read.
     */
    suspend fun pump(
        input: InputStream,
        total: Long?,
        onProgress: (Long, Long?) -> Unit = { _, _ -> },
        sink: (ByteArray, Int) -> Unit
    ): Long {
        val buffer = ByteArray(BUFFER_BYTES)
        var done = 0L
        var reported = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            sink(buffer, read)
            done += read
            if (done - reported >= PROGRESS_STEP_BYTES) {
                reported = done
                onProgress(done, total)
            }
        }
        onProgress(done, total)
        return done
    }
}
