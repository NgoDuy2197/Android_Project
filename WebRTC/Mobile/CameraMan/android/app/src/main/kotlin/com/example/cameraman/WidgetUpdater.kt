package com.example.cameraman

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.RemoteViews
import androidx.core.content.ContextCompat

/**
 * Central place that (a) turns a widget tap into a service command and (b)
 * re-renders every widget to reflect live state — video buttons turn red with a
 * stop icon while recording, and the motion button highlights while detection
 * is running. Both the standalone widgets and the 5-button combo route through
 * here, so their look stays in sync.
 */
object WidgetUpdater {

    const val ACTION_WIDGET = "com.example.cameraman.WIDGET_CAPTURE"
    const val EXTRA_KIND = "kind" // "photo" | "video" | "motion"

    /** Handles a widget tap broadcast. */
    fun handleAction(context: Context, intent: Intent) {
        if (intent.action != ACTION_WIDGET) return
        val kind = intent.getStringExtra(EXTRA_KIND) ?: "photo"
        val lens = intent.getStringExtra(Const.EXTRA_LENS) ?: Const.LENS_BACK
        val service = Intent(context, CameraCaptureService::class.java)
        when (kind) {
            "motion" -> service.action =
                if (CaptureBus.motionRunning) Const.ACTION_MOTION_STOP
                else Const.ACTION_MOTION_START
            "video" -> {
                service.action = Const.ACTION_VIDEO_TOGGLE
                service.putExtra(Const.EXTRA_LENS, lens)
            }
            else -> {
                service.action = Const.ACTION_PHOTO
                service.putExtra(Const.EXTRA_LENS, lens)
            }
        }
        try {
            ContextCompat.startForegroundService(context, service)
        } catch (_: Exception) {
            // Background-start limits on some OEMs; ignore rather than crash.
        }
    }

    /** Re-renders all placed widgets from the current [CaptureBus] state. */
    fun updateAll(context: Context) {
        val mgr = AppWidgetManager.getInstance(context) ?: return
        val recording = CaptureBus.recording
        val motion = CaptureBus.motionRunning
        try {
            updateSingle(context, mgr, FrontPhotoWidget::class.java,
                R.layout.widget_front_photo, "photo", Const.LENS_FRONT, 11, recording)
            updateSingle(context, mgr, FrontVideoWidget::class.java,
                R.layout.widget_front_video, "video", Const.LENS_FRONT, 12, recording)
            updateSingle(context, mgr, BackPhotoWidget::class.java,
                R.layout.widget_back_photo, "photo", Const.LENS_BACK, 13, recording)
            updateSingle(context, mgr, BackVideoWidget::class.java,
                R.layout.widget_back_video, "video", Const.LENS_BACK, 14, recording)
            updateCombo(context, mgr, recording, motion)
        } catch (_: Exception) {
        }
    }

    private fun updateSingle(
        context: Context,
        mgr: AppWidgetManager,
        cls: Class<*>,
        layoutRes: Int,
        kind: String,
        lens: String,
        requestCode: Int,
        recording: Boolean,
    ) {
        val ids = mgr.getAppWidgetIds(ComponentName(context, cls))
        if (ids == null || ids.isEmpty()) return
        val views = RemoteViews(context.packageName, layoutRes)
        views.setOnClickPendingIntent(
            R.id.widget_button, pending(context, cls, requestCode, kind, lens),
        )
        // Set BOTH states explicitly — relying on the layout default to revert
        // fails on launchers that cache RemoteViews (the button stayed red after
        // recording stopped).
        if (kind == "video") {
            if (recording) {
                views.setInt(R.id.widget_button, "setBackgroundResource",
                    R.drawable.widget_button_recording)
                views.setTextViewText(R.id.widget_button, "Dừng")
            } else {
                val bg = if (lens == Const.LENS_FRONT) R.drawable.widget_button_front
                else R.drawable.widget_button_back
                val txt = if (lens == Const.LENS_FRONT) R.string.widget_front_video
                else R.string.widget_back_video
                views.setInt(R.id.widget_button, "setBackgroundResource", bg)
                views.setTextViewText(R.id.widget_button, context.getString(txt))
            }
        }
        mgr.updateAppWidget(ids, views)
    }

    private fun updateCombo(
        context: Context,
        mgr: AppWidgetManager,
        recording: Boolean,
        motion: Boolean,
    ) {
        val ids = mgr.getAppWidgetIds(ComponentName(context, ComboWidget::class.java))
        if (ids == null || ids.isEmpty()) return
        val cls = ComboWidget::class.java
        val views = RemoteViews(context.packageName, R.layout.widget_combo)
        views.setOnClickPendingIntent(
            R.id.cb_front_photo, pending(context, cls, 21, "photo", Const.LENS_FRONT))
        views.setOnClickPendingIntent(
            R.id.cb_front_video, pending(context, cls, 22, "video", Const.LENS_FRONT))
        views.setOnClickPendingIntent(
            R.id.cb_back_photo, pending(context, cls, 23, "photo", Const.LENS_BACK))
        views.setOnClickPendingIntent(
            R.id.cb_back_video, pending(context, cls, 24, "video", Const.LENS_BACK))
        views.setOnClickPendingIntent(
            R.id.cb_motion, pending(context, cls, 25, "motion", Const.LENS_BACK))

        // Video buttons: set both states explicitly so they revert reliably.
        if (recording) {
            views.setInt(R.id.cb_front_video, "setBackgroundResource", R.drawable.widget_button_recording)
            views.setImageViewResource(R.id.cb_front_video, R.drawable.ic_stop)
            views.setInt(R.id.cb_back_video, "setBackgroundResource", R.drawable.widget_button_recording)
            views.setImageViewResource(R.id.cb_back_video, R.drawable.ic_stop)
        } else {
            views.setInt(R.id.cb_front_video, "setBackgroundResource", R.drawable.widget_button_front)
            views.setImageViewResource(R.id.cb_front_video, R.drawable.ic_videocam)
            views.setInt(R.id.cb_back_video, "setBackgroundResource", R.drawable.widget_button_back)
            views.setImageViewResource(R.id.cb_back_video, R.drawable.ic_videocam)
        }
        views.setInt(R.id.cb_motion, "setBackgroundResource",
            if (motion) R.drawable.widget_button_active else R.drawable.widget_button_motion)
        mgr.updateAppWidget(ids, views)
    }

    private fun pending(
        context: Context,
        cls: Class<*>,
        requestCode: Int,
        kind: String,
        lens: String,
    ): PendingIntent {
        val intent = Intent(context, cls).apply {
            action = ACTION_WIDGET
            putExtra(EXTRA_KIND, kind)
            putExtra(Const.EXTRA_LENS, lens)
        }
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags = flags or PendingIntent.FLAG_IMMUTABLE
        }
        return PendingIntent.getBroadcast(context, requestCode, intent, flags)
    }
}
