package com.anyfile.x.feature.common

import android.app.Application
import android.net.Uri
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.anyfile.x.tasks.FileTasks
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull

/**
 * "Open as <corrected name>": prepares a copy under that name as a background task and
 * hands it over once the screen is visible ([RenamedOpenEffect]). Owned by a ViewModel,
 * which calls [release] when its screen closes for good.
 */
class RenamedOpener(private val application: Application) {

    data class Request(val taskId: Long, val source: Uri, val fileName: String, val mime: String)

    private val _pending = MutableStateFlow<Request?>(null)

    /** The copy being prepared; the screen stays busy until it is handed over. */
    val pending: StateFlow<Request?> = _pending.asStateFlow()

    fun start(source: Uri, fileName: String, mime: String, size: Long?) {
        if (_pending.value != null) return
        val taskId = FileTasks.prepare(application, source, fileName, size)
        _pending.value = Request(taskId, source, fileName, mime)
    }

    /** Waits for the pending copy to finish and returns it with its result. */
    suspend fun awaitResult(): Pair<Request, FileTasks.Result> {
        val request = _pending.filterNotNull().first()
        val result = FileTasks.results.mapNotNull { it[request.taskId] }.first()
        FileTasks.claim(request.taskId)
        _pending.value = null
        return request to result
    }

    fun release() {
        _pending.value?.let { FileTasks.abandon(it.taskId) }
        _pending.value = null
    }
}

/** Hands prepared copies from [opener] to [onReady] while the screen is visible. */
@Composable
fun RenamedOpenEffect(opener: RenamedOpener, onReady: (RenamedOpener.Request, Uri) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnReady by rememberUpdatedState(onReady)
    LaunchedEffect(opener, lifecycleOwner) {
        // The app chooser cannot open from the background; a copy that finishes then waits.
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                val (request, result) = opener.awaitResult()
                when (result) {
                    is FileTasks.Result.Prepared -> currentOnReady(request, result.copy)
                    is FileTasks.Result.Failed -> Toast.makeText(
                        context,
                        "Could not prepare the file: ${result.message}",
                        Toast.LENGTH_LONG
                    ).show()
                    FileTasks.Result.Cancelled -> Unit
                }
            }
        }
    }
}
