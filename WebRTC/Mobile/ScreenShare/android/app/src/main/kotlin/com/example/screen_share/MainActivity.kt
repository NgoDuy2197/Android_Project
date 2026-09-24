package com.example.screen_share

import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {

    private val channelName = "screenshare/native"
    private val storage by lazy { PhotoStorage(this) }
    private var pendingFolderResult: MethodChannel.Result? = null

    // Âm thanh: client thu âm thanh hệ thống → Dart (EventChannel);
    // server nhận PCM từ Dart → phát ra loa.
    private var audioSink: EventChannel.EventSink? = null
    private val audioCapture = SystemAudioCapture { audioSink?.success(it) }
    private var methodChannel: MethodChannel? = null
    private val audioPlayer = AudioStreamPlayer {
        methodChannel?.invokeMethod("onHowl", null)
    }

    private companion object {
        const val REQ_PICK_FOLDER = 71
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        EventChannel(flutterEngine.dartExecutor.binaryMessenger, "screenshare/audio")
            .setStreamHandler(object : EventChannel.StreamHandler {
                override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                    audioSink = events
                }

                override fun onCancel(arguments: Any?) {
                    audioSink = null
                }
            })
        methodChannel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, channelName)
        methodChannel!!
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "startService" -> {
                        val intent = Intent(this, ScreenShareService::class.java)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            ContextCompat.startForegroundService(this, intent)
                        } else {
                            startService(intent)
                        }
                        result.success(true)
                    }
                    "stopService" -> {
                        stopService(Intent(this, ScreenShareService::class.java))
                        result.success(true)
                    }
                    "deviceName" -> {
                        val name = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
                        result.success(name)
                    }
                    "pickFolder" -> pickFolder(result)
                    "saveImage" -> saveImage(call, result)
                    "saveLocationLabel" -> {
                        val treeUri = call.argument<String>("treeUri") ?: ""
                        result.success(storage.describe(treeUri))
                    }
                    "audioCaptureStart" -> {
                        val rate = call.argument<Int>("rate") ?: 48000
                        val ch = call.argument<Int>("channels") ?: 2
                        val mic = call.argument<Boolean>("mic") ?: false
                        result.success(audioCapture.start(applicationContext, rate, ch, mic))
                    }
                    "audioCaptureStop" -> {
                        audioCapture.stop()
                        result.success(true)
                    }
                    "audioPlayStart" -> {
                        val rate = call.argument<Int>("rate") ?: 48000
                        val ch = call.argument<Int>("channels") ?: 2
                        audioPlayer.start(rate, ch, call.argument<Boolean>("howlGuard") ?: false)
                        audioPlayer.setMuted(call.argument<Boolean>("muted") ?: false)
                        result.success(true)
                    }
                    "audioPlayWrite" -> {
                        call.argument<ByteArray>("bytes")?.let { audioPlayer.write(it) }
                        result.success(null)
                    }
                    "audioPlayMute" -> {
                        audioPlayer.setMuted(call.argument<Boolean>("muted") ?: false)
                        result.success(true)
                    }
                    "audioPlayStop" -> {
                        audioPlayer.stop()
                        result.success(true)
                    }
                    else -> result.notImplemented()
                }
            }
    }

    override fun onDestroy() {
        audioCapture.stop()
        audioPlayer.stop()
        super.onDestroy()
    }

    private fun saveImage(
        call: io.flutter.plugin.common.MethodCall,
        result: MethodChannel.Result,
    ) {
        val bytes = call.argument<ByteArray>("bytes")
        val treeUri = call.argument<String>("treeUri") ?: ""
        val name = call.argument<String>("name") ?: "SS_photo"
        if (bytes == null) {
            result.error("no_bytes", "Không có dữ liệu ảnh", null)
            return
        }
        try {
            result.success(storage.save(bytes, treeUri, name))
        } catch (e: Exception) {
            result.error("save_failed", e.message ?: "Lưu ảnh lỗi", null)
        }
    }

    // --- Trình chọn thư mục SAF ---------------------------------------------

    private fun pickFolder(result: MethodChannel.Result) {
        pendingFolderResult = result
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
            )
        }
        try {
            startActivityForResult(intent, REQ_PICK_FOLDER)
        } catch (e: Exception) {
            pendingFolderResult = null
            result.error("no_picker", "Không mở được trình chọn thư mục", e.message)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK_FOLDER) return
        val result = pendingFolderResult ?: return
        pendingFolderResult = null

        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) {
            result.success(null)
            return
        }
        // Giữ quyền đọc/ghi qua các lần khởi động lại.
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (_: Exception) {
        }
        result.success(uri.toString())
    }
}
