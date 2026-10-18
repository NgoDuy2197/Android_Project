package com.dsoft.vocab_widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews

/** Per-widget settings kept in SharedPreferences. */
object WidgetPrefs {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("vocab_widgets", Context.MODE_PRIVATE)

    fun collection(ctx: Context, id: Int): String {
        val saved = prefs(ctx).getString("c_$id", null)
        val all = VocabData.collectionList(ctx)
        return if (saved != null && all.any { it.first == saved }) saved else all.firstOrNull()?.first ?: ""
    }

    fun lines(ctx: Context, id: Int): Int {
        val saved = prefs(ctx).getInt("l_$id", 0)
        if (saved in 1..3) return saved
        return VocabData.fieldCount(ctx, collection(ctx, id)).coerceIn(1, 3)
    }

    fun autoMinutes(ctx: Context, id: Int) = prefs(ctx).getInt("a_$id", 0)
    fun word(ctx: Context, id: Int): String? = prefs(ctx).getString("w_$id", null)
    fun lastChange(ctx: Context, id: Int) = prefs(ctx).getLong("t_$id", 0)

    fun setConfig(ctx: Context, id: Int, collectionId: String, lines: Int, autoMinutes: Int) {
        val old = prefs(ctx).getString("c_$id", null)
        prefs(ctx).edit().apply {
            putString("c_$id", collectionId)
            putInt("l_$id", lines)
            putInt("a_$id", autoMinutes)
            if (old != collectionId) remove("w_$id")
        }.apply()
    }

    fun setWord(ctx: Context, id: Int, wordId: String?) {
        prefs(ctx).edit().putString("w_$id", wordId).putLong("t_$id", System.currentTimeMillis()).apply()
    }

    fun delete(ctx: Context, id: Int) {
        prefs(ctx).edit().apply { listOf("c_", "l_", "a_", "w_", "t_").forEach { remove("$it$id") } }.apply()
    }
}

class VocabWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        val now = System.currentTimeMillis()
        for (id in ids) {
            val auto = WidgetPrefs.autoMinutes(ctx, id)
            if (auto > 0 && now - WidgetPrefs.lastChange(ctx, id) >= auto * 60_000L - 60_000L) {
                next(ctx, id)
            }
            render(ctx, mgr, id)
        }
    }

    override fun onDeleted(ctx: Context, ids: IntArray) {
        ids.forEach { WidgetPrefs.delete(ctx, it) }
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        when (intent.action) {
            ACTION_NEXT -> {
                Speaker.clearCache(ctx)
                next(ctx, id)
                render(ctx, AppWidgetManager.getInstance(ctx), id)
            }
            ACTION_LEARNED -> {
                val col = WidgetPrefs.collection(ctx, id)
                WidgetPrefs.word(ctx, id)?.let { VocabData.markLearned(ctx, col, it) }
                Speaker.clearCache(ctx)
                next(ctx, id)
                refreshAll(ctx) // other widgets on the same collection
            }
            ACTION_SPEAK -> {
                val text = intent.getStringExtra(EXTRA_TEXT).orEmpty()
                val locale = intent.getStringExtra(EXTRA_LOCALE).orEmpty()
                val line = intent.getIntExtra(EXTRA_LINE, 0)
                val key = id * 10 + line
                val now = System.currentTimeMillis()
                // Anti-spam: the line's speaker hides for ~1 s; taps meanwhile are ignored.
                if (text.isNotBlank() && locale.isNotBlank() && now >= (busyUntil[key] ?: 0)) {
                    busyUntil[key] = now + BUSY_MS
                    val mgr = AppWidgetManager.getInstance(ctx)
                    render(ctx, mgr, id)
                    val pending = goAsync()
                    val app = ctx.applicationContext
                    val main = android.os.Handler(android.os.Looper.getMainLooper())
                    var spoken = false
                    var shown = false
                    fun finishIfDone() {
                        if (spoken && shown) pending.finish()
                    }
                    main.postDelayed({
                        busyUntil.remove(key)
                        render(app, mgr, id)
                        shown = true
                        finishIfDone()
                    }, BUSY_MS)
                    Speaker.speakCached(app, text, locale) {
                        spoken = true
                        finishIfDone()
                    }
                }
            }
            else -> super.onReceive(ctx, intent)
        }
    }

    companion object {
        const val ACTION_NEXT = "com.dsoft.vocab_widget.NEXT"
        const val ACTION_LEARNED = "com.dsoft.vocab_widget.LEARNED"
        const val ACTION_SPEAK = "com.dsoft.vocab_widget.SPEAK"
        const val EXTRA_TEXT = "text"
        const val EXTRA_LOCALE = "locale"
        const val EXTRA_LINE = "line"
        private const val BUSY_MS = 1000L
        private val busyUntil = HashMap<Int, Long>()

        private val lineIds = intArrayOf(R.id.line1, R.id.line2, R.id.line3)
        private val rowIds = intArrayOf(R.id.row1, R.id.row2, R.id.row3)
        private val speakIds = intArrayOf(R.id.spk1, R.id.spk2, R.id.spk3)

        fun ids(ctx: Context): IntArray =
            AppWidgetManager.getInstance(ctx).getAppWidgetIds(ComponentName(ctx, VocabWidget::class.java))

        fun refreshAll(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            ids(ctx).forEach { render(ctx, mgr, it) }
        }

        private fun next(ctx: Context, id: Int) {
            val col = WidgetPrefs.collection(ctx, id)
            val card = VocabData.randomCard(ctx, col, WidgetPrefs.word(ctx, id))
            WidgetPrefs.setWord(ctx, id, card?.wordId)
        }

        private fun broadcast(ctx: Context, id: Int, action: String, code: Int, extras: Intent.() -> Unit = {}): PendingIntent {
            val i = Intent(ctx, VocabWidget::class.java).apply {
                this.action = action
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                extras()
            }
            return PendingIntent.getBroadcast(
                ctx, id * 10 + code, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        /** Plain message in rows 1–2: no speakers, no action buttons. */
        private fun messageRows(v: RemoteViews, open: PendingIntent) {
            // Drop stale per-line speak actions: any tap now opens the app.
            for (vid in intArrayOf(R.id.root, R.id.row1, R.id.row2, R.id.line1, R.id.line2)) v.setOnClickPendingIntent(vid, open)
            v.setViewVisibility(R.id.row1, View.VISIBLE)
            v.setViewVisibility(R.id.row2, View.VISIBLE)
            v.setViewVisibility(R.id.row3, View.GONE)
            v.setViewVisibility(R.id.spk1, View.GONE)
            v.setViewVisibility(R.id.spk2, View.GONE)
            v.setViewVisibility(R.id.actions, View.GONE)
        }

        fun render(ctx: Context, mgr: AppWidgetManager, id: Int) {
            val v = RemoteViews(ctx.packageName, R.layout.widget_vocab)
            val col = WidgetPrefs.collection(ctx, id)
            val lines = WidgetPrefs.lines(ctx, id)
            val summary = VocabData.summary(ctx, col)

            val open = PendingIntent.getActivity(
                ctx, id * 10 + 9, Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            v.setOnClickPendingIntent(R.id.header, open)

            if (summary == null) {
                v.setTextViewText(R.id.title, "Từ Vựng")
                v.setTextViewText(R.id.progress, "")
                v.setTextViewText(R.id.line1, "Chưa có bộ từ")
                v.setTextViewText(R.id.line2, "Mở app để tạo hoặc chọn bộ từ")
                messageRows(v, open)
                v.setViewVisibility(R.id.actions, View.GONE)
                mgr.updateAppWidget(id, v)
                return
            }
            val (name, learned, total) = summary
            v.setTextViewText(R.id.title, name)
            v.setTextViewText(R.id.progress, "$learned/$total")

            var card = VocabData.card(ctx, col, WidgetPrefs.word(ctx, id))
            if (card == null) {
                card = VocabData.randomCard(ctx, col, null)
                WidgetPrefs.setWord(ctx, id, card?.wordId)
            }
            if (card == null) {
                v.setTextViewText(R.id.line1, "🎉 Đã thuộc hết!")
                v.setTextViewText(R.id.line2, "Mở app → \"Đặt lại đã thuộc\" để học lại")
                messageRows(v, open)
                v.setViewVisibility(R.id.actions, View.GONE)
                mgr.updateAppWidget(id, v)
                return
            }
            v.setViewVisibility(R.id.actions, View.VISIBLE)
            for (i in 0 until 3) {
                val show = i < lines && i < card.lines.size && card.lines[i].first.isNotBlank()
                v.setViewVisibility(rowIds[i], if (show) View.VISIBLE else View.GONE)
                if (!show) continue
                val (text, locale) = card.lines[i]
                v.setTextViewText(lineIds[i], text)
                // Speaker at the start of each line, in that line's own language.
                val busy = System.currentTimeMillis() < (busyUntil[id * 10 + i] ?: 0)
                v.setViewVisibility(speakIds[i], if (locale.isEmpty()) View.GONE else if (busy) View.INVISIBLE else View.VISIBLE)
                if (locale.isNotEmpty()) {
                    val speak = broadcast(ctx, id, ACTION_SPEAK, 3 + i) {
                        putExtra(EXTRA_TEXT, text)
                        putExtra(EXTRA_LOCALE, locale)
                        putExtra(EXTRA_LINE, i)
                    }
                    v.setOnClickPendingIntent(speakIds[i], speak)
                    v.setOnClickPendingIntent(lineIds[i], speak)
                } else {
                    // Launchers re-apply onto old views: overwrite any stale speak action.
                    v.setOnClickPendingIntent(lineIds[i], open)
                }
            }
            v.setOnClickPendingIntent(R.id.btn_learned, broadcast(ctx, id, ACTION_LEARNED, 1))
            v.setOnClickPendingIntent(R.id.btn_next, broadcast(ctx, id, ACTION_NEXT, 2))
            mgr.updateAppWidget(id, v)
        }
    }
}
