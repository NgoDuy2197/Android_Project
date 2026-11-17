package com.dsoft.remotelink.ui

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Tiny programmatic-UI toolkit (no XML layouts, no androidx) for RemoteLink's few screens. */
object Theme {
    val bg = Color.parseColor("#0E1020")
    val card = Color.parseColor("#1A1D33")
    val text = Color.parseColor("#F2F3FA")
    val secondary = Color.parseColor("#9AA0C0")
    val accent = Color.parseColor("#6366F1")
    val accent2 = Color.parseColor("#06B6D4")
    val green = Color.parseColor("#34C759")
    val red = Color.parseColor("#FF453A")
    val orange = Color.parseColor("#FF9F0A")
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

fun Context.tv(text: CharSequence, size: Float, color: Int, bold: Boolean = false): TextView =
    TextView(this).apply {
        this.text = text
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

fun Context.rounded(color: Int, radius: Int): GradientDrawable =
    GradientDrawable().apply { setColor(color); cornerRadius = radius.toFloat() }

fun Activity.page(title: String): LinearLayout {
    val scroll = ScrollView(this).apply { setBackgroundColor(Theme.bg); isFillViewport = true }
    val col = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(28), dp(20), dp(28))
    }
    col.addView(tv(title, 26f, Theme.text, bold = true))
    scroll.addView(col, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    setContentView(scroll)
    return col
}

fun Context.card(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    background = rounded(Theme.card, dp(16))
    setPadding(dp(16), dp(14), dp(16), dp(14))
}

fun LinearLayout.addCard(block: LinearLayout.() -> Unit) {
    val c = context.card().apply(block)
    addView(c, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        topMargin = context.dp(12)
    })
}

fun Context.filledButton(label: String, color: Int = Theme.accent, onClick: () -> Unit): Button =
    Button(this).apply {
        text = label
        isAllCaps = false
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        background = rounded(color, dp(14))
        setPadding(dp(16), dp(14), dp(16), dp(14))
        stateListAnimator = null
        setOnClickListener { onClick() }
    }

fun Context.sectionHeader(text: String): TextView =
    tv(text, 13f, Theme.secondary, bold = true).apply {
        setPadding(dp(4), dp(18), dp(4), dp(6))
        letterSpacing = 0.04f
    }

/** A row: label on the left, a coloured status chip on the right, plus an optional fix button below. */
fun LinearLayout.statusRow(label: String, sub: String, ok: Boolean, actionText: String?, onAction: (() -> Unit)?) {
    val ctx = context
    addCard {
        val top = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(View(ctx).apply {
            background = ctx.rounded(if (ok) Theme.green else Theme.orange, ctx.dp(5))
            layoutParams = LinearLayout.LayoutParams(ctx.dp(10), ctx.dp(10)).apply { marginEnd = ctx.dp(10) }
        })
        top.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(ctx.tv(label, 16f, Theme.text, bold = true))
            if (sub.isNotEmpty()) addView(ctx.tv(sub, 12.5f, Theme.secondary).apply { setPadding(0, ctx.dp(2), 0, 0) })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(ctx.tv(if (ok) "OK" else "Cần bật", 13f, if (ok) Theme.green else Theme.orange, bold = true))
        addView(top)
        if (!ok && actionText != null && onAction != null) {
            addView(ctx.filledButton(actionText, Theme.accent) { onAction() },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = ctx.dp(10)
                })
        }
    }
}
