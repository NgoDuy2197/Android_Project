package com.dsoft.healthassistant

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup

/** Backup / restore: export all of the current user's data to a JSON file, or import it back. */
class BackupActivity : Activity() {
    private lateinit var p: Palette

    companion object {
        private const val REQ_EXPORT = 101
        private const val REQ_IMPORT = 102
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        p = palette()
        val col = page(p, tr("Sao lưu & khôi phục", "Backup & restore"))

        col.addView(footnote(p, tr(
            "Xuất toàn bộ dữ liệu của người dùng hiện tại (hồ sơ, bữa ăn, nước, tập luyện, cân nặng, đánh giá) ra một tệp .json để lưu trữ hoặc chuyển sang máy khác.",
            "Export all of the current user's data (profile, meals, water, exercise, weight, reviews) to a .json file to keep or move to another device.",
        )))
        col.addView(bigIconButton(tr("Xuất dữ liệu ra tệp", "Export to file"), R.drawable.ic_download, p.accent, p.accent2) { startExport() },
            vlp().apply { topMargin = dp(10) })

        col.addView(View(this), vlp(ViewGroup.LayoutParams.MATCH_PARENT, dp(16)))

        col.addView(footnote(p, tr(
            "Nhập dữ liệu từ một tệp sao lưu. Lưu ý: thao tác này sẽ GHI ĐÈ toàn bộ dữ liệu của người dùng hiện tại.",
            "Import data from a backup file. Note: this OVERWRITES all data of the current user.",
        )))
        col.addView(bigIconButton(tr("Nhập dữ liệu từ tệp", "Import from file"), R.drawable.ic_open_in_new, p.blue, p.teal) { startImport() },
            vlp().apply { topMargin = dp(10) })
    }

    private fun startExport() {
        val name = "healthassistant-backup-${Store.today()}.json"
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, name)
        }
        runCatching { startActivityForResult(intent, REQ_EXPORT) }
            .onFailure { toast(tr("Không mở được trình chọn tệp.", "Couldn't open the file picker.")) }
    }

    private fun startImport() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        runCatching { startActivityForResult(intent, REQ_IMPORT) }
            .onFailure { toast(tr("Không mở được trình chọn tệp.", "Couldn't open the file picker.")) }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        when (requestCode) {
            REQ_EXPORT -> doExport(uri)
            REQ_IMPORT -> confirmImport(uri)
        }
    }

    private fun doExport(uri: Uri) {
        val ok = runCatching {
            contentResolver.openOutputStream(uri)?.use { it.write(Store.exportAll(this).toByteArray(Charsets.UTF_8)) }
        }.isSuccess
        toast(if (ok) tr("Đã xuất dữ liệu.", "Data exported.") else tr("Xuất dữ liệu thất bại.", "Export failed."))
    }

    private fun confirmImport(uri: Uri) {
        val text = runCatching {
            contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrNull()
        if (text.isNullOrBlank()) { toast(tr("Không đọc được tệp.", "Couldn't read the file.")); return }
        AlertDialog.Builder(this)
            .setTitle(tr("Nhập dữ liệu?", "Import data?"))
            .setMessage(tr("Toàn bộ dữ liệu của người dùng hiện tại sẽ bị ghi đè bằng nội dung tệp sao lưu. Tiếp tục?",
                "All data of the current user will be overwritten with the backup. Continue?"))
            .setPositiveButton(tr("Nhập", "Import")) { _, _ ->
                if (Store.importAll(this, text)) {
                    toast(tr("Đã nhập dữ liệu.", "Data imported."))
                    finish()
                } else toast(tr("Tệp không hợp lệ.", "Invalid file."))
            }
            .setNegativeButton(tr("Huỷ", "Cancel"), null)
            .show()
    }
}
