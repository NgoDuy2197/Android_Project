package com.dsoft.bookshelf

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.UUID

object BookType {
    const val PDF = "pdf"
    const val TEXT = "text"
    const val AUDIO = "audio"
}

data class Book(
    val id: String,
    val title: String,
    val type: String,
    val addedAt: Long,
    val openedAt: Long = 0,
    val customCover: Boolean = false,
    /** For a PDF: id of the TEXT book produced by "extract text" (used by TTS / translate / MP3). */
    val textBookId: String = "",
    /** For derived books: the book they came from. */
    val sourceId: String = "",
    /** Short corner badge, e.g. target-lang "VI" for a translation (type badge PDF/TXT/MP3 is implicit). */
    val tag: String = "",
    /** Audio book: path + optional MediaStore uri of the MP3. */
    val audioPath: String = "",
    val audioUri: String = "",
    /** Original import format of a TEXT book that had no real cover: "epub" | "mobi" (else ""). */
    val format: String = "",
) {
    val isPdf get() = type == BookType.PDF
    val isAudio get() = type == BookType.AUDIO
    val isText get() = type == BookType.TEXT
    /** Badge shown at the bottom-left corner of the cover. */
    val typeBadge get() = when {
        type == BookType.PDF -> "PDF"
        type == BookType.AUDIO -> "MP3"
        format.isNotEmpty() -> format.uppercase()   // EPUB / MOBI instead of a generic TXT
        else -> "TXT"
    }

    fun toJson() = JSONObject().put("id", id).put("title", title).put("type", type).put("addedAt", addedAt)
        .put("openedAt", openedAt).put("customCover", customCover).put("textBookId", textBookId).put("sourceId", sourceId)
        .put("tag", tag).put("audioPath", audioPath).put("audioUri", audioUri).put("format", format)

    companion object {
        fun fromJson(o: JSONObject) = Book(
            o.getString("id"), o.optString("title"), o.optString("type", BookType.TEXT), o.optLong("addedAt"),
            o.optLong("openedAt"), o.optBoolean("customCover"), o.optString("textBookId"), o.optString("sourceId"),
            o.optString("tag"), o.optString("audioPath"), o.optString("audioUri"), o.optString("format"),
        )
    }
}

/** Per-book reading state (state.json). */
data class BookState(
    val pdfPage: Int = 0,
    val pdfFrac: Float = 0f,
    val pages: Int = 0,
    val textOffset: Int = 0,
    val textLength: Int = 0,
    val ttsOffset: Int = 0,
    val progress: Float = 0f,
    val updatedAt: Long = 0,
) {
    fun toJson() = JSONObject().put("pdfPage", pdfPage).put("pdfFrac", pdfFrac.toDouble()).put("pages", pages)
        .put("textOffset", textOffset).put("textLength", textLength).put("ttsOffset", ttsOffset)
        .put("progress", progress.toDouble()).put("updatedAt", updatedAt)

    companion object {
        fun fromJson(o: JSONObject) = BookState(
            o.optInt("pdfPage"), o.optDouble("pdfFrac", 0.0).toFloat(), o.optInt("pages"), o.optInt("textOffset"),
            o.optInt("textLength"), o.optInt("ttsOffset"), o.optDouble("progress", 0.0).toFloat(), o.optLong("updatedAt"),
        )
    }
}

/** Crash-safe file helpers: write to .tmp, fsync, rename over the target. */
object Files {
    fun writeAtomic(f: File, data: ByteArray) {
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, f.name + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(data)
            out.flush()
            try { out.fd.sync() } catch (_: Exception) {}
        }
        if (!tmp.renameTo(f)) {
            f.delete()
            if (!tmp.renameTo(f)) throw java.io.IOException("rename failed: $f")
        }
    }

    fun writeAtomic(f: File, s: String) = writeAtomic(f, s.toByteArray(Charsets.UTF_8))

    fun readJson(f: File): JSONObject? {
        for (cand in listOf(f, File(f.parentFile, f.name + ".tmp"))) {
            try {
                if (cand.exists()) return JSONObject(cand.readText())
            } catch (_: Exception) {}
        }
        return null
    }

    /** Truncate (or keep) [f] to exactly [len] bytes - used when resuming a job. */
    fun truncate(f: File, len: Long) {
        if (!f.exists()) { f.parentFile?.mkdirs(); f.createNewFile() }
        if (f.length() != len) RandomAccessFile(f, "rw").use { it.setLength(len.coerceAtMost(it.length())) }
    }

    /** Append and fsync, returning the new length. */
    fun append(f: File, data: ByteArray): Long {
        FileOutputStream(f, true).use { out ->
            out.write(data)
            out.flush()
            try { out.fd.sync() } catch (_: Exception) {}
        }
        return f.length()
    }

    /** Reads a text file, honouring UTF-8/UTF-16 BOMs; non-UTF-8 files fall back to UTF-16 / windows-1258. */
    fun readText(f: File): String = decode(f.readBytes())

    fun decode(bytes: ByteArray): String {
        return when {
            bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() ->
                String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
            looksUtf16Le(bytes) -> String(bytes, Charsets.UTF_16LE)
            isUtf8(bytes) -> String(bytes, Charsets.UTF_8)
            else -> try { String(bytes, java.nio.charset.Charset.forName("windows-1258")) } catch (_: Exception) { String(bytes, Charsets.ISO_8859_1) }
        }.replace("\r\n", "\n").replace('\r', '\n')
    }

    private fun isUtf8(bytes: ByteArray): Boolean = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes, 0, utf8SafeLength(bytes)))
        true
    } catch (_: java.nio.charset.CharacterCodingException) { false }

    /** Ignores a multi-byte UTF-8 sequence cut off at the very end (a file still being appended to). */
    private fun utf8SafeLength(b: ByteArray): Int {
        var i = b.size - 1
        var cont = 0
        while (i >= 0 && cont < 3 && (b[i].toInt() and 0xC0) == 0x80) { i--; cont++ }
        if (i < 0) return b.size
        val lead = b[i].toInt() and 0xFF
        val need = when { lead >= 0xF0 -> 3; lead >= 0xE0 -> 2; lead >= 0xC0 -> 1; else -> 0 }
        // only tolerate the cut when the rest already contains multi-byte UTF-8 (i.e. it is clearly a UTF-8 file)
        return if (need > cont && (0 until i).any { b[it] < 0 }) i else b.size
    }

    private fun looksUtf16Le(b: ByteArray): Boolean {
        if (b.size < 64) return false
        val n = minOf(b.size, 2000)
        var zeros = 0
        for (i in 1 until n step 2) if (b[i].toInt() == 0) zeros++
        return zeros > n / 4
    }
}

/**
 * The library index (library.json) + per-book folders `filesDir/books/<id>/` holding
 * `book.pdf|book.txt`, `cover.png`, `custom_cover.jpg` and `state.json`.
 */
object Library {
    private lateinit var root: File
    private val books = LinkedHashMap<String, Book>()
    private val states = HashMap<String, BookState>()
    private val listeners = ArrayList<() -> Unit>()

    @Synchronized
    fun init(ctx: Context) {
        if (::root.isInitialized) return
        root = ctx.applicationContext.filesDir
        File(root, "books").mkdirs()
        val o = Files.readJson(File(root, "library.json"))
        val arr = o?.optJSONArray("books") ?: JSONArray()
        for (i in 0 until arr.length()) {
            try {
                val b = Book.fromJson(arr.getJSONObject(i))
                if (dir(b.id).exists()) books[b.id] = b
            } catch (_: Exception) {}
        }
    }

    fun dir(id: String) = File(File(root, "books"), id)
    fun file(b: Book) = File(dir(b.id), if (b.isPdf) "book.pdf" else "book.txt")
    fun coverFile(id: String) = File(dir(id), "cover.png")
    fun coverFailFile(id: String) = File(dir(id), "cover.fail")
    fun customCoverFile(id: String) = File(dir(id), "custom_cover.jpg")

    @Synchronized fun all(): List<Book> = books.values.toList()
    @Synchronized fun get(id: String): Book? = books[id]

    fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(16)

    @Synchronized
    fun add(b: Book) {
        dir(b.id).mkdirs()
        books[b.id] = b
        save()
    }

    @Synchronized
    fun update(id: String, f: (Book) -> Book): Book? {
        val b = books[id] ?: return null
        val nb = f(b)
        books[id] = nb
        save()
        return nb
    }

    @Synchronized
    fun delete(id: String) {
        books.remove(id)
        states.remove(id)
        books.values.filter { it.textBookId == id }.forEach { books[it.id] = it.copy(textBookId = "") }
        save()
        dir(id).deleteRecursively()
    }

    @Synchronized
    private fun save() {
        val arr = JSONArray()
        books.values.forEach { arr.put(it.toJson()) }
        Files.writeAtomic(File(root, "library.json"), JSONObject().put("books", arr).toString())
        notifyChanged()
    }

    @Synchronized
    fun state(id: String): BookState = states.getOrPut(id) {
        Files.readJson(File(dir(id), "state.json"))?.let { BookState.fromJson(it) } ?: BookState()
    }

    @Synchronized
    fun saveState(id: String, f: (BookState) -> BookState) {
        if (!books.containsKey(id)) return
        val s = f(state(id)).copy(updatedAt = System.currentTimeMillis())
        states[id] = s
        try { Files.writeAtomic(File(dir(id), "state.json"), s.toJson().toString()) } catch (_: Exception) {}
    }

    /** Book whose text is used for TTS / translation / MP3: the TEXT book itself, or a PDF's extracted text book. */
    fun textSourceOf(b: Book): Book? = when {
        b.isAudio -> null
        !b.isPdf -> b
        else -> get(b.textBookId)?.takeIf { file(it).exists() }
    }

    /** The book whose cover a derived book (extract / translate / MP3) inherits: follows sourceId to the root. */
    fun coverSource(b: Book): Book {
        var cur = b
        var guard = 0
        val seen = HashSet<String>()
        while (cur.sourceId.isNotEmpty() && guard++ < 8 && seen.add(cur.id)) {
            cur = get(cur.sourceId) ?: break
        }
        return cur
    }

    fun addListener(l: () -> Unit) = synchronized(listeners) { listeners += l }
    fun removeListener(l: () -> Unit) = synchronized(listeners) { listeners -= l }
    private fun notifyChanged() {
        val ls = synchronized(listeners) { listeners.toList() }
        App.main.post { ls.forEach { it() } }
    }

    /** Creates an empty TEXT book (output of extract / translate jobs). */
    fun createTextBook(title: String, sourceId: String, tag: String = ""): Book {
        val b = Book(newId(), title, BookType.TEXT, System.currentTimeMillis(), sourceId = sourceId, tag = tag)
        add(b)
        file(b).createNewFile()
        return b
    }

    /** Creates an AUDIO book (output of the MP3 job) that appears on the shelf with a disc cover. */
    fun createAudioBook(title: String, sourceId: String, path: String, uri: String): Book {
        val b = Book(newId(), title, BookType.AUDIO, System.currentTimeMillis(), sourceId = sourceId,
            audioPath = path, audioUri = uri, tag = "MP3")
        add(b)
        return b
    }
}
