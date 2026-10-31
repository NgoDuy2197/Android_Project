package com.dsoft.toolbox

import android.app.Activity
import android.content.Context
import android.hardware.camera2.CameraManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.TextView

/**
 * Emergency helper: the torch (steady / strobe / SOS) plus a loud alarm siren (AudioTrack on the
 * ALARM stream, forced to max volume), and a combined "BÁO ĐỘNG" mode that flashes and sounds at once.
 */
class EmergencyActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var cm: CameraManager
    private var camId: String? = null
    private var mode = Mode.OFF
    private var strobeMs = 120L
    private lateinit var p: Palette

    private lateinit var alarmBtn: TextView
    private lateinit var lightBtn: TextView
    private lateinit var soundBtn: TextView

    @Volatile private var sirenOn = false
    private var sirenThread: Thread? = null
    private var savedAlarmVol = -1

    private enum class Mode { OFF, ON, STROBE, SOS }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        p = palette(); setupSystemBars(p)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        camId = findTorch()

        val col = page(p, "Hỗ trợ khẩn cấp")

        // ---- big combined alarm ----
        alarmBtn = bigButton("🚨 BÁO ĐỘNG", p.red, p.red) { toggleAlarm() }
        col.addView(alarmBtn, vlp(-1, -2).apply { topMargin = dp(8); marginStart = dp(4); marginEnd = dp(4); height = dp(72) })
        col.addView(footnote(p, "Bật đồng thời đèn nhấp nháy + còi hú to nhất để gây chú ý khi khẩn cấp."))

        // ---- light ----
        col.addView(sectionHeader(p, "Đèn"))
        lightBtn = bigButton("Bật đèn", p.orange, p.orange) { setLight(if (mode == Mode.ON) Mode.OFF else Mode.ON) }
        col.addView(lightBtn, vlp(-1, -2).apply { topMargin = dp(8); marginStart = dp(4); marginEnd = dp(4) })
        if (camId == null) {
            col.addView(footnote(p, "Máy không có đèn flash — còi vẫn dùng được."))
        } else {
            col.addView(group(p,
                row(p, "Nhấp nháy (strobe)", chevron(p)) { setLight(if (mode == Mode.STROBE) Mode.OFF else Mode.STROBE) },
                row(p, "SOS (··· — — — ···)", chevron(p)) { setLight(if (mode == Mode.SOS) Mode.OFF else Mode.SOS) },
            ))
            val speedLabel = text("Tốc độ nháy: ${strobeMs}ms", 15f, p.text)
            col.addView(card(p).apply {
                setPadding(dp(14), dp(10), dp(14), dp(10))
                addView(speedLabel)
                addView(android.widget.SeekBar(this@EmergencyActivity).apply {
                    max = 480; progress = (strobeMs - 20).toInt().coerceIn(0, 480)
                    setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(s: android.widget.SeekBar?, v: Int, u: Boolean) {
                            strobeMs = (v + 20).toLong(); speedLabel.text = "Tốc độ nháy: ${strobeMs}ms"
                        }
                        override fun onStartTrackingTouch(s: android.widget.SeekBar?) {}
                        override fun onStopTrackingTouch(s: android.widget.SeekBar?) {}
                    })
                }, vlp(-1, -2).apply { topMargin = dp(6) })
            }, vlp())
        }

        // ---- sound ----
        col.addView(sectionHeader(p, "Âm thanh"))
        soundBtn = bigButton("Bật còi", p.pink, p.pink) { if (sirenOn) stopSiren() else startSiren(); syncButtons() }
        col.addView(soundBtn, vlp(-1, -2).apply { topMargin = dp(8); marginStart = dp(4); marginEnd = dp(4) })
        col.addView(footnote(p, "Còi hú kéo âm lượng báo thức lên tối đa. Nháy nhanh có thể gây khó chịu với người nhạy cảm ánh sáng."))
    }

    // ---------- torch ----------
    private fun findTorch(): String? = try {
        cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
    } catch (_: Exception) { null }

    private fun torch(on: Boolean) { runCatching { camId?.let { cm.setTorchMode(it, on) } } }

    private fun setLight(m: Mode) {
        main.removeCallbacksAndMessages(null)
        mode = m
        when (m) {
            Mode.OFF -> torch(false)
            Mode.ON -> torch(true)
            Mode.STROBE -> strobe()
            Mode.SOS -> sos()
        }
        syncButtons()
    }

    private fun strobe() {
        var on = false
        val tick = object : Runnable {
            override fun run() { on = !on; torch(on); main.postDelayed(this, strobeMs) }
        }
        main.post(tick)
    }

    private fun sos() {
        val u = 200L
        val pattern = longArrayOf(u, u, u, u, u, u * 3, u * 3, u, u * 3, u, u * 3, u * 3, u, u, u, u, u, u * 7)
        var idx = 0; var lit = true
        val tick = object : Runnable {
            override fun run() {
                torch(lit); main.postDelayed(this, pattern[idx])
                lit = !lit; idx = (idx + 1) % pattern.size
            }
        }
        main.post(tick)
    }

    // ---------- siren ----------
    private fun startSiren() {
        if (sirenOn) return
        sirenOn = true
        runCatching {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            savedAlarmVol = am.getStreamVolume(AudioManager.STREAM_ALARM)
            am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
        }
        sirenThread = Thread {
            val rate = 44100
            val minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder()
                    .setSampleRate(rate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(maxOf(minBuf, rate / 2))
                .setTransferMode(AudioTrack.MODE_STREAM).build()
            runCatching { track.play() }
            val block = ShortArray(rate / 20)        // 50 ms blocks
            var phase = 0.0
            var t = 0.0
            val sweep = 1.3                            // seconds per up+down sweep
            while (sirenOn) {
                for (i in block.indices) {
                    val lfo = (Math.sin(2 * Math.PI * t / sweep) + 1) / 2   // 0..1
                    val freq = 650 + lfo * 750                               // 650..1400 Hz
                    phase += 2 * Math.PI * freq / rate
                    if (phase > 2 * Math.PI) phase -= 2 * Math.PI
                    block[i] = (Math.sin(phase) * 32767 * 0.92).toInt().toShort()
                    t += 1.0 / rate
                }
                runCatching { track.write(block, 0, block.size) }
            }
            runCatching { track.stop(); track.release() }
        }.also { it.start() }
    }

    private fun stopSiren() {
        sirenOn = false
        sirenThread = null
        if (savedAlarmVol >= 0) runCatching {
            (getSystemService(Context.AUDIO_SERVICE) as AudioManager)
                .setStreamVolume(AudioManager.STREAM_ALARM, savedAlarmVol, 0)
        }
        savedAlarmVol = -1
    }

    // ---------- combined ----------
    private fun toggleAlarm() {
        val active = sirenOn || mode == Mode.STROBE
        if (active) {
            stopSiren()
            setLight(Mode.OFF)
        } else {
            startSiren()
            if (camId != null) setLight(Mode.STROBE)
        }
        syncButtons()
    }

    private fun syncButtons() {
        if (::alarmBtn.isInitialized) alarmBtn.text = if (sirenOn || mode == Mode.STROBE) "⏹ TẮT BÁO ĐỘNG" else "🚨 BÁO ĐỘNG"
        if (::lightBtn.isInitialized) lightBtn.text = if (mode == Mode.ON) "Tắt đèn" else "Bật đèn"
        if (::soundBtn.isInitialized) soundBtn.text = if (sirenOn) "Tắt còi" else "Bật còi"
    }

    override fun onPause() {
        super.onPause()
        main.removeCallbacksAndMessages(null)
        torch(false)
        stopSiren()
        mode = Mode.OFF
    }
}
