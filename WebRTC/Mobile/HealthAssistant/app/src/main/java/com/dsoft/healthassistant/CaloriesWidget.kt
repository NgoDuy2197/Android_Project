package com.dsoft.healthassistant

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/** Home-screen widget: today's calories vs the target with a progress bar. Tapping opens the app. */
class CaloriesWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { render(ctx, mgr, it) }
    }

    companion object {
        fun refreshAll(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx) ?: return
            mgr.getAppWidgetIds(ComponentName(ctx, CaloriesWidget::class.java)).forEach { render(ctx, mgr, it) }
        }

        /** Calories counted for today: the whole-day review wins, else the sum of reviewed meals. */
        private fun caloriesToday(ctx: Context): Int {
            val d = Store.today()
            val dayReview = Store.loadDayReview(ctx, d).let { if (it.isNotBlank()) Ai.parse(it) else null }
            if (dayReview != null) return dayReview.calories
            return Store.mealsForDate(ctx, d).sumOf { m -> if (m.reviewJson.isNotBlank()) Ai.parse(m.reviewJson)?.calories ?: 0 else 0 }
        }

        fun render(ctx: Context, mgr: AppWidgetManager, id: Int) {
            I18n.apply(ctx)
            val v = RemoteViews(ctx.packageName, R.layout.widget_calories)
            val target = Store.loadProfile(ctx).targetCalories()
            val now = caloriesToday(ctx)
            val left = target - now
            val pct = if (target > 0) (now * 100 / target).coerceIn(0, 100) else 0

            v.setTextViewText(R.id.title, tr("Calo hôm nay", "Calories today"))
            v.setTextViewText(R.id.value, "$now")
            v.setTextViewText(R.id.unit, " / $target kcal")
            v.setTextViewText(
                R.id.sub,
                when {
                    target <= 0 -> tr("Thiết lập hồ sơ để có mục tiêu", "Set up a profile for a target")
                    left >= 0 -> tr("Còn ~$left kcal", "~$left kcal left")
                    else -> tr("Vượt ~${-left} kcal", "~${-left} kcal over")
                },
            )
            v.setProgressBar(R.id.bar, 100, pct, false)

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
