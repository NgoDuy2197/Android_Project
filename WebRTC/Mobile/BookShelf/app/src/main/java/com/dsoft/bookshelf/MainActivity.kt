package com.dsoft.bookshelf

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

object Readers {
    const val EXTRA_BOOK = "book_id"

    fun open(act: android.app.Activity, b: Book) {
        Library.update(b.id) { it.copy(openedAt = System.currentTimeMillis()) }
        if (b.isAudio) { playAudio(act, b); return }
        Prefs.setLastBook(act, b.id)
        val cls = if (b.isPdf) PdfReaderActivity::class.java else TextReaderActivity::class.java
        act.startActivity(Intent(act, cls).putExtra(EXTRA_BOOK, b.id))
    }

    /** Opens an audiobook in an external media player. */
    fun playAudio(act: android.app.Activity, b: Book) {
        val uri: android.net.Uri = when {
            b.audioUri.isNotEmpty() -> android.net.Uri.parse(b.audioUri)
            b.audioPath.isNotEmpty() && java.io.File(b.audioPath).exists() ->
                androidx.core.content.FileProvider.getUriForFile(act, "${act.packageName}.files", java.io.File(b.audioPath))
            else -> { act.toast(R.string.err_audio_missing); return }
        }
        try {
            act.startActivity(Intent.createChooser(
                Intent(Intent.ACTION_VIEW).setDataAndType(uri, "audio/mpeg").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                act.getString(R.string.menu_play)))
        } catch (_: Exception) { act.toast(R.string.err_no_player) }
    }
}

/** Library home: a wooden bookshelf with covers. */
class MainActivity : BaseActivity() {
    private lateinit var rv: RecyclerView
    private lateinit var adapter: ShelfAdapter
    private lateinit var subtitle: TextView
    private lateinit var searchField: android.widget.EditText
    private lateinit var emptyView: TextView
    private lateinit var playerBar: LinearLayout
    private lateinit var playerTitle: TextView
    private lateinit var playerToggle: ImageView
    private var query = ""

    /** A custom shelf background is set and present → the shelf renders as frosted glass over it. */
    private val shelfGlass get() = Prefs.shelfBgOn(this) && Covers.shelfBgFile(this).exists()

    /** The blurred / brightness-adjusted background image behind the (glass) shelf, or null. */
    private fun shelfBackgroundView(): View? {
        if (!shelfGlass) return null
        val bmp = Covers.loadShelfBg(this) ?: return null
        val blur = Prefs.shelfBgBlur(this)
        val dim = Prefs.shelfBgDim(this)
        return ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            if (Build.VERSION.SDK_INT >= 31 && blur > 0) {
                setImageBitmap(bmp)
                val r = blur * 1.6f
                setRenderEffect(android.graphics.RenderEffect.createBlurEffect(r, r, android.graphics.Shader.TileMode.CLAMP))
            } else {
                setImageBitmap(if (blur > 0) Covers.downscaleBlur(bmp, blur) else bmp)
            }
            // Brightness ("tông sáng/tối"): multiply RGB (<1 darkens, >1 brightens).
            val s = 1f + dim / 100f
            colorFilter = android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix(floatArrayOf(
                s, 0f, 0f, 0f, 0f, 0f, s, 0f, 0f, 0f, 0f, 0f, s, 0f, 0f, 0f, 0f, 0f, 1f, 0f,
            )))
        }
    }
    private var pendingCoverFor: String? = null
    private val libListener: () -> Unit = { refresh() }
    private val ttsListener: (TtsBus.State) -> Unit = { updatePlayer(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingCoverFor = savedInstanceState?.getString("cover_for")
        setupSystemBars(p.bg, p.dark)
        buildUi()
        Library.addListener(libListener)
        TtsBus.addListener(ttsListener)
        askNotificationPermission()

        val viewUri = if (intent?.action == Intent.ACTION_VIEW) intent.data else null
        if (savedInstanceState == null && viewUri != null) {
            importUris(listOf(viewUri), openAfter = true)
        } else if (savedInstanceState == null && !App.autoOpenDone && Prefs.autoOpen(this) &&
            intent?.action == Intent.ACTION_MAIN && intent?.getStringExtra(Readers.EXTRA_BOOK) == null) {
            App.autoOpenDone = true
            Library.get(Prefs.lastBook(this))?.let { Readers.open(this, it) }
        }
        App.autoOpenDone = true
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("cover_for", pendingCoverFor)
    }

    override fun onResume() {
        super.onResume()
        if (::adapter.isInitialized) refresh()
        updatePlayer(TtsBus.state)
    }

    override fun onDestroy() {
        Library.removeListener(libListener)
        TtsBus.removeListener(ttsListener)
        super.onDestroy()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(p.bg) }

        // header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(14), dp(14), dp(10))
        }
        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(getString(R.string.library_title), 30f, p.text, 700))
            subtitle = text("", 13f, p.secondary).apply { setPadding(dp(2), dp(4), 0, 0) }
            addView(subtitle)
        }
        header.addView(titleCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(circleButton(p, R.drawable.ic_search, p.text, 38) { toggleSearch() })
        header.addView(circleButton(p, R.drawable.ic_sort, p.text, 38) { chooseSort() })
        header.addView(circleButton(p, R.drawable.ic_jobs, p.text, 38) { startActivity(Intent(this, JobsActivity::class.java)) })
        header.addView(circleButton(p, R.drawable.ic_settings, p.text, 38) { startActivity(Intent(this, SettingsActivity::class.java)) })
        // big "+"
        header.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_add)
            imageTintList = ColorStateList.valueOf(Color.WHITE)
            val pad = dp(11)
            setPadding(pad, pad, pad, pad)
            // Follows the theme accent (changes with Light / Dark / Sepia / Pastel).
            val accent = p.blue
            fun lift(c: Int, f: Float) = Color.rgb(
                (Color.red(c) + (255 - Color.red(c)) * f).toInt(),
                (Color.green(c) + (255 - Color.green(c)) * f).toInt(),
                (Color.blue(c) + (255 - Color.blue(c)) * f).toInt())
            background = pressable(GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(lift(accent, 0.28f), accent)).apply {
                shape = GradientDrawable.OVAL
            }, 0x40FFFFFF)
            elevation = dpf(4)
            contentDescription = getString(R.string.add_books)
            setOnClickListener { pickFiles() }
            layoutParams = LinearLayout.LayoutParams(dp(50), dp(50)).apply { marginStart = dp(10) }
        })
        root.addView(header, vlp())

        searchField = editField(p, "", getString(R.string.search_hint)).apply {
            visibility = View.GONE
            background = rounded(p.fill, dpf(10))
            setPadding(dp(12), dp(9), dp(12), dp(9))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { query = s?.toString()?.trim() ?: ""; refresh() }
            })
        }
        root.addView(searchField, vlp().apply { setMargins(dp(16), 0, dp(16), dp(10)) })

        root.addView(ShelfRowView.Crown(this, p, shelfGlass), vlp(h = dp(14)))

        val frame = FrameLayout(this)
        shelfBackgroundView()?.let { frame.addView(it, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)) }
        rv = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            setBackgroundColor(if (shelfGlass) android.graphics.Color.TRANSPARENT else Wood(p).wallBottom)
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
            setPadding(0, 0, 0, dp(80))
        }
        adapter = ShelfAdapter()
        rv.adapter = adapter
        rv.addOnLayoutChangeListener { _, l, _, r, _, ol, _, or, _ -> if (r - l != or - ol) rv.post { refresh() } }
        frame.addView(rv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        emptyView = text(getString(R.string.empty_library), 16f, 0xFFFFF3DD.toInt(), 500).apply {
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.3f)
            setPadding(dp(22), dp(18), dp(22), dp(18))
            background = rounded(0x99000000.toInt(), dpf(16))
            setOnClickListener { pickFiles() }
        }
        frame.addView(emptyView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply {
            setMargins(dp(32), 0, dp(32), 0)
        })

        // mini player for read-aloud
        playerTitle = text("", 14f, p.text, 600).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        playerToggle = circleButton(p, R.drawable.ic_pause, p.blue, 40) { TtsService.command(this, TtsService.ACTION_TOGGLE) }
        playerBar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(8), dp(8), dp(8))
            background = rounded(p.raised, dpf(18))
            elevation = dpf(8)
            addView(icon(R.drawable.ic_headphones, p.amber, 24))
            addView(playerTitle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(10) })
            addView(circleButton(p, R.drawable.ic_prev, p.text, 36) { TtsService.command(this@MainActivity, TtsService.ACTION_PREV) })
            addView(playerToggle)
            addView(circleButton(p, R.drawable.ic_next, p.text, 36) { TtsService.command(this@MainActivity, TtsService.ACTION_NEXT) })
            addView(circleButton(p, R.drawable.ic_stop, p.red, 36) { TtsService.command(this@MainActivity, TtsService.ACTION_STOP) })
            visibility = View.GONE
            setOnClickListener { Library.get(TtsBus.state.textBookId)?.let { Readers.open(this@MainActivity, it) } }
        }
        frame.addView(playerBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
            setMargins(dp(12), 0, dp(12), dp(14))
        })
        root.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    private fun updatePlayer(s: TtsBus.State) {
        if (!::playerBar.isInitialized) return
        playerBar.visibility = if (s.active) View.VISIBLE else View.GONE
        playerTitle.text = Library.get(s.bookId)?.title ?: ""
        playerToggle.setImageResource(if (s.playing) R.drawable.ic_pause else R.drawable.ic_play)
    }

    private fun toggleSearch() {
        val show = searchField.visibility != View.VISIBLE
        searchField.visibility = if (show) View.VISIBLE else View.GONE
        if (show) {
            searchField.requestFocus()
            getSystemService(android.view.inputmethod.InputMethodManager::class.java).showSoftInput(searchField, 0)
        } else {
            searchField.setText("")
            getSystemService(android.view.inputmethod.InputMethodManager::class.java).hideSoftInputFromWindow(searchField.windowToken, 0)
        }
    }

    private fun chooseSort() {
        val items = arrayOf(getString(R.string.sort_recent), getString(R.string.sort_title), getString(R.string.sort_added))
        dialogBuilder(p).setTitle(R.string.sort_by)
            .setSingleChoiceItems(items, Prefs.sort(this)) { d, which -> Prefs.setSort(this, which); refresh(); d.dismiss() }
            .show()
    }

    private fun sortedBooks(): List<Book> {
        val q = query.lowercase()
        val list = Library.all().filter { q.isEmpty() || it.title.lowercase().contains(q) }
        return when (Prefs.sort(this)) {
            1 -> list.sortedBy { it.title.lowercase() }
            2 -> list.sortedByDescending { it.addedAt }
            else -> list.sortedByDescending { maxOf(it.openedAt, it.addedAt) }
        }
    }

    private fun refresh() {
        if (!::adapter.isInitialized) return
        val books = sortedBooks()
        val total = Library.all().size
        subtitle.text = resources.getQuantityString(R.plurals.book_count, total, total)
        emptyView.visibility = if (total == 0) View.VISIBLE else View.GONE
        val w = rv.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val side = dp(20)
        val gap = dp(14)
        val minCover = dp(if (resources.configuration.smallestScreenWidthDp >= 600) 120 else 96)
        val cols = ((w - 2 * side + gap) / (minCover + gap)).coerceIn(3, 10)
        val coverW = (w - 2 * side - (cols - 1) * gap) / cols - CoverView.extraW(resources.displayMetrics.density) / 2
        adapter.set(books.chunked(cols), cols, coverW, side, gap)
    }

    // ---------- shelf adapter ----------
    private inner class ShelfAdapter : RecyclerView.Adapter<ShelfAdapter.VH>() {
        private var rows: List<List<Book>> = emptyList()
        private var cols = 3
        private var coverW = 100
        private var side = 0
        private var gap = 0

        fun set(r: List<List<Book>>, c: Int, cw: Int, s: Int, g: Int) {
            rows = r; cols = c; coverW = cw; side = s; gap = g
            notifyDataSetChanged()
        }

        inner class VH(val row: ShelfRowView) : RecyclerView.ViewHolder(row)

        override fun getItemCount(): Int {
            // always show enough empty shelves to fill the cabinet
            val screenRows = (rv.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels) / (coverW * 1.48f + dp(70)).toInt() + 1
            return maxOf(rows.size, screenRows, 3)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val row = ShelfRowView(parent.context, p, shelfGlass)
            row.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            return VH(row)
        }

        override fun onBindViewHolder(h: VH, position: Int) {
            val books = rows.getOrNull(position) ?: emptyList()
            val row = h.row
            row.columns = cols; row.coverW = coverW; row.sidePad = side; row.gap = gap
            row.isFirst = position == 0
            row.rowIndex = position
            row.ensureCovers(books.size)
            row.titles = books.map { it.title }
            books.forEachIndexed { i, b ->
                val cv = row.covers[i]
                cv.book = b
                cv.contentDescription = b.title
                cv.progress = Library.state(b.id).progress
                cv.onOpen = { Readers.open(this@MainActivity, it) }
                cv.onMenu = { showBookMenu(it) }
                cv.bitmap = Covers.cached(b)
                if (cv.bitmap == null && !Covers.hasNoCover(b)) {
                    Covers.load(b) { bmp -> if (cv.book?.id == b.id) cv.bitmap = bmp }
                }
                cv.invalidate()
            }
            row.requestLayout()
        }
    }

    // ---------- import ----------
    private fun pickFiles() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/pdf", "text/plain", "application/epub+zip"))
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        try { startActivityForResult(i, REQ_IMPORT) } catch (_: Exception) { toast(R.string.err_no_picker) }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        when (requestCode) {
            REQ_IMPORT -> {
                val uris = ArrayList<Uri>()
                data.clipData?.let { cd -> for (k in 0 until cd.itemCount) uris += cd.getItemAt(k).uri }
                if (uris.isEmpty()) data.data?.let { uris += it }
                importUris(uris, openAfter = false)
            }
            REQ_COVER -> {
                val id = pendingCoverFor ?: return
                val uri = data.data ?: return
                uiScope.launch {
                    val ok = withContext(Dispatchers.IO) { try { Covers.saveCustom(this@MainActivity, id, uri) } catch (_: Exception) { false } }
                    if (ok) Library.update(id) { it.copy(customCover = true) } else toast(R.string.err_cover)
                    refresh()
                }
            }
        }
    }

    private fun importUris(uris: List<Uri>, openAfter: Boolean) {
        if (uris.isEmpty()) return
        toast(getString(R.string.importing, uris.size))
        uiScope.launch {
            var last: Book? = null
            var failed = 0
            for (u in uris) {
                val b = withContext(Dispatchers.IO) { try { importOne(u) } catch (_: Exception) { null } }
                if (b == null) failed++ else last = b
            }
            if (failed > 0) toast(getString(R.string.import_failed, failed))
            refresh()
            if (openAfter) last?.let { Readers.open(this@MainActivity, it) }
        }
    }

    private fun importOne(uri: Uri): Book? {
        var name = "book"
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) name = c.getString(0)
        }
        if (name == "book") uri.lastPathSegment?.substringAfterLast('/')?.let { name = it }
        val mime = contentResolver.getType(uri) ?: ""
        val head = ByteArray(5)
        contentResolver.openInputStream(uri)?.use { it.read(head) } ?: return null
        val lower = name.lowercase()
        val isZip = head.size >= 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() &&
            head[2] == 0x03.toByte() && head[3] == 0x04.toByte()
        // EPUB: convert to plain text once at import, then store as an ordinary TEXT book so the
        // whole reader / TTS / translate pipeline works unchanged.
        if (mime == "application/epub+zip" || lower.endsWith(".epub") || isZip) {
            val epubTmp = File.createTempFile("import_", ".epub", cacheDir)
            try {
                contentResolver.openInputStream(uri)?.use { input -> epubTmp.outputStream().use { input.copyTo(it, 64 * 1024) } }
                    ?: run { epubTmp.delete(); return null }
                val res = Epub.parse(epubTmp)
                if (res != null) {
                    val title = res.title.trim().ifEmpty { TextLogic.titleFromFileName(name) }
                    val eb = Book(Library.newId(), title, BookType.TEXT, System.currentTimeMillis())
                    val edir = Library.dir(eb.id).apply { mkdirs() }
                    try { Files.writeAtomic(File(edir, "book.txt"), res.text) }
                    catch (e: Exception) { edir.deleteRecursively(); throw e }
                    Library.add(eb)
                    return eb
                }
                // A declared EPUB that failed to parse is a hard failure; a bare zip just falls through.
                if (mime == "application/epub+zip" || lower.endsWith(".epub")) return null
            } finally {
                epubTmp.delete()
            }
        }
        val isPdf = mime == "application/pdf" || lower.endsWith(".pdf") || String(head, Charsets.US_ASCII).startsWith("%PDF")
        val b = Book(Library.newId(), TextLogic.titleFromFileName(name), if (isPdf) BookType.PDF else BookType.TEXT, System.currentTimeMillis())
        val dir = Library.dir(b.id).apply { mkdirs() }
        val dest = File(dir, if (isPdf) "book.pdf" else "book.txt")
        val tmp = File(dir, dest.name + ".part")
        contentResolver.openInputStream(uri)?.use { input -> tmp.outputStream().use { input.copyTo(it, 64 * 1024) } } ?: return null
        if (!tmp.renameTo(dest)) { dir.deleteRecursively(); return null }
        // normalise text files to UTF-8 (legacy encodings are detected once, here)
        if (!isPdf) try { Files.writeAtomic(dest, Files.readText(dest)) } catch (_: Exception) {}
        Library.add(b)
        if (isPdf) Covers.load(b) { refresh() }
        return b
    }

    // ---------- per-book menu ----------
    private fun showBookMenu(b: Book) {
        val actions = ArrayList<Pair<String, () -> Unit>>()
        if (b.isAudio) {
            actions += getString(R.string.menu_play) to { Readers.playAudio(this, b) }
            actions += getString(R.string.menu_rename) to { rename(b) }
            actions += getString(R.string.menu_share) to { shareBook(b) }
            actions += getString(R.string.menu_delete) to { delete(b) }
        } else {
            actions += getString(R.string.menu_open) to { Readers.open(this, b) }
            actions += getString(R.string.menu_listen) to { listen(b) }
            actions += getString(R.string.menu_rename) to { rename(b) }
            actions += getString(R.string.menu_cover) to { pickCover(b) }
            if (b.customCover) actions += getString(R.string.menu_cover_reset) to { resetCover(b) }
            if (b.isPdf) actions += getString(R.string.menu_extract) to { extract(b) }
            actions += getString(R.string.menu_translate) to { BookDialogs.translate(this, b) }
            actions += getString(R.string.menu_mp3) to { BookDialogs.audiobook(this, b) }
            actions += getString(R.string.menu_share) to { shareBook(b) }
            actions += getString(R.string.menu_jobs) to {
                startActivity(Intent(this, JobsActivity::class.java).putExtra(Readers.EXTRA_BOOK, b.id))
            }
            actions += getString(R.string.menu_delete) to { delete(b) }
        }
        dialogBuilder(p).setTitle(b.title)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
            .show()
    }

    /** Shares the actual book file (PDF / TXT / MP3) via a FileProvider content:// uri. */
    private fun shareBook(b: Book) {
        val file: File
        val mime: String
        if (b.isAudio) {
            if (b.audioUri.isNotEmpty()) {
                try {
                    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("audio/mpeg")
                        .putExtra(Intent.EXTRA_STREAM, Uri.parse(b.audioUri)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                        getString(R.string.menu_share)))
                } catch (_: Exception) { toast(R.string.err_share) }
                return
            }
            file = File(b.audioPath); mime = "audio/mpeg"
        } else {
            file = Library.file(b); mime = if (b.isPdf) "application/pdf" else "text/plain"
        }
        if (!file.exists()) { toast(R.string.err_share); return }
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.files", file)
            val ext = if (b.isPdf) ".pdf" else if (b.isAudio) ".mp3" else ".txt"
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(mime)
                .putExtra(Intent.EXTRA_STREAM, uri)
                .putExtra(Intent.EXTRA_TITLE, TextLogic.safeFileName(b.title) + ext)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), getString(R.string.menu_share)))
        } catch (_: Exception) { toast(R.string.err_share) }
    }

    private fun listen(b: Book) {
        if (b.isPdf && Library.textSourceOf(b) == null) {
            dialogBuilder(p).setTitle(R.string.need_extract_title).setMessage(R.string.need_extract_msg)
                .setPositiveButton(R.string.menu_extract) { _, _ -> extract(b) }
                .setNegativeButton(android.R.string.cancel, null).show()
            return
        }
        TtsService.play(this, b)
        toast(R.string.tts_starting)
    }

    private fun extract(b: Book) {
        JobManager.startExtract(this, b)
        toast(R.string.job_queued)
        startActivity(Intent(this, JobsActivity::class.java).putExtra(Readers.EXTRA_BOOK, b.id))
    }

    private fun rename(b: Book) {
        val f = editField(p, b.title)
        dialogBuilder(p).setTitle(R.string.menu_rename).setView(dialogColumn().apply { addView(f, vlp()) })
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val t = f.text.toString().trim()
                if (t.isNotEmpty()) { Library.update(b.id) { it.copy(title = t) }; Covers.invalidate(b.id); refresh() }
            }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun pickCover(b: Book) {
        pendingCoverFor = b.id
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "image/*" }
        try { startActivityForResult(i, REQ_COVER) } catch (_: Exception) { toast(R.string.err_no_picker) }
    }

    private fun resetCover(b: Book) {
        Library.customCoverFile(b.id).delete()
        Covers.invalidate(b.id)
        Library.update(b.id) { it.copy(customCover = false) }
        refresh()
    }

    private fun delete(b: Book) {
        dialogBuilder(p).setTitle(R.string.menu_delete).setMessage(getString(R.string.delete_confirm, b.title))
            .setPositiveButton(R.string.delete) { _, _ ->
                if (TtsBus.state.bookId == b.id || TtsBus.state.textBookId == b.id) TtsService.command(this, TtsService.ACTION_STOP)
                JobManager.removeForBook(b.id)
                Covers.invalidate(b.id)
                Library.delete(b.id)
                if (Prefs.lastBook(this) == b.id) Prefs.setLastBook(this, "")
                refresh()
            }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            val sp = getSharedPreferences("ui", MODE_PRIVATE)
            if (sp.getInt("notif_asked", 0) < 2) {
                sp.edit().putInt("notif_asked", sp.getInt("notif_asked", 0) + 1).apply()
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7)
            }
        }
    }

    companion object {
        const val REQ_IMPORT = 1
        const val REQ_COVER = 2
    }
}
