package com.anyfile.x

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.anyfile.x.data.PrefsManager
import com.anyfile.x.feature.inspect.InspectBottomSheet
import com.anyfile.x.feature.launcher.LauncherScreen
import com.anyfile.x.feature.launcher.LauncherViewModel
import com.anyfile.x.feature.openas.OpenAsBottomSheet
import com.anyfile.x.ui.AnyFileOpenerTheme
import com.anyfile.x.ui.ThemePreference

/**
 * Modernized LauncherActivity using Jetpack Compose.
 * This activity serves as the primary file picker and manual MIME override dashboard.
 */
class LauncherActivity : AppCompatActivity() {

    private val viewModel: LauncherViewModel by viewModels()
    private lateinit var prefs: PrefsManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = PrefsManager(this)

        setContent {
            val themePref by prefs.themePreference.collectAsStateWithLifecycle(ThemePreference.SYSTEM_DEFAULT)
            
            AnyFileOpenerTheme(themePreference = themePref) {
                LauncherScreen(
                    viewModel = viewModel,
                    prefsManager = prefs,
                    onSettingsClick = {
                        startActivity(Intent(this, SettingsActivity::class.java))
                    },
                    onOpenAsClick = { uri ->
                        OpenAsBottomSheet.newInstance(uri)
                            .show(supportFragmentManager, "open_as")
                    },
                    onInspectClick = { uri ->
                        InspectBottomSheet.newInstance(uri)
                            .show(supportFragmentManager, "inspect")
                    },
                    onSystemFilesClick = {
                        openSystemFileManager()
                    }
                )
            }
        }
    }

    /**
     * Attempts to open the Android System File Manager (DocumentsUI) directly.
     * This provides a better experience than a simple ACTION_GET_CONTENT.
     */
    private fun openSystemFileManager() {
        val root = Uri.parse("content://com.android.externalstorage.documents/root/primary")
        val intents = listOf(
            Intent("android.provider.action.BROWSE_DOCUMENT_ROOT").apply { data = root },
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(root, "vnd.android.document/root")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
        )

        var started = false
        for (intent in intents) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                started = true
                break
            } catch (_: Exception) {
                // Try the next public intent variant.
            }
        }

        if (!started) {
            Toast.makeText(this, "System file manager not found", Toast.LENGTH_SHORT).show()
        }
    }
}
