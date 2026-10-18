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
import java.util.Locale

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
        root.addView(sectionHeader(p, "Nhận dạng (Google)"))
        val langs = listOf("auto") + Prefs.LANGS.keys.toList()
        val langLabels = listOf("Tự động", "Việt", "Anh", "Nhật", "Trung")
        root.addView(group(p,
            row(p, "Ngôn ngữ", segmented(langLabels, langs.indexOf(prefs.language).coerceAtLeast(0)) {
                prefs.language = langs[it]
                apply()
            }),
        ))
        root.addView(footnote(p, "Dùng bộ nhận dạng giọng nói Google của máy, cần mạng. " +
            "\"Tự động\" nhờ engine tự phát hiện ngôn ngữ (phụ thuộc máy và phiên bản Android; không phải máy nào cũng hỗ trợ)."))

        // translate
        root.addView(sectionHeader(p, "Dịch"))
        val tgt = Prefs.LANGS.keys.toList()
        root.addView(group(p,
            row(p, "Dịch sang", segmented(listOf("Việt", "Anh", "Nhật", "Trung"), tgt.indexOf(prefs.targetLang).coerceAtLeast(0)) {
                prefs.targetLang = tgt[it]
            }),
        ))
        root.addView(footnote(p, "Ngôn ngữ mục tiêu cho nút \"Dịch\" cạnh mỗi đoạn. Dịch bằng Google Dịch qua mạng."))

        // line breaks
        root.addView(sectionHeader(p, "Ngắt dòng (mặc định)"))
        root.addView(group(p,
            row(p, "Thời gian chờ nghe (giây)", inlineField(p, fmtSec(prefs.waitMs), numeric = true) { v ->
                val sec = v.replace(',', '.').toDoubleOrNull()
                if (sec == null) toast("Nhập số giây, vd 1 hoặc 1.5")
                else prefs.waitMs = (sec * 1000).toLong()
            }),
            row(p, "Mức yên lặng (%)", inlineField(p, (prefs.silenceLevel * 100).toInt().toString(), numeric = true) { v ->
                val pct = v.replace(',', '.').toFloatOrNull()
                if (pct == null) toast("Nhập phần trăm 2–90, vd 25")
                else prefs.silenceLevel = pct / 100f
            }),
        ))
        root.addView(footnote(p, "Khi âm thanh nhỏ hơn mức yên lặng liên tục bằng thời gian chờ nghe, câu đang nói được " +
            "chốt thành một dòng (chỉ một lần, im lặng lâu cũng không thêm dòng trống). " +
            "Đây là giá trị mặc định cho phiên mới; trong phiên có thể chỉnh trực tiếp."))

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
        ))
        root.addView(footnote(p, "Mỗi phiên: mỗi lần bấm ghi là một file, đổi tên được trong Thư mục."))

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

    private fun fmtSec(ms: Long) = if (ms % 1000 == 0L) (ms / 1000).toString() else "%.1f".format(Locale.US, ms / 1000.0)

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
