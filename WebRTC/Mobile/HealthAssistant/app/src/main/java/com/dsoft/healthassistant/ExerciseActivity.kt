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
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Exercise tracker: a timestamped workout log for the selected day (like the meal list), a dialog
 * to log a workout (type + minutes, calories auto-estimated), a daily burn goal, and a month
 * calendar whose cells fill with calories burned.
 */
class ExerciseActivity : Activity() {
    private lateinit var p: Palette
    private lateinit var content: LinearLayout
    private var date: String = Store.today()
    private var monthStart: Calendar = Exercise.monthStart(Store.today())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        p = palette()
        intent?.getStringExtra("date")?.takeIf { it.isNotBlank() }?.let { date = it }
        monthStart = Exercise.monthStart(date)
        val col = page(p, tr("Tập luyện", "Exercise"))
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
        val goal = Exercise.goalKcal(this)
        val burned = Exercise.burned(this, date)
        val mins = Exercise.minutes(this, date)

        content.addView(dayCard(burned, mins, goal), vlp().apply { bottomMargin = dp(12) })
        content.addView(bigIconButton(tr("Ghi tập luyện", "Log workout"), R.drawable.ic_dumbbell, p.orange, p.pink) { showDialog() })

        content.addView(sectionHeader(p, tr("Đã tập ${if (date == Store.today()) "hôm nay" else "ngày này"}", "Logged ${if (date == Store.today()) "today" else "this day"}")))
        val entries = Exercise.entries(this, date)
        if (entries.isEmpty()) {
            content.addView(footnote(p, tr("Chưa ghi buổi tập nào. Bấm “Ghi tập luyện”.", "No workouts logged yet. Tap “Log workout”.")))
        } else {
            content.addView(group(p, *entries.reversed().map { entryRow(it) }.toTypedArray()))
            content.addView(footnote(p, tr("Chạm biểu tượng xoá để bỏ một buổi tập.", "Tap the delete icon to remove a workout.")))
        }

        content.addView(sectionHeader(p, tr("Cấu hình", "Settings")))
        content.addView(
            group(
                p,
                row(p, tr("Mục tiêu đốt mỗi ngày (kcal)", "Daily burn goal (kcal)"), inlineField(p, goal.toString(), numeric = true) { v ->
                    Exercise.setGoalKcal(this, v.toIntOrNull() ?: Exercise.DEFAULT_GOAL_KCAL)
                    content.post { render() }
                }),
            ),
        )
        content.addView(footnote(p, tr("Calo đốt được ước tính theo cân nặng của bạn và loại bài tập (có thể sửa khi ghi).",
            "Calories are estimated from your weight and the workout type (editable when logging).")))

        content.addView(sectionHeader(p, tr("Lịch tập luyện", "History")))
        content.addView(monthNav())
        content.addView(calendarCard(goal), vlp().apply { topMargin = dp(8) })
        content.addView(monthStats(goal))
    }

    // ---- logging ----

    private fun showDialog() {
        val weight = Store.loadProfile(this).weightKg
        var preset = Exercise.PRESETS.first()
        var userEditedKcal = false

        val minField = numField("30")
        val kcalField = numField("${Exercise.estimate(preset.met, weight, 30)}")
        fun curMin() = minField.text.toString().toIntOrNull()?.coerceIn(1, 1000) ?: 30
        fun recomputeKcal() { if (!userEditedKcal) kcalField.setText("${Exercise.estimate(preset.met, weight, curMin())}") }

        // preset chips
        val chipRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val chips = ArrayList<android.widget.TextView>()
        fun paintChips(sel: Int) = chips.forEachIndexed { i, c ->
            val on = i == sel
            c.background = rounded(if (on) p.orange else p.fill, dpf(16))
            c.setTextColor(if (on) Color.WHITE else p.text)
        }
        Exercise.PRESETS.forEachIndexed { i, pre ->
            val chip = text(pre.label(), 14f, p.text, 500).apply {
                gravity = Gravity.CENTER
                setPadding(dp(14), dp(9), dp(14), dp(9))
                setOnClickListener { preset = pre; paintChips(i); recomputeKcal() }
            }
            chips += chip
            chipRow.addView(chip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) })
        }
        paintChips(0)

        minField.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = recomputeKcal()
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        kcalField.setOnFocusChangeListener { _, has -> if (has) userEditedKcal = true }

        val view = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(8), dp(22), dp(4))
            addView(text(tr("Loại bài tập", "Workout type"), 13f, p.secondary).apply { setPadding(dp(2), dp(4), 0, dp(6)) })
            addView(HorizontalScrollView(this@ExerciseActivity).apply { isHorizontalScrollBarEnabled = false; addView(chipRow) })
            addView(stepper(tr("Thời lượng (phút)", "Duration (min)"), minField, 5, 1, 1000))
            addView(stepper(tr("Calo đốt (kcal)", "Calories burned (kcal)"), kcalField, 10, 0, 5000))
        }
        AlertDialog.Builder(this)
            .setTitle(tr("Ghi tập luyện", "Log workout"))
            .setView(view)
            .setPositiveButton(tr("Thêm", "Add")) { _, _ ->
                val m = curMin()
                val kcal = kcalField.text.toString().toIntOrNull()?.coerceIn(0, 5000) ?: Exercise.estimate(preset.met, weight, m)
                Exercise.addEntry(this, date, preset.label(), m, kcal)
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
        fun btn(s: String, onClick: () -> Unit) = text(s, 22f, p.orange, 700).apply {
            gravity = Gravity.CENTER
            background = pressable(rounded(p.fill, dpf(12)), p.ripple); clipRounded()
            setOnClickListener { onClick() }
        }
        val minus = btn("−") { field.setText("${(cur() - step).coerceAtLeast(min)}"); field.setSelection(field.text.length) }
        val plus = btn("+") { field.setText("${(cur() + step).coerceAtMost(max)}"); field.setSelection(field.text.length) }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(labelText, 13f, p.secondary).apply { setPadding(dp(2), dp(12), 0, dp(4)) })
            addView(LinearLayout(this@ExerciseActivity).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(minus, LinearLayout.LayoutParams(dp(54), dp(46)))
                addView(field, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginStart = dp(10); marginEnd = dp(10) })
                addView(plus, LinearLayout.LayoutParams(dp(54), dp(46)))
            })
        }
    }

    private fun entryRow(e: ExerciseEntry): LinearLayout {
        val tf = SimpleDateFormat("HH:mm", Locale.getDefault())
        val time = if (e.t == 0L) tr("trước đây", "earlier") else tf.format(Date(e.t))
        val label = "$time · ${e.name} · ${e.minutes} ${tr("phút", "min")}"
        val trailing = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(text("${e.kcal} kcal", 15f, p.orange, 600))
            addView(ImageView(this@ExerciseActivity).apply {
                setImageResource(R.drawable.ic_delete)
                imageTintList = ColorStateList.valueOf(p.secondary)
                val pd = dp(8); setPadding(pd, pd, pd, pd)
                background = pressable(null, p.ripple); clipRounded()
                layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginStart = dp(6) }
                setOnClickListener { Exercise.removeEntry(this@ExerciseActivity, date, e.t); render() }
            })
        }
        return row(p, label, trailing)
    }

    // ---- selected day ----

    private fun dayCard(burned: Int, mins: Int, goal: Int): View {
        val ratio = if (goal > 0) burned.toFloat() / goal else 0f
        return card(p).apply {
            setPadding(dp(18), dp(16), dp(18), dp(16))
            addView(text(if (date == Store.today()) tr("Hôm nay", "Today") else Store.prettyDate(date), 13f, p.secondary))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.BOTTOM
                setPadding(0, dp(2), 0, dp(10))
                addView(text("$burned", 40f, if (ratio >= 1f) p.green else p.orange, 700))
                addView(text("  / $goal kcal ${tr("đốt", "burned")}", 16f, p.secondary).apply { setPadding(0, 0, 0, dp(6)) })
            })
            addView(progressBar(ratio), vlp(ViewGroup.LayoutParams.MATCH_PARENT, dp(10)))
            addView(
                text(
                    tr("Tổng $mins phút tập trong ngày.", "$mins minutes of exercise today."),
                    14f, p.secondary, 500,
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
                View(context).apply { background = gradient(dpf(5), p.orange, p.pink) },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, r),
            )
            addView(View(context), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f - r))
        }
    }

    // ---- month calendar ----

    private fun monthNav(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(iconButton(p, R.drawable.ic_chevron_left, p.orange) { shiftMonth(-1) })
        addView(
            text(Exercise.monthLabel(monthStart.get(Calendar.YEAR), monthStart.get(Calendar.MONTH)), 16f, p.text, 600)
                .apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        addView(iconButton(p, R.drawable.ic_chevron_right, p.orange) { shiftMonth(1) })
    }

    private fun shiftMonth(months: Int) { monthStart.add(Calendar.MONTH, months); render() }

    private fun calendarCard(goal: Int): View {
        val year = monthStart.get(Calendar.YEAR)
        val month0 = monthStart.get(Calendar.MONTH)
        val days = monthStart.getActualMaximum(Calendar.DAY_OF_MONTH)
        val offset = (monthStart.get(Calendar.DAY_OF_WEEK) + 5) % 7
        val log = Exercise.month(this, year, month0)
        val today = Store.today()

        return card(p).apply {
            setPadding(dp(10), dp(12), dp(10), dp(12))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                listOf(tr("T2", "Mon"), tr("T3", "Tue"), tr("T4", "Wed"), tr("T5", "Thu"), tr("T6", "Fri"), tr("T7", "Sat"), tr("CN", "Sun")).forEach { d ->
                    addView(text(d, 12f, p.secondary, 500).apply { gravity = Gravity.CENTER },
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
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
                        val d = Exercise.dateOf(year, month0, day)
                        val kcal = log[d] ?: 0
                        rowView.addView(
                            ExDayCell(context, p, day, if (goal > 0) kcal.toFloat() / goal else 0f, d == today, d == date)
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

    private fun monthStats(goal: Int): View {
        val log = Exercise.month(this, monthStart.get(Calendar.YEAR), monthStart.get(Calendar.MONTH))
        val logged = log.values.count { it > 0 }
        val reached = log.values.count { it >= goal }
        val sum = log.values.sum()
        return footnote(p, tr(
            "Tháng này: $logged ngày có tập · $reached ngày đạt mục tiêu · tổng đốt $sum kcal.",
            "This month: $logged days active · $reached days on goal · $sum kcal burned total.",
        ))
    }
}

/** One day in the exercise grid: a rounded track filled from the bottom by `ratio` of the goal. */
private class ExDayCell(
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
            paint.color = if (ratio >= 1f) p.green else p.orange
            c.drawRect(0f, rect.bottom - rect.height() * ratio, rect.right, rect.bottom, paint)
        }
        c.restore()
        if (isSelected || isToday) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = context.dpf(if (isSelected) 2f else 1.5f)
            paint.color = if (isSelected) p.orange else p.secondary
            val inset = paint.strokeWidth / 2f
            c.drawRoundRect(RectF(inset, inset, rect.right - inset, rect.bottom - inset), r, r, paint)
            paint.style = Paint.Style.FILL
        }
        label.color = if (ratio >= 0.55f) Color.WHITE else p.text
        val y = height / 2f - (label.descent() + label.ascent()) / 2f
        c.drawText(day.toString(), width / 2f, y, label)
    }
}
