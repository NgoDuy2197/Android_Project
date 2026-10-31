package com.dsoft.toolbox

import java.io.DataOutputStream

/** Minimal root helper: run a command via `su`. Returns true on success (exit 0). */
object Root {
    /** Is a `su` binary present and willing to grant root? (Blocks briefly; call off the main thread.) */
    fun available(): Boolean = runAsRoot("id")

    /** Run [cmd] as root. Returns true if su launched and the command exited 0. */
    fun runAsRoot(cmd: String): Boolean {
        return try {
            val p = Runtime.getRuntime().exec("su")
            DataOutputStream(p.outputStream).use { os ->
                os.writeBytes("$cmd\n")
                os.writeBytes("exit\n")
                os.flush()
            }
            p.waitFor() == 0
        } catch (_: Exception) { false }
    }
}
