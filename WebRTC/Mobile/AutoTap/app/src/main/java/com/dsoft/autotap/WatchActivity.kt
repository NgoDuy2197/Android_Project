package com.dsoft.autotap

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView

/**
 * Settings for the text watcher built into [AutoClickService]: the list of texts
 * to tap, how to match them, and the permission it needs — checked and granted
 * from here, since the watcher itself runs in the background with no UI.
 */
class WatchActivity : Activity() {
    private lateinit var p: Palette
    private lateinit var prefs: Prefs

    private lateinit var onSwitch: Switch
    private lateinit var accStatus: TextView
    private lateinit var stateNote: TextView
    private lateinit var rulesBox: LinearLayout
    private lateinit var logBox: LinearLayout
    private lateinit var appsValue: TextView
    private var syncing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        p = palette()
        prefs = Prefs(this)
        val col = page(p, "Tự động bấm chữ")

        // ---- master switch ----
        onSwitch = iosSwitch(p, prefs.watchOn) { on -> if (!syncing) setOn(on) }
        col.addView(group(p, row(p, "Bật tự động bấm chữ", onSwitch)))
        stateNote = text("", 13f, p.secondary).apply { setPadding(dp(16), dp(7), dp(16), 0) }
        col.addView(stateNote)
        col.addView(
            footnote(
                p,
                "Khác với bước \"Chạm theo chữ\" trong kịch bản: cái này chạy nền liên tục, " +
                    "đọc chữ thẳng từ cây giao diện (không cần OCR, không cần quyền ghi màn hình) " +
                    "nên chạy được cả trên tivi không có cảm ứng.",
            ),
        )

        // ---- permission ----
        col.addView(sectionHeader(p, "Quyền"))
        accStatus = text("", 15f, p.secondary)
        col.addView(
            group(p, row(p, "Dịch vụ Trợ năng AutoTap", accStatus) { Access.openAccessibilitySettings(this) }),
        )
        col.addView(
            card(p).apply {
                setPadding(dp(16), dp(14), dp(16), dp(14))
                addView(
                    bigButton("Mở Cài đặt › Trợ năng", p.blue, p.indigo) {
                        Access.openAccessibilitySettings(this@WatchActivity)
                    },
                    vlp(),
                )
            },
            vlp().apply { topMargin = dp(10) },
        )
        col.addView(
            footnote(
                p,
                "Dùng chung dịch vụ trợ năng với phần chạy kịch bản — bật một lần là đủ.\n" +
                    "Điện thoại: Cài đặt › Trợ năng › Ứng dụng đã tải xuống. " +
                    "Tivi: Cài đặt › Hệ thống › Trợ năng.\n" +
                    "Nếu mục đó bị mờ (\"Cài đặt bị hạn chế\"): Cài đặt › Ứng dụng › AutoTap › ⋮ › " +
                    "Cho phép cài đặt bị hạn chế, rồi bật lại.\n" +
                    "Khi một kịch bản đang chạy, phần tự bấm chữ tạm nghỉ để không bấm chồng lên nhau.",
            ),
        )

        // ---- the list ----
        col.addView(sectionHeader(p, "Danh sách chữ cần bấm"))
        rulesBox = card(p)
        col.addView(rulesBox)
        col.addView(
            footnote(
                p,
                "Chạm vào một dòng để sửa. \"Đúng y hệt\" = chữ trên màn hình phải giống hệt; " +
                    "\"Chứa\" = chỉ cần có đoạn chữ này bên trong.",
            ),
        )

        // ---- matching options ----
        col.addView(sectionHeader(p, "Cách khớp"))
        val caseSwitch = iosSwitch(p, prefs.watchCaseSensitive) { on ->
            if (syncing) return@iosSwitch
            prefs.watchCaseSensitive = on
            AutoClickService.refreshWatch(this)
        }
        val clickableSwitch = iosSwitch(p, prefs.watchClickableOnly) { on ->
            if (syncing) return@iosSwitch
            prefs.watchClickableOnly = on
            AutoClickService.refreshWatch(this)
        }
        col.addView(
            group(
                p,
                row(p, "Phân biệt chữ hoa / chữ thường", caseSwitch),
                row(p, "Chỉ bấm vào nút bấm được", clickableSwitch),
            ),
        )
        col.addView(
            footnote(
                p,
                "Phân biệt hoa thường: bật thì \"Skip\" không khớp với \"skip\" hay \"SKIP\".\n" +
                    "Chỉ bấm nút bấm được: tắt thì khi chữ không phải nút, app sẽ chạm thẳng vào " +
                    "vị trí chữ đó (tivi không có cảm ứng thì cách này không chạy).",
            ),
        )

        // ---- timing ----
        col.addView(sectionHeader(p, "Tốc độ"))
        col.addView(
            group(
                p,
                row(
                    p, "Quét lại mỗi (ms)",
                    inlineField(p, prefs.watchScanMs.toString(), numeric = true) { v ->
                        prefs.watchScanMs = v.toIntOrNull() ?: 600
                        AutoClickService.refreshWatch(this)
                    },
                ),
                row(
                    p, "Nghỉ giữa 2 lần bấm (ms)",
                    inlineField(p, prefs.watchCooldownMs.toString(), numeric = true) { v ->
                        prefs.watchCooldownMs = v.toIntOrNull() ?: 1500
                        AutoClickService.refreshWatch(this)
                    },
                ),
            ),
        )
        col.addView(
            footnote(
                p,
                "Quét lại: 0 = chỉ bấm khi hệ thống báo màn hình đổi (ít tốn pin nhất). " +
                    "150–10000 ms = quét thêm theo chu kỳ, bắt được cả nút hiện ra im lặng.\n" +
                    "Nghỉ: cùng một chữ chỉ được bấm lại sau khoảng này.",
            ),
        )

        // ---- app filter ----
        col.addView(sectionHeader(p, "Chỉ chạy trong ứng dụng"))
        appsValue = text("", 15f, p.secondary)
        col.addView(
            group(
                p,
                row(p, "Ứng dụng được chọn", appsValue) { pickApps() },
                row(p, "Bỏ giới hạn (chạy mọi ứng dụng)") {
                    prefs.watchApps = emptySet()
                    AutoClickService.refreshWatch(this)
                    refresh()
                },
            ),
        )
        col.addView(footnote(p, "Để trống = chạy trong mọi ứng dụng."))

        // ---- log ----
        col.addView(sectionHeader(p, "Nhật ký"))
        val toastSwitch = iosSwitch(p, prefs.watchToast) { on ->
            if (syncing) return@iosSwitch
            prefs.watchToast = on
            AutoClickService.refreshWatch(this)
        }
        col.addView(group(p, row(p, "Hiện thông báo mỗi lần bấm", toastSwitch)))
        logBox = card(p).apply { setPadding(dp(14), dp(12), dp(14), dp(12)) }
        col.addView(logBox, vlp().apply { topMargin = dp(10) })
        col.addView(
            card(p).apply {
                setPadding(dp(16), dp(14), dp(16), dp(14))
                addView(
                    bigButton("Xoá nhật ký", p.separator, p.separator) {
                        AutoClickService.clearWatchLog()
                        refresh()
                    },
                    vlp(),
                )
            },
            vlp().apply { topMargin = dp(10) },
        )
    }

    override fun onResume() {
        super.onResume()
        AutoClickService.listener = { runOnUiThread { refresh() } }
        refresh()
    }

    override fun onPause() {
        super.onPause()
        AutoClickService.listener = null
    }

    // ------------------------------------------------------------------ state

    private fun refresh() {
        val acc = AutoClickService.isEnabled()
        syncing = true
        onSwitch.isChecked = prefs.watchOn
        syncing = false

        accStatus.text = if (acc) "Đã bật" else "Chưa bật ›"
        accStatus.setTextColor(if (acc) p.secondary else p.orange)

        val n = prefs.watchRules.count { it.enabled && it.text.isNotBlank() }
        stateNote.text = when {
            !acc -> "Chưa chạy — cần bật dịch vụ Trợ năng ở mục Quyền bên dưới."
            !prefs.watchOn -> "Dịch vụ đã bật nhưng chức năng này đang tắt."
            n == 0 -> "Đang bật nhưng danh sách chưa có chữ nào."
            AutoClickService.running -> "Tạm nghỉ — một kịch bản đang chạy."
            else -> "Đang chạy nền · theo dõi $n chữ · đã bấm ${AutoClickService.watchTaps} lần."
        }
        stateNote.setTextColor(if (acc && prefs.watchOn && n > 0) p.green else p.orange)

        val apps = prefs.watchApps
        appsValue.text = when {
            apps.isEmpty() -> "Mọi ứng dụng ›"
            apps.size == 1 -> "${label(apps.first())} ›"
            else -> "${apps.size} ứng dụng ›"
        }

        buildRules()
        buildLog()
    }

    private fun buildRules() {
        rulesBox.removeAllViews()
        val rules = prefs.watchRules
        rules.forEachIndexed { i, r ->
            if (i > 0) rulesBox.addView(separator(p))
            rulesBox.addView(ruleRow(i, r))
        }
        if (rules.isNotEmpty()) rulesBox.addView(separator(p))
        rulesBox.addView(
            row(p, "+  Thêm chữ") { editRule(-1) }.apply {
                (getChildAt(0) as TextView).setTextColor(p.blue)
            },
        )
    }

    /** One row: on/off switch, the text + mode, and a delete button. */
    private fun ruleRow(index: Int, rule: WatchRule): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(54)
        setPadding(dp(12), dp(6), dp(8), dp(6))
        background = pressable(null, p.ripple)
        dpadFocus(p)
        setOnClickListener { editRule(index) }

        addView(
            iosSwitch(p, rule.enabled) { on ->
                val list = prefs.watchRules.toMutableList()
                if (index in list.indices) {
                    list[index].enabled = on
                    prefs.watchRules = list
                    AutoClickService.refreshWatch(this@WatchActivity)
                }
            }.apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { marginEnd = dp(6) }
            },
        )
        addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(text(rule.text.ifBlank { "(trống)" }, 17f, p.text, 500))
                addView(text(rule.modeLabel, 13f, p.secondary).apply { setPadding(0, dp(3), 0, 0) })
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        addView(
            text("Xoá", 15f, p.red, 500).apply {
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = pressable(null, p.ripple)
                dpadFocus(p)
                setOnClickListener { deleteRule(index) }
            },
        )
    }

    private fun deleteRule(index: Int) {
        val list = prefs.watchRules.toMutableList()
        if (index !in list.indices) return
        AlertDialog.Builder(this)
            .setTitle("Xoá chữ")
            .setMessage("Bỏ \"${list[index].text}\" khỏi danh sách?")
            .setNegativeButton("Huỷ", null)
            .setPositiveButton("Xoá") { _, _ ->
                list.removeAt(index)
                prefs.watchRules = list
                AutoClickService.refreshWatch(this)
                refresh()
            }
            .show()
    }

    /** [index] < 0 adds a new entry. */
    private fun editRule(index: Int) {
        val list = prefs.watchRules.toMutableList()
        val rule = list.getOrNull(index)?.copy() ?: WatchRule("")
        val field = EditText(this).apply {
            setText(rule.text)
            setSelection(rule.text.length)
            hint = "Ví dụ: Skip Ads"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setTextColor(p.text)
            setHintTextColor(p.secondary)
            // No auto-capitalise / auto-correct: the text is matched letter for letter.
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine(true)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(12), dp(22), dp(4))
            addView(field, vlp())
            addView(
                text("Chữ phải khớp", 13f, p.secondary).apply { setPadding(0, dp(18), 0, dp(8)) },
                vlp(),
            )
            addView(
                Segmented(context, p, listOf("Đúng y hệt", "Chứa"), if (rule.exact) 0 else 1) {
                    rule.exact = it == 0
                },
                vlp(),
            )
            addView(
                text(
                    "Khớp cả chữ mô tả nút (content description). Phân biệt hoa/thường theo " +
                        "cài đặt ở mục \"Cách khớp\".",
                    12f, p.secondary,
                ).apply { setPadding(0, dp(12), 0, 0) },
                vlp(),
            )
        }
        AlertDialog.Builder(this)
            .setTitle(if (index < 0) "Thêm chữ" else "Sửa chữ")
            .setView(box)
            .setNegativeButton("Huỷ", null)
            .setPositiveButton("Lưu") { _, _ ->
                // Keep the text exactly as typed apart from stray outer spaces.
                rule.text = field.text.toString().trim()
                if (rule.text.isEmpty()) {
                    toast("Chưa nhập chữ nào")
                    return@setPositiveButton
                }
                if (index in list.indices) list[index] = rule else list.add(rule)
                prefs.watchRules = list
                AutoClickService.refreshWatch(this)
                refresh()
            }
            .show()
    }

    // ------------------------------------------------------------- app filter

    private fun pickApps() {
        val apps = launchableApps()
        if (apps.isEmpty()) {
            toast("Không đọc được danh sách ứng dụng")
            return
        }
        val chosen = prefs.watchApps
        val names = apps.map { it.second }.toTypedArray()
        val checked = BooleanArray(apps.size) { apps[it].first in chosen }
        AlertDialog.Builder(this)
            .setTitle("Chọn ứng dụng")
            .setMultiChoiceItems(names, checked) { _, which, isOn -> checked[which] = isOn }
            .setNegativeButton("Huỷ", null)
            .setPositiveButton("Lưu") { _, _ ->
                prefs.watchApps = apps.filterIndexed { i, _ -> checked[i] }.map { it.first }.toSet()
                AutoClickService.refreshWatch(this)
                refresh()
            }
            .show()
    }

    /** package name to app label, for everything with a launcher icon (phone or TV). */
    private fun launchableApps(): List<Pair<String, String>> {
        val pm = packageManager
        val out = LinkedHashMap<String, String>()
        for (category in listOf(Intent.CATEGORY_LAUNCHER, "android.intent.category.LEANBACK_LAUNCHER")) {
            val i = Intent(Intent.ACTION_MAIN).addCategory(category)
            val found = try { pm.queryIntentActivities(i, 0) } catch (_: Throwable) { emptyList() }
            for (r in found) {
                val pkg = r.activityInfo?.packageName ?: continue
                if (pkg == packageName) continue
                out.getOrPut(pkg) { r.loadLabel(pm)?.toString() ?: pkg }
            }
        }
        return out.entries.sortedBy { it.value.lowercase() }.map { it.key to it.value }
    }

    private fun label(pkg: String): String = try {
        val ai: ApplicationInfo = packageManager.getApplicationInfo(pkg, 0)
        packageManager.getApplicationLabel(ai).toString()
    } catch (_: PackageManager.NameNotFoundException) {
        pkg
    }

    // -------------------------------------------------------------------- log

    private fun buildLog() {
        logBox.removeAllViews()
        val lines = AutoClickService.watchLogLines()
        if (lines.isEmpty()) {
            logBox.addView(text("Chưa bấm lần nào.", 15f, p.secondary), vlp())
            return
        }
        lines.forEach { line ->
            logBox.addView(
                text(line, 13f, p.secondary).apply {
                    setPadding(0, dp(4), 0, dp(4))
                    typeface = android.graphics.Typeface.MONOSPACE
                },
                vlp(),
            )
        }
    }

    // ------------------------------------------------------------------ misc

    private fun setOn(on: Boolean) {
        prefs.watchOn = on
        if (on && !AutoClickService.isEnabled()) {
            toast("Cần bật dịch vụ Trợ năng AutoTap")
            Access.openAccessibilitySettings(this)
        }
        AutoClickService.refreshWatch(this)
        refresh()
    }
}
