package com.dsoft.toolbox

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import kotlin.math.min

/**
 * Full-screen screenshot with translations painted over the original text:
 * each block gets a box in the surrounding background color and the
 * translation auto-sized to fit. Hold anywhere to peek at the original;
 * ✕ (or Back) closes.
 */
@SuppressLint("ViewConstructor")
class ResultView(ctx: Context, private val shot: Bitmap, private val onClose: () -> Unit) : FrameLayout(ctx) {
    private var blocks: List<Block> = emptyList()
    private var showOriginal = false
    private val status: TextView
    private val canvasView = object : View(ctx) {
        override fun onDraw(c: Canvas) = drawShot(c)
    }
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
    private val rect = RectF()
    /** Per-block fitted layouts, rebuilt when the view size or the translations change. */
    private val cache = HashMap<Block, Pair<String, StaticLayout>>()

    init {
        setBackgroundColor(Color.BLACK)
        isFocusable = true
        isFocusableInTouchMode = true
        addView(canvasView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        status = TextView(ctx).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(ctx.dp(14), ctx.dp(7), ctx.dp(14), ctx.dp(7))
            background = GradientDrawable().apply { cornerRadius = ctx.dp(16).toFloat(); setColor(0xCC222222.toInt()) }
        }
        addView(status, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = ctx.dp(28)
        })
        addView(TextView(ctx).apply {
            text = "✕"
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xB3000000.toInt()); setStroke(ctx.dp(1), 0x80FFFFFF.toInt()) }
            setOnClickListener { onClose() }
            contentDescription = "Đóng"
        }, LayoutParams(ctx.dp(46), ctx.dp(46), Gravity.TOP or Gravity.END).apply {
            topMargin = ctx.dp(24)
            marginEnd = ctx.dp(18)
        })
    }

    fun setStatus(s: String?) {
        status.text = s ?: ""
        status.visibility = if (s.isNullOrEmpty()) GONE else VISIBLE
    }

    fun setBlocks(list: List<Block>) {
        blocks = list
        canvasView.invalidate()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { showOriginal = true; canvasView.invalidate() }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { showOriginal = false; canvasView.invalidate() }
        }
        return true
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (e.keyCode == KeyEvent.KEYCODE_BACK) {
            if (e.action == KeyEvent.ACTION_UP) onClose()
            return true
        }
        return super.dispatchKeyEvent(e)
    }

    private fun drawShot(c: Canvas) {
        val vw = width.toFloat()
        val vh = height.toFloat()
        if (vw <= 0 || vh <= 0) return
        val scale = min(vw / shot.width, vh / shot.height)
        val ox = (vw - shot.width * scale) / 2
        val oy = (vh - shot.height * scale) / 2
        c.save()
        c.translate(ox, oy)
        c.scale(scale, scale)
        c.drawBitmap(shot, 0f, 0f, bmpPaint)
        if (!showOriginal) for (b in blocks) drawBlock(c, b)
        c.restore()
    }

    private fun drawBlock(c: Canvas, b: Block) {
        val t = b.translated ?: return
        val bg = sampleBackground(b)
        val pad = 3f
        rect.set(b.box.left - pad, b.box.top - pad, b.box.right + pad, b.box.bottom + pad)
        val layout = fitted(b, t, bg)
        // Translation longer than the original even at the minimum size: let the box grow down.
        if (layout.height > rect.height()) rect.bottom = min(shot.height.toFloat(), rect.top + layout.height + pad * 2)
        boxPaint.color = bg
        c.drawRoundRect(rect, 6f, 6f, boxPaint)
        c.save()
        c.translate(rect.left + pad, rect.top + pad + maxOf(0f, (rect.height() - pad * 2 - layout.height) / 2))
        layout.draw(c)
        c.restore()
    }

    private fun fitted(b: Block, t: String, bg: Int): StaticLayout {
        cache[b]?.let { (key, l) -> if (key == t) return l }
        val w = (b.box.width()).coerceAtLeast(8)
        val h = b.box.height().toFloat()
        val lum = (0.299 * Color.red(bg) + 0.587 * Color.green(bg) + 0.114 * Color.blue(bg)) / 255
        textPaint.color = if (lum > 0.55) Color.BLACK else Color.WHITE
        var lo = 8f
        var hi = maxOf(10f, h)
        var best = make(t, w, lo)
        repeat(12) { // binary search the largest text size that fits the original box
            val mid = (lo + hi) / 2
            val l = make(t, w, mid)
            if (l.height <= h) { best = l; lo = mid } else hi = mid
        }
        cache[b] = t to best
        return best
    }

    @Suppress("DEPRECATION")
    private fun make(t: String, w: Int, size: Float): StaticLayout {
        textPaint.textSize = size
        return StaticLayout(t, textPaint, w, Layout.Alignment.ALIGN_NORMAL, 1.0f, 0f, false)
    }

    /** Average color along the block's border = the background behind the text. */
    private fun sampleBackground(b: Block): Int {
        var r = 0L; var g = 0L; var bl = 0L; var n = 0
        val box = b.box
        val x0 = (box.left - 2).coerceIn(0, shot.width - 1)
        val x1 = (box.right + 1).coerceIn(0, shot.width - 1)
        val y0 = (box.top - 2).coerceIn(0, shot.height - 1)
        val y1 = (box.bottom + 1).coerceIn(0, shot.height - 1)
        fun add(x: Int, y: Int) {
            val p = shot.getPixel(x, y)
            r += Color.red(p); g += Color.green(p); bl += Color.blue(p); n++
        }
        for (i in 0..15) {
            val x = x0 + (x1 - x0) * i / 15
            val y = y0 + (y1 - y0) * i / 15
            add(x, y0); add(x, y1); add(x0, y); add(x1, y)
        }
        return Color.rgb((r / n).toInt(), (g / n).toInt(), (bl / n).toInt())
    }
}
