package com.openbridge

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import android.widget.Toast

/**
 * Fires a new ACTION_VIEW intent with the URI and resolved MIME type.
 */
object IntentRouter {

    private const val TAG = "IntentRouter"

    fun open(context: Context, uri: Uri, mimeType: String) {
        try {
            val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                // Use ClipData to ensure permission is passed correctly to the target app
                clipData = ClipData.newRawUri(null, uri)
            }

            // Explicitly grant read permission to possible handlers
            try {
                val resInfoList = context.packageManager.queryIntentActivities(
                    viewIntent, PackageManager.MATCH_DEFAULT_ONLY
                )
                for (resolveInfo in resInfoList) {
                    val packageName = resolveInfo.activityInfo.packageName
                    context.grantUriPermission(
                        packageName, uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to grant permissions: ${e.message}")
            }

            val chooser = Intent.createChooser(viewIntent, "Open with").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(chooser)
        } catch (e: Exception) {
            Log.e(TAG, "No handler found for MIME: $mimeType URI: $uri", e)
            Toast.makeText(context, "No app found for: $mimeType", Toast.LENGTH_SHORT).show()
        }
    }
}
