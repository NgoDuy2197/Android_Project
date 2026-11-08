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
        col.addView(text(tr("Chọn người dùng để tiếp tục, hoặc tạo người mới.", "Choose a user to continue, or create a new one."), 15f, p.secondary)
            .apply { setPadding(dp(4), 0, dp(4), dp(10)) })

        val users = Users.list(this)
        if (users.isEmpty()) {
            col.addView(card(p).apply {
                setPadding(dp(16), dp(22), dp(16), dp(22)); gravity = Gravity.CENTER_HORIZONTAL
                addView(icon(R.drawable.ic_people, p.secondary, 44).apply {
                    (layoutParams as LinearLayout.LayoutParams).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(8) }
                })
                addView(text(tr("Chưa có người dùng nào", "No users yet"), 17f, p.text, 600).apply { gravity = Gravity.CENTER })
                addView(text(tr("Tạo hồ sơ đầu tiên để bắt đầu theo dõi bữa ăn.", "Create the first profile to start tracking meals."), 14f, p.secondary).apply {
                    gravity = Gravity.CENTER; setPadding(0, dp(4), 0, 0)
                })
            }, vlp().apply { bottomMargin = dp(14) })
        } else {
            col.addView(sectionHeader(p, tr("Người dùng (${users.size})", "Users (${users.size})")))
            col.addView(group(p, *users.map { userRow(it) }.toTypedArray()))
            col.addView(footnote(p, tr("Chạm để vào · Nhấn giữ để đổi tên hoặc xoá.", "Tap to enter · long-press to rename or delete.")))
        }

        col.addView(bigIconButton(tr("Tạo người dùng mới", "Create new user"), R.drawable.ic_add, p.accent, p.accent2) { createDialog() },
            vlp().apply { topMargin = dp(16) })
    }

    private fun userRow(u: User): LinearLayout {
        val fmt = SimpleDateFormat(if (I18n.en) "MMM d, yyyy" else "dd/MM/yyyy", if (I18n.en) Locale.ENGLISH else Locale("vi"))
        val sub = tr("Tạo ngày ", "Created ") + fmt.format(Date(u.createdAt))
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
                background = gradient(dpf(10), p.accent, p.accent2)
                clipRounded()
                layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(14) }
            })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(text(u.name + if (current) tr("  ·  đang dùng", "  ·  current") else "", 17f, p.text, 600))
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
            hint = tr("Tên người dùng", "User name")
        }
        val box = LinearLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(input) }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(box)
            .setPositiveButton(tr("Lưu", "Save")) { _, _ ->
                val n = input.text.toString().trim()
                if (n.isBlank()) toast(tr("Hãy nhập tên.", "Please enter a name.")) else onOk(n)
            }
            .setNegativeButton(tr("Huỷ", "Cancel"), null)
            .show()
    }

    private fun createDialog() = nameDialog(tr("Người dùng mới", "New user"), "") { name ->
        enter(Users.create(this, name))
    }

    private fun manageDialog(u: User) {
        AlertDialog.Builder(this)
            .setTitle(u.name)
            .setItems(arrayOf(tr("Đổi tên", "Rename"), tr("Xoá người dùng", "Delete user"))) { _, which ->
                if (which == 0) nameDialog(tr("Đổi tên", "Rename"), u.name) { Users.rename(this, u.id, it); render() }
                else deleteConfirm(u)
            }
            .show()
    }

    private fun deleteConfirm(u: User) {
        AlertDialog.Builder(this)
            .setTitle(tr("Xoá “${u.name}”?", "Delete “${u.name}”?"))
            .setMessage(tr("Toàn bộ hồ sơ và bữa ăn của người này sẽ bị xoá. Không thể hoàn tác.",
                "This user's entire profile and meals will be deleted. This cannot be undone."))
            .setPositiveButton(tr("Xoá", "Delete")) { _, _ -> Users.delete(this, u.id); toast(tr("Đã xoá ${u.name}", "Deleted ${u.name}")); render() }
            .setNegativeButton(tr("Huỷ", "Cancel"), null)
            .show()
    }
}
