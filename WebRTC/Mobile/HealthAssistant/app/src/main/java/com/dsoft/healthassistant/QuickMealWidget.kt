package com.dsoft.healthassistant

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/** Home-screen widget: one tap opens a new meal entry for today. */
class QuickMealWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { render(ctx, mgr, it) }
    }

    companion object {
        fun refreshAll(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx) ?: return
            mgr.getAppWidgetIds(ComponentName(ctx, QuickMealWidget::class.java)).forEach { render(ctx, mgr, it) }
        }

        fun render(ctx: Context, mgr: AppWidgetManager, id: Int) {
            val v = RemoteViews(ctx.packageName, R.layout.widget_quick_meal)
            val n = Store.mealsForDate(ctx, Store.today()).size
            v.setTextViewText(R.id.sub, if (n > 0) "$n bữa hôm nay · chạm để thêm" else "Chạm để thêm nhanh")
            val tap = PendingIntent.getActivity(
                ctx, 0, Intent(ctx, QuickMealActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            v.setOnClickPendingIntent(R.id.root, tap)
            mgr.updateAppWidget(id, v)
        }
    }
}
