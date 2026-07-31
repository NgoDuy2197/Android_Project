package com.dsoft.voice_ai

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log

/**
 * Spins up a throwaway TextToSpeech engine just long enough to report which
 * languages and voices are actually installed, then shuts it down. When an
 * [engine] package is given it probes THAT engine (e.g. Google's), which is how
 * we surface Vietnamese on China-ROM phones whose *default* TTS engine is a
 * Chinese one lacking `vi`. Also lists the installed engines.
 */
object TtsProbe {

    private const val TAG = "TtsProbe"

    data class Result(
        val languages: List<String>,
        val voices: List<Map<String, String>>,
        val engines: List<Map<String, String>>,
    )

    /** Callback is invoked on the main thread once the engine has initialised. */
    fun probe(context: Context, engine: String?, onResult: (Result) -> Unit) {
        var tts: TextToSpeech? = null
        val onInit = TextToSpeech.OnInitListener { status ->
            val langs = ArrayList<String>()
            val voices = ArrayList<Map<String, String>>()
            val engines = ArrayList<Map<String, String>>()
            try {
                if (status == TextToSpeech.SUCCESS) {
                    val e = tts
                    e?.availableLanguages?.forEach { loc -> langs.add(loc.toLanguageTag()) }
                    e?.voices?.forEach { v ->
                        voices.add(mapOf("name" to v.name, "locale" to v.locale.toLanguageTag()))
                    }
                    e?.engines?.forEach { en ->
                        engines.add(mapOf("name" to en.name, "label" to en.label))
                    }
                }
            } catch (ex: Exception) {
                Log.w(TAG, "probe lỗi: ${ex.message}")
            }
            langs.sort()
            voices.sortBy { it["locale"] }
            onResult(Result(langs, voices, engines))
            try { tts?.shutdown() } catch (_: Exception) {}
        }
        tts = if (!engine.isNullOrEmpty()) {
            try {
                TextToSpeech(context.applicationContext, onInit, engine)
            } catch (_: Exception) {
                TextToSpeech(context.applicationContext, onInit)
            }
        } else {
            TextToSpeech(context.applicationContext, onInit)
        }
    }
}
