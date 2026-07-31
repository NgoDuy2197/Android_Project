package com.dsoft.noti_forward

/**
 * Shared names for the Dart <-> native bridge and the config keys stored by
 * Flutter's shared_preferences.
 *
 * Flutter writes preferences into the "FlutterSharedPreferences" file and
 * prefixes every key with "flutter." — the native side reads them with that
 * prefix (see [prefKey]).
 */
object Const {
    // Dart <-> native channels.
    const val METHOD_CHANNEL = "noti_forward/native"
    const val EVENT_CHANNEL = "noti_forward/events"

    // Forward modes (must match the Dart FwdMode enum order).
    const val MODE_READ = 0     // speak with TTS
    const val MODE_DISCORD = 1  // send to Discord webhook
    const val MODE_BOTH = 2     // both

    // App filter modes (must match the Dart FilterMode enum order).
    const val FILTER_ALL = 0    // forward every app
    const val FILTER_ALLOW = 1  // only selected packages
    const val FILTER_DENY = 2   // every app except selected

    // Config keys (without the "flutter." prefix the plugin adds on Android).
    const val KEY_ENABLED = "enabled"          // bool
    const val KEY_MODE = "mode"                // int (see MODE_*)
    const val KEY_READ_CONTENT = "readContent" // bool — read body too, not just title
    const val KEY_RATE_PCT = "rate_pct"        // int 20..100 — TTS speech rate percent
    const val KEY_WEBHOOK = "webhook"          // String — Discord webhook url
    const val KEY_DISCORD_USERNAME = "discordUsername" // String — webhook display name
    const val KEY_FILTER = "filter"            // String — legacy comma-separated package fragments
    const val KEY_SELECTED_APPS = "selectedApps" // String — JSON array of package names
    const val KEY_FILTER_MODE = "filterMode"   // int (see FILTER_*)
    const val KEY_KEYWORD_FILTER = "keywordFilter" // String — title/body must match (comma OR)
    const val KEY_SKIP_ONGOING = "skipOngoing" // bool — skip ongoing/media notifications
    const val KEY_MIN_INTERVAL = "minInterval" // int seconds — anti-repeat gap
    const val KEY_TTS_LANG = "ttsLang"         // String BCP-47 tag, "" = auto
    const val KEY_TTS_VOICE = "ttsVoice"       // String voice name, "" = auto
    const val KEY_TTS_VOICE_LOCALE = "ttsVoiceLocale" // String, paired with the voice
    const val KEY_KEEP_ALIVE = "keepAlive"     // bool — keep a foreground service alive
    const val KEY_SPEAK_APP_NAME = "speakAppName" // bool — prefix TTS with app label

    const val PREFS_FILE = "FlutterSharedPreferences"

    /** Maps a logical config key to the name Flutter actually persists. */
    fun prefKey(key: String) = "flutter.$key"
}
