package com.dsoft.dailydiary

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

object Jobs {
    private const val DAILY = "daily_summary"
    private const val UPLOAD = "drive_upload"
    /** Days looked back on each run, so a missed evening (phone off, Doze) is caught up later. */
    private const val LOOKBACK_DAYS = 7L

    fun scheduleDaily(context: Context, reset: Boolean) {
        val wm = WorkManager.getInstance(context)
        if (!Prefs.autoSummary) {
            wm.cancelUniqueWork(DAILY)
            return
        }
        val now = LocalDateTime.now()
        var next = now.toLocalDate().atStartOfDay().plusMinutes(Prefs.summaryMinute.toLong())
        if (!next.isAfter(now)) next = next.plusDays(1)
        val req = PeriodicWorkRequestBuilder<DailyWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(Duration.between(now, next).toMillis(), TimeUnit.MILLISECONDS)
            .build()
        wm.enqueueUniquePeriodicWork(
            DAILY,
            if (reset) ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE else ExistingPeriodicWorkPolicy.KEEP,
            req,
        )
    }

    fun enqueueUpload(context: Context) {
        val req = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(UPLOAD, ExistingWorkPolicy.REPLACE, req)
    }

    /** Summarizes every recent day whose PDF is missing or stale. Returns how many were written. */
    fun summarizePending(): Int {
        val today = LocalDate.now()
        var n = 0
        for (i in 0..LOOKBACK_DAYS) {
            val d = today.minusDays(i)
            if (DiaryStore.needsSummary(d) && Summary.generate(d) != null) n++
        }
        return n
    }
}

class DailyWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val written = Jobs.summarizePending()
        if (Prefs.driveEmail != null && Prefs.driveAutoSync && (written > 0 || Prefs.lastDriveSync == 0L)) {
            Jobs.enqueueUpload(applicationContext)
        }
        return Result.success()
    }
}

class UploadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        // No silent token → user revoked / must re-consent in the Backup screen; nothing to retry.
        val token = Drive.silentToken(applicationContext) ?: return Result.success()
        return try {
            Drive.uploadBackup(applicationContext, token)
            Prefs.lastDriveSync = System.currentTimeMillis()
            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }
}
