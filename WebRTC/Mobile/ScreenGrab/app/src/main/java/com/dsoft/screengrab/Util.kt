package com.dsoft.screengrab

import android.content.Context
import android.os.PowerManager
import android.util.Log

object Log2 {
    private const val TAG = "ScreenGrab"
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

fun isIgnoringBatteryOptimizations(ctx: Context): Boolean {
    val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
    return pm.isIgnoringBatteryOptimizations(ctx.packageName)
}

/** Has the user granted the RECORD_AUDIO permission? */
fun hasAudioPermission(ctx: Context): Boolean =
    ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED

/** Is the LSPosed module actually loaded (i.e. hooks are live)? Overridden by the hook at runtime. */
object XposedStatus {
    /** The hook replaces this to return true inside our own process when the module is enabled. */
    @JvmStatic fun moduleActive(): Boolean = false
}
