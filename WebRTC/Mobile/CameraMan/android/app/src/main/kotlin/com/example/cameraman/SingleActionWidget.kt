package com.example.cameraman

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent

/**
 * Base for the four single-purpose home-screen widgets (front/back × photo/
 * video). Rendering and tap handling are centralised in [WidgetUpdater] so the
 * standalone widgets and the combo widget stay visually in sync (e.g. video
 * buttons turn red while recording). A tap never opens the app UI, so the
 * app-lock PIN is never involved.
 */
abstract class SingleActionWidget : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        manager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        WidgetUpdater.updateAll(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        WidgetUpdater.handleAction(context, intent)
    }
}

class FrontPhotoWidget : SingleActionWidget()
class FrontVideoWidget : SingleActionWidget()
class BackPhotoWidget : SingleActionWidget()
class BackVideoWidget : SingleActionWidget()

/**
 * The 5-button combo widget: front/back photo & video plus a motion-detection
 * toggle, laid out horizontally in a black frame.
 */
class ComboWidget : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        manager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        WidgetUpdater.updateAll(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        WidgetUpdater.handleAction(context, intent)
    }
}
