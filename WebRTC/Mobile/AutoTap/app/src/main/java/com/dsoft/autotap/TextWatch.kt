package com.dsoft.autotap

import org.json.JSONArray
import org.json.JSONObject

/**
 * One entry of the "watch the screen and tap" list: a piece of text to look for.
 *
 * Unlike a scenario step this needs no coordinates and no OCR — the text is read
 * straight off the accessibility tree, so it also works on a TV with no touchscreen.
 *
 * Matching is case sensitive by default ([Prefs.watchCaseSensitive]), so "Skip"
 * never fires on "skip".
 */
class WatchRule(
    var text: String,
    var enabled: Boolean = true,
    /** true = the node text must be exactly [text]; false = it only has to contain it. */
    var exact: Boolean = true,
) {
    fun matches(s: CharSequence?, caseSensitive: Boolean): Boolean {
        if (text.isEmpty()) return false
        val v = s?.toString()?.trim() ?: return false
        if (v.isEmpty()) return false
        return when {
            exact -> v.equals(text, ignoreCase = !caseSensitive)
            caseSensitive -> v.contains(text)
            else -> v.lowercase().contains(text.lowercase())
        }
    }

    val modeLabel get() = if (exact) "đúng y hệt" else "chứa"

    fun copy() = WatchRule(text, enabled, exact)

    companion object {
        /** Seeded on first run: the usual YouTube / ad "skip" buttons, phone + TV. */
        val DEFAULTS: List<WatchRule>
            get() = listOf(
                WatchRule("Skip Ads"),
                WatchRule("Skip Ad"),
                WatchRule("Skip ads"),
                WatchRule("Skip"),
                WatchRule("Bỏ qua quảng cáo"),
                WatchRule("Bỏ qua"),
            )

        fun parse(s: String): List<WatchRule> = try {
            val a = JSONArray(s)
            (0 until a.length()).map {
                val o = a.getJSONObject(it)
                WatchRule(o.optString("t"), o.optBoolean("on", true), o.optBoolean("x", true))
            }
        } catch (_: Exception) {
            emptyList()
        }

        fun serialize(rules: List<WatchRule>): String {
            val a = JSONArray()
            rules.forEach { a.put(JSONObject().put("t", it.text).put("on", it.enabled).put("x", it.exact)) }
            return a.toString()
        }
    }
}
