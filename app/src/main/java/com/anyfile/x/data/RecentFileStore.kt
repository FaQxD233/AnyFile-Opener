package com.anyfile.x.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

val Context.recentFilesDataStore by preferencesDataStore(name = "recent_files")

object RecentFileStore {
    private val RECENT_FILES_KEY = stringPreferencesKey("recent_files_list")
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun addRecentFileAsync(context: Context, file: RecentFile): Boolean {
        val appContext = context.applicationContext
        // Persist permission when possible, but still keep a history entry for temporary URIs.
        acquirePersistentReadPermission(appContext, Uri.parse(file.uri))

        ioScope.launch {
            try {
                addRecentFile(appContext, file)
            } catch (e: Exception) {
                Log.e("RecentFileStore", "Could not save recent file ${file.uri}", e)
            }
        }
        return true
    }

    fun removeRecentFileAsync(context: Context, uri: String) {
        val appContext = context.applicationContext
        ioScope.launch {
            try {
                removeRecentFile(appContext, uri)
            } catch (e: Exception) {
                Log.e("RecentFileStore", "Could not remove recent file $uri", e)
            }
        }
    }

    fun clearRecentFilesAsync(context: Context) {
        val appContext = context.applicationContext
        ioScope.launch {
            try {
                clearRecentFiles(appContext)
            } catch (e: Exception) {
                Log.e("RecentFileStore", "Could not clear recent files", e)
            }
        }
    }

    fun getRecentFiles(context: Context): Flow<List<RecentFile>> {
        return context.recentFilesDataStore.data.map { preferences ->
            val json = preferences[RECENT_FILES_KEY] ?: "[]"
            try {
                Json.decodeFromString<List<RecentFile>>(json)
            } catch (e: Exception) {
                emptyList()
            }
        }
    }

    /** Best-effort check whether a recent URI is still readable. */
    fun isReadable(context: Context, uriString: String): Boolean {
        val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return false
        val resolver = context.applicationContext.contentResolver
        return try {
            when (uri.scheme?.lowercase()) {
                "content" -> {
                    val listed = resolver.query(
                        uri,
                        arrayOf(OpenableColumns.DISPLAY_NAME),
                        null,
                        null,
                        null
                    )?.use { cursor -> cursor.moveToFirst() }
                    if (listed == true) return true
                    resolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false
                }
                "file" -> {
                    val path = uri.path ?: return false
                    java.io.File(path).exists()
                }
                else -> false
            }
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun addRecentFile(context: Context, file: RecentFile) {
        var evictedUris: List<String> = emptyList()
        context.recentFilesDataStore.edit { preferences ->
            val currentJson = preferences[RECENT_FILES_KEY] ?: "[]"
            val currentList = try {
                Json.decodeFromString<List<RecentFile>>(currentJson).toMutableList()
            } catch (e: Exception) {
                mutableListOf()
            }

            currentList.removeAll { it.uri == file.uri }
            currentList.add(0, file)

            val limitedList = currentList.take(10)
            evictedUris = currentList.drop(10).map { it.uri }
            preferences[RECENT_FILES_KEY] = Json.encodeToString(limitedList)
        }

        releasePermissions(context, evictedUris)
    }

    private suspend fun removeRecentFile(context: Context, uri: String) {
        context.recentFilesDataStore.edit { preferences ->
            val currentJson = preferences[RECENT_FILES_KEY] ?: "[]"
            val currentList = try {
                Json.decodeFromString<List<RecentFile>>(currentJson).toMutableList()
            } catch (e: Exception) {
                mutableListOf()
            }
            currentList.removeAll { it.uri == uri }
            preferences[RECENT_FILES_KEY] = Json.encodeToString(currentList)
        }
        releasePermissions(context, listOf(uri))
    }

    private suspend fun clearRecentFiles(context: Context) {
        var uris: List<String> = emptyList()
        context.recentFilesDataStore.edit { preferences ->
            val currentJson = preferences[RECENT_FILES_KEY] ?: "[]"
            uris = try {
                Json.decodeFromString<List<RecentFile>>(currentJson).map { it.uri }
            } catch (e: Exception) {
                emptyList()
            }
            preferences[RECENT_FILES_KEY] = "[]"
        }
        releasePermissions(context, uris)
    }

    private fun releasePermissions(context: Context, uris: List<String>) {
        uris.forEach { uriString ->
            try {
                context.contentResolver.releasePersistableUriPermission(
                    Uri.parse(uriString),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {
                // Non-persistable or already released.
            }
        }
    }

    private fun acquirePersistentReadPermission(context: Context, uri: Uri): Boolean {
        if (uri.scheme != "content") return false
        val resolver = context.contentResolver
        return try {
            val alreadyPersisted = resolver.persistedUriPermissions.any {
                it.uri == uri && it.isReadPermission
            }
            if (!alreadyPersisted) {
                resolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            true
        } catch (_: Exception) {
            false
        }
    }
}
