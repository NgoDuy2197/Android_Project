package com.dsoft.jgamer.model

import android.content.Context
import androidx.preference.PreferenceManager
import org.json.JSONObject

/** Thin wrapper over default SharedPreferences for app-wide settings. */
class Prefs(context: Context) {
    private val sp = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)

    var resumeOnLaunch: Boolean
        get() = sp.getBoolean(KEY_RESUME_ON_LAUNCH, false)
        set(v) = sp.edit().putBoolean(KEY_RESUME_ON_LAUNCH, v).apply()

    var autoSaveState: Boolean
        get() = sp.getBoolean(KEY_AUTO_SAVE, true)
        set(v) = sp.edit().putBoolean(KEY_AUTO_SAVE, v).apply()

    var vibrate: Boolean
        get() = sp.getBoolean(KEY_VIBRATE, true)
        set(v) = sp.edit().putBoolean(KEY_VIBRATE, v).apply()

    var lastGameId: String?
        get() = sp.getString(KEY_LAST_GAME, null)
        set(v) = sp.edit().putString(KEY_LAST_GAME, v).apply()

    // ---- Per-system on-screen control layout ---------------------------------
    // Stored as JSON: { "scale": Float, "pos": { token: [xFraction, yFraction] } }

    private fun overlayKey(systemId: String) = "overlay_$systemId"

    private fun overlayJson(systemId: String): JSONObject =
        runCatching { JSONObject(sp.getString(overlayKey(systemId), "{}") ?: "{}") }.getOrDefault(JSONObject())

    fun getOverlayScale(systemId: String): Float =
        overlayJson(systemId).optDouble("scale", 1.0).toFloat().coerceIn(0.6f, 1.8f)

    fun setOverlayScale(systemId: String, scale: Float) {
        val o = overlayJson(systemId); o.put("scale", scale.coerceIn(0.6f, 1.8f).toDouble())
        sp.edit().putString(overlayKey(systemId), o.toString()).apply()
    }

    /** token -> [xFraction, yFraction] */
    fun getOverlayPositions(systemId: String): Map<String, FloatArray> {
        val out = HashMap<String, FloatArray>()
        runCatching {
            val pos = overlayJson(systemId).optJSONObject("pos") ?: return out
            val it = pos.keys()
            while (it.hasNext()) {
                val k = it.next(); val arr = pos.getJSONArray(k)
                out[k] = floatArrayOf(arr.getDouble(0).toFloat(), arr.getDouble(1).toFloat())
            }
        }
        return out
    }

    fun setOverlayPosition(systemId: String, token: String, xf: Float, yf: Float) {
        val o = overlayJson(systemId)
        val pos = o.optJSONObject("pos") ?: JSONObject()
        pos.put(token, org.json.JSONArray().put(xf.toDouble()).put(yf.toDouble()))
        o.put("pos", pos)
        sp.edit().putString(overlayKey(systemId), o.toString()).apply()
    }

    fun resetOverlay(systemId: String) {
        sp.edit().remove(overlayKey(systemId)).apply()
    }

    // ---- Per-game engine (core) override -------------------------------------
    fun getGameCore(gameId: String): String? = sp.getString("core_$gameId", null)
    fun setGameCore(gameId: String, coreFile: String) =
        sp.edit().putString("core_$gameId", coreFile).apply()

    // ---- Per-system D-pad vs joystick ----------------------------------------
    fun getDpadJoystick(systemId: String): Boolean = sp.getBoolean("joy_$systemId", false)
    fun setDpadJoystick(systemId: String, on: Boolean) = sp.edit().putBoolean("joy_$systemId", on).apply()

    // ---- Control-panel theme (global) ----------------------------------------
    var controlTheme: Int
        get() = sp.getInt("control_theme", 0)
        set(v) = sp.edit().putInt("control_theme", v).apply()

    // ---- Screen size / zoom (index into a preset list; global) ---------------
    var screenSize: Int
        get() = sp.getInt("screen_size", 1)
        set(v) = sp.edit().putInt("screen_size", v).apply()

    // ---- Game Boy colour palette (gambatte internal palette index) -----------
    var gbPalette: Int
        get() = sp.getInt("gb_palette", 0)
        set(v) = sp.edit().putInt("gb_palette", v).apply()

    // ---- Local multiplayer / controllers -------------------------------------
    // Touch overlay controls this port (0 = Player 1, 1 = Player 2).
    var touchPlayer: Int
        get() = sp.getInt("touch_player", 0)
        set(v) = sp.edit().putInt("touch_player", v.coerceIn(0, 1)).apply()

    // Android deviceId of the gamepad assigned to Player 1; every other pad = P2.
    var deviceP1: Int
        get() = sp.getInt("device_p1", -1)
        set(v) = sp.edit().putInt("device_p1", v).apply()

    /** Per-device remap: physical keyCode -> RetroPad (Android BUTTON) keyCode. */
    fun getRemap(deviceId: Int): Map<Int, Int> {
        val out = HashMap<Int, Int>()
        runCatching {
            val o = JSONObject(sp.getString("remap_$deviceId", "{}") ?: "{}")
            val it = o.keys()
            while (it.hasNext()) { val k = it.next(); out[k.toInt()] = o.getInt(k) }
        }
        return out
    }

    fun setRemap(deviceId: Int, physicalKey: Int, retroKey: Int) {
        val o = runCatching { JSONObject(sp.getString("remap_$deviceId", "{}") ?: "{}") }.getOrDefault(JSONObject())
        o.put(physicalKey.toString(), retroKey)
        sp.edit().putString("remap_$deviceId", o.toString()).apply()
    }

    fun clearRemap(deviceId: Int) = sp.edit().remove("remap_$deviceId").apply()

    fun resetControllers() {
        sp.edit().remove("device_p1").remove("touch_player").apply()
    }

    // ---- Per-system filter (shader) index ------------------------------------
    fun getFilterIndex(systemId: String): Int = sp.getInt("filter_$systemId", 0)
    fun setFilterIndex(systemId: String, index: Int) =
        sp.edit().putInt("filter_$systemId", index).apply()

    companion object {
        const val KEY_RESUME_ON_LAUNCH = "resume_on_launch"
        const val KEY_AUTO_SAVE = "auto_save_state"
        const val KEY_VIBRATE = "vibrate"
        const val KEY_LAST_GAME = "last_game_id"
    }
}
