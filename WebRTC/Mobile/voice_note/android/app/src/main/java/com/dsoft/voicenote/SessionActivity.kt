package com.dsoft.voicenote

import android.app.Activity
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
        nav.addView(circleButton(p, R.drawable.ic_share, sizeDp = 36) { share() })
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
            background = pressable(gradient(dp(26).toFloat(), p.pink, p.red), 0x40FFFFFF)
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
        items.addAll(Transcripts.parse(f))
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
    }

    private fun share() {
        val body = if (live) items.joinToString("") { "${it.time}:\n- ${it.text}\n\n" }
        else file?.let { runCatching { it.readText() }.getOrNull() } ?: ""
        if (body.isBlank()) return
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, title.text.toString())
            .putExtra(Intent.EXTRA_TEXT, body.takeLast(200_000)) // binder limit ~1 MB
        startActivity(Intent.createChooser(send, title.text))
    }

    private inner class BubbleAdapter : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val holder = convertView as? LinearLayout ?: LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.END
                setPadding(0, dp(4), 0, dp(4))
                addView(text("", 11f, p.secondary).apply { setPadding(0, 0, dp(10), dp(3)) })
                addView(bubble(context, p, ""))
            }
            val e = items[position]
            (holder.getChildAt(0) as TextView).apply {
                text = e.time
                visibility = if (e.time.isEmpty()) View.GONE else View.VISIBLE
            }
            (holder.getChildAt(1) as TextView).text = e.text
            return holder
        }
    }

    companion object {
        const val EXTRA_FILE = "file"

        fun openLive(ctx: Context) =
            ctx.startActivity(Intent(ctx, SessionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP))

        fun openFile(ctx: Context, f: File) =
            ctx.startActivity(Intent(ctx, SessionActivity::class.java).putExtra(EXTRA_FILE, f.absolutePath))
    }
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
