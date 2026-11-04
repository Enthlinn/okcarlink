package com.okcar.link.port;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;

/**
 * Wires the normal-phone backends into {@link Ports} at startup. Call
 * {@link #installDefaults} before any session starts; on a rooted phone
 * (KernelSU) the four privileged capabilities route to the root helpers,
 * otherwise every default stays on the OkCarOS implementations.
 *
 * <p>{@link #installProjection} runs after the user grants the MediaProjection
 * consent dialog — only then does playback capture become available.
 */
public final class PortBootstrap {

    private static final String TAG = "okcar-port";

    private PortBootstrap() {
    }

    /** @return true when a root shell answers (KernelSU / su) */
    public static boolean hasRoot() {
        try {
            final Process process = Runtime.getRuntime().exec(new String[]{"su", "-c", "id"});
            final BufferedReader reader =
                    new BufferedReader(new InputStreamReader(process.getInputStream()));
            final String line = reader.readLine();
            process.waitFor();
            return line != null && line.contains("uid=0");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Installs the root-backed backends when available.
     *
     * <p>The uinput device's ABS range is the panel's <em>unrotated</em> size, never the logical
     * one: the input stack rotates a touchscreen's raw range into the display's current space by
     * itself, so a range that already follows the rotation gets that rotation applied twice.  The
     * caller's {@code displayWidth/displayHeight} are therefore only a fallback for when the panel
     * size cannot be read; {@code InputInjector} keeps scaling the car's coordinates into the
     * logical space separately, and {@link com.okcar.link.port.UinputTouchBackend} undoes the
     * rotation on the way in.
     */
    public static void installDefaults(Context context, int displayWidth, int displayHeight) {
        sAppContext = context == null ? null : context.getApplicationContext();
        if (!hasRoot()) {
            /* The module is a KernelSU module: every privileged backend below needs uid 0, and
             * there is deliberately no unprivileged fallback any more.  Fail loudly. */
            Log.e(TAG, "no root — the okcar-port backends cannot be installed");
            return;
        }
        /* The rotation source first: the panel size below is derived from the logical size and the
         * rotation, so it has to be readable before the device range is chosen. */
        Ports.setRotationSource(new PanelRotation(context));
        final int[] panel = context == null ? null : physicalDisplaySize(context);
        final int rawWidth = panel != null ? panel[0] : displayWidth;
        final int rawHeight = panel != null ? panel[1] : displayHeight;
        Ports.setEventInjectBackend(new UinputTouchBackend(
                locate("okcar-uinputd"), rawWidth, rawHeight));
        Ports.setDisplaySizeBackend(new RootWmSizeBackend());
        clearLeftoverDisplayOverride();
        final WifiManager wifi = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        if (wifi != null) {
            Ports.setWifiOps(new ShellWifiOps(wifi));
        }
        Log.i(TAG, "root backends installed: uinput=" + locate("okcar-uinputd")
                + " panel=" + rawWidth + 'x' + rawHeight + " (logical was "
                + displayWidth + 'x' + displayHeight + ')');
    }

    /**
     * Reads the panel's current rotation on every call.
     *
     * <p>Deliberately not cached: the phone turns landscape as soon as a video plays, and a backend
     * that keeps using the rotation it saw at install time is the stale-space bug this fixes.
     */
    private static final class PanelRotation implements Ports.RotationSource {
        private final Context mContext;

        PanelRotation(Context context) {
            mContext = context;
        }

        @Override
        public int rotation() {
            try {
                final android.view.WindowManager windowManager = (android.view.WindowManager)
                        mContext.getSystemService(Context.WINDOW_SERVICE);
                return windowManager == null ? 0
                        : windowManager.getDefaultDisplay().getRotation();
            } catch (RuntimeException e) {
                return 0;
            }
        }
    }

    /** The application context handed to {@link #installDefaults}, for late refreshes. */
    private static volatile Context sAppContext;

    /**
     * (Re)installs the uinput touch backend for the display size as it is right now.  The uinput
    /**
     * (Re)installs the uinput touch backend.  The ABS range is the panel's unrotated size, which a
     * rotation does not change, so this is only needed when the size itself changes; the rotation
     * is read live at every injection instead (see {@link Ports#displayRotation()}).
     */
    public static void refreshTouchBackend() {
        final Context context = sAppContext;
        if (context == null || !hasRoot()) {
            return;
        }
        final int[] size = physicalDisplaySize(context);
        Ports.setEventInjectBackend(new UinputTouchBackend(locate("okcar-uinputd"),
                size[0], size[1]));
        Log.i(TAG, "uinput touch backend (re)installed for panel " + size[0] + 'x' + size[1]);
    }

    private static volatile RootAppDisplayFactory sRootAppDisplay;

    /**
     * Installs the root capture path: our own public virtual display hosting {@code appPackage}.
     *
     * <p>This is what the module daemon runs (no APK, uid 0).  It replaces the MediaProjection
     * mirror outright — no consent dialog, no window in which the grant has to be used before the
     * platform revokes it, and, the point of the exercise, the shared app lives on <em>our</em>
     * display instead of on the phone's screen: the receiver's touch pixels are that display's
     * pixels, and the phone stays usable and un-rotated.  A null app package still builds the
     * display and places nothing on it.
     *
     * <p>Playback capture deliberately keeps the built-in REMOTE_SUBMIX default here: uid 0 holds
     * {@code CAPTURE_AUDIO_OUTPUT}, so audio needs no projection either.
     *
     * @param densityDpi 0 lets the factory derive the density from the panel height
     * @return the installed factory (also reachable through {@link #rootAppDisplay()})
     */
    public static RootAppDisplayFactory installRootCapture(Context context, String appPackage,
                                                           int densityDpi) {
        final RootAppDisplayFactory factory = new RootAppDisplayFactory(context, appPackage);
        factory.setRequestedDensity(densityDpi);
        sRootAppDisplay = factory;
        Log.i(TAG, "root app display installed (app=" + appPackage + ", density=" + densityDpi
                + "); playback capture stays on the REMOTE_SUBMIX default");
        return factory;
    }

    /** @return the root app display installed by {@link #installRootCapture}, or null */
    public static RootAppDisplayFactory rootAppDisplay() {
        return sRootAppDisplay;
    }

    /**
     * The one display factory: the root capture installed at daemon start.  Null when the caller
     * is not the root daemon, which is a precondition failure rather than a fallback.
     */
    public static MirrorDisplayFactory mirrorDisplayFactory() {
        return sRootAppDisplay;
    }

    /**
     * The rotation-aware display size — <em>not</em> the physical one.  What the car's touchspace
     * describes is the rotated picture we send, and the car itself declares a landscape surface.
     */
    private static int[] logicalDisplaySize(Context context) {
        try {
            final android.view.WindowManager windowManager = (android.view.WindowManager)
                    context.getSystemService(Context.WINDOW_SERVICE);
            if (windowManager != null) {
                final android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
                windowManager.getDefaultDisplay().getMetrics(metrics);
                if (metrics.widthPixels > 0 && metrics.heightPixels > 0) {
                    return new int[]{metrics.widthPixels, metrics.heightPixels};
                }
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "cannot read the display size: " + e);
        }
        return new int[]{1080, 1920};
    }

    /**
     * The panel's unrotated size — <em>not</em> the rotation-aware one.
     *
     * <p>This is the range {@code okcar-uinput-touch} must declare: the input stack already rotates
     * a touchscreen's raw range into whatever the display's current rotation is, exactly as it does
     * for the phone's own panel.  A device created with the rotated size instead gets that rotation
     * applied on top of a space that had already been rotated, so car taps land reflected across the
     * panel — measured 2026-10-02 in mirror mode.
     *
     * <p>Derived from the logical size and the current rotation rather than from
     * {@code getRealMetrics}, which on this ROM follows the rotation: with the phone landscape it
     * answered {@code 2400x1080} while {@code wm size} reported the physical {@code 1080x2400}.
     * Swapping the axes for the odd rotations gives the same answer either way, and a rotation the
     * display reports as 0 (portrait) needs no swap.
     */
    private static int[] physicalDisplaySize(Context context) {
        final int[] logical = logicalDisplaySize(context);
        final int rotation = Ports.displayRotation();
        if (rotation == 1 || rotation == 3) {
            return new int[]{logical[1], logical[0]};
        }
        return logical;
    }


    /**
     * Clears a display-size override that outlived the session which set it.
     *
     * <p>A wireless session forces the display to the accessory's size and restores it when it
     * stops.  If the process dies mid-session that restore never runs and the override survives the
     * reboot — the panel is then squashed (1080x2182 on a 1080x2400 panel, measured 2026-09-30) and
     * <em>gesture navigation breaks</em>: the recents swipe-up region no longer exists.  Startup is
     * by definition outside a session, so anything still forced here is a leftover.
     */
    private static void clearLeftoverDisplayOverride() {
        try {
            final String forced = runSu("settings get global display_size_forced");
            if (forced == null || forced.isEmpty() || "null".equals(forced)) {
                return;
            }
            Log.w(TAG, "clearing a leftover display override (" + forced + ")");
            runSu("wm size reset");
        } catch (Throwable t) {
            Log.w(TAG, "cannot clear the display override: " + t);
        }
    }

    /** The Apple OUI the accessory-side device selection keys on (the port design notes). */
    private static final String APPLE_OUI = "f0:18:98";

    /**
     * Whether starting a session may touch Bluetooth on its own.  Default <b>off</b>: writing
     * {@code bt.mac} from the KernelSU overlay was measured to be ineffective as an automatic
     * step (nv_mac rebuilds it from QMI NV during Bluetooth init and MIUI re-syncs the adapter
     * name at every BT init), so every Bluetooth-modifying capability is an explicit switch on
     * {@code okcar-port-ctl} / the module WebUI.  Turning this on restores the old self-healing
     * behaviour for field use; it costs a radio/process restart when the OUI drifted.
     */
    private static volatile boolean sAutoBtIdentity;

    public static void setAutoBtIdentity(boolean enabled) {
        sAutoBtIdentity = enabled;
        Log.i(TAG, "automatic BT identity repair " + (enabled ? "enabled" : "disabled"));
    }

    public static boolean autoBtIdentity() {
        return sAutoBtIdentity;
    }

    /**
     * Makes sure the controller's own MAC carries the Apple OUI, repairing it when it does not.
     *
     * <p>{@code bt.mac} is rebuilt from QMI NV at every Bluetooth init, so an OUI written once can
     * silently disappear — and the failure is invisible until an accessory refuses to select this
     * phone (measured 2026-09-30: after a few radio restarts the adapter came back as
     * {@code 24:D3:37:FB:68:1B} while the pinned device key still said
     * {@code F0:18:98:FB:68:1B}).  The repair is ordered cheap → disruptive: rewrite the persisted
     * bytes first ({@code oui}, no radio change), and cycle the radio only when the live address
     * still disagrees ({@code spoof-bt}).  Call it before the RFCOMM listener is published, so a
     * radio cycle can never break a listener that is already serving.
     *
     * @return the live address after the attempt, empty when it cannot be read
     */
    public static String assertAccessoryBtIdentity() {
        if (!sAutoBtIdentity) {
            /* Not a failure: the operator simply has not switched this on.  The manual switches
             * (okcar-port-ctl oui | spoof-bt | bt-did | bt-name) are the supported way. */
            Log.i(TAG, "automatic BT identity repair is off — leaving Bluetooth untouched");
            return "";
        }
        if (!hasRoot()) {
            return "";
        }
        final String ctl = locate("okcar-port-ctl");
        if (!new File(ctl).exists()) {
            Log.i(TAG, "no okcar-port-ctl at " + ctl
                    + " — cannot assert the accessory-facing BT identity");
            return "";
        }
        String address = readBluetoothAddress();
        if (address.toLowerCase().startsWith(APPLE_OUI)) {
            Log.i(TAG, "BT identity ok: " + address);
            return address;
        }
        /* the integrator/the production head unit selects the phone by the SDP Device ID record (Apple = 76 = 0x4C) rather
         * than by the MAC OUI, and the ROM ships 0x001D.  A plain setprop is enough and needs no
         * mount; do it before the radio is restarted so the stack builds its DID record with it. */
        runSu("setprop bluetooth.device_id.vendor_id 0x004C");
        Log.i(TAG, "DID vendor after assert: " + runSu("getprop bluetooth.device_id.vendor_id"));
        Log.w(TAG, "BT OUI is not Apple (" + address + ") — rewriting bt.mac");
        runSu(ctl + " oui");
        address = readBluetoothAddress();
        if (!address.toLowerCase().startsWith(APPLE_OUI)) {
            Log.w(TAG, "still " + address + " — cycling Bluetooth so the HAL re-reads bt.mac");
            runSu(ctl + " spoof-bt");
            address = readBluetoothAddress();
        }
        Log.i(TAG, "BT identity after repair: " + address);
        return address;
    }

    /** The controller address as the framework records it (real value, not the masked one). */
    private static String readBluetoothAddress() {
        return runSu("settings get secure bluetooth_address");
    }

    private static String runSu(String command) {
        try {
            final Process process = Runtime.getRuntime().exec(new String[]{"su", "-c", command});
            final BufferedReader reader =
                    new BufferedReader(new InputStreamReader(process.getInputStream()));
            final String line = reader.readLine();
            process.waitFor();
            return line == null ? "" : line.trim();
        } catch (Exception e) {
            Log.w(TAG, "su -c \"" + command + "\" failed: " + e);
            return "";
        }
    }

    /**
     * Finds one of the module's own binaries.
     *
     * <p>The candidate list used to be {@code /system/bin/<name>} plus the *full* module's path
     * ({@code /data/adb/modules/okcar-port/…}), so an {@code okcar-port-lite} install could never
     * find {@code okcar-uinputd}: {@code /system/bin} is only populated when the root manager
     * actually overlays the module, and the lite module lives under a different id.  Every tap then
     * went into a dead pipe while the backend still reported success (measured 2026-10-03).
     *
     * <p>The daemon's own class path is the dex inside its module ({@code <module>/payload/
     * okcar-link.dex}), which identifies the module directory exactly, whatever its id is.
     */
    private static String locate(String name) {
        final java.util.ArrayList<String> candidates = new java.util.ArrayList<>();
        final String moduleDir = ownModuleDir();
        if (moduleDir != null) {
            candidates.add(moduleDir + "/system/bin/" + name);
        }
        candidates.add("/system/bin/" + name);
        candidates.add("/data/local/tmp/" + name);
        for (String id : new String[]{"okcar-port", "okcar-port-lite", "okcarlink"}) {
            candidates.add("/data/adb/modules/" + id + "/system/bin/" + name);
            candidates.add("/data/adb/modules_update/" + id + "/system/bin/" + name);
        }
        for (String path : candidates) {
            if (new File(path).exists()) {
                return path;
            }
        }
        return candidates.get(0);
    }

    /** {@code <module>} from the running class path, or null when it is not a module install. */
    private static String ownModuleDir() {
        try {
            final String cp = System.getProperty("java.class.path", "");
            final int at = cp.indexOf("/payload/");
            if (at > 0 && cp.startsWith("/data/adb/modules")) {
                return cp.substring(0, at);
            }
        } catch (Throwable ignored) {
            // fall through to the static candidates
        }
        return null;
    }
}
