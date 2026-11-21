package com.dsoft.proxyspy.hook

import com.dsoft.proxyspy.Wire
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.ArrayDeque
import java.util.Collections
import java.util.WeakHashMap

/**
 * Capture-only fallback for apps that do not use OkHttp: the plaintext either side of a TLS socket
 * is tee'd off Conscrypt's socket streams and parsed as HTTP/1.1 (plus WebSocket frames once a
 * connection has been upgraded).
 *
 * Deliberately limited, and off by default:
 *  - read-only — bytes are observed, never rewritten (use the OkHttp layer to change traffic);
 *  - HTTP/2 is detected and reported, not decoded (no HPACK here);
 *  - traffic whose TLS runs in native code (Cronet, Flutter's own stack, NDK sockets) never
 *    reaches these Java streams and so stays invisible.
 */
object RawHook {

    private val SOCKET_CLASSES = listOf(
        "com.android.org.conscrypt.ConscryptFileDescriptorSocket",
        "com.android.org.conscrypt.ConscryptEngineSocket",
        "com.android.org.conscrypt.OpenSSLSocketImpl",
        "com.google.android.gms.org.conscrypt.ConscryptFileDescriptorSocket",
        "com.google.android.gms.org.conscrypt.ConscryptEngineSocket",
    )

    private val conns = Collections.synchronizedMap(WeakHashMap<Any, RawConn>())

    fun install(cl: ClassLoader): Boolean {
        var hooked = false
        for (name in SOCKET_CLASSES) {
            val c = XposedHelpers.findClassIfExists(name, cl) ?: continue
            hooked = true
            XposedBridge.hookAllMethods(c, "getOutputStream", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!Bridge.cfg.captureRaw) return
                    val s = param.result as? OutputStream ?: return
                    if (s is Tee) return
                    param.result = Tee(s, connOf(param.thisObject))
                }
            })
            XposedBridge.hookAllMethods(c, "getInputStream", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!Bridge.cfg.captureRaw) return
                    val s = param.result as? InputStream ?: return
                    if (s is TeeIn) return
                    param.result = TeeIn(s, connOf(param.thisObject))
                }
            })
        }
        return hooked
    }

    private fun connOf(socket: Any): RawConn =
        conns.getOrPut(socket) { RawConn() }

    // ------------------------------------------------------------------ stream wrappers

    private class Tee(val inner: OutputStream, val conn: RawConn) : OutputStream() {
        override fun write(b: Int) {
            inner.write(b)
            feed(byteArrayOf(b.toByte()), 0, 1)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            inner.write(b, off, len)
            feed(b, off, len)
        }

        private fun feed(b: ByteArray, off: Int, len: Int) {
            if (Bridge.active && Bridge.cfg.captureRaw) runCatching { conn.up.feed(b, off, len) }
        }

        override fun flush() = inner.flush()
        override fun close() = inner.close()
    }

    private class TeeIn(val inner: InputStream, val conn: RawConn) : InputStream() {
        override fun read(): Int {
            val v = inner.read()
            if (v >= 0) feed(byteArrayOf(v.toByte()), 0, 1)
            return v
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = inner.read(b, off, len)
            if (n > 0) feed(b, off, n)
            return n
        }

        private fun feed(b: ByteArray, off: Int, len: Int) {
            if (Bridge.active && Bridge.cfg.captureRaw) runCatching { conn.down.feed(b, off, len) }
        }

        override fun available() = inner.available()
        override fun close() = inner.close()
    }

    // ------------------------------------------------------------------ per-connection state

    private class RawConn {
        val pending = ArrayDeque<Pair<Long, String>>() // flow id + url, oldest first
        @Volatile var upgraded = false
        @Volatile var host = ""
        val up = Parser(this, true)
        val down = Parser(this, false)
    }

    /**
     * HTTP/1.1 (and, after an upgrade, WebSocket) reader for one direction of one socket.
     * Unparseable or binary protocols stop the parser instead of guessing.
     */
    private class Parser(val conn: RawConn, val outgoing: Boolean) {
        private val buf = ByteArrayOutputStream(8 * 1024)
        private var dead = false

        fun feed(b: ByteArray, off: Int, len: Int) {
            if (dead) return
            if (buf.size() > 8 * 1024 * 1024) { dead = true; return }
            buf.write(b, off, len)
            while (step()) { /* keep consuming complete messages */ }
        }

        private fun replace(rest: ByteArray) {
            buf.reset()
            buf.write(rest, 0, rest.size)
        }

        /** Returns true when a message was consumed and another attempt is worthwhile. */
        private fun step(): Boolean {
            val data = buf.toByteArray()
            if (data.isEmpty()) return false
            if (conn.upgraded) return wsStep(data)

            if (outgoing && data.size >= 3 && data[0] == 'P'.code.toByte() &&
                data[1] == 'R'.code.toByte() && data[2] == 'I'.code.toByte()
            ) {
                emitNote("HTTP/2 — ProxySpy không giải mã lớp thô (dùng lớp OkHttp để xem)")
                dead = true
                return false
            }

            val headEnd = indexOfCrLfCrLf(data)
            if (headEnd < 0) return false
            val head = String(data, 0, headEnd, Charsets.ISO_8859_1)
            val lines = head.split("\r\n")
            val start = lines.firstOrNull() ?: return false
            if (!looksHttp(start)) { dead = true; return false }
            val headers = lines.drop(1).mapNotNull { line ->
                val i = line.indexOf(':')
                if (i <= 0) null else line.substring(0, i).trim() to line.substring(i + 1).trim()
            }
            val bodyStart = headEnd + 4
            val lenHeader = headers.firstOrNull { it.first.equals("Content-Length", true) }?.second?.trim()
            val chunked = headers.any {
                it.first.equals("Transfer-Encoding", true) && it.second.contains("chunked", true)
            }

            var body: ByteArray?
            var consumed: Int
            when {
                chunked -> {
                    val r = readChunked(data, bodyStart) ?: return false
                    body = r.first; consumed = r.second
                }
                lenHeader != null -> {
                    val n = lenHeader.toIntOrNull() ?: 0
                    if (data.size - bodyStart < n) return false
                    body = data.copyOfRange(bodyStart, bodyStart + n)
                    consumed = bodyStart + n
                }
                else -> {
                    body = null
                    consumed = bodyStart
                }
            }

            if (outgoing) emitRequest(start, headers, body) else emitResponse(start, headers, body)

            if (!outgoing && start.contains(" 101 ")) conn.upgraded = true
            replace(data.copyOfRange(consumed, data.size))
            return true
        }

        private fun looksHttp(start: String): Boolean =
            start.startsWith("HTTP/") || Regex("^[A-Z]{3,10} \\S+ HTTP/1\\.[01]$").matches(start.trim())

        private fun readChunked(data: ByteArray, from: Int): Pair<ByteArray, Int>? {
            var i = from
            val out = ByteArrayOutputStream()
            while (true) {
                val nl = indexOfCrLf(data, i)
                if (nl < 0) return null
                val sizeLine = String(data, i, nl - i, Charsets.ISO_8859_1).substringBefore(';').trim()
                val n = sizeLine.toIntOrNull(16) ?: return null
                i = nl + 2
                if (n == 0) {
                    val end = indexOfCrLf(data, i)
                    return out.toByteArray() to (if (end < 0) data.size else end + 2)
                }
                if (data.size - i < n + 2) return null
                out.write(data, i, n)
                i += n + 2
            }
        }

        private fun emitRequest(start: String, headers: List<Pair<String, String>>, body: ByteArray?) {
            val parts = start.trim().split(" ")
            val method = parts.getOrElse(0) { "GET" }
            val path = parts.getOrElse(1) { "/" }
            val host = headers.firstOrNull { it.first.equals("Host", true) }?.second ?: conn.host
            if (host.isNotEmpty()) conn.host = host
            val url = if (path.startsWith("http")) path else "https://$host$path"
            val id = Bridge.nextId()
            conn.pending.addLast(id to url)
            Bridge.send(
                JSONObject().put("t", Wire.T_FLOW).put("phase", "open").put("id", id)
                    .put("ts", System.currentTimeMillis()).put("engine", "raw")
                    .put("method", method).put("url", url)
                    .put("headers", Wire.headers(headers))
                    .put("body", Wire.body(body, Wire.contentTypeOf(headers), Bridge.cfg.maxBody))
            )
        }

        private fun emitResponse(start: String, headers: List<Pair<String, String>>, body: ByteArray?) {
            val parts = start.trim().split(" ", limit = 3)
            val code = parts.getOrNull(1)?.toIntOrNull() ?: -1
            val id = conn.pending.pollFirst()?.first ?: Bridge.nextId()
            val ct = Wire.contentTypeOf(headers)
            Bridge.send(
                JSONObject().put("t", Wire.T_FLOW).put("phase", "done").put("id", id)
                    .put("status", code).put("message", parts.getOrElse(2) { "" }).put("ct", ct)
                    .put("headers", Wire.headers(headers))
                    .put("body", Wire.body(body, ct, Bridge.cfg.maxBody))
                    .put("ms", -1).put("edited", false)
            )
        }

        private fun emitNote(note: String) = Bridge.send(
            JSONObject().put("t", Wire.T_LOG).put("ts", System.currentTimeMillis()).put("msg", note)
        )

        // -------------------------------------------------------------- websocket frames

        private fun wsStep(data: ByteArray): Boolean {
            if (data.size < 2) return false
            val b0 = data[0].toInt() and 0xFF
            val b1 = data[1].toInt() and 0xFF
            val opcode = b0 and 0x0F
            val masked = (b1 and 0x80) != 0
            var len = (b1 and 0x7F).toLong()
            var i = 2
            when (len) {
                126L -> {
                    if (data.size < 4) return false
                    len = ((data[2].toInt() and 0xFF).toLong() shl 8) or (data[3].toInt() and 0xFF).toLong()
                    i = 4
                }
                127L -> {
                    if (data.size < 10) return false
                    len = 0
                    for (k in 2..9) len = (len shl 8) or (data[k].toLong() and 0xFF)
                    i = 10
                }
            }
            if (len > 16L * 1024 * 1024) { dead = true; return false }
            val mask = if (masked) {
                if (data.size < i + 4) return false
                val m = data.copyOfRange(i, i + 4); i += 4; m
            } else null
            if (data.size - i < len) return false
            val payload = data.copyOfRange(i, i + len.toInt())
            if (mask != null) for (k in payload.indices) payload[k] = (payload[k].toInt() xor mask[k % 4].toInt()).toByte()
            i += len.toInt()

            val op = when (opcode) {
                0x1 -> "text"; 0x2 -> "binary"; 0x8 -> "close"; 0x9 -> "ping"; 0xA -> "pong"; else -> "cont"
            }
            if (op != "ping" && op != "pong") {
                Bridge.send(
                    JSONObject().put("t", Wire.T_WS).put("ts", System.currentTimeMillis())
                        .put("url", conn.pending.peekFirst()?.second ?: ("wss://" + conn.host))
                        .put("dir", if (outgoing) "out" else "in").put("op", op)
                        .put("body", Wire.body(payload, if (op == "text") "text/plain" else "application/octet-stream", Bridge.cfg.maxBody))
                        .put("note", "raw")
                )
            }
            replace(data.copyOfRange(i, data.size))
            return true
        }

        private fun indexOfCrLf(d: ByteArray, from: Int): Int {
            var i = from
            while (i + 1 < d.size) {
                if (d[i] == 13.toByte() && d[i + 1] == 10.toByte()) return i
                i++
            }
            return -1
        }

        private fun indexOfCrLfCrLf(d: ByteArray): Int {
            var i = 0
            while (i + 3 < d.size) {
                if (d[i] == 13.toByte() && d[i + 1] == 10.toByte() &&
                    d[i + 2] == 13.toByte() && d[i + 3] == 10.toByte()
                ) return i
                i++
            }
            return -1
        }
    }
}
