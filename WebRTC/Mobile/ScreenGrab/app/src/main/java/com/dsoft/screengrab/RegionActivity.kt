package com.dsoft.screengrab

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast

/** Fullscreen translucent overlay to drag out the capture region. Saves it (in real screen px) to Prefs. */
class RegionActivity : Activity() {

    private lateinit var picker: PickerView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        goFullscreen()
        val root = FrameLayout(this)
        picker = PickerView(this)
        root.addView(picker, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        bar.addView(btn("Toàn màn hình") { saveFull() }, lp())
        bar.addView(btn("Lưu vùng") { saveRegion() }, lp())
        bar.addView(btn("Huỷ") { finish() }, lp())
        root.addView(bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        setContentView(root)
        Toast.makeText(this, "Kéo để chọn vùng cần quay", Toast.LENGTH_SHORT).show()
    }

    private fun goFullscreen() {
        window.setFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.hide(android.view.WindowInsets.Type.systemBars())
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
        }
    }

    private fun realSize(): Pair<Int, Int> {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.currentWindowMetrics.bounds; b.width() to b.height()
        } else {
            val dm = android.util.DisplayMetrics(); @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(dm)
            dm.widthPixels to dm.heightPixels
        }
    }

    private fun saveFull() {
        val (w, h) = realSize()
        Prefs(this).apply { regionMode = "full"; saveRegion(Rect(0, 0, w, h)) }
        Toast.makeText(this, "Đã đặt: toàn màn hình", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun saveRegion() {
        val sel = picker.selection
        if (sel == null || sel.width() < dp(20) || sel.height() < dp(20)) {
            Toast.makeText(this, "Hãy kéo một vùng lớn hơn", Toast.LENGTH_SHORT).show(); return
        }
        val (rw, rh) = realSize()
        val vw = picker.width.coerceAtLeast(1); val vh = picker.height.coerceAtLeast(1)
        val sx = rw.toFloat() / vw; val sy = rh.toFloat() / vh
        val r = Rect(
            (sel.left * sx).toInt().coerceIn(0, rw), (sel.top * sy).toInt().coerceIn(0, rh),
            (sel.right * sx).toInt().coerceIn(0, rw), (sel.bottom * sy).toInt().coerceIn(0, rh)
        )
        Prefs(this).apply { regionMode = "region"; saveRegion(r) }
        Toast.makeText(this, "Đã lưu vùng ${r.width()}x${r.height()}px", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun lp() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(5); marginEnd = dp(5) }
    private fun btn(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; setTextColor(Color.WHITE)
        setBackgroundColor(Color.parseColor("#7C3AED"))
        setOnClickListener { onClick() }
    }

    /** Draws a dim overlay with a transparent selection rectangle. */
    private class PickerView(ctx: Activity) : View(ctx) {
        var selection: Rect? = null
        private var startX = 0f; private var startY = 0f
        private val dim = Paint().apply { color = Color.parseColor("#99000000") }
        private val clear = Paint().apply { color = Color.TRANSPARENT; xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR) }
        private val border = Paint().apply { color = Color.parseColor("#EC4899"); style = Paint.Style.STROKE; strokeWidth = 5f; isAntiAlias = true }

        init { setLayerType(LAYER_TYPE_SOFTWARE, null) }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { startX = e.x; startY = e.y; selection = Rect(e.x.toInt(), e.y.toInt(), e.x.toInt(), e.y.toInt()) }
                MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                    selection = Rect(
                        minOf(startX, e.x).toInt(), minOf(startY, e.y).toInt(),
                        maxOf(startX, e.x).toInt(), maxOf(startY, e.y).toInt()
                    ); invalidate()
                }
            }
            return true
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dim)
            selection?.let {
                canvas.drawRect(it, clear)
                canvas.drawRect(it, border)
            }
        }
    }
}
