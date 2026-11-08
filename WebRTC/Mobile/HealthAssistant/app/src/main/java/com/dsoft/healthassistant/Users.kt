package com.dsoft.healthassistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One app user (a person whose profile + meals are tracked separately). */
class User(val id: String, var name: String, val createdAt: Long) {
    fun toJson() = JSONObject().apply { put("id", id); put("name", name); put("createdAt", createdAt) }

    companion object {
        fun fromJson(j: JSONObject) = User(
            id = j.optString("id"),
            name = j.optString("name", "Người dùng"),
            createdAt = j.optLong("createdAt", 0),
        )
    }
}

/**
 * Multi-user registry (users.json in filesDir). The "current" user picks which
 * namespaced data files [Store] reads/writes. AI settings stay device-wide (see [Prefs]).
 */
object Users {
    private val lock = Any()
    private fun file(ctx: Context) = File(ctx.filesDir, "users.json")

    private fun read(ctx: Context): JSONObject = synchronized(lock) {
        val f = file(ctx)
        if (!f.exists()) return JSONObject()
        return try { JSONObject(f.readText()) } catch (_: Exception) { JSONObject() }
    }

    private fun write(ctx: Context, root: JSONObject) = synchronized(lock) {
        val tmp = File(ctx.filesDir, "users.json.tmp")
        tmp.writeText(root.toString())
        tmp.renameTo(file(ctx))
    }

    fun list(ctx: Context): List<User> {
        val arr = read(ctx).optJSONArray("users") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(User::fromJson) }
            .filter { it.id.isNotBlank() }
    }

    fun currentId(ctx: Context): String = read(ctx).optString("current", "")

    fun current(ctx: Context): User? {
        val id = currentId(ctx)
        return list(ctx).firstOrNull { it.id == id }
    }

    fun setCurrent(ctx: Context, id: String) = write(ctx, read(ctx).put("current", id))

    fun get(ctx: Context, id: String): User? = list(ctx).firstOrNull { it.id == id }

    fun create(ctx: Context, name: String): User {
        val user = User("u${System.currentTimeMillis()}", name.trim().ifBlank { "Người dùng" }, System.currentTimeMillis())
        val root = read(ctx)
        val arr = root.optJSONArray("users") ?: JSONArray()
        arr.put(user.toJson())
        root.put("users", arr).put("current", user.id)
        write(ctx, root)
        return user
    }

    fun rename(ctx: Context, id: String, name: String) {
        val root = read(ctx)
        val arr = root.optJSONArray("users") ?: return
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("id") == id) { o.put("name", name.trim().ifBlank { "Người dùng" }); break }
        }
        write(ctx, root.put("users", arr))
    }

    fun delete(ctx: Context, id: String) {
        val root = read(ctx)
        val arr = root.optJSONArray("users") ?: return
        val kept = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("id") != id) kept.put(o)
        }
        root.put("users", kept)
        if (root.optString("current") == id) root.put("current", kept.optJSONObject(0)?.optString("id") ?: "")
        write(ctx, root)
        Store.deleteUserData(ctx, id) // remove that user's profile + meals + reviews
    }
}
