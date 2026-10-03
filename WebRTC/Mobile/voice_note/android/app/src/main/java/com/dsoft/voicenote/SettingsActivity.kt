package com.dsoft.voicenote

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat

/** Grouped iOS-style settings; every change is saved and applied immediately. */
class SettingsActivity : Activity() {
    private lateinit var p: Palette
    private lateinit var prefs: Prefs
    private lateinit var batteryValue: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        p = palette()
        prefs = Prefs(this)
        setupSystemBars(p)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(32))
        }
        root.addView(backLink("VoiceNote"))
        root.addView(text("Cài đặt", 34f, p.text, 700).apply { setPadding(dp(4), dp(6), 0, dp(4)) })

        // recognition
        root.addView(sectionHeader(p, "Nhận dạng"))
        root.addView(group(p,
            row(p, "Engine", segmented(listOf("Google", "Vosk"), if (prefs.engine == "vosk") 1 else 0) {
                prefs.engine = if (it == 1) "vosk" else "google"
                apply()
            }),
            row(p, "Ngôn ngữ", segmented(listOf("Tiếng Việt", "English"), if (prefs.language == "en") 1 else 0) {
                prefs.language = if (it == 1) "en" else "vi"
                apply()
            }),
        ))
        root.addView(footnote(p, "Google: nhanh và chuẩn, cần mạng (giống voice_ai). Vosk: offline, tiếng Việt kém hơn."))

        // background
        root.addView(sectionHeader(p, "Chạy nền"))
        batteryValue = text("", 16f, p.secondary)
        root.addView(group(p,
            row(p, "Tự chạy khi bật máy", iosSwitch(p, prefs.autostart) { prefs.autostart = it }),
            row(p, "Bỏ tối ưu pin", LinearLayout(this).apply {
                addView(batteryValue)
                addView(chevron(p))
            }) { askBatteryExemption() },
        ))
        root.addView(footnote(p, "Bỏ tối ưu pin giúp hệ thống không tắt app khi đang ghi lâu."))

        // files
        root.addView(sectionHeader(p, "Lưu file"))
        root.addView(group(p,
            row(p, "Chia file", segmented(listOf("Mỗi phiên", "Mỗi ngày"), if (prefs.fileMode == "day") 1 else 0) {
                prefs.fileMode = if (it == 1) "day" else "session"
                apply()
            }),
            row(p, "Định dạng giờ", inlineField(p, prefs.timeFormat) { v ->
                if (runCatching { SimpleDateFormat(v) }.isSuccess && v.isNotBlank()) {
                    prefs.timeFormat = v
                    apply()
                } else toast("Định dạng giờ không hợp lệ (vd HH:mm:ss)")
            }),
            row(p, "Gộp câu nếu nghỉ dưới (ms)", inlineField(p, prefs.pauseMs.toString(), numeric = true) { v ->
                prefs.pauseMs = v.toLongOrNull() ?: Prefs.DEFAULT_PAUSE_MS
                apply()
            }),
        ))
        root.addView(footnote(p, "Mỗi phiên: mỗi lần bấm ghi là một file, đổi tên được trong Thư mục."))

        // vosk
        root.addView(sectionHeader(p, "Vosk (offline)"))
        root.addView(group(p,
            row(p, "Model URL", inlineField(p, prefs.modelUrl(), wide = true) { v ->
                prefs.setModelUrl(prefs.language, v)
                apply()
            }),
            row(p, "Lọc nhiễu (0–1)", inlineField(p, prefs.minConfidence.toString(), numeric = true) { v ->
                prefs.minConfidence = v.toFloatOrNull() ?: Prefs.DEFAULT_MIN_CONF
                apply()
            }),
        ))
        root.addView(footnote(p, "Model URL áp dụng cho ngôn ngữ đang chọn; tự tải một lần."))

        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        val ignoring = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        batteryValue.text = if (ignoring) "Đã bật" else "Chưa"
    }

    private fun segmented(items: List<String>, selected: Int, onSelect: (Int) -> Unit) =
        Segmented(this, p, items, selected, onSelect).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

    /** Restart the running recorder with the new settings. */
    private fun apply() = RecorderService.reload(this)

    private fun askBatteryExemption() {
        if (getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)) {
            return toast("Đã bỏ tối ưu pin")
        }
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
