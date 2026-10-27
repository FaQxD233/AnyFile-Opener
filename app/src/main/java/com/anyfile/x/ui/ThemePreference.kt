package com.anyfile.x.ui

import androidx.appcompat.app.AppCompatDelegate

/** Light/dark choice, applied through AppCompat so XML themes, dialogs and Compose agree. */
enum class ThemePreference(val label: String, val nightMode: Int) {
    SYSTEM("System default", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM),
    LIGHT("Light", AppCompatDelegate.MODE_NIGHT_NO),
    DARK("Dark", AppCompatDelegate.MODE_NIGHT_YES)
}
