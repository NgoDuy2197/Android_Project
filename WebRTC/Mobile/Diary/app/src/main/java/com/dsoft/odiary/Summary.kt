package com.dsoft.odiary

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Renders one day (all events: text + photos) into a single A4 PDF.
 * Ported from the old DailyDiary app and adapted to ODiary's [Store]/[DayEntry] model.
 * Output goes to cacheDir (exposed via file_paths cache-path) so it can be shared without
 * touching the synced diary folder.
 */
object Summary {
    private const val PAGE_W = 595 // A4 @ 72dpi
    private const val PAGE_H = 842
    private const val MARGIN = 40f
    private const val PHOTO_MAX_H = 360f

    private val timeFmt = SimpleDateFormat("HH:mm", Locale.US)

    /** Returns the PDF file in cacheDir, or null when the day has no events. */
    fun generate(ctx: Context, date: String): File? {
        val day = Store.loadDay(date)
        val entries = day.events.sortedBy { it.timeMs }
        if (entries.isEmpty()) return null

        val title = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 22f; typeface = Typeface.DEFAULT_BOLD; color = Color.rgb(58, 71, 184)
        }
        val sub = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11f; color = Color.GRAY }
        val time = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 13f; typeface = Typeface.DEFAULT_BOLD; color = Color.rgb(255, 112, 67)
        }
        val body = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 12f; color = Color.rgb(33, 33, 33) }
        val rule = Paint().apply { color = Color.rgb(224, 224, 224); strokeWidth = 1f }
        val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
        val contentW = (PAGE_W - 2 * MARGIN).toInt()

        val doc = PdfDocument()
        var pageNo = 0
        var page: PdfDocument.Page? = null
        var y = 0f

        fun newPage() {
            page?.let { doc.finishPage(it) }
            page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, ++pageNo).create())
            y = MARGIN
            page!!.canvas.drawText("Trang $pageNo", PAGE_W - MARGIN - 40, PAGE_H - 20f, sub)
        }
        fun ensure(h: Float) { if (y + h > PAGE_H - MARGIN) newPage() }
        fun drawLayout(l: StaticLayout) {
            for (i in 0 until l.lineCount) {
                val lh = (l.getLineBottom(i) - l.getLineTop(i)).toFloat()
                ensure(lh)
                val c = page!!.canvas
                c.save()
                c.translate(MARGIN, y - l.getLineTop(i))
                c.clipRect(0f, l.getLineTop(i).toFloat(), contentW.toFloat(), l.getLineBottom(i).toFloat())
                l.draw(c)
                c.restore()
                y += lh
            }
        }

        try {
            newPage()
            drawLayout(layout(prettyDate(date), title, contentW))
            y += 4
            val header = buildString {
                if (day.emoji.isNotEmpty() || day.label.isNotEmpty()) append("${day.emoji} ${day.label}  •  ")
                append("${entries.size} sự kiện • ${entries.sumOf { it.photos.size }} ảnh")
            }
            drawLayout(layout(header.trim(), sub, contentW))
            y += 12

            for (e in entries) {
                ensure(40f)
                page!!.canvas.drawLine(MARGIN, y, PAGE_W - MARGIN, y, rule)
                y += 14
                drawLayout(layout(timeFmt.format(Date(e.timeMs)), time, contentW))
                y += 4
                if (e.text.isNotBlank()) {
                    drawLayout(layout(e.text.trim(), body, contentW))
                    y += 6
                }
                for (name in e.photos) {
                    val f = Store.photoFile(date, name)
                    val bmp = Store.decodeSampled(f, 1000) ?: continue
                    val s = minOf(contentW / bmp.width.toFloat(), PHOTO_MAX_H / bmp.height)
                    val w = bmp.width * s
                    val h = bmp.height * s
                    ensure(h)
                    page!!.canvas.drawBitmap(bmp, null, RectF(MARGIN, y, MARGIN + w, y + h), bmpPaint)
                    bmp.recycle()
                    y += h + 8
                }
                y += 6
            }
            doc.finishPage(page!!)

            val out = File(ctx.cacheDir, "ODiary_$date.pdf")
            val tmp = File(ctx.cacheDir, out.name + ".tmp")
            tmp.outputStream().use { doc.writeTo(it) }
            if (!tmp.renameTo(out)) { out.delete(); tmp.renameTo(out) }
            return out
        } finally {
            doc.close()
        }
    }

    private fun prettyDate(date: String): String = try {
        SimpleDateFormat("EEEE, dd/MM/yyyy", Locale("vi")).format(SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(date)!!)
    } catch (_: Exception) { date }

    private fun layout(text: String, paint: TextPaint, width: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(2f, 1f)
            .build()
}
