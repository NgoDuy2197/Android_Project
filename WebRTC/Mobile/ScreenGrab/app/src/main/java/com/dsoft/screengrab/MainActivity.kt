package com.dsoft.screengrab

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.widget.LinearLayout
import com.dsoft.screengrab.capture.RootShell
import com.dsoft.screengrab.ui.*

class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private val uiListener = { runOnUiThread { render() } }
    private var hookAuto: Boolean? = null   // cached: can we self-configure via Vector CLI? (root check is off-UI)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        requestStartupPerms()
        render()
    }

    override fun onResume() { super.onResume(); CaptureService.addListener(uiListener); SniffService.addListener(uiListener); render() }
    override fun onPause() { super.onPause(); CaptureService.removeListener(uiListener); SniffService.removeListener(uiListener) }

    private fun render() {
        val col = page("ScreenGrab", "Chụp ảnh & quay màn hình — toàn màn hình hoặc vùng chọn, kèm âm thanh máy / mic.")

        val recording = CaptureService.isRecording

        // primary actions
        col.addView(filledButton("📷  Chụp ảnh", Theme.accent) { startAction(CaptureService.ACTION_SHOT) },
            fullW())
        col.addView(filledButton(if (recording) "⏹  Dừng quay" else "⏺  Quay màn hình", if (recording) Theme.red else Theme.accent2) {
            if (recording) sendService(CaptureService.ACTION_RECORD_STOP) else startAction(CaptureService.ACTION_RECORD_START)
        }, fullW())

        // quick options
        col.addView(sectionHeader("TUỲ CHỌN NHANH"))
        col.segmented("Phạm vi", listOf("full" to "Toàn màn hình", "region" to "Vùng chọn"), prefs.regionMode) {
            prefs.regionMode = it
        }
        val regLabel = if (prefs.hasRegion()) "Vùng: ${prefs.region().width()}x${prefs.region().height()}px" else "Chưa chọn vùng"
        col.addView(filledButton("✂  Chọn vùng  ($regLabel)", Theme.card) {
            startActivity(Intent(this, RegionActivity::class.java))
        }, fullW())
        col.switchRow("Âm thanh máy", "Tiếng đang phát trên điện thoại (Android 10+)", prefs.audioDevice) { prefs.audioDevice = it }
        col.switchRow("Ghi âm mic", "Thu tiếng từ micro", prefs.audioMic) {
            prefs.audioMic = it; if (it) ensureAudioPerm()
        }
        col.segmented("Chế độ quay", listOf("projection" to "Không root", "root" to "Root"), prefs.captureMode) {
            prefs.captureMode = it; render()
        }

        // status
        col.addView(sectionHeader("ĐIỀU KIỆN"))
        col.statusRow("Quyền ghi âm", "Cần cho âm thanh máy / mic", hasAudioPermission(this), "Cấp quyền") { ensureAudioPerm() }
        col.statusRow("Tối ưu pin đã tắt", "Giúp quay nền không bị dừng", isIgnoringBatteryOptimizations(this), "Mở cài đặt") {
            runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
        val hookOn = XposedStatus.moduleActive()
        col.statusRow("Hook LSPosed (bỏ FLAG_SECURE)", if (hookOn) "Đang hoạt động — quay được app chặn (Telegram/Udemy)" else "Chưa bật. Dùng nút bên dưới để tự bật (cần root + LSPosed Vector)",
            hookOn, null, null)
        if (!hookOn) {
            when (hookAuto) {
                true -> {
                    col.addView(filledButton("⚡  Tự bật hook cho MỌI app (root)", Theme.accent) { configureHook(true) }, fullW())
                    col.addView(tv("Một chạm: tự bật module + cấp scope qua LSPosed Vector CLI. Khỏi vào LSPosed Manager. (Bật xong cần MỞ LẠI app muốn quay.)",
                        12.5f, Theme.secondary).apply { setPadding(dp(4), dp(6), dp(4), 0) })
                }
                false -> col.addView(tv("Tự bật cần máy ĐÃ ROOT + LSPosed Vector (có CLI). Nếu dùng LSPosed bản gốc: mở LSPosed Manager → Modules → bật ScreenGrab → Scope chọn app → ép dừng app.",
                    12.5f, Theme.orange).apply { setPadding(dp(4), dp(6), dp(4), 0) })
                null -> {
                    col.addView(tv("Đang kiểm tra root/LSPosed…", 12.5f, Theme.secondary).apply { setPadding(dp(4), dp(6), dp(4), 0) })
                    Thread { val ok = com.dsoft.screengrab.capture.LsposedSetup.canAutoConfigure(); runOnUiThread { hookAuto = ok; render() } }.start()
                }
            }
        }
        if (prefs.captureMode == "root") {
            col.statusRow("Root", "Chế độ Root cần quyền su", RootShell.isAvailable(), null, null)
        }

        col.addView(sectionHeader("NÚT NỔI"))
        overlayRow(col)

        // ===== Video sniffer =====
        col.addView(sectionHeader("BẮT & TẢI VIDEO"))
        val sniffing = SniffService.running
        col.segmented("Định tuyến", listOf("root" to "Root (nên dùng)", "vpn" to "VPN (ko root)"), prefs.snifferRouting) {
            prefs.snifferRouting = it
        }
        snifferRow(col, sniffing)
        col.addView(filledButton("🎬  Video đã bắt / Tải về", Theme.accent) {
            startActivity(Intent(this, MediaListActivity::class.java))
        }, fullW())
        col.addView(tv("Bật → mở app/Chrome phát video → link hiện trong 'Video đã bắt'. Chế độ Root tự cài chứng chỉ + định tuyến; cần cấp quyền su. HLS/stream sẽ tự ghép thành 1 video.",
            12.5f, Theme.secondary).apply { setPadding(dp(4), dp(6), dp(4), 0) })
        if (sniffing && SniffService.lastError.isNotBlank())
            col.addView(tv("⚠ ${SniffService.lastError}", 12.5f, Theme.orange).apply { setPadding(dp(4), dp(6), dp(4), 0) })

        col.addView(sectionHeader("KHÁC"))
        col.addView(filledButton("⚙  Cài đặt nâng cao", Theme.card) { startActivity(Intent(this, SettingsActivity::class.java)) }, fullW())
        if (CaptureService.running)
            col.addView(filledButton("Kết thúc phiên quay (bỏ quyền)", Theme.card) { sendService(CaptureService.ACTION_STOP_SESSION) }, fullW())

        col.addView(tv("Mẹo: dùng nút trên thanh thông báo để Chụp/Quay khi đang ở app khác.", 12.5f, Theme.secondary).apply {
            setPadding(dp(4), dp(16), dp(4), 0)
        })
    }

    private fun startAction(action: String) {
        // audio needs permission before recording
        if (action == CaptureService.ACTION_RECORD_START && (prefs.audioDevice || prefs.audioMic) && !hasAudioPermission(this)) {
            ensureAudioPerm(); return
        }
        if (prefs.captureMode == "root") {
            if (!RootShell.isAvailable()) { toast("Không có quyền root"); return }
            sendService(action)
        } else {
            if (CaptureService.running) sendService(action)        // reuse existing projection
            else ProjectionRequestActivity.launch(this, action)    // first action → ask consent
        }
    }

    private fun configureHook(allApps: Boolean) {
        toast("Đang cấu hình hook… (có thể hiện popup xin quyền root)")
        Thread {
            val r = com.dsoft.screengrab.capture.LsposedSetup.configure(this, emptyList(), allApps)
            runOnUiThread {
                when (r.code) {
                    com.dsoft.screengrab.capture.LsposedSetup.Code.OK ->
                        toast("Đã bật hook cho ${r.count} app. Mở lại app cần quay rồi thử.")
                    com.dsoft.screengrab.capture.LsposedSetup.Code.NO_ROOT -> toast("Không có quyền root")
                    com.dsoft.screengrab.capture.LsposedSetup.Code.NO_CLI -> toast("Không thấy LSPosed Vector CLI — dùng LSPosed Manager thủ công")
                    com.dsoft.screengrab.capture.LsposedSetup.Code.FAILED -> toast("Cấu hình thất bại: ${r.detail.take(80)}")
                }
                render()
            }
        }.start()
    }

    private fun sendService(action: String) {
        val i = Intent(this, CaptureService::class.java).putExtra(CaptureService.EXTRA_ACTION, action)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
    }

    private fun requestStartupPerms() {
        val want = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            want.add(Manifest.permission.POST_NOTIFICATIONS)
        if (want.isNotEmpty()) requestPermissions(want.toTypedArray(), 1)
    }

    /** Overlay toggle built manually so the Switch state stays correct (pref = source of truth, no full re-render). */
    private fun overlayRow(col: LinearLayout) {
        val card = card()
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        row.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(tv("Nút nổi trên màn hình", 16f, Theme.text, bold = true))
            addView(tv("Hiện nút tròn nổi; bấm để xổ menu chụp/quay khi đang ở app khác", 12.5f, Theme.secondary).apply { setPadding(0, dp(2), 0, 0) })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val sw = android.widget.Switch(this)
        sw.isChecked = prefs.overlayEnabled
        sw.setOnCheckedChangeListener { _, on ->
            if (on) {
                if (canOverlay()) {
                    prefs.overlayEnabled = true
                    OverlayService.start(this)
                } else {
                    // can't yet → send to settings and snap the switch back to off without re-triggering churn
                    requestOverlay()
                    prefs.overlayEnabled = false
                    sw.isChecked = false
                }
            } else {
                prefs.overlayEnabled = false
                OverlayService.stop(this)
            }
        }
        row.addView(sw)
        card.addView(row)
        col.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
    }

    /** Sniffer on/off switch; for VPN mode it first asks for VPN consent. */
    private fun snifferRow(col: LinearLayout, running: Boolean) {
        val card = card()
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        row.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(tv("Bắt luồng video", 16f, Theme.text, bold = true))
            addView(tv("Lắng nghe video của app/web rồi liệt kê để tải", 12.5f, Theme.secondary).apply { setPadding(0, dp(2), 0, 0) })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val sw = android.widget.Switch(this)
        sw.isChecked = running
        sw.setOnCheckedChangeListener { _, on ->
            if (on) startSniffer() else SniffService.stop(this)
        }
        row.addView(sw)
        card.addView(row)
        col.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
    }

    private fun startSniffer() {
        if (prefs.snifferRouting == "vpn") {
            if (Build.VERSION.SDK_INT < 29) { toast("VPN cần Android 10+. Hãy dùng chế độ Root."); render(); return }
            val intent = android.net.VpnService.prepare(this)
            if (intent != null) { startActivityForResult(intent, REQ_VPN); return }  // consent → onActivityResult
        }
        prefs.snifferEnabled = true
        SniffService.start(this)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_VPN) {
            if (resultCode == RESULT_OK) { prefs.snifferEnabled = true; SniffService.start(this) }
            else toast("Chưa cấp quyền VPN")
            render()
        }
    }

    private fun canOverlay(): Boolean = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this)

    private fun requestOverlay() {
        toast("Hãy cấp quyền 'Hiển thị trên ứng dụng khác' rồi bật lại")
        runCatching {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
    }

    private fun ensureAudioPerm() {
        if (!hasAudioPermission(this)) requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 2)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        render()
    }

    private fun fullW() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) }
    private fun toast(m: String) = android.widget.Toast.makeText(this, m, android.widget.Toast.LENGTH_SHORT).show()

    private companion object { const val REQ_VPN = 71 }
}
