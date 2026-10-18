package com.dsoft.news_glass

import android.content.Context
import android.text.Html
import android.util.Xml
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale

data class FeedCfg(val id: String, val name: String, val url: String)

data class Item(
    val id: String,
    val title: String,
    val link: String,
    val summary: String,
    val image: String,
    val source: String,
    val feedId: String,
    val published: Long,
    var read: Boolean = false,
) {
    fun toJson() = JSONObject().apply {
        put("id", id); put("title", title); put("link", link); put("summary", summary)
        put("image", image); put("source", source); put("feedId", feedId)
        put("published", published); put("read", read)
    }

    companion object {
        fun fromJson(j: JSONObject) = Item(
            j.optString("id"), j.optString("title"), j.optString("link"), j.optString("summary"),
            j.optString("image"), j.optString("source"), j.optString("feedId"),
            j.optLong("published"), j.optBoolean("read"),
        )
    }
}

/** Settings written by the Flutter app (config.json). */
class NewsConfig(val feeds: List<FeedCfg>, val notify: Boolean, val intervalMin: Int, val theme: String) {
    companion object {
        private val defaults = listOf(
            FeedCfg("vne_top", "VnExpress · Xem nhiều", "https://vnexpress.net/rss/tin-xem-nhieu.rss"),
            FeedCfg("vne_world", "VnExpress · Thế giới", "https://vnexpress.net/rss/the-gioi.rss"),
        )

        fun load(ctx: Context): NewsConfig {
            val f = File(ctx.filesDir, "config.json")
            if (!f.exists()) return NewsConfig(defaults, true, 60, "yahoo")
            return try {
                val j = JSONObject(f.readText())
                val arr = j.optJSONArray("feeds") ?: JSONArray()
                val feeds = (0 until arr.length()).map { arr.getJSONObject(it) }
                    .filter { it.optBoolean("enabled", true) }
                    .map { FeedCfg(it.optString("id"), it.optString("name"), it.optString("url")) }
                NewsConfig(feeds, j.optBoolean("notify", true), j.optInt("intervalMin", 60), j.optString("theme", "yahoo"))
            } catch (_: Exception) {
                NewsConfig(defaults, true, 60, "yahoo")
            }
        }
    }
}

/**
 * Single writer of news.json: the hourly job, the widget and the app (via
 * MethodChannel) all go through here. The app and widget only read it.
 */
object NewsRepo {
    private val lock = Any()
    private const val MAX_ITEMS = 400
    private const val KEEP_READ_MS = 7L * 24 * 3600 * 1000

    private fun file(ctx: Context) = File(ctx.filesDir, "news.json")

    fun load(ctx: Context): MutableList<Item> = synchronized(lock) {
        val f = file(ctx)
        if (!f.exists()) return mutableListOf()
        return try {
            val arr = JSONObject(f.readText()).optJSONArray("items") ?: JSONArray()
            (0 until arr.length()).map { Item.fromJson(arr.getJSONObject(it)) }.toMutableList()
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    private fun save(ctx: Context, items: List<Item>, updatedAt: Long?) {
        val old = if (updatedAt == null) lastUpdated(ctx) else updatedAt
        val root = JSONObject().put("updatedAt", old).put("items", JSONArray().apply { items.forEach { put(it.toJson()) } })
        val tmp = File(ctx.filesDir, "news.json.tmp")
        tmp.writeText(root.toString())
        tmp.renameTo(file(ctx))
    }

    fun lastUpdated(ctx: Context): Long = try {
        JSONObject(file(ctx).readText()).optLong("updatedAt")
    } catch (_: Exception) {
        0L
    }

    /** Unread items of enabled feeds, newest first. */
    fun unread(ctx: Context): List<Item> {
        val feeds = NewsConfig.load(ctx).feeds.map { it.id }.toSet()
        return load(ctx).filter { !it.read && it.feedId in feeds }.sortedByDescending { it.published }
    }

    fun markRead(ctx: Context, ids: Collection<String>): Unit = synchronized(lock) {
        val items = load(ctx)
        var changed = false
        items.forEach { if (it.id in ids && !it.read) { it.read = true; changed = true } }
        if (changed) save(ctx, items, null)
        Unit
    }

    /**
     * Downloads every enabled feed and merges new items.
     * Returns (newItems, firstRun) — or throws when no feed could be read.
     */
    fun fetchAll(ctx: Context): Pair<List<Item>, Boolean> {
        val cfg = NewsConfig.load(ctx)
        val fetched = mutableListOf<Item>()
        var okFeeds = 0
        for (feed in cfg.feeds) {
            try {
                fetched += fetchFeed(feed)
                okFeeds++
            } catch (_: Exception) {
            }
        }
        if (cfg.feeds.isNotEmpty() && okFeeds == 0) throw java.io.IOException("no feed reachable")
        synchronized(lock) {
            val items = load(ctx)
            val firstRun = items.isEmpty()
            val known = items.map { it.id }.toHashSet()
            val fresh = fetched.distinctBy { it.id }.filter { it.id !in known }
            items.addAll(fresh)
            val feedIds = cfg.feeds.map { it.id }.toSet()
            val now = System.currentTimeMillis()
            val kept = items
                .filter { it.feedId in feedIds || cfg.feeds.isEmpty() }
                .filter { !(it.read && now - it.published > KEEP_READ_MS) }
                .sortedByDescending { it.published }
                .take(MAX_ITEMS)
            save(ctx, kept, now)
            return fresh.sortedByDescending { it.published } to firstRun
        }
    }

    private fun fetchFeed(feed: FeedCfg): List<Item> {
        val conn = URL(feed.url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 20_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) NewsGlass/1.0")
        try {
            conn.inputStream.use { return parse(it, feed) }
        } finally {
            conn.disconnect()
        }
    }

    private val dateFormats = listOf(
        "EEE, dd MMM yyyy HH:mm:ss Z", "EEE, dd MMM yyyy HH:mm:ss zzz", "EEE, dd MMM yyyy HH:mm Z",
        "yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", "yyyy-MM-dd'T'HH:mm:ss'Z'",
    )

    private fun parseDate(s: String): Long {
        val t = s.trim()
        for (p in dateFormats) {
            try {
                return SimpleDateFormat(p, Locale.US).parse(t)!!.time
            } catch (_: Exception) {
            }
        }
        return System.currentTimeMillis()
    }

    private val imgRe = Regex("<img[^>]+src=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)

    @Suppress("DEPRECATION")
    private fun clean(html: String): String =
        Html.fromHtml(html.replace(Regex("<img[^>]*>", RegexOption.IGNORE_CASE), "")).toString()
            .replace('￼', ' ').replace(Regex("\\s+"), " ").trim()

    /** RSS 2.0 <item> and Atom <entry>. */
    private fun parse(input: java.io.InputStream, feed: FeedCfg): List<Item> {
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        p.setInput(input, null)
        val out = mutableListOf<Item>()
        var inItem = false
        var title = ""; var link = ""; var guid = ""; var desc = ""; var date = ""; var image = ""
        var tag: String? = null
        var event = p.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val name = p.name.lowercase()
                    if (name == "item" || name == "entry") {
                        inItem = true
                        title = ""; link = ""; guid = ""; desc = ""; date = ""; image = ""
                    } else if (inItem) {
                        tag = name
                        when (name) {
                            "link" -> p.getAttributeValue(null, "href")?.let { if (link.isEmpty()) link = it }
                            "enclosure", "media:content", "media:thumbnail" -> {
                                val url = p.getAttributeValue(null, "url")
                                val type = p.getAttributeValue(null, "type").orEmpty()
                                if (url != null && image.isEmpty() && (type.isEmpty() || type.startsWith("image"))) image = url
                            }
                        }
                    }
                }
                XmlPullParser.TEXT, XmlPullParser.CDSECT -> if (inItem) {
                    val text = p.text ?: ""
                    when (tag) {
                        "title" -> title += text
                        "link" -> link += text.trim()
                        "guid", "id" -> guid += text.trim()
                        "description", "summary", "content", "content:encoded" -> desc += text
                        "pubdate", "published", "updated", "dc:date" -> if (date.isEmpty()) date = text
                    }
                }
                XmlPullParser.END_TAG -> {
                    val name = p.name.lowercase()
                    if ((name == "item" || name == "entry") && inItem) {
                        inItem = false
                        if (image.isEmpty()) imgRe.find(desc)?.let { image = it.groupValues[1] }
                        val id = guid.ifEmpty { link }.ifEmpty { title }
                        if (title.isNotBlank() && id.isNotBlank()) {
                            out += Item(
                                id = id, title = clean(title), link = link, summary = clean(desc).take(300),
                                image = image, source = feed.name, feedId = feed.id,
                                published = if (date.isBlank()) System.currentTimeMillis() else parseDate(date),
                            )
                        }
                    }
                    tag = null
                }
            }
            event = p.next()
        }
        return out
    }
}
