package com.dsoft.bookshelf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

class JobAndMp3Test {
    private fun job() = Job(id = "j1", type = JobType.TRANSLATE, bookId = "b", title = "T", createdAt = 1)

    @Test fun advanceTracksProgressAndCompletes() {
        var j = job().withTotal(3, 10).start(11)
        assertEquals(JobStatus.RUNNING, j.status)
        j = j.advance(100, 12)
        assertEquals(1, j.index); assertEquals(100L, j.outLen); assertEquals(33, j.percent)
        assertEquals("1 / 3", j.progressLabel())
        j = j.advance(200, 13).advance(300, 14)
        assertEquals(JobStatus.DONE, j.status)
        assertEquals(100, j.percent)
        assertTrue(j.isFinished)
    }

    @Test fun failThenResumeContinuesFromNextBatch() {
        var j = job().withTotal(10, 1).start(1).advance(50, 2).advance(90, 3)
        j = j.fail("HTTP 429", 4)
        assertEquals(JobStatus.ERROR, j.status)
        assertTrue(j.canResume)
        assertEquals(2, j.index)
        j = j.resume(5)
        assertEquals(JobStatus.QUEUED, j.status)
        assertEquals("", j.error)
        assertEquals(2, j.index)   // next unit to process is batch #3 (index 2)
        assertEquals(90L, j.outLen) // output will be truncated back to this length
    }

    @Test fun restartRecoveryPausesRunningJobsOnly() {
        val running = job().start(1).recoverAfterRestart(2, "interrupted")
        assertEquals(JobStatus.PAUSED, running.status)
        val done = job().withTotal(1, 1).done(2).recoverAfterRestart(3, "x")
        assertEquals(JobStatus.DONE, done.status)
    }

    @Test fun withTotalNeverLosesProgress() {
        val j = job().copy(index = 5).withTotal(8, 1)
        assertEquals(5, j.index)
        assertEquals(3, job().copy(index = 5).withTotal(3, 1).index)
    }

    @Test fun cancelAndPauseRules() {
        val d = job().withTotal(1, 1).done(1)
        assertEquals(JobStatus.DONE, d.cancel(2).status)
        assertEquals(JobStatus.DONE, d.pause(2).status)
        assertFalse(d.canResume)
        assertEquals(JobStatus.CANCELLED, job().cancel(2).status)
    }

    @Test fun skipCountsSkippedUnits() {
        val j = job().withTotal(4, 1).skip(0, 2)
        assertEquals(1, j.skipped); assertEquals(1, j.index)
    }

    @Test fun nextRunnablePicksOldestQueued() {
        val a = job().copy(id = "a", createdAt = 5)
        val b = job().copy(id = "b", createdAt = 3)
        val c = job().copy(id = "c", createdAt = 1, status = JobStatus.PAUSED)
        assertEquals("b", Job.nextRunnable(listOf(a, b, c))?.id)
        assertNull(Job.nextRunnable(listOf(c)))
    }

    private fun wavBytes(samples: ShortArray, rate: Int, dataSizeField: Int? = null): ByteArray {
        val data = samples.size * 2
        val bb = ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray()); bb.putInt(36 + data); bb.put("WAVE".toByteArray())
        bb.put("fmt ".toByteArray()); bb.putInt(16); bb.putShort(1); bb.putShort(1); bb.putInt(rate)
        bb.putInt(rate * 2); bb.putShort(2); bb.putShort(16)
        bb.put("data".toByteArray()); bb.putInt(dataSizeField ?: data)
        samples.forEach { bb.putShort(it) }
        return bb.array()
    }

    private fun sine(rate: Int, seconds: Double) = ShortArray((rate * seconds).toInt()) { i ->
        (sin(2 * PI * 440 * i / rate) * 12000).toInt().toShort()
    }

    @Test fun wavParseAndReadIncludingStreamingHeader() {
        val s = sine(24000, 0.1)
        val f = File.createTempFile("wavtest", ".wav")
        try {
            f.writeBytes(wavBytes(s, 24000, dataSizeField = 0))
            val (info, pcm) = Wav.read(f)
            assertEquals(24000, info.sampleRate); assertEquals(1, info.channels); assertEquals(16, info.bits)
            assertEquals(s.size, pcm.size)
            assertEquals(s[100], pcm[100])
        } finally { f.delete() }
    }

    @Test fun jump3rEncodesConcatenatableMp3Frames() {
        val rate = 24000
        val out = ByteArrayOutputStream()
        Mp3.encode(sine(rate, 1.0), rate, 1, 48, out)
        val first = out.size()
        Mp3.encode(sine(rate, 0.5), rate, 1, 48, out)
        val bytes = out.toByteArray()
        assertTrue("mp3 too small: ${bytes.size}", first > 2000)
        assertTrue(bytes.size > first)
        // MPEG audio frame sync (11 bits set) at the start of each run
        for (start in listOf(0, first)) {
            assertEquals(0xFF, bytes[start].toInt() and 0xFF)
            assertEquals(0xE0, bytes[start + 1].toInt() and 0xE0)
        }
        // ~48 kbps * 1.5 s = ~9 KB
        assertTrue(bytes.size in 6000..14000)
    }
}
