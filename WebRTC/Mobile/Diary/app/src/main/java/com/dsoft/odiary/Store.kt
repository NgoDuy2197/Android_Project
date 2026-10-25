package com.dsoft.odiary

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/** One moment in a day: a timestamp, text, photos (filenames in the day folder), image display scale. */
class Event(
    var id: Long,
    var timeMs: Long,
    var text: String = "",
    val photos: MutableList<String> = ArrayList(),
    var imgScale: Int = 100,
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("t", timeMs).put("text", text)
        .put("scale", imgScale).put("photos", JSONArray().also { a -> photos.forEach { a.put(it) } })

    companion object {
        fun fromJson(o: JSONObject): Event {
            val ps = ArrayList<String>()
            val arr = o.optJSONArray("photos") ?: JSONArray()
            for (i in 0 until arr.length()) ps.add(arr.getString(i))
            return Event(o.optLong("id"), o.optLong("t"), o.optString("text"), ps, o.optInt("scale", 100))
        }
    }
}

/** A whole day's diary. [bg]/[padding] empty/-1 = inherit the global setting. */
class DayEntry(val date: String) {
    var label: String = ""
    var emoji: String = ""
    /** "" inherit · "none" · "tpl:<id>" · "img" (per-day file bg.jpg) */
    var bg: String = ""
    var padding: Int = -1
    val events: ArrayList<Event> = ArrayList()
    var compiled: String = ""
    var updatedAt: Long = 0

    fun hasContent() = events.isNotEmpty() || label.isNotEmpty() || compiled.isNotEmpty()

    fun toJson(): JSONObject = JSONObject().put("date", date).put("label", label).put("emoji", emoji)
        .put("bg", bg).put("padding", padding).put("compiled", compiled).put("updatedAt", updatedAt)
        .put("events", JSONArray().also { a -> events.forEach { a.put(it.toJson()) } })

    companion object {
        fun fromJson(o: JSONObject): DayEntry = DayEntry(o.optString("date")).apply {
            label = o.optString("label"); emoji = o.optString("emoji"); bg = o.optString("bg")
            padding = o.optInt("padding", -1); compiled = o.optString("compiled"); updatedAt = o.optLong("updatedAt")
            val arr = o.optJSONArray("events") ?: JSONArray()
            for (i in 0 until arr.length()) events.add(Event.fromJson(arr.getJSONObject(i)))
        }
    }
}

object Store {
    private lateinit var app: Context
    private lateinit var base: File

    /** Set whenever diary data changes; AutoSync uses it to decide when to push to Drive. */
    @Volatile var dirty = false

    fun init(ctx: Context) {
        app = ctx.applicationContext
        setAccount(Prefs.currentAccount(app))
    }

    private fun sanit(id: String) = id.ifEmpty { "default" }.replace(Regex("[^A-Za-z0-9_@.-]"), "_")

    /** The diary folder for any account id (not necessarily the current one). */
    fun accountDir(id: String): File = File(app.filesDir, "accounts/${sanit(id)}/diary")

    /** Point storage at an account's own folder (fresh per-account diary). */
    fun setAccount(id: String) {
        base = accountDir(id).apply { mkdirs() }
    }

    /**
     * Merge another account's diary into the CURRENT account (union events per day, copy photos,
     * fill empty label/emoji/day-bg). Returns the number of days touched.
     */
    fun mergeFromAccount(srcId: String): Int {
        val src = accountDir(srcId)
        if (!src.exists() || src.canonicalPath == base.canonicalPath) return 0
        var days = 0
        for (date in contentDaysIn(src)) {
            val srcDay = loadDayIn(src, date)
            val cur = loadDay(date)
            val have = cur.events.mapTo(HashSet()) { it.id }
            for (ev in srcDay.events) if (ev.id !in have) { cur.events.add(ev); have.add(ev.id) }
            if (cur.label.isEmpty()) cur.label = srcDay.label
            if (cur.emoji.isEmpty()) cur.emoji = srcDay.emoji
            // copy photo files + per-day bg that we don't already have
            val curDir = dayDir(date).apply { mkdirs() }
            val srcDir = File(File(File(src, date.substring(0, 4)), date.substring(5, 7)), date.substring(8, 10))
            srcDay.events.forEach { ev -> ev.photos.forEach { ph ->
                val s = File(srcDir, ph); val d = File(curDir, ph)
                if (s.exists() && !d.exists()) runCatching { s.copyTo(d, overwrite = false); Outbox.put(relPhoto(date, ph)) }
            } }
            val srcBg = File(srcDir, "bg.jpg"); val curBg = File(curDir, "bg.jpg")
            if (srcBg.exists() && !curBg.exists()) runCatching { srcBg.copyTo(curBg); Outbox.put("${relDir(date)}/bg.jpg") }
            saveDay(cur)
            days++
        }
        return days
    }

    private fun loadDayIn(root: File, date: String): DayEntry {
        val f = File(File(File(File(root, date.substring(0, 4)), date.substring(5, 7)), date.substring(8, 10)), "day.json")
        if (!f.exists()) return DayEntry(date)
        return try { DayEntry.fromJson(JSONObject(f.readText())) } catch (_: Exception) { DayEntry(date) }
    }

    private fun contentDaysIn(root: File): Set<String> {
        val out = HashSet<String>()
        val years = root.listFiles { f -> f.isDirectory && f.name.length == 4 && f.name.all { it.isDigit() } } ?: return out
        for (y in years) for (m in y.listFiles { f -> f.isDirectory } ?: emptyArray())
            for (d in m.listFiles { f -> f.isDirectory } ?: emptyArray())
                if (File(d, "day.json").exists()) out.add("${y.name}-${m.name}-${d.name}")
        return out
    }

    fun root(): File = base
    fun globalBgFile(): File = File(base, "_global_bg.jpg")
    /** App-wide wallpaper (shared across accounts). */
    fun appWallpaperFile(): File = File(app.filesDir, "app_wallpaper.jpg")

    /** Cheap blur for the wallpaper: shrink then grow with bilinear filtering. */
    fun downscaleBlur(src: Bitmap, blur: Int): Bitmap {
        if (blur <= 0) return src
        val factor = 1 + blur * 0.6f
        val w = (src.width / factor).toInt().coerceAtLeast(8)
        val h = (src.height / factor).toInt().coerceAtLeast(8)
        val small = Bitmap.createScaledBitmap(src, w, h, true)
        return Bitmap.createScaledBitmap(small, src.width, src.height, true)
    }

    fun dayDir(date: String): File {
        val y = date.substring(0, 4); val m = date.substring(5, 7); val d = date.substring(8, 10)
        return File(base, "$y/$m/$d")
    }

    /** Path relative to the account root, used as the outbox / Drive key. */
    fun relDir(date: String) = "${date.substring(0, 4)}/${date.substring(5, 7)}/${date.substring(8, 10)}"
    fun relPhoto(date: String, name: String) = "${relDir(date)}/$name"
    const val REL_GLOBAL_BG = "_global_bg.jpg"

    private fun dayJson(date: String) = File(dayDir(date), "day.json")
    fun dayBgFile(date: String): File = File(dayDir(date), "bg.jpg")
    fun photoFile(date: String, name: String): File = File(dayDir(date), name)

    fun loadDay(date: String): DayEntry {
        val f = dayJson(date)
        if (!f.exists()) return DayEntry(date)
        return try { DayEntry.fromJson(JSONObject(f.readText())) } catch (_: Exception) { DayEntry(date) }
    }

    fun saveDay(e: DayEntry) {
        writeDayJson(e)
        dirty = true
        Outbox.put("${relDir(e.date)}/day.json")   // queue day.json upload (also marks pendingSync)
    }

    /** Write day.json WITHOUT queueing an upload — used when applying a download from Drive. */
    fun saveDayQuiet(e: DayEntry) = writeDayJson(e)

    private fun writeDayJson(e: DayEntry) {
        e.updatedAt = System.currentTimeMillis()
        val dir = dayDir(e.date).apply { mkdirs() }
        val tmp = File(dir, "day.json.tmp")
        tmp.writeText(e.toJson().toString())
        val target = File(dir, "day.json")
        if (!tmp.renameTo(target)) { target.delete(); tmp.renameTo(target) }
    }

    /** Delete one photo locally AND queue its removal on Drive. */
    fun deletePhoto(date: String, name: String) {
        runCatching { photoFile(date, name).delete() }
        Outbox.del(relPhoto(date, name))
    }

    fun deleteEventPhotos(date: String, ev: Event) {
        ev.photos.forEach { deletePhoto(date, it) }
    }

    /** Imports photos (content Uris) into the day folder, downscaled; returns the saved filenames. */
    fun importPhotos(ctx: Context, date: String, uris: List<Uri>, idHint: Long): List<String> {
        val dir = dayDir(date).apply { mkdirs() }
        val out = ArrayList<String>()
        uris.forEachIndexed { i, uri ->
            val name = "ev${idHint}_${System.currentTimeMillis()}_$i.jpg"
            if (saveScaled(ctx, uri, File(dir, name), 2048)) { out.add(name); Outbox.put(relPhoto(date, name)) }
        }
        return out
    }

    /** Save a day background image + queue it for Drive. */
    fun saveDayBg(ctx: Context, date: String, uri: Uri): Boolean {
        val ok = saveScaled(ctx, uri, dayBgFile(date), 1600)
        if (ok) Outbox.put("${relDir(date)}/bg.jpg")
        return ok
    }

    /** Save the shared global background + queue it for Drive. */
    fun saveGlobalBg(ctx: Context, uri: Uri): Boolean {
        val ok = saveScaled(ctx, uri, globalBgFile(), 1600)
        if (ok) Outbox.put(REL_GLOBAL_BG)
        return ok
    }

    /** Copies any image Uri to [dst] as JPEG, downscaled to <= [maxPx] and rotated per EXIF. */
    fun saveScaled(ctx: Context, uri: Uri, dst: File, maxPx: Int): Boolean {
        try {
            val cr = ctx.contentResolver
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            // NOTE: with inJustDecodeBounds=true, decodeStream returns null by design — only check the STREAM for null, not the decode result.
            val boundsStream = cr.openInputStream(uri) ?: run { android.util.Log.e("ODiaryImg", "openInputStream null (bounds) for $uri"); return false }
            boundsStream.use { BitmapFactory.decodeStream(it, null, opts) }
            if (opts.outWidth <= 0) { android.util.Log.e("ODiaryImg", "decode bounds failed (outWidth<=0) for $uri type=${cr.getType(uri)}"); return false }
            var sample = 1
            while (maxOf(opts.outWidth, opts.outHeight) / sample > maxPx * 2) sample *= 2
            val fullStream = cr.openInputStream(uri) ?: run { android.util.Log.e("ODiaryImg", "openInputStream null (full) for $uri"); return false }
            var bmp = fullStream.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: run { android.util.Log.e("ODiaryImg", "decode full null for $uri"); return false }
            val scale = minOf(1f, maxPx.toFloat() / maxOf(bmp.width, bmp.height))
            if (scale < 1f) bmp = Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true)
            bmp = applyExif(ctx, uri, bmp)
            dst.parentFile?.mkdirs()
            val tmp = File(dst.parentFile, dst.name + ".tmp")
            FileOutputStream(tmp).use { bmp.compress(Bitmap.CompressFormat.JPEG, 86, it) }
            if (!tmp.renameTo(dst)) { dst.delete(); tmp.renameTo(dst) }
            return dst.exists() && dst.length() > 0
        } catch (e: Exception) {
            android.util.Log.e("ODiaryImg", "saveScaled failed for $uri -> $dst", e)
            return false
        }
    }

    private fun applyExif(ctx: Context, uri: Uri, bmp: Bitmap): Bitmap {
        return try {
            val o = ctx.contentResolver.openInputStream(uri)?.use { ExifInterface(it).getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) } ?: return bmp
            val m = Matrix()
            when (o) {
                ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
                else -> return bmp
            }
            Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        } catch (_: Exception) { bmp }
    }

    /** Decodes [file] downsampled to about [reqPx] for thumbnails/preview. */
    fun decodeSampled(file: File, reqPx: Int): Bitmap? {
        if (!file.exists()) return null
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        if (opts.outWidth <= 0) return null
        var sample = 1
        while (maxOf(opts.outWidth, opts.outHeight) / sample > reqPx * 2) sample *= 2
        return BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /** Dates ("yyyy-MM-dd") that have a diary entry — for calendar dots/thumbnails. */
    fun contentDays(): Set<String> = contentDaysIn(base)

    /** How many days another account holds (for the "sync from" picker). */
    fun dayCountOf(srcId: String): Int = contentDaysIn(accountDir(srcId)).size

    /** First photo of a day (for the calendar mini-thumb), or null. */
    fun firstPhoto(date: String): File? {
        val e = loadDay(date)
        for (ev in e.events) for (p in ev.photos) {
            val f = photoFile(date, p)
            if (f.exists()) return f
        }
        return null
    }

    fun allPhotos(date: String): List<File> {
        val e = loadDay(date)
        val out = ArrayList<File>()
        for (ev in e.events) for (p in ev.photos) {
            val f = photoFile(date, p); if (f.exists()) out.add(f)
        }
        return out
    }
}
