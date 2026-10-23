package com.dsoft.proxyspy

import java.util.concurrent.CopyOnWriteArrayList

class Body(
    val kind: String,          // "text" | "binary"
    val text: String?,
    val base64: String?,
    val size: Int,
    val truncated: Boolean,
    val contentType: String,
)

class Flow(
    val id: Long,
    @Volatile var time: Long,
    val method: String,
    val scheme: String,
    val host: String,
    val path: String,
    val url: String,
    @Volatile var reqHeaders: List<Pair<String, String>> = emptyList(),
    @Volatile var reqBody: Body? = null,
    @Volatile var status: Int = -1,
    @Volatile var reason: String = "",
    @Volatile var resHeaders: List<Pair<String, String>> = emptyList(),
    @Volatile var resBody: Body? = null,
    @Volatile var durationMs: Long = -1,
    @Volatile var error: String? = null,
) {
    val pending get() = status < 0 && error == null
    val resType get() = (resBody?.contentType ?: "").substringBefore(';').substringAfter('/')
    val size get() = (resBody?.size ?: reqBody?.size ?: 0)
}

/** In-memory ring buffer of captured flows, observed by the UI. */
object FlowStore {
    private val lock = Any()
    private val list = ArrayList<Flow>()
    private var seq = 0L
    var maxFlows = 3000
    @Volatile var paused = false
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun newId(): Long = synchronized(lock) { ++seq }

    fun add(f: Flow) {
        if (paused) return
        synchronized(lock) {
            list.add(f)
            while (list.size > maxFlows) list.removeAt(0)
        }
        notifyChanged()
    }

    /** Fields of [f] were mutated in place (response arrived); just notify observers. */
    fun touched() = notifyChanged()

    fun all(): List<Flow> = synchronized(lock) { ArrayList(list) }
    fun get(id: Long): Flow? = synchronized(lock) { list.firstOrNull { it.id == id } }
    fun clear() { synchronized(lock) { list.clear() }; notifyChanged() }

    fun addListener(l: () -> Unit) = listeners.add(l)
    fun removeListener(l: () -> Unit) = listeners.remove(l)
    private fun notifyChanged() = App.main.post { listeners.forEach { runCatching { it() } } }
}
