package com.dsoft.toolbox

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaScannerConnection
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import java.io.File

/** Foreground service hosting [FtpServer]; keeps Wi-Fi + CPU awake while it runs. */
class FtpService : Service() {
    private var server: FtpServer? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val main = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            ACTION_RESTART -> { server?.stop(); server = null }
        }
        startFg()
        if (server == null) launch()
        return START_NOT_STICKY
    }

    private fun launch() {
        val p = Prefs(this)
        val dir = File(p.ftpDir)
        error = null
        if (!dir.isDirectory) {
            fail("Thư mục không tồn tại: ${dir.path}")
            return
        }
        val s = FtpServer(dir, p.ftpPort, p.ftpUser, p.ftpPass, p.ftpReadOnly,
            onEvent = { msg -> main.post { lastEvent = msg; changed() } },
            onFileChanged = { f -> MediaScannerConnection.scanFile(this, arrayOf(f.absolutePath), null, null) },
        )
        try {
            s.start()
        } catch (e: Exception) {
            fail("Không mở được cổng ${p.ftpPort}: ${e.message}")
            return
        }
        server = s
        running = true
        port = p.ftpPort
        lastEvent = ""
        acquireLocks()
        startFg()
        changed()
    }

    private fun fail(msg: String) {
        error = msg
        running = false
        changed()
        stopSelf()
    }

    private fun startFg() {
        val ips = Net.addresses()
        val text = if (running) ips.firstOrNull()?.let { "ftp://${it.second}:$port" } ?: "Chưa có Wi-Fi / hotspot" else "Đang khởi động…"
        val n: Notification = Notif.builder(this)
            .setSmallIcon(R.drawable.ic_ftp)
            .setContentTitle("FTP server đang chạy")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(Notif.activity(this, FtpActivity::class.java, 10))
            .addAction(Notif.action("Dừng", Notif.service(this, FtpService::class.java, ACTION_STOP, 11)))
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(NOTIF_ID, n)
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        if (wifiLock == null) {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "toolbox:ftp").apply { setReferenceCounted(false); acquire() }
        }
        if (wakeLock == null) {
            val pm = getSystemService(PowerManager::class.java)
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "toolbox:ftp").apply { setReferenceCounted(false); acquire() }
        }
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        runCatching { wifiLock?.release() }
        runCatching { wakeLock?.release() }
        running = false
        changed()
        super.onDestroy()
    }

    private fun changed() {
        main.post { listener?.invoke() }
    }

    companion object {
        private const val NOTIF_ID = 1
        private const val ACTION_STOP = "stop"
        private const val ACTION_RESTART = "restart"

        @Volatile var running = false
        @Volatile var port = 0
        @Volatile var error: String? = null
        @Volatile var lastEvent = ""
        /** UI refresh hook (main thread). */
        var listener: (() -> Unit)? = null

        fun start(ctx: Context) {
            val i = Intent(ctx, FtpService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, FtpService::class.java).setAction(ACTION_STOP))
        }

        /** Re-reads settings (folder, port, account) if the server is running. */
        fun restartIfRunning(ctx: Context) {
            if (running) ctx.startService(Intent(ctx, FtpService::class.java).setAction(ACTION_RESTART))
        }
    }
}
