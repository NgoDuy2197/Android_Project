package com.dsoft.remotelink.input

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.dsoft.remotelink.Log2

/** No-root input via the AccessibilityService's gesture + global-action APIs. */
class AccessibilityInjector : InputInjector {

    private val svc: RemoteAccessibilityService? get() = RemoteAccessibilityService.instance

    private var lastStroke: GestureDescription.StrokeDescription? = null
    private var lastX = 0f
    private var lastY = 0f

    private fun dispatch(stroke: GestureDescription.StrokeDescription) {
        val s = svc ?: run { Log2.w("no accessibility service — enable it in Settings"); return }
        val gd = GestureDescription.Builder().addStroke(stroke).build()
        s.dispatchGesture(gd, null, null)
    }

    override fun tap(x: Float, y: Float) {
        val p = Path().apply { moveTo(x, y) }
        dispatch(GestureDescription.StrokeDescription(p, 0, 1))
    }

    override fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long) {
        val p = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        dispatch(GestureDescription.StrokeDescription(p, 0, durationMs.coerceIn(1, 60_000)))
    }

    // Continuous drag via continued strokes (API 26+); below that, fall back to discrete taps/swipes.
    override fun touchDown(x: Float, y: Float, pointerId: Int) {
        if (Build.VERSION.SDK_INT < 26) { lastX = x; lastY = y; return }
        val p = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(p, 0, GESTURE_STEP_MS, true)
        lastStroke = stroke; lastX = x; lastY = y
        dispatch(stroke)
    }

    override fun touchMove(x: Float, y: Float, pointerId: Int) {
        if (Build.VERSION.SDK_INT < 26) { lastX = x; lastY = y; return }
        val prev = lastStroke ?: return
        val p = Path().apply { moveTo(lastX, lastY); lineTo(x, y) }
        val stroke = prev.continueStroke(p, 0, GESTURE_STEP_MS, true)
        lastStroke = stroke; lastX = x; lastY = y
        dispatch(stroke)
    }

    override fun touchUp(x: Float, y: Float, pointerId: Int) {
        if (Build.VERSION.SDK_INT < 26) { swipeIfMoved(x, y); return }
        val prev = lastStroke
        if (prev == null) { tap(x, y); return }
        val p = Path().apply { moveTo(lastX, lastY); lineTo(x, y) }
        val stroke = prev.continueStroke(p, 0, GESTURE_STEP_MS, false)
        lastStroke = null
        dispatch(stroke)
    }

    private fun swipeIfMoved(x: Float, y: Float) {
        if (kotlin.math.abs(x - lastX) > 4 || kotlin.math.abs(y - lastY) > 4) swipe(lastX, lastY, x, y, 120)
        else tap(x, y)
    }

    override fun key(keyCode: Int) {
        when (keyCode) {
            KeyEvent.KEYCODE_BACK -> global("back")
            KeyEvent.KEYCODE_HOME -> global("home")
            KeyEvent.KEYCODE_APP_SWITCH -> global("recents")
            else -> Log2.w("accessibility can't inject keycode $keyCode (use root input mode)")
        }
    }

    override fun text(s: String) {
        val svc = svc ?: return
        val root = svc.rootInActiveWindow ?: return
        val focus = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return
        if (!focus.isEditable) return
        val existing = focus.text?.toString() ?: ""
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, existing + s)
        }
        focus.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    override fun global(action: String) {
        val s = svc ?: run { Log2.w("no accessibility service for global '$action'"); return }
        val code = when (action) {
            "back" -> AccessibilityService.GLOBAL_ACTION_BACK
            "home" -> AccessibilityService.GLOBAL_ACTION_HOME
            "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS
            "notifications" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            "quicksettings" -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
            "power" -> AccessibilityService.GLOBAL_ACTION_POWER_DIALOG
            "lock" -> if (Build.VERSION.SDK_INT >= 28) AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN else return
            else -> return
        }
        s.performGlobalAction(code)
    }

    companion object { private const val GESTURE_STEP_MS = 16L }
}
