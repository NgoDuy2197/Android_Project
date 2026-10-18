package com.dsoft.voicenote

import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

/**
 * Text translation via Google's free web endpoint (no library, no key).
 * It is the unofficial `client=gtx` endpoint, so it is rate-limited; fine for
 * a line or a transcript, not for bulk abuse. `src = "auto"` lets Google detect
 * the language and the detected code is returned to the caller.
 */
object Translator {
    /** Languages offered as translation targets / explicit sources. */
    val LANGS = linkedMapOf("vi" to "Tiếng Việt", "en" to "English", "ja" to "日本語", "zh" to "中文")
    val SHORT = mapOf("auto" to "Tự động", "vi" to "Việt", "en" to "Anh", "ja" to "Nhật", "zh" to "Trung")

    class Result(val ok: Boolean, val text: String, val detected: String?)

    private val io = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    /** Translates on a background thread; [cb] runs on the main thread. */
    fun translate(text: String, src: String, target: String, cb: (Result) -> Unit) {
        io.execute {
            val r = runCatching { gtx(text, src, target) }
                .getOrElse { Result(false, it.message ?: "lỗi", null) }
            main.post { cb(r) }
        }
    }

    /** Blocking call for bulk translation (used off the main thread). */
    fun gtx(text: String, src: String, target: String): Result {
        if (text.isBlank()) return Result(true, "", null)
        val gl = mapOf("zh" to "zh-CN")
        val sl = if (src == "auto") "auto" else (gl[src] ?: src)
        val tl = gl[target] ?: target
        val url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=$sl&tl=$tl&dt=t&q=" +
            URLEncoder.encode(text, "UTF-8")
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 20_000
        c.setRequestProperty("User-Agent", "Mozilla/5.0")
        try {
            if (c.responseCode != 200) return Result(false, "HTTP ${c.responseCode}", null)
            val root = JSONArray(c.inputStream.bufferedReader().readText())
            val parts = root.getJSONArray(0)
            val sb = StringBuilder()
            for (i in 0 until parts.length()) sb.append(parts.getJSONArray(i).optString(0))
            val detected = root.optString(2, "").ifBlank { null }
            return Result(true, sb.toString(), detected)
        } finally {
            c.disconnect()
        }
    }
}
