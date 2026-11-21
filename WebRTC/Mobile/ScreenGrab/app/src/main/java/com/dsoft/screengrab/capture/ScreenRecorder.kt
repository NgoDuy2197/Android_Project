package com.dsoft.screengrab.capture

import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.view.Surface
import com.dsoft.screengrab.Log2

/**
 * Records the screen (full or a cropped region) to an MP4, with optional device audio and/or mic.
 * Video path: VirtualDisplay → [GlPipeline] (crop) → MediaCodec (H.264) → [Muxer].
 */
class ScreenRecorder(
    private val projection: MediaProjection,
    private val dispW: Int,
    private val dispH: Int,
    private val dpi: Int,
) {
    private var codec: MediaCodec? = null
    private var pipeline: GlPipeline? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var muxer: Muxer? = null
    private var audio: AudioEncoder? = null
    private var videoTrack = -1
    @Volatile private var running = false
    private var renderThread: Thread? = null
    private var drainThread: Thread? = null
    private val startNanos = System.nanoTime()
    private var projectionCallback: MediaProjection.Callback? = null

    fun start(outPath: String, crop: Rect, fps: Int, bitrateMbps: Int, useDevice: Boolean, useMic: Boolean): Boolean {
        try {
            val outW = (crop.width() / 2) * 2
            val outH = (crop.height() / 2) * 2
            if (outW < 2 || outH < 2) { Log2.e("vùng quay quá nhỏ"); return false }

            val wantAudio = useDevice || useMic
            // Decide audio availability first so the muxer knows how many tracks to expect.
            val a = if (wantAudio) AudioEncoder(projection, useMic, useDevice) else null
            val audioOk = a?.prepare() ?: false
            if (wantAudio && !audioOk) Log2.w("audio bật nhưng không khởi tạo được nguồn → quay không tiếng")
            muxer = Muxer(outPath, if (audioOk) 2 else 1)

            // video encoder
            val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, outW, outH).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrateMbps * 1_000_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            }
            val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            c.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val encoderSurface: Surface = c.createInputSurface()
            c.start()
            codec = c

            // crop pipeline (full display → encoder surface at region size)
            val gl = GlPipeline(encoderSurface, dispW, dispH, crop)
            gl.start()
            pipeline = gl

            // API 34 needs a registered callback before createVirtualDisplay
            projectionCallback = object : MediaProjection.Callback() {
                override fun onStop() { Log2.w("MediaProjection stopped"); stop() }
            }
            projection.registerCallback(projectionCallback!!, Handler(Looper.getMainLooper()))

            virtualDisplay = projection.createVirtualDisplay(
                "ScreenGrab", dispW, dispH, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                gl.inputSurface, null, null
            )

            // audio
            if (audioOk && a != null) { a.begin(muxer!!); audio = a }

            running = true
            drainThread = Thread({ drainVideo() }, "ScreenGrab-VideoDrain").also { it.start() }
            renderThread = Thread({ renderLoop(fps) }, "ScreenGrab-Render").also { it.start() }
            Log2.i("đang quay ${outW}x$outH @${fps}fps → $outPath (devAudio=$useDevice mic=$useMic)")
            return true
        } catch (e: Throwable) {
            Log2.e("start record failed", e)
            stop()
            return false
        }
    }

    private fun renderLoop(fps: Int) {
        val frameNs = 1_000_000_000L / fps
        var next = System.nanoTime()
        val gl = pipeline ?: return
        while (running) {
            val pts = System.nanoTime() - startNanos
            gl.drawFrameBlocking(pts)
            next += frameNs
            val sleep = next - System.nanoTime()
            if (sleep > 0) { try { Thread.sleep(sleep / 1_000_000, (sleep % 1_000_000).toInt()) } catch (_: InterruptedException) { break } }
            else next = System.nanoTime()
        }
    }

    private fun drainVideo() {
        val c = codec ?: return
        val info = MediaCodec.BufferInfo()
        try {
            while (running || true) {
                val outIdx = c.dequeueOutputBuffer(info, 10_000)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> videoTrack = muxer!!.addTrack(c.outputFormat)
                    outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> { if (!running) break }
                    outIdx >= 0 -> {
                        val buf = c.getOutputBuffer(outIdx)
                        if (buf != null && info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && videoTrack >= 0) {
                            buf.position(info.offset); buf.limit(info.offset + info.size)
                            muxer!!.write(videoTrack, buf, info)
                        }
                        c.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
        } catch (e: Exception) {
            Log2.e("video drain failed", e)
        }
    }

    fun stop() {
        if (!running && codec == null) return
        running = false
        runCatching { renderThread?.join(500) }
        // signal end of video stream, let drain flush
        runCatching { codec?.signalEndOfInputStream() }
        runCatching { drainThread?.join(1000) }
        runCatching { audio?.stop() }
        runCatching { virtualDisplay?.release() }
        runCatching { pipeline?.release() }
        runCatching { codec?.stop(); codec?.release() }
        runCatching { muxer?.stop() }
        runCatching { projectionCallback?.let { projection.unregisterCallback(it) } }
        codec = null; pipeline = null; virtualDisplay = null; muxer = null; audio = null
        Log2.i("đã dừng quay")
    }
}
