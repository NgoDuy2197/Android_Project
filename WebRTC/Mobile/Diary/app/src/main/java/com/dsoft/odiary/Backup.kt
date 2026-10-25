package com.dsoft.odiary

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Backup/restore the whole diary as a ZIP. Inside: year/month/day/{day.json, photos} + _global_bg.jpg.
 * Restore MERGES: day.json events are unioned by id; empty day fields are filled from the backup.
 */
object Backup {

    fun export(ctx: Context, out: OutputStream) {
        val root = Store.root()
        ZipOutputStream(out.buffered()).use { zip ->
            addDir(zip, root, root)
        }
    }

    /** Zip a single day's folder (paths relative to root, e.g. 2026/10/25/day.json) for Drive sync. */
    fun zipDay(date: String, out: OutputStream) {
        val dir = Store.dayDir(date)
        ZipOutputStream(out.buffered()).use { zip -> if (dir.exists()) addDir(zip, Store.root(), dir) }
    }

    private fun addDir(zip: ZipOutputStream, root: File, dir: File) {
        val files = dir.listFiles() ?: return
        for (f in files) {
            if (f.name.endsWith(".tmp")) continue
            if (f.isDirectory) addDir(zip, root, f)
            else {
                val rel = f.absolutePath.removePrefix(root.absolutePath).trimStart('/', '\\').replace('\\', '/')
                zip.putNextEntry(ZipEntry(rel))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    /** Returns number of files restored. */
    fun restore(ctx: Context, input: InputStream): Int {
        val root = Store.root()
        var n = 0
        ZipInputStream(input.buffered()).use { zip ->
            var e = zip.nextEntry
            while (e != null) {
                if (!e.isDirectory) {
                    // Accept a zip made by downloading the Drive "ODiary" folder (everything nested under ODiary/).
                    val rel = e.name.replace('\\', '/').trimStart('/').removePrefix("ODiary/")
                    if (rel.endsWith("day.json")) {
                        val bytes = zip.readBytes()
                        mergeDayJson(root, rel, bytes)
                    } else if (rel.isNotEmpty() && !rel.contains("..")) {
                        val dst = File(root, rel)
                        dst.parentFile?.mkdirs()
                        dst.outputStream().use { zip.copyTo(it) }
                    }
                    n++
                }
                zip.closeEntry()
                e = zip.nextEntry
            }
        }
        return n
    }

    private fun mergeDayJson(root: File, rel: String, bytes: ByteArray) {
        val dst = File(root, rel)
        dst.parentFile?.mkdirs()
        val incoming = try { DayEntry.fromJson(JSONObject(String(bytes, Charsets.UTF_8))) } catch (_: Exception) { return }
        if (!dst.exists()) { dst.writeText(incoming.toJson().toString()); return }
        val existing = try { DayEntry.fromJson(JSONObject(dst.readText())) } catch (_: Exception) { incoming }
        // union events by id
        val ids = existing.events.map { it.id }.toHashSet()
        for (ev in incoming.events) if (ev.id !in ids) existing.events.add(ev)
        existing.events.sortBy { it.timeMs }
        if (existing.label.isEmpty()) existing.label = incoming.label
        if (existing.emoji.isEmpty()) existing.emoji = incoming.emoji
        if (existing.bg.isEmpty()) existing.bg = incoming.bg
        if (existing.padding < 0) existing.padding = incoming.padding
        if (existing.compiled.isEmpty()) existing.compiled = incoming.compiled
        dst.writeText(existing.toJson().toString())
    }
}
