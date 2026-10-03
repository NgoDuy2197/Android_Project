package com.dsoft.voicenote

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import kotlin.math.roundToInt

/** iOS system colors (light / dark). */
class Palette(val dark: Boolean) {
    val bg = if (dark) 0xFF000000.toInt() else 0xFFF2F2F7.toInt()
    val card = if (dark) 0xFF1C1C1E.toInt() else Color.WHITE
    val raised = if (dark) 0xFF2C2C2E.toInt() else Color.WHITE
    val text = if (dark) Color.WHITE else Color.BLACK
    val secondary = if (dark) 0xFF98989F.toInt() else 0xFF8E8E93.toInt()
    val separator = if (dark) 0xFF38383A.toInt() else 0xFFC6C6C8.toInt()
    val fill = if (dark) 0x3D767680 else 0x1F767680
    val blue = if (dark) 0xFF0A84FF.toInt() else 0xFF007AFF.toInt()
    val indigo = if (dark) 0xFF5E5CE6.toInt() else 0xFF5856D6.toInt()
    val red = if (dark) 0xFFFF453A.toInt() else 0xFFFF3B30.toInt()
    val pink = if (dark) 0xFFFF375F.toInt() else 0xFFFF2D55.toInt()
    val green = if (dark) 0xFF30D158.toInt() else 0xFF34C759.toInt()
    val ripple = if (dark) 0x33FFFFFF else 0x1A000000
}

fun Context.palette() = Palette(
    (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
)

fun Context.dp(v: Number): Int = (v.toFloat() * resources.displayMetrics.density).roundToInt()

fun rounded(color: Int, radius: Float) = GradientDrawable().apply {
    setColor(color)
    cornerRadius = radius
}

fun gradient(radius: Float, vararg colors: Int) =
    GradientDrawable(GradientDrawable.Orientation.TL_BR, colors).apply { cornerRadius = radius }

fun pressable(bg: Drawable?, rippleColor: Int): Drawable =
    RippleDrawable(ColorStateList.valueOf(rippleColor), bg, bg ?: ColorDrawable(Color.WHITE))

/** Clip children to the background's rounded outline (cards). */
fun View.clipRounded() {
    outlineProvider = ViewOutlineProvider.BACKGROUND
    clipToOutline = true
}

@Suppress("DEPRECATION")
fun Activity.setupSystemBars(p: Palette) {
    window.statusBarColor = p.bg
    window.navigationBarColor = p.bg
    var flags = window.decorView.systemUiVisibility
    if (!p.dark) {
        flags = flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        if (Build.VERSION.SDK_INT >= 26) flags = flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
    }
    window.decorView.systemUiVisibility = flags
}

fun Context.text(s: CharSequence, sp: Float, color: Int, weight: Int = 400): TextView = TextView(this).apply {
    text = s
    setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
    setTextColor(color)
    typeface = when {
        weight >= 700 -> Typeface.create("sans-serif", Typeface.BOLD)
        weight >= 500 -> Typeface.create("sans-serif-medium", Typeface.NORMAL)
        else -> Typeface.create("sans-serif", Typeface.NORMAL)
    }
    includeFontPadding = false
}

fun Context.icon(res: Int, tint: Int, sizeDp: Int): ImageView = ImageView(this).apply {
    setImageResource(res)
    imageTintList = ColorStateList.valueOf(tint)
    layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
}

/** Round translucent icon button, like the iOS toolbar "circle" buttons. */
fun Context.circleButton(p: Palette, res: Int, tint: Int = p.blue, sizeDp: Int = 40, onClick: () -> Unit) =
    ImageView(this).apply {
        setImageResource(res)
        imageTintList = ColorStateList.valueOf(tint)
        val pad = dp(sizeDp * 0.24f)
        setPadding(pad, pad, pad, pad)
        background = pressable(GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(p.fill) }, p.ripple)
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
        setOnClickListener { onClick() }
    }

fun Context.card(p: Palette): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    background = rounded(p.card, dp(14).toFloat())
    clipRounded()
}

fun Context.sectionHeader(p: Palette, s: String): TextView = text(s.uppercase(), 13f, p.secondary).apply {
    setPadding(dp(16), dp(22), dp(16), dp(7))
}

fun Context.footnote(p: Palette, s: String): TextView = text(s, 13f, p.secondary).apply {
    setPadding(dp(16), dp(7), dp(16), 0)
    setLineSpacing(0f, 1.15f)
}

fun Context.separator(p: Palette, insetDp: Int = 16): View = View(this).apply {
    setBackgroundColor(p.separator)
    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply {
        marginStart = dp(insetDp)
    }
}

/** A settings row: title on the left, any control on the right; tappable if [onClick]. */
fun Context.row(p: Palette, title: String, trailing: View? = null, onClick: (() -> Unit)? = null): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(48)
        setPadding(dp(16), dp(6), dp(12), dp(6))
        addView(text(title, 16f, p.text), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        trailing?.let { addView(it) }
        if (onClick != null) {
            background = pressable(null, p.ripple)
            setOnClickListener { onClick() }
        }
    }

/** Rows inside one card, with iOS inset separators. */
fun Context.group(p: Palette, vararg rows: View): LinearLayout = card(p).apply {
    rows.forEachIndexed { i, r ->
        if (i > 0) addView(separator(p))
        addView(r)
    }
}

fun Context.chevron(p: Palette): ImageView = icon(R.drawable.ic_chevron_right, p.separator, 20)

fun Context.iosSwitch(p: Palette, checked: Boolean, onChange: (Boolean) -> Unit) = Switch(this).apply {
    isChecked = checked
    val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
    thumbTintList = ColorStateList(states, intArrayOf(Color.WHITE, Color.WHITE))
    trackTintList = ColorStateList(states, intArrayOf(p.green, p.separator))
    setOnCheckedChangeListener { _, c -> onChange(c) }
}

/** Borderless right-aligned field that saves on Done / focus loss. */
fun Context.inlineField(p: Palette, value: String, numeric: Boolean = false, wide: Boolean = false, onSave: (String) -> Unit) =
    EditText(this).apply {
        setText(value)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setTextColor(p.secondary)
        background = null
        gravity = Gravity.END or Gravity.CENTER_VERTICAL
        setSingleLine(!wide)
        if (wide) maxLines = 3
        inputType = if (numeric) InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        imeOptions = EditorInfo.IME_ACTION_DONE
        setPadding(dp(8), dp(4), dp(4), dp(4))
        layoutParams = LinearLayout.LayoutParams(if (wide) dp(210) else dp(120), ViewGroup.LayoutParams.WRAP_CONTENT)
        var last = value
        val save = { val v = text.toString().trim(); if (v != last) { last = v; onSave(v) } }
        setOnFocusChangeListener { _, has -> if (!has) save() }
        setOnEditorActionListener { _, _, _ -> save(); clearFocus(); false }
    }

/** iOS segmented control. */
class Segmented(ctx: Context, private val p: Palette, items: List<String>, selected: Int, onSelect: (Int) -> Unit) :
    LinearLayout(ctx) {
    private val cells = ArrayList<TextView>()

    init {
        orientation = HORIZONTAL
        background = rounded(p.fill, ctx.dp(9).toFloat())
        val pad = ctx.dp(2)
        setPadding(pad, pad, pad, pad)
        items.forEachIndexed { i, s ->
            val cell = ctx.text(s, 13f, p.text, 500).apply {
                gravity = Gravity.CENTER
                setPadding(ctx.dp(12), ctx.dp(7), ctx.dp(12), ctx.dp(7))
                setOnClickListener { select(i); onSelect(i) }
            }
            cells += cell
            addView(cell, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        select(selected)
    }

    fun select(i: Int) {
        cells.forEachIndexed { j, c ->
            val on = j == i
            c.background = if (on) rounded(if (p.dark) 0xFF636366.toInt() else Color.WHITE, context.dp(7).toFloat()) else null
            c.elevation = if (on) context.dp(1).toFloat() else 0f
        }
    }
}

fun vlp(w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT) =
    LinearLayout.LayoutParams(w, h)
