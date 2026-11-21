package com.dsoft.screengrab.ui

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Tiny programmatic-UI toolkit (no XML layouts, no androidx) for ScreenGrab's screens. */
object Theme {
    val bg = Color.parseColor("#0E1020")
    val card = Color.parseColor("#1A1D33")
    val text = Color.parseColor("#F2F3FA")
    val secondary = Color.parseColor("#9AA0C0")
    val accent = Color.parseColor("#7C3AED")   // violet
    val accent2 = Color.parseColor("#EC4899")  // pink
    val green = Color.parseColor("#34C759")
    val red = Color.parseColor("#EF4444")
    val orange = Color.parseColor("#FF9F0A")
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

fun Context.tv(text: CharSequence, size: Float, color: Int, bold: Boolean = false): TextView =
    TextView(this).apply {
        this.text = text
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

fun Context.rounded(color: Int, radius: Int): GradientDrawable =
    GradientDrawable().apply { setColor(color); cornerRadius = radius.toFloat() }

fun Activity.page(title: String, subtitle: String? = null): LinearLayout {
    val scroll = ScrollView(this).apply { setBackgroundColor(Theme.bg); isFillViewport = true }
    val col = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(28), dp(20), dp(28))
    }
    col.addView(tv(title, 26f, Theme.text, bold = true))
    if (subtitle != null) col.addView(tv(subtitle, 13f, Theme.secondary).apply { setPadding(0, dp(4), 0, 0) })
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

/** Status row: coloured dot + label/sub + chip, with an optional fix button. */
fun LinearLayout.statusRow(label: String, sub: String, ok: Boolean, actionText: String?, onAction: (() -> Unit)?) {
    val ctx = context
    addCard {
        val top = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
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
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ctx.dp(10) })
        }
    }
}

/** A labelled on/off toggle row using a real Switch control. */
fun LinearLayout.switchRow(label: String, sub: String, value: Boolean, onChange: (Boolean) -> Unit) {
    val ctx = context
    addCard {
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(ctx.tv(label, 16f, Theme.text, bold = true))
            if (sub.isNotEmpty()) addView(ctx.tv(sub, 12.5f, Theme.secondary).apply { setPadding(0, ctx.dp(2), 0, 0) })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(styledSwitch(ctx, value, onChange))
        addView(row)
    }
}

/** A Switch tinted to the app theme. */
fun styledSwitch(ctx: Context, value: Boolean, onChange: (Boolean) -> Unit): android.widget.Switch =
    android.widget.Switch(ctx).apply {
        isChecked = value
        thumbTintList = android.content.res.ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(Theme.green, Color.parseColor("#CFD3E6"))
        )
        trackTintList = android.content.res.ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(Color.parseColor("#1F7A33"), Color.parseColor("#3A3E58"))
        )
        setOnCheckedChangeListener { _, b -> onChange(b) }
    }

/** A segmented control: a row of options; the selected one is highlighted. */
fun LinearLayout.segmented(label: String, options: List<Pair<String, String>>, selectedKey: String, onSelect: (String) -> Unit) {
    val ctx = context
    addCard {
        addView(ctx.tv(label, 16f, Theme.text, bold = true))
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, ctx.dp(10), 0, 0) }
        val chips = ArrayList<TextView>()
        var current = selectedKey
        options.forEach { (key, title) ->
            val chip = ctx.tv(title, 14f, if (key == current) Color.WHITE else Theme.secondary, bold = true).apply {
                gravity = Gravity.CENTER
                background = ctx.rounded(if (key == current) Theme.accent else ctx.run { Color.parseColor("#262A45") }, ctx.dp(12))
                setPadding(ctx.dp(10), ctx.dp(12), ctx.dp(10), ctx.dp(12))
            }
            chip.setOnClickListener {
                current = key
                chips.forEachIndexed { i, c ->
                    val k = options[i].first
                    c.setTextColor(if (k == current) Color.WHITE else Theme.secondary)
                    c.background = ctx.rounded(if (k == current) Theme.accent else Color.parseColor("#262A45"), ctx.dp(12))
                }
                onSelect(current)
            }
            chips.add(chip)
            row.addView(chip, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = ctx.dp(4); marginEnd = ctx.dp(4)
            })
        }
        addView(row)
    }
}
