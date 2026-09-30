package com.dsoft.volteguard

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Tiny persistent log (ring buffer) shown in the UI and mirrored to logcat tag "VoLTEGuard". */
object LogStore {
    private const val TAG = "VoLTEGuard"
    private const val MAX = 300
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
    private lateinit var file: File
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines

    private var lastMsg: String? = null
    private var repeat = 0
    private val throttle = HashMap<String, Long>()

    fun init(ctx: Context) {
        file = File(ctx.filesDir, "guard.log")
        _lines.value = runCatching { file.readLines().takeLast(MAX) }.getOrDefault(emptyList())
    }

    @Synchronized
    fun i(msg: String) {
        Log.i(TAG, msg)
        val now = fmt.format(Date())
        val cur = _lines.value.toMutableList()
        // Collapse identical consecutive messages into one line with a "(xN)" counter.
        if (msg == lastMsg && cur.isNotEmpty()) {
            repeat++
            cur[cur.lastIndex] = "$now  $msg  (x${repeat + 1})"
        } else {
            lastMsg = msg
            repeat = 0
            cur.add("$now  $msg")
        }
        val next = cur.takeLast(MAX)
        _lines.value = next
        // Always persist the bounded ring buffer (≤MAX lines) so the file can never grow unbounded
        // and reflects the collapsed counter.
        runCatching { file.writeText(next.joinToString("\n", postfix = "\n")) }
    }

    /** Log at most once per [minMs] for the given [key]; drops repeats in between (anti-spam). */
    @Synchronized
    fun throttled(key: String, minMs: Long, msg: String) {
        val now = System.currentTimeMillis()
        if (now - (throttle[key] ?: 0L) < minMs) return
        throttle[key] = now
        i(msg)
    }

    @Synchronized
    fun clear() {
        _lines.value = emptyList()
        lastMsg = null
        repeat = 0
        throttle.clear()
        runCatching { file.writeText("") }
    }
}
