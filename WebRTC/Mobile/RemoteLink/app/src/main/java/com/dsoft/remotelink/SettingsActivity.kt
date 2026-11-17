package com.dsoft.remotelink

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import com.dsoft.remotelink.ui.*

class SettingsActivity : Activity() {
    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        build()
    }

    private fun build() {
        val col = page("Cài đặt")

        col.addView(sectionHeader("Cơ chế capture màn hình"))
        col.addView(segmented(listOf("MediaProjection" to "projection", "Root (su)" to "root"), prefs.captureMode) {
            prefs.captureMode = it; build()
        })
        col.addView(tv(
            if (prefs.captureMode == "root") "Root: không hộp thoại, cần máy đã root."
            else "MediaProjection: chạy mọi máy; hỏi đồng ý 1 lần mỗi phiên.",
            12.5f, Theme.secondary).apply { setPadding(dp(4), dp(6), dp(4), 0) })

        col.addView(sectionHeader("Cơ chế điều khiển (input)"))
        col.addView(segmented(listOf("Accessibility" to "accessibility", "Root (su)" to "root"), prefs.inputMode) {
            prefs.inputMode = it; build()
        })

        col.addView(sectionHeader("Giao thức bật"))
        col.addView(switchRow("VNC server (RFB) — mọi VNC viewer kết nối được", prefs.enableVnc) { prefs.enableVnc = it })
        col.addView(switchRow("Stream (RustDesk-like) — dùng app Desktop", prefs.enableStream) { prefs.enableStream = it })

        col.addView(sectionHeader("Cổng & mật khẩu"))
        col.addView(numberField("Cổng VNC", prefs.vncPort) { prefs.vncPort = it })
        col.addView(numberField("Cổng Stream", prefs.streamPort) { prefs.streamPort = it })
        col.addView(textField("Mật khẩu (để trống = không cần)", prefs.password, true) { prefs.password = it })

        col.addView(sectionHeader("Chất lượng"))
        col.addView(numberField("Stream FPS", prefs.streamFps) { prefs.streamFps = it.coerceIn(5, 60) })
        col.addView(numberField("Stream bitrate (Mbps)", prefs.streamBitrateMbps) { prefs.streamBitrateMbps = it.coerceIn(1, 40) })
        col.addView(numberField("VNC FPS", prefs.vncFps) { prefs.vncFps = it.coerceIn(2, 30) })
        col.addView(numberField("Thu nhỏ capture (1=gốc, 2=1/2)", prefs.scaleDiv) { prefs.scaleDiv = it.coerceIn(1, 4) })

        col.addView(tv("Đổi cài đặt sẽ áp dụng ở lần Bắt đầu chia sẻ tiếp theo.", 12.5f, Theme.orange)
            .apply { setPadding(dp(4), dp(18), dp(4), 0) })

        col.addView(filledButton("Xong", Theme.accent) { finish() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) })
    }

    private fun segmented(options: List<Pair<String, String>>, current: String, onPick: (String) -> Unit): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            options.forEachIndexed { i, (label, value) ->
                val sel = value == current
                addView(Button(this@SettingsActivity).apply {
                    text = label; isAllCaps = false
                    setTextColor(if (sel) android.graphics.Color.WHITE else Theme.text)
                    background = rounded(if (sel) Theme.accent else Theme.card, dp(12))
                    setOnClickListener { onPick(value) }
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (i > 0) marginStart = dp(8)
                })
            }
        }

    private fun switchRow(label: String, value: Boolean, onChange: (Boolean) -> Unit): LinearLayout {
        val row = card().apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(tv(label, 15f, Theme.text), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(Switch(this@SettingsActivity).apply {
                isChecked = value
                setOnCheckedChangeListener { _, c -> onChange(c) }
            })
        }
        return wrap(row)
    }

    private fun numberField(label: String, value: Int, onChange: (Int) -> Unit): LinearLayout =
        fieldBase(label, value.toString(), InputType.TYPE_CLASS_NUMBER) { it.toIntOrNull()?.let(onChange) }

    private fun textField(label: String, value: String, password: Boolean, onChange: (String) -> Unit): LinearLayout =
        fieldBase(label, value, if (password) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT) { onChange(it) }

    private fun fieldBase(label: String, value: String, inputType: Int, onChange: (String) -> Unit): LinearLayout {
        val c = card().apply {
            addView(tv(label, 12.5f, Theme.secondary))
            addView(EditText(this@SettingsActivity).apply {
                setText(value); setTextColor(Theme.text); setHintTextColor(Theme.secondary)
                this.inputType = inputType
                background = null
                addTextChangedListener(object : android.text.TextWatcher {
                    override fun afterTextChanged(s: android.text.Editable?) { onChange(s?.toString()?.trim() ?: "") }
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, cc: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, cc: Int) {}
                })
            })
        }
        return wrap(c)
    }

    private fun wrap(v: LinearLayout): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
    }
}
