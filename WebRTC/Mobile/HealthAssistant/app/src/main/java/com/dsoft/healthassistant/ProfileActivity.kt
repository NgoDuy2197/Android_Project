package com.dsoft.healthassistant

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/** Height / weight / gender / age / activity / goal — drives the calorie target the AI reviews against. */
class ProfileActivity : Activity() {
    private lateinit var p: Palette
    private lateinit var prof: Profile
    private lateinit var summary: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        p = palette()
        prof = Store.loadProfile(this)
        val col = page(p, "Hồ sơ sức khoẻ")

        col.addView(sectionHeader(p, "Cơ thể"))
        col.addView(group(p,
            numRow("Chiều cao (cm)", prof.heightCm.toString()) { prof.heightCm = it; },
            numRow("Cân nặng (kg)", prof.weightKg.toString()) { prof.weightKg = it; },
            numRow("Tuổi", prof.age.toString()) { prof.age = it.toInt(); },
        ))

        col.addView(sectionHeader(p, "Giới tính"))
        col.addView(seg(listOf("Nam", "Nữ"), if (prof.gender == "female") 1 else 0) {
            prof.gender = if (it == 1) "female" else "male"; refresh()
        })

        col.addView(sectionHeader(p, "Mức vận động"))
        col.addView(seg(Profile.ACTIVITY, prof.activity) { prof.activity = it; refresh() })

        col.addView(sectionHeader(p, "Mục tiêu"))
        col.addView(seg(Profile.GOALS.map { it.second }, Profile.GOALS.indexOfFirst { it.first == prof.goal }.coerceAtLeast(0)) {
            prof.goal = Profile.GOALS[it].first; refresh()
        })
        col.addView(group(p,
            numRow("Cân nặng mục tiêu (kg)", prof.targetWeightKg.toString()) { prof.targetWeightKg = it; },
            numRow("Tốc độ (kg/tuần)", prof.weeklyRateKg.toString()) { prof.weeklyRateKg = it; },
        ))

        col.addView(sectionHeader(p, "Yêu cầu riêng (tuỳ chọn)"))
        val notesField = inlineField(p, prof.notes, wide = true) { prof.notes = it }.apply {
            gravity = Gravity.START; setTextColor(p.text)
            hint = "VD: nhiều đạm, ít đường, ăn chay, tiểu đường, tập gym 3 buổi/tuần…"
            setHintTextColor(p.secondary)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        col.addView(card(p).apply { addView(notesField) })

        col.addView(sectionHeader(p, "Tính toán (tự động)"))
        summary = text("", 14.5f, p.text).apply { setPadding(dp(16), dp(14), dp(16), dp(14)); setLineSpacing(0f, 1.3f) }
        col.addView(card(p).apply { addView(summary) })
        refresh()

        col.addView(bigIconButton("Lưu hồ sơ", R.drawable.ic_check, p.green, p.teal) {
            Store.saveProfile(this, prof); toast("Đã lưu hồ sơ"); finish()
        }, vlp().apply { topMargin = dp(22) })
    }

    private fun numRow(title: String, value: String, onSet: (Double) -> Unit): LinearLayout {
        val field = inlineField(p, value, numeric = true) { s ->
            s.replace(",", ".").toDoubleOrNull()?.let { runCatching { onSet(it); refresh() } }
        }
        return row(p, title, field)
    }

    private fun seg(items: List<String>, selected: Int, onSelect: (Int) -> Unit) =
        Segmented(this, p, items, selected, onSelect).apply {
            layoutParams = vlp().apply { marginStart = dp(4); marginEnd = dp(4) }
        }

    private fun refresh() {
        summary.text = buildString {
            append("BMI: ${String.format("%.1f", prof.bmi())} — ${prof.bmiLabel()}\n")
            append("BMR (chuyển hoá cơ bản): ~${prof.bmr()} kcal/ngày\n")
            append("TDEE (tiêu hao cả ngày): ~${prof.tdee()} kcal/ngày\n")
            append("→ Calo mục tiêu: ~${prof.targetCalories()} kcal/ngày (${prof.goalLabel()})")
        }
    }
}
