package com.anyfile.x.feature.openas

import android.app.Application
import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.anyfile.x.engine.FileInspector
import com.anyfile.x.engine.NameCheck
import com.anyfile.x.feature.common.LastApp
import com.anyfile.x.feature.common.RenamedOpener
import com.anyfile.x.tasks.FileTasks
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** State of one "Open as" sheet; it survives rotation and is cleared when the sheet closes. */
class OpenAsViewModel(application: Application) : AndroidViewModel(application) {

    data class State(
        /** Name to show before the inspection is done. */
        val name: String? = null,
        /** The file every action uses: the incoming URI, or a cached copy of a file:// source. */
        val workingUri: Uri? = null,
        val inspection: FileInspector.Inspection? = null,
        val suggestion: NameCheck.Suggestion? = null,
        val lastApp: LastApp? = null,
        /** A file:// source that this app is not allowed to read. */
        val unreadable: Boolean = false
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    val renamedOpener = RenamedOpener(application)

    private var bridgeTaskId: Long? = null
    private var loaded = false

    /** Inspects [uri] once, unless [preset] already holds the result. */
    fun load(uri: Uri, preset: FileInspector.Inspection?, fallbackMime: String) {
        if (loaded) return
        loaded = true
        _state.value = State(name = preset?.displayName)
        val app = getApplication<Application>()
        viewModelScope.launch {
            val source = if (uri.scheme == ContentResolver.SCHEME_FILE) {
                bridge(uri) ?: return@launch
            } else {
                uri
            }
            _state.update { it.copy(workingUri = source) }
            val inspection = preset ?: FileInspector.inspect(app, source, fallbackMime)
            _state.update {
                it.copy(
                    name = inspection.displayName ?: it.name,
                    inspection = inspection,
                    suggestion = NameCheck.check(inspection.displayName, inspection.result)
                )
            }
            val lastApp = withContext(Dispatchers.IO) { LastApp.find(app, source, inspection.result) }
            _state.update { it.copy(lastApp = lastApp) }
        }
    }

    /**
     * Copies a legacy file:// source into the cache so it can be forwarded as a content
     * URI. Returns null, and marks the file unreadable, when that fails.
     */
    private suspend fun bridge(uri: Uri): Uri? {
        val name = uri.lastPathSegment ?: "file"
        _state.update { it.copy(name = name) }
        val size = withContext(Dispatchers.IO) { uri.path?.let { File(it).length() }?.takeIf { it > 0 } }
        val taskId = FileTasks.prepare(getApplication<Application>(), uri, name, size)
        bridgeTaskId = taskId
        val result = FileTasks.results.mapNotNull { it[taskId] }.first()
        FileTasks.claim(taskId)
        bridgeTaskId = null
        val copy = (result as? FileTasks.Result.Prepared)?.copy
        if (copy == null) _state.update { it.copy(unreadable = true) }
        return copy
    }

    override fun onCleared() {
        bridgeTaskId?.let(FileTasks::abandon)
        renamedOpener.release()
    }
}
