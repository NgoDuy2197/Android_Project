package com.dsoft.voicenote

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Home: big glossy record button, quick status, recent transcripts. */
class MainActivity : Activity() {
    private lateinit var p: Palette
    private lateinit var prefs: Prefs
    private lateinit var record: RecordButton
    private lateinit var bars: LevelBars
    private lateinit var hint: TextView
    private lateinit var chip: TextView
    private lateinit var stopPill: TextView
    private lateinit var recent: LinearLayout
    private val handler = Handler(Looper.getMainLooper())
    private var wasRunning = false

    private val tick = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 100)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        p = palette()
        prefs = Prefs(this)
        setupSystemBars(p)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), dp(24))
        }

        // header: large title + folder / settings
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(text("VoiceNote", 34f, p.text, 700), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(circleButton(p, R.drawable.ic_folder) { startActivity(Intent(this@MainActivity, FilesActivity::class.java)) })
            addView(circleButton(p, R.drawable.ic_settings) { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }.apply {
                (layoutParams as LinearLayout.LayoutParams).marginStart = dp(10)
            })
        }
        root.addView(header)
        val today = SimpleDateFormat("EEEE, d 'tháng' M", Locale("vi")).format(Date())
            .replaceFirstChar { it.titlecase(Locale("vi")) }
        root.addView(text(today, 15f, p.secondary).apply { setPadding(0, dp(4), 0, 0) })

        // record area
        val stage = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(28), 0, dp(12))
        }
        chip = text("", 13f, p.secondary, 500).apply {
            background = rounded(p.fill, dp(14).toFloat())
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }
        stage.addView(chip)
        record = RecordButton(this).apply { setOnClickListener { onRecordTap() } }
        stage.addView(record, LinearLayout.LayoutParams(dp(240), dp(240)).apply { topMargin = dp(8) })
        bars = LevelBars(this)
        stage.addView(bars, LinearLayout.LayoutParams(dp(200), dp(34)))
        hint = text("", 15f, p.secondary).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, 0)
            setLineSpacing(0f, 1.2f)
        }
        stage.addView(hint)
        stopPill = text("Dừng ghi", 15f, p.red, 500).apply {
            gravity = Gravity.CENTER
            background = pressable(rounded(p.fill, dp(18).toFloat()), p.ripple)
            setPadding(dp(22), dp(9), dp(22), dp(9))
            setOnClickListener { RecorderService.stop(this@MainActivity) }
        }
        stage.addView(stopPill, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(12)
        })
        root.addView(stage)

        // recent files
        root.addView(sectionHeader(p, "Gần đây").apply { setPadding(dp(4), dp(18), 0, dp(7)) })
        recent = card(p)
        root.addView(recent)

        setContentView(ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            addView(root)
        })
        handleStartExtra(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleStartExtra(intent)
    }

    override fun onResume() {
        super.onResume()
        fillRecent()
        handler.post(tick)
    }

    override fun onPause() {
        handler.removeCallbacks(tick)
        super.onPause()
    }

    private fun handleStartExtra(intent: Intent?) {
        if (intent?.getBooleanExtra(RecorderService.EXTRA_START, false) == true) {
            intent.removeExtra(RecorderService.EXTRA_START)
            startRecording()
        }
    }

    private fun onRecordTap() {
        if (RecorderService.isRunning) SessionActivity.openLive(this) else startRecording()
    }

    private fun startRecording() {
        // Only the mic is requested: without POST_NOTIFICATIONS the mandatory
        // recording notification stays hidden on Android 13+.
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }
        try {
            RecorderService.start(this)
            SessionActivity.openLive(this)
        } catch (t: Throwable) {
            toast("Không khởi động được: ${t.message}")
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode != REQ_MIC) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startRecording()
        } else {
            toast("Cần quyền micro để ghi âm")
        }
    }

    private fun render() {
        val running = RecorderService.isRunning
        record.recording = running
        record.level = RecorderService.level
        bars.push(if (running) RecorderService.level else 0f)
        val engine = if (prefs.engine == "google") "Google" else "Vosk"
        chip.text = "$engine · ${Prefs.LANGS[prefs.language]}"
        hint.text = if (running) "${elapsed(RecorderService.sessionStart)} · ${RecorderService.status}\nChạm để mở phiên"
        else "Chạm để bắt đầu ghi"
        stopPill.visibility = if (running) TextView.VISIBLE else TextView.GONE
        if (wasRunning && !running) fillRecent()
        wasRunning = running
    }

    private fun fillRecent() {
        recent.removeAllViews()
        val files = Transcripts.list(this).take(3)
        if (files.isEmpty()) {
            recent.addView(text("Chưa có bản ghi nào", 15f, p.secondary).apply {
                setPadding(dp(16), dp(14), dp(16), dp(14))
            })
            return
        }
        files.forEachIndexed { i, f ->
            if (i > 0) recent.addView(separator(p, 60))
            recent.addView(fileRow(f) { SessionActivity.openFile(this, f) })
        }
        recent.addView(separator(p, 0))
        recent.addView(row(p, "Xem tất cả", chevron(p)) { startActivity(Intent(this, FilesActivity::class.java)) }.apply {
            (getChildAt(0) as TextView).setTextColor(p.blue)
        })
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    companion object {
        private const val REQ_MIC = 1
    }
}

/** "12:31" or "1:02:31" since [start]. */
fun elapsed(start: Long): String {
    if (start <= 0) return "00:00"
    val s = (System.currentTimeMillis() - start) / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%02d:%02d".format(s / 60, s % 60)
}

/** Shared file row: colored doc tile, name, meta line, trailing view (chevron by default). */
fun Activity.fileRow(f: File, trailing: android.view.View? = null, onClick: () -> Unit): LinearLayout {
    val p = palette()
    val active = RecorderService.isRunning && RecorderService.sessionFile == f
    return LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), dp(10), dp(10), dp(10))
        background = pressable(null, p.ripple)
        setOnClickListener { onClick() }
        addView(icon(R.drawable.ic_doc, android.graphics.Color.WHITE, 34).apply {
            background = gradient(dp(9).toFloat(), if (active) p.pink else p.blue, if (active) p.red else p.indigo)
            setPadding(dp(7), dp(7), dp(7), dp(7))
        })
        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(6), 0)
            addView(text(f.nameWithoutExtension, 16f, p.text, 500).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            val meta = SimpleDateFormat("d/M · HH:mm", Locale.getDefault()).format(Date(f.lastModified())) +
                " · ${Transcripts.countEntries(f)} đoạn" + if (active) " · đang ghi" else ""
            addView(text(meta, 13f, if (active) p.red else p.secondary).apply { setPadding(0, dp(3), 0, 0) })
        }
        addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(trailing ?: chevron(p))
    }
}
