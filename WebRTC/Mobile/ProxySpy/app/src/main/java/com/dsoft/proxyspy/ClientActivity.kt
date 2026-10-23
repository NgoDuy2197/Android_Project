package com.dsoft.proxyspy

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.zxing.integration.android.IntentIntegrator
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * Client mode for a ROOTED phone: point this phone through another phone running ProxySpy,
 * fetch that phone's CA and install it as a system certificate — entirely on-device, no PC.
 */
class ClientActivity : Activity() {
    private lateinit var p: Palette
    private lateinit var addr: EditText
    private lateinit var pkg: EditText
    private lateinit var forceStopCb: CheckBox
    private lateinit var log: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        p = palette()
        window.statusBarColor = p.card
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(p.bg)
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }

        col.addView(text("Máy khách (root)", 20f, p.text, 600))
        col.addView(text("Đi qua một máy khác đang chạy ProxySpy, rồi cài CA của máy đó làm CA hệ thống.",
            13f, p.sec).apply { setPadding(0, dp(4), 0, dp(10)) })

        // Nhắc quan trọng: đăng nhập trước khi bật MITM (trang login Microsoft pin cert)
        col.addView(text("⚠ ĐĂNG NHẬP vào mini-app TRƯỚC khi cài CA. Trang login (vd Microsoft) pin cert nên " +
            "không đăng nhập được khi đang MITM — đăng nhập sẵn rồi mới cài CA (không đóng app).",
            12.5f, p.blue, 600).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(p.bg)                              // nền đen
                setStroke(dp(1), 0x55808080)               // viền xám mờ
            }
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { bottomMargin = dp(14) })

        col.addView(text("Địa chỉ máy chạy ProxySpy (IP:cổng)", 13f, p.sec))
        addr = EditText(this).apply {
            setText(Prefs.clientAddr(this@ClientActivity).ifEmpty { "" })
            hint = "vd 192.168.1.50:8080"
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setTextColor(p.text)
        }
        col.addView(addr)

        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(10), 0, 0) }
        row1.addView(pill("Quét QR", p.fill, p.blue) { scan() }, flex())
        row1.addView(pill("Tải CA + Cài (root)", p.blue, android.graphics.Color.WHITE) { install() }, flex())
        col.addView(row1)

        val rowMod = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(10), 0, 0) }
        rowMod.addView(pill("Cài CA bền (Magisk + reboot)", p.green, android.graphics.Color.WHITE) { installPersistent() }, flex())
        col.addView(rowMod)
        col.addView(text("Dùng cái này cho mini-app/WebView: CA vào kho từ lúc boot nên WebView mới tin. " +
            "Cài xong REBOOT, rồi bật proxy Wi-Fi + mở app.", 12f, p.sec).apply { setPadding(dp(4), dp(4), 0, 0) })

        col.addView(text("Gói app cần mở lại sau khi cài (tuỳ chọn)", 13f, p.sec).apply { setPadding(0, dp(14), 0, 0) })
        pkg = EditText(this).apply {
            setText(Prefs.targetPkg(this@ClientActivity))
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setTextColor(p.text)
        }
        col.addView(pkg)

        val rowPick = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(10), 0, 0) }
        rowPick.addView(pill("Chọn app đang mở…", p.fill, p.blue) { pickApp() }, flex())
        col.addView(rowPick)

        forceStopCb = CheckBox(this).apply {
            text = "Ép đóng app đích sau khi cài CA"
            textSize = 14f
            isChecked = Prefs.forceStop(this@ClientActivity)
            setTextColor(p.text)
            buttonTintList = ColorStateList.valueOf(p.blue)   // ô vuông màu xanh cho dễ thấy trên nền đen
            gravity = Gravity.CENTER_VERTICAL
            includeFontPadding = false
            setPadding(dp(8), 0, 0, 0)                         // chữ sát ô, căn giữa theo chiều dọc
            setOnCheckedChangeListener { _, v -> Prefs.setForceStop(this@ClientActivity, v) }
        }
        col.addView(forceStopCb, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(10) })
        col.addView(text("Tắt (khuyên dùng): giữ phiên đăng nhập 2FA; CA được tiêm thẳng vào app đang chạy. " +
            "Bật: chắc chắn nhận CA nhưng app khởi động lại (phải đăng nhập lại).",
            12f, p.sec).apply { setPadding(dp(4), dp(2), 0, 0) })

        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(10), 0, 0) }
        row2.addView(pill("Trả mạng bình thường", p.fill, p.red) { restore() }, flex())
        row2.addView(pill("Kiểm tra root", p.fill, p.blue) { checkRoot() }, flex())
        col.addView(row2)
        col.addView(text("“Trả mạng bình thường”: gỡ proxy + tháo CA hệ thống (không cần reboot).",
            12f, p.sec).apply { setPadding(0, dp(6), 0, 0) })

        log = text("", 12f, p.text).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = rounded(p.card, dp(8).toFloat())
            setTextIsSelectable(true)
        }
        col.addView(log, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(16) })

        setContentView(ScrollView(this).apply { setBackgroundColor(p.bg); addView(col) })
    }

    private fun flex() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        .apply { marginStart = dp(5); marginEnd = dp(5) }

    private fun logln(s: String) = runOnUiThread { log.text = (log.text.toString() + s + "\n").takeLast(4000) }

    private fun normalize(raw: String): String {
        var a = raw.trim().removePrefix("http://").removePrefix("https://").trimEnd('/')
        a = a.substringBefore('/')
        if (!a.contains(':')) a = "$a:8080"
        return a
    }

    private fun scan() {
        IntentIntegrator(this).apply {
            setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
            setPrompt("Quét QR trên máy chạy ProxySpy")
            setBeepEnabled(false)
            setOrientationLocked(false)
        }.initiateScan()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val r = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
        if (r != null && r.contents != null) {
            addr.setText(normalize(r.contents))
            logln("Đã quét: ${addr.text}")
        } else super.onActivityResult(requestCode, resultCode, data)
    }

    private data class AppItem(val pkg: String, val label: String, val running: Boolean)

    /** Liệt kê app mở được; đánh dấu ● app đang chạy (lấy qua root), xếp lên đầu. Vẫn nhập tay được. */
    private fun pickApp() {
        logln("Đang quét app…")
        Thread {
            val pm = packageManager
            // tên tiến trình đang chạy (qua root) → suy ra package đang "mở"
            val running = HashSet<String>()
            val (_, ps) = RootCa.runSu("ps -A -o NAME")
            for (line in ps.lineSequence()) {
                val n = line.trim()
                if (n.contains('.') && !n.startsWith('[')) running.add(n.substringBefore(':'))
            }
            // app có icon mở được (user apps + system app đang chạy)
            val items = ArrayList<AppItem>()
            for (ai in pm.getInstalledApplications(0)) {
                val pk = ai.packageName
                if (pm.getLaunchIntentForPackage(pk) == null) continue
                val run = running.contains(pk)
                val isSys = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                if (!run && isSys) continue   // bớt rác: system app không chạy thì ẩn
                items.add(AppItem(pk, pm.getApplicationLabel(ai).toString(), run))
            }
            items.sortWith(compareByDescending<AppItem> { it.running }.thenBy { it.label.lowercase() })
            val display = items.map { (if (it.running) "● " else "○ ") + it.label + "\n" + it.pkg }.toTypedArray()
            runOnUiThread {
                if (items.isEmpty()) { logln("[!] Không liệt kê được app (cần cấp quyền root)."); return@runOnUiThread }
                logln("Thấy ${items.count { it.running }} app đang chạy / ${items.size} app mở được.")
                AlertDialog.Builder(this)
                    .setTitle("Chọn app  (● đang chạy)")
                    .setItems(display) { _, which ->
                        val it = items[which]
                        pkg.setText(it.pkg)
                        Prefs.setTargetPkg(this, it.pkg)
                        logln("Đã chọn: ${it.label}  (${it.pkg})")
                    }
                    .setNegativeButton("Đóng", null)
                    .show()
            }
        }.start()
    }

    private fun checkRoot() {
        Thread {
            val (code, out) = RootCa.runSu("id")
            logln(if (out.contains("uid=0")) "Root OK: $out" else "Chưa có root: $out (code=$code)")
        }.start()
    }

    private fun install() {
        val a = normalize(addr.text.toString())
        if (a.startsWith(":") || a.length < 7) { logln("Nhập địa chỉ máy A trước."); return }
        Prefs.setClientAddr(this, a)
        Prefs.setTargetPkg(this, pkg.text.toString().trim())
        logln("— Bắt đầu với $a —")
        Thread {
            // 1. tải CA của máy A
            logln("Tải CA từ http://$a/ca.crt …")
            val der = fetchCa(a)
            if (der == null) { logln("[!] Không tải được CA. Cùng Wi-Fi với máy A? App A đã Bật proxy?"); return@Thread }
            logln("Đã tải CA (${der.size} byte).")
            // 2. cài CA hệ thống (root) — tiêm luôn vào app đích đang chạy để khỏi phải đóng app
            val pk = Prefs.targetPkg(this)
            logln("Cài CA hệ thống (xin quyền root)…")
            val (ok, out) = RootCa.installSystemCa(this, der, pk)
            logln(out.takeLast(400))
            if (!ok) {
                if (out.contains("ABORT_LOW_CA"))
                    logln("[!] Kho CA gốc đang hỏng (<100). HÃY REBOOT máy rồi bấm lại — không có gì bị phá thêm.")
                else logln("[!] Cài CA thất bại.")
                return@Thread
            }
            logln("CA hệ thống: OK." + if (out.contains("INJECT_$pk")) "  (đã tiêm vào $pk đang chạy)" else "")
            // 3. đặt proxy về máy A
            RootCa.setProxy(a)
            logln("Đã đặt proxy máy này → $a")
            // 4. đóng app đích — CHỈ khi người dùng tick (mặc định giữ phiên đăng nhập)
            if (pk.isNotEmpty() && Prefs.forceStop(this)) {
                RootCa.forceStop(pk); logln("Đã force-stop $pk — mở lại app để bắt.")
            } else if (pk.isNotEmpty()) {
                logln("Giữ app $pk (không đóng). Nếu app chưa bắt được, thực hiện 1 thao tác mới trong app; " +
                    "không ăn thì mới tick \"Ép đóng app\" rồi cài lại.")
            }
            logln("✓ XONG. Request hiện trên máy A.")
        }.start()
    }

    private fun installPersistent() {
        val a = normalize(addr.text.toString())
        if (a.startsWith(":") || a.length < 7) { logln("Nhập địa chỉ máy A trước."); return }
        Prefs.setClientAddr(this, a)
        logln("— Cài CA BỀN (Magisk) với $a —")
        Thread {
            logln("Tải CA từ http://$a/ca.crt …")
            val der = fetchCa(a)
            if (der == null) { logln("[!] Không tải được CA. Cùng Wi-Fi với máy A? App A đã Bật proxy?"); return@Thread }
            logln("Đã tải CA (${der.size} byte). Tạo Magisk module (xin quyền root)…")
            val (ok, out) = RootCa.installMagiskModule(this, der)
            logln(out.takeLast(400))
            if (!ok) {
                if (out.contains("ABORT_LOW_CA"))
                    logln("[!] Kho CA gốc đang hỏng (<100). Gỡ overlay/reboot rồi thử lại.")
                else logln("[!] Tạo module thất bại.")
                return@Thread
            }
            RootCa.setProxy(a)
            logln("✓ Module đã cài + proxy đã đặt → $a")
            logln("➜ HÃY REBOOT máy. Sau khi khởi động lại, mọi app & WebView (mini-app) sẽ tin CA.")
        }.start()
    }

    private fun restore() {
        Thread {
            logln("Trả mạng về bình thường (gỡ proxy + tháo CA + gỡ module)…")
            val (_, out) = RootCa.fullRestore()
            logln(out.takeLast(200))
            val (_, out2) = RootCa.removeMagiskModule()
            logln(out2.takeLast(120))
            logln("✓ Xong. Proxy gỡ, CA lúc-chạy tháo. Nếu đã cài Magisk module: REBOOT để gỡ hẳn.")
        }.start()
    }

    private fun fetchCa(addr: String): ByteArray? = try {
        val client = OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .connectTimeout(8, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).build()
        client.newCall(Request.Builder().url("http://$addr/ca.crt").build()).execute().use { resp ->
            if (resp.isSuccessful) resp.body?.bytes() else null
        }
    } catch (_: Exception) { null }
}
