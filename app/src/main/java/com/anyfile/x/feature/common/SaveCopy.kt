package com.anyfile.x.feature.common

import android.Manifest
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.anyfile.x.engine.MimeTypes
import com.anyfile.x.files.FileExporter
import com.anyfile.x.tasks.FileTaskService
import com.anyfile.x.tasks.FileTasks

/** Asks for the permission that progress notifications need on Android 13+; copies run without it. */
@Composable
fun rememberNotificationRequest(): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    return remember(launcher) {
        {
            if (FileTaskService.needsNotificationPermission(context)) {
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}

/**
 * "Save a copy": the system picker prefilled with a file name, then a background copy
 * that keeps going when the screen closes. Returns the function that starts it.
 */
@Composable
fun rememberSaveCopy(): (source: Uri, fileName: String, size: Long?) -> Unit {
    val context = LocalContext.current
    val requestNotifications = rememberNotificationRequest()
    // Kept across recreation while the picker is open.
    var pendingSource by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingName by rememberSaveable { mutableStateOf("") }
    var pendingSize by rememberSaveable { mutableStateOf(-1L) }
    val picker = rememberLauncherForActivityResult(SaveCopyContract()) { target ->
        val source = pendingSource
        pendingSource = null
        when {
            target == null -> Unit
            // The request was lost; drop the empty document the picker created.
            source == null -> FileExporter.discard(context.contentResolver, target)
            else -> {
                FileTasks.save(
                    context,
                    Uri.parse(source),
                    target,
                    pendingName,
                    pendingSize.takeIf { it >= 0 }
                )
                requestNotifications()
            }
        }
    }
    return remember(picker) {
        { source: Uri, fileName: String, size: Long? ->
            pendingSource = source.toString()
            pendingName = fileName
            pendingSize = size ?: -1L
            picker.launch(SaveCopyContract.Request(fileName, MimeTypes.mimeForFileName(fileName)))
        }
    }
}
