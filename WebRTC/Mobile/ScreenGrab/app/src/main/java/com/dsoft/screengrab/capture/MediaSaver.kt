package com.dsoft.screengrab.capture

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.dsoft.screengrab.Log2
import java.io.File

/** Saves screenshots and recordings into the gallery (MediaStore on API 29+, else public dirs). */
object MediaSaver {

    private fun stamp(): String =
        android.text.format.DateFormat.format("yyyyMMdd_HHmmss", System.currentTimeMillis()).toString()

    /** Save a PNG screenshot; returns its gallery Uri or null. */
    fun saveBitmap(ctx: Context, bmp: Bitmap): Uri? {
        val name = "ScreenGrab_${stamp()}.png"
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                val cv = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/ScreenGrab")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv) ?: return null
                ctx.contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                cv.clear(); cv.put(MediaStore.Images.Media.IS_PENDING, 0)
                ctx.contentResolver.update(uri, cv, null, null)
                uri
            } else {
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "ScreenGrab")
                dir.mkdirs()
                val f = File(dir, name)
                f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                Uri.fromFile(f)
            }
        } catch (e: Exception) { Log2.e("save bitmap failed", e); null }
    }

    /** A temp file (app-private) in the downloads cache for the sniffer's downloader. */
    fun newDownloadTemp(ctx: Context, ext: String): File {
        val dir = File(ctx.cacheDir, "dl").apply { mkdirs() }
        return File(dir, "ScreenGrab_${stamp()}.$ext")
    }

    /**
     * Publish a downloaded media file into the gallery. Videos → Movies/ScreenGrab, audio → Music/ScreenGrab.
     * [displayName] is the final file name; [mime] e.g. "video/mp4", "audio/mp4".
     */
    fun publishDownloaded(ctx: Context, temp: File, displayName: String, mime: String): Uri? {
        val audio = mime.startsWith("audio/")
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                val coll = if (audio) MediaStore.Audio.Media.EXTERNAL_CONTENT_URI else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                val rel = (if (audio) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_MOVIES) + "/ScreenGrab"
                val cv = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, rel)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = ctx.contentResolver.insert(coll, cv) ?: return null
                ctx.contentResolver.openOutputStream(uri)?.use { out -> temp.inputStream().use { it.copyTo(out) } }
                cv.clear(); cv.put(MediaStore.MediaColumns.IS_PENDING, 0)
                ctx.contentResolver.update(uri, cv, null, null)
                temp.delete()
                uri
            } else {
                val base = Environment.getExternalStoragePublicDirectory(
                    if (audio) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_MOVIES)
                val dir = File(base, "ScreenGrab").apply { mkdirs() }
                val f = File(dir, displayName)
                temp.inputStream().use { inp -> f.outputStream().use { inp.copyTo(it) } }
                temp.delete()
                Uri.fromFile(f)
            }
        } catch (e: Exception) { Log2.e("publish download failed", e); null }
    }

    /** A temp file (app-private) the muxer writes the recording to before we publish it. */
    fun newVideoTemp(ctx: Context): File {
        val dir = File(ctx.cacheDir, "rec").apply { mkdirs() }
        return File(dir, "ScreenGrab_${stamp()}.mp4")
    }

    /** Publish a finished recording into the gallery (Movies/ScreenGrab); returns its Uri. */
    fun publishVideo(ctx: Context, temp: File): Uri? {
        val name = temp.name
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                val cv = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, name)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/ScreenGrab")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
                val uri = ctx.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv) ?: return null
                ctx.contentResolver.openOutputStream(uri)?.use { out -> temp.inputStream().use { it.copyTo(out) } }
                cv.clear(); cv.put(MediaStore.Video.Media.IS_PENDING, 0)
                ctx.contentResolver.update(uri, cv, null, null)
                temp.delete()
                uri
            } else {
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "ScreenGrab")
                dir.mkdirs()
                val f = File(dir, name)
                temp.inputStream().use { inp -> f.outputStream().use { inp.copyTo(it) } }
                temp.delete()
                Uri.fromFile(f)
            }
        } catch (e: Exception) { Log2.e("publish video failed", e); null }
    }
}
