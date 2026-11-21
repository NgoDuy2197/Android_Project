package com.dsoft.proxyspy

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.text.InputType
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ScrollView
import android.widget.Switch
import android.widget.Toast
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
    setPadding(dp(14), dp(12), dp(14), dp(12))
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

// ===================== helpers dùng chung cho các màn của chế độ tự soi =====================

fun Context.row(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

fun Context.col(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

fun Context.wlp(weight: Float = 1f) =
    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight)

fun Context.gap(w: Int = 8): View =
    View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(w), 1) }

fun Context.mono(s: CharSequence, sp: Float, color: Int): TextView = TextView(this).apply {
    text = s
    setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
    setTextColor(color)
    typeface = Typeface.MONOSPACE
    setTextIsSelectable(true)
}

fun Context.input(p: Palette, hint: String, value: String, lines: Int = 1): EditText = EditText(this).apply {
    setText(value)
    this.hint = hint
    setHintTextColor(p.sec)
    setTextColor(p.text)
    setTextSize(TypedValue.COMPLEX_UNIT_SP, if (lines > 1) 13f else 15f)
    background = rounded(p.fill, dp(10).toFloat())
    setPadding(dp(12), dp(10), dp(12), dp(10))
    if (lines > 1) {
        typeface = Typeface.MONOSPACE
        minLines = lines
        maxLines = lines * 3
        gravity = Gravity.TOP or Gravity.START
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
    } else {
        inputType = InputType.TYPE_CLASS_TEXT
        maxLines = 1
    }
}

/** Nhãn nhỏ phía trên một ô nhập. */
fun LinearLayout.field(p: Palette, label: String, editor: View) {
    val c = context
    addView(c.text(label, 12f, p.sec, 600), c.vlp().apply { topMargin = c.dp(10) })
    addView(editor, c.vlp().apply { topMargin = c.dp(4) })
}

@Suppress("DEPRECATION")
fun Context.switchRow(p: Palette, label: String, checked: Boolean, onChange: (Boolean) -> Unit): View {
    val r = row().apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(8), 0, dp(8))
    }
    r.addView(text(label, 15f, p.text), wlp())
    r.addView(Switch(this).apply {
        isChecked = checked
        setOnCheckedChangeListener { _, v -> onChange(v) }
    })
    return r
}

fun Context.scroll(child: View): ScrollView = ScrollView(this).apply {
    addView(
        child,
        ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    )
    isFillViewport = true
}

fun Activity.toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

fun Activity.copyToClipboard(label: String, value: String) {
    getSystemService(android.content.ClipboardManager::class.java)
        .setPrimaryClip(android.content.ClipData.newPlainText(label, value))
    toast("Đã copy")
}

fun fmtTime(ms: Long): String = android.text.format.DateFormat.format("HH:mm:ss", ms).toString()
