package com.dsoft.healthassistant

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.Calendar
import java.util.Locale

/**
 * Water intake, per user: a daily target, the size of one cup, and a `date -> ml` log.
 * Stored as JSON in filesDir, same shape/idiom as [Store].
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
        runCatching { WaterWidget.refreshAll(ctx) } // keep the widget's numbers current
        Unit
    }

    // ---- config ----
    fun targetMl(ctx: Context): Int = read(ctx).optInt("targetMl", DEFAULT_TARGET_ML).coerceIn(100, 10000)
    fun cupMl(ctx: Context): Int = read(ctx).optInt("cupMl", DEFAULT_CUP_ML).coerceIn(20, 2000)

    fun setTargetMl(ctx: Context, ml: Int) = write(ctx, read(ctx).put("targetMl", ml.coerceIn(100, 10000)))
    fun setCupMl(ctx: Context, ml: Int) = write(ctx, read(ctx).put("cupMl", ml.coerceIn(20, 2000)))

    // ---- log ----
    fun amount(ctx: Context, date: String): Int = read(ctx).optJSONObject("log")?.optInt(date, 0) ?: 0

    fun setAmount(ctx: Context, date: String, ml: Int) {
        val root = read(ctx)
        val log = root.optJSONObject("log") ?: JSONObject()
        val v = ml.coerceIn(0, 50_000)
        if (v == 0) log.remove(date) else log.put(date, v)
        write(ctx, root.put("log", log))
        if (v > 0) Store.snapshotTargets(ctx, date) // this day now has activity — remember its targets
    }

    fun add(ctx: Context, date: String, ml: Int) = setAmount(ctx, date, amount(ctx, date) + ml)

    /** One tap on the widget: adds a cup to *today* and returns the new total. */
    fun addCup(ctx: Context): Int {
        val d = Store.today()
        add(ctx, d, cupMl(ctx))
        return amount(ctx, d)
    }

    /** Every logged day of one month, as `"yyyy-MM-dd" -> ml`. */
    fun month(ctx: Context, year: Int, month0: Int): Map<String, Int> {
        val log = read(ctx).optJSONObject("log") ?: return emptyMap()
        val prefix = String.format(Locale.US, "%04d-%02d-", year, month0 + 1)
        val out = HashMap<String, Int>()
        log.keys().forEach { k -> if (k.startsWith(prefix)) out[k] = log.optInt(k, 0) }
        return out
    }

    fun cups(ml: Int, cupMl: Int): Int = if (cupMl <= 0) 0 else ml / cupMl

    fun dateOf(year: Int, month0: Int, day: Int): String =
        String.format(Locale.US, "%04d-%02d-%02d", year, month0 + 1, day)

    fun monthLabel(year: Int, month0: Int): String = "Tháng ${month0 + 1}/$year"

    /** Calendar positioned on the first day of the month that [date] falls in. */
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
