package com.dsoft.proxyspy

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One rewrite rule: match a request by URL/method, then mock or modify the response. */
class Rule(
    var enabled: Boolean = true,
    var name: String = "",
    var match: String = "",          // substring of URL, or "re:regex"; empty = all
    var method: String = "",
    var mock: Boolean = false,       // true: return canned response without forwarding
    var status: String = "",         // override/mock status code
    var contentType: String = "application/json; charset=utf-8",
    var body: String = "",           // full replacement / mock body
    var find: String = "",           // find in response body
    var replace: String = "",
    // --- request payload (body gửi lên server) ---
    var reqBody: String = "",        // thay thế TOÀN BỘ payload gửi lên
    var reqMerge: String = "",       // JSON object: dùng payload gốc, đè các property này vào (deep-merge)
    var reqFind: String = "",        // find trong payload
    var reqReplace: String = "",
) {
    fun matches(m: String, url: String): Boolean {
        if (!enabled) return false
        if (match.isNotEmpty()) {
            if (match.startsWith("re:")) {
                if (!runCatching { Regex(match.substring(3)).containsMatchIn(url) }.getOrDefault(false)) return false
            } else if (!url.contains(match)) return false
        }
        if (method.isNotEmpty() && !method.equals(m, true)) return false
        return true
    }

    /** Có sửa payload request không? */
    fun modifiesRequest(): Boolean =
        reqBody.isNotEmpty() || reqMerge.isNotEmpty() || reqFind.isNotEmpty()

    /** Áp dụng sửa payload lên [bytes] gốc, trả về payload mới. */
    fun applyRequest(bytes: ByteArray): ByteArray {
        var cur = bytes
        // 1) thay toàn bộ
        if (reqBody.isNotEmpty()) cur = reqBody.toByteArray(Charsets.UTF_8)
        // 2) find → replace (text)
        else if (reqFind.isNotEmpty())
            cur = String(cur, Charsets.UTF_8).replace(reqFind, reqReplace).toByteArray(Charsets.UTF_8)
        // 3) merge JSON: dùng payload gốc, đè property từ reqMerge (deep-merge)
        if (reqMerge.isNotEmpty()) {
            try {
                val over = JSONObject(reqMerge)
                val base = runCatching { JSONObject(String(cur, Charsets.UTF_8)) }.getOrElse { JSONObject() }
                cur = deepMerge(base, over).toString().toByteArray(Charsets.UTF_8)
            } catch (_: Exception) { /* reqMerge không phải JSON object → bỏ qua */ }
        }
        return cur
    }

    fun toJson(): JSONObject = JSONObject().put("enabled", enabled).put("name", name).put("match", match)
        .put("method", method).put("mock", mock).put("status", status).put("contentType", contentType)
        .put("body", body).put("find", find).put("replace", replace)
        .put("reqBody", reqBody).put("reqMerge", reqMerge).put("reqFind", reqFind).put("reqReplace", reqReplace)

    companion object {
        fun fromJson(o: JSONObject) = Rule(
            o.optBoolean("enabled", true), o.optString("name"), o.optString("match"), o.optString("method"),
            o.optBoolean("mock"), o.optString("status"), o.optString("contentType", "application/json; charset=utf-8"),
            o.optString("body"), o.optString("find"), o.optString("replace"),
            o.optString("reqBody"), o.optString("reqMerge"), o.optString("reqFind"), o.optString("reqReplace"),
        )

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
            if (!f.exists()) emptyList()
            else {
                val arr = JSONArray(f.readText())
                (0 until arr.length()).map { Rule.fromJson(arr.getJSONObject(it)) }
            }
        } catch (_: Exception) { emptyList() }
    }

    fun all(): List<Rule> = synchronized(lock) { rules }

    fun setAll(list: List<Rule>) {
        synchronized(lock) { rules = list }
        try {
            val arr = JSONArray()
            list.forEach { arr.put(it.toJson()) }
            file?.writeText(arr.toString())
        } catch (_: Exception) {}
    }

    fun matching(method: String, url: String): List<Rule> =
        synchronized(lock) { rules }.filter { it.matches(method, url) }
}
