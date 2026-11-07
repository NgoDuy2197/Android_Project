package com.dsoft.remind_me

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Home-screen list widget: every reminder as a row. Tapping a row toggles it
 * on/off right there (no need to open the app) and bumps it to the top of the
 * list. Each row marks whether it is currently active (●) or off (○).
 */
class RemindWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { render(ctx, mgr, it) }
        mgr.notifyAppWidgetViewDataChanged(ids, R.id.list)
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == ACTION_TOGGLE) {
            val id = intent.getStringExtra(EXTRA_ID)
            if (id.isNullOrEmpty()) return
            val app = ctx.applicationContext
            val pending = goAsync() // file IO + alarm (re)scheduling off the main thread
            Thread {
                try {
                    Scheduler.toggle(app, id)
                    WidgetOrder.touch(app, id)   // just-tapped jumps to the top
                    Scheduler.rescheduleAll(app) // recompute nextFire + alarms, then refresh the widget
                    refresh(app)
                } finally {
                    pending.finish()
                }
            }.start()
            return
        }
        super.onReceive(ctx, intent)
    }

    companion object {
        const val ACTION_TOGGLE = "com.dsoft.remind_me.WIDGET_TOGGLE"
        const val EXTRA_ID = "id"

        /** Re-render every widget and tell the list to reload its rows. Safe to call from anywhere. */
        fun refresh(ctx: Context) {
            runCatching {
                val mgr = AppWidgetManager.getInstance(ctx) ?: return
                val ids = mgr.getAppWidgetIds(ComponentName(ctx, RemindWidget::class.java))
                if (ids.isEmpty()) return
                ids.forEach { render(ctx, mgr, it) }
                mgr.notifyAppWidgetViewDataChanged(ids, R.id.list)
            }
        }

        fun render(ctx: Context, mgr: AppWidgetManager, id: Int) {
            val v = RemoteViews(ctx.packageName, R.layout.widget_remind)

            // Header -> open the app.
            val openApp = PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            v.setOnClickPendingIntent(R.id.header, openApp)

            // List adapter (unique data per widget so hosts don't share a cached adapter).
            val svc = Intent(ctx, RemindWidgetService::class.java)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            svc.data = Uri.parse(svc.toUri(Intent.URI_INTENT_SCHEME))
            @Suppress("DEPRECATION")
            v.setRemoteAdapter(R.id.list, svc)
            v.setEmptyView(R.id.list, R.id.empty)

            // One template for all rows; each row fills in its own id.
            val template = PendingIntent.getBroadcast(
                ctx, 0, Intent(ctx, RemindWidget::class.java).setAction(ACTION_TOGGLE),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            v.setPendingIntentTemplate(R.id.list, template)

            mgr.updateAppWidget(id, v)
        }
    }
}

/** Remembers the order rows were last tapped in (most-recent first), so a tap floats the row to the top. */
object WidgetOrder {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("remind_widget", Context.MODE_PRIVATE)

    fun order(ctx: Context): List<String> = try {
        val a = JSONArray(sp(ctx).getString("order", "[]"))
        (0 until a.length()).map { a.getString(it) }
    } catch (_: Exception) {
        emptyList()
    }

    fun touch(ctx: Context, id: String) {
        val cur = ArrayList(order(ctx))
        cur.remove(id)
        cur.add(0, id)
        while (cur.size > 50) cur.removeAt(cur.size - 1)
        val a = JSONArray()
        cur.forEach { a.put(it) }
        sp(ctx).edit().putString("order", a.toString()).apply()
    }
}

class RemindWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        RemindWidgetFactory(applicationContext)
}

class RemindWidgetFactory(private val ctx: Context) : RemoteViewsService.RemoteViewsFactory {
    private var items: List<JSONObject> = emptyList()

    override fun onCreate() {}
    override fun onDestroy() {}

    override fun onDataSetChanged() {
        val arr = Scheduler.load(ctx).optJSONArray("reminders") ?: JSONArray()
        val list = ArrayList<JSONObject>(arr.length())
        for (i in 0 until arr.length()) list.add(arr.getJSONObject(i))
        val order = WidgetOrder.order(ctx)
        items = list.sortedWith(
            compareBy(
                { val i = order.indexOf(it.optString("id")); if (i < 0) Int.MAX_VALUE else i },
                { if (isActive(it)) 0 else 1 },
                { val nf = it.optLong("nextFire"); if (nf <= 0) Long.MAX_VALUE else nf },
            ),
        )
    }

    override fun getCount() = items.size
    override fun getViewTypeCount() = 1
    override fun hasStableIds() = true
    override fun getItemId(position: Int) = position.toLong()
    override fun getLoadingView(): RemoteViews? = null

    override fun getViewAt(position: Int): RemoteViews {
        val r = items.getOrNull(position) ?: return RemoteViews(ctx.packageName, R.layout.widget_remind_row)
        val row = RemoteViews(ctx.packageName, R.layout.widget_remind_row)
        val active = isActive(r)

        row.setTextViewText(R.id.r_emoji, r.optString("emoji").ifEmpty { "⏰" })
        row.setTextViewText(R.id.r_title, r.optString("title").ifEmpty { "Nhắc nhở" })
        row.setTextColor(R.id.r_title, if (active) 0xFF1C1C1E.toInt() else 0xFF9AA0A6.toInt())
        row.setTextViewText(R.id.r_sub, subLine(r, active))
        row.setTextViewText(R.id.r_status, if (active) "● Bật" else "○ Tắt")
        row.setTextColor(R.id.r_status, if (active) 0xFF16A34A.toInt() else 0xFF9CA3AF.toInt())

        row.setOnClickFillInIntent(R.id.r_row, Intent().putExtra(RemindWidget.EXTRA_ID, r.optString("id")))
        return row
    }

    // ---- helpers ----
    private fun isActive(r: JSONObject) = r.optBoolean("enabled", true) && r.optLong("nextFire") > 0

    private fun subLine(r: JSONObject, active: Boolean): String {
        val d = describe(r)
        val nf = r.optLong("nextFire")
        return if (active && nf > 0) "$d · ${relShort(nf)}" else d
    }

    private fun describe(r: JSONObject): String = when (r.optString("type")) {
        "countdown" -> "Đếm ngược " + fmtDur(r.optLong("durationSec") * 1000)
        "repeat" -> {
            val every = r.optInt("every", 1)
            val unit = unitLabel(r.optString("unit"))
            val base = "Mỗi " + (if (every == 1) "" else "$every ") + unit
            val a = r.optLong("anchor")
            if (a > 0) "$base lúc ${timeOf(a)}" else base
        }
        "once" -> "Lúc " + fmtDateTime(r.optLong("at"))
        else -> ""
    }

    private fun unitLabel(u: String) = when (u) {
        "minute" -> "phút"; "hour" -> "giờ"; "day" -> "ngày"; "week" -> "tuần"; "month" -> "tháng"
        else -> u
    }

    private fun two(v: Long) = v.toString().padStart(2, '0')

    private fun fmtDur(ms: Long): String {
        val total = ms / 1000
        val d = total / 86400
        val h = (total % 86400) / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return when {
            d >= 1 -> "$d ngày ${two(h)}:${two(m)}:${two(s)}"
            h > 0 -> "$h:${two(m)}:${two(s)}"
            else -> "${two(m)}:${two(s)}"
        }
    }

    private fun timeOf(ms: Long) = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))
    private fun fmtDateTime(ms: Long) = SimpleDateFormat("HH:mm dd/MM", Locale.getDefault()).format(Date(ms))

    /** Fire time, short: just the clock when it is today, otherwise date + clock. */
    private fun relShort(ms: Long): String {
        val now = Calendar.getInstance()
        val t = Calendar.getInstance().apply { timeInMillis = ms }
        val sameDay = now.get(Calendar.YEAR) == t.get(Calendar.YEAR) &&
            now.get(Calendar.DAY_OF_YEAR) == t.get(Calendar.DAY_OF_YEAR)
        return if (sameDay) timeOf(ms) else fmtDateTime(ms)
    }
}
