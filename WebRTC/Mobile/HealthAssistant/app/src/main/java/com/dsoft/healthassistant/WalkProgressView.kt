package com.dsoft.healthassistant

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.abs
import kotlin.math.sin

/**
 * Indeterminate "journey" progress: a little character walks along a road toward a flag, loops
 * while work is in flight. Friendlier than a spinner for the slow offline model. Pure Canvas.
 */
class WalkProgressView(ctx: Context, private val p: Palette) : View(ctx) {
    private val density = resources.displayMetrics.density
    private fun px(v: Float) = v * density

    private var frac = 0f
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 3400L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { frac = it.animatedFraction; invalidate() }
    }

    private val road = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = p.separator; style = Paint.Style.STROKE; strokeWidth = px(3f); strokeCap = Paint.Cap.ROUND
    }
    private val dash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = p.secondary; style = Paint.Style.STROKE; strokeWidth = px(2f); strokeCap = Paint.Cap.ROUND
    }
    private val fig = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = p.green; style = Paint.Style.STROKE; strokeWidth = px(3.2f); strokeCap = Paint.Cap.ROUND
    }
    private val figFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p.green; style = Paint.Style.FILL }
    private val pole = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = p.secondary; style = Paint.Style.STROKE; strokeWidth = px(2.5f); strokeCap = Paint.Cap.ROUND
    }
    private val flag = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p.orange; style = Paint.Style.FILL }
    private val flagPath = Path()

    override fun onAttachedToWindow() { super.onAttachedToWindow(); if (!animator.isStarted) animator.start() }
    override fun onDetachedFromWindow() { animator.cancel(); super.onDetachedFromWindow() }
    override fun onMeasure(w: Int, h: Int) =
        setMeasuredDimension(resolveSize(px(280f).toInt(), w), resolveSize(px(96f).toInt(), h))

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val baseY = height - px(24f) // the road
        val startX = px(20f)
        val flagX = w - px(24f)

        // road + lane dashes
        canvas.drawLine(px(8f), baseY, w - px(8f), baseY, road)
        var dx = px(14f)
        while (dx < w - px(14f)) { canvas.drawLine(dx, baseY + px(7f), dx + px(8f), baseY + px(7f), dash); dx += px(20f) }

        // destination flag
        canvas.drawLine(flagX, baseY, flagX, baseY - px(34f), pole)
        flagPath.reset()
        flagPath.moveTo(flagX, baseY - px(34f))
        flagPath.lineTo(flagX + px(15f), baseY - px(29f))
        flagPath.lineTo(flagX, baseY - px(24f))
        flagPath.close()
        canvas.drawPath(flagPath, flag)

        // walking character
        val cx = startX + frac * (flagX - px(16f) - startX)
        val phase = frac * 2f * Math.PI.toFloat() * 7f // ~7 strides across
        val bob = -abs(sin(phase)) * px(3f)
        val legSwing = sin(phase) * px(7f)
        val armSwing = sin(phase) * px(5f)

        val hipY = baseY - px(15f) + bob
        val shoulderY = hipY - px(15f)
        val headCy = shoulderY - px(6.5f)

        canvas.drawLine(cx, shoulderY, cx, hipY, fig)                              // torso
        canvas.drawLine(cx, hipY, cx + legSwing, baseY, fig)                       // leg 1
        canvas.drawLine(cx, hipY, cx - legSwing, baseY, fig)                       // leg 2
        canvas.drawLine(cx, shoulderY + px(2f), cx + armSwing, shoulderY + px(10f), fig) // arm 1
        canvas.drawLine(cx, shoulderY + px(2f), cx - armSwing, shoulderY + px(10f), fig) // arm 2
        canvas.drawCircle(cx, headCy, px(6f), figFill)                             // head
    }
}
