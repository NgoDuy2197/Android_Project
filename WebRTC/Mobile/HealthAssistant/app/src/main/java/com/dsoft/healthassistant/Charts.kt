package com.dsoft.healthassistant

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import kotlin.math.max

/** Horizontal stacked bar of the macro energy split (protein/carb/fat by kcal) + a legend. */
class MacroBar(ctx: Context, p: Palette, protein: Int, carb: Int, fat: Int) : LinearLayout(ctx) {
    init {
        orientation = VERTICAL
        background = rounded(p.card, ctx.dpf(14)); clipRounded()
        setPadding(ctx.dp(16), ctx.dp(14), ctx.dp(16), ctx.dp(14))

        val pC = protein * 4; val cC = carb * 4; val fC = fat * 9
        val total = (pC + cC + fC).coerceAtLeast(1)

        addView(ctx.text(tr("Tỷ lệ năng lượng", "Energy ratio"), 13f, p.secondary))

        val bar = LinearLayout(ctx).apply {
            orientation = HORIZONTAL
            background = rounded(p.fill, ctx.dpf(7)); clipRounded()
        }
        fun seg(color: Int, weight: Int) = bar.addView(
            View(ctx).apply { setBackgroundColor(color) },
            LayoutParams(0, LayoutParams.MATCH_PARENT, weight.toFloat()),
        )
        seg(p.blue, pC); seg(p.orange, cC); seg(p.purple, fC)
        addView(bar, LayoutParams(LayoutParams.MATCH_PARENT, ctx.dp(14)).apply { topMargin = ctx.dp(10) })

        fun pct(x: Int) = Math.round(x * 100f / total)
        val legend = LinearLayout(ctx).apply { orientation = HORIZONTAL }
        legend.addView(legendItem(ctx, p, p.blue, "${tr("Đạm", "Protein")} ${pct(pC)}%"))
        legend.addView(legendItem(ctx, p, p.orange, "${tr("Tinh bột", "Carbs")} ${pct(cC)}%"))
        legend.addView(legendItem(ctx, p, p.purple, "${tr("Béo", "Fat")} ${pct(fC)}%"))
        addView(legend, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = ctx.dp(10) })
    }

    private fun legendItem(ctx: Context, p: Palette, color: Int, label: String) = LinearLayout(ctx).apply {
        orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        layoutParams = LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        addView(View(ctx).apply { background = rounded(color, ctx.dpf(3)) },
            LayoutParams(ctx.dp(9), ctx.dp(9)).apply { marginEnd = ctx.dp(5) })
        addView(ctx.text(label, 12f, p.secondary, 500))
    }
}

/**
 * A 7-day bar chart where each day carries its OWN target: the bar colour reflects whether that
 * day was under / within / over its target, and a short horizontal tick marks that day's target
 * (so targets changing later don't distort old days). Pure Canvas, theme-aware.
 */
class WeeklyBarChart(ctx: Context, private val p: Palette, private val bars: List<Bar>) : View(ctx) {

    /** One day: [value] actual, [target] goal for that day, [color] already zoned by the caller. */
    class Bar(val label: String, val value: Int, val target: Int, val color: Int, val highlight: Boolean = false)

    private val density = resources.displayMetrics.density
    private val scaled = resources.displayMetrics.scaledDensity
    private fun px(v: Float) = v * density

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = (p.text and 0xFFFFFF) or (0x99 shl 24) // target marker, in the current text tone
        style = Paint.Style.STROKE; strokeWidth = px(2f); strokeCap = Paint.Cap.ROUND
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = p.secondary; textAlign = Paint.Align.CENTER; textSize = 9.5f * scaled
    }
    private val dayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; textSize = 11f * scaled
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            resolveSize(px(320f).toInt(), widthMeasureSpec),
            resolveSize(px(172f).toInt(), heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        val n = bars.size
        if (n == 0) return
        val w = width.toFloat(); val h = height.toFloat()
        val topPad = px(18f); val botPad = px(22f)
        val chartH = h - topPad - botPad
        val baseY = topPad + chartH
        val maxVal = max(bars.maxOf { max(it.value, it.target) }, 1)
        val slot = w / n
        val barW = slot * 0.46f
        val tickW = barW * 1.35f

        bars.forEachIndexed { i, b ->
            val cx = slot * i + slot / 2f
            val bh = chartH * (b.value.toFloat() / maxVal)
            val top = baseY - bh
            barPaint.color = if (b.value == 0) p.separator else b.color
            if (bh >= px(2f)) canvas.drawRoundRect(cx - barW / 2, top, cx + barW / 2, baseY, px(4f), px(4f), barPaint)

            if (b.target > 0) {
                val ty = baseY - chartH * (b.target.toFloat() / maxVal)
                canvas.drawLine(cx - tickW / 2, ty, cx + tickW / 2, ty, tickPaint)
            }
            if (b.value > 0) canvas.drawText(b.value.toString(), cx, (top - px(5f)).coerceAtLeast(px(10f)), valuePaint)

            dayPaint.isFakeBoldText = b.highlight
            dayPaint.color = if (b.highlight) p.text else p.secondary
            canvas.drawText(b.label, cx, h - px(6f), dayPaint)
        }
    }
}
