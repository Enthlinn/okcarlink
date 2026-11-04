package com.okcar.link.port;

/**
 * Privileged display-size sink. The OkCarOS build reaches
 * {@code IWindowManager.setForcedDisplaySize} through WindowManagerGlobal
 * reflection (needs WRITE_SECURE_SETTINGS + privileged identity); a normal-phone
 * build installs a root {@code wm size} bridge here (plan §1).
 */
public interface DisplaySizeBackend {

    /**
     * Forces a display to the given size in its natural orientation.
     *
     * @param displayId 0 for the default display
     * @return true when the override is in place
     */
    boolean setForcedDisplaySize(int displayId, int width, int height);
}
