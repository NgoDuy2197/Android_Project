package com.dsoft.noti_forward

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.util.Log
import java.util.Locale

/**
 * Native Text-To-Speech wrapper so notifications can be read aloud even when the
 * Flutter UI isn't running. A single engine instance is created lazily on first
 * use and kept alive for the process; utterances queue up behind each other.
 *
 * The language/voice come from config: if the user picked one it is applied
 * exactly, which is what stops the engine reading Vietnamese text with whatever
 * random default voice it had (e.g. a Chinese one) when no voice was chosen.
 */
object Speaker {

    private const val TAG = "Speaker"

    private var tts: TextToSpeech? = null
    private var ready = false

    // Last requested voice settings, applied on init and before each utterance.
    private var rate = 0.5f
    private var lang = ""
    private var voiceName = ""
    private var voiceLocale = ""

    private val pending = ArrayList<String>()

    @Synchronized
    fun speak(
        context: Context,
        text: String,
        rate: Float,
        lang: String,
        voiceName: String,
        voiceLocale: String,
    ) {
        if (text.isBlank()) return
        this.rate = rate.coerceIn(0.2f, 1.0f)
        this.lang = lang
        this.voiceName = voiceName
        this.voiceLocale = voiceLocale

        val engine = tts
        if (engine != null && ready) {
            applyVoice(engine)
            engine.speak(text, TextToSpeech.QUEUE_ADD, null, utteranceId(text))
            return
        }
        pending.add(text)
        if (engine == null) {
            tts = TextToSpeech(context.applicationContext) { status -> onInit(status) }
        }
    }

    /** Stop any current/queued speech (used before disabling). */
    @Synchronized
    fun stop() {
        try {
            tts?.stop()
        } catch (_: Exception) {
        }
        pending.clear()
    }

    @Synchronized
    private fun onInit(status: Int) {
        val engine = tts ?: return
        if (status != TextToSpeech.SUCCESS) {
            Log.w(TAG, "Khởi tạo TTS thất bại (status=$status)")
            pending.clear()
            return
        }
        ready = true
        applyVoice(engine)
        for (t in pending) {
            engine.speak(t, TextToSpeech.QUEUE_ADD, null, utteranceId(t))
        }
        pending.clear()
    }

    private fun applyVoice(engine: TextToSpeech) {
        try {
            engine.setSpeechRate(rate)
        } catch (_: Exception) {
        }
        // 1) An exact voice wins if it still exists on the device.
        if (voiceName.isNotEmpty()) {
            val v = findVoice(engine, voiceName)
            if (v != null) {
                try {
                    engine.voice = v
                    return
                } catch (_: Exception) {
                }
            }
        }
        // 2) Otherwise a chosen language.
        if (lang.isNotEmpty()) {
            try {
                val loc = Locale.forLanguageTag(lang)
                val res = engine.setLanguage(loc)
                if (res == TextToSpeech.LANG_MISSING_DATA ||
                    res == TextToSpeech.LANG_NOT_SUPPORTED
                ) {
                    Log.w(TAG, "Ngôn ngữ '$lang' chưa có trên máy (res=$res)")
                }
                return
            } catch (_: Exception) {
            }
        }
        // 3) Fall back to Vietnamese, then the device default — never a silent
        //    wrong language.
        try {
            val vi = engine.setLanguage(Locale("vi", "VN"))
            if (vi == TextToSpeech.LANG_MISSING_DATA ||
                vi == TextToSpeech.LANG_NOT_SUPPORTED
            ) {
                engine.setLanguage(Locale.getDefault())
            }
        } catch (_: Exception) {
        }
    }

    private fun findVoice(engine: TextToSpeech, name: String): Voice? {
        return try {
            engine.voices?.firstOrNull { it.name == name }
        } catch (_: Exception) {
            null
        }
    }

    private fun utteranceId(text: String) = "${hashCode()}-${text.hashCode()}"
}
