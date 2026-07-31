package com.dsoft.noti_forward

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.speech.tts.TextToSpeech
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Hosts the Flutter config UI and bridges to the native side:
 *  - notification-access permission check + opening its settings screen;
 *  - listing installed apps for the app picker;
 *  - listing the device's installed TTS languages/voices and a test utterance;
 *  - Discord webhook test;
 *  - toggling the keep-alive foreground service and the battery-optimisation
 *    exemption so reading keeps working in the background;
 *  - an EventChannel that streams captured notifications to the on-screen log.
 */
class MainActivity : FlutterActivity() {

    // A short-lived engine used for the "test voice" button.
    private var testTts: TextToSpeech? = null
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, Const.METHOD_CHANNEL)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "isPermissionGranted" -> result.success(isListenerEnabled())
                    "requestPermission", "openSettings" -> {
                        openNotificationAccessSettings()
                        result.success(null)
                    }

                    // Installed launchable apps for the picker (icons optional).
                    "listInstalledApps" -> {
                        val includeIcons = call.argument<Boolean>("includeIcons") ?: true
                        io.execute {
                            try {
                                val apps = AppCatalog.listLaunchableApps(
                                    packageManager,
                                    includeIcons = includeIcons,
                                )
                                main.post { result.success(apps) }
                            } catch (e: Exception) {
                                main.post {
                                    result.error("list_apps", e.message, null)
                                }
                            }
                        }
                    }

                    // Installed languages + voices for the pickers.
                    "ttsInfo" -> TtsProbe.probe(this) { r ->
                        result.success(
                            mapOf("languages" to r.languages, "voices" to r.voices)
                        )
                    }

                    // Speak a sample with the chosen language/voice/rate.
                    "speakTest" -> {
                        speakTest(
                            (call.argument<String>("text") ?: "Xin chào, đây là giọng đọc thử."),
                            call.argument<String>("lang") ?: "",
                            call.argument<String>("voice") ?: "",
                            ((call.argument<Int>("ratePct") ?: 50) / 100f),
                        )
                        result.success(null)
                    }

                    // Discord webhook smoke test.
                    "testWebhook" -> {
                        val url = call.argument<String>("webhook") ?: ""
                        val username = call.argument<String>("username") ?: ""
                        DiscordNotifier.send(
                            url,
                            "✅ Noti Forward — webhook hoạt động tốt.",
                            username,
                        ) { ok, err ->
                            main.post {
                                result.success(
                                    mapOf(
                                        "ok" to ok,
                                        "error" to (err ?: ""),
                                    )
                                )
                            }
                        }
                    }

                    // Background reliability.
                    "applyBackground" -> {
                        val on = call.argument<Boolean>("enabled") ?: false
                        if (on) KeepAliveService.start(this) else KeepAliveService.stop(this)
                        result.success(null)
                    }
                    "isIgnoringBattery" -> result.success(isIgnoringBattery())
                    "requestIgnoreBattery" -> {
                        requestIgnoreBattery()
                        result.success(null)
                    }
                    "openAppDetails" -> {
                        openAppDetails()
                        result.success(null)
                    }

                    else -> result.notImplemented()
                }
            }

        EventChannel(flutterEngine.dartExecutor.binaryMessenger, Const.EVENT_CHANNEL)
            .setStreamHandler(object : EventChannel.StreamHandler {
                override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                    NotiBus.setSink(events)
                }

                override fun onCancel(arguments: Any?) {
                    NotiBus.setSink(null)
                }
            })
    }

    // ---- Notification access -------------------------------------------------

    private fun isListenerEnabled(): Boolean {
        val flat = Settings.Secure.getString(
            contentResolver, "enabled_notification_listeners"
        ) ?: return false
        val me = ComponentName(this, NotiForwardService::class.java)
        return flat.split(":").any {
            val c = ComponentName.unflattenFromString(it)
            c != null && c.packageName == me.packageName && c.className == me.className
        }
    }

    private fun openNotificationAccessSettings() {
        startActivity(
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private fun openAppDetails() {
        try {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:$packageName"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) {
        }
    }

    // ---- TTS test ------------------------------------------------------------

    private fun speakTest(text: String, lang: String, voice: String, rate: Float) {
        testTts?.let { try { it.shutdown() } catch (_: Exception) {} }
        testTts = TextToSpeech(applicationContext) { status ->
            val e = testTts ?: return@TextToSpeech
            if (status != TextToSpeech.SUCCESS) return@TextToSpeech
            try {
                e.setSpeechRate(rate.coerceIn(0.2f, 1.0f))
                val v = if (voice.isNotEmpty()) e.voices?.firstOrNull { it.name == voice } else null
                if (v != null) {
                    e.voice = v
                } else if (lang.isNotEmpty()) {
                    e.setLanguage(Locale.forLanguageTag(lang))
                } else {
                    e.setLanguage(Locale("vi", "VN"))
                }
                e.speak(text, TextToSpeech.QUEUE_FLUSH, null, "test")
            } catch (_: Exception) {
            }
        }
    }

    // ---- Battery optimisation ------------------------------------------------

    private fun isIgnoringBattery(): Boolean {
        return try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.isIgnoringBatteryOptimizations(packageName)
        } catch (_: Exception) {
            false
        }
    }

    private fun requestIgnoreBattery() {
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) {
            // Some ROMs hide this intent — fall back to the general settings page.
            try {
                startActivity(
                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Exception) {
            }
        }
    }

    override fun onDestroy() {
        testTts?.let { try { it.shutdown() } catch (_: Exception) {} }
        testTts = null
        super.onDestroy()
    }
}
