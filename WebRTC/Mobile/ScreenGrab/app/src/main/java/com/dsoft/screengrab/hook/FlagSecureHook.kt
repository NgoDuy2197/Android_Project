package com.dsoft.screengrab.hook

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * Removes the FLAG_SECURE window flag (and SurfaceView.setSecure) inside a hooked app, so screenshots
 * and MediaProjection recording are no longer blacked out. The classic "DisableFlagSecure" technique.
 *
 * This only affects apps the user has scoped the module to in LSPosed. Hardware DRM (e.g. Widevine L1
 * video) is enforced below the view layer and is NOT defeated by this.
 */
object FlagSecureHook {
    private const val FLAG_SECURE = 0x00002000   // WindowManager.LayoutParams.FLAG_SECURE

    fun install() {
        // Window.setFlags(int flags, int mask) → clear FLAG_SECURE from both
        XposedHelpers.findAndHookMethod(
            "android.view.Window", null, "setFlags",
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val flags = param.args[0] as Int
                    val mask = param.args[1] as Int
                    param.args[0] = flags and FLAG_SECURE.inv()
                    param.args[1] = mask and FLAG_SECURE.inv()
                }
            }
        )

        // Window.addFlags(int) → clear FLAG_SECURE
        XposedHelpers.findAndHookMethod(
            "android.view.Window", null, "addFlags", Int::class.javaPrimitiveType,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.args[0] = (param.args[0] as Int) and FLAG_SECURE.inv()
                }
            }
        )

        // SurfaceView.setSecure(boolean) → always false
        runCatching {
            XposedHelpers.findAndHookMethod(
                "android.view.SurfaceView", null, "setSecure", Boolean::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) { param.args[0] = false }
                }
            )
        }

        XposedBridge.log("ScreenGrab: FLAG_SECURE hook đã gắn")
    }
}
