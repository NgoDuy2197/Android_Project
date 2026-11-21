package com.dsoft.screengrab.net

import android.content.Context
import com.dsoft.screengrab.capture.MediaSaver
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.OutputStream
import java.net.URI
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * Downloads a detected [MediaItem]. Direct files stream to disk; HLS playlists are expanded (master →
 * best variant), their segments fetched (AES-128 decrypted when needed) and concatenated into one
 * playable file; DASH is handled best-effort (SegmentTemplate / SegmentList / single BaseURL).
 * Downloading re-fetches from the learned URL with the captured replay headers, so a stream can be
 * saved in full without watching it through.
 */
object MediaDownloader {

    private val http: OkHttpClient by lazy {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(c: Array<out java.security.cert.X509Certificate>?, a: String?) {}
            override fun checkServerTrusted(c: Array<out java.security.cert.X509Certificate>?, a: String?) {}
            override fun getAcceptedIssuers() = arrayOf<java.security.cert.X509Certificate>()
        }
        val sc = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustAll), null) }
        OkHttpClient.Builder()
            .sslSocketFactory(sc.socketFactory, trustAll)
            .hostnameVerifier { _, _ -> true }
            .followRedirects(true).followSslRedirects(true)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    @Volatile private var cancelled = HashSet<Long>()
    fun cancel(id: Long) { cancelled.add(id) }
    private fun isCancelled(item: MediaItem) = cancelled.contains(item.id)

    fun download(ctx: Context, item: MediaItem) {
        cancelled.remove(item.id)
        item.state = MediaItem.State.DOWNLOADING; item.progress = -1; item.detail = "Đang bắt đầu…"
        MediaCatalog.notifyChanged()
        try {
            when (item.kind) {
                MediaItem.Kind.FILE -> downloadFile(ctx, item)
                MediaItem.Kind.HLS -> downloadHls(ctx, item)
                MediaItem.Kind.DASH -> downloadDash(ctx, item)
            }
        } catch (e: Throwable) {
            if (isCancelled(item)) { item.state = MediaItem.State.IDLE; item.detail = "Đã huỷ" }
            else { item.state = MediaItem.State.ERROR; item.detail = e.message ?: e.javaClass.simpleName }
            MediaCatalog.notifyChanged()
        }
    }

    // ---- request helper ----
    private fun req(url: String, headers: Map<String, String>): Request {
        val b = Request.Builder().url(url)
        for ((k, v) in headers) b.addHeader(k, v)
        return b.build()
    }

    private fun getText(url: String, headers: Map<String, String>): String =
        http.newCall(req(url, headers)).execute().use { r ->
            if (!r.isSuccessful) throw RuntimeException("HTTP ${r.code} khi tải playlist")
            r.body?.string() ?: ""
        }

    private fun getBytes(url: String, headers: Map<String, String>): ByteArray =
        http.newCall(req(url, headers)).execute().use { r ->
            if (!r.isSuccessful) throw RuntimeException("HTTP ${r.code}")
            r.body?.bytes() ?: ByteArray(0)
        }

    private fun resolve(base: String, ref: String): String =
        try { URI(base).resolve(ref.trim()).toString() } catch (_: Exception) { ref }

    private fun done(ctx: Context, item: MediaItem, temp: File, name: String, mime: String) {
        item.detail = "Đang lưu vào thư viện…"; MediaCatalog.notifyChanged()
        val uri = MediaSaver.publishDownloaded(ctx, temp, name, mime)
        if (uri == null) { item.state = MediaItem.State.ERROR; item.detail = "Lưu thất bại" }
        else { item.state = MediaItem.State.DONE; item.progress = 100; item.savedUri = uri.toString(); item.detail = "Đã lưu: $name" }
        MediaCatalog.notifyChanged()
    }

    private fun safeName(item: MediaItem, ext: String): String {
        val base = item.title.replace(Regex("[^A-Za-z0-9 ._-]"), "").trim().take(50).ifBlank { "video" }
        val stamp = android.text.format.DateFormat.format("yyyyMMdd_HHmmss", System.currentTimeMillis())
        return "${base}_$stamp.$ext"
    }

    // ---- direct file ----
    private fun downloadFile(ctx: Context, item: MediaItem) {
        val ext = item.url.substringBefore('?').substringAfterLast('.', "mp4").lowercase().take(4)
        val mime = if (item.contentType.isNotBlank()) item.contentType.substringBefore(';').trim()
                   else if (ext in setOf("mp3", "m4a", "aac", "flac", "ogg", "wav")) "audio/mp4" else "video/mp4"
        val temp = MediaSaver.newDownloadTemp(ctx, ext)
        http.newCall(req(item.url, item.headers)).execute().use { r ->
            if (!r.isSuccessful) throw RuntimeException("HTTP ${r.code}")
            val total = r.body?.contentLength() ?: -1L
            val src = r.body!!.byteStream()
            temp.outputStream().use { out ->
                val buf = ByteArray(64 * 1024); var got = 0L
                while (true) {
                    if (isCancelled(item)) { temp.delete(); throw RuntimeException("cancelled") }
                    val n = src.read(buf); if (n < 0) break
                    out.write(buf, 0, n); got += n
                    if (total > 0) { item.progress = ((got * 100) / total).toInt(); item.detail = fmtMB(got, total) }
                    else { item.detail = fmtMB(got, -1) }
                    MediaCatalog.notifyChanged()
                }
            }
        }
        done(ctx, item, temp, safeName(item, ext), mime)
    }

    private fun fmtMB(got: Long, total: Long): String {
        val g = got / (1024.0 * 1024)
        return if (total > 0) String.format("%.1f/%.1f MB", g, total / (1024.0 * 1024)) else String.format("%.1f MB", g)
    }

    // ---- HLS ----
    private fun downloadHls(ctx: Context, item: MediaItem) {
        var playlistUrl = item.url
        var text = getText(playlistUrl, item.headers)

        // master playlist → pick highest-bandwidth variant
        if (text.contains("#EXT-X-STREAM-INF")) {
            val best = pickBestVariant(text) ?: throw RuntimeException("Không đọc được variant trong master playlist")
            playlistUrl = resolve(playlistUrl, best)
            text = getText(playlistUrl, item.headers)
        }

        val lines = text.lines()
        val segUrls = ArrayList<String>()
        var initUrl: String? = null
        // AES-128 key state (can change mid-playlist)
        var keyBytes: ByteArray? = null
        var keyIvHex: String? = null
        var mediaSeq = 0
        lines.firstOrNull { it.startsWith("#EXT-X-MEDIA-SEQUENCE") }
            ?.substringAfter(':')?.trim()?.toIntOrNull()?.let { mediaSeq = it }

        data class Seg(val url: String, val key: ByteArray?, val iv: ByteArray?)
        val segs = ArrayList<Seg>()
        var seqNo = mediaSeq

        for (raw in lines) {
            val line = raw.trim()
            when {
                line.startsWith("#EXT-X-MAP") -> {
                    val uri = attr(line, "URI"); if (uri != null) initUrl = resolve(playlistUrl, uri)
                }
                line.startsWith("#EXT-X-KEY") -> {
                    val method = attr(line, "METHOD")
                    if (method == null || method == "NONE") { keyBytes = null; keyIvHex = null }
                    else if (method == "AES-128") {
                        val ku = attr(line, "URI")?.let { resolve(playlistUrl, it) }
                        keyBytes = ku?.let { getBytes(it, item.headers) }
                        keyIvHex = attr(line, "IV")
                    } else throw RuntimeException("Mã hoá HLS không hỗ trợ: $method")
                }
                line.isNotEmpty() && !line.startsWith("#") -> {
                    val su = resolve(playlistUrl, line)
                    val iv = when {
                        keyBytes == null -> null
                        keyIvHex != null -> hexToBytes(keyIvHex!!)
                        else -> seqIv(seqNo)
                    }
                    segs.add(Seg(su, keyBytes, iv))
                    segUrls.add(su)
                    seqNo++
                }
            }
        }
        if (segs.isEmpty()) throw RuntimeException("Playlist không có đoạn video (có thể là live/DRM)")

        val fmp4 = initUrl != null || segUrls.firstOrNull()?.substringBefore('?')?.endsWith(".m4s") == true
        val ext = if (fmp4) "mp4" else "ts"
        val temp = MediaSaver.newDownloadTemp(ctx, ext)
        temp.outputStream().use { out ->
            initUrl?.let { out.write(getBytes(it, item.headers)) }
            segs.forEachIndexed { i, s ->
                if (isCancelled(item)) { temp.delete(); throw RuntimeException("cancelled") }
                var data = getBytes(s.url, item.headers)
                if (s.key != null) data = aesDecrypt(data, s.key, s.iv)
                out.write(data)
                item.progress = (((i + 1) * 100) / segs.size)
                item.detail = "${i + 1}/${segs.size} đoạn"
                MediaCatalog.notifyChanged()
            }
        }
        done(ctx, item, temp, safeName(item, ext), if (fmp4) "video/mp4" else "video/mp2t")
    }

    /** Return the variant URI with the highest BANDWIDTH from a master playlist. */
    private fun pickBestVariant(master: String): String? {
        val lines = master.lines()
        var bestBw = -1L; var bestUri: String? = null
        var i = 0
        while (i < lines.size) {
            val l = lines[i].trim()
            if (l.startsWith("#EXT-X-STREAM-INF")) {
                val bw = Regex("BANDWIDTH=(\\d+)").find(l)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                var j = i + 1
                while (j < lines.size && (lines[j].trim().isEmpty() || lines[j].trim().startsWith("#"))) j++
                if (j < lines.size && bw > bestBw) { bestBw = bw; bestUri = lines[j].trim() }
                i = j
            } else i++
        }
        return bestUri
    }

    private fun attr(line: String, name: String): String? =
        Regex("$name=\"([^\"]*)\"").find(line)?.groupValues?.get(1)
            ?: Regex("$name=([^,\\s]+)").find(line)?.groupValues?.get(1)

    private fun seqIv(seq: Int): ByteArray {
        val iv = ByteArray(16)
        iv[12] = (seq ushr 24).toByte(); iv[13] = (seq ushr 16).toByte()
        iv[14] = (seq ushr 8).toByte(); iv[15] = seq.toByte()
        return iv
    }

    private fun hexToBytes(h: String): ByteArray {
        val s = h.removePrefix("0x").removePrefix("0X")
        val out = ByteArray(s.length / 2)
        for (i in out.indices) out[i] = ((Character.digit(s[i * 2], 16) shl 4) + Character.digit(s[i * 2 + 1], 16)).toByte()
        return out
    }

    private fun aesDecrypt(data: ByteArray, key: ByteArray, iv: ByteArray?): ByteArray {
        val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv ?: ByteArray(16)))
        return c.doFinal(data)
    }

    // ---- DASH (best-effort) ----
    private fun downloadDash(ctx: Context, item: MediaItem) {
        val mpd = getText(item.url, item.headers)
        val base = item.url
        // Pick the Representation with the highest bandwidth that has a video mime (fallback: any).
        val reps = Regex("<Representation\\b[^>]*>", RegexOption.DOT_MATCHES_ALL).findAll(mpd).map { it.value }.toList()
        if (reps.isEmpty()) throw RuntimeException("MPD không có Representation (DASH này chưa hỗ trợ)")
        val rep = reps.maxByOrNull { Regex("bandwidth=\"(\\d+)\"").find(it)?.groupValues?.get(1)?.toLongOrNull() ?: 0L }!!
        val repId = Regex("id=\"([^\"]+)\"").find(rep)?.groupValues?.get(1) ?: ""

        // Case 1: SegmentTemplate with $Number$
        val tmpl = Regex("<SegmentTemplate\\b[^>]*>").findAll(mpd).lastOrNull()?.value
        if (tmpl != null && tmpl.contains("media=")) {
            val initTmpl = attrX(tmpl, "initialization")
            val mediaTmpl = attrX(tmpl, "media") ?: throw RuntimeException("SegmentTemplate thiếu media")
            val startNumber = attrX(tmpl, "startNumber")?.toIntOrNull() ?: 1
            // count segments from SegmentTimeline if present, else from duration
            val timeline = Regex("<S\\b[^>]*/?>").findAll(mpd).toList()
            val count = if (timeline.isNotEmpty()) timeline.sumOf { s ->
                (attrX(s.value, "r")?.toIntOrNull() ?: 0) + 1
            } else estimateDashCount(mpd)
            if (count <= 0) throw RuntimeException("Không xác định được số đoạn DASH")
            val temp = MediaSaver.newDownloadTemp(ctx, "mp4")
            temp.outputStream().use { out ->
                initTmpl?.let { out.write(getBytes(resolve(base, fillTmpl(it, repId, startNumber)), item.headers)) }
                for (k in 0 until count) {
                    if (isCancelled(item)) { temp.delete(); throw RuntimeException("cancelled") }
                    val u = resolve(base, fillTmpl(mediaTmpl, repId, startNumber + k))
                    out.write(getBytes(u, item.headers))
                    item.progress = (((k + 1) * 100) / count); item.detail = "${k + 1}/$count đoạn"
                    MediaCatalog.notifyChanged()
                }
            }
            done(ctx, item, temp, safeName(item, "mp4"), "video/mp4"); return
        }

        // Case 2: single BaseURL inside the Representation → direct file
        val baseUrl = Regex("<BaseURL>([^<]+)</BaseURL>").find(rep)?.groupValues?.get(1)
            ?: Regex("<BaseURL>([^<]+)</BaseURL>").find(mpd)?.groupValues?.get(1)
        if (baseUrl != null) {
            val fileUrl = resolve(base, baseUrl)
            val temp = MediaSaver.newDownloadTemp(ctx, "mp4")
            http.newCall(req(fileUrl, item.headers)).execute().use { r ->
                if (!r.isSuccessful) throw RuntimeException("HTTP ${r.code}")
                val total = r.body?.contentLength() ?: -1L; val src = r.body!!.byteStream()
                temp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024); var got = 0L
                    while (true) {
                        if (isCancelled(item)) { temp.delete(); throw RuntimeException("cancelled") }
                        val n = src.read(buf); if (n < 0) break; out.write(buf, 0, n); got += n
                        if (total > 0) item.progress = ((got * 100) / total).toInt()
                        item.detail = fmtMB(got, total); MediaCatalog.notifyChanged()
                    }
                }
            }
            done(ctx, item, temp, safeName(item, "mp4"), "video/mp4"); return
        }
        throw RuntimeException("DASH kiểu này chưa hỗ trợ (SegmentList/động). Thử tải bằng mp4 trực tiếp nếu có.")
    }

    private fun attrX(tag: String, name: String): String? =
        Regex("$name=\"([^\"]*)\"").find(tag)?.groupValues?.get(1)

    private fun fillTmpl(t: String, repId: String, number: Int): String =
        t.replace("\$RepresentationID\$", repId)
            .replace(Regex("\\\$Number%0(\\d+)d\\\$")) { m -> String.format("%0${m.groupValues[1]}d", number) }
            .replace("\$Number\$", number.toString())

    private fun estimateDashCount(mpd: String): Int {
        val dur = Regex("mediaPresentationDuration=\"([^\"]+)\"").find(mpd)?.groupValues?.get(1)?.let { parseIsoDuration(it) } ?: return 0
        val tmpl = Regex("<SegmentTemplate\\b[^>]*>").findAll(mpd).lastOrNull()?.value ?: return 0
        val segDur = attrX(tmpl, "duration")?.toDoubleOrNull() ?: return 0
        val timescale = attrX(tmpl, "timescale")?.toDoubleOrNull() ?: 1.0
        if (segDur <= 0) return 0
        return Math.ceil(dur / (segDur / timescale)).toInt()
    }

    private fun parseIsoDuration(s: String): Double {
        val m = Regex("PT(?:(\\d+)H)?(?:(\\d+)M)?(?:([\\d.]+)S)?").find(s) ?: return 0.0
        val h = m.groupValues[1].toDoubleOrNull() ?: 0.0
        val min = m.groupValues[2].toDoubleOrNull() ?: 0.0
        val sec = m.groupValues[3].toDoubleOrNull() ?: 0.0
        return h * 3600 + min * 60 + sec
    }
}
