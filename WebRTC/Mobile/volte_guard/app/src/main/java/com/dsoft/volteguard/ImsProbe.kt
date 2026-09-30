package com.dsoft.volteguard

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.telephony.CarrierConfigManager
import android.telephony.NetworkRegistrationInfo
import android.telephony.ServiceState
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.telephony.AccessNetworkConstants

const val BLOCK_IN_CALL = "Đang trong cuộc gọi"

enum class St { PASS, FAIL, WARN, UNKNOWN, INFO }

enum class Health { HEALTHY, UNHEALTHY, UNKNOWN, NO_SERVICE }

data class CheckItem(
    val key: String,
    val title: String,
    val status: St,
    val detail: String,
    /** Required checks decide health; the others are informational / preconditions. */
    val required: Boolean = false,
)

data class CheckReport(
    val time: Long,
    val slot: Int,
    val subId: Int,
    val items: List<CheckItem>,
    val health: Health,
    val summary: String,
    /** Null when recovery may run; otherwise why it must not. */
    val blockReason: String?,
) {
    fun item(key: String) = items.firstOrNull { it.key == key }
    fun failed(key: String) = item(key)?.status == St.FAIL
    fun failedKeys() = items.filter { it.required && it.status == St.FAIL }.map { it.key }
}

/**
 * Runs every check for one SIM slot. Blocking (binder + reflection), call off the main thread.
 *
 * Required checks (all must PASS):
 *  - carrier_cfg : carrier config says VoLTE available (Pixel IMS override still in place)
 *  - volte_switch: "VoLTE / 4G calling" user toggle is on             (Shizuku)
 *  - ims_reg     : ITelephony.isImsRegistered(subId)                  (Shizuku / hidden)
 *  - volte_avail : MMTEL voice capability available (HD icon)         (Shizuku / hidden)
 * Preconditions (block recovery): SIM active, radio on, not airplane, LTE/NR registered, not in call.
 */
object ImsProbe {
    const val K_CARRIER = "carrier_cfg"
    const val K_SWITCH = "volte_switch"
    const val K_IMS = "ims_reg"
    const val K_VOLTE = "volte_avail"
    const val K_HEUR = "heuristic"

    fun probe(ctx: Context, slot: Int, prefs: Prefs = Prefs(ctx)): CheckReport {
        val items = mutableListOf<CheckItem>()
        val now = System.currentTimeMillis()
        fun done(health: Health, summary: String, block: String?, subId: Int = -1) =
            CheckReport(now, slot, subId, items.toList(), health, summary, block)

        items += CheckItem(
            "shizuku", "Shizuku",
            if (ShizukuShell.hasPermission()) St.PASS else St.WARN,
            ShizukuShell.describe() + if (!ShizukuShell.hasPermission()) " → chỉ còn kiểm tra heuristic" else ""
        )

        if (ShizukuShell.hasPermission()) {
            val wd = Watchdog.isRunning()
            items += CheckItem("watchdog", "Watchdog (shell)", if (wd) St.PASS else St.WARN,
                if (wd) "Đang chạy · tự hồi sinh app khi bị tắt" else "Chưa chạy")
        }

        if (ctx.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            items += CheckItem("perm", "Quyền READ_PHONE_STATE", St.FAIL, "Chưa cấp quyền đọc trạng thái điện thoại")
            return done(Health.UNKNOWN, "Thiếu quyền READ_PHONE_STATE", "Thiếu quyền")
        }

        val sm = ctx.getSystemService(SubscriptionManager::class.java)
        val info = runCatching { sm.getActiveSubscriptionInfoForSimSlotIndex(slot) }.getOrNull()
        if (info == null) {
            items += CheckItem("sim", "SIM ${slot + 1}", St.FAIL, "Không có SIM hoạt động ở khe này")
            return done(Health.NO_SERVICE, "Không có SIM ${slot + 1}", "Không có SIM")
        }
        val subId = info.subscriptionId
        val tm = ctx.getSystemService(TelephonyManager::class.java).createForSubscriptionId(subId)
        val simReady = runCatching { tm.simState }.getOrDefault(TelephonyManager.SIM_STATE_UNKNOWN) == TelephonyManager.SIM_STATE_READY
        items += CheckItem(
            "sim", "SIM ${slot + 1}", if (simReady) St.PASS else St.FAIL,
            "${info.carrierName ?: info.displayName} · subId=$subId" + if (!simReady) " · SIM chưa READY" else ""
        )
        if (!simReady) return done(Health.NO_SERVICE, "SIM chưa sẵn sàng", "SIM chưa sẵn sàng", subId)

        // ---- Radio / coverage -------------------------------------------------------------
        val airplane = Settings.Global.getInt(ctx.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
        val ss: ServiceState? = runCatching { tm.serviceState }.getOrNull()
        val nris = ss?.networkRegistrationInfoList.orEmpty()
            .filter { it.transportType == AccessNetworkConstants.TRANSPORT_TYPE_WWAN }
        val ps = nris.firstOrNull { it.domain and NetworkRegistrationInfo.DOMAIN_PS != 0 }
        val cs = nris.firstOrNull { it.domain and NetworkRegistrationInfo.DOMAIN_CS != 0 }
        val psReg = ps?.isRegistered == true
        val psRat = ps?.accessNetworkTechnology ?: TelephonyManager.NETWORK_TYPE_UNKNOWN
        val voiceRat = runCatching { tm.voiceNetworkType }.getOrDefault(TelephonyManager.NETWORK_TYPE_UNKNOWN)
        val dataRat = runCatching { tm.dataNetworkType }.getOrDefault(TelephonyManager.NETWORK_TYPE_UNKNOWN)

        val radioSt = when {
            airplane -> St.FAIL
            ss == null -> St.UNKNOWN
            ss.state == ServiceState.STATE_POWER_OFF -> St.FAIL
            !psReg -> St.FAIL
            else -> St.PASS
        }
        items += CheckItem(
            "radio", "Sóng di động", radioSt,
            when {
                airplane -> "Đang bật chế độ máy bay"
                ss == null -> "Không đọc được ServiceState"
                ss.state == ServiceState.STATE_POWER_OFF -> "Radio đang tắt"
                else -> "PS: ${if (psReg) "đã đăng ký" else "CHƯA đăng ký"} ${ratName(psRat)} · " +
                    "CS: ${if (cs?.isRegistered == true) "có (${ratName(cs.accessNetworkTechnology)})" else "không"} · " +
                    "${ss.operatorAlphaShort ?: ""}"
            }
        )
        items += CheckItem(
            "rat", "Loại mạng", St.INFO,
            "Data: ${ratName(dataRat)} · Voice: ${ratName(voiceRat)}"
        )

        val callState = runCatching {
            if (Build.VERSION.SDK_INT >= 31) tm.callStateForSubscription else @Suppress("DEPRECATION") tm.callState
        }.getOrDefault(TelephonyManager.CALL_STATE_IDLE)
        val inCall = callState != TelephonyManager.CALL_STATE_IDLE

        // ---- Carrier config (Pixel IMS override) -----------------------------------------
        val cc = runCatching {
            ctx.getSystemService(CarrierConfigManager::class.java).getConfigForSubId(subId)
        }.getOrNull()
        val volteCfg = cc?.getBoolean(CarrierConfigManager.KEY_CARRIER_VOLTE_AVAILABLE_BOOL)
        items += CheckItem(
            K_CARRIER, "Carrier config VoLTE", tri(volteCfg),
            when (volteCfg) {
                true -> "carrier_volte_available_bool = true"
                false -> "= false → override Pixel IMS đã bị mất"
                null -> "Không đọc được carrier config"
            },
            required = true
        )

        // ---- User VoLTE switch -----------------------------------------------------------
        val sw = PhoneHidden.isAdvancedCallingEnabled(subId)
        items += CheckItem(
            K_SWITCH, "Công tắc VoLTE / 4G Calling", tri(sw?.value),
            sw?.let { it.error ?: if (it.value == true) "Đang bật" else "Đang TẮT" } ?: "Cần Shizuku",
            required = sw?.value != null
        )

        // ---- IMS registration ------------------------------------------------------------
        val regTech = PhoneHidden.imsRegTech(subId)?.value
        val ims = PhoneHidden.isImsRegistered(tm, subId)
        items += CheckItem(
            K_IMS, "IMS registered", tri(ims.value),
            (ims.error ?: if (ims.value == true) "Đã đăng ký IMS" else "MẤT đăng ký IMS") +
                " [${ims.via}]" + (regTech?.let { " · ${PhoneHidden.regTechName(it)}" } ?: ""),
            // Unreadable checks are informational; at least one of ims/volte must be known (hiddenKnown).
            required = ims.value != null
        )

        val volte = PhoneHidden.isVolteAvailable(tm, subId, regTech)
        items += CheckItem(
            K_VOLTE, "VoLTE (HD) khả dụng", tri(volte.value),
            (volte.error ?: if (volte.value == true) "MMTEL voice sẵn sàng" else "Không có VoLTE") + " [${volte.via}]",
            required = volte.value != null
        )

        // ---- Heuristic when hidden APIs are unavailable ----------------------------------
        val hiddenKnown = ims.value != null || volte.value != null
        if (!hiddenKnown) {
            val ok = voiceRat in VOICE_OVER_PS_RATS && !(cs?.isRegistered == true && cs.accessNetworkTechnology !in VOICE_OVER_PS_RATS)
            items += CheckItem(
                K_HEUR, "Heuristic (Voice RAT)", if (ok) St.PASS else St.WARN,
                "Voice RAT=${ratName(voiceRat)} → ${if (ok) "có vẻ OK" else "có thể mất VoLTE"}" +
                    if (prefs.heuristicTrigger) " (được phép kích hoạt khắc phục)" else " (chỉ tham khảo)",
                required = prefs.heuristicTrigger
            )
        }

        // ---- Verdict ---------------------------------------------------------------------
        val block = when {
            airplane -> "Chế độ máy bay đang bật"
            ss?.state == ServiceState.STATE_POWER_OFF -> "Radio đang tắt"
            !psReg -> "Không có sóng LTE/NR (vùng mất sóng)"
            inCall -> BLOCK_IN_CALL
            else -> null
        }
        val required = items.filter { it.required }
        val health = when {
            radioSt == St.FAIL -> Health.NO_SERVICE
            required.any { it.status == St.FAIL || it.status == St.WARN } -> Health.UNHEALTHY
            hiddenKnown && required.all { it.status == St.PASS } -> Health.HEALTHY
            else -> Health.UNKNOWN
        }
        val summary = when (health) {
            Health.HEALTHY -> "HD Call OK · ${ratName(dataRat)}" + (regTech?.let { " · IMS ${PhoneHidden.regTechName(it)}" } ?: "")
            Health.UNHEALTHY -> "Mất HD: " + required.filter { it.status != St.PASS }.joinToString { it.title }
            Health.NO_SERVICE -> block ?: "Không có dịch vụ"
            Health.UNKNOWN -> "Không xác định (cần Shizuku)"
        }
        return done(health, summary, block, subId)
    }

    private val VOICE_OVER_PS_RATS = setOf(
        TelephonyManager.NETWORK_TYPE_LTE, TelephonyManager.NETWORK_TYPE_NR, TelephonyManager.NETWORK_TYPE_IWLAN
    )

    private fun tri(b: Boolean?) = when (b) { true -> St.PASS; false -> St.FAIL; null -> St.UNKNOWN }

    fun ratName(t: Int) = when (t) {
        TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
        TelephonyManager.NETWORK_TYPE_NR -> "NR/5G"
        TelephonyManager.NETWORK_TYPE_IWLAN -> "IWLAN"
        TelephonyManager.NETWORK_TYPE_UMTS, TelephonyManager.NETWORK_TYPE_HSPA,
        TelephonyManager.NETWORK_TYPE_HSPAP, TelephonyManager.NETWORK_TYPE_HSDPA,
        TelephonyManager.NETWORK_TYPE_HSUPA -> "3G"
        TelephonyManager.NETWORK_TYPE_GSM, TelephonyManager.NETWORK_TYPE_EDGE,
        TelephonyManager.NETWORK_TYPE_GPRS -> "2G"
        TelephonyManager.NETWORK_TYPE_UNKNOWN -> "—"
        else -> "type$t"
    }
}
