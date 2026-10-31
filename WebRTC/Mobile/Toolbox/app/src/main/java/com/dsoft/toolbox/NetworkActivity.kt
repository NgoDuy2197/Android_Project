package com.dsoft.toolbox

import android.app.Activity
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.LinearLayout
import android.widget.TextView
import java.net.HttpURLConnection
import java.net.URL

/** Network info: local IPs, Wi-Fi details, public IP, and a quick download-speed test. */
class NetworkActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var p: Palette
    private lateinit var publicIp: TextView
    private lateinit var speedValue: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        p = palette(); setupSystemBars(p)
        val col = page(p, "Mạng")

        // local IPs
        col.addView(sectionHeader(p, "Địa chỉ nội bộ (LAN / hotspot)"))
        val addrs = Net.addresses()
        col.addView(group(p, *(if (addrs.isEmpty()) listOf(row(p, "Không có kết nối", null))
        else addrs.map { (iface, ip) -> row(p, iface, text(ip, 15f, p.secondary)) { copyText(ip); toast("Đã copy") } }).toTypedArray()))

        // wifi
        wifiRows()?.let {
            col.addView(sectionHeader(p, "Wi-Fi"))
            col.addView(group(p, *it.toTypedArray()))
        }

        // public IP + speed
        col.addView(sectionHeader(p, "Internet"))
        publicIp = text("đang lấy…", 15f, p.secondary)
        speedValue = text("chưa test", 15f, p.secondary)
        col.addView(group(p,
            row(p, "IP công cộng", LinearLayout(this).apply { addView(publicIp); addView(chevron(p)) }) {
                val ip = publicIp.text.toString(); if (ip.isNotBlank() && ip[0].isDigit()) { copyText(ip); toast("Đã copy") }
            },
            row(p, "Tốc độ tải", speedValue),
        ))
        col.addView(bigButton("Test tốc độ tải", p.blue, p.blue) { runSpeedTest() },
            vlp(-1, -2).apply { topMargin = dp(12); marginStart = dp(4); marginEnd = dp(4) })
        col.addView(footnote(p, "Test tải một tệp từ Cloudflare để ước lượng tốc độ. SSID có thể hiện trống nếu chưa cấp quyền vị trí."))

        fetchPublicIp()
    }

    private fun wifiRows(): List<LinearLayout>? {
        return try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            if (!wm.isWifiEnabled) return null
            @Suppress("DEPRECATION") val info = wm.connectionInfo ?: return null
            val rows = ArrayList<LinearLayout>()
            val ssid = info.ssid?.trim('"').orEmpty()
            if (ssid.isNotEmpty() && ssid != "<unknown ssid>") rows.add(row(p, "SSID", text(ssid, 15f, p.secondary)))
            if (info.linkSpeed > 0) rows.add(row(p, "Tốc độ liên kết", text("${info.linkSpeed} Mbps", 15f, p.secondary)))
            @Suppress("DEPRECATION") val freq = info.frequency
            if (freq > 0) rows.add(row(p, "Băng tần", text("${if (freq > 4000) "5 GHz" else "2.4 GHz"} ($freq MHz)", 15f, p.secondary)))
            rows.add(row(p, "Tín hiệu", text("${info.rssi} dBm", 15f, p.secondary)))
            if (rows.isEmpty()) null else rows
        } catch (_: Exception) { null }
    }

    private fun fetchPublicIp() {
        Thread {
            val ip = httpText("https://api.ipify.org") ?: "không lấy được"
            main.post { publicIp.text = ip }
        }.start()
    }

    private fun runSpeedTest() {
        speedValue.text = "đang test…"
        Thread {
            val bytes = 10_000_000L
            try {
                val c = (URL("https://speed.cloudflare.com/__down?bytes=$bytes").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000; readTimeout = 20000
                }
                val t0 = SystemClock.elapsedRealtime()
                var total = 0L
                c.inputStream.use { ins ->
                    val buf = ByteArray(64 * 1024)
                    while (true) { val n = ins.read(buf); if (n < 0) break; total += n }
                }
                val secs = (SystemClock.elapsedRealtime() - t0) / 1000.0
                c.disconnect()
                val mbps = if (secs > 0) (total * 8 / 1_000_000.0) / secs else 0.0
                val mBps = if (secs > 0) (total / 1_000_000.0) / secs else 0.0
                main.post { speedValue.text = String.format("%.1f MB/s · %.1f Mbps (%.1fs)", mBps, mbps, secs) }
            } catch (e: Exception) {
                main.post { speedValue.text = "lỗi: ${e.message?.take(40)}" }
            }
        }.start()
    }

    private fun httpText(url: String): String? = try {
        val c = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 6000; readTimeout = 6000 }
        c.inputStream.bufferedReader().use { it.readText().trim() }.also { c.disconnect() }
    } catch (_: Exception) { null }
}
