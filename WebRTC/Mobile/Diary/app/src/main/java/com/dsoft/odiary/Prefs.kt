package com.dsoft.odiary

import android.content.Context

object Prefs {
    private fun sp(c: Context) = c.applicationContext.getSharedPreferences("diary", Context.MODE_PRIVATE)
    private fun put(c: Context, block: android.content.SharedPreferences.Editor.() -> Unit) =
        sp(c).edit().apply(block).apply()

    // Appearance
    fun themeId(c: Context): String = sp(c).getString("theme", "bubblegum") ?: "bubblegum"
    fun setThemeId(c: Context, v: String) = put(c) { putString("theme", v) }

    /** none | sakura | hearts | stars | snow | fairy | bubbles */
    fun fallEffect(c: Context): String = sp(c).getString("fall", "sakura") ?: "sakura"
    fun setFallEffect(c: Context, v: String) = put(c) { putString("fall", v) }

    /** none | sparkle | hearts | fairy | ripple | burst */
    fun tapEffect(c: Context): String = sp(c).getString("tap", "sparkle") ?: "sparkle"
    fun setTapEffect(c: Context, v: String) = put(c) { putString("tap", v) }

    // Diary look
    /** Global diary background: "" none, "paper"/"grid"/"dots"/"clouds" templates, or a file:// / content uri. */
    fun globalBg(c: Context): String = sp(c).getString("global_bg", "") ?: ""
    fun setGlobalBg(c: Context, v: String) = put(c) { putString("global_bg", v) }
    fun globalPadding(c: Context): Int = sp(c).getInt("global_pad", 16)
    fun setGlobalPadding(c: Context, v: Int) = put(c) { putInt("global_pad", v.coerceIn(0, 80)) }

    // TTS
    fun ttsVoice(c: Context): String = sp(c).getString("tts_voice", "") ?: ""
    fun setTtsVoice(c: Context, v: String) = put(c) { putString("tts_voice", v) }
    fun ttsRate(c: Context): Int = sp(c).getInt("tts_rate", 100)
    fun setTtsRate(c: Context, v: Int) = put(c) { putInt("tts_rate", v.coerceIn(50, 200)) }
    fun ttsPitch(c: Context): Int = sp(c).getInt("tts_pitch", 100)
    fun setTtsPitch(c: Context, v: Int) = put(c) { putInt("tts_pitch", v.coerceIn(50, 200)) }

    // Account
    fun currentAccount(c: Context): String = sp(c).getString("acct", "") ?: ""
    fun setCurrentAccount(c: Context, v: String) = put(c) { putString("acct", v) }

    /** Auto-sync to Drive whenever diary data changes (default ON). */
    fun autoSync(c: Context): Boolean = sp(c).getBoolean("auto_sync", true)
    fun setAutoSync(c: Context, v: Boolean) = put(c) { putBoolean("auto_sync", v) }

    /**
     * Durable "có thay đổi chưa đẩy lên Drive" flag. Set when data changes, cleared only after a
     * successful sync. Survives app kill, so sync auto-retries on next launch / when network returns.
     * Uses commit() so it isn't lost if the process dies right after.
     */
    fun pendingSync(c: Context): Boolean = sp(c).getBoolean("pending_sync", false)
    fun setPendingSync(c: Context, v: Boolean) { runCatching { sp(c).edit().putBoolean("pending_sync", v).commit() } }

    /** Durable "cần kéo dữ liệu từ Drive về" flag (set on login/restore); cleared after a successful pull. */
    fun pendingPull(c: Context): Boolean = sp(c).getBoolean("pending_pull", false)
    fun setPendingPull(c: Context, v: Boolean) { runCatching { sp(c).edit().putBoolean("pending_pull", v).commit() } }

    /** Last sync result message (shown in Settings). */
    fun lastSync(c: Context): String = sp(c).getString("last_sync", "") ?: ""
    fun setLastSync(c: Context, v: String) = put(c) { putString("last_sync", v) }

    // App wallpaper (behind everything), with brightness + blur
    fun appBgOn(c: Context) = sp(c).getBoolean("appbg_on", false)
    fun setAppBgOn(c: Context, v: Boolean) = put(c) { putBoolean("appbg_on", v) }
    fun appBgBlur(c: Context) = sp(c).getInt("appbg_blur", 0).coerceIn(0, 25)
    fun setAppBgBlur(c: Context, v: Int) = put(c) { putInt("appbg_blur", v.coerceIn(0, 25)) }
    /** -80 (tối) .. +80 (sáng) */
    fun appBgDim(c: Context) = sp(c).getInt("appbg_dim", -15).coerceIn(-80, 80)
    fun setAppBgDim(c: Context, v: Int) = put(c) { putInt("appbg_dim", v.coerceIn(-80, 80)) }

    /** "calendar" | "list" */
    fun viewMode(c: Context): String = sp(c).getString("view", "calendar") ?: "calendar"
    fun setViewMode(c: Context, v: String) = put(c) { putString("view", v) }

    // Misc
    fun styleStamp(c: Context) = "${themeId(c)}|${globalBg(c)}|${globalPadding(c)}|${appBgOn(c)}|${appBgBlur(c)}|${appBgDim(c)}|${Store.appWallpaperFile().lastModified()}"
}
