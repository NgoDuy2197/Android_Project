package com.dsoft.proxyspy

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

class Palette(val dark: Boolean) {
    val bg = if (dark) 0xFF0B0B0E.toInt() else 0xFFF2F2F7.toInt()
    val card = if (dark) 0xFF1C1C1E.toInt() else Color.WHITE
    val text = if (dark) 0xFFF2F2F7.toInt() else 0xFF1C1C1E.toInt()
    val sec = if (dark) 0xFF98989F.toInt() else 0xFF8E8E93.toInt()
    val sep = if (dark) 0xFF38383A.toInt() else 0xFFD8D8DD.toInt()
    val blue = if (dark) 0xFF0A84FF.toInt() else 0xFF007AFF.toInt()
    val green = if (dark) 0xFF30D158.toInt() else 0xFF34C759.toInt()
    val red = if (dark) 0xFFFF453A.toInt() else 0xFFFF3B30.toInt()
    val orange = if (dark) 0xFFFF9F0A.toInt() else 0xFFFF9500.toInt()
    val purple = if (dark) 0xFFBF5AF2.toInt() else 0xFFAF52DE.toInt()
    val fill = if (dark) 0x33767680 else 0x1F767680
    val ripple = if (dark) 0x33FFFFFF else 0x1A000000
}

fun Context.palette() = Palette(
    (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
)

fun Context.dp(v: Number): Int = (v.toFloat() * resources.displayMetrics.density).roundToInt()

fun rounded(color: Int, radius: Float) = GradientDrawable().apply { setColor(color); cornerRadius = radius }

fun Context.text(s: CharSequence, sp: Float, color: Int, weight: Int = 400): TextView = TextView(this).apply {
    text = s
    setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
    setTextColor(color)
    typeface = if (weight >= 600) Typeface.create("sans-serif-medium", Typeface.NORMAL)
    else Typeface.create("sans-serif", Typeface.NORMAL)
    includeFontPadding = false
}

fun Context.card(p: Palette): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    background = rounded(p.card, dp(14).toFloat())
}

fun Context.pill(label: String, bg: Int, fg: Int, onClick: () -> Unit): TextView =
    text(label, 14f, fg, 600).apply {
        gravity = Gravity.CENTER
        setPadding(dp(14), dp(9), dp(14), dp(9))
        background = rounded(bg, dp(12).toFloat())
        isClickable = true
        setOnClickListener { onClick() }
    }

fun Context.vlp(h: Int = LinearLayout.LayoutParams.WRAP_CONTENT) =
    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, h)

fun methodColor(p: Palette, m: String): Int = when (m.uppercase()) {
    "GET" -> p.green; "POST" -> p.orange; "PUT" -> p.purple
    "DELETE" -> p.red; "PATCH" -> p.blue; else -> p.sec
}

fun statusColor(p: Palette, code: Int): Int = when {
    code < 0 -> p.sec
    code in 200..299 -> p.green
    code in 300..399 -> p.blue
    code in 400..499 -> p.orange
    else -> p.red
}

fun fmtSize(n: Int): String = when {
    n <= 0 -> ""
    n < 1024 -> "$n B"
    n < 1048576 -> String.format("%.1f KB", n / 1024f)
    else -> String.format("%.2f MB", n / 1048576f)
}

fun View.pad(l: Int, t: Int, r: Int, b: Int) = setPadding(l, t, r, b)
