package com.dsoft.noti_forward

import android.os.Handler
import android.os.Looper
import io.flutter.plugin.common.EventChannel

/**
 * Best-effort bridge that pushes captured notifications to the Flutter UI for
 * the on-screen log. Events are only delivered while the app is open and an
 * EventChannel listener is attached; the actual forwarding always happens
 * natively in [NotiForwardService], independent of this bus.
 */
object NotiBus {

    @Volatile
    private var sink: EventChannel.EventSink? = null
    private val main = Handler(Looper.getMainLooper())

    fun setSink(s: EventChannel.EventSink?) {
        sink = s
    }

    fun emit(pkg: String, title: String, content: String) {
        val s = sink ?: return
        val payload = mapOf(
            "package" to pkg,
            "title" to title,
            "content" to content,
        )
        main.post {
            try {
                s.success(payload)
            } catch (_: Exception) {
                // Listener went away between the null-check and delivery.
            }
        }
    }
}
