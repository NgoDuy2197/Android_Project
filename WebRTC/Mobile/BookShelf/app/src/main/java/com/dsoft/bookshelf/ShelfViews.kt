package com.dsoft.bookshelf

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import kotlin.math.abs
import kotlin.random.Random

/** Wood colours for the shelf (light / dark walnut). */
class Wood(dark: Boolean) {
    val wallTop = if (dark) 0xFF24170F.toInt() else 0xFFA87A4F.toInt()
    val wallBottom = if (dark) 0xFF35221A.toInt() else 0xFFBF8F60.toInt()
    val wallGrain = if (dark) 0x14000000 else 0x16000000
    val wallGrainLight = if (dark) 0x08FFFFFF else 0x10FFFFFF
    val topLight = if (dark) 0xFF7A5232.toInt() else 0xFFF0CB98.toInt()
    val topDark = if (dark) 0xFF5E3D24.toInt() else 0xFFDDAE74.toInt()
    val frontTop = if (dark) 0xFF6A4528.toInt() else 0xFFCB9258.toInt()
    val frontBottom = if (dark) 0xFF3E2716.toInt() else 0xFF8E5A2C.toInt()
    val frontGrain = if (dark) 0x22000000 else 0x26000000
    val highlight = if (dark) 0x30FFFFFF else 0x66FFF4DC
    val label = if (dark) 0xCCE9D6BC.toInt() else 0xE6FFF6E6.toInt()
    val labelShadow = if (dark) 0xAA000000.toInt() else 0x804A2A10.toInt()
    val crownTop = if (dark) 0xFF5A3A22.toInt() else 0xFFC48D58.toInt()
    val crownBottom = if (dark) 0xFF2E1D10.toInt() else 0xFF8A5A30.toInt()
}

/**
 * One shelf: wooden back wall + a plank (top surface, front edge with grain and title labels, cast shadow),
 * with [CoverView]s standing on it.
 */
@SuppressLint("ViewConstructor")
class ShelfRowView(ctx: Context, dark: Boolean) : ViewGroup(ctx) {
    private val wood = Wood(dark)
    private val d = resources.displayMetrics.density
    var columns = 3
    var coverW = 100
    var sidePad = 0
    var gap = 0
    var rowIndex = 0
        set(v) { field = v; grainSeed = v * 7919 + 13; invalidate() }
    var isFirst = false
    var titles: List<String> = emptyList()
        set(v) { field = v; invalidate() }

    private var grainSeed = 13
    private val topPad get() = (if (isFirst) 26 * d else 20 * d).toInt()
    private val coverH get() = (coverW * 1.48f).toInt()
    private val surfaceH = 11 * d
    private val frontH = 22 * d
    private val shadowH = 16 * d
    private val sink = 5 * d // covers stand slightly "into" the top surface

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 10.5f * d
        typeface = Typeface.create("serif", Typeface.BOLD)
        color = wood.label
        textAlign = Paint.Align.CENTER
        setShadowLayer(1.2f * d, 0f, 1f * d, wood.labelShadow)
    }

    init {
        setWillNotDraw(false)
        clipChildren = false
        clipToPadding = false
    }

    val covers: List<CoverView> get() = (0 until childCount).map { getChildAt(it) as CoverView }

    fun ensureCovers(n: Int) {
        while (childCount < n) addView(CoverView(context))
        while (childCount > n) removeViewAt(childCount - 1)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val cvW = coverW + CoverView.extraW(d)
        val cvH = coverH + CoverView.extraH(d)
        for (i in 0 until childCount) {
            getChildAt(i).measure(MeasureSpec.makeMeasureSpec(cvW, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(cvH, MeasureSpec.EXACTLY))
        }
        setMeasuredDimension(w, (topPad + coverH + surfaceH + frontH + shadowH).toInt())
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val coverBottom = (topPad + coverH + sink).toInt()
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            val x = sidePad + i * (coverW + gap)
            val y = coverBottom - coverH
            c.layout(x, y, x + c.measuredWidth, y + c.measuredHeight)
        }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val plankTop = topPad + coverH.toFloat()
        val frontTop = plankTop + surfaceH
        val frontBottom = frontTop + frontH
        val h = height.toFloat()

        // back wall
        paint.shader = LinearGradient(0f, 0f, 0f, frontBottom, wood.wallTop, wood.wallBottom, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null
        val rnd = Random(grainSeed)
        paint.strokeWidth = 1f * d
        paint.style = Paint.Style.STROKE
        var x = rnd.nextFloat() * 18 * d
        while (x < w) {
            paint.color = if (rnd.nextBoolean()) wood.wallGrain else wood.wallGrainLight
            val wobble = (rnd.nextFloat() - 0.5f) * 6 * d
            canvas.drawLine(x, 0f, x + wobble, plankTop, paint)
            x += (6 + rnd.nextFloat() * 22) * d
        }
        paint.style = Paint.Style.FILL
        // soft shadow at top of wall (under the previous plank / crown)
        paint.shader = LinearGradient(0f, 0f, 0f, 18 * d, 0x66000000, 0x00000000, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, 18 * d, paint)
        // dark side panels (cabinet sides)
        paint.shader = LinearGradient(0f, 0f, 14 * d, 0f, 0x55000000, 0x00000000, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, 14 * d, frontBottom, paint)
        paint.shader = LinearGradient(w, 0f, w - 14 * d, 0f, 0x55000000, 0x00000000, Shader.TileMode.CLAMP)
        canvas.drawRect(w - 14 * d, 0f, w, frontBottom, paint)
        // ambient occlusion where the plank meets the wall
        paint.shader = LinearGradient(0f, plankTop - 14 * d, 0f, plankTop, 0x00000000, 0x55000000, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, plankTop - 14 * d, w, plankTop, paint)

        // plank top surface (slight perspective: narrower at the back)
        paint.shader = LinearGradient(0f, plankTop, 0f, frontTop, wood.topDark, wood.topLight, Shader.TileMode.CLAMP)
        val surf = Path().apply {
            moveTo(6 * d, plankTop); lineTo(w - 6 * d, plankTop); lineTo(w, frontTop); lineTo(0f, frontTop); close()
        }
        canvas.drawPath(surf, paint)

        // front edge
        paint.shader = LinearGradient(0f, frontTop, 0f, frontBottom, wood.frontTop, wood.frontBottom, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, frontTop, w, frontBottom, paint)
        paint.shader = null
        // grain on the front edge
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 0.8f * d
        val grain = Path()
        repeat(7) { k ->
            paint.color = wood.frontGrain
            val y0 = frontTop + (k + 1) * frontH / 8f + (rnd.nextFloat() - 0.5f) * 2 * d
            grain.reset()
            grain.moveTo(0f, y0)
            var gx = 0f
            while (gx < w) {
                val step = (30 + rnd.nextFloat() * 50) * d
                grain.rQuadTo(step / 2, (rnd.nextFloat() - 0.5f) * 3 * d, step, 0f)
                gx += step
            }
            canvas.drawPath(grain, paint)
        }
        // a couple of knots
        repeat(1 + rnd.nextInt(2)) {
            val kx = rnd.nextFloat() * w
            val ky = frontTop + frontH * (0.35f + rnd.nextFloat() * 0.3f)
            paint.strokeWidth = 0.8f * d
            canvas.drawOval(RectF(kx - 7 * d, ky - 2.2f * d, kx + 7 * d, ky + 2.2f * d), paint)
            canvas.drawOval(RectF(kx - 3.5f * d, ky - 1f * d, kx + 3.5f * d, ky + 1f * d), paint)
        }
        paint.style = Paint.Style.FILL
        // highlight on the front top edge
        paint.color = wood.highlight
        canvas.drawRect(0f, frontTop, w, frontTop + 1.3f * d, paint)
        // darker bottom lip
        paint.color = 0x40000000
        canvas.drawRect(0f, frontBottom - 1.5f * d, w, frontBottom, paint)

        // cast shadow below the plank
        paint.shader = LinearGradient(0f, frontBottom, 0f, h, 0x8C000000.toInt(), 0x00000000, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, frontBottom, w, h, paint)
        paint.shader = null

        // title labels engraved on the front edge
        val ly = frontTop + frontH / 2f - (labelPaint.descent() + labelPaint.ascent()) / 2f
        titles.forEachIndexed { i, t ->
            if (i >= columns) return@forEachIndexed
            val cx = sidePad + i * (coverW + gap) + coverW / 2f
            val s = TextUtils.ellipsize(t, labelPaint, coverW - 4 * d, TextUtils.TruncateAt.END).toString()
            canvas.drawText(s, cx, ly, labelPaint)
        }
    }

    /** Thick top piece of the cabinet. */
    class Crown(ctx: Context, dark: Boolean) : View(ctx) {
        private val wood = Wood(dark)
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            paint.shader = LinearGradient(0f, 0f, 0f, h, wood.crownTop, wood.crownBottom, Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, w, h, paint)
            paint.shader = null
            paint.color = wood.highlight
            canvas.drawRect(0f, 0f, w, resources.displayMetrics.density * 1.5f, paint)
        }
    }
}

/**
 * A book cover standing on the shelf: drop shadow, page block (thickness), rounded corners, spine shading,
 * gloss, reading-progress bar and a "⋮" button. Without a bitmap it draws a generated placeholder book with "?".
 */
class CoverView(ctx: Context) : View(ctx) {
    private val d = resources.displayMetrics.density
    var book: Book? = null
    var progress = 0f
    var bitmap: Bitmap? = null
        set(v) { field = v; shaderFor = null; invalidate() }
    var onOpen: ((Book) -> Unit)? = null
    var onMenu: ((Book) -> Unit)? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val chipPath = Path()
    private val rect = RectF()
    private var shaderFor: Bitmap? = null
    private var shader: BitmapShader? = null
    private var downX = 0f
    private var downY = 0f

    private val gestures = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val b = book ?: return false
            playSoundEffect(android.view.SoundEffectConstants.CLICK)
            if (inMenuZone(e.x, e.y)) onMenu?.invoke(b) else onOpen?.invoke(b)
            return true
        }
        override fun onLongPress(e: MotionEvent) {
            val b = book ?: return
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            onMenu?.invoke(b)
        }
    })

    init {
        // shadow layers on arbitrary shapes need software rendering before Android 9
        if (Build.VERSION.SDK_INT < 28) setLayerType(LAYER_TYPE_SOFTWARE, null)
        isClickable = true
        isFocusable = true
    }

    private fun bodyRect(): RectF {
        rect.set(0f, 0f, (width - extraW(d)).toFloat().coerceAtLeast(10f), (height - extraH(d)).toFloat().coerceAtLeast(10f))
        return rect
    }

    private fun inMenuZone(x: Float, y: Float): Boolean {
        val r = bodyRect()
        return x > r.right - 34 * d && y < r.top + 34 * d
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) { downX = event.x; downY = event.y }
        val handled = gestures.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_DOWN) { animate().scaleX(0.97f).scaleY(0.97f).setDuration(90).start() }
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL ||
            (event.actionMasked == MotionEvent.ACTION_MOVE && (abs(event.x - downX) > 12 * d || abs(event.y - downY) > 12 * d))
        ) animate().scaleX(1f).scaleY(1f).setDuration(120).start()
        return handled || true
    }

    override fun performClick(): Boolean {
        super.performClick()
        book?.let { onOpen?.invoke(it) }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        val b = book ?: return
        val body = bodyRect()
        val r = 4 * d
        val thick = 4 * d

        // soft contact shadow on the plank
        shadowPaint.shader = RadialGradient(body.centerX() + thick, body.bottom, body.width() * 0.65f,
            0x70000000, 0x00000000, Shader.TileMode.CLAMP)
        canvas.save()
        canvas.scale(1f, 0.16f, body.centerX(), body.bottom)
        canvas.drawCircle(body.centerX() + thick, body.bottom, body.width() * 0.65f, shadowPaint)
        canvas.restore()
        shadowPaint.shader = null

        // drop shadow
        shadowPaint.color = 0xFF000000.toInt()
        shadowPaint.setShadowLayer(7 * d, 3 * d, 2 * d, 0x88000000.toInt())
        canvas.drawRoundRect(body.left + 1, body.top + 2, body.right + thick - 1, body.bottom, r, r, shadowPaint)
        shadowPaint.clearShadowLayer()

        // page block (book thickness) on the right
        paint.shader = LinearGradient(body.right, 0f, body.right + thick, 0f, 0xFFF7F0E1.toInt(), 0xFFD9CCB3.toInt(), Shader.TileMode.CLAMP)
        canvas.drawRoundRect(body.right - r, body.top + 2.5f * d, body.right + thick, body.bottom - 1.5f * d, r, r, paint)
        paint.shader = null
        paint.color = 0x22000000
        paint.strokeWidth = 0.6f * d
        var py = body.top + 5 * d
        while (py < body.bottom - 3 * d) {
            canvas.drawLine(body.right, py, body.right + thick - 1 * d, py, paint)
            py += 2.2f * d
        }

        // cover body
        path.reset()
        path.addRoundRect(body, floatArrayOf(2 * d, 2 * d, r, r, r, r, 2 * d, 2 * d), Path.Direction.CW)
        canvas.save()
        canvas.clipPath(path)
        // opaque white base so a bitmap with any transparency never shows the dark backdrop through it
        paint.color = Color.WHITE
        canvas.drawRect(body, paint)
        val bmp = bitmap
        when {
            b.isAudio -> drawDisc(canvas, body, b)
            bmp != null && !bmp.isRecycled -> drawBitmapCover(canvas, body, bmp)
            else -> drawPlaceholder(canvas, body, b)
        }

        // thin spine highlight on the very left edge (no dark scrim over the artwork)
        paint.shader = LinearGradient(body.left, 0f, body.left + body.width() * 0.06f, 0f,
            intArrayOf(0x2E000000, 0x30FFFFFF, 0x00FFFFFF), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRect(body.left, body.top, body.left + body.width() * 0.06f, body.bottom, paint)
        // light diagonal gloss only (lightens, never darkens)
        paint.shader = LinearGradient(body.left, body.top, body.left + body.width() * 0.8f, body.top + body.height() * 0.5f,
            0x22FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
        canvas.drawRect(body, paint)
        paint.shader = null

        // reading progress
        if (progress > 0.001f) {
            val bh = 4.5f * d
            val inset = 7 * d
            val top = body.bottom - inset - bh
            paint.color = 0x73000000
            canvas.drawRoundRect(body.left + inset, top, body.right - inset, top + bh, bh / 2, bh / 2, paint)
            paint.color = if (progress >= 0.995f) 0xFF34C759.toInt() else 0xFFFFB340.toInt()
            val pw = (body.width() - 2 * inset) * progress.coerceIn(0.04f, 1f)
            canvas.drawRoundRect(body.left + inset, top, body.left + inset + pw, top + bh, bh / 2, bh / 2, paint)
            // percent pill
            val label = if (progress >= 0.995f) "✓" else "${(progress * 100).toInt()}%"
            textPaint.textSize = 10f * d
            textPaint.typeface = Typeface.DEFAULT_BOLD
            textPaint.color = Color.WHITE
            textPaint.textAlign = Paint.Align.CENTER
            val tw = textPaint.measureText(label) + 10 * d
            val ph = 16 * d
            val px = body.left + body.width() * 0.12f + 2 * d
            val pyTop = top - ph - 4 * d
            paint.color = 0x99000000.toInt()
            canvas.drawRoundRect(px, pyTop, px + tw, pyTop + ph, ph / 2, ph / 2, paint)
            canvas.drawText(label, px + tw / 2, pyTop + ph / 2 - (textPaint.descent() + textPaint.ascent()) / 2, textPaint)
        }

        // type + optional tag badges: tag chips flush to the LEFT edge, stacked on separate lines
        val typeColor = when (b.type) {
            "pdf" -> 0xF2D64540.toInt()
            "audio" -> 0xF28E5CF7.toInt()
            else -> 0xF22E7BD6.toInt()
        }
        var by = body.top + 9 * d
        by = drawBadge(canvas, body.left, by, b.typeBadge, typeColor)
        if (b.tag.isNotEmpty() && b.tag != b.typeBadge) drawBadge(canvas, body.left, by, b.tag, 0xF21E9E5A.toInt())
        canvas.restore()

        // menu button
        val cx = body.right - 15 * d
        val cy = body.top + 15 * d
        paint.color = 0x80000000.toInt()
        canvas.drawCircle(cx, cy, 11 * d, paint)
        paint.color = Color.WHITE
        for (k in -1..1) canvas.drawCircle(cx, cy + k * 4.2f * d, 1.6f * d, paint)

        // subtle edge
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 0.8f
        paint.color = 0x33000000
        canvas.drawPath(path, paint)
        paint.style = Paint.Style.FILL
    }

    private fun drawBitmapCover(canvas: Canvas, body: RectF, bmp: Bitmap) {
        if (shaderFor !== bmp || shader == null) {
            shader = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            shaderFor = bmp
        }
        val s = maxOf(body.width() / bmp.width, body.height() / bmp.height)
        val m = Matrix()
        m.setScale(s, s)
        m.postTranslate(body.left + (body.width() - bmp.width * s) / 2f, body.top + (body.height() - bmp.height * s) / 2f)
        shader!!.setLocalMatrix(m)
        paint.shader = shader
        canvas.drawRect(body, paint)
        paint.shader = null
    }

    /**
     * A little tag chip hugging the LEFT edge of the cover (left corners square, right corners rounded).
     * [top] is the chip's top; returns the top for the next stacked chip below it.
     */
    private fun drawBadge(canvas: Canvas, left: Float, top: Float, s: String, bg: Int): Float {
        textPaint.textSize = 9f * d
        textPaint.typeface = Typeface.DEFAULT_BOLD
        textPaint.textAlign = Paint.Align.LEFT
        val padH = 6 * d
        val h = 16 * d
        val w = textPaint.measureText(s) + padH * 2
        val rad = h / 2
        chipPath.reset()
        chipPath.addRoundRect(left, top, left + w, top + h,
            floatArrayOf(0f, 0f, rad, rad, rad, rad, 0f, 0f), Path.Direction.CW)
        paint.color = bg
        canvas.drawPath(chipPath, paint)
        textPaint.color = Color.WHITE
        canvas.drawText(s, left + padH, top + h / 2 - (textPaint.descent() + textPaint.ascent()) / 2, textPaint)
        return top + h + 5 * d
    }

    /** Vinyl/CD illustration for an audiobook cover. */
    private fun drawDisc(canvas: Canvas, body: RectF, b: Book) {
        // warm backdrop
        paint.shader = LinearGradient(body.left, body.top, body.right, body.bottom, 0xFF3A2F52.toInt(), 0xFF1C1730.toInt(), Shader.TileMode.CLAMP)
        canvas.drawRect(body, paint)
        paint.shader = null
        val cx = body.centerX()
        val cy = body.top + body.height() * 0.42f
        val rad = minOf(body.width(), body.height()) * 0.33f
        // disc body
        paint.shader = RadialGradient(cx - rad * 0.3f, cy - rad * 0.3f, rad * 1.6f, 0xFF2B2B2E.toInt(), 0xFF050507.toInt(), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, rad, paint)
        paint.shader = null
        // grooves
        paint.style = Paint.Style.STROKE
        paint.color = 0x22FFFFFF
        paint.strokeWidth = 0.7f * d
        var gr = rad * 0.45f
        while (gr < rad * 0.95f) { canvas.drawCircle(cx, cy, gr, paint); gr += 2.4f * d }
        // sheen
        paint.color = 0x33FFFFFF
        paint.strokeWidth = rad * 0.5f
        canvas.drawArc(RectF(cx - rad * 0.7f, cy - rad * 0.7f, cx + rad * 0.7f, cy + rad * 0.7f), -60f, 40f, false, paint)
        paint.style = Paint.Style.FILL
        // centre label
        val lab = 0xFFE8890C.toInt()
        paint.shader = RadialGradient(cx, cy, rad * 0.4f, lighten(lab, 0.25f), darken(lab, 0.15f), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, rad * 0.4f, paint)
        paint.shader = null
        // spindle hole
        paint.color = 0xFF151518.toInt()
        canvas.drawCircle(cx, cy, rad * 0.08f, paint)
        // play triangle on the label
        paint.color = 0xFFFFFFFF.toInt()
        val tr = rad * 0.16f
        path.reset()
        path.moveTo(cx - tr * 0.5f, cy - tr); path.lineTo(cx - tr * 0.5f, cy + tr); path.lineTo(cx + tr, cy); path.close()
        // offset triangle above the hole so it stays visible
        canvas.save(); canvas.translate(0f, -rad * 0.0f); canvas.drawPath(path, paint); canvas.restore()
        // title
        textPaint.color = 0xFFEDE6FF.toInt()
        textPaint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textPaint.textSize = (body.width() * 0.1f).coerceIn(9 * d, 14 * d)
        val tw = (body.width() * 0.84f).toInt().coerceAtLeast(10)
        val layout = staticLayout(b.title, textPaint, tw)
        canvas.save()
        canvas.translate(cx - tw / 2f, cy + rad + 8 * d)
        layout.draw(canvas)
        canvas.restore()
    }

    /** Generated cover: cloth-like colour from the title hash, gold frame, a big "?" medallion and the title. */
    private fun drawPlaceholder(canvas: Canvas, body: RectF, b: Book) {
        val hues = intArrayOf(0xFFB5453E.toInt(), 0xFF2E7E97.toInt(), 0xFF3E8A57.toInt(), 0xFF7E55A6.toInt(),
            0xFFC07A24.toInt(), 0xFF3D5C94.toInt(), 0xFF9A3C66.toInt(), 0xFF5E7A34.toInt())
        val base = hues[abs(b.title.hashCode()) % hues.size]
        paint.shader = LinearGradient(body.left, body.top, body.right, body.bottom, lighten(base, 0.22f), darken(base, 0.12f), Shader.TileMode.CLAMP)
        canvas.drawRect(body, paint)
        paint.shader = null
        // cloth texture
        paint.color = 0x0DFFFFFF
        var yy = body.top
        while (yy < body.bottom) { canvas.drawRect(body.left, yy, body.right, yy + 1, paint); yy += 3 * d }
        // gold frame
        val gold = 0xFFE3C27A.toInt()
        paint.style = Paint.Style.STROKE
        paint.color = gold
        paint.strokeWidth = 1.2f * d
        val inset = body.width() * 0.1f
        canvas.drawRoundRect(body.left + inset + body.width() * 0.06f, body.top + inset, body.right - inset, body.bottom - inset, 3 * d, 3 * d, paint)
        paint.strokeWidth = 0.6f * d
        canvas.drawRoundRect(body.left + inset + body.width() * 0.06f + 3 * d, body.top + inset + 3 * d, body.right - inset - 3 * d, body.bottom - inset - 3 * d, 2 * d, 2 * d, paint)
        paint.style = Paint.Style.FILL
        // medallion with "?"
        val cx = body.centerX() + body.width() * 0.03f
        val cy = body.top + body.height() * 0.40f
        val rad = body.width() * 0.22f
        paint.shader = RadialGradient(cx - rad * 0.3f, cy - rad * 0.3f, rad * 1.3f, 0xFFF6E3AE.toInt(), 0xFFB8913F.toInt(), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, rad, paint)
        paint.shader = null
        textPaint.color = darken(base, 0.1f)
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        textPaint.textSize = rad * 1.45f
        canvas.drawText("?", cx, cy - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint)
        // title
        textPaint.color = 0xFFF5E6C4.toInt()
        textPaint.typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        textPaint.textSize = (body.width() * 0.105f).coerceIn(9 * d, 15 * d)
        val tw = (body.width() - 2 * inset - body.width() * 0.08f).toInt().coerceAtLeast(10)
        val layout = staticLayout(b.title, textPaint, tw)
        canvas.save()
        canvas.translate(cx - tw / 2f, cy + rad + 8 * d)
        layout.draw(canvas)
        canvas.restore()
    }

    @Suppress("DEPRECATION")
    private fun staticLayout(s: String, tp: TextPaint, w: Int): StaticLayout {
        tp.textAlign = Paint.Align.LEFT
        return if (Build.VERSION.SDK_INT >= 23) {
            StaticLayout.Builder.obtain(s, 0, s.length, tp, w).setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setMaxLines(3).setEllipsize(TextUtils.TruncateAt.END).build()
        } else StaticLayout(s, tp, w, Layout.Alignment.ALIGN_CENTER, 1f, 0f, false)
    }

    private fun lighten(c: Int, f: Float) = Color.rgb(
        (Color.red(c) + (255 - Color.red(c)) * f).toInt(), (Color.green(c) + (255 - Color.green(c)) * f).toInt(),
        (Color.blue(c) + (255 - Color.blue(c)) * f).toInt()
    )

    private fun darken(c: Int, f: Float) = Color.rgb((Color.red(c) * (1 - f)).toInt(), (Color.green(c) * (1 - f)).toInt(), (Color.blue(c) * (1 - f)).toInt())

    companion object {
        /** extra width for the page block + drop shadow; extra height for shadow below */
        fun extraW(d: Float) = (10 * d).toInt()
        fun extraH(d: Float) = (6 * d).toInt()
    }
}
