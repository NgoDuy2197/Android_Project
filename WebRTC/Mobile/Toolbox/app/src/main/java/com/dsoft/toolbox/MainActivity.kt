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
        val (flashRow, flashSub) = toolRow(p, R.drawable.ic_sos, p.red, "Hỗ trợ khẩn cấp") {
            startActivity(Intent(this, EmergencyActivity::class.java))
        }
        flashSub.text = "Đèn (nháy/SOS) · còi báo động"
        val (netRow, netSub) = toolRow(p, R.drawable.ic_network, p.teal, "Mạng & tốc độ") {
            startActivity(Intent(this, NetworkActivity::class.java))
        }
        netSub.text = "IP nội bộ/công cộng, Wi-Fi, test tốc độ"
        val (domainRow, domainSub) = toolRow(p, R.drawable.ic_domain, p.blue, "Tra cứu domain") {
            startActivity(Intent(this, DomainActivity::class.java))
        }
        domainSub.text = "IP qua DNS máy & công cộng, cổng mở"
        val (ocrRow, ocrSub) = toolRow(p, R.drawable.ic_ocr, p.green, "Chép chữ từ ảnh") {
            startActivity(Intent(this, OcrCopyActivity::class.java))
        }
        ocrSub.text = "Chụp/chọn ảnh → nhận chữ → copy"
        val (speechRow, speechSub) = toolRow(p, R.drawable.ic_mic, p.pink, "Giọng nói → chữ") {
            startActivity(Intent(this, SpeechActivity::class.java))
        }
        speechSub.text = "Ghi âm chuyển text, dịch tùy chọn"
        val (levelRow, levelSub) = toolRow(p, R.drawable.ic_ruler, p.indigo, "Thước & đo") {
            startActivity(Intent(this, LevelActivity::class.java))
        }
        levelSub.text = "Bọt nước · đo khoảng cách bằng ảnh"
        val (audioRow, audioSub) = toolRow(p, R.drawable.ic_audio, p.pink, "Âm thanh") {
            startActivity(Intent(this, AudioActivity::class.java))
        }
        audioSub.text = "Chuyển loa ngoài/trong · tăng âm vượt mức"
        val (infoRow, infoSub) = toolRow(p, R.drawable.ic_info, p.purple, "Thông tin thiết bị") {
            startActivity(Intent(this, DeviceInfoActivity::class.java))
        }
        infoSub.text = "Máy, pin, RAM, bộ nhớ, mạng"
        val (restartRow, restartSub) = toolRow(p, R.drawable.ic_restart, p.red, "Khởi động lại") {
            startActivity(Intent(this, RestartActivity::class.java))
        }
        restartSub.text = "Reboot · Recovery · Fastboot · Tắt nguồn (root)"
        shareSub = shs
        trSub = ts
        ftpSub = fs
        dimSub = ds
        col.addView(group(p, ftpRow, dimRow, scanRow, trRow, ocrRow, speechRow, shareRow, flashRow, netRow, domainRow, levelRow, audioRow, infoRow, restartRow))
        col.addView(footnote(p, "FTP: chép file giữa máy tính và điện thoại qua Wi-Fi.\nLàm tối: phủ một lớp đen lên màn hình, tối hơn mức thấp nhất của hệ thống.\nKhởi động lại: cần quyền root."))

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
