package com.dsoft.vocab_widget

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One word as shown on a widget: up to 3 (text, ttsLocale) lines. */
data class Card(val wordId: String, val lines: List<Pair<String, String>>)

/**
 * Reads/writes `collections.json` (written by the Flutter app with
 * path_provider's application-support dir == Context.filesDir).
 */
object VocabData {
    private val lock = Any()

    private fun file(ctx: Context) = File(ctx.filesDir, "collections.json")

    private fun ttsLocale(lang: String) = when (lang) {
        "zh" -> "zh-CN"
        "en" -> "en-US"
        "ja" -> "ja-JP"
        "vi" -> "vi-VN"
        else -> ""
    }

    private fun readRoot(ctx: Context): JSONObject? = synchronized(lock) {
        val f = file(ctx)
        if (!f.exists()) return null
        return try {
            JSONObject(f.readText())
        } catch (_: Exception) {
            null
        }
    }

    private fun collections(root: JSONObject?): JSONArray = root?.optJSONArray("collections") ?: JSONArray()

    fun collectionList(ctx: Context): List<Pair<String, String>> {
        val arr = collections(readRoot(ctx))
        return (0 until arr.length()).map {
            val c = arr.getJSONObject(it)
            c.optString("id") to c.optString("name")
        }
    }

    fun fieldCount(ctx: Context, collectionId: String): Int =
        find(collections(readRoot(ctx)), collectionId)?.optJSONArray("fields")?.length() ?: 0

    private fun find(arr: JSONArray, id: String): JSONObject? {
        for (i in 0 until arr.length()) {
            val c = arr.getJSONObject(i)
            if (c.optString("id") == id) return c
        }
        return null
    }

    /** (name, learned, total) of a collection, or null if it is gone. */
    fun summary(ctx: Context, collectionId: String): Triple<String, Int, Int>? {
        val c = find(collections(readRoot(ctx)), collectionId) ?: return null
        val words = c.optJSONArray("words") ?: JSONArray()
        var learned = 0
        for (i in 0 until words.length()) if (words.getJSONObject(i).optBoolean("learned")) learned++
        return Triple(c.optString("name"), learned, words.length())
    }

    fun card(ctx: Context, collectionId: String, wordId: String?): Card? {
        val c = find(collections(readRoot(ctx)), collectionId) ?: return null
        val words = c.optJSONArray("words") ?: return null
        for (i in 0 until words.length()) {
            val w = words.getJSONObject(i)
            if (w.optString("id") == wordId && !w.optBoolean("learned")) return toCard(c, w)
        }
        return null
    }

    private fun toCard(c: JSONObject, w: JSONObject): Card {
        val fields = c.optJSONArray("fields") ?: JSONArray()
        val values = w.optJSONArray("values") ?: JSONArray()
        val lines = (0 until minOf(3, fields.length())).map {
            values.optString(it, "") to ttsLocale(fields.getJSONObject(it).optString("lang"))
        }
        return Card(w.optString("id"), lines)
    }

    /** Random unlearned word other than [exceptId]; null when all are learned. */
    fun randomCard(ctx: Context, collectionId: String, exceptId: String?): Card? {
        val c = find(collections(readRoot(ctx)), collectionId) ?: return null
        val words = c.optJSONArray("words") ?: return null
        val pool = (0 until words.length()).map { words.getJSONObject(it) }.filter { !it.optBoolean("learned") }
        if (pool.isEmpty()) return null
        val choices = pool.filter { it.optString("id") != exceptId }.ifEmpty { pool }
        return toCard(c, choices.random())
    }

    fun markLearned(ctx: Context, collectionId: String, wordId: String): Unit = synchronized(lock) {
        val root = readRoot(ctx) ?: return
        val c = find(collections(root), collectionId) ?: return
        val words = c.optJSONArray("words") ?: return
        for (i in 0 until words.length()) {
            val w = words.getJSONObject(i)
            if (w.optString("id") == wordId) w.put("learned", true)
        }
        val tmp = File(ctx.filesDir, "collections.json.tmp")
        tmp.writeText(root.toString(1))
        tmp.renameTo(file(ctx))
        Unit
    }
}
