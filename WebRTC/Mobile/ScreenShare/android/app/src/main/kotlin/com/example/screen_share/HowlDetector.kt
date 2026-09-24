package com.example.screen_share

import android.util.Log
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Phát hiện tiếng hú do vòng lặp âm thanh (acoustic feedback): micro client thu
 * lại tiếng loa server → server phát lại → ... Dấu hiệu đặc trưng: âm lượng lớn,
 * năng lượng dồn gần hết vào MỘT tần số (âm đơn, "cao vút") và tần số đó giữ
 * nguyên liên tục. Tiếng nói / game / nhạc thông thường có phổ trải rộng hoặc
 * đổi cao độ liên tục nên không thoả cả ba điều kiện cùng lúc đủ lâu.
 *
 * Nhận PCM 16-bit little-endian, gom thành cửa sổ [FFT_SIZE] mẫu (mono), với mỗi
 * cửa sổ tính phổ bằng FFT và kiểm tra các điều kiện. [process] trả về:
 * - [Result.SUSPECT] khi nghi có hú đủ [SUSPECT_MS] → nên hạ âm lượng ngay để
 *   giảm độ lợi vòng lặp (thường đủ làm tiếng hú tắt dần);
 * - [Result.HOWL] khi hú kéo dài đủ [MIN_HOWL_MS] → ngắt hẳn;
 * - [Result.CLEAR] khi không còn dấu hiệu.
 * Log tag "Howl" ghi thông số từng khung đủ to để chỉnh ngưỡng.
 */
class HowlDetector(private val sampleRate: Int, private val channels: Int) {

    enum class Result { CLEAR, NONE, SUSPECT, HOWL }

    private companion object {
        const val TAG = "Howl"
        const val FFT_SIZE = 1024
        const val MIN_LEVEL_DBFS = -30.0 // đủ to mới xét
        const val MIN_PEAK_RATIO = 30.0 // đỉnh ≥ ~15dB so với trung bình phổ
        const val MIN_PEAK_SHARE = 0.5 // đỉnh chiếm ≥ 50% năng lượng (âm đơn)
        const val MIN_FREQ_HZ = 300.0
        const val MAX_DRIFT_HZ = 25.0 // tần số đỉnh gần như đứng yên
        const val SUSPECT_MS = 90
        const val MIN_HOWL_MS = 260
        const val LOG_LEVEL_DBFS = -45.0
    }

    private val window = DoubleArray(FFT_SIZE) { 0.5 - 0.5 * cos(2 * PI * it / (FFT_SIZE - 1)) }
    private val buf = DoubleArray(FFT_SIZE)
    private var filled = 0
    private val re = DoubleArray(FFT_SIZE)
    private val im = DoubleArray(FFT_SIZE)

    private var lastPeakBin = -1
    private var howlFrames = 0
    private val framesNeeded =
        maxOf(2, (MIN_HOWL_MS * sampleRate / 1000.0 / FFT_SIZE).toInt())
    private val suspectFrames =
        maxOf(1, (SUSPECT_MS * sampleRate / 1000.0 / FFT_SIZE).toInt())
    private val maxBinDrift =
        maxOf(1, kotlin.math.ceil(MAX_DRIFT_HZ * FFT_SIZE / sampleRate).toInt())

    fun reset() {
        filled = 0
        lastPeakBin = -1
        howlFrames = 0
    }

    fun process(pcm: ByteArray): Result {
        var result = Result.NONE
        val frameBytes = 2 * channels
        var i = 0
        while (i + frameBytes <= pcm.size) {
            // Trộn các kênh về mono.
            var sum = 0.0
            for (c in 0 until channels) {
                val o = i + c * 2
                val s = (pcm[o].toInt() and 0xFF) or (pcm[o + 1].toInt() shl 8)
                sum += s.toShort().toDouble()
            }
            buf[filled++] = sum / channels
            if (filled == FFT_SIZE) {
                val r = analyze()
                if (r.ordinal > result.ordinal) result = r
                filled = 0
            }
            i += frameBytes
        }
        return result
    }

    private fun analyze(): Result {
        var energy = 0.0
        for (n in 0 until FFT_SIZE) {
            energy += buf[n] * buf[n]
            re[n] = buf[n] * window[n]
            im[n] = 0.0
        }
        val rms = sqrt(energy / FFT_SIZE)
        val level = 20 * log10(maxOf(rms, 1.0) / 32768.0)
        if (level < MIN_LEVEL_DBFS) {
            if (level >= LOG_LEVEL_DBFS) Log.d(TAG, "quiet lvl=%.1f".format(level))
            return miss()
        }

        fft(re, im)
        val minBin = maxOf(1, (MIN_FREQ_HZ * FFT_SIZE / sampleRate).toInt())
        val maxBin = FFT_SIZE / 2 - 1
        var peakBin = minBin
        var peakPow = 0.0
        var total = 0.0
        for (k in minBin..maxBin) {
            val p = re[k] * re[k] + im[k] * im[k]
            total += p
            if (p > peakPow) {
                peakPow = p
                peakBin = k
            }
        }
        // Năng lượng của đỉnh tính cả 2 bin lân cận (cửa sổ Hann làm loang đỉnh).
        var peakBand = 0.0
        for (k in maxOf(minBin, peakBin - 1)..minOf(maxBin, peakBin + 1)) {
            peakBand += re[k] * re[k] + im[k] * im[k]
        }
        val restBins = (maxBin - minBin + 1) - 3
        val restMean = (total - peakBand) / maxOf(1, restBins)
        val ratio = peakPow / maxOf(restMean, 1e-9)
        // Giọng nói / nhạc có nhiều hoạ âm chia năng lượng → không đạt tỉ lệ này.
        val share = peakBand / maxOf(total, 1e-9)
        Log.d(
            TAG, "lvl=%.1f hz=%d ratio=%.0f share=%.2f run=%d".format(
                level, peakBin * sampleRate / FFT_SIZE, ratio, share, howlFrames
            )
        )
        if (ratio < MIN_PEAK_RATIO || share < MIN_PEAK_SHARE) return miss()

        if (lastPeakBin >= 0 && abs(peakBin - lastPeakBin) <= maxBinDrift) {
            howlFrames++
        } else {
            howlFrames = 1
        }
        lastPeakBin = peakBin
        if (howlFrames >= framesNeeded) {
            Log.i(TAG, "HOWL at ${peakBin * sampleRate / FFT_SIZE}Hz")
            reset()
            return Result.HOWL
        }
        return if (howlFrames >= suspectFrames) Result.SUSPECT else Result.NONE
    }

    private fun miss(): Result {
        val was = howlFrames
        howlFrames = 0
        lastPeakBin = -1
        return if (was > 0) Result.CLEAR else Result.NONE
    }

    /** FFT radix-2 tại chỗ. */
    private fun fft(x: DoubleArray, y: DoubleArray) {
        val n = x.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                var t = x[i]; x[i] = x[j]; x[j] = t
                t = y[i]; y[i] = y[j]; y[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            val wr = cos(ang)
            val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0
                var ci = 0.0
                for (k in 0 until len / 2) {
                    val a = i + k
                    val b = a + len / 2
                    val tr = x[b] * cr - y[b] * ci
                    val ti = x[b] * ci + y[b] * cr
                    x[b] = x[a] - tr
                    y[b] = y[a] - ti
                    x[a] += tr
                    y[a] += ti
                    val ncr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
    }
}
