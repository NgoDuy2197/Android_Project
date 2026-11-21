package com.dsoft.proxyspy

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import com.dsoft.proxyspy.Wire
import org.json.JSONObject

/**
 * The breakpoint editor: one call is frozen inside the app right now, and whatever is on this
 * screen when "Gửi tiếp" is tapped is what the app will actually send or receive.
 *
 * The app thread stays blocked while this is open (the hook waits up to two minutes), so leaving
 * without answering is not free — "Cho đi tiếp" is always one tap away.
 */
class BreakActivity : Activity() {

    private lateinit var p: Palette
    private var askId: Long = -1
    private var stage: String = ""

    private var fUrl: EditText? = null
    private var fStatus: EditText? = null
    private var fHeaders: EditText? = null
    private lateinit var fBody: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        p = palette()
        window.statusBarColor = p.card
        askId = intent.getLongExtra("askId", -1)
        val pend = Hub.pending[askId]
        if (pend == null) {
            toast("Call này đã đi tiếp rồi")
            finish()
            return
        }
        val f = pend.frame
        stage = pend.stage

        val root = col().apply {
            setBackgroundColor(p.bg)
            setPadding(dp(14), dp(14), dp(14), dp(28))
        }

        val title = when (stage) {
            Wire.ST_REQ -> "Dừng REQUEST"
            Wire.ST_RES -> "Dừng RESPONSE"
            Wire.ST_WS_OUT -> "Dừng frame GỬI"
            else -> "Dừng frame NHẬN"
        }
        root.addView(text(title, 22f, p.orange, 600))
        root.addView(
            text(
                pend.conn.label + " · " + f.optString("method", "WS") + " " + pend.url,
                12f, p.sec,
            ),
            vlp().apply { topMargin = dp(4) },
        )

        val box = card(p).apply { }
        if (stage == Wire.ST_REQ) {
            fUrl = input(p, "URL", f.optString("url"))
            box.field(p, "URL gửi đi", fUrl!!)
        }
        if (stage == Wire.ST_RES) {
            fStatus = input(p, "200", f.optInt("status", 200).toString())
            box.field(p, "Status code", fStatus!!)
        }
        if (stage == Wire.ST_REQ || stage == Wire.ST_RES) {
            val hs = Wire.headerList(f.optJSONArray("headers"))
                .joinToString("\n") { it.first + ": " + it.second }
            fHeaders = input(p, "Key: value mỗi dòng", hs, lines = 4)
            box.field(p, "Headers", fHeaders!!)
        }

        val bodyObj = f.optJSONObject("body")
        val bodyText = bodyObj?.optString("text") ?: ""
        val binary = bodyObj != null && bodyObj.has("b64")
        fBody = input(p, "(trống)", prettify(bodyText), lines = 8)
        if (binary) fBody.isEnabled = false
        box.field(p, if (binary) "Body (nhị phân — không sửa được ở đây)" else "Body", fBody)
        root.addView(box, vlp().apply { topMargin = dp(12) })

        val bar = row().apply { setPadding(0, dp(14), 0, 0) }
        bar.addView(pill("Gửi tiếp", p.green, Color.WHITE) { send(edited = true) }, wlp())
        bar.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(8), 1) })
        bar.addView(pill("Nguyên bản", p.fill, p.blue) { send(edited = false) }, wlp())
        bar.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(8), 1) })
        bar.addView(pill("Bỏ", p.fill, p.red) { drop() }, wlp())
        root.addView(bar, vlp())

        root.addView(
            text(
                "App đang bị treo ở call này cho tới khi bạn bấm. Quá 2 phút không trả lời thì " +
                    "call tự đi tiếp nguyên bản.",
                11f, p.sec,
            ),
            vlp().apply { topMargin = dp(12) },
        )

        setContentView(scroll(root))
    }

    private fun prettify(s: String): String {
        val t = s.trim()
        return runCatching {
            when {
                t.startsWith("{") -> JSONObject(t).toString(2)
                t.startsWith("[") -> org.json.JSONArray(t).toString(2)
                else -> s
            }
        }.getOrDefault(s)
    }

    private fun send(edited: Boolean) {
        val v = JSONObject().put("act", Wire.ACT_GO)
        if (edited) {
            fUrl?.let { v.put("url", it.text.toString().trim()) }
            fStatus?.let { e -> e.text.toString().trim().toIntOrNull()?.let { v.put("status", it) } }
            fHeaders?.let { v.put("headers", Wire.headers(Wire.parseHeaderLines(it.text.toString()))) }
            if (fBody.isEnabled) {
                val text = fBody.text.toString()
                v.put("body", JSONObject().put("text", text).put("size", text.toByteArray().size))
            }
        }
        Hub.answer(askId, v)
        finish()
    }

    private fun drop() {
        Hub.answer(askId, JSONObject().put("act", Wire.ACT_DROP))
        finish()
    }
}
