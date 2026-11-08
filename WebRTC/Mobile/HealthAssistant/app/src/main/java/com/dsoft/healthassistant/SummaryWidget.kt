package com.dsoft.healthassistant

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/** Home-screen widget: today's calories eaten, water drunk and calories burned, side by side. */
class SummaryWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { render(ctx, mgr, it) }
    }

    companion object {
        fun refreshAll(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx) ?: return
            mgr.getAppWidgetIds(ComponentName(ctx, SummaryWidget::class.java)).forEach { render(ctx, mgr, it) }
        }

        private fun caloriesToday(ctx: Context): Int {
            val d = Store.today()
            val dayReview = Store.loadDayReview(ctx, d).let { if (it.isNotBlank()) Ai.parse(it) else null }
            if (dayReview != null) return dayReview.calories
            return Store.mealsForDate(ctx, d).sumOf { m -> if (m.reviewJson.isNotBlank()) Ai.parse(m.reviewJson)?.calories ?: 0 else 0 }
        }

        fun render(ctx: Context, mgr: AppWidgetManager, id: Int) {
            I18n.apply(ctx)
            val d = Store.today()
            val v = RemoteViews(ctx.packageName, R.layout.widget_summary)

            val eaten = caloriesToday(ctx)
            val calTarget = Store.loadProfile(ctx).targetCalories()
            val water = Water.amount(ctx, d)
            val waterTarget = Water.targetMl(ctx)
            val burned = Exercise.burned(ctx, d)

            v.setTextViewText(R.id.cal_label, tr("Calo", "Calories"))
            v.setTextViewText(R.id.cal_value, "$eaten")
            v.setTextViewText(R.id.cal_sub, "/ $calTarget")

            v.setTextViewText(R.id.water_label, tr("Nước", "Water"))
            v.setTextViewText(R.id.water_value, "$water")
            v.setTextViewText(R.id.water_sub, "/ $waterTarget ml")

            v.setTextViewText(R.id.ex_label, tr("Đốt", "Burned"))
            v.setTextViewText(R.id.ex_value, "$burned")
            v.setTextViewText(R.id.ex_sub, "kcal")

            val open = PendingIntent.getActivity(
                ctx, id,
                Intent(ctx, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            v.setOnClickPendingIntent(R.id.root, open)

            mgr.updateAppWidget(id, v)
        }
    }
}
