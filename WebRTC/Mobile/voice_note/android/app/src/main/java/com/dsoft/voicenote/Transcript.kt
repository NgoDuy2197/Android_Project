package com.dsoft.voicenote

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One chat line: when it started ([at], epoch ms; 0 if unknown), its time label, and what was said. */
data class Entry(val at: Long, val time: String, val text: String)

/**
 * Saves entries as JSON. The whole file is rewritten atomically (tmp + fsync +
 * rename) on every entry, so a crash loses at most the line being spoken.
 * [target] picks the file for an entry (per session or per day).
 */
class TranscriptWriter(timeFormat: String, private val lang: String, private val target: (Long) -> File) {
    private val timeFmt = try {
        SimpleDateFormat(timeFormat, Locale.getDefault())
    } catch (e: IllegalArgumentException) {
        SimpleDateFormat(Prefs.DEFAULT_TIME_FORMAT, Locale.getDefault())
    }
    private var file: File? = null
    private val list = ArrayList<Entry>()

    @Synchronized
    fun write(startMs: Long, text: String): Entry {
        val f = target(startMs)
        if (f != file) {
            list.clear()
            if (f.exists()) list.addAll(Transcripts.read(f)) // "per day" file: append to it
            file = f
        }
        val e = Entry(startMs, timeFmt.format(Date(startMs)), text)
        list.add(e)
        try {
            Transcripts.save(f, list, lang)
        } catch (t: Exception) {
            list.removeAt(list.size - 1) // retried by the caller
            throw t
        }
        return e
    }
}

/** The transcript folder and its files (.json; old .txt files are still readable). */
object Transcripts {
    private val badChars = Regex("[\\\\/:*?\"<>|\\r\\n\\t]")
    private val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)

    fun dir(ctx: Context): File =
        ctx.getExternalFilesDir("transcripts") ?: File(ctx.filesDir, "transcripts")

    /** Newest first. */
    fun list(ctx: Context): List<File> =
        dir(ctx).listFiles { f -> f.isFile && (f.name.endsWith(".json") || f.name.endsWith(".txt")) }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()

    fun sessionFileName(startMs: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH-mm-ss", Locale.US).format(Date(startMs)) + ".json"

    fun dayFileName(startMs: Long): String =
        "transcript_" + SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(startMs)) + ".json"

    /** Display/rename-safe file name, without extension; empty if nothing usable is left. */
    fun cleanName(name: String): String = name.replace(badChars, " ").trim().trimEnd('.').take(80)

    fun toJson(entries: List<Entry>, lang: String): String {
        val arr = JSONArray()
        for (e in entries) arr.put(JSONObject().apply {
            put("time", e.time)
            if (e.at > 0) {
                put("timestamp", e.at)
                put("iso", synchronized(iso) { iso.format(Date(e.at)) })
            }
            put("text", e.text)
        })
        return JSONObject().apply {
            put("app", "VoiceNote")
            put("version", 2)
            put("language", lang)
            entries.firstOrNull { it.at > 0 }?.let { put("start", synchronized(iso) { iso.format(Date(it.at)) }) }
            put("entries", arr)
        }.toString(2)
    }

    /** Plain text for copy / .txt export: "08:15:32  Nội dung" per line. */
    fun toText(entries: List<Entry>): String =
        entries.joinToString("\n") { if (it.time.isEmpty()) it.text else "${it.time}  ${it.text}" }

    fun save(f: File, entries: List<Entry>, lang: String) {
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, f.name + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(toJson(entries, lang).toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        if (!tmp.renameTo(f)) {
            f.delete()
            if (!tmp.renameTo(f)) throw java.io.IOException("Không ghi được ${f.name}")
        }
    }

    fun read(f: File): List<Entry> = if (f.name.endsWith(".json")) readJson(f) else readTxt(f)

    private fun readJson(f: File): List<Entry> = runCatching {
        val arr = JSONObject(f.readText()).optJSONArray("entries") ?: return@runCatching emptyList()
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Entry(o.optLong("timestamp", 0), o.optString("time"), o.optString("text"))
        }
    }.getOrDefault(emptyList())

    /** v1 format: "08:15:32:\n- text\n\n" blocks (tolerates hand-edited files). */
    private fun readTxt(f: File): List<Entry> {
        val text = runCatching { f.readText() }.getOrDefault("")
        return text.split(Regex("\\n\\s*\\n")).mapNotNull { block ->
            val lines = block.trim().lines().filter { it.isNotBlank() }
            when {
                lines.isEmpty() -> null
                lines.size > 1 && lines[0].endsWith(":") ->
                    Entry(0, lines[0].dropLast(1), lines.drop(1).joinToString("\n") { it.removePrefix("- ") })
                else -> Entry(0, "", lines.joinToString("\n") { it.removePrefix("- ") })
            }
        }
    }

    fun countEntries(f: File): Int = read(f).size
}

/**
 * Turns recognizer results + mic level into chat lines ("Enter" = close a line).
 *
 * A line is closed once the level has stayed below the silence threshold for
 * the listening wait. It happens only once per line: while the silence goes on
 * nothing more is added, and the next line starts only when new speech arrives.
 * If the recognizer is still mid-utterance at that moment, [requestEnd] asks it
 * to finish and the line is closed as soon as its final text arrives.
 */
class Segmenter(private val requestEnd: () -> Unit, private val sink: (Long, String) -> Unit) {
    private val pending = StringBuilder()
    private var pendingStart = 0L
    private var utterStart = 0L
    private var lastPartial = ""
    private var lastLoud = 0L
    private var breakAfterFinal = false

    /** Text of the open line + what is being said right now (for the typing bubble). */
    fun liveText(partial: String): String = when {
        pending.isEmpty() -> partial
        partial.isBlank() -> pending.toString()
        else -> "$pending $partial"
    }

    fun onPartial(text: String, now: Long) {
        if (text.isBlank()) return
        if (utterStart == 0L) utterStart = now
        lastPartial = text
    }

    /** Final text of an utterance; "" when the recognizer gave up (then the last partial is kept). */
    fun onFinal(text: String, now: Long) {
        val start = if (utterStart != 0L) utterStart else now
        val t = text.trim().ifEmpty { if (utterStart != 0L) lastPartial.trim() else "" }
        utterStart = 0L
        lastPartial = ""
        if (t.isNotEmpty()) {
            if (pending.isEmpty()) pendingStart = start else pending.append(' ')
            pending.append(t)
        }
        if (breakAfterFinal) {
            breakAfterFinal = false
            flush()
        }
    }

    /** Call every ~100 ms with the current level and the session's thresholds. */
    fun tick(now: Long, level: Float, silence: Float, waitMs: Long) {
        if (level >= silence) {
            lastLoud = now
            return
        }
        if (now - lastLoud < waitMs) return
        if (utterStart != 0L) {
            if (!breakAfterFinal) {
                breakAfterFinal = true
                requestEnd()
            }
        } else if (pending.isNotEmpty()) {
            flush()
        }
    }

    /** If [sink] throws, the text stays pending and is retried on the next tick. */
    fun flush() {
        if (pending.isEmpty()) return
        val text = pending.toString().replaceFirstChar { it.titlecase(Locale.getDefault()) }
        sink(pendingStart, text)
        pending.setLength(0)
    }
}
