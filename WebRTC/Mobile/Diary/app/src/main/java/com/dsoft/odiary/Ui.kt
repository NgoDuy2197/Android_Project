package com.dsoft.odiary

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.emoji2.text.EmojiCompat
import kotlin.math.roundToInt

/** A youth/kid-friendly color theme. */
data class Theme(
    val id: String, val name: String, val emoji: String, val dark: Boolean,
    val bg: Int, val card: Int, val text: Int, val sub: Int, val sep: Int,
    val accent: Int, val accent2: Int, val paper: Int, val ink: Int,
)

val THEMES: List<Theme> = listOf(
    Theme("bubblegum", "Kẹo bông", "🍬", false,
        0xFFFFF0F6.toInt(), 0xFFFFFFFF.toInt(), 0xFF3A2B3A.toInt(), 0xFFB08BA5.toInt(), 0xFFF3D9E6.toInt(),
        0xFFFF5E9C.toInt(), 0xFFFFA64D.toInt(), 0xFFFFF7FB.toInt(), 0xFF4A3340.toInt()),
    Theme("ocean", "Đại dương", "🌊", false,
        0xFFEAF6FF.toInt(), 0xFFFFFFFF.toInt(), 0xFF1E3A4C.toInt(), 0xFF7FA8BE.toInt(), 0xFFD3E8F5.toInt(),
        0xFF2BA6FF.toInt(), 0xFF22D3C5.toInt(), 0xFFF5FBFF.toInt(), 0xFF23384A.toInt()),
    Theme("mint", "Bạc hà", "🍃", false,
        0xFFEBFBF2.toInt(), 0xFFFFFFFF.toInt(), 0xFF1F3B30.toInt(), 0xFF84B7A3.toInt(), 0xFFD2EEE0.toInt(),
        0xFF27C98C.toInt(), 0xFF46C2E8.toInt(), 0xFFF4FFF9.toInt(), 0xFF244237.toInt()),
    Theme("sunset", "Hoàng hôn", "🌇", false,
        0xFFFFF3EA.toInt(), 0xFFFFFFFF.toInt(), 0xFF4A2E22.toInt(), 0xFFC79B86.toInt(), 0xFFF6DFCE.toInt(),
        0xFFFF7A4D.toInt(), 0xFFFFC24D.toInt(), 0xFFFFF8F2.toInt(), 0xFF50352A.toInt()),
    Theme("grape", "Nho tím", "🍇", false,
        0xFFF3EEFF.toInt(), 0xFFFFFFFF.toInt(), 0xFF352B4F.toInt(), 0xFF9E90C4.toInt(), 0xFFE2D8F7.toInt(),
        0xFF8E5CFF.toInt(), 0xFFFF6FB5.toInt(), 0xFFFAF7FF.toInt(), 0xFF392E54.toInt()),
    Theme("midnight", "Đêm neon", "🌌", true,
        0xFF0E0E17.toInt(), 0xFF1A1A2A.toInt(), 0xFFEDEBFF.toInt(), 0xFF8E8BB5.toInt(), 0xFF2A2A40.toInt(),
        0xFF35E0FF.toInt(), 0xFFFF5ED2.toInt(), 0xFF15151F.toInt(), 0xFFE7E5FF.toInt()),
)

fun themeById(id: String): Theme = THEMES.firstOrNull { it.id == id } ?: THEMES[0]

class Palette(val t: Theme) {
    val dark get() = t.dark
    val bg get() = t.bg; val card get() = t.card; val text get() = t.text; val sub get() = t.sub; val sep get() = t.sep
    val accent get() = t.accent; val accent2 get() = t.accent2; val paper get() = t.paper; val ink get() = t.ink
    val ripple = if (t.dark) 0x33FFFFFF else 0x1A000000
    val fill = if (t.dark) 0x30FFFFFF else 0x12000000
    val red = if (t.dark) 0xFFFF6B6B.toInt() else 0xFFE4483E.toInt()
}

fun Context.palette(): Palette = Palette(themeById(Prefs.themeId(this)))

fun Context.dp(v: Number): Int = (v.toFloat() * resources.displayMetrics.density).roundToInt()
fun Context.dpf(v: Number): Float = v.toFloat() * resources.displayMetrics.density

fun rounded(color: Int, radius: Float) = GradientDrawable().apply { setColor(color); cornerRadius = radius }
fun gradientD(radius: Float, vararg colors: Int) =
    GradientDrawable(GradientDrawable.Orientation.TL_BR, colors).apply { cornerRadius = radius }

/** Run text through EmojiCompat (bundled font) when it's ready; otherwise return as-is. */
fun emojify(s: CharSequence): CharSequence = runCatching {
    val ec = EmojiCompat.get()
    if (ec.loadState == EmojiCompat.LOAD_STATE_SUCCEEDED) ec.process(s) ?: s else s
}.getOrDefault(s)

/** Sets [s] on [tv], processed for emoji; if the font isn't loaded yet, re-process once it is. */
fun applyEmoji(tv: TextView, s: CharSequence) {
    val ec = runCatching { EmojiCompat.get() }.getOrNull()
    if (ec != null && ec.loadState == EmojiCompat.LOAD_STATE_SUCCEEDED) { tv.text = ec.process(s) ?: s; return }
    tv.text = s
    ec?.registerInitCallback(object : EmojiCompat.InitCallback() {
        override fun onInitialized() {
            runCatching { tv.text = EmojiCompat.get().process(s) ?: s }
            runCatching { EmojiCompat.get().unregisterInitCallback(this) }
        }
        override fun onFailed(throwable: Throwable?) { runCatching { EmojiCompat.get().unregisterInitCallback(this) } }
    })
}

fun Context.text(s: CharSequence, sp: Float, color: Int, weight: Int = 400): TextView = TextView(this).apply {
    applyEmoji(this, s)
    setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
    setTextColor(color)
    typeface = if (weight >= 600) Typeface.create("sans-serif-medium", Typeface.NORMAL)
    else Typeface.create("sans-serif", Typeface.NORMAL)
    includeFontPadding = false
}

fun Context.card(p: Palette): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    background = rounded(p.card, dp(18).toFloat())
}

fun Context.pill(label: String, bg: Int, fg: Int, onClick: () -> Unit): TextView =
    text(label, 14f, fg, 600).apply {
        gravity = Gravity.CENTER
        setPadding(dp(16), dp(10), dp(16), dp(10))
        background = rounded(bg, dp(22).toFloat())
        isClickable = true
        setOnClickListener { onClick() }
    }

fun Context.bigButton(label: String, c1: Int, c2: Int, onClick: () -> Unit): TextView =
    text(label, 16f, android.graphics.Color.WHITE, 700).apply {
        gravity = Gravity.CENTER
        setPadding(dp(18), dp(14), dp(18), dp(14))
        background = gradientD(dp(16).toFloat(), c1, c2)
        isClickable = true
        setOnClickListener { onClick() }
    }

fun View.lp(w: Int, h: Int) = LinearLayout.LayoutParams(w, h)
