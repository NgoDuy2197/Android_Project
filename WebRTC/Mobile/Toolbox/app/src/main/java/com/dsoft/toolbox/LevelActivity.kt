package com.dsoft.toolbox

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Two tools in one:
 *  • Thước bọt nước — a bubble level from the gravity/accelerometer sensor.
 *  • Đo bằng ảnh — rough length measuring: draw a line on a photo; length is estimated from the
 *    camera distance + field of view. Results are approximate (labelled "ước lượng").
 */
class LevelActivity : Activity() {
    private lateinit var p: Palette
    private lateinit var sm: SensorManager
    private var levelView: LevelView? = null
    private var angleLabel: TextView? = null

    // measure state
    private var measureView: MeasureView? = null
    private var measureResult: TextView? = null
    private var distanceM = 0.5
    private var fovDeg = 66.0
    private var lastRatio = 0.0

    private val sensorL = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            val gx = e.values[0]; val gy = e.values[1]; val gz = e.values[2]
            levelView?.update(gx, gy, gz)
            val pitch = Math.toDegrees(atan2(gy.toDouble(), sqrt((gx * gx + gz * gz).toDouble())))
            val roll = Math.toDegrees(atan2(gx.toDouble(), sqrt((gy * gy + gz * gz).toDouble())))
            angleLabel?.text = "Nghiêng: dọc %.1f°  ·  ngang %.1f°".format(pitch, -roll)
        }
        override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        p = palette(); setupSystemBars(p)
        sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val root = page(p, "Thước & đo")

        val levelPane = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val measurePane = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        root.addView(Segmented(this, p, listOf("Bọt nước", "Đo bằng ảnh"), 0) { i ->
            levelPane.visibility = if (i == 0) View.VISIBLE else View.GONE
            measurePane.visibility = if (i == 1) View.VISIBLE else View.GONE
        }, vlp().apply { marginStart = dp(4); marginEnd = dp(4); bottomMargin = dp(8) })
        root.addView(levelPane, vlp())
        root.addView(measurePane, vlp())

        buildLevel(levelPane)
        buildMeasure(measurePane)
    }

    // ---------- level ----------
    private fun buildLevel(pane: LinearLayout) {
        val lv = LevelView(this, p); levelView = lv
        pane.addView(card(p).apply { setPadding(dp(8), dp(8), dp(8), dp(8)); addView(lv, vlp(-1, dp(320))) }, vlp())
        angleLabel = text("Đặt máy lên bề mặt để đo độ nghiêng", 16f, p.text).apply {
            gravity = android.view.Gravity.CENTER; setPadding(0, dp(14), 0, 0)
        }
        pane.addView(angleLabel)
        pane.addView(footnote(p, "Bọt vào giữa = phẳng. Dùng cảm biến gia tốc; cầm yên để ổn định."))
    }

    // ---------- measure ----------
    private fun buildMeasure(pane: LinearLayout) {
        pane.addView(group(p,
            row(p, "📷 Chụp ảnh", chevron(p)) { takePhoto() },
            row(p, "🖼️ Chọn ảnh", chevron(p)) { pickImage() },
        ))
        val dist = EditText(this).apply {
            setText("0.5"); setTextColor(p.text); setHintTextColor(p.secondary)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL; setSingleLine()
        }
        val fov = EditText(this).apply {
            setText("66"); setTextColor(p.text); setHintTextColor(p.secondary)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL; setSingleLine()
        }
        val recompute = {
            distanceM = dist.text.toString().toDoubleOrNull() ?: 0.5
            fovDeg = fov.text.toString().toDoubleOrNull()?.coerceIn(10.0, 170.0) ?: 66.0
            updateMeasure()
        }
        dist.setOnFocusChangeListener { _, h -> if (!h) recompute() }
        fov.setOnFocusChangeListener { _, h -> if (!h) recompute() }
        pane.addView(group(p,
            row(p, "Khoảng cách tới vật (m)", dist.apply { layoutParams = LinearLayout.LayoutParams(dp(90), -2) }),
            row(p, "Góc nhìn camera (FOV°)", fov.apply { layoutParams = LinearLayout.LayoutParams(dp(90), -2) }),
        ))

        val mv = MeasureView(this, p) { ratio -> lastRatio = ratio; updateMeasure() }; measureView = mv
        pane.addView(card(p).apply { setPadding(dp(6), dp(6), dp(6), dp(6)); addView(mv, vlp(-1, -2)) }, vlp())
        measureResult = text("Chụp/chọn ảnh rồi kẻ 1 đường trên vật cần đo.", 17f, p.text, 700).apply {
            gravity = android.view.Gravity.CENTER; setPadding(0, dp(12), 0, 0)
        }
        pane.addView(measureResult)
        pane.addView(footnote(p, "⚠️ Chỉ là ƯỚC LƯỢNG: độ dài ≈ tỉ lệ đường kẻ × bề rộng khung ảnh ở khoảng cách đó. " +
            "Sai số lớn nếu nhập sai khoảng cách/FOV hoặc vật không vuông góc ống kính. Không thay thiết bị đo chuẩn."))
    }

    private fun updateMeasure() {
        if (lastRatio <= 0.0) return
        // real width captured at the subject plane = 2 * d * tan(FOV/2); length = ratio * that width
        val widthAtDist = 2.0 * distanceM * tan(Math.toRadians(fovDeg / 2.0))
        val meters = lastRatio * widthAtDist
        val txt = if (meters >= 1.0) "%.2f m".format(meters)
            else if (meters >= 0.01) "%.1f cm".format(meters * 100)
            else "%.0f mm".format(meters * 1000)
        measureResult?.text = "≈ $txt  [ước lượng]"
    }

    private var cameraFile: java.io.File? = null
    private fun takePhoto() {
        val f = java.io.File(cacheDir, "measure_${System.currentTimeMillis()}.jpg"); cameraFile = f
        val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.files", f)
        try {
            startActivityForResult(Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE)
                .putExtra(android.provider.MediaStore.EXTRA_OUTPUT, uri)
                .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION), REQ_CAMERA)
        } catch (_: Exception) { toast("Máy không có camera") }
    }

    private fun pickImage() {
        try { startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE), REQ_PICK) }
        catch (_: Exception) { toast("Không mở được trình chọn ảnh") }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val bmp = when (requestCode) {
            REQ_CAMERA -> cameraFile?.let { if (it.exists()) decode(it.readBytes()) else null }
            REQ_PICK -> data?.data?.let { u: Uri -> contentResolver.openInputStream(u)?.use { decode(it.readBytes()) } }
            else -> null
        } ?: return
        lastRatio = 0.0
        measureResult?.text = "Kẻ 1 đường trên vật cần đo."
        measureView?.setBitmap(bmp)
    }

    private fun decode(bytes: ByteArray): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        if (o.outWidth <= 0) return null
        var s = 1; while (maxOf(o.outWidth, o.outHeight) / s > 2000) s *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s })
    }

    override fun onResume() {
        super.onResume()
        (sm.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER))?.let {
            sm.registerListener(sensorL, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun onPause() { super.onPause(); sm.unregisterListener(sensorL) }

    companion object { private const val REQ_CAMERA = 1; private const val REQ_PICK = 2 }

    // ================= views =================
    private class LevelView(ctx: Context, val p: Palette) : View(ctx) {
        private var gx = 0f; private var gy = 0f; private var gz = 9.8f
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = ctx.dpf(2); color = p.separator }
        private val cross = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = ctx.dpf(1); color = p.separator }
        private val target = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = ctx.dpf(2); color = p.green }
        private val bubble = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p.blue }
        private val bubbleOk = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p.green }

        fun update(x: Float, y: Float, z: Float) { gx = x; gy = y; gz = z; invalidate() }

        override fun onDraw(c: Canvas) {
            val cx = width / 2f; val cy = height / 2f
            val r = minOf(cx, cy) - context.dpf(8)
            c.drawCircle(cx, cy, r, ring)
            c.drawCircle(cx, cy, context.dpf(26), target)
            c.drawLine(cx - r, cy, cx + r, cy, cross)
            c.drawLine(cx, cy - r, cx, cy + r, cross)
            val g = maxOf(1f, hypot(hypot(gx, gy), gz))
            val maxOff = r - context.dpf(18)
            val bx = cx + (-gx / g) * maxOff
            val by = cy + (gy / g) * maxOff
            val flat = hypot(gx, gy) / g < 0.035f
            c.drawCircle(bx, by, context.dpf(16), if (flat) bubbleOk else bubble)
        }
    }

    private class MeasureView(ctx: Context, val p: Palette, val onLine: (Double) -> Unit) : View(ctx) {
        private var bmp: Bitmap? = null
        private var sx = 0f; private var sy = 0f; private var ex = 0f; private var ey = 0f
        private var has = false
        private val imgPaint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 59, 48); strokeWidth = ctx.dpf(3) }
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 59, 48) }
        private val hint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p.secondary; textSize = ctx.dpf(14) }

        fun setBitmap(b: Bitmap) { bmp = b; has = false; requestLayout(); invalidate() }

        override fun onMeasure(wspec: Int, hspec: Int) {
            val w = MeasureSpec.getSize(wspec)
            val b = bmp
            val h = if (b != null) (w.toFloat() * b.height / b.width).toInt() else context.dp(220)
            setMeasuredDimension(w, h)
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (bmp == null) return false
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = e.x; sy = e.y; ex = e.x; ey = e.y; has = true; invalidate() }
                MotionEvent.ACTION_MOVE -> { ex = e.x; ey = e.y; invalidate() }
                MotionEvent.ACTION_UP -> {
                    ex = e.x; ey = e.y; invalidate()
                    if (width > 0) onLine(hypot(ex - sx, ey - sy).toDouble() / width)
                }
            }
            return true
        }

        override fun onDraw(c: Canvas) {
            val b = bmp
            if (b == null) { c.drawText("Chưa có ảnh", context.dpf(12), context.dpf(28), hint); return }
            c.drawBitmap(b, null, RectF(0f, 0f, width.toFloat(), height.toFloat()), imgPaint)
            if (has) {
                c.drawLine(sx, sy, ex, ey, line)
                c.drawCircle(sx, sy, context.dpf(6), dot)
                c.drawCircle(ex, ey, context.dpf(6), dot)
            }
        }
    }
}
