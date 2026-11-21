package com.dsoft.toolbox

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Power menu. Every action says up front what it needs.
 *
 * Reboot and power off have a no-root path: the accessibility service opens the
 * system power menu ([DimAccessibility.powerDialog]). That is tried first, and
 * `su` is only used when it is unavailable (or when the user asks for root
 * first). Soft reboot / Recovery / Bootloader have no public API at all, so they
 * are root-only and labelled as such.
 */
class RestartActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var p: Palette
    private lateinit var prefs: Prefs

    private lateinit var rootStatus: TextView
    private lateinit var accStatus: TextView
    private val subs = HashMap<String, TextView>()

    /** null = still probing. */
    @Volatile private var hasRoot: Boolean? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        p = palette()
        prefs = Prefs(this)
        setupSystemBars(p)
        val col = page(p, "Khởi động lại")

        // ---- what is available right now ----
        col.addView(sectionHeader(p, "Cách thực hiện"))
        rootStatus = text("Đang kiểm tra…", 15f, p.secondary)
        accStatus = text("", 15f, p.secondary)
        col.addView(
            group(
                p,
                row(p, "Quyền root (su)", rootStatus),
                row(p, "Menu nguồn (Trợ năng)", accStatus) { openAccessibility() },
                row(
                    p, "Ưu tiên dùng root khi đã có",
                    iosSwitch(p, prefs.rebootPreferRoot) { on ->
                        prefs.rebootPreferRoot = on
                        refresh()
                    },
                ),
            ),
        )
        col.addView(
            footnote(
                p,
                "Mặc định app thử cách KHÔNG CẦN ROOT trước: mở sẵn menu nguồn của hệ thống để bạn " +
                    "chọn, dùng dịch vụ Trợ năng của Toolbox (cùng dịch vụ với chức năng Làm tối). " +
                    "Bật tuỳ chọn trên nếu muốn chạy thẳng lệnh root, đỡ phải bấm thêm một bước.",
            ),
        )

        // ---- actions that work without root ----
        col.addView(sectionHeader(p, "Không cần root"))
        col.addView(
            group(
                p,
                actionRow(
                    "reboot", "Khởi động lại", rootOnly = false,
                ) { power("reboot", "Khởi động lại máy?") },
                actionRow(
                    "poweroff", "Tắt nguồn", rootOnly = false,
                ) { power("reboot -p", "Tắt nguồn máy?") },
            ),
        )
        col.addView(
            footnote(
                p,
                "Hai mục này chạy được khi đã bật Trợ năng: app mở menu nguồn, bạn chọn " +
                    "\"Khởi động lại\" / \"Tắt nguồn\". Có root thì chạy thẳng, không cần bấm thêm.",
            ),
        )

        // ---- root-only actions ----
        col.addView(sectionHeader(p, "Bắt buộc root"))
        col.addView(
            group(
                p,
                actionRow("soft", "Khởi động lại nhanh (soft)", rootOnly = true) {
                    rootOnly("setprop ctl.restart zygote", "Khởi động lại nhanh (chỉ framework)?")
                },
                actionRow("recovery", "Vào Recovery", rootOnly = true) {
                    rootOnly("reboot recovery", "Khởi động vào Recovery?")
                },
                actionRow("bootloader", "Vào Bootloader / Fastboot", rootOnly = true) {
                    rootOnly("reboot bootloader", "Khởi động vào Bootloader (Fastboot)?")
                },
            ),
        )
        col.addView(
            footnote(
                p,
                "Android không có API công khai cho ba mục này nên bắt buộc phải có root (su).\n" +
                    "Soft reboot chỉ khởi động lại giao diện (nhanh, không tắt máy). " +
                    "Recovery/Bootloader là chế độ sửa chữa — chỉ dùng khi biết rõ.\n" +
                    "Không có root thì cắm máy tính và dùng ADB: " +
                    "adb reboot recovery / adb reboot bootloader.",
            ),
        )

        probeRoot()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    // ------------------------------------------------------------------- rows

    /** A row with a title, a live "cách chạy" subtitle and a ROOT / no-ROOT badge. */
    private fun actionRow(key: String, title: String, rootOnly: Boolean, onClick: () -> Unit): LinearLayout {
        val sub = text("", 13f, p.secondary).apply { setPadding(0, dp(3), 0, 0) }
        subs[key] = sub
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(58)
            setPadding(dp(16), dp(8), dp(12), dp(8))
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(text(title, 16f, p.text, 500))
                    addView(sub)
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(badge(rootOnly))
            addView(chevron(p))
            background = pressable(null, p.ripple)
            setOnClickListener { onClick() }
            dpadFocus(p)
        }
    }

    private fun badge(rootOnly: Boolean): TextView =
        text(if (rootOnly) "ROOT" else "KHÔNG ROOT", 11f, Color.WHITE, 700).apply {
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = rounded(if (rootOnly) p.red else p.green, dp(7).toFloat())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = dp(6) }
        }

    // ----------------------------------------------------------------- status

    private fun probeRoot() {
        Thread {
            val ok = Root.available()
            hasRoot = ok
            main.post { refresh() }
        }.start()
    }

    private fun refresh() {
        val acc = DimAccessibility.instance != null
        accStatus.text = if (acc) "Đã bật" else "Chưa bật ›"
        accStatus.setTextColor(if (acc) p.secondary else p.orange)

        rootStatus.text = when (hasRoot) {
            null -> "Đang kiểm tra…"
            true -> "Đã có"
            else -> "Chưa có"
        }
        rootStatus.setTextColor(if (hasRoot == true) p.secondary else p.orange)

        val viaRoot = prefs.rebootPreferRoot && hasRoot == true
        val softSub = when {
            viaRoot -> "Chạy lệnh root"
            acc -> "Mở menu nguồn của hệ thống"
            hasRoot == true -> "Chạy lệnh root (chưa bật Trợ năng)"
            hasRoot == null -> "Đang kiểm tra cách chạy…"
            else -> "Chưa chạy được — bật Trợ năng hoặc cấp root"
        }
        subs["reboot"]?.text = softSub
        subs["poweroff"]?.text = softSub

        val rootSub = when (hasRoot) {
            null -> "Đang kiểm tra quyền root…"
            true -> "Chạy lệnh root"
            else -> "Chưa chạy được — cần cấp quyền root (su)"
        }
        listOf("soft", "recovery", "bootloader").forEach { subs[it]?.text = rootSub }
    }

    // ---------------------------------------------------------------- actions

    /** Reboot / power off: no-root path first, root as the fallback. */
    private fun power(cmd: String, msg: String) {
        val acc = DimAccessibility.instance != null
        val viaRoot = prefs.rebootPreferRoot && hasRoot == true
        if (!acc && hasRoot == false) {
            needSomething()
            return
        }
        val how = if (viaRoot || !acc) "Sẽ chạy lệnh root." else "Sẽ mở menu nguồn của hệ thống để bạn chọn."
        confirm(msg, how) {
            if (!viaRoot && acc && DimAccessibility.instance?.powerDialog() == true) {
                toast("Đã mở menu nguồn — chọn mục bạn cần")
                return@confirm
            }
            runRoot(cmd, offerAccessibility = true)
        }
    }

    /** No public API exists for these, so root is the only way. */
    private fun rootOnly(cmd: String, msg: String) {
        if (hasRoot == false) {
            toast("Mục này bắt buộc có root (su)")
            return
        }
        confirm(msg, "Mục này bắt buộc quyền root.") { runRoot(cmd, offerAccessibility = false) }
    }

    private fun confirm(msg: String, how: String, onYes: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle("Xác nhận")
            .setMessage("$msg\n\n$how")
            .setPositiveButton("Đồng ý") { _, _ -> onYes() }
            .setNegativeButton("Huỷ", null)
            .show()
    }

    private fun runRoot(cmd: String, offerAccessibility: Boolean) {
        toast("Đang gửi lệnh…")
        Thread {
            val ok = Root.runAsRoot(cmd)
            // If the device really reboots we never get here; false means no root / failed.
            if (!ok) {
                hasRoot = false
                main.post {
                    refresh()
                    if (offerAccessibility) needSomething()
                    else toast("Không chạy được — cần cấp quyền root (su).")
                }
            }
        }.start()
    }

    private fun needSomething() {
        AlertDialog.Builder(this)
            .setTitle("Chưa chạy được")
            .setMessage(
                "Máy chưa cấp quyền root. Cách không cần root: bật dịch vụ Trợ năng của Toolbox, " +
                    "app sẽ mở sẵn menu nguồn của hệ thống để bạn chọn Khởi động lại / Tắt nguồn.",
            )
            .setNegativeButton("Để sau", null)
            .setPositiveButton("Mở Trợ năng") { _, _ -> openAccessibility() }
            .show()
    }

    private fun openAccessibility() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (_: Exception) {
            toast("Không mở được trang Trợ năng")
        }
    }
}
