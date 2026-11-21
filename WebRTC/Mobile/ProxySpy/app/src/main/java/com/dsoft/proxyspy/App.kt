package com.dsoft.proxyspy

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.os.Handler
import android.os.Looper

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        main = Handler(Looper.getMainLooper())
        CertManager.init(this)
        RuleStore.init(this)
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CH, "ProxySpy", NotificationManager.IMPORTANCE_LOW)
            )
            // Breakpoints must interrupt: the watched app is frozen until the user answers.
            nm.createNotificationChannel(
                NotificationChannel(CH_BREAK, "Breakpoint", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Một call đang bị dừng, chờ bạn sửa"
                }
            )
        }
    }

    companion object {
        const val CH = "proxyspy"
        const val CH_BREAK = "proxyspy_break"
        lateinit var instance: App
        lateinit var main: Handler
    }
}
