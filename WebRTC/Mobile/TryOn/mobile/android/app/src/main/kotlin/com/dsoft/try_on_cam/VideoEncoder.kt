package com.dsoft.try_on_cam

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Handler
import android.os.HandlerThread
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger

/**
 * Encodes NV21 camera frames (+ an optional RGBA outfit overlay) into an
 * H.264 MP4. Frames stay in sensor orientation; the muxer orientation hint
 * makes players show them upright.
 *
 * When [mirror] is set the frame is flipped along the axis that becomes
 * horizontal once upright, so the video matches the mirrored selfie preview.
 * The overlay is half the frame size (one RGBA pixel per chroma sample),
 * premultiplied, already in the flipped sensor coordinate space.
 */
class VideoEncoder(
    path: String,
    private val width: Int,
    private val height: Int,
    rotation: Int,
    mirror: Boolean,
) {
    private val flipRows = mirror && (rotation == 90 || rotation == 270)
    private val flipCols = mirror && (rotation == 0 || rotation == 180)

    private val codec: MediaCodec
    private val muxer = MediaMuxer(path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private var track = -1
    private var muxing = false
    private var wroteSample = false

    private val thread = HandlerThread("tryon-encoder").apply { start() }
    private val handler = Handler(thread.looper)
    private val pending = AtomicInteger(0)
    private val info = MediaCodec.BufferInfo()

    private var overlay: ByteArray? = null
    private var firstTs = -1L
    private var lastPtsUs = -1L
    private var row = ByteArray(width)

    init {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
            )
            setInteger(MediaFormat.KEY_BIT_RATE, (width * height * 5).coerceAtMost(16_000_000))
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        muxer.setOrientationHint(rotation)
    }

    /** Replaces the overlay used for following frames (null = none). */
    fun setOverlay(rgba: ByteArray?, w: Int, h: Int) {
        val ok = rgba != null && w == width / 2 && h == height / 2
        handler.post { overlay = if (ok) rgba else null }
    }

    /** Queues a frame; returns false (frame dropped) when the encoder lags. */
    fun offer(nv21: ByteArray, stride: Int, tsUs: Long): Boolean {
        if (pending.get() >= 3) return false
        pending.incrementAndGet()
        handler.post {
            try {
                encode(nv21, stride, tsUs)
            } catch (_: Exception) {
            } finally {
                pending.decrementAndGet()
            }
        }
        return true
    }

    /** Finishes the file; [done] gets true if a playable video was written. */
    fun stop(done: (Boolean) -> Unit) {
        handler.post {
            var ok = false
            try {
                val idx = codec.dequeueInputBuffer(100_000)
                if (idx >= 0) {
                    codec.queueInputBuffer(idx, 0, 0, lastPtsUs + 33_333, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                }
                drain(true)
                ok = wroteSample
            } catch (_: Exception) {
            }
            try { codec.stop() } catch (_: Exception) {}
            codec.release()
            try { if (muxing) muxer.stop() } catch (_: Exception) { ok = false }
            muxer.release()
            thread.quitSafely()
            done(ok)
        }
    }

    private fun encode(nv21: ByteArray, stride: Int, tsUs: Long) {
        val idx = codec.dequeueInputBuffer(20_000)
        if (idx < 0) {
            drain(false)
            return
        }
        val image = codec.getInputImage(idx) ?: return
        val planes = image.planes
        writeY(planes[0].buffer, planes[0].rowStride, nv21, stride)
        writeUv(planes[1], planes[2], nv21, stride)
        overlay?.let { blend(planes, it) }

        if (firstTs < 0) firstTs = tsUs
        var pts = tsUs - firstTs
        if (pts <= lastPtsUs) pts = lastPtsUs + 1
        lastPtsUs = pts
        codec.queueInputBuffer(idx, 0, width * height * 3 / 2, pts, 0)
        drain(false)
    }

    private fun writeY(dst: ByteBuffer, rowStride: Int, src: ByteArray, stride: Int) {
        for (y in 0 until height) {
            val sy = if (flipRows) height - 1 - y else y
            dst.position(y * rowStride)
            if (flipCols) {
                val base = sy * stride
                for (x in 0 until width) row[x] = src[base + width - 1 - x]
                dst.put(row, 0, width)
            } else {
                dst.put(src, sy * stride, width)
            }
        }
    }

    private fun writeUv(uPlane: android.media.Image.Plane, vPlane: android.media.Image.Plane, src: ByteArray, stride: Int) {
        val u = uPlane.buffer
        val v = vPlane.buffer
        val uRow = uPlane.rowStride
        val vRow = vPlane.rowStride
        val uPix = uPlane.pixelStride
        val vPix = vPlane.pixelStride
        val uvStart = stride * height
        val cw = width / 2
        val ch = height / 2
        for (y in 0 until ch) {
            val sy = if (flipRows) ch - 1 - y else y
            val srcRow = uvStart + sy * stride
            for (x in 0 until cw) {
                val sx = if (flipCols) cw - 1 - x else x
                val s = srcRow + sx * 2
                v.put(y * vRow + x * vPix, src[s])
                u.put(y * uRow + x * uPix, src[s + 1])
            }
        }
    }

    private fun blend(planes: Array<android.media.Image.Plane>, rgba: ByteArray) {
        val yb = planes[0].buffer
        val yRow = planes[0].rowStride
        val ub = planes[1].buffer
        val vb = planes[2].buffer
        val uRow = planes[1].rowStride
        val vRow = planes[2].rowStride
        val uPix = planes[1].pixelStride
        val vPix = planes[2].pixelStride
        val cw = width / 2
        val ch = height / 2
        for (y in 0 until ch) {
            for (x in 0 until cw) {
                val o = (y * cw + x) * 4
                val a = rgba[o + 3].toInt() and 0xFF
                if (a == 0) continue
                val r = rgba[o].toInt() and 0xFF
                val g = rgba[o + 1].toInt() and 0xFF
                val b = rgba[o + 2].toInt() and 0xFF
                val inv = 255 - a
                // Premultiplied colour -> premultiplied YUV (offsets scaled by alpha).
                val py = (77 * r + 150 * g + 29 * b) shr 8
                val pu = ((-43 * r - 85 * g + 128 * b) shr 8) + ((128 * a) shr 8)
                val pv = ((128 * r - 107 * g - 21 * b) shr 8) + ((128 * a) shr 8)
                for (dy in 0..1) {
                    val rowBase = (y * 2 + dy) * yRow + x * 2
                    for (dx in 0..1) {
                        val i = rowBase + dx
                        val d = yb.get(i).toInt() and 0xFF
                        yb.put(i, (py + d * inv / 255).coerceIn(0, 255).toByte())
                    }
                }
                val ui = y * uRow + x * uPix
                val vi = y * vRow + x * vPix
                val du = ub.get(ui).toInt() and 0xFF
                val dv = vb.get(vi).toInt() and 0xFF
                ub.put(ui, (pu + du * inv / 255).coerceIn(0, 255).toByte())
                vb.put(vi, (pv + dv * inv / 255).coerceIn(0, 255).toByte())
            }
        }
    }

    private fun drain(endOfStream: Boolean) {
        var idle = 0
        while (true) {
            val out = codec.dequeueOutputBuffer(info, if (endOfStream) 10_000 else 0)
            when {
                out == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream || ++idle > 300) return
                }
                out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxing = true
                }
                out >= 0 -> {
                    val buf = codec.getOutputBuffer(out)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (buf != null && info.size > 0 && muxing) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        muxer.writeSampleData(track, buf, info)
                        wroteSample = true
                    }
                    codec.releaseOutputBuffer(out, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }
}
