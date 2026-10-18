package com.dsoft.news_glass

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import kotlin.concurrent.thread

/**
 * Exact-alarm twin of the WorkManager job. ColorOS defers jobs for hours when
 * the phone idles; an exact "allow while idle" alarm still fires, so the
 * feed (and the widget) stay fresh every interval.
 */
object FetchAlarm {
    private fun pending(ctx: Context) = PendingIntent.getBroadcast(
        ctx, 1, Intent(ctx, FetchAlarmReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun intervalMs(ctx: Context) = NewsConfig.load(ctx).intervalMin.coerceAtLeast(15) * 60_000L

    fun isStale(ctx: Context) = System.currentTimeMillis() - NewsRepo.lastUpdated(ctx) >= intervalMs(ctx) - 60_000

    /** Next fetch = last fetch + interval (at least a minute from now). */
    fun schedule(ctx: Context) {
        val at = maxOf(NewsRepo.lastUpdated(ctx) + intervalMs(ctx), System.currentTimeMillis() + 60_000)
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = pending(ctx)
        try {
            if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    /** Fetch off the main thread inside a receiver, then arm the next alarm. */
    fun fetchAsync(receiver: BroadcastReceiver, ctx: Context, onlyIfStale: Boolean) {
        val pending = receiver.goAsync()
        val app = ctx.applicationContext
        thread {
            try {
                if (!onlyIfStale || isStale(app)) {
                    try {
                        FetchWorker.runFetch(app)
                    } catch (_: Exception) {
                    }
                }
                NewsWidget.refreshAll(app)
                schedule(app)
            } finally {
                pending.finish()
            }
        }
    }
}

class FetchAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) = FetchAlarm.fetchAsync(this, ctx, onlyIfStale = false)
}

/** Woken by the shell watchdog after a force-stop, or after boot: re-arm + catch up. */
class KeepAliveReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        thread { runCatching { FetchWorker.schedule(ctx.applicationContext) } }
        FetchAlarm.fetchAsync(this, ctx, onlyIfStale = true)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        thread { runCatching { FetchWorker.schedule(ctx.applicationContext) } }
        FetchAlarm.fetchAsync(this, ctx, onlyIfStale = true)
    }
}