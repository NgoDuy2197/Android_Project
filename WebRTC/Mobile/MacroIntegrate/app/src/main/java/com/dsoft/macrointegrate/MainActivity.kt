package com.dsoft.macrointegrate

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/** Setup screen: webhook, username, permissions, enable screen capture, and test buttons. */
class MainActivity : Activity() {
    private lateinit var p: Palette
    private lateinit var prefs: Prefs
    private lateinit var captureStatus: TextView
    private lateinit var locStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        p = palette()
        prefs = Prefs(this)
        val col = page(p, "MacroIntegrate", back = false)

        col.addView(footnote(p, "Công cụ nhẹ cho MacroDroid: gọi bằng Launch Activity, Shortcut hoặc Intent để " +
            "gửi vị trí hoặc ảnh màn hình lên Discord."))

        // ---- webhook ----
        col.addView(sectionHeader(p, "Discord webhook"))
        val hook = inlineField(p, prefs.webhookOverride, wide = true) { prefs.webhook = it }.apply {
            hint = if (prefs.hasDefault) "Đang dùng link mặc định trong bản build" else "Dán link webhook…"
        }
        col.addView(group(p,
            row(p, "Link webhook", hook),
            row(p, "Tên hiển thị", inlineField(p, prefs.username) { prefs.username = it }),
        ))
        col.addView(footnote(p, if (prefs.hasDefault)
            "Để trống link = dùng link mặc định khai trong webhook.properties lúc build."
        else
            "Chưa có link mặc định. Khai trong file webhook.properties rồi build lại, hoặc dán link vào đây."))

        // ---- actions MacroDroid calls ----
        col.addView(sectionHeader(p, "Chức năng"))
        locStatus = text("", 13f, p.secondary)
        captureStatus = text("", 13f, p.secondary)
        col.addView(group(p,
            row(p, "Gửi vị trí ngay (thử)", chevron(p)) { testLocation() },
            row(p, "Gửi ảnh màn hình ngay (thử)", chevron(p)) { testScreenshot() },
        ))

        // ---- permissions ----
        col.addView(sectionHeader(p, "Quyền & bật sẵn"))
        col.addView(group(p,
            row(p, "Quyền vị trí", locStatus) { askLocation() },
            row(p, "Chụp màn hình", LinearLayout(this).apply {
                addView(captureStatus)
                addView(chevron(p))
            }) { toggleCapture() },
        ))
        col.addView(footnote(p, "Chụp màn hình cần bật một lần (Android yêu cầu đồng ý ghi màn hình). " +
            "Khi bật, có một thông báo thường trực và MacroDroid gửi ảnh được bất cứ lúc nào. " +
            "Tắt máy/khởi động lại thì bật lại."))

        // ---- how to wire MacroDroid ----
        col.addView(sectionHeader(p, "Cách gọi từ MacroDroid"))
        col.addView(card(p).apply {
            setPadding(dp(16), dp(14), dp(16), dp(14))
            addView(text(
                "Cách 1 — Shortcut: MacroDroid > Action > \"Mở ứng dụng/phím tắt\" > chọn phím tắt " +
                    "\"Gửi vị trí\" hoặc \"Gửi ảnh màn hình\" của MacroIntegrate.\n\n" +
                    "Cách 2 — Launch Activity:\n" +
                    "• Package: $packageName\n" +
                    "• Class: $packageName.ActionActivity\n" +
                    "• Extra (text) action = location  hoặc  screenshot\n\n" +
                    "Cách 3 — Intent action: $packageName.SEND_LOCATION / .SEND_SCREENSHOT\n\n" +
                    "Tùy chọn mỗi lần gọi: extra webhook (đổi link), message (ghi chú kèm theo, chỉ với vị trí).",
                13f, p.secondary,
            ).apply { setTextIsSelectable(true); setLineSpacing(0f, 1.25f) })
        })

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
    }

    override fun onResume() {
        super.onResume()
        CaptureService.listener = { runOnUiThread { refresh() } }
        refresh()
    }

    override fun onPause() {
        super.onPause()
        CaptureService.listener = null
    }

    private fun refresh() {
        val loc = Locations.hasPermission(this)
        locStatus.text = if (loc) "Đã cấp" else "Chưa cấp ›"
        locStatus.setTextColor(if (loc) p.green else p.orange)
        captureStatus.text = if (CaptureService.running) "Đang bật" else "Tắt ›"
        captureStatus.setTextColor(if (CaptureService.running) p.green else p.secondary)
    }

    private fun askLocation() {
        if (Locations.hasPermission(this)) return
        requestPermissions(
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), REQ_LOC
        )
    }

    private fun toggleCapture() {
        if (CaptureService.running) {
            CaptureService.stop(this)
            return
        }
        if (!hasWebhook()) return toast("Cấu hình webhook trước đã")
        val mpm = getSystemService(MediaProjectionManager::class.java)
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE)
    }

    private fun testLocation() {
        if (!hasWebhook()) return toast("Cấu hình webhook trước đã")
        startActivity(Intent(this, ActionActivity::class.java).putExtra("action", "location"))
    }

    private fun testScreenshot() {
        if (!hasWebhook()) return toast("Cấu hình webhook trước đã")
        if (!CaptureService.running) return toast("Bật 'Chụp màn hình' trước đã")
        startActivity(Intent(this, ActionActivity::class.java).putExtra("action", "screenshot"))
    }

    private fun hasWebhook() = prefs.webhook.isNotBlank()

    private fun toast(s: String) = android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show()

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_CAPTURE) {
            if (resultCode == RESULT_OK && data != null) CaptureService.enable(this, resultCode, data)
            else toast("Không bật được chụp màn hình")
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
        if (requestCode == REQ_LOC && !Locations.hasPermission(this)) {
            // User may need "Allow all the time" for background triggers.
            toast("Nếu MacroDroid gọi lúc màn hình tắt, hãy chọn \"Luôn cho phép\" trong cài đặt vị trí")
        }
    }

    companion object {
        private const val REQ_NOTIF = 1
        private const val REQ_LOC = 2
        private const val REQ_CAPTURE = 3
    }
}
