package com.dsoft.macrointegrate

import android.content.Context

/** Settings. The webhook falls back to the one baked in at build time (webhook.properties). */
class Prefs(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("macrointegrate", Context.MODE_PRIVATE)

    /** Empty = use the build-time default [BuildConfig.DEFAULT_WEBHOOK]. */
    var webhook: String
        get() = sp.getString("webhook", "")?.takeIf { it.isNotBlank() } ?: BuildConfig.DEFAULT_WEBHOOK
        set(v) = sp.edit().putString("webhook", v.trim()).apply()

    /** The raw stored override ("" when relying on the default). */
    val webhookOverride: String get() = sp.getString("webhook", "") ?: ""

    val hasDefault: Boolean get() = BuildConfig.DEFAULT_WEBHOOK.isNotBlank()

    /** Name shown as the Discord message author. */
    var username: String
        get() = sp.getString("username", "")?.takeIf { it.isNotBlank() } ?: "MacroIntegrate"
        set(v) = sp.edit().putString("username", v.trim()).apply()
}
