/*
 * OkcarOS link stack — JNI bridge.
 *
 * The native side carries the code that needs to sit close to the kernel: the
 * /dev/okcar role gate and, later, the iAP2 bulk-endpoint transport.  Keep the
 * Java-visible surface tiny and honest — every method must degrade gracefully
 * when the shared library is missing.
 */

package com.okcar.link;

import android.util.Log;

public final class NativeBridge {

    private static final String TAG = "OkCarLink/Native";

    private static volatile boolean sLoaded;
    private static volatile String sLoadError = "";

    private NativeBridge() {
    }

    public static synchronized boolean ensureLoaded() {
        if (sLoaded) {
            return true;
        }
        try {
            System.loadLibrary("okcarlink_jni");
            sLoaded = true;
        } catch (UnsatisfiedLinkError e) {
            sLoadError = String.valueOf(e);
            Log.w(TAG, "libokcarlink_jni unavailable: " + e);
        }
        return sLoaded;
    }

    public static boolean isAvailable() {
        return ensureLoaded();
    }

    public static String loadError() {
        ensureLoaded();
        return sLoadError;
    }

    public static String version() {
        if (!ensureLoaded()) {
            return "jni-not-loaded";
        }
        try {
            return nativeVersion();
        } catch (RuntimeException e) {
            return "jni-error: " + e;
        }
    }

    /**
     * iAP2 checksum (two's complement of the 8-bit sum) computed natively.
     * Used by the self test to prove the Java codec and the native side agree.
     */
    public static int checksum(byte[] data, int offset, int length) {
        if (!ensureLoaded() || data == null) {
            return Integer.MIN_VALUE;
        }
        try {
            return nativeChecksum(data, offset, length);
        } catch (RuntimeException e) {
            return Integer.MIN_VALUE;
        }
    }

    private static native String nativeVersion();

    private static native int nativeChecksum(byte[] data, int offset, int length);
}
