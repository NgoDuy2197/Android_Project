package com.dsoft.screengrab

import android.content.Context
import android.content.Intent
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor

/**
 * EXPERIMENTAL no-root routing: a VpnService that declares our local proxy via setHttpProxy (API 29+),
 * so proxy-aware apps send their HTTP(S) through ScreenGrab's MITM. Root mode is more reliable; this is
 * a fallback for devices without root. Requires user consent (VpnService.prepare) handled by the caller.
 */
class SniffVpn : VpnService() {

    private var tun: ParcelFileDescriptor? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { teardown(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY }
        goForeground()
        establish()
        return START_STICKY
    }

    private fun goForeground() {
        val chId = "screengrab_vpn"
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(android.app.NotificationManager::class.java)
            if (nm.getNotificationChannel(chId) == null)
                nm.createNotificationChannel(android.app.NotificationChannel(chId, "ScreenGrab VPN", android.app.NotificationManager.IMPORTANCE_MIN))
        }
        val b = if (Build.VERSION.SDK_INT >= 26) android.app.Notification.Builder(this, chId) else @Suppress("DEPRECATION") android.app.Notification.Builder(this)
        val notif = b.setContentTitle("ScreenGrab VPN").setContentText("Định tuyến bắt video")
            .setSmallIcon(android.R.drawable.ic_menu_save).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(9, notif, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(9, notif)
    }

    private fun establish() {
        if (Build.VERSION.SDK_INT < 29) { stopSelf(); return }   // setHttpProxy needs API 29+
        val port = Prefs(this).snifferPort
        try {
            val b = Builder()
                .setSession("ScreenGrab Sniffer")
                .addAddress("10.111.0.2", 32)
                .addRoute("0.0.0.0", 0)
                .setMtu(1500)
                .setHttpProxy(ProxyInfo.buildDirectProxy("127.0.0.1", port))
            runCatching { b.addDisallowedApplication(packageName) }  // our own upstream bypasses the VPN
            tun = b.establish()
            // Drain the tun so the interface stays up; proxy-aware traffic is handled via setHttpProxy.
            val fd = tun?.fileDescriptor ?: return
            Thread {
                val inp = java.io.FileInputStream(fd)
                val buf = ByteArray(32767)
                try { while (inp.read(buf) >= 0) { /* discard non-proxied packets */ } } catch (_: Exception) {}
            }.start()
        } catch (e: Exception) {
            Log2.e("vpn establish failed", e); stopSelf()
        }
    }

    private fun teardown() { runCatching { tun?.close() }; tun = null }
    override fun onDestroy() { teardown(); super.onDestroy() }

    companion object {
        const val ACTION_STOP = "com.dsoft.screengrab.VPN_STOP"
        fun start(ctx: Context) {
            val i = Intent(ctx, SniffVpn::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }
        fun stop(ctx: Context) = ctx.startService(Intent(ctx, SniffVpn::class.java).setAction(ACTION_STOP))
    }
}
