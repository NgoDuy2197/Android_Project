package com.dsoft.screengrab.capture

import com.dsoft.screengrab.Log2

/** Small helpers for running commands as root (su). Used by the root capture mode. */
object RootShell {
    @Volatile private var cached: Boolean? = null

    /** True if `su` grants root (prompts the superuser manager once). */
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

    /** Run one command as root, capturing stdout as raw bytes (for screencap PNG). */
    fun execBytes(cmd: String): ByteArray? = try {
        val p = ProcessBuilder("su", "-c", cmd).start()
        val out = p.inputStream.readBytes()
        p.waitFor()
        out
    } catch (e: Exception) { Log2.e("root execBytes failed: $cmd", e); null }

    /** Start a long-lived root process (e.g. screenrecord) and return it; caller reads its stdout. */
    fun spawn(cmd: String): Process? = try {
        ProcessBuilder("su", "-c", cmd).start()
    } catch (e: Exception) { Log2.e("root spawn failed: $cmd", e); null }
}
