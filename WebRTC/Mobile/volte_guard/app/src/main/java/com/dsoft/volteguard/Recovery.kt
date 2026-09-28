package com.dsoft.volteguard

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import kotlinx.coroutines.delay

/**
 * Escalating recovery ladder. After each step we wait up to [Prefs.settleSec] re-checking
 * every few seconds; the first HEALTHY result stops the ladder.
 */
object Recovery {

    private class Step(val title: String, val run: suspend (Context, CheckReport) -> String)

    fun hasWriteSecureSettings(ctx: Context) =
        ctx.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED

    private fun plan(ctx: Context, r: CheckReport, p: Prefs): List<Step> {
        val sh = ShizukuShell.hasPermission()
        val steps = mutableListOf<Step>()
        if (p.stepCarrierConfig && sh && r.failed(ImsProbe.K_CARRIER))
            steps += Step("Áp lại carrier config VoLTE") { _, rep -> overrideConfig(rep) }
        if (p.stepVolteSwitch && sh && r.failed(ImsProbe.K_SWITCH))
            steps += Step("Bật công tắc VoLTE") { _, rep -> enableSwitch(rep) }
        if (p.stepResetIms && sh)
            steps += Step("Reset IMS stack") { _, rep -> resetIms(rep) }
        if (p.stepData && sh)
            steps += Step("Tắt/bật mobile data") { _, _ -> toggleData() }
        if (p.stepAirplane && (sh || hasWriteSecureSettings(ctx)))
            steps += Step("Bật/tắt chế độ máy bay") { c, _ -> toggleAirplane(c) }
        if (p.stepModem && sh)
            steps += Step("Khởi động lại modem") { _, rep -> rebootModem(rep) }
        return steps
    }

    /** @return true if the SIM is healthy again. */
    suspend fun run(ctx: Context, first: CheckReport, onPhase: (String) -> Unit): Boolean {
        val p = Prefs(ctx)
        val steps = plan(ctx, first, p)
        if (steps.isEmpty()) {
            LogStore.i("Khắc phục: không có bước nào khả dụng (thiếu Shizuku / WRITE_SECURE_SETTINGS hoặc đã tắt hết bước)")
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
            val res = runCatching { step.run(ctx, report) }.getOrElse { "lỗi ${it.javaClass.simpleName}: ${it.message}" }
            LogStore.i("$label → $res")

            report = waitHealthy(ctx, report.slot, p.settleSec) { left -> onPhase("$label · chờ ${left}s") }
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
        var left = settleSec.coerceAtLeast(5)
        var r: CheckReport
        while (true) {
            val chunk = minOf(5, left)
            tick(left)
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

    private fun enableSwitch(r: CheckReport): String =
        PhoneHidden.setAdvancedCallingEnabled(r.subId, true)?.let { it.error ?: "OK" } ?: "không có binder"

    private suspend fun resetIms(r: CheckReport): String {
        val viaBinder = PhoneHidden.resetIms(r.slot)
        if (viaBinder != null && viaBinder.error == null) return "ITelephony.resetIms OK"
        // Fallback: telephony shell command (Android 10+).
        val off = ShizukuShell.exec("cmd phone ims disable -s ${r.slot}")
        delay(3000)
        val on = ShizukuShell.exec("cmd phone ims enable -s ${r.slot}")
        return "resetIms: ${viaBinder?.error ?: "n/a"} · cmd ims disable[$off] enable[$on]"
    }

    private suspend fun toggleData(): String {
        val off = ShizukuShell.exec("svc data disable")
        delay(4000)
        val on = ShizukuShell.exec("svc data enable")
        return "svc data disable[$off] enable[$on]"
    }

    private suspend fun toggleAirplane(ctx: Context): String {
        if (ShizukuShell.hasPermission()) {
            val on = ShizukuShell.exec("cmd connectivity airplane-mode enable")
            if (on.ok) {
                delay(6000)
                val off = ShizukuShell.exec("cmd connectivity airplane-mode disable")
                if (off.ok) return "cmd connectivity airplane-mode OK"
                // Never leave the phone in airplane mode.
                ShizukuShell.exec("settings put global airplane_mode_on 0")
                return "disable lỗi [$off] → đã ghi setting=0"
            }
            LogStore.i("cmd connectivity airplane-mode lỗi [$on], thử WRITE_SECURE_SETTINGS")
        }
        if (!hasWriteSecureSettings(ctx)) return "không có quyền"
        // Legacy path: only writes the setting + broadcast; the broadcast is protected on
        // modern Android so this may not actually cycle the radio.
        val cr = ctx.contentResolver
        Settings.Global.putInt(cr, Settings.Global.AIRPLANE_MODE_ON, 1)
        val b1 = runCatching {
            ctx.sendBroadcast(Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED).putExtra("state", true)); "ok"
        }.getOrElse { it.javaClass.simpleName }
        delay(6000)
        Settings.Global.putInt(cr, Settings.Global.AIRPLANE_MODE_ON, 0)
        runCatching { ctx.sendBroadcast(Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED).putExtra("state", false)) }
        return "WRITE_SECURE_SETTINGS (broadcast=$b1)"
    }

    private fun rebootModem(r: CheckReport): String {
        val res = PhoneHidden.rebootModem(r.slot)
        if (res?.value == true) return "ITelephony.rebootModem OK"
        return "rebootModem: ${res?.error ?: res?.value}"
    }
}
