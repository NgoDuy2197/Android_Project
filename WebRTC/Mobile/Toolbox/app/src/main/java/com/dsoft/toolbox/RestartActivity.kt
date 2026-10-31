package com.dsoft.toolbox

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper

/** Reboot menu (requires root): system / recovery / bootloader(fastboot) / power off / soft reboot. */
class RestartActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val p = palette()
        setupSystemBars(p)
        val col = page(p, "Khởi động lại")

        col.addView(group(p,
            row(p, "Khởi động lại", chevron(p)) { confirm("Khởi động lại máy?", "reboot") },
            row(p, "Khởi động lại nhanh (soft)", chevron(p)) { confirm("Khởi động lại nhanh (chỉ framework)?", "setprop ctl.restart zygote") },
            row(p, "Vào Recovery", chevron(p)) { confirm("Khởi động vào Recovery?", "reboot recovery") },
            row(p, "Vào Bootloader / Fastboot", chevron(p)) { confirm("Khởi động vào Bootloader (Fastboot)?", "reboot bootloader") },
            row(p, "Tắt nguồn", chevron(p)) { confirm("Tắt nguồn máy?", "reboot -p") },
        ))
        col.addView(footnote(p, "Cần quyền ROOT (su). Soft reboot chỉ khởi động lại giao diện (nhanh, không tắt máy). " +
            "Recovery/Bootloader để vào chế độ sửa chữa — chỉ dùng khi biết rõ."))

        // probe root availability in the background, show a hint
        val hint = footnote(p, "Đang kiểm tra quyền root…")
        col.addView(hint)
        Thread {
            val ok = Root.available()
            main.post { hint.text = if (ok) "✓ Đã có quyền root." else "⚠ Chưa cấp quyền root — các lệnh sẽ không chạy được." }
        }.start()
    }

    private fun confirm(msg: String, cmd: String) {
        AlertDialog.Builder(this).setTitle("Xác nhận").setMessage(msg)
            .setPositiveButton("Đồng ý") { _, _ -> run(cmd) }
            .setNegativeButton("Huỷ", null).show()
    }

    private fun run(cmd: String) {
        toast("Đang gửi lệnh…")
        Thread {
            val ok = Root.runAsRoot(cmd)
            // if the device actually reboots we won't get here; a false means no root / failed
            if (!ok) main.post { toast("Không chạy được — cần cấp quyền root (su).") }
        }.start()
    }
}
