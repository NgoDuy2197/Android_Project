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
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Foreground service: mic -> Google speech recognizer -> chat lines -> JSON file.
 *
 * Everything runs on the service's main looper under a partial wake lock, so
 * recognition and line breaking keep working with the screen off and no
 * activity open. Failures are reported in [status] and retried; the service
 * never stops on its own.
 */
class RecorderService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private val main = Handler(Looper.getMainLooper())
    private var stt: SystemStt? = null
    private var seg: Segmenter? = null
    private var rawPartial = ""
    private val tick = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            if (now - levelAt > 400) level = 0f // recognizer between utterances: no level = quiet
            seg?.let { s -> safe { s.tick(now, level, silenceLevel, waitMs) }; partial = s.liveText(rawPartial) }
            main.postDelayed(this, 100)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Prefs(this).wantRunning = false
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RELOAD -> if (isRunning) stopEngine()
        }
        if (!goForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!isRunning) beginSession(Prefs(this))
        startEngine()
        isRunning = true
        return START_STICKY
    }

    /** Starts recognition (no-op if it is already running). */
    private fun startEngine() {
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VoiceNote:rec").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
        if (stt != null) return
        val prefs = Prefs(this)
        val writer = TranscriptWriter(prefs.timeFormat, prefs.language, ::fileForEntry)
        lateinit var recognizer: SystemStt
        val s = Segmenter(requestEnd = { recognizer.finish() }) { start, text -> addEntry(writer.write(start, text)) }
        seg = s
        recognizer = SystemStt(this, prefs.language, object : SystemStt.Callback {
            override fun onStatus(s: String) = setStatus(s)
            override fun onSpeechStart() {}
            override fun onPartial(text: String) {
                rawPartial = text
                s.onPartial(text, System.currentTimeMillis())
            }
            override fun onFinal(text: String) {
                rawPartial = ""
                safe { s.onFinal(text, System.currentTimeMillis()) }
            }
            override fun onLevel(level: Float) {
                RecorderService.level = level
                levelAt = System.currentTimeMillis()
            }
        })
        stt = recognizer.also { it.start() }
        main.removeCallbacks(tick)
        main.post(tick)
        setStatus("Đang khởi động nhận dạng Google…")
    }

    private fun stopEngine() {
        stt?.stop()
        stt = null
        main.removeCallbacks(tick)
        seg?.let { s ->
            safe { s.onFinal("", System.currentTimeMillis()) } // keep a half-said sentence
            safe { s.flush() }
        }
        seg = null
        rawPartial = ""
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
     * File for an entry: "day" -> transcript_yyyy-MM-dd.json; "session" -> one
     * file per recording session (named by its first entry), split again at midnight.
     * The file is only created on the first entry, so silent sessions leave nothing.
     */
    private fun fileForEntry(startMs: Long): File {
        val dir = Transcripts.dir(this)
        if (Prefs(this).fileMode == "day") {
            return File(dir, Transcripts.dayFileName(startMs)).also { sessionFile = it }
        }
        val day = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(startMs))
        val cur = sessionFile
        if (cur != null && sessionDay == day) return cur
        val base = Transcripts.sessionFileName(startMs).removeSuffix(".json")
        var f = File(dir, "$base.json")
        var n = 2
        while (f.exists()) f = File(dir, "$base ($n).json").also { n++ }
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

    /** Disk errors must not kill recognition: log, show, keep the text pending. */
    private inline fun safe(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            Log.e(TAG, "write failed", e)
            setStatus("Lỗi ghi file: ${e.message}")
        }
    }

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
        // Required by Android for background mic use; kept as quiet as the OS allows.
        @Suppress("DEPRECATION")
        return builder(this)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("VoiceNote")
            .setOngoing(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setPriority(Notification.PRIORITY_MIN)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null as Icon?, "Dừng", stop).build())
            .build()
    }

    companion object {
        private const val TAG = "VoiceNote"
        private const val CHANNEL = "rec_quiet"
        private const val NOTIF_ID = 1
        private const val NOTIF_TAP_ID = 2
        private const val ACTION_STOP = "com.dsoft.voicenote.STOP"
        private const val ACTION_RELOAD = "com.dsoft.voicenote.RELOAD"
        const val EXTRA_START = "start"

        @Volatile var status = "Đã dừng"
            private set
        @Volatile var isRunning = false
            private set
        /** The open (not yet "Entered") line plus what is being said now; shown live in the UI. */
        @Volatile var partial = ""
            private set
        /** Mic loudness 0..1 for the UI meter and silence detection. */
        @Volatile var level = 0f
            private set
        @Volatile private var levelAt = 0L

        /** This session's thresholds: start from the defaults in Settings, adjustable live. */
        @Volatile var silenceLevel = Prefs.DEFAULT_SILENCE
        @Volatile var waitMs = Prefs.DEFAULT_WAIT_MS

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

        private fun beginSession(prefs: Prefs) {
            sessionStart = System.currentTimeMillis()
            sessionFile = null
            sessionDay = ""
            silenceLevel = prefs.silenceLevel
            waitMs = prefs.waitMs
            synchronized(sessionEntries) { sessionEntries.clear() }
            entriesVersion++
        }

        private fun addEntry(e: Entry) {
            synchronized(sessionEntries) { sessionEntries.add(e) }
            entriesVersion++
        }

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
