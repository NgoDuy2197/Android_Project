package com.dsoft.odiary

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import kotlin.math.max
import kotlin.random.Random

/**
 * Transparent overlay that plays decorative effects: a continuous "falling" effect (sakura, hearts,
 * stars, snow, fairy dust, bubbles) and a burst where the user taps. Touches pass through.
 */
class FxView(ctx: Context) : View(ctx) {
    var fallType: String = "sakura"
    var tapType: String = "sparkle"
    var tint: Int = 0xFFFF5E9C.toInt()
    var tint2: Int = 0xFFFFC24D.toInt()

    private class P(
        var x: Float, var y: Float, var vx: Float, var vy: Float,
        var size: Float, var rot: Float, var vrot: Float,
        var life: Float, var maxLife: Float,
        val glyph: String?, var color: Int, val ring: Boolean = false, val grav: Float = 0f,
    )

    private val parts = ArrayList<P>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private var lastSpawn = 0L
    private var lastFrame = 0L

    init { setWillNotDraw(false) }

    private fun dp(v: Int): Float = v * resources.displayMetrics.density
    private fun dpf(v: Int): Float = v * resources.displayMetrics.density

    private fun glyphsFor(type: String): Array<String> = when (type) {
        "sakura" -> arrayOf("🌸", "🌸", "🌷")
        "hearts" -> arrayOf("💗", "💖", "💕")
        "stars" -> arrayOf("⭐", "✨", "🌟")
        "snow" -> arrayOf("❄", "❅", "❆")
        "fairy" -> arrayOf("✨", "🧚", "✨", "⭐")
        "bubbles" -> arrayOf("", "")   // drawn as circles
        else -> arrayOf("✨")
    }

    fun spawnFall() {
        if (fallType == "none") return
        if (parts.count { it.vy >= 0 || fallType == "bubbles" } > 70) return
        val w = width.coerceAtLeast(1)
        val g = glyphsFor(fallType).random()
        val size = dp(14 + Random.nextInt(14)).toFloat()
        if (fallType == "bubbles") {
            parts.add(P(Random.nextInt(w).toFloat(), height + 20f, (Random.nextFloat() - .5f) * dp(10),
                -dp(30 + Random.nextInt(50)).toFloat(), size, 0f, 0f, 1f, 1f, null,
                (0x40FFFFFF.toInt()), false))
        } else {
            parts.add(P(Random.nextInt(w).toFloat(), -20f, (Random.nextFloat() - .5f) * dp(30),
                dp(40 + Random.nextInt(60)).toFloat(), size, Random.nextFloat() * 360,
                (Random.nextFloat() - .5f) * 120, 1f, 1f, g, tint))
        }
    }

    /** Burst at a tap point based on [tapType]. */
    fun burst(px: Float, py: Float) {
        when (tapType) {
            "none" -> return
            "ripple" -> {
                parts.add(P(px, py, 0f, 0f, dp(8).toFloat(), 0f, 0f, 1f, 0.6f, null, tint, ring = true))
            }
            "ripple2" -> {}
            else -> {
                val glyphs = when (tapType) {
                    "hearts" -> arrayOf("💗", "💖")
                    "fairy" -> arrayOf("✨", "🧚", "⭐")
                    "burst" -> arrayOf("")   // colored shards
                    else -> arrayOf("✨", "⭐", "💫")
                }
                val n = if (tapType == "burst") 16 else 10
                for (i in 0 until n) {
                    val ang = Math.PI * 2 * i / n + Random.nextDouble(-0.2, 0.2)
                    val sp = dp(60 + Random.nextInt(90)).toFloat()
                    val g = glyphs.random().ifEmpty { null }
                    parts.add(P(px, py, (Math.cos(ang) * sp).toFloat(), (Math.sin(ang) * sp).toFloat(),
                        dp(10 + Random.nextInt(12)).toFloat(), Random.nextFloat() * 360, (Random.nextFloat() - .5f) * 300,
                        1f, 0.9f, g, if (Random.nextBoolean()) tint else tint2, grav = dp(140).toFloat()))
                }
            }
        }
        kick()
    }

    private fun kick() { if (lastFrame == 0L) { lastFrame = System.nanoTime(); postInvalidateOnAnimation() } }

    override fun onDraw(canvas: Canvas) {
        val now = System.nanoTime()
        val dt = if (lastFrame == 0L) 0.016f else ((now - lastFrame) / 1_000_000_000f).coerceAtMost(0.05f)
        lastFrame = now

        if (fallType != "none" && System.currentTimeMillis() - lastSpawn > 220) {
            lastSpawn = System.currentTimeMillis(); spawnFall()
        }

        val it = parts.iterator()
        while (it.hasNext()) {
            val p = it.next()
            if (p.ring) {
                p.size += dp(260) * dt
                p.life -= dt / p.maxLife
                if (p.life <= 0) { it.remove(); continue }
                ringPaint.color = Color.argb((180 * p.life).toInt().coerceIn(0, 255),
                    Color.red(p.color), Color.green(p.color), Color.blue(p.color))
                ringPaint.strokeWidth = dpf(3)
                canvas.drawCircle(p.x, p.y, p.size, ringPaint)
                continue
            }
            p.vy += p.grav * dt
            p.x += p.vx * dt; p.y += p.vy * dt; p.rot += p.vrot * dt
            if (p.grav > 0f || p.maxLife < 1f) p.life -= dt / p.maxLife
            val off = p.y > height + 40 || p.x < -60 || p.x > width + 60 || p.life <= 0
            if (off) { it.remove(); continue }
            val a = (255 * p.life).toInt().coerceIn(0, 255)
            if (p.glyph == null) {
                paint.color = Color.argb(a, Color.red(p.color), Color.green(p.color), Color.blue(p.color))
                paint.style = Paint.Style.FILL
                canvas.drawCircle(p.x, p.y, p.size / 2, paint)
            } else {
                paint.textSize = p.size
                paint.alpha = a
                canvas.save(); canvas.rotate(p.rot, p.x, p.y)
                canvas.drawText(p.glyph, p.x, p.y + p.size / 3, paint)
                canvas.restore()
                paint.alpha = 255
            }
        }

        if (fallType != "none" || parts.isNotEmpty()) postInvalidateOnAnimation() else lastFrame = 0L
    }
}
