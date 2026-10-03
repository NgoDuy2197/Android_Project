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
import android.os.Handler
import android.os.Looper
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Foreground service: mic -> recognizer -> daily transcript file.
 * Engines: "google" = the phone's SpeechRecognizer (online, accurate, like voice_ai),
 * "vosk" = fully offline.
 * Any failure (mic busy, model broken, disk error...) is caught, reported in the
 * app's status line and retried with exponential backoff; the service never dies on its own.
 */
class RecorderService : Service() {
    @Volatile private var stopped = false
    @Volatile private var reload = false
    private val sleepLock = Object()
    private var worker: Thread? = null
    private var model: Model? = null
    private var modelUrl: String? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val main = Handler(Looper.getMainLooper())
    private var engineKind: String? = null
    private var stt: SystemStt? = null
    private var sttSeg: Segmenter? = null
    private val sttTick = object : Runnable {
        override fun run() {
            safe { sttSeg?.tick(System.currentTimeMillis()) }
            main.postDelayed(this, 300)
        }
    }

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
            ACTION_RELOAD -> if (isRunning) stopEngine()
        }
        if (!goForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!isRunning) beginSession()
        startEngine()
        isRunning = true
        return START_STICKY
    }

    /** Starts the configured engine (no-op if it is already running). */
    private fun startEngine() {
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VoiceNote:rec").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
        val prefs = Prefs(this)
        engineKind = prefs.engine
        if (prefs.engine == "google") {
            if (stt != null) return
            val writer = TranscriptWriter(prefs.timeFormat, ::fileForEntry)
            val seg = Segmenter(prefs.pauseMs) { start, text -> addEntry(Entry(writer.write(start, text), text)) }
            sttSeg = seg
            val locale = if (prefs.language == "en") "en-US" else "vi-VN"
            stt = SystemStt(this, locale, object : SystemStt.Callback {
                override fun onStatus(s: String) = setStatus(s)
                override fun onSpeechStart() = seg.onPartial("…", System.currentTimeMillis())
                override fun onPartial(text: String) {
                    partial = text
                    if (text.isNotBlank()) seg.onPartial(text, System.currentTimeMillis())
                }
                override fun onFinal(text: String) = safe { seg.onFinal(text, System.currentTimeMillis()) }
                override fun onLevel(level: Float) {
                    RecorderService.level = level
                }
            }).also { it.start() }
            main.removeCallbacks(sttTick)
            main.post(sttTick)
            setStatus("Đang khởi động nhận dạng Google…")
        } else if (worker?.isAlive != true) {
            stopped = false
            worker = Thread(::loop, "voicenote-worker").also { it.start() }
        }
    }

    private fun stopEngine() {
        stt?.stop()
        stt = null
        main.removeCallbacks(sttTick)
        sttSeg?.let { seg -> safe { seg.flush() } }
        sttSeg = null
        shutdown()
        partial = ""
        level = 0f
    }

    override fun onDestroy() {
        stopEngine()
        runCatching { wakeLock?.release() }
        wakeLock = null
        isRunning = false
        status = "Đã dừng"
        super.onDestroy()
    }

    /**
     * File for an entry: "mỗi ngày" -> transcript_yyyy-MM-dd.txt; "mỗi phiên" -> one
     * file per recording session (named by its first entry), split again at midnight.
     * The file is only created on the first entry, so silent sessions leave nothing.
     */
    private fun fileForEntry(startMs: Long): File {
        val dir = transcriptDir(this)
        if (Prefs(this).fileMode == "day") {
            return File(dir, Transcripts.dayFileName(startMs)).also { sessionFile = it }
        }
        val day = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(startMs))
        val cur = sessionFile
        if (cur != null && cur.exists() && sessionDay == day) return cur
        var f = File(dir, Transcripts.sessionFileName(startMs))
        var n = 2
        while (f.exists()) f = File(dir, Transcripts.sessionFileName(startMs).removeSuffix(".txt") + " ($n).txt").also { n++ }
        sessionFile = f
        sessionDay = day
        return f
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
        var backoff = 2000L
        run {
            while (!stopped) {
                try {
                    reload = false
                    val prefs = Prefs(this)
                    recognize(loadModel(prefs.modelUrl()), prefs)
                    backoff = 2000L
                } catch (t: Throwable) {
                    if (stopped) break
                    Log.e(TAG, "worker error", t)
                    setStatus("Lỗi: ${t.message ?: t.javaClass.simpleName}. Thử lại sau ${backoff / 1000}s")
                    sleep(backoff)
                    backoff = (backoff * 2).coerceAtMost(60_000)
                }
            }
        }
    }

    private fun loadModel(url: String): Model {
        model?.let { if (modelUrl == url) return it }
        runCatching { model?.close() }
        model = null
        val mm = ModelManager(filesDir, cacheDir, url)
        val dir = mm.ensure { setStatus(it) }
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
        val writer = TranscriptWriter(prefs.timeFormat, ::fileForEntry)
        val seg = Segmenter(prefs.pauseMs) { start, text -> addEntry(Entry(writer.write(start, text), text)) }
        val recognizer = try {
            Recognizer(model, SAMPLE_RATE.toFloat()).apply { setWords(true) }
        } catch (e: Throwable) {
            rec.release()
            throw e
        }
        try {
            if (rec.state != AudioRecord.STATE_INITIALIZED) throw IOException("Không mở được micro")
            rec.startRecording()
            if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) throw IOException("Micro đang bị ứng dụng khác dùng")
            setStatus("Đang nghe (${Prefs.LANGS[prefs.language]})…")
            val minConf = prefs.minConfidence
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
                var sum = 0.0
                for (i in 0 until n) sum += buf[i].toDouble() * buf[i]
                val db = 10 * kotlin.math.log10(sum / n / (32768.0 * 32768.0) + 1e-12)
                level = ((db + 60) / 60).toFloat().coerceIn(0f, 1f) // -60..0 dBFS
                if (recognizer.acceptWaveForm(buf, n)) {
                    safe { seg.onFinal(finalText(recognizer.result, minConf), now) }
                    partial = ""
                } else {
                    partial = field(recognizer.partialResult, "partial")
                    seg.onPartial(partial, now)
                }
                safe { seg.tick(now) }
            }
            safe { seg.onFinal(finalText(recognizer.finalResult, minConf), System.currentTimeMillis()) }
        } finally {
            partial = ""
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

    /** Text of a final result, or "" when it looks like noise (low word confidence). */
    private fun finalText(json: String, minConf: Float): String = runCatching {
        val o = JSONObject(json)
        val text = o.optString("text")
        val words = o.optJSONArray("result")
        if (text.isEmpty() || words == null || words.length() == 0) return@runCatching text
        var sum = 0.0
        for (i in 0 until words.length()) sum += words.getJSONObject(i).optDouble("conf", 1.0)
        val mean = sum / words.length()
        // Background noise mostly decodes to 1-2 stray words: demand more for those.
        val need = if (words.length() <= 2) minOf(0.95, minConf + 0.25) else minConf.toDouble()
        if (mean < need) {
            Log.i(TAG, "dropped (conf %.2f): %s".format(mean, text))
            ""
        } else text
    }.getOrDefault("")

    private fun sleep(ms: Long) = synchronized(sleepLock) {
        if (!stopped) runCatching { sleepLock.wait(ms) }
    }

    private fun wake() = synchronized(sleepLock) { sleepLock.notifyAll() }

    // ---- notification --------------------------------------------------------

    private fun setStatus(s: String) {
        status = s
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, RecorderService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        // Required by Android for background mic use; kept as quiet as the OS allows:
        // min-importance channel (no status-bar icon), no content, and since the app
        // never asks for POST_NOTIFICATIONS it is not shown at all on Android 13+.
        @Suppress("DEPRECATION")
        return builder(this)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("VoiceNote")
            .setOngoing(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setPriority(Notification.PRIORITY_MIN)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Dừng", stop).build())
            .build()
    }

    companion object {
        private const val TAG = "VoiceNote"
        private const val CHANNEL = "rec_quiet"
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
        /** What is being said right now (not yet final); shown live in the UI. */
        @Volatile var partial = ""
            private set
        /** Mic loudness 0..1 for the UI meter. */
        @Volatile var level = 0f
            private set

        /** Start time of the current recording session (0 when stopped). */
        @Volatile var sessionStart = 0L
            private set
        /** File the current session writes to (null until the first entry). */
        @Volatile var sessionFile: File? = null
            private set
        private var sessionDay = ""
        private val sessionEntries = ArrayList<Entry>()
        /** Bumped on every new entry, so the UI only copies the list when it changed. */
        @Volatile var entriesVersion = 0
            private set

        fun entries(): List<Entry> = synchronized(sessionEntries) { ArrayList(sessionEntries) }

        private fun beginSession() {
            sessionStart = System.currentTimeMillis()
            sessionFile = null
            sessionDay = ""
            synchronized(sessionEntries) { sessionEntries.clear() }
            entriesVersion++
        }

        private fun addEntry(e: Entry) {
            synchronized(sessionEntries) { sessionEntries.add(e) }
            entriesVersion++
        }

        fun transcriptDir(ctx: Context): File = Transcripts.dir(ctx)

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
            val nm = ctx.getSystemService(NotificationManager::class.java)
            runCatching { nm.deleteNotificationChannel("rec") } // v1 channel showed content
            val ch = NotificationChannel(CHANNEL, "Ghi âm nền", NotificationManager.IMPORTANCE_MIN).apply {
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            }
            nm.createNotificationChannel(ch)
        }

        @Suppress("DEPRECATION")
        private fun builder(ctx: Context): Notification.Builder =
            if (Build.VERSION.SDK_INT >= 26) Notification.Builder(ctx, CHANNEL) else Notification.Builder(ctx)
    }
}
