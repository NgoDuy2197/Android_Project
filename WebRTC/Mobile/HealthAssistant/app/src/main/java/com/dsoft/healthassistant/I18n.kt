package com.dsoft.healthassistant

import android.content.Context

/**
 * Tiny runtime i18n. [en] mirrors Prefs.lang and is refreshed by each Activity in onCreate (via
 * [apply]). UI strings are written as `tr("tiếng Việt", "English")` right where they're used.
 */
object I18n {
    @Volatile var en = false
    fun apply(ctx: Context) { en = Prefs(ctx).lang == "en" }
}

/** Pick the Vietnamese or English variant for the current language. */
fun tr(vi: String, en: String): String = if (I18n.en) en else vi
