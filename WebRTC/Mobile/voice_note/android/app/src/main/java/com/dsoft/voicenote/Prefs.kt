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

    /** Recording language: "auto" (let the recognizer detect) or "vi"/"en"/"ja"/"zh". */
    var language: String
        get() = sp.getString(K_LANGUAGE, null)?.takeIf { it == "auto" || it in LANGS } ?: "vi"
        set(v) = sp.edit().putString(K_LANGUAGE, v.takeIf { it == "auto" || it in LANGS } ?: "vi").apply()

    /** Target language for the translate buttons (default Vietnamese). */
    var targetLang: String
        get() = sp.getString(K_TARGET, null)?.takeIf { it in LANGS } ?: "vi"
        set(v) = sp.edit().putString(K_TARGET, v.takeIf { it in LANGS } ?: "vi").apply()

    /** "session" = one file per recording session, "day" = one file per day. */
    var fileMode: String
        get() = sp.getString(K_FILE_MODE, null)?.takeIf { it == "session" || it == "day" } ?: "session"
        set(v) = sp.edit().putString(K_FILE_MODE, v).apply()

    var timeFormat: String
        get() = sp.getString(K_TIME_FORMAT, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_TIME_FORMAT
        set(v) = sp.edit().putString(K_TIME_FORMAT, v.trim()).apply()

    /**
     * Default "listening wait": after the sound stays below [silenceLevel] this
     * long, the current line is closed (one Enter). Sessions can override it.
     */
    var waitMs: Long
        get() = sp.getLong(K_WAIT_MS, DEFAULT_WAIT_MS)
        set(v) = sp.edit().putLong(K_WAIT_MS, v.coerceIn(MIN_WAIT_MS, MAX_WAIT_MS)).apply()

    /** Default silence level (0..1 of the level meter); quieter = silence. */
    var silenceLevel: Float
        get() = sp.getFloat(K_SILENCE, DEFAULT_SILENCE)
        set(v) = sp.edit().putFloat(K_SILENCE, v.coerceIn(0.02f, 0.9f)).apply()

    companion object {
        val LANGS = linkedMapOf("vi" to "Tiếng Việt", "en" to "English", "ja" to "日本語", "zh" to "中文")
        /** Google recognizer locales. */
        val LOCALES = mapOf("vi" to "vi-VN", "en" to "en-US", "ja" to "ja-JP", "zh" to "zh-CN")
        const val DEFAULT_TIME_FORMAT = "HH:mm:ss"
        const val DEFAULT_WAIT_MS = 1000L
        const val MIN_WAIT_MS = 300L
        const val MAX_WAIT_MS = 10_000L
        const val DEFAULT_SILENCE = 0.25f

        private const val K_AUTOSTART = "autostart"
        private const val K_WANT_RUNNING = "want_running"
        private const val K_LANGUAGE = "language"
        private const val K_TARGET = "target_lang"
        private const val K_FILE_MODE = "file_mode"
        private const val K_TIME_FORMAT = "time_format"
        private const val K_WAIT_MS = "wait_ms"
        private const val K_SILENCE = "silence_level"
    }
}
