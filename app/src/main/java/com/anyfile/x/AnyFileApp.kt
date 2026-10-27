package com.anyfile.x

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.anyfile.x.data.PrefsManager
import com.anyfile.x.files.ApkInfoReader
import com.anyfile.x.files.FileBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AnyFileApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // Before any screen exists, so XML themes, dialogs and Compose all start in the chosen mode.
        AppCompatDelegate.setDefaultNightMode(PrefsManager(this).themePreference.nightMode)

        // Cached copies used to be pruned only when the next copy was made.
        CoroutineScope(Dispatchers.IO).launch {
            FileBridge.pruneExpired(this@AnyFileApp)
            ApkInfoReader.clearLeftovers(this@AnyFileApp)
        }
    }
}
