package com.abk.extension.schedpower

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SchedulerPolicyService : Service() {
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var loopJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())
        loopJob = scope.launch {
            while (true) {
                runCatching { tickOnce() }
                delay(3000)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        loopJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun tickOnce() {
        val bridge = HostBridge(
            resolver = contentResolver,
            authority = "com.abk.kernel.extensionhost",
            extensionId = "sched_power_profile"
        )
        SchedulerPolicyController.applyCurrentConfig(this, bridge)
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.service_channel_name),
                    NotificationManager.IMPORTANCE_MIN
                )
            )
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentTitle(getString(R.string.service_title))
            .setContentText(getString(R.string.service_text))
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "sched_power_extension"
        private const val NOTIFICATION_ID = 1001
    }
}
