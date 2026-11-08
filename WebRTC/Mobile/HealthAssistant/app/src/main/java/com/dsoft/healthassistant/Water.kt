package com.dsoft.healthassistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Calendar
import java.util.Locale

/** One logged drink: when ([t], epoch millis; 0 = legacy/unknown) and how much ([ml]). */
class WaterEntry(val t: Long, val ml: Int)

/**
 * Water intake, per user: a daily target, the default cup size, and a timestamped log of drinks
 * ("entries": date -> [{t, ml}]). Older builds stored a single daily total under "log"; that is
 * still read and summed in, so no data is lost.
 */
object Water {
    const val DEFAULT_TARGET_ML = 2000
    const val DEFAULT_CUP_ML = 250

    private val lock = Any()

    private fun uid(ctx: Context) = Users.currentId(ctx).ifBlank { "default" }
    fun fileName(id: String) = "water_$id.json"
    private fun file(ctx: Context) = File(ctx.filesDir, fileName(uid(ctx)))

    private fun read(ctx: Context): JSONObject = synchronized(lock) {
        val f = file(ctx)
        if (!f.exists()) return JSONObject()
        return try { JSONObject(f.readText()) } catch (_: Exception) { JSONObject() }
    }

    private fun write(ctx: Context, root: JSONObject) = synchronized(lock) {
        val f = file(ctx)
        val tmp = File(f.parentFile, "${f.name}.tmp")
        tmp.writeText(root.toString())
        tmp.renameTo(f)
        runCatching { WaterWidget.refreshAll(ctx) }
        runCatching { SummaryWidget.refreshAll(ctx) }
        Unit
    }

    // ---- config ----
    fun targetMl(ctx: Context): Int = read(ctx).optInt("targetMl", DEFAULT_TARGET_ML).coerceIn(100, 10000)
    fun cupMl(ctx: Context): Int = read(ctx).optInt("cupMl", DEFAULT_CUP_ML).coerceIn(20, 2000)
    fun setTargetMl(ctx: Context, ml: Int) = write(ctx, read(ctx).put("targetMl", ml.coerceIn(100, 10000)))
    fun setCupMl(ctx: Context, ml: Int) = write(ctx, read(ctx).put("cupMl", ml.coerceIn(20, 2000)))

    // ---- log ----
    fun amount(ctx: Context, date: String): Int {
        val root = read(ctx)
        var sum = root.optJSONObject("log")?.optInt(date, 0) ?: 0
        root.optJSONObject("entries")?.optJSONArray(date)?.let { arr ->
            for (i in 0 until arr.length()) sum += arr.optJSONObject(i)?.optInt("ml") ?: 0
        }
        return sum
    }

    /** All drinks logged for [date], oldest first. A legacy daily total shows as one t=0 entry. */
    fun entries(ctx: Context, date: String): List<WaterEntry> {
        val root = read(ctx)
        val list = ArrayList<WaterEntry>()
        val legacy = root.optJSONObject("log")?.optInt(date, 0) ?: 0
        if (legacy > 0) list.add(WaterEntry(0, legacy))
        root.optJSONObject("entries")?.optJSONArray(date)?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                list.add(WaterEntry(o.optLong("t"), o.optInt("ml")))
            }
        }
        return list.sortedBy { it.t }
    }

    fun addEntry(ctx: Context, date: String, ml: Int, t: Long = System.currentTimeMillis()) {
        if (ml <= 0) return
        val root = read(ctx)
        val entries = root.optJSONObject("entries") ?: JSONObject()
        val arr = entries.optJSONArray(date) ?: JSONArray()
        arr.put(JSONObject().put("t", t).put("ml", ml))
        entries.put(date, arr)
        write(ctx, root.put("entries", entries))
        Store.snapshotTargets(ctx, date) // this day now has activity — remember its targets
    }

    /** Remove one drink by its timestamp (t = 0 removes the legacy daily total). */
    fun removeEntry(ctx: Context, date: String, t: Long) {
        val root = read(ctx)
        if (t == 0L) { root.optJSONObject("log")?.remove(date); write(ctx, root); return }
        val entries = root.optJSONObject("entries") ?: return
        val arr = entries.optJSONArray(date) ?: return
        val keep = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optLong("t") != t) keep.put(o)
        }
        entries.put(date, keep)
        write(ctx, root.put("entries", entries))
    }

    /** Back-compat: add a plain amount (used by the dashboard quick-add). */
    fun add(ctx: Context, date: String, ml: Int) = addEntry(ctx, date, ml)

    /** One tap on the widget: a default cup for today; returns the new total. */
    fun addCup(ctx: Context): Int {
        val d = Store.today()
        addEntry(ctx, d, cupMl(ctx))
        return amount(ctx, d)
    }

    /** Every logged day of one month, as `"yyyy-MM-dd" -> total ml`. */
    fun month(ctx: Context, year: Int, month0: Int): Map<String, Int> {
        val root = read(ctx)
        val prefix = String.format(Locale.US, "%04d-%02d-", year, month0 + 1)
        val out = HashMap<String, Int>()
        root.optJSONObject("log")?.let { l -> l.keys().forEach { k -> if (k.startsWith(prefix)) out[k] = (out[k] ?: 0) + l.optInt(k, 0) } }
        root.optJSONObject("entries")?.let { e ->
            e.keys().forEach { k ->
                if (k.startsWith(prefix)) {
                    var s = 0
                    e.optJSONArray(k)?.let { for (i in 0 until it.length()) s += it.optJSONObject(i)?.optInt("ml") ?: 0 }
                    out[k] = (out[k] ?: 0) + s
                }
            }
        }
        return out
    }

    fun cups(ml: Int, cupMl: Int): Int = if (cupMl <= 0) 0 else ml / cupMl

    /** Localized "N cups" label, with correct English singular/plural. */
    fun cupsLabel(ml: Int, cupMl: Int): String {
        val n = cups(ml, cupMl)
        return "$n " + tr("cốc", if (n == 1) "cup" else "cups")
    }

    fun dateOf(year: Int, month0: Int, day: Int): String =
        String.format(Locale.US, "%04d-%02d-%02d", year, month0 + 1, day)

    fun monthLabel(year: Int, month0: Int): String = tr("Tháng ${month0 + 1}/$year", "${month0 + 1}/$year")

    fun monthStart(date: String): Calendar {
        val c = Calendar.getInstance()
        runCatching {
            val parts = date.split("-")
            c.set(parts[0].toInt(), parts[1].toInt() - 1, 1, 0, 0, 0)
        }
        c.set(Calendar.DAY_OF_MONTH, 1)
        c.set(Calendar.MILLISECOND, 0)
        return c
    }
}
