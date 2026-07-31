package com.dsoft.noti_forward

import android.content.Context
import org.json.JSONArray

/**
 * Read-only view of the user's settings, backed by the same SharedPreferences
 * file that Flutter's `shared_preferences` plugin writes to. Reading it here
 * lets the background NotificationListenerService honour the config without a
 * round trip to Dart (which may not even be running).
 *
 * Note on numeric keys: Flutter stores a Dart `int` as a Java `Long`, but a Dart
 * `double` as raw long-bits, which is awkward to read back natively. So the rate
 * is persisted as a plain int percent on the Dart side and read with [getLong].
 */
class AppConfig(context: Context) {

    private val prefs =
        context.getSharedPreferences(Const.PREFS_FILE, Context.MODE_PRIVATE)

    val enabled: Boolean
        get() = prefs.getBoolean(Const.prefKey(Const.KEY_ENABLED), true)

    val mode: Int
        get() = getLong(Const.KEY_MODE, Const.MODE_READ.toLong()).toInt()

    val readContent: Boolean
        get() = prefs.getBoolean(Const.prefKey(Const.KEY_READ_CONTENT), true)

    /** TTS speech rate as a fraction (0.2..1.0). Stored as an int percent. */
    val rate: Float
        get() = (getLong(Const.KEY_RATE_PCT, 50L).toFloat() / 100f).coerceIn(0.2f, 1.0f)

    val webhook: String
        get() = prefs.getString(Const.prefKey(Const.KEY_WEBHOOK), "") ?: ""

    val discordUsername: String
        get() = prefs.getString(Const.prefKey(Const.KEY_DISCORD_USERNAME), "") ?: ""

    /** Legacy comma-separated package-name fragments; empty means unused. */
    val filter: String
        get() = prefs.getString(Const.prefKey(Const.KEY_FILTER), "") ?: ""

    /** Exact package names chosen in the app picker. */
    val selectedApps: Set<String>
        get() = parseSelectedApps(
            prefs.getString(Const.prefKey(Const.KEY_SELECTED_APPS), "") ?: ""
        )

    val filterMode: Int
        get() = getLong(Const.KEY_FILTER_MODE, Const.FILTER_ALL.toLong()).toInt()

    /** Comma-separated keywords; empty = no keyword filter. Match is OR. */
    val keywordFilter: String
        get() = prefs.getString(Const.prefKey(Const.KEY_KEYWORD_FILTER), "") ?: ""

    val skipOngoing: Boolean
        get() = prefs.getBoolean(Const.prefKey(Const.KEY_SKIP_ONGOING), true)

    val minIntervalSec: Int
        get() = getLong(Const.KEY_MIN_INTERVAL, 0L).toInt()

    /** BCP-47 language tag for reading (e.g. "vi-VN"); empty = engine default. */
    val ttsLang: String
        get() = prefs.getString(Const.prefKey(Const.KEY_TTS_LANG), "") ?: ""

    /** Exact TTS voice name; empty = pick by language. */
    val ttsVoice: String
        get() = prefs.getString(Const.prefKey(Const.KEY_TTS_VOICE), "") ?: ""

    val ttsVoiceLocale: String
        get() = prefs.getString(Const.prefKey(Const.KEY_TTS_VOICE_LOCALE), "") ?: ""

    /** Keep a foreground service alive so reading keeps working in the background. */
    val keepAlive: Boolean
        get() = prefs.getBoolean(Const.prefKey(Const.KEY_KEEP_ALIVE), true)

    val speakAppName: Boolean
        get() = prefs.getBoolean(Const.prefKey(Const.KEY_SPEAK_APP_NAME), false)

    private fun getLong(key: String, def: Long): Long {
        val full = Const.prefKey(key)
        if (!prefs.contains(full)) return def
        return try {
            prefs.getLong(full, def)
        } catch (_: ClassCastException) {
            def
        }
    }

    companion object {
        fun parseSelectedApps(raw: String): Set<String> {
            val s = raw.trim()
            if (s.isEmpty()) return emptySet()
            return try {
                if (s.startsWith("[")) {
                    val arr = JSONArray(s)
                    buildSet {
                        for (i in 0 until arr.length()) {
                            val pkg = arr.optString(i).trim()
                            if (pkg.isNotEmpty()) add(pkg)
                        }
                    }
                } else {
                    // Comma-separated fallback.
                    s.split(',')
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .toSet()
                }
            } catch (_: Exception) {
                emptySet()
            }
        }
    }
}
