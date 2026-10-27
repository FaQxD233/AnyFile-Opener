package com.anyfile.x.tasks

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.anyfile.x.LauncherActivity
import com.anyfile.x.R
import com.anyfile.x.feature.common.formatFileSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps the process alive while [FileTasks] has work, and shows that work in a
 * notification with its progress and a cancel action. It stops itself when idle.
 */
class FileTaskService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observing = false
    private var shownIds: List<Long> = emptyList()
    private var lastUpdate = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(CHANNEL_ID, "File copies", NotificationManager.IMPORTANCE_LOW)
        channel.description = "Progress of saving and preparing files"
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            FileTasks.cancelAll()
            // Started only to deliver the cancel action.
            if (!observing) stopSelf()
            return START_NOT_STICKY
        }
        // Required after startForegroundService(), even when the work already finished.
        startInForeground(buildNotification(FileTasks.tasks.value))
        if (!observing) {
            observing = true
            scope.launch { FileTasks.tasks.collect { render(it) } }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun render(tasks: List<FileTasks.Task>) {
        if (tasks.isEmpty()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        val ids = tasks.map { it.id }
        val now = SystemClock.elapsedRealtime()
        // The system drops notification updates that come faster than a few per second.
        if (ids == shownIds && now - lastUpdate < UPDATE_INTERVAL_MS) return
        shownIds = ids
        lastUpdate = now
        if (canNotify()) {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, buildNotification(tasks))
        }
    }

    private fun buildNotification(tasks: List<FileTasks.Task>): Notification {
        val task = tasks.firstOrNull()
        val total = task?.total
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_save)
            .setContentTitle(task?.let { "${it.kind.verb} ${it.fileName}" } ?: "Finishing")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(openAppIntent())
            .addAction(0, "Cancel", cancelIntent())
        if (task != null) {
            builder.setContentText(
                if (total != null) {
                    "${formatFileSize(task.done)} / ${formatFileSize(total)}"
                } else {
                    formatFileSize(task.done)
                }
            )
        }
        if (tasks.size > 1) builder.setSubText("+${tasks.size - 1} more")
        if (total != null && task != null) {
            builder.setProgress(100, (task.done * 100 / total).toInt().coerceIn(0, 100), false)
        } else {
            builder.setProgress(0, 0, true)
        }
        return builder.build()
    }

    private fun startInForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun canNotify(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, LauncherActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun cancelIntent(): PendingIntent = PendingIntent.getService(
        this,
        0,
        Intent(this, FileTaskService::class.java).setAction(ACTION_CANCEL),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    companion object {
        private const val TAG = "FileTaskService"
        private const val CHANNEL_ID = "file_tasks"
        private const val NOTIFICATION_ID = 1
        private const val UPDATE_INTERVAL_MS = 500L
        private const val ACTION_CANCEL = "com.anyfile.x.action.CANCEL_FILE_TASKS"

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, FileTaskService::class.java))
            } catch (e: Exception) {
                // Refused in the background on Android 12+: the work goes on without keep-alive.
                Log.w(TAG, "Could not start the task service", e)
            }
        }

        /** True when progress notifications still need the runtime permission (Android 13+). */
        fun needsNotificationPermission(context: Context): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
    }
}
