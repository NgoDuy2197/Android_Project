package com.dsoft.volteguard

import android.content.Context
import android.content.SharedPreferences

/** All user settings. Times are in seconds. */
class Prefs(ctx: Context) {
    private val sp: SharedPreferences = ctx.getSharedPreferences("guard", Context.MODE_PRIVATE)

    var enabled by bool("enabled", false)
    /** 0 = SIM 1, 1 = SIM 2. */
    var simSlot by int("sim_slot", 0)
    var intervalSec by int("interval_sec", 60)
    /** Consecutive failed checks required before recovery kicks in (filters short handovers). */
    var confirmCount by int("confirm_count", 2)
    /** Wait after each recovery step before re-checking. */
    var settleSec by int("settle_sec", 30)
    /** Minimum time between two recovery runs. */
    var cooldownSec by int("cooldown_sec", 300)

    var stepCarrierConfig by bool("step_carrier_cfg", true)
    var stepVolteSwitch by bool("step_volte_switch", true)
    var stepResetIms by bool("step_reset_ims", true)
    var stepData by bool("step_data", true)
    var stepAirplane by bool("step_airplane", true)
    var stepModem by bool("step_modem", false)

    /** Without Shizuku only a heuristic is available; allow it to trigger recovery? */
    var heuristicTrigger by bool("heuristic_trigger", false)
    var notifyEvents by bool("notify_events", true)

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
