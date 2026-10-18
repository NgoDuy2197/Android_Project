package com.dsoft.bookshelf

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job as CoJob
import kotlinx.coroutines.launch

/**
 * Foreground service (type dataSync) that runs queued jobs one at a time, so long extract / translate / MP3
 * jobs keep going with the screen off. Progress lives in JobManager (persisted after every unit).
 */
class JobService : Service() {
    private var worker: CoJob? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastNotify = 0L

    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(Locales.wrap(newBase))
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_PAUSE) {
            intent.getStringExtra(EXTRA_ID)?.let { JobManager.pause(it) }
        }
        startFg(buildNotification(JobManager.all().firstOrNull { it.status == JobStatus.RUNNING }))
        if (worker?.isActive != true) {
            worker = App.scope.launch(Dispatchers.IO) { loop() }
        }
        return START_NOT_STICKY
    }

    private fun startFg(n: Notification) {
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        try {
            ServiceCompat.startForeground(this, NOTIF_ID, n, type)
        } catch (_: Exception) {
            // Android 12+ may refuse a foreground start from the background; the jobs stay queued.
        }
    }

    private fun loop() {
        acquireWake()
        try {
            while (true) {
                val next = JobManager.nextRunnable() ?: break
                JobManager.clearStop(next.id)
                var job = JobManager.put(next.start(JobManager.now()))
                notifyProgress(job, force = true)
                val ctl = JobCtl { notifyProgress(it, force = false) }
                try {
                    when (job.type) {
                        JobType.EXTRACT -> Extractor.run(this, job, ctl)
                        JobType.TRANSLATE -> Translator.run(this, job, ctl)
                        JobType.AUDIO -> AudioMaker.run(this, job, ctl)
                    }
                } catch (e: StopSignal) {
                    job = JobManager.get(job.id) ?: continue
                    if (job.status != JobStatus.CANCELLED) JobManager.put(job.pause(JobManager.now()))
                } catch (e: ModelMissingError) {
                    // Translation model not downloaded: pause (not error) with a clear message + "Tải mô hình" button.
                    job = JobManager.get(job.id) ?: continue
                    if (job.status != JobStatus.CANCELLED)
                        JobManager.put(job.pause(JobManager.now()).copy(error = getString(R.string.err_model_missing)))
                } catch (e: Throwable) {
                    job = JobManager.get(job.id) ?: continue
                    val msg = when (e) {
                        is UserError -> e.message ?: "?"
                        is QuotaError -> getString(R.string.err_quota, e.message ?: "")
                        is java.net.UnknownHostException, is java.net.SocketTimeoutException, is java.net.ConnectException ->
                            getString(R.string.err_network, e.message ?: "")
                        is OutOfMemoryError -> getString(R.string.err_oom)
                        else -> (e.javaClass.simpleName + ": " + (e.message ?: "")).take(300)
                    }
                    if (job.status != JobStatus.CANCELLED) JobManager.put(job.fail(msg, JobManager.now()))
                } finally {
                    JobManager.clearStop(job.id)
                }
            }
        } finally {
            releaseWake()
            App.main.post {
                if (!JobManager.hasRunnable()) {
                    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    worker = App.scope.launch(Dispatchers.IO) { loop() }
                }
            }
        }
    }

    private fun notifyProgress(j: Job, force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastNotify < 1000) return
        lastNotify = now
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(j))
        } catch (_: Exception) {}
    }

    private fun buildNotification(j: Job?): Notification {
        val open = PendingIntent.getActivity(
            this, 1, Intent(this, JobsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b = NotificationCompat.Builder(this, App.CH_JOBS)
            .setSmallIcon(R.drawable.ic_stat_book)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        if (j == null) {
            b.setContentTitle(getString(R.string.jobs_title)).setContentText(getString(R.string.job_starting)).setProgress(0, 0, true)
        } else {
            b.setContentTitle(j.title)
                .setContentText("${unitLabel(this, j)} · ${j.percent}%" + if (j.message.isNotEmpty()) " · ${j.message}" else "")
                .setProgress(if (j.total > 0) j.total else 0, j.index, j.total <= 0)
            val pause = PendingIntent.getService(
                this, 2, Intent(this, JobService::class.java).setAction(ACTION_PAUSE).putExtra(EXTRA_ID, j.id),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            b.addAction(0, getString(R.string.btn_pause), pause)
        }
        return b.build()
    }

    private fun acquireWake() {
        try {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BookShelf:jobs").apply { acquire(6 * 60 * 60 * 1000L) }
        } catch (_: Exception) {}
    }

    private fun releaseWake() {
        try { wakeLock?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
        wakeLock = null
    }

    override fun onDestroy() {
        releaseWake()
        super.onDestroy()
    }

    companion object {
        const val NOTIF_ID = 42
        const val ACTION_PAUSE = "pause"
        const val EXTRA_ID = "id"

        fun start(ctx: Context) {
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, JobService::class.java))
            } catch (_: Exception) {}
        }

        /** "Trang 3 / 10" / "Lô 3 / 10" / "Đoạn 3 / 10" */
        fun unitLabel(ctx: Context, j: Job): String = ctx.getString(
            when (j.type) {
                JobType.EXTRACT -> R.string.unit_page
                JobType.TRANSLATE -> R.string.unit_batch
                else -> R.string.unit_chunk
            },
            j.progressLabel()
        )
    }
}
