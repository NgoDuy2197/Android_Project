package com.dsoft.remind_me

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Calendar

/**
 * Owns the alarm schedule. reminders.json is edited by the app; here we
 * compute each reminder's next time, write it back as `nextFire` and
 * register one AlarmManager alarm-clock per reminder.
 */
object Scheduler {
    private val lock = Any()
    private const val LATE_RING_MS = 5 * 60_000L

    private fun file(ctx: Context) = File(ctx.filesDir, "reminders.json")

    fun load(ctx: Context): JSONObject = synchronized(lock) {
        val f = file(ctx)
        if (!f.exists()) return JSONObject().put("reminders", JSONArray())
        return try {
            JSONObject(f.readText())
        } catch (_: Exception) {
            JSONObject().put("reminders", JSONArray())
        }
    }

    private fun save(ctx: Context, root: JSONObject) {
        val tmp = File(ctx.filesDir, "reminders.json.tmp")
        tmp.writeText(root.toString())
        tmp.renameTo(file(ctx))
    }

    fun find(ctx: Context, id: String): JSONObject? {
        val arr = load(ctx).optJSONArray("reminders") ?: return null
        for (i in 0 until arr.length()) if (arr.getJSONObject(i).optString("id") == id) return arr.getJSONObject(i)
        return null
    }

    /** Next ring time after [now] in ms, or 0 when there is none. */
    fun next(r: JSONObject, now: Long): Long {
        when (r.optString("type")) {
            "once" -> {
                val at = r.optLong("at")
                return if (at > now) at else 0
            }
            "countdown" -> {
                val start = r.optLong("startedAt")
                val t = start + r.optLong("durationSec") * 1000
                return if (start > 0 && t > now) t else 0
            }
            "repeat" -> {
                val anchor = r.optLong("anchor")
                if (anchor <= 0) return 0
                if (anchor > now) return anchor
                val every = r.optInt("every", 1).coerceAtLeast(1)
                val fixed = when (r.optString("unit")) {
                    "minute" -> 60_000L
                    "hour" -> 3_600_000L
                    else -> 0L
                }
                if (fixed > 0) {
                    val period = fixed * every
                    val k = (now - anchor) / period + 1
                    return anchor + k * period
                }
                // Calendar maths for day/week/month (keeps wall-clock time, clamps day 31 → 30/28).
                val field = when (r.optString("unit")) {
                    "week" -> Calendar.WEEK_OF_YEAR
                    "month" -> Calendar.MONTH
                    else -> Calendar.DAY_OF_YEAR
                }
                val approxMs = when (field) {
                    Calendar.WEEK_OF_YEAR -> 7 * 86_400_000L
                    Calendar.MONTH -> 28 * 86_400_000L
                    else -> 86_400_000L
                } * every
                var n = ((now - anchor) / approxMs - 2).coerceAtLeast(0)
                while (true) {
                    val c = Calendar.getInstance().apply {
                        timeInMillis = anchor
                        add(field, (n * every).toInt())
                    }
                    if (c.timeInMillis > now) return c.timeInMillis
                    n++
                }
            }
        }
        return 0
    }

    private fun pending(ctx: Context, id: String, snooze: Boolean): PendingIntent {
        val i = Intent(ctx, AlarmReceiver::class.java)
            .setAction(if (snooze) AlarmReceiver.ACTION_SNOOZE_FIRE else AlarmReceiver.ACTION_FIRE)
            .putExtra("id", id)
        return PendingIntent.getBroadcast(
            ctx, id.hashCode() + if (snooze) 1 else 0, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun schedule(ctx: Context, id: String, at: Long, snooze: Boolean = false) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val fire = pending(ctx, id, snooze)
        val show = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        try {
            if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
                // Alarm-clock alarms are exact and exempt from Doze.
                am.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), fire)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, fire)
            }
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, fire)
        }
    }

    private fun cancel(ctx: Context, id: String) {
        ctx.getSystemService(AlarmManager::class.java).cancel(pending(ctx, id, false))
    }

    /**
     * Recomputes and registers every alarm (app save, boot, time change).
     * Returns how many reminders were due but never rang (app was force-stopped).
     */
    fun rescheduleAll(ctx: Context): Int = synchronized(lock) {
        val root = load(ctx)
        val arr = root.optJSONArray("reminders") ?: JSONArray()
        val now = System.currentTimeMillis()
        var missed = 0
        for (i in 0 until arr.length()) {
            val r = arr.getJSONObject(i)
            val id = r.optString("id")
            val due = r.optLong("nextFire")
            if (r.optBoolean("enabled", true) && due in 1 until now - 60_000 && r.optLong("lastFired") < due) {
                r.put("lastFired", due) // handle each miss once
                if (now - due <= LATE_RING_MS) {
                    // Woken shortly after the time (watchdog / app opened): ring now.
                    schedule(ctx, id, now + 1500, snooze = true)
                } else {
                    missed++
                }
                if (r.optString("type") != "repeat") r.put("enabled", false)
            }
            cancel(ctx, id)
            val nf = if (r.optBoolean("enabled", true)) next(r, now) else 0
            r.put("nextFire", nf)
            if (nf > 0) schedule(ctx, id, nf)
        }
        save(ctx, root)
        missed
    }

    /** After a reminder rang: one-shots switch off, repeats move to their next time. */
    fun onFired(ctx: Context, id: String): Unit = synchronized(lock) {
        val root = load(ctx)
        val arr = root.optJSONArray("reminders") ?: return
        for (i in 0 until arr.length()) {
            val r = arr.getJSONObject(i)
            if (r.optString("id") != id) continue
            r.put("lastFired", System.currentTimeMillis())
            if (r.optString("type") == "repeat") {
                val nf = next(r, System.currentTimeMillis() + 1000)
                r.put("nextFire", nf)
                if (nf > 0) schedule(ctx, id, nf)
            } else {
                r.put("enabled", false)
                r.put("nextFire", 0)
            }
        }
        save(ctx, root)
        Unit
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getStringExtra("id") ?: return
        if (intent.action == ACTION_FIRE) Scheduler.onFired(ctx, id)
        AlarmService.ring(ctx, id)
    }

    companion object {
        const val ACTION_FIRE = "com.dsoft.remind_me.FIRE"
        const val ACTION_SNOOZE_FIRE = "com.dsoft.remind_me.SNOOZE_FIRE"
    }
}

/** Sent by the shell watchdog after ColorOS force-stopped the app: alarms are gone, re-register. */
class KeepAliveReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Scheduler.rescheduleAll(ctx)
    }
}

/** Alarms are wiped on reboot / time change: register them again. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Scheduler.rescheduleAll(ctx)
    }
}
