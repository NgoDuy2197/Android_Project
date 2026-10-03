package com.dsoft.voicenote

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * Glossy record button: gradient orb with a glass highlight, soft colored glow,
 * and halo rings that breathe with the mic level while recording.
 */
class RecordButton(ctx: Context) : View(ctx) {
    private val p = ctx.palette()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mic = ctx.getDrawable(R.drawable.ic_mic_glyph)!!.mutate().apply { setTint(Color.WHITE) }
    private val oval = RectF()
    private var phase = 0f
    private var shownLevel = 0f
    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2400
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { phase = it.animatedValue as Float; invalidate() }
    }

    var recording = false
        set(v) {
            if (field == v) return
            field = v
            if (v) anim.start() else anim.cancel()
            invalidate()
        }

    var level = 0f
        set(v) {
            field = v.coerceIn(0f, 1f)
            if (!recording) invalidate()
        }

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null) // shadow layer on shapes
        isClickable = true
        contentDescription = "Ghi âm"
    }

    override fun onDetachedFromWindow() {
        anim.cancel()
        super.onDetachedFromWindow()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (recording) anim.start()
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f * 0.62f
        shownLevel += (level - shownLevel) * if (level > shownLevel) 0.6f else 0.12f
        val top = if (recording) p.pink else 0xFFFF6A88.toInt()
        val bottom = if (recording) p.red else 0xFFFF2D55.toInt()

        // halo rings
        if (recording) {
            for (k in 0..1) {
                val t = (phase + k * 0.5f) % 1f
                val ring = r * (1.05f + 0.45f * t + 0.25f * shownLevel)
                paint.shader = null
                paint.color = withAlpha(p.pink, ((1f - t) * (70 + 120 * shownLevel)).toInt().coerceIn(0, 255))
                c.drawCircle(cx, cy, ring, paint)
            }
        } else {
            val breath = 0.5f + 0.5f * sin(System.currentTimeMillis() / 600.0 * PI).toFloat()
            paint.shader = null
            paint.color = withAlpha(p.pink, (28 + 22 * breath).toInt())
            c.drawCircle(cx, cy, r * 1.16f, paint)
            postInvalidateDelayed(50)
        }

        // orb with glow
        paint.shader = LinearGradient(cx - r, cy - r, cx + r, cy + r, top, bottom, Shader.TileMode.CLAMP)
        paint.setShadowLayer(r * 0.32f, 0f, r * 0.14f, withAlpha(bottom, 140))
        c.drawCircle(cx, cy, r, paint)
        paint.clearShadowLayer()

        // glass highlight on the upper half
        oval.set(cx - r * 0.78f, cy - r * 0.94f, cx + r * 0.78f, cy - r * 0.02f)
        paint.shader = LinearGradient(0f, oval.top, 0f, oval.bottom,
            Color.argb(150, 255, 255, 255), Color.argb(8, 255, 255, 255), Shader.TileMode.CLAMP)
        c.drawOval(oval, paint)
        paint.shader = null

        // glyph
        if (recording) {
            val s = r * 0.30f
            paint.color = Color.WHITE
            oval.set(cx - s, cy - s, cx + s, cy + s)
            c.drawRoundRect(oval, s * 0.32f, s * 0.32f, paint)
        } else {
            val s = (r * 0.62f).toInt()
            mic.setBounds((cx - s).toInt(), (cy - s).toInt(), (cx + s).toInt(), (cy + s).toInt())
            mic.draw(c)
        }
    }

    private fun withAlpha(color: Int, a: Int) = Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))
}

/** Scrolling bar graph of recent mic levels (newest on the right). */
class LevelBars(ctx: Context) : View(ctx) {
    private val values = FloatArray(36)
    private var head = 0
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    var color = ctx.palette().pink

    fun push(v: Float) {
        values[head] = v.coerceIn(0f, 1f)
        head = (head + 1) % values.size
        invalidate()
    }

    fun clear() {
        values.fill(0f)
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val n = values.size
        val step = width / n.toFloat()
        val bw = step * 0.55f
        val minH = context.dp(3).toFloat()
        for (i in 0 until n) {
            val v = values[(head + i) % n]
            val h = minH + v * (height - minH)
            val x = i * step + (step - bw) / 2
            rect.set(x, (height - h) / 2, x + bw, (height + h) / 2)
            paint.color = color
            paint.alpha = (70 + 185 * (i + 1) / n)
            c.drawRoundRect(rect, bw / 2, bw / 2, paint)
        }
    }
}
