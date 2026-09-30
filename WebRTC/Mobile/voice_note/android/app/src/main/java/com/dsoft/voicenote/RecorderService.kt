package com.dsoft.voicenote

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.IOException

/**
 * Foreground service: mic -> Vosk -> daily transcript file.
 * Any failure (mic busy, model broken, disk error...) is caught, reported in the
 * notification and retried with exponential backoff; the service never dies on its own.
 */
class RecorderService : Service() {
    @Volatile private var stopped = false
    @Volatile private var reload = false
    private val sleepLock = Object()
    private var worker: Thread? = null
    private var model: Model? = null
    private var modelUrl: String? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastText = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel(this)
        runCatching { LibVosk.setLogLevel(LogLevel.WARNINGS) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Prefs(this).wantRunning = false
                shutdown()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RELOAD -> {
                reload = true
                wake()
            }
        }
        if (!goForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (worker?.isAlive != true) {
            stopped = false
            worker = Thread(::loop, "voicenote-worker").also { it.start() }
        }
        isRunning = true
        return START_STICKY
    }

    override fun onDestroy() {
        shutdown()
        isRunning = false
        status = "Đã dừng"
        super.onDestroy()
    }

    private fun goForeground(): Boolean {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            notifyTapToStart(this)
            return false
        }
        return try {
            val n = buildNotification()
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIF_ID, n)
            }
            true
        } catch (t: Throwable) {
            // e.g. Android 14+ refusing a mic service started from the background.
            Log.e(TAG, "startForeground failed", t)
            notifyTapToStart(this)
            false
        }
    }

    private fun shutdown() {
        stopped = true
        wake()
        worker?.let { runCatching { it.join(2000) } }
        if (worker?.isAlive != true) {
            runCatching { model?.close() }
            model = null
            modelUrl = null
        }
        worker = null
    }

    // ---- worker thread -------------------------------------------------------

    private fun loop() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VoiceNote:rec").apply {
            setReferenceCounted(false)
            acquire()
        }
        var backoff = 2000L
        try {
            while (!stopped) {
                try {
                    reload = false
                    val prefs = Prefs(this)
                    recognize(loadModel(prefs.modelUrl), prefs)
                    backoff = 2000L
                } catch (t: Throwable) {
                    if (stopped) break
                    Log.e(TAG, "worker error", t)
                    setStatus("Lỗi: ${t.message ?: t.javaClass.simpleName}. Thử lại sau ${backoff / 1000}s")
                    sleep(backoff)
                    backoff = (backoff * 2).coerceAtMost(60_000)
                }
            }
        } finally {
            runCatching { wakeLock?.release() }
        }
    }

    private fun loadModel(url: String): Model {
        model?.let { if (modelUrl == url) return it }
        runCatching { model?.close() }
        model = null
        val mm = ModelManager(filesDir, cacheDir)
        val dir = mm.ensure(url) { setStatus(it) }
        setStatus("Đang nạp model…")
        return try {
            Model(dir.absolutePath).also { model = it; modelUrl = url }
        } catch (e: Throwable) {
            mm.invalidate() // corrupted model -> re-download on next attempt
            throw e
        }
    }

    private fun recognize(model: Model, prefs: Prefs) {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) throw IOException("Thiết bị không hỗ trợ ghi $SAMPLE_RATE Hz")
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, SAMPLE_RATE * 2) // >= 1 s of audio
            )
        } catch (e: SecurityException) {
            throw IOException("Chưa cấp quyền micro")
        }
        val writer = TranscriptWriter(transcriptDir(this), prefs.timeFormat)
        val seg = Segmenter(prefs.pauseMs) { start, text ->
            writer.write(start, text)
            lastText = text
            updateNotification()
        }
        val recognizer = try {
            Recognizer(model, SAMPLE_RATE.toFloat())
        } catch (e: Throwable) {
            rec.release()
            throw e
        }
        try {
            if (rec.state != AudioRecord.STATE_INITIALIZED) throw IOException("Không mở được micro")
            rec.startRecording()
            if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) throw IOException("Micro đang bị ứng dụng khác dùng")
            setStatus("Đang nghe…")
            val buf = ShortArray(SAMPLE_RATE / 5) // 200 ms
            var emptyReads = 0
            while (!stopped && !reload) {
                val n = rec.read(buf, 0, buf.size)
                if (n < 0) throw IOException("Lỗi đọc micro ($n)")
                if (n == 0) {
                    if (++emptyReads > 50) throw IOException("Micro không trả dữ liệu")
                    Thread.sleep(20)
                    continue
                }
                emptyReads = 0
                val now = System.currentTimeMillis()
                if (recognizer.acceptWaveForm(buf, n)) {
                    safe { seg.onFinal(field(recognizer.result, "text"), now) }
                } else {
                    seg.onPartial(field(recognizer.partialResult, "partial"), now)
                }
                safe { seg.tick(now) }
            }
            safe { seg.onFinal(field(recognizer.finalResult, "text"), System.currentTimeMillis()) }
        } finally {
            safe { seg.flush() }
            runCatching { rec.stop() }
            rec.release()
            recognizer.close()
        }
    }

    /** Disk errors must not kill recognition: log, show, keep the text pending. */
    private inline fun safe(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            Log.e(TAG, "write failed", e)
            setStatus("Lỗi ghi file: ${e.message}")
        }
    }

    private fun field(json: String, key: String) =
        runCatching { JSONObject(json).optString(key) }.getOrDefault("")

    private fun sleep(ms: Long) = synchronized(sleepLock) {
        if (!stopped) runCatching { sleepLock.wait(ms) }
    }

    private fun wake() = synchronized(sleepLock) { sleepLock.notifyAll() }

    // ---- notification --------------------------------------------------------

    private fun setStatus(s: String) {
        if (status == s) return
        status = s
        updateNotification()
    }

    private fun updateNotification() {
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification()) }
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, RecorderService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return builder(this)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(status)
            .setContentText(lastText.ifEmpty { "Đang ghi âm → text" })
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Dừng", stop).build())
            .build()
    }

    companion object {
        private const val TAG = "VoiceNote"
        private const val CHANNEL = "rec"
        private const val NOTIF_ID = 1
        private const val NOTIF_TAP_ID = 2
        private const val SAMPLE_RATE = 16000
        private const val ACTION_STOP = "com.dsoft.voicenote.STOP"
        private const val ACTION_RELOAD = "com.dsoft.voicenote.RELOAD"
        const val EXTRA_START = "start"

        @Volatile var status = "Đã dừng"
            private set
        @Volatile var isRunning = false
            private set

        fun transcriptDir(ctx: Context): File =
            ctx.getExternalFilesDir("transcripts") ?: File(ctx.filesDir, "transcripts")

        fun start(ctx: Context) {
            Prefs(ctx).wantRunning = true
            val i = Intent(ctx, RecorderService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            Prefs(ctx).wantRunning = false
            if (isRunning) ctx.startService(Intent(ctx, RecorderService::class.java).setAction(ACTION_STOP))
        }

        fun reload(ctx: Context) {
            if (isRunning) ctx.startService(Intent(ctx, RecorderService::class.java).setAction(ACTION_RELOAD))
        }

        /** Fallback when the OS forbids starting the mic in background: one tap starts it. */
        fun notifyTapToStart(ctx: Context) {
            createChannel(ctx)
            val pi = PendingIntent.getActivity(
                ctx, 2,
                Intent(ctx, MainActivity::class.java).putExtra(EXTRA_START, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val n = builder(ctx)
                .setSmallIcon(R.drawable.ic_mic)
                .setContentTitle("VoiceNote chưa chạy")
                .setContentText("Chạm để bắt đầu ghi âm")
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build()
            runCatching { ctx.getSystemService(NotificationManager::class.java).notify(NOTIF_TAP_ID, n) }
        }

        private fun createChannel(ctx: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val ch = NotificationChannel(CHANNEL, "Ghi âm", NotificationManager.IMPORTANCE_LOW)
            ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }

        @Suppress("DEPRECATION")
        private fun builder(ctx: Context): Notification.Builder =
            if (Build.VERSION.SDK_INT >= 26) Notification.Builder(ctx, CHANNEL) else Notification.Builder(ctx)
    }
}
