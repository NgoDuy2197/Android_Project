package com.dsoft.volteguard

import android.content.Context
import android.content.SharedPreferences

/** All user settings. Times are in seconds. */
class Prefs(ctx: Context) {
    private val sp: SharedPreferences = ctx.getSharedPreferences("guard", Context.MODE_PRIVATE)

    var enabled by bool("enabled", false)
    /** 0 = SIM 1, 1 = SIM 2. */
    var simSlot by int("sim_slot", 0)
    var intervalSec by int("interval_sec", 20)
    /** Consecutive failed checks required before recovery kicks in (filters short handovers). */
    var confirmCount by int("confirm_count", 2)
    /** Wait after each recovery step before re-checking. */
    var settleSec by int("settle_sec", 25)
    /** Minimum time between two recovery runs. */
    var cooldownSec by int("cooldown_sec", 120)

    /** Force the VoLTE switch ON (test_fault.bat "restore"); fastest, tried first. */
    var stepVolteRestore by bool("step_volte_restore", true)
    var stepVolteSwitch by bool("step_volte_switch", true)
    var stepCarrierConfig by bool("step_carrier_cfg", true)
    var stepResetIms by bool("step_reset_ims", true)
    var stepData by bool("step_data", true)
    var stepModem by bool("step_modem", false)

    /** Without Shizuku only a heuristic is available; allow it to trigger recovery? */
    var heuristicTrigger by bool("heuristic_trigger", false)
    var notifyEvents by bool("notify_events", true)

    init {
        // v2: faster defaults. Values equal to the old defaults were never chosen by the user
        // (the settings screen persists whatever it shows), so move them to the new ones.
        if (sp.getInt("defaults_v", 1) < 2) {
            val e = sp.edit()
            if (sp.getInt("interval_sec", 60) == 60) e.putInt("interval_sec", 20)
            if (sp.getInt("settle_sec", 30) == 30) e.putInt("settle_sec", 25)
            if (sp.getInt("cooldown_sec", 300) == 300) e.putInt("cooldown_sec", 120)
            e.putInt("defaults_v", 2).apply()
        }
    }

    private fun bool(key: String, def: Boolean) = object : kotlin.properties.ReadWriteProperty<Any?, Boolean> {
        override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>) = sp.getBoolean(key, def)
        override fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: Boolean) =
            sp.edit().putBoolean(key, value).apply()
    }

    private fun int(key: String, def: Int) = object : kotlin.properties.ReadWriteProperty<Any?, Int> {
        override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>) = sp.getInt(key, def)
        override fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: Int) =
            sp.edit().putInt(key, value).apply()
    }
}
