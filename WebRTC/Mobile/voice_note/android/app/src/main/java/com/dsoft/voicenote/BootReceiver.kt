package com.dsoft.voicenote

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** Starts recording after boot (if enabled) and resumes it after an app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val prefs = Prefs(ctx)
        val go = when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> prefs.autostart
            Intent.ACTION_MY_PACKAGE_REPLACED -> prefs.wantRunning
            else -> false
        }
        if (!go) return
        try {
            RecorderService.start(ctx)
        } catch (t: Throwable) {
            Log.e("VoiceNote", "autostart failed", t)
            RecorderService.notifyTapToStart(ctx)
        }
    }
}
