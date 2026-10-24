package com.dsoft.toolbox

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * On/off for the share board plus the access link. The host can also open the
 * same link in its own browser to use the board.
 */
class ShareActivity : Activity() {
    private lateinit var p: Palette
    private lateinit var status: TextView
    private lateinit var addrBox: LinearLayout
    private lateinit var toggle: TextView
    private lateinit var event: TextView
    private lateinit var folderValue: TextView
    private lateinit var transfersBox: LinearLayout
    private val main = Handler(Looper.getMainLooper())
    private val poll = object : Runnable {
        override fun run() { refresh(); main.postDelayed(this, 2500) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        p = palette()
        val col = page(p, "Chia sẻ")

        status = text("", 17f, p.text, 700)
        addrBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        event = text("", 13f, p.secondary).apply { setPadding(0, dp(6), 0, 0) }
        toggle = bigButton("", p.green, p.green) { onToggle() }
        col.addView(card(p).apply {
            setPadding(dp(16), dp(16), dp(16), dp(16))
            addView(status)
            addView(addrBox, vlp())
            addView(event)
            addView(toggle, vlp().apply { topMargin = dp(14) })
        })

        col.addView(sectionHeader(p, "Dùng ngay trên máy này"))
        col.addView(group(p,
            row(p, "Mở bảng chia sẻ trong trình duyệt", chevron(p)) { openSelf() },
            row(p, "Hiện QR link truy cập", chevron(p)) { showQr() },
        ))

        col.addView(sectionHeader(p, "Tự động lưu tệp client gửi lên"))
        folderValue = text(Prefs(this).shareSaveName.ifEmpty { "Chưa chọn" }, 15f, p.secondary)
        col.addView(group(p,
            row(p, "Bật tự động lưu", iosSwitch(p, Prefs(this).shareAutoSave) { Prefs(this).shareAutoSave = it }),
            row(p, "Thư mục lưu", LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(folderValue); addView(chevron(p))
            }) { pickFolder() },
        ))
        col.addView(footnote(p, "Khi BẬT: mọi tệp client gắn lên sẽ tự tải về thư mục này (cần trang của client còn mở lúc gắn)."))

        col.addView(sectionHeader(p, "Tiến trình tự lưu"))
        transfersBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(card(p).apply { setPadding(dp(16), dp(12), dp(16), dp(12)); addView(transfersBox) })

        col.addView(footnote(p,
            "Bật server rồi mở link ở trên trên máy khác (cùng Wi-Fi hoặc hotspot). Người dùng vào web để tạo mục và gắn tệp.\n" +
                "Máy chủ chỉ lưu tệp khi bật \"Tự động lưu\"; nếu không, tệp được truyền thẳng từ trình duyệt người gắn khi có người tải.\n" +
                "Văn bản được giữ trong bộ nhớ máy chủ và mất khi tắt server."))
    }

    override fun onResume() {
        super.onResume()
        ShareService.listener = { refresh() }
        main.post(poll)
    }

    override fun onPause() {
        super.onPause()
        ShareService.listener = null
        main.removeCallbacks(poll)
    }

    private fun url(): String? = Net.addresses().firstOrNull()?.let { "http://${it.second}:${ShareService.PORT}" }

    private fun refresh() {
        val on = ShareService.running
        status.text = when {
            on -> "● Đang chạy"
            ShareService.error != null -> "Lỗi: ${ShareService.error}"
            else -> "Đang tắt"
        }
        status.setTextColor(if (on) p.green else if (ShareService.error != null) p.red else p.text)
        addrBox.removeAllViews()
        if (on) {
            val ips = Net.addresses()
            if (ips.isEmpty()) addrBox.addView(text("Chưa có Wi-Fi / hotspot", 15f, p.secondary).apply { setPadding(0, dp(10), 0, 0) })
            for ((iface, ip) in ips) {
                val u = "http://$ip:${ShareService.PORT}"
                addrBox.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, dp(10), 0, 0)
                    addView(text(u, 20f, p.blue, 700).apply { typeface = Typeface.MONOSPACE })
                    addView(text("$iface · chạm để copy", 12f, p.secondary))
                    setOnClickListener { copyText(u) }
                })
            }
        }
        event.text = if (on && ShareService.lastEvent.isNotEmpty()) ShareService.lastEvent else ""
        event.visibility = if (event.text.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
        toggle.text = if (on) "Dừng server" else "Bật server chia sẻ"
        toggle.background = pressable(gradient(dp(14).toFloat(), if (on) p.red else p.green, if (on) p.red else p.green), 0x33FFFFFF)
        renderTransfers()
    }

    private fun renderTransfers() {
        transfersBox.removeAllViews()
        val tr = ShareService.server?.transfersSnapshot() ?: emptyList()
        if (tr.isEmpty()) {
            transfersBox.addView(text("Chưa có tệp nào tự lưu.", 14f, p.secondary))
            return
        }
        val active = tr.count { it.state == "active" }
        val doneN = tr.count { it.state == "done" }
        val err = tr.count { it.state == "error" }
        val summary = "Đang tải: $active · Xong: $doneN/${tr.size}" + if (err > 0) " · Lỗi: $err" else ""
        transfersBox.addView(text(summary, 16f, if (active == 0) p.green else p.text, 700))
        if (active == 0 && tr.isNotEmpty())
            transfersBox.addView(text("Tất cả đã xong — có thể tắt server.", 13f, p.green).apply { setPadding(0, dp(2), 0, 0) })
        tr.takeLast(12).forEach { t ->
            val pct = if (t.size > 0) (t.done * 100 / t.size).toInt() else if (t.state == "done") 100 else 0
            val mark = when (t.state) { "done" -> "✓"; "error" -> "✗"; else -> "$pct%" }
            val color = when (t.state) { "error" -> p.red; "done" -> p.green; else -> p.secondary }
            transfersBox.addView(text("$mark  ${t.name}", 13f, color).apply {
                setPadding(0, dp(5), 0, 0); maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
        }
        if (doneN + err > 0) transfersBox.addView(text("Xoá danh sách đã xong", 14f, p.blue, 600).apply {
            setPadding(0, dp(10), 0, 0)
            setOnClickListener { ShareService.server?.clearFinishedTransfers(); renderTransfers() }
        })
    }

    private fun onToggle() {
        if (ShareService.running) ShareService.stop(this)
        else { ShareService.error = null; ShareService.start(this) }
    }

    private fun openSelf() {
        val u = url() ?: return toast("Bật server trước đã")
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)))
        } catch (_: Exception) {
            toast("Không mở được trình duyệt")
        }
    }

    private fun pickFolder() {
        try {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_TREE)
        } catch (_: Exception) {
            toast("Không mở được trình chọn thư mục")
        }
    }

    private fun showQr() {
        val u = url() ?: return toast("Bật server trước đã")
        val bmp = Qr.bitmap(u, 600) ?: return toast("Không tạo được QR")
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(16), dp(16), dp(16), dp(8))
            addView(ImageView(this@ShareActivity).apply {
                setImageBitmap(bmp)
                layoutParams = LinearLayout.LayoutParams(dp(240), dp(240))
            })
            addView(text(u, 13f, p.blue).apply { typeface = Typeface.MONOSPACE; setPadding(0, dp(10), 0, 0); gravity = Gravity.CENTER })
        }
        AlertDialog.Builder(this).setTitle("Quét để mở").setView(box).setPositiveButton("Đóng", null).show()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_TREE || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        runCatching {
            contentResolver.takePersistableUriPermission(uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        val name = (runCatching { DocumentsContract.getTreeDocumentId(uri).substringAfterLast(':') }.getOrNull() ?: "")
            .ifEmpty { "Thư mục" }
        Prefs(this).shareSaveTree = uri.toString()
        Prefs(this).shareSaveName = name
        folderValue.text = name
        toast("Sẽ lưu tệp vào: $name")
    }

    private fun toast(msg: String) = android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()

    companion object {
        private const val REQ_TREE = 2
    }
}
