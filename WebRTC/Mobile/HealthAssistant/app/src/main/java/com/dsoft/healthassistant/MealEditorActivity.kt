package com.dsoft.healthassistant

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** Add or edit one meal: type, time, the list of foods (free text), an optional note. */
class MealEditorActivity : Activity() {
    private lateinit var p: Palette
    private lateinit var meal: Meal
    private var isNew = false
    private var selType = 0
    private lateinit var itemsField: EditText
    private lateinit var timeField: EditText
    private lateinit var noteField: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        p = palette()
        val date = intent.getStringExtra("date") ?: Store.today()
        val id = if (intent.hasExtra("mealId")) intent.getLongExtra("mealId", 0) else 0
        meal = if (id != 0L) Store.get(this, id) ?: Meal(date = date) else Meal(date = date)
        isNew = id == 0L
        if (isNew) {
            val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            meal.type = when (hour) { in 5..10 -> 0; in 11..14 -> 1; in 17..21 -> 2; else -> 3 }
            meal.time = SimpleDateFormat("HH:mm", Locale.US).format(Calendar.getInstance().time)
        }
        selType = meal.type

        val col = page(p, if (isNew) tr("Thêm bữa ăn", "Add meal") else tr("Sửa bữa ăn", "Edit meal"))

        // type
        col.addView(sectionHeader(p, tr("Loại bữa", "Meal type")))
        col.addView(Segmented(this, p, Meal.mealTypes(), meal.type) { selType = it }, vlp().apply { marginStart = dp(4); marginEnd = dp(4) })

        // time
        col.addView(sectionHeader(p, tr("Giờ ăn", "Time")))
        timeField = inlineField(p, meal.time) {}.apply {
            gravity = Gravity.START; setTextColor(p.text)
            layoutParams = LinearLayout.LayoutParams(dp(120), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        col.addView(group(p, row(p, "HH:mm", timeField)))

        // items
        col.addView(sectionHeader(p, tr("Món ăn (mỗi dòng một món)", "Foods (one per line)")))
        itemsField = EditText(this).apply {
            setText(meal.items)
            hint = tr("VD:\n2 chén cơm\n100g thịt kho\n1 tô canh rau\n1 ly trà sữa",
                "e.g.:\n2 bowls of rice\n100g braised pork\n1 bowl of soup\n1 milk tea")
            background = null
            setTextColor(p.text); setHintTextColor(p.secondary)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            gravity = Gravity.TOP or Gravity.START
            minLines = 4
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        col.addView(card(p).apply { addView(itemsField) })

        // note
        col.addView(sectionHeader(p, tr("Ghi chú (tuỳ chọn)", "Note (optional)")))
        noteField = inlineField(p, meal.note, wide = true) {}.apply {
            gravity = Gravity.START; setTextColor(p.text)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        col.addView(card(p).apply { addView(noteField) })

        // actions
        col.addView(bigIconButton(tr("Xem xét bữa này", "Review this meal"), R.drawable.ic_search, p.indigo, p.blue) { saveThen { reviewThisMeal() } },
            vlp().apply { topMargin = dp(22) })
        col.addView(bigIconButton(tr("Lưu", "Save"), R.drawable.ic_check, p.accent, p.accent2) { saveThen { finish() } },
            vlp().apply { topMargin = dp(10) })
        if (!isNew)
            col.addView(bigIconButton(tr("Xoá bữa này", "Delete this meal"), R.drawable.ic_delete, p.red, p.pink) { Store.delete(this, meal.id); finish() },
                vlp().apply { topMargin = dp(10) })
    }

    private fun collect() {
        val newItems = itemsField.text.toString().trim()
        if (newItems != meal.items) { meal.reviewJson = ""; meal.reviewAt = 0 } // edits invalidate the cached review
        meal.items = newItems
        meal.type = selType
        meal.time = timeField.text.toString().trim()
        meal.note = noteField.text.toString().trim()
    }

    private fun saveThen(after: () -> Unit) {
        collect()
        if (meal.items.isBlank()) { toast(tr("Hãy nhập ít nhất một món ăn.", "Enter at least one food.")); return }
        Store.upsert(this, meal)
        after()
    }

    private fun reviewThisMeal() {
        startActivity(Intent(this, ReviewActivity::class.java).putExtra("mealId", meal.id))
    }
}
