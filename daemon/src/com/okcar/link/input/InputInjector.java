/*
 * OkcarOS link stack — L5: local input injection.
 *
 * The head unit's touchspace (1920x720) maps onto the phone screen that is being
 * mirrored to it, so a report from the car becomes a MotionEvent on the phone.
 * Injection uses the hidden InputManager API, which is available to a
 * platform-signed privileged app holding INJECT_EVENTS.
 *
 * TODO(rotation): the car's 1920x720 touchspace is the *CarPlay display*, whose
 * orientation follows the mirrored phone screen.  For now we scale into the
 * current real display size; once the video session exists this should be driven
 * by the actual encoder input size / display rotation instead.
 */

package com.okcar.link.input;

import android.content.Context;
import android.graphics.Point;
import android.hardware.input.InputManager;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import android.view.Display;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.WindowManager;

import com.okcar.link.hid.HidReportDecoder;
import com.okcar.link.port.MiuiSynergyMode;

public final class InputInjector {

    private static final String TAG = "OkCarLink/Input";

    /**
     * CarWith mode (2026-10-07): the picture on the head unit comes from CarWith's own virtual
     * display, so this process's displays are not what the car is looking at.  While a router is
     * installed it gets the first refusal on every touch/key and, when it consumes the event, the
     * local injection does not run at all.
     */
    public interface Router {
        /** @return true when the report was routed into CarWith and must not be injected locally */
        boolean routeTouch(HidReportDecoder.Report report);

        /** @return true when the key was routed into CarWith and must not be injected locally */
        boolean routeKey(int keyCode);
    }

    private static volatile Router sRouter;

    /** Installs the CarWith router, or null to restore local injection. */
    public static void setRouter(Router router) {
        sRouter = router;
    }

    public static Router router() {
        return sRouter;
    }

    private final InputManager mInputManager;
    /** Binder-side input service; on SDK 37 this is the only injection channel that can still carry a displayId. */
    private final android.hardware.input.IInputManager mInputService;
    private final Context mContext;
    /**
     * Used to bring the phone back before injecting: see {@link #wakeIfAsleep()}.  Null only when the
     * platform refuses the service, in which case the injection runs exactly as it did before.
     */
    private final PowerManager mPower;

    private int mSourceWidth = HidReportDecoder.TOUCH_SPACE_WIDTH;
    private int mSourceHeight = HidReportDecoder.TOUCH_SPACE_HEIGHT;

    private long mDownTime;
    private boolean mTouchDown;
    /** Where the pending finger last was, so it can be cancelled at the right place. */
    private float mLastX;
    private float mLastY;

    /** When the last touch report was injected; used to release a gesture nothing ever lifted. */
    private volatile long mLastTouchAtMs;

    /** A down with no report for this long is treated as an abandoned gesture. */
    private static final long STALE_TOUCH_MS = 4000;

    /** How long a {@code persist.okcar.keep_awake} read is trusted; see {@link #keepAwakeEnabled()}. */
    private static final long KEEP_AWAKE_CACHE_MS = 2000L;
    private static volatile boolean sKeepAwakeEnabled = true;
    private static volatile long sKeepAwakeReadAtMs;
    /** One-shot log so a per-frame path cannot flood logcat. */
    private boolean mWarnedAboutBackend;
    private boolean mWarnedAboutService;

    public InputInjector(Context context) {
        mContext = context.getApplicationContext();
        mInputManager = inputManagerOrNull();
        mInputService = inputServiceOrNull();
        mPower = powerManagerOrNull();
    }

    /**
     * {@code InputManager.getInstance()} is a hidden API; on Android 17 (SDK 37) it no longer
     * exists and the call throws {@link NoSuchMethodError}, which is an {@link Error} and so is
     * not caught by the {@code RuntimeException} guard around the injection itself.  Measured
     * 2026-10-03 on the main phone: 942 HID reports arrived from the head unit and every single
     * one died here, so touch never worked.  Treat "the API is gone" as "there is no
     * InputManager" and let the uinput backend ({@code Ports.eventInjectBackend()}, installed by
     * PortBootstrap) carry the touches instead.
     */
    /**
     * After {@code InputManager.getInstance()} was removed in SDK 37, only the binder service itself
     * ({@code android.hardware.input.IInputManager}, exposed by ServiceManager) can still carry a displayId.
     * App-cast content lives on our own virtual display, and only this can be "pointed at that display";
     * uinput writes kernel events, which always land on the phone's own screen (measured 2026-10-03:
     * app-cast video was fine, but touch feedback stayed on the phone).  Null means fall back to uinput.
     */
    private static android.hardware.input.IInputManager inputServiceOrNull() {
        try {
            final android.os.IBinder binder = android.os.ServiceManager.getService("input");
            if (binder == null) {
                Log.w(TAG, "no 'input' binder service — display-targeted injection unavailable");
                return null;
            }
            return android.hardware.input.IInputManager.Stub.asInterface(binder);
        } catch (Throwable t) {
            Log.w(TAG, "input binder service unavailable (" + t + ")");
            return null;
        }
    }

    private static InputManager inputManagerOrNull() {
        try {
            return InputManager.getInstance();
        } catch (Throwable t) {
            Log.w(TAG, "InputManager.getInstance() unavailable (" + t
                    + ") — the uinput backend carries the touches instead");
            return null;
        }
    }

    private PowerManager powerManagerOrNull() {
        try {
            return (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
        } catch (Throwable t) {
            Log.w(TAG, "PowerManager unavailable (" + t + ") — cannot wake the phone for a touch");
            return null;
        }
    }

    /**
     * Brings the phone back when it is asleep, because an injected event is dropped in that state.
     *
     * <p>InputDispatcher only delivers injected input while the device is interactive.  Measured on
     * 217 (Android 15, 2026-10-20) with one window and one coordinate: awake the tap lands and the
     * app navigates ({@code MIUIInput: publisher action=0x0} on the app's channel); asleep the same
     * tap produces no {@code publisher} line at all, while {@code dumpsys input} still reports the
     * window's focus request as OK and the virtual display still {@code mScreenState=ON}.  Measured
     * against the settings app: awake → navigates to WifiSettings, asleep → focus untouched,
     * re-awake → navigates again.
     *
     * <p>The daemon's keep-awake watch already stops the phone from falling asleep on its own while
     * a cast is live, but an explicit power-button lock still stops it, so the first touch after that
     * wakes the phone and waits for it to become interactive before the event goes in.  The wait is
     * bounded: if the phone does not come back, the event is still injected (and dropped by the
     * platform) rather than held back.  {@code persist.okcar.keep_awake=0} turns this off together
     * with the wake lock — a user who does not want the phone kept up does not want it woken by a tap
     * either (same switch, read in {@code com.okcar.link.daemon.LinkDaemon} for the lock).
     */
    private void wakeIfAsleep() {
        final PowerManager pm = mPower;
        /* Order matters on this path: it runs for every injected event, and a drag delivers a MOVE
         * per frame. isInteractive() is one cheap binder call and answers "nothing to do" for the
         * common awake case; the switch lookup and the synergy check only run when the phone really
         * is down. */
        if (pm == null || pm.isInteractive()) {
            return;
        }
        if (!keepAwakeEnabled()) {
            return;
        }
        /* MIUI synergy mode is deliberately non-interactive and still dispatches injected input, so
         * waking there would only light up the panel the driver does not need. Ask the synergy
         * bridge whether this ROM has that state before falling back to wakeUp(). That call is a
         * plain field read on purpose - it used to fork a `settings get` here, which cost 53-73 ms
         * per event and is what made touch feel laggy once the phone locked (2026-10-21). */
        if (MiuiSynergyMode.synergyActive()) {
            Log.i(TAG, "phone is asleep in MIUI synergy mode — injecting without waking it");
            return;
        }
        try {
            pm.wakeUp(SystemClock.uptimeMillis());
        } catch (Throwable t) {
            Log.w(TAG, "cannot wake the phone for a touch (" + t + ")");
            return;
        }
        /* Up to ~500 ms for the display to come back; the first event of a session may be handed in
         * right after the phone went down, so the wait is shorter than any user-visible timeout. */
        for (int i = 0; i < 25 && !pm.isInteractive(); i++) {
            try {
                Thread.sleep(20L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        Log.i(TAG, "phone was asleep — woke it for the car's touch (injected input is dropped"
                + " while the device is not interactive; interactive=" + pm.isInteractive() + ")");
    }

    /**
     * {@code persist.okcar.keep_awake=0} disables the wake lock and the wake-on-touch nudge above.
     *
     * <p>Cached for a couple of seconds: this is asked once per injected event (and a drag is one
     * event per frame), and the answer only changes when someone runs {@code setprop}. Short TTL
     * rather than a one-shot read so the switch still takes effect without restarting the daemon.
     */
    private static boolean keepAwakeEnabled() {
        final long now = SystemClock.uptimeMillis();
        if (now - sKeepAwakeReadAtMs < KEEP_AWAKE_CACHE_MS) {
            return sKeepAwakeEnabled;
        }
        sKeepAwakeReadAtMs = now;
        try {
            final Class<?> props = Class.forName("android.os.SystemProperties");
            final java.lang.reflect.Method get = props.getMethod("get", String.class, String.class);
            final String raw = String.valueOf(get.invoke(null, "persist.okcar.keep_awake", "1")).trim();
            sKeepAwakeEnabled = !("0".equals(raw) || "false".equalsIgnoreCase(raw));
        } catch (Throwable ignored) {
            sKeepAwakeEnabled = true;
        }
        return sKeepAwakeEnabled;
    }

    /**
     * Sets the fallback coordinate space for reports that do not carry their own
     * (defaults to 1920x720).  The iAP2 decoder declares 1920x720 per report and is
     * scaled from that instead; an AirPlay slot declares nothing because the receiver
     * sends its own screen pixels, so it is scaled from this configured session space
     * (the wireless path installs the video size here — 1080x2400 on this link).  The
     * choice is per report, so iAP2 and AirPlay reports can be injected in the same
     * session without this mutable value deciding between them.
     */
    public void setSourceSpace(int width, int height) {
        if (width > 0 && height > 0) {
            mSourceWidth = width;
            mSourceHeight = height;
        }
    }

    public boolean injectTouch(HidReportDecoder.Report report) {
        /* CarWith mode (2026-10-07): CarWith owns the display the picture comes from, so a car touch
         * must be injected into CarWith's own display through its IRemoteTouchListener — not into
         * this process's display.  The router is consulted first, before the local backend checks,
         * because CarWith mode needs neither InputManager nor the uinput backend. */
        final Router router = sRouter;
        if (router != null && router.routeTouch(report)) {
            return true;
        }
        if (mInputManager == null && com.okcar.link.port.Ports.eventInjectBackend() == null) {
            Log.w(TAG, "no InputManager, dropping " + report);
            return false;
        }
        final Point size = displaySize();
        // Per-report source space: the report's own space wins; the configured
        // value is only a fallback for reports that do not declare one.
        final int sourceWidth = report.sourceWidth > 0 ? report.sourceWidth : mSourceWidth;
        final int sourceHeight = report.sourceHeight > 0 ? report.sourceHeight : mSourceHeight;
        /* The accessory reports in the frame it displays, which is only the same thing as our
         * content when nothing was letterboxed (app mode).  Mirroring fits the phone's screen into
         * that frame with bars, so the point has to be taken back out of the bars first — see
         * TouchGeometry.contentPoint. */
        final float[] point = com.okcar.link.input.TouchGeometry.contentPoint(
                sourceWidth, sourceHeight, size.x, size.y, report.x, report.y);
        final float x = point[0];
        final float y = point[1];
        final long now = SystemClock.uptimeMillis();
        /* A gesture whose UP never arrived — the accessory's report was lost, or the event channel
         * dropped mid-gesture — leaves the dispatcher holding a pointer on our display, and every
         * later DOWN is delivered as a MOVE, so the screen looks dead to touch while injection still
         * reports success.  Releasing it here is the difference between "touch died forever" and "one
         * gesture was lost". */
        if (mTouchDown && now - mLastTouchAtMs > STALE_TOUCH_MS) {
            Log.i(TAG, "releasing a finger left down for " + ((now - mLastTouchAtMs) / 1000)
                    + "s (no report arrived to lift it)");
            releasePending();
        }

        final int action;
        if (report.down) {
            action = mTouchDown ? MotionEvent.ACTION_MOVE : MotionEvent.ACTION_DOWN;
            if (!mTouchDown) {
                mDownTime = now;
                mTouchDown = true;
            }
        } else {
            if (!mTouchDown) {
                return false;
            }
            action = MotionEvent.ACTION_UP;
            mTouchDown = false;
        }

        final MotionEvent event = MotionEvent.obtain(mDownTime, now, action, x, y, 0);
        mLastX = x;
        mLastY = y;
        mLastTouchAtMs = now;
        try {
            return inject(event);
        } finally {
            event.recycle();
        }
    }

    /**
     * Cancel a finger that is still down, releasing the dispatcher's pointer on the target
     * display.
     *
     * <p>An injected DOWN that never gets its UP leaves the dispatcher holding a pointer, and
     * every later DOWN is then refused as "a pointer that is already down": that display stops
     * accepting touch and does not recover on its own.  A session that ends mid-gesture — churn,
     * a reconnect, the daemon restarting — is exactly how a finger gets abandoned, and the
     * symptom is the one reported after a long connection: the app on the display simply stops
     * reacting while everything else looks healthy.
     *
     * @return true when a pending gesture was cancelled
     */
    public boolean releasePending() {
        if (!mTouchDown) {
            return false;
        }
        mTouchDown = false;
        final MotionEvent event = MotionEvent.obtain(mDownTime, SystemClock.uptimeMillis(),
                MotionEvent.ACTION_CANCEL, mLastX, mLastY, 0);
        try {
            final boolean ok = inject(event);
            Log.i(TAG, "released a finger left down at " + mLastX + "," + mLastY
                    + " (cancel accepted=" + ok + ')');
            return ok;
        } finally {
            event.recycle();
        }
    }

    /**
     * One synthetic tap (down -&gt; hold -&gt; up) through the same path a receiver's report takes.
     *
     * <p>Bring-up probe for the app-display architecture: a car touch that reaches the wrong
     * display shows up as "the app on our display never reacts, but something on the phone's own
     * screen did", and that is indistinguishable from a receiver-side problem without a way to
     * drive this exact path on demand.  The module daemon exposes it as {@code tap x y [ms]}.
     *
     * @param sourceWidth  space the coordinates are in; the car's own pixels when it declares them
     * @return true when both the down and the up were accepted by the injection backend
     */
    public boolean injectTap(int x, int y, int sourceWidth, int sourceHeight, long holdMillis) {
        boolean ok = injectTouch(HidReportDecoder.Report.touch(x, y, true, sourceWidth, sourceHeight));
        if (holdMillis > 0) {
            try {
                Thread.sleep(holdMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        ok &= injectTouch(HidReportDecoder.Report.touch(x, y, false, sourceWidth, sourceHeight));
        return ok;
    }

    public boolean injectKey(int keyCode) {
        /* See injectTouch: in CarWith mode the key belongs to CarWith's display and is translated
         * into one of its own 81xx key codes. */
        final Router router = sRouter;
        if (router != null && router.routeKey(keyCode)) {
            return true;
        }
        if (mInputManager == null && com.okcar.link.port.Ports.eventInjectBackend() == null) {
            return false;
        }
        final long now = SystemClock.uptimeMillis();
        boolean ok = inject(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0));
        ok &= inject(new KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0));
        return ok;
    }

    private boolean inject(InputEvent event) {
        try {
            /* A sleeping phone drops everything we hand to the dispatcher (see wakeIfAsleep), and the
             * car cannot see the difference — its taps simply do nothing.  Do this before the event is
             * prepared so the very first touch after a lock still lands. */
            wakeIfAsleep();
            /* The app-display architecture: the shared app runs on OUR public virtual display,
             * so an injected event must name that display — otherwise the dispatcher sends the
             * car's taps to the phone's own screen and the app on the virtual display never sees
             * them (the failure this architecture removes).  displaySize() reports that display's
             * pixel space in the same case, so the report's own space maps 1:1. */
            final int target = com.okcar.link.port.Ports.touchDisplayId();
            if (target >= 0) {
                if (event instanceof MotionEvent) {
                    ((MotionEvent) event).setDisplayId(target);
                } else if (event instanceof KeyEvent) {
                    ((KeyEvent) event).setDisplayId(target);
                }
            }
            /* The event has to say what it *is*, not only where it goes.  MotionEvent.obtain()
             * builds an event whose source is SOURCE_UNKNOWN, and an app that classifies its
             * input then sees something it cannot place and drops it — measured on Pano
             * (com.nightmare.neo), which logs the classification and rejects the tap:
             *
             *   MIUIInput: Input event injection from package: null action ACTION_UP
             *   NeoInputSession: isMouse -> false, isStylus -> false, isTouchscreen -> false,
             *                    isJoystick -> false, isUnknown -> false, isCustomStylus -> false
             *
             * That is why the kernel-level uinput path (which carries a real touchscreen
             * device) worked while this one appeared dead, and why "touches do nothing" came
             * back together with the app-display architecture.  The source is stated here so
             * every caller — receiver reports, the daemon's tap probe and keys — carries it. */
            if (event instanceof MotionEvent) {
                event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            } else if (event instanceof KeyEvent) {
                event.setSource(InputDevice.SOURCE_KEYBOARD);
            }
            final com.okcar.link.port.EventInjectBackend backend =
                    com.okcar.link.port.Ports.eventInjectBackend();
            /* The uinput backend writes kernel events straight into the phone's input stack and
             * cannot address another display, so it is unusable once the car's touches belong to
             * our virtual display.  In that case the built-in InputManager path is the only one
             * that can carry the display id — and it is available here because this process is
             * root, which is exactly why the module does not need uinput any more. */
            final boolean backendCannotTargetDisplay = target >= 0
                    && backend instanceof com.okcar.link.port.UinputTouchBackend;
            /* 1) The platform's built-in hidden client (gone since SDK 37; kept only for older devices). */
            if (mInputManager != null) {
                if (backendCannotTargetDisplay && !mWarnedAboutBackend) {
                    mWarnedAboutBackend = true;
                    Log.i(TAG, "touches target display " + target
                            + ": using InputManager injection (uinput cannot address a display)");
                }
                return mInputManager.injectInputEvent(event,
                        InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
            }
            /* 2) Touches target a virtual display (app casting): only the binder service can carry the displayId. */
            if (backendCannotTargetDisplay) {
                if (mInputService != null) {
                    if (!mWarnedAboutService) {
                        mWarnedAboutService = true;
                        Log.i(TAG, "touches target display " + target
                                + ": using the input binder service (no InputManager on this"
                                + " platform) — this is what makes app casting touchable");
                    }
                    /* Two-argument form: the displayId rides on the event itself (inject() already called
                     * setDisplayId(target) above) — exactly what the hidden InputManager client used to do. */
                    try {
                        return mInputService.injectInputEvent(event,
                                InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
                    } catch (android.os.RemoteException e) {
                        if (!mWarnedAboutService) {
                            mWarnedAboutService = true;
                            Log.w(TAG, "input binder injection failed (" + e
                                    + ") — falling back to uinput");
                        }
                    }
                }
                if (!mWarnedAboutService) {
                    mWarnedAboutService = true;
                    Log.w(TAG, "touches target display " + target
                            + " but neither InputManager nor the input binder service is"
                            + " reachable — falling back to uinput (touches will act on the"
                            + " phone's own screen)");
                }
            }
            /* 3) uinput: mirror mode (target<0, the phone screen is the content being cast) and the fallback when the above are unusable. */
            if (backend != null) {
                return backend.injectInputEvent(event);
            }
            if (!mWarnedAboutBackend) {
                mWarnedAboutBackend = true;
                Log.w(TAG, "no injection backend available (no InputManager, no input service,"
                        + " no uinput backend) — dropping touches");
            }
            return false;
        } catch (RuntimeException | LinkageError e) {
            Log.w(TAG, "inject failed: " + e);
            return false;
        }
    }

    private Point displaySize() {
        /* The registered size is what a caller ASKED for; for a display we do not own (CarWith's own
         * cast display) it can differ from the truth.  Registered 2400x1080 against a real 1920x864
         * display degraded contentPoint() into an identity map, so every tap landed off-screen and
         * the cast looked dead to touch while injection still reported success (measured
         * 2026-10-08).  The real metrics of the display we are about to inject into win. */
        final int[] real = com.okcar.link.port.DisplayMirror.sizeOf(
                mContext, com.okcar.link.port.Ports.touchDisplayId());
        if (real != null) {
            return new Point(real[0], real[1]);
        }
        /* Touches aimed at our own virtual display are in that display's pixels, not the phone's. */
        final int[] target = com.okcar.link.port.Ports.touchDisplaySize();
        if (target != null) {
            return new Point(target[0], target[1]);
        }
        final Point point = new Point(1080, 1920);
        try {
            final WindowManager windowManager =
                    (WindowManager) mContext.getSystemService(Context.WINDOW_SERVICE);
            if (windowManager == null) {
                return point;
            }
            final Display display = windowManager.getDefaultDisplay();
            final android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
            /* getMetrics, NOT getRealMetrics: the real metrics are rotation-independent (always the
             * physical portrait size), while the space the car's taps describe is the picture we
             * actually send, i.e. the rotated logical display.  With the real size a landscape tap
             * at (1644,331) in the car's 1920x720 space became (925,1103) — below a 2400x1080
             * screen — so the phone reacted somewhere unrelated and the mirrored app got nothing
             * (measured 2026-09-30). */
            display.getMetrics(metrics);
            if (metrics.widthPixels > 0 && metrics.heightPixels > 0) {
                point.x = metrics.widthPixels;
                point.y = metrics.heightPixels;
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "cannot read display size: " + e);
        }
        return point;
    }
}
