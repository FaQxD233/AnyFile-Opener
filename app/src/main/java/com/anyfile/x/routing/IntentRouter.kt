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

    private val DOCUMENTS_UI_PACKAGES = listOf(
        "com.google.android.documentsui",
        "com.android.documentsui"
    )

    // OEM / app-private roots that look like folders but are useless or non-browsable.
    private val HIDDEN_OR_PRIVATE_SEGMENTS = setOf(
        "datastorage",
        ".datastorage",
        "android_secure",
        "lost.dir",
        "found.000",
        "system volume information"
    )

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
     * Resolves the best containing-folder target and opens it through a system
     * app chooser so the user can pick any installed file manager.
     */
    fun openFolder(context: Context, uri: Uri) {
        try {
            val parent = resolveParentDirectory(context, uri)
            Log.d(TAG, "openFolder source=$uri parent=$parent")

            val folderIntents = when (parent) {
                is ParentDirectory.StorageDocument ->
                    storageDocumentDirectoryIntents(parent.documentId)
                is ParentDirectory.DocumentUri ->
                    genericDirectoryIntents(parent.uri)
                is ParentDirectory.AbsolutePath ->
                    absoluteDirectoryIntents(parent.path)
                null -> emptyList()
            }

            if (folderIntents.isNotEmpty() && launchFileManagerChooser(context, folderIntents)) {
                return
            }

            // Exact parent unavailable: still offer a file-manager chooser at storage root.
            if (launchFileManagerChooser(context, storageRootIntents())) {
                Toast.makeText(
                    context,
                    "Opened storage root; exact folder is unavailable for this source",
                    Toast.LENGTH_LONG
                ).show()
                return
            }

            Toast.makeText(
                context,
                "No file manager found to open the folder",
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

        // Prefer Documents path-like ids first; MediaStore often returns OEM private
        // roots such as ".DataStorage" which are not user-browsable folders.
        if (DocumentsContract.isDocumentUri(context, uri) || isTreeDocumentUri(uri)) {
            resolveDocumentParent(context, uri)?.let { return it }
        }

        queryMediaLocation(context, uri)?.let { return it }
        resolveMediaStoreParent(context, uri)?.let { return it }

        extractEmbeddedAbsolutePath(uri)?.let { absolute ->
            // Embedded path may include the filename; use parent directory.
            val parent = if (looksLikeFilePath(absolute)) {
                File(absolute).parent
            } else {
                absolute
            }
            parent?.let { return absolutePathToParent(it) }
        }

        return null
    }

    private fun isTreeDocumentUri(uri: Uri): Boolean =
        runCatching { DocumentsContract.isTreeUri(uri) }.getOrDefault(false)

    private fun looksLikeFilePath(path: String): Boolean {
        val name = path.substringAfterLast('/')
        return name.contains('.') && !name.startsWith('.')
    }

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

            if (absolute.startsWith("/storage/") || absolute.startsWith("/sdcard/") || File(absolute).exists()) {
                return absolute.trimEnd('/')
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
            val sanitized = sanitizeStorageDocumentId(volume, parentRelative)
            if (uri.authority == EXTERNAL_STORAGE_AUTHORITY ||
                volume == "primary" ||
                volume == "home" ||
                volume.contains('-')
            ) {
                return ParentDirectory.StorageDocument(sanitized)
            }
            return ParentDirectory.DocumentUri(
                buildParentDocumentUri(uri, sanitized)
            )
        }

        // downloads provider: ms:<id> / msf:<id> / plain numeric ids
        if (uri.authority == DOWNLOADS_DOCUMENTS_AUTHORITY) {
            val mediaId = documentId.substringAfter(':', documentId)
            if (mediaId.all { it.isDigit() }) {
                resolveMediaStoreParentById(context, mediaId.toLong())?.let { return it }
            }
            // Common downloads root fallback when the provider hides real paths.
            return ParentDirectory.StorageDocument("primary:Download")
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
                val parentId = sanitizeExistingDocumentId(segments[segments.lastIndex - 1])
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
        if (relative.isBlank()) return volume to ""
        if (relative.all { it.isDigit() }) return null
        if (relative.startsWith("ms", ignoreCase = true) && !relative.contains('/')) return null
        if (relative.startsWith("msf", ignoreCase = true) && !relative.contains('/')) return null
        // documentId points at the file; parent is everything before the last segment.
        val parentRelative = relative.substringBeforeLast('/', missingDelimiterValue = "")
        return volume to parentRelative
    }

    /**
     * OEM private roots like ".DataStorage" are not useful folder destinations and some
     * file managers treat them as mysterious files. Climb to the nearest public folder.
     */
    private fun sanitizeStorageDocumentId(volume: String, relativePath: String): String {
        val volumeKey = when (volume) {
            "home" -> "primary"
            else -> volume
        }
        val segments = relativePath
            .replace('\\', '/')
            .split('/')
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != "." && it != ".." }

        if (segments.isEmpty()) return "$volumeKey:"

        // Drop trailing hidden/private segments until a public path remains.
        val kept = segments.toMutableList()
        while (kept.isNotEmpty() && isNonBrowsableSegment(kept.last())) {
            kept.removeAt(kept.lastIndex)
        }
        // If the whole path was private (e.g. only ".DataStorage"), open volume root.
        if (kept.isEmpty() || kept.all { isNonBrowsableSegment(it) }) {
            return "$volumeKey:"
        }
        // Also strip leading private roots such as ".DataStorage/Download" -> "Download"
        while (kept.isNotEmpty() && isNonBrowsableSegment(kept.first())) {
            kept.removeAt(0)
        }
        if (kept.isEmpty()) return "$volumeKey:"
        return "$volumeKey:${kept.joinToString("/")}"
    }

    private fun sanitizeExistingDocumentId(documentId: String): String {
        if (!documentId.contains(':')) return documentId
        val volume = documentId.substringBefore(':')
        val relative = documentId.substringAfter(':')
        return sanitizeStorageDocumentId(volume, relative)
    }

    private fun isNonBrowsableSegment(segment: String): Boolean {
        val name = segment.lowercase(Locale.ROOT)
        if (name.startsWith(".")) return true
        return name in HIDDEN_OR_PRIVATE_SEGMENTS
    }

    private fun buildParentDocumentUri(sourceUri: Uri, parentId: String): Uri {
        val authority = sourceUri.authority ?: EXTERNAL_STORAGE_AUTHORITY
        return if (isTreeDocumentUri(sourceUri)) {
            DocumentsContract.buildDocumentUriUsingTree(sourceUri, parentId)
        } else {
            DocumentsContract.buildDocumentUri(authority, parentId)
        }
    }

    private fun absoluteDirectoryIntents(path: String): List<Intent> {
        val dir = File(path)
        val normalized = try {
            dir.canonicalFile
        } catch (_: Exception) {
            dir
        }
        absolutePathToDocumentId(normalized.absolutePath)?.let { documentId ->
            return storageDocumentDirectoryIntents(documentId)
        }
        return emptyList()
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
                return sanitizeStorageDocumentId("primary", relative)
            }
        }

        val match = Regex("^/storage/([^/]+)(?:/(.*))?$").matchEntire(path) ?: return null
        val volume = match.groupValues[1]
        if (volume == "emulated" || volume == "self") return null
        val relative = match.groupValues.getOrNull(2).orEmpty()
        return sanitizeStorageDocumentId(volume, relative)
    }

    /**
     * Build public intents that file managers can handle.
     * No setPackage / no URI grants — the system chooser decides the target app.
     */
    private fun storageDocumentDirectoryIntents(documentId: String): List<Intent> {
        val safeId = sanitizeExistingDocumentId(documentId)
        val documentUri =
            DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_AUTHORITY, safeId)
        val treeUri =
            DocumentsContract.buildTreeDocumentUri(EXTERNAL_STORAGE_AUTHORITY, safeId)
        val treeDocUri =
            DocumentsContract.buildDocumentUriUsingTree(treeUri, safeId)
        val rootUri = Uri.parse("content://$EXTERNAL_STORAGE_AUTHORITY/root/primary")
        val isRoot = safeId == "primary:" || safeId == "primary"

        return buildList {
            // Primary: directory VIEW — what most file managers advertise.
            add(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(documentUri, DocumentsContract.Document.MIME_TYPE_DIR)
                    addCategory(Intent.CATEGORY_DEFAULT)
                }
            )
            add(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(treeDocUri, DocumentsContract.Document.MIME_TYPE_DIR)
                    addCategory(Intent.CATEGORY_DEFAULT)
                }
            )
            add(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(documentUri, "resource/folder")
                    addCategory(Intent.CATEGORY_DEFAULT)
                }
            )
            add(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(treeUri, DocumentsContract.Document.MIME_TYPE_DIR)
                    addCategory(Intent.CATEGORY_DEFAULT)
                }
            )
            add(
                Intent("android.provider.action.BROWSE").apply {
                    setDataAndType(documentUri, DocumentsContract.Document.MIME_TYPE_DIR)
                    addCategory(Intent.CATEGORY_DEFAULT)
                }
            )
            add(
                Intent("android.provider.action.BROWSE_DOCUMENT_ROOT").apply {
                    data = if (isRoot) rootUri else treeUri
                    addCategory(Intent.CATEGORY_DEFAULT)
                }
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                add(
                    Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                        putExtra(DocumentsContract.EXTRA_INITIAL_URI, documentUri)
                    }
                )
            }
        }
    }

    private fun genericDirectoryIntents(dirUri: Uri): List<Intent> = buildList {
        add(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(dirUri, DocumentsContract.Document.MIME_TYPE_DIR)
                addCategory(Intent.CATEGORY_DEFAULT)
            }
        )
        add(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(dirUri, "resource/folder")
                addCategory(Intent.CATEGORY_DEFAULT)
            }
        )
        add(
            Intent("android.provider.action.BROWSE").apply {
                setDataAndType(dirUri, DocumentsContract.Document.MIME_TYPE_DIR)
                addCategory(Intent.CATEGORY_DEFAULT)
            }
        )
    }

    private fun storageRootIntents(): List<Intent> {
        val root = Uri.parse("content://$EXTERNAL_STORAGE_AUTHORITY/root/primary")
        val primaryDoc = DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_AUTHORITY, "primary:")
        return buildList {
            add(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(primaryDoc, DocumentsContract.Document.MIME_TYPE_DIR)
                    addCategory(Intent.CATEGORY_DEFAULT)
                }
            )
            add(
                Intent("android.provider.action.BROWSE_DOCUMENT_ROOT").apply {
                    data = root
                    addCategory(Intent.CATEGORY_DEFAULT)
                }
            )
            add(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(root, "vnd.android.document/root")
                    addCategory(Intent.CATEGORY_DEFAULT)
                }
            )
            add(
                Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                }
            )
        }
    }

    /**
     * Shows a system chooser populated with every activity that can handle any of
     * the candidate folder intents. The first intent is the primary target; the rest
     * are attached via EXTRA_INITIAL_INTENTS so more file managers appear.
     */
    private fun launchFileManagerChooser(context: Context, candidates: List<Intent>): Boolean {
        if (candidates.isEmpty()) return false

        val pm = context.packageManager
        val uniqueTargets = LinkedHashMap<ComponentName, Intent>()

        for (candidate in candidates) {
            val matches = try {
                pm.queryIntentActivities(candidate, PackageManager.MATCH_DEFAULT_ONLY)
            } catch (e: Exception) {
                Log.d(TAG, "queryIntentActivities failed for $candidate", e)
                emptyList()
            }
            for (resolve in matches) {
                val pkg = resolve.activityInfo.packageName
                if (pkg == context.packageName) continue
                val target = ComponentName(pkg, resolve.activityInfo.name)
                if (uniqueTargets.containsKey(target)) continue
                uniqueTargets[target] = Intent(candidate).apply {
                    // Bind this candidate intent to the concrete file-manager activity.
                    this.component = target
                    `package` = null
                }
            }
        }

        if (uniqueTargets.isEmpty()) {
            // Nothing advertised support; still try a raw chooser on the primary intent.
            return try {
                val primary = Intent(candidates.first()).apply {
                    addCategory(Intent.CATEGORY_DEFAULT)
                }
                val chooser = Intent.createChooser(primary, "Open folder with")
                startActivity(context, chooser)
                true
            } catch (e: Exception) {
                Log.d(TAG, "Raw folder chooser failed", e)
                false
            }
        }

        val targetList = uniqueTargets.values.toList()
        val primary = targetList.first()
        val extras = targetList.drop(1).toTypedArray()

        return try {
            val chooser = Intent.createChooser(primary, "Open folder with").apply {
                if (extras.isNotEmpty()) {
                    putExtra(Intent.EXTRA_INITIAL_INTENTS, extras)
                }
            }
            startActivity(context, chooser)
            Log.d(TAG, "Started file-manager chooser with ${targetList.size} target(s)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "File-manager chooser failed", e)
            false
        }
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
                            ?.replace('\\', '/')
                            ?.trim('/')
                            ?.trim()
                            .orEmpty()
                        // RELATIVE_PATH already is the containing folder.
                        return@use ParentDirectory.StorageDocument(
                            sanitizeStorageDocumentId("primary", relative)
                        )
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
