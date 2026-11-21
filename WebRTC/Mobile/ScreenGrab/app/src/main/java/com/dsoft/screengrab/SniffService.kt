package com.dsoft.screengrab

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.dsoft.screengrab.net.Mitm
import com.dsoft.screengrab.net.Net
import com.dsoft.screengrab.net.RootNet
import com.dsoft.screengrab.net.SniffProxy
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * Runs the local MITM proxy that sniffs video streams, and routes the device's apps through it.
 * Root mode: install our CA system-wide + set the global HTTP proxy to this device's LAN IP:port.
 * VPN mode (experimental, no root): a VpnService declares our proxy via setHttpProxy.
 */
class SniffService : Service() {

    private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()
    @Volatile private var alive = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Mitm.init(this)
        startNotif("Đang khởi động…")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopEverything(); return START_NOT_STICKY }
        if (!alive) startSniffer()
        return START_STICKY
    }

    private fun startSniffer() {
        val prefs = Prefs(this)
        val port = prefs.snifferPort
        try {
            val s = ServerSocket()
            s.reuseAddress = true
            s.bind(InetSocketAddress("0.0.0.0", port), 200)
            server = s
            alive = true
        } catch (e: Exception) {
            Log2.e("sniffer bind failed", e)
            lastError = "Không mở được cổng $port: ${e.message}"
            running = false; notifyChanged(); stopSelf(); return
        }

        // accept loop
        pool.execute {
            val s = server ?: return@execute
            while (alive) {
                val c = try { s.accept() } catch (_: Exception) { break }
                pool.execute { SniffProxy.handle(c) }
            }
        }

        // routing
        if (prefs.snifferRouting == "root") {
            pool.execute {
                val der = Mitm.caDer()
                val (caOk, _) = RootNet.installSystemCa(this, der)
                val ip = Net.primaryIp()
                proxyAddr = "$ip:$port"
                val (code, _) = RootNet.setProxy(proxyAddr)
                routeReady = caOk && code == 0
                lastError = if (routeReady) "" else "Root: cài CA/đặt proxy chưa xong (cần cấp quyền su)"
                running = true
                startNotif(if (routeReady) "Đang bắt video · proxy $proxyAddr" else "Proxy chạy nhưng định tuyến chưa sẵn")
                notifyChanged()
            }
        } else {
            // VPN mode: the VpnService handles routing; user consent is obtained by MainActivity first.
            proxyAddr = "127.0.0.1:$port"
            running = true
            startNotif("Đang bắt video (VPN) · $proxyAddr")
            SniffVpn.start(this)
            notifyChanged()
        }
    }

    private fun stopEverything() {
        alive = false
        running = false
        routeReady = false
        runCatching { server?.close() }; server = null
        val prefs = Prefs(this)
        if (prefs.snifferRouting == "root") {
            pool.execute { runCatching { RootNet.fullRestore() } }
        } else {
            SniffVpn.stop(this)
        }
        prefs.snifferEnabled = false
        notifyChanged()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        alive = false; running = false
        runCatching { server?.close() }
        runCatching { pool.shutdownNow() }
        super.onDestroy()
    }

    // ---- notification ----
    private fun startNotif(text: String) {
        val chId = "screengrab_sniffer"
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(chId) == null)
                nm.createNotificationChannel(NotificationChannel(chId, "ScreenGrab bắt video", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MediaListActivity::class.java),
            if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        val stop = PendingIntent.getService(this, 1, Intent(this, SniffService::class.java).setAction(ACTION_STOP),
            if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, chId) else @Suppress("DEPRECATION") Notification.Builder(this)
        val notif = b.setContentTitle("ScreenGrab — bắt video").setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_save).setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Danh sách", open).build())
            .addAction(Notification.Action.Builder(null, "Dừng", stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(8, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(8, notif)
    }

    companion object {
        @Volatile var running = false; private set
        @Volatile var routeReady = false; private set
        @Volatile var proxyAddr = ""; private set
        @Volatile var lastError = ""; private set

        private val listeners = CopyOnWriteArrayList<() -> Unit>()
        fun addListener(l: () -> Unit) { listeners.add(l) }
        fun removeListener(l: () -> Unit) { listeners.remove(l) }
        private fun notifyChanged() { for (l in listeners) runCatching { l() } }

        fun start(ctx: Context) {
            val i = Intent(ctx, SniffService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }
        fun stop(ctx: Context) {
            val i = Intent(ctx, SniffService::class.java).setAction(ACTION_STOP)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        const val ACTION_STOP = "com.dsoft.screengrab.SNIFF_STOP"
    }
}
