package com.dsoft.photo_frame

import android.app.Activity
import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.RemoteViews
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.Executors

/**
 * Photo frame widget. The whole frame is one bitmap drawn by [FrameRenderer]
 * at the widget's real pixel size, so resizing re-draws (and re-scales) it.
 */
class PhotoWidget : AppWidgetProvider() {

    override fun onReceive(ctx: Context, intent: Intent) {
        val app = ctx.applicationContext
        val mgr = AppWidgetManager.getInstance(app)
        val pending = goAsync()
        io.execute {
            try {
                when (intent.action) {
                    AppWidgetManager.ACTION_APPWIDGET_UPDATE, AppWidgetManager.ACTION_APPWIDGET_OPTIONS_CHANGED -> {
                        val ids = intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS)
                            ?: intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1).let { if (it >= 0) intArrayOf(it) else ids(app) }
                        ids.forEach { claim(app, it); render(app, mgr, it) }
                        Ticker.schedule(app)
                    }
                    AppWidgetManager.ACTION_APPWIDGET_DELETED ->
                        intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS)?.forEach {
                            FrameStore.forget(app, it)
                            WidgetImages.forget(app, it)
                        }
                    ACTION_TAP -> {
                        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)
                        val cfg = FrameStore.frame(app, FrameStore.widgetFrame(app, id))
                        if (cfg != null) FrameStore.advance(app, id, cfg)
                        render(app, mgr, id)
                    }
                }
            } catch (_: Exception) {
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_TAP = "com.dsoft.photo_frame.TAP"
        val io = Executors.newSingleThreadExecutor()

        fun ids(ctx: Context): IntArray =
            AppWidgetManager.getInstance(ctx).getAppWidgetIds(ComponentName(ctx, PhotoWidget::class.java))

        /** New widget without a frame: take the one picked in the app, else the first. */
        private fun claim(ctx: Context, id: Int) {
            if (FrameStore.widgetFrame(ctx, id) != null) return
            val f = FrameStore.takePendingPin(ctx) ?: FrameStore.frames(ctx).firstOrNull()?.id ?: return
            FrameStore.assign(ctx, id, f)
        }

        /** Widget size in px: width = min width (portrait), height = max height. */
        fun sizePx(ctx: Context, mgr: AppWidgetManager, id: Int): Pair<Int, Int> {
            val o = mgr.getAppWidgetOptions(id)
            val d = ctx.resources.displayMetrics.density
            val w = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 160).coerceAtLeast(60)
            val h = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 160).coerceAtLeast(60)
            return FrameRenderer.fit((w * d).toInt(), (h * d).toInt())
        }

        fun render(ctx: Context, mgr: AppWidgetManager, id: Int) {
            val cfg = FrameStore.frame(ctx, FrameStore.widgetFrame(ctx, id))
            val v = RemoteViews(ctx.packageName, R.layout.widget_photo)
            val (w, h) = sizePx(ctx, mgr, id)
            if (cfg == null) {
                v.setImageViewUri(R.id.image, null)
                v.setTextViewText(R.id.hint, "Mở app Khung Ảnh để tạo khung")
                v.setViewVisibility(R.id.hint, android.view.View.VISIBLE)
            } else {
                val photos = FrameStore.current(ctx, id, cfg).mapNotNull { FrameRenderer.probe(ctx, it) }
                // Through a file + provider: a bitmap this size is too big for RemoteViews.
                v.setImageViewUri(R.id.image, WidgetImages.publish(ctx, id, FrameRenderer.render(ctx, cfg, photos, w, h)))
                v.setViewVisibility(R.id.hint, android.view.View.GONE)
            }
            // Tap on the photo = open it (or the app); the small ▸ = next photos.
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val next = PendingIntent.getBroadcast(
                ctx, id,
                Intent(ctx, PhotoWidget::class.java).setAction(ACTION_TAP).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id),
                flags,
            )
            val tapIntent = when (cfg?.tap) {
                null, "app" -> PendingIntent.getActivity(ctx, id, Intent(ctx, MainActivity::class.java), flags)
                else -> PendingIntent.getActivity(
                    ctx, id,
                    Intent(ctx, ViewerActivity::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                    flags,
                )
            }
            v.setOnClickPendingIntent(R.id.root, tapIntent)
            v.setViewVisibility(R.id.btn_next, if (cfg != null && cfg.nextButton) android.view.View.VISIBLE else android.view.View.GONE)
            v.setOnClickPendingIntent(R.id.btn_next, next)
            mgr.updateAppWidget(id, v)
        }

        fun refreshAll(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            ids(ctx).forEach { render(ctx, mgr, it) }
        }
    }
}

/** Every 15 min: widgets whose frame interval elapsed move to their next photos. */
object Ticker {
    private const val PERIOD = 15 * 60_000L

    fun schedule(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(
            ctx, 0, Intent(ctx, TickReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        am.setInexactRepeating(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + PERIOD, PERIOD, pi)
    }

    fun tick(ctx: Context) {
        val mgr = AppWidgetManager.getInstance(ctx)
        val now = System.currentTimeMillis()
        for (id in PhotoWidget.ids(ctx)) {
            val cfg = FrameStore.frame(ctx, FrameStore.widgetFrame(ctx, id)) ?: continue
            if (cfg.interval > 0 && now - FrameStore.lastChange(ctx, id) >= cfg.interval * 60_000L - 60_000L) {
                FrameStore.advance(ctx, id, cfg)
                PhotoWidget.render(ctx, mgr, id)
            }
        }
    }
}

class TickReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val pending = goAsync()
        val app = ctx.applicationContext
        PhotoWidget.io.execute {
            try {
                if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
                    Ticker.schedule(app)
                    PhotoWidget.refreshAll(app)
                } else {
                    Ticker.tick(app)
                }
            } finally {
                pending.finish()
            }
        }
    }
}

/** Shown when a widget is placed: choose which frame it displays. */
class FrameConfigureActivity : Activity() {
    private var wid = AppWidgetManager.INVALID_APPWIDGET_ID

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        wid = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, wid))
        if (wid == AppWidgetManager.INVALID_APPWIDGET_ID) return finish()
        val frames = FrameStore.frames(this)
        val current = FrameStore.widgetFrame(this, wid)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(20), dp(22), dp(16))
            background = GradientDrawable().apply { cornerRadius = dp(26).toFloat(); setColor(Color.parseColor("#F7F6FF")) }
        }
        root.addView(TextView(this).apply {
            text = "Chọn khung cho widget"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
            setTextColor(Color.parseColor("#1C1C1E"))
            paint.isFakeBoldText = true
        })
        val group = RadioGroup(this).apply { setPadding(0, dp(10), 0, dp(6)) }
        frames.forEachIndexed { i, f ->
            group.addView(RadioButton(this).apply {
                id = 100 + i
                text = f.name.ifEmpty { "Khung ${i + 1}" }
                tag = f.id
                isChecked = f.id == current || (current == null && i == 0)
            })
        }
        if (frames.isEmpty()) root.addView(TextView(this).apply { text = "Chưa có khung — mở app để tạo." })
        root.addView(group)
        root.addView(Button(this).apply {
            text = "Xong"
            setTextColor(Color.WHITE)
            isAllCaps = false
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.parseColor("#FF6E96"), Color.parseColor("#6E5AF0")),
            ).apply { cornerRadius = dp(16).toFloat() }
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(50)).apply { topMargin = dp(10) }
            setOnClickListener {
                val f = findViewById<RadioButton>(group.checkedRadioButtonId)?.tag as? String ?: frames.firstOrNull()?.id
                val app = applicationContext
                if (f != null) FrameStore.assign(app, wid, f)
                PhotoWidget.io.execute {
                    PhotoWidget.render(app, AppWidgetManager.getInstance(app), wid)
                    Ticker.schedule(app)
                }
                setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, wid))
                finish()
            }
        })
        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#66000000"))
            addView(LinearLayout(this@FrameConfigureActivity).apply {
                gravity = Gravity.CENTER
                setPadding(dp(18), dp(80), dp(18), dp(40))
                addView(root, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            })
        })
    }

}
