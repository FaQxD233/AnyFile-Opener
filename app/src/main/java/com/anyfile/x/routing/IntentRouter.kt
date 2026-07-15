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
import android.provider.DocumentsContract
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.anyfile.x.data.DefaultAppRuleScope
import com.anyfile.x.data.DefaultAppRuleStore
import com.anyfile.x.data.RecentFile
import com.anyfile.x.data.RecentFileStore
import java.util.Locale
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
    const val EXTRA_RULE_SCOPE = "rule_scope"
    const val EXTRA_RULE_KEY = "rule_key"
    const val EXTRA_CHOOSER_REQUEST_ID = "chooser_request_id"
    const val EXTRA_FILE_URI = "file_uri"
    const val EXTRA_FILE_NAME = "file_name"

    private const val TAG = "IntentRouter"
    private const val PREFS_NAME = "mime_prefs"
    private const val ACTION_CHOOSER_RESULT = "com.anyfile.x.action.CHOOSER_RESULT"

    /**
     * Opens a URI with a saved default rule when one exists, otherwise shows the
     * system chooser. URI access is granted only through the launched intent.
     */
    fun open(
        context: Context,
        uri: Uri,
        mimeType: String,
        fileName: String? = null,
        useDefaultRule: Boolean = true,
        trackChooserResult: Boolean = false
    ): OpenResult {
        val normalizedMime = normalizeIntentMime(mimeType)

        if (useDefaultRule) {
            val rule = DefaultAppRuleStore.findRule(context, normalizedMime, fileName)
            if (rule != null) {
                val result = openWithPackage(
                    context,
                    uri,
                    normalizedMime,
                    rule.packageName,
                    fileName
                )
                if (result.status == OpenStatus.DIRECT_STARTED) return result

                // An uninstalled or no-longer-compatible target must not trap the user.
                DefaultAppRuleStore.removeRule(context, rule)
            }
        }

        return showChooser(
            context,
            uri,
            normalizedMime,
            fileName,
            null,
            null,
            trackChooserResult
        )
    }

    /** Opens the chooser and saves the selected package as a new default rule. */
    fun chooseAndSaveDefault(
        context: Context,
        uri: Uri,
        mimeType: String,
        fileName: String?,
        scope: DefaultAppRuleScope,
        trackChooserResult: Boolean = false
    ): OpenResult {
        val normalizedMime = normalizeIntentMime(mimeType)
        val ruleKey = when (scope) {
            DefaultAppRuleScope.MIME -> DefaultAppRuleStore.normalizeMime(normalizedMime)
            DefaultAppRuleScope.EXTENSION -> DefaultAppRuleStore.normalizeExtension(fileName)
        }

        if (ruleKey == null) {
            Toast.makeText(context, "This file has no usable ${scope.displayName.lowercase()} rule key", Toast.LENGTH_SHORT).show()
            return OpenResult(OpenStatus.FAILED)
        }

        return showChooser(
            context,
            uri,
            normalizedMime,
            fileName,
            scope,
            ruleKey,
            trackChooserResult
        )
    }

    /** Opens a URI in one known package without granting access to other handlers. */
    fun openWithPackage(
        context: Context,
        uri: Uri,
        mimeType: String,
        packageName: String,
        fileName: String? = null
    ): OpenResult {
        if (packageName == context.packageName) return OpenResult(OpenStatus.FAILED)

        val normalizedMime = normalizeIntentMime(mimeType)
        val intent = createViewIntent(uri, normalizedMime).apply {
            setPackage(packageName)
        }
        try {
            startActivity(context, intent)
        } catch (e: Exception) {
            Log.w(TAG, "Default package $packageName could not open $uri", e)
            return OpenResult(OpenStatus.FAILED)
        }
        recordRecent(context, uri, fileName, normalizedMime)
        return OpenResult(OpenStatus.DIRECT_STARTED)
    }

    private fun showChooser(
        context: Context,
        uri: Uri,
        mimeType: String,
        fileName: String?,
        ruleScope: DefaultAppRuleScope?,
        ruleKey: String?,
        trackChooserResult: Boolean
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
                putExtra(EXTRA_FILE_URI, uri.toString())
                fileName?.let { putExtra(EXTRA_FILE_NAME, it) }
                chooserRequestId?.let { putExtra(EXTRA_CHOOSER_REQUEST_ID, it) }
                ruleScope?.let { putExtra(EXTRA_RULE_SCOPE, it.name) }
                ruleKey?.let { putExtra(EXTRA_RULE_KEY, it) }
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
        val key = normalizeIntentMime(mimeType)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(key, packageName)
            .apply()
    }

    fun getLastApp(context: Context, mimeType: String): String? {
        val key = normalizeIntentMime(mimeType)
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(key, null)
    }

    /**
     * Opens a containing directory only when the provider exposes path-like external
     * storage document IDs. Other DocumentsProvider IDs are opaque and cannot be
     * safely converted into a parent URI.
     */
    fun openFolder(context: Context, uri: Uri) {
        try {
            if (DocumentsContract.isDocumentUri(context, uri) &&
                uri.authority == "com.android.externalstorage.documents"
            ) {
                val documentId = DocumentsContract.getDocumentId(uri)
                val volume = documentId.substringBefore(':', "")
                val relativePath = documentId.substringAfter(':', "")
                if (volume.isNotBlank()) {
                    val parentPath = relativePath.substringBeforeLast('/', "")
                    val parentId = "$volume:$parentPath"
                    val parentUri = if (DocumentsContract.isTreeUri(uri)) {
                        DocumentsContract.buildDocumentUriUsingTree(uri, parentId)
                    } else {
                        DocumentsContract.buildDocumentUri(uri.authority, parentId)
                    }
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(parentUri, DocumentsContract.Document.MIME_TYPE_DIR)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(context, intent)
                    return
                }
            }

            Toast.makeText(
                context,
                "This file provider does not expose its containing folder",
                Toast.LENGTH_SHORT
            ).show()
        } catch (e: Exception) {
            Log.e(TAG, "Error opening folder", e)
            Toast.makeText(context, "Cannot open the containing folder", Toast.LENGTH_SHORT).show()
        }
    }

    private fun normalizeIntentMime(mimeType: String): String =
        mimeType.substringBefore(';').trim().lowercase(Locale.ROOT).ifBlank { "*/*" }

    private tailrec fun findActivity(context: Context): Activity? = when (context) {
        is Activity -> context
        is ContextWrapper -> findActivity(context.baseContext)
        else -> null
    }
}
