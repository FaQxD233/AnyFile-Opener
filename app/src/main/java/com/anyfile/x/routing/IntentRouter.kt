package com.anyfile.x.routing

import android.app.Activity
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.anyfile.x.data.RecentFile
import com.anyfile.x.data.RecentFileStore
import com.anyfile.x.engine.MimeTypes
import java.util.UUID

/** Fires ACTION_VIEW intents while preserving temporary URI access safely. */
object IntentRouter {

    enum class OpenStatus {
        DIRECT_STARTED,
        CHOOSER_STARTED,
        NO_HANDLER,
        FAILED
    }

    data class OpenResult(
        val status: OpenStatus,
        val chooserRequestId: String? = null
    )

    const val EXTRA_MIME_TYPE = "mime_type"
    const val EXTRA_CHOOSER_REQUEST_ID = "chooser_request_id"
    const val EXTRA_FILE_URI = "file_uri"
    const val EXTRA_FILE_NAME = "file_name"

    private const val TAG = "IntentRouter"
    private const val PREFS_NAME = "mime_prefs"
    private const val ACTION_CHOOSER_RESULT = "com.anyfile.x.action.CHOOSER_RESULT"

    /**
     * Shows the system chooser for [uri]. URI access is granted only through the
     * launched intent. [recentUri] is what recent files record; it differs from
     * [uri] when a cached copy is forwarded in place of the original.
     */
    fun open(
        context: Context,
        uri: Uri,
        mimeType: String,
        fileName: String? = null,
        trackChooserResult: Boolean = false,
        recentUri: Uri = uri
    ): OpenResult = showChooser(
        context,
        uri,
        MimeTypes.normalize(mimeType),
        fileName,
        trackChooserResult,
        recentUri
    )

    /** Opens a URI in one known package without granting access to other handlers. */
    fun openWithPackage(
        context: Context,
        uri: Uri,
        mimeType: String,
        packageName: String,
        fileName: String? = null,
        recentUri: Uri = uri
    ): OpenResult {
        if (packageName == context.packageName) return OpenResult(OpenStatus.FAILED)

        val normalizedMime = MimeTypes.normalize(mimeType)
        val intent = createViewIntent(uri, normalizedMime).apply {
            setPackage(packageName)
        }
        try {
            startActivity(context, intent)
        } catch (e: Exception) {
            Log.w(TAG, "Package $packageName could not open $uri", e)
            return OpenResult(OpenStatus.FAILED)
        }
        recordRecent(context, recentUri, fileName, normalizedMime)
        return OpenResult(OpenStatus.DIRECT_STARTED)
    }

    private fun showChooser(
        context: Context,
        uri: Uri,
        mimeType: String,
        fileName: String?,
        trackChooserResult: Boolean,
        recentUri: Uri
    ): OpenResult {
        val viewIntent = createViewIntent(uri, mimeType)
        val allHandlers = try {
            context.packageManager.queryIntentActivities(
                viewIntent,
                PackageManager.MATCH_DEFAULT_ONLY
            )
        } catch (e: Exception) {
            Log.w(TAG, "Could not query handlers for $mimeType", e)
            emptyList()
        }

        val externalHandlers = allHandlers.filter {
            it.activityInfo.packageName != context.packageName
        }
        if (externalHandlers.isEmpty()) {
            showNoAppDialog(context, uri, mimeType)
            return OpenResult(OpenStatus.NO_HANDLER)
        }

        val chooserRequestId = if (trackChooserResult) UUID.randomUUID().toString() else null
        if (chooserRequestId != null && !ChooserRequestStore.begin(context, chooserRequestId)) {
            Log.e(TAG, "Could not persist chooser request $chooserRequestId")
            Toast.makeText(context, "Could not start a tracked app chooser", Toast.LENGTH_SHORT).show()
            return OpenResult(OpenStatus.FAILED)
        }

        val ownComponents = allHandlers
            .filter { it.activityInfo.packageName == context.packageName }
            .map { ComponentName(it.activityInfo.packageName, it.activityInfo.name) }
            .toTypedArray()

        return try {
            val receiverIntent = Intent(context, ChooserResultReceiver::class.java).apply {
                action = "$ACTION_CHOOSER_RESULT.${UUID.randomUUID()}"
                putExtra(EXTRA_MIME_TYPE, mimeType)
                putExtra(EXTRA_FILE_URI, recentUri.toString())
                fileName?.let { putExtra(EXTRA_FILE_NAME, it) }
                chooserRequestId?.let { putExtra(EXTRA_CHOOSER_REQUEST_ID, it) }
            }
            val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                0,
                receiverIntent,
                pendingFlags
            )

            val chooser = Intent.createChooser(
                viewIntent,
                "Open with",
                pendingIntent.intentSender
            ).apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = ClipData.newRawUri(null, uri)
                if (ownComponents.isNotEmpty()) {
                    putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, ownComponents)
                }
            }

            startActivity(context, chooser)
            OpenResult(OpenStatus.CHOOSER_STARTED, chooserRequestId)
        } catch (e: Exception) {
            ChooserRequestStore.clear(context, chooserRequestId)
            Log.e(TAG, "Failed to start chooser", e)
            showNoAppDialog(context, uri, mimeType)
            OpenResult(OpenStatus.FAILED)
        }
    }

    private fun recordRecent(
        context: Context,
        uri: Uri,
        fileName: String?,
        mimeType: String
    ) {
        try {
            RecentFileStore.addRecentFileAsync(
                context,
                RecentFile(
                    uri = uri.toString(),
                    fileName = fileName ?: uri.lastPathSegment ?: "Unknown file",
                    mimeType = mimeType
                )
            )
        } catch (e: Exception) {
            Log.w(TAG, "Could not add $uri to recent files", e)
        }
    }

    private fun createViewIntent(uri: Uri, mimeType: String): Intent =
        Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newRawUri(null, uri)
        }

    private fun startActivity(context: Context, intent: Intent) {
        if (findActivity(context) == null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    private fun showNoAppDialog(context: Context, uri: Uri, mimeType: String) {
        val activity = findActivity(context)
        if (activity == null || activity.isFinishing) {
            Toast.makeText(context, "No app can open $mimeType", Toast.LENGTH_LONG).show()
            return
        }

        AlertDialog.Builder(activity)
            .setTitle("No app found")
            .setMessage("No installed app can handle '$mimeType'. Copy the file URI instead?")
            .setPositiveButton("Copy URI") { _, _ ->
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newRawUri("File URI", uri))
                Toast.makeText(context, "URI copied", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Dismiss", null)
            .show()
    }

    fun saveLastApp(context: Context, mimeType: String, packageName: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(MimeTypes.normalize(mimeType), packageName)
            .apply()
    }

    fun getLastApp(context: Context, mimeType: String): String? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(MimeTypes.normalize(mimeType), null)

    /**
     * Shares a file URI via ACTION_SEND while forwarding temporary read access.
     */
    fun share(
        context: Context,
        uri: Uri,
        mimeType: String,
        fileName: String? = null
    ): Boolean {
        val normalizedMime = MimeTypes.normalize(mimeType)
        return try {
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = normalizedMime
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = ClipData.newRawUri(fileName, uri)
                fileName?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
            }
            val chooser = Intent.createChooser(sendIntent, "Share").apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = ClipData.newRawUri(fileName, uri)
            }
            startActivity(context, chooser)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to share $uri", e)
            Toast.makeText(context, "Cannot share this file", Toast.LENGTH_SHORT).show()
            false
        }
    }

    private tailrec fun findActivity(context: Context): Activity? = when (context) {
        is Activity -> context
        is ContextWrapper -> findActivity(context.baseContext)
        else -> null
    }
}
