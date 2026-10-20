package com.dsoft.bookshelf

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
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
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import kotlin.math.roundToInt

/** App theme modes. */
object ThemeMode {
    const val SYSTEM = 0   // retired (migrated to LIGHT); kept so old stored values stay valid
    const val LIGHT = 1
    const val DARK = 2
    const val SEPIA = 3
    const val PASTEL = 4
    const val KIDS = 5
}

/** iOS-like system colors for light / dark / sepia / pastel, plus a cheerful "kids" theme. */
class Palette(val dark: Boolean, val sepia: Boolean = false, val pastel: Boolean = false, val kids: Boolean = false) {
    val bg = when { dark -> 0xFF000000.toInt(); sepia -> 0xFFF3EAD7.toInt(); pastel -> 0xFFF4EFFB.toInt(); kids -> 0xFFFFF4E3.toInt(); else -> 0xFFF2F2F7.toInt() }
    val card = when { dark -> 0xFF1C1C1E.toInt(); sepia -> 0xFFFBF4E4.toInt(); pastel -> 0xFFFFFFFF.toInt(); kids -> 0xFFFFFFFF.toInt(); else -> Color.WHITE }
    val raised = when { dark -> 0xFF2C2C2E.toInt(); sepia -> 0xFFFFF9EC.toInt(); pastel -> 0xFFFFFFFF.toInt(); kids -> 0xFFFFFFFF.toInt(); else -> Color.WHITE }
    val text = when { dark -> 0xFFF2F2F7.toInt(); sepia -> 0xFF3B2F1E.toInt(); pastel -> 0xFF4A4160.toInt(); kids -> 0xFF3D4A5C.toInt(); else -> Color.BLACK }
    val secondary = when { dark -> 0xFF98989F.toInt(); sepia -> 0xFF8A7656.toInt(); pastel -> 0xFF9E92B8.toInt(); kids -> 0xFF93A0B2.toInt(); else -> 0xFF8E8E93.toInt() }
    val separator = when { dark -> 0xFF38383A.toInt(); sepia -> 0xFFDCCBA8.toInt(); pastel -> 0xFFE6DEF3.toInt(); kids -> 0xFFF2E3CB.toInt(); else -> 0xFFC6C6C8.toInt() }
    val fill = when { dark -> 0x3D767680; sepia -> 0x24806A40; pastel -> 0x1F9B82D6; kids -> 0x24FFB03A; else -> 0x1F767680 }
    val blue = when { dark -> 0xFF0A84FF.toInt(); pastel -> 0xFF7C6CE0.toInt(); kids -> 0xFFFF7A4D.toInt(); else -> 0xFF007AFF.toInt() }
    val red = if (dark) 0xFFFF453A.toInt() else 0xFFFF3B30.toInt()
    val green = if (dark) 0xFF30D158.toInt() else 0xFF34C759.toInt()
    val orange = if (dark) 0xFFFF9F0A.toInt() else 0xFFFF9500.toInt()
    val amber = if (dark) 0xFFFFB340.toInt() else 0xFFE8890C.toInt()
    val purple = if (dark) 0xFFBF5AF2.toInt() else 0xFFAF52DE.toInt()
    val teal = if (dark) 0xFF40C8E0.toInt() else 0xFF30B0C7.toInt()
    val indigo = if (dark) 0xFF5E5CE6.toInt() else 0xFF5856D6.toInt()
    val ripple = if (dark) 0x33FFFFFF else 0x1A000000
    /** Reader page colors (dark reworked for a softer, warmer night read) */
    val paper = when { dark -> 0xFF101013.toInt(); sepia -> 0xFFF4ECD8.toInt(); pastel -> 0xFFFBF7FF.toInt(); kids -> 0xFFFFF8EC.toInt(); else -> 0xFFFFFFFF.toInt() }
    val ink = when { dark -> 0xFFCFD2D8.toInt(); sepia -> 0xFF433422.toInt(); pastel -> 0xFF4A4160.toInt(); kids -> 0xFF43372B.toInt(); else -> 0xFF1C1C1E.toInt() }
    val highlight = when { dark -> 0x66E0A64A; sepia -> 0x66E8B04A; pastel -> 0x55C9B6F0; kids -> 0x66FFD23F; else -> 0x55FFD54F }
}

fun Context.isSystemDark() =
    (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

fun Context.palette(): Palette = when (Prefs.theme(this)) {
    ThemeMode.DARK -> Palette(true)
    ThemeMode.SEPIA -> Palette(false, sepia = true)
    ThemeMode.PASTEL -> Palette(false, pastel = true)
    ThemeMode.KIDS -> Palette(false, kids = true)
    else -> Palette(false)   // LIGHT (and the retired SYSTEM default)
}

fun Context.dp(v: Number): Int = (v.toFloat() * resources.displayMetrics.density).roundToInt()
fun Context.dpf(v: Number): Float = v.toFloat() * resources.displayMetrics.density

fun rounded(color: Int, radius: Float) = GradientDrawable().apply { setColor(color); cornerRadius = radius }

fun gradient(radius: Float, vararg colors: Int) =
    GradientDrawable(GradientDrawable.Orientation.TL_BR, colors).apply { cornerRadius = radius }

fun pressable(bg: Drawable?, rippleColor: Int): Drawable =
    RippleDrawable(ColorStateList.valueOf(rippleColor), bg, bg ?: ColorDrawable(Color.WHITE))

fun View.clipRounded() {
    outlineProvider = ViewOutlineProvider.BACKGROUND
    clipToOutline = true
}

@Suppress("DEPRECATION")
fun Activity.setupSystemBars(barColor: Int, lightIcons: Boolean) {
    window.statusBarColor = barColor
    window.navigationBarColor = barColor
    var flags = window.decorView.systemUiVisibility
    flags = if (!lightIcons) flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR else flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
    if (Build.VERSION.SDK_INT >= 26) {
        flags = if (!lightIcons) flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        else flags and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
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

/** Round translucent icon button (iOS toolbar style). */
fun Context.circleButton(p: Palette, res: Int, tint: Int = p.blue, sizeDp: Int = 40, bg: Int = p.fill, onClick: () -> Unit) =
    ImageView(this).apply {
        setImageResource(res)
        imageTintList = ColorStateList.valueOf(tint)
        val pad = dp(sizeDp * 0.24f)
        setPadding(pad, pad, pad, pad)
        background = pressable(GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(bg) }, p.ripple)
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)).apply { marginStart = dp(8) }
        setOnClickListener { onClick() }
    }

fun Context.card(p: Palette): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    background = rounded(p.card, dpf(14))
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
    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply { marginStart = dp(insetDp) }
}

/** Settings row: title (+ optional subtitle) left, any control right. */
fun Context.row(p: Palette, title: String, trailing: View? = null, subtitle: String? = null, onClick: (() -> Unit)? = null): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(48)
        setPadding(dp(16), dp(8), dp(12), dp(8))
        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(title, 16f, p.text))
            if (!subtitle.isNullOrEmpty()) addView(text(subtitle, 12.5f, p.secondary).apply {
                setPadding(0, dp(3), 0, 0); setLineSpacing(0f, 1.1f)
            })
        }
        addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        trailing?.let { addView(it) }
        if (onClick != null) {
            background = pressable(null, p.ripple)
            setOnClickListener { onClick() }
        }
    }

fun Context.group(p: Palette, vararg rows: View): LinearLayout = card(p).apply {
    rows.forEachIndexed { i, r ->
        if (i > 0) addView(separator(p))
        addView(r)
    }
}

fun Context.chevron(p: Palette): ImageView = icon(R.drawable.ic_chevron_right, p.separator, 20)

fun Context.valueLabel(p: Palette, s: String) = text(s, 15f, p.secondary).apply { setPadding(dp(8), 0, dp(4), 0) }

fun Context.iosSwitch(p: Palette, checked: Boolean, onChange: (Boolean) -> Unit) = Switch(this).apply {
    isChecked = checked
    val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
    thumbTintList = ColorStateList(states, intArrayOf(Color.WHITE, Color.WHITE))
    trackTintList = ColorStateList(states, intArrayOf(p.green, p.separator))
    setOnCheckedChangeListener { _, c -> onChange(c) }
}

/** Slider row: title + live value label + SeekBar. [toLabel] formats the value. */
/**
 * Slider row. The value shows as an accent pill that you can also **drag left/right** to adjust
 * directly, or **tap** to type an exact number — in addition to the slider itself.
 */
@SuppressLint("ClickableViewAccessibility")
fun Context.sliderRow(p: Palette, title: String, min: Int, max: Int, value: Int, toLabel: (Int) -> String, onChange: (Int) -> Unit): LinearLayout {
    val seek = SeekBar(this).apply {
        this.max = max - min
        progress = value - min
        progressTintList = ColorStateList.valueOf(p.blue)
        thumbTintList = ColorStateList.valueOf(p.blue)
    }
    val label = text(toLabel(value), 14f, p.blue, 600).apply {
        setPadding(dp(12), dp(5), dp(12), dp(5))
        background = rounded(p.fill, dpf(9))
    }
    fun setValue(nv: Int, fromUser: Boolean) {
        val cv = nv.coerceIn(min, max)
        label.text = toLabel(cv)
        if (seek.progress != cv - min) seek.progress = cv - min
        if (fromUser) onChange(cv)
    }
    seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(s: SeekBar?, v: Int, fromUser: Boolean) {
            label.text = toLabel(v + min)
            if (fromUser) onChange(v + min)
        }
        override fun onStartTrackingTouch(s: SeekBar?) {}
        override fun onStopTrackingTouch(s: SeekBar?) {}
    })
    // drag the number pill to change the value; a plain tap opens a type-in dialog
    val stepPx = dp(7).toFloat()
    val slop = dp(6)
    var downX = 0f
    var startVal = value
    var moved = false
    label.setOnTouchListener { v, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.rawX; startVal = min + seek.progress; moved = false; v.parent?.requestDisallowInterceptTouchEvent(true); true }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.rawX - downX
                if (kotlin.math.abs(dx) > slop) moved = true
                setValue(startVal + (dx / stepPx).toInt(), true); true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!moved) promptNumber(p, title, min, max, min + seek.progress) { setValue(it, true) }
                true
            }
            else -> false
        }
    }
    return LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(10), dp(12), dp(6))
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(text(title, 16f, p.text), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(label)
        })
        addView(seek, vlp().apply { topMargin = dp(4) })
    }
}

/** Small numeric input dialog (tap a slider's value pill). */
private fun Context.promptNumber(p: Palette, title: String, min: Int, max: Int, cur: Int, onOk: (Int) -> Unit) {
    val field = EditText(this).apply {
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
        setText(cur.toString())
        setSelectAllOnFocus(true)
        setTextColor(p.text)
    }
    AlertDialog.Builder(this, if (p.dark) android.R.style.Theme_DeviceDefault_Dialog_Alert else android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
        .setTitle("$title ($min…$max)")
        .setView(field)
        .setPositiveButton(android.R.string.ok) { _, _ -> field.text.toString().toIntOrNull()?.let { onOk(it.coerceIn(min, max)) } }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
}

/** iOS segmented control. */
class Segmented(ctx: Context, private val p: Palette, items: List<String>, selected: Int, onSelect: (Int) -> Unit) :
    LinearLayout(ctx) {
    private val cells = ArrayList<TextView>()

    init {
        orientation = HORIZONTAL
        background = rounded(p.fill, ctx.dpf(9))
        val pad = ctx.dp(2)
        setPadding(pad, pad, pad, pad)
        items.forEachIndexed { i, s ->
            val cell = ctx.text(s, 13f, p.text, 500).apply {
                gravity = Gravity.CENTER
                setPadding(ctx.dp(6), ctx.dp(7), ctx.dp(6), ctx.dp(7))
                maxLines = 1
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
            c.background = if (on) rounded(if (p.dark) 0xFF636366.toInt() else Color.WHITE, context.dpf(7)) else null
            c.elevation = if (on) context.dpf(1) else 0f
        }
    }
}

fun vlp(w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT) =
    LinearLayout.LayoutParams(w, h)

/** Scrollable iOS-style page with a back link; returns the content column. */
fun Activity.page(p: Palette, title: String, backLabel: String): LinearLayout {
    setupSystemBars(p.bg, p.dark)
    val col = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(8), dp(16), dp(40))
    }
    col.addView(LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(8), dp(12), dp(4))
        addView(icon(R.drawable.ic_back, p.blue, 26))
        addView(text(backLabel, 17f, p.blue))
        setOnClickListener { finish() }
    }, vlp(ViewGroup.LayoutParams.WRAP_CONTENT))
    col.addView(text(title, 32f, p.text, 700).apply { setPadding(dp(4), dp(4), 0, dp(10)) })
    setContentView(ScrollView(this).apply {
        isFillViewport = true
        setBackgroundColor(p.bg)
        addView(col)
    })
    return col
}

/** Filled pill button. */
fun Context.pillButton(label: String, color: Int, textColor: Int = Color.WHITE, onClick: () -> Unit): TextView =
    text(label, 14f, textColor, 600).apply {
        gravity = Gravity.CENTER
        setPadding(dp(14), dp(8), dp(14), dp(8))
        background = pressable(rounded(color, dpf(18)), 0x33FFFFFF)
        setOnClickListener { onClick() }
    }

fun Context.bigButton(label: String, vararg colors: Int, onClick: () -> Unit): TextView = text(label, 17f, Color.WHITE, 700).apply {
    gravity = Gravity.CENTER
    minHeight = dp(50)
    background = pressable(gradient(dpf(14), *colors), 0x33FFFFFF)
    setOnClickListener { onClick() }
}

fun Context.progressBar(p: Palette): ProgressBar =
    ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 1000
        progressTintList = ColorStateList.valueOf(p.blue)
        progressBackgroundTintList = ColorStateList.valueOf(p.separator)
    }

fun Context.editField(p: Palette, value: String, hint: String = "", numeric: Boolean = false, password: Boolean = false) =
    EditText(this).apply {
        setText(value)
        this.hint = hint
        setTextColor(p.text)
        setHintTextColor(p.secondary)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        inputType = when {
            numeric -> InputType.TYPE_CLASS_NUMBER
            password -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        setSingleLine(true)
        backgroundTintList = ColorStateList.valueOf(p.blue)
    }

fun Context.dialogBuilder(p: Palette): AlertDialog.Builder = AlertDialog.Builder(
    this,
    if (p.dark) android.R.style.Theme_DeviceDefault_Dialog_Alert else android.R.style.Theme_DeviceDefault_Light_Dialog_Alert
)

/** Padded vertical container for dialog content. */
fun Context.dialogColumn(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(22), dp(8), dp(22), dp(4))
}

fun Context.toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
fun Context.toast(res: Int) = toast(getString(res))
