package com.dsoft.odiary

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Home-screen widget: one tap opens today's diary editor to jot a moment. */
class QuickNoteWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { render(ctx, mgr, it) }
    }

    companion object {
        /** Re-render every placed widget (e.g. after a theme change). */
        fun refreshAll(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx) ?: return
            mgr.getAppWidgetIds(ComponentName(ctx, QuickNoteWidget::class.java)).forEach { render(ctx, mgr, it) }
        }

        fun render(ctx: Context, mgr: AppWidgetManager, id: Int) {
            val v = RemoteViews(ctx.packageName, R.layout.widget_quick_note)
            v.setTextViewText(R.id.title, "Ghi nhanh")
            v.setTextColor(R.id.title, 0xFF1C1C1E.toInt())
            val today = SimpleDateFormat("EEEE, dd/MM", Locale("vi")).format(Date())
            v.setTextViewText(R.id.sub, today.replaceFirstChar { it.uppercase() })
            v.setTextColor(R.id.sub, 0xFF8E8E93.toInt())
            val tap = PendingIntent.getActivity(
                ctx, 0, Intent(ctx, QuickNoteActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            v.setOnClickPendingIntent(R.id.root, tap)
            mgr.updateAppWidget(id, v)
        }
    }
}
