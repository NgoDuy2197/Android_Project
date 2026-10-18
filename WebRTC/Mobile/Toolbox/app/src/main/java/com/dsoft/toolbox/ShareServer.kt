package com.dsoft.toolbox

import android.content.Context
import android.net.Uri
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PushbackInputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * A tiny "share board" web server. Other devices open the link, see a list of
 * items; each item has a huge text box and attachments.
 *
 * Text lives in the host's memory. Files are NOT stored on the host: when a
 * browser attaches a file it keeps the bytes and, on download, streams them
 * through the server straight to the downloader (a relay — the host never
 * writes the file). Files the host itself attaches are streamed from disk.
 */
class ShareServer(
    private val appCtx: Context,
    private val port: Int,
    private val onEvent: (String) -> Unit,
) {
    // ---- shared state ----
    class FileRef(
        val id: String,
        val name: String,
        val size: Long,
        /** Browser clientId that owns the bytes, or "host". */
        val owner: String,
        /** For host files: content Uri to stream from disk. */
        val hostUri: String? = null,
    )

    class Item(val id: String) {
        @Volatile var title: String = "Mục mới"
        @Volatile var text: String = ""
        val files = ArrayList<FileRef>()
    }

    private val items = ConcurrentHashMap<String, Item>()
    private val order = ArrayList<String>()
    private val seq = AtomicLong(1)

    // Relay rendezvous -------------------------------------------------------
    private class Provide(val input: InputStream, val length: Long)
    private class PendingDownload {
        val handoff = SynchronousQueue<Provide>()
        val done = CountDownLatch(1)
    }
    /** fileId -> the download waiting for its owner browser to provide bytes. */
    private val pending = ConcurrentHashMap<String, PendingDownload>()
    /** clientId -> fileIds its browser must now upload (woken long-poll). */
    private val work = ConcurrentHashMap<String, java.util.concurrent.LinkedBlockingQueue<String>>()

    @Volatile private var server: ServerSocket? = null

    val isRunning: Boolean get() = server != null

    fun start() {
        val s = ServerSocket()
        s.reuseAddress = true
        s.bind(java.net.InetSocketAddress(port))
        server = s
        thread(name = "share-accept", isDaemon = true) {
            while (!s.isClosed) {
                val c = try { s.accept() } catch (_: IOException) { break }
                thread(isDaemon = true) { runCatching { handle(c) }; runCatching { c.close() } }
            }
        }
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
    }

    // ---- host-side API (from the activity) ----
    fun addItem(): Item {
        val it = Item("i${seq.getAndIncrement()}")
        items[it.id] = it
        synchronized(order) { order.add(it.id) }
        return it
    }

    fun addHostFile(itemId: String, uri: Uri, name: String, size: Long) {
        val it = items[itemId] ?: return
        val f = FileRef("f${seq.getAndIncrement()}", name, size, "host", uri.toString())
        synchronized(it.files) { it.files.add(f) }
        onEvent("Đã chia sẻ tệp: $name")
    }

    fun itemsSnapshot(): List<Item> = synchronized(order) { order.mapNotNull { items[it] } }

    // ---- request handling ----
    private fun handle(sock: Socket) {
        sock.soTimeout = 60_000
        val input = PushbackInputStream(sock.getInputStream(), 8192)
        val out = BufferedOutputStream(sock.getOutputStream())
        val reqLine = readLine(input) ?: return
        val parts = reqLine.split(' ')
        if (parts.size < 2) return
        val method = parts[0]
        val rawPath = parts[1]
        val headers = HashMap<String, String>()
        while (true) {
            val h = readLine(input) ?: break
            if (h.isEmpty()) break
            val i = h.indexOf(':')
            if (i > 0) headers[h.substring(0, i).trim().lowercase()] = h.substring(i + 1).trim()
        }
        val path = rawPath.substringBefore('?')
        val query = rawPath.substringAfter('?', "")
        val len = headers["content-length"]?.toLongOrNull() ?: 0L

        try {
            when {
                path == "/" || path == "/index.html" -> serveAsset(out, "share.html", "text/html; charset=utf-8")
                path == "/app.js" -> serveAsset(out, "share.js", "application/javascript; charset=utf-8")
                path == "/api/state" -> sendJson(out, stateJson())
                path == "/api/item" && method == "POST" -> {
                    body(input, len)
                    val it = addItem()
                    sendJson(out, "{\"id\":\"${it.id}\"}")
                    onEvent("Có mục mới")
                }
                path.startsWith("/api/item/") -> apiItem(method, path, input, len, out)
                path.startsWith("/api/file/") && method == "DELETE" -> {
                    val fid = path.removePrefix("/api/file/")
                    removeFile(fid)
                    sendText(out, 200, "ok")
                }
                path == "/api/wait" -> apiWait(query, out)
                path.startsWith("/api/provide/") && method == "POST" -> apiProvide(path.removePrefix("/api/provide/"), input, len, out)
                path.startsWith("/download/") -> apiDownload(path.removePrefix("/download/"), query, out)
                else -> sendText(out, 404, "Not found")
            }
        } catch (_: IOException) {
        }
    }

    private fun apiItem(method: String, path: String, input: InputStream, len: Long, out: OutputStream) {
        // /api/item/{id}/(text|title|attach) or DELETE /api/item/{id}
        val rest = path.removePrefix("/api/item/")
        val id = rest.substringBefore('/')
        val action = rest.substringAfter('/', "")
        val it = items[id]
        if (it == null) {
            if (method == "DELETE") body(input, len)
            return sendText(out, 404, "no item")
        }
        when {
            method == "DELETE" -> {
                body(input, len)
                items.remove(id)
                synchronized(order) { order.remove(id) }
                sendText(out, 200, "ok")
                onEvent("Đã xoá một mục")
            }
            action == "text" -> { it.text = String(body(input, len), Charsets.UTF_8); sendText(out, 200, "ok") }
            action == "title" -> { it.title = String(body(input, len), Charsets.UTF_8).take(200); sendText(out, 200, "ok") }
            action == "attach" -> {
                // Body: name\nsize\nowner  (browser registers a file it holds)
                val meta = String(body(input, len), Charsets.UTF_8).split('\n')
                if (meta.size < 3) return sendText(out, 400, "bad")
                val f = FileRef("f${seq.getAndIncrement()}", meta[0].take(200), meta[1].toLongOrNull() ?: 0, meta[2])
                synchronized(it.files) { it.files.add(f) }
                sendJson(out, "{\"id\":\"${f.id}\"}")
                onEvent("Có tệp được chia sẻ: ${f.name}")
            }
            else -> sendText(out, 404, "?")
        }
    }

    /** Long-poll: the owner browser waits here for a fileId it must upload. */
    private fun apiWait(query: String, out: OutputStream) {
        val client = param(query, "client") ?: return sendText(out, 400, "no client")
        val q = work.getOrPut(client) { java.util.concurrent.LinkedBlockingQueue() }
        val fid = q.poll(25, TimeUnit.SECONDS)
        if (fid == null) sendText(out, 204, "") else sendJson(out, "{\"fileId\":\"$fid\"}")
    }

    /** The owner browser streams the bytes here; piped into the waiting download. */
    private fun apiProvide(fileId: String, input: InputStream, len: Long, out: OutputStream) {
        val pd = pending[fileId] ?: return sendText(out, 409, "no download waiting")
        // Hand our body stream to the download thread, then wait until it finished copying.
        pd.handoff.offer(Provide(input, len), 10, TimeUnit.SECONDS)
        pd.done.await(5, TimeUnit.MINUTES)
        drain(input, len) // consume anything unread so the socket stays sane
        sendText(out, 200, "sent")
    }

    private fun apiDownload(fileId: String, query: String, out: OutputStream) {
        val ref = findFile(fileId) ?: return sendText(out, 404, "file gone")
        if (ref.owner == "host" && ref.hostUri != null) {
            streamHostFile(ref, out)
            return
        }
        // Relay: ask the owner's browser to upload, then pipe it through.
        val pd = PendingDownload()
        pending[fileId] = pd
        try {
            work.getOrPut(ref.owner) { java.util.concurrent.LinkedBlockingQueue() }.offer(fileId)
            val provide = pd.handoff.poll(40, TimeUnit.SECONDS)
            if (provide == null) {
                sendText(out, 504, "Người gửi không phản hồi (mở lại trang của họ)")
                return
            }
            sendFileHeaders(out, ref.name, provide.length)
            copy(provide.input, out, provide.length)
            out.flush()
        } finally {
            pd.done.countDown()
            pending.remove(fileId)
        }
    }

    private fun streamHostFile(ref: FileRef, out: OutputStream) {
        val uri = Uri.parse(ref.hostUri)
        val stream = runCatching { appCtx.contentResolver.openInputStream(uri) }.getOrNull()
        if (stream == null) { sendText(out, 410, "Tệp không còn trên máy chủ"); return }
        stream.use {
            sendFileHeaders(out, ref.name, ref.size)
            copy(it, out, ref.size)
            out.flush()
        }
        onEvent("Đã gửi tệp: ${ref.name}")
    }

    // ---- helpers ----
    private fun findFile(fid: String): FileRef? {
        for (it in items.values) synchronized(it.files) { it.files.firstOrNull { f -> f.id == fid }?.let { return it } }
        return null
    }

    private fun removeFile(fid: String) {
        for (it in items.values) synchronized(it.files) { it.files.removeAll { f -> f.id == fid } }
    }

    private fun stateJson(): String {
        val sb = StringBuilder("{\"items\":[")
        val snap = itemsSnapshot()
        snap.forEachIndexed { i, it ->
            if (i > 0) sb.append(',')
            sb.append("{\"id\":\"").append(it.id).append("\",\"title\":").append(jstr(it.title))
                .append(",\"text\":").append(jstr(it.text)).append(",\"files\":[")
            val fs = synchronized(it.files) { ArrayList(it.files) }
            fs.forEachIndexed { j, f ->
                if (j > 0) sb.append(',')
                sb.append("{\"id\":\"").append(f.id).append("\",\"name\":").append(jstr(f.name))
                    .append(",\"size\":").append(f.size).append(",\"host\":").append(f.owner == "host").append('}')
            }
            sb.append("]}")
        }
        return sb.append("]}").toString()
    }

    private fun jstr(s: String): String {
        val b = StringBuilder("\"")
        for (c in s) when (c) {
            '"' -> b.append("\\\""); '\\' -> b.append("\\\\"); '\n' -> b.append("\\n"); '\r' -> b.append("\\r"); '\t' -> b.append("\\t")
            else -> if (c < ' ') b.append("\\u%04x".format(c.code)) else b.append(c)
        }
        return b.append('"').toString()
    }

    private fun param(query: String, key: String): String? = query.split('&')
        .map { it.split('=', limit = 2) }.firstOrNull { it[0] == key }
        ?.let { if (it.size > 1) URLDecoder.decode(it[1], "UTF-8") else "" }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
        }
    }

    private fun body(input: InputStream, len: Long): ByteArray {
        if (len <= 0) return ByteArray(0)
        val buf = ByteArray(len.toInt().coerceAtMost(16 * 1024 * 1024))
        var read = 0
        while (read < buf.size) {
            val n = input.read(buf, read, buf.size - read)
            if (n < 0) break
            read += n
        }
        return if (read == buf.size) buf else buf.copyOf(read)
    }

    private fun drain(input: InputStream, len: Long) {
        var left = len
        val buf = ByteArray(8192)
        while (left > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) break
            left -= n
        }
    }

    private fun copy(input: InputStream, out: OutputStream, len: Long) {
        var left = len
        val buf = ByteArray(64 * 1024)
        while (left > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) break
            out.write(buf, 0, n)
            left -= n
        }
    }

    private fun serveAsset(out: OutputStream, name: String, type: String) {
        val bytes = runCatching { appCtx.assets.open(name).use { it.readBytes() } }.getOrNull()
        if (bytes == null) return sendText(out, 404, "missing $name")
        val head = "HTTP/1.1 200 OK\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        out.write(head.toByteArray()); out.write(bytes); out.flush()
    }

    private fun sendJson(out: OutputStream, json: String) {
        val b = json.toByteArray(Charsets.UTF_8)
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: ${b.size}\r\nConnection: close\r\n\r\n").toByteArray())
        out.write(b); out.flush()
    }

    private fun sendText(out: OutputStream, code: Int, msg: String) {
        val b = msg.toByteArray(Charsets.UTF_8)
        out.write(("HTTP/1.1 $code OK\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: ${b.size}\r\nConnection: close\r\n\r\n").toByteArray())
        out.write(b); out.flush()
    }

    private fun sendFileHeaders(out: OutputStream, name: String, length: Long) {
        val enc = java.net.URLEncoder.encode(name, "UTF-8").replace("+", "%20")
        val head = StringBuilder("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\n")
        if (length > 0) head.append("Content-Length: $length\r\n")
        head.append("Content-Disposition: attachment; filename*=UTF-8''$enc\r\nConnection: close\r\n\r\n")
        out.write(head.toString().toByteArray())
    }
}
