package de.robv.android.xposed;

import java.lang.reflect.Member;

import de.robv.android.xposed.callbacks.XCallback;

/**
 * Stub of {@code de.robv.android.xposed.XC_MethodHook}. Only the members this project actually
 * references are declared, and each is declared exactly where the real class declares it, so the
 * compiled descriptors resolve against LSPosed's implementation at runtime.
 */
public abstract class XC_MethodHook extends XCallback {
    public XC_MethodHook() { super(); }

    public XC_MethodHook(int priority) { super(priority); }

    protected void beforeHookedMethod(MethodHookParam param) throws Throwable { }

    protected void afterHookedMethod(MethodHookParam param) throws Throwable { }

    public static final class MethodHookParam extends XCallback.Param {
        public Member method;
        public Object thisObject;
        public Object[] args;

        public Object getResult() { throw new UnsupportedOperationException("stub"); }

        public void setResult(Object result) { throw new UnsupportedOperationException("stub"); }

        public Throwable getThrowable() { throw new UnsupportedOperationException("stub"); }

        public boolean hasThrowable() { throw new UnsupportedOperationException("stub"); }

        public void setThrowable(Throwable throwable) { throw new UnsupportedOperationException("stub"); }

        public Object getResultOrThrowable() throws Throwable { throw new UnsupportedOperationException("stub"); }
    }

    public class Unhook implements IXposedMod {
        public Member getHookedMethod() { throw new UnsupportedOperationException("stub"); }

        public XC_MethodHook getCallback() { throw new UnsupportedOperationException("stub"); }

        public void unhook() { throw new UnsupportedOperationException("stub"); }
    }
}
