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
        val col = page(p, tr("Hồ sơ sức khoẻ", "Health profile"))

        col.addView(sectionHeader(p, tr("Cơ thể", "Body")))
        col.addView(group(p,
            numRow(tr("Chiều cao (cm)", "Height (cm)"), prof.heightCm.toString()) { prof.heightCm = it; },
            numRow(tr("Cân nặng (kg)", "Weight (kg)"), prof.weightKg.toString()) { prof.weightKg = it; },
            numRow(tr("Tuổi", "Age"), prof.age.toString()) { prof.age = it.toInt(); },
        ))

        col.addView(sectionHeader(p, tr("Giới tính", "Gender")))
        col.addView(seg(listOf(tr("Nam", "Male"), tr("Nữ", "Female")), if (prof.gender == "female") 1 else 0) {
            prof.gender = if (it == 1) "female" else "male"; refresh()
        })

        col.addView(sectionHeader(p, tr("Mức vận động", "Activity level")))
        col.addView(seg(Profile.activityLabels(), prof.activity) { prof.activity = it; refresh() })

        col.addView(sectionHeader(p, tr("Mục tiêu", "Goal")))
        col.addView(seg(Profile.goalLabels(), Profile.GOALS.indexOfFirst { it.first == prof.goal }.coerceAtLeast(0)) {
            prof.goal = Profile.GOALS[it].first; refresh()
        })
        col.addView(group(p,
            numRow(tr("Cân nặng mục tiêu (kg)", "Target weight (kg)"), prof.targetWeightKg.toString()) { prof.targetWeightKg = it; },
            numRow(tr("Tốc độ (kg/tuần)", "Rate (kg/week)"), prof.weeklyRateKg.toString()) { prof.weeklyRateKg = it; },
        ))

        col.addView(sectionHeader(p, tr("Thông tin thêm (tuỳ chọn)", "Additional info (optional)")))
        val notesField = inlineField(p, prof.notes, wide = true) { prof.notes = it }.apply {
            gravity = Gravity.START; setTextColor(p.text)
            hint = tr("VD: nhiều đạm, ít đường, ăn chay, tiểu đường, tập gym 3 buổi/tuần…",
                "e.g. high protein, low sugar, vegetarian, diabetic, gym 3x/week…")
            setHintTextColor(p.secondary)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        col.addView(card(p).apply { addView(notesField) })

        col.addView(sectionHeader(p, tr("Tính toán (tự động)", "Calculated (automatic)")))
        summary = text("", 14.5f, p.text).apply { setPadding(dp(16), dp(14), dp(16), dp(14)); setLineSpacing(0f, 1.3f) }
        col.addView(card(p).apply { addView(summary) })
        refresh()

        col.addView(bigIconButton(tr("Lưu hồ sơ", "Save profile"), R.drawable.ic_check, p.accent, p.accent2) {
            prof.notes = notesField.text.toString().trim() // commit even if the field never lost focus
            Store.saveProfile(this, prof); toast(tr("Đã lưu hồ sơ", "Profile saved")); finish()
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
            append(tr("BMR (chuyển hoá cơ bản): ~${prof.bmr()} kcal/ngày", "BMR (basal metabolic rate): ~${prof.bmr()} kcal/day")).append("\n")
            append(tr("TDEE (tiêu hao cả ngày): ~${prof.tdee()} kcal/ngày", "TDEE (daily burn): ~${prof.tdee()} kcal/day")).append("\n")
            append(tr("→ Calo mục tiêu: ~${prof.targetCalories()} kcal/ngày (${prof.goalLabel()})", "→ Target calories: ~${prof.targetCalories()} kcal/day (${prof.goalLabel()})"))
        }
    }
}
