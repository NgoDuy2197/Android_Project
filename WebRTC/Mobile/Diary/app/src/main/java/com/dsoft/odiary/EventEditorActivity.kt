package com.dsoft.odiary

import android.app.TimePickerDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Full-screen editor for one event: text, photos (add / remove), image size.
 * Photos are imported into the day folder *immediately* when picked (robust against
 * URI-permission expiry / activity recreation); cleaned up on cancel if not saved.
 */
class EventEditorActivity : FxActivity() {
    private lateinit var date: String
    private var eventId: Long = -1
    private var draftId: Long = 0
    private var hadEvent = false

    private val photos = ArrayList<String>()         // filenames currently attached (shown)
    private var origPhotos: List<String> = emptyList()
    private val sessionImported = ArrayList<String>() // imported this session (cleanup if cancelled)
    private var committed = false

    private var scale = 100
    private var eventTimeMs = 0L
    private lateinit var textField: EditText
    private lateinit var photoGrid: LinearLayout
    private lateinit var timeLabel: TextView
    private var cameraOut: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        date = intent.getStringExtra("date") ?: return finish()
        eventId = intent.getLongExtra("eventId", -1)
        draftId = if (eventId > 0) eventId else System.currentTimeMillis()
        val existing = Store.loadDay(date).events.firstOrNull { it.id == eventId }
        hadEvent = existing != null
        existing?.let { scale = it.imgScale; eventTimeMs = it.timeMs; origPhotos = it.photos.toList() }
        if (!hadEvent) eventTimeMs = defaultTimeForDay()   // default = now, on this day
        if (savedInstanceState == null) {
            existing?.let { photos.addAll(it.photos) }
        } else {
            draftId = savedInstanceState.getLong("draftId", draftId)
            cameraOut = savedInstanceState.getString("cameraOut")?.let { Uri.parse(it) }
            savedInstanceState.getStringArrayList("photos")?.let { photos.clear(); photos.addAll(it) }
            savedInstanceState.getStringArrayList("sessionImported")?.let { sessionImported.clear(); sessionImported.addAll(it) }
            scale = savedInstanceState.getInt("scale", scale)
            eventTimeMs = savedInstanceState.getLong("eventTimeMs", eventTimeMs)
        }
        build()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong("draftId", draftId)
        outState.putString("cameraOut", cameraOut?.toString())
        outState.putStringArrayList("photos", ArrayList(photos))
        outState.putStringArrayList("sessionImported", ArrayList(sessionImported))
        outState.putInt("scale", scale)
        outState.putLong("eventTimeMs", eventTimeMs)
    }

    private fun defaultTimeForDay(): Long {
        val now = Calendar.getInstance()
        return try {
            Calendar.getInstance().apply {
                set(date.substring(0, 4).toInt(), date.substring(5, 7).toInt() - 1, date.substring(8, 10).toInt(),
                    now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
        } catch (_: Exception) { System.currentTimeMillis() }
    }

    private fun fmtTime(ms: Long) = SimpleDateFormat("HH:mm", Locale.US).format(Date(ms))

    private fun pickTime() {
        val c = Calendar.getInstance().apply { timeInMillis = eventTimeMs }
        TimePickerDialog(this, { _, h, m ->
            c.set(Calendar.HOUR_OF_DAY, h); c.set(Calendar.MINUTE, m); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
            eventTimeMs = c.timeInMillis
            timeLabel.text = emojify("🕒 ${fmtTime(eventTimeMs)}")
        }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), true).show()
    }

    private fun build() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(10), dp(10), dp(6)) }
        bar.addView(text("‹", 24f, p.accent, 700).apply { minWidth = dp(40); gravity = Gravity.CENTER; setOnClickListener { finish() } })
        bar.addView(text(if (hadEvent) "Sửa sự kiện" else "Thêm sự kiện", 18f, p.text, 700),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(4) })
        if (hadEvent) bar.addView(pill("Xoá", p.fill, p.red) { deleteEvent() }.apply { (layoutParams as? LinearLayout.LayoutParams)?.marginEnd = dp(8) })
        bar.addView(pill("Lưu", p.accent, android.graphics.Color.WHITE) { save() })
        root.addView(bar)

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(30)) }

        // time row — defaults to now, tap to change
        timeLabel = pill("🕒 ${fmtTime(eventTimeMs)}", p.fill, p.accent) { pickTime() }
        val timeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, dp(10)) }
        timeRow.addView(text("Thời gian", 14f, p.sub), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        timeRow.addView(timeLabel)
        col.addView(timeRow)

        textField = EditText(this).apply {
            setText(Store.loadDay(date).events.firstOrNull { it.id == eventId }?.text ?: "")
            hint = "Hôm nay có gì…"; setTextColor(p.text); setHintTextColor(p.sub)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            gravity = Gravity.TOP; minLines = 5
            background = rounded(p.card, dp(12).toFloat()); setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        col.addView(textField)

        val addRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(12), 0, 0) }
        addRow.addView(pill("📷 Chụp ảnh", p.fill, p.accent) { launchCamera() }, flex())
        addRow.addView(pill("🖼 Thêm ảnh", p.fill, p.accent) { launchGallery() }, flex())
        col.addView(addRow)

        col.addView(text("Ảnh (chạm ✕ để xoá)", 13f, p.sub, 600).apply { setPadding(dp(2), dp(14), 0, dp(4)) })
        photoGrid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(photoGrid)

        val sizeLabel = text("Cỡ ảnh khi hiển thị trong nhật ký: $scale%", 13f, p.text, 600).apply { setPadding(0, dp(16), 0, dp(2)) }
        col.addView(sizeLabel)
        col.addView(android.widget.SeekBar(this).apply {
            max = 150; progress = (scale - 50).coerceIn(0, 150)
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: android.widget.SeekBar?, v: Int, u: Boolean) { scale = v + 50; sizeLabel.text = "Cỡ ảnh khi hiển thị trong nhật ký: $scale%" }
                override fun onStartTrackingTouch(s: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(s: android.widget.SeekBar?) {}
            })
        })

        root.addView(ScrollView(this).apply { addView(col) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setFxContent(root)
        renderPhotos()
    }

    private fun flex() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(5); marginEnd = dp(5) }

    private fun renderPhotos() {
        photoGrid.removeAllViews()
        if (photos.isEmpty()) { photoGrid.addView(text("Chưa có ảnh.", 13f, p.sub)); return }
        val perRow = 3
        var row: LinearLayout? = null
        photos.forEachIndexed { i, name ->
            if (i % perRow == 0) { row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }; photoGrid.addView(row) }
            row!!.addView(photoThumb(name), LinearLayout.LayoutParams(0, dp(110), 1f).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) })
        }
        val rem = photos.size % perRow
        if (rem != 0) for (k in 0 until perRow - rem) row!!.addView(View(this), LinearLayout.LayoutParams(0, dp(110), 1f).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) })
    }

    private fun photoThumb(name: String): FrameLayout {
        val fl = FrameLayout(this)
        val iv = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true
            background = rounded(p.fill, dp(10).toFloat())
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        fl.addView(iv)
        val f = Store.photoFile(date, name)
        Thread { val b = Store.decodeSampled(f, 400); if (b != null) App.main.post { iv.setImageBitmap(b) } }.start()
        val x = text("✕", 15f, android.graphics.Color.WHITE, 700).apply {
            gravity = Gravity.CENTER; minWidth = dp(26); minHeight = dp(26)
            background = rounded(0xCC000000.toInt(), dp(13).toFloat())
            setOnClickListener { photos.remove(name); renderPhotos() }
        }
        fl.addView(x, FrameLayout.LayoutParams(dp(26), dp(26), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(4), dp(4), 0) })
        return fl
    }

    private fun launchCamera() {
        try {
            val f = File(cacheDir, "cam_${System.currentTimeMillis()}.jpg")
            cameraOut = FileProvider.getUriForFile(this, "$packageName.files", f)
            startActivityForResult(Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE)
                .putExtra(android.provider.MediaStore.EXTRA_OUTPUT, cameraOut)
                .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION), REQ_CAM)
        } catch (_: Exception) { toast("Không mở được camera") }
    }

    private fun launchGallery() {
        try {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("image/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true), REQ_GAL)
        } catch (_: Exception) { toast("Không mở được thư viện") }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uris = ArrayList<Uri>()
        when (requestCode) {
            REQ_CAM -> cameraOut?.let { uris.add(it) }
            REQ_GAL -> {
                val clip = data?.clipData
                if (clip != null) for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri)
                else data?.data?.let { uris.add(it) }
            }
            else -> return
        }
        if (uris.isEmpty()) return
        // import immediately on a background thread, then show
        Thread {
            val added = Store.importPhotos(this, date, uris, draftId)
            App.main.post {
                if (added.isEmpty()) { toast("Không lưu được ảnh"); return@post }
                photos.addAll(added); sessionImported.addAll(added); renderPhotos()
            }
        }.start()
    }

    private fun save() {
        val day = Store.loadDay(date)
        val ev = day.events.firstOrNull { it.id == eventId }
            ?: Event(draftId, eventTimeMs).also { day.events.add(it) }
        ev.timeMs = eventTimeMs
        ev.text = textField.text.toString()
        ev.imgScale = scale
        ev.photos.clear(); ev.photos.addAll(photos)
        Store.saveDay(day)
        // delete files no longer referenced (removed originals or removed session imports) + queue Drive deletion
        val keep = photos.toSet()
        (origPhotos + sessionImported).distinct().forEach { if (it !in keep) Store.deletePhoto(date, it) }
        committed = true
        finish()
    }

    private fun deleteEvent() {
        val day = Store.loadDay(date)
        day.events.firstOrNull { it.id == eventId }?.let { ev -> Store.deleteEventPhotos(date, ev); day.events.remove(ev); Store.saveDay(day) }
        committed = true
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        // cancelled without saving -> drop any files imported this session (not committed to day.json);
        // deletePhoto also cancels their queued uploads (put+del collapses to a no-op).
        if (!committed && !isChangingConfigurations) {
            sessionImported.forEach { Store.deletePhoto(date, it) }
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    companion object { private const val REQ_CAM = 1; private const val REQ_GAL = 2 }
}
