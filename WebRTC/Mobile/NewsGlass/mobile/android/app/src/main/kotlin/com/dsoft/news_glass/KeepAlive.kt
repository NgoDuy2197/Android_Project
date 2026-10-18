package com.dsoft.news_glass

import android.content.Context
import android.content.pm.PackageManager
import android.util.Base64
import rikka.shizuku.Shizuku
import kotlin.concurrent.thread

/**
 * Shell-uid watchdog (same idea as volte_guard's Watchdog): assets/keepalive.sh
 * runs detached under the shell user via Shizuku, survives ColorOS force-stopping
 * the app and wakes it again (clearing the "stopped" state) so alarms come back.
 * Without Shizuku the same script can be started over USB with keepalive.bat.
 * It dies on reboot, like Shizuku itself.
 */
object KeepAlive {
    const val REQ_CODE = 7101
    private const val SHIZUKU_PKG = "moe.shizuku.privileged.api"
    private const val SCRIPT = "/data/local/tmp/keepalive.sh"

    data class Exec(val code: Int, val out: String) {
        val ok get() = code == 0
    }

    fun shizukuInstalled(ctx: Context) = try {
        ctx.packageManager.getPackageInfo(SHIZUKU_PKG, 0)
        true
    } catch (_: Exception) {
        false
    }

    fun shizukuRunning() = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun permitted() = shizukuRunning() && runCatching {
        !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun requestPermission() {
        if (shizukuRunning() && !permitted()) runCatching { Shizuku.requestPermission(REQ_CODE) }
    }

    /** `sh -c cmd` as the shell user through Shizuku. Never throws. */
    fun exec(cmd: String, timeoutMs: Long = 15_000): Exec {
        if (!permitted()) return Exec(-100, "no shizuku")
        return try {
            val m = Shizuku::class.java.getDeclaredMethod(
                "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java,
            ).apply { isAccessible = true }
            val p = m.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process
            val sb = StringBuffer()
            val t = thread { runCatching { p.inputStream.bufferedReader().forEachLine { sb.append(it).append('\n') } } }
            var code: Int? = null
            val w = thread { code = runCatching { p.waitFor() }.getOrNull() }
            w.join(timeoutMs)
            if (w.isAlive) {
                runCatching { p.destroy() }
                return Exec(-2, "timeout")
            }
            t.join(1000)
            Exec(code ?: -3, sb.toString())
        } catch (e: Throwable) {
            Exec(-1, e.toString())
        }
    }

    private fun pidFile(ctx: Context) = "/data/local/tmp/keepalive_${ctx.packageName}.pid"

    /** true / false, or null when it cannot be checked (no Shizuku). */
    fun watchdogRunning(ctx: Context): Boolean? {
        if (!permitted()) return null
        val f = pidFile(ctx)
        return exec("p=\$(cat $f 2>/dev/null) && [ -n \"\$p\" ] && kill -0 \$p 2>/dev/null && echo yes").out.contains("yes")
    }

    fun start(ctx: Context, intervalSec: Int): Boolean {
        if (!permitted()) return false
        val script = ctx.assets.open("keepalive.sh").use { it.readBytes() }
        val b64 = Base64.encodeToString(script, Base64.NO_WRAP)
        if (!exec("echo $b64 | base64 -d > $SCRIPT && chmod 755 $SCRIPT").ok) return false
        val rcv = "${ctx.packageName}/.KeepAliveReceiver"
        // setsid + background + closed fds: outlives the shell Shizuku tracks.
        return exec("setsid sh $SCRIPT ${ctx.packageName} $rcv $intervalSec </dev/null >/dev/null 2>&1 &").ok
    }

    fun stop(ctx: Context): Boolean {
        if (!permitted()) return false
        val f = pidFile(ctx)
        return exec("p=\$(cat $f 2>/dev/null); rm -f $f; [ -n \"\$p\" ] && kill \$p 2>/dev/null; echo ok").ok
    }

    /** Starts the watchdog if Shizuku allows it and it is not running yet. */
    fun ensure(ctx: Context, intervalSec: Int) {
        if (permitted() && watchdogRunning(ctx) == false) start(ctx, intervalSec)
    }

    fun status(ctx: Context): Map<String, Any?> = mapOf(
        "installed" to shizukuInstalled(ctx),
        "running" to shizukuRunning(),
        "permitted" to permitted(),
        "watchdog" to watchdogRunning(ctx),
    )
}