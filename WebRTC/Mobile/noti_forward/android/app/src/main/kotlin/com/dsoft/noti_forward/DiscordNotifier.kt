package com.dsoft.noti_forward

import android.util.Log
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Fire-and-forget Discord webhook sender. Posts a simple JSON body with
 * `content` (and optional `username`). Network work runs off the caller's
 * thread so it never blocks the notification listener.
 */
object DiscordNotifier {

    private const val TAG = "DiscordNotifier"
    private val io = Executors.newSingleThreadExecutor()

    fun send(
        webhookUrl: String,
        message: String,
        username: String = "",
        onResult: ((Boolean, String?) -> Unit)? = null,
    ) {
        val url = webhookUrl.trim()
        if (url.isEmpty() || !url.startsWith("http")) {
            onResult?.invoke(false, "Webhook URL không hợp lệ")
            return
        }
        io.execute {
            val (ok, err) = post(url, message, username)
            onResult?.invoke(ok, err)
        }
    }

    private fun post(
        webhookUrl: String,
        message: String,
        username: String,
    ): Pair<Boolean, String?> {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(webhookUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10_000
                readTimeout = 10_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
            val body = JSONObject().apply {
                put("content", message)
                val name = username.trim()
                if (name.isNotEmpty()) put("username", name.take(80))
            }.toString()
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }
            val code = conn.responseCode
            // Discord returns 204 No Content on success.
            val ok = code in 200..299
            if (!ok) {
                val errBody = try {
                    conn.errorStream?.bufferedReader()?.readText()?.take(200)
                } catch (_: Exception) {
                    null
                }
                val msg = "Webhook trả về mã $code${errBody?.let { ": $it" } ?: ""}"
                Log.w(TAG, msg)
                false to msg
            } else {
                true to null
            }
        } catch (e: Exception) {
            val msg = "Gửi webhook thất bại: ${e.message}"
            Log.w(TAG, msg)
            false to msg
        } finally {
            conn?.disconnect()
        }
    }
}
