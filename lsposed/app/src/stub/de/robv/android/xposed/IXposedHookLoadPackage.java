package de.robv.android.xposed;

import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/** Compile-time stub of the Xposed API (provided by LSPosed at runtime). */
public interface IXposedHookLoadPackage {
    void handleLoadPackage(LoadPackageParam lpparam) throws Throwable;
}
