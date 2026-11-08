package com.dsoft.healthassistant

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Water tracker: a timestamped drink log for the selected day (like the meal list), a cups × ml
 * dialog for logging, target/cup config, and a month calendar whose cells fill with intake.
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
        val col = page(p, tr("Uống nước", "Water"))
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
        content.addView(bigIconButton(tr("Ghi uống nước", "Log water"), R.drawable.ic_water, p.blue, p.teal) { showDrinkDialog() })

        // today's drinks, like the meal list
        content.addView(sectionHeader(p, tr("Đã uống ${if (date == Store.today()) "hôm nay" else "ngày này"}", "Logged ${if (date == Store.today()) "today" else "this day"}")))
        val entries = Water.entries(this, date)
        if (entries.isEmpty()) {
            content.addView(footnote(p, tr("Chưa ghi lần uống nào. Bấm “Ghi uống nước”.", "No drinks logged yet. Tap “Log water”.")))
        } else {
            content.addView(group(p, *entries.reversed().map { entryRow(it) }.toTypedArray()))
            content.addView(footnote(p, tr("Chạm biểu tượng xoá để bỏ một lần uống.", "Tap the delete icon to remove a drink.")))
        }

        content.addView(sectionHeader(p, tr("Cấu hình", "Settings")))
        content.addView(
            group(
                p,
                row(p, tr("Mục tiêu mỗi ngày (ml)", "Daily target (ml)"), inlineField(p, target.toString(), numeric = true) { v ->
                    Water.setTargetMl(this, v.toIntOrNull() ?: Water.DEFAULT_TARGET_ML)
                    content.post { render() }
                }),
                row(p, tr("Dung tích 1 cốc mặc định (ml)", "Default cup size (ml)"), inlineField(p, cup.toString(), numeric = true) { v ->
                    Water.setCupMl(this, v.toIntOrNull() ?: Water.DEFAULT_CUP_ML)
                    content.post { render() }
                }),
            ),
        )
        content.addView(footnote(p, tr("Dung tích 1 cốc sẽ tự cập nhật theo lần ghi gần nhất để nhập nhanh hơn.",
            "The cup size updates to your last entry for faster logging.")))

        content.addView(sectionHeader(p, tr("Lịch uống nước", "History")))
        content.addView(monthNav())
        content.addView(calendarCard(target), vlp().apply { topMargin = dp(8) })
        content.addView(monthStats(target))
        content.addView(footnote(p, tr("Ô ngày tô đầy theo tỉ lệ đã uống / mục tiêu. Chạm một ngày để xem và ghi cho ngày đó.",
            "Each day fills by intake / target. Tap a day to view and log it.")))
    }

    // ---- logging ----

    private fun showDrinkDialog() {
        val cup0 = Water.cupMl(this)
        val cupsField = numField("1")
        val mlField = numField("$cup0")
        val totalView = text("", 15f, p.text, 600)
        fun cur(f: EditText, min: Int, max: Int, d: Int) = f.text.toString().toIntOrNull()?.coerceIn(min, max) ?: d
        fun refresh() {
            val c = cur(cupsField, 1, 50, 1); val m = cur(mlField, 20, 2000, cup0)
            totalView.text = "${tr("Tổng", "Total")}: ${c * m} ml  ($c × $m ml)"
        }
        val watcher = object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = refresh()
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        }
        cupsField.addTextChangedListener(watcher); mlField.addTextChangedListener(watcher); refresh()

        val view = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(8), dp(22), dp(4))
            addView(stepper(tr("Số cốc", "Cups"), cupsField, 1, 1, 50))
            addView(stepper(tr("Lượng 1 cốc (ml)", "Per cup (ml)"), mlField, 50, 20, 2000))
            addView(totalView.apply { setPadding(dp(2), dp(14), 0, 0) })
        }
        AlertDialog.Builder(this)
            .setTitle(tr("Ghi uống nước", "Log water"))
            .setView(view)
            .setPositiveButton(tr("Thêm", "Add")) { _, _ ->
                val c = cur(cupsField, 1, 50, 1); val m = cur(mlField, 20, 2000, cup0)
                Water.setCupMl(this, m)        // remember as default for next time
                Water.addEntry(this, date, c * m)
                render()
            }
            .setNegativeButton(tr("Huỷ", "Cancel"), null)
            .show()
    }

    private fun numField(v: String) = EditText(this).apply {
        setText(v); inputType = InputType.TYPE_CLASS_NUMBER; gravity = Gravity.CENTER
        setTextColor(p.text); setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        background = rounded(p.fill, dpf(10)); setPadding(dp(8), dp(8), dp(8), dp(8))
    }

    private fun stepper(labelText: String, field: EditText, step: Int, min: Int, max: Int): LinearLayout {
        fun cur() = field.text.toString().toIntOrNull()?.coerceIn(min, max) ?: min
        fun btn(s: String, onClick: () -> Unit) = text(s, 22f, p.accent, 700).apply {
            gravity = Gravity.CENTER
            background = pressable(rounded(p.fill, dpf(12)), p.ripple); clipRounded()
            setOnClickListener { onClick() }
        }
        val minus = btn("−") { field.setText("${(cur() - step).coerceAtLeast(min)}"); field.setSelection(field.text.length) }
        val plus = btn("+") { field.setText("${(cur() + step).coerceAtMost(max)}"); field.setSelection(field.text.length) }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(labelText, 13f, p.secondary).apply { setPadding(dp(2), dp(10), 0, dp(4)) })
            addView(LinearLayout(this@WaterActivity).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(minus, LinearLayout.LayoutParams(dp(54), dp(46)))
                addView(field, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginStart = dp(10); marginEnd = dp(10) })
                addView(plus, LinearLayout.LayoutParams(dp(54), dp(46)))
            })
        }
    }

    private fun entryRow(e: WaterEntry): LinearLayout {
        val tf = SimpleDateFormat("HH:mm", Locale.getDefault())
        val time = if (e.t == 0L) tr("trước đây", "earlier") else tf.format(Date(e.t))
        val trailing = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(text("${e.ml} ml", 15f, p.blue, 600))
            addView(ImageView(this@WaterActivity).apply {
                setImageResource(R.drawable.ic_delete)
                imageTintList = ColorStateList.valueOf(p.secondary)
                val pd = dp(8); setPadding(pd, pd, pd, pd)
                background = pressable(null, p.ripple); clipRounded()
                layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginStart = dp(6) }
                setOnClickListener { Water.removeEntry(this@WaterActivity, date, e.t); render() }
            })
        }
        return row(p, time, trailing)
    }

    // ---- selected day ----

    private fun dayCard(now: Int, target: Int, cup: Int): View {
        val ratio = if (target > 0) now.toFloat() / target else 0f
        val remaining = target - now
        return card(p).apply {
            setPadding(dp(18), dp(16), dp(18), dp(16))
            addView(text(if (date == Store.today()) tr("Hôm nay", "Today") else Store.prettyDate(date), 13f, p.secondary))
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
                    if (remaining > 0) "${Water.cupsLabel(now, cup)} · ${tr("còn", "need")} $remaining ml"
                    else "${Water.cupsLabel(now, cup)} · ${tr("đã đạt mục tiêu 🎉", "target reached 🎉")}",
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
                listOf(tr("T2", "Mon"), tr("T3", "Tue"), tr("T4", "Wed"), tr("T5", "Thu"), tr("T6", "Fri"), tr("T7", "Sat"), tr("CN", "Sun")).forEach { d ->
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
        return footnote(p, tr(
            "Tháng này: $logged ngày có ghi · $reached ngày đạt mục tiêu · trung bình $avg ml/ngày.",
            "This month: $logged days logged · $reached days on target · avg $avg ml/day.",
        ))
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
