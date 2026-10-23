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

    fun toJson(): JSONObject = JSONObject().put("enabled", enabled).put("name", name).put("match", match)
        .put("method", method).put("mock", mock).put("status", status).put("contentType", contentType)
        .put("body", body).put("find", find).put("replace", replace)

    companion object {
        fun fromJson(o: JSONObject) = Rule(
            o.optBoolean("enabled", true), o.optString("name"), o.optString("match"), o.optString("method"),
            o.optBoolean("mock"), o.optString("status"), o.optString("contentType", "application/json; charset=utf-8"),
            o.optString("body"), o.optString("find"), o.optString("replace"),
        )
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
