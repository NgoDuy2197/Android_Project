package com.dsoft.voice_ai

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ActivityCompat

/**
 * Keeps the app alive and the CPU running while the screen is off, so listening,
 * speaking (TTS) and the AI↔AI auto-dialogue keep going in the background.
 *
 * It runs as a foreground service (a persistent notification) and holds a
 * PARTIAL_WAKE_LOCK — that wakelock keeps the CPU on even with the screen off
 * (unlike a screen wakelock). Foreground-service type is `microphone` when the
 * mic permission is granted (needed for background STT on Android 14+), else
 * `specialUse`.
 */
class KeepAliveService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        ensureChannel(this)
        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Voice AI đang chạy nền")
            .setContentText("Giữ chạy để nghe/đọc/tự thoại khi tắt màn hình.")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val micGranted = ActivityCompat.checkSelfPermission(
                this, Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            val type = if (micGranted) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                else ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            }
            try {
                startForeground(NOTIF_ID, notification, type)
            } catch (_: Exception) {
                try {
                    startForeground(NOTIF_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
                } catch (_: Exception) {
                    startForeground(NOTIF_ID, notification)
                }
            }
        } else {
            startForeground(NOTIF_ID, notification)
        }
        acquireLock()
        return START_STICKY
    }

    private fun acquireLock() {
        if (wakeLock?.isHeld == true) return
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "voice_ai:keepalive").apply {
                setReferenceCounted(false)
                acquire(3 * 60 * 60 * 1000L) // 3h safety cap
            }
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {
        }
        wakeLock = null
        super.onDestroy()
    }

    private fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Chạy nền", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "Giữ Voice AI chạy nền khi tắt màn hình." }
            )
        }
    }

    companion object {
        private const val CHANNEL_ID = "voice_ai_keepalive"
        private const val NOTIF_ID = 8231
        private const val ACTION_STOP = "com.dsoft.voice_ai.KEEPALIVE_STOP"

        fun start(ctx: Context) {
            try {
                ctx.startForegroundService(Intent(ctx, KeepAliveService::class.java))
            } catch (_: Exception) {
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
