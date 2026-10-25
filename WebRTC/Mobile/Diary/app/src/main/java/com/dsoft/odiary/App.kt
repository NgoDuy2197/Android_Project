package com.dsoft.odiary

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.Looper
import androidx.emoji2.bundled.BundledEmojiCompatConfig
import androidx.emoji2.text.EmojiCompat

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        main = Handler(Looper.getMainLooper())
        // Offline, consistent color emoji (font embedded via emoji2-bundled); replaceAll overrides the device's ugly ones.
        runCatching { EmojiCompat.init(BundledEmojiCompatConfig(this).setReplaceAll(true)) }
        Accounts.init(this)
        Store.init(this)
        watchNetworkForRetry()
    }

    /** When the network comes back, retry any pending Drive sync. */
    private fun watchNetworkForRetry() {
        runCatching {
            val cm = getSystemService(ConnectivityManager::class.java) ?: return
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    runCatching { main.post { AutoSync.maybeSync(this@App) } }
                }
            })
        }
    }

    companion object {
        lateinit var instance: App
        lateinit var main: Handler
    }
}
