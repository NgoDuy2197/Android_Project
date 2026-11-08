package com.dsoft.healthassistant

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Launcher: pick which user to continue as, or create a new one. */
class UserPickerActivity : Activity() {
    private lateinit var p: Palette

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        p = palette()
        render()
    }

    private fun render() {
        val col = page(p, "HealthAssistant", back = false)
        col.addView(text("Chọn người dùng để tiếp tục, hoặc tạo người mới.", 15f, p.secondary)
            .apply { setPadding(dp(4), 0, dp(4), dp(10)) })

        val users = Users.list(this)
        if (users.isEmpty()) {
            col.addView(card(p).apply {
                setPadding(dp(16), dp(22), dp(16), dp(22)); gravity = Gravity.CENTER_HORIZONTAL
                addView(icon(R.drawable.ic_people, p.secondary, 44).apply {
                    (layoutParams as LinearLayout.LayoutParams).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(8) }
                })
                addView(text("Chưa có người dùng nào", 17f, p.text, 600).apply { gravity = Gravity.CENTER })
                addView(text("Tạo hồ sơ đầu tiên để bắt đầu theo dõi bữa ăn.", 14f, p.secondary).apply {
                    gravity = Gravity.CENTER; setPadding(0, dp(4), 0, 0)
                })
            }, vlp().apply { bottomMargin = dp(14) })
        } else {
            col.addView(sectionHeader(p, "Người dùng (${users.size})"))
            col.addView(group(p, *users.map { userRow(it) }.toTypedArray()))
            col.addView(footnote(p, "Chạm để vào · Nhấn giữ để đổi tên hoặc xoá."))
        }

        col.addView(bigIconButton("Tạo người dùng mới", R.drawable.ic_add, p.green, p.teal) { createDialog() },
            vlp().apply { topMargin = dp(16) })
    }

    private fun userRow(u: User): LinearLayout {
        val sub = "Tạo ngày " + SimpleDateFormat("dd/MM/yyyy", Locale("vi")).format(Date(u.createdAt))
        val current = Users.currentId(this) == u.id
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(60); setPadding(dp(14), dp(8), dp(12), dp(8))
            background = pressable(null, p.ripple)
            setOnClickListener { enter(u) }
            setOnLongClickListener { manageDialog(u); true }
            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_person)
                imageTintList = ColorStateList.valueOf(Color.WHITE)
                val pd = dp(7); setPadding(pd, pd, pd, pd)
                background = gradient(dpf(10), p.green, p.teal)
                clipRounded()
                layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(14) }
            })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(text(u.name + if (current) "  ·  đang dùng" else "", 17f, p.text, 600))
                addView(text(sub, 13f, p.secondary).apply { setPadding(0, dp(2), 0, 0) })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(chevron(p))
        }
    }

    private fun enter(u: User) {
        Users.setCurrent(this, u.id)
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun nameDialog(title: String, prefill: String, onOk: (String) -> Unit) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setText(prefill); setSelection(text.length)
            hint = "Tên người dùng"
        }
        val box = LinearLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(input) }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(box)
            .setPositiveButton("Lưu") { _, _ ->
                val n = input.text.toString().trim()
                if (n.isBlank()) toast("Hãy nhập tên.") else onOk(n)
            }
            .setNegativeButton("Huỷ", null)
            .show()
    }

    private fun createDialog() = nameDialog("Người dùng mới", "") { name ->
        enter(Users.create(this, name))
    }

    private fun manageDialog(u: User) {
        AlertDialog.Builder(this)
            .setTitle(u.name)
            .setItems(arrayOf("Đổi tên", "Xoá người dùng")) { _, which ->
                if (which == 0) nameDialog("Đổi tên", u.name) { Users.rename(this, u.id, it); render() }
                else deleteConfirm(u)
            }
            .show()
    }

    private fun deleteConfirm(u: User) {
        AlertDialog.Builder(this)
            .setTitle("Xoá “${u.name}”?")
            .setMessage("Toàn bộ hồ sơ và bữa ăn của người này sẽ bị xoá. Không thể hoàn tác.")
            .setPositiveButton("Xoá") { _, _ -> Users.delete(this, u.id); toast("Đã xoá ${u.name}"); render() }
            .setNegativeButton("Huỷ", null)
            .show()
    }
}
