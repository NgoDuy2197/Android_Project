package com.dsoft.healthassistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One weigh-in: when ([t], epoch millis) and the weight ([kg]). */
class WeightEntry(val t: Long, val kg: Double)

/**
 * Weight log, per user: a time-ordered list of weigh-ins ("entries": [{t, kg}]). Adding a weigh-in
 * also updates the profile's current weight so BMI / TDEE stay in sync.
 */
object Weight {
    private val lock = Any()

    private fun uid(ctx: Context) = Users.currentId(ctx).ifBlank { "default" }
    fun fileName(id: String) = "weight_$id.json"
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
        Unit
    }

    /** All weigh-ins, oldest first. */
    fun entries(ctx: Context): List<WeightEntry> {
        val list = ArrayList<WeightEntry>()
        read(ctx).optJSONArray("entries")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                list.add(WeightEntry(o.optLong("t"), o.optDouble("kg")))
            }
        }
        return list.sortedBy { it.t }
    }

    fun latest(ctx: Context): WeightEntry? = entries(ctx).lastOrNull()

    fun add(ctx: Context, kg: Double, t: Long = System.currentTimeMillis()) {
        if (kg <= 0) return
        val root = read(ctx)
        val arr = root.optJSONArray("entries") ?: JSONArray()
        arr.put(JSONObject().put("t", t).put("kg", kg))
        write(ctx, root.put("entries", arr))
        syncProfile(ctx)
    }

    fun removeAt(ctx: Context, t: Long) {
        val root = read(ctx)
        val arr = root.optJSONArray("entries") ?: return
        val keep = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optLong("t") != t) keep.put(o)
        }
        write(ctx, root.put("entries", keep))
        syncProfile(ctx)
    }

    /** Point the profile's current weight at the most recent weigh-in. */
    private fun syncProfile(ctx: Context) {
        val last = latest(ctx) ?: return
        val prof = Store.loadProfile(ctx)
        if (prof.weightKg != last.kg) { prof.weightKg = last.kg; Store.saveProfile(ctx, prof) }
    }
}
