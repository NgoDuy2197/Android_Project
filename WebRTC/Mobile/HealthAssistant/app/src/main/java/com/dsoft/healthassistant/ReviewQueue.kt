package com.dsoft.healthassistant

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CopyOnWriteArraySet

/**
 * A list/queue of "Xem xét" (review) requests, processed one at a time by a single worker
 * (the offline LLM can't run concurrently anyway). Re-requesting a target that is still
 * queued/running/awaiting-approval returns the SAME job instead of creating a duplicate.
 *
 * Flow: QUEUED → RUNNING → AWAITING (có kết quả, "Chờ duyệt") → user bấm "Duyệt" → APPROVED
 * (kết quả mới được lưu vào bữa/ngày và tính calo). ERROR = "Thất bại", CANCELLED = "Đã huỷ".
 * The UI observes changes through [addListener]. In-memory only (cleared when the process dies).
 */
object ReviewQueue {
    private const val TAG = "HA_QUEUE"
    private const val TIMEOUT_MS = 300_000L // safety net for a stuck / very slow model (first load can be slow)

    enum class Status { QUEUED, RUNNING, AWAITING, APPROVED, ERROR, CANCELLED }

    class Job(
        val id: Long,
        val userId: String,
        val isDay: Boolean,
        val mealId: Long,        // 0 for a whole-day review
        val date: String,
        val title: String,       // display label
        val scopeLabel: String,  // for the prompt ("bữa trưa" / "cả ngày")
        val mealsText: String,   // snapshot of what to review
        val engine: String,      // engine at enqueue time (display only)
    ) {
        val targetKey: String = if (isDay) "day:$userId:$date" else "meal:$userId:$mealId"
        @Volatile var status: Status = Status.QUEUED
        @Volatile var resultJson: String = ""
        @Volatile var error: String = ""
        @Volatile var finishedAt: Long = 0
        @Volatile var cancelled: Boolean = false
        val createdAt: Long = System.currentTimeMillis()
    }

    private val jobs = CopyOnWriteArrayList<Job>()
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private val gate = Any()
    @Volatile private var worker: Thread? = null

    // ---- observation ----
    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }
    private fun notifyChanged() = main.post { listeners.forEach { runCatching { it() } } }

    // ---- queries ----
    fun jobs(): List<Job> = jobs.toList()
    fun job(id: Long): Job? = jobs.firstOrNull { it.id == id }

    /** Not yet resolved by the user: still working, or waiting to be approved. */
    private fun Status.active() = this == Status.QUEUED || this == Status.RUNNING || this == Status.AWAITING
    fun activeCount(): Int = jobs.count { it.status.active() }

    private fun inFlight(key: String): Job? = jobs.firstOrNull { it.targetKey == key && it.status.active() }
    fun inFlightForMeal(ctx: Context, mealId: Long): Job? = inFlight("meal:${Users.currentId(ctx)}:$mealId")
    fun inFlightForDay(ctx: Context, date: String): Job? = inFlight("day:${Users.currentId(ctx)}:$date")

    // ---- enqueue ----
    fun enqueueDay(ctx: Context, date: String, scopeLabel: String, mealsText: String): Job {
        val uid = Users.currentId(ctx)
        inFlight("day:$uid:$date")?.let { return it }
        return add(ctx, Job(System.nanoTime(), uid, true, 0, date,
            "Cả ngày · ${Store.prettyDate(date)}", scopeLabel, mealsText, Prefs(ctx).engine))
    }

    fun enqueueMeal(ctx: Context, meal: Meal, scopeLabel: String, mealsText: String): Job {
        val uid = Users.currentId(ctx)
        inFlight("meal:$uid:${meal.id}")?.let { return it }
        val title = "Bữa ${meal.typeLabel().lowercase()}" + if (meal.time.isNotBlank()) " · ${meal.time}" else ""
        return add(ctx, Job(System.nanoTime(), uid, false, meal.id, meal.date,
            title, scopeLabel, mealsText, Prefs(ctx).engine))
    }

    fun cancel(id: Long) {
        val j = job(id) ?: return
        when (j.status) {
            Status.QUEUED, Status.AWAITING -> { j.status = Status.CANCELLED; j.finishedAt = System.currentTimeMillis() }
            Status.RUNNING -> j.cancelled = true // result will be dropped when it finishes
            else -> return
        }
        notifyChanged()
    }

    /** Approve an AWAITING result: save it to the meal/day so it counts. Returns false if it couldn't. */
    fun approve(ctx: Context, id: Long): Boolean {
        val j = job(id) ?: return false
        if (j.status != Status.AWAITING) return j.status == Status.APPROVED
        if (Users.currentId(ctx) != j.userId) return false // wrong user is active — don't write their data
        cacheResult(ctx.applicationContext, j)
        j.status = Status.APPROVED
        j.finishedAt = System.currentTimeMillis()
        DiagLog.i(TAG, "Đã duyệt & lưu: ${j.title}")
        notifyChanged()
        return true
    }

    /** Remove a job entirely (used by "Xoá & tạo lại"). */
    fun delete(id: Long) {
        jobs.removeAll { it.id == id }
        notifyChanged()
    }

    fun clearFinished() {
        jobs.removeAll { it.status == Status.APPROVED || it.status == Status.ERROR || it.status == Status.CANCELLED }
        notifyChanged()
    }

    // ---- worker ----
    private fun add(ctx: Context, job: Job): Job {
        jobs.add(job)
        notifyChanged()
        ensureWorker(ctx.applicationContext)
        return job
    }

    /** Claim the next queued job (or null + stop the worker). Synchronized so two threads never share one. */
    private fun claimNext(): Job? = synchronized(gate) {
        val next = jobs.firstOrNull { it.status == Status.QUEUED }
        if (next == null) { worker = null; return null }
        next.status = if (next.cancelled) Status.CANCELLED else Status.RUNNING
        next
    }

    private fun ensureWorker(appCtx: Context) = synchronized(gate) {
        if (worker != null) return
        worker = Thread {
            while (true) {
                val job = claimNext() ?: break
                notifyChanged()
                if (job.status == Status.CANCELLED) continue
                runJob(appCtx, job)
                job.finishedAt = System.currentTimeMillis()
                notifyChanged()
            }
        }.apply { isDaemon = true; name = "review-queue"; start() }
    }

    private fun runJob(appCtx: Context, job: Job) {
        DiagLog.i(TAG, "Xử lý: ${job.title} (${job.engine})")
        val holder = arrayOfNulls<Any?>(2) // [0]=Review, [1]=Throwable
        val t = Thread {
            try { holder[0] = Ai.reviewBlocking(appCtx, job.scopeLabel, job.mealsText) }
            catch (x: Throwable) { holder[1] = x }
        }.apply { isDaemon = true; name = "review-job-${job.id}"; start() }
        t.join(TIMEOUT_MS)
        when {
            job.cancelled -> { job.status = Status.CANCELLED; DiagLog.i(TAG, "Đã huỷ: ${job.title}") }
            t.isAlive -> {
                job.status = Status.ERROR
                job.error = tr("Quá thời gian (${TIMEOUT_MS / 1000}s) — model có thể quá chậm hoặc bị treo. Thử model nhỏ hơn / giảm max tokens, hoặc dùng Gemini.",
                    "Timed out (${TIMEOUT_MS / 1000}s) — the model may be too slow or stuck. Try a smaller model / lower max tokens, or use Gemini.")
                DiagLog.e(TAG, "THẤT BẠI (quá thời gian): ${job.title}")
            }
            holder[1] != null -> {
                val x = holder[1] as Throwable
                job.status = Status.ERROR; job.error = Ai.friendly(x)
                DiagLog.e(TAG, "THẤT BẠI: ${job.title}", x)
            }
            else -> {
                val r = holder[0] as Ai.Review
                job.resultJson = r.toJson()
                job.status = Status.AWAITING // có kết quả — chờ người dùng "Duyệt" rồi mới lưu
                DiagLog.i(TAG, "Có kết quả, chờ duyệt: ${job.title} (${r.calories} kcal)")
            }
        }
    }

    /** Cache into the store so the dashboard shows calories — only when still the same user. */
    private fun cacheResult(ctx: Context, job: Job) {
        runCatching {
            if (job.isDay) {
                Store.saveDayReview(ctx, job.date, job.resultJson)
                runCatching { splitDayToMeals(ctx, job.date, job.resultJson) }
                    .onFailure { DiagLog.e(TAG, "Tách theo bữa lỗi", it) }
            } else Store.get(ctx, job.mealId)?.let { m ->
                m.reviewJson = job.resultJson; m.reviewAt = System.currentTimeMillis(); Store.upsert(ctx, m)
            }
        }.onFailure { DiagLog.e(TAG, "Lưu kết quả thất bại: ${job.title}", it) }
    }

    /**
     * Attribute the whole-day review's items to the day's meals (best-effort word-overlap match)
     * and write a small per-meal review (marked "from_day") so each meal row shows its calories.
     * Uses saveMeals (not upsert) so it doesn't wipe the day review we just saved.
     */
    private fun splitDayToMeals(ctx: Context, date: String, json: String) {
        val review = Ai.parse(json) ?: return
        val meals = Store.loadMeals(ctx).filter { it.date == date }
        if (meals.isEmpty() || review.items.isEmpty()) return

        fun toks(s: String) = s.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 2 }.toSet()
        val mealToks = meals.associate { it.id to toks(it.items) }
        val assigned = HashMap<Long, MutableList<Pair<String, Int>>>()
        review.items.forEach { item ->
            val itTok = toks(item.first)
            val best = meals.maxByOrNull { m -> (mealToks[m.id] ?: emptySet()).count { it in itTok } }
            val overlap = best?.let { b -> (mealToks[b.id] ?: emptySet()).count { it in itTok } } ?: 0
            val target = if (overlap > 0) best!! else meals.first()
            assigned.getOrPut(target.id) { mutableListOf() }.add(item)
        }

        val all = Store.loadMeals(ctx)
        var changed = false
        all.forEach { m ->
            if (m.date != date) return@forEach
            val items = assigned[m.id] ?: return@forEach
            val mj = JSONObject().apply {
                put("total_calories", items.sumOf { it.second })
                put("items", JSONArray().apply { items.forEach { put(JSONObject().put("name", it.first).put("calories", it.second)) } })
                put("assessment", tr("(Tách từ xem xét cả ngày)", "(Split from the whole-day review)"))
                put("from_day", true)
            }
            m.reviewJson = mj.toString(); m.reviewAt = System.currentTimeMillis(); changed = true
        }
        if (changed) Store.saveMeals(ctx, all)
    }
}
