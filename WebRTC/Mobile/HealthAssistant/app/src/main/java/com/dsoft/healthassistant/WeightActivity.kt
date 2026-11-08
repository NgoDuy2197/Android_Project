package com.dsoft.healthassistant

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Weight tracker: current weight, a trend line chart with the target line, a dialog to log a
 * weigh-in, and the full weigh-in history. Logging updates the profile weight (BMI / TDEE).
 */
class WeightActivity : Activity() {
    private lateinit var p: Palette
    private lateinit var content: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        p = palette()
        val col = page(p, tr("Cân nặng", "Weight"))
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(content)
        render()
    }

    override fun onResume() {
        super.onResume()
        if (::content.isInitialized) render()
    }

    private fun render() {
        content.removeAllViews()
        val prof = Store.loadProfile(this)
        val entries = Weight.entries(this)
        val latest = entries.lastOrNull()?.kg ?: prof.weightKg
        val target = prof.targetWeightKg

        content.addView(currentCard(latest, target, entries), vlp().apply { bottomMargin = dp(12) })
        content.addView(bigIconButton(tr("Ghi cân nặng", "Log weight"), R.drawable.ic_person, p.accent, p.accent2) { showDialog(latest) })

        content.addView(sectionHeader(p, tr("Xu hướng", "Trend")))
        if (entries.size < 2) {
            content.addView(footnote(p, tr("Ghi ít nhất 2 lần để thấy biểu đồ xu hướng.", "Log at least 2 weigh-ins to see the trend.")))
        } else {
            content.addView(card(p).apply {
                setPadding(dp(14), dp(14), dp(14), dp(10))
                addView(WeightChart(this@WeightActivity, p, entries, target),
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(190)))
            })
        }

        content.addView(sectionHeader(p, tr("Cấu hình", "Settings")))
        content.addView(group(p,
            row(p, tr("Cân nặng mục tiêu (kg)", "Target weight (kg)"), inlineField(p, trimNum(target), numeric = true) { v ->
                val kg = v.toDoubleOrNull() ?: target
                val pr = Store.loadProfile(this); pr.targetWeightKg = kg; Store.saveProfile(this, pr)
                content.post { render() }
            }),
        ))

        content.addView(sectionHeader(p, tr("Lịch sử", "History")))
        if (entries.isEmpty()) {
            content.addView(footnote(p, tr("Chưa ghi lần cân nào.", "No weigh-ins yet.")))
        } else {
            content.addView(group(p, *entries.reversed().mapIndexed { idx, e ->
                // delta vs the previous (older) weigh-in
                val order = entries.reversed()
                val prev = order.getOrNull(idx + 1)?.kg
                historyRow(e, prev)
            }.toTypedArray()))
            content.addView(footnote(p, tr("Chạm biểu tượng xoá để bỏ một lần cân.", "Tap the delete icon to remove a weigh-in.")))
        }
    }

    private fun showDialog(default: Double) {
        val field = EditText(this).apply {
            setText(trimNum(default))
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            gravity = Gravity.CENTER
            setTextColor(p.text); setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            background = rounded(p.fill, dpf(10)); setPadding(dp(10), dp(12), dp(10), dp(12))
        }
        val view = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(10), dp(22), dp(4))
            addView(text(tr("Cân nặng (kg)", "Weight (kg)"), 13f, p.secondary).apply { setPadding(dp(2), 0, 0, dp(6)) })
            addView(field)
        }
        AlertDialog.Builder(this)
            .setTitle(tr("Ghi cân nặng", "Log weight"))
            .setView(view)
            .setPositiveButton(tr("Lưu", "Save")) { _, _ ->
                val kg = field.text.toString().toDoubleOrNull()?.coerceIn(20.0, 400.0)
                if (kg != null) { Weight.add(this, kg); render() }
            }
            .setNegativeButton(tr("Huỷ", "Cancel"), null)
            .show()
    }

    private fun currentCard(latest: Double, target: Double, entries: List<WeightEntry>): View {
        val first = entries.firstOrNull()?.kg
        val change = if (first != null) latest - first else 0.0
        val toTarget = latest - target
        val prof = Store.loadProfile(this)
        return card(p).apply {
            setPadding(dp(18), dp(16), dp(18), dp(16))
            addView(text(tr("Hiện tại", "Current"), 13f, p.secondary))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM
                setPadding(0, dp(2), 0, dp(10))
                addView(text(trimNum(latest), 40f, p.accent, 700))
                addView(text("  kg", 16f, p.secondary).apply { setPadding(0, 0, 0, dp(6)) })
            })
            val parts = ArrayList<String>()
            parts.add(tr("Mục tiêu ${trimNum(target)} kg", "Target ${trimNum(target)} kg"))
            if (kotlin.math.abs(toTarget) >= 0.05)
                parts.add(tr("còn ${trimNum(kotlin.math.abs(toTarget))} kg", "${trimNum(kotlin.math.abs(toTarget))} kg to go"))
            if (first != null && kotlin.math.abs(change) >= 0.05)
                parts.add(tr("${if (change < 0) "giảm" else "tăng"} ${trimNum(kotlin.math.abs(change))} kg", "${if (change < 0) "down" else "up"} ${trimNum(kotlin.math.abs(change))} kg"))
            addView(text(parts.joinToString(" · "), 14f, p.secondary, 500))
            addView(View(context).apply { setBackgroundColor(p.separator) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply { topMargin = dp(12) })
            addView(text("BMI ${String.format("%.1f", prof.bmi())} (${prof.bmiLabel()})", 13f, p.accent, 500).apply { setPadding(0, dp(10), 0, 0) })
        }
    }

    private fun historyRow(e: WeightEntry, prev: Double?): LinearLayout {
        val df = SimpleDateFormat(if (I18n.en) "MMM d, yyyy" else "dd/MM/yyyy", if (I18n.en) Locale.ENGLISH else Locale("vi"))
        val delta = if (prev != null) e.kg - prev else null
        val trailing = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(text(trimNum(e.kg) + " kg", 15f, p.text, 600))
            if (delta != null && kotlin.math.abs(delta) >= 0.05)
                addView(text("  ${if (delta < 0) "▼" else "▲"}${trimNum(kotlin.math.abs(delta))}", 12f, if (delta < 0) p.green else p.orange, 600)
                    .apply { setPadding(dp(6), 0, 0, 0) })
            addView(ImageView(this@WeightActivity).apply {
                setImageResource(R.drawable.ic_delete)
                imageTintList = ColorStateList.valueOf(p.secondary)
                val pd = dp(8); setPadding(pd, pd, pd, pd)
                background = pressable(null, p.ripple); clipRounded()
                layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginStart = dp(6) }
                setOnClickListener { Weight.removeAt(this@WeightActivity, e.t); render() }
            })
        }
        return row(p, df.format(Date(e.t)), trailing)
    }

    private fun trimNum(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString() else String.format(Locale.US, "%.1f", v)
}

/** A compact weight trend line with a dashed target line, auto-scaled to the data. */
private class WeightChart(
    ctx: Context,
    private val p: Palette,
    private val entries: List<WeightEntry>,
    private val target: Double,
) : View(ctx) {

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = ctx.dpf(2.5f); strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val goalLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = ctx.dpf(1.5f); color = p.secondary; pathEffect = android.graphics.DashPathEffect(floatArrayOf(ctx.dpf(5), ctx.dpf(4)), 0f) }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = p.secondary
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11f, ctx.resources.displayMetrics)
    }

    override fun onDraw(c: Canvas) {
        if (entries.size < 2) return
        val padL = context.dpf(8); val padR = context.dpf(40); val padT = context.dpf(14); val padB = context.dpf(22)
        val w = width.toFloat(); val h = height.toFloat()
        val kgs = entries.map { it.kg } + target
        var lo = kgs.min(); var hi = kgs.max()
        if (hi - lo < 1.0) { hi += 0.5; lo -= 0.5 }
        val range = hi - lo
        fun x(i: Int) = padL + (w - padL - padR) * i / (entries.size - 1)
        fun y(kg: Double) = padT + (h - padT - padB) * (1f - ((kg - lo) / range)).toFloat()

        // target line + its label
        val ty = y(target)
        c.drawLine(padL, ty, w - padR, ty, goalLine)
        labelPaint.color = p.secondary
        c.drawText("${trim(target)}", w - padR + context.dpf(4), ty + context.dpf(4), labelPaint)

        // trend line
        line.color = p.accent
        val path = Path()
        entries.forEachIndexed { i, e ->
            val px = x(i); val py = y(e.kg)
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        c.drawPath(path, line)

        // dots
        dot.color = p.accent
        entries.forEachIndexed { i, e -> c.drawCircle(x(i), y(e.kg), context.dpf(3.5f), dot) }

        // first / last value labels
        labelPaint.color = p.text
        c.drawText("${trim(entries.first().kg)}", x(0), y(entries.first().kg) - context.dpf(8), labelPaint)
        val lastTxt = "${trim(entries.last().kg)}"
        c.drawText(lastTxt, x(entries.size - 1) - labelPaint.measureText(lastTxt), y(entries.last().kg) - context.dpf(8), labelPaint)
    }

    private fun trim(v: Double) = if (v == v.toLong().toDouble()) v.toLong().toString() else String.format(Locale.US, "%.1f", v)
}
