package com.dsoft.photo_frame

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * frames.json (written by the Flutter app), the photo list of each frame's
 * source, and the per-widget state (which frame, which photos are shown).
 */
object FrameStore {
    private val lock = Any()
    private const val RESCAN_MS = 6 * 3600_000L

    fun frames(ctx: Context): List<FrameConfig> {
        val f = File(ctx.filesDir, "frames.json")
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONObject(f.readText()).optJSONArray("frames") ?: JSONArray()
            (0 until arr.length()).map { FrameConfig(arr.getJSONObject(it)) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun frame(ctx: Context, id: String?): FrameConfig? = frames(ctx).let { all -> all.firstOrNull { it.id == id } ?: all.firstOrNull() }

    // --------------------------------------------------------------- photos

    private fun sourceKey(cfg: FrameConfig): String = cfg.j.optJSONObject("source")?.toString() ?: ""

    /** Every photo of the frame's source, sorted by name (cached, rescanned every 6 h). */
    fun photos(ctx: Context, cfg: FrameConfig, forceScan: Boolean = false): List<String> = synchronized(lock) {
        val src = cfg.j.optJSONObject("source") ?: JSONObject().put("type", "samples")
        when (src.optString("type", "samples")) {
            "photos" -> {
                val arr = src.optJSONArray("uris") ?: JSONArray()
                return (0 until arr.length()).map { arr.getString(it) }
            }
            "folder" -> {
                val cache = File(ctx.filesDir, "photos_${cfg.id}.json")
                if (!forceScan && cache.exists()) {
                    try {
                        val j = JSONObject(cache.readText())
                        if (j.optString("key") == sourceKey(cfg) && System.currentTimeMillis() - j.optLong("at") < RESCAN_MS) {
                            val arr = j.getJSONArray("list")
                            return (0 until arr.length()).map { arr.getString(it) }
                        }
                    } catch (_: Exception) {
                    }
                }
                val list = scanTree(ctx, Uri.parse(src.optString("uri")), src.optBoolean("recursive", true))
                cache.writeText(
                    JSONObject().put("key", sourceKey(cfg)).put("at", System.currentTimeMillis())
                        .put("list", JSONArray(list)).toString(),
                )
                return list
            }
            else -> return (ctx.assets.list("samples") ?: emptyArray()).sorted().map { "asset:samples/$it" }
        }
    }

    /** Images inside a SAF folder (optionally its sub-folders), sorted by file name. */
    private fun scanTree(ctx: Context, tree: Uri, recursive: Boolean): List<String> {
        val out = ArrayList<Pair<String, String>>()
        val root = try {
            DocumentsContract.getTreeDocumentId(tree)
        } catch (_: Exception) {
            return emptyList()
        }
        val queue = ArrayDeque<String>().apply { add(root) }
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        )
        while (queue.isNotEmpty() && out.size < 5000) {
            val docId = queue.removeFirst()
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            try {
                ctx.contentResolver.query(children, cols, null, null, null)?.use { cur ->
                    while (cur.moveToNext()) {
                        val id = cur.getString(0)
                        val mime = cur.getString(1) ?: ""
                        val name = cur.getString(2) ?: ""
                        when {
                            mime == DocumentsContract.Document.MIME_TYPE_DIR -> if (recursive) queue.add(id)
                            mime.startsWith("image/") ->
                                out += name.lowercase() to DocumentsContract.buildDocumentUriUsingTree(tree, id).toString()
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
        return out.sortedBy { it.first }.map { it.second }
    }

    // --------------------------------------------------------------- widgets

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("frame_widgets", Context.MODE_PRIVATE)

    fun widgetFrame(ctx: Context, wid: Int): String? = prefs(ctx).getString("f_$wid", null)

    fun assign(ctx: Context, wid: Int, frameId: String) {
        prefs(ctx).edit().putString("f_$wid", frameId).remove("cur_$wid").putInt("idx_$wid", 0).apply()
    }

    fun forget(ctx: Context, wid: Int) {
        prefs(ctx).edit().apply { listOf("f_", "cur_", "idx_", "t_").forEach { remove("$it$wid") } }.apply()
    }

    /** A frame chosen in the app right before "add widget": claimed by the next new widget. */
    fun setPendingPin(ctx: Context, frameId: String?) = prefs(ctx).edit().putString("pending", frameId).apply()

    fun takePendingPin(ctx: Context): String? = prefs(ctx).getString("pending", null)?.also { setPendingPin(ctx, null) }

    fun lastChange(ctx: Context, wid: Int) = prefs(ctx).getLong("t_$wid", 0)

    /** Photos currently shown on the widget (advances once if none yet). */
    fun current(ctx: Context, wid: Int, cfg: FrameConfig): List<String> {
        val saved = prefs(ctx).getString("cur_$wid", null)
        if (!saved.isNullOrEmpty()) {
            val list = saved.split('\n')
            if (list.size >= cfg.count || list.size >= photos(ctx, cfg).size) return list
        }
        return advance(ctx, wid, cfg)
    }

    /** Next batch of photos: the following ones in name order, or a random pick. */
    fun advance(ctx: Context, wid: Int, cfg: FrameConfig): List<String> = synchronized(lock) {
        val all = photos(ctx, cfg)
        val n = cfg.count
        val p = prefs(ctx)
        val chosen = if (all.isEmpty()) {
            emptyList()
        } else if (cfg.order == "random") {
            val prev = p.getString("cur_$wid", "")!!.split('\n').toSet()
            val pool = all.filter { it !in prev }.ifEmpty { all }
            (pool.shuffled().take(n) + all.shuffled()).distinct().take(minOf(n, all.size))
        } else {
            val idx = p.getInt("idx_$wid", 0)
            val list = (0 until minOf(n, all.size)).map { all[(idx + it) % all.size] }
            p.edit().putInt("idx_$wid", (idx + n) % all.size).apply()
            list
        }
        p.edit().putString("cur_$wid", chosen.joinToString("\n")).putLong("t_$wid", System.currentTimeMillis()).apply()
        return chosen
    }
}
