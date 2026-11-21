package de.robv.android.xposed;

import java.lang.reflect.Member;
import java.util.Set;

/** Stub of {@code de.robv.android.xposed.XposedBridge}. */
public final class XposedBridge {
    private XposedBridge() { }

    public static void log(String text) { }

    public static void log(Throwable t) { }

    public static XC_MethodHook.Unhook hookMethod(Member hookMethod, XC_MethodHook callback) {
        throw new UnsupportedOperationException("stub");
    }

    public static Set<XC_MethodHook.Unhook> hookAllMethods(Class<?> hookClass, String methodName,
                                                           XC_MethodHook callback) {
        throw new UnsupportedOperationException("stub");
    }

    public static Object invokeOriginalMethod(Member method, Object thisObject, Object[] args)
            throws Throwable {
        throw new UnsupportedOperationException("stub");
    }
}
