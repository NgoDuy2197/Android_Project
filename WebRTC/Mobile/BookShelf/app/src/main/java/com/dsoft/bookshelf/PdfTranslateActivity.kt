package com.dsoft.bookshelf

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.text.StaticLayout
import android.text.TextPaint
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * PDF translate viewer: renders a page, OCRs it into text blocks (with positions), translates each block,
 * and OVERLAYS the translation on top of the original page. Toggle "Đè bản dịch" to compare with the original.
 * (The other mode — a clean reflowed translated book for reading/TTS — is the existing "Dịch" job.)
 */
class PdfTranslateActivity : BaseActivity() {
    private lateinit var book: Book
    private var pfd: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    private var pageCount = 0
    private var page = 0
    private var overlayOn = true
    private val io = Executors.newSingleThreadExecutor()
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private var engine: TranslateEngine? = null
    private val cache = HashMap<Int, List<Block>>()   // page -> translated blocks

    private lateinit var overlay: OverlayView
    private lateinit var indicator: TextView
    private lateinit var status: TextView
    private lateinit var toggleBtn: TextView

    private class Block(val box: RectF, val text: String)

    override fun keepScreenOnHere() = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        book = Library.get(intent.getStringExtra(Readers.EXTRA_BOOK) ?: "") ?: run { finish(); return }
        page = intent.getIntExtra("page", 0)
        buildUi()
        open()
    }

    private fun buildUi() {
        setupSystemBars(p.bg, p.dark)
        val root = FrameLayout(this).apply { setBackgroundColor(if (p.dark) 0xFF1A1A1A.toInt() else 0xFFE5E5EA.toInt()) }
        overlay = OverlayView(this)
        root.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val bar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(6), dp(6), dp(8), dp(6))
            setBackgroundColor(if (p.dark) 0xEE1C1C1E.toInt() else 0xF0FFFFFF.toInt()); elevation = dpf(4)
        }
        val fg = if (p.dark) Color.WHITE else Color.BLACK
        bar.addView(circleButton(p, R.drawable.ic_back, p.blue, 40, bg = 0) { finish() })
        bar.addView(text(book.title, 15f, fg, 600).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END },
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(4) })
        toggleBtn = pill("Đè: BẬT") { overlayOn = !overlayOn; toggleBtn.text = "Đè: ${if (overlayOn) "BẬT" else "TẮT"}"; overlay.setOverlay(if (overlayOn) cache[page] else null) }
        bar.addView(toggleBtn)
        root.addView(bar, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        status = text("", 13f, Color.WHITE, 600).apply {
            setPadding(dp(12), dp(6), dp(12), dp(6)); background = rounded(0xAA000000.toInt(), dpf(14)); visibility = View.GONE
        }
        root.addView(status, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))

        indicator = text("", 13f, Color.WHITE, 600).apply {
            setPadding(dp(12), dp(6), dp(12), dp(6)); background = rounded(0xAA000000.toInt(), dpf(14))
        }
        root.addView(indicator, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(16) })

        val nav = LinearLayout(this).apply {
            addView(navBtn(R.drawable.ic_up) { step(-1) })
            addView(navBtn(R.drawable.ic_down) { step(1) })
        }
        root.addView(nav, FrameLayout.LayoutParams(-2, -2, Gravity.START or Gravity.BOTTOM).apply { marginStart = dp(14); bottomMargin = dp(36) })
        setContentView(root)
    }

    private fun navBtn(res: Int, f: () -> Unit) = android.widget.ImageView(this).apply {
        setImageResource(res); imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
        val pd = dp(10); setPadding(pd, pd, pd, pd)
        background = pressable(android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(0xFF000000.toInt())
        }, 0x55FFFFFF)
        setOnClickListener { f() }
        layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply { topMargin = dp(8) }
    }

    private fun pill(label: String, onClick: () -> Unit) = text(label, 14f, Color.WHITE, 700).apply {
        setPadding(dp(14), dp(8), dp(14), dp(8)); background = rounded(p.blue, dpf(18)); setOnClickListener { onClick() }
    }

    private fun open() {
        io.execute {
            try {
                val d = ParcelFileDescriptor.open(Library.file(book), ParcelFileDescriptor.MODE_READ_ONLY)
                pfd = d; val r = PdfRenderer(d); renderer = r; pageCount = r.pageCount
            } catch (e: Exception) {
                App.main.post { toast("Không mở được PDF"); finish() }; return@execute
            }
            App.main.post { showPage(page) }
        }
    }

    private fun step(d: Int) { val np = (page + d).coerceIn(0, pageCount - 1); if (np != page) showPage(np) }

    private fun showPage(idx: Int) {
        page = idx
        indicator.text = "${idx + 1} / $pageCount"
        toggleBtn.text = "Đè: ${if (overlayOn) "BẬT" else "TẮT"}"
        io.execute {
            val bmp = renderPage(idx) ?: return@execute
            App.main.post { overlay.setPage(bmp); overlay.setOverlay(if (overlayOn && cache.containsKey(idx)) cache[idx] else null) }
            if (overlayOn && !cache.containsKey(idx)) translatePage(idx, bmp)
        }
    }

    private fun renderPage(i: Int): Bitmap? {
        val r = renderer ?: return null
        return try {
            synchronized(r) {
                r.openPage(i).use { pg ->
                    val w = resources.displayMetrics.widthPixels
                    val h = (w.toFloat() * pg.height / pg.width).toInt().coerceAtLeast(1)
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    pg.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bmp
                }
            }
        } catch (_: Throwable) { null }
    }

    private fun translatePage(idx: Int, bmp: Bitmap) {
        App.main.post { status.visibility = View.VISIBLE; status.text = "Đang nhận chữ & dịch…" }
        try {
            val result = Tasks.await(recognizer.process(InputImage.fromBitmap(bmp, 0)), 60, TimeUnit.SECONDS)
            val blocks = result.textBlocks.mapNotNull { b -> b.boundingBox?.let { it to b.text } }.filter { it.second.isNotBlank() }
            if (blocks.isEmpty()) { App.main.post { status.visibility = View.GONE; toast("Trang này không có chữ để dịch") }; cache[idx] = emptyList(); return }
            val eng = engine(blocks.joinToString("\n") { it.second })
            val out = ArrayList<Block>()
            for ((box, txt) in blocks) {
                val tr = try { eng.translate(txt) } catch (e: Exception) { txt }
                out.add(Block(RectF(box), tr))
            }
            cache[idx] = out
            App.main.post { status.visibility = View.GONE; if (page == idx && overlayOn) overlay.setOverlay(out) }
        } catch (e: Exception) {
            App.main.post { status.visibility = View.GONE; toast("Lỗi dịch: ${e.message?.take(50)}") }
        }
    }

    /** Pick a translate engine once: Gemini (if key+selected), else ML Kit offline when ready, else free Google online. */
    private fun engine(sample: String): TranslateEngine {
        engine?.let { return it }
        val tgt = Prefs.trTarget(this)
        val chosen: TranslateEngine = try {
            when (Prefs.engine(this)) {
                Engines.GEMINI -> if (Prefs.geminiKey(this).isNotBlank()) GeminiEngine(Prefs.geminiKey(this), Langs.AUTO, tgt) else GoogleFreeEngine(Langs.AUTO, tgt)
                Engines.MLKIT -> {
                    val src = Translator.detectLanguage(sample)
                    if (src != null && src != tgt && MlKitModels.ready(src, tgt)) MlKitEngine(this, src, tgt, "pdf") {} else GoogleFreeEngine(Langs.AUTO, tgt)
                }
                else -> GoogleFreeEngine(Langs.AUTO, tgt)
            }
        } catch (_: Exception) { GoogleFreeEngine(Langs.AUTO, tgt) }
        engine = chosen
        return chosen
    }

    override fun onDestroy() {
        super.onDestroy()
        io.execute { runCatching { renderer?.close(); pfd?.close(); engine?.close(); recognizer.close() } }
    }

    /** Draws the page bitmap, then (if set) white boxes + translated text fitted into each OCR block. */
    private class OverlayView(ctx: android.content.Context) : View(ctx) {
        private var bmp: Bitmap? = null
        private var blocks: List<Block>? = null
        private val boxPaint = android.graphics.Paint().apply { color = Color.WHITE; isAntiAlias = true }
        private val tp = TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }

        fun setPage(b: Bitmap) { bmp = b; blocks = null; requestLayout(); invalidate() }
        fun setOverlay(bl: List<Block>?) { blocks = bl; invalidate() }

        override fun onMeasure(wSpec: Int, hSpec: Int) {
            val w = MeasureSpec.getSize(wSpec)
            val h = bmp?.let { (w.toFloat() * it.height / it.width).toInt() } ?: MeasureSpec.getSize(hSpec)
            setMeasuredDimension(w, h)
        }

        override fun onDraw(canvas: Canvas) {
            val b = bmp ?: return
            val scale = width.toFloat() / b.width
            canvas.save(); canvas.scale(scale, scale)
            canvas.drawBitmap(b, 0f, 0f, null)
            canvas.restore()
            val bl = blocks ?: return
            for (blk in bl) {
                val r = RectF(blk.box.left * scale, blk.box.top * scale, blk.box.right * scale, blk.box.bottom * scale)
                canvas.drawRoundRect(r, 4f, 4f, boxPaint)
                drawFitted(canvas, blk.text, r)
            }
        }

        private fun drawFitted(canvas: Canvas, s: String, r: RectF) {
            val maxW = (r.width() - 6).toInt().coerceAtLeast(8)
            val maxH = (r.height() - 4).coerceAtLeast(8f)
            var size = r.height() * 0.9f
            var layout: StaticLayout
            while (true) {
                tp.textSize = size
                layout = StaticLayout.Builder.obtain(s, 0, s.length, tp, maxW)
                    .setAlignment(android.text.Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0f, 1f).setIncludePad(false).build()
                if ((layout.height <= maxH && layout.width <= maxW) || size <= 7f) break
                size -= 1f
            }
            canvas.save()
            canvas.translate(r.left + 3, r.top + ((maxH - layout.height) / 2f).coerceAtLeast(0f) + 2)
            layout.draw(canvas)
            canvas.restore()
        }
    }
}
