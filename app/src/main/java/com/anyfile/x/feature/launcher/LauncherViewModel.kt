package com.anyfile.x.feature.launcher

import android.app.Application
import android.net.Uri
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.anyfile.x.data.RecentFile
import com.anyfile.x.data.RecentFileStore
import com.anyfile.x.engine.MimeDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LauncherViewModel(application: Application) : AndroidViewModel(application) {

    data class AutoOpenRequest(
        val uri: Uri,
        val fileName: String,
        val result: MimeDetector.DetectionResult
    )

    private val _detectionResult = MutableStateFlow<MimeDetector.DetectionResult?>(null)
    val detectionResult: StateFlow<MimeDetector.DetectionResult?> = _detectionResult

    private val _isDetecting = MutableStateFlow(false)
    val isDetecting: StateFlow<Boolean> = _isDetecting

    // Events that should only be handled once (like auto-opening)
    private val _autoOpenEvent = MutableSharedFlow<AutoOpenRequest>(replay = 0)
    val autoOpenEvent: SharedFlow<AutoOpenRequest> = _autoOpenEvent
    private var detectionJob: Job? = null
    private var detectionGeneration = 0L

    fun detectMime(
        uri: Uri,
        fileName: String?,
        fallbackMime: String = "*/*",
        triggerAutoOpen: Boolean = false
    ) {
        detectionJob?.cancel()
        val generation = ++detectionGeneration
        _detectionResult.value = null
        _isDetecting.value = true

        val job = viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    MimeDetector.detect(
                        getApplication<Application>().contentResolver,
                        uri,
                        fileName,
                        fallbackMime
                    )
                }
                if (generation == detectionGeneration) {
                    _detectionResult.value = result
                    if (triggerAutoOpen &&
                        result.confidence == MimeDetector.DetectionConfidence.HIGH
                    ) {
                        _autoOpenEvent.emit(
                            AutoOpenRequest(uri, fileName ?: uri.lastPathSegment ?: "Unknown file", result)
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation == detectionGeneration) {
                    _detectionResult.value = null
                }
            } finally {
                if (generation == detectionGeneration) {
                    _isDetecting.value = false
                    detectionJob = null
                }
            }
        }
        detectionJob = job
    }
    
    fun resetDetection() {
        detectionJob?.cancel()
        detectionJob = null
        detectionGeneration++
        _detectionResult.value = null
        _isDetecting.value = false
    }

    suspend fun queryFileName(uri: Uri): String? = withContext(Dispatchers.IO) {
        var name: String? = null
        try {
            getApplication<Application>().contentResolver.query(
                uri,
                arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use {
                if (it.moveToFirst()) {
                    val column = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (column >= 0) name = it.getString(column)
                }
            }
        } catch (_: Exception) {}
        name
    }

    fun addToRecents(uri: Uri, fileName: String, mime: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                getApplication<Application>().contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {
                // Not every provider offers persistable grants; the current session can still open it.
            }
            RecentFileStore.addRecentFile(getApplication(), RecentFile(uri.toString(), fileName, mime))
        }
    }

    /**
     * Proactive caching: Manually set a known MIME result to avoid re-detection.
     */
    fun setDetectedMimeManual(mime: String) {
        detectionJob?.cancel()
        detectionJob = null
        detectionGeneration++
        _isDetecting.value = false
        // Find the best FileType match for the cached MIME
        val fileType = when {
            mime == "application/vnd.android.package-archive" -> MimeDetector.FileType.APK
            mime == "application/pdf" || mime.contains("document") || mime.contains("sheet") ||
                mime.contains("presentation") || mime.contains("epub") -> MimeDetector.FileType.DOCUMENT
            mime == "application/zip" || mime.contains("archive") || mime.contains("tar") ||
                mime.contains("rar") || mime.contains("7z") || mime.contains("gzip") -> MimeDetector.FileType.ARCHIVE
            mime.startsWith("video/") -> MimeDetector.FileType.VIDEO
            mime.startsWith("audio/") -> MimeDetector.FileType.AUDIO
            mime.startsWith("image/") -> MimeDetector.FileType.IMAGE
            mime.startsWith("text/") || mime.contains("json") || mime.contains("xml") -> MimeDetector.FileType.TEXT
            mime.startsWith("font/") -> MimeDetector.FileType.FONT
            else -> MimeDetector.FileType.UNKNOWN
        }
               
        _detectionResult.value = MimeDetector.DetectionResult(
            fileType = fileType,
            mime = mime.substringBefore(';').trim(),
            confidence = MimeDetector.DetectionConfidence.LOW,
            source = MimeDetector.DetectionSource.USER_OVERRIDE,
            evidence = "MIME restored from recent-file history"
        )
    }
}
