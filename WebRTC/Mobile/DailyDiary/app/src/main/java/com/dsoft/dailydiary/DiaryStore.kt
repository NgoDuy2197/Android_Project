package com.dsoft.dailydiary

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

/** One diary event: optional photo + text, at a point in time. */
data class Entry(
    val id: String = UUID.randomUUID().toString(),
    val time: Long,
    val text: String,
    /** File name inside the day folder, or null when the event is text only. */
    val photo: String? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("time", time).put("text", text)
        .apply { if (photo != null) put("photo", photo) }

    companion object {
        fun fromJson(o: JSONObject) = Entry(
            id = o.getString("id"),
            time = o.getLong("time"),
            text = o.optString("text"),
            photo = o.optString("photo").ifEmpty { null },
        )
    }
}

/** What the calendar needs for one cell. */
data class DayInfo(val date: LocalDate, val entryCount: Int, val photos: List<File>)

/**
 * Plain-file storage, laid out exactly like the backup zip:
 *
 *   files/diary/2026/10/24/entries.json
 *   files/diary/2026/10/24/IMG_083015_ab12.jpg
 *   files/diary/2026/10/24/NhatKy_2026-10-24.pdf   (daily summary)
 */
object DiaryStore {
    const val ENTRIES = "entries.json"
    const val SUMMARY_PREFIX = "NhatKy_"

    lateinit var root: File
        private set
    private val lock = Any()

    fun init(context: Context) {
        root = File(context.filesDir, "diary").apply { mkdirs() }
    }

    fun dayDir(date: LocalDate) =
        File(root, "%04d/%02d/%02d".format(date.year, date.monthValue, date.dayOfMonth))

    fun summaryFile(date: LocalDate) = File(dayDir(date), "$SUMMARY_PREFIX$date.pdf")

    fun entries(date: LocalDate): List<Entry> = synchronized(lock) { readEntries(dayDir(date)) }

    fun photos(date: LocalDate): List<File> {
        val dir = dayDir(date)
        return entries(date).mapNotNull { e -> e.photo?.let { File(dir, it) }?.takeIf { it.exists() } }
    }

    fun upsert(date: LocalDate, entry: Entry) = synchronized(lock) {
        val dir = dayDir(date).apply { mkdirs() }
        val list = readEntries(dir).toMutableList()
        val i = list.indexOfFirst { it.id == entry.id }
        val old = if (i >= 0) list[i] else null
        if (i >= 0) list[i] = entry else list += entry
        writeEntries(dir, list)
        // Photo replaced or removed while editing → drop the orphaned file.
        if (old?.photo != null && old.photo != entry.photo) File(dir, old.photo).delete()
    }

    fun delete(date: LocalDate, id: String) = synchronized(lock) {
        val dir = dayDir(date)
        val list = readEntries(dir).toMutableList()
        val e = list.firstOrNull { it.id == id } ?: return@synchronized
        list.remove(e)
        e.photo?.let { File(dir, it).delete() }
        if (list.isEmpty()) {
            dir.deleteRecursively()
        } else {
            writeEntries(dir, list)
        }
    }

    fun month(ym: YearMonth): Map<LocalDate, DayInfo> {
        val monthDir = File(root, "%04d/%02d".format(ym.year, ym.monthValue))
        val days = monthDir.listFiles { f -> f.isDirectory } ?: return emptyMap()
        val out = HashMap<LocalDate, DayInfo>()
        for (d in days) {
            val day = d.name.toIntOrNull() ?: continue
            if (day !in 1..ym.lengthOfMonth()) continue
            val date = ym.atDay(day)
            val list = entries(date)
            if (list.isEmpty()) continue
            out[date] = DayInfo(date, list.size, list.mapNotNull { e ->
                e.photo?.let { File(d, it) }?.takeIf { it.exists() }
            })
        }
        return out
    }

    /** True when the day has events that are not yet in an up-to-date summary file. */
    fun needsSummary(date: LocalDate): Boolean {
        val json = File(dayDir(date), ENTRIES)
        if (!json.exists()) return false
        val pdf = summaryFile(date)
        return !pdf.exists() || pdf.lastModified() < json.lastModified()
    }

    fun newPhotoName(time: Long): String {
        val t = java.time.Instant.ofEpochMilli(time).atZone(java.time.ZoneId.systemDefault())
        return "IMG_%02d%02d%02d_%s.jpg".format(t.hour, t.minute, t.second, UUID.randomUUID().toString().take(4))
    }

    // ---- JSON io (also used by the backup merge) ----

    fun parseEntries(text: String): List<Entry> = runCatching {
        val arr = JSONObject(text).getJSONArray("entries")
        (0 until arr.length()).map { Entry.fromJson(arr.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    fun serializeEntries(list: List<Entry>): String {
        val arr = JSONArray()
        list.sortedBy { it.time }.forEach { arr.put(it.toJson()) }
        return JSONObject().put("version", 1).put("entries", arr).toString(2)
    }

    private fun readEntries(dir: File): List<Entry> {
        val f = File(dir, ENTRIES)
        return if (f.exists()) parseEntries(f.readText()) else emptyList()
    }

    private fun writeEntries(dir: File, list: List<Entry>) {
        val tmp = File(dir, "$ENTRIES.tmp")
        tmp.writeText(serializeEntries(list))
        tmp.renameTo(File(dir, ENTRIES)) || run { File(dir, ENTRIES).writeText(tmp.readText()); tmp.delete() }
    }
}
