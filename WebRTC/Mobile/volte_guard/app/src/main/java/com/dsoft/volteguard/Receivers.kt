package com.dsoft.volteguard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (Prefs(ctx).enabled) {
            LogStore.i("${intent.action?.substringAfterLast('.')} → khởi động giám sát")
            runCatching { GuardService.start(ctx) }.onFailure { LogStore.i("Không start được service: ${it.message}") }
        }
    }
}

/** Doze-proof heartbeat: exact alarms may start / kick the foreground service. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (Prefs(ctx).enabled) runCatching { GuardService.start(ctx, GuardService.ACTION_ALARM) }
    }
}
