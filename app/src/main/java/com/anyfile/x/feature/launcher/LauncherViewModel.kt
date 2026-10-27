package com.anyfile.x.feature.launcher

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.anyfile.x.engine.FileInspector
import com.anyfile.x.engine.MimeDetector
import com.anyfile.x.tasks.FileTasks
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
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

    // One-shot events wait in channels until the screen collects them, also across rotation.
    private val _autoOpenEvent = Channel<AutoOpenRequest>(Channel.BUFFERED)
    val autoOpenEvent: Flow<AutoOpenRequest> = _autoOpenEvent.receiveAsFlow()
    private var detectionJob: Job? = null
    private var detectionGeneration = 0L

    /** The first background copy that is still running, if any. */
    val fileTask: StateFlow<FileTask?> = FileTasks.tasks
        .map { tasks -> tasks.firstOrNull()?.let { FileTask(it.kind.verb, it.done, it.total) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    private val _renamedCopies = Channel<RenamedCopy>(Channel.BUFFERED)
    val renamedCopies: Flow<RenamedCopy> = _renamedCopies.receiveAsFlow()

    /** The renamed copy being prepared; abandoned when this screen goes away for good. */
    private var renameTaskId: Long? = null

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
                        _autoOpenEvent.send(
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

    /** Copies [source] into the document the user created, in the background. */
    fun saveCopy(source: Uri, target: Uri, fileName: String) {
        FileTasks.save(getApplication(), source, target, fileName, selectedSize)
    }

    /** Prepares a cached copy named [fileName]; the screen opens it from [renamedCopies]. */
    fun openWithCorrectedName(source: Uri, fileName: String, mime: String) {
        if (renameTaskId != null) return
        val id = FileTasks.prepare(getApplication(), source, fileName, selectedSize)
        renameTaskId = id
        viewModelScope.launch {
            val result = FileTasks.results.mapNotNull { it[id] }.first()
            FileTasks.claim(id)
            renameTaskId = null
            when (result) {
                is FileTasks.Result.Prepared ->
                    _renamedCopies.send(RenamedCopy(source, result.copy, fileName, mime))
                is FileTasks.Result.Failed -> _messages.send("Preparing failed: ${result.message}")
                FileTasks.Result.Cancelled -> Unit
            }
        }
    }

    override fun onCleared() {
        renameTaskId?.let(FileTasks::abandon)
    }
}
