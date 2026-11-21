package com.dsoft.screengrab

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import com.dsoft.screengrab.ui.*

class SettingsActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Prefs(this)
        val col = page("Cài đặt nâng cao")

        col.addView(sectionHeader("VIDEO"))
        col.numberField("Khung hình/giây (FPS)", prefs.fps) { prefs.fps = it.coerceIn(5, 60) }
        col.numberField("Bitrate (Mbps)", prefs.bitrateMbps) { prefs.bitrateMbps = it.coerceIn(1, 80) }

        col.addView(tv("FPS/bitrate cao → video mượt & nét hơn nhưng file lớn và máy nóng hơn. 30fps / 12Mbps là cân bằng tốt.",
            12.5f, Theme.secondary).apply { setPadding(dp(4), dp(14), dp(4), 0) })
    }

    private fun LinearLayout.numberField(label: String, value: Int, onChange: (Int) -> Unit) {
        val ctx = context
        addCard {
            addView(ctx.tv(label, 16f, Theme.text, bold = true))
            val et = EditText(ctx).apply {
                setText(value.toString())
                inputType = InputType.TYPE_CLASS_NUMBER
                setTextColor(Theme.text); setHintTextColor(Theme.secondary)
                background = ctx.rounded(Theme.bg, ctx.dp(8))
                setPadding(ctx.dp(12), ctx.dp(10), ctx.dp(12), ctx.dp(10))
            }
            et.setOnFocusChangeListener { _, has -> if (!has) et.text.toString().toIntOrNull()?.let { onChange(it) } }
            addView(et, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ctx.dp(8) })
        }
    }
}
