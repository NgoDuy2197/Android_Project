package com.dsoft.odiary

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The compiled day: events laid out as a timeline, with read-aloud and share. */
class CompileActivity : FxActivity() {
    private lateinit var date: String
    private lateinit var day: DayEntry
    private var built = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        date = intent.getStringExtra("date") ?: return finish()
        day = Store.loadDay(date)
        build()
    }

    override fun onResume() {
        super.onResume()
        // reload after editing (so re-compiled content shows); skip the first resume right after onCreate
        if (built && ::date.isInitialized) { day = Store.loadDay(date); build() } else built = true
    }

    private fun build() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(10), dp(8), dp(6)) }
        bar.addView(iconBtn("‹") { finish() })
        bar.addView(text("Tổng hợp ngày", 17f, p.text, 600), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(6) })
        bar.addView(iconBtn("✏️") { startActivity(Intent(this, DayActivity::class.java).putExtra("date", date)) })
        bar.addView(iconBtn("🎧") { Tts.read(this, day.compiled.ifEmpty { textFallback() }) })
        bar.addView(iconBtn("↗") { share() })
        root.addView(bar)

        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = DiaryBg.resolve(this@CompileActivity, date) }
        page.setPadding(dp(14), dp(10), dp(14), dp(16))

        // header
        page.addView(text(prettyDate(), 20f, p.ink, 700))
        if (day.emoji.isNotEmpty() || day.label.isNotEmpty())
            page.addView(text("${day.emoji}  ${day.label}", 16f, p.accent, 600).apply { setPadding(0, dp(4), 0, dp(4)) })

        if (day.events.isEmpty()) page.addView(text("(Ngày này chưa có sự kiện)", 15f, p.sub).apply { setPadding(0, dp(16), 0, 0) })
        day.events.sortedBy { it.timeMs }.forEachIndexed { i, ev -> page.addView(timelineRow(ev, i == day.events.size - 1)) }

        root.addView(ScrollView(this).apply { addView(page) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setFxContent(root)
    }

    private fun timelineRow(ev: Event, last: Boolean): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(10), 0, 0) }
        // left rail: dot + line
        val rail = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; layoutParams = LinearLayout.LayoutParams(dp(26), ViewGroup.LayoutParams.MATCH_PARENT) }
        rail.addView(View(this).apply { background = rounded(p.accent, dp(6).toFloat()); layoutParams = LinearLayout.LayoutParams(dp(12), dp(12)).apply { topMargin = dp(6) } })
        if (!last) rail.addView(View(this).apply { setBackgroundColor(withAlpha(p.accent, 0x55)); layoutParams = LinearLayout.LayoutParams(dp(2), 0, 1f).apply { topMargin = dp(4) } })
        row.addView(rail)

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(withAlpha(p.card, 0xF0), dp(14).toFloat())
            setPadding(dp(12), dp(10), dp(12), dp(12))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(6) }
        }
        card.addView(text(SimpleDateFormat("HH:mm", Locale.US).format(Date(ev.timeMs)), 14f, p.accent, 700))
        if (ev.text.isNotEmpty()) card.addView(text(ev.text, 15f, p.ink).apply { setPadding(0, dp(6), 0, 0) })
        if (ev.photos.isNotEmpty()) {
            val strip = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; setPadding(dp(4), dp(10), dp(4), dp(12))
                clipChildren = false; clipToPadding = false
            }
            val w = (dp(200) * ev.imgScale / 100).coerceAtLeast(dp(80))
            ev.photos.forEach { name ->
                // framed "photo print" with a drop shadow (polaroid-ish)
                val frame = android.widget.FrameLayout(this).apply {
                    background = rounded(0xFFFFFFFF.toInt(), dp(12).toFloat())
                    setPadding(dp(5), dp(5), dp(5), dp(14))
                    elevation = dpf(7)
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                        .apply { marginEnd = dp(14); topMargin = dp(2); bottomMargin = dp(4) }
                }
                val iv = ImageView(this).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true
                    background = rounded(0xFFEDEDED.toInt(), dp(7).toFloat())
                    layoutParams = android.widget.FrameLayout.LayoutParams(w, (w * 0.75f).toInt())
                    setOnClickListener { startActivity(Intent(this@CompileActivity, PhotoViewerActivity::class.java).putExtra("date", date).putExtra("name", name)) }
                }
                frame.addView(iv)
                strip.addView(frame)
                val f = Store.photoFile(date, name)
                Thread { val b = Store.decodeSampled(f, 700); if (b != null) App.main.post { iv.setImageBitmap(b) } }.start()
            }
            card.addView(HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false; clipChildren = false; clipToPadding = false; addView(strip)
            })
        }
        row.addView(card)
        return row
    }

    private fun share() {
        val txt = day.compiled.ifEmpty { textFallback() }
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, txt), "Chia sẻ"))
    }

    private fun textFallback(): String = "${prettyDate()}\n" + day.events.sortedBy { it.timeMs }
        .joinToString("\n\n") { SimpleDateFormat("HH:mm", Locale.US).format(Date(it.timeMs)) + "\n" + it.text }

    override fun onPause() { super.onPause(); Tts.stop() }

    private fun iconBtn(s: String, onClick: () -> Unit) = text(s, 20f, p.accent, 700).apply {
        gravity = Gravity.CENTER; minWidth = dp(42); minHeight = dp(42)
        background = rounded(p.fill, dp(12).toFloat()); setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(4) }
    }

    private fun prettyDate(): String = try {
        SimpleDateFormat("EEEE, dd/MM/yyyy", Locale("vi")).format(SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(date)!!)
    } catch (_: Exception) { date }

    private fun withAlpha(c: Int, a: Int) = (c and 0x00FFFFFF) or (a shl 24)
}
