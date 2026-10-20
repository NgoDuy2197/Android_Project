package com.dsoft.autotap

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import java.net.Inet4Address
import java.net.NetworkInterface

object Net {
    /** (interface, IPv4) pairs reachable from the LAN / hotspot / VPN; Wi-Fi first, mobile data skipped. */
    fun addresses(): List<Pair<String, String>> = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !SKIP.any { p -> it.name.startsWith(p) } }
            .flatMap { ni -> ni.inetAddresses.toList().filterIsInstance<Inet4Address>().map { ni.name to (it.hostAddress ?: "") } }
            .filter { it.second.isNotEmpty() }
            .sortedBy { (name, _) -> PREFER.indexOfFirst { name.startsWith(it) }.let { if (it < 0) 99 else it } }
    } catch (_: Exception) {
        emptyList()
    }

    private val SKIP = listOf("rmnet", "ccmni", "dummy", "v4-rmnet", "ims")
    private val PREFER = listOf("wlan", "swlan", "ap", "softap", "eth", "rndis", "tun")
}

object Notif {
    const val CHANNEL = "tools"

    fun channel(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Tiện ích đang chạy", NotificationManager.IMPORTANCE_LOW).apply {
                    setShowBadge(false)
                }
            )
        }
    }

    @Suppress("DEPRECATION")
    fun builder(ctx: Context): Notification.Builder {
        channel(ctx)
        return if (Build.VERSION.SDK_INT >= 26) Notification.Builder(ctx, CHANNEL) else Notification.Builder(ctx)
    }

    fun action(title: String, pi: PendingIntent): Notification.Action =
        Notification.Action.Builder(null as Icon?, title, pi).build()

    fun activity(ctx: Context, cls: Class<*>, req: Int): PendingIntent = PendingIntent.getActivity(
        ctx, req, Intent(ctx, cls).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun service(ctx: Context, cls: Class<*>, action: String, req: Int): PendingIntent = PendingIntent.getService(
        ctx, req, Intent(ctx, cls).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
