package com.dsoft.volteguard

import android.content.pm.PackageManager
import android.os.IBinder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.lang.reflect.InvocationTargetException
import kotlin.concurrent.thread

/**
 * Everything that needs the shell (adb) identity goes through here:
 *  - [exec]: run a shell command (`svc data`, `cmd connectivity airplane-mode`, ...)
 *  - [binder]/[call]: talk to hidden system AIDL services (ITelephony...) as uid 2000.
 */
object ShizukuShell {
    const val REQ_CODE = 7001

    data class ExecResult(val code: Int, val out: String) {
        val ok get() = code == 0
        override fun toString() = "exit=$code ${out.trim().take(300)}"
    }

    private val _state = MutableStateFlow(0)
    /** Bumped every time the Shizuku binder / permission state changes (UI refresh hook). */
    val state: StateFlow<Int> = _state

    fun init() {
        Shizuku.addBinderReceivedListenerSticky { _state.value++ }
        Shizuku.addBinderDeadListener { _state.value++ }
        Shizuku.addRequestPermissionResultListener { _, _ -> _state.value++ }
    }

    fun isRunning(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun hasPermission(): Boolean = isRunning() && runCatching {
        !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun requestPermission() {
        if (isRunning() && !hasPermission()) runCatching { Shizuku.requestPermission(REQ_CODE) }
    }

    fun describe(): String = when {
        !isRunning() -> "Shizuku chưa chạy"
        !hasPermission() -> "Shizuku đang chạy, chưa cấp quyền cho app"
        else -> "Shizuku OK (uid=${runCatching { Shizuku.getUid() }.getOrDefault(-1)})"
    }

    /** Runs `sh -c cmd` as shell/root via Shizuku. Never throws. */
    fun exec(cmd: String, timeoutMs: Long = 20_000): ExecResult {
        if (!hasPermission()) return ExecResult(-100, "Shizuku not available")
        return try {
            // newProcess is private since API 13 but still present; fine for a personal tool.
            val m = Shizuku::class.java.getDeclaredMethod(
                "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
            ).apply { isAccessible = true }
            val p = m.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process
            val sb = StringBuffer()
            val t1 = thread { runCatching { p.inputStream.bufferedReader().forEachLine { sb.append(it).append('\n') } } }
            val t2 = thread { runCatching { p.errorStream.bufferedReader().forEachLine { sb.append(it).append('\n') } } }
            // ShizukuRemoteProcess breaks Process.waitFor(timeout) (its exitValue() throws
            // IllegalArgumentException, not IllegalThreadStateException), so wait on a thread.
            var code: Int? = null
            val w = thread { code = runCatching { p.waitFor() }.getOrNull() }
            w.join(timeoutMs)
            if (w.isAlive) {
                runCatching { p.destroy() }
                return ExecResult(-2, "timeout. $sb")
            }
            t1.join(1000); t2.join(1000)
            ExecResult(code ?: -3, sb.toString())
        } catch (e: Throwable) {
            val c = if (e is InvocationTargetException) e.targetException else e
            ExecResult(-1, "${c.javaClass.simpleName}: ${c.message}")
        }
    }

    /** System service binder wrapped so transactions run with Shizuku's (shell) identity. */
    fun binder(service: String): IBinder? {
        if (!hasPermission()) return null
        return runCatching { SystemServiceHelper.getSystemService(service)?.let { ShizukuBinderWrapper(it) } }.getOrNull()
    }

    /** `IFoo.Stub.asInterface(binder)` via reflection. */
    fun asInterface(stubClass: String, binder: IBinder): Any? = runCatching {
        Class.forName(stubClass).getMethod("asInterface", IBinder::class.java).invoke(null, binder)
    }.getOrNull()

    /** Reflective call by method name + arity. Unwraps InvocationTargetException. */
    fun call(target: Any, name: String, vararg args: Any?): Any? {
        val m = target.javaClass.methods.firstOrNull { it.name == name && it.parameterTypes.size == args.size }
            ?: throw NoSuchMethodException("$name/${args.size}")
        return try {
            m.invoke(target, *args)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }
}
