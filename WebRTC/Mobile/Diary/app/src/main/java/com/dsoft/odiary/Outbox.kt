package com.dsoft.odiary

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Durable upload queue ("outbox"). Every local change appends an operation to `_outbox.json` in the
 * current account's folder; the sync only processes THESE operations (O(number of changes)), instead
 * of scanning every file against Drive (O(all data) — not viable once there's a lot of data).
 *
 * An op references a path relative to the account's diary root, e.g. "2026/10/25/day.json" or a photo.
 * Ops survive app kills; each is removed only after it succeeds on Drive, so failures auto-retry.
 */
object Outbox {
    private val lock = Any()

    class Op(val op: String, val path: String) { // op = "put" (upload/update) | "del" (remove on Drive)
        fun toJson(): JSONObject = JSONObject().put("op", op).put("path", path)
    }

    private fun file(): File = File(Store.root(), "_outbox.json")

    fun put(path: String) = enqueue("put", path)
    fun del(path: String) = enqueue("del", path)

    private fun enqueue(op: String, path: String) {
        synchronized(lock) {
            val ops = load().toMutableList()
            ops.removeAll { it.path == path }   // collapse: only the latest intent per path matters
            ops.add(Op(op, path))
            save(ops)
        }
        runCatching { Prefs.setPendingSync(App.instance, true) }
    }

    fun all(): List<Op> = synchronized(lock) { load() }
    fun isEmpty(): Boolean = synchronized(lock) { load().isEmpty() }
    fun size(): Int = synchronized(lock) { load().size }

    fun remove(path: String) {
        synchronized(lock) {
            val ops = load().toMutableList()
            if (ops.removeAll { it.path == path }) save(ops)
        }
    }

    fun clear() = synchronized(lock) { save(emptyList()) }

    private fun load(): List<Op> {
        val f = file()
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { val o = arr.getJSONObject(it); Op(o.getString("op"), o.getString("path")) }
        } catch (_: Exception) { emptyList() }
    }

    private fun save(ops: List<Op>) {
        runCatching {
            val f = file()
            f.parentFile?.mkdirs()
            val arr = JSONArray(); ops.forEach { arr.put(it.toJson()) }
            val tmp = File(f.parentFile, "_outbox.json.tmp")
            tmp.writeText(arr.toString())
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }
    }
}
