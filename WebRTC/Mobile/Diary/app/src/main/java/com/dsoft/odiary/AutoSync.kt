package com.dsoft.odiary

import android.content.Context
import android.widget.Toast

/**
 * Background auto-sync to Drive. Driven by a DURABLE pending flag ([Prefs.pendingSync]) so a change
 * is retried across timeouts, crashes, network loss and app restarts:
 *  - any data change sets pendingSync = true (persisted);
 *  - maybeSync() (called on screen resume/pause and when the network returns) pushes to Drive;
 *  - the flag is cleared only after a successful sync; on failure it stays set and retries later.
 * Everything is wrapped so a sync failure can never crash the app; errors surface as a toast + the
 * "Lần đồng bộ cuối" line in Settings.
 */
object AutoSync {
    @Volatile private var running = false
    @Volatile private var lastAttemptAt = 0L
    @Volatile private var lastErrorToastAt = 0L
    private const val COOLDOWN_MS = 15_000L   // don't hammer retries

    fun enabledAndReady(ctx: Context): Boolean = try {
        Prefs.autoSync(ctx) && Drive.signedIn(ctx) &&
            (Accounts.get(Prefs.currentAccount(ctx))?.isGoogle == true)
    } catch (_: Exception) { false }

    /** Sync if auto-sync is on and there is pending work (queued changes, or a pending pull). */
    fun maybeSync(ctx: Context) {
        try {
            if (running) return
            val pull = Prefs.pendingPull(ctx)
            if (!pull && !Prefs.pendingSync(ctx)) return
            if (!enabledAndReady(ctx)) return
            if (System.currentTimeMillis() - lastAttemptAt < COOLDOWN_MS) return
            start(ctx.applicationContext, pull)
        } catch (_: Exception) { running = false }
    }

    private fun start(app: Context, pull: Boolean) {
        running = true
        lastAttemptAt = System.currentTimeMillis()
        Prefs.setPendingSync(app, false)   // claim queued changes; a download-merge during sync may re-set it
        Thread {
            try {
                Drive.sync(app, pull = pull, log = { m -> runCatching { Prefs.setLastSync(app, "$m (tự động ${now()})") } })
                if (pull) Prefs.setPendingPull(app, false)   // pulled successfully
            } catch (e: Drive.RecoverableException) {
                Prefs.setPendingSync(app, true)   // needs consent; keep for a manual sync to prompt + later retry
                runCatching { Prefs.setLastSync(app, "⚠ Cần cấp lại quyền Google (${now()})") }
            } catch (e: Exception) {
                Prefs.setPendingSync(app, true)   // keep pending so it retries next launch / reconnect
                runCatching { Prefs.setLastSync(app, "⚠ Lỗi tự động: ${e.message} (${now()})") }
                reportError(app, e.message)
            } finally { running = false }
        }.start()
    }

    private fun now() = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date())

    private fun reportError(ctx: Context, msg: String?) {
        val t = System.currentTimeMillis()
        if (t - lastErrorToastAt < 30_000) return   // don't spam the same failure
        lastErrorToastAt = t
        runCatching { App.main.post { Toast.makeText(ctx, "Tự đồng bộ lỗi: ${msg ?: "không rõ"}", Toast.LENGTH_LONG).show() } }
    }
}
