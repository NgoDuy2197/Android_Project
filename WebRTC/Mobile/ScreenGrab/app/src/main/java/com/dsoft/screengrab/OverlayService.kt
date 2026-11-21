package com.dsoft.screengrab

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.dsoft.screengrab.ui.*

/**
 * A draggable floating bubble over other apps. Tap it to open a quick menu: full/region, screenshot,
 * record (with device-audio / mic switches). Lets you capture other apps (e.g. Udemy) without leaving them.
 */
class OverlayService : Service() {

    private lateinit var wm: WindowManager
    private var bubble: View? = null
    private var panel: View? = null
    private lateinit var prefs: Prefs
    private val h = Handler(Looper.getMainLooper())
    private val uiListener: () -> Unit = { h.post { refreshPanel() }; Unit }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        startForegroundNotif()
        addBubble()
        CaptureService.addListener(uiListener)
        SniffService.addListener(uiListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    // ---- bubble ----
    private fun addBubble() {
        val size = dp(54)
        val v = TextView(this).apply {
            text = "◉"
            setTextColor(Color.WHITE)
            textSize = 24f
            gravity = Gravity.CENTER
            background = rounded(Theme.accent, size / 2)
            alpha = 0.92f
        }
        val lp = WindowManager.LayoutParams(
            size, size, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = dp(12); y = dp(140) }

        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y; moved = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - downX).toInt(); val dy = (e.rawY - downY).toInt()
                    if (kotlin.math.abs(dx) > dp(6) || kotlin.math.abs(dy) > dp(6)) moved = true
                    lp.x = startX + dx; lp.y = startY + dy
                    runCatching { wm.updateViewLayout(v, lp) }; true
                }
                MotionEvent.ACTION_UP -> { if (!moved) togglePanel(); true }
                else -> false
            }
        }
        bubble = v
        runCatching { wm.addView(v, lp) }
    }

    // ---- panel ----
    private fun togglePanel() { if (panel == null) showPanel() else hidePanel() }

    private fun hidePanel() { panel?.let { runCatching { wm.removeView(it) } }; panel = null }

    private fun showPanel() {
        val recording = CaptureService.isRecording
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Theme.card, dp(16))
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        root.addView(tv("ScreenGrab", 16f, Theme.text, bold = true).apply {
            setOnClickListener {
                hidePanel()
                startActivityExternal(Intent(this@OverlayService, MainActivity::class.java))
            }
        })

        // scope: full / region
        val segRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0) }
        segRow.addView(menuBtn(if (prefs.regionMode == "full") "● Toàn màn hình" else "○ Toàn màn hình") {
            prefs.regionMode = "full"; refreshPanel()
        })
        segRow.addView(menuBtn(if (prefs.regionMode == "region") "● Vùng chọn" else "○ Vùng chọn") {
            hidePanel(); startActivityExternal(Intent(this, RegionActivity::class.java))
        })
        root.addView(segRow)

        // audio switches (shown for recording)
        root.addView(switchLine("Âm thanh máy", prefs.audioDevice) { prefs.audioDevice = it })
        root.addView(switchLine("Ghi âm mic", prefs.audioMic) { prefs.audioMic = it })

        // actions
        root.addView(menuBtnWide("📷  Chụp ảnh", Theme.accent) {
            hidePanel(); hideBubbleTemporarily(); h.postDelayed({ CaptureService.trigger(this, CaptureService.ACTION_SHOT) }, 350)
        })
        root.addView(menuBtnWide(if (recording) "⏹  Dừng quay" else "⏺  Quay màn hình", if (recording) Theme.red else Theme.accent2) {
            if (recording) CaptureService.trigger(this, CaptureService.ACTION_RECORD_STOP)
            else { hidePanel(); CaptureService.trigger(this, CaptureService.ACTION_RECORD_START) }
        })

        // video sniffer
        root.addView(switchLine("🎬 Bắt video", SniffService.running) { on ->
            if (on) {
                if (prefs.snifferRouting == "vpn") { hidePanel(); startActivityExternal(Intent(this, MainActivity::class.java)) }
                else { prefs.snifferEnabled = true; SniffService.start(this) }
            } else SniffService.stop(this)
        })
        root.addView(menuBtnWide("📥  Video đã bắt", Theme.card) {
            hidePanel(); startActivityExternal(Intent(this, MediaListActivity::class.java))
        })

        val lp = WindowManager.LayoutParams(
            dp(230), WindowManager.LayoutParams.WRAP_CONTENT, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = dp(12); y = dp(200) }
        panel = root
        runCatching { wm.addView(root, lp) }
    }

    private fun refreshPanel() { if (panel != null) { hidePanel(); showPanel() } }

    private fun hideBubbleTemporarily() {
        bubble?.visibility = View.GONE
        h.postDelayed({ bubble?.visibility = View.VISIBLE }, 1500)
    }

    // ---- small view helpers ----
    private fun menuBtn(label: String, onClick: () -> Unit): TextView =
        tv(label, 13f, Theme.text).apply {
            gravity = Gravity.CENTER
            background = rounded(Color.parseColor("#262A45"), dp(10))
            setPadding(dp(10), dp(10), dp(10), dp(10))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(3); marginEnd = dp(3) }
            setOnClickListener { onClick() }
        }

    private fun menuBtnWide(label: String, color: Int, onClick: () -> Unit): TextView =
        tv(label, 15f, Color.WHITE, bold = true).apply {
            gravity = Gravity.CENTER
            background = rounded(color, dp(12))
            setPadding(dp(12), dp(12), dp(12), dp(12))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
            setOnClickListener { onClick() }
        }

    private fun switchLine(label: String, value: Boolean, onChange: (Boolean) -> Unit): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, 0) }
        row.addView(tv(label, 14f, Theme.text), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(styledSwitch(this, value, onChange))
        return row
    }

    private fun startActivityExternal(i: Intent) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(i)
    }

    private fun startForegroundNotif() {
        val chId = "screengrab_overlay"
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(chId) == null)
                nm.createNotificationChannel(NotificationChannel(chId, "ScreenGrab nút nổi", NotificationManager.IMPORTANCE_MIN))
        }
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, chId) else @Suppress("DEPRECATION") Notification.Builder(this)
        val notif = b.setContentTitle("ScreenGrab").setContentText("Nút nổi đang bật")
            .setSmallIcon(android.R.drawable.ic_menu_camera).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 34)
            startForeground(7, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else if (Build.VERSION.SDK_INT >= 29)
            startForeground(7, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(7, notif)
    }

    override fun onDestroy() {
        CaptureService.removeListener(uiListener)
        SniffService.removeListener(uiListener)
        hidePanel()
        bubble?.let { runCatching { wm.removeView(it) } }; bubble = null
        super.onDestroy()
        running = false
    }

    init { running = true }

    companion object {
        @Volatile var running = false
            private set
        fun start(ctx: android.content.Context) {
            val i = Intent(ctx, OverlayService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }
        fun stop(ctx: android.content.Context) { ctx.stopService(Intent(ctx, OverlayService::class.java)) }
    }
}
