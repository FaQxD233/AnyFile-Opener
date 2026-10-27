package com.anyfile.x.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.anyfile.x.R
import com.anyfile.x.routing.SystemFileManager

class FolderWidgetProvider : AppWidgetProvider() {

    companion object {
        /** Click broadcast of widgets placed by older versions. */
        private const val LEGACY_ACTION_OPEN_FOLDER = "com.anyfile.x.action.OPEN_FOLDER"
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, FolderShortcutActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        for (appWidgetId in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.widget_folder_shortcut)
            views.setOnClickPendingIntent(R.id.widget_root, pendingIntent)
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == LEGACY_ACTION_OPEN_FOLDER) {
            SystemFileManager.open(context)
            // Rebind the widgets so later taps start the activity directly.
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, FolderWidgetProvider::class.java))
            onUpdate(context, manager, ids)
        }
    }
}
