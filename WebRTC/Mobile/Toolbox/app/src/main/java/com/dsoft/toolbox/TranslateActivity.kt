package com.dsoft.toolbox

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/** Settings + on/off for the floating screen-translate bubble; can also translate a picked image. */
class TranslateActivity : Activity() {
    private lateinit var p: Palette
    private lateinit var prefs: Prefs
    private lateinit var toggle: TextView
    private lateinit var modelBox: LinearLayout
    private val modelStatus = HashMap<String, TextView>()
    private var downloading = HashSet<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        p = palette()
        prefs = Prefs(this)
        val col = page(p, "Dịch màn hình")

        toggle = bigButton("", p.teal, p.indigo) { onToggle() }
        col.addView(toggle)
        col.addView(footnote(p, "Bật để hiện nút tròn 文A nổi trên màn hình (kể cả khi đang chơi game). " +
            "Chạm nút: chụp màn hình → nhận dạng chữ → hiện ảnh toàn màn hình với bản dịch đè lên chữ gốc. " +
            "Giữ tay trên ảnh để xem bản gốc, bấm ✕ hoặc Back để quay lại. Kéo để di chuyển nút, giữ lâu để tắt.\n" +
            "Khi Android hỏi quyền ghi màn hình, chọn \"Toàn bộ màn hình\"."))

        // ---- languages ----
        col.addView(sectionHeader(p, "Ngôn ngữ"))
        col.addView(group(p,
            row(p, "Từ", seg(Langs.SOURCES, prefs.trSrc) { prefs.trSrc = it }),
            row(p, "Sang", seg(Langs.TARGETS, prefs.trDst) { prefs.trDst = it }),
        ))

        // ---- engine ----
        col.addView(sectionHeader(p, "Cách dịch"))
        val engines = listOf("offline", "online")
        col.addView(group(p,
            row(p, "Engine", Segmented(this, p, listOf("Offline", "Online"), engines.indexOf(prefs.trEngine).coerceAtLeast(0)) {
                prefs.trEngine = engines[it]
            }.apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT) }),
        ))
        col.addView(footnote(p, "Offline: Google ML Kit dịch ngay trên máy, không giới hạn, không cần mạng sau khi tải gói ngôn ngữ (~30 MB mỗi thứ tiếng). " +
            "Online: Google Dịch qua mạng (cổng web miễn phí, không chính thức, có thể bị giới hạn nếu dùng quá nhiều)."))

        // ---- offline models ----
        col.addView(sectionHeader(p, "Gói ngôn ngữ offline"))
        modelBox = card(p)
        for (code in Langs.TARGETS) {
            val st = text("…", 15f, p.secondary)
            modelStatus[code] = st
            if (modelBox.childCount > 0) modelBox.addView(separator(p))
            modelBox.addView(row(p, Langs.NAMES[code] ?: code, st) { onModel(code) })
        }
        col.addView(modelBox)
        col.addView(footnote(p, "Chạm để tải hoặc xóa. Lần dịch đầu tiên cũng tự tải gói còn thiếu."))

        // ---- image ----
        col.addView(sectionHeader(p, "Khác"))
        col.addView(group(p, row(p, "Dịch một ảnh có sẵn…", chevron(p)) { pickImage() }))
    }

    override fun onResume() {
        super.onResume()
        ScreenTranslateService.listener = { runOnUiThread { refresh() } }
        refresh()
        refreshModels()
    }

    override fun onPause() {
        super.onPause()
        ScreenTranslateService.listener = null
    }

    private fun seg(codes: List<String>, cur: String, onPick: (String) -> Unit) =
        Segmented(this, p, codes.map { Langs.SHORT[it] ?: it }, codes.indexOf(cur).coerceAtLeast(0)) { onPick(codes[it]) }.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

    private fun refresh() {
        toggle.text = if (ScreenTranslateService.running) "Tắt nút dịch nổi" else "Bật nút dịch nổi"
    }

    private fun refreshModels() {
        TranslatePipeline.downloadedModels { have ->
            for ((code, tv) in modelStatus) {
                val has = Langs.mlkit(code) in have
                tv.text = when {
                    code in downloading -> "Đang tải…"
                    has -> "Đã tải"
                    else -> "Tải về ›"
                }
                tv.setTextColor(if (has) p.green else p.blue)
            }
        }
    }

    private fun onModel(code: String) {
        if (code in downloading) return
        TranslatePipeline.downloadedModels { have ->
            if (Langs.mlkit(code) in have) {
                if (code == "en") return@downloadedModels toast("Gói tiếng Anh là gói gốc, luôn có sẵn")
                TranslatePipeline.delete(code) { refreshModels() }
            } else {
                downloading += code
                refreshModels()
                TranslatePipeline.download(code) { err ->
                    downloading -= code
                    if (err != null) toast("Tải lỗi: $err")
                    refreshModels()
                }
            }
        }
    }

    private fun onToggle() {
        if (ScreenTranslateService.running) {
            ScreenTranslateService.stop(this)
            return
        }
        if (!DimService.canOverlay(this)) {
            toast("Cần cho phép \"Hiển thị trên ứng dụng khác\"")
            startActivity(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:$packageName")))
            return
        }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE)
    }

    private fun pickImage() {
        try {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"), REQ_IMAGE)
        } catch (_: Exception) {
            toast("Không mở được trình chọn ảnh")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        when (requestCode) {
            REQ_CAPTURE -> {
                ScreenTranslateService.start(this, resultCode, data)
                moveTaskToBack(true) // go back to the game / app to translate
            }
            REQ_IMAGE -> data.data?.let { uri ->
                val bmp = runCatching { decode(uri) }.getOrNull()
                if (bmp == null) toast("Không đọc được ảnh") else showImage(bmp)
            }
        }
    }

    private fun decode(uri: android.net.Uri): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
        var sample = 1
        while (maxOf(o.outWidth, o.outHeight) / (sample * 2) >= 2400) sample *= 2
        return contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            })
        }
    }

    private fun showImage(bmp: Bitmap) {
        val dlg = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val rv = ResultView(this, bmp) { dlg.dismiss() }
        dlg.setContentView(rv)
        dlg.show()
        TranslatePipeline.run(this, bmp, object : TranslatePipeline.Listener {
            override fun onStatus(s: String) = rv.setStatus(s)
            override fun onBlocks(blocks: List<Block>) = rv.setBlocks(blocks)
            override fun onDone(error: String?) = rv.setStatus(error ?: "Giữ để xem bản gốc")
        })
    }

    companion object {
        private const val REQ_CAPTURE = 1
        private const val REQ_IMAGE = 2
    }
}
