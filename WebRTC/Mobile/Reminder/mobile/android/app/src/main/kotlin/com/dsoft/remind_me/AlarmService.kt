package com.dsoft.remind_me

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import java.io.File
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Rings a reminder: foreground service playing the recording or the phone
 * ringtone on the ALARM stream (audible in silent/vibrate mode), raising the
 * alarm volume when it is low, vibrating, with a full-screen stop screen.
 */
class AlarmService : Service() {
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var savedVolume = -1
    private var wake: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private var currentId: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopAll()
                return START_NOT_STICKY
            }
            ACTION_SNOOZE -> {
                currentId?.let { Scheduler.schedule(this, it, System.currentTimeMillis() + SNOOZE_MS, snooze = true) }
                stopAll()
                return START_NOT_STICKY
            }
        }
        val id = intent?.getStringExtra("id") ?: return START_NOT_STICKY.also { stopSelf() }
        val r = Scheduler.find(this, id)
        val title = r?.optString("title").orEmpty().ifEmpty { "Nhắc nhở" }
        val note = r?.optString("note").orEmpty()
        currentId = id
        startForegroundCompat(notification(id, title, note))
        wake = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "remind_me:ring").apply { acquire(6 * 60_000L) }
        startSound(
            recording = if (r?.optBoolean("useRecording") == true) r.optString("recording") else "",
            boost = r?.optBoolean("boostVolume", true) ?: true,
        )
        if (r?.optBoolean("vibrate", true) != false) startVibration()
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ stopAll() }, (r?.optInt("ringSeconds", 60) ?: 60).coerceIn(5, 600) * 1000L)
        AlarmActivity.launch(this, id, title, note)
        return START_NOT_STICKY
    }

    private fun startForegroundCompat(n: Notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun notification(id: String, title: String, note: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Nhắc nhở đang kêu", NotificationManager.IMPORTANCE_HIGH).apply {
                    setSound(null, null) // the service plays the sound itself
                    enableVibration(false)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                },
            )
        }
        fun action(a: String, code: Int) = PendingIntent.getService(
            this, code, Intent(this, AlarmService::class.java).setAction(a), PendingIntent.FLAG_IMMUTABLE,
        )
        val full = PendingIntent.getActivity(
            this, 3, AlarmActivity.intent(this, id, title, note),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setContentTitle("⏰ $title")
            .setContentText(note.ifEmpty { SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()) })
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setFullScreenIntent(full, true)
            .setContentIntent(full)
            .addAction(0, "Tắt", action(ACTION_STOP, 1))
            .addAction(0, "Báo lại 5 phút", action(ACTION_SNOOZE, 2))
            .build()
    }

    private fun startSound(recording: String, boost: Boolean) {
        stopSound()
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        if (boost) {
            val max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            val cur = am.getStreamVolume(AudioManager.STREAM_ALARM)
            if (cur < max * 0.7) {
                savedVolume = cur
                try {
                    am.setStreamVolume(AudioManager.STREAM_ALARM, (max * 0.85).toInt().coerceAtLeast(1), 0)
                } catch (_: SecurityException) {
                    savedVolume = -1 // Do Not Disturb policy forbids it
                }
            }
        }
        val sources = buildList<Any> {
            if (recording.isNotEmpty() && File(recording).exists()) add(recording)
            RingtoneManager.getActualDefaultRingtoneUri(this@AlarmService, RingtoneManager.TYPE_RINGTONE)?.let { add(it) }
            RingtoneManager.getActualDefaultRingtoneUri(this@AlarmService, RingtoneManager.TYPE_ALARM)?.let { add(it) }
            add(Settings.System.DEFAULT_ALARM_ALERT_URI)
            add(Settings.System.DEFAULT_NOTIFICATION_URI)
        }
        for (src in sources) {
            try {
                player = MediaPlayer().apply {
                    // ALARM usage: plays even when the ringer is silent/vibrate.
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build(),
                    )
                    if (src is String) setDataSource(src) else setDataSource(this@AlarmService, src as Uri)
                    isLooping = true
                    prepare()
                    start()
                }
                return
            } catch (_: Exception) {
                player?.release()
                player = null
            }
        }
    }

    private fun startVibration() {
        val v = if (Build.VERSION.SDK_INT >= 31) {
            (getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as Vibrator
        }
        vibrator = v
        val pattern = longArrayOf(0, 700, 500, 700, 1200)
        if (Build.VERSION.SDK_INT >= 26) {
            v.vibrate(
                VibrationEffect.createWaveform(pattern, 0),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build(),
            )
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(pattern, 0)
        }
    }

    private fun stopSound() {
        player?.let {
            try {
                it.stop()
            } catch (_: Exception) {
            }
            it.release()
        }
        player = null
        if (savedVolume >= 0) {
            try {
                (getSystemService(AUDIO_SERVICE) as AudioManager).setStreamVolume(AudioManager.STREAM_ALARM, savedVolume, 0)
            } catch (_: Exception) {
            }
            savedVolume = -1
        }
    }

    private fun stopAll() {
        handler.removeCallbacksAndMessages(null)
        stopSound()
        vibrator?.cancel()
        vibrator = null
        wake?.let { if (it.isHeld) it.release() }
        wake = null
        AlarmActivity.closeAll()
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        stopSound()
        vibrator?.cancel()
        wake?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.dsoft.remind_me.STOP"
        const val ACTION_SNOOZE = "com.dsoft.remind_me.SNOOZE"
        private const val CHANNEL = "ringing"
        private const val NOTIF_ID = 42
        private const val SNOOZE_MS = 5 * 60_000L

        fun ring(ctx: Context, id: String) {
            val i = Intent(ctx, AlarmService::class.java).putExtra("id", id)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, AlarmService::class.java).setAction(ACTION_STOP))
        }
    }
}

/** Full-screen "Tắt / Báo lại" screen, shown over the lock screen. */
class AlarmActivity : Activity() {
    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        current = WeakReference(this)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }
        val title = intent.getStringExtra("title").orEmpty()
        val note = intent.getStringExtra("note").orEmpty()
        fun text(s: String, size: Float, bold: Boolean = false, alpha: Int = 255) = TextView(this).apply {
            text = s
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            setTextColor(Color.argb(alpha, 255, 255, 255))
            gravity = Gravity.CENTER
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }
        // iOS-like translucent pill: faint white fill + light hairline border.
        fun button(label: String, fill: String, textColor: String, onClick: () -> Unit) = Button(this).apply {
            text = label
            setTextColor(Color.parseColor(textColor))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.DEFAULT_BOLD
            isAllCaps = false
            stateListAnimator = null
            background = GradientDrawable().apply {
                cornerRadius = dp(30).toFloat()
                setColor(Color.parseColor(fill))
                setStroke(dp(1), Color.parseColor("#B3FFFFFF"))
            }
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(60)).apply { topMargin = dp(14) }
            setOnClickListener { onClick() }
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(32), dp(48), dp(32), dp(48))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.parseColor("#34D399"), Color.parseColor("#2E7CF6"), Color.parseColor("#5B50E6")),
            )
            addView(text("⏰", 64f))
            addView(text(SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()), 72f, bold = true))
            addView(text(title.ifEmpty { "Nhắc nhở" }, 26f, bold = true))
            if (note.isNotEmpty()) addView(text(note, 17f, alpha = 220))
            addView(LinearLayout(this@AlarmActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(48), 0, 0)
                addView(button("Tắt", "#59FFFFFF", "#FFFFFF") {
                    AlarmService.stop(this@AlarmActivity)
                    finish()
                })
                addView(button("Báo lại sau 5 phút", "#26FFFFFF", "#E6FFFFFF") {
                    startService(Intent(this@AlarmActivity, AlarmService::class.java).setAction(AlarmService.ACTION_SNOOZE))
                    finish()
                })
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        setContentView(root)
    }

    override fun onDestroy() {
        if (current?.get() === this) current = null
        super.onDestroy()
    }

    companion object {
        private var current: WeakReference<AlarmActivity>? = null

        fun intent(ctx: Context, id: String, title: String, note: String) = Intent(ctx, AlarmActivity::class.java)
            .putExtra("id", id).putExtra("title", title).putExtra("note", note)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

        fun launch(ctx: Context, id: String, title: String, note: String) {
            // Android only lets a background app open a screen when it may draw over
            // other apps; otherwise the full-screen notification shows it (locked phone).
            try {
                ctx.startActivity(intent(ctx, id, title, note))
            } catch (_: Exception) {
                // Background start blocked: the full-screen notification shows it instead.
            }
        }

        fun closeAll() {
            current?.get()?.finish()
            current = null
        }
    }
}
