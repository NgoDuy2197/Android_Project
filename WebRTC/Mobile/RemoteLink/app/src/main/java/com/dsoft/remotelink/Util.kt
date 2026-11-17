package com.dsoft.remotelink

import android.content.Context
import android.util.Log
import java.net.Inet4Address
import java.net.NetworkInterface

object Log2 {
    private const val TAG = "RemoteLink"
    private val buf = ArrayDeque<String>()
    private val listeners = mutableListOf<() -> Unit>()

    fun i(msg: String) { Log.i(TAG, msg); push("I $msg") }
    fun w(msg: String) { Log.w(TAG, msg); push("W $msg") }
    fun e(msg: String, t: Throwable? = null) { Log.e(TAG, msg, t); push("E $msg" + (t?.let { " — ${it.message}" } ?: "")) }

    @Synchronized private fun push(line: String) {
        val ts = android.text.format.DateFormat.format("HH:mm:ss", System.currentTimeMillis())
        buf.addLast("$ts $line")
        while (buf.size > 200) buf.removeFirst()
        listeners.toList().forEach { runCatching { it() } }
    }

    @Synchronized fun snapshot(): List<String> = buf.toList()
    @Synchronized fun addListener(l: () -> Unit) { listeners.add(l) }
    @Synchronized fun removeListener(l: () -> Unit) { listeners.remove(l) }
}

object Net {
    /** Best-guess LAN IPv4 address of this device (Wi-Fi / hotspot), or null. */
    fun localIpv4(): String? {
        return try {
            NetworkInterface.getNetworkInterfaces().toList()
                .asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList().asSequence() }
                .filterIsInstance<Inet4Address>()
                .map { it.hostAddress }
                .firstOrNull { it != null && !it.startsWith("127.") }
        } catch (e: Exception) { null }
    }

    fun allIpv4(): List<String> = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { ni -> ni.inetAddresses.toList().filterIsInstance<Inet4Address>().mapNotNull { it.hostAddress } }
            .filter { !it.startsWith("127.") }
    } catch (e: Exception) { emptyList() }
}

/** Is our AccessibilityService currently enabled in system settings? */
fun isAccessibilityEnabled(ctx: Context): Boolean {
    val expected = "${ctx.packageName}/com.dsoft.remotelink.input.RemoteAccessibilityService"
    val enabled = android.provider.Settings.Secure.getString(
        ctx.contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false
    return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
}

fun isIgnoringBatteryOptimizations(ctx: Context): Boolean {
    return try {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        pm.isIgnoringBatteryOptimizations(ctx.packageName)
    } catch (e: Exception) { true }
}
