package com.dsoft.healthassistant

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads a MediaPipe `.task` model straight into the app's files dir and selects it.
 * Follows redirects manually so the Hugging Face bearer token is only ever sent to
 * huggingface.co (never to the presigned CDN it redirects to). Detects gated models
 * (401/403 or an HTML login page) and reports [Result.needsAuth] so the UI can react.
 */
object ModelDownloader {
    @Volatile private var cancelFlag = false
    @Volatile var busy = false; private set
    private val main = Handler(Looper.getMainLooper())

    class Result(val ok: Boolean, val message: String, val needsAuth: Boolean = false)

    fun cancel() { cancelFlag = true }

    fun download(
        ctx: Context, startUrl: String, token: String, suggestedName: String,
        onProgress: (Long, Long) -> Unit, onDone: (Result) -> Unit,
    ) {
        if (busy) { onDone(Result(false, tr("Đang có một lượt tải khác.", "Another download is in progress."))); return }
        busy = true; cancelFlag = false
        Thread {
            var conn: HttpURLConnection? = null
            try {
                var current = URL(startUrl)
                var code = 0
                var redirects = 0
                while (true) {
                    conn = (current.openConnection() as HttpURLConnection).apply {
                        instanceFollowRedirects = false
                        connectTimeout = 20_000
                        readTimeout = 60_000
                        setRequestProperty("User-Agent", "HealthAssistant-Android")
                        if (token.isNotBlank() && current.host.endsWith("huggingface.co"))
                            setRequestProperty("Authorization", "Bearer $token")
                    }
                    code = conn!!.responseCode
                    if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                        val loc = conn!!.getHeaderField("Location") ?: break
                        conn!!.disconnect()
                        current = URL(current, loc)
                        if (++redirects > 6) throw IOException(tr("Quá nhiều lần chuyển hướng", "Too many redirects"))
                        continue
                    }
                    break
                }
                if (code == 401 || code == 403) {
                    finish(onDone, Result(false,
                        tr("Model này cần đăng nhập Hugging Face (gated). Dán HF token ở trên rồi thử lại, hoặc bấm “Mở trang” để tải thủ công.",
                            "This model is gated on Hugging Face. Paste an HF token above and retry, or tap “Open page” to download manually."),
                        needsAuth = true))
                    return@Thread
                }
                if (code != 200) throw IOException(tr("Máy chủ trả về HTTP $code", "Server returned HTTP $code"))
                val ctype = conn!!.contentType ?: ""
                if (ctype.startsWith("text/html")) {
                    finish(onDone, Result(false,
                        tr("Link trả về trang web chứ không phải tệp model (thường do cần đăng nhập). Dán HF token rồi thử lại, hoặc mở trang.",
                            "The link returned a web page, not a model file (usually needs login). Paste an HF token and retry, or open the page."),
                        needsAuth = true))
                    return@Thread
                }

                val total = conn!!.contentLengthLong
                val dir = File(ctx.filesDir, "models").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() } // keep only one model to save space
                val name = sanitize(suggestedName.ifBlank {
                    current.path.substringAfterLast('/').ifBlank { "model.task" }
                })
                val out = File(dir, name)

                conn!!.inputStream.use { inp ->
                    out.outputStream().use { os ->
                        val buf = ByteArray(1 shl 16)
                        var done = 0L
                        var lastPost = 0L
                        while (true) {
                            if (cancelFlag) { os.flush(); out.delete(); finish(onDone, Result(false, tr("Đã huỷ tải.", "Download cancelled."))); return@Thread }
                            val r = inp.read(buf)
                            if (r < 0) break
                            os.write(buf, 0, r)
                            done += r
                            val now = System.currentTimeMillis()
                            if (now - lastPost > 250) { lastPost = now; val d = done; main.post { onProgress(d, total) } }
                        }
                    }
                }

                if (out.length() < 1_000_000L) { out.delete(); throw IOException(tr("Tệp tải về quá nhỏ — có thể không phải model hợp lệ.", "Downloaded file is too small — may not be a valid model.")) }
                Prefs(ctx).apply { offlineModelPath = out.absolutePath; offlineModelName = name }
                Ai.resetOffline()
                finish(onDone, Result(true, tr("Đã tải xong: $name (${out.length() / (1024 * 1024)} MB). Model đã được chọn.",
                    "Downloaded: $name (${out.length() / (1024 * 1024)} MB). Model selected.")))
            } catch (e: Exception) {
                finish(onDone, Result(false, "${tr("Lỗi tải", "Download error")}: ${e.message}"))
            } finally {
                conn?.disconnect()
            }
        }.start()
    }

    private fun finish(onDone: (Result) -> Unit, r: Result) { busy = false; main.post { onDone(r) } }

    private fun sanitize(n: String) = n.replace(Regex("[^A-Za-z0-9._-]"), "_")
}
