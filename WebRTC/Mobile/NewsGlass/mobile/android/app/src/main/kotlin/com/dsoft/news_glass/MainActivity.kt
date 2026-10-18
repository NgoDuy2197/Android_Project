package com.dsoft.news_glass

import android.Manifest
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7)
        }
        Thread { KeepAlive.ensure(applicationContext, WATCHDOG_SEC) }.start()
    }

    /** OEM screens that let an app auto-start / run in the background. */
    private val autostartScreens = listOf(
        "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity",
        "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
        "com.oplus.safecenter" to "com.oplus.safecenter.startupapp.StartupAppListActivity",
        "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity",
        "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
    )

    private fun openPermission(which: String) {
        val pkg = android.net.Uri.parse("package:$packageName")
        if (which == "autostart") {
            for ((p, c) in autostartScreens) {
                try {
                    startActivity(android.content.Intent().setClassName(p, c).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                    return
                } catch (_: Exception) {
                }
            }
        }
        val intent = when {
            which == "battery" -> android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg)
            which == "exactAlarm" && Build.VERSION.SDK_INT >= 31 ->
                android.content.Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkg)
            else -> android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)
        }
        try {
            startActivity(intent)
        } catch (_: Exception) {
            startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg))
        }
    }

    companion object {
        private const val WATCHDOG_SEC = 60
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "news/native").setMethodCallHandler { call, result ->
            val app = applicationContext
            when (call.method) {
                "fetchNow" -> NewsWidget.net.execute {
                    val n = try {
                        FetchWorker.runFetch(app)
                    } catch (_: Exception) {
                        -1
                    }
                    runOnUiThread { result.success(n) }
                }
                "markRead" -> {
                    val ids = call.argument<List<String>>("ids").orEmpty()
                    NewsWidget.io.execute {
                        NewsRepo.markRead(app, ids)
                        NewsWidget.refreshAll(app)
                        runOnUiThread { result.success(null) }
                    }
                }
                "configChanged" -> NewsWidget.io.execute {
                    FetchWorker.schedule(app)
                    FetchAlarm.schedule(app)
                    NewsWidget.refreshAll(app)
                    runOnUiThread { result.success(null) }
                }
                "permissions" -> {
                    val am = getSystemService(android.app.AlarmManager::class.java)
                    result.success(
                        mapOf(
                            "exactAlarm" to (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()),
                            "battery" to getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(packageName),
                        ),
                    )
                }
                "openPermission" -> {
                    openPermission(call.argument<String>("which").orEmpty())
                    result.success(null)
                }
                "keepAliveStatus" -> Thread {
                    val s = KeepAlive.status(app)
                    runOnUiThread { result.success(s) }
                }.start()
                "shizukuRequest" -> {
                    KeepAlive.requestPermission()
                    result.success(null)
                }
                "keepAliveStart" -> Thread {
                    val ok = KeepAlive.start(app, WATCHDOG_SEC)
                    runOnUiThread { result.success(ok) }
                }.start()
                "keepAliveStop" -> Thread {
                    val ok = KeepAlive.stop(app)
                    runOnUiThread { result.success(ok) }
                }.start()
                "pinWidget" -> {
                    val mgr = AppWidgetManager.getInstance(this)
                    result.success(
                        mgr.isRequestPinAppWidgetSupported &&
                            mgr.requestPinAppWidget(ComponentName(this, NewsWidget::class.java), null, null),
                    )
                }
                else -> result.notImplemented()
            }
        }
    }
}
