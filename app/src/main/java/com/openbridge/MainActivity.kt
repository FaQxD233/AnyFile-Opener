package com.openbridge

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity

/**
 * Transparent entry-point activity.
 * Receives ACTION_VIEW intents and immediately delegates to [OpenAsBottomSheet].
 * Has no visible UI of its own — the window background is transparent.
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Transparent placeholder — the real UI is the bottom sheet.
        setContentView(View(this))

        if (savedInstanceState != null) {
            // Already showing the sheet after a config change; don't re-add.
            return
        }

        if (intent.action == Intent.ACTION_VIEW) {
            val uri = intent.data
            if (uri != null) {
                OpenAsBottomSheet.newInstance(uri)
                    .show(supportFragmentManager, "openAs")
            } else {
                startLauncher()
            }
        } else {
            startLauncher()
        }
    }

    private fun startLauncher() {
        startActivity(Intent(this, LauncherActivity::class.java))
        finish()
    }
}
