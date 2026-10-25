package com.dsoft.odiary

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Launcher: pick/create an account. Google accounts are added from the main screen, not here. */
class AccountActivity : Activity() {
    private lateinit var p: Palette
    private lateinit var listBox: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        p = palette()
        window.statusBarColor = p.bg
        build()
    }

    private fun build() {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(p.bg); setPadding(dp(20), dp(28), dp(20), dp(24)) }
        col.addView(text("ODiary", 30f, p.accent, 700))
        col.addView(text("Chọn tài khoản để viết nhật ký", 14f, p.sub).apply { setPadding(0, dp(4), 0, dp(16)) })

        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(listBox)

        col.addView(bigButton("＋ Tạo tài khoản (mã PIN)", p.accent, p.accent2) { createLocal() }, lpTop(dp(18)))
        col.addView(text("Tài khoản local: riêng tư, cần mã PIN, ngoại tuyến.\nĐăng nhập Google ở màn hình chính để đồng bộ Drive.", 12f, p.sub).apply { setPadding(dp(2), dp(14), 0, 0) })

        setContentView(ScrollView(this).apply { setBackgroundColor(p.bg); addView(col) })
        renderList()
    }

    private fun lpTop(t: Int) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = t }

    private fun renderList() {
        listBox.removeAllViews()
        val accts = Accounts.all()
        if (accts.isEmpty()) { listBox.addView(text("Chưa có tài khoản — tạo một cái bên dưới.", 14f, p.sub)); return }
        accts.forEach { a ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                background = rounded(p.card, dp(16).toFloat()); setPadding(dp(16), dp(14), dp(14), dp(14))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) }
                setOnClickListener { open(a) }
            }
            // avatar circle
            row.addView(text(if (a.isGoogle) "G" else "🔒", 18f, android.graphics.Color.WHITE, 700).apply {
                gravity = Gravity.CENTER; background = rounded(if (a.isGoogle) p.accent2 else p.accent, dp(22).toFloat())
                layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(12) }
            })
            val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            info.addView(text(a.name, 17f, p.text, 600))
            info.addView(text(if (a.isGoogle) a.email else "Local · cần PIN", 12f, p.sub))
            row.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            // badge
            row.addView(text(if (a.isGoogle) "☁ Google" else "offline", 11f, if (a.isGoogle) p.accent2 else p.sub, 600).apply {
                setPadding(dp(8), dp(4), dp(8), dp(4)); background = rounded(p.fill, dp(10).toFloat())
            })
            row.addView(text("  ✕", 14f, p.red).apply { setPadding(dp(8), dp(8), dp(4), dp(8)); setOnClickListener { confirmRemove(a) } })
            listBox.addView(row)
        }
    }

    private fun open(a: Account) {
        if (a.isGoogle) { enter(a); return }
        // local: ask PIN
        val et = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD; hint = "Nhập PIN"; setTextColor(p.text) }
        AlertDialog.Builder(this).setTitle("PIN cho \"${a.name}\"").setView(et)
            .setPositiveButton("Vào") { _, _ ->
                if (Accounts.checkPin(a, et.text.toString())) enter(a) else Toast.makeText(this, "Sai PIN", Toast.LENGTH_SHORT).show()
            }.setNegativeButton("Huỷ", null).show()
    }

    private fun enter(a: Account) {
        Prefs.setCurrentAccount(this, a.id)
        Store.setAccount(a.id)
        if (a.isGoogle) Prefs.setPendingPull(this, true)   // reconcile with Drive (pull + push) on entry
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun createLocal() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(10), dp(18), dp(4)) }
        val name = EditText(this).apply { hint = "Tên hiển thị"; setTextColor(p.text) }
        val pin = EditText(this).apply { hint = "Đặt PIN (4-6 số)"; inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD; setTextColor(p.text) }
        box.addView(name); box.addView(pin)
        AlertDialog.Builder(this).setTitle("Tạo tài khoản local").setView(box)
            .setPositiveButton("Tạo") { _, _ ->
                val pv = pin.text.toString()
                if (pv.length < 4) { Toast.makeText(this, "PIN tối thiểu 4 số", Toast.LENGTH_SHORT).show(); return@setPositiveButton }
                enter(Accounts.createLocal(name.text.toString(), pv))
            }.setNegativeButton("Huỷ", null).show()
    }

    private fun confirmRemove(a: Account) {
        // Local accounts need their PIN before deletion.
        if (!a.isGoogle) {
            val et = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD; hint = "Nhập PIN để xoá"; setTextColor(p.text) }
            AlertDialog.Builder(this).setTitle("Xoá \"${a.name}\"?").setView(et)
                .setMessage("Nhập đúng PIN mới xoá được. Dữ liệu của tài khoản này cũng mất.")
                .setPositiveButton("Xoá") { _, _ ->
                    if (Accounts.checkPin(a, et.text.toString())) { Accounts.remove(a.id); renderList() }
                    else Toast.makeText(this, "Sai PIN", Toast.LENGTH_SHORT).show()
                }.setNegativeButton("Huỷ", null).show()
            return
        }
        AlertDialog.Builder(this).setTitle("Xoá tài khoản?").setMessage("${a.name} — chỉ gỡ khỏi máy này.")
            .setPositiveButton("Xoá") { _, _ -> Accounts.remove(a.id); renderList() }.setNegativeButton("Huỷ", null).show()
    }
}
