package com.example.screen_share

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.cloudwebrtc.webrtc.FlutterWebRTCPlugin
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit

/**
 * Thu âm thanh hệ thống (âm thanh đang phát trên máy) ở phía **Client**.
 *
 * flutter_webrtc trên Android chỉ quay hình khi getDisplayMedia, không có âm
 * thanh. Ta dùng AudioPlaybackCapture (Android 10+) trên chính MediaProjection
 * mà flutter_webrtc đang dùng (Android 14+ không cho tạo MediaProjection thứ hai
 * từ cùng một lần cấp quyền), rồi đẩy PCM 16-bit từng khối 20ms qua [onChunk].
 *
 * Một số app chặn việc bị thu âm (vd game cũ targetSdk ≤ 28, app gọi điện, app
 * có DRM) — khi đó sẽ chỉ nhận được im lặng, đây là giới hạn của Android. Lúc đó
 * Server có thể chuyển sang thu bằng micro (`useMic`): thu tiếng loa + môi trường.
 */
class SystemAudioCapture(private val onChunk: (ByteArray) -> Unit) {

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var running = false
    private var record: AudioRecord? = null
    private var thread: Thread? = null

    /** Trả về null nếu bắt đầu được, ngược lại là thông báo lỗi. */
    @SuppressLint("MissingPermission")
    fun start(context: Context, sampleRate: Int, channels: Int, useMic: Boolean): String? {
        stop()
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return "Chưa cấp quyền ghi âm"
        }
        if (!useMic && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return "Cần Android 10 trở lên để thu âm thanh hệ thống (hãy dùng micro)"
        }
        return try {
            val mask = if (channels == 2) AudioFormat.CHANNEL_IN_STEREO
            else AudioFormat.CHANNEL_IN_MONO
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(mask)
                .build()
            val chunk = sampleRate / 50 * channels * 2 // 20ms
            val minBuf = AudioRecord.getMinBufferSize(
                sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT
            )
            val builder = AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(minBuf * 2, chunk * 4))
            if (useMic) {
                builder.setAudioSource(MediaRecorder.AudioSource.MIC)
            } else {
                val projection = findWebRtcProjection()
                    ?: return "Không tìm thấy phiên quay màn hình"
                builder.setAudioPlaybackCaptureConfig(playbackConfig(projection))
            }
            val rec = builder.build()
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                rec.release()
                return "Không khởi tạo được bộ thu âm thanh"
            }
            record = rec
            running = true
            rec.startRecording()
            thread = Thread({ loop(rec, chunk) }, "ss-audio-capture").apply { start() }
            null
        } catch (e: Throwable) {
            stop()
            e.message ?: e.toString()
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun playbackConfig(projection: MediaProjection) =
        AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

    private fun loop(rec: AudioRecord, chunk: Int) {
        val buf = ByteArray(chunk)
        while (running) {
            val n = try {
                rec.read(buf, 0, chunk)
            } catch (_: Throwable) {
                -1
            }
            if (n < 0) break
            if (n == 0) continue
            val out = buf.copyOf(n)
            main.post { if (running) onChunk(out) }
        }
    }

    fun stop() {
        running = false
        val rec = record
        record = null
        try {
            rec?.stop()
        } catch (_: Throwable) {
        }
        try {
            thread?.join(300)
        } catch (_: Throwable) {
        }
        thread = null
        try {
            rec?.release()
        } catch (_: Throwable) {
        }
    }

    /**
     * Lấy MediaProjection đang chạy bên trong flutter_webrtc bằng reflection:
     * FlutterWebRTCPlugin → methodCallHandler → getUserMediaImpl →
     * mVideoCapturers → capturer (OrientationAwareScreenCapturer) → mediaProjection.
     * Tên trường khớp flutter_webrtc 1.5.x; plugin đã giữ nguyên tên lớp qua
     * proguard (consumer rules). Đổi phiên bản plugin → cần kiểm tra lại.
     */
    private fun findWebRtcProjection(): MediaProjection? {
        return try {
            val plugin = FlutterWebRTCPlugin.sharedSingleton ?: return null
            val handler = field(plugin, "methodCallHandler") ?: return null
            val gum = field(handler, "getUserMediaImpl") ?: return null
            val capturers = field(gum, "mVideoCapturers") as? Map<*, *> ?: return null
            capturers.values.reversed().firstNotNullOfOrNull { info ->
                val capturer = info?.let { field(it, "capturer") } ?: return@firstNotNullOfOrNull null
                field(capturer, "mediaProjection") as? MediaProjection
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun field(target: Any, name: String): Any? {
        var cls: Class<*>? = target.javaClass
        while (cls != null) {
            try {
                val f = cls.getDeclaredField(name)
                f.isAccessible = true
                return f.get(target)
            } catch (_: NoSuchFieldException) {
                cls = cls.superclass
            }
        }
        return null
    }
}

/**
 * Phát âm thanh PCM 16-bit nhận từ client ở phía **Server**.
 *
 * Dữ liệu được đưa vào hàng đợi và một luồng riêng ghi vào AudioTrack. Nếu hàng
 * đợi dồn quá [MAX_QUEUED] khối (mạng chậm rồi dồn dập), bỏ khối cũ nhất để giữ
 * độ trễ thấp thay vì phát chậm dần.
 *
 * Khi bật [howlGuard] (client thu bằng micro), mỗi khối được đưa qua
 * [HowlDetector]: nghi có hú → hạ âm lượng còn [DUCK_GAIN] ngay (giảm độ lợi vòng
 * lặp); hú kéo dài → tạm ngắt loa [HOWL_MUTE_MS] (dừng + xoá bộ đệm) để phá vòng
 * lặp rồi phát tiếp, và gọi [onHowl] để báo lên UI.
 */
class AudioStreamPlayer(private val onHowl: () -> Unit) {

    private companion object {
        const val MAX_QUEUED = 10 // ~200ms
        const val HOWL_MUTE_MS = 1500L
        const val DUCK_GAIN = 0.25f
    }

    private val main = Handler(Looper.getMainLooper())
    private val queue = LinkedBlockingDeque<ByteArray>()
    @Volatile private var running = false
    private var track: AudioTrack? = null
    private var thread: Thread? = null
    private var detector: HowlDetector? = null
    @Volatile private var userMuted = false
    @Volatile private var ducked = false

    fun start(sampleRate: Int, channels: Int, howlGuard: Boolean) {
        stop()
        detector = if (howlGuard) HowlDetector(sampleRate, channels) else null
        ducked = false
        try {
            val mask = if (channels == 2) AudioFormat.CHANNEL_OUT_STEREO
            else AudioFormat.CHANNEL_OUT_MONO
            val minBuf = AudioTrack.getMinBufferSize(
                sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT
            )
            val t = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(mask)
                        .build()
                )
                .setBufferSizeInBytes(minBuf * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            track = t
            running = true
            t.play()
            thread = Thread({ loop(t) }, "ss-audio-play").apply { start() }
        } catch (_: Throwable) {
            stop()
        }
    }

    fun write(bytes: ByteArray) {
        if (!running) return
        while (queue.size >= MAX_QUEUED) queue.pollFirst()
        queue.offerLast(bytes)
    }

    fun setMuted(muted: Boolean) {
        userMuted = muted
        applyVolume()
    }

    private fun applyVolume() {
        val gain = when {
            userMuted -> 0f
            ducked -> DUCK_GAIN
            else -> 1f
        }
        try {
            track?.setVolume(gain)
        } catch (_: Throwable) {
        }
    }

    private fun setDucked(on: Boolean) {
        if (ducked == on) return
        ducked = on
        applyVolume()
    }

    private fun loop(t: AudioTrack) {
        var mutedUntil = 0L
        while (running) {
            val data = try {
                queue.pollFirst(100, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                null
            } ?: continue
            try {
                val now = System.currentTimeMillis()
                if (mutedUntil != 0L) {
                    if (now < mutedUntil) continue // bỏ dữ liệu trong lúc ngắt
                    mutedUntil = 0L
                    detector?.reset()
                    setDucked(false)
                    t.play()
                }
                when (detector?.process(data)) {
                    HowlDetector.Result.HOWL -> {
                        // Phá vòng lặp: dừng loa + xoá phần đang chờ phát.
                        mutedUntil = now + HOWL_MUTE_MS
                        t.pause()
                        t.flush()
                        queue.clear()
                        main.post { onHowl() }
                        continue
                    }
                    HowlDetector.Result.SUSPECT -> setDucked(true)
                    HowlDetector.Result.CLEAR -> setDucked(false)
                    else -> {}
                }
                t.write(data, 0, data.size)
            } catch (_: Throwable) {
                break
            }
        }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        try {
            thread?.join(300)
        } catch (_: Throwable) {
        }
        thread = null
        queue.clear()
        val t = track
        track = null
        try {
            t?.stop()
        } catch (_: Throwable) {
        }
        try {
            t?.release()
        } catch (_: Throwable) {
        }
    }
}
