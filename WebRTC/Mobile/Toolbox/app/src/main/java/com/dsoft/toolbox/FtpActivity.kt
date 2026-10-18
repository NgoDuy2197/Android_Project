package com.dsoft.toolbox

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.Settings
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File

class FtpActivity : Activity() {
    private lateinit var p: Palette
    private lateinit var prefs: Prefs
    private lateinit var status: TextView
    private lateinit var addrBox: LinearLayout
    private lateinit var event: TextView
    private lateinit var toggle: TextView
    private lateinit var pathField: EditText
    private lateinit var permStatus: TextView
    private val main = Handler(Looper.getMainLooper())
    private val poll = object : Runnable {
        override fun run() { refresh(); main.postDelayed(this, 3000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        p = palette()
        prefs = Prefs(this)
        val col = page(p, "FTP Server")

        // ---- status card ----
        status = text("", 17f, p.text, 700)
        addrBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        event = text("", 13f, p.secondary).apply { setPadding(0, dp(6), 0, 0) }
        toggle = bigButton("", p.green, p.green) { onToggle() }
        col.addView(card(p).apply {
            setPadding(dp(16), dp(16), dp(16), dp(16))
            addView(status)
            addView(addrBox, vlp())
            addView(event)
            addView(toggle, vlp().apply { topMargin = dp(14) })
        })

        // ---- folder ----
        col.addView(sectionHeader(p, "Thư mục chia sẻ"))
        pathField = inlineField(p, prefs.ftpDir, wide = true) { v ->
            prefs.ftpDir = v
            FtpService.restartIfRunning(this)
            refresh()
        }
        col.addView(group(p,
            row(p, "Đường dẫn", pathField),
            row(p, "Chọn thư mục…", chevron(p)) { pickFolder() },
            row(p, "Dùng bộ nhớ trong (gốc)", chevron(p)) { setDir(Environment.getExternalStorageDirectory().absolutePath) },
        ))
        col.addView(footnote(p, "Máy tính chỉ thấy và thao tác được bên trong thư mục này (kể cả thư mục con)."))

        // ---- access ----
        col.addView(sectionHeader(p, "Truy cập"))
        val restart = { FtpService.restartIfRunning(this) }
        val user = inlineField(p, prefs.ftpUser) { prefs.ftpUser = it; restart() }.apply { hint = "ẩn danh" }
        val pass = inlineField(p, prefs.ftpPass) { prefs.ftpPass = it; restart() }.apply {
            hint = "không"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        }
        val port = inlineField(p, prefs.ftpPort.toString(), numeric = true) {
            prefs.ftpPort = it.toIntOrNull() ?: Prefs.DEFAULT_PORT
            restart()
            refresh()
        }.apply { inputType = InputType.TYPE_CLASS_NUMBER }
        col.addView(group(p,
            row(p, "Chỉ đọc (không cho ghi/xóa)", iosSwitch(p, prefs.ftpReadOnly) { prefs.ftpReadOnly = it; restart() }),
            row(p, "Tài khoản", user),
            row(p, "Mật khẩu", pass),
            row(p, "Cổng", port),
            row(p, "Khôi phục mặc định", chevron(p)) {
                prefs.ftpUser = Prefs.DEFAULT_USER
                prefs.ftpPass = Prefs.DEFAULT_PASS
                prefs.ftpPort = Prefs.DEFAULT_PORT
                user.setText(prefs.ftpUser)
                pass.setText(prefs.ftpPass)
                port.setText(prefs.ftpPort.toString())
                restart()
                refresh()
            },
        ))
        col.addView(footnote(p, "Mặc định: tài khoản ${Prefs.DEFAULT_USER} / mật khẩu ${Prefs.DEFAULT_PASS}, cổng ${Prefs.DEFAULT_PORT}. " +
            "Xóa trống tài khoản = đăng nhập ẩn danh (ai cùng mạng cũng vào được). Cổng phải từ 1024 trở lên."))

        // ---- permission ----
        col.addView(sectionHeader(p, "Quyền"))
        permStatus = text("", 15f, p.secondary)
        col.addView(group(p, row(p, "Truy cập tất cả tệp", permStatus) { requestStorage() }))
        col.addView(footnote(p,
            "Cách kết nối (điện thoại và máy tính cùng Wi-Fi, hoặc máy tính bắt hotspot của điện thoại):\n" +
                "• Windows: mở File Explorer, gõ địa chỉ ftp://… ở trên vào thanh địa chỉ rồi Enter. Kéo thả file vào để chép.\n" +
                "• Hoặc dùng FileZilla / WinSCP: Host = IP, Port = cổng.\n" +
                "Server vẫn chạy khi tắt màn hình; bấm Dừng trên thông báo để tắt."))
    }

    override fun onResume() {
        super.onResume()
        FtpService.listener = { refresh() }
        main.post(poll)
    }

    override fun onPause() {
        super.onPause()
        FtpService.listener = null
        main.removeCallbacks(poll)
    }

    private fun refresh() {
        val on = FtpService.running
        status.text = when {
            on -> "● Đang chạy"
            FtpService.error != null -> "Lỗi: ${FtpService.error}"
            else -> "Đang tắt"
        }
        status.setTextColor(if (on) p.green else if (FtpService.error != null) p.red else p.text)
        addrBox.removeAllViews()
        if (on) {
            val ips = Net.addresses()
            if (ips.isEmpty()) addrBox.addView(text("Chưa có Wi-Fi / hotspot", 15f, p.secondary).apply { setPadding(0, dp(10), 0, 0) })
            for ((iface, ip) in ips) {
                val url = "ftp://$ip:${FtpService.port}"
                addrBox.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, dp(10), 0, 0)
                    addView(text(url, 21f, p.blue, 700).apply { typeface = Typeface.MONOSPACE })
                    addView(text("$iface · chạm để copy", 12f, p.secondary).apply { setPadding(0, dp(2), 0, 0) })
                    setOnClickListener { copyText(url) }
                })
            }
            val login = if (prefs.ftpUser.isEmpty()) "Đăng nhập: ẩn danh" else "Đăng nhập: ${prefs.ftpUser} / ${prefs.ftpPass}"
            addrBox.addView(text(login, 14f, p.text, 500).apply { setPadding(0, dp(10), 0, 0) })
        }
        event.text = if (on && FtpService.lastEvent.isNotEmpty()) FtpService.lastEvent else ""
        event.visibility = if (event.text.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
        toggle.text = if (on) "Dừng server" else "Bật FTP server"
        toggle.background = pressable(
            if (on) gradient(dp(14).toFloat(), p.red, p.red) else gradient(dp(14).toFloat(), p.green, p.green), 0x33FFFFFF,
        )
        permStatus.text = if (hasStorage()) "Đã cấp" else "Chưa cấp ›"
        permStatus.setTextColor(if (hasStorage()) p.secondary else p.red)
    }

    private fun onToggle() {
        if (FtpService.running) {
            FtpService.stop(this)
            return
        }
        if (!hasStorage()) {
            toast("Cần cấp quyền truy cập tệp trước")
            requestStorage()
            return
        }
        if (!File(prefs.ftpDir).isDirectory) {
            toast("Thư mục không tồn tại: ${prefs.ftpDir}")
            return
        }
        FtpService.error = null
        FtpService.start(this)
    }

    // ---- storage permission ----

    private fun hasStorage(): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    private fun requestStorage() {
        if (hasStorage()) return
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE), REQ_PERM)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        refresh()
    }

    // ---- folder picker ----

    private fun pickFolder() {
        try {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_TREE)
        } catch (_: Exception) {
            toast("Máy không có trình chọn thư mục — hãy gõ đường dẫn")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (requestCode != REQ_TREE || resultCode != RESULT_OK) return
        val path = treeToPath(uri)
        if (path == null) toast("Không đọc được đường dẫn thư mục này") else setDir(path)
    }

    private fun setDir(path: String) {
        prefs.ftpDir = path
        pathField.setText(prefs.ftpDir)
        FtpService.restartIfRunning(this)
        refresh()
    }

    /** "primary:DCIM/Camera" -> /storage/emulated/0/DCIM/Camera; "1234-ABCD:x" -> /storage/1234-ABCD/x */
    private fun treeToPath(uri: Uri): String? = try {
        val id = DocumentsContract.getTreeDocumentId(uri)
        val vol = id.substringBefore(':')
        val rel = id.substringAfter(':', "")
        val base = if (vol.equals("primary", true)) Environment.getExternalStorageDirectory().absolutePath else "/storage/$vol"
        if (rel.isEmpty()) base else "$base/$rel"
    } catch (_: Exception) {
        null
    }

    companion object {
        private const val REQ_TREE = 1
        private const val REQ_PERM = 2
    }
}
