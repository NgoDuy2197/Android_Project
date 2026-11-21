package com.dsoft.proxyspy

/**
 * Self-check for "is the module actually enabled in LSPosed?".
 *
 * When LSPosed loads this module into ProxySpy's own process it replaces the result of
 * [moduleActive] with true. If the module is installed but not ticked in LSPosed, nothing is
 * replaced and the UI can say so instead of leaving the user guessing.
 */
object XposedStatus {
    @JvmStatic
    fun moduleActive(): Boolean = false
}
