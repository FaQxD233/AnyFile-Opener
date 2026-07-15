package com.anyfile.x.routing

import android.content.Context

/**
 * Persists the small amount of state needed to distinguish a real chooser
 * selection from the user cancelling the chooser. Persistence also covers process
 * recreation while the selected target application is in the foreground.
 */
object ChooserRequestStore {
    private const val PREFS_NAME = "chooser_requests"
    private const val STATUS_PENDING = "pending"
    private const val STATUS_CHOSEN = "chosen"

    fun begin(context: Context, requestId: String): Boolean =
        prefs(context).edit().putString(requestId, STATUS_PENDING).commit()

    fun markChosen(context: Context, requestId: String) {
        val preferences = prefs(context)
        if (preferences.contains(requestId)) {
            preferences.edit().putString(requestId, STATUS_CHOSEN).commit()
        }
    }

    /** Returns true only when the system chooser reported a selected component. */
    fun consumeWasChosen(context: Context, requestId: String): Boolean {
        val preferences = prefs(context)
        val wasChosen = preferences.getString(requestId, null) == STATUS_CHOSEN
        preferences.edit().remove(requestId).commit()
        return wasChosen
    }

    fun clear(context: Context, requestId: String?) {
        if (requestId != null) prefs(context).edit().remove(requestId).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
