package com.dsoft.volteguard

import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.delay

/**
 * Escalating recovery ladder, fastest / least disruptive first. After each step we re-check
 * every [POLL_SEC] seconds for up to the step's settle time; the first HEALTHY result stops it.
 */
object Recovery {

    private const val POLL_SEC = 2

    /** One recovery method, for the "test thủ công" buttons in the UI. */
    enum class StepId { VOLTE_RESTORE, VOLTE_SWITCH, CARRIER_CONFIG, RESET_IMS, DATA, MODEM }

    /**
     * Run a single method on demand (manual test button). Bypasses the confirm/cooldown logic
     * so the user can see which method actually brings HD Call back.
     */
    suspend fun runManualStep(ctx: Context, id: StepId): String {
        if (!ShizukuShell.hasPermission()) return "Cần Shizuku"
        val p = Prefs(ctx)
        val r = ImsProbe.probe(ctx, p.simSlot, p)
        if (r.subId < 0) return "Không có SIM ở khe đang theo dõi"
        return runCatching {
            when (id) {
                StepId.VOLTE_RESTORE -> restoreVolteSwitch(r)
                StepId.VOLTE_SWITCH -> kickVolteSwitch(r)
                StepId.CARRIER_CONFIG -> overrideConfig(r)
                StepId.RESET_IMS -> resetIms(r)
                StepId.DATA -> toggleData()
                StepId.MODEM -> rebootModem(r)
            }
        }.getOrElse { "lỗi ${it.javaClass.simpleName}: ${it.message}" }
    }

    /** @param settleSec max wait after the step; null = user setting (slow radio steps). */
    private class Step(val title: String, val settleSec: Int?, val run: suspend (Context, CheckReport) -> String)

    fun hasWriteSecureSettings(ctx: Context) =
        ctx.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED

    private fun plan(ctx: Context, r: CheckReport, p: Prefs): List<Step> {
        val sh = ShizukuShell.hasPermission()
        val steps = mutableListOf<Step>()
        // Fastest & most reliable in field testing (= test_fault.bat "restore"): just force the
        // VoLTE / 4G-Calling switch ON. Re-registers IMS almost immediately.
        if (p.stepVolteRestore && sh)
            steps += Step("Bật lại công tắc VoLTE (restore)", 15) { _, rep -> restoreVolteSwitch(rep) }
        // ~3s: flipping the VoLTE switch off→on forces an immediate IMS re-registration.
        if (p.stepVolteSwitch && sh)
            steps += Step("Nháy công tắc VoLTE (tắt→bật)", 20) { _, rep -> kickVolteSwitch(rep) }
        // instant, only meaningful when the Pixel IMS override was dropped.
        if (p.stepCarrierConfig && sh && r.failed(ImsProbe.K_CARRIER))
            steps += Step("Áp lại carrier config VoLTE", 15) { _, rep -> overrideConfig(rep) }
        // ~5s
        if (p.stepResetIms && sh)
            steps += Step("Reset IMS stack", 20) { _, rep -> resetIms(rep) }
        // ~5s, re-attaches PDN
        if (p.stepData && sh)
            steps += Step("Tắt/bật mobile data", 20) { _, _ -> toggleData() }
        if (p.stepModem && sh)
            steps += Step("Khởi động lại modem", null) { _, rep -> rebootModem(rep) }
        return steps
    }

    /** @return true if the SIM is healthy again. */
    suspend fun run(ctx: Context, first: CheckReport, onPhase: (String) -> Unit): Boolean {
        val p = Prefs(ctx)
        val steps = plan(ctx, first, p)
        if (steps.isEmpty()) {
            LogStore.throttled("nosteps", 300_000, "Khắc phục: không có bước nào khả dụng (thiếu Shizuku / WRITE_SECURE_SETTINGS hoặc đã tắt hết bước)")
            return false
        }
        LogStore.i("Khắc phục SIM ${first.slot + 1}: lỗi=${first.failedKeys()} · ${steps.size} bước")
        var report = first
        for ((i, step) in steps.withIndex()) {
            val label = "Bước ${i + 1}/${steps.size}: ${step.title}"
            if (i > 0 && report.health == Health.NO_SERVICE) {
                // Radio still re-attaching after the previous step: give it one more window.
                report = waitHealthy(ctx, report.slot, p.settleSec) { left -> onPhase("Chờ sóng ổn định · ${left}s") }
                if (report.health == Health.HEALTHY) {
                    LogStore.i("✅ Khôi phục thành công (sau khi sóng ổn định): ${report.summary}")
                    return true
                }
            }
            if (report.blockReason == BLOCK_IN_CALL || (i == 0 && report.blockReason != null)) {
                LogStore.i("Dừng khắc phục: ${report.blockReason}")
                return false
            }
            onPhase(label)
            GuardService.scheduleAlarm(ctx, 15_000, padMs = 0)
            val res = runCatching { step.run(ctx, report) }.getOrElse { "lỗi ${it.javaClass.simpleName}: ${it.message}" }
            LogStore.i("$label → $res")

            report = waitHealthy(ctx, report.slot, step.settleSec ?: p.settleSec) { left -> onPhase("$label · chờ ${left}s") }
            if (report.health == Health.HEALTHY) {
                LogStore.i("✅ Khôi phục thành công sau ${step.title}: ${report.summary}")
                return true
            }
            LogStore.i("Sau ${step.title}: ${report.health} · ${report.summary}")
        }
        LogStore.i("❌ Đã thử hết ${steps.size} bước, chưa khôi phục được")
        return false
    }

    private suspend fun waitHealthy(ctx: Context, slot: Int, settleSec: Int, tick: (Int) -> Unit): CheckReport {
        val prefs = Prefs(ctx)
        var left = settleSec.coerceAtLeast(POLL_SEC)
        var r: CheckReport
        while (true) {
            val chunk = minOf(POLL_SEC, left)
            tick(left)
            // Keep an alarm armed so a frozen process (ColorOS Hans) is woken to finish the ladder.
            GuardService.scheduleAlarm(ctx, chunk * 1000L, padMs = 1_000)
            delay(chunk * 1000L)
            left -= chunk
            r = ImsProbe.probe(ctx, slot, prefs)
            Engine.publish(r)
            if (r.health == Health.HEALTHY || left <= 0) return r
        }
    }

    // ---- Steps ---------------------------------------------------------------------------

    private fun overrideConfig(r: CheckReport): String =
        PhoneHidden.overrideVolteConfig(r.subId)?.let { it.error ?: "OK (overrideConfig persistent)" } ?: "không có binder"

    /**
     * Force the VoLTE / 4G-Calling switch ON (no off-flip). Identical to test_fault.bat "restore"
     * (`service call phone <code> i32 <sub> i32 1`) which brings HD back almost instantly.
     */
    private fun restoreVolteSwitch(r: CheckReport): String {
        val was = PhoneHidden.isAdvancedCallingEnabled(r.subId)?.value
        return "was=$was · on:${setVolteSwitch(r.subId, true)}"
    }

    /** Switch on if off; if already on, flip off -> on to force IMS re-registration. */
    private suspend fun kickVolteSwitch(r: CheckReport): String {
        val wasOn = PhoneHidden.isAdvancedCallingEnabled(r.subId)?.value
        val out = StringBuilder("wasOn=$wasOn")
        if (wasOn == true) {
            out.append(" · off:").append(setVolteSwitch(r.subId, false))
            delay(1500)
        }
        out.append(" · on:").append(setVolteSwitch(r.subId, true))
        return out.toString()
    }

    private fun setVolteSwitch(subId: Int, on: Boolean): String {
        val b = PhoneHidden.setAdvancedCallingEnabled(subId, on)
        if (b != null && b.error == null) return "binder OK"
        // Same call the test script uses: `service call phone <code> i32 sub i32 0|1` as shell.
        val code = PhoneHidden.transactionCode("setAdvancedCallingSettingEnabled")
            ?: return "binder lỗi ${b?.error} · không tìm được transaction code"
        val sh = ShizukuShell.exec("service call phone $code i32 $subId i32 ${if (on) 1 else 0}")
        return "binder lỗi ${b?.error} · service call[$sh]"
    }

    private suspend fun resetIms(r: CheckReport): String {
        // Shell command first: it also re-enables IMS if something left it disabled.
        val off = ShizukuShell.exec("cmd phone ims disable -s ${r.slot}")
        delay(3000)
        val on = ShizukuShell.exec("cmd phone ims enable -s ${r.slot}")
        if (on.ok) return "cmd ims disable[$off] enable[$on]"
        val viaBinder = PhoneHidden.resetIms(r.slot)
        return "cmd ims enable lỗi[$on] · resetIms: ${viaBinder?.error ?: if (viaBinder != null) "OK" else "n/a"}"
    }

    private suspend fun toggleData(): String {
        val off = ShizukuShell.exec("svc data disable")
        delay(2000)
        val on = ShizukuShell.exec("svc data enable")
        return "svc data disable[$off] enable[$on]"
    }

    private fun rebootModem(r: CheckReport): String {
        val res = PhoneHidden.rebootModem(r.slot)
        if (res?.value == true) return "ITelephony.rebootModem OK"
        val cmd = ShizukuShell.exec("cmd phone restart-modem")
        return "rebootModem: ${res?.error ?: res?.value} · cmd phone restart-modem[$cmd]"
    }
}
