package com.dsoft.toolbox

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Hold-to-talk speech → text (device SpeechRecognizer). Pick the spoken language; optionally pick an
 * output language and the recognized text is auto-translated into it.
 */
class SpeechActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var p: Palette
    private lateinit var recBtn: TextView
    private lateinit var heard: TextView
    private lateinit var translated: TextView
    private var recognizer: SpeechRecognizer? = null
    private var listening = false

    private val langs = listOf("vi", "en", "zh", "ja")
    private val locales = mapOf("vi" to "vi-VN", "en" to "en-US", "zh" to "zh-CN", "ja" to "ja-JP")
    private var inLang = "vi"
    private var outLang = ""   // "" = no translation

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        p = palette(); setupSystemBars(p)
        val col = page(p, "Giọng nói → chữ")

        col.addView(sectionHeader(p, "Ngôn ngữ nói (input)"))
        col.addView(Segmented(this, p, langs.map { Langs.NAMES[it] ?: it }, 0) { inLang = langs[it] },
            vlp().apply { marginStart = dp(4); marginEnd = dp(4) })

        col.addView(sectionHeader(p, "Dịch sang (output, tùy chọn)"))
        val outOpts = listOf("") + langs
        col.addView(Segmented(this, p, listOf("Không dịch") + langs.map { Langs.NAMES[it] ?: it }, 0) { outLang = outOpts[it] },
            vlp().apply { marginStart = dp(4); marginEnd = dp(4) })

        recBtn = bigButton("🎤 Bắt đầu nói", p.pink, p.pink) { toggle() }
        col.addView(recBtn, vlp(-1, -2).apply { topMargin = dp(16); marginStart = dp(4); marginEnd = dp(4); height = dp(64) })

        col.addView(sectionHeader(p, "Nghe được"))
        heard = text("…", 16f, p.text).apply { setTextIsSelectable(true); setPadding(dp(14), dp(12), dp(14), dp(12)) }
        col.addView(card(p).apply { addView(heard) }, vlp())
        col.addView(group(p, row(p, "Copy", chevron(p)) { copyText(heard.text.toString()) }))

        col.addView(sectionHeader(p, "Bản dịch"))
        translated = text("—", 16f, p.text).apply { setTextIsSelectable(true); setPadding(dp(14), dp(12), dp(14), dp(12)) }
        col.addView(card(p).apply { addView(translated) }, vlp())
        col.addView(group(p, row(p, "Copy bản dịch", chevron(p)) { copyText(translated.text.toString()) }))

        col.addView(footnote(p, "Nhận dạng giọng nói dùng dịch vụ của máy (thường cần mạng). " +
            "Nếu chọn ngôn ngữ dịch, bản dịch dùng dịch vụ online."))

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
    }

    private fun toggle() {
        if (listening) { stopListening(); return }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1); return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { toast("Máy không hỗ trợ nhận dạng giọng nói"); return }
        startListening()
    }

    private fun startListening() {
        val r = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { setState(true, "Đang nghe… (chạm để dừng)") }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partial: Bundle?) {
                partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { heard.text = it }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onError(error: Int) { setState(false, "🎤 Bắt đầu nói"); if (error != SpeechRecognizer.ERROR_NO_MATCH) toast("Lỗi nhận dạng ($error)") }
            override fun onResults(results: Bundle?) {
                val txt = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                if (txt.isNotEmpty()) { heard.text = txt; maybeTranslate(txt) }
                setState(false, "🎤 Bắt đầu nói")
            }
        })
        val intent = android.content.Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, locales[inLang] ?: "vi-VN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        try { r.startListening(intent) } catch (e: Exception) { toast("Không bật được micro: ${e.message}"); setState(false, "🎤 Bắt đầu nói") }
    }

    private fun stopListening() {
        runCatching { recognizer?.stopListening() }
        runCatching { recognizer?.destroy() }
        recognizer = null
        setState(false, "🎤 Bắt đầu nói")
    }

    private fun setState(on: Boolean, label: String) {
        listening = on
        recBtn.text = if (on) "⏹ Dừng" else label
    }

    private fun maybeTranslate(text: String) {
        val dst = outLang
        if (dst.isEmpty() || dst == inLang) { translated.text = if (dst == inLang && dst.isNotEmpty()) text else "—"; return }
        translated.text = "đang dịch…"
        Thread {
            val out = try { TranslatePipeline.gtx(text, inLang, dst) } catch (e: Exception) { "lỗi dịch: ${e.message}" }
            main.post { translated.text = out }
        }.start()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) toast("Cần quyền micro để nhận dạng giọng nói")
    }

    override fun onPause() { super.onPause(); stopListening() }
}
