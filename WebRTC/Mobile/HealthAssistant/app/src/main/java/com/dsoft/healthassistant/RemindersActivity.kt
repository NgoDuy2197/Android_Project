package com.dsoft.healthassistant

import android.Manifest
import android.app.Activity
import android.app.TimePickerDialog
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.LinearLayout

/** Configure local reminders: a repeating water nudge and daily meal-logging nudges. */
class RemindersActivity : Activity() {
    private lateinit var p: Palette
    private lateinit var content: LinearLayout
    private val prefs by lazy { Prefs(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        p = palette()
        val col = page(p, tr("Nhắc nhở", "Reminders"))
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(content)
        render()
    }

    private fun render() {
        content.removeAllViews()

        content.addView(sectionHeader(p, tr("Nhắc uống nước", "Water reminder")))
        val waterRows = ArrayList<android.view.View>()
        waterRows.add(row(p, tr("Bật nhắc uống nước", "Enable water reminder"), iosSwitch(p, prefs.waterRemind) { on ->
            prefs.waterRemind = on
            if (on) ensurePermission()
            apply(); content.post { render() }
        }))
        if (prefs.waterRemind) {
            waterRows.add(row(p, tr("Nhắc mỗi (giờ)", "Every (hours)"), inlineField(p, prefs.waterRemindHours.toString(), numeric = true) { v ->
                prefs.waterRemindHours = (v.toIntOrNull() ?: 2).coerceIn(1, 12)
                apply()
            }))
        }
        content.addView(group(p, *waterRows.toTypedArray()))

        content.addView(sectionHeader(p, tr("Nhắc ghi bữa ăn", "Meal reminder")))
        val mealRows = ArrayList<android.view.View>()
        mealRows.add(row(p, tr("Bật nhắc ghi bữa", "Enable meal reminder"), iosSwitch(p, prefs.mealRemind) { on ->
            prefs.mealRemind = on
            if (on) ensurePermission()
            apply(); content.post { render() }
        }))
        if (prefs.mealRemind) {
            val labels = listOf(tr("Sáng", "Morning"), tr("Trưa", "Noon"), tr("Tối", "Evening"))
            val times = currentTimes()
            labels.forEachIndexed { i, label ->
                val t = times.getOrElse(i) { "08:00" }
                mealRows.add(row(p, label, text(t, 16f, p.secondary)) { pickTime(i, t) })
            }
        }
        content.addView(group(p, *mealRows.toTypedArray()))

        content.addView(footnote(p, tr(
            "Nhắc nhở là thông báo cục bộ trên máy (không cần mạng). Trên Android 13+ cần cấp quyền thông báo. Thời gian có thể xê dịch ít phút để tiết kiệm pin.",
            "Reminders are local notifications (no internet). Android 13+ needs notification permission. Times may drift a few minutes to save battery.",
        )))
    }

    private fun currentTimes(): List<String> =
        prefs.mealTimes.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            .let { if (it.size >= 3) it else listOf("08:00", "12:00", "19:00") }

    private fun pickTime(index: Int, current: String) {
        val parts = current.split(":")
        val h = parts.getOrNull(0)?.toIntOrNull() ?: 8
        val m = parts.getOrNull(1)?.toIntOrNull() ?: 0
        TimePickerDialog(this, { _, hh, mm ->
            val times = currentTimes().toMutableList()
            while (times.size <= index) times.add("08:00")
            times[index] = String.format("%02d:%02d", hh, mm)
            prefs.mealTimes = times.joinToString(",")
            apply(); render()
        }, h, m, true).show()
    }

    private fun apply() = Reminders.reschedule(this)

    private fun ensurePermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            runCatching { requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1) }
        }
    }
}
