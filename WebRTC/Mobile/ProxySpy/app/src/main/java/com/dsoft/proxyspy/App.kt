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
            val ch = NotificationChannel(CH, "ProxySpy", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    companion object {
        const val CH = "proxyspy"
        lateinit var instance: App
        lateinit var main: Handler
    }
}
