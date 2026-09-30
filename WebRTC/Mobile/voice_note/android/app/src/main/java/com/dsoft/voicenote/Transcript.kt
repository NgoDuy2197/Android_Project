package com.dsoft.voicenote

import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Appends entries to one UTF-8 file per day:
 *
 *     08:15:32:
 *     - Nội dung câu nói
 *     (blank line)
 *
 * Every write is opened, fsynced and closed, so a crash loses at most the
 * entry still being spoken.
 */
class TranscriptWriter(private val dir: File, timeFormat: String) {
    private val timeFmt = try {
        SimpleDateFormat(timeFormat, Locale.getDefault())
    } catch (e: IllegalArgumentException) {
        SimpleDateFormat(Prefs.DEFAULT_TIME_FORMAT, Locale.getDefault())
    }
    private val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    @Synchronized
    fun write(startMs: Long, text: String) {
        if (!dir.exists()) dir.mkdirs()
        val date = Date(startMs)
        FileOutputStream(fileFor(dir, dayFmt.format(date)), true).use { out ->
            out.write("${timeFmt.format(date)}:\n- $text\n\n".toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
    }

    companion object {
        fun fileFor(dir: File, day: String) = File(dir, "transcript_$day.txt")
        fun today(dir: File) = fileFor(dir, SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()))
    }
}

/**
 * Turns the recognizer's stream of partial/final results into entries.
 * Each final result (the recognizer ends one on silence) becomes its own entry,
 * unless the next utterance starts within [pauseMs], in which case they are joined.
 */
class Segmenter(private val pauseMs: Long, private val sink: (Long, String) -> Unit) {
    private val pending = StringBuilder()
    private var pendingStart = 0L
    private var lastFinalAt = 0L
    private var utterStart = 0L

    fun onPartial(text: String, now: Long) {
        if (text.isNotBlank() && utterStart == 0L) utterStart = now
    }

    fun onFinal(text: String, now: Long) {
        val start = if (utterStart != 0L) utterStart else now
        utterStart = 0L
        val t = text.trim()
        if (t.isEmpty()) return
        if (pending.isNotEmpty() && start - lastFinalAt > pauseMs) flush()
        if (pending.isEmpty()) pendingStart = start else pending.append(' ')
        pending.append(t)
        lastFinalAt = now
    }

    /** Call regularly: writes the pending entry once the silence is long enough. */
    fun tick(now: Long) {
        if (pending.isNotEmpty() && utterStart == 0L && now - lastFinalAt > pauseMs) flush()
    }

    /** If [sink] throws, the text stays pending and is retried on the next tick. */
    fun flush() {
        if (pending.isEmpty()) return
        val text = pending.toString().replaceFirstChar { it.titlecase(Locale.getDefault()) }
        sink(pendingStart, text)
        pending.setLength(0)
    }
}
