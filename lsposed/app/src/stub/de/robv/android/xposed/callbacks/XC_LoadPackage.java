package de.robv.android.xposed.callbacks;

/** Compile-time stub of the Xposed API (provided by LSPosed at runtime). */
public abstract class XC_LoadPackage extends XCallback {

    public XC_LoadPackage(CallbackHelper helper) { super(helper); }

    public static class LoadPackageParam extends XCallback.Param {
        public String packageName;
        public String processName;
        public ClassLoader classLoader;
        public boolean isFirstApplication;
    }
}
