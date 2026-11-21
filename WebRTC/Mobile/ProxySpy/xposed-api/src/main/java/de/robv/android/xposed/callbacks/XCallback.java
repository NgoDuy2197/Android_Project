package de.robv.android.xposed.callbacks;

/** Stub of {@code de.robv.android.xposed.callbacks.XCallback}. */
public abstract class XCallback implements Comparable<XCallback> {
    public static final int PRIORITY_DEFAULT = 50;
    public static final int PRIORITY_LOWEST = -10000;
    public static final int PRIORITY_HIGHEST = 10000;

    public final int priority;

    public XCallback() { this.priority = PRIORITY_DEFAULT; }

    public XCallback(int priority) { this.priority = priority; }

    @Override
    public int compareTo(XCallback other) { return 0; }

    public abstract static class Param {
        protected Param() { }
    }
}
