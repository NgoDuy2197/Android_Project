package com.dsoft.screengrab.capture

import android.content.Context
import com.dsoft.screengrab.Log2

/**
 * Self-configures the LSPosed **Vector** fork via its root CLI (`/data/adb/lspd/cli`): enables the
 * ScreenGrab module and sets its scope — so the user never has to touch LSPosed Manager by hand.
 *
 * Only Vector ships this CLI. On the original LSPosed (no CLI) we return NO_CLI and the UI falls back
 * to the manual instructions.
 */
object LsposedSetup {
    private const val SELF = "com.dsoft.screengrab"
    private val CLI_PATHS = listOf("/data/adb/lspd/cli", "/data/adb/modules/zygisk_vector/cli")

    enum class Code { OK, NO_ROOT, NO_CLI, FAILED }
    data class Result(val code: Code, val count: Int = 0, val detail: String = "")

    private fun cli(): String? {
        for (p in CLI_PATHS) {
            val out = RootShell.exec("ls $p 2>/dev/null").trim()
            if (out.contains(p.substringAfterLast('/'))) return p
        }
        // last resort: search
        val found = RootShell.exec("ls /data/adb/lspd/cli 2>/dev/null").trim()
        return if (found.isNotEmpty()) "/data/adb/lspd/cli" else null
    }

    /** Is the Vector CLI present (so auto-config is possible)? */
    fun canAutoConfigure(): Boolean = RootShell.isAvailable() && cli() != null

    /** Third-party (user-installed) app package names. */
    private fun userApps(ctx: Context): List<String> =
        RootShell.exec("pm list packages -3").lineSequence()
            .mapNotNull { it.trim().removePrefix("package:").ifBlank { null } }
            .toList()

    /**
     * Enable the module and scope it to [targets] (+ system framework + self). If [allApps] is true,
     * scope to every user-installed app so screenshots/recording work everywhere.
     */
    fun configure(ctx: Context, targets: List<String>, allApps: Boolean): Result {
        if (!RootShell.isAvailable()) return Result(Code.NO_ROOT)
        val cli = cli() ?: return Result(Code.NO_CLI)

        val en = RootShell.exec("$cli modules enable $SELF")
        Log2.i("lsposed enable: ${en.trim()}")

        val pkgs = LinkedHashSet<String>()
        pkgs.add("android")
        pkgs.add(SELF)
        if (allApps) pkgs.addAll(userApps(ctx)) else pkgs.addAll(targets.filter { it.isNotBlank() })

        val scopeArgs = pkgs.joinToString(" ") { "$it/0" }
        val res = RootShell.exec("$cli scope set $SELF $scopeArgs")
        Log2.i("lsposed scope: ${res.trim()}")

        // reload hooks in the targeted apps that are currently running
        if (!allApps) targets.forEach { if (it.isNotBlank()) RootShell.exec("am force-stop $it") }

        val ok = res.contains("overwrote", true) || res.contains("Success", true)
        return if (ok) Result(Code.OK, pkgs.size - 2, res.trim()) else Result(Code.FAILED, 0, res.trim())
    }
}
