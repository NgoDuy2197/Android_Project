package com.dsoft.autotap

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager

/** Foreground service hosting [ApiServer]; keeps Wi-Fi + CPU awake while it runs. */
class ApiService : Service() {
    private var server: ApiServer? = null
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
        error = null
        val s = ApiServer(applicationContext, p.apiPort, p.apiUser, p.apiPass) { msg ->
            main.post { lastEvent = msg; changed() }
        }
        try {
            s.start()
        } catch (e: Exception) {
            fail("Không mở được cổng ${p.apiPort}: ${e.message}")
            return
        }
        server = s
        running = true
        port = p.apiPort
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
        val text = if (running) ips.firstOrNull()?.let { "http://${it.second}:$port" } ?: "Chưa có Wi-Fi / hotspot" else "Đang khởi động…"
        val n: Notification = Notif.builder(this)
            .setSmallIcon(R.drawable.ic_api)
            .setContentTitle("API server đang chạy")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(Notif.activity(this, MainActivity::class.java, 20))
            .addAction(Notif.action("Dừng", Notif.service(this, ApiService::class.java, ACTION_STOP, 21)))
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(NOTIF_ID, n)
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        if (!Prefs(this).keepAwake) return
        if (wifiLock == null) {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "autotap:api").apply { setReferenceCounted(false); acquire() }
        }
        if (wakeLock == null) {
            val pm = getSystemService(PowerManager::class.java)
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "autotap:api").apply { setReferenceCounted(false); acquire() }
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
        private const val NOTIF_ID = 2
        private const val ACTION_STOP = "stop"
        private const val ACTION_RESTART = "restart"

        @Volatile var running = false
        @Volatile var port = 0
        @Volatile var error: String? = null
        @Volatile var lastEvent = ""
        var listener: (() -> Unit)? = null

        fun start(ctx: Context) {
            val i = Intent(ctx, ApiService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, ApiService::class.java).setAction(ACTION_STOP))
        }

        fun restartIfRunning(ctx: Context) {
            if (running) ctx.startService(Intent(ctx, ApiService::class.java).setAction(ACTION_RESTART))
        }
    }
}
