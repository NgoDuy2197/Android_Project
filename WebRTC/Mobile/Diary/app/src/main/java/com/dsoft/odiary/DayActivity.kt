package com.dsoft.odiary

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** View one day: events (text + small thumbnails), day label/emoji, background, padding, compile, read. */
class DayActivity : FxActivity() {
    private lateinit var date: String
    private lateinit var day: DayEntry
    private lateinit var pageBox: LinearLayout
    private lateinit var eventsBox: LinearLayout
    private lateinit var labelView: TextView
    private var built = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        date = intent.getStringExtra("date") ?: return finish()
        day = Store.loadDay(date)
        buildUi()
        built = true
    }

    override fun onResume() {
        super.onResume()
        if (built) { day = Store.loadDay(date); applyPage(); renderLabel(); renderEvents() }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(6), dp(10), dp(6), dp(6)) }
        bar.addView(iconBtn("‹") { finish() })
        bar.addView(text(prettyDate(), 16f, p.text, 600), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(6) })
        bar.addView(iconBtn("🎧") { readDay() })
        bar.addView(iconBtn("⋯") { moreMenu() })
        root.addView(bar)

        // Tổng hợp — ở ngay trên cùng, dưới thanh back
        root.addView(pill("📖 Tổng hợp & xem lại", p.fill, p.accent) { compile() }.apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).apply { setMargins(dp(14), dp(2), dp(14), dp(4)) })

        pageBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipToPadding = false; clipChildren = false
        }
        applyPage()
        labelView = text("", 16f, p.ink, 600).apply { setOnClickListener { editLabel() }; setPadding(dp(4), 0, dp(4), 0) }
        pageBox.addView(labelView)
        eventsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipToPadding = false; clipChildren = false }
        pageBox.addView(eventsBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        root.addView(ScrollView(this).apply { clipToPadding = false; addView(pageBox) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // Thêm sự kiện — nút tròn ở giữa đáy
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(12), dp(8), dp(12), dp(14)); gravity = Gravity.CENTER }
        actions.addView(bigButton("＋  Thêm sự kiện", p.accent, p.accent2) { openEditor(-1) }.apply { setPadding(dp(30), dp(14), dp(30), dp(14)) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(54)))
        root.addView(actions)

        setFxContent(root)
        renderLabel(); renderEvents()
    }

    private fun applyPage() {
        pageBox.background = DiaryBg.resolve(this, date)
        // nội dung không còn padding tuỳ chỉnh — dùng lề cố định gọn gàng
        pageBox.setPadding(dp(14), dp(6), dp(14), dp(16))
    }

    private fun iconBtn(s: String, onClick: () -> Unit) = text(s, 20f, p.accent, 700).apply {
        gravity = Gravity.CENTER; minWidth = dp(44); minHeight = dp(44)
        background = rounded(p.fill, dp(12).toFloat()); setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(4) }
    }

    private fun renderLabel() {
        labelView.text = emojify((if (day.emoji.isNotEmpty()) day.emoji + "  " else "") + day.label.ifEmpty { "✎ Thêm nhãn cho ngày…" })
        labelView.setTextColor(if (day.label.isEmpty() && day.emoji.isEmpty()) p.sub else p.ink)
    }

    private fun renderEvents() {
        eventsBox.removeAllViews()
        if (day.events.isEmpty()) {
            eventsBox.addView(text("Chưa có sự kiện. Bấm \"+ Thêm sự kiện\".", 14f, p.sub).apply { setPadding(0, dp(20), 0, 0) })
            return
        }
        day.events.sortBy { it.timeMs }
        day.events.forEach { ev -> eventsBox.addView(eventCard(ev)) }
    }

    private fun eventCard(ev: Event): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(p.card, dp(20).toFloat())
            setPadding(dp(14), dp(12), dp(14), dp(14))
            elevation = dpf(5)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(12); leftMargin = dp(4); rightMargin = dp(4) }
            setOnClickListener { openEditor(ev.id) }
        }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(text("🕒 " + SimpleDateFormat("HH:mm", Locale.US).format(Date(ev.timeMs)), 13f, p.accent, 600))
        head.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        if (ev.photos.isNotEmpty()) head.addView(text("📷 ${ev.photos.size}", 12f, p.sub))
        head.addView(text("  ✎ sửa", 12f, p.sub))
        col.addView(head)
        if (ev.text.isNotEmpty()) col.addView(text(ev.text, 15f, p.ink).apply { setPadding(0, dp(6), 0, 0) })
        // small thumbnails UNDER the text
        if (ev.photos.isNotEmpty()) {
            val strip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0) }
            val t = dp(58)
            ev.photos.forEach { name ->
                val iv = ImageView(this).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true
                    background = rounded(p.fill, dp(9).toFloat())
                    layoutParams = LinearLayout.LayoutParams(t, t).apply { marginEnd = dp(6) }
                    setOnClickListener { openPhotos(name) }
                }
                strip.addView(iv)
                val f = Store.photoFile(date, name)
                Thread { val b = Store.decodeSampled(f, 200); if (b != null) App.main.post { iv.setImageBitmap(b) } }.start()
            }
            col.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(strip) })
        }
        return col
    }

    private fun openEditor(eventId: Long) =
        startActivity(Intent(this, EventEditorActivity::class.java).putExtra("date", date).putExtra("eventId", eventId))

    private fun openPhotos(name: String) =
        startActivity(Intent(this, PhotoViewerActivity::class.java).putExtra("date", date).putExtra("name", name))

    // ---------- label / emoji ----------
    private fun editLabel() {
        val et = EditText(this).apply { setText(day.label); hint = "Mô tả ngắn cho ngày"; setTextColor(p.text) }
        AlertDialog.Builder(this).setTitle("Nhãn ngày").setView(et)
            .setPositiveButton("Lưu") { _, _ -> day.label = et.text.toString().trim(); Store.saveDay(day); renderLabel() }
            .setNeutralButton("Chọn emoji") { _, _ -> day.label = et.text.toString().trim(); Store.saveDay(day); pickEmoji() }
            .setNegativeButton("Huỷ", null).show()
    }

    private var emojiDialog: AlertDialog? = null
    private fun pickEmoji() {
        // NOTE: keep as a list of full strings — splitting by Char breaks surrogate-pair emojis into "?".
        val emojis = listOf(
            "😀", "😁", "😍", "🥰", "😎", "🤩", "😭", "😡", "🥳", "😴",
            "🤒", "😇", "🤗", "😋", "😅", "🙂", "😢", "😤", "👍", "💪",
            "🎉", "🎂", "🎁", "❤️", "💔", "⭐", "🔥", "🌈", "☀️", "🌧️",
            "⛄", "🌸", "🍀", "🍎", "🍔", "🍜", "🍺", "☕", "🎮", "⚽",
            "🏀", "🎸", "🎵", "✈️", "🏖️", "🏠", "💼", "📚", "✏️", "💡",
            "💊", "🐶", "🐱", "🌟", "🍩"
        )
        val cols = 5
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(10), dp(12), dp(10)) }
        var rowL: LinearLayout? = null
        emojis.forEachIndexed { i, e ->
            if (i % cols == 0) { rowL = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }; grid.addView(rowL) }
            rowL!!.addView(text(e, 26f, p.text).apply {
                gravity = Gravity.CENTER
                setPadding(dp(10), dp(12), dp(10), dp(12))
                setOnClickListener { day.emoji = e; Store.saveDay(day); renderLabel(); emojiDialog?.dismiss() }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        emojiDialog = AlertDialog.Builder(this).setTitle("Chọn icon cho ngày").setView(ScrollView(this).apply { addView(grid) })
            .setNeutralButton("Bỏ icon") { _, _ -> day.emoji = ""; Store.saveDay(day); renderLabel() }
            .setNegativeButton("Đóng", null).show()
    }

    // ---------- more menu ----------
    private fun moreMenu() {
        AlertDialog.Builder(this).setItems(arrayOf("🎨 Nền nhật ký", "📖 Tổng hợp & xem", "🎧 Đọc nhật ký")) { _, w ->
            when (w) { 0 -> bgMenu(); 1 -> compile(); 2 -> readDay() }
        }.show()
    }

    private fun bgMenu() {
        val specs = DiaryBg.templates + listOf("img", "")
        val labels = specs.map { if (it == "") "Theo cấu hình chung" else if (it == "img") "Import ảnh nền (ngày này)" else DiaryBg.label(it) }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Nền cho ngày này").setItems(labels) { _, w ->
            val spec = specs[w]
            if (spec == "img") pickDayBg() else { day.bg = spec; Store.saveDay(day); applyPage() }
        }.show()
    }

    private fun pickDayBg() {
        try { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"), REQ_DAYBG) }
        catch (_: Exception) { toast("Không mở được thư viện") }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_DAYBG && resultCode == RESULT_OK) data?.data?.let { setDayBgImage(it) }
    }

    private fun setDayBgImage(uri: Uri) {
        if (Store.saveDayBg(this, date, uri)) { day.bg = "img"; Store.saveDay(day); applyPage() }
        else toast("Không lưu được ảnh nền")
    }

    // ---------- compile & read ----------
    private fun compile() {
        if (day.events.isEmpty() && day.label.isEmpty()) { toast("Chưa có gì để tổng hợp"); return }
        day.compiled = buildCompiled(); Store.saveDay(day)
        startActivity(Intent(this, CompileActivity::class.java).putExtra("date", date))
    }

    private fun buildCompiled(): String {
        val sb = StringBuilder(prettyDate())
        if (day.emoji.isNotEmpty() || day.label.isNotEmpty()) sb.append("  ").append(day.emoji).append(" ").append(day.label)
        sb.append("\n\n")
        day.events.sortedBy { it.timeMs }.forEach { ev ->
            sb.append("● ").append(SimpleDateFormat("HH:mm", Locale.US).format(Date(ev.timeMs)))
            if (ev.photos.isNotEmpty()) sb.append("  📷×").append(ev.photos.size)
            sb.append("\n")
            if (ev.text.isNotEmpty()) sb.append(ev.text).append("\n")
            sb.append("\n")
        }
        return sb.toString().trim()
    }

    private fun readDay() {
        val txt = if (day.compiled.isNotEmpty()) day.compiled else buildCompiled()
        if (txt.isBlank()) { toast("Chưa có nội dung"); return }
        Tts.read(this, txt); toast("Đang đọc…")
    }

    override fun onPause() { super.onPause(); Tts.stop() }

    private fun prettyDate(): String = try {
        SimpleDateFormat("EEEE, dd/MM/yyyy", Locale("vi")).format(SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(date)!!)
    } catch (_: Exception) { date }

    private fun withAlpha(c: Int, a: Int) = (c and 0x00FFFFFF) or (a shl 24)
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    companion object { private const val REQ_DAYBG = 3 }
}
