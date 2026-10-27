package com.anyfile.x.widget

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import com.anyfile.x.routing.SystemFileManager

/**
 * Started by the home screen widget. Unlike a broadcast receiver, an activity the user
 * just tapped may start another app's activity, so it opens the file manager and closes.
 */
class FolderShortcutActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!SystemFileManager.open(this)) {
            Toast.makeText(this, "System file manager not found", Toast.LENGTH_SHORT).show()
        }
        finish()
    }
}
