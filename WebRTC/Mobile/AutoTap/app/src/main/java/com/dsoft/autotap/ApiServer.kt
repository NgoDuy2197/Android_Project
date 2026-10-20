package com.dsoft.autotap

import android.content.Context
import android.util.Base64
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PushbackInputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import kotlin.concurrent.thread

/**
 * A tiny plain-socket HTTP server to trigger scenarios remotely. Request parsing
 * follows Toolbox's ShareServer pattern (request line + headers, hand-written
 * responses). Optional HTTP Basic auth.
 */
class ApiServer(
    private val appCtx: Context,
    private val port: Int,
    private val user: String,
    private val pass: String,
    private val onEvent: (String) -> Unit,
) {
    @Volatile private var server: ServerSocket? = null
    val isRunning: Boolean get() = server != null
    private val store = Store(appCtx)

    fun start() {
        val s = ServerSocket()
        s.reuseAddress = true
        s.bind(java.net.InetSocketAddress(port))
        server = s
        thread(name = "autotap-api-accept", isDaemon = true) {
            while (!s.isClosed) {
                val c = try { s.accept() } catch (_: IOException) { break }
                thread(isDaemon = true) { runCatching { handle(c) }; runCatching { c.close() } }
            }
        }
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
    }

    private fun handle(sock: Socket) {
        sock.soTimeout = 30_000
        val input = PushbackInputStream(sock.getInputStream(), 8192)
        val out = BufferedOutputStream(sock.getOutputStream())
        val reqLine = readLine(input) ?: return
        val parts = reqLine.split(' ')
        if (parts.size < 2) return
        val method = parts[0]
        val rawPath = parts[1]
        val headers = HashMap<String, String>()
        while (true) {
            val h = readLine(input) ?: break
            if (h.isEmpty()) break
            val i = h.indexOf(':')
            if (i > 0) headers[h.substring(0, i).trim().lowercase()] = h.substring(i + 1).trim()
        }
        val path = rawPath.substringBefore('?')
        val query = rawPath.substringAfter('?', "")
        val len = headers["content-length"]?.toLongOrNull() ?: 0L
        val bodyBytes = readBody(input, len)
        val bodyStr = String(bodyBytes, Charsets.UTF_8)

        try {
            if (!authOk(headers["authorization"])) {
                sendUnauthorized(out)
                return
            }
            when {
                path == "/" || path == "/index.html" -> sendHtml(out, statusPage())
                path == "/scenarios" -> sendJson(out, 200, scenariosJson())
                path == "/run" -> run(parseParams(query) + parseParams(bodyStr), out)
                path.startsWith("/run/") -> {
                    val name = URLDecoder.decode(path.removePrefix("/run/"), "UTF-8")
                    run(parseParams(query) + parseParams(bodyStr) + mapOf("__name" to name), out, name)
                }
                else -> sendJson(out, 404, "{\"ok\":false,\"message\":\"Không tìm thấy đường dẫn\"}")
            }
        } catch (_: IOException) {
        }
    }

    private fun run(params: Map<String, String>, out: OutputStream, explicitName: String? = null) {
        val name = explicitName ?: params["name"] ?: params["id"]
        if (name.isNullOrBlank()) {
            sendJson(out, 400, "{\"ok\":false,\"message\":\"Thiếu tham số name\"}")
            return
        }
        val scenario = store.find(name)
        if (scenario == null) {
            sendJson(out, 404, "{\"ok\":false,\"message\":${jstr("Không có kịch bản: $name")}}")
            return
        }
        if (!AutoClickService.isEnabled()) {
            sendJson(out, 409, "{\"ok\":false,\"message\":${jstr("Chưa bật dịch vụ Trợ năng AutoTap trên điện thoại")}}")
            return
        }
        val stepParams = params.filterKeys { it != "name" && it != "id" && it != "__name" }
        val ok = AutoClickService.run(scenario, stepParams)
        onEvent("Chạy qua API: ${scenario.name}")
        sendJson(out, 200, "{\"ok\":$ok,\"message\":${jstr(if (ok) "Đang chạy ${scenario.name}" else "Không chạy được")}}")
    }

    private fun authOk(header: String?): Boolean {
        if (user.isEmpty() && pass.isEmpty()) return true
        if (header == null || !header.startsWith("Basic ", ignoreCase = true)) return false
        val decoded = try {
            String(Base64.decode(header.substring(6).trim(), Base64.DEFAULT), Charsets.UTF_8)
        } catch (_: Exception) {
            return false
        }
        val u = decoded.substringBefore(':')
        val p = decoded.substringAfter(':', "")
        return u == user && p == pass
    }

    private fun scenariosJson(): String {
        val sb = StringBuilder("{\"scenarios\":[")
        store.load().forEachIndexed { i, s ->
            if (i > 0) sb.append(',')
            sb.append("{\"id\":").append(jstr(s.id))
                .append(",\"name\":").append(jstr(s.name))
                .append(",\"steps\":").append(s.steps.size).append('}')
        }
        return sb.append("]}").toString()
    }

    private fun statusPage(): String {
        val sb = StringBuilder()
        sb.append("<!doctype html><html lang=\"vi\"><head><meta charset=\"utf-8\">")
        sb.append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
        sb.append("<title>AutoTap API</title><style>")
        sb.append("body{font-family:-apple-system,system-ui,Roboto,sans-serif;margin:0;background:#f2f2f7;color:#111}")
        sb.append(".w{max-width:680px;margin:0 auto;padding:20px}")
        sb.append("h1{font-size:24px}code{background:#e5e5ea;padding:2px 6px;border-radius:6px}")
        sb.append(".card{background:#fff;border-radius:14px;padding:16px;margin:14px 0}")
        sb.append("li{margin:6px 0}@media(prefers-color-scheme:dark){body{background:#000;color:#fff}.card{background:#1c1c1e}code{background:#2c2c2e}}")
        sb.append("</style></head><body><div class=\"w\"><h1>AutoTap API</h1>")
        sb.append("<div class=\"card\"><b>Kịch bản</b><ul>")
        val list = store.load()
        if (list.isEmpty()) sb.append("<li>(chưa có kịch bản nào)</li>")
        for (s in list) {
            val enc = java.net.URLEncoder.encode(s.name, "UTF-8")
            sb.append("<li><a href=\"/run?name=").append(enc).append("\">")
                .append(escapeHtml(s.name)).append("</a> — ").append(s.steps.size).append(" bước</li>")
        }
        sb.append("</ul></div>")
        sb.append("<div class=\"card\"><b>Ví dụ</b><p>Chạy một kịch bản:</p>")
        sb.append("<p><code>curl \"http://IP:$port/run?name=TenKichBan&amp;user=An\"</code></p>")
        sb.append("<p>Hoặc: <code>GET /run/TenKichBan</code>, <code>GET /scenarios</code></p>")
        sb.append("<p>Tham số (ngoài <code>name</code>/<code>id</code>) được thay vào các chỗ <code>{{ten}}</code> trong kịch bản.</p>")
        sb.append("</div></div></body></html>")
        return sb.toString()
    }

    // ---- helpers ----
    private fun parseParams(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        val m = LinkedHashMap<String, String>()
        for (pair in query.split('&')) {
            if (pair.isEmpty()) continue
            val k = pair.substringBefore('=')
            val v = pair.substringAfter('=', "")
            if (k.isEmpty()) continue
            m[dec(k)] = dec(v)
        }
        return m
    }

    private fun dec(s: String): String = try { URLDecoder.decode(s, "UTF-8") } catch (_: Exception) { s }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
        }
    }

    private fun readBody(input: InputStream, len: Long): ByteArray {
        if (len <= 0) return ByteArray(0)
        val buf = ByteArray(len.toInt().coerceAtMost(1 shl 20))
        var read = 0
        while (read < buf.size) {
            val n = input.read(buf, read, buf.size - read)
            if (n < 0) break
            read += n
        }
        return if (read == buf.size) buf else buf.copyOf(read)
    }

    private fun jstr(s: String): String {
        val b = StringBuilder("\"")
        for (c in s) when (c) {
            '"' -> b.append("\\\""); '\\' -> b.append("\\\\"); '\n' -> b.append("\\n"); '\r' -> b.append("\\r"); '\t' -> b.append("\\t")
            else -> if (c < ' ') b.append("\\u%04x".format(c.code)) else b.append(c)
        }
        return b.append('"').toString()
    }

    private fun escapeHtml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun sendJson(out: OutputStream, code: Int, json: String) {
        val b = json.toByteArray(Charsets.UTF_8)
        out.write(("HTTP/1.1 $code OK\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: ${b.size}\r\nConnection: close\r\n\r\n").toByteArray())
        out.write(b); out.flush()
    }

    private fun sendHtml(out: OutputStream, html: String) {
        val b = html.toByteArray(Charsets.UTF_8)
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${b.size}\r\nConnection: close\r\n\r\n").toByteArray())
        out.write(b); out.flush()
    }

    private fun sendUnauthorized(out: OutputStream) {
        val b = "{\"ok\":false,\"message\":\"Sai tài khoản hoặc mật khẩu\"}".toByteArray(Charsets.UTF_8)
        out.write(("HTTP/1.1 401 Unauthorized\r\nWWW-Authenticate: Basic realm=\"AutoTap\"\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: ${b.size}\r\nConnection: close\r\n\r\n").toByteArray())
        out.write(b); out.flush()
    }
}
