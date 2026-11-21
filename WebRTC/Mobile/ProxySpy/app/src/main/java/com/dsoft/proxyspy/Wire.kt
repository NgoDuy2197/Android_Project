package com.dsoft.proxyspy

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/**
 * The line protocol spoken between the hook (running inside the inspected app's process) and the
 * Hub (the ProxySpy UI app). One UTF-8 JSON object per line over a loopback TCP socket.
 *
 * Both sides live in the same APK, so this file is the single source of truth for the wire format.
 * It must stay free of anything that only exists in the ProxySpy process (no Context, no singletons),
 * because the very same class is loaded into every hooked app.
 */
object Wire {
    /** First port tried. The Hub binds the first free one in [PORT, PORT + PORT_SPAN). */
    const val PORT = 48732
    const val PORT_SPAN = 8

    const val PROTOCOL = 1

    // --- frame types: hook -> hub ---
    const val T_HELLO = "hello"
    const val T_FLOW = "flow"
    const val T_WS = "ws"
    const val T_ASK = "ask"
    const val T_LOG = "log"

    // --- frame types: hub -> hook ---
    const val T_CONFIG = "config"
    const val T_VERDICT = "verdict"

    // --- verdict actions ---
    const val ACT_GO = "go"      // continue, possibly with edits carried in the frame
    const val ACT_DROP = "drop"  // fail the call / swallow the frame

    // --- stages a breakpoint can fire at ---
    const val ST_REQ = "req"
    const val ST_RES = "res"
    const val ST_WS_OUT = "wsout"
    const val ST_WS_IN = "wsin"

    /** Bodies larger than this are cut; the UI shows "đã cắt". */
    const val DEFAULT_MAX_BODY = 512 * 1024

    fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    fun unb64(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)

    /** True when [ct] names a type worth showing as text. */
    fun textual(ct: String): Boolean {
        val t = ct.lowercase()
        return t.isEmpty() || t.startsWith("text/") || t.contains("json") || t.contains("xml") ||
            t.contains("javascript") || t.contains("x-www-form-urlencoded") || t.contains("graphql") ||
            t.contains("html") || t.contains("csv")
    }

    /** True when [bytes] decode cleanly as UTF-8 without control junk — safe to show as text. */
    fun looksUtf8(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return true
        var i = 0
        var suspicious = 0
        while (i < bytes.size) {
            val b = bytes[i].toInt() and 0xFF
            val extra = when {
                b < 0x80 -> 0
                b in 0xC2..0xDF -> 1
                b in 0xE0..0xEF -> 2
                b in 0xF0..0xF4 -> 3
                else -> return false
            }
            if (i + extra >= bytes.size) return false
            for (k in 1..extra) {
                if ((bytes[i + k].toInt() and 0xC0) != 0x80) return false
            }
            if (extra == 0 && b < 0x09) suspicious++
            i += extra + 1
        }
        return suspicious * 20 < bytes.size + 20
    }

    /**
     * Packs a body for the wire: text when it reads as text, base64 otherwise, cut at [max].
     * `null` [bytes] means "no body"; an empty array means "a body of zero bytes".
     */
    fun body(bytes: ByteArray?, contentType: String, max: Int = DEFAULT_MAX_BODY): JSONObject? {
        if (bytes == null) return null
        val cut = bytes.size > max
        val kept = if (cut) bytes.copyOf(max) else bytes
        val o = JSONObject()
        o.put("ct", contentType)
        o.put("size", bytes.size)
        if (cut) o.put("trunc", true)
        if (textual(contentType) && looksUtf8(kept)) o.put("text", String(kept, Charsets.UTF_8))
        else o.put("b64", b64(kept))
        return o
    }

    /** Raw bytes back out of a body object (the kept part only). */
    fun bodyBytes(o: JSONObject?): ByteArray? {
        if (o == null) return null
        o.optString("text", "__none__").let { if (it != "__none__") return it.toByteArray(Charsets.UTF_8) }
        val b = o.optString("b64", "")
        return if (b.isEmpty()) ByteArray(0) else runCatching { unb64(b) }.getOrNull()
    }

    fun headers(pairs: List<Pair<String, String>>): JSONArray {
        val a = JSONArray()
        for ((k, v) in pairs) a.put(JSONArray().put(k).put(v))
        return a
    }

    fun headerList(a: JSONArray?): List<Pair<String, String>> {
        if (a == null) return emptyList()
        val out = ArrayList<Pair<String, String>>(a.length())
        for (i in 0 until a.length()) {
            val row = a.optJSONArray(i) ?: continue
            out.add(row.optString(0) to row.optString(1))
        }
        return out
    }

    /** "Content-Type: application/json" lines <-> header pairs, for the rule editor. */
    fun parseHeaderLines(text: String): List<Pair<String, String>> =
        text.lineSequence().mapNotNull { line ->
            val t = line.trim()
            if (t.isEmpty()) return@mapNotNull null
            val i = t.indexOf(':')
            if (i <= 0) null else t.substring(0, i).trim() to t.substring(i + 1).trim()
        }.toList()

    fun contentTypeOf(headers: List<Pair<String, String>>): String =
        headers.firstOrNull { it.first.equals("Content-Type", true) }?.second ?: ""
}
