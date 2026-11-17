package com.dsoft.remotelink

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import com.dsoft.remotelink.capture.RootShell
import com.dsoft.remotelink.ui.*

class MainActivity : Activity() {
    private lateinit var prefs: Prefs
    private var rootOk = false
    private val onState = { runOnUiThread { render() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        render()
        // probe root in the background if any root mode is selected
        if (prefs.captureMode == "root" || prefs.inputMode == "root") {
            Thread { rootOk = RootShell.isAvailable(); runOnUiThread { render() } }.start()
        }
    }

    override fun onResume() {
        super.onResume()
        RemoteService.addStateListener(onState)
        Log2.addListener(onState)
        render()
    }

    override fun onPause() {
        super.onPause()
        RemoteService.removeStateListener(onState)
        Log2.removeListener(onState)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        render()
    }

    private fun render() {
        val col = page("RemoteLink")
        col.addView(tv("Xem & điều khiển màn hình điện thoại từ máy tính cùng mạng LAN.", 14f, Theme.secondary)
            .apply { setPadding(dp(4), dp(4), dp(4), 0) })

        connectionCard(col)
        startStopButton(col)
        conditions(col)

        col.addView(sectionHeader("Khác"))
        col.addView(filledButton("Cài đặt (giao thức, cổng, mật khẩu)", Theme.card) {
            startActivity(Intent(this, SettingsActivity::class.java))
        }, fullW().apply { topMargin = dp(4) })

        logTail(col)
    }

    private fun connectionCard(col: LinearLayout) {
        val running = RemoteService.running
        col.addCard {
            addView(context.tv(if (running) "● Đang chạy" else "○ Đang tắt", 15f,
                if (running) Theme.green else Theme.secondary, bold = true))
            val ip = Net.localIpv4() ?: "(chưa có mạng)"
            addView(context.tv("IP máy này: $ip", 15f, Theme.text).apply { setPadding(0, context.dp(8), 0, 0) })
            if (prefs.enableVnc) addView(context.tv("• VNC (RFB): cổng ${prefs.vncPort}  →  kết nối tới  $ip:${prefs.vncPort}", 13.5f, Theme.secondary))
            if (prefs.enableStream) addView(context.tv("• Stream (RustDesk-like): cổng ${prefs.streamPort}  →  dùng app Desktop", 13.5f, Theme.secondary))
            if (running) addView(context.tv("Đang xem: VNC ${RemoteService.vncClients()} · Stream ${RemoteService.streamClients()}",
                13.5f, Theme.accent2).apply { setPadding(0, context.dp(8), 0, 0) })
            addView(context.tv("Capture: ${if (prefs.captureMode == "root") "Root" else "MediaProjection"} · Input: ${if (prefs.inputMode == "root") "Root" else "Accessibility"}",
                12.5f, Theme.secondary).apply { setPadding(0, context.dp(8), 0, 0) })
        }
    }

    private fun startStopButton(col: LinearLayout) {
        val running = RemoteService.running
        if (running) {
            col.addView(filledButton("Dừng chia sẻ", Theme.red) { RemoteService.stop(this) },
                fullW().apply { topMargin = dp(14) })
        } else {
            col.addView(filledButton("Bắt đầu chia sẻ màn hình", Theme.accent) { startServer() },
                fullW().apply { topMargin = dp(14) })
        }
    }

    private fun startServer() {
        if (prefs.captureMode == "root") {
            Thread {
                val ok = RootShell.isAvailable()
                runOnUiThread {
                    if (!ok) { toast("Không có quyền root. Đổi Capture sang MediaProjection trong Cài đặt."); return@runOnUiThread }
                    val svc = Intent(this, RemoteService::class.java)
                    if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc) else startService(svc)
                }
            }.start()
        } else {
            ProjectionRequestActivity.launch(this)
        }
    }

    private fun conditions(col: LinearLayout) {
        col.addView(sectionHeader("Điều kiện hoạt động"))

        if (prefs.inputMode == "accessibility") {
            col.statusRow("Quyền Accessibility", "Để chạm/vuốt/gõ phím thay bạn (cấp 1 lần).",
                isAccessibilityEnabled(this), "Mở cài đặt Accessibility") {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        col.statusRow("Bỏ tối ưu pin", "Giữ dịch vụ không bị hệ thống tắt khi chạy nền.",
            isIgnoringBatteryOptimizations(this), "Yêu cầu bỏ tối ưu pin") {
            requestIgnoreBattery()
        }

        if (Build.VERSION.SDK_INT >= 33) {
            val granted = checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
            col.statusRow("Thông báo", "Cần cho thông báo dịch vụ nền (foreground).", granted, "Cấp quyền thông báo") {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        if (prefs.captureMode == "root" || prefs.inputMode == "root") {
            col.statusRow("Quyền Root", "Capture/điều khiển không cần hộp thoại (cần máy đã root).", rootOk, "Kiểm tra lại root") {
                Thread { rootOk = RootShell.isAvailable(); runOnUiThread { render() } }.start()
            }
        }
    }

    private fun requestIgnoreBattery() {
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
    }

    private fun logTail(col: LinearLayout) {
        col.addView(sectionHeader("Nhật ký"))
        col.addCard {
            val lines = Log2.snapshot().takeLast(8)
            addView(context.tv(if (lines.isEmpty()) "(trống)" else lines.joinToString("\n"), 11.5f, Theme.secondary).apply {
                typeface = android.graphics.Typeface.MONOSPACE
            })
        }
    }

    private fun fullW() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    private fun toast(s: String) = android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show()
}
