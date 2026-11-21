package com.dsoft.proxyspy.hook

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * LSPosed entry point, named in `assets/xposed_init`. Runs once per process of every app LSPosed
 * has this module scoped to.
 *
 * Hooks are installed straight away rather than after the Hub answers, because an app may build
 * its HTTP client during `Application.onCreate` — earlier than any handshake could finish. While
 * no Hub is listening (or this app is not a selected target) every hook short-circuits on
 * `Bridge.active == false`, which costs one volatile read per call.
 */
class HookEntry : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        val pkg = lpparam.packageName ?: return

        if (pkg == SELF) {
            markModuleActive(lpparam.classLoader)
            return
        }
        if (pkg in SKIP) return

        try {
            Bridge.start(pkg)
            val cl = lpparam.classLoader
            val ok = OkHttpHook.install(cl)
            val ws = if (ok) WsHook.install(cl) else false
            val raw = RawHook.install(cl)
            Bridge.log("gắn hook: okhttp=$ok ws=$ws raw=$raw")
        } catch (t: Throwable) {
            XposedBridge.log("ProxySpy: không gắn được hook cho $pkg: $t")
        }
    }

    /** Lets the ProxySpy UI tell "module enabled in LSPosed" from "module installed but off". */
    private fun markModuleActive(cl: ClassLoader) {
        val c = XposedHelpers.findClassIfExists("com.dsoft.proxyspy.XposedStatus", cl) ?: return
        XposedBridge.hookAllMethods(c, "moduleActive", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                param.setResult(true)
            }
        })
    }

    private companion object {
        const val SELF = "com.dsoft.proxyspy"
        val SKIP = setOf("android", "system", "com.android.systemui", "com.android.phone")
    }
}
