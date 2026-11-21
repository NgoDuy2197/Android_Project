package com.dsoft.proxyspy

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Inspector side of **self-watch mode**: a loopback-only TCP server that every hooked app dials
 * into. Each connection is one process of one app; the Hub answers its hello with a config built
 * for *that* package, then turns the captures into ordinary [Flow]s — the same list the MITM proxy
 * fills — and parks breakpoint requests in [pending] until the phone answers them.
 *
 * Bound to 127.0.0.1, so nothing outside this device can reach it.
 */
object Hub {

    class Conn(val id: Long, private val socket: Socket) {
        @Volatile var pkg: String = "?"
        @Volatile var proc: String = ""
        @Volatile var pid: Int = 0
        @Volatile var capturing: Boolean = false

        private val out: OutputStream = socket.getOutputStream()
        private val writeLock = Any()

        /** hook-local flow id -> flow, so a "done" frame finds the request it belongs to. */
        val flows = ConcurrentHashMap<Long, Flow>()

        fun send(o: JSONObject) {
            runCatching {
                val bytes = (o.toString() + "\n").toByteArray(Charsets.UTF_8)
                synchronized(writeLock) {
                    out.write(bytes)
                    out.flush()
                }
            }
        }

        fun close() = runCatching { socket.close() }

        val label: String get() = if (proc.isNotEmpty() && proc != pkg) "$pkg ($proc)" else pkg
    }

    /** A call frozen inside the watched app, waiting for the phone to say what to do with it. */
    class Pending(val askId: Long, val conn: Conn, val aid: Long, val frame: JSONObject) {
        val stage: String get() = frame.optString("stage")
        val url: String get() = frame.optString("url")
    }

    @Volatile var running = false
        private set
    @Volatile var port = -1
        private set
    @Volatile var lastError: String? = null
        private set

    private val conns = CopyOnWriteArrayList<Conn>()
    val pending = ConcurrentHashMap<Long, Pending>()

    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private var server: ServerSocket? = null
    private var ids = 0L

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }
    private fun notifyUi() = App.main.post { listeners.forEach { runCatching { it() } } }

    fun connections(): List<Conn> = conns.toList()

    // ------------------------------------------------------------------ lifecycle

    @Synchronized
    fun start(): Boolean {
        if (running) return true
        val local = InetAddress.getByName("127.0.0.1")
        for (p in Wire.PORT until Wire.PORT + Wire.PORT_SPAN) {
            try {
                val s = ServerSocket(p, 64, local)
                server = s
                port = p
                running = true
                lastError = null
                Thread({ accept(s) }, "proxyspy-hub").apply { isDaemon = true }.start()
                notifyUi()
                return true
            } catch (t: Throwable) {
                lastError = t.message
            }
        }
        running = false
        notifyUi()
        return false
    }

    @Synchronized
    fun stop() {
        running = false
        runCatching { server?.close() }
        server = null
        port = -1
        conns.forEach { it.close() }
        conns.clear()
        pending.clear()
        notifyUi()
    }

    private fun accept(s: ServerSocket) {
        while (running) {
            val sock = try { s.accept() } catch (_: Throwable) { break }
            val conn = Conn(++ids, sock)
            conns.add(conn)
            Thread({ serve(conn, sock) }, "proxyspy-hook-${conn.id}").apply { isDaemon = true }.start()
        }
        running = false
        notifyUi()
    }

    private fun serve(conn: Conn, sock: Socket) {
        try {
            sock.tcpNoDelay = true
            val r = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.UTF_8), 1 shl 16)
            while (true) {
                val line = r.readLine() ?: break
                if (line.isEmpty()) continue
                val o = runCatching { JSONObject(line) }.getOrNull() ?: continue
                handle(conn, o)
            }
        } catch (_: Throwable) {
            // connection dropped; fall through to cleanup
        } finally {
            conns.remove(conn)
            pending.entries.removeAll { it.value.conn === conn }
            conn.close()
            notifyUi()
        }
    }

    // ------------------------------------------------------------------ incoming frames

    private fun handle(conn: Conn, o: JSONObject) {
        when (o.optString("t")) {
            Wire.T_HELLO -> {
                conn.pkg = o.optString("pkg", "?")
                conn.proc = o.optString("proc", "")
                conn.pid = o.optInt("pid")
                sendConfig(conn)
                notifyUi()
            }

            Wire.T_FLOW -> {
                val hookId = o.optLong("id", -1)
                if (o.optString("phase") == "open") {
                    val url = o.optString("url")
                    val f = newFlow(o.optString("method", "GET"), url, o.optLong("ts", System.currentTimeMillis()))
                    f.pkg = conn.pkg
                    f.source = if (o.optString("engine") == "raw") Flow.SRC_RAW else Flow.SRC_HOOK
                    f.reqHeaders = Wire.headerList(o.optJSONArray("headers"))
                    f.reqBody = toBody(o.optJSONObject("body"))
                    conn.flows[hookId] = f
                    FlowStore.add(f)
                } else {
                    val f = conn.flows.remove(hookId) ?: return
                    f.status = o.optInt("status", -1)
                    f.reason = o.optString("message")
                    f.resHeaders = Wire.headerList(o.optJSONArray("headers"))
                    f.resBody = toBody(o.optJSONObject("body"))
                    f.durationMs = o.optLong("ms", -1)
                    f.error = o.optString("err").takeIf { it.isNotEmpty() && it != "null" }
                    f.note = o.optString("note").takeIf { it.isNotEmpty() && it != "null" }
                    if (o.optBoolean("edited")) f.upstream = "ProxySpy đã sửa call này"
                    FlowStore.touched()
                }
            }

            Wire.T_WS -> {
                val url = o.optString("url")
                val f = newFlow("WS", url, o.optLong("ts", System.currentTimeMillis()))
                f.pkg = conn.pkg
                f.source = Flow.SRC_HOOK
                f.ws = true
                f.wsOut = o.optString("dir") == "out"
                f.wsOp = o.optString("op")
                f.status = 0
                f.resBody = toBody(o.optJSONObject("body"))
                f.note = o.optString("note").takeIf { it.isNotEmpty() && it != "null" }
                FlowStore.add(f)
            }

            Wire.T_LOG -> {
                val f = newFlow("—", "", System.currentTimeMillis())
                f.pkg = conn.pkg
                f.source = Flow.SRC_HOOK
                f.ws = true
                f.wsOp = "note"
                f.status = 0
                f.note = o.optString("msg")
                FlowStore.add(f)
            }

            Wire.T_ASK -> {
                val askId = FlowStore.newId()
                pending[askId] = Pending(askId, conn, o.optLong("aid"), o)
                notifyUi()
                BreakNotifier.show(askId)
            }
        }
    }

    private fun newFlow(method: String, url: String, ts: Long): Flow {
        val u = runCatching { java.net.URI(url) }.getOrNull()
        val scheme = u?.scheme ?: if (url.startsWith("ws")) "wss" else "https"
        val host = u?.host ?: url.substringAfter("//").substringBefore('/')
        val path = ((u?.rawPath ?: "") + (u?.rawQuery?.let { "?$it" } ?: "")).ifEmpty { "/" }
        return Flow(FlowStore.newId(), ts, method, scheme, host, path, url)
    }

    private fun toBody(o: JSONObject?): Body? {
        if (o == null) return null
        val text = if (o.has("text")) o.optString("text") else null
        val b64 = if (o.has("b64")) o.optString("b64") else null
        if (text == null && b64 == null) return null
        return Body(
            kind = if (text != null) "text" else "binary",
            text = text,
            base64 = b64,
            size = o.optInt("size"),
            truncated = o.optBoolean("trunc"),
            contentType = o.optString("ct"),
        )
    }

    // ------------------------------------------------------------------ outbound

    fun sendConfig(conn: Conn) {
        val capture = Prefs.isTarget(conn.pkg)
        conn.capturing = capture
        conn.send(
            JSONObject()
                .put("t", Wire.T_CONFIG)
                .put("capture", capture)
                .put("brkReq", capture && Prefs.brkReq)
                .put("brkRes", capture && Prefs.brkRes)
                .put("brkWs", capture && Prefs.brkWs)
                .put("brkMatch", Prefs.brkMatch)
                .put("maxBody", Prefs.maxBody(App.instance))
                .put("captureWs", Prefs.captureWs)
                .put("captureRaw", Prefs.captureRaw)
                .put("rules", Rule.listToJson(RuleStore.all().filter { it.enabled }))
        )
    }

    fun broadcastConfig() {
        conns.forEach { sendConfig(it) }
        notifyUi()
    }

    /** Releases a frozen call. [verdict] carries the edits, or act = drop. */
    fun answer(askId: Long, verdict: JSONObject) {
        val p = pending.remove(askId) ?: return
        p.conn.send(verdict.put("t", Wire.T_VERDICT).put("aid", p.aid))
        BreakNotifier.dismiss(askId)
        notifyUi()
    }

    fun answerGo(askId: Long) = answer(askId, JSONObject().put("act", Wire.ACT_GO))

    fun answerAll() = pending.keys.toList().forEach { answerGo(it) }
}
