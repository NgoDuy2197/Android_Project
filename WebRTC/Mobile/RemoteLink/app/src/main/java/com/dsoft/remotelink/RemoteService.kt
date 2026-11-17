package com.dsoft.remotelink

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.DisplayMetrics
import android.view.WindowManager
import com.dsoft.remotelink.capture.MediaProjectionCapturer
import com.dsoft.remotelink.capture.RootCapturer
import com.dsoft.remotelink.capture.ScreenCapturer
import com.dsoft.remotelink.input.AccessibilityInjector
import com.dsoft.remotelink.input.InputInjector
import com.dsoft.remotelink.input.RootInjector
import com.dsoft.remotelink.net.ServerManager

/** Foreground service that owns capture + the servers, so streaming survives screen-off / backgrounding. */
class RemoteService : Service() {

    private var manager: ServerManager? = null
    private var projection: MediaProjection? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopEverything(); stopSelf(); return START_NOT_STICKY }
        if (running) return START_STICKY

        val prefs = Prefs(this)
        startForegroundNotif(prefs.captureMode == "root")
        val (realW, realH, dpi) = realScreen()

        val capturer: ScreenCapturer = try {
            if (prefs.captureMode == "root") {
                RootCapturer(realW, realH, dpi, prefs.scaleDiv)
            } else {
                val rc = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
                val data = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
                if (rc == 0 || data == null) { Log2.e("no projection token"); stopSelf(); return START_NOT_STICKY }
                val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                val mp = mpm.getMediaProjection(rc, data)
                projection = mp
                MediaProjectionCapturer(mp, realW, realH, dpi, prefs.scaleDiv)
            }
        } catch (e: Exception) { Log2.e("capturer init failed", e); stopSelf(); return START_NOT_STICKY }

        val injector: InputInjector = if (prefs.inputMode == "root") RootInjector() else AccessibilityInjector()

        manager = ServerManager(capturer, injector, realW, realH, prefs).also { it.start() }
        running = true
        current = this
        acquireWake()
        notifyState()
        Log2.i("RemoteService running (capture=${prefs.captureMode}, input=${prefs.inputMode})")
        return START_STICKY
    }

    private fun realScreen(): Triple<Int, Int, Int> {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val dpi = resources.displayMetrics.densityDpi
        return if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.currentWindowMetrics.bounds
            Triple(b.width(), b.height(), dpi)
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(dm)
            Triple(dm.widthPixels, dm.heightPixels, dpi)
        }
    }

    private fun acquireWake() {
        runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RemoteLink:serve").apply { acquire() }
        }
    }

    private fun startForegroundNotif(rootMode: Boolean) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "RemoteLink", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val stopPi = android.app.PendingIntent.getService(
            this, 1, Intent(this, RemoteService::class.java).setAction(ACTION_STOP),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL)
            else @Suppress("DEPRECATION") Notification.Builder(this)
        val notif: Notification = builder
            .setContentTitle("RemoteLink đang chạy")
            .setContentText("Màn hình đang được chia sẻ trong mạng LAN")
            .setSmallIcon(R.mipmap.ic_launcher)
            .addAction(0, "Dừng", stopPi)
            .setOngoing(true)
            .build()
        // Root capture has no MediaProjection token, so it can't use the mediaProjection FGS type.
        when {
            !rootMode && Build.VERSION.SDK_INT >= 29 ->
                startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            rootMode && Build.VERSION.SDK_INT >= 34 ->
                startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else -> startForeground(NOTIF_ID, notif)
        }
    }

    private fun stopEverything() {
        runCatching { manager?.stop() }; manager = null
        runCatching { projection?.stop() }; projection = null
        runCatching { wakeLock?.release() }; wakeLock = null
        running = false
        if (current === this) current = null
        notifyState()
    }

    override fun onDestroy() {
        stopEverything()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL = "remotelink"
        const val NOTIF_ID = 42
        const val ACTION_STOP = "com.dsoft.remotelink.STOP"
        const val EXTRA_RESULT_CODE = "rc"
        const val EXTRA_RESULT_DATA = "data"

        @Volatile var running = false; private set
        @Volatile private var current: RemoteService? = null

        private val stateListeners = mutableListOf<() -> Unit>()
        fun addStateListener(l: () -> Unit) { synchronized(stateListeners) { stateListeners.add(l) } }
        fun removeStateListener(l: () -> Unit) { synchronized(stateListeners) { stateListeners.remove(l) } }
        private fun notifyState() { synchronized(stateListeners) { stateListeners.toList() }.forEach { runCatching { it() } } }

        fun vncClients() = current?.manager?.vncClients() ?: 0
        fun streamClients() = current?.manager?.streamClients() ?: 0

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, RemoteService::class.java).setAction(ACTION_STOP))
        }
    }
}
