package com.example.cameraman

/**
 * Shared names for the Dart <-> native bridge, the capture-service intent
 * protocol, and the config keys stored by Flutter's shared_preferences.
 *
 * Flutter writes preferences into the "FlutterSharedPreferences" file and
 * prefixes every key with "flutter." — the native side reads them with that
 * prefix (see [prefKey]).
 */
object Const {
    // Dart <-> native channels.
    const val METHOD_CHANNEL = "cameraman/native"
    const val EVENT_CHANNEL = "cameraman/events"

    // Intent actions understood by CameraCaptureService.
    const val ACTION_PHOTO = "com.example.cameraman.action.PHOTO"
    const val ACTION_VIDEO_START = "com.example.cameraman.action.VIDEO_START"
    const val ACTION_VIDEO_STOP = "com.example.cameraman.action.VIDEO_STOP"
    const val ACTION_VIDEO_TOGGLE = "com.example.cameraman.action.VIDEO_TOGGLE"
    const val ACTION_MOTION_START = "com.example.cameraman.action.MOTION_START"
    const val ACTION_MOTION_STOP = "com.example.cameraman.action.MOTION_STOP"

    const val EXTRA_LENS = "lens" // "front" | "back"

    const val LENS_FRONT = "front"
    const val LENS_BACK = "back"

    // Config keys (without the "flutter." prefix the plugin adds on Android).
    const val KEY_MOTION_ENABLED = "motion_enabled"      // bool
    const val KEY_MOTION_MODE = "motion_mode"            // "photo" | "video"
    const val KEY_DISCORD_WEBHOOK = "discord_webhook"    // String
    const val KEY_SAVE_TREE_URI = "save_tree_uri"        // SAF tree uri, "" = default
    const val KEY_MOTION_SENSITIVITY = "motion_sensitivity" // double 0..1
    const val KEY_MOTION_VIDEO_SECONDS = "motion_video_seconds" // int
    const val KEY_MOTION_LENS = "motion_lens"            // "front" | "back"
    const val KEY_DEVICE_NAME = "device_name"            // shown in Discord alerts
    const val KEY_SPLIT_MINUTES = "manual_split_minutes" // auto-split long clips
    const val KEY_NOTI_FORWARD = "noti_forward_enabled"  // forward system notifications

    const val PREFS_FILE = "FlutterSharedPreferences"

    /** Maps a logical config key to the name Flutter actually persists. */
    fun prefKey(key: String) = "flutter.$key"
}
