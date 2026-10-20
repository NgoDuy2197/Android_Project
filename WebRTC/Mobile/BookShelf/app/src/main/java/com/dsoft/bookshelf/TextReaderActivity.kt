package com.dsoft.bookshelf

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Text reader: the file is split into paragraphs shown in a RecyclerView (fast for huge files).
 * Position = character offset, saved continuously and restored exactly. Long-press a paragraph to "read from here".
 * The utterance being read aloud is highlighted.
 */
class TextReaderActivity : BaseActivity() {
    private lateinit var book: Book
    private lateinit var rv: RecyclerView
    private lateinit var lm: LinearLayoutManager
    private lateinit var topBar: LinearLayout
    private lateinit var percentView: TextView
    private lateinit var playBtn: android.widget.ImageView
    private var text = ""
    private var paras: List<Span> = emptyList()
    private var loaded = false
    private var hlStart = -1
    private var hlEnd = -1
    private var lastUserScroll = 0L
    private var touchX = 0f
    private var touchY = 0f
    private val saveRunnable = Runnable { savePosition() }
    private val ttsListener: (TtsBus.State) -> Unit = { onTts(it) }

    private var fontSp = 19
    private var spacing = 150
    private var serif = true
    private var marginDp = 20

    override fun keepScreenOnHere() = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        book = Library.get(intent.getStringExtra(Readers.EXTRA_BOOK) ?: "") ?: run { finish(); return }
        Prefs.setLastBook(this, book.id)
        fontSp = Prefs.fontSize(this); spacing = Prefs.lineSpacing(this); serif = Prefs.serif(this); marginDp = Prefs.margin(this)
        buildUi()
        load()
        TtsBus.addListener(ttsListener)
    }

    override fun onNewIntent(intent: android.content.Intent?) {
        super.onNewIntent(intent)
        val id = intent?.getStringExtra(Readers.EXTRA_BOOK) ?: return
        if (id != book.id) { setIntent(intent); recreate() }
    }

    private fun buildUi() {
        setupSystemBars(p.paper, p.dark)
        val root = FrameLayout(this).apply { setBackgroundColor(p.paper) }
        lm = LinearLayoutManager(this)
        rv = RecyclerView(this).apply {
            layoutManager = lm
            adapter = ParaAdapter()
            setPadding(0, dp(60), 0, dp(80))
            clipToPadding = false
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(r: RecyclerView, dx: Int, dy: Int) {
                    updatePercent()
                    App.main.removeCallbacks(saveRunnable)
                    App.main.postDelayed(saveRunnable, 700)
                }
                override fun onScrollStateChanged(r: RecyclerView, state: Int) {
                    if (state == RecyclerView.SCROLL_STATE_DRAGGING) lastUserScroll = System.currentTimeMillis()
                }
            })
        }
        root.addView(rv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val barBg = (p.paper and 0x00FFFFFF) or (0xF0 shl 24)
        topBar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(6), dp(8), dp(6))
            setBackgroundColor(barBg)
            elevation = dpf(3)
            addView(circleButton(p, R.drawable.ic_back, p.blue, 40, bg = 0) { finish() }.apply {
                (layoutParams as LinearLayout.LayoutParams).marginStart = 0
            })
            addView(text(book.title, 16f, p.ink, 600).apply {
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(4) })
            addView(circleButton(p, R.drawable.ic_textsize, p.ink, 38) { styleDialog() })
            addView(circleButton(p, R.drawable.ic_goto, p.ink, 38) { goToPercent() })
            playBtn = circleButton(p, R.drawable.ic_headphones, p.ink, 38) { togglePlay() }
            addView(playBtn)
            addView(circleButton(p, R.drawable.ic_more, p.ink, 38) { moreMenu() })
        }
        root.addView(topBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        percentView = text("", 12f, p.secondary, 500).apply {
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = rounded(p.fill, dpf(10))
            setOnClickListener { goToPercent() }
        }
        root.addView(percentView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(14) })
        setContentView(root)
    }

    private fun load() {
        uiScope.launch {
            val (t, ps) = withContext(Dispatchers.IO) {
                val t = try { Files.readText(Library.file(book)) } catch (_: Exception) { "" }
                t to TextLogic.paragraphs(t, 1200)
            }
            text = t; paras = ps
            loaded = true
            Library.saveState(book.id) { it.copy(textLength = t.length) }
            rv.adapter?.notifyDataSetChanged()
            if (t.isEmpty()) { percentView.text = getString(R.string.empty_text_hint); return@launch }
            restore(Library.state(book.id).textOffset)
            onTts(TtsBus.state)
        }
    }

    // ---------- position ----------
    private fun restore(offset: Int) {
        if (paras.isEmpty()) return
        val idx = TextLogic.indexAt(paras, offset).coerceAtLeast(0)
        lm.scrollToPositionWithOffset(idx, 0)
        rv.post {
            val v = lm.findViewByPosition(idx) as? TextView
            val layout = v?.layout
            if (v != null && layout != null) {
                val local = (offset - paras[idx].start).coerceIn(0, v.text.length)
                val y = layout.getLineTop(layout.getLineForOffset(local)) + v.paddingTop
                if (y > 0) rv.scrollBy(0, y)
            }
            updatePercent()
        }
    }

    private fun currentOffset(): Int? {
        if (paras.isEmpty()) return null
        val first = lm.findFirstVisibleItemPosition()
        if (first == RecyclerView.NO_POSITION) return null
        val v = lm.findViewByPosition(first) as? TextView ?: return paras[first].start
        val layout = v.layout ?: return paras[first].start
        val y = (rv.paddingTop - v.top - v.paddingTop).coerceAtLeast(0)
        val line = layout.getLineForVertical(y)
        return paras[first].start + layout.getLineStart(line).coerceAtMost(paras[first].length)
    }

    private fun savePosition() {
        if (!loaded || text.isEmpty()) return
        val off = currentOffset() ?: return
        val atEnd = !rv.canScrollVertically(1)
        Library.saveState(book.id) {
            it.copy(textOffset = off, textLength = text.length, progress = if (atEnd) 1f else off.toFloat() / text.length)
        }
    }

    private fun updatePercent() {
        if (text.isEmpty()) return
        val off = currentOffset() ?: return
        val pct = if (!rv.canScrollVertically(1)) 100.0 else off * 100.0 / text.length
        percentView.text = String.format("%.1f%%", pct)
    }

    private fun goToPercent() {
        if (text.isEmpty()) return
        val col = dialogColumn()
        val cur = ((currentOffset() ?: 0) * 1000L / text.length).toInt()
        val label = text(String.format("%.1f%%", cur / 10.0), 16f, p.text, 600).apply { gravity = Gravity.CENTER }
        val seek = SeekBar(this).apply {
            max = 1000; progress = cur
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, v: Int, f: Boolean) { label.text = String.format("%.1f%%", v / 10.0) }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        val field = editField(p, "", getString(R.string.goto_percent_hint), numeric = true)
        col.addView(label, vlp())
        col.addView(seek, vlp().apply { topMargin = dp(8) })
        col.addView(field, vlp().apply { topMargin = dp(8) })
        dialogBuilder(p).setTitle(R.string.goto_position).setView(col)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val typed = field.text.toString().toDoubleOrNull()
                val frac = if (typed != null) typed / 100.0 else seek.progress / 1000.0
                restore((frac.coerceIn(0.0, 1.0) * text.length).toInt())
                rv.postDelayed({ savePosition() }, 300)
            }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    // ---------- style ----------
    private fun typeface(): Typeface = Typeface.create(if (serif) "serif" else "sans-serif", Typeface.NORMAL)

    private fun styleDialog() {
        val col = dialogColumn()
        col.addView(sliderRow(p, getString(R.string.font_size), 12, 34, fontSp, { "$it sp" }) {
            fontSp = it; Prefs.setFontSize(this, it); restyle()
        })
        col.addView(sliderRow(p, getString(R.string.line_spacing), 100, 220, spacing, { String.format("%.2f", it / 100f) }) {
            spacing = it; Prefs.setLineSpacing(this, it); restyle()
        })
        col.addView(sliderRow(p, getString(R.string.margins), 4, 48, marginDp, { "$it dp" }) {
            marginDp = it; Prefs.setMargin(this, it); restyle()
        })
        col.addView(Segmented(this, p, listOf(getString(R.string.font_serif), getString(R.string.font_sans)), if (serif) 0 else 1) {
            serif = it == 0; Prefs.setSerif(this, serif); restyle()
        }, vlp().apply { setMargins(dp(16), dp(10), dp(12), dp(6)) })
        val themes = listOf(getString(R.string.theme_light), getString(R.string.theme_dark), getString(R.string.theme_sepia),
            getString(R.string.theme_pastel), getString(R.string.theme_system))
        val modes = listOf(ThemeMode.LIGHT, ThemeMode.DARK, ThemeMode.SEPIA, ThemeMode.PASTEL, ThemeMode.SYSTEM)
        col.addView(Segmented(this, p, themes, modes.indexOf(Prefs.theme(this)).coerceAtLeast(0)) {
            savePosition(); Prefs.setTheme(this, modes[it]); recreate()
        }, vlp().apply { setMargins(dp(16), dp(10), dp(12), dp(6)) })
        dialogBuilder(p).setTitle(R.string.reading_style).setView(android.widget.ScrollView(this).apply { addView(col) })
            .setPositiveButton(android.R.string.ok, null).show()
    }

    private fun restyle() {
        val off = currentOffset()
        rv.adapter?.notifyDataSetChanged()
        if (off != null) rv.post { restore(off) }
    }

    // ---------- read aloud ----------
    private fun togglePlay() {
        val s = TtsBus.state
        if (s.active && (s.textBookId == book.id || s.bookId == book.id)) {
            TtsService.command(this, TtsService.ACTION_TOGGLE)
        } else {
            TtsService.play(this, book)
            toast(R.string.tts_starting)
        }
    }

    private fun onTts(s: TtsBus.State) {
        val mine = s.active && (s.textBookId == book.id || s.bookId == book.id)
        playBtn.setImageResource(if (mine && s.playing) R.drawable.ic_pause else R.drawable.ic_headphones)
        val ns = if (mine) s.start else -1
        val ne = if (mine) s.end else -1
        if (ns == hlStart && ne == hlEnd) return
        val old = affected(hlStart, hlEnd)
        hlStart = ns; hlEnd = ne
        val now = affected(hlStart, hlEnd)
        (old + now).toSet().forEach { rv.adapter?.notifyItemChanged(it) }
        // follow the voice unless the user scrolled recently
        if (mine && s.playing && now.isNotEmpty() && System.currentTimeMillis() - lastUserScroll > 6000) {
            val first = lm.findFirstCompletelyVisibleItemPosition()
            val last = lm.findLastCompletelyVisibleItemPosition()
            val target = now.first()
            if (target < first || target > last) lm.scrollToPositionWithOffset(target, dp(80))
        }
    }

    private fun affected(s: Int, e: Int): List<Int> {
        if (s < 0 || paras.isEmpty()) return emptyList()
        val a = TextLogic.indexAt(paras, s)
        val b = TextLogic.indexAt(paras, (e - 1).coerceAtLeast(s))
        return (a..b).toList()
    }

    private fun paragraphMenu(pos: Int, localOffset: Int) {
        val ps = paras.getOrNull(pos) ?: return
        val global = ps.start + localOffset.coerceIn(0, ps.length)
        val from = TextLogic.sentenceStartAt(text, global)
        val preview = text.substring(from, minOf(text.length, from + 80)).replace('\n', ' ')
        val items = arrayOf(getString(R.string.read_from_here), getString(R.string.set_listen_mark), getString(R.string.copy_paragraph))
        dialogBuilder(p).setTitle("“$preview…”").setItems(items) { _, w ->
            when (w) {
                0 -> { TtsService.play(this, book, from); toast(R.string.tts_starting) }
                1 -> { Library.saveState(book.id) { it.copy(ttsOffset = from) }; toast(R.string.listen_mark_set) }
                2 -> {
                    getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("text", text.substring(ps.start, ps.end).trim()))
                    toast(R.string.copied)
                }
            }
        }.show()
    }

    private fun moreMenu() {
        val items = arrayOf(getString(R.string.menu_translate), getString(R.string.menu_mp3), "Hẹn giờ tắt (nghe)", getString(R.string.tts_stop))
        dialogBuilder(p).setItems(items) { _, w ->
            when (w) {
                0 -> BookDialogs.translate(this, book)
                1 -> BookDialogs.audiobook(this, book)
                2 -> sleepTimerDialog()
                3 -> TtsService.command(this, TtsService.ACTION_STOP)
            }
        }.show()
    }

    private fun sleepTimerDialog() {
        val mins = listOf(0, 5, 10, 15, 30, 45, 60)
        val labels = mins.map { if (it == 0) "Tắt hẹn giờ" else "$it phút" }.toTypedArray()
        val left = if (TtsBus.sleepAtMs > 0) " (còn ${((TtsBus.sleepAtMs - System.currentTimeMillis()) / 60000 + 1).coerceAtLeast(0)}′)" else ""
        dialogBuilder(p).setTitle("Hẹn giờ tắt khi nghe$left").setItems(labels) { _, w ->
            TtsService.sleep(this, mins[w])
            toast(if (mins[w] == 0) "Đã tắt hẹn giờ" else "Sẽ dừng đọc sau ${mins[w]} phút")
        }.show()
    }

    /** Double-tap a word → look it up with the online translator (no model needed) and show the meaning. */
    private fun translateWord(word: String) {
        val w = word.trim().trim('“', '”', '"', '\'', '«', '»', '(', ')', '.', ',', ';', ':', '!', '?', '…')
        if (w.isEmpty()) return
        val target = Prefs.trTarget(this)
        val body = text("Đang dịch…", 16f, p.text).apply { setPadding(dp(4), dp(8), dp(4), 0) }
        val dlg = dialogBuilder(p).setTitle(w)
            .setView(dialogColumn().apply { addView(body, vlp()) })
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton("Copy") { _, _ ->
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("text", "$w = ${body.text}"))
                toast(R.string.copied)
            }.create()
        dlg.show()
        Thread {
            val res = try { GoogleFreeEngine(Langs.AUTO, target).translate(w) } catch (e: Exception) { null }
            runOnUiThread { body.text = res?.takeIf { it.isNotBlank() } ?: "Không dịch được (cần mạng)" }
        }.start()
    }

    private fun wordAt(s: String, off: Int): String {
        if (s.isEmpty()) return ""
        val i = off.coerceIn(0, s.length - 1)
        fun isWord(c: Char) = Character.isLetterOrDigit(c) || c == '\'' || c == '-' || c == '_'
        fun isCjk(c: Char) = c in '一'..'鿿' || c in '぀'..'ヿ' || c in '가'..'힣'
        val c = s[i]
        if (isCjk(c)) { // no spaces between words: grab a short run of CJK chars
            var a = i; var b = i + 1
            while (a > 0 && isCjk(s[a - 1]) && i - a < 5) a--
            while (b < s.length && isCjk(s[b]) && b - i < 6) b++
            return s.substring(a, b)
        }
        if (!isWord(c)) return ""
        var a = i; var b = i + 1
        while (a > 0 && isWord(s[a - 1])) a--
        while (b < s.length && isWord(s[b])) b++
        return s.substring(a, b)
    }

    override fun onPause() {
        super.onPause()
        App.main.removeCallbacks(saveRunnable)
        savePosition()
    }

    override fun onDestroy() {
        App.main.removeCallbacks(saveRunnable)
        TtsBus.removeListener(ttsListener)
        super.onDestroy()
    }

    private inner class ParaAdapter : RecyclerView.Adapter<ParaAdapter.VH>() {
        inner class VH(val tv: TextView) : RecyclerView.ViewHolder(tv)

        override fun getItemCount() = paras.size

        @SuppressLint("ClickableViewAccessibility")
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val tv = TextView(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            val vh = VH(tv)
            val gd = android.view.GestureDetector(parent.context, object : android.view.GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent) = true
                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    topBar.visibility = if (topBar.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                    return true
                }
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    if (vh.bindingAdapterPosition != RecyclerView.NO_POSITION) {
                        val off = tv.getOffsetForPosition(e.x, e.y).coerceAtLeast(0)
                        translateWord(wordAt(tv.text?.toString() ?: "", off))
                    }
                    return true
                }
                override fun onLongPress(e: MotionEvent) {
                    val pos = vh.bindingAdapterPosition
                    if (pos != RecyclerView.NO_POSITION) paragraphMenu(pos, tv.getOffsetForPosition(e.x, e.y).coerceAtLeast(0))
                }
            })
            tv.setOnTouchListener { _, e -> gd.onTouchEvent(e) }
            return vh
        }

        override fun onBindViewHolder(h: VH, position: Int) {
            val s = paras[position]
            val raw = text.substring(s.start, s.end).trimEnd('\n', ' ', '\t')
            val tv = h.tv
            tv.setTextColor(p.ink)
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSp.toFloat())
            tv.setLineSpacing(0f, spacing / 100f)
            tv.typeface = typeface()
            val m = dp(marginDp)
            tv.setPadding(m, dp(fontSp * 0.35f), m, dp(fontSp * 0.35f))
            if (hlStart >= 0 && hlStart < s.end && hlEnd > s.start) {
                val sp = SpannableString(raw)
                val a = (hlStart - s.start).coerceIn(0, raw.length)
                val b = (hlEnd - s.start).coerceIn(0, raw.length)
                if (b > a) sp.setSpan(BackgroundColorSpan(p.highlight), a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                tv.text = sp
            } else {
                tv.text = raw
            }
            if (raw.isEmpty()) tv.setTextColor(Color.TRANSPARENT)
        }
    }
}
