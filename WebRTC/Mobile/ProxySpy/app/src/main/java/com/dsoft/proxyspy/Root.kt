package com.dsoft.proxyspy

import java.io.BufferedReader
import java.io.InputStreamReader

/** Thin `su` wrapper. Every call returns output + exit code instead of throwing. */
object Root {

    class Result(val code: Int, val out: String) {
        val ok get() = code == 0
    }

    fun available(): Boolean = run("id").out.contains("uid=0")

    fun run(cmd: String, timeoutMs: Long = 8000): Result = try {
        val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
        val text = StringBuilder()
        val reader = Thread {
            runCatching {
                BufferedReader(InputStreamReader(p.inputStream)).useLines { lines ->
                    lines.forEach { text.append(it).append('\n') }
                }
            }
        }
        reader.isDaemon = true
        reader.start()
        val done = p.waitForMs(timeoutMs)
        reader.join(500)
        Result(if (done) p.exitValue() else -1, text.toString().trim())
    } catch (t: Throwable) {
        Result(-1, t.message ?: "không chạy được su")
    }

    /** Restarts the target so it is re-created with the hooks in place. */
    fun forceStop(pkg: String): Result = run("am force-stop $pkg")

    private fun Process.waitForMs(ms: Long): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            try {
                exitValue()
                return true
            } catch (_: IllegalThreadStateException) {
                Thread.sleep(50)
            }
        }
        destroy()
        return false
    }
}
