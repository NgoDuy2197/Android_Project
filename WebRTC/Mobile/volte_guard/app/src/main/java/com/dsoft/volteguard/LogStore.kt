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
    private const val MAX = 400
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
    private lateinit var file: File
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines

    fun init(ctx: Context) {
        file = File(ctx.filesDir, "guard.log")
        _lines.value = runCatching { file.readLines().takeLast(MAX) }.getOrDefault(emptyList())
    }

    @Synchronized
    fun i(msg: String) {
        Log.i(TAG, msg)
        val line = "${fmt.format(Date())}  $msg"
        val next = (_lines.value + line).takeLast(MAX)
        _lines.value = next
        runCatching {
            if (file.length() > 256 * 1024) file.writeText(next.joinToString("\n", postfix = "\n"))
            else file.appendText(line + "\n")
        }
    }

    @Synchronized
    fun clear() {
        _lines.value = emptyList()
        runCatching { file.writeText("") }
    }
}
