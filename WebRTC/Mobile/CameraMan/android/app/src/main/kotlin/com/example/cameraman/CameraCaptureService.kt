package com.example.cameraman

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Owns the camera off-screen. Every capture path — the 4 home-screen widget
 * buttons, the in-app buttons, and motion detection — funnels here as an
 * intent action. Uses CameraX bound to this [LifecycleService], so no preview
 * surface is needed. Results are pushed back to the UI through [CaptureBus].
 *
 * The service starts on demand, keeps itself in the foreground while it holds
 * the camera, and stops itself once nothing is recording or watching for
 * motion — releasing the camera promptly.
 */
class CameraCaptureService : LifecycleService() {

    companion object {
        private const val CHANNEL_ID = "cameraman_capture_channel"
        private const val NOTIFICATION_ID = 2001
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val storage by lazy { MediaStorage(this) }

    // Manual capture state.
    private var manualRecording: Recording? = null
    private var manualVideoCapture: VideoCapture<Recorder>? = null
    private var manualStopRequested = false
    private val splitRunnable = Runnable { manualRecording?.stop() } // rollover

    // Motion state.
    private var motionRunning = false
    private var motionImageCapture: ImageCapture? = null
    private var motionVideoCapture: VideoCapture<Recorder>? = null
    private var motionRecording: Recording? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        ensureForeground()

        val lens = intent?.getStringExtra(Const.EXTRA_LENS) ?: Const.LENS_BACK
        when (intent?.action) {
            Const.ACTION_PHOTO -> takePhoto(lens)
            Const.ACTION_VIDEO_START -> startManualVideo(lens)
            Const.ACTION_VIDEO_STOP -> stopManualVideo()
            Const.ACTION_VIDEO_TOGGLE ->
                if (manualVideoCapture != null) stopManualVideo() else startManualVideo(lens)
            Const.ACTION_MOTION_START -> startMotion()
            Const.ACTION_MOTION_STOP -> stopMotion()
        }
        return START_NOT_STICKY
    }

    // --- Manual photo -------------------------------------------------------

    private fun takePhoto(lens: String) {
        if (motionRunning) { emitBusy(); return }
        withProvider { provider ->
            val capture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()
            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, selectorFor(lens), capture)
            } catch (e: Exception) {
                emitError("Không mở được camera: ${e.message}"); finishIfIdle(); return@withProvider
            }
            val target = try {
                storage.preparePhoto()
            } catch (e: Exception) {
                emitError(e.message ?: "Lỗi lưu ảnh"); provider.unbindAll(); finishIfIdle(); return@withProvider
            }
            capture.takePicture(
                target.options,
                ContextCompat.getMainExecutor(this),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(results: ImageCapture.OutputFileResults) {
                        emitCaptured("photo", target.displayPath)
                        provider.unbindAll()
                        finishIfIdle()
                    }

                    override fun onError(exc: ImageCaptureException) {
                        emitError("Chụp ảnh lỗi: ${exc.message}")
                        provider.unbindAll()
                        finishIfIdle()
                    }
                },
            )
        }
    }

    // --- Manual video -------------------------------------------------------

    private fun startManualVideo(lens: String) {
        if (motionRunning) { emitBusy(); return }
        if (manualRecording != null) return
        if (manualVideoCapture != null) return
        manualStopRequested = false
        withProvider { provider ->
            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.HD))
                .build()
            val videoCapture = VideoCapture.withOutput(recorder)
            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, selectorFor(lens), videoCapture)
            } catch (e: Exception) {
                emitError("Không mở được camera: ${e.message}"); finishIfIdle(); return@withProvider
            }
            manualVideoCapture = videoCapture
            CaptureBus.emit(mapOf("event" to "recording", "value" to true))
            refreshWidgets()
            startManualChunk()
        }
    }

    /**
     * Records one segment. When the segment finalizes we either roll straight
     * into the next one (continuous recording auto-split) or, if the user asked
     * to stop, tear everything down. The default split length is 5 minutes.
     */
    private fun startManualChunk() {
        val recorder = manualVideoCapture?.output ?: return
        val target = storage.prepareVideo()
        var pending = recorder.prepareRecording(
            this,
            FileOutputOptions.Builder(target.recordInto).build(),
        )
        if (hasAudioPermission()) pending = pending.withAudioEnabled()
        manualRecording = pending.start(ContextCompat.getMainExecutor(this)) { event ->
            if (event is VideoRecordEvent.Finalize) {
                mainHandler.removeCallbacks(splitRunnable)
                val finalPath = target.commit()
                manualRecording = null
                if (!event.hasError()) emitCaptured("video", finalPath)
                else emitError("Quay video lỗi (mã ${event.error})")

                if (manualStopRequested) {
                    manualVideoCapture = null
                    try {
                        ProcessCameraProvider.getInstance(this).get().unbindAll()
                    } catch (_: Exception) {}
                    CaptureBus.emit(mapOf("event" to "recording", "value" to false))
                    refreshWidgets()
                    finishIfIdle()
                } else {
                    // Roll over into the next segment seamlessly.
                    startManualChunk()
                }
            }
        }
        // Schedule the auto-split for this segment (if enabled).
        val minutes = AppConfig(this).splitMinutes
        if (minutes > 0) {
            mainHandler.postDelayed(splitRunnable, minutes * 60_000L)
        }
    }

    private fun stopManualVideo() {
        manualStopRequested = true
        mainHandler.removeCallbacks(splitRunnable)
        manualRecording?.stop()
        // finalize callback commits the last segment and stops the service.
    }

    // --- Motion detection ---------------------------------------------------

    private fun startMotion() {
        if (motionRunning) return
        val cfg = AppConfig(this)
        motionRunning = true
        CaptureBus.emit(mapOf("event" to "motion", "value" to true))
        refreshWidgets()

        withProvider { provider ->
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            val mode = cfg.motionMode
            // Wait out the clip length (plus a margin) before re-triggering.
            val minInterval =
                if (mode == "video") (cfg.motionVideoSeconds + 3) * 1000L else 4000L
            analysis.setAnalyzer(
                analysisExecutor,
                MotionDetector(
                    sensitivity = cfg.motionSensitivity,
                    minIntervalMs = minInterval,
                    clock = { System.currentTimeMillis() },
                ) { mainHandler.post { onMotionDetected(mode, cfg) } },
            )

            val selector = selectorFor(cfg.motionLens)
            try {
                provider.unbindAll()
                if (mode == "video") {
                    val recorder = Recorder.Builder()
                        .setQualitySelector(QualitySelector.from(Quality.HD))
                        .build()
                    val vc = VideoCapture.withOutput(recorder)
                    motionVideoCapture = vc
                    provider.bindToLifecycle(this, selector, analysis, vc)
                } else {
                    val ic = ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .build()
                    motionImageCapture = ic
                    provider.bindToLifecycle(this, selector, analysis, ic)
                }
            } catch (e: Exception) {
                emitError("Không bật được chế độ chuyển động: ${e.message}")
                stopMotion()
            }
        }
    }

    private fun onMotionDetected(mode: String, cfg: AppConfig) {
        val time = SimpleDateFormat("HH:mm:ss dd/MM/yyyy", Locale.US).format(Date())
        if (cfg.discordWebhook.isNotEmpty()) {
            val message = buildString {
                append("\n━━━━━━━━━━━━━━\n")
                append("📸 **CameraMan**\n")
                append("🖥 Thiết bị: ").append(cfg.deviceName).append("\n")
                append("🚶 Phát hiện chuyển động\n")
                append("🕒 ").append(time)
            }
            DiscordNotifier.send(cfg.discordWebhook, message)
        }
        if (mode == "video") startMotionClip(cfg) else takeMotionPhoto()
    }

    private fun takeMotionPhoto() {
        val capture = motionImageCapture ?: return
        val target = try { storage.preparePhoto(motion = true) } catch (e: Exception) { return }
        capture.takePicture(
            target.options,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(results: ImageCapture.OutputFileResults) =
                    emitCaptured("photo", target.displayPath)

                override fun onError(exc: ImageCaptureException) =
                    emitError("Chụp (chuyển động) lỗi: ${exc.message}")
            },
        )
    }

    private fun startMotionClip(cfg: AppConfig) {
        if (motionRecording != null) return
        val recorder = (motionVideoCapture ?: return).output
        val target = storage.prepareVideo(motion = true)
        var pending = recorder.prepareRecording(
            this,
            FileOutputOptions.Builder(target.recordInto).build(),
        )
        if (hasAudioPermission()) pending = pending.withAudioEnabled()
        motionRecording = pending.start(ContextCompat.getMainExecutor(this)) { event ->
            if (event is VideoRecordEvent.Finalize) {
                val finalPath = target.commit()
                motionRecording = null
                if (!event.hasError()) emitCaptured("video", finalPath)
            }
        }
        // Auto-stop the clip after the configured length.
        mainHandler.postDelayed({ motionRecording?.stop() }, cfg.motionVideoSeconds * 1000L)
    }

    private fun stopMotion() {
        motionRunning = false
        motionRecording?.stop()
        motionRecording = null
        motionImageCapture = null
        motionVideoCapture = null
        try {
            ProcessCameraProvider.getInstance(this).get().unbindAll()
        } catch (_: Exception) {
        }
        CaptureBus.emit(mapOf("event" to "motion", "value" to false))
        refreshWidgets()
        finishIfIdle()
    }

    private fun refreshWidgets() {
        try {
            WidgetUpdater.updateAll(this)
        } catch (_: Exception) {
        }
    }

    // --- Helpers ------------------------------------------------------------

    private fun withProvider(action: (ProcessCameraProvider) -> Unit) {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                action(future.get())
            } catch (e: Exception) {
                emitError("Camera không sẵn sàng: ${e.message}")
                finishIfIdle()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun selectorFor(lens: String): CameraSelector =
        if (lens == Const.LENS_FRONT) CameraSelector.DEFAULT_FRONT_CAMERA
        else CameraSelector.DEFAULT_BACK_CAMERA

    private fun hasAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun finishIfIdle() {
        if (!motionRunning &&
            manualRecording == null &&
            manualVideoCapture == null &&
            motionRecording == null
        ) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun emitCaptured(type: String, path: String) =
        CaptureBus.emit(mapOf("event" to "captured", "type" to type, "path" to path))

    private fun emitError(message: String) =
        CaptureBus.emit(mapOf("event" to "error", "message" to message))

    private fun emitBusy() =
        CaptureBus.emit(mapOf("event" to "busy", "message" to "Đang bận (chế độ chuyển động đang chạy)"))

    override fun onDestroy() {
        analysisExecutor.shutdown()
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // --- Foreground notification -------------------------------------------

    private fun ensureForeground() {
        createChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            startForeground(NOTIFICATION_ID, notification, type)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "CameraMan",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { setShowBadge(false) }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("CameraMan")
            .setContentText("Đang sử dụng camera")
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setOngoing(true)
            .build()
    }
}
