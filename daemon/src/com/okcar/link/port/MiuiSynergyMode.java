package com.okcar.link.port;

import android.content.Context;
import android.util.Log;

import java.lang.reflect.Method;

/**
 * MIUI synergy-mode bridge (Xiaomi/Redmi only; no-op everywhere else).
 *
 * <p>Why this exists: AOSP drops every injected event while the device is not interactive. The
 * check sits in
 * {@code frameworks/base/services/core/jni/com_android_server_input_InputManagerService.cpp}
 * ({@code interceptMotionBeforeQueueing}): injected events only get
 * {@code POLICY_FLAG_PASS_TO_USER} when {@code mInteractive} is true, and
 * {@code InputDispatcher.cpp} then drops them with {@code dropReason=POLICY}. Holding a
 * SCREEN_BRIGHT wake lock prevents the automatic screen timeout but not the power button, so
 * pressing it still lands in ASLEEP and the car's touches die. Waking the phone up again (the
 * other half of that fallback) restores the touches but lights the panel, which is not what a
 * head unit should do.
 *
 * <p>MIUI has a second input path. {@code InputManagerServiceStubImpl.updateSynergyModeFromSettings}
 * mirrors the Secure key {@code synergy_mode} into {@code InputCommonConfig}, which parcels it down
 * to {@code libmiinputflinger.so}; there {@code MiInputDispatcherStubImpl} keeps dispatching
 * injected events while the device is non-interactive. Turning that key on is therefore enough to
 * keep the car's touches alive with the phone asleep and dark - no wake-up, no lit panel.
 *
 * <p>This is exactly what CarWith/ICCOA does: {@code CarlinkStateMachine} calls
 * {@code MiuiCarService.setCastingState(3)} when a cast starts and {@code (4)} when it ends, and
 * that method sets {@code ucar_casting_state}, {@code screen_project_in_screening} and
 * {@code synergy_mode}. It never touches {@code screen_project_hang_up_on}. We call the same method
 * by binder reflection.
 *
 * <p>Field evidence (Redmi K30 Pro, MIUI 14 / Android 12, 2026-10-20). With the phone asleep
 * ({@code mWakefulness=Asleep}, {@code Interactive: false}, panel {@code mScreenState=OFF}) and
 * {@code screen_project_in_screening=1}:
 *
 * <ul>
 *   <li>{@code synergy_mode=1} - an injected tap reached the live app window
 *       ({@code MIUIInput: publisher action=0x0, channel 'com.autonavi.minimap/...UcarMapActivity'}),
 *       twice, with the device still asleep.</li>
 *   <li>{@code synergy_mode=0} - no {@code publisher} line at all, twice: the event was dropped.</li>
 * </ul>
 *
 * <p>An earlier version of this bridge also wrote {@code screen_project_hang_up_on} to force MIUI's
 * projection-<em>hangup</em> state. That was the wrong lever: it made
 * {@code PowerManagerServiceImpl.updateScreenProjectionLocked()} enter hangup even while the screen
 * was on, so the panel went dark again the moment the user pressed power - the phone appeared to
 * lock itself on every wake-up. The key is not needed for the input path and is no longer written.
 *
 * <p>Because the daemon runs as uid 0, AOSP {@code ActivityManager.checkComponentPermission} grants
 * every permission to root, so MIUI's {@code permissionCheck()} passes without us holding
 * {@code miui.car.permission.MI_CARLINK_STATUS}.
 *
 * <p>Everything here is best-effort: a missing service, a non-MIUI ROM, a renamed method or a
 * permission denial must never affect casting. Failures are logged once and the phone simply keeps
 * its normal AOSP sleep behaviour.
 */
public final class MiuiSynergyMode {
    private static final String TAG = "MiuiSynergy";

    private static final String SERVICE_NAME = "MiuiCarService";
    private static final String STUB = "miui.car.IMiuiCarManager$Stub";
    private static final String METHOD_SET_CASTING_STATE = "setCastingState";

    /** Mirrors {@code MiuiCarService}: 3 = casting starts, anything else = casting stops. */
    private static final int CASTING_START = 3;
    private static final int CASTING_STOP = 4;

    /**
     * The Secure key MIUI's input pipeline reads. {@code MiuiCarService.setCastingState} owns it,
     * so this class only ever reads it - see {@link #synergyActive}.
     */
    private static final String KEY_SYNERGY_MODE = "synergy_mode";

    private static volatile boolean sTried;
    private static volatile Object sService;
    private static volatile boolean sAvailable;
    private static volatile boolean sApplied;
    /**
     * Cached answer to "is the key set right now", so {@link #synergyActive()} can be called from the
     * touch hot path without going near the settings provider.
     *
     * <p>It used to read the key back with a {@code settings get} child process on every injected
     * event. Measured on the main phone 2026-10-21: one {@code settings get secure synergy_mode}
     * costs 53-73 ms; a tap is two events (DOWN + UP) and every MOVE of a drag is another, so a
     * locked-screen session ran ~65 ms late per event - reported as "touch latency is huge once the
     * phone locks". The value is now refreshed off the hot path: {@link #apply} and
     * {@link #rearmIfCasting} set it from the call they just made, and the daemon's synergy watch
     * calls {@link #refreshKey} on its once-a-second tick to catch the platform clearing the key
     * behind our back.
     */
    private static volatile boolean sKeyOn;

    private MiuiSynergyMode() {
    }

    /**
     * Turns MIUI synergy mode on or off to match a live cast.
     *
     * <p>One binder call, the same one CarWith makes: {@code MiuiCarService.setCastingState(3)}
     * when a cast starts, {@code (4)} when it ends. That writes {@code ucar_casting_state},
     * {@code screen_project_in_screening} and {@code synergy_mode}; the last of those is what makes
     * the input pipeline keep dispatching while the phone is non-interactive.
     *
     * <p>The phone keeps its normal sleep behaviour: the power button still wakes it, the panel
     * still lights on demand, and the screen timeout still sleeps it. Only the injected-input path
     * changes.
     *
     * @param context any context; only used to log problems consistently
     * @param active  true while a cast session is live
     */
    public static void apply(Context context, boolean active) {
        if (active == sApplied) {
            return;
        }
        if (!ensureService()) {
            return;
        }
        try {
            final Method m = sService.getClass().getMethod(METHOD_SET_CASTING_STATE, int.class);
            m.invoke(sService, active ? CASTING_START : CASTING_STOP);
            sApplied = active;
            sKeyOn = active;
            Log.i(TAG, "synergy " + (active ? "on" : "off")
                    + " (setCastingState " + (active ? CASTING_START : CASTING_STOP) + ")");
        } catch (Throwable t) {
            Log.w(TAG, "setCastingState failed: " + t);
        }
    }

    /**
     * Re-applies synergy mode while a cast is live.
     *
     * <p>{@code PowerManagerServiceImpl.doDieLocked(0)} clears all three projection keys whenever
     * the last registered projection client dies - field-verified 2026-10-20, when the low-memory
     * killer reclaimed {@code com.miui.carlink} and the keys went to 0 mid-session. Repeating the
     * same call is cheap: {@code updateScreenProjectionLocked()} returns early when nothing
     * actually changed.
     */
    public static void rearmIfCasting() {
        if (!sApplied || !ensureService()) {
            return;
        }
        try {
            final Method m = sService.getClass().getMethod(METHOD_SET_CASTING_STATE, int.class);
            m.invoke(sService, CASTING_START);
            sKeyOn = true;
        } catch (Throwable t) {
            Log.i(TAG, "rearm failed (" + t + ")");
        }
    }

    /**
     * Re-reads the key off the touch hot path and caches the result.
     *
     * <p>Called by the daemon's synergy watch once per tick. {@link #synergyActive} cannot do this
     * itself: it runs on every injected event, and reading the key costs a child process (see
     * {@link #sKeyOn}).
     */
    public static void refreshKey() {
        if (!(sAvailable && sApplied)) {
            sKeyOn = false;
            return;
        }
        sKeyOn = readSecure(KEY_SYNERGY_MODE);
    }

    /** True when this ROM exposes the casting-state service at all. */
    public static boolean available() {
        return ensureService();
    }

    /**
     * True while MIUI is dispatching injected input to a non-interactive device.
     *
     * <p>Callers use this as "do not wake the phone, the touch will be delivered anyway". This is a
     * plain field read: it runs on every injected event, so it must not do IPC. The value is kept
     * current by {@link #apply}, {@link #rearmIfCasting} and {@link #refreshKey}. On a non-MIUI ROM,
     * or before the first cast of this process, it returns false and the caller keeps its normal
     * AOSP behaviour.
     */
    public static boolean synergyActive() {
        return sAvailable && sApplied && sKeyOn;
    }

    /** Reads one Secure int setting back; false on any error or a zero value. */
    private static boolean readSecure(String key) {
        try {
            final Process p = new ProcessBuilder("settings", "get", "secure", key)
                    .redirectErrorStream(true).start();
            final java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream()));
            final String line = r.readLine();
            p.waitFor();
            final String v = line == null ? "" : line.trim();
            return !v.isEmpty() && !"0".equals(v) && !"null".equals(v);
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean ensureService() {
        if (sTried) {
            return sAvailable;
        }
        sTried = true;
        try {
            final Class<?> stub = Class.forName(STUB);
            final Object binder = android.os.ServiceManager.getService(SERVICE_NAME);
            if (binder == null) {
                Log.i(TAG, "no " + SERVICE_NAME + " (not a MIUI car ROM? nothing to do)");
                return false;
            }
            final Method asInterface = stub.getMethod("asInterface", android.os.IBinder.class);
            final Object svc = asInterface.invoke(null, binder);
            if (svc == null) {
                Log.i(TAG, SERVICE_NAME + " asInterface returned null");
                return false;
            }
            svc.getClass().getMethod(METHOD_SET_CASTING_STATE, int.class);
            sService = svc;
            sAvailable = true;
        } catch (Throwable t) {
            Log.i(TAG, "unavailable: " + t);
        }
        return sAvailable;
    }
}
