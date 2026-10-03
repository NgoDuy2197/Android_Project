package com.dsoft.voicenote

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Downloads + unpacks one Vosk model zip once, and locates its root directory.
 * Each URL gets its own folder, so switching language never re-downloads.
 */
class ModelManager(filesDir: File, private val cacheDir: File, private val url: String) {
    private val modelsDir = File(filesDir, "models")
    private val name = url.substringBefore('?').substringAfterLast('/').removeSuffix(".zip").ifBlank { "model" }
    private val root = File(modelsDir, name)
    private val marker = File(root, ".ready")

    init {
        File(filesDir, "model").deleteRecursively() // layout of v1.0 (single model)
    }

    private fun isReady() = marker.exists() && runCatching { marker.readText() }.getOrNull() == url

    /** Forces a fresh download next time (used when the model fails to load). */
    fun invalidate() {
        marker.delete()
    }

    /** Returns the model directory, downloading it first if needed. */
    fun ensure(progress: (String) -> Unit): File {
        if (!isReady()) download(progress)
        return findModelDir(root) ?: throw IOException("Model không hợp lệ trong $root")
    }

    private fun download(progress: (String) -> Unit) {
        modelsDir.mkdirs()
        val zip = File(cacheDir, "$name.zip.part")
        val tmp = File(modelsDir, "$name.tmp")
        tmp.deleteRecursively()
        progress("Đang tải model…")
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
            }
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                FileOutputStream(zip).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var lastPct = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val pct = if (total > 0) (done * 100 / total).toInt() else (done shr 20).toInt()
                        if (pct != lastPct) {
                            lastPct = pct
                            progress(if (total > 0) "Đang tải model… $pct%" else "Đang tải model… $pct MB")
                        }
                    }
                }
            }
            progress("Đang giải nén model…")
            unzip(zip, tmp)
            if (findModelDir(tmp) == null) throw IOException("File zip không chứa model Vosk")
            root.deleteRecursively()
            if (!tmp.renameTo(root)) throw IOException("Không đổi tên được thư mục model")
            marker.writeText(url)
        } finally {
            conn?.disconnect()
            zip.delete()
            tmp.deleteRecursively()
        }
    }

    private fun unzip(zip: File, dest: File) {
        val destPath = dest.canonicalPath + File.separator
        ZipInputStream(zip.inputStream().buffered()).use { zin ->
            while (true) {
                val e = zin.nextEntry ?: break
                val out = File(dest, e.name)
                if (!out.canonicalPath.startsWith(destPath)) throw IOException("Zip entry ngoài thư mục: ${e.name}")
                if (e.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    FileOutputStream(out).use { zin.copyTo(it) }
                }
            }
        }
    }

    /** Model root = folder containing "am" or "conf" (zips usually wrap it in one folder). */
    private fun findModelDir(dir: File, depth: Int = 0): File? {
        if (!dir.isDirectory) return null
        if (File(dir, "am").isDirectory || File(dir, "conf").isDirectory) return dir
        if (depth >= 2) return null
        return dir.listFiles()?.filter { it.isDirectory }?.firstNotNullOfOrNull { findModelDir(it, depth + 1) }
    }
}
