package com.dsoft.remotelink.net

import com.dsoft.remotelink.Prefs
import com.dsoft.remotelink.capture.ScreenCapturer
import com.dsoft.remotelink.input.InputInjector

/** Owns the capturer + injector and starts whichever servers are enabled. */
class ServerManager(
    private val capturer: ScreenCapturer,
    private val injector: InputInjector,
    private val realW: Int,
    private val realH: Int,
    private val prefs: Prefs,
) {
    private var vnc: VncServer? = null
    private var stream: StreamServer? = null

    fun start() {
        capturer.start()
        if (prefs.enableVnc) VncServer(capturer, injector, realW, realH, prefs).also { it.start() }.let { vnc = it }
        if (prefs.enableStream) StreamServer(capturer, injector, realW, realH, prefs).also { it.start() }.let { stream = it }
    }

    fun stop() {
        runCatching { vnc?.stop() }; vnc = null
        runCatching { stream?.stop() }; stream = null
        runCatching { injector.release() }
        runCatching { capturer.stop() }
    }

    fun vncClients() = vnc?.clientCount() ?: 0
    fun streamClients() = stream?.clientCount() ?: 0
}
