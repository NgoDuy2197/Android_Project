package com.dsoft.toolbox

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.Properties

/**
 * A plain SSH terminal (same role as JuiceSSH / PuTTY) for administering a machine the user controls
 * — typically the LAN host they just reached over FTP. Opens an interactive shell channel, streams
 * its output into a scrolling console, and sends typed lines to it.
 */
class SshActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var p: Palette
    private lateinit var console: TextView
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText

    private var session: Session? = null
    private var channel: ChannelShell? = null
    private var out: OutputStream? = null
    @Volatile private var alive = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        p = palette(); setupSystemBars(p)
        val host = intent.getStringExtra("host").orEmpty()
        val port = intent.getIntExtra("port", 22).let { if (it <= 0) 22 else it }
        val user = intent.getStringExtra("user").orEmpty().ifEmpty { "root" }
        val pass = intent.getStringExtra("pass").orEmpty()

        val col = page(p, "SSH")
        col.addView(text("$user@$host:$port", 14f, p.secondary).apply { setPadding(dp(4), 0, 0, dp(8)) })

        console = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            setTextColor(p.text)
            textSize = 12.5f
            setTextIsSelectable(true)
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        scroll = ScrollView(this).apply { addView(console) }
        col.addView(card(p).apply { addView(scroll) }, vlp(-1, dp(360)))

        input = EditText(this).apply {
            hint = "gõ lệnh rồi Enter…"
            setTextColor(p.text); setHintTextColor(p.secondary)
            typeface = Typeface.MONOSPACE
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEND
        }
        val send = text("Gửi", 16f, p.blue, 700).apply {
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setOnClickListener { sendLine() }
        }
        input.setOnEditorActionListener { _, _, _ -> sendLine(); true }
        col.addView(card(p).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(2), dp(4), dp(2))
            addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(send)
        }, vlp().apply { topMargin = dp(10) })
        col.addView(footnote(p, "Yêu cầu máy đích đang bật dịch vụ SSH (sshd) và cho đăng nhập bằng mật khẩu. " +
            "Dùng tài khoản SSH của máy đó (có thể khác tài khoản FTP)."))

        append("Đang kết nối tới $host:$port …\n")
        connect(host, port, user, pass)
    }

    private fun connect(host: String, port: Int, user: String, pass: String) {
        Thread {
            try {
                val jsch = JSch()
                val s = jsch.getSession(user, host, port)
                s.setPassword(pass)
                s.setConfig(Properties().apply {
                    put("StrictHostKeyChecking", "no")   // LAN hosts have no known_hosts entry yet
                    put("PreferredAuthentications", "password,keyboard-interactive")
                })
                s.connect(10000)
                val ch = s.openChannel("shell") as ChannelShell
                ch.setPtyType("xterm")
                val inStream = ch.inputStream
                out = ch.outputStream
                ch.connect(8000)
                session = s; channel = ch; alive = true
                main.post { append("✓ Đã kết nối.\n") }
                pump(inStream)
            } catch (e: Exception) {
                main.post { append("\n✗ Lỗi: ${e.message}\n") }
            }
        }.start()
    }

    /** Streams shell output into the console until the channel closes. */
    private fun pump(inStream: InputStream) {
        val buf = ByteArray(4096)
        try {
            while (alive) {
                val n = inStream.read(buf)
                if (n < 0) break
                if (n > 0) {
                    val s = String(buf, 0, n, StandardCharsets.UTF_8)
                    main.post { append(s) }
                }
            }
        } catch (_: Exception) {
        } finally {
            main.post { append("\n— phiên đã đóng —\n") }
        }
    }

    private fun sendLine() {
        val line = input.text.toString()
        input.setText("")
        val o = out ?: return
        Thread {
            try { o.write((line + "\n").toByteArray(StandardCharsets.UTF_8)); o.flush() }
            catch (e: Exception) { main.post { append("\n✗ không gửi được: ${e.message}\n") } }
        }.start()
    }

    private fun append(s: String) {
        console.append(s)
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    override fun onDestroy() {
        super.onDestroy()
        alive = false
        runCatching { channel?.disconnect() }
        runCatching { session?.disconnect() }
    }
}
