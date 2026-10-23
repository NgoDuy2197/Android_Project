package com.dsoft.proxyspy

import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * Handles one client connection. For CONNECT it terminates the client's TLS with a per-host cert
 * (so the request/response are readable), then forwards each decoded request upstream with OkHttp.
 * Plain HTTP is forwarded the same way. Both directions are recorded into [FlowStore].
 */
object ProxyEngine {
    private const val HARD_BODY_CAP = 40 * 1024 * 1024   // in-memory safety cap when forwarding

    private val HOP = setOf(
        "connection", "proxy-connection", "keep-alive", "transfer-encoding", "te",
        "trailer", "upgrade", "proxy-authenticate", "proxy-authorization",
    )

    private val client: OkHttpClient by lazy {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(c: Array<out java.security.cert.X509Certificate>?, a: String?) {}
            override fun checkServerTrusted(c: Array<out java.security.cert.X509Certificate>?, a: String?) {}
            override fun getAcceptedIssuers() = arrayOf<java.security.cert.X509Certificate>()
        }
        val sc = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustAll), null) }
        OkHttpClient.Builder()
            .proxy(java.net.Proxy.NO_PROXY)   // reach origins directly, never via the system proxy (avoid loops)
            .sslSocketFactory(sc.socketFactory, trustAll)
            .hostnameVerifier { _, _ -> true }
            .followRedirects(false).followSslRedirects(false)
            .retryOnConnectionFailure(true)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    fun handle(client: Socket, maxBody: Int) {
        try {
            client.soTimeout = 60_000
            val cin = BufferedInputStream(client.getInputStream())
            val cout = client.getOutputStream()
            val reqLine = readLine(cin) ?: return
            val parts = reqLine.split(' ')
            if (parts.size < 2) return
            val method = parts[0]
            val target = parts[1]

            if (method.equals("CONNECT", true)) {
                val hp = target.split(':')
                val host = hp[0]
                // consume the CONNECT headers
                readHeaders(cin)
                cout.write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray())
                cout.flush()
                // wrap as a server-side TLS socket presenting a cert for `host`
                val ssl = try {
                    (CertManager.serverContext(host).socketFactory
                        .createSocket(client, host, client.port, true) as SSLSocket).apply {
                        useClientMode = false
                        startHandshake()
                    }
                } catch (_: Throwable) { runCatching { client.close() }; return }
                serveLoop(BufferedInputStream(ssl.getInputStream()), ssl.getOutputStream(), "https", host, maxBody)
                runCatching { ssl.close() }
            } else {
                // plain HTTP, absolute-form target: http://host/path
                serveRequest(method, target, cin, cout, "http", null, maxBody)
                serveLoopPlain(cin, cout, maxBody)
            }
        } catch (_: Throwable) {
        } finally {
            runCatching { client.close() }
        }
    }

    /** Keep-alive loop for a decrypted HTTPS tunnel (host fixed from CONNECT). */
    private fun serveLoop(input: InputStream, out: OutputStream, scheme: String, host: String, maxBody: Int) {
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) continue
            val parts = line.split(' ')
            if (parts.size < 2) break
            val keepAlive = serveRequest(parts[0], parts[1], input, out, scheme, host, maxBody)
            if (!keepAlive) break
        }
    }

    /** Keep-alive loop for plain HTTP (each request carries its own absolute URL). */
    private fun serveLoopPlain(input: InputStream, out: OutputStream, maxBody: Int) {
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) continue
            val parts = line.split(' ')
            if (parts.size < 2) break
            val keepAlive = serveRequest(parts[0], parts[1], input, out, "http", null, maxBody)
            if (!keepAlive) break
        }
    }

    /** Parses one request from [input], forwards it, writes the response. Returns whether to keep the connection. */
    private fun serveRequest(
        method: String, target: String, input: InputStream, out: OutputStream,
        scheme: String, connectHost: String?, maxBody: Int,
    ): Boolean {
        val headers = readHeaders(input)
        val hmap = headers.associate { it.first.lowercase() to it.second }
        // Direct (non-proxied) hit on the proxy port: serve the CA so the client can fetch it easily.
        if (connectHost == null && target.startsWith("/")) {
            readBody(input, hmap)
            serveCaPage(target, out)
            return hmap["connection"]?.lowercase()?.contains("close") != true
        }
        val hostHeader = hmap["host"]
        val host: String
        val path: String
        if (connectHost != null) {                 // https origin-form: target = /path
            host = (hostHeader ?: connectHost).substringBefore(':')
            path = if (target.startsWith("/")) target else "/$target"
        } else {                                    // http absolute-form: target = http://host/path
            val noScheme = target.removePrefix("http://").removePrefix("https://")
            host = noScheme.substringBefore('/').substringBefore(':')
            path = "/" + noScheme.substringAfter('/', "")
        }
        val port = (connectHost ?: hostHeader)?.substringAfter(':', "")?.toIntOrNull()
        val authority = host + (if (port != null && port != (if (scheme == "https") 443 else 80)) ":$port" else "")
        val url = "$scheme://$authority$path"
        val reqBody = readBody(input, hmap)
        val connClose = hmap["connection"]?.lowercase()?.contains("close") == true

        // local CA-download host, so the other phone can install the cert through the proxy
        if (scheme == "http" && (host == "proxyspy.ca" || host == "ca.proxyspy")) {
            serveCaPage(path, out)
            return !connClose
        }

        val f = Flow(FlowStore.newId(), System.currentTimeMillis(), method, scheme, host, path, url)
        f.reqHeaders = headers
        f.reqBody = extractBody(reqBody, hmap["content-type"] ?: "", maxBody)
        FlowStore.add(f)

        val started = System.currentTimeMillis()
        try {
            val permits = !method.equals("GET", true) && !method.equals("HEAD", true)
            val mustBody = method.uppercase() in setOf("POST", "PUT", "PATCH", "DELETE")
            val mt = (hmap["content-type"] ?: "").let { if (it.isBlank()) null else it.toMediaTypeOrNull() }
            val rb = if (permits && (reqBody.isNotEmpty() || mustBody)) reqBody.toRequestBody(mt) else null
            val rb2 = Request.Builder().url(url)
            for ((k, v) in headers) {
                val lk = k.lowercase()
                // let OkHttp set Host and manage gzip itself so we get decoded bodies back
                if (lk in HOP || lk == "content-length" || lk == "host" || lk == "accept-encoding") continue
                rb2.addHeader(k, v)
            }
            rb2.method(method, rb)
            client.newCall(rb2.build()).execute().use { resp ->
                val bodyBytes = resp.body?.bytes() ?: ByteArray(0)
                f.status = resp.code
                f.reason = resp.message
                val rh = ArrayList<Pair<String, String>>()
                for (name in resp.headers.names()) {
                    if (name.lowercase() in HOP || name.equals("content-length", true) ||
                        name.equals("content-encoding", true)) continue
                    for (v in resp.headers.values(name)) rh.add(name to v)
                }
                f.resHeaders = rh
                f.resBody = extractBody(bodyBytes, resp.header("content-type") ?: "", maxBody)
                f.durationMs = System.currentTimeMillis() - started
                FlowStore.touched()
                writeResponse(out, resp.code, resp.message, rh, bodyBytes, connClose)
            }
        } catch (e: Throwable) {
            f.error = e.message ?: e.javaClass.simpleName
            f.durationMs = System.currentTimeMillis() - started
            FlowStore.touched()
            runCatching {
                val msg = "ProxySpy lỗi: ${f.error}".toByteArray()
                writeResponse(out, 502, "Bad Gateway",
                    listOf("Content-Type" to "text/plain; charset=utf-8"), msg, true)
            }
            return false
        }
        return !connClose
    }

    private fun serveCaPage(path: String, out: OutputStream) {
        if (path.startsWith("/ca") && (path.endsWith(".crt") || path.endsWith(".cer") || path.endsWith(".pem"))) {
            writeResponse(out, 200, "OK",
                listOf("Content-Type" to "application/x-x509-ca-cert",
                    "Content-Disposition" to "attachment; filename=\"ProxySpy-CA.crt\""),
                CertManager.caDer(), true)
            return
        }
        val html = """
            <!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
            <title>ProxySpy CA</title>
            <body style="font-family:sans-serif;max-width:560px;margin:24px auto;padding:0 16px">
            <h2>Cài chứng chỉ ProxySpy</h2>
            <p>Bấm để tải CA, rồi cài vào <b>Cài đặt → Bảo mật → Cài chứng chỉ → Chứng chỉ CA</b>.</p>
            <p><a href="/ca.crt" style="display:inline-block;background:#007aff;color:#fff;padding:12px 18px;border-radius:10px;text-decoration:none">Tải chứng chỉ CA (.crt)</a></p>
            <p style="color:#888">Chỉ dùng để gỡ lỗi thiết bị của bạn.</p>
            </body>
        """.trimIndent().toByteArray()
        writeResponse(out, 200, "OK", listOf("Content-Type" to "text/html; charset=utf-8"), html, true)
    }

    // ---- HTTP wire helpers ----

    private fun writeResponse(
        out: OutputStream, code: Int, reason: String,
        headers: List<Pair<String, String>>, body: ByteArray, close: Boolean,
    ) {
        val sb = StringBuilder("HTTP/1.1 $code $reason\r\n")
        for ((k, v) in headers) sb.append(k).append(": ").append(v).append("\r\n")
        sb.append("Content-Length: ").append(body.size).append("\r\n")
        sb.append("Connection: ").append(if (close) "close" else "keep-alive").append("\r\n\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        if (body.isNotEmpty()) out.write(body)
        out.flush()
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
        }
    }

    private fun readHeaders(input: InputStream): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val i = line.indexOf(':')
            if (i > 0) out.add(line.substring(0, i).trim() to line.substring(i + 1).trim())
        }
        return out
    }

    private fun readBody(input: InputStream, h: Map<String, String>): ByteArray {
        val te = h["transfer-encoding"]?.lowercase()
        if (te != null && te.contains("chunked")) return readChunked(input)
        val len = h["content-length"]?.toIntOrNull() ?: return ByteArray(0)
        if (len <= 0) return ByteArray(0)
        val cap = minOf(len, HARD_BODY_CAP)
        val buf = ByteArray(cap)
        var read = 0
        while (read < cap) {
            val n = input.read(buf, read, cap - read)
            if (n < 0) break
            read += n
        }
        // if larger than cap, drain the rest so the stream stays aligned
        var remaining = len - read
        val skip = ByteArray(8192)
        while (remaining > 0) {
            val n = input.read(skip, 0, minOf(skip.size, remaining))
            if (n < 0) break
            remaining -= n
        }
        return if (read == cap) buf else buf.copyOf(read)
    }

    private fun readChunked(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val sizeLine = readLine(input) ?: break
            val size = sizeLine.substringBefore(';').trim().toIntOrNull(16) ?: break
            if (size == 0) { readLine(input); break }
            var left = size
            val buf = ByteArray(8192)
            while (left > 0) {
                val n = input.read(buf, 0, minOf(buf.size, left))
                if (n < 0) break
                if (out.size() < HARD_BODY_CAP) out.write(buf, 0, n)
                left -= n
            }
            readLine(input) // trailing CRLF
        }
        return out.toByteArray()
    }

    private val TEXT_HINTS = listOf("text/", "json", "xml", "javascript", "x-www-form-urlencoded",
        "html", "csv", "+json", "+xml", "graphql")

    private fun extractBody(content: ByteArray, contentType: String, cap: Int): Body? {
        if (content.isEmpty()) return null
        val ct = contentType.lowercase()
        val textual = TEXT_HINTS.any { ct.contains(it) } || (ct.isEmpty() && looksText(content))
        val truncated = content.size > cap
        val chunk = if (truncated) content.copyOf(cap) else content
        return if (textual) {
            Body("text", String(chunk, Charsets.UTF_8), null, content.size, truncated, contentType)
        } else {
            Body("binary", null, android.util.Base64.encodeToString(chunk, android.util.Base64.NO_WRAP),
                content.size, truncated, contentType)
        }
    }

    private fun looksText(b: ByteArray): Boolean {
        val n = minOf(b.size, 2048)
        var printable = 0
        for (i in 0 until n) {
            val c = b[i].toInt() and 0xFF
            if (c == 9 || c == 10 || c == 13 || c in 32..126 || c >= 128) printable++
        }
        return printable >= n * 0.9
    }
}
