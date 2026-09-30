package com.dsoft.volteguard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (!Prefs(ctx).enabled) return
        LogStore.i("${intent.action?.substringAfterLast('.')} → khởi động giám sát")
        runCatching { GuardService.start(ctx) }
            .onFailure { LogStore.i("Không start được service: ${it.message}") }
        // Backup: an exact alarm re-attempts the start a few seconds later, in case the foreground
        // service launch was throttled during boot. AlarmReceiver starts the service again.
        runCatching { GuardService.scheduleAlarm(ctx, 8_000, padMs = 0) }
        // If Shizuku happens to be already up (e.g. root / wireless-debug autostart), bring the
        // shell watchdog back too — it dies on reboot otherwise.
        if (ShizukuShell.hasPermission()) {
            Engine.scope.launch { LogStore.i("Boot · watchdog: ${Watchdog.start(ctx.applicationContext)}") }
        }
    }
}

/** Doze-proof heartbeat: exact alarms may start / kick the foreground service. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (Prefs(ctx).enabled) runCatching { GuardService.start(ctx, GuardService.ACTION_ALARM) }
    }
}

/**
 * Fault injection for testing, adb only (guarded by android.permission.DUMP):
 *   adb shell am broadcast -n com.dsoft.volteguard/.DebugReceiver --es fault volte_off|carrier_off
 */
class DebugReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val fault = intent.getStringExtra("fault") ?: return
        val slot = Prefs(ctx).simSlot
        val sub = runCatching {
            ctx.getSystemService(android.telephony.SubscriptionManager::class.java)
                .getActiveSubscriptionInfoForSimSlotIndex(slot)?.subscriptionId
        }.getOrNull() ?: return
        val res = when (fault) {
            "volte_off" -> PhoneHidden.setAdvancedCallingEnabled(sub, false)?.let { it.error ?: "OK" }
            "carrier_off" -> PhoneHidden.overrideVolteConfig(sub, enabled = false, persistent = false)?.let { it.error ?: "OK" }
            // Binder codes for `adb shell service call phone <code> ...` (fault injection without Shizuku).
            "codes" -> runCatching {
                val stub = Class.forName("com.android.internal.telephony.ITelephony\$Stub")
                listOf("setAdvancedCallingSettingEnabled", "isImsRegistered").joinToString { n ->
                    val f = stub.getDeclaredField("TRANSACTION_$n").apply { isAccessible = true }
                    "$n=${f.getInt(null)}"
                }
            }.getOrElse { "${it.javaClass.simpleName}: ${it.message}" }
            else -> "unknown fault"
        }
        LogStore.i("🧪 DEBUG fault=$fault sub=$sub → ${res ?: "không có Shizuku"}")
    }
}
