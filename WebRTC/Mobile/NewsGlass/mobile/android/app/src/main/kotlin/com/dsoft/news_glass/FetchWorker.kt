package com.dsoft.news_glass

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** Periodic background fetch (default every hour): RSS → news.json → notify → widget. */
class FetchWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result = try {
        runFetch(applicationContext)
        Result.success()
    } catch (_: Exception) {
        Result.retry()
    }

    companion object {
        private const val WORK = "news_fetch"

        /** Fetch + notify + widget refresh. Returns number of new items. */
        fun runFetch(ctx: Context): Int {
            val (fresh, firstRun) = NewsRepo.fetchAll(ctx)
            // No notification storm on the very first download.
            if (!firstRun && fresh.isNotEmpty() && NewsConfig.load(ctx).notify) Notifier.notifyNew(ctx, fresh)
            NewsWidget.refreshAll(ctx)
            FetchAlarm.schedule(ctx) // next exact fetch = now + interval
            return fresh.size
        }

        fun schedule(ctx: Context) {
            val minutes = NewsConfig.load(ctx).intervalMin.coerceAtLeast(15).toLong()
            val req = PeriodicWorkRequestBuilder<FetchWorker>(minutes, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .addTag("interval_$minutes")
                .build()
            val wm = WorkManager.getInstance(ctx)
            // Replace only when the interval changed, so the timer is not reset on every app start.
            val infos = try {
                wm.getWorkInfosForUniqueWork(WORK).get()
            } catch (_: Exception) {
                emptyList()
            }
            val same = infos.any { !it.state.isFinished && it.tags.contains("interval_$minutes") }
            wm.enqueueUniquePeriodicWork(
                WORK,
                if (same) ExistingPeriodicWorkPolicy.KEEP else ExistingPeriodicWorkPolicy.UPDATE,
                req,
            )
        }
    }
}

object Notifier {
    private const val CHANNEL = "news"
    private const val SUMMARY_ID = 1

    private fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Tin mới", NotificationManager.IMPORTANCE_DEFAULT))
        }
    }

    fun notifyNew(ctx: Context, fresh: List<Item>) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        ensureChannel(ctx)
        val nm = NotificationManagerCompat.from(ctx)
        try {
            if (fresh.size <= 5) {
                // One notification per article; tapping opens it (and marks it read).
                fresh.forEach { item ->
                    val n = NotificationCompat.Builder(ctx, CHANNEL)
                        .setSmallIcon(R.drawable.ic_stat_news)
                        .setContentTitle(item.source)
                        .setContentText(item.title)
                        .setStyle(NotificationCompat.BigTextStyle().bigText(item.title))
                        .setWhen(item.published)
                        .setShowWhen(true)
                        .setAutoCancel(true)
                        .setContentIntent(OpenArticleActivity.pending(ctx, item))
                        .build()
                    nm.notify(item.id.hashCode(), n)
                }
            } else {
                val open = PendingIntent.getActivity(
                    ctx, 0, Intent(ctx, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                val n = NotificationCompat.Builder(ctx, CHANNEL)
                    .setSmallIcon(R.drawable.ic_stat_news)
                    .setContentTitle("Có ${fresh.size} tin mới")
                    .setContentText(fresh.take(3).joinToString(" · ") { it.title })
                    .setAutoCancel(true)
                    .setContentIntent(open)
                    .build()
                nm.notify(SUMMARY_ID, n)
            }
        } catch (_: SecurityException) {
        }
    }
}
