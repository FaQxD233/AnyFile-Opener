package com.anyfile.x.data

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import com.anyfile.x.ui.ThemePreference

class PrefsManager(context: Context) {
    private val sharedPrefs = context.getSharedPreferences("settings_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_AUTO_OPEN = "auto_open"
        private const val KEY_DEFAULT_MIME = "default_mime"
        private const val KEY_THEME = "theme"
    }

    var autoOpen: Boolean
        get() = sharedPrefs.getBoolean(KEY_AUTO_OPEN, false)
        set(value) = sharedPrefs.edit().putBoolean(KEY_AUTO_OPEN, value).apply()

    var defaultMime: String
        get() = sharedPrefs.getString(KEY_DEFAULT_MIME, "application/octet-stream") ?: "application/octet-stream"
        set(value) = sharedPrefs.edit().putString(KEY_DEFAULT_MIME, value).apply()

    /**
     * Read synchronously at startup; setting it also applies the choice, which
     * recreates the open screens.
     */
    var themePreference: ThemePreference
        get() = sharedPrefs.getString(KEY_THEME, null)
            ?.let { name -> ThemePreference.entries.firstOrNull { it.name == name } }
            ?: ThemePreference.SYSTEM
        set(value) {
            sharedPrefs.edit().putString(KEY_THEME, value.name).apply()
            AppCompatDelegate.setDefaultNightMode(value.nightMode)
        }
}
