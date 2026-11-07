package com.dsoft.toolbox

import android.content.Intent
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient

/**
 * "Kết nối" tab of [FtpActivity]: a standard FTP client (same role as FileZilla/WinSCP, which this
 * screen already recommends). It lists a remote folder to confirm a login works, remembers each
 * working target in [ConnStore] (export/import as JSON), and can open an SSH shell to the host.
 */
class FtpClientTab(private val a: FtpActivity, private val p: Palette, pane: LinearLayout) {
    private val main = Handler(Looper.getMainLooper())
    private val host: EditText
    private val port: EditText
    private val user: EditText
    private val pass: EditText
    private val connectBtn: TextView
    private val sshBtn: TextView
    private val status: TextView
    private val listing: TextView
    private val savedBox: LinearLayout
    @Volatile private var busy = false

    init {
        pane.addView(a.sectionHeader(p, "Kết nối tới máy chủ FTP khác"))
        host = field("vd: 192.168.1.10")
        port = field("21", numeric = true).apply { setText("21") }
        user = field("bỏ trống = ẩn danh")
        pass = field("mật khẩu").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        }
        pane.addView(a.group(p,
            a.row(p, "Máy chủ", host),
            a.row(p, "Cổng", port),
            a.row(p, "Tài khoản", user),
            a.row(p, "Mật khẩu", pass),
        ))
        connectBtn = a.bigButton("Kết nối", p.blue, p.blue) { connect() }
        pane.addView(connectBtn, margins())

        status = a.text("", 14f, p.secondary).apply { setPadding(a.dp(16), a.dp(8), a.dp(16), 0) }
        pane.addView(status)

        sshBtn = a.bigButton("Mở SSH (gõ lệnh trên máy)", p.indigo, p.indigo) { openSsh() }
        sshBtn.visibility = View.GONE
        pane.addView(sshBtn, margins())

        listing = a.text("", 13f, p.secondary).apply {
            typeface = Typeface.MONOSPACE
            setPadding(a.dp(14), a.dp(10), a.dp(14), a.dp(10))
            setTextIsSelectable(true)
        }
        listing.visibility = View.GONE
        pane.addView(a.card(p).apply { addView(listing) }, vlp())

        pane.addView(a.sectionHeader(p, "Đã lưu"))
        savedBox = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        pane.addView(savedBox, vlp())
        pane.addView(a.group(p,
            a.row(p, "Export ra tệp…", a.chevron(p)) { a.exportConns() },
            a.row(p, "Import từ tệp…", a.chevron(p)) { a.importConns() },
        ))
        pane.addView(a.footnote(p, "Chạm một mục đã lưu để điền lại rồi kết nối. Chỉ lưu khi đăng nhập thành công."))

        refreshSaved()
    }

    private fun margins(): LinearLayout.LayoutParams =
        vlp(-1, -2).apply { topMargin = a.dp(10); marginStart = a.dp(4); marginEnd = a.dp(4) }

    private fun field(hintText: String, numeric: Boolean = false): EditText =
        a.inlineField(p, "", numeric = numeric) {}.apply { hint = hintText }

    private fun current(): Conn = Conn(
        name = host.text.toString().trim(),
        host = host.text.toString().trim(),
        port = port.text.toString().toIntOrNull() ?: 21,
        user = user.text.toString().trim(),
        pass = pass.text.toString(),
    )

    fun fill(c: Conn) {
        host.setText(c.host); port.setText(c.port.toString())
        user.setText(c.user); pass.setText(c.pass)
    }

    // ---------- connect (login + list root to verify) ----------
    private fun connect() {
        val c = current()
        if (c.host.isEmpty()) { a.toast("Nhập địa chỉ máy chủ"); return }
        if (busy) return
        busy = true
        status.text = "Đang kết nối ${c.host}:${c.port}…"
        sshBtn.visibility = View.GONE
        listing.visibility = View.GONE
        Thread {
            val ftp = FTPClient()
            ftp.connectTimeout = 8000
            val result = try {
                ftp.connect(c.host, c.port)
                val u = c.user.ifEmpty { "anonymous" }
                val ok = ftp.login(u, c.pass)
                if (!ok) throw IllegalStateException("Sai tài khoản/mật khẩu (${ftp.replyString.trim()})")
                ftp.enterLocalPassiveMode()
                ftp.setFileType(FTP.BINARY_FILE_TYPE)
                val names = ftp.listNames()?.toList().orEmpty()
                val dir = ftp.printWorkingDirectory() ?: "/"
                Result.success(dir to names)
            } catch (e: Exception) {
                Result.failure(e)
            } finally {
                runCatching { if (ftp.isConnected) { ftp.logout(); ftp.disconnect() } }
            }
            main.post {
                busy = false
                result.onSuccess { (dir, names) ->
                    status.text = "✓ Kết nối được. Thư mục: $dir"
                    status.setTextColor(p.green)
                    listing.visibility = View.VISIBLE
                    listing.text = if (names.isEmpty()) "(thư mục trống)"
                        else names.take(200).joinToString("\n")
                    sshBtn.visibility = View.VISIBLE
                    ConnStore.upsert(a, c.copy(lastOk = System.currentTimeMillis()))
                    refreshSaved()
                }.onFailure {
                    status.text = "✗ ${it.message?.take(120)}"
                    status.setTextColor(p.red)
                }
            }
        }.start()
    }

    private fun openSsh() {
        val c = current()
        if (c.host.isEmpty()) { a.toast("Chưa có máy chủ"); return }
        a.startActivity(Intent(a, SshActivity::class.java)
            .putExtra("host", c.host)
            .putExtra("port", c.sshPort)
            .putExtra("user", c.user)
            .putExtra("pass", c.pass))
    }

    // ---------- saved list ----------
    fun refreshSaved() {
        savedBox.removeAllViews()
        val list = ConnStore.load(a)
        if (list.isEmpty()) {
            savedBox.addView(a.footnote(p, "Chưa có kết nối nào được lưu."))
            return
        }
        val rows = list.map { c ->
            val sub = a.text("${c.host}:${c.port}  ·  ${c.user.ifEmpty { "ẩn danh" }}", 13f, p.secondary)
            a.row(p, c.name.ifEmpty { c.host }, deleteBtn(c)) { fill(c); connect() }
                .apply {
                    // stack title + subtitle in the left cell
                    (getChildAt(0) as? TextView)?.let { title ->
                        val cell = LinearLayout(a).apply {
                            orientation = LinearLayout.VERTICAL
                            addView(a.text(title.text.toString(), 16f, p.text))
                            addView(sub)
                        }
                        removeViewAt(0)
                        addView(cell, 0, LinearLayout.LayoutParams(0, -2, 1f))
                    }
                }
        }
        savedBox.addView(a.group(p, *rows.toTypedArray()))
    }

    private fun deleteBtn(c: Conn): TextView = a.text("Xóa", 14f, p.red).apply {
        setPadding(a.dp(10), a.dp(6), a.dp(6), a.dp(6))
        setOnClickListener { ConnStore.remove(a, c); refreshSaved() }
    }
}
