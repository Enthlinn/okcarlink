package com.okcar.link.port;

import android.hardware.display.VirtualDisplay;
import android.view.Surface;

/**
 * Creates the virtual display the mirror screen is captured through.
 *
 * <p>OkCarOS runs as {@code android.uid.system} and builds it with
 * {@code DisplayManager.createVirtualDisplay(AUTO_MIRROR)}, which
 * DisplayManagerService gates behind the signature permission
 * {@code CAPTURE_VIDEO_OUTPUT}.  A port build holds no such permission, so it
 * installs a factory that goes through an active {@link android.media.projection.MediaProjection}
 * instead — the same consent the playback capture uses.  A null factory means
 * "use the built-in DisplayManager default", exactly like every other backend
 * in {@link Ports}.
 */
public interface MirrorDisplayFactory {

    /**
     * @param name    display name, as the DisplayManager path would pass it
     * @param width   capture width in pixels
     * @param height  capture height in pixels
     * @param density dpi of the capture surface
     * @param surface the encoder's input surface; the mirror is drawn into it
     * @return the created display, or null when the platform refused
     */
    VirtualDisplay createVirtualDisplay(String name, int width, int height, int density,
                                        Surface surface);

    /**
     * Size of the region the platform says it is capturing, or null while unknown.
     *
     * <p>Whole-display capture reports the panel; single-app capture reports the app window's own
     * size, which is what the mirror display has to be built at — otherwise the app is scaled or
     * letterboxed inside a display sized for the whole phone.  The value is only known once a
     * display exists (the platform reports it through the projection callback), so callers must
     * still have a fallback.
     */
    int[] capturedContentSize();

    /**
     * Called when the video source that created {@code display} goes away.
     *
     * <p>The default keeps the historical behaviour — release the display.  A factory that owns the
     * display as a <em>shared app surface</em> overrides this and keeps it: the display, its id, and
     * the app task living on it then survive a session restart instead of being torn down.
     *
     * <p>That is not cosmetic.  Releasing a virtual display that hosts an app makes
     * DisplayManagerService move the app's task to the default display (the phone's own screen), and
     * the next session then launches a <em>second</em> instance on the fresh display — measured on
     * 217 as "a new app window pops up on the phone's desktop" every time the AirPlay session was
     * rebuilt.
     */
    default void releaseFromSource(VirtualDisplay display) {
        if (display != null) {
            display.release();
        }
    }
}
