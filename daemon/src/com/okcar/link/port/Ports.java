package com.okcar.link.port;

/**
 * Wiring point for the platform-dependent backends behind the privileged call
 * sites. OkCarOS keeps every default (hidden API / REMOTE_SUBMIX); a port build
 * installs replacements before the session starts — see
 * the port design notes §1.
 *
 * <p>Registry semantics: a null backend means "use the built-in OkCarOS
 * default", so installing nothing preserves legacy behaviour exactly and
 * {@code set*(null)} restores it.
 */
public final class Ports {

    private static volatile EventInjectBackend sEventInject;
    private static volatile DisplaySizeBackend sDisplaySize;
    private static volatile WifiOps sWifiOps;
    /**
     * Display the car's touches have to land on, and the pixel space they are in.
     *
     * <p>Mirroring needs nothing here (-1 = the default display, which is where the mirrored
     * picture comes from).  The root app-display architecture does: the shared app lives on our
     * own public display, so an injected event has to name that display or the dispatcher sends
     * it to the phone's own screen — the exact failure the architecture exists to remove.  Set by
     * {@link RootAppDisplayFactory} when it builds the display and read by
     * {@code com.okcar.link.input.InputInjector}.
     */
    private static volatile int sTouchDisplayId = -1;
    private static volatile int sTouchDisplayWidth;
    private static volatile int sTouchDisplayHeight;

    /**
     * Where the panel's current rotation comes from, for the backend that writes into a kernel
     * device whose native space does not rotate with the display.
     *
     * <p>Read live at every injection, never cached: the phone can turn landscape mid-session (a
     * playing video is enough), and a value captured when the session started is exactly the stale
     * space that made mirror-mode touches land far from where the car was tapped.
     */
    public interface RotationSource {
        /** @return the display's current {@code android.view.Surface.ROTATION_*} */
        int rotation();
    }

    private static volatile RotationSource sRotation;

    private Ports() {
    }

    public static void setRotationSource(RotationSource source) {
        sRotation = source;
    }

    /** @return the rotation of the display touches land on; 0 when no source is installed */
    public static int displayRotation() {
        final RotationSource source = sRotation;
        if (source == null) {
            return 0;
        }
        try {
            final int rotation = source.rotation();
            return rotation >= 0 && rotation <= 3 ? rotation : 0;
        } catch (RuntimeException e) {
            /* Reading the rotation must never break an injection. */
            return 0;
        }
    }

    /** @return the installed backend, or null for the built-in InputManager default */
    public static EventInjectBackend eventInjectBackend() {
        return sEventInject;
    }

    public static void setEventInjectBackend(EventInjectBackend backend) {
        final EventInjectBackend previous = sEventInject;
        sEventInject = backend;
        /* Replacing a backend must not leak its process: the uinput backend owns a root daemon
         * whose ABS range is fixed at creation, so a rotation-aware re-install replaces the whole
         * object and the old daemon has to go. */
        if (previous != null && previous != backend && previous instanceof java.io.Closeable) {
            try {
                ((java.io.Closeable) previous).close();
            } catch (java.io.IOException ignored) {
                /* closing anyway */
            }
        }
    }

    /** @return the installed backend, or null for the built-in reflection default */
    public static DisplaySizeBackend displaySizeBackend() {
        return sDisplaySize;
    }

    public static void setDisplaySizeBackend(DisplaySizeBackend backend) {
        sDisplaySize = backend;
    }

    /** @return the installed adapter, or null for the built-in WifiManager default */
    public static WifiOps wifiOps() {
        return sWifiOps;
    }

    public static void setWifiOps(WifiOps ops) {
        sWifiOps = ops;
    }

    /** @return the display the car's touches must target, or -1 for the default display */
    public static int touchDisplayId() {
        return sTouchDisplayId;
    }

    /** @return {width, height} of that display, or null when touches target the default one */
    public static int[] touchDisplaySize() {
        final int width = sTouchDisplayWidth;
        final int height = sTouchDisplayHeight;
        return sTouchDisplayId >= 0 && width > 0 && height > 0 ? new int[] {width, height} : null;
    }

    /** @param displayId -1 (or any negative) restores default-display targeting */
    public static void setTouchDisplay(int displayId, int width, int height) {
        sTouchDisplayId = displayId;
        sTouchDisplayWidth = width;
        sTouchDisplayHeight = height;
    }

    /** Restores every built-in default; used by tests and by session teardown. */
    public static void reset() {
        sEventInject = null;
        sDisplaySize = null;
        sWifiOps = null;
        sRotation = null;
        sTouchDisplayId = -1;
        sTouchDisplayWidth = 0;
        sTouchDisplayHeight = 0;
    }
}
