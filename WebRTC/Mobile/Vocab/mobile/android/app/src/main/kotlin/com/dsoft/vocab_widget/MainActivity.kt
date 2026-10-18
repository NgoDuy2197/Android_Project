package com.dsoft.vocab_widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.speech.tts.TextToSpeech
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    private var speaker: Speaker? = null
    private var pendingResult: MethodChannel.Result? = null
    private var pendingContent: String? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "vocab/native").setMethodCallHandler { call, result ->
            when (call.method) {
                "speak" -> {
                    val s = speaker ?: Speaker(this).also { speaker = it }
                    s.speak(call.argument<String>("text").orEmpty(), call.argument<String>("locale").orEmpty(), { r ->
                        runOnUiThread { result.success(r) }
                    })
                }
                "getRate" -> result.success(Speaker.rate(this).toDouble())
                "setRate" -> {
                    Speaker.setRate(this, (call.argument<Double>("rate") ?: 0.8).toFloat())
                    result.success(null)
                }
                "openJson" -> {
                    pendingResult = result
                    startActivityForResult(
                        Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                            .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "text/plain", "application/octet-stream")),
                        REQ_OPEN,
                    )
                }
                "saveJson" -> {
                    pendingResult = result
                    pendingContent = call.argument<String>("content")
                    startActivityForResult(
                        Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                            .setType("application/json").putExtra(Intent.EXTRA_TITLE, call.argument<String>("name")),
                        REQ_SAVE,
                    )
                }
                "installTts" -> {
                    try {
                        startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA))
                    } catch (_: Exception) {
                        startActivity(Intent("com.android.settings.TTS_SETTINGS"))
                    }
                    result.success(null)
                }
                "getWidgets" -> {
                    result.success(VocabWidget.ids(this).map {
                        mapOf(
                            "id" to it,
                            "collectionId" to WidgetPrefs.collection(this, it),
                            "lines" to WidgetPrefs.lines(this, it),
                            "autoMinutes" to WidgetPrefs.autoMinutes(this, it),
                        )
                    })
                }
                "setWidget" -> {
                    val id = call.argument<Int>("id")!!
                    WidgetPrefs.setConfig(
                        this, id,
                        call.argument<String>("collectionId").orEmpty(),
                        call.argument<Int>("lines") ?: 2,
                        call.argument<Int>("autoMinutes") ?: 0,
                    )
                    VocabWidget.render(this, AppWidgetManager.getInstance(this), id)
                    result.success(null)
                }
                "refreshWidgets" -> {
                    VocabWidget.refreshAll(this)
                    result.success(null)
                }
                "pinWidget" -> {
                    val mgr = AppWidgetManager.getInstance(this)
                    val ok = mgr.isRequestPinAppWidgetSupported &&
                        mgr.requestPinAppWidget(ComponentName(this, VocabWidget::class.java), null, null)
                    result.success(ok)
                }
                else -> result.notImplemented()
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val result = pendingResult ?: return
        if (requestCode != REQ_OPEN && requestCode != REQ_SAVE) return
        pendingResult = null
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) {
            result.success(if (requestCode == REQ_OPEN) null else false)
            return
        }
        try {
            if (requestCode == REQ_OPEN) {
                result.success(contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) })
            } else {
                contentResolver.openOutputStream(uri, "wt")!!.use { it.write(pendingContent.orEmpty().toByteArray()) }
                result.success(true)
            }
        } catch (e: Exception) {
            result.error("io", e.message, null)
        }
    }

    override fun onDestroy() {
        speaker?.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val REQ_OPEN = 41
        private const val REQ_SAVE = 42
    }
}
