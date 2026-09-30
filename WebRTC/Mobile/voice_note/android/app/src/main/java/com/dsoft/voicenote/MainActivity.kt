package com.dsoft.voicenote

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import java.io.RandomAccessFile
import java.text.SimpleDateFormat

class MainActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var status: TextView
    private lateinit var toggle: Button
    private lateinit var transcript: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var shownModified = -1L

    private val refresh = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)
        status = findViewById(R.id.status)
        toggle = findViewById(R.id.toggle)
        transcript = findViewById(R.id.transcript)
        val autostart = findViewById<CheckBox>(R.id.autostart)
        val modelUrl = findViewById<EditText>(R.id.modelUrl)
        val timeFormat = findViewById<EditText>(R.id.timeFormat)
        val pauseMs = findViewById<EditText>(R.id.pauseMs)

        autostart.isChecked = prefs.autostart
        modelUrl.setText(prefs.modelUrl)
        timeFormat.setText(prefs.timeFormat)
        pauseMs.setText(prefs.pauseMs.toString())
        findViewById<TextView>(R.id.path).text = "File lưu tại:\n${RecorderService.transcriptDir(this)}"

        toggle.setOnClickListener {
            if (RecorderService.isRunning) RecorderService.stop(this) else startWithPermission()
        }
        autostart.setOnCheckedChangeListener { _, checked -> prefs.autostart = checked }
        findViewById<Button>(R.id.save).setOnClickListener {
            val fmt = timeFormat.text.toString().trim()
            if (runCatching { SimpleDateFormat(fmt) }.isFailure) {
                toast("Định dạng giờ không hợp lệ")
                return@setOnClickListener
            }
            prefs.modelUrl = modelUrl.text.toString()
            prefs.timeFormat = fmt
            prefs.pauseMs = pauseMs.text.toString().toLongOrNull() ?: Prefs.DEFAULT_PAUSE_MS
            RecorderService.reload(this)
            toast("Đã lưu")
        }
        findViewById<Button>(R.id.share).setOnClickListener { shareToday() }
        findViewById<Button>(R.id.battery).setOnClickListener { askBatteryExemption() }

        handleStartExtra(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleStartExtra(intent)
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresh)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    private fun handleStartExtra(intent: Intent?) {
        if (intent?.getBooleanExtra(RecorderService.EXTRA_START, false) == true) {
            intent.removeExtra(RecorderService.EXTRA_START)
            startWithPermission()
        }
    }

    private fun startWithPermission() {
        val needed = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isEmpty()) startService() else requestPermissions(needed.toTypedArray(), REQ_PERMS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode != REQ_PERMS) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startService()
        } else {
            toast("Cần quyền micro để ghi âm")
        }
    }

    private fun startService() {
        try {
            RecorderService.start(this)
        } catch (t: Throwable) {
            toast("Không khởi động được: ${t.message}")
        }
    }

    private fun render() {
        status.text = RecorderService.status
        toggle.text = if (RecorderService.isRunning) "Dừng ghi" else "Bắt đầu ghi"
        val f = TranscriptWriter.today(RecorderService.transcriptDir(this))
        val modified = if (f.exists()) f.lastModified() else 0L
        if (modified == shownModified) return
        shownModified = modified
        transcript.text = if (modified == 0L) "(Chưa có nội dung hôm nay)" else tail(f, 4096)
    }

    /** Last [bytes] of the file, cut at a line start so UTF-8 is never split. */
    private fun tail(f: java.io.File, bytes: Int): String = runCatching {
        RandomAccessFile(f, "r").use { raf ->
            val start = maxOf(0L, raf.length() - bytes)
            raf.seek(start)
            val buf = ByteArray((raf.length() - start).toInt())
            raf.readFully(buf)
            val s = String(buf, Charsets.UTF_8)
            if (start > 0) s.substringAfter('\n') else s
        }
    }.getOrDefault("")

    private fun shareToday() {
        val f = TranscriptWriter.today(RecorderService.transcriptDir(this))
        if (!f.exists()) {
            toast("Chưa có nội dung hôm nay")
            return
        }
        // Plain-text share keeps the app dependency-free (no FileProvider); binder limit ~1 MB.
        val text = runCatching { f.readText() }.getOrDefault("").takeLast(200_000)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, f.name)
            .putExtra(Intent.EXTRA_TEXT, text)
        startActivity(Intent.createChooser(send, f.name))
    }

    private fun askBatteryExemption() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            toast("Đã được bỏ tối ưu pin")
            return
        }
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        } catch (e: Exception) {
            runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    companion object {
        private const val REQ_PERMS = 1
    }
}
