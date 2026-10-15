package com.dsoft.try_on_cam

import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    private var encoder: VideoEncoder? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "tryon/video")
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "start" -> try {
                        encoder?.stop { }
                        encoder = VideoEncoder(
                            call.argument<String>("path")!!,
                            call.argument<Int>("width")!!,
                            call.argument<Int>("height")!!,
                            call.argument<Int>("rotation")!!,
                            call.argument<Boolean>("mirror")!!,
                        )
                        result.success(true)
                    } catch (e: Exception) {
                        encoder = null
                        result.error("start", e.message, null)
                    }
                    "frame" -> {
                        val ok = encoder?.offer(
                            call.argument<ByteArray>("nv21")!!,
                            call.argument<Int>("stride")!!,
                            call.argument<Number>("ts")!!.toLong(),
                        ) ?: false
                        result.success(ok)
                    }
                    "overlay" -> {
                        encoder?.setOverlay(
                            call.argument<ByteArray>("rgba"),
                            call.argument<Int>("width") ?: 0,
                            call.argument<Int>("height") ?: 0,
                        )
                        result.success(null)
                    }
                    "stop" -> {
                        val e = encoder
                        encoder = null
                        if (e == null) {
                            result.success(false)
                        } else {
                            e.stop { ok -> runOnUiThread { result.success(ok) } }
                        }
                    }
                    else -> result.notImplemented()
                }
            }
    }

    override fun onDestroy() {
        encoder?.stop { }
        encoder = null
        super.onDestroy()
    }
}
