package com.dsoft.voice_ai

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bridges to the system settings screens where speech-recognition and
 * text-to-speech language packs are installed. There's no single guaranteed
 * intent across OEMs, so each method tries a list of candidates and opens the
 * first that resolves, returning whether anything opened.
 */
class MainActivity : FlutterActivity() {

    private val channelName = "voice_ai/native"
    private val REQ_MIC = 4919
    private val REQ_CAMERA = 4920

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        EventChannel(flutterEngine.dartExecutor.binaryMessenger, "voice_ai/stt")
            .setStreamHandler(object : EventChannel.StreamHandler {
                override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                    sttSink = events
                }
                override fun onCancel(arguments: Any?) {
                    sttSink = null
                }
            })

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, channelName)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    // Installed TTS languages + voices from a specific engine
                    // (Google's, so Vietnamese shows even when the default engine
                    // is a Chinese one), plus the list of installed engines.
                    "ttsInfo" -> TtsProbe.probe(this, call.argument<String>("engine")) { r ->
                        result.success(
                            mapOf(
                                "languages" to r.languages,
                                "voices" to r.voices,
                                "engines" to r.engines,
                            )
                        )
                    }
                    // Speech-recognition (STT) capabilities: whether a recognizer
                    // exists, its supported languages (via the proper Android
                    // broadcast — works even when the plugin's locales() is empty),
                    // and which recognition engines are installed.
                    "sttDetails" -> sttDetails(result)

                    // Native speech-to-text using SpeechRecognizer directly (with
                    // an explicit engine fallback), so it works on ROMs where the
                    // system has no *default* recognizer set.
                    "micGranted" -> result.success(hasMic())
                    "requestMic" -> requestMic(result)
                    "cameraGranted" -> result.success(hasCamera())
                    "requestCamera" -> requestCamera(result)
                    "sttStart" -> {
                        sttStart(call.argument<String>("locale") ?: "vi-VN")
                        result.success(true)
                    }
                    "sttStop" -> {
                        sttStop()
                        result.success(null)
                    }
                    // Keep-alive foreground service (background + screen-off).
                    "startBackground" -> {
                        KeepAliveService.start(this)
                        result.success(null)
                    }
                    "stopBackground" -> {
                        KeepAliveService.stop(this)
                        result.success(null)
                    }
                    // Ask the system to download the on-device recognition pack
                    // for a language (Android 13+). Fixes STT error 13.
                    "sttDownloadModel" -> {
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                            result.error("UNSUPPORTED", "Cần Android 13 trở lên", null)
                        } else {
                            sttDownloadModel(call.argument<String>("locale") ?: "vi-VN")
                            result.success(true)
                        }
                    }

                    // Best-effort path to where the Vietnamese *recognition*
                    // language is downloaded (voice input / Speech Services).
                    "openSttSettings" -> result.success(openRecognitionSettings())
                    // Text-to-speech output / voice data.
                    "openTtsSettings" -> result.success(
                        openFirst(
                            Intent("com.android.settings.TTS_SETTINGS"),
                            Intent(Settings.ACTION_SETTINGS),
                        )
                    )
                    // "Speech Services by Google" — where offline voices/languages
                    // are actually downloaded on most phones.
                    "openSpeechServicesStore" -> result.success(
                        openFirst(
                            Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse("market://details?id=com.google.android.tts")
                            ),
                            Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse(
                                    "https://play.google.com/store/apps/details?id=com.google.android.tts"
                                )
                            ),
                        )
                    )
                    else -> result.notImplemented()
                }
            }
    }

    /**
     * Report speech-recognition capabilities. `languages` comes from the
     * recognizer's own `ACTION_GET_LANGUAGE_DETAILS` reply, which is the correct
     * way to enumerate STT languages and often works when the Flutter plugin
     * reports nothing. Answers asynchronously (the broadcast is async), with a
     * timeout so it never hangs.
     */
    private fun installedRecognizers(): ArrayList<Map<String, String>> {
        val recognizers = ArrayList<Map<String, String>>()
        try {
            val pm = packageManager
            for (ri in pm.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)) {
                val si = ri.serviceInfo ?: continue
                val label = try { si.loadLabel(pm).toString() } catch (_: Exception) { si.packageName }
                recognizers.add(mapOf("package" to si.packageName, "label" to label))
            }
        } catch (_: Exception) {
        }
        return recognizers
    }

    private fun sttDetails(result: MethodChannel.Result) {
        // Android 13+: ask the recogniser exactly which languages are installed /
        // online / downloadable — the reliable API (the old broadcast frequently
        // returns nothing even when Vietnamese works).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            sttDetailsV33(result)
        } else {
            sttDetailsLegacy(result)
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun sttDetailsV33(result: MethodChannel.Result) {
        val recognizers = installedRecognizers()
        val onDevice = try { SpeechRecognizer.isOnDeviceRecognitionAvailable(this) }
        catch (_: Exception) { false }
        val responded = AtomicBoolean(false)
        sttMain.post {
            val comp = pickRecognizer()
            val rec = try {
                if (comp != null) SpeechRecognizer.createSpeechRecognizer(this, comp)
                else SpeechRecognizer.createSpeechRecognizer(this)
            } catch (e: Exception) {
                if (!responded.getAndSet(true)) {
                    result.success(mapOf("available" to false, "onDevice" to onDevice,
                        "languages" to emptyList<String>(), "recognizers" to recognizers))
                }
                return@post
            }
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            }
            fun done(payload: Map<String, Any?>) {
                if (responded.getAndSet(true)) return
                result.success(payload)
                try { rec.destroy() } catch (_: Exception) {}
            }
            try {
                rec.checkRecognitionSupport(intent, mainExecutor, object : RecognitionSupportCallback {
                    override fun onSupportResult(support: RecognitionSupport) {
                        val installed = support.installedOnDeviceLanguages.toList()
                        val online = support.onlineLanguages.toList()
                        val supported = support.supportedOnDeviceLanguages.toList()
                        val pending = support.pendingOnDeviceLanguages.toList()
                        // Usable right now = installed on-device OR available online.
                        val usable = (installed + online).distinct()
                        done(mapOf(
                            "available" to usable.isNotEmpty(),
                            "onDevice" to onDevice,
                            "languages" to usable,
                            "installed" to installed,
                            "online" to online,
                            "downloadable" to supported,
                            "pending" to pending,
                            "recognizers" to recognizers,
                        ))
                    }
                    override fun onError(error: Int) {
                        done(mapOf("available" to (recognizers.isNotEmpty()),
                            "onDevice" to onDevice, "languages" to emptyList<String>(),
                            "recognizers" to recognizers, "supportError" to error))
                    }
                })
            } catch (e: Exception) {
                done(mapOf("available" to recognizers.isNotEmpty(), "onDevice" to onDevice,
                    "languages" to emptyList<String>(), "recognizers" to recognizers))
            }
            sttMain.postDelayed({
                done(mapOf("available" to recognizers.isNotEmpty(), "onDevice" to onDevice,
                    "languages" to emptyList<String>(), "recognizers" to recognizers))
            }, 5000)
        }
    }

    /** Pre-Android-13 path: the ordered `ACTION_GET_LANGUAGE_DETAILS` broadcast. */
    private fun sttDetailsLegacy(result: MethodChannel.Result) {
        val available = try { SpeechRecognizer.isRecognitionAvailable(this) } catch (_: Exception) { false }
        val recognizers = installedRecognizers()
        val responded = AtomicBoolean(false)
        fun finish(langs: List<String>) {
            if (responded.getAndSet(true)) return
            result.success(mapOf(
                "available" to (available || recognizers.isNotEmpty()),
                "onDevice" to false, "languages" to langs, "recognizers" to recognizers))
        }
        val details = try { RecognizerIntent.getVoiceDetailsIntent(this) } catch (_: Exception) { null }
        if (details == null) { finish(emptyList()); return }
        try {
            sendOrderedBroadcast(details, null, object : BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    val extras: Bundle = getResultExtras(true) ?: Bundle()
                    finish(extras.getStringArrayList(RecognizerIntent.EXTRA_SUPPORTED_LANGUAGES) ?: arrayListOf())
                }
            }, null, Activity.RESULT_OK, null, null)
        } catch (_: Exception) {
            finish(emptyList()); return
        }
        Handler(Looper.getMainLooper()).postDelayed({ finish(emptyList()) }, 3000)
    }

    // ---- Native speech-to-text ----------------------------------------------

    private var recognizer: SpeechRecognizer? = null
    private var sttSink: EventChannel.EventSink? = null
    private var sttListening = false
    private var sttLocale = "vi-VN"
    private val sttMain = Handler(Looper.getMainLooper())
    private var sttRestart: Runnable? = null

    private var micResult: MethodChannel.Result? = null
    private var cameraResult: MethodChannel.Result? = null

    private fun hasMic(): Boolean =
        ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestMic(result: MethodChannel.Result) {
        if (hasMic()) { result.success(true); return }
        micResult = result
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
    }

    private fun hasCamera(): Boolean =
        ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestCamera(result: MethodChannel.Result) {
        if (hasCamera()) { result.success(true); return }
        cameraResult = result
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        when (requestCode) {
            REQ_MIC -> {
                micResult?.success(granted)
                micResult = null
            }
            REQ_CAMERA -> {
                cameraResult?.success(granted)
                cameraResult = null
            }
        }
    }

    /**
     * Pick the recognition engine. Order matters for Vietnamese:
     *  1) the Google app (googlequicksearchbox) — CLOUD recogniser, best vi;
     *  2) the SYSTEM DEFAULT — i.e. exactly what other working apps use;
     *  3) only then fall back to an explicit on-device engine (Speech Services
     *     `com.google.android.tts`), which is offline-only and often lacks the
     *     vi pack. Forcing (3) over (2) was the bug — it overrode a working
     *     recogniser with an offline one that can't do Vietnamese.
     */
    private fun pickRecognizer(): ComponentName? {
        // 1) Prefer the SYSTEM DEFAULT recogniser — that's exactly what other
        //    working apps use. Forcing a specific component (e.g. the Google
        //    "trampoline" service) is what caused ERROR_INSUFFICIENT_PERMISSIONS
        //    (code 9) even with the mic permission granted.
        if (SpeechRecognizer.isRecognitionAvailable(this)) return null
        // 2) No default set (some China ROMs): fall back to an installed engine.
        val byPkg = HashMap<String, ComponentName>()
        try {
            for (ri in packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)) {
                val si = ri.serviceInfo ?: continue
                byPkg[si.packageName] = ComponentName(si.packageName, si.name)
            }
        } catch (_: Exception) {
        }
        for (p in listOf("com.google.android.tts", "com.google.android.katniss")) {
            byPkg[p]?.let { return it }
        }
        return byPkg.values.firstOrNull()
    }

    private fun ensureRecognizer() {
        if (recognizer != null) return
        val comp = pickRecognizer()
        sttEmit("engine", message = comp?.flattenToShortString() ?: "default")
        recognizer = try {
            if (comp != null) SpeechRecognizer.createSpeechRecognizer(this, comp)
            else SpeechRecognizer.createSpeechRecognizer(this)
        } catch (_: Exception) {
            SpeechRecognizer.createSpeechRecognizer(this)
        }
        recognizer?.setRecognitionListener(sttListener)
    }

    private fun sttStart(locale: String) {
        sttLocale = locale.replace('_', '-')
        sttListening = true
        sttMain.post {
            ensureRecognizer()
            startOne()
        }
    }

    private fun sttStop() {
        sttListening = false
        sttRestart?.let { sttMain.removeCallbacks(it) }
        sttMain.post {
            try { recognizer?.cancel() } catch (_: Exception) {}
            try { recognizer?.destroy() } catch (_: Exception) {}
            recognizer = null
        }
    }

    /** Trigger the on-device recognition model download for [locale] (API 33+). */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun sttDownloadModel(locale: String) {
        sttMain.post {
            try {
                val comp = pickRecognizer()
                val rec = if (comp != null) SpeechRecognizer.createSpeechRecognizer(this, comp)
                else SpeechRecognizer.createSpeechRecognizer(this)
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale.replace('_', '-'))
                    putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                    )
                }
                rec.triggerModelDownload(intent)
                sttEmit("dl", message = "started")
                sttMain.postDelayed({ try { rec.destroy() } catch (_: Exception) {} }, 3000)
            } catch (e: Exception) {
                sttEmit("dl", message = "error: ${e.message}")
            }
        }
    }

    private fun startOne() {
        if (!sttListening) return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, sttLocale)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, sttLocale)
            // Vietnamese recognises best via the cloud model — don't force offline.
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            putExtra("android.speech.extra.DICTATION_MODE", true)
        }
        try {
            recognizer?.startListening(intent)
        } catch (e: Exception) {
            sttEmit("error", message = e.message)
            recreateRecognizer()
            scheduleRestart(800)
        }
    }

    private fun scheduleRestart(delayMs: Long) {
        sttRestart?.let { sttMain.removeCallbacks(it) }
        val r = Runnable { if (sttListening) startOne() }
        sttRestart = r
        sttMain.postDelayed(r, delayMs)
    }

    private fun recreateRecognizer() {
        try { recognizer?.destroy() } catch (_: Exception) {}
        recognizer = null
        ensureRecognizer()
    }

    private fun firstText(results: Bundle?): String? =
        results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    private fun sttEmit(type: String, text: String? = null, code: Int? = null, message: String? = null) {
        val m = HashMap<String, Any?>()
        m["type"] = type
        if (text != null) m["text"] = text
        if (code != null) m["code"] = code
        if (message != null) m["message"] = message
        sttMain.post { try { sttSink?.success(m) } catch (_: Exception) {} }
    }

    private val sttListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) { sttEmit("ready") }
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
        override fun onPartialResults(partialResults: Bundle?) {
            firstText(partialResults)?.let { if (it.isNotEmpty()) sttEmit("partial", it) }
        }
        override fun onResults(results: Bundle?) {
            firstText(results)?.let { sttEmit("final", it) }
            if (sttListening) scheduleRestart(250)
        }
        override fun onError(error: Int) {
            sttEmit("error", code = error)
            if (!sttListening) return
            when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS,
                12, /* ERROR_LANGUAGE_NOT_SUPPORTED */
                13 /* ERROR_LANGUAGE_UNAVAILABLE */ -> sttListening = false // fatal, don't loop
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_CLIENT -> {
                    recreateRecognizer(); scheduleRestart(700)
                }
                else -> scheduleRestart(400) // NO_MATCH / SPEECH_TIMEOUT / NETWORK…
            }
        }
    }

    override fun onDestroy() {
        sttStop()
        super.onDestroy()
    }

    /**
     * Try the likeliest screens for downloading the on-device recognition
     * language, in order: voice-input settings → the Speech Services / Google
     * app itself → its app info → TTS settings → generic settings.
     */
    private fun openRecognitionSettings(): Boolean {
        val intents = ArrayList<Intent>()
        intents.add(Intent("android.settings.VOICE_INPUT_SETTINGS"))
        // Launch the recognizer app directly if it exposes a UI.
        for (pkg in listOf("com.google.android.tts", "com.google.android.googlequicksearchbox")) {
            packageManager.getLaunchIntentForPackage(pkg)?.let { intents.add(it) }
        }
        // Its App Info (some ROMs let you manage languages from there).
        intents.add(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:com.google.android.tts")
            )
        )
        intents.add(Intent("com.android.settings.TTS_SETTINGS"))
        intents.add(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        intents.add(Intent(Settings.ACTION_SETTINGS))
        return openFirst(*intents.toTypedArray())
    }

    /** Launch the first intent that a system activity can handle. */
    private fun openFirst(vararg intents: Intent): Boolean {
        for (intent in intents) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                return true
            } catch (_: ActivityNotFoundException) {
                // Try the next candidate.
            } catch (_: Exception) {
                // Some OEM intents throw SecurityException etc.; keep trying.
            }
        }
        return false
    }
}
