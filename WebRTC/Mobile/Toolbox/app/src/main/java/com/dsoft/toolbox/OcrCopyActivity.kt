package com.dsoft.toolbox

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.FileProvider
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File

/**
 * Pick/take a picture, recognize the text on it, then let the user copy it — either by normal
 * text selection ("bôi") in the big box, or one-tap per line. Uses ML Kit on-device OCR.
 */
class OcrCopyActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var p: Palette
    private lateinit var results: LinearLayout
    private lateinit var status: TextView
    private lateinit var floatBtn: TextView
    private var cameraFile: File? = null
    private var script = "latin"   // latin | zh | ja

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        p = palette(); setupSystemBars(p)
        val col = page(p, "Chép chữ từ ảnh")

        col.addView(group(p,
            row(p, "📷 Chụp ảnh", chevron(p)) { takePhoto() },
            row(p, "🖼️ Chọn ảnh từ máy", chevron(p)) { pickImage() },
        ))

        col.addView(sectionHeader(p, "Nút nổi chụp màn hình"))
        floatBtn = bigButton("", p.teal, p.teal) { toggleFloat() }
        col.addView(floatBtn, vlp(-1, -2).apply { topMargin = dp(6); marginStart = dp(4); marginEnd = dp(4) })
        col.addView(footnote(p, "Bật để hiện nút tròn ⧉ nổi trên mọi màn hình. Chạm nút: chụp màn hình → nhận chữ → chọn phần cần chép. " +
            "Kéo để di chuyển, giữ lâu để tắt. (Dùng chung nút nổi với 'Dịch màn hình'.)"))

        col.addView(sectionHeader(p, "Ngôn ngữ chữ trên ảnh"))
        col.addView(Segmented(this, p, listOf("Latin (Việt/Anh)", "中文", "日本語"), 0) { i ->
            script = when (i) { 1 -> "zh"; 2 -> "ja"; else -> "latin" }
        }, vlp().apply { marginStart = dp(4); marginEnd = dp(4) })

        status = text("", 14f, p.secondary).apply { setPadding(dp(16), dp(10), dp(16), 0) }
        col.addView(status)
        results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(results, vlp())
        col.addView(footnote(p, "Nhận dạng chạy trên máy (ML Kit). Chọn đúng ngôn ngữ để nhận dạng chuẩn hơn. " +
            "Trong ô kết quả: bôi đen đoạn cần rồi copy, hoặc chạm 1 dòng để chép nhanh dòng đó."))
    }

    private fun takePhoto() {
        val f = File(cacheDir, "ocr_capture_${System.currentTimeMillis()}.jpg")
        cameraFile = f
        val uri = FileProvider.getUriForFile(this, "$packageName.files", f)
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            .putExtra(MediaStore.EXTRA_OUTPUT, uri)
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        try { startActivityForResult(intent, REQ_CAMERA) }
        catch (_: Exception) { toast("Máy không có ứng dụng camera") }
    }

    private fun pickImage() {
        try {
            startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE), REQ_PICK)
        } catch (_: Exception) { toast("Không mở được trình chọn ảnh") }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            REQ_CAMERA -> cameraFile?.let { runOcr(decodeFile(it)) }
            REQ_PICK -> data?.data?.let { runOcr(decodeUri(it)) }
            REQ_CAPTURE -> if (data != null) {
                ScreenTranslateService.start(this, resultCode, data, ScreenTranslateService.MODE_COPY)
                moveTaskToBack(true)
            }
        }
    }

    // ---- floating screen-capture (copy mode of the shared bubble) ----
    private fun toggleFloat() {
        if (ScreenTranslateService.running) { ScreenTranslateService.stop(this); return }
        if (!DimService.canOverlay(this)) {
            toast("Cần cho phép \"Hiển thị trên ứng dụng khác\"")
            startActivity(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:$packageName")))
            return
        }
        val mpm = getSystemService(android.media.projection.MediaProjectionManager::class.java)
        try { startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE) }
        catch (_: Exception) { toast("Máy không hỗ trợ chụp màn hình") }
    }

    private fun refreshFloat() {
        if (::floatBtn.isInitialized) floatBtn.text = if (ScreenTranslateService.running) "Tắt nút nổi" else "Bật nút nổi chụp để chép"
    }

    override fun onResume() {
        super.onResume()
        ScreenTranslateService.listener = { runOnUiThread { refreshFloat() } }
        refreshFloat()
    }

    override fun onPause() {
        super.onPause()
        ScreenTranslateService.listener = null
    }

    private fun runOcr(bmp: Bitmap?) {
        if (bmp == null) { toast("Không đọc được ảnh"); return }
        results.removeAllViews()
        status.text = "Đang nhận dạng chữ…"
        val options = when (script) {
            "zh" -> ChineseTextRecognizerOptions.Builder().build()
            "ja" -> JapaneseTextRecognizerOptions.Builder().build()
            else -> TextRecognizerOptions.DEFAULT_OPTIONS
        }
        val client = TextRecognition.getClient(options)
        client.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { t ->
                client.close()
                val sep = if (script == "zh" || script == "ja") "" else " "
                val lines = ArrayList<String>()
                for (b in t.textBlocks) for (l in b.lines) l.text.trim().takeIf { it.isNotEmpty() }?.let { lines.add(it) }
                val full = t.textBlocks.joinToString("\n\n") { b -> b.lines.joinToString(sep) { it.text } }.trim()
                showResults(full, lines)
            }
            .addOnFailureListener { e -> client.close(); status.text = "Lỗi nhận dạng: ${e.message}" }
    }

    private fun showResults(full: String, lines: List<String>) {
        results.removeAllViews()
        if (full.isEmpty()) { status.text = "Không tìm thấy chữ nào (thử đổi ngôn ngữ)."; return }
        status.text = "Xong — bôi đen để copy, hoặc chạm 1 dòng:"

        // selectable full text
        val box = text(full, 15f, p.text).apply {
            setTextIsSelectable(true)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        results.addView(card(p).apply { addView(box) }, vlp())

        results.addView(group(p,
            row(p, "Copy toàn bộ", chevron(p)) { copyText(full) },
        ))

        // per-line quick copy
        if (lines.isNotEmpty()) {
            results.addView(sectionHeader(p, "Chép nhanh từng dòng"))
            results.addView(group(p, *lines.map { line ->
                row(p, line, text("Copy", 13f, p.blue)) { copyText(line) }
            }.toTypedArray()))
        }
    }

    private fun decodeUri(uri: Uri): Bitmap? = try {
        contentResolver.openInputStream(uri)?.use { decodeStreamSampled(it.readBytes()) }
    } catch (_: Exception) { null }

    private fun decodeFile(f: File): Bitmap? = try {
        if (f.exists() && f.length() > 0) decodeStreamSampled(f.readBytes()) else null
    } catch (_: Exception) { null }

    /** Decode downsampled to <= ~2500px so OCR is accurate but memory stays sane. */
    private fun decodeStreamSampled(bytes: ByteArray): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        if (o.outWidth <= 0) return null
        var sample = 1
        while (maxOf(o.outWidth, o.outHeight) / sample > 2500) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    companion object {
        private const val REQ_CAMERA = 1
        private const val REQ_PICK = 2
        private const val REQ_CAPTURE = 3
    }
}
