package com.dsoft.toolbox

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import kotlin.math.max

/**
 * A black, click-through window laid over the whole screen; alpha = darkness.
 *
 * Android 12+ blocks touches that pass through an app overlay more opaque
 * than 0.8 (untrusted-touch protection), so a normal overlay is capped at
 * [APP_MAX]. An accessibility overlay is trusted and may go darker.
 */
class DimOverlay(private val ctx: Context, private val type: Int) {
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private var view: View? = null

    fun show(alpha: Float) {
        val a = if (type == appType() && Build.VERSION.SDK_INT >= 31) alpha.coerceAtMost(APP_MAX) else alpha
        val v = view
        if (v != null) {
            val lp = v.layoutParams as WindowManager.LayoutParams
            lp.alpha = a
            runCatching { wm.updateViewLayout(v, lp) }
            return
        }
        val m = ctx.resources.displayMetrics
        val side = (max(m.widthPixels, m.heightPixels) * 1.6f).toInt() // covers bars + rotation
        val lp = WindowManager.LayoutParams(
            side, side, type,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.CENTER
            this.alpha = a
            if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            title = "Toolbox dimmer"
        }
        val nv = View(ctx).apply { setBackgroundColor(Color.BLACK) }
        try {
            wm.addView(nv, lp)
            view = nv
        } catch (_: Exception) {
        }
    }

    fun hide() {
        view?.let { runCatching { wm.removeView(it) } }
        view = null
    }

    companion object {
        const val APP_MAX = 0.79f

        @Suppress("DEPRECATION")
        fun appType() = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else WindowManager.LayoutParams.TYPE_PHONE

        /** Darkness (%) the user actually gets with the current setup. */
        fun effective(level: Int): Int =
            if (DimAccessibility.instance == null && Build.VERSION.SDK_INT >= 31) minOf(level, (APP_MAX * 100).toInt()) else level
    }
}
