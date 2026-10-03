package com.dsoft.voicenote

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import java.io.File

/** The transcript folder: open, rename, share, delete. */
class FilesActivity : Activity() {
    private lateinit var p: Palette
    private lateinit var listCard: LinearLayout
    private lateinit var info: android.widget.TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        p = palette()
        setupSystemBars(p)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(24))
        }
        root.addView(backLink("VoiceNote"))
        root.addView(text("Thư mục", 34f, p.text, 700).apply { setPadding(dp(4), dp(6), 0, dp(2)) })
        info = text("", 13f, p.secondary).apply { setPadding(dp(4), 0, 0, dp(14)) }
        root.addView(info)
        listCard = card(p)
        root.addView(listCard)
        root.addView(footnote(p, "Nhấn giữ một file hoặc bấm ⋯ để đổi tên, chia sẻ, xóa.\n" +
            "Vị trí: ${Transcripts.dir(this)}").apply { setTextIsSelectable(true) })
        setContentView(ScrollView(this).apply { isFillViewport = true; addView(root) })
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        listCard.removeAllViews()
        val files = Transcripts.list(this)
        info.text = "${files.size} file · ${files.sumOf { it.length() } / 1024} KB"
        if (files.isEmpty()) {
            listCard.addView(text("Chưa có bản ghi nào.\nBấm nút ghi ở màn hình chính để bắt đầu.", 15f, p.secondary).apply {
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(28), dp(16), dp(28))
            })
            return
        }
        files.forEachIndexed { i, f ->
            if (i > 0) listCard.addView(separator(p, 62))
            val more = icon(R.drawable.ic_more, p.blue, 36).apply {
                setPadding(dp(6), dp(6), dp(6), dp(6))
                background = pressable(null, p.ripple)
                setOnClickListener { actions(f) }
            }
            listCard.addView(fileRow(f, more) { SessionActivity.openFile(this, f) }.apply {
                setOnLongClickListener { actions(f); true }
            })
        }
    }

    private fun isActive(f: File) = RecorderService.isRunning && RecorderService.sessionFile == f

    private fun actions(f: File) {
        val labels = arrayOf("Mở", "Đổi tên", "Chia sẻ", "Xóa")
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(f.nameWithoutExtension)
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> SessionActivity.openFile(this, f)
                    1 -> rename(f)
                    2 -> share(f)
                    3 -> delete(f)
                }
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    private fun rename(f: File) {
        if (isActive(f)) return toast("Đang ghi vào file này, dừng ghi trước khi đổi tên")
        val input = EditText(this).apply {
            setText(f.nameWithoutExtension)
            setSelectAllOnFocus(true)
            setSingleLine()
        }
        val box = LinearLayout(this).apply {
            setPadding(dp(22), dp(8), dp(22), 0)
            addView(input, vlp())
        }
        val dlg = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Đổi tên")
            .setView(box)
            .setPositiveButton("Lưu") { _, _ ->
                val name = Transcripts.cleanName(input.text.toString())
                val target = File(f.parentFile, "$name.txt")
                when {
                    name.isEmpty() -> toast("Tên không hợp lệ")
                    target == f -> Unit
                    target.exists() -> toast("Đã có file tên \"$name\"")
                    f.renameTo(target) -> refresh()
                    else -> toast("Không đổi tên được")
                }
            }
            .setNegativeButton("Hủy", null)
            .create()
        dlg.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dlg.show()
        input.requestFocus()
    }

    private fun delete(f: File) {
        if (isActive(f)) return toast("Đang ghi vào file này, dừng ghi trước khi xóa")
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Xóa \"${f.nameWithoutExtension}\"?")
            .setMessage("${Transcripts.countEntries(f)} đoạn sẽ bị xóa vĩnh viễn.")
            .setPositiveButton("Xóa") { _, _ -> if (f.delete()) refresh() else toast("Không xóa được") }
            .setNegativeButton("Hủy", null)
            .show()
            .getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(p.red)
    }

    private fun share(f: File) {
        val body = runCatching { f.readText() }.getOrDefault("")
        if (body.isBlank()) return toast("File trống")
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, f.nameWithoutExtension)
            .putExtra(Intent.EXTRA_TEXT, body.takeLast(200_000))
        startActivity(Intent.createChooser(send, f.nameWithoutExtension))
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}

/** iOS "‹ Back" link used on sub screens. */
fun Activity.backLink(label: String): View = LinearLayout(this).apply {
    val p = palette()
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    setPadding(0, dp(6), dp(10), dp(6))
    background = pressable(null, p.ripple)
    addView(icon(R.drawable.ic_back, p.blue, 28))
    addView(text(label, 17f, p.blue))
    setOnClickListener { finish() }
    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
}
