package com.dsoft.healthassistant

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.Toast

/** Home-screen widget: the "+" button logs one cup for today; the text opens the water screen. */
class WaterWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { render(ctx, mgr, it) }
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        super.onReceive(ctx, intent)
        if (intent.action == ACTION_ADD_CUP) {
            val cup = Water.cupMl(ctx)
            val total = Water.addCup(ctx)
            Toast.makeText(ctx, "+$cup ml · hôm nay $total ml", Toast.LENGTH_SHORT).show()
            refreshAll(ctx)
        }
    }

    companion object {
        const val ACTION_ADD_CUP = "com.dsoft.healthassistant.ADD_CUP"

        fun refreshAll(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx) ?: return
            mgr.getAppWidgetIds(ComponentName(ctx, WaterWidget::class.java)).forEach { render(ctx, mgr, it) }
        }

        fun render(ctx: Context, mgr: AppWidgetManager, id: Int) {
            val v = RemoteViews(ctx.packageName, R.layout.widget_water)
            val target = Water.targetMl(ctx)
            val cup = Water.cupMl(ctx)
            val now = Water.amount(ctx, Store.today())
            val pct = if (target > 0) (now * 100 / target).coerceIn(0, 100) else 0

            v.setTextViewText(R.id.sub, "$now / $target ml · ${Water.cups(now, cup)} cốc")
            v.setProgressBar(R.id.bar, 100, pct, false)

            val add = PendingIntent.getBroadcast(
                ctx, id,
                Intent(ctx, WaterWidget::class.java).setAction(ACTION_ADD_CUP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            v.setOnClickPendingIntent(R.id.add, add)

            val open = PendingIntent.getActivity(
                ctx, id,
                Intent(ctx, WaterActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            v.setOnClickPendingIntent(R.id.info, open)

            mgr.updateAppWidget(id, v)
        }
    }
}
