package com.anyfile.x.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews
import com.anyfile.x.R

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

            for (browseIntent in intents) {
                try {
                    browseIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(browseIntent)
                    break
                } catch (_: Exception) {
                    // Try the next public intent variant.
                }
            }
        }
    }
}
