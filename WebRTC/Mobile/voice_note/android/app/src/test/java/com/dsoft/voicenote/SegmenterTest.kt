package com.dsoft.voicenote

import org.junit.Assert.assertEquals
import org.junit.Test

class SegmenterTest {
    private val out = ArrayList<String>()
    private var ends = 0
    private val seg = Segmenter(requestEnd = { ends++ }) { _, t -> out += t }

    /** Simulates [ms] of audio at [level], ticking every 100 ms from [t0]. */
    private fun run(t0: Long, ms: Long, level: Float): Long {
        var t = t0
        while (t < t0 + ms) { seg.tick(t, level, 0.25f, 1000); t += 100 }
        return t
    }

    @Test
    fun oneEnterPerSilence() {
        var t = run(0, 2000, 0.6f)
        seg.onPartial("xin chào", t)
        seg.onFinal("xin chào", t)
        t = run(t, 10_000, 0.05f) // long silence
        assertEquals(listOf("Xin chào"), out)
        t = run(t, 1500, 0.6f)
        seg.onPartial("câu hai", t); seg.onFinal("câu hai", t)
        t = run(t, 300, 0.6f)
        seg.onPartial("tiếp", t); seg.onFinal("tiếp", t) // short pause inside: same line
        run(t, 5000, 0.0f)
        assertEquals(listOf("Xin chào", "Câu hai tiếp"), out)
        assertEquals(0, ends)
    }

    @Test
    fun silenceMidUtteranceAsksRecognizerToFinish() {
        var t = run(0, 1000, 0.6f)
        seg.onPartial("đang nói", t)
        t = run(t, 3000, 0.05f)
        assertEquals(1, ends) // asked once, not every tick
        assertEquals(emptyList<String>(), out)
        seg.onFinal("", t) // recognizer gave up: keep the partial
        assertEquals(listOf("Đang nói"), out)
    }
}
