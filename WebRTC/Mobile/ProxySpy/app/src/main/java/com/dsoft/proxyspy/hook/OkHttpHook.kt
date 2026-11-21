package com.dsoft.proxyspy.hook

import com.dsoft.proxyspy.Rule
import com.dsoft.proxyspy.Wire
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import org.json.JSONObject
import java.io.IOException
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * HTTP capture and rewriting for apps that use OkHttp (which includes Retrofit, Ktor's OkHttp
 * engine, Coil/Glide/Picasso and most Volley setups).
 *
 * The hook waits for the app to build an `OkHttpClient` and slips a dynamic-proxy `Interceptor` in
 * at position 0 of the application interceptor list. From there we see the request exactly as the
 * app wrote it and the response exactly as the app will read it — already decrypted, already
 * un-gzipped, HTTP/2 or not, and regardless of certificate pinning, because we are *inside* the
 * TLS boundary rather than in front of it.
 */
object OkHttpHook {

    /** Bodies bigger than this are never buffered, however they are typed. */
    private const val MAX_BUFFER = 8L * 1024 * 1024

    /** Content types that must never be buffered — reading them would stall the app. */
    private val STREAMING = listOf("text/event-stream", "application/grpc", "multipart/x-mixed-replace")

    fun install(cl: ClassLoader): Boolean {
        val builderCls = XposedHelpers.findClassIfExists("okhttp3.OkHttpClient\$Builder", cl) ?: return false
        val api = OkApi.of(cl)
        if (!api.usable) {
            Bridge.log("okhttp3 classes found but incomplete — bỏ qua lớp OkHttp")
            return false
        }
        XposedBridge.hookAllMethods(builderCls, "build", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                runCatching { inject(api, cl, param.thisObject) }
            }
        })
        return true
    }

    @Suppress("UNCHECKED_CAST")
    private fun inject(api: OkApi, cl: ClassLoader, builder: Any) {
        val list = XposedHelpers.getObjectField(builder, "interceptors") as? MutableList<Any> ?: return
        if (list.any { Proxy.isProxyClass(it.javaClass) && Proxy.getInvocationHandler(it) is Spy }) return
        val spy = Proxy.newProxyInstance(cl, arrayOf(api.interceptor), Spy(api))
        list.add(0, spy)
    }

    /** Invocation handler standing in for an `okhttp3.Interceptor`. */
    private class Spy(private val api: OkApi) : InvocationHandler {
        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? = when {
            method.name == "intercept" && args != null && args.size == 1 -> intercept(api, args[0]!!)
            method.name == "equals" && args != null -> proxy === args[0]
            method.name == "hashCode" -> System.identityHashCode(proxy)
            method.name == "toString" -> "ProxySpyInterceptor"
            else -> null
        }
    }

    // ------------------------------------------------------------------ the interception itself

    private fun intercept(api: OkApi, chain: Any): Any {
        val req0 = api.chainRequest(chain) ?: throw IOException("ProxySpy: không đọc được request")
        if (!Bridge.active) return api.chainProceed(chain, req0)
        return runCatching { interceptInner(api, chain, req0) }.getOrElse { t ->
            // Never let a bug in the inspector break the app being inspected.
            if (t is ProxySpyAbort) throw (t.cause ?: IOException(t.message ?: "ProxySpy"))
            Bridge.log("intercept lỗi: $t")
            api.chainProceed(chain, req0)
        }
    }

    private class ProxySpyAbort(message: String, cause: Throwable?) : RuntimeException(message, cause)

    private fun interceptInner(api: OkApi, chain: Any, req0: Any): Any {
        val cfg = Bridge.cfg
        val id = Bridge.nextId()
        val url0 = api.reqUrl(req0)
        val method = api.reqMethod(req0)
        val rules = cfg.rules.filter { it.matchesHttp(method, url0) }

        // ---------- request side ----------
        var headers = api.reqHeaders(req0)
        val reqBodyObj = api.reqBodyObj(req0)
        val reqCt = reqBodyObj?.let { api.reqBodyContentType(it) } ?: ""
        var reqBody = api.readRequestBody(reqBodyObj)
        val hadBody = reqBodyObj != null
        var url = url0
        var changed = false

        for (r in rules) {
            if (r.urlFind.isNotEmpty()) {
                val n = r.newUrl(url)
                if (n != url) { url = n; changed = true }
            }
            if (r.reqHeaders.isNotEmpty()) {
                headers = mergeHeaders(headers, Wire.parseHeaderLines(r.reqHeaders)); changed = true
            }
            if (r.reqBody.isNotEmpty() || r.reqMerge.isNotEmpty() || r.reqFind.isNotEmpty()) {
                val nb = r.applyRequestBody(reqBody)
                if (nb != null && !nb.contentEquals(reqBody)) { reqBody = nb; changed = true }
            }
        }

        if (cfg.breakpointOnReq(url) || rules.any { it.brkReq }) {
            val ans = Bridge.ask(
                JSONObject().put("stage", Wire.ST_REQ).put("id", id).put("pkg", Bridge.pkg)
                    .put("method", method).put("url", url)
                    .put("headers", Wire.headers(headers))
                    .put("body", Wire.body(reqBody, reqCt, cfg.maxBody))
            )
            if (ans != null) {
                if (ans.optString("act") == Wire.ACT_DROP) {
                    flowDone(id, -1, "", emptyList(), null, "", 0, "đã chặn ở breakpoint", false)
                    throw ProxySpyAbort("ProxySpy: request bị chặn", null)
                }
                ans.optString("url", "").takeIf { it.isNotEmpty() }?.let { if (it != url) { url = it; changed = true } }
                ans.optJSONArray("headers")?.let { headers = Wire.headerList(it); changed = true }
                ans.optJSONObject("body")?.let { Wire.bodyBytes(it)?.let { b -> reqBody = b; changed = true } }
            }
        }

        val bodyAllowed = hadBody || (reqBody != null && method !in setOf("GET", "HEAD"))
        val req = if (!changed) req0 else api.rebuildRequest(
            req = req0,
            url = if (url != url0) url else null,
            method = null,
            headers = headers,
            body = if (bodyAllowed) reqBody else null,
            bodyContentType = reqCt,
        )

        flowOpen(id, method, url, headers, Wire.body(reqBody, reqCt, cfg.maxBody), url != url0 || changed)

        // ---------- hitting (or faking) the network ----------
        val started = System.currentTimeMillis()
        val delay = rules.maxOfOrNull { it.delayMs } ?: 0
        if (delay > 0) runCatching { Thread.sleep(delay.toLong()) }

        rules.firstOrNull { it.failWith.isNotEmpty() }?.let { r ->
            flowDone(id, -1, "", emptyList(), null, "", System.currentTimeMillis() - started, r.failWith, false)
            throw ProxySpyAbort(r.failWith, IOException(r.failWith))
        }

        val mockRule = rules.firstOrNull { it.mock }
        var res: Any
        var mocked = false
        if (mockRule != null) {
            val made = buildMock(api, req, mockRule)
            if (made != null) { res = made; mocked = true } else res = api.chainProceed(chain, req)
        } else {
            res = try {
                api.chainProceed(chain, req)
            } catch (t: Throwable) {
                flowDone(id, -1, "", emptyList(), null, "", System.currentTimeMillis() - started,
                    t.javaClass.simpleName + ": " + (t.message ?: ""), false)
                throw ProxySpyAbort(t.message ?: "lỗi mạng", t)
            }
        }

        // ---------- response side ----------
        var code = api.resCode(res)
        var message = api.resMessage(res)
        var resHeaders = api.resHeaders(res)
        var resCt = Wire.contentTypeOf(resHeaders).ifEmpty { api.resBodyContentType(res) }
        val streaming = STREAMING.any { resCt.contains(it, ignoreCase = true) }
        val declared = api.resContentLength(res)
        val tooBig = declared > MAX_BUFFER
        // Streaming and very large bodies are passed straight through: buffering them would stall
        // the app or blow its heap, and a 50 MB download is not what anyone is here to read.
        val skipBody = streaming || tooBig
        var note: String? = when {
            streaming -> "body kiểu stream — cho đi thẳng, không ghi"
            tooBig -> "body " + declared + " byte — quá lớn, cho đi thẳng, không ghi"
            else -> null
        }
        var resBody: ByteArray? = if (skipBody || mocked) null else api.readResponseBody(res)
        if (mocked) resBody = mockRule?.body?.toByteArray(Charsets.UTF_8)
        var resChanged = false

        if (!mocked) {
            for (r in rules) {
                if (r.status.isNotEmpty()) {
                    r.status.toIntOrNull()?.let { if (it != code) { code = it; resChanged = true } }
                }
                if (r.resHeaders.isNotEmpty()) {
                    resHeaders = mergeHeaders(resHeaders, Wire.parseHeaderLines(r.resHeaders)); resChanged = true
                }
                if (r.body.isNotEmpty() || r.resMerge.isNotEmpty() || r.find.isNotEmpty()) {
                    val nb = r.applyResponseBody(resBody)
                    if (nb != null && !nb.contentEquals(resBody)) { resBody = nb; resChanged = true }
                }
            }
        }

        if (!skipBody && (cfg.breakpointOnRes(url) || rules.any { it.brkRes })) {
            val ans = Bridge.ask(
                JSONObject().put("stage", Wire.ST_RES).put("id", id).put("pkg", Bridge.pkg)
                    .put("method", method).put("url", url).put("status", code)
                    .put("headers", Wire.headers(resHeaders))
                    .put("body", Wire.body(resBody, resCt, cfg.maxBody))
            )
            if (ans != null) {
                if (ans.optString("act") == Wire.ACT_DROP) {
                    flowDone(id, code, message, resHeaders, null, resCt,
                        System.currentTimeMillis() - started, "đã chặn response", mocked)
                    throw ProxySpyAbort("ProxySpy: response bị chặn", null)
                }
                if (ans.has("status")) {
                    val s = ans.optInt("status", code)
                    if (s != code) { code = s; resChanged = true }
                }
                ans.optJSONArray("headers")?.let { resHeaders = Wire.headerList(it); resChanged = true }
                ans.optJSONObject("body")?.let { Wire.bodyBytes(it)?.let { b -> resBody = b; resChanged = true } }
            }
        }

        if (resChanged) resCt = Wire.contentTypeOf(resHeaders).ifEmpty { resCt }

        // Reading the body consumed it, so whenever we have the bytes the response must be rebuilt
        // around them — otherwise the app would read an already-drained stream.
        val final = when {
            mocked -> res
            resBody != null -> api.rebuildResponse(res, code, message, resHeaders, resBody, resCt)
            resChanged -> api.rebuildResponse(res, code, message, resHeaders, null, resCt)
            else -> res
        }

        if (mocked) note = null
        flowDone(id, code, message, resHeaders, Wire.body(resBody, resCt, cfg.maxBody), resCt,
            System.currentTimeMillis() - started, null, mocked || resChanged, note)
        return final
    }

    private fun buildMock(api: OkApi, req: Any, r: Rule): Any? {
        val code = r.status.toIntOrNull() ?: 200
        val ct = r.contentType.ifEmpty { "application/json; charset=utf-8" }
        val hdrs = Wire.parseHeaderLines(r.resHeaders).toMutableList()
        if (hdrs.none { it.first.equals("Content-Type", true) }) hdrs.add("Content-Type" to ct)
        return api.mockResponse(req, code, "OK", hdrs, r.body.toByteArray(Charsets.UTF_8), ct)
    }

    /** Overwrites same-named headers, keeps the rest, appends what is new. */
    private fun mergeHeaders(
        base: List<Pair<String, String>>, over: List<Pair<String, String>>,
    ): List<Pair<String, String>> {
        if (over.isEmpty()) return base
        val out = base.filter { b -> over.none { it.first.equals(b.first, true) } }.toMutableList()
        out.addAll(over)
        return out
    }

    // ------------------------------------------------------------------ frames

    private fun flowOpen(
        id: Long, method: String, url: String,
        headers: List<Pair<String, String>>, body: JSONObject?, edited: Boolean,
    ) = Bridge.send(
        JSONObject().put("t", Wire.T_FLOW).put("phase", "open").put("id", id)
            .put("ts", System.currentTimeMillis()).put("engine", "okhttp")
            .put("method", method).put("url", url)
            .put("headers", Wire.headers(headers)).put("body", body).put("edited", edited)
    )

    private fun flowDone(
        id: Long, status: Int, message: String, headers: List<Pair<String, String>>,
        body: JSONObject?, contentType: String, ms: Long, err: String?, edited: Boolean,
        note: String? = null,
    ) = Bridge.send(
        JSONObject().put("t", Wire.T_FLOW).put("phase", "done").put("id", id)
            .put("status", status).put("message", message).put("ct", contentType)
            .put("headers", Wire.headers(headers)).put("body", body)
            .put("ms", ms).put("err", err).put("edited", edited).put("note", note)
    )
}
