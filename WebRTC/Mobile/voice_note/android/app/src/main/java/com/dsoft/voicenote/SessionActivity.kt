package com.dsoft.voicenote

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.res.ColorStateList
import android.widget.SeekBar
import android.widget.Toast
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import java.io.File

/**
 * A recording session as a chat: every transcript entry is a bubble.
 * Live mode follows the running recorder (typing bubble, level bars, stop);
 * file mode shows a saved transcript.
 */
class SessionActivity : Activity() {
    private lateinit var p: Palette
    private lateinit var title: TextView
    private lateinit var subtitle: TextView
    private lateinit var list: ListView
    private lateinit var empty: TextView
    private lateinit var bottom: LinearLayout
    private lateinit var bars: LevelBars
    private lateinit var timer: TextView
    private lateinit var typing: TextView
    private lateinit var tune: LinearLayout
    private lateinit var silenceLabel: TextView
    private lateinit var waitLabel: TextView
    private lateinit var silenceSeek: SeekBar
    private lateinit var waitSeek: SeekBar
    private var exportJson = true
    /** key(entry) -> translation (shown under the bubble). */
    private val translated = HashMap<String, String>()
    private val items = ArrayList<Entry>()
    private val adapter = BubbleAdapter()
    private val handler = Handler(Looper.getMainLooper())
    private var live = false
    private var file: File? = null
    private var shownVersion = -1
    private var liveSince = 0L

    private val tick = object : Runnable {
        override fun run() {
            if (live) renderLive()
            handler.postDelayed(this, 100)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        p = palette()
        setupSystemBars(p)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // nav bar
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(8), dp(12), dp(8))
        }
        nav.addView(icon(R.drawable.ic_back, p.blue, 34).apply {
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = pressable(null, p.ripple)
            setOnClickListener { finish() }
        })
        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        title = text("", 17f, p.text, 700).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        }
        subtitle = text("", 12f, p.secondary).apply { setPadding(0, dp(2), 0, 0) }
        titles.addView(title)
        titles.addView(subtitle)
        nav.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        nav.addView(circleButton(p, R.drawable.ic_translate, sizeDp = 36) { translateAll() })
        nav.addView(circleButton(p, R.drawable.ic_copy, sizeDp = 36) { copyAll() }.apply {
            (layoutParams as LinearLayout.LayoutParams).marginStart = dp(8)
        })
        nav.addView(circleButton(p, R.drawable.ic_export, sizeDp = 36) { exportMenu() }.apply {
            (layoutParams as LinearLayout.LayoutParams).marginStart = dp(8)
        })
        root.addView(nav)
        root.addView(View(this).apply { setBackgroundColor(p.separator) }, vlp(h = 1))

        // messages
        val content = FrameLayout(this)
        list = ListView(this).apply {
            divider = null
            selector = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
            transcriptMode = AbsListView.TRANSCRIPT_MODE_NORMAL // stick to bottom while at bottom
            clipToPadding = false
            setPadding(dp(12), dp(10), dp(12), dp(10))
            isVerticalScrollBarEnabled = false
        }
        typing = bubble(this, p, "", typing = true)
        list.addFooterView(FrameLayout(this).apply {
            setPadding(0, dp(4), 0, dp(4))
            addView(typing, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END))
        }, null, false)
        list.adapter = adapter
        content.addView(list)
        empty = text("", 15f, p.secondary).apply { gravity = Gravity.CENTER }
        content.addView(empty, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // live tuning: this session's silence level + listening wait
        tune = card(p).apply { setPadding(dp(14), dp(8), dp(14), dp(8)) }
        silenceLabel = text("", 13f, p.secondary, 500)
        waitLabel = text("", 13f, p.secondary, 500)
        silenceSeek = seek(88) { v ->
            RecorderService.silenceLevel = (v + 2) / 100f
            renderTune()
        }
        waitSeek = seek(97) { v ->
            RecorderService.waitMs = (v + 3) * 100L
            renderTune()
        }
        tune.addView(tuneRow(silenceLabel, silenceSeek))
        tune.addView(tuneRow(waitLabel, waitSeek))
        root.addView(tune, vlp().apply { setMargins(dp(12), dp(6), dp(12), 0) })

        // live controls
        bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(p.card, dp(22).toFloat())
            elevation = dp(6).toFloat()
            setPadding(dp(18), dp(10), dp(10), dp(10))
        }
        timer = text("00:00", 17f, p.text, 500).apply { minWidth = dp(64) }
        bottom.addView(timer)
        bars = LevelBars(this)
        bottom.addView(bars, LinearLayout.LayoutParams(0, dp(30), 1f).apply { marginStart = dp(8); marginEnd = dp(12) })
        bottom.addView(FrameLayout(this).apply {
            background = pressable(gradient(dp(26).toFloat(), p.roseLight, p.roseDeep), 0x40FFFFFF)
            addView(View(context).apply { background = rounded(Color.WHITE, dp(4).toFloat()) },
                FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER))
            setOnClickListener { RecorderService.stop(this@SessionActivity) }
            contentDescription = "Dừng ghi"
        }, LinearLayout.LayoutParams(dp(52), dp(52)))
        root.addView(bottom, vlp().apply { setMargins(dp(12), dp(4), dp(12), dp(14)) })

        setContentView(root)
        load(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        load(intent)
    }

    override fun onResume() {
        super.onResume()
        handler.post(tick)
    }

    override fun onPause() {
        handler.removeCallbacks(tick)
        super.onPause()
    }

    private fun load(intent: Intent) {
        val path = intent.getStringExtra(EXTRA_FILE)
        if (path == null && (RecorderService.isRunning || Prefs(this).wantRunning)) {
            live = true
            liveSince = System.currentTimeMillis()
            file = null
            shownVersion = -1
            title.text = "Phiên ghi âm"
            bottom.visibility = View.VISIBLE
            tune.visibility = View.VISIBLE
            silenceSeek.progress = (RecorderService.silenceLevel * 100).toInt() - 2
            waitSeek.progress = (RecorderService.waitMs / 100).toInt() - 3
            renderTune()
            empty.text = "Hãy nói gì đó…\nNội dung sẽ hiện ở đây."
            renderLive()
        } else {
            showFile(path?.let { File(it) } ?: RecorderService.sessionFile)
        }
    }

    private fun showFile(f: File?) {
        live = false
        file = f
        bottom.visibility = View.GONE
        tune.visibility = View.GONE
        typing.visibility = View.GONE
        if (f == null || !f.exists()) {
            title.text = "Phiên ghi âm"
            subtitle.text = "Không có nội dung"
            items.clear()
            adapter.notifyDataSetChanged()
            empty.text = "Phiên này chưa ghi được câu nào."
            empty.visibility = View.VISIBLE
            return
        }
        title.text = f.nameWithoutExtension
        items.clear()
        items.addAll(Transcripts.read(f))
        adapter.notifyDataSetChanged()
        subtitle.text = "${items.size} đoạn"
        empty.text = "File trống"
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        list.post { list.setSelection(adapter.count - 1) }
    }

    private fun renderLive() {
        if (!RecorderService.isRunning) {
            // the service may still be starting right after the record tap
            if (Prefs(this).wantRunning && System.currentTimeMillis() - liveSince < 5000) {
                subtitle.text = "Đang khởi động…"
                return
            }
            showFile(RecorderService.sessionFile) // recording stopped: show what was saved
            return
        }
        if (RecorderService.entriesVersion != shownVersion) {
            shownVersion = RecorderService.entriesVersion
            items.clear()
            items.addAll(RecorderService.entries())
            adapter.notifyDataSetChanged()
        }
        val partial = RecorderService.partial
        typing.text = if (partial.isBlank()) "" else "$partial …"
        typing.visibility = if (partial.isBlank()) View.GONE else View.VISIBLE
        empty.visibility = if (items.isEmpty() && partial.isBlank()) View.VISIBLE else View.GONE
        timer.text = elapsed(RecorderService.sessionStart)
        subtitle.text = RecorderService.status
        bars.push(RecorderService.level)
        bars.threshold = RecorderService.silenceLevel
    }

    private fun renderTune() {
        silenceLabel.text = "Yên lặng khi < ${(RecorderService.silenceLevel * 100).toInt()}%"
        waitLabel.text = "Chờ nghe %.1f giây".format(RecorderService.waitMs / 1000.0)
    }

    private fun seek(max: Int, onChange: (Int) -> Unit) = SeekBar(this).apply {
        this.max = max
        progressTintList = ColorStateList.valueOf(p.roseDeep)
        thumbTintList = ColorStateList.valueOf(p.roseDeep)
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, v: Int, fromUser: Boolean) { if (fromUser) onChange(v) }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
    }

    private fun tuneRow(label: TextView, seek: SeekBar) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(label, LinearLayout.LayoutParams(dp(132), ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(seek, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    /** Live: everything said so far (incl. the open line); file: the saved lines. */
    private fun current(): List<Entry> {
        if (!live) return ArrayList(items)
        val open = RecorderService.partial.trim()
        return if (open.isEmpty()) ArrayList(items) else items + Entry(System.currentTimeMillis(), "…", open)
    }

    private fun copyAll() {
        val list = current()
        val text = Transcripts.toText(list)
        if (text.isBlank()) return toast("Chưa có nội dung")
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(title.text, text))
        toast("Đã copy ${list.size} đoạn")
    }

    private fun exportMenu() {
        if (current().isEmpty()) return toast("Chưa có nội dung")
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Xuất phiên")
            .setItems(arrayOf("Lưu file JSON (có thời gian)", "Lưu file văn bản (.txt)", "Chia sẻ…")) { _, which ->
                when (which) {
                    0, 1 -> saveAs(json = which == 0)
                    2 -> share()
                }
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    private fun baseName() = file?.nameWithoutExtension ?: RecorderService.sessionFile?.nameWithoutExtension
        ?: Transcripts.sessionFileName(System.currentTimeMillis()).removeSuffix(".json")

    private fun saveAs(json: Boolean) {
        exportJson = json
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType(if (json) "application/json" else "text/plain")
            .putExtra(Intent.EXTRA_TITLE, baseName() + if (json) ".json" else ".txt")
        try {
            startActivityForResult(i, REQ_EXPORT)
        } catch (e: Exception) {
            toast("Máy không có trình lưu file")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode != REQ_EXPORT || resultCode != RESULT_OK || uri == null) return
        val entries = current()
        val body = if (exportJson) Transcripts.toJson(entries, Prefs(this).language) else Transcripts.toText(entries)
        try {
            contentResolver.openOutputStream(uri, "wt")!!.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            toast("Đã lưu")
        } catch (e: Exception) {
            toast("Không lưu được: ${e.message}")
        }
    }

    private fun share() {
        val body = Transcripts.toText(current())
        if (body.isBlank()) return
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, title.text.toString())
            .putExtra(Intent.EXTRA_TEXT, body.takeLast(200_000)) // binder limit ~1 MB
        startActivity(Intent.createChooser(send, title.text))
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private inner class BubbleAdapter : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val holder = convertView as? LinearLayout ?: LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.END
                setPadding(0, dp(4), 0, dp(4))
                addView(text("", 11f, p.secondary).apply { setPadding(0, 0, dp(10), dp(3)) })            // 0 time
                addView(bubble(context, p, ""))                                                           // 1 original
                addView(text("Dịch", 12f, p.blue, 600).apply {                                            // 2 translate link
                    setPadding(dp(6), dp(4), dp(10), dp(2))
                })
                addView(transBubble(context, p, ""))                                                      // 3 translation
            }
            val e = items[position]
            val key = keyOf(e)
            (holder.getChildAt(0) as TextView).apply {
                text = e.time
                visibility = if (e.time.isEmpty()) View.GONE else View.VISIBLE
            }
            (holder.getChildAt(1) as TextView).text = e.text
            val link = holder.getChildAt(2) as TextView
            val tr = holder.getChildAt(3) as TextView
            val existing = translated[key]
            tr.text = existing ?: ""
            tr.visibility = if (existing.isNullOrEmpty()) View.GONE else View.VISIBLE
            link.visibility = if (e.text.isBlank()) View.GONE else View.VISIBLE
            link.text = if (existing.isNullOrEmpty()) "Dịch" else "Dịch lại"
            link.setOnClickListener { translateOne(e, link, tr) }
            return holder
        }
    }

    private fun keyOf(e: Entry) = "${e.at}|${e.time}|${e.text}"

    private fun translateOne(e: Entry, link: TextView, tr: TextView) {
        if (e.text.isBlank()) return
        link.text = "Đang dịch…"
        link.isEnabled = false
        val target = Prefs(this).targetLang
        Translator.translate(e.text, "auto", target) { r ->
            link.isEnabled = true
            if (r.ok && r.text.isNotBlank()) {
                translated[keyOf(e)] = r.text
                tr.text = r.text
                tr.visibility = View.VISIBLE
                link.text = "Dịch lại"
            } else {
                link.text = "Dịch"
                toast(if (r.ok) "Không có nội dung dịch" else "Lỗi dịch: ${r.text}")
            }
        }
    }

    /** "Dịch tất cả": pick source (incl. auto) + target, then translate every line. */
    private fun translateAll() {
        val entries = current().filter { it.text.isNotBlank() }
        if (entries.isEmpty()) return toast("Chưa có nội dung")
        val srcCodes = listOf("auto", "vi", "en", "ja", "zh")
        val tgtCodes = Translator.LANGS.keys.toList()
        val srcSpin = android.widget.Spinner(this).apply {
            adapter = android.widget.ArrayAdapter(this@SessionActivity, android.R.layout.simple_spinner_dropdown_item,
                srcCodes.map { Translator.SHORT[it] ?: it })
            setSelection(0)
        }
        val tgtSpin = android.widget.Spinner(this).apply {
            adapter = android.widget.ArrayAdapter(this@SessionActivity, android.R.layout.simple_spinner_dropdown_item,
                tgtCodes.map { Translator.LANGS[it] ?: it })
            setSelection(tgtCodes.indexOf(Prefs(this@SessionActivity).targetLang).coerceAtLeast(0))
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(10), dp(22), 0)
            addView(text("Ngôn ngữ gốc", 13f, p.secondary))
            addView(srcSpin)
            addView(text("Dịch sang", 13f, p.secondary).apply { setPadding(0, dp(12), 0, 0) })
            addView(tgtSpin)
        }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Dịch tất cả (${entries.size} đoạn)")
            .setView(box)
            .setPositiveButton("Dịch") { _, _ ->
                runTranslateAll(entries, srcCodes[srcSpin.selectedItemPosition], tgtCodes[tgtSpin.selectedItemPosition])
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    private fun runTranslateAll(entries: List<Entry>, src: String, target: String) {
        toast("Đang dịch ${entries.size} đoạn…")
        Thread {
            var done = 0
            var failed = 0
            for (e in entries) {
                val r = runCatching { Translator.gtx(e.text, src, target) }.getOrNull()
                if (r != null && r.ok && r.text.isNotBlank()) {
                    translated[keyOf(e)] = r.text
                    done++
                } else failed++
                handler.post { adapter.notifyDataSetChanged() }
                try { Thread.sleep(120) } catch (_: InterruptedException) {}
            }
            handler.post {
                adapter.notifyDataSetChanged()
                toast("Đã dịch $done đoạn" + if (failed > 0) ", lỗi $failed" else "")
            }
        }.start()
    }

    companion object {
        const val EXTRA_FILE = "file"
        private const val REQ_EXPORT = 7

        fun openLive(ctx: Context) =
            ctx.startActivity(Intent(ctx, SessionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP))

        fun openFile(ctx: Context, f: File) =
            ctx.startActivity(Intent(ctx, SessionActivity::class.java).putExtra(EXTRA_FILE, f.absolutePath))
    }
}

/** Softer bubble for the translation shown under the original. */
private fun transBubble(ctx: Context, p: Palette, s: String): TextView = ctx.text(s, 15f, Color.WHITE).apply {
    setLineSpacing(0f, 1.18f)
    setPadding(ctx.dp(14), ctx.dp(8), ctx.dp(14), ctx.dp(9))
    maxWidth = (ctx.resources.displayMetrics.widthPixels * 0.80f).toInt()
    setTextIsSelectable(true)
    background = gradient(ctx.dp(19).toFloat(), p.green, 0xFF2AA198.toInt())
    (layoutParams as? LinearLayout.LayoutParams)?.topMargin = ctx.dp(2)
}

/** iMessage-style gradient bubble; [typing] = translucent "being said" bubble. */
private fun bubble(ctx: Context, p: Palette, s: String, typing: Boolean = false): TextView = ctx.text(s, 16f, Color.WHITE).apply {
    setLineSpacing(0f, 1.18f)
    setPadding(ctx.dp(14), ctx.dp(9), ctx.dp(14), ctx.dp(10))
    maxWidth = (ctx.resources.displayMetrics.widthPixels * 0.80f).toInt()
    setTextIsSelectable(!typing)
    background = gradient(ctx.dp(19).toFloat(), p.blue, p.indigo)
    if (typing) alpha = 0.55f
}
