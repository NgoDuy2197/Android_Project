package com.dsoft.healthassistant

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import java.util.Calendar

/**
 * Water tracker: target + cup size, one-tap logging for the selected day, and a month
 * calendar whose cells fill up in proportion to that day's intake.
 */
class WaterActivity : Activity() {
    private lateinit var p: Palette
    private lateinit var content: LinearLayout
    private var date: String = Store.today()
    private var monthStart: Calendar = Water.monthStart(Store.today())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        p = palette()
        intent?.getStringExtra("date")?.takeIf { it.isNotBlank() }?.let { date = it }
        monthStart = Water.monthStart(date)
        val col = page(p, "Uống nước")
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
        val target = Water.targetMl(this)
        val cup = Water.cupMl(this)
        val now = Water.amount(this, date)

        content.addView(dayCard(now, target, cup), vlp().apply { bottomMargin = dp(12) })
        content.addView(actionRow(cup), vlp().apply { bottomMargin = dp(4) })

        content.addView(sectionHeader(p, "Cấu hình"))
        content.addView(
            group(
                p,
                row(p, "Mục tiêu mỗi ngày (ml)", inlineField(p, target.toString(), numeric = true) { v ->
                    Water.setTargetMl(this, v.toIntOrNull() ?: Water.DEFAULT_TARGET_ML)
                    content.post { render() }
                }),
                row(p, "Dung tích 1 cốc (ml)", inlineField(p, cup.toString(), numeric = true) { v ->
                    Water.setCupMl(this, v.toIntOrNull() ?: Water.DEFAULT_CUP_ML)
                    content.post { render() }
                }),
            ),
        )
        content.addView(footnote(p, "Mỗi lần chạm “+ 1 cốc” (trong app hoặc trên widget) sẽ cộng đúng dung tích này."))

        content.addView(sectionHeader(p, "Lịch uống nước"))
        content.addView(monthNav())
        content.addView(calendarCard(target), vlp().apply { topMargin = dp(8) })
        content.addView(monthStats(target))
        content.addView(
            footnote(p, "Ô ngày được tô đầy theo tỉ lệ nước đã uống / mục tiêu. Chạm một ngày để xem và sửa ngày đó."),
        )
    }

    // ---- selected day ----

    private fun dayCard(now: Int, target: Int, cup: Int): View {
        val ratio = if (target > 0) now.toFloat() / target else 0f
        val remaining = target - now
        return card(p).apply {
            setPadding(dp(18), dp(16), dp(18), dp(16))
            addView(text(if (date == Store.today()) "Hôm nay" else Store.prettyDate(date), 13f, p.secondary))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.BOTTOM
                setPadding(0, dp(2), 0, dp(10))
                addView(text("$now", 40f, if (ratio >= 1f) p.green else p.blue, 700))
                addView(text("  / $target ml", 16f, p.secondary).apply { setPadding(0, 0, 0, dp(6)) })
            })
            addView(progressBar(ratio), vlp(ViewGroup.LayoutParams.MATCH_PARENT, dp(10)))
            addView(
                text(
                    if (remaining > 0) "${Water.cups(now, cup)} cốc · còn $remaining ml"
                    else "${Water.cups(now, cup)} cốc · đã đạt mục tiêu 🎉",
                    14f, if (remaining > 0) p.secondary else p.green, 500,
                ).apply { setPadding(0, dp(10), 0, 0) },
            )
        }
    }

    private fun progressBar(ratio: Float): View {
        val r = ratio.coerceIn(0f, 1f)
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(p.fill, dpf(5))
            clipRounded()
            addView(
                View(context).apply { background = gradient(dpf(5), p.blue, p.teal) },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, r),
            )
            addView(View(context), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f - r))
        }
    }

    private fun actionRow(cup: Int): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(
            text("− 1 cốc", 17f, p.text, 700).apply {
                gravity = Gravity.CENTER
                minHeight = dp(52)
                background = pressable(rounded(p.fill, dpf(14)), p.ripple)
                clipRounded()
                setOnClickListener {
                    Water.add(this@WaterActivity, date, -cup)
                    render()
                }
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(10) },
        )
        addView(
            bigIconButton("+ 1 cốc ($cup ml)", R.drawable.ic_water, p.blue, p.teal) {
                Water.add(this@WaterActivity, date, cup)
                render()
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f),
        )
    }

    // ---- month calendar ----

    private fun monthNav(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(iconButton(p, R.drawable.ic_chevron_left, p.blue) { shiftMonth(-1) })
        addView(
            text(Water.monthLabel(monthStart.get(Calendar.YEAR), monthStart.get(Calendar.MONTH)), 16f, p.text, 600)
                .apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        addView(iconButton(p, R.drawable.ic_chevron_right, p.blue) { shiftMonth(1) })
    }

    private fun shiftMonth(months: Int) {
        monthStart.add(Calendar.MONTH, months)
        render()
    }

    private fun calendarCard(target: Int): View {
        val year = monthStart.get(Calendar.YEAR)
        val month0 = monthStart.get(Calendar.MONTH)
        val days = monthStart.getActualMaximum(Calendar.DAY_OF_MONTH)
        val offset = (monthStart.get(Calendar.DAY_OF_WEEK) + 5) % 7   // Monday first
        val log = Water.month(this, year, month0)
        val today = Store.today()

        return card(p).apply {
            setPadding(dp(10), dp(12), dp(10), dp(12))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                listOf("T2", "T3", "T4", "T5", "T6", "T7", "CN").forEach { d ->
                    addView(
                        text(d, 12f, p.secondary, 500).apply { gravity = Gravity.CENTER },
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                    )
                }
                setPadding(0, 0, 0, dp(6))
            })

            var cell = 0
            val total = offset + days
            while (cell < total) {
                val rowView = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                repeat(7) {
                    val lp = LinearLayout.LayoutParams(0, dp(46), 1f).apply {
                        marginStart = dp(2); marginEnd = dp(2); topMargin = dp(2); bottomMargin = dp(2)
                    }
                    val day = cell - offset + 1
                    if (cell < offset || day > days) {
                        rowView.addView(View(context), lp)
                    } else {
                        val d = Water.dateOf(year, month0, day)
                        val ml = log[d] ?: 0
                        rowView.addView(
                            DayCell(context, p, day, if (target > 0) ml.toFloat() / target else 0f, d == today, d == date)
                                .apply { setOnClickListener { date = d; render() } },
                            lp,
                        )
                    }
                    cell++
                }
                addView(rowView)
            }
        }
    }

    private fun monthStats(target: Int): View {
        val log = Water.month(this, monthStart.get(Calendar.YEAR), monthStart.get(Calendar.MONTH))
        val logged = log.values.count { it > 0 }
        val reached = log.values.count { it >= target }
        val sum = log.values.sum()
        val avg = if (logged > 0) sum / logged else 0
        return footnote(p, "Tháng này: $logged ngày có ghi · $reached ngày đạt mục tiêu · trung bình $avg ml/ngày.")
    }
}

/** One day in the month grid: a rounded track filled from the bottom by `ratio` of the target. */
private class DayCell(
    ctx: Context,
    private val p: Palette,
    private val day: Int,
    ratio: Float,
    private val isToday: Boolean,
    private val isSelected: Boolean,
) : View(ctx) {

    private val ratio = ratio.coerceIn(0f, 1f)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 13f, ctx.resources.displayMetrics)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    override fun onDraw(c: Canvas) {
        val r = context.dpf(9)
        val rect = RectF(0f, 0f, width.toFloat(), height.toFloat())
        val path = Path().apply { addRoundRect(rect, r, r, Path.Direction.CW) }

        c.save()
        c.clipPath(path)
        paint.color = p.fill
        c.drawRect(rect, paint)
        if (ratio > 0f) {
            paint.color = if (ratio >= 1f) p.green else p.blue
            c.drawRect(0f, rect.bottom - rect.height() * ratio, rect.right, rect.bottom, paint)
        }
        c.restore()

        if (isSelected || isToday) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = context.dpf(if (isSelected) 2f else 1.5f)
            paint.color = if (isSelected) p.blue else p.secondary
            val inset = paint.strokeWidth / 2f
            c.drawRoundRect(RectF(inset, inset, rect.right - inset, rect.bottom - inset), r, r, paint)
            paint.style = Paint.Style.FILL
        }

        label.color = if (ratio >= 0.55f) Color.WHITE else p.text
        val y = height / 2f - (label.descent() + label.ascent()) / 2f
        c.drawText(day.toString(), width / 2f, y, label)
    }
}
