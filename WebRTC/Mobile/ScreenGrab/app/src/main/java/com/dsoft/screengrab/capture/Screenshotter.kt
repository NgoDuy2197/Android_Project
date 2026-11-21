package com.dsoft.screengrab.capture

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import com.dsoft.screengrab.Log2
import java.util.concurrent.CountDownLatch

/** Grabs a single frame via MediaProjection + ImageReader and crops it to [crop]. */
object Screenshotter {

    fun capture(projection: MediaProjection, dispW: Int, dispH: Int, dpi: Int, crop: Rect): Bitmap? {
        val reader = ImageReader.newInstance(dispW, dispH, PixelFormat.RGBA_8888, 2)
        val thread = HandlerThread("ScreenGrab-Shot").apply { start() }
        val handler = Handler(thread.looper)
        val latch = CountDownLatch(1)
        var result: Bitmap? = null
        var vd: android.hardware.display.VirtualDisplay? = null
        var cb: MediaProjection.Callback? = null

        try {
            cb = object : MediaProjection.Callback() {}
            projection.registerCallback(cb, handler)
            reader.setOnImageAvailableListener({ r ->
                if (result != null) return@setOnImageAvailableListener
                val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    val plane = image.planes[0]
                    val rowStride = plane.rowStride
                    val pixelStride = plane.pixelStride
                    val rowPadding = rowStride - pixelStride * dispW
                    val full = Bitmap.createBitmap(dispW + rowPadding / pixelStride, dispH, Bitmap.Config.ARGB_8888)
                    full.copyPixelsFromBuffer(plane.buffer)
                    var bmp = if (rowPadding == 0) full else Bitmap.createBitmap(full, 0, 0, dispW, dispH)
                    // crop
                    val l = crop.left.coerceIn(0, dispW); val t = crop.top.coerceIn(0, dispH)
                    val w = crop.width().coerceIn(1, dispW - l); val h = crop.height().coerceIn(1, dispH - t)
                    if (!(l == 0 && t == 0 && w == dispW && h == dispH)) bmp = Bitmap.createBitmap(bmp, l, t, w, h)
                    result = bmp
                } catch (e: Exception) {
                    Log2.e("screenshot decode failed", e)
                } finally {
                    image.close()
                    latch.countDown()
                }
            }, handler)

            vd = projection.createVirtualDisplay(
                "ScreenGrab-Shot", dispW, dispH, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface, null, handler
            )
            latch.await(3, java.util.concurrent.TimeUnit.SECONDS)
        } catch (e: Throwable) {
            Log2.e("screenshot failed", e)
        } finally {
            runCatching { vd?.release() }
            runCatching { cb?.let { projection.unregisterCallback(it) } }
            runCatching { reader.close() }
            thread.quitSafely()
        }
        return result
    }
}
