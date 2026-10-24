package com.dsoft.dailydiary

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        DiaryStore.init(this)
        Jobs.scheduleDaily(this, reset = false)
    }
}
