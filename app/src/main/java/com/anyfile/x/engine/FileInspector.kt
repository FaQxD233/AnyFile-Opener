package com.anyfile.x.engine

import android.content.Context
import android.net.Uri
import com.anyfile.x.engine.utils.UriMetadata
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Reads file metadata and detects the type under time limits, so a slow or stuck
 * provider (cloud storage, pipes) cannot leave the UI waiting indefinitely.
 */
object FileInspector {

    const val METADATA_TIMEOUT_MS = 3_000L
    const val DETECTION_TIMEOUT_MS = 6_000L

    data class Inspection(
        val displayName: String?,
        val size: Long?,
        val result: MimeDetector.DetectionResult
    )

    /** Owns reads that outlive a timeout: blocking provider I/O cannot be interrupted. */
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun metadata(context: Context, uri: Uri): UriMetadata.Metadata {
        val resolver = context.applicationContext.contentResolver
        return withIoTimeout(METADATA_TIMEOUT_MS) { UriMetadata.query(resolver, uri) }
            ?: UriMetadata.Metadata(null, null)
    }

    /** Full detection, or a name-based guess when the content cannot be read in time. */
    suspend fun detect(
        context: Context,
        uri: Uri,
        displayName: String?,
        fallbackMime: String
    ): MimeDetector.DetectionResult {
        val resolver = context.applicationContext.contentResolver
        return withIoTimeout(DETECTION_TIMEOUT_MS) {
            MimeDetector.detect(resolver, uri, displayName, fallbackMime)
        } ?: MimeDetector.detectFromName(displayName, fallbackMime).let {
            it.copy(evidence = "Reading the file timed out; ${it.evidence}")
        }
    }

    suspend fun inspect(context: Context, uri: Uri, fallbackMime: String): Inspection {
        val metadata = metadata(context, uri)
        val name = metadata.displayName ?: uri.lastPathSegment
        return Inspection(name, metadata.size, detect(context, uri, name, fallbackMime))
    }

    /**
     * Runs [block] on the IO pool and waits at most [timeoutMs]. Returns null on
     * timeout; the abandoned read finishes in the background and is discarded.
     */
    internal suspend fun <T : Any> withIoTimeout(timeoutMs: Long, block: () -> T?): T? {
        val deferred = ioScope.async { block() }
        try {
            return withTimeoutOrNull(timeoutMs) { deferred.await() }
        } finally {
            deferred.cancel()
        }
    }
}
