package com.example.cameraman

import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel

/**
 * Bridges Flutter to the native capture stack.
 *
 * MethodChannel `cameraman/native` handles one-shot commands (capture, toggle
 * recording, motion on/off, list/delete media, pick a save folder, test the
 * webhook). EventChannel `cameraman/events` streams [CaptureBus] updates back
 * so the UI reflects captures triggered from the widget or motion detector.
 */
class MainActivity : FlutterActivity() {

    private val storage by lazy { MediaStorage(this) }
    private var eventSink: EventChannel.EventSink? = null
    private var pendingFolderResult: MethodChannel.Result? = null

    private companion object {
        const val REQ_PICK_FOLDER = 42
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        val messenger = flutterEngine.dartExecutor.binaryMessenger

        MethodChannel(messenger, Const.METHOD_CHANNEL)
            .setMethodCallHandler { call, result -> onMethod(call.method, call, result) }

        EventChannel(messenger, Const.EVENT_CHANNEL).setStreamHandler(
            object : EventChannel.StreamHandler {
                override fun onListen(arguments: Any?, sink: EventChannel.EventSink?) {
                    eventSink = sink
                    CaptureBus.listener = { event ->
                        runOnUiThread { eventSink?.success(event) }
                    }
                }

                override fun onCancel(arguments: Any?) {
                    CaptureBus.listener = null
                    eventSink = null
                }
            },
        )
    }

    private fun onMethod(
        method: String,
        call: io.flutter.plugin.common.MethodCall,
        result: MethodChannel.Result,
    ) {
        val lens = call.argument<String>("lens") ?: Const.LENS_BACK
        when (method) {
            "capturePhoto" -> { startCapture(Const.ACTION_PHOTO, lens); result.success(true) }
            "startVideo" -> { startCapture(Const.ACTION_VIDEO_START, lens); result.success(true) }
            "stopVideo" -> { startCapture(Const.ACTION_VIDEO_STOP, null); result.success(true) }
            "toggleVideo" -> { startCapture(Const.ACTION_VIDEO_TOGGLE, lens); result.success(true) }
            "startMotion" -> { startCapture(Const.ACTION_MOTION_START, null); result.success(true) }
            "stopMotion" -> { startCapture(Const.ACTION_MOTION_STOP, null); result.success(true) }
            "isRecording" -> result.success(CaptureBus.recording)
            "isMotionRunning" -> result.success(CaptureBus.motionRunning)
            "listMedia" -> result.success(storage.listMedia())
            "deleteMedia" -> result.success(
                storage.deleteMedia(
                    call.argument<String>("uri") ?: "",
                    call.argument<String>("path"),
                ),
            )
            "deviceName" -> result.success(
                "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}".trim(),
            )
            "notiAccessGranted" -> result.success(isNotiAccessGranted())
            "openNotiAccess" -> { openNotiAccessSettings(); result.success(true) }
            "saveLocation" -> result.success(storage.describeLocation())
            "wipeMedia" -> result.success(storage.wipeAll())
            "openFolder" -> result.success(openFolder())
            "pickFolder" -> pickFolder(result)
            "clearFolder" -> result.success(true) // Dart clears the pref; nothing native to undo.
            "openMedia" -> { openMedia(call.argument<String>("uri"), call.argument<String>("path")); result.success(true) }
            "sendTestWebhook" -> {
                val name = AppConfig(this).deviceName
                val message = buildString {
                    append("\n━━━━━━━━━━━━━━\n")
                    append("✅ **CameraMan**\n")
                    append("🖥 Thiết bị: ").append(name).append("\n")
                    append("🔔 Kiểm tra webhook thành công")
                }
                DiscordNotifier.send(call.argument<String>("url") ?: "", message) { ok ->
                    runOnUiThread { result.success(ok) }
                }
            }
            else -> result.notImplemented()
        }
    }

    private fun isNotiAccessGranted(): Boolean {
        return try {
            androidx.core.app.NotificationManagerCompat
                .getEnabledListenerPackages(this)
                .contains(packageName)
        } catch (_: Exception) {
            false
        }
    }

    private fun openNotiAccessSettings() {
        try {
            startActivity(
                Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (_: Exception) {
            // Fall back to the app's own settings if the listener screen is absent.
            try {
                startActivity(
                    Intent(android.provider.Settings.ACTION_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } catch (_: Exception) {
            }
        }
    }

    private fun startCapture(action: String, lens: String?) {
        val intent = Intent(this, CameraCaptureService::class.java).apply {
            this.action = action
            lens?.let { putExtra(Const.EXTRA_LENS, it) }
        }
        ContextCompat.startForegroundService(this, intent)
    }

    // --- SAF folder picker --------------------------------------------------

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
        // Keep read/write access across reboots.
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (_: Exception) {
        }
        result.success(uri.toString())
    }

    /**
     * Opens a saved capture with a plain ACTION_VIEW (no forced chooser), so the
     * system shows the app picker with the "Always / Just once" option and can
     * remember the user's default player. Local files are shared through the
     * FileProvider to satisfy Android 7+ URI rules.
     */
    private fun openMedia(uri: String?, path: String?) {
        val isVideo = (path ?: uri ?: "").endsWith(".mp4", ignoreCase = true)
        val data: Uri = try {
            when {
                !path.isNullOrEmpty() -> androidx.core.content.FileProvider.getUriForFile(
                    this, "$packageName.fileprovider", java.io.File(path),
                )
                !uri.isNullOrEmpty() -> Uri.parse(uri)
                else -> return
            }
        } catch (e: Exception) {
            return
        }
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(data, if (isVideo) "video/*" else "image/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(view)
        } catch (_: Exception) {
            // No app can handle it; nothing else we can safely do here.
        }
    }

    /**
     * Best-effort "open the folder in a file manager". SAF folders open
     * reliably; the default app-specific folder often can't be browsed by
     * external apps, so on failure we just return its path for the UI to show.
     */
    private fun openFolder(): String {
        val config = AppConfig(this)
        return try {
            if (config.saveTreeUri.isNotEmpty()) {
                startActivity(
                    Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(Uri.parse(config.saveTreeUri), "vnd.android.document/directory")
                        addFlags(
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK,
                        )
                    },
                )
                "Đã mở thư mục đã chọn."
            } else {
                val dir = java.io.File(
                    getExternalFilesDir(android.os.Environment.DIRECTORY_DCIM),
                    "CameraMan",
                )
                startActivity(
                    Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(Uri.parse(dir.absolutePath), "resource/folder")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    },
                )
                "Đã mở: ${dir.absolutePath}"
            }
        } catch (_: Exception) {
            "Không có ứng dụng mở được thư mục. Vị trí: ${MediaStorage(this).describeLocation()}"
        }
    }
}
