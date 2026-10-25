package com.anyfile.x.feature.common

import android.net.Uri
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.anyfile.x.engine.MimeTypes
import com.anyfile.x.files.FileExporter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * "Save a copy" for a bottom sheet: system picker, copy with progress, result toast.
 * Create it as a property of the fragment so the picker result is registered in time.
 */
class SaveCopyAction(private val fragment: Fragment) {

    private class Pending(
        val source: Uri,
        val size: Long?,
        val progress: TaskProgress,
        val onBusyChanged: (Boolean) -> Unit
    )

    private var pending: Pending? = null

    private val launcher = fragment.registerForActivityResult(SaveCopyContract()) { target ->
        val request = pending
        pending = null
        when {
            target == null -> Unit
            // The sheet was recreated while the picker was open; drop the empty file.
            request == null -> FileExporter.discard(fragment.requireContext().contentResolver, target)
            else -> export(request, target)
        }
    }

    fun start(
        source: Uri,
        fileName: String,
        size: Long?,
        progress: TaskProgress,
        onBusyChanged: (Boolean) -> Unit
    ) {
        pending = Pending(source, size, progress, onBusyChanged)
        launcher.launch(SaveCopyContract.Request(fileName, MimeTypes.mimeForFileName(fileName)))
    }

    private fun export(request: Pending, target: Uri) {
        val context = fragment.requireContext().applicationContext
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            request.onBusyChanged(true)
            request.progress.start("Saving", request.size)
            try {
                FileExporter.export(context, request.source, target, request.size, request.progress.listener())
                Toast.makeText(context, "Copy saved", Toast.LENGTH_SHORT).show()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val reason = e.message?.let { ": $it" }.orEmpty()
                Toast.makeText(context, "Could not save the copy$reason", Toast.LENGTH_LONG).show()
            } finally {
                request.progress.finish()
                request.onBusyChanged(false)
            }
        }
    }
}
