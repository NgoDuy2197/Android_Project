package com.dsoft.toolbox

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import android.text.format.Formatter
import android.widget.LinearLayout
import android.widget.TextView

/** Read-only device / battery / storage / network info — tap a row to copy. */
class DeviceInfoActivity : Activity() {
    private val rows = ArrayList<Pair<String, String>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val p = palette()
        setupSystemBars(p)
        val col = page(p, "Thông tin thiết bị")

        add("Thiết bị", "${Build.MANUFACTURER} ${Build.MODEL}")
        add("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        add("Bản dựng", Build.DISPLAY)
        add("CPU", "${Runtime.getRuntime().availableProcessors()} nhân · ${Build.SUPPORTED_ABIS.firstOrNull() ?: "?"}")

        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        add("RAM", "${fmt(mi.availMem)} trống / ${fmt(mi.totalMem)}")

        add("Bộ nhớ trong", storage(Environment.getDataDirectory().path))
        Environment.getExternalStorageDirectory()?.let { add("Bộ nhớ chung", storage(it.path)) }

        val dm = resources.displayMetrics
        add("Màn hình", "${dm.widthPixels}×${dm.heightPixels} · ${dm.densityDpi} dpi")

        battery()?.let { for ((k, v) in it) add(k, v) }

        add("Mạng", Net.addresses().firstOrNull()?.let { "${it.first}: ${it.second}" } ?: "Không có")
        add("Uptime", fmtDur(SystemClock.elapsedRealtime()))

        col.addView(group(p, *rows.map { (k, v) ->
            row(p, k, text(v, 15f, p.secondary).apply { maxWidth = dp(200); maxLines = 2 }) { copyText("$k: $v"); toast("Đã copy") }
        }.toTypedArray()))
        col.addView(bigButton("Copy tất cả", p.blue, p.blue) {
            copyText(rows.joinToString("\n") { "${it.first}: ${it.second}" }); toast("Đã copy toàn bộ")
        }, vlp(-1, -2).apply { topMargin = dp(12); marginStart = dp(4); marginEnd = dp(4) })
        col.addView(footnote(p, "Chạm một dòng để copy. Thông tin đọc trực tiếp từ hệ thống."))
    }

    private fun add(k: String, v: String) { rows.add(k to v) }

    private fun storage(path: String): String = try {
        val s = StatFs(path)
        "${fmt(s.availableBytes)} trống / ${fmt(s.totalBytes)}"
    } catch (_: Exception) { "?" }

    private fun fmt(b: Long) = Formatter.formatShortFileSize(this, b)

    private fun battery(): List<Pair<String, String>>? {
        val i = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
        val temp = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)   // tenths of °C
        val volt = i.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)       // mV
        val tech = i.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY) ?: "?"
        val health = when (i.getIntExtra(BatteryManager.EXTRA_HEALTH, 0)) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "Tốt"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Quá nóng"
            BatteryManager.BATTERY_HEALTH_DEAD -> "Hỏng"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Quá áp"
            BatteryManager.BATTERY_HEALTH_COLD -> "Lạnh"
            else -> "?"
        }
        val plugged = when (i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)) {
            BatteryManager.BATTERY_PLUGGED_AC -> "Sạc AC"
            BatteryManager.BATTERY_PLUGGED_USB -> "Sạc USB"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Sạc không dây"
            else -> "Dùng pin"
        }
        val out = ArrayList<Pair<String, String>>()
        out.add("Pin" to (if (pct >= 0) "$pct% · $plugged" else plugged))
        if (temp > 0) out.add("Nhiệt độ pin" to String.format("%.1f°C", temp / 10f))
        if (volt > 0) out.add("Điện áp pin" to "$volt mV")
        out.add("Tình trạng pin" to "$health · $tech")
        return out
    }

    private fun fmtDur(ms: Long): String {
        val s = ms / 1000; val h = s / 3600; val m = (s % 3600) / 60
        return if (h > 0) "${h}h ${m}m" else "${m}m"
    }
}
