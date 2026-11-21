package com.dsoft.proxyspy

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder

/**
 * Keeps the Hub alive while the user is driving the app under test. Without a foreground service
 * Android would eventually freeze or kill ProxySpy, the hooks would lose their socket, and capture
 * would stop exactly when it is needed.
 */
class HookService : Service() {

    private val onHub: () -> Unit = { updateNotification() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTI_ID, buildNotification())
        if (!Hub.start()) {
            // Could not bind any port in the range — tell the user instead of pretending to run.
            updateNotification()
        }
        Hub.addListener(onHub)
        return START_STICKY
    }

    override fun onDestroy() {
        Hub.removeListener(onHub)
        Hub.answerAll()
        Hub.stop()
        super.onDestroy()
    }

    private fun updateNotification() = runCatching {
        getSystemService(android.app.NotificationManager::class.java).notify(NOTI_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP), flags,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, HookService::class.java).setAction(ACTION_STOP), flags,
        )

        val apps = Hub.connections()
        val capturing = apps.count { it.capturing }
        val text = when {
            !Hub.running -> "Không mở được cổng " + com.dsoft.proxyspy.Wire.PORT
            apps.isEmpty() -> "Cổng ${Hub.port} · chưa app nào gắn hook"
            else -> "Cổng ${Hub.port} · $capturing/${apps.size} app đang bắt"
        }

        val b = (if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, App.CH)
        else @Suppress("DEPRECATION") Notification.Builder(this))
            .setContentTitle("ProxySpy — tự soi")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    null as android.graphics.drawable.Icon?, "Dừng", stop
                ).build()
            )
        return b.build()
    }

    companion object {
        const val NOTI_ID = 12
        const val ACTION_STOP = "hook_stop"

        fun start(c: Context) {
            val i = Intent(c, HookService::class.java)
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i)
        }

        fun stop(c: Context) = c.stopService(Intent(c, HookService::class.java))
    }
}
