package com.dsoft.screengrab.hook

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * LSPosed entry point (named in assets/xposed_init). Runs once per process of each app the module is
 * scoped to. In every scoped app it strips FLAG_SECURE so ScreenGrab's MediaProjection can capture it
 * (e.g. Telegram, Udemy). In our own app it just flips the "module active" marker so the UI can tell.
 */
class HookEntry : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        val pkg = lpparam.packageName ?: return
        if (pkg == SELF) {
            markModuleActive(lpparam.classLoader)
            return
        }
        try {
            FlagSecureHook.install()
        } catch (t: Throwable) {
            XposedBridge.log("ScreenGrab: không gắn được hook cho $pkg: $t")
        }
    }

    /** Make com.dsoft.screengrab.XposedStatus.moduleActive() return true inside our own process. */
    private fun markModuleActive(cl: ClassLoader) {
        val c = XposedHelpers.findClassIfExists("com.dsoft.screengrab.XposedStatus", cl) ?: return
        XposedBridge.hookAllMethods(c, "moduleActive", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) { param.setResult(true) }
        })
    }

    private companion object {
        const val SELF = "com.dsoft.screengrab"
    }
}
