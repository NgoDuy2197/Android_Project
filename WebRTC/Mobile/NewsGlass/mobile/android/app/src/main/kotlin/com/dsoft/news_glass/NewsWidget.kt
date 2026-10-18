package com.dsoft.news_glass

import android.app.Activity
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.format.DateUtils
import android.view.View
import android.widget.RemoteViews
import java.util.concurrent.Executors

/** Home-screen widget: newest unread article; the small ▸ marks it read and shows the next. */
class NewsWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { render(ctx, mgr, it) }
    }

    override fun onAppWidgetOptionsChanged(ctx: Context, mgr: AppWidgetManager, id: Int, options: Bundle) {
        render(ctx, mgr, id) // resized: re-fit the headline
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == ACTION_NEXT) {
            val id = intent.getStringExtra(EXTRA_ITEM)
            val wid = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)
            val mgr = AppWidgetManager.getInstance(ctx)
            // Ignore taps while the previous one is still being handled.
            if (wid >= 0 && !busy.add(wid)) return
            // Instant feedback: hide the ▸ until the next article is drawn.
            if (wid >= 0) {
                mgr.partiallyUpdateAppWidget(
                    wid,
                    RemoteViews(ctx.packageName, layoutFor(ctx)).apply { setViewVisibility(R.id.btn_next, View.INVISIBLE) },
                )
            }
            val app = ctx.applicationContext
            val pending = goAsync()
            val started = System.currentTimeMillis()
            // Local work only (news.json): never waits behind a network fetch, works offline.
            io.execute {
                try {
                    if (id != null) NewsRepo.markRead(app, listOf(id))
                    val wait = 400 - (System.currentTimeMillis() - started)
                    if (wait > 0) Thread.sleep(wait)
                    refreshAll(app)
                } finally {
                    busy.remove(wid)
                    pending.finish()
                }
            }
            return
        }
        super.onReceive(ctx, intent)
    }

    companion object {
        const val ACTION_NEXT = "com.dsoft.news_glass.NEXT"
        const val EXTRA_ITEM = "item"
        /** Local file work (mark read, redraw). Kept separate from [net] so offline taps stay instant. */
        val io = Executors.newSingleThreadExecutor()

        /** Network fetches (can block for the connect/read timeouts when offline). */
        val net = Executors.newSingleThreadExecutor()
        private val busy = java.util.Collections.synchronizedSet(HashSet<Int>())

        private fun layoutFor(ctx: Context) =
            if (NewsConfig.load(ctx).theme == "midnight") R.layout.widget_news_dark else R.layout.widget_news

        private val accents = mapOf(
            "yahoo" to "#6001D2", "rosy" to "#E0457B", "ocean" to "#0284C7", "midnight" to "#A78BFA",
        )

        fun refreshAll(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            mgr.getAppWidgetIds(ComponentName(ctx, NewsWidget::class.java)).forEach { render(ctx, mgr, it) }
        }

        fun render(ctx: Context, mgr: AppWidgetManager, wid: Int) {
            val cfg = NewsConfig.load(ctx)
            val v = RemoteViews(ctx.packageName, layoutFor(ctx))
            val accent = Color.parseColor(accents[cfg.theme] ?: "#6001D2")
            val unread = NewsRepo.unread(ctx)
            val item = unread.firstOrNull()

            v.setTextColor(R.id.brand, accent)
            v.setTextViewText(R.id.count, if (unread.isEmpty()) "" else "${unread.size} chưa đọc")
            val openApp = PendingIntent.getActivity(
                ctx, wid, Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            v.setOnClickPendingIntent(R.id.header, openApp)

            // Fit text to the widget's real size (it can be resized).
            val opts = mgr.getAppWidgetOptions(wid)
            val hDp = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 110).coerceAtLeast(60)
            val wDp = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250).coerceAtLeast(120)
            val big = hDp >= 170 && wDp >= 220
            val titleSp = if (big) 19f else 16f
            val lineDp = titleSp * 1.32f
            val freeDp = hDp - 62 // header + meta + paddings
            v.setTextViewTextSize(R.id.headline, android.util.TypedValue.COMPLEX_UNIT_SP, titleSp)

            if (item == null) {
                v.setTextViewText(R.id.meta, "")
                v.setTextViewText(R.id.headline, "Bạn đã đọc hết tin 🎉")
                v.setViewVisibility(R.id.summary, View.GONE)
                v.setOnClickPendingIntent(R.id.headline, openApp)
                v.setViewVisibility(R.id.btn_next, View.GONE)
            } else {
                val ago = DateUtils.getRelativeTimeSpanString(item.published, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
                v.setTextViewText(R.id.meta, "${item.source} · $ago")
                v.setTextViewText(R.id.headline, item.title)
                // Title gets the lines it needs (up to the space); leftovers go to the summary.
                val maxTitle = (freeDp / lineDp).toInt().coerceIn(2, 10)
                val charsPerLine = ((wDp - 70) / (titleSp * 0.55f)).coerceAtLeast(10f)
                val titleLines = minOf(maxTitle, kotlin.math.ceil(item.title.length / charsPerLine).toInt().coerceAtLeast(1))
                v.setInt(R.id.headline, "setMaxLines", maxTitle)
                val summaryLines = ((freeDp - titleLines * lineDp - 4) / 17f).toInt()
                if (item.summary.isNotBlank() && summaryLines >= 1) {
                    v.setTextViewText(R.id.summary, item.summary)
                    v.setInt(R.id.summary, "setMaxLines", summaryLines.coerceAtMost(8))
                    v.setViewVisibility(R.id.summary, View.VISIBLE)
                } else {
                    v.setViewVisibility(R.id.summary, View.GONE)
                }
                v.setOnClickPendingIntent(R.id.headline, OpenArticleActivity.pending(ctx, item))
                v.setOnClickPendingIntent(R.id.summary, OpenArticleActivity.pending(ctx, item))
                v.setViewVisibility(R.id.btn_next, View.VISIBLE)
                val next = Intent(ctx, NewsWidget::class.java).setAction(ACTION_NEXT).putExtra(EXTRA_ITEM, item.id)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, wid)
                v.setOnClickPendingIntent(
                    R.id.btn_next,
                    PendingIntent.getBroadcast(ctx, wid, next, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE),
                )
            }
            mgr.updateAppWidget(wid, v)
        }
    }
}

/** Trampoline for widget/notification taps: mark read, open the browser, refresh widget. */
class OpenArticleActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra("id")
        val link = intent.getStringExtra("link")
        val app = applicationContext
        NewsWidget.io.execute {
            if (id != null) NewsRepo.markRead(app, listOf(id))
            NewsWidget.refreshAll(app)
        }
        if (!link.isNullOrBlank()) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {
            }
        }
        finish()
    }

    companion object {
        fun pending(ctx: Context, item: Item): PendingIntent {
            val i = Intent(ctx, OpenArticleActivity::class.java)
                .putExtra("id", item.id)
                .putExtra("link", item.link)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            return PendingIntent.getActivity(
                ctx, item.id.hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }
}
