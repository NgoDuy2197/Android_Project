package com.dsoft.screengrab

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.DisplayMetrics
import android.view.WindowManager
import android.widget.Toast
import com.dsoft.screengrab.capture.MediaSaver
import com.dsoft.screengrab.capture.RootShell
import com.dsoft.screengrab.capture.ScreenRecorder
import com.dsoft.screengrab.capture.Screenshotter
import java.io.File

/**
 * Foreground service that owns the MediaProjection for a session and performs screenshots / recording.
 * Keeping one projection alive means the consent dialog only shows once per session.
 */
class CaptureService : Service() {

    private var projection: MediaProjection? = null
    private var recorder: ScreenRecorder? = null
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var recording = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.getStringExtra(EXTRA_ACTION) ?: ACTION_SHOT
        val prefs = Prefs(this)
        val rootMode = prefs.captureMode == "root"

        startForegroundNotif(rootMode)

        // Projection token (first action of a projection-mode session)
        if (!rootMode && projection == null) {
            val code = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
            val data = if (Build.VERSION.SDK_INT >= 33)
                intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
            else @Suppress("DEPRECATION") intent?.getParcelableExtra(EXTRA_RESULT_DATA)
            if (code != 0 && data != null) {
                val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                projection = mpm.getMediaProjection(code, data)
            }
        }

        when (action) {
            ACTION_SHOT -> Thread { doScreenshot(prefs, rootMode) }.start()
            ACTION_RECORD_START -> Thread { doRecordStart(prefs, rootMode) }.start()
            ACTION_RECORD_STOP -> Thread { doRecordStop(rootMode) }.start()
            ACTION_STOP_SESSION -> stopSession()
        }
        notifyUi()
        return START_NOT_STICKY
    }

    // ---- screenshot ----
    private fun doScreenshot(prefs: Prefs, rootMode: Boolean) {
        val (w, h, dpi) = screenMetrics()
        val crop = cropRect(prefs, w, h)
        val bmp = if (rootMode) rootScreenshot(crop) else {
            val p = projection ?: run { toast("Chưa có quyền quay màn hình"); return }
            Screenshotter.capture(p, w, h, dpi, crop)
        }
        if (bmp == null) { toast("Chụp thất bại (app có thể chặn — cần bật hook LSPosed)"); return }
        val uri = MediaSaver.saveBitmap(this, bmp)
        toast(if (uri != null) "Đã lưu ảnh vào Thư viện" else "Lưu ảnh thất bại")
        if (!recording) maybeStopIfIdle()
    }

    private fun rootScreenshot(crop: Rect): android.graphics.Bitmap? {
        val png = RootShell.execBytes("screencap -p") ?: return null
        var bmp = BitmapFactory.decodeByteArray(png, 0, png.size) ?: return null
        val (w, h, _) = screenMetrics()
        val l = crop.left.coerceIn(0, w); val t = crop.top.coerceIn(0, h)
        val cw = crop.width().coerceIn(1, w - l); val ch = crop.height().coerceIn(1, h - t)
        if (!(l == 0 && t == 0 && cw == bmp.width && ch == bmp.height)) {
            runCatching { bmp = android.graphics.Bitmap.createBitmap(bmp, l, t, cw, ch) }
        }
        return bmp
    }

    // ---- record ----
    private var rootRecProc: Process? = null
    private var rootRecTemp: File? = null

    private fun doRecordStart(prefs: Prefs, rootMode: Boolean) {
        if (recording) { toast("Đang quay rồi"); return }
        val (w, h, dpi) = screenMetrics()
        val crop = cropRect(prefs, w, h)
        acquireWake()
        if (rootMode) {
            // screenrecord: full screen only, no audio, no crop
            val temp = MediaSaver.newVideoTemp(this)
            rootRecTemp = temp
            val sdPath = "/sdcard/.screengrab_tmp.mp4"
            rootRecProc = RootShell.spawn("screenrecord --bit-rate ${prefs.bitrateMbps * 1_000_000} --size ${w}x$h $sdPath")
            if (rootRecProc == null) { toast("Không chạy được screenrecord (root?)"); releaseWake(); return }
            recording = true
            toast("Đang quay (root, toàn màn hình, không tiếng)")
        } else {
            val p = projection ?: run { toast("Chưa có quyền quay màn hình"); releaseWake(); return }
            val temp = MediaSaver.newVideoTemp(this)
            rootRecTemp = temp
            val rec = ScreenRecorder(p, w, h, dpi)
            val ok = rec.start(temp.absolutePath, crop, prefs.fps, prefs.bitrateMbps, prefs.audioDevice, prefs.audioMic)
            if (!ok) { toast("Bắt đầu quay thất bại"); releaseWake(); return }
            recorder = rec
            recording = true
            toast("Đang quay màn hình")
        }
        notifyUi(); startForegroundNotif(rootMode)
    }

    private fun doRecordStop(rootMode: Boolean) {
        if (!recording) return
        recording = false
        if (rootMode) {
            runCatching { rootRecProc?.destroy() }
            rootRecProc = null
            // pull the file written on /sdcard into our temp, then publish
            val temp = rootRecTemp
            Thread.sleep(400)
            val data = RootShell.execBytes("cat /sdcard/.screengrab_tmp.mp4")
            if (temp != null && data != null) { temp.writeBytes(data); RootShell.exec("rm -f /sdcard/.screengrab_tmp.mp4"); publish(temp) }
            else toast("Lưu video thất bại")
        } else {
            recorder?.stop()
            recorder = null
            rootRecTemp?.let { publish(it) }
        }
        releaseWake()
        notifyUi(); maybeStopIfIdle()
    }

    private fun publish(temp: File) {
        val uri = MediaSaver.publishVideo(this, temp)
        toast(if (uri != null) "Đã lưu video vào Thư viện" else "Lưu video thất bại")
    }

    // ---- session / foreground ----
    private fun stopSession() {
        if (recording) doRecordStop(Prefs(this).captureMode == "root")
        projection?.stop(); projection = null
        releaseWake()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        notifyUi()
    }

    private fun maybeStopIfIdle() {
        // keep projection alive for the session so consent isn't asked again; only drop wakelock
        if (!recording) releaseWake()
    }

    private fun startForegroundNotif(rootMode: Boolean) {
        val chId = "screengrab"
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(chId) == null)
                nm.createNotificationChannel(NotificationChannel(chId, "ScreenGrab", NotificationManager.IMPORTANCE_LOW))
        }
        fun pi(action: String): PendingIntent {
            val i = Intent(this, CaptureService::class.java).putExtra(EXTRA_ACTION, action)
            val fl = if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE else PendingIntent.FLAG_UPDATE_CURRENT
            return PendingIntent.getService(this, action.hashCode(), i, fl)
        }
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, chId) else @Suppress("DEPRECATION") Notification.Builder(this)
        builder.setContentTitle("ScreenGrab")
            .setContentText(if (recording) "Đang quay màn hình…" else "Sẵn sàng chụp/quay")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Chụp ảnh", pi(ACTION_SHOT)).build())
        if (recording) builder.addAction(Notification.Action.Builder(null, "Dừng quay", pi(ACTION_RECORD_STOP)).build())
        else builder.addAction(Notification.Action.Builder(null, "Quay", pi(ACTION_RECORD_START)).build())
        builder.addAction(Notification.Action.Builder(null, "Thoát", pi(ACTION_STOP_SESSION)).build())

        val notif = builder.build()
        if (Build.VERSION.SDK_INT >= 29) {
            val type = if (rootMode && Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            else ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            startForeground(NOTIF_ID, notif, type)
        } else startForeground(NOTIF_ID, notif)
    }

    // ---- helpers ----
    private fun cropRect(prefs: Prefs, w: Int, h: Int): Rect =
        if (prefs.regionMode == "region" && prefs.hasRegion()) {
            val r = prefs.region()
            Rect(r.left.coerceIn(0, w), r.top.coerceIn(0, h), r.right.coerceIn(1, w), r.bottom.coerceIn(1, h))
        } else Rect(0, 0, w, h)

    private fun screenMetrics(): Triple<Int, Int, Int> {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.currentWindowMetrics.bounds
            Triple(b.width(), b.height(), resources.configuration.densityDpi)
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(dm)
            Triple(dm.widthPixels, dm.heightPixels, dm.densityDpi)
        }
    }

    private fun acquireWake() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ScreenGrab:rec").also { it.acquire(3 * 60 * 60 * 1000L) }
    }

    private fun releaseWake() { runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }; }

    private fun toast(msg: String) {
        android.os.Handler(mainLooper).post { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
        Log2.i(msg)
    }

    override fun onDestroy() {
        runCatching { recorder?.stop() }
        runCatching { projection?.stop() }
        releaseWake()
        super.onDestroy()
        running = false
        notifyUi()
    }

    companion object {
        const val EXTRA_ACTION = "action"
        const val EXTRA_RESULT_CODE = "code"
        const val EXTRA_RESULT_DATA = "data"
        const val ACTION_SHOT = "shot"
        const val ACTION_RECORD_START = "record_start"
        const val ACTION_RECORD_STOP = "record_stop"
        const val ACTION_STOP_SESSION = "stop_session"
        private const val NOTIF_ID = 42

        @Volatile var running = false
            private set
        @Volatile var isRecording = false
            private set
        private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
        fun addListener(l: () -> Unit) { listeners.add(l) }
        fun removeListener(l: () -> Unit) { listeners.remove(l) }
        private fun fire() { listeners.forEach { runCatching { it() } } }

        /** Fire a capture action from anywhere (UI or overlay), routing by capture mode / projection state. */
        fun trigger(ctx: Context, action: String) {
            val prefs = Prefs(ctx)
            if (prefs.captureMode == "root" || running) sendIntent(ctx, action)
            else ProjectionRequestActivity.launch(ctx, action)   // first projection action → consent
        }

        private fun sendIntent(ctx: Context, action: String) {
            val i = Intent(ctx, CaptureService::class.java).putExtra(EXTRA_ACTION, action)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }
    }

    init { running = true }

    private fun notifyUi() {
        isRecording = recording
        running = true
        fire()
    }
}
