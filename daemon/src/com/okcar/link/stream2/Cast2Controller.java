/*
 * OkcarOS link stack — display 2 (`cast2`): the whole second-screen pipeline in one place.
 *
 * One controller per daemon process; everything it owns is OFF until the head-unit-side program
 * asks for it (a FIFO command or the HTTP control API — see {@link Cast2HttpControl} for the
 * endpoint list and the equivalent `cast2`/`rtsp` FIFO commands).
 *
 * Pipeline:  app --(am start --display N)--> [display 2] --(encoder input surface)-->
 *            MediaCodec video/avc --> FrameHub --> Rtsp2Server --> rtsp://<ip>:8554/cast2
 *
 * Failure policy (no fallback): every precondition is checked and its failure is reported.  There is
 * no silent fallback to mirroring, no container/codec substitution, and no display rebuild to
 * "make it fit".  If the app cannot be placed on display 2 the whole start is rolled back and the
 * reason is thrown; a half-up pipeline would look like a working stream to the caller while
 * showing nothing.
 *
 * Touch: display 2 is deliberately NOT registered as the car's touch space.  It is a
 * one-directional video surface; see {@link #describe()}.
 */
package com.okcar.link.stream2;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class Cast2Controller {

    private static final String TAG = "okcar-cast2";
    /** How many encoded access units the hub keeps for the live edge. */
    private static final int HUB_CAPACITY = 12;
    private static final int PLACEMENT_ATTEMPTS = 5;
    private static final long PLACEMENT_FIRST_WAIT_MS = 700L;
    private static final long PLACEMENT_WAIT_MS = 600L;

    private final Context mContext;
    private final Cast2Config mConfig;
    private final Object mLock = new Object();
    /**
     * The current encoder-to-RTSP queue.  Replaced on every start: a new encoder must not publish
     * into a hub whose reader threads belong to the previous session.
     */
    private volatile FrameHub mHub = new FrameHub(HUB_CAPACITY);

    private Cast2Display mDisplay;
    private Cast2Encoder mEncoder;
    private Rtsp2Server mRtsp;
    private Cast2HttpControl mHttp;

    private volatile boolean mListenWanted;
    private volatile String mApp;
    private volatile int mWidth;
    private volatile int mHeight;
    private volatile String mPlacement = "not attempted";
    private volatile String mLastError = "";
    private volatile String mRtspError = "";

    public Cast2Controller(Context context) {
        mContext = context;
        Cast2Config.ensureDir();
        mConfig = new Cast2Config();
        mApp = mConfig.app();
        final int[] size = mConfig.size();
        mWidth = size[0];
        mHeight = size[1];
        writeStateFile("daemon startup (cast2 down)");
    }

    /* ------------------------------------------------------------------ queries */

    public boolean isUp() {
        return mDisplay != null && mDisplay.isUp() && mEncoder != null && mEncoder.running();
    }

    public boolean isRtspUp() {
        final Rtsp2Server server = mRtsp;
        return server != null && server.isRunning();
    }

    public boolean isListening() {
        final Cast2HttpControl http = mHttp;
        return http != null && http.isRunning();
    }

    public String app() {
        return mApp;
    }

    public int width() {
        return mWidth;
    }

    public int height() {
        return mHeight;
    }

    public String lastError() {
        return mLastError;
    }

    public int rtspPort() {
        return mConfig.rtspPort();
    }

    public int httpPort() {
        return mConfig.httpPort();
    }

    /** The one-line summary the daemon prints after every `cast2 ...` command. */
    public String describe() {
        final Cast2Encoder encoder = mEncoder;
        final Cast2Display display = mDisplay;
        final Rtsp2Server rtsp = mRtsp;
        final StringBuilder text = new StringBuilder();
        text.append("cast2=").append(isUp() ? "up" : "down");
        text.append(" display=").append(display != null && display.isUp() ? display.displayId() : -1);
        text.append(" size=").append(mWidth).append('x').append(mHeight);
        text.append(" app=").append(mApp.isEmpty() ? "-" : mApp);
        text.append(" placement=").append(mPlacement);
        if (encoder != null) {
            text.append(" encoder=").append(encoder.running() ? "running" : "stopped")
                    .append(' ').append(encoder.fps()).append("fps ")
                    .append(encoder.bitrateBps() / 1000).append("kbps frames=")
                    .append(encoder.frames());
        } else {
            text.append(" encoder=none");
        }
        text.append(' ').append(rtsp != null ? rtsp.describe()
                : "rtsp down :" + mConfig.rtspPort() + " path=" + Rtsp2Server.TRACK_PATH
                        + " clients=0 playing=0");
        final Cast2HttpControl http = mHttp;
        text.append(" listen=").append(http != null && http.isRunning() ? "on" : "off")
                .append(" httpPort=").append(mConfig.httpPort());
        text.append(" touch=none");
        return text.toString();
    }

    /**
     * The `cast2 status` body: one line per fact, each starting `linkd: cast2 `.
     *
     * <p>Kept multi-line because the fields have different lifetimes (the display, the encoder, the
     * RTSP listener and the control listener can each be up while the others are down) and a
     * one-line summary hides exactly the combination an operator is debugging.
     */
    public List<String> statusLines() {
        final Cast2Encoder encoder = mEncoder;
        final Cast2Display display = mDisplay;
        final Rtsp2Server rtsp = mRtsp;
        final Cast2HttpControl http = mHttp;
        final List<String> lines = new ArrayList<String>();
        lines.add("linkd: cast2 up=" + isUp()
                + " display=" + (display != null && display.isUp() ? display.displayId() : -1)
                + " size=" + mWidth + 'x' + mHeight
                + " placement=" + mPlacement);
        lines.add("linkd: cast2 app=" + (mApp.isEmpty() ? "-" : mApp)
                + (mConfig.activity().isEmpty() ? "" : " activity=" + mConfig.activity()));
        lines.add("linkd: cast2 encoder="
                + (encoder == null ? "none" : (encoder.running() ? "running" : "stopped")
                        + " codec=" + Cast2Encoder.MIME
                        + " fps=" + encoder.fps()
                        + " bitrate_bps=" + encoder.bitrateBps()
                        + " frames=" + encoder.frames()
                        + " keyframes=" + encoder.keyFrames()
                        + " dropped=" + encoder.dropped()
                        + " bytes=" + encoder.bytes()
                        + (encoder.failure() == null ? "" : " failure=" + encoder.failure())));
        lines.add("linkd: cast2 rtsp=" + (rtsp != null && rtsp.isRunning() ? "up" : "down")
                + " port=" + mConfig.rtspPort()
                + " url=rtsp://<phone-ip>:" + mConfig.rtspPort() + Rtsp2Server.TRACK_PATH
                + " clients=" + (rtsp == null ? 0 : rtsp.clientCount())
                + " playing=" + (rtsp == null ? 0 : rtsp.playingCount())
                + (mRtspError.isEmpty() ? "" : " rtsp_error=" + mRtspError));
        lines.add("linkd: cast2 listen=" + (http != null && http.isRunning() ? "on" : "off")
                + " port=" + mConfig.httpPort()
                + " requests=" + (http == null ? 0 : http.requests()));
        final int touchDisplayId = mDisplay != null && mDisplay.isUp() ? mDisplay.displayId() : -1;
        lines.add("linkd: cast2 touch=none (display " + touchDisplayId + " has no touch return path)"
                + (mLastError.isEmpty() ? "" : " last_error=" + mLastError));
        return lines;
    }

    /* ------------------------------------------------------------------ commands */

    /**
     * `cast2 up [WxH]` — create display 2, start the encoder, place the app, start RTSP.
     *
     * <p>Idempotent: when display 2 is already up nothing is touched, and a different size is
     * refused (a live display's geometry is fixed; the operator must `cast2 down` first).  RTSP is
     * brought up as part of a start because that is what "cast" means here; `rtsp up` on its own
     * brings the listener up before a display exists, which is also supported.
     *
     * @throws IllegalStateException on any failed precondition, after rolling the start back
     */
    public void start() {
        synchronized (mLock) {
            if (isUp()) {
                mLastError = "";
                Log.i(TAG, "cast2 up: already up, nothing changed");
                return;
            }
            if (mDisplay != null && mDisplay.isUp()) {
                /* The display survived but the encoder did not (it failed after the display was
                 * built).  Releasing the display and rebuilding both halves is the honest path:
                 * the platform has no way to hand a fresh input surface to a display whose encoder
                 * an app process is not allowed to reuse. */
                Log.i(TAG, "cast2 up: display " + mDisplay.displayId()
                        + " is up without a working encoder — rebuilding the pipeline");
                releasePipeline();
            }
            final FrameHub hub = new FrameHub(HUB_CAPACITY);
            final Cast2Encoder encoder = new Cast2Encoder(mWidth, mHeight, mConfig.bitrateBps(),
                    mConfig.fps(), hub);
            try {
                encoder.start();
            } catch (RuntimeException e) {
                encoder.release();
                hub.close();
                mLastError = String.valueOf(e.getMessage());
                writeStateFile("cast2 up failed: " + mLastError);
                throw e;
            }
            final Cast2Display display = new Cast2Display(mContext);
            try {
                display.create(mWidth, mHeight, encoder.inputSurface());
            } catch (RuntimeException e) {
                encoder.release();
                hub.close();
                mLastError = String.valueOf(e.getMessage());
                writeStateFile("cast2 up failed: " + mLastError);
                throw e;
            }
            /* From here on the pipeline is the controller's: install it before anything that can
             * throw, so the error path releases by the same rules as `cast2 down`. */
            final FrameHub previous = mHub;
            mHub = hub;
            mEncoder = encoder;
            mDisplay = display;
            mPlacement = "created display " + display.displayId();
            if (previous != null) {
                previous.close();
            }

            final String placed = placeApp(display, mConfig.activity());
            if (placed == null) {
                final String message = "cannot place " + mApp + " on display " + display.displayId()
                        + " — check the package is installed and has a launch activity";
                mLastError = message;
                /* Roll the whole start back: a display with no app is not a working cast2. */
                releasePipeline();
                writeStateFile("cast2 up failed: " + message);
                throw new IllegalStateException(message);
            }
            mPlacement = placed;
            mLastError = "";
            try {
                startRtsp();
            } catch (RuntimeException e) {
                mLastError = String.valueOf(e.getMessage());
                releasePipeline();
                writeStateFile("cast2 up failed: " + mLastError);
                throw e;
            }
            writeStateFile("cast2 up");
            Log.i(TAG, "cast2 up: " + describe());
        }
    }

    /** `cast2 down` — the RTSP listener and its sessions, the encoder, then display 2. */
    public void stopCast2() {
        synchronized (mLock) {
            stopRtsp();
            releasePipeline();
            mPlacement = "released";
            writeStateFile("cast2 down");
            Log.i(TAG, "cast2 down: " + describe());
        }
    }

    private void releasePipeline() {
        final Cast2Encoder encoder = mEncoder;
        final Cast2Display display = mDisplay;
        mEncoder = null;
        mDisplay = null;
        final FrameHub hub = mHub;
        if (hub != null) {
            /* The hub's waiters are this session's RTSP sender threads; close() ends them. */
            hub.close();
        }
        if (encoder != null) {
            encoder.release();
        }
        if (display != null) {
            display.release();
        }
        mPlacement = "released";
    }

    /**
     * `cast2 app <pkg>` — persist the hosted app; when display 2 is up the new app is placed there
     * immediately.
     *
     * @throws IllegalStateException when the new app cannot be placed; the configuration still
     *     holds the new package (the operator asked for it) and the display keeps showing the old
     *     app, which the status line reports.
     */
    public void setApp(String pkg) {
        final String wanted = pkg == null ? "" : pkg.trim();
        if (wanted.isEmpty() || "-".equals(wanted)) {
            throw new IllegalArgumentException("cast2 app wants a package name");
        }
        synchronized (mLock) {
            mApp = wanted;
            mConfig.setApp(wanted);
            mConfig.save();
            final Cast2Display display = mDisplay;
            if (display == null || !display.isUp()) {
                Log.i(TAG, "cast2 app=" + wanted + " (no display yet; takes effect on `cast2 up`)");
                writeStateFile("cast2 app=" + wanted);
                return;
            }
            /* Stop the previous app so two packages cannot stack on one display. */
            final String previous = mPlacement;
            final String placed = placeApp(display, mConfig.activity());
            if (placed == null) {
                mPlacement = "failed: " + wanted + " is not on display " + display.displayId()
                        + " (still showing the previous app)";
                mLastError = "cannot place " + wanted + " on display " + display.displayId();
                writeStateFile("cast2 app failed: " + mLastError);
                throw new IllegalStateException(mLastError + " (previous placement: " + previous + ')');
            }
            mPlacement = placed;
            mLastError = "";
            writeStateFile("cast2 app=" + wanted);
            Log.i(TAG, "cast2 app=" + wanted + ": " + placed);
        }
    }

    /** `cast2 size WxH` — persist display 2's geometry; refused while the display is up. */
    public void setSize(int width, int height) {
        synchronized (mLock) {
            if (isUp()) {
                throw new IllegalStateException("display 2 is up at " + mWidth + 'x' + mHeight
                        + "; `cast2 down` before changing the size to " + width + 'x' + height);
            }
            mWidth = width;
            mHeight = height;
            mConfig.setSize(width, height);
            mConfig.save();
            writeStateFile("cast2 size=" + width + 'x' + height);
            Log.i(TAG, "cast2 size=" + width + 'x' + height + " (takes effect on `cast2 up`)");
        }
    }

    /** `rtsp up` — bind the RTSP listener.  Needs a running encoder; there is no stream without one. */
    public void startRtsp() {
        synchronized (mLock) {
            if (isRtspUp()) {
                mRtspError = "";
                return;
            }
            if (mEncoder == null || !mEncoder.running()) {
                mRtspError = "no encoder: run `cast2 up` first";
                throw new IllegalStateException("rtsp up refused: " + mRtspError);
            }
            final Rtsp2Server server = new Rtsp2Server(mHub, mConfig.rtspPort());
            server.setGeometry(mWidth, mHeight, mConfig.fps());
            try {
                server.start();
            } catch (Exception e) {
                mRtspError = String.valueOf(e.getMessage());
                writeStateFile("rtsp up failed: " + mRtspError);
                throw new IllegalStateException("rtsp up refused: " + mRtspError, e);
            }
            mRtsp = server;
            mRtspError = "";
            writeStateFile("rtsp up :" + mConfig.rtspPort());
        }
    }

    /** `rtsp down` — close the listener and every session; the display keeps running. */
    public void stopRtsp() {
        synchronized (mLock) {
            final Rtsp2Server server = mRtsp;
            mRtsp = null;
            if (server != null) {
                server.stop();
            }
            mRtspError = "";
            writeStateFile("rtsp down");
        }
    }

    /** `cast2 listen on|off` — the HTTP control API; on by explicit request only. */
    public void setListening(boolean on) {
        synchronized (mLock) {
            mListenWanted = on;
            if (on) {
                if (isListening()) {
                    return;
                }
                final Cast2HttpControl http = new Cast2HttpControl(mConfig.httpPort(), this);
                try {
                    http.start();
                } catch (Exception e) {
                    throw new IllegalStateException("cast2 listen on refused: " + e.getMessage(), e);
                }
                mHttp = http;
            } else {
                final Cast2HttpControl http = mHttp;
                mHttp = null;
                if (http != null) {
                    http.stop();
                }
            }
            writeStateFile("cast2 listen=" + (on ? "on :" + mConfig.httpPort() : "off"));
        }
    }

    /** The mode the operator last asked for, including across a `cast2 down`/`up` cycle. */
    public boolean listeningWanted() {
        return mListenWanted;
    }

    /* ------------------------------------------------------------------ placement */

    /**
     * Puts {@code pkg} on display 2 and verifies it with `dumpsys activity activities`.
     *
     * <p>A task record is not enough: the dump keeps naming a package whose UI is gone, and the
     * symptom of trusting it is a black display with zero encoded frames.  Placement therefore
     * verifies that the package owns a window inside the display's own section of the dump.
     *
     * @return a description of the successful route, or null when the app could not be placed
     */
    private String placeApp(Cast2Display display, String activity) {
        final int displayId = display.displayId();
        final String pkg = mApp;
        if (displayId < 0) {
            return null;
        }
        if (pkg.isEmpty()) {
            Log.w(TAG, "no app configured for display 2");
            return null;
        }
        if (hasWindowOnDisplay(dumpsysActivities(), displayId, pkg)) {
            return "already on display " + displayId;
        }
        /* A leftover instance from a previous display or a previous daemon must not survive: the
         * display id it was launched on is gone, and two instances of a car-navigation app is a
         * real conflict (both would try to hold the same engine). */
        if (findTaskId(dumpsysActivities(), pkg) > 0) {
            Log.i(TAG, "stopping the stale instance of " + pkg + " before placing it on display "
                    + displayId);
            run(new String[]{"/system/bin/am", "force-stop", pkg}, "am force-stop " + pkg);
        }
        final String component = resolveComponent(pkg, activity);
        if (component == null) {
            Log.w(TAG, "cannot resolve a launch component for " + pkg
                    + " (no launcher activity and no configured `activity=`)");
            return null;
        }
        for (int attempt = 0; attempt < PLACEMENT_ATTEMPTS; attempt++) {
            await(attempt == 0 ? PLACEMENT_FIRST_WAIT_MS : PLACEMENT_WAIT_MS);
            run(new String[]{"/system/bin/am", "start", "--display", String.valueOf(displayId),
                    "-n", component}, "am start --display " + displayId + " -n " + component);
            if (hasWindowOnDisplay(dumpsysActivities(), displayId, pkg)) {
                return "am start --display " + displayId + " -n " + component
                        + " (attempt " + (attempt + 1) + ')';
            }
        }
        return null;
    }

    /**
     * The component {@code am start} must be given: the configured `activity=` when set, otherwise
     * the package's launcher activity.  Null when neither exists — a failure, never a guess.
     */
    private String resolveComponent(String pkg, String activity) {
        if (activity != null && !activity.isEmpty()) {
            if (activity.indexOf('/') > 0) {
                return activity;
            }
            /* `pkg/.Activity` shorthand, or a bare class name. */
            return activity.startsWith(pkg) ? activity
                    : pkg + (activity.startsWith(".") ? activity : '/' + activity);
        }
        try {
            final Intent launch = mContext.getPackageManager().getLaunchIntentForPackage(pkg);
            if (launch == null) {
                return null;
            }
            final ComponentName component = launch.getComponent();
            return component == null ? null : component.flattenToShortString();
        } catch (Throwable t) {
            Log.w(TAG, "getLaunchIntentForPackage(" + pkg + "): " + t);
            return null;
        }
    }

    private static String[] dumpsysActivities() {
        try {
            final Process process = Runtime.getRuntime()
                    .exec(new String[]{"/system/bin/dumpsys", "activity", "activities"});
            final List<String> lines = new ArrayList<String>();
            final BufferedReader reader =
                    new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
            process.waitFor();
            return lines.toArray(new String[0]);
        } catch (Throwable t) {
            Log.w(TAG, "dumpsys activity activities failed: " + t);
            return null;
        }
    }

    /**
     * True when the package owns a window inside {@code Display #<id>}'s section of the dump.
     *
     * <p>The section ends where the supervisor-wide state begins; scanning past it counts a
     * package's task history as "on this display" — the false positive that leaves a display a
     * black picture (measured on the mirroring stack, same dump, same trap).
     */
    static boolean hasWindowOnDisplay(String[] dump, int displayId, String pkg) {
        if (dump == null) {
            return false;
        }
        boolean inSection = false;
        boolean mine = false;
        for (String line : dump) {
            final int at = line.indexOf("Display #");
            if (at >= 0) {
                inSection = readInt(line, at + "Display #".length()) == displayId;
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
            }
        }
        return false;
    }

    /** Root task id of a running package, or -1 when the dump names no task of it. */
    static int findTaskId(String[] dump, String pkg) {
        if (dump == null) {
            return -1;
        }
        int rootTask = -1;
        int current = -1;
        for (String line : dump) {
            final int rootAt = line.indexOf("RootTask{");
            if (rootAt >= 0) {
                final int hash = line.indexOf('#', rootAt);
                rootTask = hash < 0 ? -1 : readInt(line, hash + 1);
                current = rootTask;
                if (line.contains(pkg)) {
                    return rootTask;
                }
                continue;
            }
            final int taskAt = line.indexOf("Task{");
            if (taskAt >= 0) {
                final int hash = line.indexOf('#', taskAt);
                current = hash < 0 ? -1 : readInt(line, hash + 1);
                if (line.contains(pkg)) {
                    return rootTask > 0 ? rootTask : current;
                }
                continue;
            }
            if (line.contains(pkg)) {
                return rootTask > 0 ? rootTask : current;
            }
        }
        return -1;
    }

    private static int readInt(String line, int from) {
        int value = 0;
        boolean digits = false;
        for (int i = from; i < line.length(); i++) {
            final char c = line.charAt(i);
            if (c < '0' || c > '9') {
                break;
            }
            value = value * 10 + (c - '0');
            digits = true;
        }
        return digits ? value : -1;
    }

    private static void run(String[] command, String what) {
        try {
            final Process process = Runtime.getRuntime().exec(command);
            final BufferedReader reader =
                    new BufferedReader(new InputStreamReader(process.getInputStream()));
            final StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (output.length() < 300) {
                    output.append(line).append(' ');
                }
            }
            process.waitFor();
            Log.i(TAG, what + " -> " + output.toString().trim());
        } catch (Throwable t) {
            Log.w(TAG, what + " failed: " + t);
        }
    }

    private static void await(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /* ------------------------------------------------------------------ state file */

    /** The `cast2.state` snapshot, timestamped first line, same idiom as the daemon's status file. */
    public void writeStateFile(String reason) {
        Cast2Config.ensureDir();
        final StringBuilder text = new StringBuilder();
        text.append("# cast2 state snapshot ")
                .append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()))
                .append(" (").append(System.currentTimeMillis()).append(")\n");
        text.append("reason=").append(reason).append('\n');
        for (String line : statusLines()) {
            text.append(line).append('\n');
        }
        Cast2Config.writeFile(new File(Cast2Config.STATE_FILE), text.toString());
    }

    /** Called by the daemon on the way out so a stopped daemon cannot look like a live cast2. */
    public void shutdown() {
        synchronized (mLock) {
            releasePipeline();
            stopRtsp();
            setListening(false);
            writeStateFile("daemon exiting");
        }
    }
}
