package com.dsoft.jgamer.model

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.view.InputDevice

/** TV / controller detection used for the "Auto" on-screen controls mode. */
object DeviceInfo {

    fun isTv(context: Context): Boolean = runCatching {
        val ui = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        ui?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    }.getOrDefault(false)

    /** Device ids of connected gamepads / joysticks (not virtual devices). */
    fun connectedPads(): List<Int> = runCatching {
        InputDevice.getDeviceIds().filter {
            val d = InputDevice.getDevice(it) ?: return@filter false
            val s = d.sources
            !d.isVirtual && (s and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
                s and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK)
        }
    }.getOrDefault(emptyList())

    /** Resolve a [Prefs] pad mode to "show the touch pad?". */
    fun showPad(context: Context, mode: Int): Boolean = when (mode) {
        Prefs.PAD_ON -> true
        Prefs.PAD_OFF -> false
        else -> !isTv(context) && connectedPads().isEmpty()
    }
}
