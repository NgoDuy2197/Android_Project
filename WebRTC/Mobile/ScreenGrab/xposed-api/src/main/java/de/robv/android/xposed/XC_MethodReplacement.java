package de.robv.android.xposed;

/** Stub of {@code de.robv.android.xposed.XC_MethodReplacement}. */
public abstract class XC_MethodReplacement extends XC_MethodHook {
    public XC_MethodReplacement() { super(); }

    public XC_MethodReplacement(int priority) { super(priority); }

    protected abstract Object replaceHookedMethod(MethodHookParam param) throws Throwable;
}
