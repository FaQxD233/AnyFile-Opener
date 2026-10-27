package com.anyfile.x

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.anyfile.x.data.PrefsManager
import com.anyfile.x.feature.details.DetailsBottomSheet
import com.anyfile.x.feature.launcher.LauncherScreen
import com.anyfile.x.feature.launcher.LauncherViewModel
import com.anyfile.x.feature.openas.OpenAsBottomSheet
import com.anyfile.x.routing.SystemFileManager
import com.anyfile.x.ui.AnyFileOpenerTheme

/**
 * Modernized LauncherActivity using Jetpack Compose.
 * This activity serves as the primary file picker and manual MIME override dashboard.
 */
class LauncherActivity : AppCompatActivity() {

    private val viewModel: LauncherViewModel by viewModels()
    private lateinit var prefs: PrefsManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        prefs = PrefsManager(this)

        setContent {
            AnyFileOpenerTheme {
                LauncherScreen(
                    viewModel = viewModel,
                    prefsManager = prefs,
                    onSettingsClick = {
                        startActivity(Intent(this, SettingsActivity::class.java))
                    },
                    onOpenAsClick = { uri, inspection ->
                        // The home screen has the other actions; the sheet adds the type grid.
                        OpenAsBottomSheet.newInstance(uri, preset = inspection, typesOnly = true)
                            .show(supportFragmentManager, "open_as")
                    },
                    onDetailsClick = { uri, inspection ->
                        DetailsBottomSheet.newInstance(uri, inspection)
                            .show(supportFragmentManager, "details")
                    },
                    onSystemFilesClick = {
                        if (!SystemFileManager.open(this)) {
                            Toast.makeText(this, "System file manager not found", Toast.LENGTH_SHORT).show()
                        }
                    }
                )
            }
        }
    }
}
