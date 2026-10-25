package com.dsoft.odiary

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import java.io.File

/** Resolves and draws the diary page background (per-day override, else global, else plain paper). */
object DiaryBg {
    val templates = listOf("none", "tpl:paper", "tpl:grid", "tpl:dots", "tpl:clouds", "tpl:stars")
    fun label(spec: String): String = when (spec) {
        "", "inherit" -> "Theo cấu hình chung"
        "none" -> "Không nền"
        "tpl:paper" -> "Giấy trơn"
        "tpl:grid" -> "Kẻ ô"
        "tpl:dots" -> "Chấm bi"
        "tpl:clouds" -> "Mây"
        "tpl:stars" -> "Ngôi sao"
        "img" -> "Ảnh đã chọn"
        else -> spec
    }

    /** Effective background Drawable for a day, following per-day → global → paper. */
    fun resolve(ctx: Context, date: String): Drawable {
        val day = Store.loadDay(date)
        var spec = day.bg
        if (spec.isEmpty()) spec = Prefs.globalBg(ctx)
        if (spec.isEmpty()) spec = "none"
        val imgFile: File? = if (spec == "img") {
            if (day.bg == "img") Store.dayBgFile(date) else Store.globalBgFile()
        } else null
        return drawableFor(ctx, ctx.palette(), spec, imgFile)
    }

    fun drawableFor(ctx: Context, p: Palette, spec: String, imgFile: File?): Drawable = when {
        spec == "img" && imgFile != null && imgFile.exists() ->
            (Store.decodeSampled(imgFile, 1600)?.let { BitmapDrawable(ctx.resources, it) } ?: ColorDrawable(p.paper))
        spec.startsWith("tpl:") && spec != "tpl:paper" -> PatternDrawable(spec.removePrefix("tpl:"), p)
        else -> ColorDrawable(p.paper)
    }

    class PatternDrawable(private val kind: String, private val p: Palette) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val d = App.instance.resources.displayMetrics.density
        override fun draw(canvas: Canvas) {
            val b = bounds
            paint.color = p.paper; paint.style = Paint.Style.FILL
            canvas.drawRect(b, paint)
            val acc = Color.argb(40, Color.red(p.accent), Color.green(p.accent), Color.blue(p.accent))
            when (kind) {
                "grid" -> {
                    paint.color = acc; paint.strokeWidth = 1f * d
                    var x = b.left.toFloat(); val step = 28 * d
                    while (x < b.right) { canvas.drawLine(x, b.top.toFloat(), x, b.bottom.toFloat(), paint); x += step }
                    var y = b.top.toFloat(); while (y < b.bottom) { canvas.drawLine(b.left.toFloat(), y, b.right.toFloat(), y, paint); y += step }
                }
                "dots" -> {
                    paint.color = acc; val step = 30 * d; val r = 3f * d
                    var y = b.top + step; while (y < b.bottom) { var x = b.left + step; while (x < b.right) { canvas.drawCircle(x, y, r, paint); x += step }; y += step }
                }
                "clouds" -> {
                    paint.color = Color.argb(28, Color.red(p.accent2), Color.green(p.accent2), Color.blue(p.accent2))
                    val r = 60 * d
                    var i = 0
                    var y = b.top + 60 * d
                    while (y < b.bottom) {
                        var x = b.left + (if (i % 2 == 0) 40 * d else 140 * d)
                        while (x < b.right) { canvas.drawCircle(x, y, r, paint); x += 220 * d }
                        y += 160 * d; i++
                    }
                }
                "stars" -> {
                    paint.color = Color.argb(60, Color.red(p.accent), Color.green(p.accent), Color.blue(p.accent))
                    paint.textSize = 16 * d
                    val step = 70 * d; var i = 0; var y = b.top + step
                    while (y < b.bottom) { var x = b.left + (if (i % 2 == 0) 20 * d else 55 * d); while (x < b.right) { canvas.drawText("✦", x, y, paint); x += step }; y += step; i++ }
                }
            }
        }
        override fun setAlpha(a: Int) {}
        override fun setColorFilter(c: android.graphics.ColorFilter?) {}
        @Deprecated("deprecated") override fun getOpacity() = android.graphics.PixelFormat.OPAQUE
    }
}
