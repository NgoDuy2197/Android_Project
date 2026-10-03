package com.dsoft.voicenote

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Continuous dictation on top of the phone's own SpeechRecognizer (usually Google,
 * cloud-backed: fast and accurate for Vietnamese). Same approach as voice_ai:
 * one recognition session per utterance, restarted right after each result.
 * All calls must happen on the main thread; errors never stop the loop, they
 * only back off.
 */
class SystemStt(private val ctx: Context, private val locale: String, private val cb: Callback) {
    interface Callback {
        fun onStatus(s: String)
        fun onSpeechStart()
        fun onPartial(text: String)
        fun onFinal(text: String)
        fun onLevel(level: Float)
    }

    private val main = Handler(Looper.getMainLooper())
    private var rec: SpeechRecognizer? = null
    private var running = false
    private var errors = 0
    private val restart = Runnable { listenOnce() }

    fun start() = main.post {
        running = true
        errors = 0
        listenOnce()
    }

    fun stop() = main.post {
        running = false
        main.removeCallbacks(restart)
        destroy()
    }

    /** System default first (what every working app uses), then any installed engine. */
    private fun pickRecognizer(): ComponentName? {
        if (SpeechRecognizer.isRecognitionAvailable(ctx)) return null
        val byPkg = HashMap<String, ComponentName>()
        runCatching {
            for (ri in ctx.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)) {
                val si = ri.serviceInfo ?: continue
                byPkg[si.packageName] = ComponentName(si.packageName, si.name)
            }
        }
        for (p in listOf("com.google.android.googlequicksearchbox", "com.google.android.tts")) {
            byPkg[p]?.let { return it }
        }
        return byPkg.values.firstOrNull()
    }

    private fun ensure(): SpeechRecognizer? {
        rec?.let { return it }
        val comp = pickRecognizer()
        rec = runCatching {
            if (comp != null) SpeechRecognizer.createSpeechRecognizer(ctx, comp)
            else SpeechRecognizer.createSpeechRecognizer(ctx)
        }.getOrNull()
        rec?.setRecognitionListener(listener)
        return rec
    }

    private fun destroy() {
        runCatching { rec?.cancel() }
        runCatching { rec?.destroy() }
        rec = null
    }

    private fun listenOnce() {
        if (!running) return
        val r = ensure()
        if (r == null) {
            cb.onStatus("Máy không có bộ nhận dạng giọng nói (cài app Google)")
            schedule(15_000)
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, locale)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false) // cloud model is best for vi
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
            putExtra("android.speech.extra.DICTATION_MODE", true)
        }
        try {
            r.startListening(intent)
        } catch (e: Exception) {
            destroy()
            schedule(backoff())
        }
    }

    private fun schedule(ms: Long) {
        main.removeCallbacks(restart)
        if (running) main.postDelayed(restart, ms)
    }

    private fun backoff(): Long {
        errors = (errors + 1).coerceAtMost(6)
        return (400L shl errors).coerceAtMost(15_000) // 0.8 s … 15 s
    }

    private fun first(b: Bundle?) =
        b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            cb.onStatus("Đang nghe (${Prefs.LANGS[locale.take(2)] ?: locale}, Google)…")
        }

        override fun onBeginningOfSpeech() = cb.onSpeechStart()

        // rmsdB is roughly -2 (silence) … 10 (loud).
        override fun onRmsChanged(rmsdB: Float) = cb.onLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() = cb.onLevel(0f)

        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            first(partialResults).let { if (it.isNotBlank()) cb.onPartial(it) }
        }

        override fun onResults(results: Bundle?) {
            errors = 0
            cb.onPartial("")
            first(results).let { if (it.isNotBlank()) cb.onFinal(it) }
            schedule(150)
        }

        override fun onError(error: Int) {
            cb.onPartial("")
            cb.onLevel(0f)
            when (error) {
                // Silence / nothing understood: normal while nobody talks.
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    errors = 0
                    schedule(150)
                }
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    cb.onStatus("Bộ nhận dạng thiếu quyền micro, thử lại sau 30s")
                    destroy()
                    schedule(30_000)
                }
                12, 13 -> { // LANGUAGE_NOT_SUPPORTED / LANGUAGE_UNAVAILABLE
                    cb.onStatus("Bộ nhận dạng chưa hỗ trợ $locale, thử lại sau 30s")
                    schedule(30_000)
                }
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                SpeechRecognizer.ERROR_SERVER, 11 /* SERVER_DISCONNECTED */ -> {
                    val ms = backoff()
                    cb.onStatus("Mất mạng, thử lại sau ${ms / 1000.0}s")
                    schedule(ms)
                }
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_CLIENT -> {
                    destroy()
                    schedule(backoff())
                }
                else -> schedule(backoff())
            }
        }
    }
}
