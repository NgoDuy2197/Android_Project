package com.dsoft.vocab_widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView

/** Shown when a widget is placed / reconfigured: pick collection, lines, auto-change. */
class VocabWidgetConfigure : Activity() {
    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        widgetId = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        val collections = VocabData.collectionList(this)
        val currentCol = WidgetPrefs.collection(this, widgetId)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(22), dp(22), dp(16))
            background = GradientDrawable().apply {
                cornerRadius = dp(26).toFloat()
                setColor(Color.parseColor("#F7F6FF"))
            }
        }
        fun title(text: String, size: Float = 15f) = TextView(this).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            setTextColor(Color.parseColor("#1C1C1E"))
            setPadding(0, dp(12), 0, dp(4))
            paint.isFakeBoldText = true
        }
        root.addView(title("Cấu hình widget Từ Vựng", 19f))

        root.addView(title("Hiển thị từ bộ"))
        val colGroup = RadioGroup(this)
        if (collections.isEmpty()) {
            root.addView(TextView(this).apply { text = "Chưa có bộ từ – mở app để tạo." })
        }
        collections.forEachIndexed { i, (cid, name) ->
            colGroup.addView(RadioButton(this).apply {
                id = 1000 + i
                text = name
                tag = cid
                isChecked = cid == currentCol
            })
        }
        root.addView(colGroup)

        root.addView(title("Số dòng"))
        val lineGroup = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val curLines = WidgetPrefs.lines(this, widgetId)
        for (n in 1..3) {
            lineGroup.addView(RadioButton(this).apply {
                id = 2000 + n
                text = "$n dòng   "
                isChecked = n == curLines
            })
        }
        root.addView(lineGroup)

        root.addView(title("Tự động đổi từ"))
        val autoGroup = RadioGroup(this)
        val curAuto = WidgetPrefs.autoMinutes(this, widgetId)
        listOf(0 to "Tắt (bấm nút Đổi từ)", 30 to "Mỗi 30 phút", 60 to "Mỗi 1 giờ", 180 to "Mỗi 3 giờ", 1440 to "Mỗi ngày")
            .forEach { (m, label) ->
                autoGroup.addView(RadioButton(this).apply {
                    id = 3000 + m
                    text = label
                    tag = m
                    isChecked = m == curAuto
                })
            }
        root.addView(autoGroup)

        root.addView(Button(this).apply {
            text = "Xong"
            setTextColor(Color.WHITE)
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.parseColor("#6C5CE7"), Color.parseColor("#00B4D8")),
            ).apply { cornerRadius = dp(16).toFloat() }
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(50)).apply { topMargin = dp(16) }
            setOnClickListener {
                val col = findViewById<RadioButton>(colGroup.checkedRadioButtonId)?.tag as? String
                    ?: collections.firstOrNull()?.first ?: ""
                val lines = (lineGroup.checkedRadioButtonId - 2000).coerceIn(1, 3)
                val auto = findViewById<RadioButton>(autoGroup.checkedRadioButtonId)?.tag as? Int ?: 0
                WidgetPrefs.setConfig(this@VocabWidgetConfigure, widgetId, col, lines, auto)
                VocabWidget.render(this@VocabWidgetConfigure, AppWidgetManager.getInstance(this@VocabWidgetConfigure), widgetId)
                setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
                finish()
            }
        })

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#66000000"))
            addView(LinearLayout(this@VocabWidgetConfigure).apply {
                gravity = Gravity.CENTER
                setPadding(dp(18), dp(60), dp(18), dp(40))
                addView(root, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            })
        })
    }
}
