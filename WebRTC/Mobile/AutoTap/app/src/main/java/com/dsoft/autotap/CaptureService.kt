package com.dsoft.autotap

import android.app.Activity
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

/**
 * Foreground service that holds a MediaProjection screen-capture session mirrored
 * into an [ImageReader]. [grabLatest] returns the most recent frame as a Bitmap,
 * used by the TAP_TEXT (OCR) step. Based on Toolbox's capture pattern.
 */
class CaptureService : Service() {
    private val wm by lazy { getSystemService(WindowManager::class.java) }
    private val main = Handler(Looper.getMainLooper())
    private val bg = HandlerThread("autotap-capture").apply { start() }
    private val bgHandler = Handler(bg.looper)
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private val frameLock = Any()
    private var latest: Image? = null
    private var capW = 0
    private var capH = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startFg()
        if (projection == null) {
            val code = intent?.getIntExtra(EXTRA_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
            @Suppress("DEPRECATION")
            val data = intent?.getParcelableExtra<Intent>(EXTRA_DATA)
            val mpm = getSystemService(MediaProjectionManager::class.java)
            val p = if (data != null) runCatching { mpm.getMediaProjection(code, data) }.getOrNull() else null
            if (p == null) {
                toast("Không bắt đầu được chụp màn hình")
                stopSelf()
                return START_NOT_STICKY
            }
            p.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { main.post { stopSelf() } }
            }, main)
            projection = p
            setupCapture()
            running = true
            instance = this
            notifyChange()
        }
        return START_NOT_STICKY
    }

    private fun startFg() {
        val n = Notif.builder(this)
            .setSmallIcon(R.drawable.ic_eye)
            .setContentTitle("Đọc màn hình (OCR) đang bật")
            .setContentText("Dùng cho bước \"Chạm theo chữ\"")
            .setOngoing(true)
            .setContentIntent(Notif.activity(this, MainActivity::class.java, 40))
            .addAction(Notif.action("Tắt", Notif.service(this, CaptureService::class.java, ACTION_STOP, 41)))
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(NOTIF_ID, n)
    }

    @Suppress("DEPRECATION")
    private fun realMetrics(): DisplayMetrics = DisplayMetrics().also { wm.defaultDisplay.getRealMetrics(it) }

    private fun setupCapture() {
        val m = realMetrics()
        if (display != null && m.widthPixels == capW && m.heightPixels == capH) return
        capW = m.widthPixels
        capH = m.heightPixels
        val old = reader
        val r = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 3)
        r.setOnImageAvailableListener({ ir ->
            val img = runCatching { ir.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
            synchronized(frameLock) {
                latest?.close()
                latest = img
            }
        }, bgHandler)
        reader = r
        val d = display
        if (d == null) {
            display = projection?.createVirtualDisplay(
                "autotap-capture", capW, capH, m.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, bgHandler,
            )
        } else {
            d.resize(capW, capH, m.densityDpi)
            d.surface = r.surface
        }
        synchronized(frameLock) {
            latest?.close()
            latest = null
        }
        old?.close()
    }

    private fun grab(): Bitmap? = synchronized(frameLock) {
        val img = latest ?: return null
        try {
            val plane = img.planes[0]
            val pixelStride = plane.pixelStride
            val rowPad = plane.rowStride - pixelStride * img.width
            val full = Bitmap.createBitmap(img.width + rowPad / pixelStride, img.height, Bitmap.Config.ARGB_8888)
            full.copyPixelsFromBuffer(plane.buffer.apply { rewind() })
            if (rowPad == 0) full else Bitmap.createBitmap(full, 0, 0, img.width, img.height).also { full.recycle() }
        } catch (_: Exception) {
            null
        }
    }

    override fun onDestroy() {
        runCatching { display?.release() }
        runCatching { projection?.stop() }
        projection = null
        synchronized(frameLock) { latest?.close(); latest = null }
        runCatching { reader?.close() }
        bg.quitSafely()
        running = false
        if (instance === this) instance = null
        notifyChange()
        super.onDestroy()
    }

    private fun notifyChange() = main.post { listener?.invoke() }
    private fun toast(s: String) = main.post { Toast.makeText(this, s, Toast.LENGTH_SHORT).show() }

    companion object {
        private const val NOTIF_ID = 5
        private const val ACTION_STOP = "stop"
        private const val EXTRA_CODE = "code"
        private const val EXTRA_DATA = "data"

        @Volatile var running = false
        @Volatile var instance: CaptureService? = null
        var listener: (() -> Unit)? = null

        fun isEnabled(): Boolean = running
        fun grabLatest(): Bitmap? = instance?.grab()

        fun start(ctx: Context, code: Int, data: Intent) {
            val i = Intent(ctx, CaptureService::class.java).putExtra(EXTRA_CODE, code).putExtra(EXTRA_DATA, data)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            if (running) ctx.startService(Intent(ctx, CaptureService::class.java).setAction(ACTION_STOP))
        }
    }
}
