package com.dsoft.photo_frame

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException

/**
 * Widget bitmaps are too big to travel inside RemoteViews (binder ~1 MB limit:
 * a 900 px frame is ~3 MB and gets silently dropped). Instead each render is
 * saved as a PNG and the launcher loads it through this read-only provider.
 */
object WidgetImages {
    private fun dir(ctx: Context) = File(ctx.filesDir, "widget_img").apply { mkdirs() }

    /** Saves [bmp] for widget [id] and returns a fresh URI (new name = launcher reloads). */
    fun publish(ctx: Context, id: Int, bmp: Bitmap): Uri {
        val d = dir(ctx)
        d.listFiles { f -> f.name.startsWith("w${id}_") }?.forEach { it.delete() }
        val name = "w${id}_${System.currentTimeMillis()}.png"
        File(d, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        return Uri.parse("content://${ctx.packageName}.widgetimg/$name")
    }

    fun forget(ctx: Context, id: Int) {
        dir(ctx).listFiles { f -> f.name.startsWith("w${id}_") }?.forEach { it.delete() }
    }

    fun file(ctx: Context, name: String): File? {
        if (!Regex("w\\d+_\\d+\\.png").matches(name)) return null // only our own files
        return File(dir(ctx), name).takeIf { it.exists() }
    }
}

class WidgetImageProvider : ContentProvider() {
    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val f = WidgetImages.file(context!!, uri.lastPathSegment.orEmpty()) ?: throw FileNotFoundException(uri.toString())
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri) = "image/png"
    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
}
