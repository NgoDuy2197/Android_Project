package com.dsoft.bookshelf

import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier

/**
 * A quick CPU-scaling probe to suggest a sensible max thread count for parallel offline translation.
 * It times a fixed per-thread workload at 1..cores threads and reports the largest thread count that
 * still runs at >=70% efficiency. It is only an estimate (background load, thermal, and the fact that
 * each ML Kit inference is itself multi-threaded all affect the real optimum).
 */
object Bench {
    @Volatile private var cached: Pair<Int, Int>? = null

    /** (suggestedThreads, cores). Cached after the first run. Call off the main thread. */
    fun suggestThreads(): Pair<Int, Int> {
        cached?.let { return it }
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val cap = minOf(cores, Prefs.MAX_TR_THREADS)
        if (cap <= 1) return (1 to cores).also { cached = it }
        val iters = 4_000_000
        busy(iters) // warm up (JIT)
        val t1 = timeK(1, iters).coerceAtLeast(1)
        var suggested = 1
        for (k in 2..cap) {
            val tk = timeK(k, iters)
            val eff = t1.toDouble() / tk           // ideal ~1.0 if the k threads run truly in parallel
            if (eff >= 0.70) suggested = k else break
        }
        return (suggested to cores).also { cached = it }
    }

    private fun busy(iters: Int): Double {
        var x = 0.0
        for (i in 0 until iters) x += Math.sqrt((i and 0xffff).toDouble() + 1.0)
        return x
    }

    /** Wall time (ns) for [k] threads each doing [iters] work, started together. */
    private fun timeK(k: Int, iters: Int): Long {
        val barrier = CyclicBarrier(k + 1)
        val done = CountDownLatch(k)
        val sink = DoubleArray(k)
        repeat(k) { t ->
            Thread {
                runCatching { barrier.await() }
                sink[t] = busy(iters)
                done.countDown()
            }.start()
        }
        runCatching { barrier.await() }
        val t0 = System.nanoTime()
        done.await()
        return System.nanoTime() - t0
    }
}
