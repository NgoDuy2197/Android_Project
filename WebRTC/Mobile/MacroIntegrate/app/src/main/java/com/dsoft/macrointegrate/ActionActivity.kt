package com.dsoft.macrointegrate

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

/**
 * Headless entry point MacroDroid triggers (Launch Activity / Shortcut / Intent).
 * It runs one action, shows a toast, and finishes with no visible UI.
 *
 * Accepted ways to pick the action (any one):
 *  - component shortcut (see shortcuts.xml): extra "action" = "location" | "screenshot"
 *  - intent action:  com.dsoft.macrointegrate.SEND_LOCATION / .SEND_SCREENSHOT
 *  - string extra "action": "location" | "screenshot"
 *
 * MacroDroid can optionally override per-call: string extras "webhook" and "message".
 */
class ActionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val what = when {
            intent.action == ACTION_LOCATION -> "location"
            intent.action == ACTION_SCREENSHOT -> "screenshot"
            else -> intent.getStringExtra("action")?.lowercase()?.trim()
        }
        when (what) {
            "location", "loc", "gps" -> doLocation()
            "screenshot", "screen", "shot" -> doScreenshot()
            else -> {
                toast(this, "Thiếu action (location / screenshot)")
                finish()
            }
        }
    }

    private fun prefs() = Prefs(this).also { p ->
        intent.getStringExtra("webhook")?.takeIf { it.isNotBlank() }?.let { oneShotWebhook = it.trim() }
        intent.getStringExtra("message")?.let { oneShotMessage = it }
        hasWebhook = p.webhook.isNotBlank()
    }

    private var oneShotWebhook: String? = null
    private var oneShotMessage: String? = null
    private var hasWebhook = false

    private fun webhook() = oneShotWebhook ?: Prefs(this).webhook

    private fun doLocation() {
        prefs()
        if (webhook().isBlank()) { toast(this, "Chưa cấu hình webhook Discord"); return finish() }
        if (!Locations.hasPermission(this)) { toast(this, "Chưa cấp quyền vị trí — mở app để cấp"); return finish() }
        toast(this, "Đang lấy vị trí…")
        val app = applicationContext
        val user = Prefs(this).username
        val hook = webhook()
        val prefix = oneShotMessage
        Locations.current(this) { loc ->
            Thread {
                val res = if (loc == null) {
                    Discord.sendText(hook, user, (prefix?.plus("\n") ?: "") + "📍 Không lấy được vị trí (bật GPS và thử lại).")
                } else {
                    Discord.sendText(hook, user, (prefix?.plus("\n") ?: "") + Locations.describe(loc))
                }
                toast(app, if (res.ok) "Đã gửi vị trí" else res.message)
            }.start()
            finish()
        }
    }

    private fun doScreenshot() {
        prefs()
        if (webhook().isBlank()) { toast(this, "Chưa cấu hình webhook Discord"); return finish() }
        // A per-call webhook can't reach the running service; it uses the saved one.
        if (CaptureService.captureAndSend(this)) {
            finish()
        } else {
            toast(this, "Bật 'Chụp màn hình' trong app một lần trước đã")
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
        }
    }

    companion object {
        const val ACTION_LOCATION = "com.dsoft.macrointegrate.SEND_LOCATION"
        const val ACTION_SCREENSHOT = "com.dsoft.macrointegrate.SEND_SCREENSHOT"

        fun toast(ctx: Context, s: String) = Toast.makeText(ctx, s, Toast.LENGTH_SHORT).show()
    }
}
