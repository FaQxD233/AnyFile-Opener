package com.anyfile.x

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.anyfile.x.data.PrefsManager

class AnyFileApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // Before any screen exists, so XML themes, dialogs and Compose all start in the chosen mode.
        AppCompatDelegate.setDefaultNightMode(PrefsManager(this).themePreference.nightMode)
    }
}
