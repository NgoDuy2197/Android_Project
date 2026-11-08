package com.dsoft.healthassistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.pow
import kotlin.math.roundToInt

/** Body + goal profile the AI reviews meals against. */
class Profile(
    var heightCm: Double = 170.0,
    var weightKg: Double = 65.0,
    var gender: String = "male",          // "male" | "female"
    var age: Int = 30,
    var activity: Int = 1,                // index into ACTIVITY
    var goal: String = "lose",            // "lose" | "maintain" | "gain"
    var targetWeightKg: Double = 60.0,
    var weeklyRateKg: Double = 0.5,       // desired kg per week to lose/gain
    var notes: String = "",               // "một số thông số mong muốn" — free text goals/prefs
) {
    fun toJson() = JSONObject().apply {
        put("heightCm", heightCm); put("weightKg", weightKg); put("gender", gender)
        put("age", age); put("activity", activity); put("goal", goal)
        put("targetWeightKg", targetWeightKg); put("weeklyRateKg", weeklyRateKg); put("notes", notes)
    }

    companion object {
        val ACTIVITY = listOf("Ít", "Nhẹ", "Vừa", "Nhiều", "Rất cao")
        private val FACTOR = listOf(1.2, 1.375, 1.55, 1.725, 1.9)
        val GOALS = listOf("lose" to "Giảm cân", "maintain" to "Giữ cân", "gain" to "Tăng cân")

        fun fromJson(j: JSONObject) = Profile(
            heightCm = j.optDouble("heightCm", 170.0),
            weightKg = j.optDouble("weightKg", 65.0),
            gender = j.optString("gender", "male"),
            age = j.optInt("age", 30),
            activity = j.optInt("activity", 1),
            goal = j.optString("goal", "lose"),
            targetWeightKg = j.optDouble("targetWeightKg", 60.0),
            weeklyRateKg = j.optDouble("weeklyRateKg", 0.5),
            notes = j.optString("notes", ""),
        )
    }

    // ---- derived health numbers (Mifflin-St Jeor) ----
    fun bmr(): Int {
        val base = 10 * weightKg + 6.25 * heightCm - 5 * age
        return (base + if (gender == "female") -161 else 5).roundToInt()
    }

    fun tdee(): Int = (bmr() * (Profile.FACTOR.getOrElse(activity) { 1.2 })).roundToInt()

    /** Daily calorie target given the goal + desired weekly rate (1 kg ≈ 7700 kcal). */
    fun targetCalories(): Int {
        val tdee = tdee()
        val dailyDelta = (weeklyRateKg * 7700.0 / 7.0).roundToInt()
        return when (goal) {
            "lose" -> (tdee - dailyDelta).coerceAtLeast((bmr() * 1.1).roundToInt())
            "gain" -> tdee + dailyDelta
            else -> tdee
        }
    }

    fun bmi(): Double {
        val m = heightCm / 100.0
        if (m <= 0) return 0.0
        return weightKg / m.pow(2)
    }

    fun bmiLabel(): String {
        val b = bmi()
        return when {
            b < 18.5 -> "Gầy"
            b < 23 -> "Bình thường"
            b < 25 -> "Hơi thừa cân"
            b < 30 -> "Thừa cân"
            else -> "Béo phì"
        }
    }

    fun activityLabel() = ACTIVITY.getOrElse(activity) { ACTIVITY[0] }
    fun goalLabel() = GOALS.firstOrNull { it.first == goal }?.second ?: goal
}

/** One logged meal. */
class Meal(
    val id: Long = System.currentTimeMillis(),
    var date: String = Store.today(),
    var type: Int = 0,                    // index into MEAL_TYPES
    var time: String = "",               // "HH:mm"
    var items: String = "",              // free-text, one food per line (optionally with amount)
    var note: String = "",
    var reviewJson: String = "",         // cached AI review (JSON), empty if not reviewed yet
    var reviewAt: Long = 0,
) {
    fun toJson() = JSONObject().apply {
        put("id", id); put("date", date); put("type", type); put("time", time)
        put("items", items); put("note", note); put("reviewJson", reviewJson); put("reviewAt", reviewAt)
    }

    fun typeLabel() = MEAL_TYPES.getOrElse(type) { MEAL_TYPES[0] }

    companion object {
        val MEAL_TYPES = listOf("Sáng", "Trưa", "Tối", "Phụ")

        fun fromJson(j: JSONObject) = Meal(
            id = j.optLong("id", System.currentTimeMillis()),
            date = j.optString("date", Store.today()),
            type = j.optInt("type", 0),
            time = j.optString("time", ""),
            items = j.optString("items", ""),
            note = j.optString("note", ""),
            reviewJson = j.optString("reviewJson", ""),
            reviewAt = j.optLong("reviewAt", 0),
        )
    }
}

/** JSON-file store for the profile and the meal log, in the app's files dir. */
object Store {
    private val lock = Any()

    fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    fun prettyDate(date: String): String = try {
        val d = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(date)!!
        SimpleDateFormat("EEEE, dd/MM/yyyy", Locale("vi")).format(d).replaceFirstChar { it.uppercase() }
    } catch (_: Exception) { date }

    // ---- per-user file names (namespaced by the current user) ----
    private fun uid(ctx: Context) = Users.currentId(ctx).ifBlank { "default" }
    private fun profileFile(ctx: Context) = File(ctx.filesDir, "profile_${uid(ctx)}.json")
    private fun mealsFile(ctx: Context) = File(ctx.filesDir, "meals_${uid(ctx)}.json")
    private fun reviewsFile(ctx: Context) = File(ctx.filesDir, "day_reviews_${uid(ctx)}.json")

    // ---- profile ----
    fun loadProfile(ctx: Context): Profile = synchronized(lock) {
        val f = profileFile(ctx)
        if (!f.exists()) return Profile()
        return try { Profile.fromJson(JSONObject(f.readText())) } catch (_: Exception) { Profile() }
    }

    fun saveProfile(ctx: Context, p: Profile) = synchronized(lock) {
        atomicWrite(profileFile(ctx), p.toJson().toString())
    }

    fun profileConfigured(ctx: Context) = profileFile(ctx).exists()

    // ---- meals ----
    fun loadMeals(ctx: Context): MutableList<Meal> = synchronized(lock) {
        val f = mealsFile(ctx)
        if (!f.exists()) return mutableListOf()
        return try {
            val arr = JSONObject(f.readText()).optJSONArray("meals") ?: JSONArray()
            MutableList(arr.length()) { Meal.fromJson(arr.getJSONObject(it)) }
        } catch (_: Exception) { mutableListOf() }
    }

    fun saveMeals(ctx: Context, list: List<Meal>) = synchronized(lock) {
        val arr = JSONArray()
        list.sortedWith(compareBy({ it.date }, { it.type }, { it.time })).forEach { arr.put(it.toJson()) }
        atomicWrite(mealsFile(ctx), JSONObject().put("meals", arr).toString())
        runCatching { QuickMealWidget.refreshAll(ctx) } // keep the widget's count current
    }

    fun mealsForDate(ctx: Context, date: String): List<Meal> =
        loadMeals(ctx).filter { it.date == date }.sortedWith(compareBy({ it.type }, { it.time }))

    fun get(ctx: Context, id: Long): Meal? = loadMeals(ctx).firstOrNull { it.id == id }

    fun upsert(ctx: Context, m: Meal) {
        val list = loadMeals(ctx)
        val i = list.indexOfFirst { it.id == m.id }
        if (i >= 0) list[i] = m else list.add(m)
        saveMeals(ctx, list)
        snapshotTargets(ctx, m.date) // this day now has activity — remember its targets
        clearDayReview(ctx, m.date) // the day's holistic review is now stale
    }

    fun delete(ctx: Context, id: Long) {
        val list = loadMeals(ctx)
        val d = list.firstOrNull { it.id == id }?.date
        list.removeAll { it.id == id }
        saveMeals(ctx, list)
        if (d != null) clearDayReview(ctx, d)
    }

    // ---- whole-day holistic review cache ----
    fun loadDayReview(ctx: Context, date: String): String = synchronized(lock) {
        val f = reviewsFile(ctx)
        if (!f.exists()) return ""
        return try { JSONObject(f.readText()).optString(date, "") } catch (_: Exception) { "" }
    }

    fun saveDayReview(ctx: Context, date: String, json: String) = synchronized(lock) {
        val f = reviewsFile(ctx)
        val root = try { if (f.exists()) JSONObject(f.readText()) else JSONObject() } catch (_: Exception) { JSONObject() }
        root.put(date, json)
        atomicWrite(f, root.toString())
    }

    fun clearDayReview(ctx: Context, date: String) = synchronized(lock) {
        val f = reviewsFile(ctx)
        if (!f.exists()) return
        val root = try { JSONObject(f.readText()) } catch (_: Exception) { return }
        if (root.has(date)) { root.remove(date); atomicWrite(f, root.toString()) }
    }

    // ---- per-day target snapshots (so changing a target later doesn't rewrite old days) ----
    private fun dayMetaFile(ctx: Context) = File(ctx.filesDir, "day_meta_${uid(ctx)}.json")

    /** The whole date -> {cal, water} map (empty if none yet). */
    fun loadDayMeta(ctx: Context): JSONObject = synchronized(lock) {
        val f = dayMetaFile(ctx)
        if (!f.exists()) return JSONObject()
        return try { JSONObject(f.readText()) } catch (_: Exception) { JSONObject() }
    }

    /** Snapshot the current targets for [date] — call only when that day actually gets activity. */
    fun snapshotTargets(ctx: Context, date: String) =
        recordDayTargets(ctx, date, loadProfile(ctx).targetCalories(), Water.targetMl(ctx))

    /** Snapshot the targets in effect on [date]; no-op if already stored with the same values. */
    fun recordDayTargets(ctx: Context, date: String, calTarget: Int, waterTarget: Int) = synchronized(lock) {
        val f = dayMetaFile(ctx)
        val root = try { if (f.exists()) JSONObject(f.readText()) else JSONObject() } catch (_: Exception) { JSONObject() }
        val existing = root.optJSONObject(date)
        if (existing != null && existing.optInt("cal") == calTarget && existing.optInt("water") == waterTarget) return
        root.put(date, JSONObject().put("cal", calTarget).put("water", waterTarget))
        atomicWrite(f, root.toString())
    }

    /** Remove every data file belonging to one user id (called when a user is deleted). */
    fun deleteUserData(ctx: Context, id: String) = synchronized(lock) {
        listOf("profile_$id.json", "meals_$id.json", "day_reviews_$id.json", "day_meta_$id.json", Water.fileName(id)).forEach {
            runCatching { File(ctx.filesDir, it).delete() }
        }
    }

    private fun atomicWrite(target: File, content: String) {
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.writeText(content)
        tmp.renameTo(target)
    }
}
