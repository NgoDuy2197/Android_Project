package com.dsoft.screengrab.capture

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Build
import com.dsoft.screengrab.Log2

/**
 * Captures microphone and/or device (internal) audio, mixes them to mono 44.1 kHz PCM, encodes AAC,
 * and feeds the muxer. Device audio uses AudioPlaybackCapture (API 29+) via the MediaProjection.
 */
class AudioEncoder(
    private val projection: MediaProjection?,
    private val useMic: Boolean,
    private val useDevice: Boolean,
) {
    private val sampleRate = 44100
    private val chMask = AudioFormat.CHANNEL_IN_MONO
    private var mic: AudioRecord? = null
    private var dev: AudioRecord? = null
    private var codec: MediaCodec? = null
    private var trackIndex = -1
    private var bufSize = 4096
    private lateinit var muxer: Muxer
    @Volatile private var running = false
    private var thread: Thread? = null
    private val startNanos = System.nanoTime()

    /** Set up audio sources + AAC encoder. Returns true if at least one source is ready. */
    @SuppressLint("MissingPermission")
    fun prepare(): Boolean {
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, chMask, AudioFormat.ENCODING_PCM_16BIT)
        bufSize = maxOf(minBuf, 4096)

        if (useMic) {
            mic = runCatching {
                AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, chMask, AudioFormat.ENCODING_PCM_16BIT, bufSize)
            }.getOrNull()
            if (mic?.state != AudioRecord.STATE_INITIALIZED) { Log2.w("mic AudioRecord init failed"); mic = null }
        }
        if (useDevice && Build.VERSION.SDK_INT >= 29 && projection != null) {
            dev = runCatching {
                val cfg = AudioPlaybackCaptureConfiguration.Builder(projection!!)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build()
                AudioRecord.Builder()
                    .setAudioFormat(AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(chMask)
                        .build())
                    .setBufferSizeInBytes(bufSize)
                    .setAudioPlaybackCaptureConfig(cfg)
                    .build()
            }.getOrNull()
            if (dev?.state != AudioRecord.STATE_INITIALIZED) { Log2.w("device-audio AudioRecord init failed"); dev = null }
        } else if (useDevice && Build.VERSION.SDK_INT < 29) {
            Log2.w("device audio cần Android 10+ (API 29)")
        }

        if (mic == null && dev == null) { Log2.w("no audio source available"); return false }

        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
            val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, android.media.MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, bufSize * 2)
            }
            configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            start()
        }
        return true
    }

    /** Begin recording + encoding into [m]. Call after [prepare] returned true. */
    fun begin(m: Muxer) {
        muxer = m
        running = true
        mic?.startRecording()
        dev?.startRecording()
        thread = Thread({ loop(bufSize) }, "ScreenGrab-Audio").also { it.start() }
    }

    private fun loop(bufSize: Int) {
        val codec = codec ?: return
        val info = MediaCodec.BufferInfo()
        val micBuf = ShortArray(bufSize / 2)
        val devBuf = ShortArray(bufSize / 2)
        val mixed = ShortArray(bufSize / 2)
        try {
            while (running) {
                val n = readMixed(micBuf, devBuf, mixed)
                if (n > 0) feed(codec, mixed, n)
                drain(codec, info, false)
            }
            // flush
            val inIdx = codec.dequeueInputBuffer(10_000)
            if (inIdx >= 0) codec.queueInputBuffer(inIdx, 0, 0, ptsUs(), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            drain(codec, info, true)
        } catch (e: Exception) {
            Log2.e("audio loop failed", e)
        }
    }

    /** Read from whichever sources are active and mix into [mixed]; returns #samples. */
    private fun readMixed(micBuf: ShortArray, devBuf: ShortArray, mixed: ShortArray): Int {
        val m = mic
        val d = dev
        val nm = if (m != null) m.read(micBuf, 0, micBuf.size) else 0
        val nd = if (d != null) d.read(devBuf, 0, devBuf.size) else 0
        val n = maxOf(nm, nd)
        if (n <= 0) return 0
        for (i in 0 until n) {
            val a = if (i < nm) micBuf[i].toInt() else 0
            val b = if (i < nd) devBuf[i].toInt() else 0
            var s = a + b
            if (s > Short.MAX_VALUE) s = Short.MAX_VALUE.toInt()
            if (s < Short.MIN_VALUE) s = Short.MIN_VALUE.toInt()
            mixed[i] = s.toShort()
        }
        return n
    }

    private fun feed(codec: MediaCodec, pcm: ShortArray, n: Int) {
        val inIdx = codec.dequeueInputBuffer(10_000)
        if (inIdx < 0) return
        val buf = codec.getInputBuffer(inIdx) ?: return
        buf.clear()
        buf.asShortBuffer().put(pcm, 0, n)
        codec.queueInputBuffer(inIdx, 0, n * 2, ptsUs(), 0)
    }

    private fun drain(codec: MediaCodec, info: MediaCodec.BufferInfo, eos: Boolean) {
        while (true) {
            val outIdx = codec.dequeueOutputBuffer(info, if (eos) 10_000 else 0)
            when {
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    trackIndex = muxer.addTrack(codec.outputFormat)
                }
                outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                outIdx >= 0 -> {
                    val buf = codec.getOutputBuffer(outIdx)
                    if (buf != null && info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && trackIndex >= 0) {
                        buf.position(info.offset); buf.limit(info.offset + info.size)
                        muxer.write(trackIndex, buf, info)
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    private fun ptsUs(): Long = (System.nanoTime() - startNanos) / 1000

    fun stop() {
        running = false
        runCatching { thread?.join(500) }
        runCatching { mic?.stop(); mic?.release() }
        runCatching { dev?.stop(); dev?.release() }
        runCatching { codec?.stop(); codec?.release() }
        mic = null; dev = null; codec = null
    }
}
