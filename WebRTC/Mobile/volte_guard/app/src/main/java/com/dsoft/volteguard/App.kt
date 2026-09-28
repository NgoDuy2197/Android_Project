package com.dsoft.volteguard

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import org.lsposed.hiddenapibypass.HiddenApiBypass

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        // Allow reflection on ITelephony / ICarrierConfigLoader (non-SDK interfaces).
        runCatching { HiddenApiBypass.addHiddenApiExemptions("") }
        LogStore.init(this)
        ShizukuShell.init()

        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_STATUS, "Trạng thái giám sát", NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_EVENTS, "Sự kiện mất / khôi phục HD", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    companion object {
        const val CH_STATUS = "status"
        const val CH_EVENTS = "events"
        lateinit var instance: App
            private set
    }
}
