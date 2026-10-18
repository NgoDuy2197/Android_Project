package com.dsoft.macrointegrate

import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** Minimal Discord webhook client (plain HttpURLConnection, no libraries). */
object Discord {
    class Result(val ok: Boolean, val message: String)

    /** Posts a text message. */
    fun sendText(webhook: String, username: String, content: String): Result {
        if (!valid(webhook)) return Result(false, "Webhook chưa cấu hình hoặc sai định dạng")
        return try {
            val body = json(
                "username" to username,
                "content" to content.take(1900),
            )
            val c = open(webhook)
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            finish(c)
        } catch (e: Exception) {
            Result(false, "Lỗi mạng: ${e.message}")
        }
    }

    /**
     * Uploads one image file with an optional caption, as multipart/form-data
     * (what Discord expects for attachments).
     */
    fun sendImage(webhook: String, username: String, caption: String, bytes: ByteArray, fileName: String): Result {
        if (!valid(webhook)) return Result(false, "Webhook chưa cấu hình hoặc sai định dạng")
        return try {
            val boundary = "----dh" + System.nanoTime()
            val payload = json("username" to username, "content" to caption.take(1900))
            val c = open(webhook)
            c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            DataOutputStream(c.outputStream).use { out ->
                fun line(s: String) = out.writeBytes(s + "\r\n")
                line("--$boundary")
                line("Content-Disposition: form-data; name=\"payload_json\"")
                line("Content-Type: application/json")
                line("")
                out.write(payload.toByteArray(Charsets.UTF_8)); out.writeBytes("\r\n")
                line("--$boundary")
                line("Content-Disposition: form-data; name=\"files[0]\"; filename=\"$fileName\"")
                line("Content-Type: image/png")
                line("")
                out.write(bytes); out.writeBytes("\r\n")
                line("--$boundary--")
            }
            finish(c)
        } catch (e: Exception) {
            Result(false, "Lỗi gửi ảnh: ${e.message}")
        }
    }

    private fun open(webhook: String) = (URL(webhook).openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        doOutput = true
        connectTimeout = 15_000
        readTimeout = 20_000
        setRequestProperty("User-Agent", "MacroIntegrate/1.0")
    }

    private fun finish(c: HttpURLConnection): Result {
        val code = c.responseCode
        return try {
            if (code in 200..299) {
                Result(true, "Đã gửi")
            } else {
                val err = (c.errorStream ?: c.inputStream)?.bufferedReader()?.readText()?.take(200).orEmpty()
                Result(false, "Discord trả về $code${if (err.isBlank()) "" else ": $err"}")
            }
        } finally {
            c.disconnect()
        }
    }

    private fun valid(w: String) = w.startsWith("https://") && w.contains("/api/webhooks/")

    private fun json(vararg pairs: Pair<String, String>) =
        pairs.joinToString(",", "{", "}") { (k, v) -> "${esc(k)}:${esc(v)}" }

    private fun esc(s: String): String {
        val sb = StringBuilder("\"")
        for (ch in s) when (ch) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (ch < ' ') sb.append("\\u%04x".format(ch.code)) else sb.append(ch)
        }
        return sb.append("\"").toString()
    }
}
