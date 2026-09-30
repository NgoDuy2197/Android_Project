package com.dsoft.volteguard

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import kotlinx.coroutines.launch
import org.lsposed.hiddenapibypass.HiddenApiBypass

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        // Allow reflection on ITelephony / ICarrierConfigLoader (non-SDK interfaces).
        runCatching { HiddenApiBypass.addHiddenApiExemptions("") }
        LogStore.init(this)
        ShizukuShell.init()
        // Shizuku started after boot (or restarted): bring the service + shell watchdog back.
        rikka.shizuku.Shizuku.addBinderReceivedListenerSticky {
            if (Prefs(this).enabled && ShizukuShell.hasPermission()) {
                runCatching { GuardService.start(this, GuardService.ACTION_KICK) }
                Engine.scope.launch { LogStore.i("Shizuku sẵn sàng → watchdog: ${Watchdog.start(this@App)}") }
            }
        }

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
