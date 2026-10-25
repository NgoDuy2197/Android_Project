package com.dsoft.odiary

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import java.util.Locale

/** Shared Google TTS engine for reading diary entries, with configurable voice/rate/pitch. */
object Tts {
    private const val GOOGLE_ENGINE = "com.google.android.tts"
    private var engine: TextToSpeech? = null
    private var ready = false
    private val pending = ArrayList<(TextToSpeech) -> Unit>()

    private fun hasGoogleEngine(ctx: Context) = try {
        ctx.packageManager.getPackageInfo(GOOGLE_ENGINE, 0); true
    } catch (_: Exception) { false }

    fun ensure(ctx: Context, cb: (TextToSpeech) -> Unit) {
        val e = engine
        if (e != null && ready) { cb(e); return }
        pending.add(cb)
        if (e == null) {
            val listener = TextToSpeech.OnInitListener { status ->
                App.main.post {
                    ready = status == TextToSpeech.SUCCESS
                    val eng = engine
                    if (ready && eng != null) {
                        val list = ArrayList(pending); pending.clear()
                        list.forEach { runCatching { it(eng) } }
                    } else {
                        pending.clear()
                        toast("Thiết bị chưa có bộ đọc (cài \"Google Text-to-Speech\" trong CH Play).")
                        runCatching { eng?.shutdown() }
                        engine = null
                    }
                }
            }
            // Prefer the Google engine explicitly (like BookShelf) — the device default may lack data.
            engine = if (hasGoogleEngine(ctx)) TextToSpeech(ctx.applicationContext, listener, GOOGLE_ENGINE)
                     else TextToSpeech(ctx.applicationContext, listener)
        }
    }

    fun read(ctx: Context, text: String) {
        if (text.isBlank()) return
        ensure(ctx) { e ->
            applyPrefs(ctx, e)
            val r = e.speak(text, TextToSpeech.QUEUE_FLUSH, null, "diary")
            if (r != TextToSpeech.SUCCESS) toast("Không đọc được (thử chọn giọng trong Cài đặt).")
        }
    }

    fun stop() { runCatching { engine?.stop() } }

    fun applyPrefs(ctx: Context, e: TextToSpeech) {
        runCatching { e.setSpeechRate(Prefs.ttsRate(ctx) / 100f) }
        runCatching { e.setPitch(Prefs.ttsPitch(ctx) / 100f) }
        val vn = Prefs.ttsVoice(ctx)
        var set = false
        if (vn.isNotEmpty()) runCatching { e.voices?.firstOrNull { it.name == vn }?.let { set = e.setVoice(it) == TextToSpeech.SUCCESS } }
        if (!set) {
            // Prefer Vietnamese; fall back to the engine default if vi data is missing.
            val vi = runCatching { e.setLanguage(Locale("vi", "VN")) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
            if (vi == TextToSpeech.LANG_MISSING_DATA || vi == TextToSpeech.LANG_NOT_SUPPORTED)
                runCatching { e.setLanguage(Locale.getDefault()) }
        }
    }

    private fun toast(s: String) = runCatching { android.widget.Toast.makeText(App.instance, s, android.widget.Toast.LENGTH_LONG).show() }

    fun voices(ctx: Context, cb: (List<Voice>) -> Unit) {
        ensure(ctx) { e ->
            val list = try {
                e.voices?.filter { !it.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
                    ?.sortedWith(compareBy({ if (it.locale.language == "vi") 0 else 1 }, { it.locale.displayName }, { it.name }))
                    ?: emptyList()
            } catch (_: Exception) { emptyList() }
            cb(list)
        }
    }
}
