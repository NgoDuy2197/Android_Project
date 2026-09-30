package com.dsoft.volteguard

import android.content.Context
import android.telephony.SubscriptionManager
import android.util.Base64

/**
 * Shell-uid watchdog daemon started through Shizuku. It is a plain `sh` loop detached from
 * the Shizuku server, so it keeps running when ColorOS kills (swipe from recents) or freezes
 * (OplusHansManager) the app process. Each round it:
 *  1. restarts GuardService if the app process / service is gone, otherwise kicks it
 *     (a start-service also un-freezes a frozen app);
 *  2. if IMS stays down for [DIRECT_FIX_AFTER] rounds (app could not fix it), flips the VoLTE
 *     switch itself via `service call phone`, with exponential backoff.
 * Dies with Shizuku (i.e. on reboot); the app restarts it whenever the service starts.
 */
object Watchdog {
    private const val DIR = "/data/local/tmp"
    private const val SCRIPT = "$DIR/volteguard_wd.sh"
    private const val PIDFILE = "$DIR/volteguard_wd.pid"
    private const val DIRECT_FIX_AFTER = 4

    fun isRunning(): Boolean =
        ShizukuShell.exec("p=\$(cat $PIDFILE 2>/dev/null) && [ -n \"\$p\" ] && kill -0 \$p 2>/dev/null && echo yes").out.contains("yes")

    /** (Re)start the daemon with the current settings. No-op without Shizuku. */
    fun start(ctx: Context): String {
        if (!ShizukuShell.hasPermission()) return "không có Shizuku"
        val p = Prefs(ctx)
        val sub = runCatching {
            ctx.getSystemService(SubscriptionManager::class.java)
                .getActiveSubscriptionInfoForSimSlotIndex(p.simSlot)?.subscriptionId
        }.getOrNull() ?: -1
        val reg = PhoneHidden.transactionCode("isImsRegistered") ?: -1
        val set = PhoneHidden.transactionCode("setAdvancedCallingSettingEnabled") ?: -1
        val interval = p.intervalSec.coerceIn(10, 300)
        val script = script(ctx.packageName, sub, reg, set, interval)
        val b64 = Base64.encodeToString(script.toByteArray(), Base64.NO_WRAP)
        val write = ShizukuShell.exec("echo $b64 | base64 -d > $SCRIPT && chmod 755 $SCRIPT")
        if (!write.ok) return "ghi script lỗi [$write]"
        // setsid + background + closed fds: survives the sh that Shizuku tracks and kills.
        val run = ShizukuShell.exec("setsid sh $SCRIPT </dev/null >/dev/null 2>&1 &")
        return if (run.ok) "OK (sub=$sub, ${interval}s)" else "start lỗi [$run]"
    }

    fun stop(): String {
        if (!ShizukuShell.hasPermission()) return "không có Shizuku"
        // The loop exits on its own once the pidfile no longer holds its pid.
        return ShizukuShell.exec("p=\$(cat $PIDFILE 2>/dev/null); rm -f $PIDFILE; [ -n \"\$p\" ] && kill \$p 2>/dev/null; echo stopped").out.trim()
    }

    private fun script(pkg: String, sub: Int, reg: Int, set: Int, interval: Int) = """
        |#!/system/bin/sh
        |PKG=$pkg
        |SVC=${'$'}PKG/.GuardService
        |SUB=$sub; REG=$reg; SET=$set; INT=$interval
        |old=${'$'}(cat $PIDFILE 2>/dev/null); [ -n "${'$'}old" ] && kill ${'$'}old 2>/dev/null
        |echo ${'$'}${'$'} > $PIDFILE
        |log -t VoLTEGuardWD "start pid=${'$'}${'$'} sub=${'$'}SUB int=${'$'}INT"
        |fails=0; backoff=30
        |while [ "${'$'}(cat $PIDFILE 2>/dev/null)" = "${'$'}${'$'}" ]; do
        |  if [ -z "${'$'}(pidof ${'$'}PKG)" ]; then
        |    log -t VoLTEGuardWD "app dead -> start service"
        |    am start-foreground-service -n ${'$'}SVC -a $pkg.WATCHDOG >/dev/null 2>&1
        |  else
        |    am start-foreground-service -n ${'$'}SVC -a $pkg.WATCHDOG >/dev/null 2>&1
        |  fi
        |  if [ ${'$'}REG -gt 0 ] && [ ${'$'}SUB -ge 0 ]; then
        |    if service call phone ${'$'}REG i32 ${'$'}SUB | grep -q "00000000 00000001"; then
        |      fails=0; backoff=30
        |    else
        |      fails=${'$'}((fails+1))
        |    fi
        |    if [ ${'$'}fails -ge $DIRECT_FIX_AFTER ] && [ ${'$'}SET -gt 0 ]; then
        |      log -t VoLTEGuardWD "IMS down x${'$'}fails -> flip VoLTE switch (backoff ${'$'}backoff)"
        |      service call phone ${'$'}SET i32 ${'$'}SUB i32 0 >/dev/null; sleep 2
        |      service call phone ${'$'}SET i32 ${'$'}SUB i32 1 >/dev/null
        |      fails=0; sleep ${'$'}backoff
        |      backoff=${'$'}((backoff*2)); [ ${'$'}backoff -gt 600 ] && backoff=600
        |    fi
        |  fi
        |  sleep ${'$'}INT
        |done
        |log -t VoLTEGuardWD "exit pid=${'$'}${'$'}"
        |""".trimMargin()
}
