package com.dsoft.proxyspy

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.Executors

/** Foreground service: runs the MITM proxy's accept loop and keeps CPU/Wi-Fi awake. */
class ProxyService : Service() {
    private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopAll(); return START_NOT_STICKY }
        val p = Prefs.port(this)
        startFg(p)
        if (server == null) startServer(p)
        return START_STICKY
    }

    private fun startServer(port: Int) {
        try {
            val s = ServerSocket()
            s.reuseAddress = true
            s.bind(InetSocketAddress("0.0.0.0", port), 200)
            server = s
            running = true
            Companion.port = port
            acquireLocks()
            notifyChanged()
            Thread({
                val maxBody = Prefs.maxBody(this)
                while (!s.isClosed) {
                    val c = try { s.accept() } catch (_: Exception) { break }
                    pool.execute { ProxyEngine.handle(c, maxBody) }
                }
            }, "proxyspy-accept").start()
        } catch (e: Exception) {
            error = "Không mở được cổng $port: ${e.message}"
            running = false
            notifyChanged()
            stopSelf()
        }
    }

    private fun startFg(port: Int) {
        val ip = Net.primaryIp()
        val stop = PendingIntent.getService(this, 1,
            Intent(this, ProxyService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = PendingIntent.getActivity(this, 2,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n: Notification = NotificationCompat.Builder(this, App.CH)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("ProxySpy đang chạy")
            .setContentText("Proxy  $ip:$port  —  đặt proxy máy khác vào đây")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Dừng", stop)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        try { ServiceCompat.startForeground(this, NOTIF_ID, n, type) } catch (_: Exception) {}
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        try {
            if (wake == null) wake = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "proxyspy:cpu").apply { acquire() }
            if (wifi == null) wifi = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "proxyspy:wifi").apply { acquire() }
        } catch (_: Exception) {}
    }

    private fun stopAll() {
        running = false
        try { server?.close() } catch (_: Exception) {}
        server = null
        try { wake?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
        try { wifi?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
        wake = null; wifi = null
        notifyChanged()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        try { server?.close() } catch (_: Exception) {}
        running = false
        notifyChanged()
        super.onDestroy()
    }

    private fun notifyChanged() = App.main.post { listener?.invoke() }

    companion object {
        private const val NOTIF_ID = 11
        private const val ACTION_STOP = "stop"
        @Volatile var running = false
        @Volatile var port = 0
        @Volatile var error: String? = null
        var listener: (() -> Unit)? = null

        fun start(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, ProxyService::class.java))
        }

        fun stop(ctx: Context) {
            try { ctx.startService(Intent(ctx, ProxyService::class.java).setAction(ACTION_STOP)) } catch (_: Exception) {}
        }
    }
}
