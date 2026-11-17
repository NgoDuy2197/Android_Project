package com.dsoft.remotelink.capture

import android.view.Surface
import java.io.InputStream

/**
 * Abstraction over "how do we get the screen". Two consumers:
 *  - the VNC server pulls raw RGBA frames ([latestRgba]);
 *  - the stream (H.264) server either renders into a MediaCodec input Surface ([feedsEncoderSurface] = true,
 *    [renderTo]) or reads a ready-made H.264 elementary stream ([openH264Stream], e.g. root `screenrecord`).
 *
 * Implementations: [MediaProjectionCapturer] (non-root), [RootCapturer] (su).
 */
interface ScreenCapturer {
    val width: Int
    val height: Int
    val densityDpi: Int

    /** Begin capturing. Safe to call once. */
    fun start()
    fun stop()

    /**
     * Copy the most recent frame into [out] as tightly-packed RGBA_8888 (row stride == width*4).
     * Returns false if no frame is available yet. [out] must be >= width*height*4 bytes.
     */
    fun latestRgba(out: ByteArray): Boolean

    /** True if the stream server should drive a MediaCodec surface via [renderTo]; false if it should read [openH264Stream]. */
    val feedsEncoderSurface: Boolean

    /** Only when [feedsEncoderSurface]: mirror the screen onto [surface] (a MediaCodec input surface). */
    fun renderTo(surface: Surface) {}

    /** Only when ![feedsEncoderSurface]: an H.264 Annex-B elementary stream of the screen. Caller closes it. */
    fun openH264Stream(fps: Int, bitrateBps: Int): InputStream? = null
}
