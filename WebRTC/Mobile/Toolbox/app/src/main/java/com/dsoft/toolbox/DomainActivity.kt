package com.dsoft.toolbox

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

/**
 * Domain inspector: resolve a host through the device's DNS and (independently) through public
 * DNS-over-HTTPS, flag each address private vs public, do a reverse lookup, and probe common ports.
 */
class DomainActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var p: Palette
    private lateinit var input: EditText
    private lateinit var results: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        p = palette(); setupSystemBars(p)
        val col = page(p, "Tra cứu domain")

        input = EditText(this).apply {
            hint = "vd: google.com hoặc nas.local"
            setText("")
            setTextColor(p.text); setHintTextColor(p.secondary)
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
        }
        col.addView(card(p).apply { setPadding(dp(14), dp(6), dp(14), dp(6)); addView(input) }, vlp())
        col.addView(bigButton("Tra cứu", p.teal, p.teal) { lookup() },
            vlp(-1, -2).apply { topMargin = dp(10); marginStart = dp(4); marginEnd = dp(4) })

        results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(results, vlp())
        col.addView(footnote(p,
            "• DNS máy: phân giải bằng DNS mạng đang dùng (tên nội bộ → IP riêng tư trong LAN).\n" +
            "• DNS công cộng: hỏi dns.google qua Internet (IP công cộng thật của domain).\n" +
            "Hai kết quả khác nhau = mạng đang chặn/đổi hướng domain đó."))
    }

    private fun lookup() {
        val host = cleanHost(input.text.toString())
        if (host.isEmpty()) { toast("Nhập domain đã"); return }
        results.removeAllViews()
        results.addView(sectionHeader(p, host))
        val loading = text("đang tra cứu…", 15f, p.secondary)
        results.addView(loading)
        Thread {
            val local = resolveLocal(host)
            val doh = resolveDoh(host)
            val first = local.firstOrNull() ?: doh.firstOrNull()
            val ptr = first?.let { reverse(it) }
            val ports = first?.let { probePorts(it) } ?: emptyList()
            main.post {
                results.removeView(loading)
                if (first == null) { results.addView(text("Không phân giải được domain này.", 15f, p.red)); return@post }

                results.addView(sectionHeader(p, "IP đầu tiên"))
                results.addView(group(p, ipRow("$first", classify(first))))

                if (local.isNotEmpty()) {
                    results.addView(sectionHeader(p, "Qua DNS máy (${local.size})"))
                    results.addView(group(p, *local.map { ipRow(it, classify(it)) }.toTypedArray()))
                }
                if (doh.isNotEmpty()) {
                    results.addView(sectionHeader(p, "Qua DNS công cộng (dns.google)"))
                    results.addView(group(p, *doh.map { ipRow(it, classify(it)) }.toTypedArray()))
                }
                val extra = ArrayList<LinearLayout>()
                if (ptr != null) extra.add(row(p, "Tên ngược (PTR)", text(ptr, 14f, p.secondary)))
                if (ports.isNotEmpty()) extra.add(row(p, "Cổng mở", text(ports.joinToString(", "), 15f, p.green)))
                if (extra.isNotEmpty()) {
                    results.addView(sectionHeader(p, "Khác"))
                    results.addView(group(p, *extra.toTypedArray()))
                }
            }
        }.start()
    }

    private fun ipRow(ip: String, kind: String): LinearLayout {
        val color = if (kind == "riêng tư") p.orange else if (kind == "công cộng") p.green else p.secondary
        return row(p, ip, text(kind, 13f, color)) { copyText(ip); toast("Đã copy $ip") }
            .also { (it.getChildAt(0) as? TextView)?.typeface = android.graphics.Typeface.MONOSPACE }
    }

    /** Strips scheme / path / port so "https://x.com/a" and "x.com:443" both become "x.com". */
    private fun cleanHost(raw: String): String {
        var s = raw.trim()
        if (s.isEmpty()) return s
        s = s.substringAfter("://")
        s = s.substringBefore('/')
        s = s.substringBefore('?')
        // keep IPv6 literals intact; otherwise drop :port
        if (!s.startsWith("[") && s.count { it == ':' } == 1) s = s.substringBefore(':')
        return s.trim().trim('.', '[', ']')
    }

    private fun resolveLocal(host: String): List<String> = try {
        InetAddress.getAllByName(host).map { it.hostAddress ?: "" }.filter { it.isNotEmpty() }.distinct()
    } catch (_: Exception) { emptyList() }

    /** Public resolution via DNS-over-HTTPS (A + AAAA), independent of the device resolver. */
    private fun resolveDoh(host: String): List<String> {
        val out = LinkedHashSet<String>()
        for (type in listOf("A", "AAAA")) {
            try {
                val body = httpText("https://dns.google/resolve?name=$host&type=$type") ?: continue
                val ans = JSONObject(body).optJSONArray("Answer") ?: continue
                for (i in 0 until ans.length()) {
                    val o = ans.getJSONObject(i)
                    // type 1 = A, 28 = AAAA; skip CNAME (5) rows
                    if (o.optInt("type") == 1 || o.optInt("type") == 28) o.optString("data").takeIf { it.isNotBlank() }?.let { out.add(it) }
                }
            } catch (_: Exception) {}
        }
        return out.toList()
    }

    private fun classify(ip: String): String = try {
        val a = InetAddress.getByName(ip)
        when {
            a.isSiteLocalAddress || a.isLinkLocalAddress || a.isLoopbackAddress || isUniqueLocalV6(a) -> "riêng tư"
            else -> "công cộng"
        }
    } catch (_: Exception) { "" }

    private fun isUniqueLocalV6(a: InetAddress): Boolean =
        a is Inet6Address && (a.address.getOrNull(0)?.toInt()?.and(0xFE) == 0xFC) // fc00::/7

    private fun reverse(ip: String): String? = try {
        val name = InetAddress.getByName(ip).canonicalHostName
        if (name.isNotEmpty() && name != ip) name else null
    } catch (_: Exception) { null }

    private fun probePorts(ip: String): List<String> {
        val names = mapOf(80 to "80 (http)", 443 to "443 (https)", 22 to "22 (ssh)", 21 to "21 (ftp)")
        val open = ArrayList<String>()
        for ((port, label) in names) {
            try {
                Socket().use { it.connect(InetSocketAddress(ip, port), 1200); open.add(label) }
            } catch (_: Exception) {}
        }
        return open
    }

    private fun httpText(url: String): String? = try {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 6000; readTimeout = 6000
            setRequestProperty("Accept", "application/dns-json")
        }
        c.inputStream.bufferedReader().use { it.readText().trim() }.also { c.disconnect() }
    } catch (_: Exception) { null }
}
