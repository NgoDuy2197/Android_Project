package com.dsoft.toolbox

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.TextView

/** Home: list of tools (iOS Settings style). */
class MainActivity : Activity() {
    private lateinit var ftpSub: TextView
    private lateinit var dimSub: TextView
    private lateinit var trSub: TextView
    private lateinit var shareSub: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val p = palette()
        val col = page(p, "Tiện ích", back = false)
        val (ftpRow, fs) = toolRow(p, R.drawable.ic_ftp, p.blue, "FTP Server") {
            startActivity(Intent(this, FtpActivity::class.java))
        }
        val (dimRow, ds) = toolRow(p, R.drawable.ic_moon, p.indigo, "Làm tối màn hình") {
            startActivity(Intent(this, DimActivity::class.java))
        }
        val (scanRow, ss) = toolRow(p, R.drawable.ic_qr, p.teal, "Quét mã vạch / QR") {
            startActivity(Intent(this, ScanActivity::class.java))
        }
        ss.text = "Copy nội dung, mở liên kết"
        val (trRow, ts) = toolRow(p, R.drawable.ic_translate, p.orange, "Dịch màn hình") {
            startActivity(Intent(this, TranslateActivity::class.java))
        }
        val (shareRow, shs) = toolRow(p, R.drawable.ic_share_board, p.green, "Chia sẻ (văn bản + tệp)") {
            startActivity(Intent(this, ShareActivity::class.java))
        }
        shareSub = shs
        trSub = ts
        ftpSub = fs
        dimSub = ds
        col.addView(group(p, ftpRow, dimRow, scanRow, trRow, shareRow))
        col.addView(footnote(p, "FTP: chép file giữa máy tính và điện thoại qua Wi-Fi.\nLàm tối: phủ một lớp đen lên màn hình, tối hơn mức thấp nhất của hệ thống."))

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
    }

    override fun onResume() {
        super.onResume()
        ftpSub.text = if (FtpService.running) {
            Net.addresses().firstOrNull()?.let { "Đang chạy · ftp://${it.second}:${FtpService.port}" } ?: "Đang chạy"
        } else "Tắt"
        val pr = Prefs(this)
        trSub.text = (if (ScreenTranslateService.running) "Nút nổi đang bật · " else "") +
            "${Langs.SHORT[pr.trSrc]} → ${Langs.SHORT[pr.trDst]} · ${if (pr.trEngine == "online") "online" else "offline"}"
        shareSub.text = if (ShareService.running)
            Net.addresses().firstOrNull()?.let { "Đang chạy · http://${it.second}:${ShareService.PORT}" } ?: "Đang chạy"
        else "Tắt"
        dimSub.text = if (DimService.active) "Đang bật · ${DimOverlay.effective(Prefs(this).dimLevel)}%" else "Tắt"
    }
}
