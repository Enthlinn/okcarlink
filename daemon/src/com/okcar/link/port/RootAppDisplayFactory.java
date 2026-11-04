package com.okcar.link.port;

import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.util.Log;
import android.view.Surface;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Root-side capture backend: <em>our own</em> public virtual display that hosts a shared app.
 *
 * <p>This is the architecture the module exists for.  The MediaProjection backends were
 * removed with the rest of the fallback stack: they could only mirror either the whole phone
 * screen or one app that is <em>rendered on the phone's own screen</em> — so the car's
 * touches landed on the phone and the picture followed the phone's rotation.  A root
 * {@code app_process} instead builds a {@code VIRTUAL_DISPLAY_FLAG_PUBLIC} display of the
 * size the head unit declared, puts the shared app on it, and encodes <em>that</em> display:
 * the phone screen stays the phone's, the car sees only the app, and the receiver's touch
 * pixels are exactly this display's pixels.
 *
 * <p>Measured on 217 with {@code the desktop display harness}: a root process does hold
 * {@code CAPTURE_VIDEO_OUTPUT} (the signature permission {@code DisplayManager} gates the
 * public flag behind), so the display itself always comes up (id 31/32, 1920x720, state=ON).
 * The hard part is getting a <em>third-party</em> app onto it, which MIUI blocks in ways that
 * depend on the caller, so {@link #placeApp()} tries three escalating routes and verifies
 * each one against {@code dumpsys activity activities} instead of trusting the start call.
 *
 * <p>Sizing is deliberately never a constant: the width/height come from the session's
 * resolved video size (the accessory's own {@code /info displays[0]}), so every session
 * builds the display for the head unit that is actually connected.
 *
 * <p>Input: the uinput touch device is left unassociated, which is what makes the car's
 * touches usable here — an unassociated device may dispatch to any display and follows the
 * focused one, and the display we build becomes focused once the app is on it.  (This tree
 * has no {@code InputManager.associateInputDeviceToDisplay}; the IDC association is by
 * display port/unique-id, neither of which a virtual display we create could match.)
 */
public final class RootAppDisplayFactory implements MirrorDisplayFactory {

    private static final String TAG = "okcar-port";
    /** Display name; also what `dumpsys display` and the module WebUI show. */
    public static final String DISPLAY_NAME = "OkCarLink-app";
    /** Logical height the car panel is mapped onto when no density was asked for. */
    private static final int REFERENCE_HEIGHT_DP = 720;
    private static final int MIN_DENSITY = 120;
    private static final int MAX_DENSITY = 480;

    private final Context mContext;
    private volatile String mAppPackage;
    /**
     * Activity that provides the car screen's UI, when it differs from the app's launch activity.
     *
     * <p>Measured need (Pano workbench, 2026-10-01): it is a Flutter app whose workbench runs in a
     * second activity while the Flutter "Main Engine" lives in the normal instance.  Started only on
     * the virtual display it logs {@code Unable to connect to the Main Engine} and never leaves its
     * splash screen — the car gets a blank picture.  With the normal instance running on the phone's
     * own display and this component on the virtual display, the engine connects and the workbench
     * renders.  Empty means "no separate component": the launch activity is placed as before.
     */
    private volatile String mAppUiComponent = "";
    /**
     * Whether the app's normal instance has to run on the phone's own display.
     *
     * <p>{@code true} keeps the measured Pano workaround above.  {@code false} is for operators who
     * want the car screen only: nothing of the app may be visible on the phone.  It works for apps
     * whose engine is a process of its own (Pano/NeoPlayer spawns {@code pano_server} in its data
     * dir, so the car-screen activity reaches it without a phone-side host) — measured on 217
     * 2026-10-02: with the engine up, {@code PanoExternalDisplayActivity} renders on the virtual
     * display while display 0 owns no window of the app.
     */
    private volatile boolean mHostOnPhone = true;
    /** The phone's own display: where an app's normal (host) instance has to live. */
    private static final int DEFAULT_DISPLAY = 0;
    /* ------------------------------------------------ display owned by the app (in-app module) */
    /**
     * Hand the display to the app's own process instead of owning it here.
     *
     * <p>Measured on 217 (2026-10-02): {@code SafeActivityOptions.checkPermissions} refuses a
     * {@code launchDisplayId} whose display is not owned by the caller, so an LSPosed module in the
     * app process cannot pin its launches to a display we created (uid 0) — it throws
     * RemoteException and the app crashes.  Owning the display from the app's uid makes that check
     * pass; the encoder Surface still comes from here, over a binder the module pulls from.
     */
    private volatile boolean mModuleOwnsDisplay;
    private volatile Surface mModuleSurface;
    private boolean mBrokerRegistered;
    /** Geometry handover back to the module (it owns the display and does the resizing). */
    private static final String GEOMETRY_FILE = "/data/local/tmp/okcar-vdisplay-geometry";
    private static final String BROKER_NAME = "okcar-display";

    public void setModuleOwnsDisplay(boolean on) {
        mModuleOwnsDisplay = on;
    }

    public boolean moduleOwnsDisplay() {
        return mModuleOwnsDisplay;
    }

    /** Geometry plus the encoder Surface, pulled by the in-app module (bare binder, no AIDL). */
    private final android.os.Binder mBroker = new android.os.Binder() {
        @Override
        public boolean onTransact(int code, android.os.Parcel data, android.os.Parcel reply, int flags)
                throws android.os.RemoteException {
            switch (code) {
                case 1:
                    data.enforceInterface(BROKER_NAME);
                    reply.writeNoException();
                    reply.writeInt(mWidth);
                    reply.writeInt(mHeight);
                    reply.writeInt(mDensity);
                    return true;
                case 2: {
                    data.enforceInterface(BROKER_NAME);
                    reply.writeNoException();
                    final Surface surface = mModuleSurface;
                    if (surface == null) {
                        reply.writeInt(0);
                    } else {
                        reply.writeInt(1);
                        surface.writeToParcel(reply, 0);
                    }
                    return true;
                }
                case 3: {
                    data.enforceInterface(BROKER_NAME);
                    final int id = data.readInt();
                    reply.writeNoException();
                    adoptModuleDisplay(id);
                    return true;
                }
                default:
                    return super.onTransact(code, data, reply, flags);
            }
        }
    };

    private void ensureBroker() {
        if (mBrokerRegistered) {
            return;
        }
        try {
            final Class<?> sm = Class.forName("android.os.ServiceManager");
            sm.getMethod("addService", String.class, android.os.IBinder.class)
                    .invoke(null, BROKER_NAME, mBroker);
            mBrokerRegistered = true;
            Log.i(TAG, "display broker registered as " + BROKER_NAME);
        } catch (Throwable t) {
            Log.w(TAG, "cannot register the display broker: " + t);
        }
    }

    /** The module created the display (owner = app uid); adopt its id for touch and for the pin. */
    private void adoptModuleDisplay(int id) {
        if (id <= 0) {
            return;
        }
        mDisplayId = id;
        Ports.setTouchDisplay(id, mWidth, mHeight);
        publishDisplayId(id);
        mPlacement = "app-owned display " + id;
        Log.i(TAG, "module-owned display adopted: " + mPlacement);
    }

    /** Publishes the geometry the module must build/resize its own display to ({@code -1} = none). */
    private static void publishGeometry(int width, int height, int density) {
        final String text = width > 0 && height > 0 ? width + " " + height + " " + density : "-1";
        java.io.FileOutputStream out = null;
        try {
            out = new java.io.FileOutputStream(GEOMETRY_FILE);
            out.write(text.getBytes("UTF-8"));
            out.flush();
        } catch (Throwable t) {
            Log.w(TAG, "cannot publish " + GEOMETRY_FILE + ": " + t);
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (Throwable ignored) {
                    /* best effort */
                }
            }
        }
    }

    /** How often the display keeper looks for a task that wandered off (see startDisplayKeeper). */
    private static final long KEEPER_INTERVAL_MS = 1500L;
    /**
     * Where the display id is published for out-of-process readers.
     *
     * <p>Written for anything that has to know which screen this session is on without talking to
     * the daemon: the LSPosed pin-display module used to read it on every activity start to force
     * {@code android.activity.launchDisplayId} (that module was removed 2026-10-02 — its injection
     * never fired for the app that mattered — so the file now only serves tools and forensics).
     * The daemon's status snapshot cannot be used for that, because it is only as fresh as the last
     * {@code status} command.  Written here on every create/resize/release, {@code -1} when there is
     * no display.
     */
    private static final String DISPLAY_ID_FILE = "/data/local/tmp/okcar-vdisplay-id";
    /**
     * One activity dump per placement attempt, reused by every check in that attempt.
     *
     * <p>Every {@code dumpsys} call takes ActivityManager/WindowManager locks, so the version that
     * polled six times per check — and one {@code dumpsys window windows} per poll — could stall
     * system_server long enough for MIUI's watchdog to reboot the phone (measured on 217: the phone
     * rebooted by itself right after a session connected, with the daemon polling placements every
     * time the session was rebuilt).
     */
    private volatile String[] mActivityDump;

    /** Which command actually yields a task stack (see {@link #activityDump()}). 0 unknown, 1 dumpsys, 2 am stack list. */
    private static final int SOURCE_UNKNOWN = 0;
    private static final int SOURCE_DUMPSYS = 1;
    private static final int SOURCE_AM = 2;
    private volatile int mActivitySource = SOURCE_UNKNOWN;
    /** Minimum gap between two successful placements, so session churn cannot re-poll in a loop. */
    private static final long PLACEMENT_MIN_GAP_MS = 15_000L;
    private volatile long mLastPlacedAtMs;
    private volatile int mLastPlacedDisplay = -1;
    /** Since when the target app has been "process alive, no task anywhere" — the starting-grace clock for the keeper
     * (see {@link KeeperPolicy#LIVE_NO_TASK_GRACE_MS}); 0 means the clock is off. */
    private volatile long mLiveNoTaskSinceMs;
    private volatile int mRequestedDensity;
    private volatile VirtualDisplay mDisplay;
    private volatile int mWidth;
    private volatile int mHeight;
    private volatile int mDensity;
    private volatile int mDisplayId = -1;
    /** True when the running display is the public app display (not the mirror fallback). */
    private volatile boolean mPublic;

    /** When true the pipeline mirrors the phone's screen instead of hosting our desktop. */
    private volatile boolean mMirrorMode;

    /**
     * Mirror the phone's own screen instead of hosting content on a display we own.
     *
     * <p>The two shapes are different display kinds (a mirror display is built with {@code
     * AUTO_MIRROR}; ours is {@code PUBLIC|PRESENTATION|OWN_CONTENT_ONLY}) and a display's flags are
     * fixed at creation, so this is a session-level switch rather than a live one.
     */
    public void setMirrorMode(boolean mirror) {
        mMirrorMode = mirror;
        /* Suppressing transition animations is not done here: app_process is not a registered app, so Settings.Global.putFloat is
         * rejected by SettingsProvider (measured: "Unable to find app for caller … pid=…"). That is left to the okcar-linkd
         * wrapper script using the `settings` command (see the script's animations_on/off). */
    }

    public boolean mirrorMode() {
        return mMirrorMode;
    }
    private volatile String mPlacement = "not attempted";

    public RootAppDisplayFactory(Context context, String appPackage) {
        mContext = context;
        mAppPackage = appPackage == null ? "" : appPackage.trim();
        /* A daemon that has just started has no display yet.  Publishing -1 clears whatever the
         * previous daemon lifetime left behind, so a crash cannot leave the pin module pointing the
         * app's launches at a display id that died with the process that owned it. */
        publishDisplayId(-1);
    }

    /* ------------------------------------------------------------------ config */

    public String appPackage() {
        return mAppPackage;
    }

    /** Replaces the shared app; when a public display already exists the new app is placed too. */
    public void setAppPackage(String appPackage) {
        final String previous = mAppPackage;
        mAppPackage = appPackage == null ? "" : appPackage.trim();
        if (mDisplayId < 0) {
            return;
        }
        if (!mPublic) {
            /* The running display mirrors the phone (no app was configured when it was built);
             * a display's flags are fixed at creation, so the app takes effect next session. */
            Log.i(TAG, "shared app set to '" + mAppPackage
                    + "'; the running session keeps mirroring — takes effect on the next one");
            return;
        }
        /* Drive the old app off the display.  Without this the previous shared app keeps its window
         * (it is simply no longer placed), so the car would show whatever was last on top while the
         * new package is brought up underneath — measured when switching Pudding -> Pano workbench. */
        if (!previous.isEmpty() && !previous.equals(mAppPackage)) {
            mPlacement = "stopping the old app " + previous;
            Log.i(TAG, "shared app changed " + previous + " -> " + mAppPackage
                    + ": stopping the old one");
            run(new String[]{"/system/bin/am", "force-stop", previous}, "am force-stop " + previous);
        }
        placeApp();
    }

    /** @param densityDpi 0 = derive from the panel height (see {@link #chooseDensity}) */
    public void setRequestedDensity(int densityDpi) {
        mRequestedDensity = densityDpi;
    }

    /**
     * Sets the activity that draws the car screen's UI; empty or "-" clears it.
     *
     * <p>See {@link #mAppUiComponent} for why this exists.  When a public display already exists the
     * new component is placed immediately, so switching it from the WebUI takes effect without a
     * session restart.
     */
    public void setAppUiComponent(String component) {
        final String value = component == null ? "" : component.trim();
        mAppUiComponent = "-".equals(value) ? "" : value;
        if (mDisplayId >= 0 && mPublic) {
            placeApp();
        }
    }

    /** See {@link #mHostOnPhone}: false = never put the app's normal instance on the phone. */
    public void setHostOnPhone(boolean on) {
        mHostOnPhone = on;
    }

    public boolean hostOnPhone() {
        return mHostOnPhone;
    }

    public String appUiComponent() {
        return mAppUiComponent;
    }

    public int displayId() {
        return mDisplayId;
    }

    public int width() {
        return mWidth;
    }

    public int height() {
        return mHeight;
    }

    public int density() {
        return mDensity;
    }

    public String placement() {
        return mPlacement;
    }

    /** One line for `okcar-linkd status` and the WebUI. */
    public String describe() {
        /* The touch target is the one fact that decides whether a car tap can reach the app at
         * all, and it is registered on one path (create/reuse) while another path clears it
         * (release → -1).  When those disagree the symptom is exactly "it reconnected and now
         * nothing on the car screen reacts, then another reconnect fixed it", so the target is
         * reported next to the display the app is on and a disagreement is called out. */
        final int touch = Ports.touchDisplayId();
        final String touchText = " touch=" + touch
                + (mDisplayId >= 0 && touch != mDisplayId ? " (MISMATCH)" : "");
        if (mDisplayId < 0) {
            return "app=" + ((mAppPackage == null || mAppPackage.isEmpty()) ? "-" : mAppPackage)
                    + uiSuffix() + " display=none placement=" + mPlacement + touchText;
        }
        return "app=" + ((mAppPackage == null || mAppPackage.isEmpty()) ? "-" : mAppPackage)
                + uiSuffix() + " display=" + mDisplayId + ' ' + mWidth + 'x' + mHeight + " dpi=" + mDensity
                + " placement=" + mPlacement + touchText;
    }

    private String uiSuffix() {
        final String ui = (mAppUiComponent == null || mAppUiComponent.isEmpty()) ? "" : " ui=" + mAppUiComponent;
        return ui + (mHostOnPhone ? "" : " host=off");
    }

    /* ----------------------------------------------------- MirrorDisplayFactory */

    @Override
    public int[] capturedContentSize() {
        /* App mode must NOT feed its own geometry back into the size resolver: this display is
         * built *from* the session's video size, so returning it would make every later session
         * inherit the first one's choice and the head unit's declaration could never win.  Mirror
         * mode is honest — that display really does hold the phone's panel. */
        if (mPublic) {
            return null;
        }
        final int width = mWidth;
        final int height = mHeight;
        return width > 0 && height > 0 ? new int[] {width, height} : null;
    }

    /** The phone panel's current native size (varies with rotation); null if unreadable, in which case the caller keeps the current value. */
    private static int[] panelSize(Context context) {
        try {
            final DisplayManager manager =
                    (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
            if (manager == null) {
                return null;
            }
            final android.view.Display display =
                    manager.getDisplay(android.view.Display.DEFAULT_DISPLAY);
            if (display == null) {
                return null;
            }
            final android.graphics.Point size = new android.graphics.Point();
            display.getRealSize(size);
            return size.x > 0 && size.y > 0 ? new int[]{size.x, size.y} : null;
        } catch (Throwable t) {
            Log.w(TAG, "cannot read the panel size: " + t);
            return null;
        }
    }

    /** **Off** by default: the mirror canvas takes the size declared by the head unit (see {@link #clampMirrorCanvas});
     *  {@code persist.okcar.mirror_clamp=1} switches it to "canvas = panel", an experiment that skips per-frame scaling. */
    private static boolean mirrorClampEnabled() {
        try {
            final Class<?> props = Class.forName("android.os.SystemProperties");
            final java.lang.reflect.Method get = props.getMethod("get", String.class, String.class);
            return "1".equals(String.valueOf(get.invoke(null, "persist.okcar.mirror_clamp", "0")).trim());
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Mirror canvas size: **by default returns the head-unit-declared size unchanged** (called upstream of both the encoder and the virtual display).
     *
     * <p>Why not take the panel size (the 1.58~1.62 approach; measured to distort the whole way): this chain has two different stages —
     * SurfaceFlinger scales the source screen **proportionally, centered** (letterbox) into the canvas without stretching; the receiver (the emulator)
     * **stretches** the stream it receives to fill its own screen. So only when the **canvas has the same aspect ratio as the head-unit-declared screen**
     * is the receiver's stretch an identity transform: a portrait phone cast to a landscape head unit shows "centered, aspect-preserved full screen" (the old
     * behavior, explicitly requested by the user); once the phone turns landscape, the source screen fills the canvas and the head unit fills naturally. Any change
     * to the canvas size/aspect ratio (clamping to the panel, taking min per dimension) makes the receiver's stretch distort — appearing in turn as a cropped border, squashing, or wrong proportions.
     *
     * <p>{@code persist.okcar.mirror_clamp=1} selects "canvas = panel" (skips per-frame scaling, experimental).
     * Note that the encoder and the virtual display must share this return value: clamping only the virtual display leaves the encoder configured at the old size, cropping the picture.
     */
    public static int[] clampMirrorCanvas(Context context, int width, int height) {
        if (!mirrorClampEnabled()) {
            return new int[]{width, height};
        }
        final int[] panel = panelSize(context);
        if (panel == null || (panel[0] == width && panel[1] == height)) {
            return new int[]{width, height};
        }
        Log.i(TAG, "mirror canvas " + width + 'x' + height + " -> " + panel[0] + 'x' + panel[1]
                + " (persist.okcar.mirror_clamp=1: the mirror is the panel itself; the receiver"
                + " stretches whatever aspect it gets, so expect a different framing)");
        return new int[]{panel[0], panel[1]};
    }

    /** @return true unless {@code persist.okcar.display_carwith=0} (see the flag list below). */
    private boolean carwithFlagSetEnabled() {
        try {
            final Class<?> props = Class.forName("android.os.SystemProperties");
            final java.lang.reflect.Method get = props.getMethod("get", String.class, String.class);
            return !"0".equals(String.valueOf(get.invoke(null, "persist.okcar.display_carwith", "1")).trim());
        } catch (Throwable t) {
            return true;                       /* default on */
        }
    }

    /**
     * Reads {@code android.hardware.display.DisplayManager.<name>} from the RUNNING platform.
     *
     * <p>The constants this class used to OR together were inlined at compile time from the Android 13
     * classpath; on Android 15 (217) some of those bits mean something else, and the ROM then refuses
     * the whole display — measured 2026-10-08:
     * {@code IllegalArgumentException: Public display must not be marked as SHOW_WHEN_LOCKED_INSECURE}.
     * A display that is never created leaves the cast with no video source, which is what the user saw
     * as "carwith 投屏连接不上".  Reading the field at runtime keeps the bits aligned with the platform;
     * a field the platform does not have contributes nothing (callers pass the fallback they want).
     */
    private static int vdFlag(String name, int fallback) {
        try {
            return android.hardware.display.DisplayManager.class.getField(name).getInt(null);
        } catch (Throwable t) {
            return fallback;
        }
    }

    /** Resolved {@code VirtualDisplay.setDisplayState(boolean)}; null when the ROM has no such method. */
    private static Method sSetDisplayState;

    /**
     * Marks the virtual display as ON so the platform keeps treating it as a live display.
     *
     * <p>This is the whole difference between a cast that works with the phone locked and one that
     * does not.  The platform decides whether an event injected into a non-interactive display
     * belongs to the user in {@code PhoneWindowManager.shouldDispatchInputWhenNonInteractive}
     * (frameworks/base/services/core/java/com/android/server/policy/PhoneWindowManager.java):
     * {@code displayOff = display.getState() == Display.STATE_OFF}, and while that holds and the
     * device has no watch feature the answer is simply false — every tap the car sends is dropped,
     * with no log line from us and no window state change, which is exactly what "锁屏后触摸无效"
     * looked like on 217 until 2026-10-20.
     *
     * <p>A virtual display reports {@code STATE_ON/OFF} from {@code mIsDisplayOn}
     * (VirtualDisplayAdapter.java:495), which starts as {@code surface != null} and is otherwise
     * only moved by {@code VirtualDisplay.setDisplayState(boolean)}.  Being PUBLIC also means we
     * get no {@code FLAG_NEVER_BLANK} (VirtualDisplayAdapter.java:441 only adds it for private
     * displays), so the power manager will not keep it lit for us.  CarWith's own cast display stays
     * ON because it calls this method on every resume
     * (com/miui/carlink/castfwk/CastController.java:1786 -> common/utils/y.java:203), and that is
     * why ICCOA keeps accepting touches with the phone locked and the built-in screen dark.
     *
     * <p>Missing the method is not fatal: the display is still created, the cast still plays, and
     * the only loss is touch on a locked phone. */
    private static void setDisplayStateOn(VirtualDisplay display) {
        if (display == null) {
            return;
        }
        try {
            if (sSetDisplayState == null) {
                sSetDisplayState = VirtualDisplay.class.getMethod("setDisplayState", Boolean.TYPE);
            }
            sSetDisplayState.invoke(display, Boolean.TRUE);
            Log.i(TAG, "cast display marked ON (keeps the car's touches alive while the phone sleeps)");
        } catch (Throwable t) {
            Log.i(TAG, "setDisplayState unavailable on this ROM (" + t + ") — touch on a locked"
                    + " phone stays off");
        }
    }

    @Override
    public VirtualDisplay createVirtualDisplay(String name, int width, int height, int density,
                                               Surface surface) {
        /* app-mode render scale (default 100 = no change). Scaling down reduces resolution and dpi together; the touch space and
         * the encoder both follow this (width,height,density), so scaling in this one place is enough. */
        final int renderScale = com.okcar.link.media.VideoQos.appRenderScalePercent();
        if (renderScale != 100 && width > 0 && height > 0) {
            width = Math.max(320, width * renderScale / 100);
            height = Math.max(240, height * renderScale / 100);
            if (density > 0) {
                density = Math.max(120, density * renderScale / 100);
            }
            Log.i(TAG, "app render scale " + renderScale + "% -> " + width + 'x' + height
                    + " dpi " + density);
        }

        /* In mirror mode the canvas must not exceed the phone panel's native size.
         *
         * The head-unit-declared size is often larger than the panel: measured on the main device, the emulator declares 2608x1200, so with the phone in
         * portrait we built a 1200x2608 mirror display while the panel is only 1080x2400. Those extra pixels are not clearer — they only
         * **non-integer upscale** the panel content (1.111x) before handing it to the encoder: one extra GPU scale per frame, the encoder chews
         * 36% more pixels, and the receiver scales it back down at the end. The 2026-10-04 field incident (mirror mode + entering any app freezes
         * the whole device) is suspected to be exactly this path dragging SurfaceFlinger/GPU through — normal screen recording never does this, the canvas is the screen itself.
         *
         * After clamping to the panel size, the mirror step is a 1:1 copy (aspect ratio differs by only 2%; the receiver adapts by itself).
         * persist.okcar.mirror_clamp=0 turns it off to compare against the old behavior. */
        if (mMirrorMode) {
            final int[] canvas = clampMirrorCanvas(mContext, width, height);
            width = canvas[0];
            height = canvas[1];
        }
        final int dpi = chooseDensity(width, height, density);
        /* Our own desktop is the default content, so the public display is built even when no
         * third-party app is configured (an empty app package used to mean "mirror the phone",
         * which is now the explicit {@link #setMirrorMode(boolean)} instead). */
        final boolean appMode = !mMirrorMode;
        if (mModuleOwnsDisplay) {
            mWidth = width;
            mHeight = height;
            mDensity = dpi;
            mModuleSurface = surface;
            ensureBroker();
            publishGeometry(width, height, dpi);
            mPlacement = "waiting for the app-owned display (module-owned)";
            Log.i(TAG, "module-owned display mode: handed " + width + 'x' + height + " dpi=" + dpi
                    + " to the in-app module (surface " + (surface == null ? "none" : "ready") + ')');
            return null;
        }
        /* Reuse the running display when nothing that matters has changed, and only swap the
         * encoder's surface.
         *
         * Rebuilding used to be unconditional, and that is what produced the instability the touch
         * work kept tripping over: releasing a virtual display that hosts an app makes
         * DisplayManagerService move that app's task onto the default display (the phone's own
         * screen), and the next session then starts a *second* instance on the fresh display.  A
         * screen that shows one app must stay the same screen for the whole run: same display id,
         * same task, same touch target.  Measured on 217 before this: display ids 58 -> 69 across a
         * handful of session restarts, with the shared app re-launched every time. */
        final VirtualDisplay existing = mDisplay;
        if (existing != null && mWidth == width && mHeight == height && mDensity == dpi
                && mPublic == appMode) {
            if (surface != null) {
                try {
                    existing.setSurface(surface);
                } catch (Throwable t) {
                    Log.w(TAG, "cannot swap the surface of display " + mDisplayId + ": " + t
                            + " — rebuilding it");
                    release();
                    return createVirtualDisplay(name, width, height, density, surface);
                }
            }
            mPlacement = "reusing display " + mDisplayId;
            Log.i(TAG, "reusing app display " + mDisplayId + ' ' + width + 'x' + height
                    + " dpi=" + dpi + " (surface " + (surface == null ? "kept" : "swapped") + ')');
            if (appMode) {
                Ports.setTouchDisplay(mDisplayId, width, height);
        publishDisplayId(mDisplayId);
                /* The state lives with the display, so it survived a release of the *encoder's*
                 * surface — unless the platform reset it.  Re-assert it on every reuse: it costs one
                 * binder call and it is what keeps the car's taps from being dropped. */
                setDisplayStateOn(existing);
                placeApp();
                if (surface != null) {
                    nudgeAppOnDisplay();
                }
            }
            return existing;
        }
        /* The geometry changed but the display is still the right one: resize it in place, do NOT
         * rebuild.
         *
         * <p>Rebuilding destroys the display, DisplayManagerService then destroys the app activities
         * that lived on it, and the next placement has no choice but to launch them again.  Each
         * launch adds another engine to the same app process — measured 2026-10-01: Pano workbench
         * grew 307 MB -> 380 MB -> 435 MB across three rebuilds while every rebuild also re-ran the
         * placement handshake, and the run finished with system_server ANR-ing on input dispatch
         * (waited 15339 ms for a MotionEvent).  resize() keeps the display, the task, the activity
         * and the touch target exactly as they are. */
        if (existing != null && mPublic == appMode) {
            try {
                existing.resize(width, height, dpi);
                if (surface != null) {
                    existing.setSurface(surface);
                }
                mWidth = width;
                mHeight = height;
                mDensity = dpi;
                Ports.setTouchDisplay(mDisplayId, width, height);
                setDisplayStateOn(existing);
                mPlacement = "resizing display " + mDisplayId + " to " + width + 'x' + height;
                Log.i(TAG, "resized app display " + mDisplayId + " to " + width + 'x' + height
                        + " dpi=" + dpi + " (kept the running app instance)");
                placeApp();
                if (surface != null) {
                    nudgeAppOnDisplay();
                }
                return existing;
            } catch (Throwable t) {
                Log.w(TAG, "resize of display " + mDisplayId + " failed (" + t + ") — rebuilding");
            }
        }
        release();
        final DisplayManager manager =
                (DisplayManager) mContext.getSystemService(Context.DISPLAY_SERVICE);
        if (manager == null) {
            mPlacement = "no DisplayManager";
            return null;
        }
        if (!appMode) {
            /* Explicit mirror mode: mirror the phone's own display into the encoder surface.
             * This is the fallback shape; the default is our own desktop (see #setMirrorMode). */
            final VirtualDisplay mirror;
            try {
                mirror = manager.createVirtualDisplay(
                        name == null || name.isEmpty() ? DISPLAY_NAME : name,
                        width, height, dpi, surface,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR);
            } catch (Throwable t) {
                mPlacement = "mirror display creation failed: " + t;
                Log.w(TAG, "root mirror display refused: " + t);
                return null;
            }
            if (mirror == null) {
                mPlacement = "mirror display creation returned null";
                return null;
            }
            mDisplay = mirror;
            mWidth = width;
            mHeight = height;
            mDensity = dpi;
            mDisplayId = mirror.getDisplay() == null ? -1 : mirror.getDisplay().getDisplayId();
            mPublic = false;
            /* Mirroring: the car's pixels are the phone's, so touches keep targeting the default
             * display (Ports -1) and are scaled into its logical size by InputInjector. */
            Ports.setTouchDisplay(-1, 0, 0);
            mPlacement = "full-screen mirror (no target app specified)";
            Log.i(TAG, "root mirror display " + mDisplayId + ' ' + width + 'x' + height
                    + " dpi=" + dpi + " created (no shared app configured)");
            return mirror;
        }
        /* PUBLIC + PRESENTATION + OWN_CONTENT_ONLY.
         *
         * <p>OWN_CONTENT_ONLY is not optional here, and it is not the "blank display" the earlier
         * note claimed — that note was written before the shared app was actually placed.
         * DisplayManagerService forces AUTO_MIRROR onto every PUBLIC display
         * (`if ((flags & VIRTUAL_DISPLAY_FLAG_PUBLIC) != 0) flags |= VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR;`)
         * and only OWN_CONTENT_ONLY clears it again.  Measured on 217 with the phone screen
         * awake and showing content (the desktop display harness dumping the display's own surface):
         *
         *   PUBLIC|PRESENTATION                     -> frame all black, app resumed on the display
         *   PUBLIC|PRESENTATION|OWN_CONTENT_ONLY    -> frame carries the app's live UI
         *
         * so without OWN_CONTENT_ONLY the encoder gets a black picture, and with it the display
         * composes its own layer stack, which is exactly the point of this class.
         *
         * <p>TRUSTED is what lets the shared app's <em>own</em> activity starts land here.  A
         * virtual display is untrusted by default, and the platform then refuses to launch an
         * activity onto it that does not declare {@code android:allowEmbedded}:
         * {@code ActivityTaskSupervisor.isCallerAllowedToLaunchOnDisplay} answers
         * {@code !display.isTrusted() && aInfo has no FLAG_ALLOW_EMBEDDED -> false}, which
         * {@code SafeActivityOptions.checkPermissions} turns into a SecurityException — measured on
         * 217 (2026-10-02) as the player crashing the moment it opened a video from its own list.
         * Trusted, that branch is skipped and the next one ({@code !isPrivate()}) allows anyone, so
         * the platform itself permits the launch and no permission hook is needed anywhere.  Only
         * uid 0/1000 may pass the flag: ADD_TRUSTED_DISPLAY is a signature permission, but
         * {@code ActivityManager.checkComponentPermission} grants root and system everything — and
         * this factory only ever runs in the root daemon, which is exactly why the privileged half
         * belongs here instead of in the in-app module.
         *
         * <p>It costs nothing visually: {@code shouldShowSystemDecorsLocked} defaults to false for a
         * non-default display (no status/navigation bar, no launcher) and the IME policy defaults to
         * FALLBACK_DISPLAY (the keyboard keeps appearing on the phone). */
        /* Resolve every flag from the RUNNING platform instead of the constants inlined at compile time.
         * This payload is compiled against the Android 13 classpath and runs on Android 15 (217), where
         * the same bit can mean something else: with the inlined CarWith set the ROM refused the display
         * outright — "Public display must not be marked as SHOW_WHEN_LOCKED_INSECURE" (2026-10-08) — and
         * a display that is never created means the cast has no video source at all, which is exactly
         * what "carwith 投屏连接不上" looked like.  A field the platform does not have contributes
         * nothing rather than a wrong bit. */
        final int base = vdFlag("VIRTUAL_DISPLAY_FLAG_PUBLIC", 1 << 0)
                | vdFlag("VIRTUAL_DISPLAY_FLAG_PRESENTATION", 1 << 1)
                | vdFlag("VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY", 1 << 4)
                | vdFlag("VIRTUAL_DISPLAY_FLAG_TRUSTED", 0);
        /* The extra flags CarWith uses for its own cast display (read from CarWith.apk's dex on
         * 2026-10-07): OWN_DISPLAY_GROUP keeps the cast out of the phone's display group (the
         * WindowManager reconfiguration path that froze the desktop), OWN_FOCUS stops the cast from
         * fighting the phone's focus, ROTATES_WITH_CONTENT + ALWAYS_UNLOCKED + the insecure-keyguard
         * flag keep it alive across rotation and the lock screen, DESTROY_CONTENT_ON_REMOVAL cleans up.
         * persist.okcar.display_carwith=0 restores the plain four-flag set for an A/B comparison. */
        final int extra = vdFlag("VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP", 0)
                | vdFlag("VIRTUAL_DISPLAY_FLAG_OWN_FOCUS", 0)
                | vdFlag("VIRTUAL_DISPLAY_FLAG_ROTATES_WITH_CONTENT", 0)
                | vdFlag("VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED", 0)
                | vdFlag("VIRTUAL_DISPLAY_FLAG_CAN_SHOW_WITH_INSECURE_KEYGUARD", 0)
                | vdFlag("VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL", 0)
                /* TOUCH is what makes the display a touch target: without it the injected taps are
                 * refused by InputDispatcher ("Asynchronous input event injection failed", 217,
                 * 2026-10-08) even though the window is focused and touchable — the cast then looks
                 * alive but nothing in it can be pressed. */
                | vdFlag("VIRTUAL_DISPLAY_FLAG_TOUCH", 1 << 6);
        /* Ladder: the full set, then without each flag a ROM is known to police on a PUBLIC display.
         * The accepted set is logged, so a new ROM that refuses something else is one log line away. */
        final int on = carwithFlagSetEnabled() ? extra : 0;
        final int showWhenLocked = vdFlag("VIRTUAL_DISPLAY_FLAG_CAN_SHOW_WITH_INSECURE_KEYGUARD", 0);
        final int ownFocus = vdFlag("VIRTUAL_DISPLAY_FLAG_OWN_FOCUS", 0);
        final int touch = vdFlag("VIRTUAL_DISPLAY_FLAG_TOUCH", 1 << 6);
        final int trusted = vdFlag("VIRTUAL_DISPLAY_FLAG_TRUSTED", 0);
        final int[] ladder = new int[] {
                base | on,
                base | (on & ~showWhenLocked),
                base | (on & ~(showWhenLocked | touch)),
                base | (on & ~(showWhenLocked | touch | ownFocus)),
                base,
                base & ~trusted,
        };
        VirtualDisplay display = null;
        Throwable refused = null;
        for (int i = 0; i < ladder.length && display == null; i++) {
            try {
                display = manager.createVirtualDisplay(
                        name == null || name.isEmpty() ? DISPLAY_NAME : name,
                        width, height, dpi, surface, ladder[i]);
            } catch (Throwable t) {
                refused = t;
            }
            if (display == null && refused == null) {
                refused = new IllegalStateException("createVirtualDisplay returned null");
            }
            Log.i(TAG, "cast display attempt " + i + " flags 0x" + Integer.toHexString(ladder[i])
                    + " (carwith set " + (carwithFlagSetEnabled() ? "on" : "off") + ") -> "
                    + (display == null ? "refused: " + refused : "created"));
        }
        if (display == null) {
            mPlacement = "createVirtualDisplay failed: " + refused;
            Log.w(TAG, "root app display refused: " + refused);
            return null;
        }
        mDisplay = display;
        mPublic = true;
        mWidth = width;
        mHeight = height;
        mDensity = dpi;
        mDisplayId = display.getDisplay() == null ? -1 : display.getDisplay().getDisplayId();
        /* Publish before placing the app: the pin module reads this file to decide which display a
         * launch belongs on, and the placement below is itself an activity start.  The create path
         * used to skip this (only reuse/release/adopt published), so the first session after a
         * daemon restart left the file at the -1 the rebuild had just written and the module had
         * nothing to pin to. */
        publishDisplayId(mDisplayId);
        /* The car's touches belong to this display and are already in its pixels: publish both
         * facts so InputInjector tags its events and scales 1:1 (no focus roulette). */
        Ports.setTouchDisplay(mDisplayId, width, height);
        /* Turn the display ON before the first app lands on it.  STATE_OFF here is what made the
         * car's taps vanish with the phone locked, so this has to happen on every create/reuse path,
         * not only at boot. */
        setDisplayStateOn(display);
        Log.i(TAG, "root app display " + mDisplayId + ' ' + width + 'x' + height + " dpi=" + dpi
                + " created (public, sized from the session's video size)");
        placeApp();
        startDisplayKeeper();
        return display;
    }

    /**
     * Panel height -&gt; density.  The car's screen becomes a 720dp-tall logical space
     * (1280x720@160 is the head-unit convention), unless the operator pinned a density.
     */
    private int chooseDensity(int width, int height, int callerDensity) {
        final int requested = mRequestedDensity;
        if (requested > 0) {
            return clampDensity(requested);
        }
        if (height > 0) {
            return clampDensity(Math.round(160f * height / REFERENCE_HEIGHT_DP));
        }
        return clampDensity(callerDensity > 0 ? callerDensity : 160);
    }

    private static int clampDensity(int dpi) {
        return Math.max(MIN_DENSITY, Math.min(MAX_DENSITY, dpi));
    }

    /**
     * The video source is going away: detach its surface and keep the display.
     *
     * <p>This display is the shared app's home, not the video source's scratch surface.  Releasing it
     * would move the app's task back onto the phone's display and force the next session to launch
     * another copy, so the display, its id and the running app all stay; the next session only swaps
     * a fresh encoder surface in through {@link #createVirtualDisplay}.
     */
    @Override
    public void releaseFromSource(VirtualDisplay display) {
        if (mModuleOwnsDisplay) {
            /* The module owns the display: tell it to let go instead of releasing it here. */
            publishGeometry(-1, -1, -1);
        }
        if (display == null) {
            return;
        }
        if (display != mDisplay) {
            /* Not ours (a mirror display, or one already replaced): the source owns nothing here. */
            try {
                display.release();
            } catch (Throwable t) {
                Log.w(TAG, "releasing a foreign display failed: " + t);
            }
            return;
        }
        try {
            display.setSurface(null);
            Log.i(TAG, "video source detached from app display " + mDisplayId
                    + " (display and app kept for the next session)");
        } catch (Throwable t) {
            Log.w(TAG, "cannot detach the surface from display " + mDisplayId + ": " + t
                    + " — releasing the display instead");
            release();
        }
    }

    /** Releases the display we own; safe to call repeatedly. */
    public void release() {
        stopDisplayKeeper();
        final VirtualDisplay display = mDisplay;
        mDisplay = null;
        mDisplayId = -1;
        mPublic = false;
        Ports.setTouchDisplay(-1, 0, 0);
        publishDisplayId(-1);
        if (display != null) {
            try {
                display.release();
                Log.i(TAG, "root app display released");
            } catch (Throwable t) {
                Log.w(TAG, "root app display release: " + t);
            }
        }
    }

    /** Publishes {@link #DISPLAY_ID_FILE} for out-of-process readers (best effort, never throws). */
    private static void publishDisplayId(int id) {
        java.io.FileOutputStream out = null;
        try {
            out = new java.io.FileOutputStream(DISPLAY_ID_FILE);
            out.write(Integer.toString(id).getBytes("UTF-8"));
            out.flush();
            new java.io.File(DISPLAY_ID_FILE).setReadable(true, false);
        } catch (Throwable t) {
            Log.w(TAG, "cannot publish " + DISPLAY_ID_FILE + ": " + t);
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (Throwable ignored) {
                    /* best effort */
                }
            }
        }
    }

    /* ------------------------------------------------------- keeping the app here */

    private volatile Thread mKeeper;
    private volatile boolean mKeeperRunning;
    /** Recovery can restart a running app; operators who prefer an empty screen can turn it off. */
    private volatile boolean mKeeperEnabled = true;

    public void setKeeperEnabled(boolean enabled) {
        mKeeperEnabled = enabled;
    }

    public boolean keeperEnabled() {
        return mKeeperEnabled;
    }

    /**
     * Keeps the shared app's <em>task</em> on our display.
     *
     * <p>Measured on 217 (live.alist.mpv, 2026-10-02): tapping a video inside the app starts the
     * player with {@code NEW_TASK|CLEAR_TOP}, and the whole task (player <em>and</em> the list
     * activity) ends up on the phone's display 0 — the car keeps an empty virtual display and taps
     * on it stop doing anything, because there is no window left there to receive them.  The app's
     * own launch flags are not ours to change, so the task is moved back whenever it wanders:
     * one {@code moveRootTaskToDisplay} per interval, which also bounds a fight with the app.
     */
    public void startDisplayKeeper() {
        if (mKeeperRunning) {
            return;
        }
        mKeeperRunning = true;
        final Thread t = new Thread(() -> {
            int missed = 0;        /* task is on another display */
            int missing = 0;       /* not even a task exists */
            long nextReplaceAtMs = 0L;
            while (mKeeperRunning) {
                try {
                    Thread.sleep(KeeperPolicy.intervalMs(
                            System.currentTimeMillis() - mLastPlacedAtMs));
                } catch (InterruptedException e) {
                    return;
                }
                final int id = mDisplayId;
                final String pkg = mAppPackage;
                if (id < 0 || pkg.isEmpty()) {
                    missed = 0;
                    missing = 0;
                    continue;
                }
                try {
                    mActivityDump = activityDump();
                    final int task = TaskDump.findTask(mActivityDump, pkg);
                    if (task <= 0) {
                        /* No task has two causes: the app is starting (process alive), or it was killed by the system (process gone too,
                         * which is what MIUI's SwipeUpClean did when measured). Only the latter is re-placed — otherwise a second
                         * instance would be created, and that was exactly the source of the 2026-10-01 system_server input-dispatch ANR.
                         *
                         * 2026-10-27 correction: "process alive" no longer means "starting" forever. Measured on 217 (com.mcar.auto,
                         * wireless): the app finished its own activity (wm_finish_activity … app-request) and kept its process — the
                         * car screen froze on the last frame, every touch was dropped with NO_WINDOW, and this branch answered
                         * "it must be starting" on every 4-second sample for 80+ minutes. So the assumption now has a time bound: a
                         * live process with no task counts as "starting" only inside the grace window
                         * (KeeperPolicy.shouldReplaceLiveWithoutTask); past it the app owns neither a task nor a window on our
                         * display and is re-placed — still behind the same MISSES_BEFORE_REPLACE and replace cooldowns. */
                        final boolean alive = appProcessAlive(pkg);
                        if (alive && hasWindowOnDisplay(id, pkg)) {
                            /* Drawing here while the task line is missing (dump-format quirk): treat as fine, don't restart it. */
                            mLiveNoTaskSinceMs = 0L;
                            missing = 0;
                            continue;
                        }
                        if (alive) {
                            if (mLiveNoTaskSinceMs == 0L) {
                                mLiveNoTaskSinceMs = System.currentTimeMillis();
                            }
                            if (!KeeperPolicy.shouldReplaceLiveWithoutTask(
                                    System.currentTimeMillis() - mLiveNoTaskSinceMs)) {
                                continue;   /* still inside the "it may be starting" grace */
                            }
                            /* past the grace: it finished itself — fall through to the re-place block below */
                        } else {
                            mLiveNoTaskSinceMs = 0L;
                        }
                        if (++missing < KeeperPolicy.MISSES_BEFORE_REPLACE) {
                            continue;
                        }
                        final long now = System.currentTimeMillis();
                        if (!KeeperPolicy.cooldownElapsed(now, nextReplaceAtMs)) {
                            continue;
                        }
                        missing = 0;
                        mLiveNoTaskSinceMs = 0L;
                        Log.i(TAG, "keeper: " + pkg + (alive
                                ? " is alive but owns no task and no window on display " + id
                                  + " (it finished its own activity) — re-placing it"
                                : " has neither a task nor a live process"
                                  + " (killed by the system?) — re-placing it on display " + id));
                        mActivityDump = null;   /* placeApp fetches a fresh one itself */
                        final boolean placed = mKeeperEnabled && placeApp(true);
                        nextReplaceAtMs = System.currentTimeMillis()
                                + (placed ? KeeperPolicy.REPLACE_COOLDOWN_MS
                                          : KeeperPolicy.REPLACE_FAIL_COOLDOWN_MS);
                        if (placed) {
                            Ports.setTouchDisplay(id, mWidth, mHeight);
                        }
                        continue;
                    }
                    missing = 0;
                    mLiveNoTaskSinceMs = 0L;
                    final int where = TaskDump.displayOfTask(mActivityDump, task);
                    if (where == id) {
                        missed = 0;
                        continue;
                    }
                    /* Two checks in a row: a single sample can catch a task mid-move (session
                     * churn) and moving it then would kill an activity that is about to land. */
                    if (++missed < KeeperPolicy.MISSES_BEFORE_MOVE) {
                        Log.i(TAG, "keeper: task " + task + " is on display " + where
                                + " (first sample, watching)");
                        continue;
                    }
                    missed = 0;
                    if (mKeeperEnabled && moveRootTaskToDisplay(task, id)) {
                        /* Re-publish the touch space: an app that was just dragged back must still
                         * be the target of the car's touches (and of any `tap` the operator sends).
                         *
                         * NOTE: a cross-display task move always recreates the activity (Android
                         * tears down and relaunches on the new display), so a recovery is visible
                         * as "the app restarted".  It is still better than an empty car screen,
                         * and it only happens when the task really left the display — normally the
                         * app's own activity starts join the task that is already here and never
                         * move.  `keeper off` turns the recovery off entirely. */
                        Ports.setTouchDisplay(id, mWidth, mHeight);
                        Log.i(TAG, "keeper: task " + task + " moved from display " + where
                                + " back to " + id + " (" + pkg + ')');
                    }
                } catch (Throwable e) {
                    Log.w(TAG, "keeper: " + e);
                } finally {
                    mActivityDump = null;
                }
            }
        }, "okcar-display-keeper");
        t.setDaemon(true);
        mKeeper = t;
        t.start();
    }

    /**
     * Is a live process still present for the package (including child processes like {@code pkg:remote}).
     *
     * <p>Scans {@code /proc} directly: this is the only liveness check that still works on HyperOS — `dumpsys` is entirely blind,
     * while {@code am stack list} only says "there is or isn't a task". Skip a process that can't be read; when even {@code /proc}
     * can't be listed, return true (don't draw a conclusion, prefer no action).
     */
    private boolean appProcessAlive(String pkg) {
        final java.io.File[] entries = new java.io.File("/proc").listFiles();
        if (entries == null) {
            return true;
        }
        for (java.io.File entry : entries) {
            final String name = entry.getName();
            if (name.isEmpty() || name.charAt(0) < '0' || name.charAt(0) > '9') {
                continue;
            }
            final String cmdline = readCmdline(entry);
            if (cmdline == null || cmdline.isEmpty()) {
                continue;
            }
            if (cmdline.equals(pkg) || cmdline.startsWith(pkg + ':')) {
                return true;
            }
        }
        return false;
    }

    /** The process's {@code /proc/<pid>/cmdline} (NUL-terminated); null if unreadable. */
    private static String readCmdline(java.io.File procDir) {
        try (java.io.FileInputStream in =
                     new java.io.FileInputStream(new java.io.File(procDir, "cmdline"))) {
            final byte[] buffer = new byte[256];
            final int read = in.read(buffer);
            if (read <= 0) {
                return null;
            }
            int end = 0;
            while (end < read && buffer[end] != 0) {
                end++;
            }
            return new String(buffer, 0, end, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return null;
        }
    }

    public void stopDisplayKeeper() {
        mKeeperRunning = false;
        final Thread t = mKeeper;
        mKeeper = null;
        if (t != null) {
            t.interrupt();
        }
    }

    /** Display that currently hosts root task {@code taskId}, or -1 when the dump has no such task. */
    private int taskDisplay(int taskId) {
        return TaskDump.displayOfTask(currentDump(), taskId);
    }

    /* ---------------------------------------------------------------- placement */

    /**
     * Puts {@link #appPackage()} on the display and verifies it landed there.  Three routes,
     * in the order MIUI is least likely to refuse:
     * <ol>
     *   <li>system-context {@code startActivity} with {@code setLaunchDisplayId} (asks for
     *       {@code MODE_BACKGROUND_ACTIVITY_START_ALLOWED} when the platform has it);</li>
     *   <li>{@code am start --display <id>} — the shell-shaped call;</li>
     *   <li>{@code ActivityTaskManager.moveRootTaskToDisplay} — root-only, for the case where
     *       the app did start but MIUI ignored {@code --display}.</li>
     * </ol>
     *
     * @return true when the app is verifiably on the display afterwards
     */
    public boolean placeApp() {
        return placeApp(false);
    }

    /**
     * Places the shared app, optionally forcing a clean restart first.
     *
     * <p>{@code forceRelaunch} is the operator's "the car screen is wrong, put it there again"
     * action (the daemon's {@code place}).  It skips the reuse shortcuts and restarts the app, which
     * is the only way to repair a picture whose activity survived a display rebuild with a blank
     * surface.
     */
    public boolean placeApp(boolean forceRelaunch) {
        final int displayId = mDisplayId;
        final String pkg = mAppPackage;
        if (displayId < 0) {
            mPlacement = "no virtual display";
            return false;
        }
        if (pkg.isEmpty()) {
            mPlacement = "no target app specified";
            return false;
        }
        /* Nothing changed since the last successful placement on this same display: answer without
         * touching the framework at all.  Session churn re-enters this method every time the
         * receiver reconnects, and each entry used to cost several dumpsys calls. */
        final long now = System.currentTimeMillis();
        if (!forceRelaunch && mLastPlacedDisplay == displayId
                && now - mLastPlacedAtMs < PLACEMENT_MIN_GAP_MS) {
            mPlacement = "already on display " + displayId + " (confirmed within "
                    + ((now - mLastPlacedAtMs) / 1000) + "s)";
            return true;
        }
        mActivityDump = activityDump();
        try {
            final boolean placed = placeAppLocked(displayId, pkg, forceRelaunch);
            if (placed) {
                mLastPlacedAtMs = System.currentTimeMillis();
                mLastPlacedDisplay = displayId;
            }
            return placed;
        } finally {
            mActivityDump = null;
        }
    }

    private boolean placeAppLocked(int displayId, String pkg, boolean forceRelaunch) {
        final Intent launch = launchIntent(pkg);
        final boolean uiOnly = !(mAppUiComponent == null || mAppUiComponent.isEmpty());
        if (launch == null && !uiOnly) {
            mPlacement = "cannot find a launch entry for " + pkg;
            Log.w(TAG, mPlacement);
            return false;
        }

        /* An app that keeps its engine in the normal instance needs that instance alive first; only
         * then can the activity we put on the car display reach it (Pano workbench: without this the
         * second activity logs "Unable to connect to the Main Engine" and stays on its splash
         * screen, which the car shows as a blank picture). */
        final boolean hasUi = uiOnly;
        if (hasUi && mHostOnPhone && launch != null) {
            restartHost(pkg, launch);
        } else if (hasUi) {
            Log.i(TAG, "host stays off the phone for " + pkg
                    + " (app-ui only, hostOnPhone=" + mHostOnPhone + ')');
        }

        /* Already there *and drawing*?  A task record alone is not enough, and that distinction is
         * the difference between a picture and a black screen: `dumpsys activity activities` keeps
         * naming a package long after its UI is gone (task history, focus bookkeeping) and a killed
         * app's record can survive as a leftover.  Measured on 217 — with the app killed and not a
         * single window anywhere, the task-record check answered "already on display 2", the app was never
         * relaunched, the display stayed empty and the receiver got a black screen with zero encoded
         * frames (`mirror/encode stopped: 0 frames, 0B`).  Placement is only done when the package
         * owns a window on this display. */
        if (!forceRelaunch && hasWindowOnDisplay(displayId, pkg)) {
            mPlacement = "already on display " + displayId;
            Log.i(TAG, "app already drawing on display " + displayId + ": " + pkg);
            return true;
        }

        /* The configured car-screen activity, when the app does not put its UI in the launcher
         * activity.  There is deliberately no fallback to the launcher activity here: for an app
         * shaped like Pano workbench the launcher activity only splashes when it lands on a secondary
         * display (measured: black picture with the app's splash still on top), so falling back
         * would trade a missing UI for a confidently wrong one.  Failing keeps the last frame the
         * receiver already shows. */
        if (hasUi) {
            /* A rebuilt display gets a NEW id while the instance from the previous one is still
             * running — Flutter engine, memory and all.  Launching here leaves two copies of the app
             * alive: measured 2026-10-01, Pano workbench ran as 2 processes / ~370 MB, the phone's
             * system_server ANR'd on input dispatch (waited 15339 ms for a MotionEvent) and the
             * screen froze right after a successful cast.  Move the existing car-screen task first.
             * Only that task: the same package also keeps its host instance on the phone display,
             * and moving *that* one would put the phone-shaped UI on the car screen. */
            final int existingTask = findTaskIdForComponent(mAppUiComponent);
            if (existingTask > 0 && forceRelaunch) {
                /* Operator pressed "place again" (or the daemon's `place`), which happens exactly when
                 * the car screen is wrong — typically a white picture from a task that was moved onto
                 * a rebuilt display: its window keeps a surface but its content was never recreated
                 * (measured 2026-10-01: the moved activity reported isReadyForDisplay=true while the
                 * receiver decoded 8 fps / 26 kbps of flat white).  A clean restart is the only thing
                 * that rebuilds that content. */
                Log.i(TAG, "forced placement: restarting " + pkg + " instead of reusing task "
                        + existingTask);
                run(new String[]{"/system/bin/am", "force-stop", pkg}, "am force-stop " + pkg);
                mActivityDump = activityDump();
                restartHost(pkg, launch);
            } else if (existingTask > 0
                    && moveRootTaskToDisplay(existingTask, displayId)
                    && awaitOnDisplay(displayId, pkg)) {
                mPlacement = "moveRootTaskToDisplay task=" + existingTask + " -> display " + displayId
                        + " (reused the existing instance, no relaunch)";
                Log.i(TAG, "app moved instead of relaunched: " + mPlacement);
                /* Moving preserves the instance but not its rendered content: the activity's surface
                 * belonged to the display that just went away.  Re-delivering the launch intent makes
                 * it rebuild for this display (cheap), which is the difference between a picture and
                 * the white screen the receiver otherwise shows. */
                nudgeAppOnDisplay();
                return true;
            }
            if (existingTask > 0) {
                /* The task is there but would not move (a display it cannot leave, or the move was
                 * refused).  Stop it before launching, so that whichever route succeeds next there
                 * is still exactly one instance; force-stop takes the host down too, hence the
                 * re-start, and the cached activity dump is dropped because it still shows the
                 * windows that just died. */
                Log.i(TAG, "task " + existingTask + " would not move to display " + displayId
                        + " — stopping the stale instance first");
                run(new String[]{"/system/bin/am", "force-stop", pkg}, "am force-stop " + pkg);
                mActivityDump = activityDump();
                restartHost(pkg, launch);
            }
            if (startComponentOnDisplay(mAppUiComponent, displayId) && awaitOnDisplay(displayId, pkg)) {
                mPlacement = "am start --display " + displayId + " -n " + mAppUiComponent;
                Log.i(TAG, "app placed: " + mPlacement);
                return true;
            }
            /* One retry: the UI activity connects to the host's engine, which may still have been
             * coming up when the first launch went out (that is exactly what its splash screen
             * means). */
            try {
                Thread.sleep(1500L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (startComponentOnDisplay(mAppUiComponent, displayId) && awaitOnDisplay(displayId, pkg)) {
                mPlacement = "am start --display " + displayId + " -n " + mAppUiComponent + " (succeeded after retry)";
                Log.i(TAG, "app placed: " + mPlacement);
                return true;
            }
            mPlacement = "failed: " + mAppUiComponent + " did not come up on display " + displayId
                    + " (is the host " + pkg + " on the phone screen?)";
            Log.w(TAG, mPlacement);
            return false;
        }

        /* No window here, so launch it.  A launch is also what makes an external-display-aware app
         * build its own UI for the display — Pano workbench shows its workbench as
         * PanoExternalDisplayActivity only when a launch sees the secondary display, while moving an
         * already-running task across displays leaves its phone-shaped MainActivity on screen. */
        if (startWithOptions(launch, displayId) && awaitOnDisplay(displayId, pkg)) {
            mPlacement = "startActivity -> display " + displayId;
            Log.i(TAG, "app placed: " + mPlacement);
            return true;
        }
        if (startWithAm(launch, displayId) && awaitOnDisplay(displayId, pkg)) {
            mPlacement = "am start --display " + displayId;
            Log.i(TAG, "app placed: " + mPlacement);
            return true;
        }

        /* Last resort: it is running somewhere else and would not start here — move that task rather
         * than leave the car with an empty display. */
        final int runningTask = findTaskId(pkg);
        if (runningTask > 0 && moveRootTaskToDisplay(runningTask, displayId)
                && awaitOnDisplay(displayId, pkg)) {
            mPlacement = "moveRootTaskToDisplay task=" + runningTask + " -> display " + displayId;
            Log.i(TAG, "app placed: " + mPlacement);
            return true;
        }
        mPlacement = "failed: " + pkg + " is not on display " + displayId
                + " (taskId=" + runningTask + ", all three routes failed)";
        Log.w(TAG, mPlacement);
        return false;
    }

    private Intent launchIntent(String pkg) {
        try {
            final Intent launch = mContext.getPackageManager().getLaunchIntentForPackage(pkg);
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            return launch;
        } catch (Throwable t) {
            Log.w(TAG, "getLaunchIntentForPackage(" + pkg + "): " + t);
            return null;
        }
    }

    /** Route 1: system-context start with an explicit launch display. */
    private boolean startWithOptions(Intent launch, int displayId) {
        try {
            final ActivityOptions options = ActivityOptions.makeBasic();
            try {
                options.setLaunchDisplayId(displayId);
            } catch (Throwable t) {
                Log.w(TAG, "setLaunchDisplayId: " + t);
                return false;
            }
            /* Android 14+: ask the background-activity-start policy for permission instead of
             * letting the start be aborted.  Values live in ActivityOptions
             * (MODE_BACKGROUND_ACTIVITY_START_ALLOWED = 1); the API-33 compile surface has no
             * such method, so it goes through reflection and is simply absent on older builds. */
            try {
                final Method allowed = ActivityOptions.class
                        .getMethod("setBackgroundActivityStartMode", int.class);
                allowed.invoke(options, 1);
            } catch (Throwable ignored) {
                /* platform predates the BAL modes */
            }
            mContext.startActivity(launch, options.toBundle());
            Log.i(TAG, "startActivity on display " + displayId + " did not throw");
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "startActivity on display " + displayId + " refused: " + t);
            return false;
        }
    }

    /** Route 2: `am start --display <id> -n <component>` (the module runs as root already). */
    private boolean startWithAm(Intent launch, int displayId) {
        final android.content.ComponentName component = launch.getComponent();
        if (component == null) {
            Log.w(TAG, "launch intent has no component; cannot use am start");
            return false;
        }
        return run(new String[]{"/system/bin/am", "start", "--display", String.valueOf(displayId),
                "-n", component.flattenToShortString()}, "am start");
    }

    /** Route 3: move the app's already-running root task onto the display (root-only call). */
    private boolean moveRootTaskToDisplay(int taskId, int displayId) {
        try {
            final Class<?> atm = Class.forName("android.app.ActivityTaskManager");
            final Object service = atm.getMethod("getService").invoke(null);
            if (service == null) {
                return false;
            }
            final Method move = service.getClass()
                    .getMethod("moveRootTaskToDisplay", int.class, int.class);
            move.invoke(service, taskId, displayId);
            Log.i(TAG, "moveRootTaskToDisplay(" + taskId + ", " + displayId + ") returned");
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "moveRootTaskToDisplay(" + taskId + ", " + displayId + ") failed: " + t);
            return false;
        }
    }

    private boolean run(String[] command, String what) {
        try {
            final Process process = Runtime.getRuntime().exec(command);
            final StringBuilder output = new StringBuilder();
            final BufferedReader reader =
                    new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                if (output.length() < 400) {
                    output.append(line).append(' ');
                }
            }
            process.waitFor();
            Log.i(TAG, what + " -> " + output.toString().trim());
            return true;
        } catch (Throwable t) {
            Log.w(TAG, what + " failed: " + t);
            return false;
        }
    }

    /* -------------------------------------------------------------- verification */

    /** Waits for the app to be drawing on {@code displayId}; reuses one dump per iteration. */
    private boolean awaitOnDisplay(int displayId, String pkg) {
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                Thread.sleep(attempt == 0 ? 700 : 600);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            if (hasWindowOnDisplay(displayId, pkg)) {
                return true;
            }
            /* Take exactly one fresh dump per iteration: this loop is the only place that needs to
             * observe a change, and every call costs ActivityManager locks. */
            mActivityDump = activityDump();
        }
        return false;
    }

    /** The attempt's cached dump, or a fresh one when no attempt is in progress. */
    private String[] currentDump() {
        final String[] cached = mActivityDump;
        return cached != null ? cached : activityDump();
    }

    /** Waits for a window of {@code pkg} on {@code displayId} without requiring a task record. */
    private boolean awaitWindowOnDisplay(int displayId, String pkg) {
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                Thread.sleep(attempt == 0 ? 400 : 800);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            mActivityDump = activityDump();
            if (hasWindowOnDisplay(displayId, pkg)) {
                return true;
            }
        }
        return false;
    }

    /** {@code am start --display <id> -n <component>} — the route MIUI actually honours. */
    private boolean startComponentOnDisplay(String component, int displayId) {
        return run(new String[]{"/system/bin/am", "start", "--display", String.valueOf(displayId),
                "-n", component}, "am start --display");
    }

    /**
     * Makes sure the app's own launch activity (the engine host) is up on the phone display.
     *
     * <p>Idempotent on purpose: it is called before the car-screen activity on every placement, and
     * again after a force-stop (which necessarily took the host down with it).
     */
    private void restartHost(String pkg, Intent launch) {
        if (hasWindowOnDisplay(DEFAULT_DISPLAY, pkg)) {
            return;
        }
        final android.content.ComponentName host = launch.getComponent();
        if (host == null) {
            return;
        }
        mPlacement = "starting the host " + host.flattenToShortString();
        Log.i(TAG, "starting the host instance on the phone display: " + mPlacement);
        run(new String[]{"/system/bin/am", "start", "-n", host.flattenToShortString()}, "am start host");
        awaitWindowOnDisplay(DEFAULT_DISPLAY, pkg);
    }

    /**
     * Re-fronts the app on its display so it redraws and the receiver gets a fresh picture.
     *
     * <p>A static app produces no frames.  After a session restart the receiver has a brand-new
     * decoder and the display has not changed, so the stream carries only repeated or blank frames
     * and the car sits on a black picture until something happens on screen.  Measured on 217 right
     * after a reconnect: {@code shown=0.0fps, kbps=108} with nothing the decoder could start from;
     * re-delivering the launch intent took it to {@code 8.2fps / 165kbps} immediately.  Re-starting
     * an activity that is already on the display just brings it to the front — it is not a restart,
     * and it is what makes a reconnected session show a picture instead of a blank frame.
     */
    public void nudgeAppOnDisplay() {
        final int displayId = mDisplayId;
        if (displayId < 0 || (mAppPackage == null || mAppPackage.isEmpty())) {
            return;
        }
        String component = mAppUiComponent;
        if (component.isEmpty()) {
            final Intent launch = launchIntent(mAppPackage);
            if (launch == null || launch.getComponent() == null) {
                return;
            }
            component = launch.getComponent().flattenToShortString();
        }
        Log.i(TAG, "nudging the app on display " + displayId + " for a fresh frame: " + component);
        startComponentOnDisplay(component, displayId);
    }

    /**
     * True when the package owns a window on {@code displayId}, read from the <em>activity</em> dump.
     *
     * <p>An ActivityRecord prints its own windows as {@code windows=[Window{…}]}, so one
     * {@code dumpsys activity activities} answers both "is there a task?" and "is it drawing?".
     * This replaces a separate {@code dumpsys window windows} per poll — that call walks every
     * window in the system and was the heavy half of the pairing that could stall system_server.
     */
    private boolean hasWindowOnDisplay(int displayId, String pkg) {
        final String[] dump = currentDump();
        if (dump == null) {
            return false;
        }
        boolean inSection = false;
        boolean mine = false;
        for (String line : dump) {
            final Matcher display = DISPLAY_SECTION.matcher(line);
            if (display.find()) {
                inSection = Integer.parseInt(display.group(1)) == displayId;
                mine = false;
                continue;
            }
            if (line.contains("ActivityTaskSupervisor state")) {
                inSection = false;
                continue;
            }
            if (!inSection) {
                continue;
            }
            if (line.contains("* Task{") || line.contains("* ActivityRecord{")) {
                mine = line.contains(pkg);
                continue;
            }
            if (line.contains("topResumedActivity=") && line.contains(pkg)) {
                mine = true;
                continue;
            }
            if (line.contains("windows=[")) {
                if (mine && line.contains("Window{")) {
                    return true;
                }
                continue;
            }
        }
        return false;
    }

    /**
     * True when {@code dumpsys activity activities} shows a <em>task or activity</em> of the package
     * inside the section for {@code Display #<id>}.  The start call cannot be trusted: MIUI accepts
     * {@code --display} and then launches on the phone's display, and only this output tells the two
     * outcomes apart.
     *
     * <p>Two details keep this honest.  The per-display listing ends where the supervisor-wide state
     * begins, and the scan has to stop there — otherwise it runs to EOF and any later mention of the
     * package (task history, focus bookkeeping) counts as "on this display"; measured on 217, that
     * false positive is what let a killed app keep the "already on display N" verdict and the car stayed
     * black.  And only task/activity lines count, not any line that happens to name the package.
     */
    private boolean isOnDisplay(int displayId, String pkg) {
        final String[] dump = currentDump();
        if (dump == null) {
            return false;
        }
        boolean inSection = false;
        for (String line : dump) {
            final Matcher display = DISPLAY_SECTION.matcher(line);
            if (display.find()) {
                inSection = Integer.parseInt(display.group(1)) == displayId;
                continue;
            }
            if (line.contains("ActivityTaskSupervisor state")) {
                inSection = false;
                continue;
            }
            if (inSection && line.contains(pkg)
                    && (line.contains("* Task{") || line.contains("* ActivityRecord{")
                        || line.contains("topResumedActivity="))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Task id of a running {@code pkg}, preferring the enclosing <em>root</em> task.
     *
     * <p>{@code moveRootTaskToDisplay} wants a root task, and the dump prints both kinds:
     * {@code RootTask{… #12 …}} wrapping {@code Task{… #123 …}} whose {@code ActivityRecord}
     * carries the short {@code t123}.  Reading a root task id when there is one is what makes
     * route 3 an actual move instead of a no-op on a leaf task.
     */
    private int findTaskId(String pkg) {
        return TaskDump.findTask(currentDump(), pkg);
    }

    /**
     * Task stack dump: try the standard {@code dumpsys activity activities} first; if it is blind (HyperOS measured 0 bytes) switch to
     * {@code am stack list}, and remember the working source so each round doesn't waste a try.
     */
    private String[] activityDump() {
        if (mActivitySource == SOURCE_AM) {
            final String[] am = amStackList();
            if (!TaskDump.looksBlind(am)) {
                return am;
            }
            mActivitySource = SOURCE_UNKNOWN;
        }
        final String[] dump = dumpsys("activity", "activities");
        if (!TaskDump.looksBlind(dump)) {
            mActivitySource = SOURCE_DUMPSYS;
            return dump;
        }
        final String[] am = amStackList();
        if (!TaskDump.looksBlind(am)) {
            mActivitySource = SOURCE_AM;
            Log.i(TAG, "activity dump: dumpsys is blind here, using `am stack list`");
            return am;
        }
        /* Both are blind: return the dumpsys output as-is; the caller uses TaskDump.looksBlind to detect emptiness rather than treating it as "no task". */
        return dump;
    }

    private String[] amStackList() {
        return run("/system/bin/am", "stack", "list");
    }

    /**
     * Root task that already hosts {@code component} on <em>any</em> display, or -1.
     *
     * <p>This is what keeps a rebuilt display from spawning a second copy of the app: the car-screen
     * activity from the previous display is usually still alive, and it can be moved instead of
     * launched.  Filtering by component (not by package) matters — the same package also runs its
     * host activity on the phone display, and that task must stay where it is.
     */
    private int findTaskIdForComponent(String component) {
        final String[] dump = currentDump();
        if (dump == null || component == null || component.isEmpty()) {
            return -1;
        }
        for (String line : dump) {
            if (!line.contains("ActivityRecord{") || !line.contains(component)) {
                continue;
            }
            /* Only the record's own {@code tNNN}.  Inheriting "the RootTask seen last" is how an
             * unrelated task got moved onto the car screen (measured 2026-10-01: a start-session
             * move dragged another app's task over, and the car came back showing something else
             * entirely).  A record whose task id cannot be read is refused — moving the wrong task
             * is far worse than not moving at all. */
            final Matcher own = OWN_TASK_LINE.matcher(line);
            return own.find() ? Integer.parseInt(own.group(1)) : -1;
        }
        return -1;
    }

    private String[] dumpsys(String... args) {
        return run("/system/bin/dumpsys", args);
    }

    private String[] run(String binary, String... args) {
        final String[] command = new String[args.length + 1];
        command[0] = binary;
        System.arraycopy(args, 0, command, 1, args.length);
        try {
            final Process process = Runtime.getRuntime().exec(command);
            final java.util.List<String> lines = new java.util.ArrayList<>();
            final BufferedReader reader =
                    new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
            process.waitFor();
            return lines.toArray(new String[0]);
        } catch (Throwable t) {
            Log.w(TAG, binary + " " + String.join(" ", args) + " failed: " + t);
            return null;
        }
    }

    private static final Pattern DISPLAY_SECTION = Pattern.compile("Display #(\\d+)");
    /* Parsing of both the RootTask{… #N …} and Task{… #N …} formats has moved to TaskDump, where the host can test it. */
    /** The {@code tNNN} an {@code ActivityRecord{…}} prints next to its component. */
    private static final Pattern OWN_TASK_LINE = Pattern.compile(" t(\\d+)(\\}|\\s)");
    /** {@code Window #12 Window{abc123 u0 com.pkg/.Act}:} — group 1 is {@code com.pkg/.Act}. */
    private static final Pattern WINDOW_LINE =
            Pattern.compile("Window #\\d+ Window\\{[0-9a-f]+ u\\d+ (\\S+)");
    /** The {@code mDisplayId=N} that follows a window's header line. */
    private static final Pattern WINDOW_DISPLAY = Pattern.compile("mDisplayId=(-?\\d+)");
}
