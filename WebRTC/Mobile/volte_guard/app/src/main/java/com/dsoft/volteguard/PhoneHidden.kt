package com.dsoft.volteguard

import android.os.PersistableBundle
import android.telephony.TelephonyManager

/**
 * Hidden telephony calls. Primary path: ITelephony through Shizuku (runs as shell,
 * which holds READ_PRIVILEGED_PHONE_STATE / MODIFY_PHONE_STATE).
 * Fallback: the hidden TelephonyManager method in our own process (usually SecurityException).
 */
object PhoneHidden {
    private const val ITELEPHONY = "com.android.internal.telephony.ITelephony\$Stub"
    private const val ICARRIER = "com.android.internal.telephony.ICarrierConfigLoader\$Stub"

    // ImsRegistrationImplBase.REGISTRATION_TECH_*
    fun regTechName(t: Int) = when (t) {
        -1 -> "NONE"; 0 -> "LTE"; 1 -> "IWLAN (VoWiFi)"; 2 -> "CROSS_SIM"; 3 -> "NR (VoNR)"; else -> "tech=$t"
    }

    /** Result of a hidden call: value or error text. */
    data class R<T>(val value: T?, val via: String, val error: String? = null)

    private fun itel(): Any? = ShizukuShell.binder("phone")?.let { ShizukuShell.asInterface(ITELEPHONY, it) }

    private inline fun <T> viaShizuku(block: (Any) -> T): R<T>? {
        val t = itel() ?: return null
        return try {
            R(block(t), "shizuku")
        } catch (e: Throwable) {
            R(null, "shizuku", "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private inline fun <T> viaLocal(block: () -> T): R<T> = try {
        R(block(), "local")
    } catch (e: Throwable) {
        val c = if (e is java.lang.reflect.InvocationTargetException) e.targetException else e
        R(null, "local", "${c.javaClass.simpleName}: ${c.message}")
    }

    private fun <T> firstOk(vararg rs: R<T>?): R<T> {
        val list = rs.filterNotNull()
        return list.firstOrNull { it.value != null } ?: list.lastOrNull() ?: R(null, "none", "no path")
    }

    fun isImsRegistered(tm: TelephonyManager, subId: Int): R<Boolean> {
        val s = viaShizuku { ShizukuShell.call(it, "isImsRegistered", subId) as Boolean }
        if (s?.value != null) return s
        val l = viaLocal { tm.javaClass.getMethod("isImsRegistered", Int::class.javaPrimitiveType).invoke(tm, subId) as Boolean }
        return firstOk(s, l)
    }

    /** MMTEL voice capability available over IMS (the "HD"/VoLTE icon condition). */
    fun isVolteAvailable(tm: TelephonyManager, subId: Int, regTech: Int?): R<Boolean> {
        val s = viaShizuku {
            try {
                ShizukuShell.call(it, "isVolteAvailable", subId) as Boolean
            } catch (e: NoSuchMethodException) {
                // isAvailable(subId, CAPABILITY_TYPE_VOICE=1, regTech)
                ShizukuShell.call(it, "isAvailable", subId, 1, regTech ?: 0) as Boolean
            }
        }
        if (s?.value != null) return s
        val l = viaLocal { tm.javaClass.getMethod("isVolteAvailable").invoke(tm) as Boolean }
        return firstOk(s, l)
    }

    fun imsRegTech(subId: Int): R<Int>? = viaShizuku { ShizukuShell.call(it, "getImsRegTechnologyForMmTel", subId) as Int }

    /** The "VoLTE / 4G Calling" user toggle. */
    fun isAdvancedCallingEnabled(subId: Int): R<Boolean>? = viaShizuku {
        ShizukuShell.call(it, "isAdvancedCallingSettingEnabled", subId) as Boolean
    }

    fun setAdvancedCallingEnabled(subId: Int, on: Boolean): R<Unit>? = viaShizuku {
        ShizukuShell.call(it, "setAdvancedCallingSettingEnabled", subId, on); Unit
    }

    /** Tear down and re-bind the IMS service of the slot (forces re-registration). */
    fun resetIms(slot: Int): R<Unit>? = viaShizuku { ShizukuShell.call(it, "resetIms", slot); Unit }

    fun rebootModem(slot: Int): R<Boolean>? = viaShizuku { ShizukuShell.call(it, "rebootModem", slot) as Boolean }

    /**
     * Re-apply the VoLTE carrier-config override (what Pixel IMS does). Overrides can be
     * dropped after reboot / SIM refresh / carrier config update, which removes the HD icon.
     */
    fun overrideVolteConfig(subId: Int): R<Unit>? {
        val b = ShizukuShell.binder("carrier_config")?.let { ShizukuShell.asInterface(ICARRIER, it) } ?: return null
        val bundle = PersistableBundle().apply {
            putBoolean("carrier_volte_available_bool", true)
            putBoolean("editable_enhanced_4g_lte_bool", true)
            putBoolean("hide_enhanced_4g_lte_bool", false)
        }
        return try {
            try {
                ShizukuShell.call(b, "overrideConfig", subId, bundle, true)
            } catch (e: NoSuchMethodException) {
                ShizukuShell.call(b, "overrideConfig", subId, bundle)
            }
            R(Unit, "shizuku")
        } catch (e: Throwable) {
            R(null, "shizuku", "${e.javaClass.simpleName}: ${e.message}")
        }
    }
}
