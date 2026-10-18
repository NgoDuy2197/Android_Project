package com.dsoft.toolbox

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Patterns
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Scans every barcode / QR format with Google's code scanner (camera UI runs
 * inside Play services, so the app needs no camera permission).
 * Result card: copy, open (for links), plus a short history.
 */
class ScanActivity : Activity() {
    private lateinit var p: Palette
    private lateinit var resultCard: LinearLayout
    private lateinit var resultType: TextView
    private lateinit var resultText: TextView
    private lateinit var openBtn: TextView
    private lateinit var historyBox: LinearLayout
    private var current = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        p = palette()
        val col = page(p, "Quét mã")

        col.addView(bigButton("Quét mã vạch / QR", p.teal, p.blue) { scan() })

        resultType = text("", 13f, p.secondary, 500)
        resultText = text("", 18f, p.text).apply {
            setTextIsSelectable(true)
            setLineSpacing(0f, 1.15f)
            setPadding(0, dp(8), 0, 0)
        }
        openBtn = bigButton("Mở liên kết", p.blue, p.indigo) { open(current) }
        resultCard = card(p).apply {
            setPadding(dp(16), dp(14), dp(16), dp(16))
            addView(resultType)
            addView(resultText)
            addView(LinearLayout(context).apply {
                setPadding(0, dp(14), 0, 0)
                addView(bigButton("Copy", p.green, p.green) { copyText(current) },
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(openBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(10) })
            })
            visibility = View.GONE
        }
        col.addView(resultCard, vlp().apply { topMargin = dp(16) })

        col.addView(sectionHeader(p, "Lịch sử"))
        historyBox = card(p)
        col.addView(historyBox)
        col.addView(footnote(p, "Hỗ trợ QR, Data Matrix, Aztec, PDF417, EAN-13/8, UPC-A/E, Code 128/39/93, Codabar, ITF. " +
            "Dùng trình quét của Google Play services: lần đầu có thể mất vài giây để tải."))
        renderHistory()
        if (savedInstanceState == null) scan()
    }

    private fun scan() {
        val opts = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS)
            .enableAutoZoom()
            .build()
        GmsBarcodeScanning.getClient(this, opts).startScan()
            .addOnSuccessListener { b -> show(b.rawValue ?: b.displayValue ?: "", formatName(b.format), isLink(b)) }
            .addOnFailureListener { e -> toast("Không mở được trình quét: ${e.message}") }
    }

    private fun show(value: String, format: String, link: Boolean) {
        if (value.isEmpty()) return toast("Mã không có nội dung")
        current = value
        resultType.text = format + if (link) " · Liên kết" else ""
        resultText.text = value
        openBtn.visibility = if (link) View.VISIBLE else View.GONE
        resultCard.visibility = View.VISIBLE
        addHistory(value, format)
    }

    private fun isLink(b: Barcode) = b.valueType == Barcode.TYPE_URL || looksLikeUrl(b.rawValue ?: "")

    private fun looksLikeUrl(s: String) = s.startsWith("http://", true) || s.startsWith("https://", true) ||
        (Patterns.WEB_URL.matcher(s.trim()).matches() && !s.contains(' '))

    private fun open(s: String) {
        val url = if (s.contains("://")) s.trim() else "https://${s.trim()}"
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            toast("Không có ứng dụng mở được liên kết này")
        }
    }

    // ---- history (last 30, stored as JSON in prefs) ----

    private fun history(): JSONArray =
        runCatching { JSONArray(getSharedPreferences("toolbox", MODE_PRIVATE).getString("scan_history", "[]")) }
            .getOrDefault(JSONArray())

    private fun addHistory(value: String, format: String) {
        val old = history()
        val arr = JSONArray().put(JSONObject().put("v", value).put("f", format).put("t", System.currentTimeMillis()))
        for (i in 0 until old.length()) {
            if (arr.length() >= 30) break
            val o = old.getJSONObject(i)
            if (o.optString("v") != value) arr.put(o)
        }
        getSharedPreferences("toolbox", MODE_PRIVATE).edit().putString("scan_history", arr.toString()).apply()
        renderHistory()
    }

    private fun renderHistory() {
        historyBox.removeAllViews()
        val arr = history()
        if (arr.length() == 0) {
            historyBox.addView(text("Chưa quét mã nào", 15f, p.secondary).apply { setPadding(dp(16), dp(14), dp(16), dp(14)) })
            return
        }
        val fmt = SimpleDateFormat("d/M HH:mm", Locale.getDefault())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val v = o.optString("v")
            if (i > 0) historyBox.addView(separator(p))
            historyBox.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(10), dp(16), dp(10))
                background = pressable(null, p.ripple)
                addView(text(v, 15f, p.text).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
                addView(text("${o.optString("f")} · ${fmt.format(Date(o.optLong("t")))}", 12f, p.secondary).apply {
                    setPadding(0, dp(3), 0, 0)
                })
                setOnClickListener { show(v, o.optString("f"), looksLikeUrl(v)) }
            })
        }
        historyBox.addView(separator(p, 0))
        historyBox.addView(row(p, "Xóa lịch sử", null) {
            getSharedPreferences("toolbox", MODE_PRIVATE).edit().remove("scan_history").apply()
            renderHistory()
        }.apply { (getChildAt(0) as TextView).setTextColor(p.red) })
    }

    private fun formatName(f: Int) = when (f) {
        Barcode.FORMAT_QR_CODE -> "QR Code"
        Barcode.FORMAT_DATA_MATRIX -> "Data Matrix"
        Barcode.FORMAT_AZTEC -> "Aztec"
        Barcode.FORMAT_PDF417 -> "PDF417"
        Barcode.FORMAT_EAN_13 -> "EAN-13"
        Barcode.FORMAT_EAN_8 -> "EAN-8"
        Barcode.FORMAT_UPC_A -> "UPC-A"
        Barcode.FORMAT_UPC_E -> "UPC-E"
        Barcode.FORMAT_CODE_128 -> "Code 128"
        Barcode.FORMAT_CODE_39 -> "Code 39"
        Barcode.FORMAT_CODE_93 -> "Code 93"
        Barcode.FORMAT_CODABAR -> "Codabar"
        Barcode.FORMAT_ITF -> "ITF"
        else -> "Mã vạch"
    }
}
