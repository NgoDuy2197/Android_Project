package com.dsoft.toolbox

import android.accessibilityservice.AccessibilityService
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

/**
 * Optional. When enabled in Accessibility settings, the dim layer is drawn as
 * a trusted accessibility overlay, which may be darker than 80% while
 * touches still pass through to the apps below.
 */
class DimAccessibility : AccessibilityService() {
    private val overlay by lazy { DimOverlay(this, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY) }

    override fun onServiceConnected() {
        instance = this
        DimService.refresh(this)
    }

    fun show(alpha: Float) = overlay.show(alpha)
    fun hide() = overlay.hide()

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        overlay.hide()
        instance = null
        DimService.refresh(this) // fall back to the normal overlay
        super.onDestroy()
    }

    companion object {
        @Volatile var instance: DimAccessibility? = null
    }
}
