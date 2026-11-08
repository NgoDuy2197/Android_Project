package com.dsoft.healthassistant

import android.content.Context

/** AI engine configuration (which backend, keys, model, imported offline model). */
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("ha_prefs", Context.MODE_PRIVATE)

    /** "gemini" (online API) or "offline" (imported on-device model). */
    var engine: String
        get() = sp.getString("engine", "gemini") ?: "gemini"
        set(v) = sp.edit().putString("engine", v).apply()

    /** Legacy single key (kept for migration into [geminiKeys]). */
    var geminiKey: String
        get() = sp.getString("geminiKey", "") ?: ""
        set(v) = sp.edit().putString("geminiKey", v).apply()

    /** One API key per line. The app tries them in order, falling back to the next on quota/key errors. */
    var geminiKeys: String
        get() = sp.getString("geminiKeys", "") ?: ""
        set(v) = sp.edit().putString("geminiKeys", v).apply()

    /** Parsed, de-duplicated key list; falls back to the legacy single key if the list is empty. */
    fun geminiKeyList(): List<String> {
        val list = geminiKeys.lines().map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (list.isNotEmpty()) return list
        val single = geminiKey.trim()
        return if (single.isNotEmpty()) listOf(single) else emptyList()
    }

    var geminiModel: String
        get() = sp.getString("geminiModel", "gemini-2.5-flash") ?: "gemini-2.5-flash"
        set(v) = sp.edit().putString("geminiModel", v).apply()

    /** Absolute path of the imported .task model, copied into the app's files dir. */
    var offlineModelPath: String
        get() = sp.getString("offlineModelPath", "") ?: ""
        set(v) = sp.edit().putString("offlineModelPath", v).apply()

    var offlineModelName: String
        get() = sp.getString("offlineModelName", "") ?: ""
        set(v) = sp.edit().putString("offlineModelName", v).apply()

    var offlineMaxTokens: Int
        get() = sp.getInt("offlineMaxTokens", 1024)
        set(v) = sp.edit().putInt("offlineMaxTokens", v).apply()

    /** Optional Hugging Face access token (read scope) for downloading gated models (e.g. Gemma). */
    var hfToken: String
        get() = sp.getString("hfToken", "") ?: ""
        set(v) = sp.edit().putString("hfToken", v).apply()
}
