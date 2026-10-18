package com.dsoft.toolbox

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
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
            row(p, "Thêm mục mới", chevron(p)) { addItem() },
            row(p, "Gắn tệp từ máy này…", chevron(p)) { pickHostFile() },
        ))

        col.addView(footnote(p,
            "Bật server rồi mở link ở trên trên máy khác (cùng Wi-Fi hoặc hotspot). Trang web hiện danh sách mục; " +
                "mỗi mục có ô văn bản siêu to và nút gắn tệp.\n" +
                "Máy chủ KHÔNG lưu tệp: khi ai bấm tải, tệp được truyền thẳng từ trình duyệt của người gắn (trang của họ phải còn mở). " +
                "Tệp do chính máy này gắn thì truyền thẳng từ bộ nhớ máy.\n" +
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

    private fun addItem() {
        val s = ShareService.server ?: return toast("Bật server trước đã")
        s.addItem()
        toast("Đã thêm mục — mở trang để nhập nội dung")
    }

    private fun pickHostFile() {
        if (ShareService.server == null) return toast("Bật server trước đã")
        try {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), REQ_FILE)
        } catch (_: Exception) {
            toast("Không mở được trình chọn tệp")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_FILE || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val s = ShareService.server ?: return
        runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        var name = "tep"
        var size = 0L
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val si = c.getColumnIndex(OpenableColumns.SIZE)
                if (ni >= 0) name = c.getString(ni) ?: name
                if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
            }
        }
        val item = s.itemsSnapshot().lastOrNull() ?: s.addItem()
        s.addHostFile(item.id, uri, name, size)
        toast("Đã gắn \"$name\" vào mục \"${item.title}\"")
    }

    private fun toast(msg: String) = android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()

    companion object {
        private const val REQ_FILE = 1
    }
}
