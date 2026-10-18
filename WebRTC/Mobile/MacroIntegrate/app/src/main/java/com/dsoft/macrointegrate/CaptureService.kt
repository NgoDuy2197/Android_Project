package com.dsoft.macrointegrate

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import android.widget.Toast
import java.io.ByteArrayOutputStream

/**
 * Holds the screen-capture session (MediaProjection mirrored into an ImageReader)
 * as a foreground service. The user enables it once in the app (grants the
 * system screen-record consent); afterwards an action grabs the latest frame
 * and sends it to Discord. A persistent notification shows while it is active.
 */
class CaptureService : Service() {
    private val wm by lazy { getSystemService(WindowManager::class.java) }
    private val main = Handler(Looper.getMainLooper())
    private val bg = HandlerThread("capture").apply { start() }
    private val bgHandler = Handler(bg.looper)
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private val frameLock = Any()
    private var latest: Image? = null
    private var w = 0
    private var h = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            ACTION_CAPTURE -> { capture(); return START_STICKY }
        }
        startFg()
        if (projection == null) {
            val code = intent?.getIntExtra(EXTRA_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
            @Suppress("DEPRECATION")
            val data = intent?.getParcelableExtra<Intent>(EXTRA_DATA)
            val mpm = getSystemService(MediaProjectionManager::class.java)
            val p = if (data != null) runCatching { mpm.getMediaProjection(code, data) }.getOrNull() else null
            if (p == null) {
                toast("Chưa cấp quyền chụp màn hình")
                stopSelf()
                return START_NOT_STICKY
            }
            p.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { main.post { stopSelf() } }
            }, main)
            projection = p
            setup()
            running = true
            listener?.invoke()
        }
        return START_STICKY
    }

    private fun startFg() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Chụp màn hình", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) }
            )
        }
        val stop = PendingIntent.getService(
            this, 1, Intent(this, CaptureService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        val open = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        @Suppress("DEPRECATION")
        val n: Notification = (if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else Notification.Builder(this))
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("MacroIntegrate sẵn sàng chụp màn hình")
            .setContentText("MacroDroid có thể gửi ảnh màn hình đến Discord")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null as android.graphics.drawable.Icon?, "Tắt", stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(NOTIF_ID, n)
    }

    @Suppress("DEPRECATION")
    private fun metrics() = DisplayMetrics().also { wm.defaultDisplay.getRealMetrics(it) }

    private fun setup() {
        val m = metrics()
        if (display != null && m.widthPixels == w && m.heightPixels == h) return
        w = m.widthPixels; h = m.heightPixels
        val old = reader
        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3)
        r.setOnImageAvailableListener({ ir ->
            val img = runCatching { ir.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
            synchronized(frameLock) { latest?.close(); latest = img }
        }, bgHandler)
        reader = r
        val d = display
        if (d == null) {
            display = projection?.createVirtualDisplay(
                "macrointegrate", w, h, m.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, bgHandler
            )
        } else {
            d.resize(w, h, m.densityDpi); d.surface = r.surface
        }
        synchronized(frameLock) { latest?.close(); latest = null }
        old?.close()
    }

    private fun grab(): Bitmap? = synchronized(frameLock) {
        val img = latest ?: return null
        try {
            val plane = img.planes[0]
            val rowPad = plane.rowStride - plane.pixelStride * img.width
            val full = Bitmap.createBitmap(img.width + rowPad / plane.pixelStride, img.height, Bitmap.Config.ARGB_8888)
            full.copyPixelsFromBuffer(plane.buffer.apply { rewind() })
            if (rowPad == 0) full else Bitmap.createBitmap(full, 0, 0, img.width, img.height).also { full.recycle() }
        } catch (_: Exception) { null }
    }

    private fun capture() {
        setup()
        // Give the mirror a moment to produce a fresh frame, then send it.
        main.postDelayed({
            val bmp = grab()
            if (bmp == null) { toast("Chưa lấy được khung hình, thử lại"); return@postDelayed }
            bgHandler.post {
                val out = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                bmp.recycle()
                val p = Prefs(this)
                val res = Discord.sendImage(p.webhook, p.username, "🖼️ Ảnh màn hình", out.toByteArray(), "screenshot.png")
                toast(if (res.ok) "Đã gửi ảnh màn hình" else res.message)
            }
        }, 350)
    }

    override fun onDestroy() {
        runCatching { display?.release() }
        runCatching { projection?.stop() }
        projection = null
        synchronized(frameLock) { latest?.close(); latest = null }
        runCatching { reader?.close() }
        bg.quitSafely()
        running = false
        listener?.invoke()
        super.onDestroy()
    }

    private fun toast(s: String) = main.post { Toast.makeText(this, s, Toast.LENGTH_SHORT).show() }

    companion object {
        private const val CHANNEL = "capture"
        private const val NOTIF_ID = 7
        private const val ACTION_STOP = "stop"
        private const val ACTION_CAPTURE = "capture"
        private const val EXTRA_CODE = "code"
        private const val EXTRA_DATA = "data"

        @Volatile var running = false
        var listener: (() -> Unit)? = null

        fun enable(ctx: Context, code: Int, data: Intent) {
            val i = Intent(ctx, CaptureService::class.java).putExtra(EXTRA_CODE, code).putExtra(EXTRA_DATA, data)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            if (running) ctx.startService(Intent(ctx, CaptureService::class.java).setAction(ACTION_STOP))
        }

        /** Grabs the current screen and sends it. Returns false if capture is not enabled yet. */
        fun captureAndSend(ctx: Context): Boolean {
            if (!running) return false
            ctx.startService(Intent(ctx, CaptureService::class.java).setAction(ACTION_CAPTURE))
            return true
        }
    }
}
