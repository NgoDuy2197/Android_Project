package com.dsoft.healthassistant

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** Dashboard: calories vs target for the chosen day + the day's meals; add / review buttons. */
class MainActivity : Activity() {
    private lateinit var p: Palette
    private var date: String = Store.today()
    private lateinit var content: LinearLayout
    private lateinit var dateLabel: TextView
    private lateinit var titleLabel: TextView
    private var builtLang = ""
    private var builtTheme = ""
    private val onQueue = { runOnUiThread { if (::content.isInitialized && !isFinishing) render() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        p = palette()
        if (Users.current(this) == null) { goPicker(); return }
        buildFrame()
        runCatching { Reminders.reschedule(this) } // keep reminder alarms armed
    }

    override fun onResume() {
        super.onResume()
        if (Users.current(this) == null) { goPicker(); return }
        // language/theme changed in Settings -> rebuild the whole frame
        if (::content.isInitialized && (Prefs(this).lang != builtLang || Prefs(this).theme != builtTheme)) { recreate(); return }
        ReviewQueue.addListener(onQueue) // live-refresh the queue status on the dashboard
        if (::content.isInitialized) {
            titleLabel.text = Users.current(this)?.name ?: "HealthAssistant"
            render()
            // the widgets show the *current* user's numbers, so re-render them after a switch
            runCatching { WaterWidget.refreshAll(this) }
        }
    }

    override fun onPause() {
        super.onPause()
        ReviewQueue.removeListener(onQueue)
    }

    private fun goPicker() {
        if (isFinishing) return
        startActivity(Intent(this, UserPickerActivity::class.java))
        finish()
    }

    private fun buildFrame() {
        builtLang = Prefs(this).lang; builtTheme = Prefs(this).theme
        setupSystemBars(p)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(14), dp(10), dp(6))
        }
        // user chip -> switch user
        titleLabel = text(Users.current(this)?.name ?: "HealthAssistant", 22f, p.accent, 700)
        header.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = pressable(rounded(p.fill, dpf(12)), p.ripple); clipRounded()
            setPadding(dp(10), dp(6), dp(12), dp(6))
            addView(icon(R.drawable.ic_people, p.accent, 18).apply {
                (layoutParams as LinearLayout.LayoutParams).marginEnd = dp(7)
            })
            addView(titleLabel)
            setOnClickListener { goPicker() }
        })
        header.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        header.addView(iconButton(p, R.drawable.ic_person, p.accent) { startActivity(Intent(this, ProfileActivity::class.java)) })
        header.addView(iconButton(p, R.drawable.ic_settings, p.accent) { startActivity(Intent(this, AiSettingsActivity::class.java)) })
        root.addView(header)

        // date nav
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(16), dp(4))
        }
        nav.addView(iconButton(p, R.drawable.ic_chevron_left, p.accent) { shiftDate(-1) })
        dateLabel = text("", 15f, p.text, 600).apply { gravity = Gravity.CENTER }
        nav.addView(dateLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        nav.addView(iconButton(p, R.drawable.ic_chevron_right, p.accent) { shiftDate(1) })
        root.addView(nav)

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(40))
        }
        root.addView(ScrollView(this).apply {
            isFillViewport = true; setBackgroundColor(p.bg)
            addView(content)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root.apply { setBackgroundColor(p.bg) })
        render()
    }

    private fun shiftDate(days: Int) {
        val c = Calendar.getInstance()
        c.time = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(date) ?: return
        c.add(Calendar.DAY_OF_MONTH, days)
        date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(c.time)
        render()
    }

    private fun render() {
        dateLabel.text = if (date == Store.today()) "${tr("Hôm nay", "Today")} · ${Store.prettyDate(date)}" else Store.prettyDate(date)
        content.removeAllViews()

        if (!Store.profileConfigured(this)) {
            content.addView(card(p).apply {
                setPadding(dp(16), dp(16), dp(16), dp(16))
                addView(text(tr("Thiết lập hồ sơ", "Set up your profile"), 18f, p.text, 700))
                addView(text(tr("Nhập chiều cao, cân nặng, giới tính và mục tiêu để tính calo mục tiêu và nhận xét chính xác hơn.",
                    "Enter height, weight, gender and goal for an accurate calorie target and better reviews."), 14f, p.secondary).apply { setPadding(0, dp(6), 0, dp(12)) })
                addView(bigIconButton(tr("Thiết lập ngay", "Set up now"), R.drawable.ic_person, p.accent, p.accent2) { startActivity(Intent(this@MainActivity, ProfileActivity::class.java)) })
            }, vlp().apply { bottomMargin = dp(14) })
        }

        val meals = Store.mealsForDate(this, date)
        content.addView(summaryCard(meals), vlp().apply { bottomMargin = dp(14) })
        content.addView(weeklyCard(), vlp().apply { bottomMargin = dp(14) })
        content.addView(waterWeeklyCard(), vlp().apply { bottomMargin = dp(14) })
        content.addView(waterCard(), vlp().apply { bottomMargin = dp(14) })
        content.addView(exerciseCard(), vlp().apply { bottomMargin = dp(14) })

        // actions
        content.addView(bigIconButton(tr("Thêm bữa ăn", "Add meal"), R.drawable.ic_add, p.accent, p.accent2) { openEditor(null) }, vlp().apply { bottomMargin = dp(10) })
        if (meals.isNotEmpty()) {
            val savedDay = Store.loadDayReview(this, date).isNotBlank()
            val inflightDay = ReviewQueue.inFlightForDay(this, date)
            when {
                savedDay -> content.addView(bigIconButton(tr("Xem kết quả cả ngày", "View whole-day result"), R.drawable.ic_search, p.accent, p.accent2) {
                    startActivity(Intent(this, ReviewActivity::class.java).putExtra("savedDate", date))
                }, vlp().apply { bottomMargin = dp(14) })
                inflightDay != null -> content.addView(bigIconButton(
                    if (inflightDay.status == ReviewQueue.Status.AWAITING) tr("Xem kết quả (chờ duyệt)", "View result (to approve)")
                    else tr("Đang xử lý cả ngày…", "Processing whole day…"),
                    R.drawable.ic_search, p.orange, p.orange) {
                    startActivity(Intent(this, ReviewActivity::class.java).putExtra("jobId", inflightDay.id))
                }, vlp().apply { bottomMargin = dp(14) })
                else -> content.addView(bigIconButton(tr("Xem xét cả ngày", "Review whole day"), R.drawable.ic_search, p.indigo, p.blue) { reviewDay() }, vlp().apply { bottomMargin = dp(14) })
            }
        }

        val activeJobs = ReviewQueue.activeCount()
        if (activeJobs > 0) content.addView(queueBanner(activeJobs), vlp().apply { bottomMargin = dp(14) })

        // meal list
        content.addView(sectionHeader(p, tr("Bữa ăn (${meals.size})", "Meals (${meals.size})")))
        if (meals.isEmpty()) {
            content.addView(footnote(p, tr("Chưa có bữa nào. Bấm “Thêm bữa ăn”, hoặc dùng widget màn hình chính để ghi nhanh.",
                "No meals yet. Tap “Add meal”, or use the home-screen widget for quick logging.")))
        } else {
            val rows = meals.map { mealRow(it) }.toTypedArray()
            content.addView(group(p, *rows))
        }

        content.addView(sectionHeader(p, tr("Tiện ích", "Tools")))

        val (waterRow, waterSub) = toolRow(p, R.drawable.ic_water, p.blue, tr("Uống nước", "Water")) {
            startActivity(Intent(this, WaterActivity::class.java).putExtra("date", date))
        }
        waterSub.text = tr("Mục tiêu ${Water.targetMl(this)} ml · cốc ${Water.cupMl(this)} ml", "Target ${Water.targetMl(this)} ml · cup ${Water.cupMl(this)} ml")

        val (exRow, exSub) = toolRow(p, R.drawable.ic_dumbbell, p.orange, tr("Tập luyện", "Exercise")) {
            startActivity(Intent(this, ExerciseActivity::class.java).putExtra("date", date))
        }
        exSub.text = tr("Đã đốt ${Exercise.burned(this, date)} kcal · mục tiêu ${Exercise.goalKcal(this)} kcal",
            "Burned ${Exercise.burned(this, date)} kcal · goal ${Exercise.goalKcal(this)} kcal")

        val (weightRow, weightSub) = toolRow(p, R.drawable.ic_person, p.teal, tr("Cân nặng", "Weight")) {
            startActivity(Intent(this, WeightActivity::class.java))
        }
        run {
            val prof = Store.loadProfile(this)
            val w = Weight.latest(this)?.kg ?: prof.weightKg
            fun n(v: Double) = if (v == v.toLong().toDouble()) v.toLong().toString() else String.format(Locale.US, "%.1f", v)
            weightSub.text = tr("Hiện tại ${n(w)} kg · mục tiêu ${n(prof.targetWeightKg)} kg",
                "Current ${n(w)} kg · target ${n(prof.targetWeightKg)} kg")
        }
        content.addView(group(p, waterRow, exRow, weightRow))

        val (statsRow, statsSub) = toolRow(p, R.drawable.ic_chart, p.purple, tr("Thống kê", "Statistics")) {
            startActivity(Intent(this, StatsActivity::class.java))
        }
        statsSub.text = tr("Trung bình, macro, chuỗi ngày ghi", "Averages, macros, logging streak")

        val (remindRow, remindSub) = toolRow(p, R.drawable.ic_bell, p.pink, tr("Nhắc nhở", "Reminders")) {
            startActivity(Intent(this, RemindersActivity::class.java))
        }
        remindSub.text = Prefs(this).let { pr ->
            if (pr.waterRemind || pr.mealRemind) tr("Đang bật", "On") else tr("Đang tắt", "Off")
        }

        val (backupRow, backupSub) = toolRow(p, R.drawable.ic_download, p.green, tr("Sao lưu & khôi phục", "Backup & restore")) {
            startActivity(Intent(this, BackupActivity::class.java))
        }
        backupSub.text = tr("Xuất / nhập toàn bộ dữ liệu", "Export / import all data")

        val (queueRow, queueSub) = toolRow(p, R.drawable.ic_list, p.orange, tr("Hàng đợi xem xét", "Review queue")) {
            startActivity(Intent(this, ReviewQueueActivity::class.java))
        }
        queueSub.text = ReviewQueue.jobs().let { all ->
            val act = all.count { it.status == ReviewQueue.Status.QUEUED || it.status == ReviewQueue.Status.RUNNING || it.status == ReviewQueue.Status.AWAITING }
            if (act > 0) tr("$act đang xử lý/chờ duyệt", "$act processing/pending") else if (all.isEmpty()) tr("Trống", "Empty") else tr("${all.size} mục", "${all.size} items")
        }
        content.addView(group(p, statsRow, remindRow, backupRow, queueRow))
    }

    /** Last-7-days calorie bars; each day keeps its own saved target, bar colour by zone. */
    private fun weeklyCard(): View = card(p).apply {
        setPadding(dp(14), dp(14), dp(12), dp(10))
        addView(text(tr("Calo 7 ngày gần đây", "Calories, last 7 days"), 13f, p.secondary).apply { setPadding(dp(2), 0, 0, dp(6)) })
        val meta = Store.loadDayMeta(this@MainActivity)
        val curTarget = Store.loadProfile(this@MainActivity).targetCalories()
        val today = Store.today()
        val bars = last7Days().map { (d, dow) ->
            val v = dayCalories(d)
            val t = meta.optJSONObject(d)?.optInt("cal")?.takeIf { it > 0 } ?: curTarget
            WeeklyBarChart.Bar(dayShort(dow), v, t, calZone(v, t), highlight = d == today)
        }
        addView(WeeklyBarChart(this@MainActivity, p, bars),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(172)))
        addView(chartCaption(tr("Cột: calo đã duyệt • vạch ngang: mục tiêu ngày đó • xanh dương=thiếu, xanh lá=đạt, cam=vượt",
            "Bars: approved calories • tick: that day's target • blue=under, green=on target, orange=over")))
    }

    /** Last-7-days water bars; each day keeps its own saved target, bar colour by zone. */
    private fun waterWeeklyCard(): View = card(p).apply {
        setPadding(dp(14), dp(14), dp(12), dp(10))
        addView(text(tr("Nước uống 7 ngày", "Water, last 7 days"), 13f, p.secondary).apply { setPadding(dp(2), 0, 0, dp(6)) })
        val meta = Store.loadDayMeta(this@MainActivity)
        val curTarget = Water.targetMl(this@MainActivity)
        val today = Store.today()
        val bars = last7Days().map { (d, dow) ->
            val v = Water.amount(this@MainActivity, d)
            val t = meta.optJSONObject(d)?.optInt("water")?.takeIf { it > 0 } ?: curTarget
            WeeklyBarChart.Bar(dayShort(dow), v, t, waterZone(v, t), highlight = d == today)
        }
        addView(WeeklyBarChart(this@MainActivity, p, bars),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(172)))
        addView(chartCaption(tr("Cột: ml đã uống • vạch ngang: mục tiêu ngày đó • cam=thiếu, xanh lá=đạt, xanh dương=nhiều",
            "Bars: ml drunk • tick: that day's target • orange=under, green=on target, blue=more")))
    }

    private fun chartCaption(s: String) = text(s, 11.5f, p.secondary).apply {
        setPadding(dp(2), dp(8), dp(2), dp(2)); setLineSpacing(0f, 1.1f)
    }

    // calories: under = thiếu (xanh dương), trong khoảng ±10% = đạt (xanh lá), vượt = cam
    private fun calZone(v: Int, t: Int): Int = when {
        v == 0 || t <= 0 -> p.green
        v > t * 1.1 -> p.orange
        v < t * 0.9 -> p.blue
        else -> p.green
    }

    // water: under = chưa đủ (cam), trong khoảng = đạt (xanh lá), vượt = nhiều (xanh dương)
    private fun waterZone(v: Int, t: Int): Int = when {
        v == 0 || t <= 0 -> p.green
        v > t * 1.1 -> p.blue
        v < t * 0.9 -> p.orange
        else -> p.green
    }

    /** The last 7 dates ending at [date], as (yyyy-MM-dd, Calendar day-of-week). */
    private fun last7Days(): List<Pair<String, Int>> {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val cal = Calendar.getInstance()
        cal.time = fmt.parse(date) ?: return emptyList()
        cal.add(Calendar.DAY_OF_MONTH, -6)
        val out = ArrayList<Pair<String, Int>>(7)
        repeat(7) {
            out.add(fmt.format(cal.time) to cal.get(Calendar.DAY_OF_WEEK))
            cal.add(Calendar.DAY_OF_MONTH, 1)
        }
        return out
    }

    /** Calories counted for a day: the whole-day review wins, else the sum of reviewed meals. */
    private fun dayCalories(d: String): Int {
        val dayReview = Store.loadDayReview(this, d).let { if (it.isNotBlank()) Ai.parse(it) else null }
        if (dayReview != null) return dayReview.calories
        return Store.mealsForDate(this, d).sumOf { m -> if (m.reviewJson.isNotBlank()) Ai.parse(m.reviewJson)?.calories ?: 0 else 0 }
    }

    private fun dayShort(dow: Int) = when (dow) {
        Calendar.MONDAY -> tr("T2", "Mon"); Calendar.TUESDAY -> tr("T3", "Tue"); Calendar.WEDNESDAY -> tr("T4", "Wed")
        Calendar.THURSDAY -> tr("T5", "Thu"); Calendar.FRIDAY -> tr("T6", "Fri"); Calendar.SATURDAY -> tr("T7", "Sat"); else -> tr("CN", "Sun")
    }

    /** Compact water progress for [date], with a one-tap "+1 cup". */
    private fun waterCard(): View {
        val target = Water.targetMl(this)
        val cup = Water.cupMl(this)
        val now = Water.amount(this, date)
        val ratio = (if (target > 0) now.toFloat() / target else 0f).coerceIn(0f, 1f)

        return card(p).apply {
            setPadding(dp(18), dp(14), dp(14), dp(14))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(text(tr("Nước uống", "Water"), 13f, p.secondary))
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.BOTTOM
                        setPadding(0, dp(2), 0, 0)
                        addView(text("$now", 26f, if (ratio >= 1f) p.green else p.blue, 700))
                        addView(text("  / $target ml · ${Water.cupsLabel(now, cup)}", 13f, p.secondary)
                            .apply { setPadding(0, 0, 0, dp(3)) })
                    })
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(ImageView(context).apply {
                    setImageResource(R.drawable.ic_add)
                    imageTintList = ColorStateList.valueOf(Color.WHITE)
                    val pd = dp(9); setPadding(pd, pd, pd, pd)
                    background = pressable(gradient(dpf(14), p.blue, p.teal), 0x33FFFFFF)
                    clipRounded()
                    layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
                    setOnClickListener { Water.add(this@MainActivity, date, cup); render() }
                })
            })
            // progress track
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                background = rounded(p.fill, dpf(5))
                clipRounded()
                addView(View(context).apply { background = gradient(dpf(5), p.blue, p.teal) },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, ratio))
                addView(View(context), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f - ratio))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(10)).apply { topMargin = dp(12) })
            background = pressable(rounded(p.card, dpf(14)), p.ripple)
            clipRounded()
            setOnClickListener { startActivity(Intent(this@MainActivity, WaterActivity::class.java).putExtra("date", date)) }
        }
    }

    /** Compact exercise progress for [date]; taps open the exercise log. */
    private fun exerciseCard(): View {
        val goal = Exercise.goalKcal(this)
        val burned = Exercise.burned(this, date)
        val mins = Exercise.minutes(this, date)
        val ratio = (if (goal > 0) burned.toFloat() / goal else 0f).coerceIn(0f, 1f)
        return card(p).apply {
            setPadding(dp(18), dp(14), dp(14), dp(14))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(text(tr("Tập luyện", "Exercise"), 13f, p.secondary))
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.BOTTOM
                        setPadding(0, dp(2), 0, 0)
                        addView(text("$burned", 26f, if (ratio >= 1f) p.green else p.orange, 700))
                        addView(text("  / $goal kcal · $mins ${tr("phút", "min")}", 13f, p.secondary)
                            .apply { setPadding(0, 0, 0, dp(3)) })
                    })
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(ImageView(context).apply {
                    setImageResource(R.drawable.ic_add)
                    imageTintList = ColorStateList.valueOf(Color.WHITE)
                    val pd = dp(9); setPadding(pd, pd, pd, pd)
                    background = pressable(gradient(dpf(14), p.orange, p.pink), 0x33FFFFFF)
                    clipRounded()
                    layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
                    setOnClickListener { startActivity(Intent(this@MainActivity, ExerciseActivity::class.java).putExtra("date", date)) }
                })
            })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                background = rounded(p.fill, dpf(5))
                clipRounded()
                addView(View(context).apply { background = gradient(dpf(5), p.orange, p.pink) },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, ratio))
                addView(View(context), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f - ratio))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(10)).apply { topMargin = dp(12) })
            background = pressable(rounded(p.card, dpf(14)), p.ripple)
            clipRounded()
            setOnClickListener { startActivity(Intent(this@MainActivity, ExerciseActivity::class.java).putExtra("date", date)) }
        }
    }

    private fun summaryCard(meals: List<Meal>): View {
        val profile = Store.loadProfile(this)
        val dayReview = Store.loadDayReview(this, date).let { if (it.isNotBlank()) Ai.parse(it) else null }
        var total = 0; var reviewed = 0
        meals.forEach { m ->
            if (m.reviewJson.isNotBlank()) { total += Ai.parse(m.reviewJson)?.calories ?: 0; reviewed++ }
        }
        if (dayReview != null) { total = dayReview.calories; reviewed = meals.size } // whole-day review wins
        val target = profile.targetCalories()
        val burned = Exercise.burned(this, date)
        val remaining = target - total

        return card(p).apply {
            setPadding(dp(18), dp(16), dp(18), dp(16))
            addView(text(tr("Calo hôm nay (ước tính)", "Calories today (estimated)"), 13f, p.secondary))
            val big = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
            big.addView(text("$total", 40f, p.accent, 700))
            big.addView(text("  / $target kcal", 16f, p.secondary).apply { setPadding(0, 0, 0, dp(6)) })
            addView(big.apply { setPadding(0, dp(2), 0, dp(2)) })
            val remLabel = if (remaining >= 0) tr("Còn lại ~$remaining kcal để đạt mục tiêu", "~$remaining kcal left to target")
            else tr("Vượt mục tiêu ~${-remaining} kcal", "~${-remaining} kcal over target")
            addView(text(remLabel, 14f, if (remaining >= 0) p.secondary else p.orange, 500))
            if (burned > 0)
                addView(text(tr("🔥 Đã đốt ~$burned kcal từ tập · còn có thể nạp ~${target - total + burned} kcal",
                    "🔥 Burned ~$burned kcal · ~${target - total + burned} kcal left incl. exercise"), 12.5f, p.orange, 500)
                    .apply { setPadding(0, dp(6), 0, 0) })
            if (reviewed < meals.size && dayReview == null)
                addView(text(tr("• ${meals.size - reviewed} bữa chưa “Xem xét” nên chưa tính calo.", "• ${meals.size - reviewed} meal(s) not reviewed yet, so not counted."), 12.5f, p.secondary).apply { setPadding(0, dp(6), 0, 0) })
            addView(View(context).apply { setBackgroundColor(p.separator) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply { topMargin = dp(12) })
            addView(text("TDEE ~${profile.tdee()} • ${tr("Mục tiêu", "Goal")}: ${profile.goalLabel()} • BMI ${String.format("%.1f", profile.bmi())} (${profile.bmiLabel()})  ⓘ",
                13f, p.accent, 500).apply {
                setPadding(0, dp(10), 0, 0)
                setOnClickListener { showHealthInfo(profile) }
            })
        }
    }

    /** Explain BMR / TDEE / target / BMI when the user taps the summary line. */
    private fun showHealthInfo(prof: Profile) {
        val msg = tr(
            "• BMR (chuyển hoá cơ bản): ~${prof.bmr()} kcal/ngày — năng lượng cơ thể cần khi nghỉ hoàn toàn.\n\n" +
                "• TDEE (tổng tiêu hao/ngày): ~${prof.tdee()} kcal — BMR nhân mức vận động (${prof.activityLabel()}). Đây là mức calo để GIỮ cân.\n\n" +
                "• Calo mục tiêu: ~${prof.targetCalories()} kcal/ngày — điều chỉnh từ TDEE theo mục tiêu “${prof.goalLabel()}”.\n\n" +
                "• BMI: ${String.format("%.1f", prof.bmi())} (${prof.bmiLabel()}) — cân nặng ÷ chiều cao².",
            "• BMR (basal metabolic rate): ~${prof.bmr()} kcal/day — energy needed at complete rest.\n\n" +
                "• TDEE (total daily energy): ~${prof.tdee()} kcal — BMR × your activity level (${prof.activityLabel()}). This maintains weight.\n\n" +
                "• Target calories: ~${prof.targetCalories()} kcal/day — adjusted from TDEE for “${prof.goalLabel()}”.\n\n" +
                "• BMI: ${String.format("%.1f", prof.bmi())} (${prof.bmiLabel()}) — weight ÷ height².",
        )
        android.app.AlertDialog.Builder(this)
            .setTitle(tr("Giải thích chỉ số", "What these mean"))
            .setMessage(msg)
            .setPositiveButton(tr("Đã hiểu", "Got it"), null)
            .show()
    }

    /** Tappable card shown while the review queue has work in flight. */
    private fun queueBanner(n: Int): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(56); setPadding(dp(16), dp(10), dp(12), dp(10))
        background = pressable(rounded(p.card, dpf(14)), p.ripple); clipRounded()
        setOnClickListener { startActivity(Intent(this@MainActivity, ReviewQueueActivity::class.java)) }
        addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_list)
            imageTintList = ColorStateList.valueOf(Color.WHITE)
            val pd = dp(7); setPadding(pd, pd, pd, pd)
            background = gradient(dpf(10), p.orange, p.orange); clipRounded()
            layoutParams = LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginEnd = dp(12) }
        })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(tr("Đang xem xét…", "Reviewing…"), 16f, p.text, 600))
            addView(text(tr("$n yêu cầu đang xử lý/chờ trong hàng đợi", "$n request(s) processing/pending in the queue"), 13f, p.secondary).apply { setPadding(0, dp(2), 0, 0) })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(chevron(p))
    }

    private fun mealRow(m: Meal): View {
        val kcal = if (m.reviewJson.isNotBlank()) Ai.parse(m.reviewJson)?.calories else null
        val firstLine = m.items.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        val title = buildString {
            append(m.typeLabel())
            if (m.time.isNotBlank()) append(" · ${m.time}")
        }
        val inflight = ReviewQueue.inFlightForMeal(this, m.id)
        val trailing = when {
            inflight?.status == ReviewQueue.Status.AWAITING -> text(tr("chờ duyệt", "to approve"), 14f, p.blue, 600)
            inflight != null -> text(tr("đang xử lý…", "processing…"), 14f, p.orange, 600)
            kcal != null -> text("$kcal kcal", 14f, p.green, 600)
            else -> text(tr("chưa xem xét", "not reviewed"), 14f, p.secondary, 600)
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(56); setPadding(dp(14), dp(8), dp(12), dp(8))
            background = pressable(null, p.ripple)
            setOnClickListener { openEditor(m.id) }
            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_restaurant)
                imageTintList = ColorStateList.valueOf(Color.WHITE)
                val pd = dp(7); setPadding(pd, pd, pd, pd)
                background = gradient(dpf(10), p.accent, p.accent2)
                clipRounded()
                layoutParams = LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginEnd = dp(12) }
            })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(text(title, 16f, p.text, 500))
                addView(text(firstLine.ifBlank { tr("(trống)", "(empty)") }, 13f, p.secondary).apply {
                    maxLines = 1; setPadding(0, dp(2), 0, 0)
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(trailing)
            addView(chevron(p))
        }
    }

    private fun openEditor(id: Long?) {
        startActivity(Intent(this, MealEditorActivity::class.java).apply {
            putExtra("date", date)
            if (id != null) putExtra("mealId", id)
        })
    }

    private fun reviewDay() {
        startActivity(Intent(this, ReviewActivity::class.java).putExtra("date", date))
    }
}
