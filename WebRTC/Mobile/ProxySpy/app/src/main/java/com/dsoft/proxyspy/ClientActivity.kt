package com.dsoft.proxyspy

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
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
            13f, p.sec).apply { setPadding(0, dp(4), 0, dp(14)) })

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

        col.addView(text("Gói app cần mở lại sau khi cài (tuỳ chọn)", 13f, p.sec).apply { setPadding(0, dp(14), 0, 0) })
        pkg = EditText(this).apply {
            setText(Prefs.targetPkg(this@ClientActivity))
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setTextColor(p.text)
        }
        col.addView(pkg)

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
            // 2. cài CA hệ thống (root)
            logln("Cài CA hệ thống (xin quyền root)…")
            val (ok, out) = RootCa.installSystemCa(this, der)
            logln(out.takeLast(400))
            if (!ok) { logln("[!] Cài CA thất bại."); return@Thread }
            logln("CA hệ thống: OK.")
            // 3. đặt proxy về máy A
            RootCa.setProxy(a)
            logln("Đã đặt proxy máy này → $a")
            // 4. mở lại app đích
            val pk = Prefs.targetPkg(this)
            if (pk.isNotEmpty()) { RootCa.forceStop(pk); logln("Đã force-stop $pk — mở lại app để bắt.") }
            logln("✓ XONG. Mở app đích; request hiện trên máy A.")
        }.start()
    }

    private fun restore() {
        Thread {
            logln("Trả mạng về bình thường (gỡ proxy + tháo CA)…")
            val (_, out) = RootCa.fullRestore()
            logln(out.takeLast(200))
            logln("✓ Xong. Proxy đã gỡ, CA hệ thống đã tháo. Mạng truy cập bình thường trở lại.")
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
