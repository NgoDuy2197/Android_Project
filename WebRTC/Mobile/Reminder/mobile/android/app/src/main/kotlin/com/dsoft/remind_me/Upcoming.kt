package com.dsoft.remind_me

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * "Sắp đến giờ" notification for reminders due within [WINDOW_MS]: live
 * countdown, visible on the lock screen, removed when the reminder rings.
 * A pre-alarm at (time - 5 min) posts it; reminders already inside the window
 * get it right away when the schedule is rebuilt.
 */
object Upcoming {
    const val WINDOW_MS = 5 * 60_000L
    private const val CHANNEL = "upcoming"
    private const val TAG = "upcoming:"

    private fun channel(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Sắp đến giờ (dưới 5 phút)", NotificationManager.IMPORTANCE_DEFAULT).apply {
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
        )
    }

    fun show(ctx: Context, id: String, r: JSONObject?, at: Long, snooze: Boolean = false) {
        val now = System.currentTimeMillis()
        if (r == null || at <= now) return
        channel(ctx)
        val title = r.optString("title").ifEmpty { "Nhắc nhở" }
        val emoji = r.optString("emoji").ifEmpty { "⏰" }
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(at))
        val open = PendingIntent.getActivity(
            ctx, 7, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setContentTitle("$emoji $title")
            .setContentText((if (snooze) "Báo lại lúc " else "Sắp đến giờ · ") + time +
                r.optString("note").let { if (it.isEmpty()) "" else " · $it" })
            .setWhen(at)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setTimeoutAfter(at - now + 60_000)
            .setContentIntent(open)
            .build()
        runCatching { ctx.getSystemService(NotificationManager::class.java).notify(TAG + id, 0, n) }
    }

    fun cancel(ctx: Context, id: String) {
        runCatching { ctx.getSystemService(NotificationManager::class.java).cancel(TAG + id, 0) }
    }

    fun cancelAll(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        runCatching {
            for (s in nm.activeNotifications) if (s.tag?.startsWith(TAG) == true) nm.cancel(s.tag, s.id)
        }
    }

    private fun pre(ctx: Context, id: String) = PendingIntent.getBroadcast(
        ctx, id.hashCode() + 2,
        Intent(ctx, AlarmReceiver::class.java).setAction(AlarmReceiver.ACTION_PRE).putExtra("id", id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** Shows the notification now if [at] is within 5 minutes, otherwise arms the pre-alarm. */
    fun arm(ctx: Context, id: String, r: JSONObject, at: Long) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        am.cancel(pre(ctx, id))
        val now = System.currentTimeMillis()
        if (at - now <= WINDOW_MS) {
            show(ctx, id, r, at)
            return
        }
        val t = at - WINDOW_MS
        try {
            if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pre(ctx, id))
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pre(ctx, id))
            }
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pre(ctx, id))
        }
    }

    fun disarm(ctx: Context, id: String) {
        ctx.getSystemService(AlarmManager::class.java).cancel(pre(ctx, id))
        cancel(ctx, id)
    }
}
