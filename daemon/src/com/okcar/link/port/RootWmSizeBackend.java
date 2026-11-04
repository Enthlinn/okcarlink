package com.okcar.link.port;

/**
 * Normal-phone {@link DisplaySizeBackend}: the hidden IWindowManager RPC is
 * unavailable to a plain app, but a root shell can run the very command the
 * framework CLI uses. Synchronous and small: sessions call it at most twice
 * (apply / restore).
 */
public final class RootWmSizeBackend implements DisplaySizeBackend {

    @Override
    public boolean setForcedDisplaySize(int displayId, int width, int height) {
        try {
            final Process process = Runtime.getRuntime().exec(new String[]{
                    "su", "-c", "wm size " + width + "x" + height});
            final int code = process.waitFor();
            return code == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
