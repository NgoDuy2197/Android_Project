package com.dsoft.remotelink.input

import android.view.KeyEvent
import com.dsoft.remotelink.capture.RootShell

/** Root input via the `input` command over a persistent su shell. */
class RootInjector : InputInjector {
    private val sh = RootShell.Session()

    private var downX = 0f
    private var downY = 0f

    override fun tap(x: Float, y: Float) = sh.run("input tap ${x.toInt()} ${y.toInt()}")

    override fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long) =
        sh.run("input swipe ${x1.toInt()} ${y1.toInt()} ${x2.toInt()} ${y2.toInt()} ${durationMs.coerceIn(1, 60_000)}")

    // `input` has no persistent pointer; approximate a drag as down->up swipe.
    override fun touchDown(x: Float, y: Float, pointerId: Int) { downX = x; downY = y }
    override fun touchMove(x: Float, y: Float, pointerId: Int) { /* accumulated into the final swipe */ }
    override fun touchUp(x: Float, y: Float, pointerId: Int) {
        if (kotlin.math.abs(x - downX) > 4 || kotlin.math.abs(y - downY) > 4) swipe(downX, downY, x, y, 120)
        else tap(x, y)
    }

    override fun key(keyCode: Int) = sh.run("input keyevent $keyCode")

    override fun text(s: String) {
        val esc = s.replace("\\", "\\\\").replace(" ", "%s").replace("\"", "\\\"")
        sh.run("input text \"$esc\"")
    }

    override fun global(action: String) {
        val code = when (action) {
            "back" -> KeyEvent.KEYCODE_BACK
            "home" -> KeyEvent.KEYCODE_HOME
            "recents" -> KeyEvent.KEYCODE_APP_SWITCH
            "power" -> KeyEvent.KEYCODE_POWER
            "notifications" -> { sh.run("cmd statusbar expand-notifications"); return }
            "quicksettings" -> { sh.run("cmd statusbar expand-settings"); return }
            "lock" -> KeyEvent.KEYCODE_POWER
            else -> return
        }
        key(code)
    }

    override fun release() = sh.close()
}
