package com.dsoft.toolbox

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One saved FTP/SSH target. */
data class Conn(
    var name: String,
    var host: String,
    var port: Int = 21,
    var user: String = "",
    var pass: String = "",
    var sshPort: Int = 22,
    var lastOk: Long = 0,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name); put("host", host); put("port", port)
        put("user", user); put("pass", pass); put("sshPort", sshPort); put("lastOk", lastOk)
    }

    companion object {
        fun fromJson(j: JSONObject) = Conn(
            j.optString("name"), j.optString("host"), j.optInt("port", 21),
            j.optString("user"), j.optString("pass"), j.optInt("sshPort", 22), j.optLong("lastOk"),
        )
    }
}

/** Saved connections (connections.json in filesDir). Export/import is the same JSON text. */
object ConnStore {
    private fun file(ctx: Context) = File(ctx.filesDir, "connections.json")

    fun load(ctx: Context): MutableList<Conn> = try {
        val arr = JSONArray(file(ctx).readText())
        (0 until arr.length()).map { Conn.fromJson(arr.getJSONObject(it)) }
            .filter { it.host.isNotBlank() }.toMutableList()
    } catch (_: Exception) { mutableListOf() }

    fun save(ctx: Context, list: List<Conn>) {
        val tmp = File(ctx.filesDir, "connections.json.tmp")
        tmp.writeText(export(list))
        tmp.renameTo(file(ctx))
    }

    /** Adds or refreshes the entry with the same host+port+user (new ones go on top). */
    fun upsert(ctx: Context, c: Conn) {
        val list = load(ctx)
        val i = list.indexOfFirst { it.host == c.host && it.port == c.port && it.user == c.user }
        if (i >= 0) list[i] = c else list.add(0, c)
        save(ctx, list)
    }

    fun remove(ctx: Context, c: Conn) {
        val list = load(ctx)
        list.removeAll { it.host == c.host && it.port == c.port && it.user == c.user }
        save(ctx, list)
    }

    fun export(list: List<Conn>): String = JSONArray().apply { list.forEach { put(it.toJson()) } }.toString(2)

    /** Merges imported entries into the stored list; returns how many were read. */
    fun import(ctx: Context, text: String): Int {
        val arr = JSONArray(text)
        var n = 0
        for (i in 0 until arr.length()) {
            val c = Conn.fromJson(arr.getJSONObject(i))
            if (c.host.isNotBlank()) { upsert(ctx, c); n++ }
        }
        return n
    }
}
