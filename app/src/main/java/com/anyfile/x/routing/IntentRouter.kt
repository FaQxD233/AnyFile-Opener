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
import java.util.concurrent.atomic.AtomicInteger
import java.util.Locale

/** Fires ACTION_VIEW intents while preserving temporary URI access safely. */
object IntentRouter {

    enum class OpenResult {
        STARTED,
        NO_HANDLER,
        FAILED
    }

    const val EXTRA_MIME_TYPE = "mime_type"
    const val EXTRA_RULE_SCOPE = "rule_scope"
    const val EXTRA_RULE_KEY = "rule_key"

    private const val TAG = "IntentRouter"
    private const val PREFS_NAME = "mime_prefs"
    private val nextRequestCode = AtomicInteger(1)

    /**
     * Opens a URI with a saved default rule when one exists, otherwise shows the
     * system chooser. URI access is granted only through the launched intent.
     */
    fun open(
        context: Context,
        uri: Uri,
        mimeType: String,
        fileName: String? = null,
        useDefaultRule: Boolean = true
    ): OpenResult {
        val normalizedMime = normalizeIntentMime(mimeType)

        if (useDefaultRule) {
            val rule = DefaultAppRuleStore.findRule(context, normalizedMime, fileName)
            if (rule != null) {
                val result = openWithPackage(context, uri, normalizedMime, rule.packageName)
                if (result == OpenResult.STARTED) return result

                // An uninstalled or no-longer-compatible target must not trap the user.
                DefaultAppRuleStore.removeRule(context, rule)
            }
        }

        return showChooser(context, uri, normalizedMime, null, null)
    }

    /** Opens the chooser and saves the selected package as a new default rule. */
    fun chooseAndSaveDefault(
        context: Context,
        uri: Uri,
        mimeType: String,
        fileName: String?,
        scope: DefaultAppRuleScope
    ): OpenResult {
        val normalizedMime = normalizeIntentMime(mimeType)
        val ruleKey = when (scope) {
            DefaultAppRuleScope.MIME -> DefaultAppRuleStore.normalizeMime(normalizedMime)
            DefaultAppRuleScope.EXTENSION -> DefaultAppRuleStore.normalizeExtension(fileName)
        }

        if (ruleKey == null) {
            Toast.makeText(context, "This file has no usable ${scope.displayName.lowercase()} rule key", Toast.LENGTH_SHORT).show()
            return OpenResult.FAILED
        }

        return showChooser(context, uri, normalizedMime, scope, ruleKey)
    }

    /** Opens a URI in one known package without granting access to other handlers. */
    fun openWithPackage(
        context: Context,
        uri: Uri,
        mimeType: String,
        packageName: String
    ): OpenResult {
        if (packageName == context.packageName) return OpenResult.FAILED

        val intent = createViewIntent(uri, normalizeIntentMime(mimeType)).apply {
            setPackage(packageName)
        }
        return try {
            startActivity(context, intent)
            OpenResult.STARTED
        } catch (e: Exception) {
            Log.w(TAG, "Default package $packageName could not open $uri", e)
            OpenResult.FAILED
        }
    }

    private fun showChooser(
        context: Context,
        uri: Uri,
        mimeType: String,
        ruleScope: DefaultAppRuleScope?,
        ruleKey: String?
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
            return OpenResult.NO_HANDLER
        }

        val receiverIntent = Intent(context, ChooserResultReceiver::class.java).apply {
            putExtra(EXTRA_MIME_TYPE, mimeType)
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
            nextRequestCode.getAndIncrement(),
            receiverIntent,
            pendingFlags
        )

        val ownComponents = allHandlers
            .filter { it.activityInfo.packageName == context.packageName }
            .map { ComponentName(it.activityInfo.packageName, it.activityInfo.name) }
            .toTypedArray()

        val chooser = Intent.createChooser(viewIntent, "Open with", pendingIntent.intentSender).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newRawUri(null, uri)
            if (ownComponents.isNotEmpty()) {
                putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, ownComponents)
            }
        }

        return try {
            startActivity(context, chooser)
            OpenResult.STARTED
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start chooser", e)
            showNoAppDialog(context, uri, mimeType)
            OpenResult.FAILED
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
