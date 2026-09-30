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

    var modelUrl: String
        get() = sp.getString(K_MODEL_URL, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_MODEL_URL
        set(v) = sp.edit().putString(K_MODEL_URL, v.trim()).apply()

    var timeFormat: String
        get() = sp.getString(K_TIME_FORMAT, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_TIME_FORMAT
        set(v) = sp.edit().putString(K_TIME_FORMAT, v.trim()).apply()

    /** Recognizer results closer than this are merged into one entry; 0 = never merge. */
    var pauseMs: Long
        get() = sp.getLong(K_PAUSE_MS, DEFAULT_PAUSE_MS)
        set(v) = sp.edit().putLong(K_PAUSE_MS, v.coerceIn(0, 60_000)).apply()

    companion object {
        const val DEFAULT_MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-vn-0.4.zip"
        const val DEFAULT_TIME_FORMAT = "HH:mm:ss"
        const val DEFAULT_PAUSE_MS = 1200L

        private const val K_AUTOSTART = "autostart"
        private const val K_WANT_RUNNING = "want_running"
        private const val K_MODEL_URL = "model_url"
        private const val K_TIME_FORMAT = "time_format"
        private const val K_PAUSE_MS = "pause_ms"
    }
}
