package com.example.cameraman

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import kotlin.math.abs

/**
 * Cheap frame-differencing motion detector for CameraX [ImageAnalysis].
 *
 * It reads the luma (Y) plane of each YUV frame, subsamples it onto a small
 * fixed grid, and compares that grid to the previous frame. When the fraction
 * of grid cells whose brightness changed by more than [PIXEL_DELTA] exceeds
 * [sensitivity], it reports motion — at most once per [minIntervalMs] so a
 * single event doesn't fire dozens of captures.
 */
class MotionDetector(
    private val sensitivity: Double,
    private val minIntervalMs: Long,
    private val clock: () -> Long,
    private val onMotion: () -> Unit,
) : ImageAnalysis.Analyzer {

    private companion object {
        const val GRID = 32           // 32x32 subsample grid
        const val PIXEL_DELTA = 24    // luma change (0..255) that counts as "different"
    }

    private var previous: IntArray? = null
    private var lastTriggerMs = 0L
    // Ignore the first couple of frames while the sensor auto-exposes.
    private var warmup = 3

    override fun analyze(image: ImageProxy) {
        try {
            val current = sample(image)
            val prev = previous
            previous = current

            if (warmup > 0) {
                warmup--
                return
            }
            if (prev == null) return

            var changed = 0
            for (i in current.indices) {
                if (abs(current[i] - prev[i]) > PIXEL_DELTA) changed++
            }
            val fraction = changed.toDouble() / current.size
            if (fraction < sensitivity) return

            val now = clock()
            if (now - lastTriggerMs < minIntervalMs) return
            lastTriggerMs = now
            onMotion()
        } catch (_: Exception) {
            // A dropped frame must never crash the camera pipeline.
        } finally {
            image.close()
        }
    }

    /** Subsamples the Y plane onto a GRID x GRID array of luma values. */
    private fun sample(image: ImageProxy): IntArray {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val width = image.width
        val height = image.height

        val out = IntArray(GRID * GRID)
        var idx = 0
        for (gy in 0 until GRID) {
            val y = (gy * height) / GRID
            val rowBase = y * rowStride
            for (gx in 0 until GRID) {
                val x = (gx * width) / GRID
                val pos = rowBase + x * pixelStride
                out[idx++] = if (pos < buffer.limit()) (buffer.get(pos).toInt() and 0xFF) else 0
            }
        }
        return out
    }
}
