package com.dsoft.dailydiary

import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Zip layout == on-disk layout:
 *
 *   manifest.json
 *   2026/10/24/entries.json
 *   2026/10/24/IMG_083015_ab12.jpg
 *   2026/10/24/NhatKy_2026-10-24.pdf
 */
object Backup {
    private const val MANIFEST = "manifest.json"
    private val DAY_PATH = Regex("""^\d{4}/\d{2}/\d{2}/[^/.][^/]*$""")

    data class Result(val days: Int, val files: Int)

    fun export(out: OutputStream): Result {
        val root = DiaryStore.root
        var files = 0
        val days = HashSet<String>()
        ZipOutputStream(out.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(MANIFEST))
            zip.write(
                JSONObject().put("app", "com.dsoft.dailydiary").put("format", 1)
                    .put("createdAt", System.currentTimeMillis()).toString(2).toByteArray()
            )
            zip.closeEntry()
            root.walkTopDown().filter { it.isFile && !it.name.endsWith(".tmp") }.forEach { f ->
                val rel = f.relativeTo(root).invariantSeparatorsPath
                if (!DAY_PATH.matches(rel)) return@forEach
                zip.putNextEntry(ZipEntry(rel).apply { time = f.lastModified() })
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
                files++
                days += rel.substringBeforeLast('/')
            }
        }
        return Result(days.size, files)
    }

    fun exportToFile(dest: File): Result = dest.outputStream().use { export(it) }

    /**
     * @param replace true → wipe the current diary first.
     *                false → merge: events are joined by id, existing files are kept.
     */
    fun import(input: InputStream, replace: Boolean): Result {
        val root = DiaryStore.root
        val canonicalRoot = root.canonicalPath + File.separator
        // Unpack into a staging folder first so a corrupt zip never leaves the diary half-replaced.
        val staging = File(root.parentFile, "restore_staging").apply { deleteRecursively(); mkdirs() }
        var sawManifest = false
        try {
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    val name = e.name.replace('\\', '/')
                    if (name == MANIFEST) sawManifest = true
                    if (e.isDirectory || !DAY_PATH.matches(name)) continue
                    val target = File(staging, name)
                    // Zip-slip guard (regex already forbids "..", this is belt and braces).
                    if (!target.canonicalPath.startsWith(staging.canonicalPath + File.separator)) continue
                    target.parentFile?.mkdirs()
                    target.outputStream().use { zip.copyTo(it) }
                    if (e.time > 0) target.setLastModified(e.time)
                }
            }
            require(sawManifest) { "File ZIP không phải bản sao lưu Nhật ký" }

            if (replace) root.listFiles()?.forEach { it.deleteRecursively() }

            var files = 0
            val days = HashSet<String>()
            staging.walkTopDown().filter { it.isFile }.forEach { src ->
                val rel = src.relativeTo(staging).invariantSeparatorsPath
                val dest = File(root, rel)
                if (!dest.canonicalPath.startsWith(canonicalRoot)) return@forEach
                dest.parentFile?.mkdirs()
                when {
                    src.name == DiaryStore.ENTRIES && dest.exists() -> {
                        val merged = (DiaryStore.parseEntries(dest.readText()) + DiaryStore.parseEntries(src.readText()))
                            .associateBy { it.id }.values.toList()
                        dest.writeText(DiaryStore.serializeEntries(merged))
                    }
                    dest.exists() -> return@forEach
                    else -> src.copyTo(dest).setLastModified(src.lastModified())
                }
                files++
                days += rel.substringBeforeLast('/')
            }
            return Result(days.size, files)
        } finally {
            staging.deleteRecursively()
        }
    }
}
