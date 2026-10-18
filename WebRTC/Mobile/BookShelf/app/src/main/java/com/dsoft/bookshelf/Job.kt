package com.dsoft.bookshelf

/** Background job types. */
object JobType {
    const val EXTRACT = "extract"
    const val TRANSLATE = "translate"
    const val AUDIO = "audio"
}

object JobStatus {
    const val QUEUED = "queued"
    const val RUNNING = "running"
    const val PAUSED = "paused"
    const val ERROR = "error"
    const val DONE = "done"
    const val CANCELLED = "cancelled"
}

/**
 * A resumable job. Pure data + bookkeeping (no Android types) so it is JVM unit-testable.
 * [index] = number of finished units (pages / batches / chunks); the next unit to process is [index].
 * [outLen] = byte length of the output file after the last finished unit; on resume the output is truncated
 * to it so a unit that was half-written when the process died is redone exactly once.
 */
data class Job(
    val id: String,
    val type: String,
    val bookId: String,
    val title: String,
    val status: String = JobStatus.QUEUED,
    val index: Int = 0,
    val total: Int = 0,
    val outLen: Long = 0,
    val outBookId: String = "",
    val outPath: String = "",
    val outUri: String = "",
    val engine: String = "",
    val src: String = "",
    val tgt: String = "",
    val unitChars: Int = 0,
    val error: String = "",
    val message: String = "",
    val skipped: Int = 0,
    /** Allow downloading ML Kit models over mobile data (overrides the Wi-Fi-only setting for this job). */
    val cell: Boolean = false,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
) {
    val isActive get() = status == JobStatus.QUEUED || status == JobStatus.RUNNING
    val isFinished get() = status == JobStatus.DONE || status == JobStatus.CANCELLED
    val canResume get() = status == JobStatus.PAUSED || status == JobStatus.ERROR
    val canPause get() = isActive

    /** 0..1 */
    val fraction: Float get() = if (total <= 0) 0f else (index.toFloat() / total).coerceIn(0f, 1f)
    val percent: Int get() = if (status == JobStatus.DONE) 100 else (fraction * 100).toInt()

    /** Called when the runner knows the total number of units. Keeps progress when resuming. */
    fun withTotal(t: Int, now: Long) = copy(total = t, index = index.coerceAtMost(t), updatedAt = now)

    /** One unit finished; [newOutLen] is the output size after appending it. */
    fun advance(newOutLen: Long, now: Long): Job {
        val next = index + 1
        return copy(
            index = next, outLen = newOutLen, error = "", updatedAt = now,
            status = if (total in 1..next) JobStatus.DONE else status,
        )
    }

    fun skip(newOutLen: Long, now: Long) = advance(newOutLen, now).copy(skipped = skipped + 1)

    fun start(now: Long) = copy(status = JobStatus.RUNNING, error = "", updatedAt = now)
    fun pause(now: Long) = if (isFinished) this else copy(status = JobStatus.PAUSED, message = "", updatedAt = now)
    fun fail(err: String, now: Long) = if (isFinished) this else copy(status = JobStatus.ERROR, error = err, message = "", updatedAt = now)
    fun resume(now: Long) = if (canResume || status == JobStatus.QUEUED) copy(status = JobStatus.QUEUED, error = "", updatedAt = now) else this
    fun cancel(now: Long) = if (status == JobStatus.DONE) this else copy(status = JobStatus.CANCELLED, updatedAt = now)
    fun done(now: Long) = copy(status = JobStatus.DONE, index = total.coerceAtLeast(index), error = "", message = "", updatedAt = now)

    /** A RUNNING job found at process start was interrupted (app killed) - becomes PAUSED, progress kept. */
    fun recoverAfterRestart(now: Long, msg: String) =
        if (status == JobStatus.RUNNING) copy(status = JobStatus.PAUSED, error = msg, updatedAt = now) else this

    fun progressLabel(): String = if (total > 0) "${minOf(index, total)} / $total" else "$index / ?"

    companion object {
        /** Picks the next job to run: oldest QUEUED one. */
        fun nextRunnable(jobs: Collection<Job>): Job? =
            jobs.filter { it.status == JobStatus.QUEUED }.minWithOrNull(compareBy<Job>({ it.createdAt }, { it.id }))
    }
}
