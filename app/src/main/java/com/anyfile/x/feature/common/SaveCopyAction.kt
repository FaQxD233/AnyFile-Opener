package com.anyfile.x.feature.common

import android.Manifest
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.anyfile.x.engine.MimeTypes
import com.anyfile.x.files.FileExporter
import com.anyfile.x.tasks.FileTaskService
import com.anyfile.x.tasks.FileTasks

/**
 * "Save a copy" for a bottom sheet: the system picker, then a background copy that
 * keeps going when the sheet closes. Create it as a property of the fragment so the
 * picker result is registered in time.
 */
class SaveCopyAction(private val fragment: Fragment) {

    private class Pending(val source: Uri, val fileName: String, val size: Long?)

    private var pending: Pending? = null

    private val notificationPermission = fragment.registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    private val launcher = fragment.registerForActivityResult(SaveCopyContract()) { target ->
        val request = pending
        pending = null
        when {
            target == null -> Unit
            // The sheet was recreated while the picker was open; drop the empty file.
            request == null -> FileExporter.discard(fragment.requireContext().contentResolver, target)
            else -> {
                val context = fragment.requireContext()
                FileTasks.save(context, request.source, target, request.fileName, request.size)
                if (FileTaskService.needsNotificationPermission(context)) {
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }
    }

    fun start(source: Uri, fileName: String, size: Long?) {
        pending = Pending(source, fileName, size)
        launcher.launch(SaveCopyContract.Request(fileName, MimeTypes.mimeForFileName(fileName)))
    }
}
