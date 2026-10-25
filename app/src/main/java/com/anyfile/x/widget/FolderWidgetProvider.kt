package com.anyfile.x.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.anyfile.x.R
import com.anyfile.x.routing.SystemFileManager

class FolderWidgetProvider : AppWidgetProvider() {

    companion object {
        private const val ACTION_OPEN_FOLDER = "com.anyfile.x.action.OPEN_FOLDER"
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.widget_folder_shortcut)

            // Fallback intents if the above fails
            val pendingIntent = PendingIntent.getBroadcast(
                context, 0, Intent(context, FolderWidgetProvider::class.java).apply {
                    action = ACTION_OPEN_FOLDER
                }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            views.setOnClickPendingIntent(R.id.widget_root, pendingIntent)
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_OPEN_FOLDER) {
            SystemFileManager.open(context)
        }
    }
}
