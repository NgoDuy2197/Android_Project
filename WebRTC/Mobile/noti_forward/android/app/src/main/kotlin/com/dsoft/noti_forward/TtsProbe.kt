package com.dsoft.noti_forward

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log

/**
 * Spins up a throwaway TextToSpeech engine just long enough to report which
 * languages and voices are actually installed on the device, then shuts it
 * down. `availableLanguages` is the truthful set (installed voice data), which
 * is what lets the UI show the real usable list instead of every language the
 * engine merely claims to support.
 */
object TtsProbe {

    private const val TAG = "TtsProbe"

    data class Result(
        val languages: List<String>,
        val voices: List<Map<String, String>>,
    )

    /** Callback is invoked on the main thread once the engine has initialised. */
    fun probe(context: Context, onResult: (Result) -> Unit) {
        var engine: TextToSpeech? = null
        engine = TextToSpeech(context.applicationContext) { status ->
            val langs = ArrayList<String>()
            val voices = ArrayList<Map<String, String>>()
            try {
                if (status == TextToSpeech.SUCCESS) {
                    val e = engine
                    e?.availableLanguages?.forEach { loc ->
                        langs.add(loc.toLanguageTag())
                    }
                    e?.voices?.forEach { v ->
                        voices.add(
                            mapOf(
                                "name" to v.name,
                                "locale" to v.locale.toLanguageTag(),
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "probe lỗi: ${e.message}")
            }
            langs.sort()
            voices.sortBy { it["locale"] }
            onResult(Result(langs, voices))
            try {
                engine?.shutdown()
            } catch (_: Exception) {
            }
        }
    }
}
