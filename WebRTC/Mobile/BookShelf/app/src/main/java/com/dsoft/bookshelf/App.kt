package com.dsoft.bookshelf

import android.app.Activity
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.util.Locale

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        Library.init(this)
        JobManager.init(this)
        try { PDFBoxResourceLoader.init(applicationContext) } catch (_: Throwable) {}
        createChannels()
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < 26) return
        val c = Locales.wrap(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_TTS, c.getString(R.string.ch_tts), NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_JOBS, c.getString(R.string.ch_jobs), NotificationManager.IMPORTANCE_LOW))
    }

    companion object {
        const val CH_TTS = "tts"
        const val CH_JOBS = "jobs"
        lateinit var instance: App
        val main = Handler(Looper.getMainLooper())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        /** Auto-open the last book only once per process start. */
        var autoOpenDone = false
    }
}

/** In-app language switching: wraps contexts with the chosen locale. */
object Locales {
    fun wrap(base: Context): Context {
        val tag = Prefs.lang(base)
        val loc = when (tag) {
            "zh" -> Locale.SIMPLIFIED_CHINESE
            else -> Locale.forLanguageTag(tag)
        }
        Locale.setDefault(loc)
        val cfg = Configuration(base.resources.configuration)
        cfg.setLocale(loc)
        cfg.setLayoutDirection(loc)
        return base.createConfigurationContext(cfg)
    }
}

/**
 * Base for every screen: locale + theme palette, recreate when style changes,
 * in-app blue-light filter overlay and in-app brightness.
 */
open class BaseActivity : Activity() {
    lateinit var p: Palette
    private var stamp = ""
    private var filterView: View? = null
    val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(Locales.wrap(newBase))

    override fun onCreate(savedInstanceState: Bundle?) {
        p = palette()
        stamp = Prefs.styleStamp(this)
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(p.bg))
        super.onCreate(savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        if (stamp != Prefs.styleStamp(this)) {
            recreate(); return
        }
        applyComfort()
    }

    override fun onDestroy() {
        uiScope.cancel()
        super.onDestroy()
    }

    /** Applies the warm overlay and brightness from Prefs. Call again after changing them. */
    fun applyComfort() {
        val decor = window.decorView as ViewGroup
        if (Prefs.filterOn(this)) {
            val v = filterView ?: View(this).apply {
                isClickable = false
                isFocusable = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                filterView = this
            }
            if (v.parent == null) decor.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            v.setBackgroundColor(filterColor(Prefs.filterStrength(this), Prefs.filterWarmth(this)))
            v.bringToFront()
        } else {
            filterView?.let { decor.removeView(it) }
        }
        val lp = window.attributes
        lp.screenBrightness = if (Prefs.dimOn(this)) Prefs.dimLevel(this).coerceIn(1, 100) / 100f
        else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = lp
        if (Prefs.keepScreenOn(this) && keepScreenOnHere()) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    open fun keepScreenOnHere() = false

    companion object {
        /** Amber overlay: warmth 0 = soft yellow, 100 = deep orange; strength 0..80 % -> alpha. */
        fun filterColor(strength: Int, warmth: Int): Int {
            val w = warmth.coerceIn(0, 100) / 100f
            val r = 255
            val g = (200 - 110 * w).toInt()
            val b = (60 - 60 * w).toInt().coerceAtLeast(0)
            val a = (strength.coerceIn(0, 80) / 100f * 0.75f * 255).toInt()
            return Color.argb(a, r, g, b)
        }
    }
}

fun Activity.restartApp() {
    val i = packageManager.getLaunchIntentForPackage(packageName) ?: return
    i.addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK or android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    startActivity(i)
}
