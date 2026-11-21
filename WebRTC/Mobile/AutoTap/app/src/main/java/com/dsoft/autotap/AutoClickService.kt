package com.dsoft.autotap

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The playback engine. When enabled in Accessibility settings it can inject real
 * taps / swipes anywhere via [dispatchGesture], and run a whole [Scenario] on a
 * background thread. TAP_TEXT additionally reads the screen through [CaptureService]
 * (MediaProjection) + ML Kit OCR to find where to tap.
 */
class AutoClickService : AccessibilityService() {

    private val main = Handler(Looper.getMainLooper())
    private var worker: HandlerThread? = null
    private var handler: Handler? = null
    @Volatile private var cancelled = false

    // Floating status bar shown while a scenario runs (accessibility overlay: no extra permission needed).
    private var overlay: View? = null
    private var statusView: TextView? = null

    override fun onServiceConnected() {
        try {
            instance = this
            watchPrefs = Prefs(this)
            reloadWatch()
            notifyChange()
        } catch (_: Throwable) {}
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !this::watchPrefs.isInitialized || !watchPrefs.watchOn) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            -> {
                main.removeCallbacks(watchOnce)
                main.postDelayed(watchOnce, WATCH_DEBOUNCE_MS)
            }
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        try { stopRun() } catch (_: Throwable) {}
        try { hideOverlay() } catch (_: Throwable) {}
        try { stopWatch() } catch (_: Throwable) {}
        instance = null
        notifyChange()
        super.onDestroy()
    }

    // ---- gesture primitives (blocking; call off the main thread) ----

    /** Clamps a coordinate inside the display so StrokeDescription never rejects an off-screen point. */
    private fun clampX(x: Int): Float = x.toFloat().coerceIn(0f, (resources.displayMetrics.widthPixels - 1).coerceAtLeast(1).toFloat())
    private fun clampY(y: Int): Float = y.toFloat().coerceIn(0f, (resources.displayMetrics.heightPixels - 1).coerceAtLeast(1).toFloat())

    private fun doGesture(path: Path, startMs: Long, durationMs: Long): Boolean = try {
        // Android caps a single gesture at ~60s; stay safely under it.
        val dur = durationMs.coerceIn(1, 59_000)
        val stroke = GestureDescription.StrokeDescription(path, startMs.coerceAtLeast(0), dur)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val latch = CountDownLatch(1)
        val ok = AtomicBoolean(false)
        val dispatched = try {
            dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(d: GestureDescription?) { ok.set(true); latch.countDown() }
                override fun onCancelled(d: GestureDescription?) { latch.countDown() }
            }, main)
        } catch (_: Throwable) {
            false
        }
        if (!dispatched) false
        else try {
            latch.await(dur + 5_000, TimeUnit.MILLISECONDS)
            ok.get()
        } catch (_: InterruptedException) {
            false
        }
    } catch (_: Throwable) {
        false
    }

    fun tap(x: Int, y: Int): Boolean = doGesture(Path().apply { moveTo(clampX(x), clampY(y)) }, 0, 50)

    fun longPress(x: Int, y: Int, ms: Long): Boolean =
        doGesture(Path().apply { moveTo(clampX(x), clampY(y)) }, 0, ms.coerceAtLeast(400))

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, ms: Long): Boolean = doGesture(
        Path().apply { moveTo(clampX(x1), clampY(y1)); lineTo(clampX(x2), clampY(y2)) },
        0, ms.coerceAtLeast(50),
    )

    fun back(): Boolean = try { performGlobalAction(GLOBAL_ACTION_BACK) } catch (_: Throwable) { false }
    fun home(): Boolean = try { performGlobalAction(GLOBAL_ACTION_HOME) } catch (_: Throwable) { false }
    fun recents(): Boolean = try { performGlobalAction(GLOBAL_ACTION_RECENTS) } catch (_: Throwable) { false }

    /** Best-effort: set text into the currently focused editable node. */
    fun inputText(s: String): Boolean {
        val node = try {
            val root = rootInActiveWindow
            root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: root?.let { deepestEditable(it) }
        } catch (_: Exception) {
            null
        } ?: return false
        return try {
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, s)
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } catch (_: Exception) {
            false
        }
    }

    private fun deepestEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            deepestEditable(c)?.let { return it }
        }
        return null
    }

    // ---- scenario runner ----

    private fun startRun(scenario: Scenario, params: Map<String, String>) {
        try {
            stopRun()
            cancelled = false
            val prefs = Prefs(this)
            val t = HandlerThread("autotap-runner").apply { start() }
            worker = t
            handler = Handler(t.looper)
            running = true
            notifyChange()
            showOverlay()
            handler?.post {
                try {
                    execute(scenario, params, prefs)
                } catch (_: Throwable) {
                } finally {
                    main.post { finishRun() }
                }
            }
        } catch (_: Throwable) {
            running = false
            notifyChange()
            hideOverlay()
        }
    }

    private fun execute(scenario: Scenario, params: Map<String, String>, prefs: Prefs) {
        val repeats = scenario.repeatCount.coerceAtLeast(1)
        val defaultDelay = scenario.defaultDelayMs.coerceAtLeast(0)
        val continueOnFail = prefs.continueOnFail
        val total = scenario.steps.size

        // Countdown so the user can switch to the target app before the first tap lands.
        var left = prefs.startDelayMs
        while (left > 0 && !cancelled) {
            setStatus("Bắt đầu sau ${(left + 999) / 1000}…")
            val chunk = minOf(left, 250L)
            try { Thread.sleep(chunk) } catch (_: InterruptedException) { return }
            left -= chunk
        }

        loop@ for (round in 0 until repeats) {
            for (i in scenario.steps.indices) {
                if (cancelled) break@loop
                val roundInfo = if (repeats > 1) " · vòng ${round + 1}/$repeats" else ""
                setStatus("Đang chạy · bước ${i + 1}/$total$roundInfo")
                val step = try { Params.apply(scenario.steps[i], params) } catch (_: Throwable) { scenario.steps[i] }
                val ok = runStep(step)
                if (!ok && !continueOnFail) break@loop
                if (cancelled) break@loop
                if (step.type != Step.WAIT && defaultDelay > 0) sleep(defaultDelay)
            }
        }
    }

    private fun runStep(step: Step): Boolean = try {
        when (step.type) {
            Step.TAP -> tap(step.x, step.y)
            Step.LONG_PRESS -> longPress(step.x, step.y, step.durationMs)
            Step.SWIPE -> swipe(step.x, step.y, step.x2, step.y2, step.durationMs)
            Step.WAIT -> { sleep(step.durationMs); true }
            Step.BACK -> back()
            Step.HOME -> home()
            Step.RECENTS -> recents()
            Step.INPUT -> inputText(step.text)
            Step.TAP_TEXT -> runTapText(step)
            else -> true
        }
    } catch (_: Exception) {
        false
    }

    private fun runTapText(step: Step): Boolean {
        val bmp: Bitmap = CaptureService.grabLatest() ?: return false
        val region = if (step.hasRegion)
            Rect(step.regionLeft, step.regionTop, step.regionRight, step.regionBottom) else null
        val center = Ocr.findTextCenter(bmp, step.text, step.occurrence, region)
        bmp.recycle()
        if (center == null) return false
        return tap(center.first.toInt(), center.second.toInt())
    }

    private fun sleep(ms: Long) {
        if (ms <= 0) return
        var left = ms
        while (left > 0 && !cancelled) {
            val chunk = minOf(left, 100L)
            try { Thread.sleep(chunk) } catch (_: InterruptedException) { return }
            left -= chunk
        }
    }

    private fun stopRun() {
        cancelled = true
        try { worker?.quitSafely() } catch (_: Throwable) {}
        worker = null
        handler = null
        hideOverlay()
        if (running) {
            running = false
            notifyChange()
        }
    }

    private fun finishRun() {
        try { worker?.quitSafely() } catch (_: Throwable) {}
        worker = null
        handler = null
        hideOverlay()
        if (running) {
            running = false
            notifyChange()
        }
    }

    // ---- text watcher: find a configured text on screen and tap it ----

    private lateinit var watchPrefs: Prefs

    /** Per-rule time of the last tap, so one button is not hammered. */
    private val watchLastTap = HashMap<String, Long>()

    private val watchOnce = Runnable { scanWatch() }

    private val watchLoop = object : Runnable {
        override fun run() {
            scanWatch()
            val ms = watchPrefs.watchScanMs
            if (ms > 0) main.postDelayed(this, ms.toLong())
        }
    }

    /** Re-read the watcher settings: restart, or stop, the periodic scan. */
    fun reloadWatch() {
        main.removeCallbacks(watchLoop)
        main.removeCallbacks(watchOnce)
        watchLastTap.clear()
        if (!watchPrefs.watchOn) return
        if (watchPrefs.watchScanMs > 0) main.post(watchLoop) else main.post(watchOnce)
    }

    private fun stopWatch() {
        main.removeCallbacks(watchLoop)
        main.removeCallbacks(watchOnce)
    }

    private fun scanWatch() {
        if (!watchPrefs.watchOn) return
        // A scenario owns the screen while it runs; do not interfere with it.
        if (running) return
        val rules = watchPrefs.watchRules.filter { it.enabled && it.text.isNotBlank() }
        if (rules.isEmpty()) return

        val root = try { rootInActiveWindow } catch (_: Throwable) { null } ?: return
        val pkg = root.packageName?.toString().orEmpty()
        if (pkg == packageName) return // never tap inside AutoTap itself
        val only = watchPrefs.watchApps
        if (only.isNotEmpty() && pkg !in only) return

        val cs = watchPrefs.watchCaseSensitive
        val now = SystemClock.elapsedRealtime()
        val cooldown = watchPrefs.watchCooldownMs.toLong()

        val nodes = ArrayList<AccessibilityNodeInfo>(128)
        collectNodes(root, nodes, 0)

        for (rule in rules) {
            if (now - (watchLastTap[rule.text] ?: 0L) < cooldown) continue
            for (n in nodes) {
                val hit = try {
                    n.isVisibleToUser && (rule.matches(n.text, cs) || rule.matches(n.contentDescription, cs))
                } catch (_: Throwable) { false }
                if (!hit) continue
                val how = tapNode(n) ?: continue
                watchLastTap[rule.text] = now
                recordWatch(rule.text, pkg, how)
                return // one tap per pass; the next scan picks up what is left
            }
        }
    }

    private fun collectNodes(n: AccessibilityNodeInfo?, out: MutableList<AccessibilityNodeInfo>, depth: Int) {
        if (n == null || depth > WATCH_MAX_DEPTH || out.size >= WATCH_MAX_NODES) return
        out += n
        for (i in 0 until n.childCount) {
            collectNodes(try { n.getChild(i) } catch (_: Throwable) { null }, out, depth + 1)
        }
    }

    /**
     * Clicks [node] or its nearest clickable parent; returns how it was tapped, or null.
     * ACTION_CLICK first, because it needs no touchscreen and so works on a TV.
     */
    private fun tapNode(node: AccessibilityNodeInfo): String? {
        var n: AccessibilityNodeInfo? = node
        var up = 0
        while (n != null && up <= WATCH_MAX_CLICK_PARENTS) {
            val clicked = try {
                n.isClickable && n.isEnabled && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            } catch (_: Throwable) { false }
            if (clicked) return "bấm nút"
            n = try { n.parent } catch (_: Throwable) { null }
            up++
        }
        if (watchPrefs.watchClickableOnly) return null

        val r = Rect().also { node.getBoundsInScreen(it) }
        if (r.width() <= 0 || r.height() <= 0) return null
        // Non-blocking on purpose: this runs on the main thread, unlike doGesture().
        val path = Path().apply { moveTo(clampX(r.centerX()), clampY(r.centerY())) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 60L))
            .build()
        val sent = try { dispatchGesture(gesture, null, null) } catch (_: Throwable) { false }
        return if (sent) "chạm toạ độ" else null
    }

    private fun recordWatch(text: String, pkg: String, how: String) {
        val line = "${WATCH_STAMP.format(Date())}  $text  -> $pkg  ($how)"
        synchronized(watchLog) {
            watchLog.add(0, line)
            while (watchLog.size > WATCH_LOG_MAX) watchLog.removeAt(watchLog.size - 1)
        }
        watchTaps++
        if (watchPrefs.watchToast) {
            try { Toast.makeText(this, "Đã bấm: $text", Toast.LENGTH_SHORT).show() } catch (_: Throwable) {}
        }
        notifyChange()
    }

    // ---- floating status bar (accessibility overlay) ----

    private fun showOverlay() = main.post {
        try {
            if (overlay != null) return@post
            val wm = getSystemService(WindowManager::class.java) ?: return@post
            val p = palette()
            val bg = GradientDrawable().apply {
                setColor(if (p.dark) 0xF21C1C1E.toInt() else 0xF2FFFFFF.toInt())
                cornerRadius = dp(22).toFloat()
                setStroke(dp(1), p.separator)
            }
            val status = text("Chuẩn bị…", 14f, p.text, 600).apply {
                maxLines = 1
                setPadding(dp(4), 0, dp(8), 0)
            }
            statusView = status
            val stop = text("■ Dừng", 14f, Color.WHITE, 700).apply {
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(6), dp(12), dp(6))
                background = rounded(p.red, dp(16).toFloat())
                setOnClickListener { stop() }
            }
            val bar = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = bg
                elevation = dp(8).toFloat()
                setPadding(dp(14), dp(8), dp(10), dp(8))
                addView(status)
                addView(stop)
            }
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= 22) WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                else WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = dp(38)
            }
            wm.addView(bar, lp)
            overlay = bar
        } catch (_: Throwable) {
            overlay = null; statusView = null
        }
    }

    private fun setStatus(s: String) = main.post { try { statusView?.text = s } catch (_: Throwable) {} }

    private fun hideOverlay() = main.post {
        try { overlay?.let { getSystemService(WindowManager::class.java)?.removeView(it) } } catch (_: Throwable) {}
        overlay = null
        statusView = null
    }

    private fun notifyChange() = main.post { listener?.invoke() }

    companion object {
        @Volatile var instance: AutoClickService? = null
        @Volatile var running = false
        var listener: (() -> Unit)? = null

        /** Whether the accessibility service is bound and ready to inject gestures. */
        fun isEnabled(): Boolean = instance != null

        /** Starts a scenario. Returns false if the service is not enabled. */
        fun run(scenario: Scenario, params: Map<String, String> = emptyMap()): Boolean {
            val svc = instance ?: return false
            return try { svc.startRun(scenario, params); true } catch (_: Throwable) { false }
        }

        fun stop() {
            try { instance?.stopRun() } catch (_: Throwable) {}
        }

        // ---- text watcher ----

        private const val WATCH_DEBOUNCE_MS = 150L
        private const val WATCH_MAX_DEPTH = 40
        private const val WATCH_MAX_NODES = 1500
        private const val WATCH_MAX_CLICK_PARENTS = 6
        private const val WATCH_LOG_MAX = 30
        private val WATCH_STAMP = SimpleDateFormat("HH:mm:ss", Locale.US)

        /** Taps the watcher has made since the service started. */
        @Volatile var watchTaps = 0

        private val watchLog = ArrayList<String>()

        fun watchLogLines(): List<String> = synchronized(watchLog) { ArrayList(watchLog) }

        fun clearWatchLog() {
            synchronized(watchLog) { watchLog.clear() }
            watchTaps = 0
            instance?.notifyChange()
        }

        /** Push changed watcher settings into the running service. */
        fun refreshWatch(ctx: Context) {
            instance?.let {
                it.watchPrefs = Prefs(ctx)
                try { it.reloadWatch() } catch (_: Throwable) {}
            }
            listener?.invoke()
        }
    }
}
