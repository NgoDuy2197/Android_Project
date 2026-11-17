package com.dsoft.remotelink.capture

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.dsoft.remotelink.Log2
import java.io.InputStream

/**
 * Root capture. Stream path = `screenrecord` H.264 straight to stdout (no dialog, low latency).
 * VNC path = `screencap -p` (PNG) decoded per frame — heavy, so meant as a fallback at low fps.
 *
 * NOTE: `screenrecord` enforces a time limit (historically ~180s); [StreamServer] re-opens the
 * stream when it ends, so capture continues across the restart.
 */
class RootCapturer(
    fullWidth: Int,
    fullHeight: Int,
    override val densityDpi: Int,
    private val scaleDiv: Int,
) : ScreenCapturer {

    override val width: Int = (fullWidth / scaleDiv).let { it - (it % 2) }.coerceAtLeast(2)
    override val height: Int = (fullHeight / scaleDiv).let { it - (it % 2) }.coerceAtLeast(2)
    override val feedsEncoderSurface = false

    private var reuse: Bitmap? = null

    override fun start() { Log2.i("RootCapturer started ${width}x$height") }
    override fun stop() { reuse?.recycle(); reuse = null }

    override fun openH264Stream(fps: Int, bitrateBps: Int): InputStream? {
        val cmd = "screenrecord --output-format=h264 --size ${width}x${height} " +
            "--bit-rate $bitrateBps --time-limit 170 -"
        val p = RootShell.spawn(cmd) ?: return null
        // drain stderr so the pipe doesn't stall
        Thread {
            runCatching { p.errorStream.bufferedReader().forEachLine { /* screenrecord diagnostics */ } }
        }.apply { isDaemon = true; start() }
        return p.inputStream
    }

    override fun latestRgba(out: ByteArray): Boolean {
        return try {
            val p = ProcessBuilder("su", "-c", "screencap -p").start()
            val png = p.inputStream.readBytes()
            p.waitFor()
            if (png.isEmpty()) return false
            val bmp = BitmapFactory.decodeByteArray(png, 0, png.size) ?: return false
            val scaled = if (bmp.width == width && bmp.height == height) bmp
                else Bitmap.createScaledBitmap(bmp, width, height, true).also { if (it != bmp) bmp.recycle() }
            val buf = java.nio.ByteBuffer.wrap(out)
            scaled.copyPixelsToBuffer(buf) // ARGB_8888 -> bytes are R,G,B,A in native order for RGBA? see note
            if (scaled != bmp) scaled.recycle()
            true
        } catch (e: Exception) { Log2.e("screencap failed", e); false }
    }
}
