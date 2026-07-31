package com.example.cameraman

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Reads the phone's system notifications and forwards each to the Discord
 * webhook. The user must grant "Notification access" in system settings (this
 * class is what makes CameraMan appear in that list); forwarding also requires
 * the toggle in Config and a webhook to be set.
 *
 * Guards: never forwards our own notifications, skips group-summary duplicates,
 * and de-duplicates identical content per notification key within a short window
 * (apps re-post the same notification on every minor update).
 */
class NotiForwardService : NotificationListenerService() {

    // key -> (contentHash, timeMillis)
    private val recent = HashMap<String, Pair<Int, Long>>()

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        try {
            sbn ?: return
            if (sbn.packageName == packageName) return // don't forward ourselves

            val notification = sbn.notification ?: return
            if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return

            val cfg = AppConfig(this)
            if (!cfg.notiForwardEnabled) return
            val webhook = cfg.discordWebhook
            if (webhook.isEmpty()) return

            val extras = notification.extras ?: return
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
            val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
            if (title.isEmpty() && text.isEmpty()) return

            // Drop repeats of the same content for the same notification.
            val hash = (title + "" + text).hashCode()
            val now = System.currentTimeMillis()
            val prev = recent[sbn.key]
            if (prev != null && prev.first == hash && now - prev.second < 15_000L) return
            recent[sbn.key] = hash to now
            if (recent.size > 200) recent.clear() // keep the map bounded

            val app = appLabel(sbn.packageName)
            val msg = buildString {
                append("🔔 **").append(app).append("**")
                if (title.isNotEmpty()) append("\n").append(title)
                if (text.isNotEmpty()) append("\n").append(text)
            }
            DiscordNotifier.send(webhook, msg)
        } catch (_: Exception) {
            // A single bad notification must never crash the listener.
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // No action needed.
    }

    private fun appLabel(pkg: String): String {
        return try {
            val pm = packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Exception) {
            pkg
        }
    }
}
