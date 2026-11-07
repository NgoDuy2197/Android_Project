package com.dsoft.toolbox

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Full-screen overlay shown by [ScreenTranslateService] in "copy" mode: the recognized text from a
 * screenshot, with normal selection plus one-tap copy per line and copy-all. Tap outside / ✕ closes.
 */
class CopyOverlay(ctx: Context, private val onClose: () -> Unit) : FrameLayout(ctx) {
    private val p = ctx.palette()
    private val body: LinearLayout
    private val status: TextView

    init {
        setBackgroundColor(0xBB000000.toInt())
        setOnClickListener { onClose() } // tap the dim area to close
        isFocusableInTouchMode = true

        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = gradient(context.dp(18).toFloat(), p.bg, p.bg)
            setOnClickListener { } // swallow taps inside the card
        }

        // header
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(context.dp(16), context.dp(12), context.dp(10), context.dp(8))
        }
        header.addView(ctx.text("Chép chữ từ màn hình", 18f, p.text, 700),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(ctx.text("✕", 20f, p.secondary, 700).apply {
            setPadding(context.dp(12), context.dp(6), context.dp(12), context.dp(6))
            setOnClickListener { onClose() }
        })
        card.addView(header)

        status = ctx.text("Đang nhận dạng chữ…", 14f, p.secondary).apply {
            setPadding(context.dp(16), 0, context.dp(16), context.dp(8))
        }
        card.addView(status)

        body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(context.dp(12), 0, context.dp(12), context.dp(16)) }
        card.addView(ScrollView(ctx).apply { addView(body) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        addView(card, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
            val m = context.dp(16); setMargins(m, context.dp(48), m, context.dp(32))
        })
    }

    override fun dispatchKeyEvent(e: android.view.KeyEvent): Boolean {
        if (e.keyCode == android.view.KeyEvent.KEYCODE_BACK && e.action == android.view.KeyEvent.ACTION_UP) { onClose(); return true }
        return super.dispatchKeyEvent(e)
    }

    fun setStatus(s: String) { status.text = s }

    fun setResult(full: String, lines: List<String>) {
        body.removeAllViews()
        val ctx = context
        if (full.isEmpty()) { status.text = "Không tìm thấy chữ nào (kiểm tra ngôn ngữ gốc trong Cài đặt dịch)."; return }
        status.text = "Bôi đen để copy, hoặc chạm 1 dòng:"

        val box = ctx.text(full, 15f, p.text).apply {
            setTextIsSelectable(true); setPadding(ctx.dp(14), ctx.dp(12), ctx.dp(14), ctx.dp(12))
        }
        body.addView(ctx.card(p).apply { addView(box) }, lp())
        body.addView(ctx.group(p, ctx.row(p, "Copy toàn bộ", ctx.text("⧉", 18f, p.blue)) { ctx.copyText(full); onClose() }))

        if (lines.isNotEmpty()) {
            body.addView(ctx.sectionHeader(p, "Chép nhanh từng dòng"))
            body.addView(ctx.group(p, *lines.map { line ->
                ctx.row(p, line, ctx.text("Copy", 13f, p.blue)) { ctx.copyText(line) }
            }.toTypedArray()))
        }
    }

    private fun lp() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        .apply { topMargin = context.dp(6) }
}
