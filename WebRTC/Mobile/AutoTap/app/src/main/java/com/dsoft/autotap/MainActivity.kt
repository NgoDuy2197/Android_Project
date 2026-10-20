package com.dsoft.autotap

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView

/** Home: setup/status card + list of scenarios (iOS Settings style). */
class MainActivity : Activity() {
    private lateinit var p: Palette
    private lateinit var store: Store
    private lateinit var content: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        p = palette()
        store = Store(this)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        build()
    }

    override fun onResume() {
        super.onResume()
        AutoClickService.listener = { runOnUiThread { build() } }
        CaptureService.listener = { runOnUiThread { build() } }
        ApiService.listener = { runOnUiThread { build() } }
        build()
    }

    override fun onPause() {
        super.onPause()
        AutoClickService.listener = null
        CaptureService.listener = null
        ApiService.listener = null
    }

    private fun build() {
        content = page(p, "AutoTap", back = false)

        // ---- setup / status ----
        content.addView(sectionHeader(p, "Thiết lập"))
        content.addView(group(p,
            statusRow("Dịch vụ Trợ năng (bắt buộc)",
                if (AutoClickService.isEnabled()) "Đã bật" else "Chưa bật — chạm để mở Cài đặt",
                AutoClickService.isEnabled()) { Access.openAccessibilitySettings(this) },
            statusRow("Đọc màn hình OCR (cho \"Chạm theo chữ\")",
                if (CaptureService.isEnabled()) "Đang bật" else "Tắt — chạm để cấp quyền ghi màn hình",
                CaptureService.isEnabled()) { toggleCapture() },
        ))

        // ---- API server ----
        content.addView(sectionHeader(p, "API server"))
        val apiCard = card(p)
        apiCard.addView(row(p, "Bật API server",
            iosSwitch(p, ApiService.running) { on -> if (on) ApiService.start(this) else ApiService.stop(this) }))
        if (ApiService.running) {
            apiCard.addView(separator(p))
            val url = Net.addresses().firstOrNull()?.let { "http://${it.second}:${ApiService.port}" } ?: "Chưa có Wi-Fi/hotspot"
            apiCard.addView(row(p, "Địa chỉ", text(url, 15f, p.blue)) { copyText(url) })
        }
        apiCard.addView(separator(p))
        apiCard.addView(row(p, "Cấu hình API (cổng, mật khẩu)", chevron(p)) {
            startActivity(Intent(this, SettingsActivity::class.java))
        })
        content.addView(apiCard)
        content.addView(footnote(p, "Gọi GET http://IP:cổng/run?name=TenKichBan để chạy từ máy khác. " +
            "Tham số thêm sẽ thay vào {{ten}} trong kịch bản."))

        // ---- scenarios ----
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(sectionHeader(p, "Kịch bản"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(circleButton(p, R.drawable.ic_add, p.blue, 34) { createScenario() })
        }
        content.addView(header)

        val list = store.load()
        if (list.isEmpty()) {
            content.addView(group(p, row(p, "Chưa có kịch bản. Chạm + để tạo.", null, null)))
        } else {
            val group = card(p)
            list.forEachIndexed { i, s ->
                if (i > 0) group.addView(separator(p))
                group.addView(scenarioRow(s))
            }
            content.addView(group)
        }

        content.addView(bigButton("＋ Tạo kịch bản mới", p.blue, p.indigo) { createScenario() },
            vlp().apply { topMargin = dp(18) })
        if (!Access.canOverlay(this)) {
            content.addView(footnote(p, "Để Ghi thao tác và Chọn điểm trên màn hình, hãy bật quyền \"Hiển thị trên ứng dụng khác\" trong Cài đặt."))
        }
    }

    private fun statusRow(title: String, sub: String, on: Boolean, onClick: () -> Unit): View {
        val dot = TextView(this).apply {
            text = "●"
            setTextColor(if (on) p.green else p.orange)
            textSize = 14f
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(title, 16f, p.text, 500))
            addView(text(sub, 13f, p.secondary).apply { setPadding(0, dp(3), 0, 0) })
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(56)
            setPadding(dp(16), dp(8), dp(12), dp(8))
            addView(dot, LinearLayout.LayoutParams(dp(20), ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(chevron(p))
            background = pressable(null, p.ripple)
            setOnClickListener { onClick() }
        }
    }

    private fun scenarioRow(s: Scenario): View {
        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(s.name, 17f, p.text, 500))
            addView(text("${s.steps.size} bước · lặp ${s.repeatCount}×", 13f, p.secondary).apply { setPadding(0, dp(3), 0, 0) })
            background = pressable(null, p.ripple)
            setOnClickListener { openEditor(s.id) }
        }
        val run = circleButton(p, R.drawable.ic_play, p.green, 38) { testRun(s) }
        lateinit var more: View
        more = circleButton(p, R.drawable.ic_more, p.secondary, 38) { showMenu(more, s) }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(60)
            setPadding(dp(14), dp(6), dp(8), dp(6))
            addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(run)
            addView(more)
        }
    }

    private fun showMenu(anchor: View, s: Scenario) {
        val m = PopupMenu(this, anchor)
        m.menu.add("Sửa")
        m.menu.add("Ghi thao tác vào kịch bản")
        m.menu.add("Đổi tên")
        m.menu.add("Nhân bản")
        m.menu.add("Copy URL API")
        m.menu.add("Xoá")
        m.setOnMenuItemClickListener { item ->
            when (item.title) {
                "Sửa" -> openEditor(s.id)
                "Ghi thao tác vào kịch bản" -> startRecord(s)
                "Đổi tên" -> renameScenario(s)
                "Nhân bản" -> { store.upsert(s.copy(id = Store.newId(), name = s.name + " (bản sao)", steps = ArrayList(s.steps))); build() }
                "Copy URL API" -> {
                    val base = Net.addresses().firstOrNull()?.let { "http://${it.second}:${ApiService.port}" } ?: "http://IP:${Prefs(this).apiPort}"
                    copyText("$base/run?name=" + java.net.URLEncoder.encode(s.name, "UTF-8"))
                }
                "Xoá" -> confirmDelete(s)
            }
            true
        }
        m.show()
    }

    private fun confirmDelete(s: Scenario) {
        android.app.AlertDialog.Builder(this)
            .setTitle("Xoá kịch bản?")
            .setMessage(s.name)
            .setNegativeButton("Huỷ", null)
            .setPositiveButton("Xoá") { _, _ -> store.delete(s.id); build() }
            .show()
    }

    private fun renameScenario(s: Scenario) {
        val et = android.widget.EditText(this).apply { setText(s.name) }
        android.app.AlertDialog.Builder(this)
            .setTitle("Đổi tên")
            .setView(et)
            .setNegativeButton("Huỷ", null)
            .setPositiveButton("Lưu") { _, _ ->
                val name = et.text.toString().trim()
                if (name.isNotEmpty()) { s.name = name; store.upsert(s); build() }
            }
            .show()
    }

    private fun createScenario() {
        val s = Scenario(Store.newId(), "Kịch bản mới", defaultDelayMs = Prefs(this).defaultDelayMs)
        store.upsert(s)
        openEditor(s.id)
    }

    private fun openEditor(id: String) {
        startActivity(Intent(this, ScenarioEditorActivity::class.java).putExtra("id", id))
    }

    private fun startRecord(s: Scenario) {
        if (!Access.canOverlay(this)) { toast("Cần quyền hiển thị trên ứng dụng khác"); Access.requestOverlay(this); return }
        RecordService.start(this, s.id)
        moveTaskToBack(true)
    }

    private fun testRun(s: Scenario) {
        if (!AutoClickService.isEnabled()) {
            toast("Hãy bật dịch vụ Trợ năng AutoTap trước")
            Access.openAccessibilitySettings(this)
            return
        }
        if (s.steps.isEmpty()) { toast("Kịch bản chưa có bước nào"); return }
        if (AutoClickService.running) { AutoClickService.stop(); toast("Đã dừng"); return }
        AutoClickService.run(s)
        toast("Đang chạy \"${s.name}\"… mở ứng dụng đích")
        moveTaskToBack(true)
    }

    private fun toggleCapture() {
        if (CaptureService.isEnabled()) { CaptureService.stop(this); return }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        try {
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE)
        } catch (_: Exception) {
            toast("Thiết bị không hỗ trợ ghi màn hình")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_CAPTURE && resultCode == Activity.RESULT_OK && data != null) {
            CaptureService.start(this, resultCode, data)
        }
    }

    companion object {
        private const val REQ_CAPTURE = 7
    }
}
