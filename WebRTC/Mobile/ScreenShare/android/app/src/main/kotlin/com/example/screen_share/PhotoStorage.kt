package com.example.screen_share

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import java.io.File

/**
 * Lưu ảnh nhận được ở phía Server. Hai đích đến, chọn trong màn hình Server:
 *
 *  - **Thư mục do người dùng chọn (SAF):** ghi qua ContentResolver, không cần
 *    quyền lưu trữ, hoạt động trên mọi phiên bản Android.
 *  - **Mặc định — thư viện ảnh:** trên Android 10+ (API 29) ghi vào MediaStore
 *    `Pictures/ScreenShare` nên ảnh hiện ngay trong ứng dụng Ảnh/Thư viện.
 *    Trên bản cũ hơn thì ghi vào thư mục riêng của app (không cần xin quyền).
 *
 * Định dạng (PNG/JPEG) được nhận biết từ vài byte đầu để đặt đuôi & MIME đúng.
 */
class PhotoStorage(private val context: Context) {

    private val subDir = "ScreenShare"

    /**
     * Ghi [bytes] xuống nơi lưu. Trả về mô tả nơi đã lưu, hoặc ném lỗi nếu
     * thất bại (bên gọi bắt để báo về server).
     */
    fun save(bytes: ByteArray, treeUri: String, name: String): String {
        val jpeg = isJpeg(bytes)
        val ext = if (jpeg) "jpg" else "png"
        val mime = if (jpeg) "image/jpeg" else "image/png"
        val fileName = "$name.$ext"

        if (treeUri.isNotEmpty()) {
            return saveToTree(bytes, treeUri, fileName, mime)
        }
        return saveToGallery(bytes, fileName, mime)
    }

    /** Mô tả nơi lưu hiện tại cho phần cài đặt. */
    fun describe(treeUri: String): String {
        if (treeUri.isEmpty()) {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                "Thư viện ảnh: Pictures/$subDir"
            } else {
                "Bộ nhớ ứng dụng: ${defaultDir().absolutePath}"
            }
        }
        return try {
            val tree = DocumentFile.fromTreeUri(context, Uri.parse(treeUri))
            "Thư mục đã chọn: ${tree?.name ?: treeUri}"
        } catch (_: Exception) {
            "Thư mục đã chọn"
        }
    }

    // --- SAF ----------------------------------------------------------------

    private fun saveToTree(
        bytes: ByteArray,
        treeUri: String,
        fileName: String,
        mime: String,
    ): String {
        val tree = DocumentFile.fromTreeUri(context, Uri.parse(treeUri))
            ?: throw IllegalStateException("Thư mục đã chọn không còn hợp lệ")
        val doc = tree.createFile(mime, fileName)
            ?: throw IllegalStateException("Không tạo được tệp trong thư mục")
        context.contentResolver.openOutputStream(doc.uri)?.use { it.write(bytes) }
            ?: throw IllegalStateException("Không mở được luồng ghi")
        return "thư mục ${tree.name ?: ""}/$fileName"
    }

    // --- Thư viện / MediaStore ---------------------------------------------

    private fun saveToGallery(bytes: ByteArray, fileName: String, mime: String): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, mime)
                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    "${Environment.DIRECTORY_PICTURES}/$subDir",
                )
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values,
            ) ?: throw IllegalStateException("Không tạo được mục trong thư viện")
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: throw IllegalStateException("Không mở được luồng ghi thư viện")
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return "thư viện ảnh (Pictures/$subDir)"
        }
        // Android < 10: ghi vào thư mục riêng của app (không cần quyền).
        val file = File(defaultDir(), fileName)
        file.outputStream().use { it.write(bytes) }
        return file.absolutePath
    }

    private fun defaultDir(): File {
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), subDir)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun isJpeg(bytes: ByteArray): Boolean =
        bytes.size >= 2 &&
            (bytes[0].toInt() and 0xFF) == 0xFF &&
            (bytes[1].toInt() and 0xFF) == 0xD8
}
