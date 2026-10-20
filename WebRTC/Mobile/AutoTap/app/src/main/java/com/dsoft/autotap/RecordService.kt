package com.dsoft.autotap

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Record mode. A full-screen, dimmed, touchable overlay captures the user's
 * taps / long-presses / drags and appends them (with real timing gaps as WAIT
 * steps) to a scenario. A small draggable control bar offers Record/Pause/Stop/Undo.
 *
 * Caveat: while recording, the overlay consumes touches, so the app underneath
 * does NOT react — the user is only demonstrating positions. Playback later
 * performs them for real through the accessibility service.
 */
class RecordService : Service() {
    private val wm by lazy { getSystemService(WindowManager::class.java) }
    private val main = Handler(Looper.getMainLooper())

    private lateinit var prefs: Prefs
    private lateinit var scenarioId: String
    private var scenario: Scenario? = null
    private val recorded = ArrayList<Step>()

    private var capture: View? = null
    private var bar: LinearLayout? = null
    private var counter: TextView? = null
    private var recBtn: TextView? = null

    @Volatile private var recording = true
    private var lastUpTime = 0L

    // gesture tracking
    private var downX = 0f; private var downY = 0f; private var downTime = 0L
    private var slop = 24

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        prefs = Prefs(this)
        scenarioId = intent?.getStringExtra(EXTRA_ID) ?: run { stopSelf(); return START_NOT_STICKY }
        scenario = Store(this).byId(scenarioId)
        if (scenario == null) { stopSelf(); return START_NOT_STICKY }
        slop = ViewConfiguration.get(this).scaledTouchSlop
        try {
            showCapture()
            showBar()
            running = true
            instance = this
            notifyChange()
        } catch (e: Exception) {
            toast("Cần quyền \"Hiển thị trên ứng dụng khác\"")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showCapture() {
        val dim = (prefs.overlayDim.coerceIn(0, 80) * 255 / 100)
        val v = View(this).apply {
            setBackgroundColor(Color.argb(dim, 0, 0, 0))
        }
        v.setOnTouchListener { _, e ->
            if (!recording) return@setOnTouchListener false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; downTime = System.currentTimeMillis()
                }
                MotionEvent.ACTION_UP -> onGestureUp(e.rawX, e.rawY)
            }
            true
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            appType(),
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        wm.addView(v, lp)
        capture = v
    }

    private fun onGestureUp(upX: Float, upY: Float) {
        val now = System.currentTimeMillis()
        val dt = now - downTime
        val dist = hypot((upX - downX).toDouble(), (upY - downY).toDouble())
        // insert the real gap since the previous gesture, as a WAIT
        if (lastUpTime > 0) {
            val gap = downTime - lastUpTime
            if (gap in 50..600_000) add(Step.wait(gap))
        }
        val step = when {
            dist <= slop && dt < 400 -> Step.tap(downX.toInt(), downY.toInt())
            dist <= slop -> Step.longPress(downX.toInt(), downY.toInt(), dt)
            else -> Step.swipe(downX.toInt(), downY.toInt(), upX.toInt(), upY.toInt(), dt.coerceIn(80, 5_000))
        }
        add(step)
        lastUpTime = now
    }

    private fun add(step: Step) {
        recorded.add(step)
        main.post { counter?.text = "${recorded.size}" }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showBar() {
        val p = palette()
        val bg = GradientDrawable().apply {
            setColor(if (p.dark) 0xF21C1C1E.toInt() else 0xF2FFFFFF.toInt())
            cornerRadius = dp(22).toFloat()
            setStroke(dp(1), p.separator)
        }
        counter = text("0", 15f, p.text, 700)
        recBtn = pill("⏸", p.red) { toggleRecord() }
        val b = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = bg
            elevation = dp(8).toFloat()
            setPadding(dp(12), dp(8), dp(12), dp(8))
            addView(icon(R.drawable.ic_record, p.red, 18).apply {
                (layoutParams as LinearLayout.LayoutParams).marginEnd = dp(4)
            })
            addView(counter, LinearLayout.LayoutParams(dp(28), WindowManager.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) })
            addView(recBtn)
            addView(space())
            addView(pill("↶", p.orange) { undo() })
            addView(space())
            addView(pill("■", p.blue) { finishRecording() })
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            appType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (prefs.barX >= 0) prefs.barX else dp(16)
            y = if (prefs.barY >= 0) prefs.barY else dp(60)
        }
        // drag the bar by its background
        var dX = 0f; var dY = 0f; var sX = 0; var sY = 0; var moved = false
        b.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { dX = e.rawX; dY = e.rawY; sX = lp.x; sY = lp.y; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    val ddx = e.rawX - dX; val ddy = e.rawY - dY
                    if (abs(ddx) > slop || abs(ddy) > slop) moved = true
                    if (moved) { lp.x = sX + ddx.toInt(); lp.y = sY + ddy.toInt(); runCatching { wm.updateViewLayout(b, lp) } }
                }
                MotionEvent.ACTION_UP -> if (moved) { prefs.barX = lp.x; prefs.barY = lp.y }
            }
            moved // consume only while dragging, so child buttons still click
        }
        wm.addView(b, lp)
        bar = b
    }

    private fun pill(label: String, color: Int, onClick: () -> Unit): TextView {
        val p = palette()
        return text(label, 17f, Color.WHITE, 700).apply {
            gravity = Gravity.CENTER
            minWidth = dp(40)
            minHeight = dp(38)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = rounded(color, dp(19).toFloat())
            setOnClickListener { onClick() }
        }
    }

    private fun space(): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(dp(6), 1)
    }

    private fun toggleRecord() {
        recording = !recording
        recBtn?.text = if (recording) "⏸" else "●"
        recBtn?.background = rounded(if (recording) palette().red else palette().green, dp(19).toFloat())
        val v = capture ?: return
        val lp = v.layoutParams as WindowManager.LayoutParams
        lp.flags = if (recording)
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        else
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        runCatching { wm.updateViewLayout(v, lp) }
        if (!recording) lastUpTime = 0L
        toast(if (recording) "Đang ghi" else "Tạm dừng — chạm được vào ứng dụng")
    }

    private fun undo() {
        if (recorded.isEmpty()) return
        recorded.removeAt(recorded.size - 1)
        counter?.text = "${recorded.size}"
    }

    private fun finishRecording() {
        val s = scenario
        if (s != null && recorded.isNotEmpty()) {
            s.steps.addAll(recorded)
            Store(this).upsert(s)
            toast("Đã lưu ${recorded.size} bước vào \"${s.name}\"")
        }
        stopSelf()
    }

    override fun onDestroy() {
        capture?.let { runCatching { wm.removeView(it) } }
        bar?.let { runCatching { wm.removeView(it) } }
        capture = null; bar = null
        running = false
        if (instance === this) instance = null
        notifyChange()
        super.onDestroy()
    }

    private fun appType() = if (Build.VERSION.SDK_INT >= 26)
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE

    private fun notifyChange() = main.post { listener?.invoke() }
    private fun toast(s: String) = main.post { Toast.makeText(this, s, Toast.LENGTH_SHORT).show() }

    companion object {
        private const val ACTION_STOP = "stop"
        private const val EXTRA_ID = "id"
        @Volatile var running = false
        @Volatile var instance: RecordService? = null
        var listener: (() -> Unit)? = null

        fun start(ctx: Context, scenarioId: String) {
            ctx.startService(Intent(ctx, RecordService::class.java).putExtra(EXTRA_ID, scenarioId))
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, RecordService::class.java).setAction(ACTION_STOP))
        }
    }
}
