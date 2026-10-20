package com.dsoft.autotap

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
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

    override fun onServiceConnected() {
        instance = this
        notifyChange()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        stopRun()
        instance = null
        notifyChange()
        super.onDestroy()
    }

    // ---- gesture primitives (blocking; call off the main thread) ----

    private fun doGesture(path: Path, startMs: Long, durationMs: Long): Boolean {
        val stroke = GestureDescription.StrokeDescription(path, startMs, durationMs.coerceAtLeast(1))
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val latch = CountDownLatch(1)
        val ok = AtomicBoolean(false)
        val dispatched = try {
            dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(d: GestureDescription?) { ok.set(true); latch.countDown() }
                override fun onCancelled(d: GestureDescription?) { latch.countDown() }
            }, main)
        } catch (_: Exception) {
            false
        }
        if (!dispatched) return false
        return try {
            latch.await(durationMs + 5_000, TimeUnit.MILLISECONDS)
            ok.get()
        } catch (_: InterruptedException) {
            false
        }
    }

    fun tap(x: Int, y: Int): Boolean = doGesture(Path().apply { moveTo(x.toFloat(), y.toFloat()) }, 0, 50)

    fun longPress(x: Int, y: Int, ms: Long): Boolean =
        doGesture(Path().apply { moveTo(x.toFloat(), y.toFloat()) }, 0, ms.coerceAtLeast(400))

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, ms: Long): Boolean = doGesture(
        Path().apply { moveTo(x1.toFloat(), y1.toFloat()); lineTo(x2.toFloat(), y2.toFloat()) },
        0, ms.coerceAtLeast(50),
    )

    fun back(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
    fun home(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)
    fun recents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)

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
        stopRun()
        cancelled = false
        val prefs = Prefs(this)
        val t = HandlerThread("autotap-runner").apply { start() }
        worker = t
        handler = Handler(t.looper)
        running = true
        notifyChange()
        handler?.post {
            try {
                execute(scenario, params, prefs)
            } catch (_: Exception) {
            } finally {
                main.post { finishRun() }
            }
        }
    }

    private fun execute(scenario: Scenario, params: Map<String, String>, prefs: Prefs) {
        val repeats = scenario.repeatCount.coerceAtLeast(1)
        val defaultDelay = scenario.defaultDelayMs.coerceAtLeast(0)
        val continueOnFail = prefs.continueOnFail
        loop@ for (round in 0 until repeats) {
            for (raw in scenario.steps) {
                if (cancelled) break@loop
                val step = Params.apply(raw, params)
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
        worker?.quitSafely()
        worker = null
        handler = null
        if (running) {
            running = false
            notifyChange()
        }
    }

    private fun finishRun() {
        worker?.quitSafely()
        worker = null
        handler = null
        if (running) {
            running = false
            notifyChange()
        }
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
            svc.startRun(scenario, params)
            return true
        }

        fun stop() {
            instance?.stopRun()
        }
    }
}
