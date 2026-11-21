package com.dsoft.screengrab

import android.content.Context
import android.graphics.Rect

/** All user-tunable settings, backed by SharedPreferences. */
class Prefs(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("screengrab", Context.MODE_PRIVATE)

    // "projection" = non-root MediaProjection (one consent per session; needs the LSPosed hook to see
    //                FLAG_SECURE apps like Telegram/Udemy).
    // "root"       = su screencap/screenrecord (no consent dialog; needs root).
    var captureMode: String
        get() = sp.getString("captureMode", "projection") ?: "projection"
        set(v) { sp.edit().putString("captureMode", v).apply() }

    /** Record internal/device audio (what the phone is playing) — API 29+, via AudioPlaybackCapture. */
    var audioDevice: Boolean
        get() = sp.getBoolean("audioDevice", false)
        set(v) { sp.edit().putBoolean("audioDevice", v).apply() }

    /** Record the microphone. */
    var audioMic: Boolean
        get() = sp.getBoolean("audioMic", false)
        set(v) { sp.edit().putBoolean("audioMic", v).apply() }

    // "full" = whole screen; "region" = the rectangle below.
    var regionMode: String
        get() = sp.getString("regionMode", "full") ?: "full"
        set(v) { sp.edit().putString("regionMode", v).apply() }

    var regLeft: Int get() = sp.getInt("regLeft", 0); set(v) { sp.edit().putInt("regLeft", v).apply() }
    var regTop: Int get() = sp.getInt("regTop", 0); set(v) { sp.edit().putInt("regTop", v).apply() }
    var regRight: Int get() = sp.getInt("regRight", 0); set(v) { sp.edit().putInt("regRight", v).apply() }
    var regBottom: Int get() = sp.getInt("regBottom", 0); set(v) { sp.edit().putInt("regBottom", v).apply() }

    /** Valid saved region (non-empty)? */
    fun hasRegion(): Boolean = regRight > regLeft && regBottom > regTop

    fun region(): Rect = Rect(regLeft, regTop, regRight, regBottom)

    fun saveRegion(r: Rect) {
        sp.edit().putInt("regLeft", r.left).putInt("regTop", r.top)
            .putInt("regRight", r.right).putInt("regBottom", r.bottom).apply()
    }

    /** User wants the floating bubble shown. */
    var overlayEnabled: Boolean
        get() = sp.getBoolean("overlayEnabled", false)
        set(v) { sp.edit().putBoolean("overlayEnabled", v).apply() }

    // ===== Video sniffer (bắt luồng video của app/web) =====
    /** How to route all apps through our local MITM proxy: "root" (global proxy, recommended) or "vpn". */
    var snifferRouting: String
        get() = sp.getString("snifferRouting", "root") ?: "root"
        set(v) { sp.edit().putString("snifferRouting", v).apply() }

    /** User wants the sniffer running. */
    var snifferEnabled: Boolean
        get() = sp.getBoolean("snifferEnabled", false)
        set(v) { sp.edit().putBoolean("snifferEnabled", v).apply() }

    var snifferPort: Int get() = sp.getInt("snifferPort", 8899); set(v) { sp.edit().putInt("snifferPort", v).apply() }

    var fps: Int get() = sp.getInt("fps", 30); set(v) { sp.edit().putInt("fps", v).apply() }

    /** Video bitrate in Mbps. */
    var bitrateMbps: Int get() = sp.getInt("bitrateMbps", 12); set(v) { sp.edit().putInt("bitrateMbps", v).apply() }
}
