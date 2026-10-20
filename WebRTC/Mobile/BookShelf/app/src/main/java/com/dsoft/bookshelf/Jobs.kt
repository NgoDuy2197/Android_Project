package com.dsoft.bookshelf

import android.content.Context
import org.json.JSONObject
import java.io.File

/** Thrown inside runners when the user paused / cancelled the job. */
class StopSignal : RuntimeException()

/** An error with a user-facing message. */
class UserError(msg: String) : RuntimeException(msg)

/** Persists every job as `filesDir/jobs/<id>.json` (atomic writes) and notifies observers live. */
object JobManager {
    private lateinit var dir: File
    private lateinit var app: Context
    private val jobs = LinkedHashMap<String, Job>()
    private val stopRequests = HashSet<String>()
    private val listeners = ArrayList<(Job?) -> Unit>()

    @Synchronized
    fun init(ctx: Context) {
        if (::dir.isInitialized) return
        app = ctx.applicationContext
        dir = File(app.filesDir, "jobs").apply { mkdirs() }
        val now = System.currentTimeMillis()
        val msg = Locales.wrap(app).getString(R.string.job_interrupted)
        dir.listFiles { f -> f.name.endsWith(".json") }?.forEach { f ->
            try {
                val j = fromJson(Files.readJson(f) ?: return@forEach)
                val r = j.recoverAfterRestart(now, msg)
                jobs[r.id] = r
                if (r !== j) write(r)
            } catch (_: Exception) {}
        }
    }

    @Synchronized fun all(): List<Job> = jobs.values.sortedByDescending { it.createdAt }
    @Synchronized fun get(id: String): Job? = jobs[id]
    @Synchronized fun forBook(bookId: String) = jobs.values.filter { it.bookId == bookId }
    @Synchronized fun nextRunnable(): Job? = Job.nextRunnable(jobs.values)
    @Synchronized fun hasRunnable() = jobs.values.any { it.status == JobStatus.QUEUED }

    @Synchronized
    fun put(j: Job): Job {
        jobs[j.id] = j
        try { write(j) } catch (_: Exception) {}
        notifyChanged(j)
        return j
    }

    /** Transient status text (not a progress change) - not written to disk. */
    @Synchronized
    fun setMessage(id: String, msg: String) {
        val j = jobs[id] ?: return
        jobs[id] = j.copy(message = msg)
        notifyChanged(jobs[id])
    }

    private fun write(j: Job) = Files.writeAtomic(File(dir, "${j.id}.json"), toJson(j).toString())

    @Synchronized fun shouldStop(id: String) = id in stopRequests
    @Synchronized fun clearStop(id: String) { stopRequests -= id }

    fun pause(id: String) {
        synchronized(this) {
            val j = jobs[id] ?: return
            stopRequests += id
            when (j.status) {
                // Queued: not running yet, pause right away.
                JobStatus.QUEUED -> put(j.pause(now()))
                // Running: the runner stops at its next check; show instant feedback meanwhile.
                JobStatus.RUNNING -> { jobs[id] = j.copy(message = "Đang tạm dừng…"); notifyChanged(jobs[id]) }
                else -> {}
            }
        }
    }

    fun resume(ctx: Context, id: String) {
        synchronized(this) {
            val j = jobs[id] ?: return
            stopRequests -= id
            put(j.resume(now()))
        }
        JobService.start(ctx)
    }

    fun cancel(id: String) {
        synchronized(this) {
            val j = jobs[id] ?: return
            stopRequests += id
            put(j.cancel(now()))
        }
    }

    fun remove(id: String) {
        synchronized(this) {
            stopRequests += id
            val j = jobs.remove(id) ?: return
            File(dir, "$id.json").delete()
            File(app.cacheDir, "chunk_$id.wav").delete()
            notifyChanged(null)
            if (j.status != JobStatus.DONE && j.type == JobType.AUDIO && j.outPath.isNotEmpty()) File(j.outPath).delete()
        }
    }

    fun removeForBook(bookId: String) = forBook(bookId).forEach { remove(it.id) }

    fun addListener(l: (Job?) -> Unit) = synchronized(listeners) { listeners += l }
    fun removeListener(l: (Job?) -> Unit) = synchronized(listeners) { listeners -= l }
    private fun notifyChanged(j: Job?) {
        val ls = synchronized(listeners) { listeners.toList() }
        App.main.post { ls.forEach { it(j) } }
    }

    fun now() = System.currentTimeMillis()

    private fun newJob(type: String, book: Book, title: String) =
        Job(id = "${type}_${Library.newId()}", type = type, bookId = book.id, title = title, createdAt = now(), updatedAt = now())

    /** Existing unfinished job of this type for this book (so a second tap resumes instead of duplicating). */
    private fun unfinished(type: String, bookId: String, match: (Job) -> Boolean = { true }) =
        forBook(bookId).firstOrNull { it.type == type && !it.isFinished && match(it) }

    fun startExtract(ctx: Context, book: Book): Job {
        val existing = unfinished(JobType.EXTRACT, book.id)
        if (existing != null) { resume(ctx, existing.id); return get(existing.id)!! }
        val j = put(newJob(JobType.EXTRACT, book, ctx.getString(R.string.job_title_extract, book.title)))
        JobService.start(ctx)
        return j
    }

    /** For a PDF without extracted text, queue an extraction first (jobs run oldest-first). */
    private fun ensureText(ctx: Context, book: Book) {
        if (book.isPdf && Library.textSourceOf(book) == null) startExtract(ctx, book)
    }

    fun startTranslate(ctx: Context, book: Book, engine: String, src: String, tgt: String, allowCell: Boolean = false): Job {
        ensureText(ctx, book)
        val existing = unfinished(JobType.TRANSLATE, book.id) { it.engine == engine && it.tgt == tgt && it.src == src }
        if (existing != null) {
            if (allowCell && !existing.cell) put(existing.copy(cell = true))
            resume(ctx, existing.id); return get(existing.id)!!
        }
        val unit = when (engine) { Engines.GOOGLE -> 1800; Engines.GEMINI -> 4000; else -> 3000 }
        val j = put(newJob(JobType.TRANSLATE, book, ctx.getString(R.string.job_title_translate, book.title, Langs.name(tgt)))
            .copy(engine = engine, src = src, tgt = tgt, unitChars = unit, cell = allowCell))
        JobService.start(ctx)
        return j
    }

    /** Lets the user switch a waiting/paused translate job to mobile-data model download, then resume. */
    fun allowCellularAndResume(ctx: Context, id: String) {
        synchronized(this) { get(id)?.let { put(it.copy(cell = true)) } }
        resume(ctx, id)
    }

    fun startAudio(ctx: Context, book: Book): Job {
        ensureText(ctx, book)
        val existing = unfinished(JobType.AUDIO, book.id)
        if (existing != null) { resume(ctx, existing.id); return get(existing.id)!! }
        val j = put(newJob(JobType.AUDIO, book, ctx.getString(R.string.job_title_audio, book.title))
            .copy(engine = Prefs.ttsVoice(ctx), unitChars = Prefs.mp3Kbps(ctx)))
        JobService.start(ctx)
        return j
    }

    fun toJson(j: Job): JSONObject = JSONObject()
        .put("id", j.id).put("type", j.type).put("bookId", j.bookId).put("title", j.title).put("status", j.status)
        .put("index", j.index).put("total", j.total).put("outLen", j.outLen).put("outBookId", j.outBookId)
        .put("outPath", j.outPath).put("outUri", j.outUri).put("engine", j.engine).put("src", j.src).put("tgt", j.tgt)
        .put("unitChars", j.unitChars).put("error", j.error).put("skipped", j.skipped).put("cell", j.cell)
        .put("createdAt", j.createdAt).put("updatedAt", j.updatedAt)

    fun fromJson(o: JSONObject) = Job(
        id = o.getString("id"), type = o.getString("type"), bookId = o.optString("bookId"), title = o.optString("title"),
        status = o.optString("status", JobStatus.PAUSED), index = o.optInt("index"), total = o.optInt("total"),
        outLen = o.optLong("outLen"), outBookId = o.optString("outBookId"), outPath = o.optString("outPath"),
        outUri = o.optString("outUri"), engine = o.optString("engine"), src = o.optString("src"), tgt = o.optString("tgt"),
        unitChars = o.optInt("unitChars"), error = o.optString("error"), skipped = o.optInt("skipped"), cell = o.optBoolean("cell"),
        createdAt = o.optLong("createdAt"), updatedAt = o.optLong("updatedAt"),
    )
}

/** Handle given to job runners: persist progress, check for pause/cancel, show status text. */
class JobCtl(private val onSaved: (Job) -> Unit) {
    fun save(j: Job): Job {
        val cur = JobManager.get(j.id)
        // never resurrect a job the user cancelled meanwhile
        val toSave = if (cur != null && cur.status == JobStatus.CANCELLED) j.copy(status = JobStatus.CANCELLED) else j
        JobManager.put(toSave)
        onSaved(toSave)
        return toSave
    }

    fun checkStop(id: String) {
        if (JobManager.shouldStop(id)) throw StopSignal()
    }

    fun message(id: String, msg: String) = JobManager.setMessage(id, msg)
}
