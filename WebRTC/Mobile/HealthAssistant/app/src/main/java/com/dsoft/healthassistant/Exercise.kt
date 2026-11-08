package com.dsoft.healthassistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Calendar
import java.util.Locale
import kotlin.math.roundToInt

/** One logged workout: when ([t]), what ([name]), how long ([minutes]) and calories burned ([kcal]). */
class ExerciseEntry(val t: Long, val name: String, val minutes: Int, val kcal: Int)

/** A workout preset with a MET value used to estimate burned calories. */
class ExercisePreset(val vi: String, val en: String, val met: Double) {
    fun label() = tr(vi, en)
}

/**
 * Exercise log, per user: a daily "burn" goal and a timestamped log of workouts
 * ("entries": date -> [{t, name, min, kcal}]). Calories burned are estimated with the standard
 * MET formula (MET × weightKg × hours) but stay editable per entry.
 */
object Exercise {
    const val DEFAULT_GOAL_KCAL = 300

    val PRESETS = listOf(
        ExercisePreset("Đi bộ", "Walking", 3.5),
        ExercisePreset("Chạy bộ", "Running", 9.8),
        ExercisePreset("Đạp xe", "Cycling", 7.5),
        ExercisePreset("Bơi", "Swimming", 8.0),
        ExercisePreset("Gym / tạ", "Gym / weights", 5.0),
        ExercisePreset("Yoga", "Yoga", 3.0),
        ExercisePreset("Thể thao", "Sports", 7.0),
        ExercisePreset("Khác", "Other", 4.0),
    )

    private val lock = Any()

    private fun uid(ctx: Context) = Users.currentId(ctx).ifBlank { "default" }
    fun fileName(id: String) = "exercise_$id.json"
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
        runCatching { SummaryWidget.refreshAll(ctx) }
        Unit
    }

    // ---- config ----
    fun goalKcal(ctx: Context): Int = read(ctx).optInt("goalKcal", DEFAULT_GOAL_KCAL).coerceIn(50, 5000)
    fun setGoalKcal(ctx: Context, v: Int) = write(ctx, read(ctx).put("goalKcal", v.coerceIn(50, 5000)))

    /** Estimate burned calories: MET × weightKg × hours (rounded). */
    fun estimate(met: Double, weightKg: Double, minutes: Int): Int =
        (met * weightKg.coerceAtLeast(1.0) * (minutes.coerceAtLeast(0) / 60.0)).roundToInt()

    // ---- log ----
    fun burned(ctx: Context, date: String): Int {
        var sum = 0
        read(ctx).optJSONObject("entries")?.optJSONArray(date)?.let { arr ->
            for (i in 0 until arr.length()) sum += arr.optJSONObject(i)?.optInt("kcal") ?: 0
        }
        return sum
    }

    fun minutes(ctx: Context, date: String): Int {
        var sum = 0
        read(ctx).optJSONObject("entries")?.optJSONArray(date)?.let { arr ->
            for (i in 0 until arr.length()) sum += arr.optJSONObject(i)?.optInt("min") ?: 0
        }
        return sum
    }

    /** All workouts logged for [date], oldest first. */
    fun entries(ctx: Context, date: String): List<ExerciseEntry> {
        val list = ArrayList<ExerciseEntry>()
        read(ctx).optJSONObject("entries")?.optJSONArray(date)?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                list.add(ExerciseEntry(o.optLong("t"), o.optString("name"), o.optInt("min"), o.optInt("kcal")))
            }
        }
        return list.sortedBy { it.t }
    }

    fun addEntry(ctx: Context, date: String, name: String, minutes: Int, kcal: Int, t: Long = System.currentTimeMillis()) {
        if (kcal <= 0 && minutes <= 0) return
        val root = read(ctx)
        val entries = root.optJSONObject("entries") ?: JSONObject()
        val arr = entries.optJSONArray(date) ?: JSONArray()
        arr.put(JSONObject().put("t", t).put("name", name).put("min", minutes).put("kcal", kcal))
        entries.put(date, arr)
        write(ctx, root.put("entries", entries))
        Store.snapshotTargets(ctx, date) // this day now has activity — remember its targets
    }

    fun removeEntry(ctx: Context, date: String, t: Long) {
        val root = read(ctx)
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

    /** Every logged day of one month, as `"yyyy-MM-dd" -> kcal burned`. */
    fun month(ctx: Context, year: Int, month0: Int): Map<String, Int> {
        val prefix = String.format(Locale.US, "%04d-%02d-", year, month0 + 1)
        val out = HashMap<String, Int>()
        read(ctx).optJSONObject("entries")?.let { e ->
            e.keys().forEach { k ->
                if (k.startsWith(prefix)) {
                    var s = 0
                    e.optJSONArray(k)?.let { for (i in 0 until it.length()) s += it.optJSONObject(i)?.optInt("kcal") ?: 0 }
                    out[k] = s
                }
            }
        }
        return out
    }

    fun dateOf(year: Int, month0: Int, day: Int): String =
        String.format(Locale.US, "%04d-%02d-%02d", year, month0 + 1, day)

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

    fun monthLabel(year: Int, month0: Int): String = tr("Tháng ${month0 + 1}/$year", "${month0 + 1}/$year")
}
