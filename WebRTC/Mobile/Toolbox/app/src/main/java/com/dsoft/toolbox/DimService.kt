package com.dsoft.toolbox

import android.app.Notification
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.service.quicksettings.TileService

/** Keeps the dim layer alive (foreground) and owns its notification controls. */
class DimService : Service() {
    private val overlay by lazy { DimOverlay(this, DimOverlay.appType()) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val p = Prefs(this)
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            ACTION_UP -> p.dimLevel = p.dimLevel + STEP
            ACTION_DOWN -> p.dimLevel = p.dimLevel - STEP
        }
        startFg(p)
        val acc = DimAccessibility.instance
        if (acc == null && !canOverlay(this)) { stopSelf(); return START_NOT_STICKY }
        if (!active) {
            active = true
            if (p.dimLowBrightness) Brightness.lower(this)
        }
        val alpha = p.dimLevel / 100f
        if (acc != null) {
            overlay.hide()
            acc.show(alpha)
        } else {
            overlay.show(alpha)
        }
        changed()
        return START_STICKY
    }

    private fun startFg(p: Prefs) {
        val n: Notification = Notif.builder(this)
            .setSmallIcon(R.drawable.ic_moon)
            .setContentTitle("Đang làm tối màn hình")
            .setContentText("Độ tối ${DimOverlay.effective(p.dimLevel)}%")
            .setOngoing(true)
            .setContentIntent(Notif.activity(this, DimActivity::class.java, 20))
            .addAction(Notif.action("Sáng hơn", Notif.service(this, DimService::class.java, ACTION_DOWN, 21)))
            .addAction(Notif.action("Tối hơn", Notif.service(this, DimService::class.java, ACTION_UP, 22)))
            .addAction(Notif.action("Tắt", Notif.service(this, DimService::class.java, ACTION_STOP, 23)))
            .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NOTIF_ID, n)
    }

    override fun onDestroy() {
        overlay.hide()
        DimAccessibility.instance?.hide()
        if (active) Brightness.restore(this)
        active = false
        changed()
        super.onDestroy()
    }

    private fun changed() {
        listener?.invoke()
        runCatching { TileService.requestListeningState(this, ComponentName(this, DimTileService::class.java)) }
    }

    companion object {
        private const val NOTIF_ID = 2
        private const val STEP = 5
        private const val ACTION_STOP = "stop"
        private const val ACTION_UP = "up"
        private const val ACTION_DOWN = "down"

        @Volatile var active = false
        /** UI refresh hook (main thread). */
        var listener: (() -> Unit)? = null

        fun canOverlay(ctx: Context) = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(ctx)

        fun start(ctx: Context) {
            val i = Intent(ctx, DimService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        /** Re-applies level / overlay kind while active. */
        fun refresh(ctx: Context) {
            if (active) start(ctx)
        }

        fun stop(ctx: Context) {
            if (active) ctx.startService(Intent(ctx, DimService::class.java).setAction(ACTION_STOP))
        }
    }
}

/** Optional system-brightness drop (needs "Modify system settings"). */
object Brightness {
    fun canWrite(ctx: Context) = Build.VERSION.SDK_INT < 23 || Settings.System.canWrite(ctx)

    fun lower(ctx: Context) {
        if (!canWrite(ctx)) return
        val cr = ctx.contentResolver
        val p = Prefs(ctx)
        runCatching {
            if (p.savedBrightness < 0) {
                p.savedBrightness = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS)
                p.savedBrightnessMode = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE)
            }
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, 1)
        }
    }

    fun restore(ctx: Context) {
        val p = Prefs(ctx)
        if (p.savedBrightness < 0 || !canWrite(ctx)) return
        val cr = ctx.contentResolver
        runCatching {
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, p.savedBrightness)
            if (p.savedBrightnessMode >= 0) Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, p.savedBrightnessMode)
        }
        p.savedBrightness = -1
        p.savedBrightnessMode = -1
    }
}
