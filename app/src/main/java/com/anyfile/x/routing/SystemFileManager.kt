package com.anyfile.x.routing

import android.content.Context
import android.content.Intent
import android.net.Uri

/** Opens the system file manager (DocumentsUI) at the primary storage root. */
object SystemFileManager {

    private const val PRIMARY_ROOT = "content://com.android.externalstorage.documents/root/primary"

    /**
     * Tries public intent variants in order; returns false when none could start. A file
     * picker is no substitute: nothing would receive the file the user picks.
     */
    fun open(context: Context): Boolean {
        val root = Uri.parse(PRIMARY_ROOT)
        val intents = listOf(
            Intent("android.provider.action.BROWSE_DOCUMENT_ROOT").apply { data = root },
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(root, "vnd.android.document/root")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        )

        for (intent in intents) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return true
            } catch (_: Exception) {
                // Try the next public intent variant.
            }
        }
        return false
    }
}
