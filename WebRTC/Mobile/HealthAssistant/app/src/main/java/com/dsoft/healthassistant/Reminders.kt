package com.dsoft.healthassistant

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Calendar

/**
 * Local reminder scheduling: a repeating water nudge and daily meal-logging nudges, delivered via
 * [ReminderReceiver]. Uses inexact alarms (no exact-alarm permission needed). Re-run [reschedule]
 * whenever settings change, the app starts, or the device boots.
 */
object Reminders {
    const val CHANNEL = "ha_reminders"
    const val ACTION = "com.dsoft.healthassistant.REMIND"
    const val EXTRA_KIND = "kind"

    private const val RC_WATER = 2000
    private const val RC_MEAL_BASE = 3000
    private const val MAX_MEALS = 6

    fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val mgr = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL) == null) {
            val ch = NotificationChannel(CHANNEL, tr("Nhắc nhở", "Reminders"), NotificationManager.IMPORTANCE_DEFAULT)
            ch.description = tr("Nhắc uống nước và ghi bữa ăn.", "Water and meal-logging nudges.")
            mgr.createNotificationChannel(ch)
        }
    }

    private fun pending(ctx: Context, rc: Int, kind: String): PendingIntent {
        val i = Intent(ctx, ReminderReceiver::class.java).setAction("$ACTION.$kind.$rc").putExtra(EXTRA_KIND, kind)
        return PendingIntent.getBroadcast(ctx, rc, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun am(ctx: Context) = ctx.getSystemService(AlarmManager::class.java)

    /** Cancel every reminder alarm and re-create the enabled ones from [Prefs]. */
    fun reschedule(ctx: Context) {
        val alarm = am(ctx) ?: return
        ensureChannel(ctx)
        val prefs = Prefs(ctx)

        // clear all known alarms first
        alarm.cancel(pending(ctx, RC_WATER, "water"))
        for (i in 0 until MAX_MEALS) alarm.cancel(pending(ctx, RC_MEAL_BASE + i, "meal"))

        if (prefs.waterRemind) {
            val interval = prefs.waterRemindHours.coerceIn(1, 12) * 3600_000L
            runCatching {
                alarm.setInexactRepeating(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + interval, interval, pending(ctx, RC_WATER, "water"))
            }
        }
        if (prefs.mealRemind) {
            prefs.mealTimes.split(",").map { it.trim() }.filter { it.isNotEmpty() }.take(MAX_MEALS)
                .forEachIndexed { i, t ->
                    val hm = parseTime(t) ?: return@forEachIndexed
                    runCatching {
                        alarm.setInexactRepeating(AlarmManager.RTC_WAKEUP, nextAt(hm.first, hm.second), AlarmManager.INTERVAL_DAY, pending(ctx, RC_MEAL_BASE + i, "meal"))
                    }
                }
        }
    }

    private fun parseTime(s: String): Pair<Int, Int>? {
        val parts = s.split(":")
        val h = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val m = parts.getOrNull(1)?.toIntOrNull() ?: return null
        if (h !in 0..23 || m !in 0..59) return null
        return h to m
    }

    private fun nextAt(hour: Int, minute: Int): Long {
        val c = Calendar.getInstance()
        c.set(Calendar.HOUR_OF_DAY, hour); c.set(Calendar.MINUTE, minute)
        c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        if (c.timeInMillis <= System.currentTimeMillis()) c.add(Calendar.DAY_OF_MONTH, 1)
        return c.timeInMillis
    }

    /** Post a reminder notification for [kind] ("water" | "meal"). Called from the receiver. */
    fun show(ctx: Context, kind: String) {
        I18n.apply(ctx)
        ensureChannel(ctx)
        val mgr = ctx.getSystemService(NotificationManager::class.java) ?: return
        val (title, textBody, icon) = when (kind) {
            "water" -> Triple(tr("Uống nước 💧", "Drink water 💧"), tr("Đã đến giờ uống nước. Giữ đủ nước nhé!", "Time to hydrate. Stay on track!"), R.drawable.ic_water)
            else -> Triple(tr("Ghi bữa ăn 🍚", "Log your meal 🍚"), tr("Đừng quên ghi lại bữa ăn hôm nay.", "Don't forget to log today's meal."), R.drawable.ic_restaurant)
        }
        val tap = PendingIntent.getActivity(
            ctx, kind.hashCode(),
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        @Suppress("DEPRECATION")
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(ctx, CHANNEL) else Notification.Builder(ctx)
        val n: Notification = builder.apply {
            setSmallIcon(icon)
            setContentTitle(title)
            setContentText(textBody)
            setAutoCancel(true)
            setContentIntent(tap)
        }.build()
        runCatching { mgr.notify(if (kind == "water") 1 else 2, n) }
    }
}
