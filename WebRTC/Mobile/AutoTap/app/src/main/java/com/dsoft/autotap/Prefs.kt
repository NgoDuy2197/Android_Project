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

    // ---- Text watcher (tự động bấm chữ trên màn hình) ----
    /** Feature switch. The accessibility service stays enabled for scenarios either way. */
    var watchOn: Boolean
        get() = sp.getBoolean("watch_on", false)
        set(v) = sp.edit().putBoolean("watch_on", v).apply()

    /** The texts to look for; seeded with [WatchRule.DEFAULTS] on first run. */
    var watchRules: List<WatchRule>
        get() {
            val s = sp.getString("watch_rules", null)
            return if (s.isNullOrBlank()) WatchRule.DEFAULTS else WatchRule.parse(s)
        }
        set(v) = sp.edit().putString("watch_rules", WatchRule.serialize(v)).apply()

    /** "Skip" must not match "skip". On by default — the texts match letter for letter. */
    var watchCaseSensitive: Boolean
        get() = sp.getBoolean("watch_case", true)
        set(v) = sp.edit().putBoolean("watch_case", v).apply()

    /** Only tap real buttons; when off, a gesture tap is sent on the text position. */
    var watchClickableOnly: Boolean
        get() = sp.getBoolean("watch_clickable_only", true)
        set(v) = sp.edit().putBoolean("watch_clickable_only", v).apply()

    /** Milliseconds before the same text may be tapped again. */
    var watchCooldownMs: Int
        get() = sp.getInt("watch_cooldown", 1500).coerceIn(200, 60_000)
        set(v) = sp.edit().putInt("watch_cooldown", v.coerceIn(200, 60_000)).apply()

    /** Re-scan period in ms; 0 = only react to accessibility events. */
    var watchScanMs: Int
        get() = sp.getInt("watch_scan_ms", 600).let { if (it <= 0) 0 else it.coerceIn(150, 10_000) }
        set(v) = sp.edit().putInt("watch_scan_ms", if (v <= 0) 0 else v.coerceIn(150, 10_000)).apply()

    /** Packages to restrict tapping to; empty = every app. */
    var watchApps: Set<String>
        get() = (sp.getString("watch_apps", "") ?: "").split(',')
            .map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        set(v) = sp.edit().putString("watch_apps", v.joinToString(",")).apply()

    /** Show a toast on every tap (handy while setting the list up). */
    var watchToast: Boolean
        get() = sp.getBoolean("watch_toast", false)
        set(v) = sp.edit().putBoolean("watch_toast", v).apply()

    companion object {
        const val DEFAULT_PORT = 8900
    }
}
