package com.dsoft.toolbox

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
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
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs

/**
 * Floating translate bubble. Holds a screen-capture session (MediaProjection
 * mirrored into an ImageReader); tapping the bubble grabs the current frame,
 * OCRs + translates it and shows [ResultView] full screen on top of everything.
 * Long-press the bubble (or "Tắt" in the notification) to turn it off.
 */
class ScreenTranslateService : Service() {
    private val wm by lazy { getSystemService(WindowManager::class.java) }
    private val main = Handler(Looper.getMainLooper())
    private val bg = HandlerThread("capture").apply { start() }
    private val bgHandler = Handler(bg.looper)
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private val frameLock = Any()
    private var latest: Image? = null
    private var capW = 0
    private var capH = 0
    private var bubble: View? = null
    private var result: ResultView? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        // Android 14: the mediaProjection foreground service must be running before getMediaProjection().
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
            showBubble()
            running = true
            listener?.invoke()
        }
        return START_NOT_STICKY
    }

    private fun startFg() {
        val n = Notif.builder(this)
            .setSmallIcon(R.drawable.ic_translate)
            .setContentTitle("Dịch màn hình đang bật")
            .setContentText("Chạm nút nổi để chụp và dịch")
            .setOngoing(true)
            .setContentIntent(Notif.activity(this, TranslateActivity::class.java, 30))
            .addAction(Notif.action("Tắt", Notif.service(this, ScreenTranslateService::class.java, ACTION_STOP, 31)))
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(NOTIF_ID, n)
    }

    @Suppress("DEPRECATION")
    private fun realMetrics(): DisplayMetrics = DisplayMetrics().also { wm.defaultDisplay.getRealMetrics(it) }

    /** (Re)creates the mirror at the current screen size (games switch to landscape). */
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
                "toolbox-translate", capW, capH, m.densityDpi,
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

    // ---- bubble ----

    @SuppressLint("ClickableViewAccessibility")
    private fun showBubble() {
        if (bubble != null) return
        val p = Prefs(this)
        val size = dp(54)
        val v = TextView(this).apply {
            text = "文A"
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xEE30B0C7.toInt(), 0xEE5856D6.toInt())).apply {
                shape = GradientDrawable.OVAL
                setStroke(dp(2), 0xB3FFFFFF.toInt())
            }
            elevation = dp(6).toFloat()
        }
        val m = realMetrics()
        val lp = WindowManager.LayoutParams(
            size, size, DimOverlay.appType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (p.bubbleX >= 0) p.bubbleX.coerceAtMost(m.widthPixels - size) else m.widthPixels - size - dp(8)
            y = if (p.bubbleY >= 0) p.bubbleY.coerceAtMost(m.heightPixels - size) else m.heightPixels / 3
        }
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        val longPress = Runnable {
            if (!moved) {
                toast("Đã tắt dịch màn hình")
                stopSelf()
            }
        }
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y; moved = false
                    main.postDelayed(longPress, 900)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!moved && (abs(dx) > slop || abs(dy) > slop)) moved = true
                    if (moved) {
                        lp.x = (startX + dx).toInt()
                        lp.y = (startY + dy).toInt()
                        runCatching { wm.updateViewLayout(v, lp) }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    main.removeCallbacks(longPress)
                    if (moved) { p.bubbleX = lp.x; p.bubbleY = lp.y } else capture()
                }
                MotionEvent.ACTION_CANCEL -> main.removeCallbacks(longPress)
            }
            true
        }
        try {
            wm.addView(v, lp)
            bubble = v
        } catch (e: Exception) {
            toast("Cần quyền \"Hiển thị trên ứng dụng khác\"")
            stopSelf()
        }
    }

    private fun capture() {
        if (result != null) return
        val b = bubble ?: return
        b.visibility = View.INVISIBLE // keep it out of the screenshot
        setupCapture()
        // Wait for a frame drawn without the bubble.
        main.postDelayed({
            val shot = grab()
            if (shot == null) {
                b.visibility = View.VISIBLE
                toast("Chưa chụp được màn hình, thử lại")
                return@postDelayed
            }
            showResult(shot)
        }, 400)
    }

    private fun showResult(shot: Bitmap) {
        val rv = ResultView(this, shot) { closeResult() }
        val m = realMetrics()
        val lp = WindowManager.LayoutParams(
            m.widthPixels, m.heightPixels, DimOverlay.appType(),
            // NOT_FOCUSABLE: the app/game underneath keeps input focus, so it is not
            // paused while the result is shown. Touch still works (hold-to-peek, ✕);
            // we lose the hardware Back key, so the ✕ button closes it.
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_FULLSCREEN or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.OPAQUE,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        try {
            wm.addView(rv, lp)
        } catch (e: Exception) {
            bubble?.visibility = View.VISIBLE
            toast("Không hiện được kết quả: ${e.message}")
            return
        }
        result = rv
        TranslatePipeline.run(this, shot, object : TranslatePipeline.Listener {
            override fun onStatus(s: String) { if (result === rv) rv.setStatus(s) }
            override fun onBlocks(blocks: List<Block>) { if (result === rv) rv.setBlocks(blocks) }
            override fun onDone(error: String?) {
                if (result !== rv) return
                rv.setStatus(error ?: "Giữ để xem bản gốc")
                if (error == null) main.postDelayed({ if (result === rv) rv.setStatus(null) }, 2500)
            }
        })
    }

    private fun closeResult() {
        result?.let { runCatching { wm.removeView(it) } }
        result = null
        bubble?.visibility = View.VISIBLE
    }

    override fun onDestroy() {
        closeResult()
        bubble?.let { runCatching { wm.removeView(it) } }
        bubble = null
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
        private const val NOTIF_ID = 3
        private const val ACTION_STOP = "stop"
        private const val EXTRA_CODE = "code"
        private const val EXTRA_DATA = "data"

        @Volatile var running = false
        var listener: (() -> Unit)? = null

        fun start(ctx: Context, code: Int, data: Intent) {
            val i = Intent(ctx, ScreenTranslateService::class.java).putExtra(EXTRA_CODE, code).putExtra(EXTRA_DATA, data)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            if (running) ctx.startService(Intent(ctx, ScreenTranslateService::class.java).setAction(ACTION_STOP))
        }
    }
}
