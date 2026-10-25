package com.dsoft.odiary

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView

/** Base activity: applies the theme bg, overlays [FxView] for falling + tap effects. */
abstract class FxActivity : Activity() {
    protected lateinit var p: Palette
    private var bgFx: FxView? = null   // falling effect — BEHIND the UI
    private var fgFx: FxView? = null   // tap bursts — in front
    private var stamp: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        p = palette()
        stamp = Prefs.styleStamp(this)
    }

    override fun onPause() {
        super.onPause()
        // Push changes to Drive in the background when leaving a screen (no-op unless enabled + dirty).
        AutoSync.maybeSync(this)
    }

    override fun onResume() {
        super.onResume()
        // Recreate if theme/style changed on another screen.
        if (Prefs.styleStamp(this) != stamp || p.t.id != Prefs.themeId(this)) recreate()
        else {
            bgFx?.apply { fallType = Prefs.fallEffect(this@FxActivity); invalidate() }
            fgFx?.apply { tapType = Prefs.tapEffect(this@FxActivity) }
        }
        // Retry any pending Drive sync on app launch / return to foreground.
        AutoSync.maybeSync(this)
    }

    /** Wraps [content]: falling effect BEHIND, UI in the middle, tap bursts in front. */
    protected fun setFxContent(content: View) {
        window.statusBarColor = p.bg
        val root = FrameLayout(this).apply { setBackgroundColor(p.bg) }
        val mp = { FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT) }
        // 0) app wallpaper (image + brightness/blur) — at the very back
        addWallpaper(root, mp)
        // 1) falling effect — behind everything
        bgFx = FxView(this).apply {
            fallType = Prefs.fallEffect(this@FxActivity); tapType = "none"
            tint = p.accent; tint2 = p.accent2; isClickable = false; isFocusable = false
        }
        root.addView(bgFx, mp())
        // 2) the actual UI
        root.addView(content, mp())
        // 3) tap bursts — on top so sparkles show over where you touch
        fgFx = FxView(this).apply {
            fallType = "none"; tapType = Prefs.tapEffect(this@FxActivity)
            tint = p.accent; tint2 = p.accent2; isClickable = false; isFocusable = false
        }
        root.addView(fgFx, mp())
        setContentView(root)
    }

    /** Draws the configured app wallpaper (with blur + a brightness/dim overlay) behind all content. */
    private fun addWallpaper(root: FrameLayout, mp: () -> FrameLayout.LayoutParams) {
        if (!Prefs.appBgOn(this)) return
        val f = Store.appWallpaperFile()
        if (!f.exists()) return
        val bmp = try { Store.decodeSampled(f, 1280) } catch (_: Exception) { null } ?: return
        val shown = try { Store.downscaleBlur(bmp, Prefs.appBgBlur(this)) } catch (_: Exception) { bmp }
        root.addView(ImageView(this).apply {
            setImageBitmap(shown); scaleType = ImageView.ScaleType.CENTER_CROP
            isClickable = false; isFocusable = false
        }, mp())
        // brightness: negative dim => darken, positive => lighten
        val dim = Prefs.appBgDim(this)
        if (dim != 0) {
            val a = (kotlin.math.abs(dim) * 255 / 100).coerceIn(0, 230)
            val overlay = if (dim < 0) Color.argb(a, 0, 0, 0) else Color.argb(a, 255, 255, 255)
            root.addView(View(this).apply { setBackgroundColor(overlay); isClickable = false; isFocusable = false }, mp())
        }
    }

    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) fgFx?.let {
            val loc = IntArray(2); it.getLocationOnScreen(loc)
            it.burst(e.rawX - loc[0], e.rawY - loc[1])
        }
        return super.dispatchTouchEvent(e)
    }
}
