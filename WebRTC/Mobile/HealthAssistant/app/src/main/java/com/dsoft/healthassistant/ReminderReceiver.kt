package com.dsoft.healthassistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Fires when a reminder alarm goes off and posts the matching notification. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val kind = intent.getStringExtra(Reminders.EXTRA_KIND) ?: "meal"
        Reminders.show(ctx.applicationContext, kind)
    }
}
