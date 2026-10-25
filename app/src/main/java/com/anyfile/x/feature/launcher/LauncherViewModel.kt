package com.anyfile.x.feature.launcher

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.anyfile.x.engine.FileInspector
import com.anyfile.x.engine.MimeDetector
import com.anyfile.x.files.FileBridge
import com.anyfile.x.files.FileExporter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class LauncherViewModel(application: Application) : AndroidViewModel(application) {

    data class AutoOpenRequest(
        val uri: Uri,
        val fileName: String,
        val result: MimeDetector.DetectionResult
    )

    /** A copy in progress (saving, or preparing a renamed copy to open). */
    data class FileTask(val label: String, val done: Long, val total: Long?)

    /** A cached copy ready to be opened under its corrected name. */
    data class RenamedCopy(
        val original: Uri,
        val copy: Uri,
        val fileName: String,
        val mime: String
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

    private val _fileTask = MutableStateFlow<FileTask?>(null)
    val fileTask: StateFlow<FileTask?> = _fileTask

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages

    private val _renamedCopies = MutableSharedFlow<RenamedCopy>(extraBufferCapacity = 1)
    val renamedCopies: SharedFlow<RenamedCopy> = _renamedCopies

    private var fileTaskJob: Job? = null

    /** Size of the selected file when its provider reports one. */
    var selectedSize: Long? = null
        private set

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
                val result = FileInspector.detect(getApplication<Application>(), uri, fileName, fallbackMime)
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

    suspend fun queryFileName(uri: Uri): String? {
        val metadata = FileInspector.metadata(getApplication<Application>(), uri)
        selectedSize = metadata.size
        return metadata.displayName
    }

    /** Copies [source] into the document the user created; survives rotation. */
    fun saveCopy(source: Uri, target: Uri) = runFileTask("Saving") { onProgress ->
        FileExporter.export(getApplication<Application>(), source, target, selectedSize, onProgress)
        _messages.emit("Copy saved")
    }

    /** Prepares a cached copy named [fileName]; the screen opens it from [renamedCopies]. */
    fun openWithCorrectedName(source: Uri, fileName: String, mime: String) =
        runFileTask("Preparing") { onProgress ->
            val copy = FileBridge.copy(getApplication<Application>(), source, fileName, selectedSize, onProgress)
            _renamedCopies.emit(RenamedCopy(source, copy, fileName, mime))
        }

    private fun runFileTask(
        label: String,
        block: suspend (onProgress: (Long, Long?) -> Unit) -> Unit
    ) {
        if (fileTaskJob?.isActive == true) return
        val total = selectedSize
        fileTaskJob = viewModelScope.launch {
            _fileTask.value = FileTask(label, 0, total)
            try {
                block { done, size -> _fileTask.value = FileTask(label, done, size) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.emit("$label failed${e.message?.let { ": $it" }.orEmpty()}")
            } finally {
                _fileTask.value = null
            }
        }
    }
}
