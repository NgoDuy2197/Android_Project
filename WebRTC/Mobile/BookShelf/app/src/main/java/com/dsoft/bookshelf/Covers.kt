package com.dsoft.bookshelf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.LruCache
import java.io.FileOutputStream
import java.util.concurrent.Executors

/** Cover thumbnails: custom image > rendered PDF page 1 (cached as cover.png) > generated placeholder (null). */
object Covers {
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 1024 / 10).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }
    private val versions = HashMap<String, Int>()
    private val pending = HashSet<String>()
    private val noCover = HashSet<String>()
    private val pool = Executors.newFixedThreadPool(2)

    private fun key(b: Book) = "${b.id}:${b.customCover}:${synchronized(versions) { versions[b.id] ?: 0 }}"

    fun cached(b: Book): Bitmap? = cache.get(key(b))
    fun hasNoCover(b: Book) = synchronized(noCover) { key(b) in noCover }

    /** Loads asynchronously; [done] runs on the main thread with the bitmap or null (= use placeholder). */
    fun load(b: Book, done: (Bitmap?) -> Unit) {
        val k = key(b)
        cache.get(k)?.let { done(it); return }
        if (synchronized(noCover) { k in noCover }) { done(null); return }
        synchronized(pending) { if (!pending.add(k)) return }
        pool.execute {
            val bmp = try { loadBlocking(b) } catch (_: Throwable) { null }
            if (bmp != null) cache.put(k, bmp) else synchronized(noCover) { noCover += k }
            synchronized(pending) { pending -= k }
            App.main.post { done(bmp) }
        }
    }

    private fun loadBlocking(b: Book): Bitmap? {
        if (b.customCover) {
            val f = Library.customCoverFile(b.id)
            if (f.exists()) BitmapFactory.decodeFile(f.absolutePath)?.let { return it }
        }
        if (!b.isPdf) return null
        val cf = Library.coverFile(b.id)
        if (cf.exists()) BitmapFactory.decodeFile(cf.absolutePath)?.let { return it }
        if (Library.coverFailFile(b.id).exists()) return null
        val bmp = renderFirstPage(b)
        if (bmp == null) {
            try { Library.coverFailFile(b.id).createNewFile() } catch (_: Exception) {}
            return null
        }
        try { FileOutputStream(cf).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } } catch (_: Exception) {}
        return bmp
    }

    /** Renders page 1 at 360 px wide with PdfRenderer; null if the PDF can't be rendered (encrypted, broken...). */
    fun renderFirstPage(b: Book): Bitmap? {
        val pfd = ParcelFileDescriptor.open(Library.file(b), ParcelFileDescriptor.MODE_READ_ONLY)
        try {
            PdfRenderer(pfd).use { r ->
                if (r.pageCount == 0) return null
                r.openPage(0).use { page ->
                    val w = 360
                    val h = (w.toFloat() * page.height / page.width).toInt().coerceIn(200, 720)
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    return bmp
                }
            }
        } finally {
            try { pfd.close() } catch (_: Exception) {}
        }
    }

    fun invalidate(id: String) {
        synchronized(versions) { versions[id] = (versions[id] ?: 0) + 1 }
    }

    /** Copies a picked image into app storage, downscaled to <= 900 px. Blocking. */
    fun saveCustom(ctx: Context, id: String, uri: Uri): Boolean {
        val cr = ctx.contentResolver
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        if (opts.outWidth <= 0) return false
        var sample = 1
        while (maxOf(opts.outWidth, opts.outHeight) / sample > 1800) sample *= 2
        val bmp = cr.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return false
        val scale = minOf(1f, 900f / maxOf(bmp.width, bmp.height))
        val out = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
        val f = Library.customCoverFile(id)
        val tmp = java.io.File(f.parentFile, f.name + ".tmp")
        FileOutputStream(tmp).use { out.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        invalidate(id)
        return true
    }
}
