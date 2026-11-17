package com.dsoft.remotelink.capture

import com.dsoft.remotelink.Log2
import java.io.DataOutputStream

/** Small helpers for running commands as root (su). */
object RootShell {
    @Volatile private var cached: Boolean? = null

    /** True if `su` grants root (prompts the user once via the superuser manager). */
    fun isAvailable(): Boolean {
        cached?.let { return it }
        val ok = try {
            val p = ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor()
            out.contains("uid=0")
        } catch (e: Exception) { false }
        cached = ok
        Log2.i("root available = $ok")
        return ok
    }

    /** Run one command as root, return combined stdout+stderr. */
    fun exec(cmd: String): String = try {
        val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        out
    } catch (e: Exception) { Log2.e("root exec failed: $cmd", e); "" }

    /** Start a long-lived root process (e.g. screenrecord) and return it; caller reads its stdout. */
    fun spawn(cmd: String): Process? = try {
        ProcessBuilder("su", "-c", cmd).start()
    } catch (e: Exception) { Log2.e("root spawn failed: $cmd", e); null }

    /** A persistent root shell you can keep feeding commands to (fast input injection). */
    class Session {
        private var proc: Process? = null
        private var stdin: DataOutputStream? = null

        fun ensure(): Boolean {
            if (proc?.isAlive == true) return true
            return try {
                val p = ProcessBuilder("su").redirectErrorStream(true).start()
                proc = p
                stdin = DataOutputStream(p.outputStream)
                true
            } catch (e: Exception) { Log2.e("root session failed", e); false }
        }

        @Synchronized fun run(line: String) {
            if (!ensure()) return
            try { stdin!!.write((line + "\n").toByteArray()); stdin!!.flush() }
            catch (e: Exception) { Log2.e("root session write failed", e); close() }
        }

        fun close() {
            runCatching { stdin?.write("exit\n".toByteArray()); stdin?.flush() }
            runCatching { proc?.destroy() }
            proc = null; stdin = null
        }
    }
}
