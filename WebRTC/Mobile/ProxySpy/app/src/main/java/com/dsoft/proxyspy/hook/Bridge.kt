package com.dsoft.proxyspy.hook

import com.dsoft.proxyspy.Rule
import com.dsoft.proxyspy.Wire
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * The hook half of the link to the ProxySpy UI ("the Hub"). Runs inside the inspected app.
 *
 * Everything here is best-effort: if the Hub is not running, or the app was never selected as a
 * target, [active] stays false and every capture call becomes a cheap no-op, so a hooked app that
 * nobody is watching keeps running at full speed.
 */
object Bridge {

    /** What the Hub told us to do. Replaced wholesale whenever a config frame arrives. */
    class Config(
        val capture: Boolean = false,
        val brkReq: Boolean = false,
        val brkRes: Boolean = false,
        val brkWs: Boolean = false,
        val brkMatch: String = "",
        val maxBody: Int = Wire.DEFAULT_MAX_BODY,
        val captureWs: Boolean = true,
        val captureRaw: Boolean = false,
        val rules: List<Rule> = emptyList(),
    ) {
        fun breakpointOnReq(url: String) = brkReq && urlInScope(url)
        fun breakpointOnRes(url: String) = brkRes && urlInScope(url)
        fun breakpointOnWs(url: String) = brkWs && urlInScope(url)
        private fun urlInScope(url: String) =
            brkMatch.isEmpty() || url.contains(brkMatch, ignoreCase = true)
    }

    private const val TAG = "ProxySpy"
    private const val ASK_TIMEOUT_MS = 120_000L

    @Volatile var pkg: String = ""
        private set

    /** True once the Hub has answered our hello with "yes, this app is a target". */
    @Volatile var active = false
        private set

    @Volatile var cfg = Config()
        private set

    private val ids = AtomicLong(0)
    private val asks = ConcurrentHashMap<Long, Ask>()
    private var out: OutputStream? = null
    private val writeLock = Any()
    @Volatile private var started = false

    private class Ask {
        val latch = CountDownLatch(1)
        @Volatile var answer: JSONObject? = null
    }

    fun nextId(): Long = ids.incrementAndGet()

    fun log(msg: String) = runCatching { de.robv.android.xposed.XposedBridge.log("$TAG/$pkg: $msg") }

    fun start(packageName: String) {
        if (started) return
        started = true
        pkg = packageName
        Thread({ loop() }, "proxyspy-bridge").apply { isDaemon = true }.start()
    }

    // ------------------------------------------------------------------ connection

    private fun loop() {
        var backoff = 1500L
        while (true) {
            val s = connect()
            if (s == null) {
                sleep(backoff)
                backoff = (backoff * 2).coerceAtMost(20_000L)
                continue
            }
            backoff = 1500L
            runCatching { pump(s) }
            active = false
            synchronized(writeLock) { out = null }
            failAllPendingAsks()
            runCatching { s.close() }
            sleep(2000)
        }
    }

    private fun connect(): Socket? {
        for (p in Wire.PORT until Wire.PORT + Wire.PORT_SPAN) {
            val s = Socket()
            try {
                s.connect(InetSocketAddress("127.0.0.1", p), 400)
                s.tcpNoDelay = true
                return s
            } catch (_: Throwable) {
                runCatching { s.close() }
            }
        }
        return null
    }

    private fun pump(s: Socket) {
        synchronized(writeLock) { out = s.getOutputStream() }
        send(
            JSONObject().put("t", Wire.T_HELLO).put("pkg", pkg)
                .put("proc", currentProcessName()).put("pid", android.os.Process.myPid())
                .put("ver", Wire.PROTOCOL)
        )
        val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8), 1 shl 16)
        while (true) {
            val line = reader.readLine() ?: return
            if (line.isEmpty()) continue
            val o = runCatching { JSONObject(line) }.getOrNull() ?: continue
            when (o.optString("t")) {
                Wire.T_CONFIG -> {
                    cfg = Config(
                        capture = o.optBoolean("capture", true),
                        brkReq = o.optBoolean("brkReq"),
                        brkRes = o.optBoolean("brkRes"),
                        brkWs = o.optBoolean("brkWs"),
                        brkMatch = o.optString("brkMatch"),
                        maxBody = o.optInt("maxBody", Wire.DEFAULT_MAX_BODY),
                        captureWs = o.optBoolean("captureWs", true),
                        captureRaw = o.optBoolean("captureRaw", false),
                        rules = Rule.listFromJson(o.optJSONArray("rules")),
                    )
                    active = cfg.capture
                }
                Wire.T_VERDICT -> {
                    val ask = asks.remove(o.optLong("aid", -1))
                    if (ask != null) {
                        ask.answer = o
                        ask.latch.countDown()
                    }
                }
            }
        }
    }

    private fun failAllPendingAsks() {
        val it = asks.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            it.remove()
            e.value.latch.countDown()
        }
    }

    // ------------------------------------------------------------------ sending

    fun send(frame: JSONObject) {
        val o = synchronized(writeLock) { out } ?: return
        val bytes = (frame.toString() + "\n").toByteArray(Charsets.UTF_8)
        try {
            synchronized(writeLock) {
                o.write(bytes)
                o.flush()
            }
        } catch (_: Throwable) {
            synchronized(writeLock) { out = null }
            active = false
        }
    }

    /**
     * Sends a breakpoint request and blocks this (app) thread until the phone answers, the Hub
     * disconnects, or [ASK_TIMEOUT_MS] passes. Returns null to mean "just carry on unchanged".
     */
    fun ask(frame: JSONObject): JSONObject? {
        if (!active) return null
        val aid = nextId()
        val ask = Ask()
        asks[aid] = ask
        send(frame.put("t", Wire.T_ASK).put("aid", aid))
        val answered = try {
            ask.latch.await(ASK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        asks.remove(aid)
        return if (answered) ask.answer else null
    }

    // ------------------------------------------------------------------ misc

    private fun sleep(ms: Long) = runCatching { Thread.sleep(ms) }

    private fun currentProcessName(): String = runCatching {
        java.io.File("/proc/self/cmdline").readText().trim { it <= ' ' || it == '\u0000' }
    }.getOrDefault(pkg)
}
