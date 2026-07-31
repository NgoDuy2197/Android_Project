package com.example.cameraman

import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.camera.core.ImageCapture
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Decides where captures are written and lists them back for the gallery.
 *
 * Two locations are supported, chosen in the app's Config screen:
 *  - Default: the app's own external DCIM folder
 *    (`Android/data/<pkg>/files/DCIM/CameraMan`). Always writable, needs no
 *    runtime permission, and its files can be read directly by the Flutter UI.
 *  - Custom: a Storage-Access-Framework tree the user picked. Files there are
 *    addressed by `content://` uris and opened through the ContentResolver.
 *
 * Photos and videos live side by side in a single folder; the type is derived
 * from the extension (`.jpg` / `.mp4`).
 */
class MediaStorage(private val context: Context) {

    private val config = AppConfig(context)

    private val usesSaf: Boolean
        get() = config.saveTreeUri.isNotEmpty()

    private fun defaultDir(): File {
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_DCIM), "CameraMan")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun safTree(): DocumentFile? {
        val uri = config.saveTreeUri
        if (uri.isEmpty()) return null
        return DocumentFile.fromTreeUri(context, Uri.parse(uri))
    }

    /** Human-readable description of the current destination, for the UI. */
    fun describeLocation(): String {
        val tree = safTree()
        return if (tree != null) {
            "Thư mục đã chọn: ${tree.name ?: config.saveTreeUri}"
        } else {
            "Mặc định (bộ nhớ ứng dụng): ${defaultDir().absolutePath}"
        }
    }

    private fun stamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    // Motion-triggered captures get an "MO_" tag so the gallery can filter them.
    private fun photoName(motion: Boolean) = "CM_${if (motion) "MO_" else ""}${stamp()}.jpg"
    private fun videoName(motion: Boolean) = "CM_${if (motion) "MO_" else ""}${stamp()}.mp4"

    // --- Photo output -------------------------------------------------------

    /** Result of preparing a photo destination. */
    class PhotoTarget(
        val options: ImageCapture.OutputFileOptions,
        /** file path or content-uri string, resolved after capture. */
        val displayPath: String,
        val isVideo: Boolean = false,
    )

    fun preparePhoto(motion: Boolean = false): PhotoTarget {
        val tree = safTree()
        return if (tree != null) {
            val doc = tree.createFile("image/jpeg", photoName(motion))
                ?: throw IllegalStateException("Không tạo được tệp trong thư mục đã chọn")
            val out = context.contentResolver.openOutputStream(doc.uri)
                ?: throw IllegalStateException("Không mở được luồng ghi ảnh")
            PhotoTarget(
                ImageCapture.OutputFileOptions.Builder(out).build(),
                doc.uri.toString(),
            )
        } else {
            val file = File(defaultDir(), photoName(motion))
            PhotoTarget(
                ImageCapture.OutputFileOptions.Builder(file).build(),
                file.absolutePath,
            )
        }
    }

    // --- Video output -------------------------------------------------------

    /**
     * CameraX records to a plain [File]. For the default location that file is
     * the final destination; for a SAF folder we record to app cache first and
     * copy it in once recording stops (see [commitVideo]).
     */
    class VideoTarget(val recordInto: File, private val storage: MediaStorage) {
        /** Called after CameraX finalizes the file; returns the final path/uri. */
        fun commit(): String = storage.commitVideo(recordInto)
    }

    fun prepareVideo(motion: Boolean = false): VideoTarget {
        val file = if (usesSaf) {
            File(context.cacheDir, videoName(motion))
        } else {
            File(defaultDir(), videoName(motion))
        }
        return VideoTarget(file, this)
    }

    private fun commitVideo(temp: File): String {
        val tree = safTree() ?: return temp.absolutePath // already in final dir
        try {
            val doc = tree.createFile("video/mp4", temp.name)
                ?: return temp.absolutePath
            context.contentResolver.openOutputStream(doc.uri)?.use { out ->
                temp.inputStream().use { it.copyTo(out) }
            }
            temp.delete()
            return doc.uri.toString()
        } catch (e: Exception) {
            // Fall back to leaving the clip in cache rather than losing it.
            return temp.absolutePath
        }
    }

    // --- Listing / deletion -------------------------------------------------

    /** All captures, newest first, as plain maps for the MethodChannel. */
    fun listMedia(): List<Map<String, Any?>> {
        val tree = safTree()
        val items = if (tree != null) {
            tree.listFiles().mapNotNull { doc ->
                val name = doc.name ?: return@mapNotNull null
                if (!name.startsWith("CM_")) return@mapNotNull null
                mapOf(
                    "name" to name,
                    "type" to typeOf(name),
                    "time" to doc.lastModified(),
                    "size" to doc.length(),
                    "path" to null,
                    "uri" to doc.uri.toString(),
                )
            }
        } else {
            (defaultDir().listFiles() ?: emptyArray()).mapNotNull { file ->
                if (!file.isFile || !file.name.startsWith("CM_")) return@mapNotNull null
                mapOf(
                    "name" to file.name,
                    "type" to typeOf(file.name),
                    "time" to file.lastModified(),
                    "size" to file.length(),
                    "path" to file.absolutePath,
                    "uri" to Uri.fromFile(file).toString(),
                )
            }
        }
        return items.sortedByDescending { it["time"] as Long }
    }

    /** Deletes every capture (default folder + SAF). Returns the count removed. */
    fun wipeAll(): Int {
        var removed = 0
        for (item in listMedia()) {
            val ok = deleteMedia(item["uri"] as String? ?: "", item["path"] as String?)
            if (ok) removed++
        }
        return removed
    }

    fun deleteMedia(uri: String, path: String?): Boolean {
        return try {
            if (path != null) {
                File(path).delete()
            } else {
                DocumentFile.fromSingleUri(context, Uri.parse(uri))?.delete() ?: false
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun typeOf(name: String) =
        if (name.endsWith(".mp4", true)) "video" else "photo"
}
