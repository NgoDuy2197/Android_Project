package com.dsoft.toolbox

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

/** Foreground service hosting [ShareServer]; keeps Wi-Fi + CPU awake while on. */
class ShareService : Service() {
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val main = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        startFg()
        if (server == null) launch()
        return START_STICKY
    }

    private fun launch() {
        val port = PORT
        val s = ShareServer(applicationContext, port) { msg -> main.post { lastEvent = msg; changed() } }
        try {
            s.start()
        } catch (e: Exception) {
            error = "Không mở được cổng $port: ${e.message}"
            running = false
            changed()
            stopSelf()
            return
        }
        server = s
        running = true
        error = null
        acquireLocks()
        startFg()
        changed()
    }

    private fun startFg() {
        val ip = Net.addresses().firstOrNull()?.second
        val text = if (running && ip != null) "http://$ip:$PORT" else "Đang khởi động…"
        val n: Notification = Notif.builder(this)
            .setSmallIcon(R.drawable.ic_share_board)
            .setContentTitle("Chia sẻ đang chạy")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(Notif.activity(this, ShareActivity::class.java, 40))
            .addAction(Notif.action("Dừng", Notif.service(this, ShareService::class.java, ACTION_STOP, 41)))
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(NOTIF_ID, n)
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        if (wifiLock == null) {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "toolbox:share").apply { setReferenceCounted(false); acquire() }
        }
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "toolbox:share").apply { setReferenceCounted(false); acquire() }
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

    private fun changed() = main.post { listener?.invoke() }

    companion object {
        const val PORT = 8777
        private const val NOTIF_ID = 4
        private const val ACTION_STOP = "stop"

        @Volatile var server: ShareServer? = null
            private set
        @Volatile var running = false
        @Volatile var error: String? = null
        @Volatile var lastEvent = ""
        var listener: (() -> Unit)? = null

        fun start(ctx: Context) {
            val i = Intent(ctx, ShareService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: Context) { ctx.startService(Intent(ctx, ShareService::class.java).setAction(ACTION_STOP)) }
    }
}
