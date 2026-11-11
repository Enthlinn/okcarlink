package de.robv.android.xposed.callbacks;

/** Compile-time stub of the Xposed API (provided by LSPosed at runtime). */
public abstract class XCallback {
    public final CallbackHelper helper;

    public XCallback(CallbackHelper helper) { this.helper = helper; }

    public static class Param {
        public Object[] args;
    }

    public static class CallbackHelper {
        public CallbackHelper() {}
    }
}
