package com.anyfile.x.feature.launcher

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.anyfile.x.data.RecentFile
import com.anyfile.x.data.RecentFileStore
import com.anyfile.x.engine.FileInspector
import com.anyfile.x.engine.MimeDetector
import com.anyfile.x.feature.common.LastApp
import com.anyfile.x.feature.common.RenamedOpener
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LauncherViewModel(application: Application) : AndroidViewModel(application) {

    data class AutoOpenRequest(
        val uri: Uri,
        val fileName: String,
        val result: MimeDetector.DetectionResult
    )

    /** A recent file, and whether its URI can still be read. */
    data class RecentEntry(val file: RecentFile, val readable: Boolean)

    private val _detectionResult = MutableStateFlow<MimeDetector.DetectionResult?>(null)
    val detectionResult: StateFlow<MimeDetector.DetectionResult?> = _detectionResult

    private val _isDetecting = MutableStateFlow(false)
    val isDetecting: StateFlow<Boolean> = _isDetecting

    private val _lastApp = MutableStateFlow<LastApp?>(null)

    /** The app last picked for the selected file's type, for "Open with <app>". */
    val lastApp: StateFlow<LastApp?> = _lastApp.asStateFlow()

    // One-shot events wait in a channel until the screen collects them, also across rotation.
    private val _autoOpenEvent = Channel<AutoOpenRequest>(Channel.BUFFERED)
    val autoOpenEvent: Flow<AutoOpenRequest> = _autoOpenEvent.receiveAsFlow()
    private var detectionJob: Job? = null
    private var detectionGeneration = 0L

    val renamedOpener = RenamedOpener(application)

    private val recentsRefresh = MutableStateFlow(0)

    /** Recent files. Readability is checked off the main thread: it asks other apps' providers. */
    val recents: StateFlow<List<RecentEntry>> = combine(
        RecentFileStore.getRecentFiles(application),
        recentsRefresh
    ) { files, _ -> files }
        .map { files -> files.map { RecentEntry(it, RecentFileStore.isReadable(application, it.uri)) } }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Size of the selected file when its provider reports one. */
    var selectedSize: Long? = null
        private set

    /** Checks again whether recent files are readable, e.g. after returning to the app. */
    fun refreshRecents() {
        recentsRefresh.update { it + 1 }
    }

    fun detectMime(
        uri: Uri,
        fileName: String?,
        fallbackMime: String = "*/*",
        triggerAutoOpen: Boolean = false
    ) {
        detectionJob?.cancel()
        val generation = ++detectionGeneration
        _detectionResult.value = null
        _lastApp.value = null
        _isDetecting.value = true

        val app = getApplication<Application>()
        val job = viewModelScope.launch {
            try {
                val result = FileInspector.detect(app, uri, fileName, fallbackMime)
                if (generation != detectionGeneration) return@launch
                _detectionResult.value = result
                _isDetecting.value = false
                if (triggerAutoOpen && result.confidence == MimeDetector.DetectionConfidence.HIGH) {
                    _autoOpenEvent.send(
                        AutoOpenRequest(uri, fileName ?: uri.lastPathSegment ?: "Unknown file", result)
                    )
                }
                val lastApp = withContext(Dispatchers.IO) { LastApp.find(app, uri, result) }
                if (generation == detectionGeneration) _lastApp.value = lastApp
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation == detectionGeneration) {
                    _detectionResult.value = MimeDetector.detectFromName(fileName, fallbackMime)
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
        _lastApp.value = null
        _isDetecting.value = false
    }

    suspend fun queryFileName(uri: Uri): String? {
        val metadata = FileInspector.metadata(getApplication<Application>(), uri)
        selectedSize = metadata.size
        return metadata.displayName
    }

    /** Prepares a copy named [fileName] in the background; the screen opens it when ready. */
    fun openWithCorrectedName(source: Uri, fileName: String, mime: String) {
        renamedOpener.start(source, fileName, mime, selectedSize)
    }

    override fun onCleared() {
        renamedOpener.release()
    }
}
