/*
 * OkCarLink — the daemon-side owner of one CarWith cast session (2026-10-07).
 *
 * It is the single place that ties the four measured halves of the CarWith I/O together:
 *
 *   VIDEO  it implements {@link MirrorVideoSource.ExternalDisplay}.  In CarWith mode the AirPlay
 *          video sender does NOT build a mirror display; it hands its MediaCodec **input surface**
 *          here, we publish it through {@link CarwithBridge}, the component puts it in a
 *          `CastConfig` and CarWith renders its virtual display into it.  Zero copy: what the car
 *          sees is CarWith's own composition, encoded by the encoder the AirPlay path already owns.
 *   INPUT  {@link #injectTouch}/{@link #injectKey} feed the binders CarWith delivered through our
 *          ICarLifeCastCallback (`addTouchListener`/`addKeyListener`).  Coordinates are scaled with
 *          CarWith's own rule ({@link CarwithContract#coordinate}).
 *   AUDIO  CarWith's mixed system audio arrives on our ISystemAudioCallback and is offered to the
 *          attached {@link SystemAudioSink}; the microphone path is the reverse
 *          ({@link #feedMicrophone} → IRemoteAudioListener.onReceiveAudioStream).
 *   STATE  every callback is logged and mirrored into {@link CarwithBridge}'s state so
 *          `carwith status` (and the daemon log) can explain a session that never produced a frame.
 *
 * Failure policy: a call without the needed binder is refused with a log line — never "accepted"
 * silently, because the whole point of this class is to make "CarWith's picture is on the wire"
 * verifiable rather than assumed.
 */
package com.okcar.link.carkit;

import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;

import com.okcar.link.media.MirrorVideoSource;

import java.util.concurrent.atomic.AtomicLong;

public final class CarwithCastClient implements MirrorVideoSource.ExternalDisplay,
        CarwithCastCallback.Events, CarwithAudioCallback.Sink,
        com.okcar.link.input.InputInjector.Router {

    private static final String TAG = "okcar-carwith";

    /**
     * Bitrate for the locally started encoder ({@link #armLocal}).  The AirPlay path derives its own
     * from the frame rate; this one is only here to keep the encoder cheap while nothing consumes
     * the bitstream yet.
     */
    private static final int LOCAL_BITRATE_BPS = 8_000_000;

    /** Where CarWith's mixed system audio goes (the AirPlay audio path installs one). */
    public interface SystemAudioSink {
        /**
         * @param category 1 = media, 2 = navigation/TTS, 0 = unknown
         * @return true when the frame was taken
         */
        boolean onSystemAudio(byte[] pcm, int rate, int channels, int encoding, int category);

        void onAudioState(int state, int category);
    }

    private static final CarwithCastClient INSTANCE = new CarwithCastClient();

    private final CarwithCastCallback mCastCallback = new CarwithCastCallback(this);
    private final CarwithAudioCallback mAudioCallback = new CarwithAudioCallback(this);
    private final AtomicLong mTouches = new AtomicLong();
    private final AtomicLong mKeys = new AtomicLong();
    private final AtomicLong mMicFrames = new AtomicLong();
    private final AtomicLong mMicBytes = new AtomicLong();

    private volatile boolean mArmed;
    private volatile SystemAudioSink mSystemAudioSink;
    private volatile String mSessionId = "";
    private volatile String mLastError = "";
    private volatile int mCastWidth;
    private volatile int mCastHeight;
    private volatile int mCastDpi = 160;
    private volatile long mArmedAtMs;
    private volatile long mPreparedAtMs;
    /** The display CarWith's cast framework reported through `onCastPrepared`; -1 until it arrives. */
    private volatile int mDisplayId = -1;
    /** How many `onCastPrepared` deliveries this session saw (a replay may produce more than one). */
    private volatile int mPreparedCount;
    private volatile int mFrameRateHint = -1;
    /** Gesture bookkeeping for {@link #routeTouch}: CarWith needs DOWN before MOVE/UP. */
    private volatile boolean mTouchDown;
    /**
     * The encoder {@link #armLocal} starts locally so a surface exists WITHOUT an AirPlay session
     * (2026-10-08).  Null in the plain {@link #arm} path, where the surface is published by the
     * AirPlay session's own encoder.
     */
    private volatile MirrorVideoSource mLocalSource;
    /** Only needed to build {@link #mLocalSource}; set from the daemon before {@link #armLocal}. */
    private volatile android.content.Context mContext;
    /** Frames the local encoder produced — proof that CarWith really renders into our surface. */
    private final AtomicLong mLocalFrames = new AtomicLong();
    /** The display the local source mirrors, or -1 when the picture is CarWith's own rendering. */
    private volatile int mLocalMirrorDisplay = -1;
    /** Lab knob: virtual-display flags for the mirror (-1 = default). */
    private volatile int mLocalMirrorFlags = -1;

    private CarwithCastClient() {
    }

    /** The one client instance this daemon owns; {@link CarwithBridge} is the usual entry point. */
    public static CarwithCastClient get() {
        return INSTANCE;
    }

    /* ------------------------------------------------------------------ arming */

    /**
     * Switches the daemon into CarWith source mode.
     *
     * <p>The next video encoder start hands its input surface here instead of building the mirror
     * display.  Turning it on while a stream is already encoding is refused: that encoder's display
     * belongs to the mirroring session and cannot be re-pointed.
     */
    public synchronized String arm(int width, int height, int fps) {
        if (mArmed) {
            return "already armed (" + describe() + ')';
        }
        mArmed = true;
        mArmedAtMs = SystemClock.elapsedRealtime();
        mPreparedAtMs = 0L;
        mDisplayId = -1;
        mPreparedCount = 0;
        mCastWidth = width;
        mCastHeight = height;
        mFrameRateHint = fps;
        mLastError = "";
        mSessionId = "carwith-" + Long.toHexString(System.currentTimeMillis());
        CarwithBridge.setState("armed, waiting for the encoder input surface");
        com.okcar.link.input.InputInjector.setRouter(this);
        Log.i(TAG, "armed: session=" + mSessionId + " requested=" + width + 'x' + height
                + '@' + fps);
        CarwithBridge.note("armed session=" + mSessionId + " " + width + 'x' + height + '@' + fps);
        return "armed " + mSessionId;
    }

    /** The daemon's context; only {@link #armLocal} needs it (to build the local encoder). */
    public void setContext(android.content.Context context) {
        mContext = context;
    }

    /** Lab knob for {@link #armLocalMirror}: the virtual-display flags to use (-1 = default). */
    public void setLocalMirrorFlags(int flags) {
        mLocalMirrorFlags = flags;
    }

    /**
     * Arms CarWith mode AND publishes an encoder input surface right away, without waiting for an
     * AirPlay session (2026-10-08).
     *
     * <p>Why: the wired ICCOA (AOA) attach makes CarWith enter a real cast session on its own, but
     * the {@code CastConfig} we hand it is only routed to {@code CarlinkStateMachine.l1()} while
     * that session is alive — and the plain {@link #arm} path only publishes a surface once the
     * video sender of an AirPlay session starts, whose own session tears the ICCOA one down again.
     * Starting the encoder locally decouples the two: CarWith renders into this surface while no
     * AirPlay session exists at all.  The frames are simply counted here; an AirPlay session started
     * later can adopt {@link #localSource()} and send them.
     *
     * @return a one-line result: the encoder's name on success, the reason otherwise
     */
    public synchronized String armLocal(int width, int height, int fps) {
        return armLocalInternal(width, height, fps, -1);
    }

    /**
     * Like {@link #armLocal}, but the picture is taken by MIRRORING an existing display instead of
     * CarWith rendering into our surface (2026-10-08).  This is the route that survives the measured
     * facts: CarWith accepts the CarLife-slot {@code prepareCast} but never routes it into
     * {@code CarlinkStateMachine} on the wired ICCOA path, and its cast display has no
     * SurfaceFlinger capture token — while {@code VirtualDisplayConfig.setDisplayIdToMirror} lets
     * uid 0 mirror that display straight into the encoder.
     *
     * @param sourceDisplayId the display to mirror (CarWith's cast display, e.g. 14)
     */
    public synchronized String armLocalMirror(int width, int height, int fps, int sourceDisplayId) {
        return armLocalInternal(width, height, fps, sourceDisplayId);
    }

    private synchronized String armLocalInternal(int width, int height, int fps, int sourceDisplayId) {
        final String armed = arm(width, height, fps);
        if (mLocalSource != null) {
            return armed + " — local surface already published (" + localDescribe() + ')';
        }
        if (mContext == null) {
            return "cannot publish a local surface: no context (daemon must call setContext)";
        }
        int resolved = sourceDisplayId;
        if (resolved == com.okcar.link.port.DisplayMirror.AUTO) {
            resolved = com.okcar.link.port.DisplayMirror.findCarwithCastDisplay(mContext);
            if (resolved < 0) {
                return "carwith mirror auto: CarWith's cast display not found right now — start the"
                        + " wired ICCOA session first (or pass the display id explicitly)";
            }
        }
        final MirrorVideoSource video = new MirrorVideoSource(mContext);
        if (resolved >= 0) {
            video.setMirrorSourceDisplay(resolved);
            if (mLocalMirrorFlags >= 0) {
                video.setMirrorFlags(mLocalMirrorFlags);
            }
        } else {
            video.useExternalDisplay(this);
        }
        video.setFrameSink(new MirrorVideoSource.FrameSink() {
            @Override
            public void onFrame(byte[] data, int flags, long presentationTimeUs) {
                mLocalFrames.incrementAndGet();
            }
        });
        video.configure(width, height, fps, LOCAL_BITRATE_BPS);
        if (!video.start(null)) {
            final String why = video.stats().error;
            disarm("local encoder did not start");
            return "local encoder did not start: " + (why == null || why.isEmpty() ? "unknown" : why);
        }
        mLocalSource = video;
        mLocalMirrorDisplay = sourceDisplayId;
        if (resolved >= 0) {
            /* The car's touches must land on the UI the driver is looking at — that is CarWith's own
             * cast display, not the mirror display we just created (a mirror has no windows of its
             * own, so a tap injected there would go nowhere).  InputInjector reads the registered
             * target at injection time, so this takes effect without restarting anything. */
            final int[] size = displaySize(resolved);
            final int touchWidth = size[0] > 0 ? size[0] : mCastWidth;
            final int touchHeight = size[1] > 0 ? size[1] : mCastHeight;
            com.okcar.link.port.Ports.setTouchDisplay(resolved, touchWidth, touchHeight);
            CarwithBridge.note("touch target -> display " + resolved
                    + " (" + touchWidth + 'x' + touchHeight + ')');
        }
        final String how = sourceDisplayId > 0 ? "mirroring display " + sourceDisplayId
                : "surface handed to CarWith";
        CarwithBridge.note("local surface armed " + width + 'x' + height + '@' + fps
                + " codec=" + video.stats().codecName + " (" + how + ", no AirPlay session needed)");
        CarwithBridge.setState("local surface published " + width + 'x' + height + " (" + how + ')');
        Log.i(TAG, "local surface armed " + width + 'x' + height + '@' + fps
                + " codec=" + video.stats().codecName + " (" + how + ')');
        return armed + " + local surface " + width + 'x' + height + '@' + fps
                + " codec=" + video.stats().codecName + " (" + how + ')';
    }

    /** The locally started encoder (see {@link #armLocal}); null when only {@link #arm} ran. */
    public MirrorVideoSource localSource() {
        return mLocalSource;
    }

    /**
     * The display {@link #armLocalMirror} is mirroring, or -1 when the picture is CarWith's own
     * rendering into our surface.  A later AirPlay session uses this to mirror the same display.
     */
    public int mirrorDisplay() {
        return mLocalMirrorDisplay;
    }

    /** Encoded frames since {@link #armLocal} — nonzero means CarWith is rendering into our surface. */
    public long localFrames() {
        return mLocalFrames.get();
    }

    /** True when a mirror source is armed: an explicit display id or {@code DisplayMirror.AUTO}. */
    private boolean mirrorConfigured() {
        final int mirror = mLocalMirrorDisplay;
        return mirror >= 0 || mirror == com.okcar.link.port.DisplayMirror.AUTO;
    }

    /** The pixel size of {@code displayId}, or {0,0} when it cannot be resolved. */
    private int[] displaySize(int displayId) {
        try {
            final android.hardware.display.DisplayManager manager =
                    (android.hardware.display.DisplayManager) mContext
                            .getSystemService(android.content.Context.DISPLAY_SERVICE);
            final android.view.Display display = manager == null ? null
                    : manager.getDisplay(displayId);
            if (display != null) {
                final android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
                display.getRealMetrics(metrics);
                if (metrics.widthPixels > 0 && metrics.heightPixels > 0) {
                    return new int[] { metrics.widthPixels, metrics.heightPixels };
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "display size for " + displayId + " failed: " + t);
        }
        return new int[] { 0, 0 };
    }

    private String localDescribe() {
        final MirrorVideoSource video = mLocalSource;
        if (video == null) {
            return "local=none";
        }
        final MirrorVideoSource.Stats s = video.stats();
        return "local=" + s.codecName + " " + s.width + 'x' + s.height + " frames=" + s.frames
                + " key=" + s.keyFrames + " bytes=" + s.bytes + " running=" + video.isRunning()
                + (mirrorConfigured() ? " mirrorDisplay="
                        + (mLocalMirrorDisplay == com.okcar.link.port.DisplayMirror.AUTO
                                ? "auto" : String.valueOf(mLocalMirrorDisplay)) : "")
                + (mirrorConfigured() && mLocalMirrorFlags >= 0
                        ? " mirrorFlags=" + mLocalMirrorFlags : "")
                + (s.error == null || s.error.isEmpty() ? "" : " error=" + s.error);
    }

    public synchronized String disarm(String reason) {
        if (!mArmed) {
            return "not armed";
        }
        mArmed = false;
        com.okcar.link.input.InputInjector.setRouter(null);
        /* The locally started encoder is ours (the AirPlay path's is not): stop it, or the decoder
         * stays configured and the surface CarWith keeps rendering into goes nowhere. */
        final MirrorVideoSource local = mLocalSource;
        mLocalSource = null;
        if (mirrorConfigured()) {
            /* The car's touches had been pointed at the mirrored display; hand the target back so a
             * later app display registers its own. */
            com.okcar.link.port.Ports.setTouchDisplay(-1, 0, 0);
        }
        mLocalMirrorDisplay = -1;
        if (local != null) {
            try {
                local.stop();
            } catch (Throwable t) {
                Log.w(TAG, "stopping the local encoder failed: " + t);
            }
        }
        CarwithBridge.withdrawSurface(reason == null ? "disarmed" : reason);
        mSystemAudioSink = null;
        CarwithBridge.setState("disarmed");
        Log.i(TAG, "disarmed: " + reason);
        CarwithBridge.note("disarmed: " + reason);
        return "disarmed";
    }

    public boolean armed() {
        return mArmed;
    }

    /** The sink the video sender installs with `MirrorVideoSource.useExternalDisplay`. */
    public MirrorVideoSource.ExternalDisplay externalDisplay() {
        return this;
    }

    public void setSystemAudioSink(SystemAudioSink sink) {
        mSystemAudioSink = sink;
    }

    public CarwithCastCallback castCallback() {
        return mCastCallback;
    }

    public CarwithAudioCallback audioCallback() {
        return mAudioCallback;
    }

    /* ------------------------------------------------------------------ video */

    /**
     * The encoder's input surface, i.e. the surface CarWith must render into.
     *
     * @return true when the surface was published; false when nobody asked for CarWith mode, in
     *     which case the caller (MirrorVideoSource) falls back to building the mirror display
     */
    @Override
    public boolean onInputSurface(Surface surface, int width, int height) {
        if (!mArmed || surface == null || !surface.isValid()) {
            return false;
        }
        mCastWidth = width;
        mCastHeight = height;
        CarwithBridge.publishSurface(surface, width, height, mCastDpi, mSessionId);
        return true;
    }

    /* ------------------------------------------------------- CarwithCastCallback.Events */

    @Override
    public void onServiceReady() {
        CarwithBridge.setState("CarWith service ready");
        Log.i(TAG, "CarWith onServiceReady");
    }

    @Override
    public void onCastPrepared(int displayId) {
        mPreparedAtMs = SystemClock.elapsedRealtime();
        mDisplayId = displayId;
        mPreparedCount++;
        CarwithBridge.setState("cast prepared displayId=" + displayId);
        Log.i(TAG, "CarWith cast prepared on display " + displayId + " after "
                + (mPreparedAtMs - mArmedAtMs) + "ms (delivery #" + mPreparedCount + ')');
        CarwithBridge.note("cast prepared displayId=" + displayId);
    }

    @Override
    public void onServiceDestroy() {
        CarwithBridge.setState("CarWith service destroyed");
        Log.i(TAG, "CarWith onServiceDestroy — the cast is over");
        CarwithBridge.note("CarWith service destroyed");
    }

    @Override
    public void onAudioState(int state) {
        final SystemAudioSink sink = mSystemAudioSink;
        if (sink != null) {
            sink.onAudioState(state, 0);
        }
    }

    @Override
    public void onMusicInfo(String title, String artist, String album, long durationMs, int state,
                            int source) {
        Log.i(TAG, "MusicInfo strings=[" + title + ',' + artist + ',' + album + "] duration="
                + durationMs + " state=" + state + " source=" + source);
    }

    @Override
    public void onMusicProgress(long positionMs) {
        Log.i(TAG, "music progress=" + positionMs + "ms");
    }

    @Override
    public void onInjectorsChanged(IBinder key, IBinder touch, IBinder audio) {
        Log.i(TAG, "injectors now key=" + (key != null) + " touch=" + (touch != null)
                + " audio=" + (audio != null));
        CarwithBridge.note("injectors key=" + (key != null) + " touch=" + (touch != null)
                + " audio=" + (audio != null));
    }

    @Override
    public void onFrameRateHint(int fps) {
        if (fps > 0) {
            mFrameRateHint = fps;
            Log.i(TAG, "CarWith asked for " + fps + "fps capture");
        }
    }

    /* ------------------------------------------------------- CarwithAudioCallback.Sink */

    @Override
    public boolean onSystemAudio(byte[] pcm, int rate, int channels, int encoding, int category) {
        final SystemAudioSink sink = mSystemAudioSink;
        if (sink == null) {
            return false;
        }
        return sink.onSystemAudio(pcm, rate, channels, encoding, category);
    }

    @Override
    public void onAudioState(int state, int category) {
        final SystemAudioSink sink = mSystemAudioSink;
        if (sink != null) {
            sink.onAudioState(state, category);
        }
    }

    /* ------------------------------------------------------------------ input */

    /**
     * A touch from the accessory, in the accessory's own coordinate space.
     *
     * @param action {@link MotionEvent#ACTION_DOWN}, {@link MotionEvent#ACTION_UP},
     *     {@link MotionEvent#ACTION_MOVE} or {@link MotionEvent#ACTION_CANCEL}
     * @return true when CarWith took the event
     */
    public boolean injectTouch(int action, int x, int y, int sourceWidth, int sourceHeight) {
        final IBinder listener = mCastCallback.touchListener();
        if (listener == null) {
            mLastError = "no touch listener from CarWith (addTouchListener not delivered yet)";
            return false;
        }
        final int targetWidth = mCastWidth > 0 ? mCastWidth : sourceWidth;
        final int targetHeight = mCastHeight > 0 ? mCastHeight : sourceHeight;
        final float scaledX = CarwithContract.coordinate(x, sourceWidth, targetWidth);
        final float scaledY = CarwithContract.coordinate(y, sourceHeight, targetHeight);
        final MotionEvent event = CarwithInput.touchEvent(action, scaledX, scaledY);
        try {
            final boolean ok = CarwithInput.touch(listener, event);
            if (ok) {
                mTouches.incrementAndGet();
            } else {
                mLastError = "CarWith refused touch action=" + action;
            }
            return ok;
        } finally {
            event.recycle();
        }
    }

    /** A key by CarWith's own name (上一曲/下一曲/播放/暂停/语音/通话 Hook Switch/通话 Drop). */
    public boolean injectKey(String name, boolean down) {
        final IBinder listener = mCastCallback.keyListener();
        if (listener == null) {
            mLastError = "no key listener from CarWith (addKeyListener not delivered yet)";
            return false;
        }
        final KeyEvent event = CarwithInput.keyEvent(name, down);
        if (event == null) {
            Log.w(TAG, "key unmapped name=" + name);
            return false;
        }
        try {
            final boolean ok = CarwithInput.key(listener, event);
            if (ok) {
                mKeys.incrementAndGet();
            } else {
                mLastError = "CarWith refused key " + name;
            }
            return ok;
        } finally {
            event.recycle();
        }
    }

    /**
     * Microphone PCM for CarWith's voice pipeline (PCM16LE 16 kHz mono by default).
     *
     * @return true when CarWith took the frame
     */
    public boolean feedMicrophone(byte[] pcm) {
        final IBinder listener = mCastCallback.audioListener();
        if (listener == null) {
            mLastError = "no audio listener from CarWith (addAudioListener not delivered yet)";
            return false;
        }
        final boolean ok = CarwithInput.microphone(listener, pcm, CarwithContract.MIC_RATE,
                CarwithContract.MIC_CHANNELS, CarwithContract.MIC_ENCODING);
        if (ok) {
            mMicFrames.incrementAndGet();
            mMicBytes.addAndGet(pcm.length);
        } else {
            mLastError = "CarWith refused a microphone frame (" + pcm.length + "B)";
        }
        return ok;
    }

    /**
     * CarWith's own cast is gone (stopCast from CarWith, or the component said so).
     *
     * <p>The published surface is deliberately NOT withdrawn here (2026-10-08): it is the AirPlay
     * encoder's input surface and belongs to this daemon's armed session, not to one CarWith cast
     * attempt.  Withdrawing it whenever the CarLife component went away (a CarWith restart, a
     * force-stop) left the next `prepareCast` with "no surface yet" and no way to get one back
     * without a whole new AirPlay session.  {@link #disarm} still withdraws it when the session
     * itself ends, which is the only event that actually invalidates the surface.
     */
    void onCastStopped(String detail) {
        mPreparedAtMs = 0L;
        mDisplayId = -1;
        mPreparedCount = 0;
        CarwithBridge.setState("cast stopped: " + detail);
        CarwithBridge.note("cast stopped (surface kept): " + detail);
    }

    /* ------------------------------------------------- InputInjector.Router (CarWith mode) */

    /**
     * A car touch arrives here first while the daemon is in CarWith mode.
     *
     * <p>It is reported in the accessory's own coordinate space.  CarWith's display was created at
     * the CastConfig size, so the point is scaled by {@link CarwithContract#coordinate} before the
     * IRemoteTouchListener transaction; CarWith then re-maps it into its own display with the scale
     * it measured (`CarlifeUibc.coordinateMapping`).
     *
     * @return true when CarWith mode owns this report.  It is true even when CarWith refuses the
     *     event: in CarWith mode the phone's own display is not what the car is looking at, so
     *     falling back to local injection would move a finger on a screen nobody is watching.
     */
    @Override
    public boolean routeTouch(com.okcar.link.hid.HidReportDecoder.Report report) {
        if (!mArmed || report == null || report.type != com.okcar.link.hid.HidReportDecoder.TYPE_TOUCH) {
            return false;
        }
        final int action;
        if (report.down) {
            action = mTouchDown ? MotionEvent.ACTION_MOVE : MotionEvent.ACTION_DOWN;
            mTouchDown = true;
        } else {
            if (!mTouchDown) {
                /* A stray UP with no DOWN would be refused by CarWith; swallow it so it does not
                 * reach the phone's own display either. */
                return true;
            }
            action = MotionEvent.ACTION_UP;
            mTouchDown = false;
        }
        final int sourceWidth = report.sourceWidth > 0 ? report.sourceWidth : mCastWidth;
        final int sourceHeight = report.sourceHeight > 0 ? report.sourceHeight : mCastHeight;
        final boolean ok = injectTouch(action, report.x, report.y, sourceWidth, sourceHeight);
        if (!ok) {
            Log.w(TAG, "CarWith refused " + action + " at " + report.x + ',' + report.y
                    + " (source " + sourceWidth + 'x' + sourceHeight + ')');
        }
        return true;
    }

    /**
     * A media/phone key from the accessory.
     *
     * @return true only for the keys CarWith owns (transport, voice, call).  Volume and home stay
     *     local — the phone's own volume UI is still what the driver hears.
     */
    @Override
    public boolean routeKey(int keyCode) {
        if (!mArmed) {
            return false;
        }
        final String name = CarwithContract.nameForKeyCode(keyCode);
        if (name == null) {
            return false;
        }
        /* CarWith's CarlifeHw dispatches the press on ACTION_DOWN and derives the release from the
         * code (语音) or synthesises the pair itself, so a media key is one call here. */
        final boolean ok = injectKey(name, true);
        if (!ok) {
            Log.w(TAG, "CarWith refused key " + name + " (android code " + keyCode + ')');
        }
        return true;
    }

    /* ------------------------------------------------------------------ status */

    public String sessionId() {
        return mSessionId;
    }

    public String lastError() {
        return mLastError;
    }

    public int castWidth() {
        return mCastWidth;
    }

    public int castHeight() {
        return mCastHeight;
    }

    public int frameRateHint() {
        return mFrameRateHint;
    }

    /**
     * The display CarWith reported through `onCastPrepared`, or -1 before it arrives.
     *
     * <p>This is the signal the component's `prepareCast` replay waits for: CarWith only calls it
     * once the CastConfig reached `CarlinkStateMachine` and the virtual display is live.
     */
    public int displayId() {
        return mDisplayId;
    }

    /** True once this session saw at least one `onCastPrepared`. */
    public boolean prepared() {
        return mPreparedCount > 0;
    }

    /** How many `onCastPrepared` deliveries this session saw. */
    public int preparedCount() {
        return mPreparedCount;
    }

    public long touches() {
        return mTouches.get();
    }

    public long keys() {
        return mKeys.get();
    }

    public long micFrames() {
        return mMicFrames.get();
    }

    public long micBytes() {
        return mMicBytes.get();
    }

    /** One line, always non-null; this is what `carwith status` prints. */
    public String describe() {
        return "carwith client armed=" + mArmed + " session=" + (mSessionId.isEmpty() ? "-"
                : mSessionId) + " cast=" + mCastWidth + 'x' + mCastHeight
                + (mFrameRateHint > 0 ? "@" + mFrameRateHint : "")
                + " touches=" + mTouches.get() + " keys=" + mKeys.get()
                + " micFrames=" + mMicFrames.get() + " micBytes=" + mMicBytes.get()
                + " systemAudio=" + (mSystemAudioSink != null ? "sink" : "none")
                + " prepared=" + prepared() + " displayId=" + mDisplayId
                + " preparedCount=" + mPreparedCount
                + (mPreparedAtMs > 0 ? " preparedIn=" + (mPreparedAtMs - mArmedAtMs) + "ms" : "")
                + " callback[" + mCastCallback.describe() + ']'
                + " audio[" + mAudioCallback.describe() + ']'
                + ' ' + localDescribe()
                + (mLastError.isEmpty() ? "" : " last_error=" + mLastError);
    }
}
