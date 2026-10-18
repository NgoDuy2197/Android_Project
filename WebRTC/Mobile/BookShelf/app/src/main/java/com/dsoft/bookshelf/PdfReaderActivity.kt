package com.dsoft.bookshelf

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.LruCache
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.Executors

/**
 * PDF reader: continuous vertical scroll of pages rendered by PdfRenderer on a background thread at screen width,
 * LRU bitmap cache, pinch / double-tap zoom, exact position (page + offset fraction) saved continuously.
 */
class PdfReaderActivity : BaseActivity() {
    private lateinit var book: Book
    private lateinit var rv: RecyclerView
    private lateinit var lm: LinearLayoutManager
    private lateinit var zoom: ZoomFrame
    private lateinit var indicator: TextView
    private lateinit var topBar: LinearLayout
    private lateinit var navBar: LinearLayout
    private val renderThread = Executors.newSingleThreadExecutor()
    private var pfd: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    private var sizes: List<Pair<Int, Int>> = emptyList()
    private var pageGap = 0
    private var night = false
    private var restored = false
    @Volatile private var firstVisible = 0
    private val inFlight = HashSet<Int>()
    private val cache = object : LruCache<Int, Bitmap>((Runtime.getRuntime().maxMemory() / 1024 / 5).toInt()) {
        override fun sizeOf(key: Int, value: Bitmap) = value.byteCount / 1024
    }
    private val saveRunnable = Runnable { savePosition() }

    override fun keepScreenOnHere() = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        book = Library.get(intent.getStringExtra(Readers.EXTRA_BOOK) ?: "") ?: run { finish(); return }
        Prefs.setLastBook(this, book.id)
        night = Prefs.pdfNight(this)
        pageGap = dp(8)
        buildUi()
        openDocument()
    }

    private fun bgColor() = if (night || p.dark) 0xFF1A1A1A.toInt() else 0xFFE5E5EA.toInt()

    private fun buildUi() {
        setupSystemBars(if (night || p.dark) 0xFF111111.toInt() else p.bg, night || p.dark)
        val root = FrameLayout(this).apply { setBackgroundColor(bgColor()) }
        lm = LinearLayoutManager(this)
        rv = RecyclerView(this).apply {
            layoutManager = lm
            setItemViewCacheSize(4)
            adapter = PageAdapter()
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(r: RecyclerView, dx: Int, dy: Int) {
                    firstVisible = lm.findFirstVisibleItemPosition().coerceAtLeast(0)
                    updateIndicator()
                    App.main.removeCallbacks(saveRunnable)
                    App.main.postDelayed(saveRunnable, 700)
                }
                override fun onScrollStateChanged(r: RecyclerView, state: Int) {
                    if (state == RecyclerView.SCROLL_STATE_IDLE) renderMissing()
                }
            })
        }
        zoom = ZoomFrame(this, rv) { toggleBars() }
        zoom.addView(rv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(zoom, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val barBg = if (night || p.dark) 0xEE1C1C1E.toInt() else 0xF0FFFFFF.toInt()
        val fg = if (night || p.dark) Color.WHITE else Color.BLACK
        topBar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(6), dp(8), dp(6))
            setBackgroundColor(barBg)
            elevation = dpf(4)
            addView(circleButton(p, R.drawable.ic_back, p.blue, 40, bg = 0) { finish() }.apply {
                (layoutParams as LinearLayout.LayoutParams).marginStart = 0
            })
            addView(text(book.title, 16f, fg, 600).apply {
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(4) })
            addView(circleButton(p, R.drawable.ic_goto, fg, 38) { goToPage() })
            addView(circleButton(p, R.drawable.ic_moon, if (night) p.amber else fg, 38) { toggleNight() })
            addView(circleButton(p, R.drawable.ic_headphones, fg, 38) { listen() })
            addView(circleButton(p, R.drawable.ic_more, fg, 38) { moreMenu() })
        }
        root.addView(topBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        indicator = text("", 13f, Color.WHITE, 600).apply {
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = rounded(0xAA000000.toInt(), dpf(14))
            setOnClickListener { goToPage() }
        }
        root.addView(indicator, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(18) })

        // on-screen page buttons: Prev / Next only, bottom-left, thumb-reachable, translucent.
        fun navBtn(res: Int, f: () -> Unit) = ImageView(this).apply {
            setImageResource(res)
            imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            val pd = dp(10); setPadding(pd, pd, pd, pd)
            background = pressable(android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(0xFF000000.toInt())
            }, 0x55FFFFFF)
            setOnClickListener { f() }
            layoutParams = LinearLayout.LayoutParams(dp(46), dp(46)).apply { topMargin = dp(10) }
        }
        navBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(navBtn(R.drawable.ic_up) { stepPage(-1) })
            addView(navBtn(R.drawable.ic_down) { stepPage(1) })
        }
        applyNavOpacity()
        root.addView(navBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.START or Gravity.BOTTOM).apply { marginStart = dp(14); bottomMargin = dp(40) })
        setContentView(root)
        rv.setPadding(0, dp(56), 0, dp(40))
        rv.clipToPadding = false
    }

    /** Re-requests pages that are visible but were skipped during a fast fling. */
    private fun renderMissing() {
        val a = rv.adapter as? PageAdapter ?: return
        for (i in 0 until rv.childCount) {
            val vh = rv.getChildViewHolder(rv.getChildAt(i)) as? PageAdapter.VH ?: continue
            if (vh.img.drawable == null && vh.page >= 0) a.requestRender(vh.page, pageWidth())
        }
    }

    private fun toggleBars() {
        val show = topBar.visibility != View.VISIBLE
        topBar.visibility = if (show) View.VISIBLE else View.GONE
        navBar.visibility = if (show) View.VISIBLE else View.GONE
    }

    /** Applies the configurable page-button opacity (0..100 %); hidden entirely at 0. */
    private fun applyNavOpacity() {
        val o = Prefs.navOpacity(this).coerceIn(0, 100)
        navBar.alpha = o / 100f
    }

    /** Jumps to an absolute page index. */
    private fun jumpTo(page: Int) {
        if (sizes.isEmpty()) return
        zoom.reset()
        lm.scrollToPositionWithOffset(page.coerceIn(0, sizes.size - 1), 0)
        rv.post { savePosition(); updateIndicator(); renderMissing() }
    }

    /** Moves one page forward / back from the current top page. */
    private fun stepPage(delta: Int) {
        val cur = currentPos()?.first ?: lm.findFirstVisibleItemPosition().coerceAtLeast(0)
        jumpTo(cur + delta)
    }

    private fun openDocument() {
        renderThread.execute {
            try {
                val f = Library.file(book)
                val d = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
                val r = PdfRenderer(d)
                val s = ArrayList<Pair<Int, Int>>(r.pageCount)
                for (i in 0 until r.pageCount) r.openPage(i).use { s += it.width to it.height }
                pfd = d; renderer = r
                App.main.post {
                    if (isFinishing || isDestroyed) return@post
                    sizes = s
                    Library.saveState(book.id) { it.copy(pages = s.size) }
                    rv.adapter?.notifyDataSetChanged()
                    rv.post { restorePosition() }
                }
            } catch (e: Exception) {
                App.main.post {
                    if (isFinishing) return@post
                    dialogBuilder(p).setTitle(R.string.err_pdf_open).setMessage(e.message ?: "")
                        .setPositiveButton(android.R.string.ok) { _, _ -> finish() }.setCancelable(false).show()
                }
            }
        }
    }

    private fun pageWidth() = rv.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
    private fun itemHeight(i: Int): Int {
        val (w, h) = sizes.getOrNull(i) ?: return pageWidth()
        return (pageWidth().toLong() * h / w).toInt() + pageGap
    }

    private fun restorePosition() {
        val st = Library.state(book.id)
        val page = st.pdfPage.coerceIn(0, (sizes.size - 1).coerceAtLeast(0))
        firstVisible = page
        lm.scrollToPositionWithOffset(page, -(st.pdfFrac * itemHeight(page)).toInt())
        restored = true
        rv.post { updateIndicator() }
        rv.postDelayed({ renderMissing() }, 400)
    }

    private fun currentPos(): Pair<Int, Float>? {
        val first = lm.findFirstVisibleItemPosition()
        if (first == RecyclerView.NO_POSITION) return null
        val v = lm.findViewByPosition(first) ?: return null
        val top = v.top - rv.paddingTop
        val frac = (-top.toFloat() / itemHeight(first).coerceAtLeast(1)).coerceIn(0f, 0.999f)
        return first to frac
    }

    private fun savePosition() {
        if (!restored || sizes.isEmpty()) return
        val (page, frac) = currentPos() ?: return
        val progress = if (sizes.size <= 1) frac else ((page + frac) / sizes.size).coerceIn(0f, 1f)
        val atEnd = !rv.canScrollVertically(1)
        Library.saveState(book.id) { it.copy(pdfPage = page, pdfFrac = frac, pages = sizes.size, progress = if (atEnd) 1f else progress) }
    }

    private fun updateIndicator() {
        if (sizes.isEmpty()) return
        val (page, _) = currentPos() ?: return
        val last = lm.findLastVisibleItemPosition()
        val shown = if (!rv.canScrollVertically(1) && last != RecyclerView.NO_POSITION) last else page
        indicator.text = "${shown + 1} / ${sizes.size}"
    }

    private fun goToPage() {
        if (sizes.isEmpty()) return
        val f = editField(p, "", "1 - ${sizes.size}", numeric = true)
        dialogBuilder(p).setTitle(R.string.goto_page).setView(dialogColumn().apply { addView(f, vlp()) })
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val n = f.text.toString().toIntOrNull() ?: return@setPositiveButton
                lm.scrollToPositionWithOffset((n - 1).coerceIn(0, sizes.size - 1), 0)
                rv.post { savePosition(); updateIndicator() }
            }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun toggleNight() {
        night = !night
        Prefs.setPdfNight(this, night)
        savePosition()
        recreate()
    }

    private fun listen() {
        if (Library.textSourceOf(book) == null) {
            dialogBuilder(p).setTitle(R.string.need_extract_title).setMessage(R.string.need_extract_msg)
                .setPositiveButton(R.string.menu_extract) { _, _ ->
                    JobManager.startExtract(this, book); toast(R.string.job_queued)
                }
                .setNegativeButton(android.R.string.cancel, null).show()
            return
        }
        TtsService.play(this, book)
        toast(R.string.tts_starting)
    }

    private fun moreMenu() {
        val items = arrayOf(getString(R.string.menu_extract), getString(R.string.menu_translate), getString(R.string.menu_mp3),
            getString(R.string.tts_stop))
        dialogBuilder(p).setItems(items) { _, w ->
            when (w) {
                0 -> { JobManager.startExtract(this, book); toast(R.string.job_queued) }
                1 -> BookDialogs.translate(this, book)
                2 -> BookDialogs.audiobook(this, book)
                3 -> TtsService.command(this, TtsService.ACTION_STOP)
            }
        }.show()
    }

    override fun onResume() {
        super.onResume()
        if (::navBar.isInitialized) applyNavOpacity()
    }

    override fun onPause() {
        super.onPause()
        App.main.removeCallbacks(saveRunnable)
        savePosition()
    }

    override fun onDestroy() {
        App.main.removeCallbacks(saveRunnable)
        renderThread.execute {
            try { renderer?.close() } catch (_: Exception) {}
            try { pfd?.close() } catch (_: Exception) {}
        }
        renderThread.shutdown()
        cache.evictAll()
        super.onDestroy()
    }

    private val invert = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
        -1f, 0f, 0f, 0f, 255f,
        0f, -1f, 0f, 0f, 255f,
        0f, 0f, -1f, 0f, 255f,
        0f, 0f, 0f, 1f, 0f,
    )))

    private inner class PageAdapter : RecyclerView.Adapter<PageAdapter.VH>() {
        inner class VH(val img: ImageView) : RecyclerView.ViewHolder(img) { var page = -1 }

        override fun getItemCount() = sizes.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val iv = ImageView(parent.context).apply {
                scaleType = ImageView.ScaleType.FIT_XY
                setBackgroundColor(Color.WHITE)
                if (night) colorFilter = invert
            }
            return VH(iv)
        }

        override fun onBindViewHolder(h: VH, position: Int) {
            h.page = position
            val ph = itemHeight(position) - pageGap
            h.img.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ph).apply { bottomMargin = pageGap }
            h.img.setBackgroundColor(if (night) 0xFF000000.toInt() else Color.WHITE)
            val cached = cache.get(position)
            if (cached != null) { h.img.setImageBitmap(cached); return }
            h.img.setImageDrawable(null)
            requestRender(position, pageWidth())
            // pre-render neighbours
            if (position + 1 < sizes.size) requestRender(position + 1, pageWidth())
        }

        fun requestRender(position: Int, width: Int) {
            if (cache.get(position) != null || !inFlight.add(position)) return
            renderThread.execute {
                val bmp = try { renderPage(position, width) } catch (_: Throwable) { null }
                App.main.post {
                    inFlight.remove(position)
                    if (bmp == null || isDestroyed) return@post
                    cache.put(position, bmp)
                    for (i in 0 until rv.childCount) {
                        val vh = rv.getChildViewHolder(rv.getChildAt(i)) as? VH ?: continue
                        if (vh.page == position) vh.img.setImageBitmap(bmp)
                    }
                }
            }
        }
    }

    /** Runs on the render thread (PdfRenderer allows one open page at a time). */
    private fun renderPage(i: Int, width: Int): Bitmap? {
        val r = renderer ?: return null
        // skip pages scrolled far away
        val first = firstVisible
        if (i < first - 3 || i > first + 6) return null
        r.openPage(i).use { page ->
            val w = width.coerceAtMost(2000)
            val h = (w.toLong() * page.height / page.width).toInt().coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            return bmp
        }
    }
}

/**
 * Wraps the page list: pinch to zoom (1x-4x), double-tap toggles 1x/2.5x, one-finger horizontal pan while zoomed.
 * Vertical scrolling stays with the RecyclerView. Single tap toggles the toolbar.
 */
@SuppressLint("ViewConstructor")
class ZoomFrame(ctx: Context, private val target: View, private val onTap: () -> Unit) : FrameLayout(ctx) {
    private var scale = 1f
    private val scaleDetector = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            setScale(scale * d.scaleFactor, d.focusX, d.focusY)
            return true
        }
    })
    private val gestures = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            // cycle 1x -> 2x -> 3.5x -> 1x on repeated double-taps
            val next = when { scale < 1.5f -> 2f; scale < 2.75f -> 3.5f; else -> 1f }
            setScale(next, e.x, e.y)
            return true
        }
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean { onTap(); return true }
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (scale > 1f) {
                target.translationX = clampX(target.translationX - dx)
                if (!target.canScrollVertically(if (dy > 0) 1 else -1)) target.translationY = clampY(target.translationY - dy)
                return true
            }
            return false
        }
    })

    init {
        target.pivotX = 0f
        target.pivotY = 0f
    }

    /** Resets to 1x (used before a page jump). */
    fun reset() {
        scale = 1f; target.scaleX = 1f; target.scaleY = 1f; target.translationX = 0f; target.translationY = 0f
    }

    private fun clampX(tx: Float) = tx.coerceIn(-(scale - 1f) * width, 0f)
    private fun clampY(ty: Float) = ty.coerceIn(-(scale - 1f) * height, 0f)

    private fun setScale(s: Float, fx: Float, fy: Float) {
        val ns = s.coerceIn(1f, 6f)
        // keep the focus point stationary
        val cx = (fx - target.translationX) / scale
        val cy = (fy - target.translationY) / scale
        scale = ns
        target.scaleX = ns
        target.scaleY = ns
        target.translationX = if (ns <= 1f) 0f else clampX(fx - cx * ns)
        target.translationY = if (ns <= 1f) 0f else clampY(fy - cy * ns)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(ev)
        gestures.onTouchEvent(ev)
        if (ev.pointerCount > 1 || scaleDetector.isInProgress) {
            // don't let the list scroll while pinching
            val cancel = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
            super.dispatchTouchEvent(cancel)
            cancel.recycle()
            return true
        }
        return super.dispatchTouchEvent(ev)
    }
}
