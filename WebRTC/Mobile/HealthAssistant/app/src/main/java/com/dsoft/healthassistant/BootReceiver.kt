package com.dsoft.healthassistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Re-arm reminder alarms after a reboot (alarms don't survive a restart on their own). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            runCatching { Reminders.reschedule(ctx.applicationContext) }
        }
    }
}
