package com.dsoft.voicenote

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One transcript entry: time label + what was said. */
data class Entry(val time: String, val text: String)

/**
 * Appends entries in this format:
 *
 *     08:15:32:
 *     - Nội dung câu nói
 *     (blank line)
 *
 * [target] picks the file for an entry (per session or per day). Every write is
 * opened, fsynced and closed, so a crash loses at most the entry being spoken.
 */
class TranscriptWriter(timeFormat: String, private val target: (Long) -> File) {
    private val timeFmt = try {
        SimpleDateFormat(timeFormat, Locale.getDefault())
    } catch (e: IllegalArgumentException) {
        SimpleDateFormat(Prefs.DEFAULT_TIME_FORMAT, Locale.getDefault())
    }

    /** Returns the time label that was written. */
    @Synchronized
    fun write(startMs: Long, text: String): String {
        val label = timeFmt.format(Date(startMs))
        val file = target(startMs)
        file.parentFile?.mkdirs()
        FileOutputStream(file, true).use { out ->
            out.write("$label:\n- $text\n\n".toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        return label
    }
}

/** The transcript folder and its files. */
object Transcripts {
    private val badChars = Regex("[\\\\/:*?\"<>|\\r\\n\\t]")

    fun dir(ctx: Context): File =
        ctx.getExternalFilesDir("transcripts") ?: File(ctx.filesDir, "transcripts")

    /** Newest first. */
    fun list(ctx: Context): List<File> =
        dir(ctx).listFiles { f -> f.isFile && f.name.endsWith(".txt") }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()

    fun sessionFileName(startMs: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH-mm-ss", Locale.US).format(Date(startMs)) + ".txt"

    fun dayFileName(startMs: Long): String =
        "transcript_" + SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(startMs)) + ".txt"

    /** Display/rename-safe file name, without extension; empty if nothing usable is left. */
    fun cleanName(name: String): String = name.replace(badChars, " ").trim().trimEnd('.').take(80)

    /** Reads a transcript back into entries (tolerates hand-edited files). */
    fun parse(f: File): List<Entry> {
        val text = runCatching { f.readText() }.getOrDefault("")
        return text.split(Regex("\\n\\s*\\n")).mapNotNull { block ->
            val lines = block.trim().lines().filter { it.isNotBlank() }
            when {
                lines.isEmpty() -> null
                lines.size > 1 && lines[0].endsWith(":") ->
                    Entry(lines[0].dropLast(1), lines.drop(1).joinToString("\n") { it.removePrefix("- ") })
                else -> Entry("", lines.joinToString("\n") { it.removePrefix("- ") })
            }
        }
    }

    fun countEntries(f: File): Int =
        runCatching { f.useLines { s -> s.count { it.startsWith("- ") } } }.getOrDefault(0)
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
