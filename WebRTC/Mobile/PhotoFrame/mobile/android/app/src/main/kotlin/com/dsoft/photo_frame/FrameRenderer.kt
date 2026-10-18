package com.dsoft.photo_frame

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import org.json.JSONObject
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Look of one frame (from frames.json, edited in the app). Sizes are "units" = 1/180 of the widget's short side. */
class FrameConfig(val j: JSONObject) {
    val id get() = j.optString("id")
    val name get() = j.optString("name")
    val count get() = j.optInt("count", 1).coerceIn(1, 4)
    val shape get() = j.optString("shape", "rounded")
    val corner get() = j.optDouble("corner", 18.0).toFloat()
    val border get() = j.optDouble("border", 4.0).toFloat()
    val borderColor get() = j.optLong("borderColor", 0xFFFFFFFF).toInt()
    val matColor get() = j.optLong("matColor", 0xFFFFFFFF).toInt()
    val padding get() = j.optDouble("padding", 4.0).toFloat()
    val gap get() = j.optDouble("gap", 3.0).toFloat()
    val photoRadius get() = j.optDouble("photoRadius", 6.0).toFloat()
    val opacity get() = j.optDouble("opacity", 1.0).toFloat().coerceIn(0f, 1f)
    val shadow get() = j.optBoolean("shadow", true)
    val shadowSize get() = j.optDouble("shadowSize", 8.0).toFloat()
    val gloss get() = j.optBoolean("gloss", false)
    val filter get() = j.optString("filter", "none")
    val showDate get() = j.optBoolean("showDate", false)
    val order get() = j.optString("order", "sequential")
    val interval get() = j.optInt("interval", 60)
    /** Tapping the photo: "app" opens the app, anything else opens the photo (▸ is for next). */
    val tap get() = if (j.optString("tap") == "app") "app" else "open"
    val nextButton get() = j.optBoolean("nextButton", true)
}

/** A photo: "asset:samples/x.jpg" or a content:// URI. */
data class PhotoInfo(val ref: String, val aspect: Float, val rotation: Int, val date: Long)

object FrameRenderer {
    val shapes = listOf(
        "rect", "rounded", "squircle", "circle", "oval", "heart", "star",
        "hexagon", "diamond", "arch", "flower", "stamp", "polaroid",
    )

    // ------------------------------------------------------------- photos

    private fun open(ctx: Context, ref: String): InputStream? = try {
        if (ref.startsWith("asset:")) ctx.assets.open(ref.removePrefix("asset:"))
        else ctx.contentResolver.openInputStream(Uri.parse(ref))
    } catch (_: Exception) {
        null
    }

    /** Size/orientation/date without decoding pixels. */
    fun probe(ctx: Context, ref: String): PhotoInfo? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        // Bounds-only decode always returns null by design: check the stream, then the size.
        (open(ctx, ref) ?: return null).use { BitmapFactory.decodeStream(it, null, o) }
        if (o.outWidth <= 0 || o.outHeight <= 0) return null
        var rot = 0
        var date = 0L
        try {
            open(ctx, ref)?.use {
                val ex = ExifInterface(it)
                rot = ex.rotationDegrees
                val s = ex.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: ex.getAttribute(ExifInterface.TAG_DATETIME)
                if (s != null) date = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(s)?.time ?: 0L
            }
        } catch (_: Exception) {
        }
        val w = if (rot % 180 == 0) o.outWidth else o.outHeight
        val h = if (rot % 180 == 0) o.outHeight else o.outWidth
        return PhotoInfo(ref, w.toFloat() / h, rot, date)
    }

    /** Decodes with sub-sampling to roughly [reqW]x[reqH], applying EXIF rotation. */
    private fun decode(ctx: Context, p: PhotoInfo, reqW: Int, reqH: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        (open(ctx, p.ref) ?: return null).use { BitmapFactory.decodeStream(it, null, o) }
        if (o.outWidth <= 0 || o.outHeight <= 0) return null
        val (sw, sh) = if (p.rotation % 180 == 0) o.outWidth to o.outHeight else o.outHeight to o.outWidth
        var sample = 1
        while (sw / (sample * 2) >= reqW && sh / (sample * 2) >= reqH) sample *= 2
        val bmp = open(ctx, p.ref)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        if (p.rotation == 0) return bmp
        val m = Matrix().apply { postRotate(p.rotation.toFloat()) }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }

    // ------------------------------------------------------------- shapes

    /** Outline of [shape] fitted into [r]. Square-ish shapes are centred. */
    fun shapePath(shape: String, r0: RectF, corner: Float): Path {
        val p = Path()
        val square = shape in setOf("circle", "heart", "star", "hexagon", "flower", "diamond")
        val r = if (square) {
            val s = min(r0.width(), r0.height())
            RectF(r0.centerX() - s / 2, r0.centerY() - s / 2, r0.centerX() + s / 2, r0.centerY() + s / 2)
        } else r0
        val cx = r.centerX()
        val cy = r.centerY()
        val w = r.width()
        val h = r.height()
        when (shape) {
            "rect", "polaroid" -> p.addRoundRect(r, corner * 0.15f, corner * 0.15f, Path.Direction.CW)
            "rounded" -> p.addRoundRect(r, corner, corner, Path.Direction.CW)
            "squircle" -> {
                // Superellipse (iOS app icon curve).
                val n = 5.0
                for (i in 0..180) {
                    val t = 2 * Math.PI * i / 180
                    val ct = cos(t)
                    val st = sin(t)
                    val x = cx + w / 2 * Math.signum(ct).toFloat() * Math.pow(kotlin.math.abs(ct), 2 / n).toFloat()
                    val y = cy + h / 2 * Math.signum(st).toFloat() * Math.pow(kotlin.math.abs(st), 2 / n).toFloat()
                    if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
                }
                p.close()
            }
            "circle" -> p.addCircle(cx, cy, w / 2, Path.Direction.CW)
            "oval" -> p.addOval(r, Path.Direction.CW)
            "heart" -> {
                fun x(v: Float) = r.left + v * w
                fun y(v: Float) = r.top + (0.04f + v * 0.94f) * h
                p.moveTo(x(0.5f), y(0.24f))
                p.cubicTo(x(0.5f), y(0.1f), x(0.33f), y(0f), x(0.18f), y(0.02f))
                p.cubicTo(x(-0.02f), y(0.06f), x(-0.04f), y(0.42f), x(0.12f), y(0.6f))
                p.lineTo(x(0.5f), y(1f))
                p.lineTo(x(0.88f), y(0.6f))
                p.cubicTo(x(1.04f), y(0.42f), x(1.02f), y(0.06f), x(0.82f), y(0.02f))
                p.cubicTo(x(0.67f), y(0f), x(0.5f), y(0.1f), x(0.5f), y(0.24f))
                p.close()
            }
            "star" -> {
                for (i in 0 until 10) {
                    val a = -Math.PI / 2 + i * Math.PI / 5
                    val rad = if (i % 2 == 0) w / 2 else w / 2 * 0.5f
                    val x = cx + rad * cos(a).toFloat()
                    val y = cy + rad * sin(a).toFloat() + h * 0.04f
                    if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
                }
                p.close()
            }
            "hexagon" -> {
                for (i in 0 until 6) {
                    val a = Math.PI / 6 + i * Math.PI / 3
                    val x = cx + w / 2 * cos(a).toFloat()
                    val y = cy + h / 2 * sin(a).toFloat()
                    if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
                }
                p.close()
            }
            "diamond" -> {
                p.moveTo(cx, r.top); p.lineTo(r.right, cy); p.lineTo(cx, r.bottom); p.lineTo(r.left, cy); p.close()
            }
            "arch" -> {
                val rad = w / 2
                p.moveTo(r.left, r.bottom)
                p.lineTo(r.left, r.top + rad)
                p.arcTo(RectF(r.left, r.top, r.right, r.top + 2 * rad), 180f, 180f)
                p.lineTo(r.right, r.bottom)
                p.close()
            }
            "flower" -> {
                // Wavy circle: r = R(1 + 0.07 sin 8θ).
                for (i in 0..240) {
                    val t = 2 * Math.PI * i / 240
                    val rad = w / 2 * 0.93f * (1 + 0.07f * sin(8 * t).toFloat())
                    val x = cx + rad * cos(t).toFloat()
                    val y = cy + rad * sin(t).toFloat()
                    if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
                }
                p.close()
            }
            "stamp" -> {
                // Postage stamp: rectangle with semicircle bites along every edge.
                p.addRect(r, Path.Direction.CW)
                val bite = max(3f, min(w, h) / 24f)
                val holes = Path()
                var x = r.left + bite * 1.2f
                while (x < r.right - bite) {
                    holes.addCircle(x, r.top, bite / 1.6f, Path.Direction.CW)
                    holes.addCircle(x, r.bottom, bite / 1.6f, Path.Direction.CW)
                    x += bite * 2.2f
                }
                var y = r.top + bite * 1.2f
                while (y < r.bottom - bite) {
                    holes.addCircle(r.left, y, bite / 1.6f, Path.Direction.CW)
                    holes.addCircle(r.right, y, bite / 1.6f, Path.Direction.CW)
                    y += bite * 2.2f
                }
                p.op(holes, Path.Op.DIFFERENCE)
            }
            else -> p.addRoundRect(r, corner, corner, Path.Direction.CW)
        }
        return p
    }

    /** Bounds where photos may go (inside the shape). */
    private fun photoArea(shape: String, outline: RectF): RectF {
        val w = outline.width()
        val h = outline.height()
        return when (shape) {
            // inscribed boxes of the curvy shapes, so photos are not mostly clipped away
            "circle", "flower" -> RectF(outline).apply { inset(w * 0.08f, h * 0.08f) }
            "heart" -> RectF(outline.left + w * 0.12f, outline.top + h * 0.12f, outline.right - w * 0.12f, outline.bottom - h * 0.25f)
            "star" -> RectF(outline).apply { inset(w * 0.22f, h * 0.22f) }
            "diamond" -> RectF(outline).apply { inset(w * 0.2f, h * 0.2f) }
            "hexagon" -> RectF(outline).apply { inset(w * 0.06f, h * 0.06f) }
            else -> outline
        }
    }

    private fun squareFit(shape: String, r: RectF): RectF {
        if (shape !in setOf("circle", "heart", "star", "hexagon", "flower", "diamond")) return r
        val s = min(r.width(), r.height())
        return RectF(r.centerX() - s / 2, r.centerY() - s / 2, r.centerX() + s / 2, r.centerY() + s / 2)
    }

    // ------------------------------------------------------------- layout

    /** Crop lost when a photo of aspect [p] fills a cell of aspect [c] (0 = perfect). */
    private fun loss(p: Float, c: Float) = 1f - min(p / c, c / p)

    /**
     * Places photos (aspects [a]) into [r]: tries every ordering and every way
     * to split them into rows or columns (justified like a photo mosaic) and
     * keeps the layout that crops the photos least. Cells are not equal.
     */
    fun layout(r: RectF, a: List<Float>, gap: Float): List<RectF> {
        val n = a.size
        if (n <= 1) return listOf(RectF(r))
        var best: List<RectF>? = null
        var bestScore = Float.MAX_VALUE
        for (perm in permutations((0 until n).toList())) {
            for (groups in compositions(n)) {
                for (vertical in listOf(false, true)) {
                    val cells = justified(r, perm, groups, a, gap, vertical) ?: continue
                    var score = 0f
                    val minSide = min(r.width(), r.height())
                    for ((k, idx) in perm.withIndex()) {
                        val c = cells[k]
                        score += loss(a[idx], c.width() / c.height())
                        if (min(c.width(), c.height()) < minSide * 0.2f) score += 0.6f
                    }
                    // Prefer keeping the given order when equally good.
                    if (perm != (0 until n).toList()) score += 0.01f
                    if (score < bestScore) {
                        bestScore = score
                        val ordered = MutableList(n) { RectF() }
                        for ((k, idx) in perm.withIndex()) ordered[idx] = cells[k]
                        best = ordered
                    }
                }
            }
        }
        return best ?: listOf(RectF(r))
    }

    /** Rows (or columns when [vertical]) of photos; each line's size follows its photos' natural ratio. */
    private fun justified(r: RectF, perm: List<Int>, groups: List<Int>, a: List<Float>, gap: Float, vertical: Boolean): List<RectF>? {
        val W = if (vertical) r.height() else r.width()
        val H = if (vertical) r.width() else r.height()
        // aspect seen along the line direction
        fun asp(i: Int) = if (vertical) 1f / a[i] else a[i]
        var start = 0
        val lines = groups.map { size -> perm.subList(start, start + size).also { start += size } }
        val natural = lines.map { line -> (W - gap * (line.size - 1)) / line.sumOf { asp(it).toDouble() }.toFloat() }
        val total = natural.sum()
        val avail = H - gap * (lines.size - 1)
        if (avail <= 0 || W <= 0) return null
        val out = ArrayList<RectF>()
        var y = 0f
        for ((li, line) in lines.withIndex()) {
            val lh = natural[li] / total * avail
            val sum = line.sumOf { asp(it).toDouble() }.toFloat()
            var x = 0f
            val lineW = W - gap * (line.size - 1)
            for (i in line) {
                val cw = lineW * asp(i) / sum
                out += if (vertical) RectF(r.left + y, r.top + x, r.left + y + lh, r.top + x + cw)
                else RectF(r.left + x, r.top + y, r.left + x + cw, r.top + y + lh)
                x += cw + gap
            }
            y += lh + gap
        }
        return out
    }

    private fun compositions(n: Int): List<List<Int>> {
        if (n == 0) return listOf(emptyList())
        val out = ArrayList<List<Int>>()
        for (first in 1..n) for (rest in compositions(n - first)) out += listOf(first) + rest
        return out
    }

    private fun permutations(l: List<Int>): List<List<Int>> =
        if (l.size <= 1) listOf(l) else l.flatMap { x -> permutations(l - x).map { listOf(x) + it } }

    // ------------------------------------------------------------- filters

    private fun filterOf(name: String): ColorMatrixColorFilter? {
        val m = ColorMatrix()
        when (name) {
            "bw" -> m.setSaturation(0f)
            "sepia" -> {
                m.setSaturation(0f)
                m.postConcat(ColorMatrix(floatArrayOf(1.07f, 0f, 0f, 0f, 18f, 0f, 0.98f, 0f, 0f, 6f, 0f, 0f, 0.82f, 0f, -12f, 0f, 0f, 0f, 1f, 0f)))
            }
            "warm" -> m.set(floatArrayOf(1.08f, 0f, 0f, 0f, 10f, 0f, 1.02f, 0f, 0f, 4f, 0f, 0f, 0.9f, 0f, -6f, 0f, 0f, 0f, 1f, 0f))
            "cool" -> m.set(floatArrayOf(0.92f, 0f, 0f, 0f, -4f, 0f, 1.0f, 0f, 0f, 2f, 0f, 0f, 1.1f, 0f, 12f, 0f, 0f, 0f, 1f, 0f))
            "vintage" -> {
                m.setSaturation(0.55f)
                m.postConcat(ColorMatrix(floatArrayOf(1.05f, 0f, 0f, 0f, 20f, 0f, 0.98f, 0f, 0f, 14f, 0f, 0f, 0.85f, 0f, 6f, 0f, 0f, 0f, 1f, 0f)))
            }
            "vivid" -> m.setSaturation(1.4f)
            else -> return null
        }
        return ColorMatrixColorFilter(m)
    }

    // ------------------------------------------------------------- render

    private fun withAlpha(c: Int, f: Float) = Color.argb((Color.alpha(c) * f).toInt(), Color.red(c), Color.green(c), Color.blue(c))

    /**
     * Draws the frame with [photos] into a [w]x[h] bitmap. Every size is
     * proportional to the bitmap, so resizing the widget scales the design.
     */
    fun render(ctx: Context, cfg: FrameConfig, photos: List<PhotoInfo>, w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w.coerceAtLeast(8), h.coerceAtLeast(8), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val unit = min(w, h) / 180f
        val shape = cfg.shape
        val shadowPx = if (cfg.shadow) cfg.shadowSize * unit else 0f
        val margin = max(1f, shadowPx * 1.1f)
        val outline = squareFit(shape, RectF(margin, margin * 0.7f, w - margin, h - margin * 1.3f))
        val corner = cfg.corner * unit
        val path = shapePath(shape, outline, corner)
        val opacity = cfg.opacity

        // 1. mat (frame background) + soft drop shadow
        val mat = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = withAlpha(cfg.matColor, opacity)
            if (shadowPx > 0) setShadowLayer(shadowPx, 0f, shadowPx * 0.35f, Color.argb((90 * max(opacity, 0.35f)).toInt(), 0, 0, 0))
        }
        if (shadowPx > 0 && Color.alpha(mat.color) < 40) {
            // Nearly transparent mat: draw the shadow from an opaque copy, then clear it.
            val sh = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(70, 0, 0, 0)
                maskFilter = BlurMaskFilter(shadowPx, BlurMaskFilter.Blur.NORMAL)
            }
            c.save(); c.translate(0f, shadowPx * 0.35f); c.drawPath(path, sh); c.restore()
            mat.clearShadowLayer()
        }
        c.drawPath(path, mat)

        // 2. photos, clipped inside the border
        val bw = cfg.border * unit
        val inner = RectF(outline).apply { inset(bw + cfg.padding * unit, bw + cfg.padding * unit) }
        if (shape == "polaroid") inner.bottom -= (outline.height() * 0.13f)
        val area = photoArea(shape, inner)
        val clip = shapePath(shape, RectF(outline).apply { inset(bw, bw) }, max(0f, corner - bw))
        c.save()
        c.clipPath(clip)
        val gapPx = cfg.gap * unit
        val shown = photos.take(cfg.count)
        if (shown.isEmpty()) {
            val ph = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(area.left, area.top, area.right, area.bottom, Color.parseColor("#FFD3E0"), Color.parseColor("#C9D7FF"), Shader.TileMode.CLAMP)
            }
            c.drawRect(area, ph)
            val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#88000000"); textSize = 12 * unit; textAlign = Paint.Align.CENTER
            }
            c.drawText("Chưa có ảnh", area.centerX(), area.centerY(), tp)
        } else {
            val cells = layout(area, shown.map { it.aspect }, gapPx)
            val pr = cfg.photoRadius * unit
            val photoPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { colorFilter = filterOf(cfg.filter) }
            for ((i, p) in shown.withIndex()) {
                val cell = cells[i]
                val img = decode(ctx, p, cell.width().toInt(), cell.height().toInt()) ?: continue
                // centre-crop
                val ca = cell.width() / cell.height()
                val ia = img.width.toFloat() / img.height
                val src = if (ia > ca) {
                    val sw = img.height * ca
                    android.graphics.Rect(((img.width - sw) / 2).toInt(), 0, ((img.width + sw) / 2).toInt(), img.height)
                } else {
                    val sh = img.width / ca
                    android.graphics.Rect(0, ((img.height - sh) / 2).toInt(), img.width, ((img.height + sh) / 2).toInt())
                }
                c.save()
                if (pr > 0) {
                    c.clipPath(Path().apply { addRoundRect(cell, pr, pr, Path.Direction.CW) })
                }
                c.drawBitmap(img, src, cell, photoPaint)
                c.restore()
                img.recycle()
            }
            if (cfg.showDate) {
                val d = shown.firstOrNull { it.date > 0 }?.date
                if (d != null) {
                    val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.WHITE
                        textSize = 11 * unit
                        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                        textAlign = Paint.Align.RIGHT
                        setShadowLayer(2.5f * unit, 0f, unit * 0.6f, Color.argb(160, 0, 0, 0))
                    }
                    val txt = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(d)
                    c.drawText(txt, area.right - 6 * unit, area.bottom - 6 * unit, tp)
                }
            }
        }
        // 3. glass sheen
        if (cfg.gloss) {
            val g = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(0f, outline.top, 0f, outline.top + outline.height() * 0.55f,
                    Color.argb(90, 255, 255, 255), Color.argb(0, 255, 255, 255), Shader.TileMode.CLAMP)
            }
            c.drawRect(outline, g)
        }
        c.restore()

        // 4. border on top
        if (bw > 0) {
            val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = bw
                strokeJoin = Paint.Join.ROUND
                color = withAlpha(cfg.borderColor, max(opacity, 0.15f))
            }
            c.drawPath(shapePath(shape, RectF(outline).apply { inset(bw / 2, bw / 2) }, max(0f, corner - bw / 2)), stroke)
        }
        return bmp
    }

    /** Caps the longer side at [maxSide] px (memory / file size). */
    fun fit(wPx: Int, hPx: Int, maxSide: Int = 1300): Pair<Int, Int> {
        val s = min(1f, maxSide.toFloat() / max(wPx, hPx))
        return (wPx * s).toInt().coerceAtLeast(16) to (hPx * s).toInt().coerceAtLeast(16)
    }

}
