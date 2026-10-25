package com.dsoft.odiary

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Calendar

/** Home dashboard: month calendar (or list) + an overview area. Account ops live in Settings. */
class MainActivity : FxActivity() {
    private val cal = Calendar.getInstance()
    private lateinit var monthLabel: TextView
    private lateinit var grid: LinearLayout
    private lateinit var navBar: LinearLayout
    private lateinit var weekRow: LinearLayout
    private lateinit var overview: LinearLayout
    private lateinit var viewToggle: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // No valid account chosen (fresh install / deleted / signed out) -> go pick one.
        val cur = Prefs.currentAccount(this)
        if (cur.isEmpty() || Accounts.get(cur) == null) {
            startActivity(Intent(this, AccountActivity::class.java)); finish(); return
        }
        cal.set(Calendar.DAY_OF_MONTH, 1)
        buildUi()
    }

    override fun onResume() {
        super.onResume()
        if (::grid.isInitialized) render()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(12), dp(8))
        }
        header.addView(text("ODiary", 24f, p.accent, 700))
        header.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        viewToggle = iconBtn("📋") { toggleView() }
        header.addView(viewToggle)
        header.addView(iconBtn("📝") { openDayEdit(todayStr()) })
        header.addView(iconBtn("⚙️") { startActivity(Intent(this, SettingsActivity::class.java)) })
        root.addView(header)

        // month nav (hidden in list mode)
        navBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), 0, dp(16), dp(6)) }
        navBar.addView(iconBtn("‹") { cal.add(Calendar.MONTH, -1); render() })
        monthLabel = text("", 18f, p.text, 600).apply { gravity = Gravity.CENTER }
        navBar.addView(monthLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        navBar.addView(iconBtn("›") { cal.add(Calendar.MONTH, 1); render() })
        root.addView(navBar)

        // weekday row (hidden in list mode)
        weekRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(10), dp(2), dp(10), dp(2)) }
        for (w in listOf("T2", "T3", "T4", "T5", "T6", "T7", "CN"))
            weekRow.addView(text(w, 12f, p.sub, 600).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(weekRow)

        // scrollable body: grid/list then overview
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), 0, dp(8), dp(8)) }
        body.addView(grid)
        overview = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(6), dp(14), dp(20)) }
        body.addView(overview)
        root.addView(ScrollView(this).apply { isFillViewport = true; addView(body) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setFxContent(root)
        render()
    }

    private fun iconBtn(s: String, onClick: () -> Unit): TextView = text(s, 20f, p.accent, 700).apply {
        gravity = Gravity.CENTER
        minWidth = dp(44); minHeight = dp(44)
        background = rounded(p.fill, dp(14).toFloat())
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(6) }
    }

    // ---------- view switching ----------
    private fun toggleView() {
        Prefs.setViewMode(this, if (Prefs.viewMode(this) == "calendar") "list" else "calendar")
        render()
    }

    private fun render() {
        val listMode = Prefs.viewMode(this) == "list"
        navBar.visibility = if (listMode) View.GONE else View.VISIBLE
        weekRow.visibility = if (listMode) View.GONE else View.VISIBLE
        viewToggle.text = emojify(if (listMode) "📅" else "📋")
        if (listMode) renderList() else renderMonth()
        renderOverview()
    }

    // ---------- calendar ----------
    private fun renderMonth() {
        grid.setPadding(dp(8), 0, dp(8), dp(8))
        val months = arrayOf("Tháng 1", "Tháng 2", "Tháng 3", "Tháng 4", "Tháng 5", "Tháng 6",
            "Tháng 7", "Tháng 8", "Tháng 9", "Tháng 10", "Tháng 11", "Tháng 12")
        monthLabel.text = "${months[cal.get(Calendar.MONTH)]} ${cal.get(Calendar.YEAR)}"
        grid.removeAllViews()

        val year = cal.get(Calendar.YEAR); val month = cal.get(Calendar.MONTH)
        val first = cal.clone() as Calendar
        first.set(Calendar.DAY_OF_MONTH, 1)
        val dow = first.get(Calendar.DAY_OF_WEEK) // Sun=1..Sat=7
        val lead = ((dow + 5) % 7)
        val daysInMonth = first.getActualMaximum(Calendar.DAY_OF_MONTH)
        val content = Store.contentDays()
        val today = todayStr()

        var day = 1 - lead
        while (day <= daysInMonth) {
            // does this week have any day with content? -> tall row, else compact
            var rowHasContent = false
            for (c in 0 until 7) { val d = day + c; if (d in 1..daysInMonth && content.contains(String.format("%04d-%02d-%02d", year, month + 1, d))) { rowHasContent = true; break } }
            val rowH = if (rowHasContent) dp(80) else dp(34)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rowH).apply { topMargin = dp(6) }
            }
            for (c in 0 until 7) {
                val cellLp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { marginStart = dp(3); marginEnd = dp(3) }
                if (day in 1..daysInMonth) {
                    val date = String.format("%04d-%02d-%02d", year, month + 1, day)
                    row.addView(cell(date, day, content.contains(date), date == today), cellLp)
                } else row.addView(View(this), cellLp)
                day++
            }
            grid.addView(row)
        }
    }

    private fun cell(date: String, dayNum: Int, hasContent: Boolean, isToday: Boolean): View {
        val fl = FrameLayout(this).apply {
            background = rounded(if (hasContent) p.card else p.fill, dp(14).toFloat())
            if (isToday) foreground = ringDrawable()
            setOnClickListener { openDay(date) }
        }
        if (hasContent) {
            // thumbnail behind, number top-left, day icon on the right
            val iv = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true; background = rounded(0, dp(14).toFloat()) }
            fl.addView(iv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            loadThumb(date, iv)
            fl.addView(text("$dayNum", 13f, 0xFFFFFFFF.toInt(), 700).apply {
                setPadding(dp(6), dp(4), 0, 0); setShadowLayer(dp(3).toFloat(), 0f, 0f, 0xCC000000.toInt())
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START))
            val icon = Store.loadDay(date).emoji.ifEmpty { "🙂" }
            fl.addView(text(icon, 18f, 0xFFFFFFFF.toInt()).apply {
                setPadding(0, 0, dp(5), dp(4)); setShadowLayer(dp(2).toFloat(), 0f, 0f, 0xAA000000.toInt())
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END))
        } else {
            // empty day: just the number, compact
            fl.addView(text("$dayNum", 13f, p.text, 600).apply { setPadding(dp(6), dp(4), 0, 0) },
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START))
        }
        return fl
    }

    private fun ringDrawable() = android.graphics.drawable.GradientDrawable().apply {
        setColor(0); cornerRadius = dpf(14); setStroke(dp(2), p.accent)
    }

    private fun loadThumb(date: String, iv: ImageView) {
        Thread {
            val f = Store.firstPhoto(date) ?: return@Thread
            val bmp = Store.decodeSampled(f, 160) ?: return@Thread
            App.main.post { iv.setImageBitmap(bmp) }
        }.start()
    }

    // ---------- list ----------
    private fun renderList() {
        grid.setPadding(dp(14), dp(4), dp(14), dp(8))
        monthLabel.text = ""
        grid.removeAllViews()
        val days = Store.contentDays().sortedDescending()
        if (days.isEmpty()) { grid.addView(text("Chưa có nhật ký nào. Nhấn 📝 để viết hôm nay.", 14f, p.sub)); return }
        days.forEach { date ->
            val d = Store.loadDay(date)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                background = rounded(p.card, dp(16).toFloat()); setPadding(dp(12), dp(10), dp(14), dp(10))
                elevation = dpf(3)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) }
                setOnClickListener { openDay(date) }
            }
            val thumb = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true
                background = rounded(p.fill, dp(12).toFloat())
                layoutParams = LinearLayout.LayoutParams(dp(54), dp(54)).apply { marginEnd = dp(12) }
            }
            loadThumb(date, thumb)
            row.addView(thumb)
            val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            info.addView(text(d.emoji.ifEmpty { "🙂" } + "  " + prettyDate(date), 15f, p.text, 600))
            val sub = buildString {
                append("${d.events.size} khoảnh khắc")
                if (d.label.isNotEmpty()) append(" · ${d.label}")
            }
            info.addView(text(sub, 12f, p.sub))
            row.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(text("›", 20f, p.sub))
            grid.addView(row)
        }
    }

    // ---------- overview ----------
    private fun renderOverview() {
        overview.removeAllViews()
        val content = Store.contentDays()
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(p.card, dp(18).toFloat()); setPadding(dp(16), dp(14), dp(16), dp(14)); elevation = dpf(3)
        }
        card.addView(text("Tổng quan ✨", 16f, p.text, 700).apply { setPadding(0, 0, 0, dp(10)) })

        val streak = currentStreak(content)
        val thisMonth = content.count { it.startsWith(String.format("%04d-%02d", cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1)) }

        val statsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        statsRow.addView(statTile("📔", "${content.size}", "ngày"))
        statsRow.addView(statTile("🔥", "$streak", "chuỗi ngày"))
        statsRow.addView(statTile("🗓", "$thisMonth", "tháng này"))
        val photoTile = statTile("📸", "…", "ảnh")
        statsRow.addView(photoTile)
        card.addView(statsRow)

        val msg = when {
            content.isEmpty() -> "Bắt đầu trang đầu tiên hôm nay nhé! 🌱"
            streak >= 3 -> "Tuyệt vời! Giữ vững chuỗi $streak ngày 🎉"
            else -> "Mỗi ngày một dòng, kỷ niệm đầy ắp 💗"
        }
        card.addView(text(msg, 13f, p.sub).apply { setPadding(0, dp(12), 0, 0) })
        overview.addView(card)

        Thread {
            var n = 0
            for (d in content) n += Store.loadDay(d).events.sumOf { it.photos.size }
            val total = n
            App.main.post { (photoTile.getTag() as? TextView)?.text = "$total" }
        }.start()
    }

    private fun statTile(emoji: String, value: String, label: String): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        col.addView(text(emoji, 20f, p.text))
        val v = text(value, 20f, p.accent, 700).apply { setPadding(0, dp(2), 0, 0) }
        col.addView(v)
        col.addView(text(label, 11f, p.sub))
        col.tag = v
        return col
    }

    private fun currentStreak(content: Set<String>): Int {
        if (content.isEmpty()) return 0
        val c = Calendar.getInstance()
        fun key(cc: Calendar) = String.format("%04d-%02d-%02d", cc.get(Calendar.YEAR), cc.get(Calendar.MONTH) + 1, cc.get(Calendar.DAY_OF_MONTH))
        if (key(c) !in content) c.add(Calendar.DAY_OF_MONTH, -1)
        var n = 0
        while (key(c) in content) { n++; c.add(Calendar.DAY_OF_MONTH, -1) }
        return n
    }

    // ---------- util ----------
    /** From the calendar/list: if the day was already compiled, open the compiled view; else the editor. */
    private fun openDay(date: String) {
        if (Store.loadDay(date).compiled.isNotEmpty())
            startActivity(Intent(this, CompileActivity::class.java).putExtra("date", date))
        else openDayEdit(date)
    }

    private fun openDayEdit(date: String) = startActivity(Intent(this, DayActivity::class.java).putExtra("date", date))

    private fun prettyDate(date: String): String = try {
        "${date.substring(8, 10).toInt()}/${date.substring(5, 7).toInt()}/${date.substring(0, 4)}"
    } catch (_: Exception) { date }

    private fun todayStr(): String {
        val c = Calendar.getInstance()
        return String.format("%04d-%02d-%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    }
}
