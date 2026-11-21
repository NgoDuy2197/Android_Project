package com.dsoft.screengrab.net

import java.util.concurrent.CopyOnWriteArrayList

/** One video/audio stream the sniffer saw an app or browser load. */
class MediaItem(
    val id: Long,
    val url: String,
    val kind: Kind,
    val host: String,
    var title: String,
    var contentType: String,
    var sizeBytes: Long,          // -1 if unknown
    /** Request headers worth replaying when downloading (referer / cookie / user-agent / origin). */
    val headers: Map<String, String>,
    val tsAdded: Long = System.currentTimeMillis(),
) {
    enum class Kind { HLS, DASH, FILE }

    @Volatile var state: State = State.IDLE
    @Volatile var progress: Int = 0          // 0..100, -1 = indeterminate
    @Volatile var detail: String = ""        // "12/340 đoạn" / error text / saved path
    @Volatile var savedUri: String? = null

    enum class State { IDLE, DOWNLOADING, DONE, ERROR }

    val label: String get() = when (kind) {
        Kind.HLS -> "HLS"; Kind.DASH -> "DASH"; Kind.FILE -> "FILE"
    }
}

/** Shared, in-memory list of detected media. De-duplicated by URL. */
object MediaCatalog {
    private val items = CopyOnWriteArrayList<MediaItem>()
    private val seen = java.util.Collections.synchronizedSet(HashSet<String>())
    private var nextId = 1L

    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }
    fun notifyChanged() { for (l in listeners) runCatching { l() } }

    @Synchronized
    fun offer(url: String, kind: MediaItem.Kind, host: String, title: String,
              contentType: String, size: Long, headers: Map<String, String>): MediaItem? {
        val key = canonical(url)
        if (!seen.add(key)) return null          // already have it
        val item = MediaItem(nextId++, url, kind, host, title, contentType, size, headers)
        items.add(0, item)                        // newest first
        notifyChanged()
        return item
    }

    fun all(): List<MediaItem> = items.toList()
    fun byId(id: Long): MediaItem? = items.firstOrNull { it.id == id }

    @Synchronized
    fun clear() { items.clear(); seen.clear(); notifyChanged() }

    /** Strip volatile query bits that differ per playback token but point at the same asset. */
    private fun canonical(url: String): String {
        val q = url.indexOf('?')
        return if (q < 0) url else url.substring(0, q)
    }
}
