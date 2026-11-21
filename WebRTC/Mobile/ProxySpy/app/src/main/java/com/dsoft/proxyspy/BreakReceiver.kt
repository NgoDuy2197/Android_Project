package com.dsoft.proxyspy

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** "Cho đi tiếp" straight from the breakpoint notification, without opening the app. */
class BreakReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val askId = intent.getLongExtra("askId", -1)
        if (askId >= 0) Hub.answerGo(askId)
    }
}
