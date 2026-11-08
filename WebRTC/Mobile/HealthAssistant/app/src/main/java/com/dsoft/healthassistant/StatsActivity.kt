package com.dsoft.healthassistant

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import java.util.Calendar

/** Monthly statistics: logging streak, average calories & macros, water and exercise summaries. */
class StatsActivity : Activity() {
    private lateinit var p: Palette
    private lateinit var content: LinearLayout
    private var monthStart: Calendar = Water.monthStart(Store.today())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        p = palette()
        val col = page(p, tr("Thống kê", "Statistics"))
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(content)
        render()
    }

    private fun render() {
        content.removeAllViews()
        content.addView(streakCard(), vlp().apply { bottomMargin = dp(12) })
        content.addView(monthNav())

        val year = monthStart.get(Calendar.YEAR)
        val month0 = monthStart.get(Calendar.MONTH)
        val days = monthStart.getActualMaximum(Calendar.DAY_OF_MONTH)
        val calTarget = Store.loadProfile(this).targetCalories()
        val meals = Store.loadMeals(this).filter { it.date.startsWith(String.format("%04d-%02d-", year, month0 + 1)) }
        val mealsByDate = meals.groupBy { it.date }

        var calDays = 0; var calSum = 0; var calOnTarget = 0
        var pSum = 0; var cSum = 0; var fSum = 0
        for (day in 1..days) {
            val d = Water.dateOf(year, month0, day)
            val review = Store.loadDayReview(this, d).let { if (it.isNotBlank()) Ai.parse(it) else null }
            val r = review ?: combineMeals(mealsByDate[d])
            if (r != null && r.calories > 0) {
                calDays++; calSum += r.calories
                pSum += r.protein; cSum += r.carb; fSum += r.fat
                if (r.calories in (calTarget * 0.9).toInt()..(calTarget * 1.1).toInt()) calOnTarget++
            }
        }
        val calAvg = if (calDays > 0) calSum / calDays else 0

        // water
        val waterLog = Water.month(this, year, month0)
        val waterTarget = Water.targetMl(this)
        val waterDays = waterLog.values.count { it > 0 }
        val waterAvg = if (waterDays > 0) waterLog.values.sum() / waterDays else 0
        val waterOnTarget = waterLog.values.count { it >= waterTarget }

        // exercise
        val exLog = Exercise.month(this, year, month0)
        val exDays = exLog.values.count { it > 0 }
        val exKcal = exLog.values.sum()
        val exMinutes = (1..days).sumOf { Exercise.minutes(this, Water.dateOf(year, month0, it)) }

        content.addView(sectionHeader(p, tr("Calo", "Calories")))
        content.addView(group(p,
            statRow(tr("Trung bình / ngày", "Average / day"), if (calDays > 0) "$calAvg kcal" else "—"),
            statRow(tr("Ngày đạt mục tiêu", "Days on target"), "$calOnTarget / $calDays"),
            statRow(tr("Số ngày có ghi", "Days logged"), "$calDays"),
        ))

        content.addView(sectionHeader(p, tr("Dinh dưỡng (TB/ngày)", "Nutrition (avg/day)")))
        content.addView(group(p,
            statRow(tr("Đạm", "Protein"), if (calDays > 0) "${pSum / calDays} g" else "—"),
            statRow(tr("Tinh bột", "Carbs"), if (calDays > 0) "${cSum / calDays} g" else "—"),
            statRow(tr("Béo", "Fat"), if (calDays > 0) "${fSum / calDays} g" else "—"),
        ))

        content.addView(sectionHeader(p, tr("Nước uống", "Water")))
        content.addView(group(p,
            statRow(tr("Trung bình / ngày", "Average / day"), if (waterDays > 0) "$waterAvg ml" else "—"),
            statRow(tr("Ngày đạt mục tiêu", "Days on target"), "$waterOnTarget / $waterDays"),
        ))

        content.addView(sectionHeader(p, tr("Tập luyện", "Exercise")))
        content.addView(group(p,
            statRow(tr("Tổng calo đốt", "Total burned"), "$exKcal kcal"),
            statRow(tr("Tổng thời lượng", "Total time"), "$exMinutes ${tr("phút", "min")}"),
            statRow(tr("Số ngày có tập", "Days active"), "$exDays"),
        ))
        content.addView(footnote(p, tr("Trung bình tính trên các ngày có ghi dữ liệu.", "Averages are over days with logged data.")))
    }

    /** Current consecutive days (ending today) that have at least one meal logged. */
    private fun streakCard(): View {
        val datesWithMeals = Store.loadMeals(this).map { it.date }.toHashSet()
        val c = Calendar.getInstance()
        var streak = 0
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        while (true) {
            val d = fmt.format(c.time)
            if (datesWithMeals.contains(d)) { streak++; c.add(Calendar.DAY_OF_MONTH, -1) } else break
        }
        return card(p).apply {
            setPadding(dp(18), dp(16), dp(18), dp(16))
            addView(text(tr("Chuỗi ngày ghi chép", "Logging streak"), 13f, p.secondary))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM
                setPadding(0, dp(2), 0, 0)
                addView(text("$streak", 40f, p.accent, 700))
                addView(text("  ${tr("ngày", if (streak == 1) "day" else "days")} 🔥", 16f, p.secondary).apply { setPadding(0, 0, 0, dp(6)) })
            })
            addView(text(
                if (streak > 0) tr("Tiếp tục ghi bữa ăn mỗi ngày để giữ chuỗi!", "Keep logging meals daily to keep the streak!")
                else tr("Ghi một bữa ăn hôm nay để bắt đầu chuỗi.", "Log a meal today to start a streak."),
                13f, p.secondary).apply { setPadding(0, dp(8), 0, 0) })
        }
    }

    private fun statRow(title: String, value: String) =
        row(p, title, text(value, 16f, p.secondary))

    private fun combineMeals(meals: List<Meal>?): Ai.Review? {
        if (meals.isNullOrEmpty()) return null
        var cal = 0; var pr = 0; var cb = 0; var ft = 0; var any = false
        meals.forEach { m ->
            if (m.reviewJson.isNotBlank()) {
                Ai.parse(m.reviewJson)?.let { r -> cal += r.calories; pr += r.protein; cb += r.carb; ft += r.fat; any = true }
            }
        }
        return if (any) Ai.Review(cal, pr, cb, ft, emptyList(), "", emptyList(), emptyList(), "") else null
    }

    private fun monthNav(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(iconButton(p, R.drawable.ic_chevron_left, p.accent) { monthStart.add(Calendar.MONTH, -1); render() })
        addView(
            text(Water.monthLabel(monthStart.get(Calendar.YEAR), monthStart.get(Calendar.MONTH)), 16f, p.text, 600)
                .apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        addView(iconButton(p, R.drawable.ic_chevron_right, p.accent) { monthStart.add(Calendar.MONTH, 1); render() })
    }
}
