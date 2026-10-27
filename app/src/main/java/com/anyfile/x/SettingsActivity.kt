package com.anyfile.x

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.anyfile.x.data.PrefsManager
import com.anyfile.x.feature.settings.SettingsScreen
import com.anyfile.x.ui.AnyFileOpenerTheme

/** An AppCompat activity so that changing the theme recreates it in the chosen mode. */
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val prefs = PrefsManager(this)

        setContent {
            AnyFileOpenerTheme {
                SettingsScreen(
                    prefsManager = prefs,
                    onBackClick = { finish() }
                )
            }
        }
    }
}
