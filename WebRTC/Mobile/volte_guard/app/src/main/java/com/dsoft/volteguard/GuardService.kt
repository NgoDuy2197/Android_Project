package com.dsoft.volteguard

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.telephony.ServiceState
import android.telephony.SubscriptionManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Foreground monitor. Wakes on:
 *  - its own interval (coroutine delay, and an exact alarm so Doze can't starve it)
 *  - ServiceState / IMS-related changes on the watched SIM (TelephonyCallback)
 */
class GuardService : Service() {

    private val kick = Channel<String>(Channel.CONFLATED)
    private var loopJob: Job? = null
    private var uiJob: Job? = null
    private var tmCallback: TelephonyCallback? = null
    private var watchedSub = SubscriptionManager.INVALID_SUBSCRIPTION_ID
    private var watchdogSeen = false
    private lateinit var wake: PowerManager.WakeLock

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wake = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VoLTEGuard:check")
        startFg(buildNotification(null, null))
        Engine.setServiceRunning(true)
        LogStore.i("Service bắt đầu giám sát SIM ${Prefs(this).simSlot + 1}")

        loopJob = Engine.scope.launch { loop() }
        Engine.scope.launch { LogStore.i("Watchdog (shell): ${Watchdog.start(this@GuardService)}") }
        uiJob = Engine.scope.launch {
            Engine.report.combine(Engine.phase) { r, ph -> r to ph }.collect { (r, ph) ->
                runCatching {
                    getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(r, ph))
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Prefs(this).enabled && intent?.action != ACTION_RECOVER) {
            // Started by the watchdog / alarm after the user turned monitoring off.
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_WATCHDOG && !watchdogSeen) {
            watchdogSeen = true
            LogStore.i("Được watchdog đánh thức / khởi động lại")
        }
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RECOVER -> Engine.scope.launch { withWake { Engine.recover(this@GuardService, manual = true) } }
            else -> kick.trySend(intent?.action ?: "start")
        }
        return START_STICKY
    }

    override fun onDestroy() {
        loopJob?.cancel()
        uiJob?.cancel()
        unregisterTelephony()
        cancelAlarm(this)
        Engine.setServiceRunning(false)
        if (wake.isHeld) wake.release()
        LogStore.i("Service dừng")
        super.onDestroy()
    }

    private suspend fun loop() {
        while (true) {
            val p = Prefs(this)
            ensureTelephonyCallback(p.simSlot)
            val recheckSoon = withWake { runCatching { Engine.tick(this) }.getOrElse {
                LogStore.i("tick lỗi: ${it.javaClass.simpleName}: ${it.message}"); false
            } }
            val waitMs = if (recheckSoon) FAIL_RECHECK_MS else p.intervalSec.coerceAtLeast(10) * 1000L
            scheduleAlarm(this, waitMs)
            // Kicks queued while ticking are stale; without this an unconfirmed failure is
            // re-checked immediately and the confirm-count window is skipped.
            while (kick.tryReceive().isSuccess) Unit
            val reason = withTimeoutOrNull(waitMs) { kick.receive() }
            if (reason != null && reason != ACTION_ALARM && reason != ACTION_WATCHDOG) {
                // Event-driven: let the radio settle a bit before probing.
                delay(EVENT_DEBOUNCE_MS)
                while (kick.tryReceive().isSuccess) Unit
            }
        }
    }

    private suspend fun <T> withWake(block: suspend () -> T): T {
        wake.acquire(10 * 60 * 1000L)
        try {
            return block()
        } finally {
            if (wake.isHeld) wake.release()
        }
    }

    private fun ensureTelephonyCallback(slot: Int) {
        val sub = runCatching {
            getSystemService(SubscriptionManager::class.java).getActiveSubscriptionInfoForSimSlotIndex(slot)?.subscriptionId
        }.getOrNull() ?: SubscriptionManager.INVALID_SUBSCRIPTION_ID
        if (sub == watchedSub && tmCallback != null) return
        unregisterTelephony()
        if (sub == SubscriptionManager.INVALID_SUBSCRIPTION_ID) return
        val tm = getSystemService(TelephonyManager::class.java).createForSubscriptionId(sub)
        val cb = Listener(tm)
        runCatching {
            tm.registerTelephonyCallback(mainExecutor, cb)
            tmCallback = cb
            watchedSub = sub
            LogStore.i("Lắng nghe ServiceState cho subId=$sub")
        }.onFailure { LogStore.i("registerTelephonyCallback lỗi: ${it.message}") }
    }

    private fun unregisterTelephony() {
        tmCallback?.let { cb ->
            runCatching { getSystemService(TelephonyManager::class.java).unregisterTelephonyCallback(cb) }
        }
        tmCallback = null
        watchedSub = SubscriptionManager.INVALID_SUBSCRIPTION_ID
    }

    private inner class Listener(val tm: TelephonyManager) : TelephonyCallback(),
        TelephonyCallback.ServiceStateListener,
        TelephonyCallback.DisplayInfoListener {
        private var lastSig: String? = null

        override fun onServiceStateChanged(ss: ServiceState) {
            val sig = "${ss.state}/${runCatching { tm.voiceNetworkType }.getOrNull()}/${runCatching { tm.dataNetworkType }.getOrNull()}"
            if (sig != lastSig) {
                lastSig = sig
                if (!Engine.isRecovering) kick.trySend("service_state")
            }
        }

        override fun onDisplayInfoChanged(info: android.telephony.TelephonyDisplayInfo) {
            if (!Engine.isRecovering) kick.trySend("display_info")
        }
    }

    // ---- Notification --------------------------------------------------------------------

    private fun startFg(n: Notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun buildNotification(r: CheckReport?, phase: String?): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val recover = PendingIntent.getService(
            this, 1, Intent(this, GuardService::class.java).setAction(ACTION_RECOVER), PendingIntent.FLAG_IMMUTABLE
        )
        val title = when {
            phase != null -> "Đang khắc phục…"
            r == null -> "Đang khởi động giám sát"
            r.health == Health.HEALTHY -> "✅ HD Call OK · SIM ${r.slot + 1}"
            r.health == Health.UNHEALTHY -> "⚠️ Mất HD Call · SIM ${r.slot + 1}"
            r.health == Health.NO_SERVICE -> "📵 Không có dịch vụ · SIM ${r.slot + 1}"
            else -> "❔ Chưa xác định · SIM ${r.slot + 1}"
        }
        return NotificationCompat.Builder(this, App.CH_STATUS)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(phase ?: r?.summary ?: "")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "Khắc phục ngay", recover)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val NOTIF_ID = 1
        private const val FAIL_RECHECK_MS = 5_000L
        private const val EVENT_DEBOUNCE_MS = 3_000L
        const val ACTION_ALARM = "com.dsoft.volteguard.ALARM"
        const val ACTION_STOP = "com.dsoft.volteguard.STOP"
        const val ACTION_RECOVER = "com.dsoft.volteguard.RECOVER"
        const val ACTION_KICK = "com.dsoft.volteguard.KICK"
        const val ACTION_WATCHDOG = "com.dsoft.volteguard.WATCHDOG"

        fun start(ctx: Context, action: String? = null) {
            ctx.startForegroundService(Intent(ctx, GuardService::class.java).setAction(action))
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, GuardService::class.java).setAction(ACTION_STOP))
        }

        private fun alarmPi(ctx: Context) = PendingIntent.getBroadcast(
            ctx, 0, Intent(ctx, AlarmReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        /**
         * Exact alarm slightly after [inMs]. Besides Doze, this is what un-freezes the process on
         * ColorOS (OplusHansManager freezes background apps even with a foreground service).
         */
        fun scheduleAlarm(ctx: Context, inMs: Long, padMs: Long = 5_000) {
            val am = ctx.getSystemService(AlarmManager::class.java)
            val at = SystemClock.elapsedRealtime() + inMs + padMs
            runCatching {
                if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
                    am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, alarmPi(ctx))
                } else {
                    am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, alarmPi(ctx))
                }
            }
        }

        fun cancelAlarm(ctx: Context) {
            runCatching { ctx.getSystemService(AlarmManager::class.java).cancel(alarmPi(ctx)) }
        }
    }
}
