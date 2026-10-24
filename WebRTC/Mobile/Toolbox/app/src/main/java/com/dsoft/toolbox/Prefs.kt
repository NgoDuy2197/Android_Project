package com.dsoft.toolbox

import android.content.Context
import android.content.SharedPreferences
import android.os.Environment

/** All user settings, persisted in SharedPreferences. */
class Prefs(ctx: Context) {
    private val sp: SharedPreferences =
        ctx.applicationContext.getSharedPreferences("toolbox", Context.MODE_PRIVATE)

    // ---- FTP ----
    /** Absolute path of the only folder FTP clients can see. */
    var ftpDir: String
        get() = sp.getString("ftp_dir", null)?.takeIf { it.isNotBlank() }
            ?: Environment.getExternalStorageDirectory().absolutePath
        set(v) = sp.edit().putString("ftp_dir", v.trim().trimEnd('/').ifEmpty { "/" }).apply()

    var ftpPort: Int
        get() = sp.getInt("ftp_port", DEFAULT_PORT)
        set(v) = sp.edit().putInt("ftp_port", v.coerceIn(1024, 65535)).apply()

    /** Empty = anonymous login (any user / password). */
    var ftpUser: String
        get() = sp.getString("ftp_user", DEFAULT_USER) ?: DEFAULT_USER
        set(v) = sp.edit().putString("ftp_user", v.trim()).apply()

    var ftpPass: String
        get() = sp.getString("ftp_pass", DEFAULT_PASS) ?: DEFAULT_PASS
        set(v) = sp.edit().putString("ftp_pass", v).apply()

    var ftpReadOnly: Boolean
        get() = sp.getBoolean("ftp_ro", false)
        set(v) = sp.edit().putBoolean("ftp_ro", v).apply()

    // ---- Dimmer ----
    /** Overlay darkness in percent, 0..[MAX_DIM]. */
    var dimLevel: Int
        get() = sp.getInt("dim_level", 50).coerceIn(0, MAX_DIM)
        set(v) = sp.edit().putInt("dim_level", v.coerceIn(0, MAX_DIM)).apply()

    /** Also drop the system brightness to its minimum while dimming. */
    var dimLowBrightness: Boolean
        get() = sp.getBoolean("dim_low_bright", false)
        set(v) = sp.edit().putBoolean("dim_low_bright", v).apply()

    /** System brightness saved before dimming, restored afterwards (-1 = none). */
    var savedBrightness: Int
        get() = sp.getInt("saved_bright", -1)
        set(v) = sp.edit().putInt("saved_bright", v).apply()

    var savedBrightnessMode: Int
        get() = sp.getInt("saved_bright_mode", -1)
        set(v) = sp.edit().putInt("saved_bright_mode", v).apply()

    // ---- Screen translate ----
    /** Source language: "en", "zh", "ja" or "vi". */
    var trSrc: String
        get() = sp.getString("tr_src", "en") ?: "en"
        set(v) = sp.edit().putString("tr_src", v).apply()

    /** Target language, default Vietnamese. */
    var trDst: String
        get() = sp.getString("tr_dst", "vi") ?: "vi"
        set(v) = sp.edit().putString("tr_dst", v).apply()

    /** "offline" = Google ML Kit on-device, "online" = Google Translate web. */
    var trEngine: String
        get() = sp.getString("tr_engine", "offline") ?: "offline"
        set(v) = sp.edit().putString("tr_engine", v).apply()

    var bubbleX: Int
        get() = sp.getInt("bubble_x", -1)
        set(v) = sp.edit().putInt("bubble_x", v).apply()

    var bubbleY: Int
        get() = sp.getInt("bubble_y", -1)
        set(v) = sp.edit().putInt("bubble_y", v).apply()

    // Share board: auto-save files uploaded by clients into a chosen folder.
    var shareAutoSave: Boolean
        get() = sp.getBoolean("share_autosave", false)
        set(v) = sp.edit().putBoolean("share_autosave", v).apply()

    /** SAF tree Uri (persisted) of the folder to save into; empty = not set. */
    var shareSaveTree: String
        get() = sp.getString("share_save_tree", "") ?: ""
        set(v) = sp.edit().putString("share_save_tree", v).apply()

    /** Human-readable name of that folder, for the UI. */
    var shareSaveName: String
        get() = sp.getString("share_save_name", "") ?: ""
        set(v) = sp.edit().putString("share_save_name", v).apply()

    companion object {
        const val DEFAULT_PORT = 2121
        const val DEFAULT_USER = "admin"
        const val DEFAULT_PASS = "123456"
        /** Kept below 100 so the phone is never fully blind (the slider stays visible). */
        const val MAX_DIM = 98
    }
}
