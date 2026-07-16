package com.anyfile.x.data

import android.content.Context
import android.content.Intent
import android.net.Uri
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
        // Temporary grant may die with the source app, yet the list remains useful for
        // SAF-selected files and same-session reopens.
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

    private suspend fun addRecentFile(context: Context, file: RecentFile) {
        var evictedUris: List<String> = emptyList()
        context.recentFilesDataStore.edit { preferences ->
            val currentJson = preferences[RECENT_FILES_KEY] ?: "[]"
            val currentList = try {
                Json.decodeFromString<List<RecentFile>>(currentJson).toMutableList()
            } catch (e: Exception) {
                mutableListOf()
            }

            // Remove if already exists (to bump to top)
            currentList.removeAll { it.uri == file.uri }
            currentList.add(0, file)

            // Limit to 10
            val limitedList = currentList.take(10)
            evictedUris = currentList.drop(10).map { it.uri }
            preferences[RECENT_FILES_KEY] = Json.encodeToString(limitedList)
        }

        evictedUris.forEach { uriString ->
            try {
                context.contentResolver.releasePersistableUriPermission(
                    Uri.parse(uriString),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {
                // The URI may have come from a non-persistable provider.
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
            // ACTION_SEND and many third-party providers offer temporary access only.
            false
        }
    }
}
