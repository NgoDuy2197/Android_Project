package com.dsoft.noti_forward

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * A tiny foreground service whose only job is to keep the app's process alive so
 * the NotificationListenerService and the TTS engine keep working while the app
 * is in the background — aggressive OEM battery managers otherwise kill the
 * process and forwarding silently stops.
 *
 * It shows one low-priority ongoing notification. Toggle it from the config
 * ("Giữ chạy nền"); [start]/[stop] are safe to call repeatedly.
 */
class KeepAliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        ensureChannel(this)
        val notification: Notification =
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Noti Forward đang chạy nền")
                .setContentText("Đang lắng nghe và chuyển tiếp thông báo.")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setOngoing(true)
                .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
        return START_STICKY
    }

    private fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "Chạy nền",
                NotificationManager.IMPORTANCE_MIN,
            ).apply { description = "Giữ ứng dụng chạy nền để chuyển tiếp thông báo." }
            nm.createNotificationChannel(ch)
        }
    }

    companion object {
        private const val CHANNEL_ID = "noti_forward_keepalive"
        private const val NOTIF_ID = 4711
        private const val ACTION_STOP = "com.dsoft.noti_forward.KEEPALIVE_STOP"

        fun start(ctx: Context) {
            val i = Intent(ctx, KeepAliveService::class.java)
            try {
                ctx.startForegroundService(i)
            } catch (_: Exception) {
                // e.g. background-start restrictions; the listener will retry on
                // its next connect.
            }
        }

        fun stop(ctx: Context) {
            try {
                ctx.startService(
                    Intent(ctx, KeepAliveService::class.java).setAction(ACTION_STOP)
                )
            } catch (_: Exception) {
                ctx.stopService(Intent(ctx, KeepAliveService::class.java))
            }
        }
    }
}
