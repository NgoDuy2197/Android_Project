package com.example.cameraman

import android.content.Context
import android.os.Build

/**
 * Read-only view of the user's settings, backed by the same SharedPreferences
 * file that Flutter's `shared_preferences` plugin writes to. Reading it here
 * lets the widget and the background service honour the config without a round
 * trip to Dart (which may not even be running).
 *
 * Note on numeric keys: Flutter stores Dart `int` as a Java `Long` (real
 * value) but Dart `double` as raw long-bits, which is awkward to read back
 * natively. So sensitivity and clip length are persisted as plain ints
 * (percent and seconds) on the Dart side and read with [getLong] here.
 */
class AppConfig(context: Context) {

    private val prefs =
        context.getSharedPreferences(Const.PREFS_FILE, Context.MODE_PRIVATE)

    val motionEnabled: Boolean
        get() = prefs.getBoolean(Const.prefKey(Const.KEY_MOTION_ENABLED), false)

    /** "photo" or "video" — what to capture when motion fires. */
    val motionMode: String
        get() = prefs.getString(Const.prefKey(Const.KEY_MOTION_MODE), "photo") ?: "photo"

    val discordWebhook: String
        get() = prefs.getString(Const.prefKey(Const.KEY_DISCORD_WEBHOOK), "") ?: ""

    /** Forward the phone's system notifications to the Discord webhook. */
    val notiForwardEnabled: Boolean
        get() = prefs.getBoolean(Const.prefKey(Const.KEY_NOTI_FORWARD), false)

    /** Persisted SAF tree uri, or empty when saving to the default app folder. */
    val saveTreeUri: String
        get() = prefs.getString(Const.prefKey(Const.KEY_SAVE_TREE_URI), "") ?: ""

    /**
     * Motion threshold as a fraction (0..1). Stored as an int percent; higher
     * means more of the frame must change before it counts as motion.
     */
    val motionSensitivity: Double
        get() = getLong(Const.KEY_MOTION_SENSITIVITY, 6L).toDouble() / 100.0

    /** How long a motion-triggered clip runs, in seconds. */
    val motionVideoSeconds: Int
        get() = getLong(Const.KEY_MOTION_VIDEO_SECONDS, 10L).toInt()

    /** Which camera motion detection uses: "front" or "back" (default back). */
    val motionLens: String
        get() = prefs.getString(Const.prefKey(Const.KEY_MOTION_LENS), Const.LENS_BACK)
            ?: Const.LENS_BACK

    /**
     * Length in minutes at which a continuous manual recording auto-splits into
     * a new file (default 5). A value <= 0 disables splitting.
     */
    val splitMinutes: Int
        get() = getLong(Const.KEY_SPLIT_MINUTES, 5L).toInt()

    /** User-set device name, falling back to the phone's model. */
    val deviceName: String
        get() {
            val name = prefs.getString(Const.prefKey(Const.KEY_DEVICE_NAME), "") ?: ""
            return if (name.isNotBlank()) name
            else "${Build.MANUFACTURER} ${Build.MODEL}".trim()
        }

    private fun getLong(key: String, def: Long): Long {
        val full = Const.prefKey(key)
        if (!prefs.contains(full)) return def
        return try {
            prefs.getLong(full, def)
        } catch (_: ClassCastException) {
            def
        }
    }
}
