package com.dsoft.remotelink.input

/**
 * Injects input into the device. Coordinates are REAL screen pixels (the server converts the
 * client's normalized 0..1 coords using the live display size before calling here).
 *
 * Implementations: [AccessibilityInjector] (grant-once, no root) and [RootInjector] (su).
 */
interface InputInjector {
    fun tap(x: Float, y: Float)
    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long)

    // continuous pointer (dragging) — pointerId lets a few impls support multitouch
    fun touchDown(x: Float, y: Float, pointerId: Int)
    fun touchMove(x: Float, y: Float, pointerId: Int)
    fun touchUp(x: Float, y: Float, pointerId: Int)

    /** Android KeyEvent keycode. */
    fun key(keyCode: Int)
    fun text(s: String)

    /** "back" | "home" | "recents" | "notifications" | "quicksettings" | "power" | "lock". */
    fun global(action: String)

    fun release() {}
}
