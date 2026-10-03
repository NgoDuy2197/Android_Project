package com.dsoft.voicenote

import android.content.Context
import android.content.SharedPreferences

/** All user-configurable settings, persisted in SharedPreferences. */
class Prefs(ctx: Context) {
    private val sp: SharedPreferences =
        ctx.applicationContext.getSharedPreferences("voicenote", Context.MODE_PRIVATE)

    var autostart: Boolean
        get() = sp.getBoolean(K_AUTOSTART, false)
        set(v) = sp.edit().putBoolean(K_AUTOSTART, v).apply()

    /** Last state the user asked for; used to resume after crash / app update. */
    var wantRunning: Boolean
        get() = sp.getBoolean(K_WANT_RUNNING, false)
        set(v) = sp.edit().putBoolean(K_WANT_RUNNING, v).apply()

    /** "vi" or "en". */
    var language: String
        get() = sp.getString(K_LANGUAGE, null)?.takeIf { it in LANGS } ?: "vi"
        set(v) = sp.edit().putString(K_LANGUAGE, v.takeIf { it in LANGS } ?: "vi").apply()

    /** "google" = phone's SpeechRecognizer (online, accurate); "vosk" = offline. */
    var engine: String
        get() = sp.getString(K_ENGINE, null)?.takeIf { it == "google" || it == "vosk" } ?: "google"
        set(v) = sp.edit().putString(K_ENGINE, v).apply()

    /** "session" = one file per recording session, "day" = one file per day. */
    var fileMode: String
        get() = sp.getString(K_FILE_MODE, null)?.takeIf { it == "session" || it == "day" } ?: "session"
        set(v) = sp.edit().putString(K_FILE_MODE, v).apply()

    fun modelUrl(lang: String = language): String =
        sp.getString(K_MODEL_URL + lang, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_MODEL_URLS.getValue(lang)

    fun setModelUrl(lang: String, url: String) = sp.edit().putString(K_MODEL_URL + lang, url.trim()).apply()

    var timeFormat: String
        get() = sp.getString(K_TIME_FORMAT, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_TIME_FORMAT
        set(v) = sp.edit().putString(K_TIME_FORMAT, v.trim()).apply()

    /** Recognizer results closer than this are merged into one entry; 0 = never merge. */
    var pauseMs: Long
        get() = sp.getLong(K_PAUSE_MS, DEFAULT_PAUSE_MS)
        set(v) = sp.edit().putLong(K_PAUSE_MS, v.coerceIn(0, 60_000)).apply()

    /** Utterances whose mean word confidence is lower are treated as noise. */
    var minConfidence: Float
        get() = sp.getFloat(K_MIN_CONF, DEFAULT_MIN_CONF)
        set(v) = sp.edit().putFloat(K_MIN_CONF, v.coerceIn(0f, 1f)).apply()

    companion object {
        val LANGS = mapOf("vi" to "Tiếng Việt", "en" to "English")
        val DEFAULT_MODEL_URLS = mapOf(
            "vi" to "https://alphacephei.com/vosk/models/vosk-model-vn-0.4.zip",
            "en" to "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip",
        )
        const val DEFAULT_TIME_FORMAT = "HH:mm:ss"
        const val DEFAULT_PAUSE_MS = 1200L
        const val DEFAULT_MIN_CONF = 0.6f

        private const val K_AUTOSTART = "autostart"
        private const val K_WANT_RUNNING = "want_running"
        private const val K_LANGUAGE = "language"
        private const val K_ENGINE = "engine"
        private const val K_FILE_MODE = "file_mode"
        private const val K_MODEL_URL = "model_url_"
        private const val K_TIME_FORMAT = "time_format"
        private const val K_PAUSE_MS = "pause_ms"
        private const val K_MIN_CONF = "min_confidence"
    }
}
