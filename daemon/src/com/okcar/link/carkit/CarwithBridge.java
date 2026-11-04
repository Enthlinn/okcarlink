/*
 * OkCarLink — the CarWith component bridge (daemon side), rewritten to the measured direction.
 *
 * WHAT CHANGED (2026-10-07, dex-proven — see {@link CarwithContract} for the evidence):
 * the first version of this class assumed CarWith *calls* `prepareCast(CastConfig)` on the component
 * and hands it a surface.  It is the other way round.  CarWith starts
 * `com.baidu.carlife.xiaomi/com.baidu.carlife.service.CarlifeConnectService` with
 * `bindPackageName=com.miui.carlink` / `bindClassName=com.xiaomi.ucar.carlife.CarlifeConnService`
 * extras, and the component (an ordinary app, no root) then
 *   1. binds that CarWith service and checks the returned binder's descriptor,
 *   2. asks THIS daemon for the encoder input surface (`GET_SURFACE`, code 1),
 *   3. installs our two callbacks on CarWith (`setSystemAudioCallback`, `setCastCallback`),
 *   4. calls `prepareCast(CastConfig{width,height,dpi,Surface})` — CarWith builds its virtual
 *      display **into that surface** (`CastController.displayConnected`: `mSurface = surface;`
 *      `createVirtualDisplay(new VirtualDisplayConfig.Builder(...).setSurface(mSurface)…)`).
 *
 * So the surface published here is the one we hand OUT, and the frames CarWith renders into it land
 * in the AirPlay encoder (MirrorVideoSource hands the same input surface over instead of building
 * its own mirror display — see `useExternalDisplay`).
 *
 * The service is registered as a hidden ServiceManager entry by the root daemon, which is the only
 * channel that can carry a Surface between the two processes.
 *
 * Component-side protocol (see tools/carwith-comp/src/com/baidu/carlife/service/CarlifeConnectService.java):
 *   1 GET_SURFACE        -> present(0|1), width, height, dpi, Surface, sessionId
 *   2 GET_CAST_CALLBACK  -> ICarLifeCastCallback binder (ours; CarWith calls it)
 *   3 GET_AUDIO_CALLBACK -> ISystemAudioCallback binder (ours; CarWith calls it)
 *   4 PREPARE_RESULT     <- ok(0|1), detail  (what CarWith answered to prepareCast)
 *   5 CAST_STOPPED       <- detail
 *   6 STATUS             -> one status line (for `carwith status` and the WebUI)
 *   7 ENSURE_CARWITH     -> started(0|1), detail  (start CarWith's CarlinkService, root, 2026-10-08)
 *   8 CAST_STATE         -> prepared(0|1), displayId, state  (what the prepareCast replay waits for)
 *
 * 7 and 8 exist because CarWith drops `prepareCast(CastConfig)` on the floor while its own
 * `CarlinkStateMachine` does not exist, and the component cannot see that from the AIDL reply (the
 * binder transaction still succeeds).  The daemon is root, so it can issue the real entry point
 * ({@link CarwithLauncher}) and can observe `onCastPrepared` on the callback it owns.
 *
 * Every entry point refuses a caller that is neither the CarWith component nor root: the bridge can
 * hand out a Surface, so an unchecked caller would let any app in the CarLife slot take the picture.
 */
package com.okcar.link.carkit;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.util.Log;
import android.view.Surface;

import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class CarwithBridge {

    public static final String TAG = "OkCarLink/CarwithBridge";
    public static final String NAME = "okcar-carwith";

    /* component -> daemon transaction codes (this class is the server) */
    public static final int T_GET_SURFACE = 1;
    public static final int T_GET_CAST_CALLBACK = 2;
    public static final int T_GET_AUDIO_CALLBACK = 3;
    public static final int T_PREPARE_RESULT = 4;
    public static final int T_CAST_STOPPED = 5;
    public static final int T_STATUS = 6;
    /**
     * Component -> daemon: make sure CarWith's own `CarlinkService` (and with it
     * `CarlinkStateMachine`) is up before the component sends `prepareCast`.  Reply:
     * `started(0|1)`, `detail`.  The daemon is the only side that may do this: it runs as root.
     */
    public static final int T_ENSURE_CARWITH = 7;
    /**
     * Component -> daemon: has CarWith delivered `onCastPrepared(displayId)` yet?  Reply:
     * `prepared(0|1)`, `displayId`, `state`.
     */
    public static final int T_CAST_STATE = 8;

    private static final int UID_ROOT = 0;
    private static final int UID_SYSTEM = 1000;

    /** Monotonic: every published surface belongs to exactly one cast session. */
    private static volatile long sGeneration;
    private static volatile String sSessionId = "";
    private static volatile Surface sSurface;
    private static volatile int sWidth;
    private static volatile int sHeight;
    private static volatile int sDpi = 160;
    private static volatile String sState = "idle";
    private static volatile String sLastError = "";
    private static volatile String sPrepareDetail = "";
    private static volatile String sEnsureDetail = "";
    /** 0 = no bootstrap yet; else the elapsedRealtime of the last issued start (cooldown). */
    private static volatile long sLastBootstrapAtMs;
    /** Two bootstrap starts closer than this spin CarWith's aborted connect flow (measured on 217). */
    private static final long BOOTSTRAP_COOLDOWN_MS = 8000L;
    private static volatile boolean sInstalled;
    private static volatile Context sContext;
    private static volatile CarwithCastClient sClient;

    private CarwithBridge() {
    }

    /* ------------------------------------------------------------------ install */

    public static void install() {
        install(null);
    }

    /**
     * Registers `okcar-carwith` with ServiceManager.
     *
     * @param context the daemon's context, used only to resolve the component's uid for the caller
     *     check; null is allowed (the check then accepts root/system only).
     */
    public static void install(Context context) {
        if (context != null) {
            sContext = context.getApplicationContext() != null ? context.getApplicationContext()
                    : context;
        }
        if (sInstalled) {
            return;
        }
        final CarwithCastClient client = client();
        try {
            final Binder binder = new Binder() {
                @Override
                protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
                    return handle(code, data, reply);
                }
            };
            final Class<?> sm = Class.forName("android.os.ServiceManager");
            final Method add = sm.getMethod("addService", String.class, IBinder.class);
            add.invoke(null, NAME, binder);
            sInstalled = true;
            sState = "registered";
            Log.i(TAG, "registered as '" + NAME + "' (the CarWith component can now fetch the cast"
                    + " surface and install our callbacks)");
            note("registered as " + NAME + "; " + client.describe());
        } catch (Throwable t) {
            sState = "install failed";
            sLastError = String.valueOf(t);
            Log.w(TAG, "install failed: " + t);
            note("install failed: " + t);
        }
    }

    private static boolean handle(int code, Parcel data, Parcel reply) {
        try {
            if (!allowed()) {
                Log.w(TAG, "caller uid " + Binder.getCallingUid() + " denied on code " + code);
                note("caller uid " + Binder.getCallingUid() + " denied on code " + code);
                if (reply != null) {
                    reply.writeNoException();
                }
                return true;
            }
            switch (code) {
                case T_GET_SURFACE: {
                    final Surface surface = sSurface;
                    final boolean present = surface != null && surface.isValid();
                    if (reply != null) {
                        reply.writeNoException();
                        reply.writeInt(present ? 1 : 0);
                        reply.writeInt(sWidth);
                        reply.writeInt(sHeight);
                        reply.writeInt(sDpi);
                        reply.writeParcelable(present ? surface : null, 0);
                        reply.writeString(sSessionId);
                    }
                    Log.i(TAG, "GET_SURFACE -> present=" + present + ' ' + sWidth + 'x' + sHeight
                            + " dpi=" + sDpi + " session=" + sSessionId);
                    return true;
                }
                case T_GET_CAST_CALLBACK: {
                    if (reply != null) {
                        reply.writeNoException();
                        reply.writeStrongBinder(client().castCallback());
                    }
                    Log.i(TAG, "GET_CAST_CALLBACK -> " + client().castCallback());
                    return true;
                }
                case T_GET_AUDIO_CALLBACK: {
                    if (reply != null) {
                        reply.writeNoException();
                        reply.writeStrongBinder(client().audioCallback());
                    }
                    Log.i(TAG, "GET_AUDIO_CALLBACK -> " + client().audioCallback());
                    return true;
                }
                case T_PREPARE_RESULT: {
                    final int ok = data == null ? 0 : data.readInt();
                    final String detail = data == null ? "" : String.valueOf(data.readString());
                    sPrepareDetail = detail;
                    /* "accepted" is not "prepared": the AIDL transaction succeeds even when CarWith
                     * drops the CastConfig (mStateMachine == null), so the honest state here is
                     * "prepareCast accepted".  onCastPrepared moves it to "cast prepared". */
                    sState = ok != 0 ? "prepareCast accepted" : "prepare failed";
                    if (ok == 0) {
                        sLastError = detail;
                    }
                    Log.i(TAG, "PREPARE_RESULT ok=" + ok + " detail=" + detail);
                    note("prepareCast ok=" + ok + " " + detail);
                    if (reply != null) {
                        reply.writeNoException();
                    }
                    return true;
                }
                case T_CAST_STOPPED: {
                    final String detail = data == null ? "" : String.valueOf(data.readString());
                    Log.i(TAG, "CAST_STOPPED " + detail);
                    note("stopped: " + detail);
                    sState = "stopped";
                    client().onCastStopped(detail);
                    if (reply != null) {
                        reply.writeNoException();
                    }
                    return true;
                }
                case T_STATUS: {
                    if (reply != null) {
                        reply.writeNoException();
                        reply.writeString(describe());
                    }
                    return true;
                }
                case T_ENSURE_CARWITH: {
                    /* The component asks for this immediately before its own prepareCast.  The
                     * answer is advisory: `prepareCast` still has to be confirmed by
                     * onCastPrepared, which is exactly what T_CAST_STATE reports. */
                    final String name = data == null ? null : data.readString();
                    final CarwithLauncher.Outcome outcome = ensureCarwith(name);
                    sEnsureDetail = outcome.describe();
                    if (reply != null) {
                        reply.writeNoException();
                        reply.writeInt(outcome.started ? 1 : 0);
                        reply.writeString(outcome.describe());
                    }
                    return true;
                }
                case T_CAST_STATE: {
                    final CarwithCastClient client = client();
                    if (reply != null) {
                        reply.writeNoException();
                        reply.writeInt(client.prepared() ? 1 : 0);
                        reply.writeInt(client.displayId());
                        /* `onServiceReady` seen: the component uses this to stop re-sending the
                         * CastConfig once CarWith's CarLife module is up (a duplicate config would
                         * create a second virtual display). */
                        reply.writeInt(client.castCallback().serviceReady() ? 1 : 0);
                        reply.writeString(sState);
                    }
                    return true;
                }
                default:
                    Log.i(TAG, "unhandled code " + code);
                    return false;
            }
        } catch (Throwable t) {
            Log.w(TAG, "transact " + code + " failed: " + t);
            return false;
        }
    }

    /**
     * Only the CarWith component (resolved from the package manager, so a renamed clone cannot
     * impersonate it) and root/system may use the bridge.
     */
    private static boolean allowed() {
        final int uid = Binder.getCallingUid();
        if (uid == UID_ROOT || uid == UID_SYSTEM) {
            return true;
        }
        final Context context = sContext;
        if (context == null) {
            return false;
        }
        try {
            final PackageManager pm = context.getPackageManager();
            final int componentUid = pm.getApplicationInfo(CarwithContract.COMPONENT_PACKAGE, 0).uid;
            if (uid == componentUid) {
                return true;
            }
        } catch (Throwable t) {
            Log.w(TAG, "cannot resolve " + CarwithContract.COMPONENT_PACKAGE + ": " + t);
        }
        return false;
    }

    /* ------------------------------------------------------------------ state */

    /** Called by {@link CarwithCastClient} when the encoder input surface is ready. */
    static synchronized void publishSurface(Surface surface, int width, int height, int dpi,
                                            String sessionId) {
        sSurface = surface;
        sWidth = width;
        sHeight = height;
        sDpi = dpi > 0 ? dpi : 160;
        sSessionId = sessionId == null ? "" : sessionId;
        sGeneration++;
        sState = "surface published";
        sLastError = "";
        sPrepareDetail = "";
        Log.i(TAG, "surface published session=" + sSessionId + ' ' + width + 'x' + height
                + " dpi=" + sDpi + " generation=" + sGeneration);
        note("surface published session=" + sSessionId + ' ' + width + 'x' + height);
    }

    /* -------------------------------------------------- CarWith's own CarlinkService (root) */

    /**
     * Starts `com.miui.carlink/com.miui.carlink.castfwk.CarlinkService` through CarWith's own entry
     * so that `CarlinkStateMachine` exists before the component's `prepareCast` arrives.
     *
     * <p>Runs the real factory command as root, bounded by {@link CarwithLauncher#DEFAULT_ATTEMPTS}
     * rounds, and never issues a start inside {@link #BOOTSTRAP_COOLDOWN_MS} of the previous one: on
     * 217 a `startType=4` start makes CarWith pull up its `CastingActivity` *and* our component, and
     * when the connection mode is not set (no user-initiated CarLife connect yet) CarWith finishes
     * the activity and calls `stopService(CarlinkService)` about a second later — re-issuing the
     * start in a tight loop would just spin that aborted flow.  Inside the cooldown it probes only.
     *
     * <p>This is deliberately best-effort: the authoritative success signal is `onCastPrepared`
     * (reported through {@link #T_CAST_STATE}), and the component keeps its own bounded replay going
     * while this one is unconfirmed.
     */
    public static synchronized CarwithLauncher.Outcome ensureCarwith(String wirelessTypeName) {
        final long now = android.os.SystemClock.elapsedRealtime();
        final long sinceLast = sLastBootstrapAtMs == 0L
                ? Long.MAX_VALUE : now - sLastBootstrapAtMs;
        final CarwithLauncher.Outcome outcome;
        if (sinceLast < BOOTSTRAP_COOLDOWN_MS) {
            final CarwithLauncher.Outcome probed =
                    CarwithLauncher.probe(CarwithLauncher.rootShell());
            outcome = new CarwithLauncher.Outcome(probed.started, 0, probed.probes,
                    "bootstrap cooldown (" + sinceLast + "ms since the last start) — "
                            + probed.detail, -1);
        } else {
            outcome = CarwithLauncher.ensure(
                    CarwithLauncher.rootShell(), CarwithLauncher.systemClock(),
                    CarwithLauncher.DEFAULT_ATTEMPTS, wirelessTypeName);
            sLastBootstrapAtMs = android.os.SystemClock.elapsedRealtime();
        }
        Log.i(TAG, "ENSURE_CARWITH " + CarwithLauncher.logCommand(wirelessTypeName) + " -> "
                + outcome.describe());
        note("ensure carwith: " + outcome.describe());
        return outcome;
    }

    /** One-line state for the component's replay: prepared/displayId/ready/state. */
    public static String castState() {
        final CarwithCastClient client = client();
        return "prepared=" + client.prepared() + " displayId=" + client.displayId()
                + " preparedCount=" + client.preparedCount()
                + " ready=" + client.castCallback().serviceReady() + " state=" + sState;
    }

    /** The last T_ENSURE_CARWITH answer, for `carwith status`. */
    public static String ensureDetail() {
        return sEnsureDetail;
    }

    /* ------------------------------------------------------------------ state */

    /** The cast is over: nothing may keep rendering into a surface whose encoder is gone. */
    static synchronized void withdrawSurface(String reason) {
        if (sSurface != null) {
            Log.i(TAG, "surface withdrawn: " + reason);
            note("surface withdrawn: " + reason);
        }
        sSurface = null;
        sSessionId = "";
        sState = "withdrawn: " + reason;
    }

    static void setPrepareDetail(String detail) {
        sPrepareDetail = detail == null ? "" : detail;
    }

    static void setError(String error) {
        sLastError = error == null ? "" : error;
    }

    static void setState(String state) {
        sState = state == null ? "" : state;
    }

    public static Surface surface() {
        return sSurface;
    }

    public static int width() {
        return sWidth;
    }

    public static int height() {
        return sHeight;
    }

    public static int dpi() {
        return sDpi;
    }

    public static String sessionId() {
        return sSessionId;
    }

    public static long generation() {
        return sGeneration;
    }

    public static IBinder castCallback() {
        return client().castCallback();
    }

    public static IBinder audioCallback() {
        return client().audioCallback();
    }

    public static String state() {
        return sState;
    }

    public static String lastError() {
        return sLastError;
    }

    public static String describe() {
        return "carwith bridge=" + (sInstalled ? "registered" : "absent") + " state=" + sState
                + " session=" + (sSessionId.isEmpty() ? "-" : sSessionId)
                + " size=" + sWidth + 'x' + sHeight + " dpi=" + sDpi
                + " surface=" + (sSurface != null && sSurface.isValid() ? "valid" : "none")
                + " generation=" + sGeneration
                + (sPrepareDetail.isEmpty() ? "" : " prepare=" + sPrepareDetail)
                + (sEnsureDetail.isEmpty() ? "" : " ensure=[" + sEnsureDetail + ']')
                + (sLastError.isEmpty() ? "" : " last_error=" + sLastError)
                + " | " + client().describe();
    }

    /** The one client instance this daemon owns (one CarWith cast at a time). */
    public static synchronized CarwithCastClient client() {
        if (sClient == null) {
            sClient = CarwithCastClient.get();
        }
        return sClient;
    }

    /** Also drop a line in a file: logcat rotates fast on this ROM and swallowed earlier evidence. */
    static void note(String text) {
        try {
            final java.io.FileWriter w = new java.io.FileWriter(
                    "/data/local/tmp/okcar-carwith-bridge.status", true);
            w.write(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date())
                    + ' ' + text + '\n');
            w.close();
        } catch (Throwable ignored) {
            /* the status file is a convenience, never a requirement */
        }
    }
}
