package com.dsoft.remotelink

import android.content.Context

/** All user-tunable settings, backed by SharedPreferences. */
class Prefs(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("remotelink", Context.MODE_PRIVATE)

    // --- which capture backend to use ---
    // "projection" = non-root MediaProjection (works everywhere, one consent per session)
    // "root"       = su + screenrecord/screencap (no dialog, needs root)
    var captureMode: String
        get() = sp.getString("captureMode", "projection") ?: "projection"
        set(v) { sp.edit().putString("captureMode", v).apply() }

    // --- how to inject taps/keys ---
    // "accessibility" = AccessibilityService gestures (grant once, no root)
    // "root"          = su + input / /dev/input
    var inputMode: String
        get() = sp.getString("inputMode", "accessibility") ?: "accessibility"
        set(v) { sp.edit().putString("inputMode", v).apply() }

    // --- which servers to expose ---
    var enableVnc: Boolean
        get() = sp.getBoolean("enableVnc", true)
        set(v) { sp.edit().putBoolean("enableVnc", v).apply() }

    var enableStream: Boolean
        get() = sp.getBoolean("enableStream", true)
        set(v) { sp.edit().putBoolean("enableStream", v).apply() }

    var vncPort: Int
        get() = sp.getInt("vncPort", 5900)
        set(v) { sp.edit().putInt("vncPort", v).apply() }

    var streamPort: Int
        get() = sp.getInt("streamPort", 5901)
        set(v) { sp.edit().putInt("streamPort", v).apply() }

    /** Empty = no auth. Used by both VNC (first 8 chars, DES) and the stream server (full, SHA-256). */
    var password: String
        get() = sp.getString("password", "") ?: ""
        set(v) { sp.edit().putString("password", v).apply() }

    // --- stream (H.264) tuning ---
    var streamFps: Int
        get() = sp.getInt("streamFps", 30)
        set(v) { sp.edit().putInt("streamFps", v).apply() }

    /** Mbps for the H.264 encoder. */
    var streamBitrateMbps: Int
        get() = sp.getInt("streamBitrateMbps", 8)
        set(v) { sp.edit().putInt("streamBitrateMbps", v).apply() }

    var vncFps: Int
        get() = sp.getInt("vncFps", 12)
        set(v) { sp.edit().putInt("vncFps", v).apply() }

    /** Downscale factor for capture (1 = native, 2 = half each side). Keeps bandwidth/CPU sane on big screens. */
    var scaleDiv: Int
        get() = sp.getInt("scaleDiv", 1)
        set(v) { sp.edit().putInt("scaleDiv", v).apply() }
}
