package com.dsoft.jgamer.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import com.dsoft.jgamer.model.GameSystem
import com.dsoft.jgamer.model.PadButton
import com.dsoft.jgamer.model.PadStyle
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/**
 * The bottom control panel: an opaque retro-cream gamepad living BELOW the game
 * screen (not over it), so muted "candy" buttons never wash out the picture.
 * Sizes controls from the panel height (clamped), supports drag-to-move editing
 * and a per-system size scale, and adds Menu / Fast-forward / Filter / quick
 * Save / quick Load utility buttons.
 */
class GamepadOverlay @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    interface PadListener {
        fun onDpad(x: Int, y: Int)
        fun onButton(keyCode: Int, pressed: Boolean)
        fun onMenu()
        fun onQuickSave() {}
        fun onQuickLoad() {}
        fun onFastForward(active: Boolean) {}
        fun onFilterCycle() {}
        fun onAnalog(x: Float, y: Float) {}
        fun onLayoutChanged(token: String, xFraction: Float, yFraction: Float) {}
        fun onEditFinished() {}
    }

    var listener: PadListener? = null
    var vibrate: Boolean = true
    var editMode: Boolean = false
        set(value) { field = value; invalidate() }

    /** Landscape splits controls into left/right columns over a centred screen
     *  (transparent). Portrait keeps the opaque panel below the screen. Set by
     *  the host activity from the device orientation. */
    var landscapeMode: Boolean = false
        set(value) { if (field != value) { field = value; rebuild(); invalidate() } }

    private var system: GameSystem = GameSystem.NES
    private var scale = 1f
    private var joystick = false
    private var stickNx = 0f; private var stickNy = 0f
    private var theme: ControlTheme = ControlThemes.get(0)
    private val custom = HashMap<String, FloatArray>()

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(1.4f) }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; isFakeBoldText = true }
    private val panelPaint = Paint()

    private val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

    private var dpadCx = 0f; private var dpadCy = 0f; private var dpadR = 0f
    private val doneRect = RectF()   // "finish editing" button (edit mode only)

    private data class Btn(
        val token: String, val keyCode: Int, val text: String, val color: Int,
        var cx: Float, var cy: Float, var r: Float, var pressed: Boolean = false
    )
    private val btns = ArrayList<Btn>()

    private val pointerDpad = HashSet<Int>()
    private val pointerBtn = HashMap<Int, Btn>()
    private var lastDx = 0; private var lastDy = 0
    private var dragToken: String? = null

    fun configure(system: GameSystem, alpha: Float, scale: Float, positions: Map<String, FloatArray>, joystick: Boolean = false, themeIndex: Int = 0) {
        this.system = system
        this.scale = scale.coerceIn(0.6f, 1.8f)
        this.joystick = joystick
        this.theme = ControlThemes.get(themeIndex)
        custom.clear(); positions.forEach { (k, v) -> custom[k] = v.copyOf() }
        setBackgroundColor(theme.panelBg)
        rebuild(); invalidate()
    }

    fun updateScale(scale: Float) { this.scale = scale.coerceIn(0.6f, 1.8f); rebuild(); invalidate() }
    fun setJoystick(on: Boolean) { joystick = on; stickNx = 0f; stickNy = 0f; invalidate() }
    fun setTheme(themeIndex: Int) { theme = ControlThemes.get(themeIndex); setBackgroundColor(theme.panelBg); rebuild(); invalidate() }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) = rebuild()

    private fun rebuild() {
        if (width <= 0 || height <= 0) { btns.clear(); return }
        if (landscapeMode) rebuildLandscape() else rebuildPortrait()
    }

    /**
     * Landscape: game centred with margins, controls in the LEFT column
     * (D-pad, Select, L) and RIGHT column (action cluster, Start, R), utility
     * row along the bottom strip. Transparent so the screen shows through, and
     * nothing overlaps the picture.
     */
    private fun rebuildLandscape() {
        setBackgroundColor(Color.TRANSPARENT)
        btns.clear()
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        val xL = w * 0.12f
        val xR = w * 0.88f

        // D-pad / joystick — left column, vertically centred.
        val dpadMax = min(w * 0.11f, h * 0.30f)
        dpadR = (dpadMax * scale).coerceIn(dp(46f), dpadMax)
        dpadCx = xL; dpadCy = h * 0.46f

        // Action cluster — right column.
        val ext = min(w * 0.10f, h * 0.24f)
        val r = (ext / 2.2f * scale).coerceIn(dp(15f), ext / 1.9f)
        if (system.padStyle == PadStyle.SIXBUTTON) {
            placeSixButton(w, h * 0.46f, ext, centerX = xR)
        } else {
            val actions = system.buttons.filter { it.keyCode in ACTION_KEYS }
            placeActions(actions, xR, h * 0.46f, r)
            // Shoulders at the top of each column.
            val shoulders = system.buttons.filter {
                it.keyCode == KeyEvent.KEYCODE_BUTTON_L1 || it.keyCode == KeyEvent.KEYCODE_BUTTON_R1
            }
            val sr = (r * 0.95f).coerceIn(dp(14f), dp(26f))
            shoulders.forEach {
                val x = if (it.keyCode == KeyEvent.KEYCODE_BUTTON_L1) xL else xR
                add(it.keyCode.toString(), it.keyCode, it.label, colorFor(it.label, false), x, h * 0.12f, sr)
            }
        }

        // Start / Select — bottom of the columns (Select left, Start right).
        val systemBtns = system.buttons.filter {
            it.keyCode == KeyEvent.KEYCODE_BUTTON_START || it.keyCode == KeyEvent.KEYCODE_BUTTON_SELECT
        }
        val sysR = (r * 0.8f).coerceIn(dp(14f), dp(22f))
        var leftN = 0; var rightN = 0
        systemBtns.forEach { b ->
            if (b.keyCode == KeyEvent.KEYCODE_BUTTON_SELECT) {
                add(b.keyCode.toString(), b.keyCode, b.label, colorFor(b.label, true), xL, h * 0.76f - leftN * sysR * 2.6f, sysR); leftN++
            } else {
                add(b.keyCode.toString(), b.keyCode, b.label, colorFor(b.label, true), xR, h * 0.76f - rightN * sysR * 2.6f, sysR); rightN++
            }
        }

        // Utility row along the bottom strip (below the centred game).
        val ur = (h * 0.055f).coerceIn(dp(15f), dp(22f))
        val uy = h * 0.93f; val cx = w * 0.5f; val step = (w * 0.09f).coerceAtMost(ur * 2.8f)
        add(TOKEN_FILTER, 0, "🎨", theme.util, cx - step * 2, uy, ur)
        add(TOKEN_QSAVE, 0, "💾", theme.util, cx - step, uy, ur)
        add(TOKEN_MENU, 0, "⚙️", theme.util, cx, uy, ur)
        add(TOKEN_QLOAD, 0, "📂", theme.util, cx + step, uy, ur)
        add(TOKEN_FF, 0, "⏩", theme.util, cx + step * 2, uy, ur)
        // No applyCustom in landscape — the computed split layout is fixed & tidy.
    }

    private fun rebuildPortrait() {
        setBackgroundColor(theme.panelBg)
        btns.clear()
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        // Split the panel into zones that can't overlap:
        //   top strip  -> utility row + shoulders
        //   main area  -> D-pad (left third) and action cluster (right third),
        //                 each confined to its own half-size box.
        val topStrip = h * 0.30f
        val mainCy = topStrip + (h - topStrip) * 0.50f
        val mainHalf = (h - topStrip) * 0.44f

        // D-pad / joystick (left zone) — bigger by default, kept off the left
        // edge with a margin, radius still bounded so it never overlaps.
        val dpadMax = min(w * 0.24f, mainHalf * 1.05f)
        dpadR = (dpadMax * scale * 1.12f).coerceIn(dp(46f), dpadMax)
        dpadCx = dpadR + dp(18f); dpadCy = mainCy

        // Action cluster (right zone). Keep 2.7r <= ext (diamond span) so the
        // buttons fit without touching, and place the cluster so the far button
        // (A) keeps a margin from the screen edge instead of hugging the corner.
        val ext = min(w * 0.20f, mainHalf)
        val r = (ext / 3.0f * scale).coerceIn(dp(15f), ext / 2.7f)
        if (system.padStyle == PadStyle.SIXBUTTON) {
            placeSixButton(w, mainCy, ext)
        } else {
            val d = r * 1.7f
            val actions = system.buttons.filter { it.keyCode in ACTION_KEYS }
            val rightCx = w - dp(16f) - (d + r)
            placeActions(actions, rightCx, mainCy, r)

            // Shoulders: upper corners inside the top strip.
            val shoulders = system.buttons.filter { it.keyCode == KeyEvent.KEYCODE_BUTTON_L1 || it.keyCode == KeyEvent.KEYCODE_BUTTON_R1 }
            val sr = (r * 0.95f).coerceAtMost(topStrip * 0.4f)
            shoulders.forEach {
                val x = if (it.keyCode == KeyEvent.KEYCODE_BUTTON_L1) w * 0.13f else w * 0.87f
                add(it.keyCode.toString(), it.keyCode, it.label, colorFor(it.label, false), x, topStrip * 0.66f, sr)
            }
        }

        // Start / Select / Coin: dead-centre of the panel but lifted UP into the
        // gap between the utility row and the main row, so the D-pad and the
        // A/B/X/Y cluster get the full main row to themselves (roomier).
        val systemBtns = system.buttons.filter { it.keyCode == KeyEvent.KEYCODE_BUTTON_START || it.keyCode == KeyEvent.KEYCODE_BUTTON_SELECT }
        val n = systemBtns.size
        val sysR = (r * 0.8f).coerceAtMost(dp(22f))
        val sysCy = topStrip + (h - topStrip) * 0.14f
        systemBtns.forEachIndexed { i, b ->
            val cx = w * 0.5f + (i - (n - 1) / 2f) * sysR * 2.8f
            add(b.keyCode.toString(), b.keyCode, b.label, colorFor(b.label, true), cx, sysCy, sysR)
        }

        // Utility row across the top-centre of the panel.
        val ur = (topStrip * 0.36f).coerceIn(dp(14f), dp(22f))
        val uy = topStrip * 0.42f; val step = (w * 0.13f).coerceAtMost(ur * 2.8f); val cx = w * 0.5f
        add(TOKEN_FILTER, 0, "🎨", theme.util, cx - step * 2, uy, ur)
        add(TOKEN_QSAVE, 0, "💾", theme.util, cx - step, uy, ur)
        add(TOKEN_MENU, 0, "⚙️", theme.util, cx, uy, ur)
        add(TOKEN_QLOAD, 0, "📂", theme.util, cx + step, uy, ur)
        add(TOKEN_FF, 0, "⏩", theme.util, cx + step * 2, uy, ur)

        applyCustom(w, h)
    }

    private fun add(token: String, keyCode: Int, text: String, color: Int, cx: Float, cy: Float, r: Float) {
        btns.add(Btn(token, keyCode, text, color, cx, cy, r))
    }

    private fun applyCustom(w: Float, h: Float) {
        custom[TOKEN_DPAD]?.let { dpadCx = it[0] * w; dpadCy = it[1] * h }
        for (b in btns) custom[b.token]?.let { b.cx = it[0] * w; b.cy = it[1] * h }
    }

    /** Sega 6-button grid: top row X Y Z, bottom row A B C, kept off the edge. */
    private fun placeSixButton(w: Float, cy: Float, ext: Float, centerX: Float? = null) {
        val rr = (ext / 3.4f * scale).coerceIn(dp(13f), ext / 3.0f)
        val colGap = rr * 2.25f
        val rowGap = rr * 2.35f
        val cxR = centerX ?: (w - dp(16f) - (colGap + rr))
        val topY = cy - rowGap / 2f
        val botY = cy + rowGap / 2f
        val byLabel = system.buttons.associateBy { it.label }
        fun put(lbl: String, x: Float, y: Float) = byLabel[lbl]?.let {
            add(it.keyCode.toString(), it.keyCode, it.label, colorFor(it.label, false), x, y, rr)
        }
        put("X", cxR - colGap, topY); put("Y", cxR, topY); put("Z", cxR + colGap, topY)
        put("A", cxR - colGap, botY); put("B", cxR, botY); put("C", cxR + colGap, botY)
    }

    private fun placeActions(actions: List<PadButton>, cx: Float, cy: Float, r: Float) {
        val d = r * 1.7f
        when (actions.size) {
            1 -> add(actions[0].keyCode.toString(), actions[0].keyCode, actions[0].label, colorFor(actions[0].label, false), cx, cy, r)
            2 -> {
                add(actions[0].keyCode.toString(), actions[0].keyCode, actions[0].label, colorFor(actions[0].label, false), cx - d, cy + d * 0.5f, r)
                add(actions[1].keyCode.toString(), actions[1].keyCode, actions[1].label, colorFor(actions[1].label, false), cx + d * 0.5f, cy - d * 0.5f, r)
            }
            else -> {
                val map = actions.associateBy { it.label }
                fun place(lbl: String, x: Float, y: Float) =
                    map[lbl]?.let { add(it.keyCode.toString(), it.keyCode, it.label, colorFor(lbl, false), x, y, r) }
                place("Y", cx - d, cy); place("X", cx, cy - d); place("B", cx, cy + d); place("A", cx + d, cy)
                actions.filter { it.label !in setOf("Y", "X", "B", "A") }
                    .forEachIndexed { i, b -> add(b.keyCode.toString(), b.keyCode, b.label, colorFor(b.label, false), cx, cy + d + (i + 1) * r * 2.2f, r) }
            }
        }
    }

    /** Candy colours from the active theme. */
    private fun colorFor(labelText: String, system: Boolean): Int = when (labelText) {
        "A", "O" -> theme.a
        "B" -> theme.b
        "X" -> theme.x
        "Y" -> theme.y
        "L", "R", "C" -> theme.lr
        "Z" -> theme.sys
        else -> theme.sys
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        if (!landscapeMode) {
            panelPaint.color = theme.edge
            canvas.drawRect(0f, 0f, w, dp(2f), panelPaint)
        }

        label.textSize = ((if (landscapeMode) h * 0.05f else h * 0.075f) * scale).coerceIn(dp(12f), dp(22f))
        drawDpad(canvas)
        for (b in btns) drawCuteButton(canvas, b)

        if (editMode) {
            // hint
            label.color = theme.text; label.textSize = dp(12f)
            canvas.drawText("Kéo để di chuyển nút — bấm Xong hoặc Back để lưu", w / 2f, dp(14f), label)
            // "Done (save)" button, bottom-centre
            val bw = dp(150f); val bh = dp(44f)
            doneRect.set(w / 2f - bw / 2, h - bh - dp(8f), w / 2f + bw / 2, h - dp(8f))
            fill.color = theme.accent
            canvas.drawRoundRect(doneRect, bh / 2f, bh / 2f, fill)
            label.color = Color.WHITE; label.textSize = dp(16f)
            val fm = label.fontMetrics
            canvas.drawText("✓ Xong (lưu)", doneRect.centerX(), doneRect.centerY() - (fm.ascent + fm.descent) / 2f, label)
        }
    }

    /** A soft, glossy "candy" button: drop shadow + body + top gloss + label. */
    private fun drawCuteButton(canvas: Canvas, b: Btn) {
        // shadow
        fill.color = Color.argb(55, 0, 0, 0)
        canvas.drawCircle(b.cx, b.cy + dp(2.5f), b.r, fill)
        // body
        val base = if (b.pressed) darken(b.color) else b.color
        fill.color = base
        canvas.drawCircle(b.cx, b.cy, b.r, fill)
        // top gloss highlight
        if (!b.pressed) {
            fill.color = Color.argb(70, 255, 255, 255)
            canvas.drawOval(b.cx - b.r * 0.6f, b.cy - b.r * 0.78f, b.cx + b.r * 0.6f, b.cy - b.r * 0.05f, fill)
        }
        // rim
        stroke.color = if (b.pressed) theme.accent else darken(base)
        canvas.drawCircle(b.cx, b.cy, b.r, stroke)
        // label (emoji render in colour; letters use theme text colour)
        label.color = theme.text
        val fm = label.fontMetrics
        canvas.drawText(displayText(b.text), b.cx, b.cy - (fm.ascent + fm.descent) / 2f, label)
    }

    /** Icon glyphs for buttons that aren't a single letter. */
    private fun displayText(t: String): String = when (t) {
        "START" -> "▶"
        "SELECT" -> "⊟"
        "MODE" -> "M"
        "COIN" -> "🪙"
        else -> t
    }

    private fun drawDpad(canvas: Canvas) {
        // soft shadow under the pad
        fill.color = Color.argb(55, 0, 0, 0)
        canvas.drawCircle(dpadCx, dpadCy + dp(2.5f), dpadR, fill)
        if (joystick) {
            stroke.color = darken(theme.dpad)
            fill.color = adjust(theme.dpad, 1.35f)
            canvas.drawCircle(dpadCx, dpadCy, dpadR, fill)
            canvas.drawCircle(dpadCx, dpadCy, dpadR, stroke)
            fill.color = theme.dpad
            val tx = dpadCx + stickNx * dpadR * 0.55f
            val ty = dpadCy + stickNy * dpadR * 0.55f
            canvas.drawCircle(tx, ty, dpadR * 0.42f, fill)
            canvas.drawCircle(tx, ty, dpadR * 0.42f, stroke)
            return
        }
        val arm = dpadR * 0.62f; val th = dpadR * 0.42f
        fill.color = theme.dpad; stroke.color = darken(theme.dpad)
        val rad = dp(8f)
        val hRect = RectF(dpadCx - arm, dpadCy - th / 2, dpadCx + arm, dpadCy + th / 2)
        val vRect = RectF(dpadCx - th / 2, dpadCy - arm, dpadCx + th / 2, dpadCy + arm)
        canvas.drawRoundRect(hRect, rad, rad, fill)
        canvas.drawRoundRect(vRect, rad, rad, fill)
        canvas.drawRoundRect(hRect, rad, rad, stroke)
        canvas.drawRoundRect(vRect, rad, rad, stroke)
        if (lastDx != 0 || lastDy != 0) {
            fill.color = theme.accent
            canvas.drawCircle(dpadCx + lastDx * arm * 0.6f, dpadCy + lastDy * arm * 0.6f, th * 0.5f, fill)
        }
    }

    private fun adjust(c: Int, f: Float): Int = Color.rgb(
        (Color.red(c) * f).toInt().coerceIn(0, 255),
        (Color.green(c) * f).toInt().coerceIn(0, 255),
        (Color.blue(c) * f).toInt().coerceIn(0, 255)
    )

    // ---- Touch ---------------------------------------------------------------

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (editMode) return editTouch(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = event.actionIndex; assign(event.getPointerId(idx), event.getX(idx), event.getY(idx))
            }
            MotionEvent.ACTION_MOVE -> for (i in 0 until event.pointerCount) {
                if (pointerDpad.contains(event.getPointerId(i))) updateDpad(event.getX(i), event.getY(i))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> release(event.getPointerId(event.actionIndex))
            MotionEvent.ACTION_CANCEL -> { pointerBtn.keys.toList().forEach { release(it) }; pointerDpad.toList().forEach { release(it) } }
        }
        return true
    }

    private fun assign(id: Int, x: Float, y: Float) {
        if (hypot(x - dpadCx, y - dpadCy) <= dpadR * 1.25f) { pointerDpad.add(id); updateDpad(x, y); buzz(); return }
        val b = btns.firstOrNull { hypot(x - it.cx, y - it.cy) <= it.r * 1.12f } ?: return
        b.pressed = true; pointerBtn[id] = b; buzz()
        when (b.token) {
            TOKEN_MENU -> listener?.onMenu()
            TOKEN_QSAVE -> listener?.onQuickSave()
            TOKEN_QLOAD -> listener?.onQuickLoad()
            TOKEN_FILTER -> listener?.onFilterCycle()
            TOKEN_FF -> listener?.onFastForward(true)
            else -> listener?.onButton(b.keyCode, true)
        }
        invalidate()
    }

    private fun updateDpad(x: Float, y: Float) {
        var nx = (x - dpadCx) / dpadR
        var ny = (y - dpadCy) / dpadR
        val mag = hypot(nx, ny)
        if (mag > 1f) { nx /= mag; ny /= mag }
        // Joystick mode also emits an analog left-stick value (for cores that use it).
        if (joystick) { stickNx = nx; stickNy = ny; listener?.onAnalog(nx, ny) }
        // Always emit a digital D-pad too (works on every retro core).
        val dead = 0.30f
        val dx = if (abs(nx) < dead) 0 else if (nx < 0) -1 else 1
        val dy = if (abs(ny) < dead) 0 else if (ny < 0) -1 else 1
        if (dx != lastDx || dy != lastDy) { lastDx = dx; lastDy = dy; listener?.onDpad(dx, dy) }
        invalidate()
    }

    private fun release(id: Int) {
        if (pointerDpad.remove(id)) {
            lastDx = 0; lastDy = 0; stickNx = 0f; stickNy = 0f
            listener?.onDpad(0, 0)
            if (joystick) listener?.onAnalog(0f, 0f)
            invalidate(); return
        }
        pointerBtn.remove(id)?.let {
            it.pressed = false
            when (it.token) {
                TOKEN_FF -> listener?.onFastForward(false)
                in UTILITY_TOKENS -> {}
                else -> listener?.onButton(it.keyCode, false)
            }
            invalidate()
        }
    }

    // ---- Edit mode -----------------------------------------------------------

    private fun editTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val x = event.x; val y = event.y
                if (doneRect.contains(x, y)) { editMode = false; listener?.onEditFinished(); return true }
                dragToken = if (hypot(x - dpadCx, y - dpadCy) <= dpadR * 1.1f) TOKEN_DPAD
                else btns.firstOrNull { hypot(x - it.cx, y - it.cy) <= it.r * 1.15f }?.token
            }
            MotionEvent.ACTION_MOVE -> dragToken?.let { moveDrag(it, event.x, event.y) }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragToken?.let { tok ->
                    val cx: Float; val cy: Float
                    if (tok == TOKEN_DPAD) { cx = dpadCx; cy = dpadCy }
                    else { val b = btns.firstOrNull { it.token == tok } ?: run { dragToken = null; return true }; cx = b.cx; cy = b.cy }
                    if (width > 0 && height > 0) listener?.onLayoutChanged(tok, cx / width, cy / height)
                }
                dragToken = null
            }
        }
        return true
    }

    private fun moveDrag(token: String, x: Float, y: Float) {
        val cx = x.coerceIn(0f, width.toFloat()); val cy = y.coerceIn(0f, height.toFloat())
        if (token == TOKEN_DPAD) { dpadCx = cx; dpadCy = cy }
        else btns.firstOrNull { it.token == token }?.let { it.cx = cx; it.cy = cy }
        custom[token] = floatArrayOf(cx / width, cy / height)
        invalidate()
    }

    private fun buzz() {
        if (!vibrate) return
        runCatching {
            val v = vibrator ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) v.vibrate(VibrationEffect.createOneShot(12, 60))
            else @Suppress("DEPRECATION") v.vibrate(12)
        }
    }

    private fun darken(c: Int): Int {
        val f = 0.78f
        return Color.rgb((Color.red(c) * f).toInt(), (Color.green(c) * f).toInt(), (Color.blue(c) * f).toInt())
    }

    companion object {
        const val TOKEN_DPAD = "DPAD"
        const val TOKEN_MENU = "MENU"
        const val TOKEN_QSAVE = "QSAVE"
        const val TOKEN_QLOAD = "QLOAD"
        const val TOKEN_FF = "FF"
        const val TOKEN_FILTER = "FILTER"
        private val UTILITY_TOKENS = setOf(TOKEN_MENU, TOKEN_QSAVE, TOKEN_QLOAD, TOKEN_FF, TOKEN_FILTER)
        private val ACTION_KEYS = setOf(
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B,
            KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y
        )
    }
}
