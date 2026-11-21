package com.dsoft.screengrab.capture

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import com.dsoft.screengrab.Log2
import java.nio.ByteBuffer

/**
 * Thin wrapper over [MediaMuxer] that waits until all expected tracks have reported their format
 * before starting. Samples that arrive before start are dropped (a few early frames at most).
 */
class Muxer(path: String, private val expectedTracks: Int) {
    private val muxer = MediaMuxer(path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private var added = 0
    @Volatile private var started = false
    @Volatile private var stopped = false

    @Synchronized fun addTrack(format: MediaFormat): Int {
        val i = muxer.addTrack(format)
        added++
        if (added >= expectedTracks && !started) {
            muxer.start()
            started = true
            Log2.i("muxer started ($added tracks)")
        }
        return i
    }

    val isStarted get() = started

    @Synchronized fun write(track: Int, buf: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (!started || stopped) return
        if (info.size <= 0) return
        try { muxer.writeSampleData(track, buf, info) }
        catch (e: Exception) { Log2.e("muxer write failed", e) }
    }

    @Synchronized fun stop() {
        if (stopped) return
        stopped = true
        runCatching { if (started) muxer.stop() }
        runCatching { muxer.release() }
    }
}
