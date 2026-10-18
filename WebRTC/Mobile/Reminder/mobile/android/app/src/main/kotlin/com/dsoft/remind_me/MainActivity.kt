package com.dsoft.remind_me

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    private var recorder: MediaRecorder? = null
    private var recStart = 0L
    private var preview: MediaPlayer? = null
    private var pendingRecord: Pair<String, MethodChannel.Result>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Shizuku available: make sure the watchdog runs (it dies on reboot).
        Thread { KeepAlive.ensure(applicationContext, WATCHDOG_SEC) }.start()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
        }
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "reminder/native").setMethodCallHandler { call, result ->
            when (call.method) {
                "reschedule" -> result.success(Scheduler.rescheduleAll(applicationContext))
                "test" -> {
                    AlarmService.ring(this, call.argument<String>("id").orEmpty())
                    result.success(null)
                }
                "stopRinging" -> {
                    AlarmService.stop(this)
                    result.success(null)
                }
                "permissions" -> result.success(permissions())
                "keepAliveStatus" -> Thread {
                    val s = KeepAlive.status(applicationContext)
                    runOnUiThread { result.success(s) }
                }.start()
                "shizukuRequest" -> {
                    KeepAlive.requestPermission()
                    result.success(null)
                }
                "keepAliveStart" -> Thread {
                    val ok = KeepAlive.start(applicationContext, WATCHDOG_SEC)
                    runOnUiThread { result.success(ok) }
                }.start()
                "keepAliveStop" -> Thread {
                    val ok = KeepAlive.stop(applicationContext)
                    runOnUiThread { result.success(ok) }
                }.start()
                "openPermission" -> {
                    openPermission(call.argument<String>("which").orEmpty())
                    result.success(null)
                }
                "startRecording" -> {
                    val path = call.argument<String>("path").orEmpty()
                    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        result.success(startRecording(path))
                    } else {
                        pendingRecord = path to result
                        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
                    }
                }
                "stopRecording" -> result.success(stopRecording())
                "play" -> {
                    stopPreview()
                    preview = try {
                        MediaPlayer().apply {
                            setDataSource(call.argument<String>("path"))
                            prepare()
                            start()
                            setOnCompletionListener { stopPreview() }
                        }
                    } catch (_: Exception) {
                        null
                    }
                    result.success(null)
                }
                "stopPlay" -> {
                    stopPreview()
                    result.success(null)
                }
                else -> result.notImplemented()
            }
        }
    }

    private fun permissions(): Map<String, Boolean> {
        val am = getSystemService(AlarmManager::class.java)
        val nm = getSystemService(NotificationManager::class.java)
        return mapOf(
            "notifications" to NotificationManagerCompat.from(this).areNotificationsEnabled(),
            "exactAlarm" to (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()),
            "fullScreen" to (Build.VERSION.SDK_INT < 34 || nm.canUseFullScreenIntent()),
            // Needed to pop the alarm screen up while another app is in use.
            "overlay" to Settings.canDrawOverlays(this),
            "battery" to (getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)),
        )
    }

    /** OEM screens that let an app start/run in the background (ColorOS, MIUI, …). */
    private val autostartScreens = listOf(
        "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity",
        "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
        "com.oplus.safecenter" to "com.oplus.safecenter.startupapp.StartupAppListActivity",
        "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity",
        "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
    )

    private fun openPermission(which: String) {
        val pkg = Uri.parse("package:$packageName")
        if (which == "autostart") {
            for ((p, c) in autostartScreens) {
                try {
                    startActivity(Intent().setClassName(p, c).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    return
                } catch (_: Exception) {
                }
            }
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg))
            return
        }
        val intent = when {
            which == "exactAlarm" && Build.VERSION.SDK_INT >= 31 -> Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkg)
            which == "overlay" -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkg)
            which == "battery" -> Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg)
            which == "fullScreen" && Build.VERSION.SDK_INT >= 34 -> Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg)
            which == "notifications" && Build.VERSION.SDK_INT >= 26 ->
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            else -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)
        }
        try {
            startActivity(intent)
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg))
        }
    }

    private fun startRecording(path: String): Boolean {
        stopRecording()
        return try {
            val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioEncodingBitRate(128_000)
            r.setAudioSamplingRate(44_100)
            r.setOutputFile(path)
            r.prepare()
            r.start()
            recorder = r
            recStart = SystemClock.elapsedRealtime()
            true
        } catch (_: Exception) {
            recorder?.release()
            recorder = null
            false
        }
    }

    private fun stopRecording(): Int {
        val r = recorder ?: return 0
        recorder = null
        try {
            r.stop()
        } catch (_: Exception) {
        }
        r.release()
        return (SystemClock.elapsedRealtime() - recStart).toInt()
    }

    private fun stopPreview() {
        preview?.release()
        preview = null
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MIC) {
            val (path, result) = pendingRecord ?: return
            pendingRecord = null
            val ok = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
            result.success(ok && startRecording(path))
        }
    }

    override fun onDestroy() {
        stopRecording()
        stopPreview()
        super.onDestroy()
    }

    companion object {
        private const val REQ_NOTIF = 1
        private const val REQ_MIC = 2
        private const val WATCHDOG_SEC = 30
    }
}
