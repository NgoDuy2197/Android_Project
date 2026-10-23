package com.dsoft.autotap

import android.content.Context
import android.content.SharedPreferences

/** All user settings, persisted in SharedPreferences. */
class Prefs(ctx: Context) {
    private val sp: SharedPreferences =
        ctx.applicationContext.getSharedPreferences("autotap", Context.MODE_PRIVATE)

    // ---- API server ----
    var apiPort: Int
        get() = sp.getInt("api_port", DEFAULT_PORT)
        set(v) = sp.edit().putInt("api_port", v.coerceIn(1024, 65535)).apply()

    /** Empty user = no authentication. */
    var apiUser: String
        get() = sp.getString("api_user", "") ?: ""
        set(v) = sp.edit().putString("api_user", v.trim()).apply()

    var apiPass: String
        get() = sp.getString("api_pass", "") ?: ""
        set(v) = sp.edit().putString("api_pass", v).apply()

    // ---- Playback ----
    /** Default gap inserted between steps when a step has no explicit wait. */
    var defaultDelayMs: Long
        get() = sp.getLong("default_delay", 300L)
        set(v) = sp.edit().putLong("default_delay", v.coerceIn(0, 60_000)).apply()

    /** true = keep running the scenario when a step fails; false = stop. */
    var continueOnFail: Boolean
        get() = sp.getBoolean("continue_on_fail", true)
        set(v) = sp.edit().putBoolean("continue_on_fail", v).apply()

    /** Countdown (ms) before the first step runs, so the user can switch to the target app. */
    var startDelayMs: Long
        get() = sp.getLong("start_delay", 3000L)
        set(v) = sp.edit().putLong("start_delay", v.coerceIn(0, 60_000)).apply()

    /** Dim of the record overlay, 0..80 (%). Higher = darker, easier to aim. */
    var overlayDim: Int
        get() = sp.getInt("overlay_dim", 25).coerceIn(0, 80)
        set(v) = sp.edit().putInt("overlay_dim", v.coerceIn(0, 80)).apply()

    var keepAwake: Boolean
        get() = sp.getBoolean("keep_awake", true)
        set(v) = sp.edit().putBoolean("keep_awake", v).apply()

    // Record overlay control-bar position (persisted so it stays where dragged).
    var barX: Int
        get() = sp.getInt("bar_x", -1)
        set(v) = sp.edit().putInt("bar_x", v).apply()

    var barY: Int
        get() = sp.getInt("bar_y", -1)
        set(v) = sp.edit().putInt("bar_y", v).apply()

    companion object {
        const val DEFAULT_PORT = 8900
    }
}
