package de.robv.android.xposed.callbacks;

/** Stub of {@code de.robv.android.xposed.callbacks.XC_LoadPackage}. */
public abstract class XC_LoadPackage extends XCallback {
    public XC_LoadPackage() { super(); }

    public XC_LoadPackage(int priority) { super(priority); }

    /** The real class also carries {@code appInfo}; it is left out to keep this module Android-free. */
    public static class LoadPackageParam extends XCallback.Param {
        public String packageName;
        public String processName;
        public ClassLoader classLoader;
        public boolean isFirstApplication;
    }
}
