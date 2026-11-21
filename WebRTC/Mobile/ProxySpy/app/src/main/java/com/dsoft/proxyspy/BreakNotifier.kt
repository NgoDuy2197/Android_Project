package com.dsoft.proxyspy

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build

/**
 * A frozen call is useless if the user is looking at the app under test rather than at ProxySpy,
 * so every breakpoint also raises a heads-up notification that jumps straight into the editor.
 */
object BreakNotifier {

    private fun nm() = App.instance.getSystemService(NotificationManager::class.java)

    fun show(askId: Long) {
        val p = Hub.pending[askId] ?: return
        val ctx = App.instance
        val intent = Intent(ctx, BreakActivity::class.java)
            .putExtra("askId", askId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        val pi = PendingIntent.getActivity(ctx, askId.toInt(), intent, flags)

        val go = PendingIntent.getBroadcast(
            ctx, (askId + 1_000_000).toInt(),
            Intent(ctx, BreakReceiver::class.java).putExtra("askId", askId), flags,
        )

        val title = when (p.stage) {
            com.dsoft.proxyspy.Wire.ST_REQ -> "Đang dừng REQUEST"
            com.dsoft.proxyspy.Wire.ST_RES -> "Đang dừng RESPONSE"
            else -> "Đang dừng FRAME WS"
        }
        val b = (if (Build.VERSION.SDK_INT >= 26) Notification.Builder(ctx, App.CH_BREAK)
        else @Suppress("DEPRECATION") Notification.Builder(ctx))
            .setContentTitle("$title · ${p.conn.pkg}")
            .setContentText(p.url.take(120))
            .setStyle(Notification.BigTextStyle().bigText(p.url))
            .setSmallIcon(android.R.drawable.ic_media_pause)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    null as android.graphics.drawable.Icon?, "Cho đi tiếp", go
                ).build()
            )
        nm().notify(askId.toInt(), b.build())
    }

    fun dismiss(askId: Long) = runCatching { nm().cancel(askId.toInt()) }
}
