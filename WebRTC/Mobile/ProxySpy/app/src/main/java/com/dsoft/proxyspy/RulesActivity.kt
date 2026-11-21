package com.dsoft.proxyspy

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast

/**
 * Editor for rewrite rules (mock / modify request / modify response).
 *
 * One rule list drives both capture modes. The common fields are always visible; the extras that
 * only the in-process hook can honour (breakpoints, WebSocket frames) live behind "Nâng cao", so
 * the proxy-only workflow stays as short as it was.
 */
class RulesActivity : Activity() {
    private lateinit var p: Palette
    private lateinit var list: LinearLayout
    private val cards = ArrayList<Card>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        p = palette()
        window.statusBarColor = p.card
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(p.bg) }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(p.card); setPadding(dp(16), dp(14), dp(16), dp(10))
        }
        header.addView(text("Rules — chặn & sửa request/response", 19f, p.text, 600))
        header.addView(text("Bấm vào đây để xem ⓘ ghi chú · match URL → mock / sửa response / sửa payload.",
            12f, p.blue).apply { setPadding(0, dp(3), 0, dp(2)); setOnClickListener { showNotes() } })
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(10), 0, 0) }
        bar.addView(pill("ⓘ Ghi chú", p.fill, p.blue) { showNotes() }, flex())
        bar.addView(pill("+ Thêm", p.fill, p.blue) { addCard(Rule(name = "rule mới")); scrollBottom() }, flex())
        bar.addView(pill("Lưu & áp dụng", p.blue, Color.WHITE) { save() }, flex())
        header.addView(bar)
        root.addView(header)

        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(24)) }
        val sv = ScrollView(this).apply { setBackgroundColor(p.bg); addView(list) }
        root.addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        RuleStore.all().forEach { addCard(it) }
        if (cards.isEmpty()) addCard(Rule(name = "ví dụ: login", match = "/login", mock = true,
            status = "200", body = "{\"success\":true,\"token\":\"FAKE\"}"))
    }

    private fun showNotes() {
        val notes = """
            GIẢ LẬP (MOCK) KẾT QUẢ API
            • Tích ô "mock" → trả thẳng body, KHÔNG gọi server thật.
            • Điền: status (vd 200), content-type, body (vd {"success":true}).
            • Rule mock BỎ QUA phần sửa response/payload của chính nó (vì không forward).

            SỬA RESPONSE THẬT (không tích mock)
            • status: ghi đè mã trả về.
            • body: thay TOÀN BỘ body trả về.
            • find → replace: thay chuỗi trong body (chỉ khi body để trống).
            • merge JSON (nâng cao): giữ body gốc, chỉ đè các property nhập vào.

            SỬA PAYLOAD GỬI LÊN (request)
            • payload thay thế: đè TOÀN BỘ payload.
            • merge JSON: GIỮ payload gốc, chỉ ĐÈ các property trong JSON nhập (object lồng nhau merge đệ quy). JSON sai cú pháp → bỏ qua, không phá request.
            • find → replace: thay chuỗi trong payload.
            • Thứ tự trong 1 rule: thay toàn bộ HOẶC find→replace, rồi merge chồng lên.

            NÂNG CAO (dùng được ở cả 2 chế độ)
            • đổi URL: bẻ call sang server khác, vd api.prod.com → api.staging.com.
            • header gửi lên / trả về: "Key: value" mỗi dòng, ghi đè header cùng tên.
            • trễ (ms): giữ call lại để xem spinner / timeout.
            • ép lỗi: ném lỗi mạng với thông báo của bạn.

            CHỈ CHẾ ĐỘ TỰ SOI (hook)
            • dừng req / dừng res: đóng băng call để sửa tay trên điện thoại.
            • WS: rule áp cho frame WebSocket (chọn chiều gửi/nhận, hoặc bỏ hẳn frame).

            MATCH URL
            • Để trống = khớp MỌI URL.
            • Chuỗi con (vd /login) hoặc re:regex.
            • method để trống = mọi method.

            CHUNG
            • Nhiều rule khớp sẽ áp dụng lần lượt (trừ khi 1 rule mock trả thẳng).
            • Nhớ bấm "Lưu & áp dụng" sau khi sửa — rule được đẩy xuống app đang hook ngay.
        """.trimIndent()
        AlertDialog.Builder(this)
            .setTitle("Lưu ý khi dùng Rules")
            .setMessage(notes)
            .setPositiveButton("Đã hiểu", null)
            .show()
    }

    private val svRef get() = (list.parent as ScrollView)
    private fun scrollBottom() = list.post { svRef.fullScroll(ScrollView.FOCUS_DOWN) }
    private fun flex() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        .apply { marginStart = dp(5); marginEnd = dp(5) }

    private fun field(hint: String, value: String, numeric: Boolean = false): EditText = EditText(this).apply {
        setText(value); this.hint = hint; setTextColor(p.text); setHintTextColor(p.sec); textSize = 13f
        inputType = if (numeric) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_CLASS_TEXT
        background = rounded(p.bg, dp(8).toFloat()); setPadding(dp(10), dp(8), dp(10), dp(8))
    }

    private fun multi(hint: String, value: String): EditText = field(hint, value).apply {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        minLines = 2; gravity = Gravity.TOP
    }

    private inner class Card(r: Rule) {
        /** Fields with no widget (and anything added later) survive a save through this copy. */
        private val base: Rule = r.copy()

        val enabled = CheckBox(this@RulesActivity).apply { isChecked = r.enabled; setTextColor(p.text) }
        val name = field("tên rule", r.name)
        val match = field("match URL (vd /login)", r.match)
        val method = field("method", r.method)
        val mock = CheckBox(this@RulesActivity).apply { text = "mock"; isChecked = r.mock; setTextColor(p.text) }
        val status = field("status (vd 200)", r.status, true)
        val ct = field("content-type (mock)", r.contentType)
        val body = multi("body thay thế / mock (trống nếu find→replace)", r.body)
        val find = field("find trong body", r.find)
        val replace = field("replace", r.replace)

        // --- sửa payload REQUEST ---
        val reqBody = multi("payload thay thế toàn bộ (trống nếu dùng merge)", r.reqBody)
        val reqMerge = multi("merge JSON vào payload gốc (vd {\"debug\":true})", r.reqMerge)
        val reqFind = field("find trong payload", r.reqFind)
        val reqReplace = field("replace", r.reqReplace)

        // --- nâng cao ---
        val urlFind = field("đổi URL: tìm (hoặc re:regex)", r.urlFind)
        val urlReplace = field("thay bằng", r.urlReplace)
        val reqHeaders = multi("header gửi lên — Key: value mỗi dòng", r.reqHeaders)
        val resHeaders = multi("header trả về — Key: value mỗi dòng", r.resHeaders)
        val resMerge = multi("merge JSON vào body trả về", r.resMerge)
        val delay = field("trễ (ms)", if (r.delayMs > 0) r.delayMs.toString() else "", true)
        val failWith = field("ép lỗi mạng (thông báo)", r.failWith)
        val wsScope = CheckBox(this@RulesActivity).apply {
            text = "WS"; isChecked = r.scope == Rule.SCOPE_WS; setTextColor(p.text)
        }
        val brkReq = CheckBox(this@RulesActivity).apply {
            text = "dừng req"; isChecked = r.brkReq; setTextColor(p.text)
        }
        val brkRes = CheckBox(this@RulesActivity).apply {
            text = "dừng res"; isChecked = r.brkRes; setTextColor(p.text)
        }
        val wsOut = CheckBox(this@RulesActivity).apply {
            text = "frame gửi"; isChecked = r.wsOut; setTextColor(p.text)
        }
        val wsIn = CheckBox(this@RulesActivity).apply {
            text = "frame nhận"; isChecked = r.wsIn; setTextColor(p.text)
        }
        val wsDrop = CheckBox(this@RulesActivity).apply {
            text = "bỏ frame"; isChecked = r.wsDrop; setTextColor(p.text)
        }
        val wsBody = multi("frame thay thế toàn bộ", r.wsBody)
        val wsMerge = multi("merge JSON vào frame", r.wsMerge)
        val wsFind = field("find trong frame", r.wsFind)
        val wsReplace = field("replace", r.wsReplace)

        val view: LinearLayout
        private val advanced: LinearLayout

        init {
            fun row(vararg v: View) = LinearLayout(this@RulesActivity).apply {
                orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(5), 0, 0)
                v.forEach { addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginStart = dp(3); marginEnd = dp(3) }) }
            }
            fun wide(v: View) = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(5) }
            fun label(s: String) = text(s, 11f, p.sec).apply { setPadding(0, dp(9), 0, 0) }

            advanced = LinearLayout(this@RulesActivity).apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
                addView(label("── đổi URL / header (cả 2 chế độ) ──"))
                addView(row(urlFind, urlReplace))
                addView(reqHeaders, wide(reqHeaders))
                addView(resHeaders, wide(resHeaders))
                addView(resMerge, wide(resMerge))
                addView(row(delay, failWith))
                addView(label("── chỉ chế độ tự soi (hook) ──"))
                addView(row(wsScope, brkReq, brkRes))
                addView(row(wsOut, wsIn, wsDrop))
                addView(wsBody, wide(wsBody))
                addView(wsMerge, wide(wsMerge))
                addView(row(wsFind, wsReplace))
            }

            val del = pill("Xoá", p.fill, p.red) { remove(this) }
            val more = pill("Nâng cao ▾", p.fill, p.blue) {
                advanced.visibility = if (advanced.visibility == View.GONE) View.VISIBLE else View.GONE
            }
            view = LinearLayout(this@RulesActivity).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(p.card, dp(12).toFloat())
                setPadding(dp(12), dp(10), dp(12), dp(12))
                addView(row(enabled.apply { text = "bật" }, name, del))
                addView(row(match, method, mock))
                addView(row(status, ct))
                addView(body, wide(body))
                addView(row(find, replace))
                addView(label("── sửa payload gửi lên (request) ──"))
                addView(reqBody, wide(reqBody))
                addView(reqMerge, wide(reqMerge))
                addView(row(reqFind, reqReplace))
                addView(more, wide(more))
                addView(advanced, wide(advanced))
            }
        }

        fun toRule(): Rule = base.apply {
            enabled = this@Card.enabled.isChecked
            name = this@Card.name.text.toString()
            match = this@Card.match.text.toString()
            method = this@Card.method.text.toString()
            mock = this@Card.mock.isChecked
            status = this@Card.status.text.toString()
            contentType = ct.text.toString().ifBlank { "application/json; charset=utf-8" }
            body = this@Card.body.text.toString()
            find = this@Card.find.text.toString()
            replace = this@Card.replace.text.toString()
            reqBody = this@Card.reqBody.text.toString()
            reqMerge = this@Card.reqMerge.text.toString()
            reqFind = this@Card.reqFind.text.toString()
            reqReplace = this@Card.reqReplace.text.toString()
            scope = if (wsScope.isChecked) Rule.SCOPE_WS else Rule.SCOPE_HTTP
            brkReq = this@Card.brkReq.isChecked
            brkRes = this@Card.brkRes.isChecked
            urlFind = this@Card.urlFind.text.toString()
            urlReplace = this@Card.urlReplace.text.toString()
            reqHeaders = this@Card.reqHeaders.text.toString()
            resHeaders = this@Card.resHeaders.text.toString()
            resMerge = this@Card.resMerge.text.toString()
            delayMs = delay.text.toString().trim().toIntOrNull() ?: 0
            failWith = this@Card.failWith.text.toString()
            wsOut = this@Card.wsOut.isChecked
            wsIn = this@Card.wsIn.isChecked
            wsDrop = this@Card.wsDrop.isChecked
            wsBody = this@Card.wsBody.text.toString()
            wsMerge = this@Card.wsMerge.text.toString()
            wsFind = this@Card.wsFind.text.toString()
            wsReplace = this@Card.wsReplace.text.toString()
        }
    }

    private fun addCard(r: Rule) {
        val c = Card(r)
        cards.add(c)
        list.addView(c.view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(8) })
    }

    private fun remove(c: Card) {
        cards.remove(c); list.removeView(c.view)
    }

    private fun save() {
        RuleStore.setAll(cards.map { it.toRule() })
        Toast.makeText(this, "Đã lưu ${cards.size} rule", Toast.LENGTH_SHORT).show()
    }
}
