package com.dsoft.autotap

import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout

/** API configuration + playback / overlay settings. */
class SettingsActivity : android.app.Activity() {
    private lateinit var p: Palette
    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        p = palette()
        prefs = Prefs(this)
        val col = page(p, "Cài đặt")

        // ---- API ----
        col.addView(sectionHeader(p, "API server"))
        col.addView(group(p,
            row(p, "Cổng", inlineField(p, prefs.apiPort.toString(), numeric = true) {
                prefs.apiPort = it.toIntOrNull() ?: Prefs.DEFAULT_PORT; ApiService.restartIfRunning(this)
            }),
            row(p, "Tài khoản (để trống = không cần)", inlineField(p, prefs.apiUser) {
                prefs.apiUser = it; ApiService.restartIfRunning(this)
            }),
            row(p, "Mật khẩu", inlineField(p, prefs.apiPass) {
                prefs.apiPass = it; ApiService.restartIfRunning(this)
            }),
        ))
        col.addView(footnote(p, "Đổi cấu hình sẽ khởi động lại server nếu đang chạy. " +
            "Nếu đặt tài khoản/mật khẩu, máy khách phải dùng HTTP Basic auth."))

        // ---- playback ----
        col.addView(sectionHeader(p, "Chạy kịch bản"))
        col.addView(group(p,
            row(p, "Chờ mặc định giữa bước (ms)", inlineField(p, prefs.defaultDelayMs.toString(), numeric = true) {
                prefs.defaultDelayMs = it.toLongOrNull() ?: 300
            }),
            row(p, "Khi một bước lỗi", Segmented(this, p, listOf("Tiếp tục", "Dừng"),
                if (prefs.continueOnFail) 0 else 1) { prefs.continueOnFail = it == 0 }.apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }),
            row(p, "Giữ màn hình sáng / Wi-Fi khi chạy server", iosSwitch(p, prefs.keepAwake) { prefs.keepAwake = it }),
        ))

        // ---- overlay ----
        col.addView(sectionHeader(p, "Lớp phủ khi Ghi thao tác"))
        col.addView(group(p,
            row(p, "Độ tối lớp phủ (%)", inlineField(p, prefs.overlayDim.toString(), numeric = true) {
                prefs.overlayDim = it.toIntOrNull() ?: 25
            }),
        ))
        col.addView(footnote(p, "Lớp phủ hơi tối giúp bạn thấy ứng dụng bên dưới để ngắm vị trí. 0 = trong suốt, tối đa 80%."))

        // ---- about ----
        col.addView(sectionHeader(p, "Giới thiệu"))
        col.addView(group(p,
            row(p, "Phiên bản", text("1.0.0", 16f, p.secondary)),
        ))
    }
}
