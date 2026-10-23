package com.dsoft.proxyspy

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject

class DetailActivity : Activity() {
    private lateinit var p: Palette

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        p = palette()
        window.statusBarColor = p.card
        val f = FlowStore.get(intent.getLongExtra("id", -1)) ?: run { finish(); return }

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(p.bg)
            setPadding(dp(16), dp(14), dp(16), dp(32))
        }

        col.addView(text("${f.method}  ${f.status.takeIf { it >= 0 } ?: ""}", 13f, methodColor(p, f.method), 600))
        col.addView(text(f.url, 13f, p.text).apply {
            typeface = Typeface.MONOSPACE; setPadding(0, dp(4), 0, dp(8))
        })
        copyRow(col, "Copy URL", f.url)

        f.error?.let { section(col, "Lỗi"); col.addView(mono(it, p.red)) }

        section(col, "Tổng quan")
        kv(col, "Thời gian", java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date(f.time)))
        if (f.status >= 0) kv(col, "Status", "${f.status} ${f.reason}")
        if (f.durationMs >= 0) kv(col, "Thời lượng", "${f.durationMs} ms")

        headerSection(col, "Request headers", f.reqHeaders)
        bodySection(col, "Request body", f.reqBody)
        if (f.status >= 0) {
            headerSection(col, "Response headers", f.resHeaders)
            bodySection(col, "Response body", f.resBody)
        } else if (f.error == null) {
            section(col, "Response"); col.addView(text("Đang chờ…", 13f, p.sec))
        }

        setContentView(ScrollView(this).apply { setBackgroundColor(p.bg); addView(col) })
    }

    private fun section(parent: LinearLayout, title: String) {
        parent.addView(text(title.uppercase(), 12f, p.sec, 600).apply { setPadding(0, dp(16), 0, dp(6)) })
    }

    private fun kv(parent: LinearLayout, k: String, v: String) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(3), 0, dp(3)) }
        row.addView(text(k, 12.5f, p.blue).apply {
            typeface = Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(dp(130), ViewGroup.LayoutParams.WRAP_CONTENT)
        })
        row.addView(text(v, 12.5f, p.text).apply { typeface = Typeface.MONOSPACE })
        parent.addView(row)
    }

    private fun headerSection(parent: LinearLayout, title: String, headers: List<Pair<String, String>>) {
        if (headers.isEmpty()) return
        section(parent, title)
        for ((k, v) in headers) kv(parent, k, v)
    }

    private fun bodySection(parent: LinearLayout, title: String, body: Body?) {
        if (body == null) return
        section(parent, "$title · ${body.contentType.ifBlank { "?" }} · ${fmtSize(body.size)}${if (body.truncated) " (đã cắt)" else ""}")
        val content = when (body.kind) {
            "text" -> prettyIfJson(body.text ?: "", body.contentType)
            else -> "[nhị phân ${fmtSize(body.size)}]\n" + (body.base64 ?: "").take(4000)
        }
        parent.addView(mono(content, p.text))
    }

    private fun mono(s: String, color: Int): TextView = text(s, 12f, color).apply {
        typeface = Typeface.MONOSPACE
        setTextIsSelectable(true)
        setPadding(dp(10), dp(8), dp(10), dp(8))
        background = rounded(p.card, dp(8).toFloat())
        setLineSpacing(0f, 1.1f)
    }

    private fun copyRow(parent: LinearLayout, label: String, value: String) {
        parent.addView(pill(label, p.fill, p.blue) {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("", value))
            Toast.makeText(this, "Đã copy", Toast.LENGTH_SHORT).show()
        }.apply { (this as TextView).setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f) })
    }

    private fun prettyIfJson(s: String, ct: String): String {
        if (!ct.contains("json") && !(s.trimStart().startsWith("{") || s.trimStart().startsWith("["))) return s
        return try {
            val t = s.trim()
            if (t.startsWith("[")) JSONArray(t).toString(2) else JSONObject(t).toString(2)
        } catch (_: Exception) { s }
    }
}
