package com.dsoft.remotelink.capture

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import com.dsoft.remotelink.Log2

/**
 * Non-root capture via [MediaProjection]. One projection can feed two virtual displays:
 *  - an [ImageReader] (RGBA) for the VNC server,
 *  - a MediaCodec input Surface for the stream server.
 */
class MediaProjectionCapturer(
    private val projection: MediaProjection,
    fullWidth: Int,
    fullHeight: Int,
    override val densityDpi: Int,
    scaleDiv: Int,
) : ScreenCapturer {

    override val width: Int = (fullWidth / scaleDiv).let { it - (it % 2) }.coerceAtLeast(2)
    override val height: Int = (fullHeight / scaleDiv).let { it - (it % 2) }.coerceAtLeast(2)
    override val feedsEncoderSurface = true

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var reader: ImageReader? = null
    private var vncDisplay: VirtualDisplay? = null
    private var encDisplay: VirtualDisplay? = null
    private var started = false

    // latest tightly-packed RGBA frame, guarded by lock
    private val lock = Any()
    private var latest: ByteArray? = null
    private var hasNew = false

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() { Log2.w("MediaProjection stopped by system/user"); stop() }
    }

    override fun start() {
        if (started) return
        started = true
        val t = HandlerThread("cap-mp").also { it.start() }
        thread = t
        handler = Handler(t.looper)
        projection.registerCallback(projectionCallback, handler)
        Log2.i("MediaProjectionCapturer started ${width}x$height")
    }

    private fun ensureReader() {
        if (reader != null) return
        val r = ImageReader.newInstance(width, height, android.graphics.PixelFormat.RGBA_8888, 2)
        r.setOnImageAvailableListener({ ir ->
            val img = try { ir.acquireLatestImage() } catch (e: Exception) { null } ?: return@setOnImageAvailableListener
            try {
                val plane = img.planes[0]
                val rowStride = plane.rowStride
                val pixelStride = plane.pixelStride
                val buf = plane.buffer
                val tight = ByteArray(width * height * 4)
                if (rowStride == width * 4 && pixelStride == 4) {
                    buf.get(tight)
                } else {
                    val rowTmp = ByteArray(rowStride)
                    var o = 0
                    for (y in 0 until height) {
                        buf.position(y * rowStride)
                        val toRead = minOf(rowStride, buf.remaining())
                        buf.get(rowTmp, 0, toRead)
                        var si = 0
                        for (x in 0 until width) { // copy RGBA, skip any row padding
                            tight[o] = rowTmp[si]
                            tight[o + 1] = rowTmp[si + 1]
                            tight[o + 2] = rowTmp[si + 2]
                            tight[o + 3] = rowTmp[si + 3]
                            o += 4; si += pixelStride
                        }
                    }
                }
                synchronized(lock) { latest = tight; hasNew = true }
            } catch (e: Exception) {
                Log2.e("reader copy failed", e)
            } finally { img.close() }
        }, handler)
        reader = r
        vncDisplay = projection.createVirtualDisplay(
            "rl-vnc", width, height, densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, handler
        )
    }

    override fun latestRgba(out: ByteArray): Boolean {
        ensureReader()
        synchronized(lock) {
            val l = latest ?: return false
            if (!hasNew) return false
            System.arraycopy(l, 0, out, 0, minOf(l.size, out.size))
            hasNew = false
            return true
        }
    }

    override fun renderTo(surface: Surface) {
        if (encDisplay != null) return
        encDisplay = projection.createVirtualDisplay(
            "rl-enc", width, height, densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, handler
        )
        Log2.i("stream encoder display attached")
    }

    override fun stop() {
        if (!started) return
        started = false
        runCatching { encDisplay?.release() }; encDisplay = null
        runCatching { vncDisplay?.release() }; vncDisplay = null
        runCatching { reader?.close() }; reader = null
        runCatching { projection.unregisterCallback(projectionCallback) }
        runCatching { projection.stop() }
        thread?.quitSafely(); thread = null; handler = null
        synchronized(lock) { latest = null; hasNew = false }
        Log2.i("MediaProjectionCapturer stopped")
    }
}
