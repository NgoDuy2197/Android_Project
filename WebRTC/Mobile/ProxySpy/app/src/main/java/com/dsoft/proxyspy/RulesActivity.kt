package com.dsoft.proxyspy

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast

/** Editor for rewrite rules (mock / modify response), mirroring the desktop dashboard. */
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
        header.addView(text("Rules — chặn & sửa response", 19f, p.text, 600))
        header.addView(text("match URL (vd /login) → mock trả thẳng, hoặc đổi status / body / find→replace.",
            12f, p.sec).apply { setPadding(0, dp(3), 0, 0) })
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(10), 0, 0) }
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

    private val svRef get() = (list.parent as ScrollView)
    private fun scrollBottom() = list.post { svRef.fullScroll(ScrollView.FOCUS_DOWN) }
    private fun flex() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        .apply { marginStart = dp(5); marginEnd = dp(5) }

    private fun field(hint: String, value: String, numeric: Boolean = false): EditText = EditText(this).apply {
        setText(value); this.hint = hint; setTextColor(p.text); setHintTextColor(p.sec); textSize = 13f
        inputType = if (numeric) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_CLASS_TEXT
        background = rounded(p.bg, dp(8).toFloat()); setPadding(dp(10), dp(8), dp(10), dp(8))
    }

    private inner class Card(r: Rule) {
        val enabled = CheckBox(this@RulesActivity).apply { isChecked = r.enabled; setTextColor(p.text) }
        val name = field("tên rule", r.name)
        val match = field("match URL (vd /login)", r.match)
        val method = field("method", r.method)
        val mock = CheckBox(this@RulesActivity).apply { text = "mock"; isChecked = r.mock; setTextColor(p.text) }
        val status = field("status (vd 200)", r.status, true)
        val ct = field("content-type (mock)", r.contentType)
        val body = field("body thay thế / mock (trống nếu find→replace)", r.body).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE; minLines = 2; gravity = Gravity.TOP
        }
        val find = field("find trong body", r.find)
        val replace = field("replace", r.replace)
        val view: LinearLayout

        init {
            fun row(vararg v: android.view.View) = LinearLayout(this@RulesActivity).apply {
                orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(5), 0, 0)
                v.forEach { addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginStart = dp(3); marginEnd = dp(3) }) }
            }
            val del = pill("Xoá", p.fill, p.red) { remove(this) }
            view = LinearLayout(this@RulesActivity).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(p.card, dp(12).toFloat())
                setPadding(dp(12), dp(10), dp(12), dp(12))
                addView(row(enabled.apply { text = "bật" }, name, del))
                addView(row(match, method, mock))
                addView(row(status, ct))
                addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = dp(5) })
                addView(row(find, replace))
            }
        }

        fun toRule() = Rule(enabled.isChecked, name.text.toString(), match.text.toString(), method.text.toString(),
            mock.isChecked, status.text.toString(), ct.text.toString().ifBlank { "application/json; charset=utf-8" },
            body.text.toString(), find.text.toString(), replace.text.toString())
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
