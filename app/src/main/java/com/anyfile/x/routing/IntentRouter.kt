package com.anyfile.x.routing

import android.app.Activity
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.anyfile.x.data.DefaultAppRuleScope
import com.anyfile.x.data.DefaultAppRuleStore
import com.anyfile.x.data.RecentFile
import com.anyfile.x.data.RecentFileStore
import java.io.File
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
    private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
    private const val DOWNLOADS_DOCUMENTS_AUTHORITY = "com.android.providers.downloads.documents"
    private const val MEDIA_DOCUMENTS_AUTHORITY = "com.android.providers.media.documents"

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
     * Shares a file URI via ACTION_SEND while forwarding temporary read access.
     */
    fun share(
        context: Context,
        uri: Uri,
        mimeType: String,
        fileName: String? = null
    ): Boolean {
        val normalizedMime = normalizeIntentMime(mimeType)
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

    /**
     * Opens the containing folder using every recoverable path/document strategy.
     * Falls back to the storage root when the exact parent cannot be exposed.
     */
    fun openFolder(context: Context, uri: Uri) {
        try {
            val parent = resolveParentDirectory(context, uri)
            Log.d(TAG, "openFolder source=$uri parent=$parent")
            if (parent != null && launchParentDirectory(context, parent)) {
                return
            }

            if (openSystemStorageRoot(context)) {
                Toast.makeText(
                    context,
                    "Opened storage root; exact folder is unavailable for this source",
                    Toast.LENGTH_LONG
                ).show()
                return
            }

            Toast.makeText(
                context,
                "Cannot open the containing folder for this file source",
                Toast.LENGTH_SHORT
            ).show()
        } catch (e: Exception) {
            Log.e(TAG, "Error opening folder for $uri", e)
            Toast.makeText(context, "Cannot open the containing folder", Toast.LENGTH_SHORT).show()
        }
    }

    private sealed class ParentDirectory {
        data class AbsolutePath(val path: String) : ParentDirectory()
        data class StorageDocument(val documentId: String) : ParentDirectory()
        data class DocumentUri(val uri: Uri) : ParentDirectory()
    }

    private fun resolveParentDirectory(context: Context, uri: Uri): ParentDirectory? {
        when (uri.scheme?.lowercase(Locale.ROOT)) {
            "file" -> {
                val path = uri.path?.takeIf { it.isNotBlank() } ?: return null
                val parent = File(path).parent ?: return null
                return absolutePathToParent(parent)
            }
            "content" -> Unit
            else -> return null
        }

        queryMediaLocation(context, uri)?.let { return it }

        if (DocumentsContract.isDocumentUri(context, uri) || isTreeDocumentUri(uri)) {
            resolveDocumentParent(context, uri)?.let { return it }
        }

        resolveMediaStoreParent(context, uri)?.let { return it }

        // FileProvider / custom providers sometimes embed a real path in the URI path.
        extractEmbeddedAbsolutePath(uri)?.let { absolute ->
            File(absolute).parent?.let { return absolutePathToParent(it) }
        }

        return null
    }

    private fun isTreeDocumentUri(uri: Uri): Boolean =
        runCatching { DocumentsContract.isTreeUri(uri) }.getOrDefault(false)

    private fun extractEmbeddedAbsolutePath(uri: Uri): String? {
        val candidates = buildList {
            uri.toString().let { add(Uri.decode(it)) }
            uri.path?.let { add(Uri.decode(it)) }
            uri.lastPathSegment?.let { add(Uri.decode(it)) }
            uri.pathSegments.forEach { add(Uri.decode(it)) }
        }

        for (raw in candidates) {
            val cleaned = raw
                .removePrefix("raw:")
                .removePrefix("file://")
                .removePrefix("file:")
            val storageIndex = cleaned.indexOf("/storage/")
            val sdcardIndex = cleaned.indexOf("/sdcard/")
            val absolute = when {
                cleaned.startsWith("/storage/") || cleaned.startsWith("/sdcard/") -> cleaned
                storageIndex >= 0 -> cleaned.substring(storageIndex)
                sdcardIndex >= 0 -> cleaned.substring(sdcardIndex)
                cleaned.startsWith("storage/") -> "/$cleaned"
                cleaned.startsWith("sdcard/") -> "/$cleaned"
                else -> null
            } ?: continue

            // Strip trailing file name if this still looks like a file path with extension.
            val asFile = File(absolute)
            if (asFile.exists() || absolute.startsWith("/storage/") || absolute.startsWith("/sdcard/")) {
                return absolute
            }
        }
        return null
    }

    private fun resolveDocumentParent(context: Context, uri: Uri): ParentDirectory? {
        val documentId = runCatching {
            when {
                DocumentsContract.isDocumentUri(context, uri) ->
                    DocumentsContract.getDocumentId(uri)
                isTreeDocumentUri(uri) ->
                    DocumentsContract.getTreeDocumentId(uri)
                else -> null
            }
        }.getOrNull() ?: return null

        // raw:/storage/emulated/0/Download/file.pdf
        if (documentId.startsWith("raw:", ignoreCase = true)) {
            val absolute = documentId.substringAfter(':')
            File(absolute).parent?.let { return absolutePathToParent(it) }
        }

        // Path-like document ids: primary:Download/a.pdf, home:Documents/a.pdf, UUID:DCIM/a.jpg
        parsePathLikeDocumentId(documentId)?.let { (volume, parentRelative) ->
            val parentId = "$volume:$parentRelative"
            // Prefer externalstorage-style launch; it is the only broadly openable directory form.
            if (uri.authority == EXTERNAL_STORAGE_AUTHORITY ||
                volume == "primary" ||
                volume == "home" ||
                volume.contains('-') // secondary volume UUID
            ) {
                return ParentDirectory.StorageDocument(parentId)
            }
            val parentUri = buildParentDocumentUri(uri, parentId)
            return ParentDirectory.DocumentUri(parentUri)
        }

        // downloads provider: ms:<id> / msf:<id> / plain numeric ids
        if (uri.authority == DOWNLOADS_DOCUMENTS_AUTHORITY) {
            val mediaId = documentId.substringAfter(':', documentId)
            if (mediaId.all { it.isDigit() }) {
                resolveMediaStoreParentById(context, mediaId.toLong())?.let { return it }
            }
        }

        // media documents: image:123 / video:123 / audio:123 / document:123
        if (uri.authority == MEDIA_DOCUMENTS_AUTHORITY && documentId.contains(':')) {
            val type = documentId.substringBefore(':')
            val id = documentId.substringAfter(':')
            if (id.all { it.isDigit() }) {
                val contentUri = when (type) {
                    "image" -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    "video" -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    "audio" -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                    else -> MediaStore.Files.getContentUri("external")
                }
                queryMediaLocation(
                    context,
                    ContentUris.withAppendedId(contentUri, id.toLong())
                )?.let { return it }
            }
        }

        // Tree-backed document path (API 26+), when the provider exposes ancestors.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching {
                DocumentsContract.findDocumentPath(context.contentResolver, uri)
            }.getOrNull()?.path?.takeIf { it.size >= 2 }?.let { segments ->
                val parentId = segments[segments.lastIndex - 1]
                if (uri.authority == EXTERNAL_STORAGE_AUTHORITY || parentId.contains(':')) {
                    return ParentDirectory.StorageDocument(parentId)
                }
                return ParentDirectory.DocumentUri(buildParentDocumentUri(uri, parentId))
            }
        }

        return null
    }

    private fun resolveMediaStoreParent(context: Context, uri: Uri): ParentDirectory? {
        queryMediaLocation(context, uri)?.let { return it }

        // content://media/external/file/<id>, images/media/<id>, etc.
        val last = uri.lastPathSegment ?: return null
        if (!last.all { it.isDigit() }) return null
        return resolveMediaStoreParentById(context, last.toLong())
    }

    private fun resolveMediaStoreParentById(context: Context, id: Long): ParentDirectory? {
        val candidates = buildList {
            add(MediaStore.Files.getContentUri("external"))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.Downloads.EXTERNAL_CONTENT_URI)
            }
            add(MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
            add(MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            add(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)
        }
        for (base in candidates) {
            queryMediaLocation(context, ContentUris.withAppendedId(base, id))?.let { return it }
        }
        return null
    }

    private fun parsePathLikeDocumentId(documentId: String): Pair<String, String>? {
        if (!documentId.contains(':')) return null
        val volume = documentId.substringBefore(':')
        val relative = documentId.substringAfter(':')
        if (volume.isBlank()) return null
        // Opaque numeric / ms-style ids are not filesystem relative paths.
        if (relative.isBlank()) return volume to ""
        if (relative.all { it.isDigit() }) return null
        if (relative.startsWith("ms", ignoreCase = true) && !relative.contains('/')) return null
        if (relative.startsWith("msf", ignoreCase = true) && !relative.contains('/')) return null
        val parentRelative = relative.substringBeforeLast('/', missingDelimiterValue = "")
        return volume to parentRelative
    }

    private fun buildParentDocumentUri(sourceUri: Uri, parentId: String): Uri {
        val authority = sourceUri.authority ?: EXTERNAL_STORAGE_AUTHORITY
        return if (isTreeDocumentUri(sourceUri)) {
            DocumentsContract.buildDocumentUriUsingTree(sourceUri, parentId)
        } else {
            DocumentsContract.buildDocumentUri(authority, parentId)
        }
    }

    private fun launchParentDirectory(context: Context, parent: ParentDirectory): Boolean =
        when (parent) {
            is ParentDirectory.StorageDocument ->
                tryStartAny(context, storageDocumentDirectoryIntents(parent.documentId))
            is ParentDirectory.DocumentUri ->
                tryStartAny(context, genericDirectoryIntents(parent.uri))
            is ParentDirectory.AbsolutePath ->
                launchAbsoluteDirectory(context, parent.path)
        }

    private fun launchAbsoluteDirectory(context: Context, path: String): Boolean {
        val dir = File(path)
        val normalized = try {
            dir.canonicalFile
        } catch (_: Exception) {
            dir
        }

        absolutePathToDocumentId(normalized.absolutePath)?.let { documentId ->
            if (tryStartAny(context, storageDocumentDirectoryIntents(documentId))) return true
        }

        // Avoid Uri.fromFile (FileUriExposedException on modern Android).
        return false
    }

    private fun absolutePathToParent(path: String): ParentDirectory {
        absolutePathToDocumentId(path)?.let { return ParentDirectory.StorageDocument(it) }
        return ParentDirectory.AbsolutePath(path)
    }

    private fun absolutePathToDocumentId(absolutePath: String): String? {
        val path = absolutePath.replace('\\', '/').trimEnd('/')
        val primaryPrefixes = listOf(
            "/storage/emulated/0",
            "/sdcard",
            "/mnt/sdcard"
        )
        for (prefix in primaryPrefixes) {
            if (path == prefix) return "primary:"
            if (path.startsWith("$prefix/")) {
                val relative = path.removePrefix(prefix).trimStart('/')
                return if (relative.isEmpty()) "primary:" else "primary:$relative"
            }
        }

        // /storage/<uuid>/... secondary volumes
        val match = Regex("^/storage/([^/]+)(?:/(.*))?$").matchEntire(path) ?: return null
        val volume = match.groupValues[1]
        if (volume == "emulated" || volume == "self") return null
        val relative = match.groupValues.getOrNull(2).orEmpty()
        return if (relative.isBlank()) "$volume:" else "$volume:$relative"
    }

    /**
     * Launch intents for external-storage style folder document ids.
     * Important: do NOT set FLAG_GRANT_READ_URI_PERMISSION — we do not own these URIs,
     * and granting them causes SecurityException that made every attempt fall through.
     */
    private fun storageDocumentDirectoryIntents(documentId: String): List<Intent> {
        val documentUri =
            DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_AUTHORITY, documentId)
        val treeUri =
            DocumentsContract.buildTreeDocumentUri(EXTERNAL_STORAGE_AUTHORITY, documentId)
        val treeDocUri =
            DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)

        return listOf(
            // Preferred: open folder document in Files/DocumentsUI.
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(documentUri, DocumentsContract.Document.MIME_TYPE_DIR)
            },
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(treeDocUri, DocumentsContract.Document.MIME_TYPE_DIR)
            },
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(documentUri, "resource/folder")
            },
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(documentUri, DocumentsContract.Document.MIME_TYPE_DIR)
                setPackage("com.google.android.documentsui")
            },
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(documentUri, DocumentsContract.Document.MIME_TYPE_DIR)
                setPackage("com.android.documentsui")
            },
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(treeUri, DocumentsContract.Document.MIME_TYPE_DIR)
                setPackage("com.google.android.documentsui")
            },
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(treeUri, DocumentsContract.Document.MIME_TYPE_DIR)
                setPackage("com.android.documentsui")
            },
            Intent("android.provider.action.BROWSE").apply {
                setDataAndType(documentUri, DocumentsContract.Document.MIME_TYPE_DIR)
            },
            Intent("android.provider.action.BROWSE_DOCUMENT_ROOT").apply {
                data = treeUri
            }
        )
    }

    private fun genericDirectoryIntents(dirUri: Uri): List<Intent> = listOf(
        Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(dirUri, DocumentsContract.Document.MIME_TYPE_DIR)
        },
        Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(dirUri, "resource/folder")
        },
        Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(dirUri, DocumentsContract.Document.MIME_TYPE_DIR)
            setPackage("com.google.android.documentsui")
        },
        Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(dirUri, DocumentsContract.Document.MIME_TYPE_DIR)
            setPackage("com.android.documentsui")
        },
        Intent("android.provider.action.BROWSE").apply {
            setDataAndType(dirUri, DocumentsContract.Document.MIME_TYPE_DIR)
        }
    )

    private fun openSystemStorageRoot(context: Context): Boolean {
        val root = Uri.parse("content://$EXTERNAL_STORAGE_AUTHORITY/root/primary")
        val primaryDoc = DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_AUTHORITY, "primary:")
        val intents = listOf(
            Intent("android.provider.action.BROWSE_DOCUMENT_ROOT").apply { data = root },
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(root, "vnd.android.document/root")
            },
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(primaryDoc, DocumentsContract.Document.MIME_TYPE_DIR)
            },
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(primaryDoc, DocumentsContract.Document.MIME_TYPE_DIR)
                setPackage("com.google.android.documentsui")
            },
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(primaryDoc, DocumentsContract.Document.MIME_TYPE_DIR)
                setPackage("com.android.documentsui")
            },
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
        )
        return tryStartAny(context, intents)
    }

    private fun tryStartAny(context: Context, intents: List<Intent>): Boolean {
        for (intent in intents) {
            try {
                // Do not pre-filter with resolveActivity: package visibility can hide
                // DocumentsUI handlers even when startActivity would succeed.
                startActivity(context, Intent(intent))
                Log.d(TAG, "Started folder intent: $intent")
                return true
            } catch (e: Exception) {
                Log.d(TAG, "Folder intent failed: $intent (${e.javaClass.simpleName}: ${e.message})")
            }
        }
        return false
    }

    /**
     * Reads absolute DATA and/or Q+ RELATIVE_PATH to recover a browsable folder location.
     */
    private fun queryMediaLocation(context: Context, uri: Uri): ParentDirectory? {
        val projection = buildList {
            add(MediaStore.MediaColumns.DATA)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.MediaColumns.RELATIVE_PATH)
                add(MediaStore.MediaColumns.DISPLAY_NAME)
            }
        }.toTypedArray()

        return try {
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null

                val dataIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                if (dataIndex >= 0 && !cursor.isNull(dataIndex)) {
                    val data = cursor.getString(dataIndex)
                    if (!data.isNullOrBlank() &&
                        (data.startsWith("/storage/") || data.startsWith("/sdcard/") || File(data).exists())
                    ) {
                        File(data).parent?.let { return@use absolutePathToParent(it) }
                    }
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val relativeIndex =
                        cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                    if (relativeIndex >= 0 && !cursor.isNull(relativeIndex)) {
                        val relative = cursor.getString(relativeIndex)
                            ?.trim('/')
                            ?.trim()
                            .orEmpty()
                        // RELATIVE_PATH is the containing folder, e.g. "Download/" or "DCIM/Camera/".
                        val documentId =
                            if (relative.isEmpty()) "primary:" else "primary:$relative"
                        return@use ParentDirectory.StorageDocument(documentId)
                    }
                }
                null
            }
        } catch (_: Exception) {
            null
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
