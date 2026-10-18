package com.dsoft.toolbox

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

class DimActivity : Activity() {
    private lateinit var p: Palette
    private lateinit var prefs: Prefs
    private lateinit var onSwitch: Switch
    private lateinit var value: TextView
    private lateinit var capNote: TextView
    private lateinit var brightSwitch: Switch
    private lateinit var overlayStatus: TextView
    private lateinit var accStatus: TextView
    private lateinit var writeStatus: TextView
    private var syncing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        p = palette()
        prefs = Prefs(this)
        val col = page(p, "Làm tối màn hình")

        onSwitch = iosSwitch(p, DimService.active) { on -> if (!syncing) setDim(on) }
        col.addView(group(p, row(p, "Bật làm tối", onSwitch)))

        // ---- level slider ----
        col.addView(sectionHeader(p, "Độ tối"))
        value = text("", 17f, p.text, 700)
        capNote = text("", 13f, p.orange).apply { setPadding(0, dp(8), 0, 0) }
        val seek = SeekBar(this).apply {
            max = Prefs.MAX_DIM
            progress = prefs.dimLevel
            progressTintList = ColorStateList.valueOf(p.indigo)
            thumbTintList = ColorStateList.valueOf(p.indigo)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, v: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    prefs.dimLevel = v
                    DimService.refresh(this@DimActivity)
                    refresh()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        col.addView(card(p).apply {
            setPadding(dp(16), dp(14), dp(16), dp(14))
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(text("Sáng", 13f, p.secondary))
                addView(value.apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(text("Tối om", 13f, p.secondary))
            })
            addView(seek, vlp().apply { topMargin = dp(10) })
            addView(capNote)
        })
        col.addView(footnote(p, "Kéo thanh trượt để chỉnh ngay khi đang bật. Trên thông báo cũng có nút Sáng hơn / Tối hơn / Tắt."))

        brightSwitch = iosSwitch(p, prefs.dimLowBrightness) { on -> if (!syncing) setLowBrightness(on) }
        col.addView(group(p, row(p, "Hạ độ sáng hệ thống xuống thấp nhất", brightSwitch)))
        col.addView(footnote(p, "Kết hợp với lớp phủ cho tối hơn nữa. Tắt làm tối sẽ trả lại độ sáng cũ."))

        // ---- permissions ----
        col.addView(sectionHeader(p, "Quyền"))
        overlayStatus = text("", 15f, p.secondary)
        accStatus = text("", 15f, p.secondary)
        writeStatus = text("", 15f, p.secondary)
        col.addView(group(p,
            row(p, "Hiển thị trên ứng dụng khác", overlayStatus) { openOverlaySettings() },
            row(p, "Chế độ tối om (Trợ năng)", accStatus) { openAccessibility() },
            row(p, "Sửa cài đặt hệ thống", writeStatus) { openWriteSettings() },
        ))
        col.addView(footnote(p,
            "Từ Android 12, lớp phủ thường chỉ được tối tối đa ~79% (để không chặn chạm). " +
                "Muốn tối hơn: bật \"Toolbox – Làm tối\" trong Cài đặt › Trợ năng › Ứng dụng đã tải xuống.\n" +
                "Nếu mục đó bị mờ (\"Cài đặt bị hạn chế\"): vào Cài đặt › Ứng dụng › Toolbox › ⋮ › Cho phép cài đặt bị hạn chế, rồi bật lại.\n" +
                "Có thể thêm ô \"Làm tối\" vào bảng Cài đặt nhanh (kéo thanh thông báo › sửa ô)."))
    }

    override fun onResume() {
        super.onResume()
        DimService.listener = { runOnUiThread { refresh() } }
        refresh()
    }

    override fun onPause() {
        super.onPause()
        DimService.listener = null
    }

    private fun refresh() {
        syncing = true
        onSwitch.isChecked = DimService.active
        brightSwitch.isChecked = prefs.dimLowBrightness
        syncing = false
        val level = prefs.dimLevel
        val eff = DimOverlay.effective(level)
        value.text = "$level%"
        capNote.text = if (eff < level) "Đang giới hạn $eff% — bật Chế độ tối om (Trợ năng) để tối hơn" else ""
        capNote.visibility = if (capNote.text.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
        status(overlayStatus, DimService.canOverlay(this))
        status(accStatus, DimAccessibility.instance != null, "Đang bật", "Tắt ›")
        status(writeStatus, Brightness.canWrite(this))
    }

    private fun status(v: TextView, ok: Boolean, yes: String = "Đã cấp", no: String = "Chưa cấp ›") {
        v.text = if (ok) yes else no
        v.setTextColor(if (ok) p.secondary else p.orange)
    }

    private fun setDim(on: Boolean) {
        if (!on) {
            DimService.stop(this)
            return
        }
        if (!DimService.canOverlay(this) && DimAccessibility.instance == null) {
            toast("Cần cho phép \"Hiển thị trên ứng dụng khác\"")
            openOverlaySettings()
            refresh()
            return
        }
        DimService.start(this)
    }

    private fun setLowBrightness(on: Boolean) {
        prefs.dimLowBrightness = on
        if (on && !Brightness.canWrite(this)) {
            toast("Cần cho phép \"Sửa cài đặt hệ thống\"")
            openWriteSettings()
        }
        if (DimService.active) {
            if (on) Brightness.lower(this) else Brightness.restore(this)
        }
    }

    private fun openOverlaySettings() {
        if (Build.VERSION.SDK_INT < 23) return
        open(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
    }

    private fun openWriteSettings() {
        if (Build.VERSION.SDK_INT < 23) return
        open(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName")))
    }

    private fun openAccessibility() = open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))

    private fun open(i: Intent) {
        try {
            startActivity(i)
        } catch (_: Exception) {
            toast("Không mở được trang cài đặt")
        }
    }
}
