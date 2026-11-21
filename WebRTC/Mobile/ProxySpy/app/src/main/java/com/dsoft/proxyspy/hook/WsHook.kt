package com.dsoft.proxyspy.hook

import com.dsoft.proxyspy.Wire
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import org.json.JSONObject

/**
 * WebSocket capture and rewriting.
 *
 * Frames never pass through the interceptor chain — only the HTTP upgrade does — so they are taken
 * straight off `okhttp3.internal.ws.RealWebSocket`:
 *
 *  - outgoing: `send(String)` / `send(ByteString)`, hooked *before* the frame is queued, so the
 *    payload can still be swapped or the frame dropped;
 *  - incoming: `onReadMessage(String)` / `onReadMessage(ByteString)`, hooked before the app's
 *    `WebSocketListener` runs, so the app sees whatever we hand it.
 */
object WsHook {

    fun install(cl: ClassLoader): Boolean {
        val rws = XposedHelpers.findClassIfExists("okhttp3.internal.ws.RealWebSocket", cl) ?: return false
        val api = OkApi.of(cl)

        XposedBridge.hookAllMethods(rws, "send", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                runCatching { onFrame(api, param, outgoing = true) }
            }
        })
        XposedBridge.hookAllMethods(rws, "onReadMessage", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                runCatching { onFrame(api, param, outgoing = false) }
            }
        })
        XposedBridge.hookAllMethods(rws, "onReadClose", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!Bridge.active || !Bridge.cfg.captureWs) return
                val code = param.args.getOrNull(0) as? Int ?: 0
                val reason = param.args.getOrNull(1) as? String ?: ""
                event(urlOf(api, param.thisObject), "in", "close", null, "đóng ($code) $reason")
            }
        })
        XposedBridge.hookAllMethods(rws, "connect", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (!Bridge.active || !Bridge.cfg.captureWs) return
                event(urlOf(api, param.thisObject), "out", "open", null, "mở kết nối")
            }
        })
        return true
    }

    // ------------------------------------------------------------------

    private fun onFrame(api: OkApi, param: XC_MethodHook.MethodHookParam, outgoing: Boolean) {
        if (!Bridge.active || !Bridge.cfg.captureWs) return
        val arg = param.args.getOrNull(0) ?: return
        val isText = arg is String
        val bytes: ByteArray = when {
            isText -> (arg as String).toByteArray(Charsets.UTF_8)
            else -> api.byteStringBytes(arg) ?: return
        }

        val url = urlOf(api, param.thisObject)
        val cfg = Bridge.cfg
        val rules = cfg.rules.filter { it.matchesWs(url, outgoing) }

        var out: ByteArray? = bytes
        var edited = false
        for (r in rules) {
            if (r.wsDrop) {
                event(url, if (outgoing) "out" else "in", if (isText) "text" else "binary",
                    Wire.body(bytes, if (isText) "text/plain" else "application/octet-stream", cfg.maxBody),
                    "đã bỏ (rule: ${r.name.ifEmpty { r.match }})")
                skip(param, outgoing)
                return
            }
            if (r.rewritesWs()) {
                val nb = r.applyWsBody(out)
                if (nb != null && !nb.contentEquals(out)) { out = nb; edited = true }
            }
        }

        val stage = if (outgoing) Wire.ST_WS_OUT else Wire.ST_WS_IN
        if (cfg.breakpointOnWs(url) || rules.any { it.brkReq }) {
            val ans = Bridge.ask(
                JSONObject().put("stage", stage).put("id", Bridge.nextId()).put("pkg", Bridge.pkg)
                    .put("url", url).put("dir", if (outgoing) "out" else "in")
                    .put("op", if (isText) "text" else "binary")
                    .put("body", Wire.body(out, if (isText) "text/plain" else "application/octet-stream", cfg.maxBody))
            )
            if (ans != null) {
                if (ans.optString("act") == Wire.ACT_DROP) {
                    event(url, if (outgoing) "out" else "in", if (isText) "text" else "binary",
                        Wire.body(bytes, "text/plain", cfg.maxBody), "đã bỏ ở breakpoint")
                    skip(param, outgoing)
                    return
                }
                ans.optJSONObject("body")?.let { Wire.bodyBytes(it)?.let { b -> out = b; edited = true } }
            }
        }

        val finalBytes = out ?: bytes
        if (edited) {
            val replaced: Any? =
                if (isText) String(finalBytes, Charsets.UTF_8) else api.byteStringOf(finalBytes)
            if (replaced != null) param.args[0] = replaced
        }

        event(
            url, if (outgoing) "out" else "in", if (isText) "text" else "binary",
            Wire.body(finalBytes, if (isText) "text/plain" else "application/octet-stream", cfg.maxBody),
            if (edited) "đã sửa" else null,
        )
    }

    /** Swallows the frame: `send` answers true (queued), `onReadMessage` simply returns. */
    private fun skip(param: XC_MethodHook.MethodHookParam, outgoing: Boolean) {
        if (outgoing) param.setResult(true) else param.setResult(null)
    }

    private fun urlOf(api: OkApi, ws: Any?): String {
        if (ws == null) return ""
        return runCatching {
            val req = XposedHelpers.getObjectField(ws, "originalRequest")
            if (req != null) api.reqUrl(req) else ""
        }.getOrDefault("")
    }

    private fun event(url: String, dir: String, op: String, body: JSONObject?, note: String?) =
        Bridge.send(
            JSONObject().put("t", Wire.T_WS).put("ts", System.currentTimeMillis())
                .put("url", url).put("dir", dir).put("op", op).put("body", body).put("note", note)
        )
}
