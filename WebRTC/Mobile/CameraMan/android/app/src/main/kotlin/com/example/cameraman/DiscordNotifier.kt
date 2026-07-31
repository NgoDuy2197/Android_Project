package com.example.cameraman

import android.util.Log
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Fire-and-forget Discord webhook sender. Posts a simple `{"content": ...}`
 * JSON body — the minimal payload a webhook accepts. Network work runs off the
 * caller's thread.
 */
object DiscordNotifier {

    private const val TAG = "DiscordNotifier"
    private val io = Executors.newSingleThreadExecutor()

    fun send(webhookUrl: String, message: String, onResult: ((Boolean) -> Unit)? = null) {
        val url = webhookUrl.trim()
        if (url.isEmpty() || !url.startsWith("http")) {
            onResult?.invoke(false)
            return
        }
        io.execute {
            val ok = post(url, message)
            onResult?.invoke(ok)
        }
    }

    private fun post(webhookUrl: String, message: String): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(webhookUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10_000
                readTimeout = 10_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
            val body = JSONObject().put("content", message).toString()
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }
            val code = conn.responseCode
            // Discord returns 204 No Content on success.
            val ok = code in 200..299
            if (!ok) Log.w(TAG, "Webhook trả về mã $code")
            ok
        } catch (e: Exception) {
            Log.w(TAG, "Gửi webhook thất bại: ${e.message}")
            false
        } finally {
            conn?.disconnect()
        }
    }
}
