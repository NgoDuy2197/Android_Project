package com.dsoft.odiary

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** A user profile. Local accounts are PIN-protected & offline; Google accounts can sync to Drive. */
class Account(
    val id: String,
    val type: String,          // "local" | "google"
    var name: String,
    var email: String = "",
    var pinHash: String = "",
) {
    val isGoogle get() = type == "google"
    fun toJson(): JSONObject = JSONObject().put("id", id).put("type", type).put("name", name).put("email", email).put("pinHash", pinHash)
    companion object {
        fun fromJson(o: JSONObject) = Account(o.getString("id"), o.optString("type", "local"), o.optString("name"), o.optString("email"), o.optString("pinHash"))
    }
}

object Accounts {
    private lateinit var file: File
    private val list = ArrayList<Account>()

    fun init(ctx: Context) {
        file = File(ctx.applicationContext.filesDir, "accounts.json")
        load()
    }

    private fun load() {
        list.clear()
        try {
            if (file.exists()) {
                val arr = JSONArray(file.readText())
                for (i in 0 until arr.length()) list.add(Account.fromJson(arr.getJSONObject(i)))
            }
        } catch (_: Exception) {}
    }

    private fun save() {
        val arr = JSONArray(); list.forEach { arr.put(it.toJson()) }
        runCatching { file.writeText(arr.toString()) }
    }

    fun all(): List<Account> = ArrayList(list)
    fun isEmpty() = list.isEmpty()
    fun get(id: String): Account? = list.firstOrNull { it.id == id }

    fun createLocal(name: String, pin: String): Account {
        val a = Account("local_${System.currentTimeMillis()}", "local", name.ifBlank { "Tôi" }, pinHash = hash(pin))
        list.add(a); save(); return a
    }

    /** Find-or-create a Google account by email. */
    fun upsertGoogle(email: String, name: String): Account {
        list.firstOrNull { it.type == "google" && it.email.equals(email, true) }?.let {
            it.name = name.ifBlank { it.name }; save(); return it
        }
        val a = Account("g_" + email.substringBefore('@'), "google", name.ifBlank { email.substringBefore('@') }, email)
        list.add(a); save(); return a
    }

    fun setPin(a: Account, pin: String) { a.pinHash = hash(pin); save() }
    fun checkPin(a: Account, pin: String): Boolean = a.pinHash == hash(pin)
    fun hasPin(a: Account): Boolean = a.pinHash.isNotEmpty()

    fun rename(a: Account, name: String) { a.name = name; save() }
    fun remove(id: String) { list.removeAll { it.id == id }; save() }

    private fun hash(pin: String): String =
        MessageDigest.getInstance("SHA-256").digest(("odiary:$pin").toByteArray()).joinToString("") { "%02x".format(it) }
}
