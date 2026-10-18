package com.dsoft.photo_frame

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.concurrent.thread

/**
 * Opened by tapping a frame widget. One real photo → hands it straight to the
 * phone's photo viewer. Several (or sample) photos → shows them; tap one to
 * open it in the viewer.
 */
class ViewerActivity : Activity() {
    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val wid = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)
        val cfg = FrameStore.frame(this, FrameStore.widgetFrame(this, wid))
        val refs = if (cfg == null) emptyList() else FrameStore.current(this, wid, cfg)
        if (refs.size == 1 && !refs[0].startsWith("asset:")) {
            openInViewer(this, refs[0])
            finish()
            return
        }
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(16), dp(48), dp(16), dp(32))
        }
        list.addView(TextView(this).apply {
            text = cfg?.name ?: "Khung ảnh"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            paint.isFakeBoldText = true
            setPadding(0, 0, 0, dp(12))
        })
        val maxW = resources.displayMetrics.widthPixels - dp(32)
        for (ref in refs) {
            val iv = ImageView(this).apply {
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
                background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(Color.parseColor("#22FFFFFF")) }
                clipToOutline = true
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(14) }
                setOnClickListener {
                    if (ref.startsWith("asset:")) Toast.makeText(this@ViewerActivity, "Ảnh mẫu", Toast.LENGTH_SHORT).show()
                    else openInViewer(this@ViewerActivity, ref)
                }
            }
            list.addView(iv)
            thread {
                val bmp = decode(this, ref, maxW)
                runOnUiThread { if (bmp != null) iv.setImageBitmap(bmp) }
            }
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#E6101014"))
            addView(list)
            setOnClickListener { finish() }
        })
    }

    companion object {
        fun openInViewer(ctx: Context, ref: String) {
            try {
                ctx.startActivity(
                    Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(ref), "image/*")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } catch (_: Exception) {
                Toast.makeText(ctx, "Không có app xem ảnh", Toast.LENGTH_SHORT).show()
            }
        }

        /** Sub-sampled decode near [maxW] px wide, EXIF rotation applied. */
        fun decode(ctx: Context, ref: String, maxW: Int): Bitmap? {
            val info = FrameRenderer.probe(ctx, ref) ?: return null
            fun open() = if (ref.startsWith("asset:")) ctx.assets.open(ref.removePrefix("asset:")) else ctx.contentResolver.openInputStream(Uri.parse(ref))
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            open()?.use { BitmapFactory.decodeStream(it, null, o) }
            var sample = 1
            while (maxOf(o.outWidth, o.outHeight) / (sample * 2) >= maxW) sample *= 2
            val bmp = open()?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) } ?: return null
            if (info.rotation == 0) return bmp
            return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(info.rotation.toFloat()) }, true)
        }
    }
}
