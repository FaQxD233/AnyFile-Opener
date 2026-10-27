package com.anyfile.x.tasks

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.anyfile.x.files.FileBridge
import com.anyfile.x.files.FileExporter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * File copies that must not depend on a screen: saving a copy, and preparing a copy
 * under another name. They run in the process scope, and [FileTaskService] keeps the
 * process alive while showing their progress in a notification.
 */
object FileTasks {

    enum class Kind(val verb: String) { SAVE("Saving"), PREPARE("Preparing") }

    data class Task(
        val id: Long,
        val kind: Kind,
        /** The file being read; screens show the tasks of the file they display. */
        val source: Uri,
        val fileName: String,
        val done: Long,
        val total: Long?
    )

    /** Outcome of a [Kind.PREPARE] task, kept until the screen that started it claims it. */
    sealed interface Result {
        data class Prepared(val copy: Uri) : Result
        data class Failed(val message: String) : Result
        data object Cancelled : Result
    }

    /** A notification is not worth showing for copies that finish sooner. */
    private const val NOTIFICATION_DELAY_MS = 500L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val nextId = AtomicLong()
    private val jobs = ConcurrentHashMap<Long, Job>()
    private val abandoned = ConcurrentHashMap.newKeySet<Long>()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _tasks = MutableStateFlow<List<Task>>(emptyList())
    val tasks: StateFlow<List<Task>> = _tasks.asStateFlow()

    private val _results = MutableStateFlow<Map<Long, Result>>(emptyMap())
    val results: StateFlow<Map<Long, Result>> = _results.asStateFlow()

    /** Copies [source] into [target], a document the user created; the outcome is a toast. */
    fun save(context: Context, source: Uri, target: Uri, fileName: String, size: Long?): Long {
        val appContext = context.applicationContext
        return start(appContext, Kind.SAVE, source, fileName, size) { onProgress ->
            FileExporter.export(appContext, source, target, size, onProgress)
            null
        }
    }

    /** Copies [source] into the cache as [fileName]; the result holds a shareable URI. */
    fun prepare(context: Context, source: Uri, fileName: String, size: Long?): Long {
        val appContext = context.applicationContext
        return start(appContext, Kind.PREPARE, source, fileName, size) { onProgress ->
            Result.Prepared(FileBridge.copy(appContext, source, fileName, size, onProgress))
        }
    }

    fun cancel(id: Long) {
        jobs[id]?.cancel()
    }

    fun cancelAll() {
        jobs.values.forEach { it.cancel() }
    }

    /** Removes and returns the result of [id], or null while it is not available. */
    fun claim(id: Long): Result? {
        var claimed: Result? = null
        _results.update { results ->
            claimed = results[id]
            results - id
        }
        return claimed
    }

    /** For a screen closed for good: stops [id] if it still runs and drops its result. */
    fun abandon(id: Long) {
        abandoned += id
        cancel(id)
        _results.update { it - id }
    }

    private fun start(
        context: Context,
        kind: Kind,
        source: Uri,
        fileName: String,
        size: Long?,
        work: suspend (onProgress: (Long, Long?) -> Unit) -> Result?
    ): Long {
        val id = nextId.incrementAndGet()
        _tasks.update { it + Task(id, kind, source, fileName, 0, size?.takeIf { total -> total > 0 }) }
        // Started only once registered, so a copy that ends at once still finds its entry.
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val result = try {
                work { done, total -> updateProgress(id, done, total) }
            } catch (e: CancellationException) {
                // The job ends right here; only non-suspending bookkeeping follows.
                Result.Cancelled
            } catch (e: Exception) {
                Result.Failed(e.message ?: e.javaClass.simpleName)
            }
            finish(context, id, kind, result)
        }
        jobs[id] = job
        job.start()
        scope.launch {
            delay(NOTIFICATION_DELAY_MS)
            if (jobs.isNotEmpty()) FileTaskService.start(context)
        }
        return id
    }

    private fun updateProgress(id: Long, done: Long, total: Long?) {
        _tasks.update { tasks ->
            tasks.map { task ->
                if (task.id == id) task.copy(done = done, total = total?.takeIf { it > 0 } ?: task.total) else task
            }
        }
    }

    private fun finish(context: Context, id: Long, kind: Kind, result: Result?) {
        jobs.remove(id)
        val wasAbandoned = abandoned.remove(id)
        when (kind) {
            Kind.SAVE -> toast(
                context,
                when (result) {
                    null -> "Copy saved"
                    is Result.Failed -> "Could not save the copy: ${result.message}"
                    else -> "Save cancelled"
                }
            )
            Kind.PREPARE -> if (!wasAbandoned && result != null) {
                _results.update { it + (id to result) }
            }
        }
        _tasks.update { tasks -> tasks.filterNot { it.id == id } }
    }

    private fun toast(context: Context, message: String) {
        mainHandler.post { Toast.makeText(context, message, Toast.LENGTH_SHORT).show() }
    }
}
