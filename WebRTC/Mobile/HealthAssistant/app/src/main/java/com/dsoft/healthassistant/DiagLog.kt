package com.dsoft.healthassistant

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet

/**
 * In-app diagnostic log so the user can SEE where the AI (especially the offline model) is at —
 * which step it reached, how long each took, and the exact error if it fails — without needing a
 * computer + logcat. A small in-memory ring buffer; also mirrored to Android's Logcat.
 */
object DiagLog {
    private const val CAP = 300
    private val tf = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    class Line(val time: Long, val level: Int, val tag: String, val msg: String) {
        fun format(): String = "${tf.format(Date(time))}  [$tag] $msg"
    }

    const val I = 0
    const val W = 1
    const val E = 2

    private val lines = ArrayList<Line>(CAP)
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    @Synchronized
    private fun add(level: Int, tag: String, msg: String) {
        lines.add(Line(System.currentTimeMillis(), level, tag, msg))
        if (lines.size > CAP) lines.removeAt(0)
    }

    fun i(tag: String, msg: String) { add(I, tag, msg); Log.i(tag, msg); notifyChanged() }
    fun w(tag: String, msg: String) { add(W, tag, msg); Log.w(tag, msg); notifyChanged() }
    fun e(tag: String, msg: String, t: Throwable? = null) {
        val full = if (t != null) "$msg — ${t.javaClass.simpleName}: ${t.message}" else msg
        add(E, tag, full); Log.e(tag, msg, t); notifyChanged()
    }

    @Synchronized fun snapshot(): List<Line> = ArrayList(lines)
    @Synchronized fun clear() { lines.clear() }

    fun text(): String = snapshot().joinToString("\n") { it.format() }

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }
    private fun notifyChanged() = main.post { listeners.forEach { runCatching { it() } } }
}
