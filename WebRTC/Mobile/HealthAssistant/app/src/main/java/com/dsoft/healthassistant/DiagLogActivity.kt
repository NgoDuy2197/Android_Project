package com.dsoft.healthassistant

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/** Shows the in-app AI diagnostic log (live) so the user can see where offline/online stalls or fails. */
class DiagLogActivity : Activity() {
    private lateinit var p: Palette
    private lateinit var logView: TextView
    private val onLog = { runOnUiThread { refresh() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        p = palette()
        val col = page(p, tr("Nhật ký chẩn đoán", "Diagnostic log"))

        col.addView(text(
            tr("Mỗi lần “Xem xét” sẽ ghi lại từng bước (nạp model, sinh phản hồi, lỗi…) để biết AI đang ở đâu hoặc đứng ở bước nào.",
                "Each “Review” logs every step (model load, response, errors…) so you can see where the AI is or where it stalls."),
            14f, p.secondary).apply { setPadding(dp(4), 0, dp(4), dp(10)); setLineSpacing(0f, 1.15f) })

        logView = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.MONOSPACE
            setTextColor(p.text)
            setTextIsSelectable(true)
            setLineSpacing(0f, 1.1f)
        }
        col.addView(card(p).apply {
            setPadding(dp(12), dp(12), dp(12), dp(12)); addView(logView)
        })

        col.addView(bigIconButton(tr("Sao chép nhật ký", "Copy log"), R.drawable.ic_check, p.indigo, p.blue) {
            copyText(DiagLog.text().ifBlank { "(empty)" })
        }, vlp().apply { topMargin = dp(14) })
        col.addView(bigIconButton(tr("Xoá nhật ký", "Clear log"), R.drawable.ic_delete, p.red, p.pink) {
            DiagLog.clear(); refresh()
        }, vlp().apply { topMargin = dp(10) })

        refresh()
    }

    override fun onResume() { super.onResume(); DiagLog.addListener(onLog); refresh() }
    override fun onPause() { super.onPause(); DiagLog.removeListener(onLog) }

    private fun refresh() {
        val lines = DiagLog.snapshot()
        if (lines.isEmpty()) {
            logView.text = tr("(Chưa có nhật ký. Bấm “Xem xét” một bữa để bắt đầu ghi.)",
                "(No log yet. Tap “Review” on a meal to start logging.)")
            logView.setTextColor(p.secondary)
            return
        }
        val sb = android.text.SpannableStringBuilder()
        lines.forEach { ln ->
            val start = sb.length
            sb.append(ln.format()).append("\n")
            val color = when (ln.level) { DiagLog.E -> p.red; DiagLog.W -> p.orange; else -> p.text }
            sb.setSpan(android.text.style.ForegroundColorSpan(color), start, sb.length,
                android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        logView.text = sb
    }
}
