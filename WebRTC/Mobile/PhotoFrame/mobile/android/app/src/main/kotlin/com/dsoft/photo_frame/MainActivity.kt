package com.dsoft.photo_frame

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.DocumentsContract
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import org.json.JSONObject
import java.io.ByteArrayOutputStream

class MainActivity : FlutterActivity() {
    private var pending: MethodChannel.Result? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "frame/native").setMethodCallHandler { call, result ->
            val app = applicationContext
            fun bg(work: () -> Any?) = PhotoWidget.io.execute {
                val r = try {
                    work()
                } catch (e: Exception) {
                    null
                }
                runOnUiThread { result.success(r) }
            }
            when (call.method) {
                // Live preview: same renderer as the widget.
                "render" -> bg {
                    val cfg = FrameConfig(JSONObject(call.argument<String>("frame")!!))
                    val all = FrameStore.photos(app, cfg)
                    val offset = call.argument<Int>("offset") ?: 0
                    val refs = if (all.isEmpty()) emptyList() else (0 until minOf(cfg.count, all.size)).map { all[(offset * cfg.count + it) % all.size] }
                    val photos = refs.mapNotNull { FrameRenderer.probe(app, it) }
                    val bmp = FrameRenderer.render(app, cfg, photos, call.argument<Int>("w")!!, call.argument<Int>("h")!!)
                    ByteArrayOutputStream().use {
                        bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
                        bmp.recycle()
                        it.toByteArray()
                    }
                }
                "photoCount" -> bg {
                    val cfg = FrameConfig(JSONObject(call.argument<String>("frame")!!))
                    FrameStore.photos(app, cfg, forceScan = call.argument<Boolean>("rescan") == true).size
                }
                "pickFolder" -> {
                    pending = result
                    startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_FOLDER)
                }
                "pickPhotos" -> {
                    pending = result
                    startActivityForResult(
                        Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*")
                            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true),
                        REQ_PHOTOS,
                    )
                }
                "widgets" -> {
                    val mgr = AppWidgetManager.getInstance(this)
                    result.success(PhotoWidget.ids(this).map { id ->
                        val o = mgr.getAppWidgetOptions(id)
                        mapOf(
                            "id" to id,
                            "frameId" to (FrameStore.widgetFrame(this, id) ?: ""),
                            "w" to o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0),
                            "h" to o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0),
                        )
                    })
                }
                "assign" -> bg {
                    val id = call.argument<Int>("id")!!
                    FrameStore.assign(app, id, call.argument<String>("frameId")!!)
                    PhotoWidget.render(app, AppWidgetManager.getInstance(app), id)
                    null
                }
                "next" -> bg {
                    val id = call.argument<Int>("id")!!
                    FrameStore.frame(app, FrameStore.widgetFrame(app, id))?.let { FrameStore.advance(app, id, it) }
                    PhotoWidget.render(app, AppWidgetManager.getInstance(app), id)
                    null
                }
                "refreshAll" -> bg {
                    PhotoWidget.refreshAll(app)
                    Ticker.schedule(app)
                    null
                }
                "pin" -> {
                    FrameStore.setPendingPin(this, call.argument<String>("frameId"))
                    val mgr = AppWidgetManager.getInstance(this)
                    result.success(
                        mgr.isRequestPinAppWidgetSupported &&
                            mgr.requestPinAppWidget(ComponentName(this, PhotoWidget::class.java), null, null),
                    )
                }
                else -> result.notImplemented()
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val result = pending ?: return
        pending = null
        if (resultCode != RESULT_OK || data == null) {
            result.success(null)
            return
        }
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        when (requestCode) {
            REQ_FOLDER -> {
                val uri = data.data ?: return result.success(null)
                // Keep read access across reboots for the widget.
                runCatching { contentResolver.takePersistableUriPermission(uri, flags) }
                val name = runCatching {
                    val doc = DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
                    contentResolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
                        ?.use { if (it.moveToFirst()) it.getString(0) else null }
                }.getOrNull() ?: uri.lastPathSegment.orEmpty()
                result.success(mapOf("uri" to uri.toString(), "name" to name))
            }
            REQ_PHOTOS -> {
                val uris = ArrayList<Uri>()
                data.clipData?.let { cd -> for (i in 0 until cd.itemCount) uris += cd.getItemAt(i).uri }
                if (uris.isEmpty()) data.data?.let { uris += it }
                uris.forEach { runCatching { contentResolver.takePersistableUriPermission(it, flags) } }
                result.success(mapOf("uris" to uris.map { it.toString() }))
            }
            else -> result.success(null)
        }
    }

    companion object {
        private const val REQ_FOLDER = 51
        private const val REQ_PHOTOS = 52
    }
}
