package com.dsoft.screengrab.net

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
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
 * A lightweight MITM proxy that only LOOKS at what flows by. For each request it learns the URL and
 * response Content-Type; if it points at a video/audio stream (HLS/DASH playlist or a media file) it
 * records the URL + replay headers into [MediaCatalog]. The body is streamed straight through (never
 * fully buffered) so apps/browsers keep playing normally — the actual download happens later from the
 * learned URL via [MediaDownloader].
 */
object SniffProxy {
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
            .proxy(java.net.Proxy.NO_PROXY)
            .sslSocketFactory(sc.socketFactory, trustAll)
            .hostnameVerifier { _, _ -> true }
            .followRedirects(false).followSslRedirects(false)
            .retryOnConnectionFailure(true)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    fun handle(client: Socket) {
        try {
            client.soTimeout = 60_000
            val cin = BufferedInputStream(client.getInputStream())
            val cout = client.getOutputStream()
            val reqLine = readLine(cin) ?: return
            val parts = reqLine.split(' ')
            if (parts.size < 2) return
            val method = parts[0]; val target = parts[1]

            if (method.equals("CONNECT", true)) {
                val host = target.split(':')[0]
                readHeaders(cin)
                cout.write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray())
                cout.flush()
                val ssl = try {
                    (Mitm.serverContext(host).socketFactory
                        .createSocket(client, host, client.port, true) as SSLSocket).apply {
                        useClientMode = false
                        try {
                            val pr = sslParameters; pr.applicationProtocols = arrayOf("http/1.1"); sslParameters = pr
                        } catch (_: Throwable) {}
                        startHandshake()
                    }
                } catch (_: Throwable) {
                    runCatching { client.close() }; return
                }
                serveLoop(BufferedInputStream(ssl.getInputStream()), ssl.getOutputStream(), "https", host)
                runCatching { ssl.close() }
            } else {
                serveRequest(method, target, cin, cout, "http", null)
                serveLoopPlain(cin, cout)
            }
        } catch (_: Throwable) {
        } finally {
            runCatching { client.close() }
        }
    }

    private fun serveLoop(input: InputStream, out: OutputStream, scheme: String, host: String) {
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) continue
            val parts = line.split(' '); if (parts.size < 2) break
            if (!serveRequest(parts[0], parts[1], input, out, scheme, host)) break
        }
    }

    private fun serveLoopPlain(input: InputStream, out: OutputStream) {
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) continue
            val parts = line.split(' '); if (parts.size < 2) break
            if (!serveRequest(parts[0], parts[1], input, out, "http", null)) break
        }
    }

    /** Forwards one request, streams the response, and sniffs it. Returns whether to keep-alive. */
    private fun serveRequest(
        method: String, target: String, input: InputStream, out: OutputStream,
        scheme: String, connectHost: String?,
    ): Boolean {
        val headers = readHeaders(input)
        val hmap = headers.associate { it.first.lowercase() to it.second }
        val hostHeader = hmap["host"]
        val host: String; val path: String
        if (connectHost != null) {
            host = (hostHeader ?: connectHost).substringBefore(':')
            path = if (target.startsWith("/")) target else "/$target"
        } else {
            val noScheme = target.removePrefix("http://").removePrefix("https://")
            host = noScheme.substringBefore('/').substringBefore(':')
            path = "/" + noScheme.substringAfter('/', "")
        }
        val port = (connectHost ?: hostHeader)?.substringAfter(':', "")?.toIntOrNull()
        val authority = host + (if (port != null && port != (if (scheme == "https") 443 else 80)) ":$port" else "")
        val url = "$scheme://$authority$path"
        val reqBody = readBody(input, hmap)
        val connClose = hmap["connection"]?.lowercase()?.contains("close") == true

        try {
            val permits = !method.equals("GET", true) && !method.equals("HEAD", true)
            val mt = (hmap["content-type"] ?: "").let { if (it.isBlank()) null else it.toMediaTypeOrNull() }
            val rb = if (permits && reqBody.isNotEmpty()) reqBody.toRequestBody(mt) else null
            val b = Request.Builder().url(url)
            for ((k, v) in headers) {
                val lk = k.lowercase()
                if (lk in HOP || lk == "content-length" || lk == "host") continue
                b.addHeader(k, v)
            }
            b.method(method, rb)
            client.newCall(b.build()).execute().use { resp ->
                val ct = resp.header("content-type") ?: ""
                val clen = resp.header("content-length")?.toLongOrNull() ?: -1L
                // sniff BEFORE streaming (so even if the player aborts mid-body we captured it)
                MediaSniff.inspect(method, url, host, ct, clen, hmap)

                // write status + headers (strip hop-by-hop; we set our own length/encoding)
                val sb = StringBuilder("HTTP/1.1 ${resp.code} ${resp.message}\r\n")
                for (name in resp.headers.names()) {
                    val ln = name.lowercase()
                    if (ln in HOP || ln == "content-length") continue
                    for (v in resp.headers.values(name)) sb.append(name).append(": ").append(v).append("\r\n")
                }
                val body = resp.body
                val known = clen >= 0
                if (known) sb.append("Content-Length: ").append(clen).append("\r\n")
                else sb.append("Transfer-Encoding: chunked\r\n")
                sb.append("Connection: ").append(if (connClose) "close" else "keep-alive").append("\r\n\r\n")
                out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))

                if (body != null && !method.equals("HEAD", true)) {
                    val src = body.byteStream()
                    val buf = ByteArray(32 * 1024)
                    if (known) {
                        while (true) { val n = src.read(buf); if (n < 0) break; out.write(buf, 0, n) }
                    } else {
                        while (true) {
                            val n = src.read(buf); if (n < 0) break
                            out.write(Integer.toHexString(n).toByteArray(Charsets.ISO_8859_1))
                            out.write("\r\n".toByteArray()); out.write(buf, 0, n); out.write("\r\n".toByteArray())
                        }
                        out.write("0\r\n\r\n".toByteArray())
                    }
                }
                out.flush()
            }
        } catch (_: Throwable) {
            runCatching {
                out.write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                out.flush()
            }
            return false
        }
        return !connClose
    }

    // ---- wire helpers ----
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
        val cap = minOf(len, 16 * 1024 * 1024)
        val buf = ByteArray(cap); var read = 0
        while (read < cap) { val n = input.read(buf, read, cap - read); if (n < 0) break; read += n }
        var remaining = len - read; val skip = ByteArray(8192)
        while (remaining > 0) { val n = input.read(skip, 0, minOf(skip.size, remaining)); if (n < 0) break; remaining -= n }
        return if (read == cap) buf else buf.copyOf(read)
    }

    private fun readChunked(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val sizeLine = readLine(input) ?: break
            val size = sizeLine.substringBefore(';').trim().toIntOrNull(16) ?: break
            if (size == 0) { readLine(input); break }
            var left = size; val buf = ByteArray(8192)
            while (left > 0) { val n = input.read(buf, 0, minOf(buf.size, left)); if (n < 0) break; out.write(buf, 0, n); left -= n }
            readLine(input)
        }
        return out.toByteArray()
    }
}
