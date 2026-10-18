package com.dsoft.toolbox

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

object Langs {
    val SOURCES = listOf("en", "zh", "ja", "vi")
    val TARGETS = listOf("vi", "en", "zh", "ja")
    val NAMES = mapOf("vi" to "Tiếng Việt", "en" to "English", "zh" to "中文", "ja" to "日本語")
    val SHORT = mapOf("vi" to "Việt", "en" to "Anh", "zh" to "Trung", "ja" to "Nhật")

    fun mlkit(code: String): String = when (code) {
        "vi" -> TranslateLanguage.VIETNAMESE
        "zh" -> TranslateLanguage.CHINESE
        "ja" -> TranslateLanguage.JAPANESE
        else -> TranslateLanguage.ENGLISH
    }

    fun cjk(code: String) = code == "zh" || code == "ja"
}

/** One recognized text block: where it is on the screenshot, the original and the translation. */
class Block(val box: Rect, val text: String) {
    @Volatile var translated: String? = null
}

/** Screenshot -> OCR -> translation, reported to [Listener] on the main thread. */
object TranslatePipeline {
    interface Listener {
        fun onStatus(s: String)
        fun onBlocks(blocks: List<Block>)
        fun onDone(error: String?)
    }

    private val main = Handler(Looper.getMainLooper())
    private val net = Executors.newFixedThreadPool(4)

    fun run(ctx: Context, bmp: Bitmap, l: Listener) {
        val p = Prefs(ctx)
        val src = p.trSrc
        val dst = p.trDst
        l.onStatus("Đang nhận dạng chữ…")
        val options = when (src) {
            "zh" -> ChineseTextRecognizerOptions.Builder().build()
            "ja" -> JapaneseTextRecognizerOptions.Builder().build()
            else -> TextRecognizerOptions.DEFAULT_OPTIONS
        }
        val client = TextRecognition.getClient(options)
        client.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { t ->
                client.close()
                val sep = if (Langs.cjk(src)) "" else " "
                val blocks = t.textBlocks.mapNotNull { b ->
                    val box = b.boundingBox ?: return@mapNotNull null
                    val text = b.lines.joinToString(sep) { it.text }.trim()
                    if (text.isEmpty()) null else Block(box, text)
                }
                if (blocks.isEmpty()) {
                    l.onDone("Không tìm thấy chữ nào (kiểm tra ngôn ngữ gốc)")
                    return@addOnSuccessListener
                }
                l.onBlocks(blocks)
                if (src == dst) {
                    blocks.forEach { it.translated = it.text }
                    l.onDone(null)
                    return@addOnSuccessListener
                }
                l.onStatus(if (p.trEngine == "online") "Đang dịch (online)…" else "Đang dịch (offline)…")
                if (p.trEngine == "online") online(blocks, src, dst, l) else offline(blocks, src, dst, l)
            }
            .addOnFailureListener { e ->
                client.close()
                l.onDone("Lỗi nhận dạng chữ: ${e.message}")
            }
    }

    private fun offline(blocks: List<Block>, src: String, dst: String, l: Listener) {
        val tr = Translation.getClient(
            TranslatorOptions.Builder().setSourceLanguage(Langs.mlkit(src)).setTargetLanguage(Langs.mlkit(dst)).build()
        )
        tr.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener {
                val tasks = blocks.map { b -> tr.translate(b.text).addOnSuccessListener { b.translated = it } }
                Tasks.whenAllComplete(tasks).addOnCompleteListener {
                    tr.close()
                    val failed = tasks.count { !it.isSuccessful }
                    l.onBlocks(blocks)
                    l.onDone(if (failed == blocks.size) "Dịch thất bại" else null)
                }
            }
            .addOnFailureListener { e ->
                tr.close()
                l.onDone("Chưa tải được gói ngôn ngữ offline: ${e.message}")
            }
    }

    private fun online(blocks: List<Block>, src: String, dst: String, l: Listener) {
        net.execute {
            var error: String? = null
            val futures = blocks.map { b ->
                net.submit {
                    try {
                        b.translated = gtx(b.text, src, dst)
                    } catch (e: Exception) {
                        error = e.message
                    }
                }
            }
            futures.forEach { runCatching { it.get() } }
            main.post {
                l.onBlocks(blocks)
                l.onDone(if (blocks.all { it.translated == null }) "Dịch online lỗi: $error" else null)
            }
        }
    }

    /** Google Translate web endpoint (free, unofficial, rate-limited). */
    fun gtx(text: String, src: String, dst: String): String {
        val gl = mapOf("zh" to "zh-CN")
        val url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=${gl[src] ?: src}" +
            "&tl=${gl[dst] ?: dst}&dt=t&q=" + URLEncoder.encode(text, "UTF-8")
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 15_000
        c.setRequestProperty("User-Agent", "Mozilla/5.0")
        try {
            if (c.responseCode != 200) throw java.io.IOException("HTTP ${c.responseCode}")
            val body = c.inputStream.bufferedReader().readText()
            val parts = JSONArray(body).getJSONArray(0)
            val sb = StringBuilder()
            for (i in 0 until parts.length()) sb.append(parts.getJSONArray(i).optString(0))
            return sb.toString()
        } finally {
            c.disconnect()
        }
    }

    // ---- offline model management ----

    fun downloadedModels(cb: (Set<String>) -> Unit) {
        RemoteModelManager.getInstance().getDownloadedModels(TranslateRemoteModel::class.java)
            .addOnSuccessListener { models -> cb(models.map { it.language }.toSet()) }
            .addOnFailureListener { cb(emptySet()) }
    }

    fun download(code: String, cb: (String?) -> Unit) {
        RemoteModelManager.getInstance()
            .download(TranslateRemoteModel.Builder(Langs.mlkit(code)).build(), DownloadConditions.Builder().build())
            .addOnSuccessListener { cb(null) }
            .addOnFailureListener { cb(it.message ?: "lỗi") }
    }

    fun delete(code: String, cb: () -> Unit) {
        RemoteModelManager.getInstance().deleteDownloadedModel(TranslateRemoteModel.Builder(Langs.mlkit(code)).build())
            .addOnCompleteListener { cb() }
    }
}
