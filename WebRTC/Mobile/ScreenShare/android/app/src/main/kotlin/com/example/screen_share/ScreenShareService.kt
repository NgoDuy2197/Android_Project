package com.example.screen_share

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * Foreground service tối giản cho việc chia sẻ màn hình.
 *
 * Nhiệm vụ duy nhất là giữ tiến trình sống và mang [ServiceInfo] kiểu
 * `mediaProjection` — Android 14+ (API 34) yêu cầu một foreground service như
 * vậy phải đang chạy TRƯỚC khi MediaProjection bắt đầu quay màn hình, nếu không
 * hệ thống sẽ ném lỗi. Service này không tự chạy logic gì.
 *
 * Khi người dùng vuốt app khỏi Recents, [onTaskRemoved] dừng service và giải
 * phóng tài nguyên — tức "thoát app để dừng chia sẻ".
 */
class ScreenShareService : Service() {

    companion object {
        private const val CHANNEL_ID = "screen_share_channel"
        private const val NOTIFICATION_ID = 1101
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        val notification = buildNotification()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForegroundResilient(notification)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        // Không tự khởi động lại nếu hệ thống giết service.
        return START_NOT_STICKY
    }

    /**
     * Cố gắng chạy foreground với kiểu mediaProjection + camera (để chụp ảnh
     * khi app ở nền). Nếu thiếu quyền camera hoặc hệ thống từ chối kiểu kết hợp,
     * lùi về chỉ mediaProjection, rồi cuối cùng là không kèm kiểu — tránh crash.
     */
    private fun startForegroundResilient(notification: Notification) {
        val projection = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        val withCamera = projection or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        try {
            startForeground(NOTIFICATION_ID, notification, withCamera)
            return
        } catch (_: Throwable) {
            // Thiếu quyền camera hoặc thiết bị không cho — thử không kèm camera.
        }
        try {
            startForeground(NOTIFICATION_ID, notification, projection)
            return
        } catch (_: Throwable) {
            // Trường hợp hiếm — vẫn cố chạy foreground tối thiểu.
        }
        try {
            startForeground(NOTIFICATION_ID, notification)
        } catch (_: Throwable) {
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Chia sẻ màn hình",
                NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("ScreenShare")
            .setContentText("Đang chia sẻ màn hình")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .build()
    }
}
