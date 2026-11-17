package com.dsoft.remotelink.input

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import com.dsoft.remotelink.Log2

/**
 * The AccessibilityService that actually performs gestures/global actions. It keeps a static
 * reference to the running instance so [AccessibilityInjector] can reach it. The user grants it
 * once in Settings > Accessibility; it does nothing until the server dispatches input.
 */
class RemoteAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        instance = this
        Log2.i("AccessibilityService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* we don't observe, only inject */ }
    override fun onInterrupt() {}

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        @Volatile var instance: RemoteAccessibilityService? = null
            private set
    }
}
