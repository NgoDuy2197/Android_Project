package com.dsoft.volteguard

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Shared state + check/recover entry points used by both the service and the UI. */
object Engine {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _report = MutableStateFlow<CheckReport?>(null)
    val report: StateFlow<CheckReport?> = _report

    private val _phase = MutableStateFlow<String?>(null)
    /** Non-null while a recovery is running. */
    val phase: StateFlow<String?> = _phase

    private val _running = MutableStateFlow(false)
    val serviceRunning: StateFlow<Boolean> = _running

    private val recoverLock = Mutex()
    @Volatile var lastRecoveryAt = 0L
        private set
    private var failStreak = 0
    private var lastHealth: Health? = null

    val isRecovering get() = recoverLock.isLocked

    internal fun setServiceRunning(v: Boolean) { _running.value = v }

    internal fun publish(r: CheckReport) { _report.value = r }

    suspend fun check(ctx: Context): CheckReport = withContext(Dispatchers.IO) {
        val p = Prefs(ctx)
        val r = ImsProbe.probe(ctx, p.simSlot, p)
        publish(r)
        if (r.health != lastHealth) {
            LogStore.i("Trạng thái SIM ${r.slot + 1}: ${lastHealth ?: "-"} → ${r.health} · ${r.summary}")
            if (lastHealth == Health.HEALTHY && r.health == Health.UNHEALTHY) notifyEvent(ctx, "Mất HD Call", r.summary)
            lastHealth = r.health
        }
        r
    }

    /**
     * Periodic tick from the service: check, count consecutive failures, and start recovery
     * when [Prefs.confirmCount] is reached and the cooldown has elapsed.
     * @return true if the caller should re-check soon (unconfirmed failure).
     */
    suspend fun tick(ctx: Context): Boolean {
        if (isRecovering) return false
        val p = Prefs(ctx)
        val r = check(ctx)
        if (r.health != Health.UNHEALTHY) {
            failStreak = 0
            return false
        }
        failStreak++
        if (failStreak < p.confirmCount) {
            LogStore.i("Phát hiện lỗi (${failStreak}/${p.confirmCount}), chờ xác nhận…")
            return true
        }
        val since = (System.currentTimeMillis() - lastRecoveryAt) / 1000
        if (since < p.cooldownSec) {
            LogStore.i("Đang cooldown (${p.cooldownSec - since}s nữa mới khắc phục lại)")
            return false
        }
        recover(ctx, r, manual = false)
        return false
    }

    suspend fun recover(ctx: Context, report: CheckReport? = null, manual: Boolean): Boolean {
        if (!recoverLock.tryLock()) {
            LogStore.i("Đang khắc phục rồi, bỏ qua yêu cầu mới")
            return false
        }
        try {
            lastRecoveryAt = System.currentTimeMillis()
            val r = report ?: check(ctx)
            if (r.blockReason != null) {
                LogStore.i("Không khắc phục: ${r.blockReason}")
                return false
            }
            if (!manual && r.health != Health.UNHEALTHY) return true
            _phase.value = "Bắt đầu khắc phục…"
            val ok = Recovery.run(ctx, r) { _phase.value = it }
            failStreak = 0
            lastHealth = if (ok) Health.HEALTHY else lastHealth
            notifyEvent(
                ctx,
                if (ok) "Đã khôi phục HD Call" else "Chưa khôi phục được HD Call",
                _report.value?.summary ?: ""
            )
            return ok
        } finally {
            _phase.value = null
            recoverLock.unlock()
        }
    }

    private fun notifyEvent(ctx: Context, title: String, text: String) {
        if (!Prefs(ctx).notifyEvents) return
        val pi = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(ctx, App.CH_EVENTS)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        runCatching { ctx.getSystemService(NotificationManager::class.java).notify(2, n) }
    }
}
