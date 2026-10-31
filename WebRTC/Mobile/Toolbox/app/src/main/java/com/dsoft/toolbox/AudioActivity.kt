package com.dsoft.toolbox

import android.app.Activity
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.audiofx.LoudnessEnhancer
import android.os.Build
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Audio helper: force output to the loudspeaker vs the earpiece ("loa còn lại"), and a global
 * loudness booster (LoudnessEnhancer on session 0) that lifts volume past the system maximum.
 *
 * Both are best-effort and device-dependent: Android lets an app force the *communication* route
 * (calls, VoIP) with setCommunicationDevice, and the booster attaches to the global output mix,
 * which most — but not all — OEM audio stacks honour. See the footnotes shown to the user.
 */
class AudioActivity : Activity() {

    private lateinit var p: Palette
    private lateinit var am: AudioManager
    private lateinit var routeStatus: TextView
    private lateinit var boostStatus: TextView
    private lateinit var boostLabel: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        p = palette(); setupSystemBars(p)
        am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val col = page(p, "Âm thanh")

        // ---- output routing ----
        col.addView(sectionHeader(p, "Đầu ra âm thanh"))
        routeStatus = text(routeText(), 13f, p.secondary)
        col.addView(group(p,
            row(p, "Loa ngoài", chevron(p)) { route(Route.SPEAKER) },
            row(p, "Loa trong (nghe gọi)", chevron(p)) { route(Route.EARPIECE) },
            row(p, "Mặc định hệ thống", chevron(p)) { route(Route.AUTO) },
        ))
        col.addView(card(p).apply { setPadding(dp(14), dp(10), dp(14), dp(10)); addView(routeStatus) }, vlp())
        col.addView(footnote(p,
            "Android chỉ cho ép tuyến âm thanh đàm thoại/VoIP (nghe gọi, Zalo, Messenger…). " +
            "Với nhạc/video của app khác thì nhiều máy không cho ép sang loa trong — đây là giới hạn của hệ thống."))

        // ---- loudness boost ----
        col.addView(sectionHeader(p, "Tăng âm lượng (vượt mức hệ thống)"))
        boostStatus = bigButton(if (boost != null) "Tắt tăng âm" else "Bật tăng âm", p.pink, p.pink) { toggleBoost() }
        col.addView(boostStatus, vlp(-1, -2).apply { topMargin = dp(8); marginStart = dp(4); marginEnd = dp(4) })
        boostLabel = text("Mức tăng: +${gainMb / 100} dB", 15f, p.text)
        col.addView(card(p).apply {
            setPadding(dp(14), dp(10), dp(14), dp(10))
            addView(boostLabel)
            addView(android.widget.SeekBar(this@AudioActivity).apply {
                max = MAX_GAIN_MB / 100            // 0..30 dB
                progress = gainMb / 100
                setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: android.widget.SeekBar?, v: Int, u: Boolean) {
                        gainMb = v * 100
                        boostLabel.text = "Mức tăng: +${v} dB"
                        runCatching { boost?.setTargetGain(gainMb) }
                    }
                    override fun onStartTrackingTouch(s: android.widget.SeekBar?) {}
                    override fun onStopTrackingTouch(s: android.widget.SeekBar?) {}
                })
            }, vlp(-1, -2).apply { topMargin = dp(6) })
        }, vlp())
        col.addView(group(p,
            row(p, "Đặt âm lượng media tối đa", chevron(p)) {
                runCatching {
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0)
                }
                toast("Đã kéo âm lượng media lên tối đa")
            },
        ))
        col.addView(footnote(p,
            "Tăng âm gắn vào luồng ra chung nên áp dụng cho mọi app đang phát. Đẩy quá cao có thể rè/méo " +
            "tiếng và hại loa. Hiệu ứng còn hiệu lực khi app còn chạy; một số máy OEM có thể không hỗ trợ."))
    }

    private enum class Route { SPEAKER, EARPIECE, AUTO }

    private fun route(r: Route) {
        runCatching {
            if (r == Route.AUTO) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) am.clearCommunicationDevice()
                else @Suppress("DEPRECATION") { am.isSpeakerphoneOn = false }
                am.mode = AudioManager.MODE_NORMAL
            } else {
                am.mode = AudioManager.MODE_IN_COMMUNICATION
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val type = if (r == Route.SPEAKER) AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                               else AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
                    val dev = am.availableCommunicationDevices.firstOrNull { it.type == type }
                    if (dev != null) am.setCommunicationDevice(dev)
                    else toast("Máy không có thiết bị này")
                } else {
                    @Suppress("DEPRECATION")
                    am.isSpeakerphoneOn = (r == Route.SPEAKER)
                }
            }
        }.onFailure { toast("Không đổi được đầu ra: ${it.message}") }
        routeStatus.text = routeText()
    }

    private fun routeText(): String {
        val dev = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) am.communicationDevice?.type else null
        val name = when (dev) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Loa ngoài"
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Loa trong"
            else -> if (am.mode == AudioManager.MODE_NORMAL) "Mặc định hệ thống" else "—"
        }
        return "Đang dùng: $name"
    }

    private fun toggleBoost() {
        if (boost != null) {
            runCatching { boost?.release() }
            boost = null
        } else {
            runCatching {
                boost = LoudnessEnhancer(0).apply { setTargetGain(gainMb); enabled = true }
            }.onFailure { toast("Máy không hỗ trợ tăng âm: ${it.message}") }
        }
        boostStatus.text = if (boost != null) "Tắt tăng âm" else "Bật tăng âm"
    }

    companion object {
        private const val MAX_GAIN_MB = 3000       // +30 dB ceiling
        private var gainMb = 1000                  // default +10 dB
        /** Held statically so the boost keeps working while the app process is alive. */
        private var boost: LoudnessEnhancer? = null
    }
}
