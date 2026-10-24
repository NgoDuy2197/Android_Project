package com.dsoft.dailydiary

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.LruCache
import android.widget.ImageView
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

object Images {
    /** Long edge of stored photos. Keeps the diary (and backups) small while staying sharp on phones. */
    private const val MAX_EDGE = 2048

    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /**
     * Copies a camera capture / picked image into [dest]: downscaled, EXIF rotation baked in,
     * re-encoded as JPEG. Everything downstream can then ignore orientation.
     */
    fun importPhoto(context: Context, src: Uri, dest: File) {
        val rotation = context.contentResolver.openInputStream(src)!!.use { exifRotation(it) }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(src)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, MAX_EDGE)
        }
        var bmp = context.contentResolver.openInputStream(src)!!.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: error("Không đọc được ảnh")
        val scale = MAX_EDGE.toFloat() / maxOf(bmp.width, bmp.height)
        if (scale < 1f || rotation != 0) {
            val m = Matrix().apply {
                if (scale < 1f) postScale(scale, scale)
                if (rotation != 0) postRotate(rotation.toFloat())
            }
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        }
        dest.parentFile?.mkdirs()
        dest.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
    }

    /** Decodes [file] to roughly [reqPx] on its long edge (never smaller). */
    fun decode(file: File, reqPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0) return null
        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, reqPx) }
        return BitmapFactory.decodeFile(file.path, opts)
    }

    /** Async, cached, recycle-safe load into an ImageView (RecyclerView / ViewPager cells). */
    fun load(view: ImageView, file: File?, reqPx: Int) {
        (view.getTag(R.id.tag_image_job) as? Job)?.cancel()
        view.setImageDrawable(null)
        if (file == null) return
        val key = "${file.path}@$reqPx#${file.lastModified()}"
        cache.get(key)?.let { view.setImageBitmap(it); return }
        val job = scope.launch {
            val bmp = withContext(Dispatchers.IO) { runCatching { decode(file, reqPx) }.getOrNull() } ?: return@launch
            cache.put(key, bmp)
            view.setImageBitmap(bmp)
        }
        view.setTag(R.id.tag_image_job, job)
    }

    private fun sampleSize(w: Int, h: Int, reqPx: Int): Int {
        var s = 1
        while (maxOf(w, h) / (s * 2) >= reqPx) s *= 2
        return s
    }

    private fun exifRotation(input: InputStream): Int =
        when (ExifInterface(input).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
}
