package com.dsoft.proxyspy

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * One rewrite rule: match a request by URL/method, then mock or modify it.
 *
 * The same rule list drives both capture modes — the MITM proxy ([ProxyEngine]) and the in-process
 * hook ([com.dsoft.proxyspy.hook.OkHttpHook]). The hook evaluates rules *inside the watched app*,
 * so this class must stay pure JVM + org.json: no Context, no app singletons.
 *
 * The first fourteen fields are the original proxy-era ones and keep their JSON keys, so rule files
 * written by older builds still load.
 */
class Rule(
    var enabled: Boolean = true,
    var name: String = "",
    var match: String = "",          // substring of URL, or "re:regex"; empty = all
    var method: String = "",
    var mock: Boolean = false,       // true: return canned response without forwarding
    var status: String = "",         // override/mock status code
    var contentType: String = "application/json; charset=utf-8",
    var body: String = "",           // full replacement / mock body (response)
    var find: String = "",           // find in response body
    var replace: String = "",
    // --- request payload (body gửi lên server) ---
    var reqBody: String = "",        // thay thế TOÀN BỘ payload gửi lên
    var reqMerge: String = "",       // JSON object: dùng payload gốc, đè các property này vào (deep-merge)
    var reqFind: String = "",        // find trong payload
    var reqReplace: String = "",
    // --- thêm cho chế độ tự soi (hook) ---
    var scope: String = SCOPE_HTTP,  // SCOPE_HTTP | SCOPE_WS
    var brkReq: Boolean = false,     // dừng lại cho sửa tay trước khi gửi (hook)
    var brkRes: Boolean = false,     // dừng lại cho sửa tay khi nhận (hook)
    var urlFind: String = "",        // đổi URL: tìm (hoặc re:regex)
    var urlReplace: String = "",
    var reqHeaders: String = "",     // "Key: value" mỗi dòng — thêm/ghi đè header gửi lên
    var resMerge: String = "",       // deep-merge JSON vào body trả về
    var resHeaders: String = "",     // "Key: value" mỗi dòng — thêm/ghi đè header trả về
    var delayMs: Int = 0,            // giữ call lại để xem spinner / timeout
    var failWith: String = "",       // khác rỗng: ném lỗi mạng với thông báo này
    // --- frame WebSocket (chỉ chế độ hook) ---
    var wsOut: Boolean = true,
    var wsIn: Boolean = true,
    var wsBody: String = "",
    var wsMerge: String = "",
    var wsFind: String = "",
    var wsReplace: String = "",
    var wsDrop: Boolean = false,
) {
    // ------------------------------------------------------------------ matching

    fun matchesUrl(url: String): Boolean {
        if (match.isEmpty()) return true
        return if (match.startsWith("re:"))
            runCatching { Regex(match.substring(3)).containsMatchIn(url) }.getOrDefault(false)
        else url.contains(match)
    }

    /** Used by the proxy engine (HTTP only). */
    fun matches(m: String, url: String): Boolean {
        if (!enabled || scope != SCOPE_HTTP) return false
        if (method.isNotEmpty() && !method.equals(m, true)) return false
        return matchesUrl(url)
    }

    fun matchesHttp(httpMethod: String, url: String): Boolean = matches(httpMethod, url)

    fun matchesWs(url: String, outgoing: Boolean): Boolean {
        if (!enabled || scope != SCOPE_WS) return false
        if (outgoing && !wsOut) return false
        if (!outgoing && !wsIn) return false
        return matchesUrl(url)
    }

    // ------------------------------------------------------------------ what this rule does

    /** Có sửa payload request không? */
    fun modifiesRequest(): Boolean =
        reqBody.isNotEmpty() || reqMerge.isNotEmpty() || reqFind.isNotEmpty()

    fun rewritesRequest(): Boolean =
        modifiesRequest() || reqHeaders.isNotEmpty() || urlFind.isNotEmpty()

    fun rewritesResponse(): Boolean =
        mock || status.isNotEmpty() || body.isNotEmpty() || resMerge.isNotEmpty() ||
            find.isNotEmpty() || resHeaders.isNotEmpty()

    fun rewritesWs(): Boolean =
        wsDrop || wsBody.isNotEmpty() || wsMerge.isNotEmpty() || wsFind.isNotEmpty()

    fun newUrl(url: String): String =
        if (urlFind.isEmpty()) url
        else if (urlFind.startsWith("re:"))
            runCatching { Regex(urlFind.substring(3)).replace(url, urlReplace) }.getOrDefault(url)
        else url.replace(urlFind, urlReplace)

    // ------------------------------------------------------------------ applying

    /** Áp dụng sửa payload lên [bytes] gốc, trả về payload mới. */
    fun applyRequest(bytes: ByteArray): ByteArray =
        rewrite(bytes, reqBody, reqMerge, reqFind, reqReplace) ?: bytes

    fun applyRequestBody(bytes: ByteArray?): ByteArray? =
        rewrite(bytes, reqBody, reqMerge, reqFind, reqReplace)

    fun applyResponseBody(bytes: ByteArray?): ByteArray? =
        rewrite(bytes, body, resMerge, find, replace)

    fun applyWsBody(bytes: ByteArray?): ByteArray? =
        rewrite(bytes, wsBody, wsMerge, wsFind, wsReplace)

    /** whole → (or) find/replace → then merge on top. A bad JSON merge is skipped, never fatal. */
    private fun rewrite(
        bytes: ByteArray?, whole: String, merge: String, find: String, repl: String,
    ): ByteArray? {
        var cur = bytes
        if (whole.isNotEmpty()) cur = whole.toByteArray(Charsets.UTF_8)
        else if (find.isNotEmpty() && cur != null)
            cur = String(cur, Charsets.UTF_8).replace(find, repl).toByteArray(Charsets.UTF_8)
        if (merge.isNotEmpty()) {
            runCatching {
                val over = JSONObject(merge)
                val base = runCatching { JSONObject(String(cur ?: ByteArray(0), Charsets.UTF_8)) }
                    .getOrElse { JSONObject() }
                cur = deepMerge(base, over).toString().toByteArray(Charsets.UTF_8)
            }
        }
        return cur
    }

    /** One-line summary for the rule list. */
    fun summary(): String {
        val bits = ArrayList<String>()
        if (mock) bits.add("mock")
        if (status.isNotEmpty()) bits.add("status " + status)
        if (body.isNotEmpty() || resMerge.isNotEmpty() || find.isNotEmpty()) bits.add("sửa response")
        if (rewritesRequest()) bits.add("sửa request")
        if (delayMs > 0) bits.add("+" + delayMs + "ms")
        if (failWith.isNotEmpty()) bits.add("ép lỗi")
        if (brkReq) bits.add("dừng req")
        if (brkRes) bits.add("dừng res")
        if (scope == SCOPE_WS) {
            if (wsDrop) bits.add("bỏ frame") else if (rewritesWs()) bits.add("sửa frame")
        }
        return if (bits.isEmpty()) "chỉ ghi lại" else bits.joinToString(" · ")
    }

    fun copy(): Rule = fromJson(toJson())

    // ------------------------------------------------------------------ json

    fun toJson(): JSONObject = JSONObject().put("enabled", enabled).put("name", name).put("match", match)
        .put("method", method).put("mock", mock).put("status", status).put("contentType", contentType)
        .put("body", body).put("find", find).put("replace", replace)
        .put("reqBody", reqBody).put("reqMerge", reqMerge).put("reqFind", reqFind).put("reqReplace", reqReplace)
        .put("scope", scope).put("brkReq", brkReq).put("brkRes", brkRes)
        .put("urlFind", urlFind).put("urlReplace", urlReplace).put("reqHeaders", reqHeaders)
        .put("resMerge", resMerge).put("resHeaders", resHeaders)
        .put("delayMs", delayMs).put("failWith", failWith)
        .put("wsOut", wsOut).put("wsIn", wsIn).put("wsBody", wsBody).put("wsMerge", wsMerge)
        .put("wsFind", wsFind).put("wsReplace", wsReplace).put("wsDrop", wsDrop)

    companion object {
        const val SCOPE_HTTP = "http"
        const val SCOPE_WS = "ws"

        fun fromJson(o: JSONObject) = Rule(
            o.optBoolean("enabled", true), o.optString("name"), o.optString("match"), o.optString("method"),
            o.optBoolean("mock"), o.optString("status"), o.optString("contentType", "application/json; charset=utf-8"),
            o.optString("body"), o.optString("find"), o.optString("replace"),
            o.optString("reqBody"), o.optString("reqMerge"), o.optString("reqFind"), o.optString("reqReplace"),
            o.optString("scope", SCOPE_HTTP), o.optBoolean("brkReq"), o.optBoolean("brkRes"),
            o.optString("urlFind"), o.optString("urlReplace"), o.optString("reqHeaders"),
            o.optString("resMerge"), o.optString("resHeaders"),
            o.optInt("delayMs"), o.optString("failWith"),
            o.optBoolean("wsOut", true), o.optBoolean("wsIn", true),
            o.optString("wsBody"), o.optString("wsMerge"), o.optString("wsFind"), o.optString("wsReplace"),
            o.optBoolean("wsDrop"),
        )

        fun listToJson(rules: List<Rule>): JSONArray {
            val a = JSONArray()
            rules.forEach { a.put(it.toJson()) }
            return a
        }

        fun listFromJson(a: JSONArray?): List<Rule> {
            if (a == null) return emptyList()
            return (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { fromJson(it) } }
        }

        /** Deep-merge: giá trị trong [over] đè lên [base]; object lồng nhau thì merge đệ quy. */
        private fun deepMerge(base: JSONObject, over: JSONObject): JSONObject {
            val it = over.keys()
            while (it.hasNext()) {
                val k = it.next()
                val ov = over.get(k)
                val bv = base.opt(k)
                if (ov is JSONObject && bv is JSONObject) base.put(k, deepMerge(bv, ov))
                else base.put(k, ov)
            }
            return base
        }
    }
}

object RuleStore {
    private val lock = Any()
    private var rules = emptyList<Rule>()
    private var file: File? = null

    fun init(ctx: Context) {
        file = File(ctx.filesDir, "rules.json")
        load()
    }

    private fun load() {
        val f = file ?: return
        rules = try {
            if (!f.exists()) emptyList() else Rule.listFromJson(JSONArray(f.readText()))
        } catch (_: Exception) { emptyList() }
    }

    fun all(): List<Rule> = synchronized(lock) { rules }

    fun setAll(list: List<Rule>) {
        synchronized(lock) { rules = list }
        try {
            file?.writeText(Rule.listToJson(list).toString())
        } catch (_: Exception) {}
        // Hooked apps hold their own copy of the rules; push the new set down immediately.
        Hub.broadcastConfig()
    }

    /** HTTP rules for the proxy engine — WebSocket rules only mean something in hook mode. */
    fun matching(method: String, url: String): List<Rule> =
        synchronized(lock) { rules }.filter { it.matches(method, url) }
}
