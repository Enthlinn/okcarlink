/*
 * OkcarOS link stack — L4 media plane: the mirrored video source.
 *
 * This is the "video encoder keeps emitting frames" half of the sender path: mirror the phone
 * screen into a Surface and encode it as H.264 with MediaCodec, exactly like the
 * closed-source app does with OMX-VENC (§12.17 C).
 *
 * No MediaProjection consent dance is needed here: DisplayManagerService only
 * enforces CAPTURE_VIDEO_OUTPUT for VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR when the
 * caller is not the system uid, and this app runs as android.uid.system
 * (sharedUserId) — see DisplayManagerService.java:1303.
 *
 *   DisplayManager.createVirtualDisplay(AUTO_MIRROR) -> Surface
 *        -> MediaCodec("video/avc" | "video/hevc") input surface
 *        -> H.264 access units + SPS/PPS (csd-0 / csd-1)
 *        -> H.265 access units + VPS/SPS/PPS (csd-0 / csd-1 / in-band)
 *
 * The codec is selectable ({@link #setVideoCodec(int)}, default H.264 so the wired
 * the production head unit path is bit-for-bit unchanged); {@link #parameterSetVps()} and
 * {@link #hvcCRecord()} expose the extra HEVC pieces the wireless CSD record needs.
 * The media *session* (who opens the socket, which dataPort, RTSP/plist framing)
 * is deliberately NOT here: that belongs to the AirPlay session layer and still
 * needs a field capture to pin down (§12.24 D).  This class is the encoder side,
 * which is protocol independent and verifiable on its own.
 */

package com.okcar.link.media;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;

import com.okcar.link.airplay.WirelessScreenHeader;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class MirrorVideoSource {

    private static final String TAG = "OkCarLink/Video";

    private static final String MIME = "video/avc";
    /** MediaFormat MIME of the H.265 encoder (MediaFormat.MIMETYPE_VIDEO_HEVC). */
    public static final String MIME_HEVC = MediaFormat.MIMETYPE_VIDEO_HEVC;
    private static final int DEFAULT_WIDTH = 1920;
    private static final int DEFAULT_HEIGHT = 720;
    private static final int DEFAULT_FPS = 30;
    private static final int DEFAULT_BITRATE = 8_000_000;
    /* Every IDR is 10–20× a normal frame (measured 73–106 KB vs ~5 KB), so the IDR
     * cadence is the biggest single spike we put on a slow accessory's receive queue.
     * 3 s keeps a decoder refresh well inside the accessory's own recovery window while
     * cutting those spikes to a third; an accessory that re-creates its decoder still
     * gets one on demand (requestUI -> requestSyncFrame). */
    private static final int I_FRAME_INTERVAL_SECONDS = 3;
    /** Ask for an IDR on this slow timer so a static screen cannot silence the stream. */
    private static final long KEEPALIVE_INTERVAL_MS = 5000;
    /** Let the encoder re-emit the last frame instead of going idle (microseconds). */
    private static final long REPEAT_FRAME_AFTER_US = 250000;
    private static final long DEQUEUE_TIMEOUT_US = 10_000;

    /**
     * Receives every encoded frame as it leaves MediaCodec, so a live consumer
     * (the AirPlay screen-stream sender) does not have to wait for the dump file.
     * Called inline from the encoder drain loop: implementations must not block.
     */
    public interface FrameSink {
        void onFrame(byte[] data, int flags, long presentationTimeUs);
    }

    /** Lab-only encoder name; empty means "whatever the platform offers for the MIME". */
    private volatile String mEncoderName = "";

    /** See {@link #setEncoderName}; call before {@link #start}. */
    /**
     * CarWith source mode (2026-10-07): hand the encoder's **input surface** out instead of building
     * a mirror display, so another process (CarWith) renders its own virtual display into it.
     *
     * <p>This is the measured direction of the CarWith contract — the component supplies the surface
     * in `CastConfig` and CarWith creates its virtual display with
     * `VirtualDisplayConfig.Builder(...).setSurface(mSurface)` (see
     * `com.okcar.link.carkit.CarwithContract`).  Nothing else in this class changes: the encoder, the
     * drain loop, the frame sink and the QoS knobs are exactly the ones the AirPlay path already
     * uses, so the frames land in the existing sender.
     */
    public interface ExternalDisplay {
        /**
         * @return true when the surface was taken; false makes this source fall back to building the
         *     mirror display, which is what keeps a disarmed daemon on the old behaviour
         */
        boolean onInputSurface(Surface surface, int width, int height);
    }

    /** Installs the CarWith-mode surface sink; null restores plain mirroring. */
    public void useExternalDisplay(ExternalDisplay externalDisplay) {
        mExternalDisplay = externalDisplay;
    }

    /**
     * Mirrors this display id into the encoder input surface instead of the default display
     * (2026-10-08).  Call before {@link #start}; takes precedence over
     * {@link #useExternalDisplay}.  A non-positive id means "off".
     */
    public void setMirrorSourceDisplay(int displayId) {
        mMirrorSourceDisplay = displayId;
    }

    /** Lab knob for {@link #setMirrorSourceDisplay}: the virtual-display flags to use (-1 = default). */
    public void setMirrorFlags(int flags) {
        mMirrorFlags = flags;
    }

    public void setEncoderName(String name) {
        mEncoderName = name == null ? "" : name;
    }

    public static final class Stats {
        public volatile String codecName = "";
        public volatile String mime = MIME;
        public volatile int width;
        public volatile int height;
        public volatile long frames;
        public volatile long keyFrames;
        public volatile long bytes;
        public volatile int csd0 = -1;
        public volatile int csd1 = -1;
        /** HEVC VPS size in bytes, -1 for AVC or before the encoder has emitted it. */
        public volatile int vps = -1;
        public volatile long firstPresentationUs = -1;
        /**
         * Old frames skipped after a stall (see {@link #drainLoop}: only the newest frame is sent).
         * If this number grows, it proves that "the moment an app is launched" really did build up
         * pressure on our side.
         */
        public volatile long droppedByBacklog;
        /** Largest gap between two frames in the latest stats window (ms); the sampling side clears the window. */
        public volatile long maxGapMs;
        /** Time of the last frame (monotonic ms), used by the frame watchdog. */
        public volatile long lastFrameMs;
        /** Time of the last {@code requestSyncFrame}, used by the frame watchdog. */
        public volatile long lastIdrRequestMs;
        /** Whether the session is up — set by the owner; the watchdog uses it to decide whether to care. */
        public volatile boolean sessionUp;
        public volatile String error = "";
        public volatile String outputFormat = "";

        public String describe(long elapsedMs) {
            final double seconds = Math.max(0.001, elapsedMs / 1000.0);
            return String.format("%s %dx%d: %d frames (%d key), %d bytes, %.1f fps, %.0f kbps"
                            + "%s%s%s",
                    codecName.isEmpty() ? mime : codecName, width, height,
                    frames, keyFrames, bytes, frames / seconds, bytes * 8 / seconds / 1000,
                    vps >= 0 ? ", vps=" + vps + "B" : "",
                    csd0 >= 0 ? ", csd0=" + csd0 + "B" : "",
                    csd1 >= 0 ? ", csd1=" + csd1 + "B" : "",
                    ", droppedByBacklog=" + droppedByBacklog + ", maxGapMs=" + maxGapMs
                            + (error.isEmpty() ? "" : ", ERROR: " + error));
        }
    }

    private final Context mContext;
    private final Stats mStats = new Stats();
    private final AtomicBoolean mRunning = new AtomicBoolean();
    private final AtomicLong mStoppedAt = new AtomicLong();

    private DisplayManager mDisplayManager;
    private VirtualDisplay mVirtualDisplay;
    /**
     * The factory that built {@link #mVirtualDisplay}, kept so {@link #stop()} can hand the display
     * back to it instead of releasing it blindly.  A factory that owns the display as a shared app
     * surface keeps it alive across sessions (see {@link
     * com.okcar.link.port.MirrorDisplayFactory#releaseFromSource}); releasing it here would move the
     * app's task back onto the phone's display.
     */
    private com.okcar.link.port.MirrorDisplayFactory mDisplayFactory;
    /** CarWith mode: when set and it accepts the surface, no mirror display is built. */
    private volatile ExternalDisplay mExternalDisplay;
    /**
     * Mirror ONE specific display into the encoder instead of the phone's own (2026-10-08): used to
     * take CarWith's wired-ICCOA cast display as the picture source.  -1 = off.  See
     * {@link com.okcar.link.port.DisplayMirror} for why the two other routes do not work.
     */
    private volatile int mMirrorSourceDisplay = -1;
    /** Virtual-display flags for the mirror; -1 keeps AUTO_MIRROR|PUBLIC (lab knob). */
    private volatile int mMirrorFlags = -1;
    private MediaCodec mCodec;
    private Surface mInputSurface;
    private Thread mDrainThread;
    private Thread mNudgeThread;
    private FileOutputStream mDump;
    /** SPS / PPS — the media session has to send these to the car as well. */
    private byte[] mCsd0;
    private byte[] mCsd1;
    /** HEVC only: the VPS (NAL 32), when the encoder emits it separately. */
    private byte[] mVps;
    /** HEVC only: a ready-made HEVCDecoderConfigurationRecord from the encoder. */
    private byte[] mHvcC;

    /** {@link WirelessScreenHeader#CODEC_AVC} (default) or {@link WirelessScreenHeader#CODEC_HEVC}. */
    private int mVideoCodec = WirelessScreenHeader.CODEC_AVC;

    /** Optional live consumer of encoded frames (see {@link FrameSink}). */
    private volatile FrameSink mFrameSink;

    public void setFrameSink(FrameSink sink) {
        mFrameSink = sink;
    }

    private int mWidth = DEFAULT_WIDTH;
    private int mHeight = DEFAULT_HEIGHT;
    private int mFps = DEFAULT_FPS;
    private int mBitrate = DEFAULT_BITRATE;

    public MirrorVideoSource(Context context) {
        mContext = context.getApplicationContext();
    }

    /**
     * Selects the encoder codec.  Must be called before {@link #start(File)}; the
     * default is H.264, which is what the wired head-unit path has always used (and
     * keeps that path's behaviour identical).
     *
     * @param codec {@link WirelessScreenHeader#CODEC_AVC} or {@link WirelessScreenHeader#CODEC_HEVC}
     */
    public void setVideoCodec(int codec) {
        if (mRunning.get()) {
            throw new IllegalStateException("setVideoCodec must be called before start()");
        }
        mVideoCodec = codec == WirelessScreenHeader.CODEC_HEVC
                ? WirelessScreenHeader.CODEC_HEVC : WirelessScreenHeader.CODEC_AVC;
    }

    /** The codec this source encodes with. */
    public int videoCodec() {
        return mVideoCodec;
    }

    /** MIME of a codec id. */
    public static String mimeFor(int codec) {
        return codec == WirelessScreenHeader.CODEC_HEVC ? MIME_HEVC : MIME;
    }

    /**
     * Whether this device has a surface-input encoder for {@code mime} at this size.
     * Used before committing the wireless session to HEVC when only the accessory
     * advertised it.
     */
    public static boolean isVideoCodecAvailable(String mime, int width, int height) {
        try {
            final MediaFormat format = MediaFormat.createVideoFormat(mime, width, height);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            final MediaCodecList list = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
            return list.findEncoderForFormat(format) != null;
        } catch (Throwable t) {
            Log.w(TAG, "cannot probe an encoder for " + mime + ": " + t);
            return false;
        }
    }

    /** Whether the current session is mirror mode (app mode does not clamp the canvas: there the display hosts apps at the head unit's size). */
    private static boolean isMirrorSession() {
        final com.okcar.link.port.MirrorDisplayFactory factory =
                com.okcar.link.port.PortBootstrap.mirrorDisplayFactory();
        return factory instanceof com.okcar.link.port.RootAppDisplayFactory
                && ((com.okcar.link.port.RootAppDisplayFactory) factory).mirrorMode();
    }

    public void configure(int width, int height, int fps, int bitrate) {
        if (width > 0 && height > 0) {
            /* Both the encoder and the virtual display are decided here, so the mirror canvas clamp
             * must happen here (exactly one place): clamping only where the display is created
             * leaves the encoder configured at the size the head unit declared, cropping the
             * picture by a border (measured 2026-10-04). */
            if (isMirrorSession()) {
                final int[] canvas = com.okcar.link.port.RootAppDisplayFactory
                        .clampMirrorCanvas(mContext, width, height);
                width = canvas[0];
                height = canvas[1];
            }
            mWidth = width;
            mHeight = height;
        }
        if (fps > 0) {
            mFps = fps;
        }
        if (bitrate > 0) {
            mBitrate = bitrate;
        }
    }

    public Stats stats() {
        return mStats;
    }

    /** H.264 parameter sets (SPS), or null when the encoder has not emitted them. */
    public byte[] parameterSet0() {
        return mCsd0;
    }

    /** H.264 parameter sets (PPS), or null when the encoder has not emitted them. */
    public byte[] parameterSet1() {
        return mCsd1;
    }

    /** HEVC video parameter set (NAL 32), or null for AVC / before it is known. */
    public byte[] parameterSetVps() {
        return mVps;
    }

    /** A ready-made HEVCDecoderConfigurationRecord (csd-0 of an HEVC encoder), or null. */
    public byte[] hvcCRecord() {
        return mHvcC;
    }

    private static String hex(byte[] data) {
        if (data == null) {
            return "(null)";
        }
        final StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /** Copies a ByteBuffer without disturbing its position. */
    private static byte[] toBytes(ByteBuffer buffer) {
        if (buffer == null) {
            return null;
        }
        final int position = buffer.position();
        final byte[] data = new byte[buffer.remaining()];
        buffer.get(data);
        buffer.position(position);
        return data;
    }

    /** @return the default display's current refresh rate in Hz, or 0 when it cannot be read. */
    private int panelRateHz() {
        try {
            final android.view.Display d =
                    ((android.hardware.display.DisplayManager) mContext.getSystemService(
                            android.content.Context.DISPLAY_SERVICE)).getDisplay(
                            android.view.Display.DEFAULT_DISPLAY);
            return d == null ? 0 : Math.round(d.getRefreshRate());
        } catch (Throwable ignored) {
            return 0;
        }
    }

    public boolean isRunning() {
        return mRunning.get();
    }

    /** Opens the encoder and starts mirroring; false (with stats.error) on failure. */
    /**
     * The frame rate cap must take effect **before** encoding: set the frame rate on the input
     * surface (so composition/rendering produce at that pace) and give the encoder a
     * max-fps-to-encoder cap. Never drop encoded frames — that breaks the reference chain and the
     * receiver shows garbage (measured 2026-10-03).
     */
    private void applyFrameRateCap(Surface input) {
        final int cap = mFps;
        if (cap <= 0) {
            return;
        }
        try {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                /* ONLY_IF_SEAMLESS, not ALWAYS.  ALWAYS forces a display mode/refresh-rate switch on
                 * whatever display this surface belongs to; in module-owned mode the encoder input IS
                 * the virtual display's output, so it pinned the mirror display to a rate that does not
                 * match the panel -- and this ROM's QCOM/MIUI HWC then reports duplicate VSYNC for the
                 * same display roughly every 16 ms ("HWComposer: Ignoring duplicate VSYNC event ...")
                 * while the desktop freezes.  Measured 2026-10-07 07:36 with a live wired session and
                 * 2026-10-06 20:03 before it; the operator reported both as "desktop froze".  The real
                 * fps control is encoder-side (max-fps-to-encoder + KEY_OPERATING_RATE below), so the
                 * display can keep the panel's own rate and the phone is not throttled either. */
                input.setFrameRate(cap, Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS);
                Log.i(TAG, "encoder input surface frame rate -> " + cap + " (only if seamless, panel "
                        + panelRateHz() + "Hz)");
            }
        } catch (Throwable t) {
            Log.w(TAG, "setFrameRate(" + cap + ") rejected: " + t);
        }
    }

    /**
     * App-adjustable video quality (bitrate in kbps and frame rate cap). Must be called before
     * {@link #start(File)}; a value of 0 means no override. The caller reads the wireless or wired
     * settings according to its own transport (see VideoQos).
     */
    public void setQos(VideoQos.Settings qos) {
        if (qos == null) {
            return;
        }
        if (mRunning.get()) {
            throw new IllegalStateException("setQos must be called before start()");
        }
        if (qos.overridesBitrate()) {
            mBitrate = qos.bitrateKbps * 1000;
        }
        if (qos.overridesFps()) {
            mFps = qos.fpsCap;
        }
        Log.i(TAG, "qos " + qos.describe() + " -> bitrate=" + mBitrate + " fps=" + mFps);
        /* Record the *result*, not the wish: the panel shows the conf the user saved and this file
         * side by side, so "I set 60 fps but it runs 30" is visible instead of guessed (the fps may
         * have been capped by persist.okcar.max_fps, or this session may predate the save). */
        VideoQos.writeApplied(new java.io.File(VideoQos.APPLIED_PATH),
                qos.transport, mBitrate, mFps, qos.videoDelayMs);
    }

    public boolean start(File dumpFile) {
        if (mRunning.get()) {
            return true;
        }
        /* Resolve the "auto" mirror source BEFORE the encoder is configured: the picture geometry has
         * to come from CarWith's own cast display, not from whatever the accessory negotiated — a
         * receiver started in a small window advertises e.g. 2400x2400 and that would distort the car
         * UI (measured 2026-10-08). */
        final boolean mirrorAuto = mMirrorSourceDisplay == com.okcar.link.port.DisplayMirror.AUTO;
        int mirrorSource = mMirrorSourceDisplay;
        if (mirrorAuto) {
            mirrorSource = com.okcar.link.port.DisplayMirror.findCarwithCastDisplay(mContext);
            if (mirrorSource >= 0) {
                final int[] srcSize =
                        com.okcar.link.port.DisplayMirror.sizeOf(mContext, mirrorSource);
                if (srcSize != null) {
                    mWidth = srcSize[0];
                    mHeight = srcSize[1];
                }
            }
        }
        try {
            mStats.width = mWidth;
            mStats.height = mHeight;
            final String mime = mimeFor(mVideoCodec);
            mStats.mime = mime;

            final MediaFormat format = MediaFormat.createVideoFormat(mime, mWidth, mHeight);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_BIT_RATE, mBitrate);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, mFps);
            /* The same cap on the encoder side. **The type must be float**: the AOSP docs state that
             * the value associated with KEY_MAX_FPS_TO_ENCODER is "a float, representing the max frame
             * rate to feed the encoder at", whereas setInteger passing an int32 gets dropped by the
             * platform as an unmapped key (the 2026-10-04 measured log shows exactly
             * `int32_t max-fps-to-encoder = 30`). */
            try {
                format.setFloat("max-fps-to-encoder", (float) mFps);
            } catch (RuntimeException e) {
                Log.w(TAG, "max-fps-to-encoder not settable: " + e);
            }
            /* Measured negative result (2026-10-04, 217 + the emulator receiver): `operating-rate` really is
             * delivered into the encoder (logcat `int32_t operating-rate = 30`, no "not settable"), but
             * c2.qti.hevc.encoder does not use it to cap frames — with 30fps set, the receiver still
             * measured ~113fps. Along with `max-fps-to-encoder` (Codec2 reports straight back "no c2
             * equivalents"), KEY_FRAME_RATE and the input surface's Surface.setFrameRate, these are all
             * merely hints: **the platform side cannot cap the frame rate**. Truly capping it means
             * pacing it ourselves on the capture side (feeding frames via ByteBuffer/ImageReader).
             * Keep this key and the conclusion so nobody repeats the experiment. */
            try {
                format.setInteger(MediaFormat.KEY_OPERATING_RATE, mFps);
            } catch (RuntimeException e) {
                Log.w(TAG, "operating-rate not settable: " + e);
            }
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_SECONDS);
            // The head unit recreates its H264 decoder whenever the driver leaves
            // and re-enters CarPlay (measured 2026-09-26: H264Decoder create at
            // 20:27:34, no "New format" after it -> black screen).  The avcC
            // config packet only goes out once at session start, so the fresh
            // decoder never sees SPS/PPS.  Carry them in-band on every IDR
            // instead, which makes any decoder instance self-configuring (§12.34).
            format.setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1);
            // A static mirrored screen produces no new buffers, so the encoder goes
            // silent; the receiver then sees no frames at all (measured 2026-09-26:
            // "it recovers as soon as the screen is touched").  Keep a floor of
            // ~4 fps for static content so any decoder instance stays fed (§12.34).
            try {
                format.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, REPEAT_FRAME_AFTER_US);
            } catch (RuntimeException e) {
                Log.w(TAG, "repeat-previous-frame-after not settable: " + e);
            }

            /* Lab knob: a named encoder (e.g. the software `c2.android.hevc.encoder` next to the
             * hardware `c2.qti.hevc.encoder`).  A second, independent bitstream is what separates
             * "our encoder's stream confuses the head unit" from "its HEVC path is broken". */
            mCodec = mEncoderName == null || mEncoderName.isEmpty()
                    ? MediaCodec.createEncoderByType(mime)
                    : MediaCodec.createByCodecName(mEncoderName);
            mStats.codecName = mCodec.getName();
            mCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            mInputSurface = mCodec.createInputSurface();
            applyFrameRateCap(mInputSurface);
            mCodec.start();

            /* CarWith source mode (2026-10-07): the surface belongs to the CarWith component — it
             * puts it in a CastConfig and CarWith renders its own virtual display into it.  Building
             * a mirror display here as well would attach a second consumer to the same buffer queue
             * and produce a picture that is half CarWith, half the phone. */
            /* Mirror ONE specific display when asked (CarWith's own cast display): the surface is the
             * same, only the producer differs.  This is the route the 2026-10-08 measurements left
             * standing — see DisplayMirror. */
            final ExternalDisplay external = mExternalDisplay;
            if (mirrorSource >= 0 || mirrorAuto) {
                if (mirrorSource < 0) {
                    throw new IOException("CarWith's cast display was not found (mirror auto) —"
                            + " is the wired ICCOA session up?");
                }
                mDisplayFactory = null;
                mVirtualDisplay = com.okcar.link.port.DisplayMirror.create(mContext, mirrorSource,
                        "OkCarLink-mirror-display-" + mirrorSource, mWidth, mHeight, mInputSurface,
                        mMirrorFlags);
                if (mVirtualDisplay == null) {
                    throw new IOException("mirroring display " + mirrorSource + " failed"
                            + " (setDisplayIdToMirror; needs API 34 + CAPTURE_VIDEO_OUTPUT)"
                            + " — see OkCarLink/DisplayMirror in logcat");
                }
                Log.i(TAG, "mirroring display " + mirrorSource + " into the encoder ("
                        + mWidth + 'x' + mHeight + ')');
            } else if (external != null && external.onInputSurface(mInputSurface, mWidth, mHeight)) {
                Log.i(TAG, "carwith source mode: encoder input surface handed to CarWith;"
                        + " no mirror display built");
                mDisplayFactory = null;
                mVirtualDisplay = null;
            } else {
                /* One mirror path: the root factory builds the AUTO_MIRROR display itself (uid 0
                 * holds CAPTURE_VIDEO_OUTPUT).  There is no framework/consent fallback any more. */
                final com.okcar.link.port.MirrorDisplayFactory factory =
                        com.okcar.link.port.PortBootstrap.mirrorDisplayFactory();
                if (factory == null) {
                    throw new IOException("no root display factory installed —"
                            + " start the link from the okcar-linkd daemon");
                }
                mDisplayFactory = factory;
                final int mirrorDpi = mContext.getResources().getDisplayMetrics().densityDpi;
                mVirtualDisplay = factory.createVirtualDisplay("OkCarLink-mirror",
                        mWidth, mHeight, mirrorDpi, mInputSurface);
                Log.i(TAG, "mirror display via the root Ports factory");
                if (mVirtualDisplay == null && !(factory instanceof com.okcar.link.port.RootAppDisplayFactory
                        && ((com.okcar.link.port.RootAppDisplayFactory) factory).moduleOwnsDisplay())) {
                    throw new IOException("createVirtualDisplay returned null");
                }
                /* In module-owned mode the display lives in the app's process (so its uid can launch onto
                 * it) and this null handle is expected: geometry changes travel over the factory's
                 * geometry file and the module resizes its own display. */
            }

            if (dumpFile != null) {
                mDump = new FileOutputStream(dumpFile);
                Log.i(TAG, "dumping " + mime + " to " + dumpFile);
            }

            mRunning.set(true);
            mDrainThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    MediaThreads.raise(MediaPriority.THREAD_VIDEO_ENCODE);
                    drainLoop();
                }
            }, MediaPriority.THREAD_VIDEO_ENCODE);
            mDrainThread.setDaemon(true);
            mDrainThread.start();
            mNudgeThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    nudgeLoop();
                }
            }, "okcar-mirror-keepalive");
            mNudgeThread.setDaemon(true);
            mNudgeThread.start();

            Log.i(TAG, "mirroring " + mWidth + "x" + mHeight + '@' + mFps
                    + " via " + mStats.codecName);
            return true;
        } catch (IOException | RuntimeException e) {
            mStats.error = String.valueOf(e);
            Log.w(TAG, "cannot start mirror/encode: " + e);
            stop();
            return false;
        }
    }

    private void drainLoop() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (mRunning.get()) {
            /* Snapshot the encoder per iteration and never touch the field again in here: stop()
             * joins this thread for only a moment before it releases the codec and nulls the field,
             * and a dequeue can be waiting longer than that join.  Dereferencing the field instead
             * threw `NullPointerException … dequeueOutputBuffer … on a null object reference` on
             * 2026-10-02 17:18:09, which killed the whole daemon (an uncaught exception on a
             * non-main thread is fatal) and left the head unit waiting for a session that could
             * never come back. */
            final MediaCodec codec = mCodec;
            if (codec == null) {
                return;
            }
            try {
                int index = codec.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US);
                if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    continue;
                }
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    readCodecSpecificData(codec.getOutputFormat());
                    continue;
                }
                if (index < 0) {
                    continue;
                }
                /* Note (user direction, 2026-10-05): frames are **not** dropped here. The burst of old
                 * frames after a stall is absorbed by the sender-side VideoPacer buffer, which spreads
                 * the burst by "send only when due" and pins the latency to the audio/video compensation
                 * value; dropping after encoding would also break the HEVC reference chain and cause
                 * garbage. Only when the buffer cannot hold them does the sender drop the oldest. */
                final ByteBuffer buffer = codec.getOutputBuffer(index);
                if (buffer != null && info.size > 0) {
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        readCodecSpecificDataFromBuffer(buffer, info);
                    } else {
                        mStats.frames++;
                        mStats.bytes += info.size;
                        final long nowMs = SystemClock.elapsedRealtime();
                        final long previous = mStats.lastFrameMs;
                        if (previous > 0 && nowMs - previous > mStats.maxGapMs) {
                            mStats.maxGapMs = nowMs - previous;
                        }
                        mStats.lastFrameMs = nowMs;
                        if ((info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0) {
                            mStats.keyFrames++;
                        }
                        if (mStats.firstPresentationUs < 0) {
                            mStats.firstPresentationUs = info.presentationTimeUs;
                        }
                        writeDump(buffer, info);
                        dispatchFrame(buffer, info);
                    }
                }
                codec.releaseOutputBuffer(index, false);
            } catch (IllegalStateException e) {
                if (mRunning.get()) {
                    mStats.error = String.valueOf(e);
                    Log.w(TAG, "encoder loop failed: " + e);
                }
                break;
            } catch (Throwable t) {
                /* A codec torn down under us is a stopped session, not a reason to take the module
                 * down: the thread ends here and the next session starts a fresh one. */
                mStats.error = String.valueOf(t);
                Log.w(TAG, "encoder loop ended on " + t);
                break;
            }
        }
        mStoppedAt.set(SystemClock.elapsedRealtime());
    }

    /**
     * Slow keep-alive: with a static phone screen the encoder can emit nothing at
     * all, and a receiver that has just re-created its decoder then stays black
     * until the screen changes.  Ask for an IDR every couple of seconds so the
     * stream is never silent for long (measured §12.34).
     */
    private void nudgeLoop() {
        long lastNudgeMs = 0;
        while (mRunning.get()) {
            try {
                Thread.sleep(FrameWatch.TICK_MS);
            } catch (InterruptedException e) {
                return;
            }
            if (!mRunning.get()) {
                return;
            }
            final long now = SystemClock.elapsedRealtime();
            if (now - lastNudgeMs >= KEEPALIVE_INTERVAL_MS) {
                lastNudgeMs = now;
                requestSyncFrame();
            }
            checkFrameStall(now);
        }
    }

    /**
     * Only when the session is up and we **asked for an IDR yet still get no frame** is the pipeline
     * considered stuck — a static page still emits frames thanks to
     * {@code KEY_REPEAT_PREVIOUS_FRAME_AFTER=250ms} and the nudge every {@code KEEPALIVE_INTERVAL_MS},
     * so it is not misjudged (see {@link FrameWatch} for the test).
     */
    private void checkFrameStall(long nowMs) {
        final Stats stats = mStats;
        if (!FrameWatch.stuck(stats.sessionUp, nowMs, stats.lastFrameMs, stats.lastIdrRequestMs,
                FrameWatch.WARN_MS)) {
            return;
        }
        if (FrameWatch.shouldReRequestIdr(stats.sessionUp, nowMs, stats.lastFrameMs,
                stats.lastIdrRequestMs, FrameWatch.WARN_MS)) {
            Log.w(TAG, "no frame for " + (nowMs - stats.lastFrameMs)
                    + "ms after an IDR request — asking for another sync frame");
            requestSyncFrame();
        }
        if (FrameWatch.stuck(stats.sessionUp, nowMs, stats.lastFrameMs, stats.lastIdrRequestMs,
                FrameWatch.TEARDOWN_MS) && !mStallReported) {
            mStallReported = true;
            /* By default **log only**: in app mode a static screen simply produces no frames, and
             * "no frames" is not a fault by itself. Really tearing down the pipeline automatically
             * requires explicitly enabling persist.okcar.frame_teardown=1 (otherwise static screens
             * get cut repeatedly, which in the field looks like "connects for a moment, then drops
             * and reconnects"). */
            final boolean teardown = frameTeardownEnabled();
            Log.e(TAG, "video pipeline produced no frame for " + (nowMs - stats.lastFrameMs)
                    + "ms despite an IDR request (session is up, teardown=" + teardown + ')');
            if (FrameWatch.shouldTearDown(teardown, stats.sessionUp, nowMs, stats.lastFrameMs,
                    stats.lastIdrRequestMs)) {
                final FrameStallListener listener = mStallListener;
                if (listener != null) {
                    listener.onFrameStall(nowMs - stats.lastFrameMs);
                }
            }
        }
    }

    /** Callback for when the pipeline is judged stalled; the implementation rebuilds it (by default we only log). */
    public interface FrameStallListener {
        void onFrameStall(long stalledMs);
    }

    private volatile FrameStallListener mStallListener;
    private volatile boolean mStallReported;

    public void setFrameStallListener(FrameStallListener listener) {
        mStallListener = listener;
    }

    /** Whether "tear the pipeline down when there are no frames" is really allowed: off by default; only an explicit enable touches the session. */
    private static boolean frameTeardownEnabled() {
        try {
            return "1".equals(android.os.SystemProperties.get("persist.okcar.frame_teardown", "0"));
        } catch (Throwable t) {
            return false;
        }
    }

    /** Called when the session starts/stops: on start, reset "last frame" to now so the watchdog does not false-alarm at startup. */
    public void setSessionUp(boolean up) {
        mStats.sessionUp = up;
        if (up) {
            mStats.lastFrameMs = SystemClock.elapsedRealtime();
            mStats.lastIdrRequestMs = 0;
            mStallReported = false;
        }
    }

    /**
     * Asks the encoder for an IDR right now.  The head unit re-enters CarPlay by
     * creating a fresh decoder and sending {type=requestUI} (§12.34); the answer
     * is a fresh avcC plus an immediate keyframe, otherwise the new decoder sits
     * on a black screen until the next scheduled IDR (or forever).
     */
    public void requestSyncFrame() {
        mStats.lastIdrRequestMs = SystemClock.elapsedRealtime();
        final MediaCodec codec = mCodec;
        if (codec == null) {
            return;
        }
        try {
            final Bundle params = new Bundle();
            params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
            codec.setParameters(params);
        } catch (RuntimeException e) {
            Log.w(TAG, "sync frame request failed: " + e);
        }
    }

    private void readCodecSpecificData(MediaFormat format) {
        if (format == null) {
            return;
        }
        if (mVideoCodec == WirelessScreenHeader.CODEC_HEVC) {
            readHevcCodecSpecificData(format);
            return;
        }
        if (format.containsKey("csd-0")) {
            mCsd0 = toBytes(format.getByteBuffer("csd-0"));
        }
        if (format.containsKey("csd-1")) {
            mCsd1 = toBytes(format.getByteBuffer("csd-1"));
        }
        mStats.csd0 = mCsd0 == null ? -1 : mCsd0.length;
        mStats.csd1 = mCsd1 == null ? -1 : mCsd1.length;
        // The encoder's own view of the stream — if this disagrees with the SPS,
        // the parameter sets are stale/placeholder and the car could not decode.
        Log.i(TAG, "encoder output format: " + format);
        Log.i(TAG, "csd-0 hex: " + hex(mCsd0));
        Log.i(TAG, "csd-1 hex: " + hex(mCsd1));
        mStats.outputFormat = String.valueOf(format);
        // Put the parameter sets at the head of the dump so the file is a
        // self-contained Annex-B stream (and so the session layer can hand them
        // to the car in the stream setup).
        writeDumpBytes(mCsd0);
        writeDumpBytes(mCsd1);
        if (mCsd0 != null) {
            Log.i(TAG, "parameter sets: SPS " + (mCsd0 == null ? 0 : mCsd0.length)
                    + "B, PPS " + (mCsd1 == null ? 0 : mCsd1.length) + "B");
        }
    }

    /**
     * HEVC flavour of {@link #readCodecSpecificData(MediaFormat)}.
     *
     * Android HEVC encoders are inconsistent here: some hand the whole
     * HEVCDecoderConfigurationRecord over in `csd-0` (which the decoder wants
     * verbatim), others deliver the VPS/SPS/PPS as Annex-B NALs split across
     * `csd-0`/`csd-1`/`csd-2`.  Both are accepted; the wireless CSD record needs
     * the bare VPS/SPS/PPS, and {@link #hvcCRecord()} keeps the ready-made record
     * when there was one.
     */
    private void readHevcCodecSpecificData(MediaFormat format) {
        final byte[] csd0 = format.containsKey("csd-0")
                ? toBytes(format.getByteBuffer("csd-0")) : null;
        final byte[] csd1 = format.containsKey("csd-1")
                ? toBytes(format.getByteBuffer("csd-1")) : null;
        final byte[] csd2 = format.containsKey("csd-2")
                ? toBytes(format.getByteBuffer("csd-2")) : null;
        if (WirelessScreenHeader.looksLikeHvcCRecord(csd0)) {
            mHvcC = csd0;
        }
        ingestHevcParameterSets(csd0);
        ingestHevcParameterSets(csd1);
        ingestHevcParameterSets(csd2);
        mStats.csd0 = mCsd0 == null ? -1 : mCsd0.length;
        mStats.csd1 = mCsd1 == null ? -1 : mCsd1.length;
        mStats.vps = mVps == null ? -1 : mVps.length;
        Log.i(TAG, "encoder output format: " + format);
        Log.i(TAG, "HEVC VPS: " + describe(mVps) + ", SPS: " + describe(mCsd0)
                + ", PPS: " + describe(mCsd1)
                + ", hvcC: " + (mHvcC == null ? "none" : mHvcC.length + "B"));
        Log.i(TAG, "HEVC SPS hex: " + hex(mCsd0));
        mStats.outputFormat = String.valueOf(format);
        writeDumpBytes(mVps);
        writeDumpBytes(mCsd0);
        writeDumpBytes(mCsd1);
    }

    /** Pulls VPS(32)/SPS(33)/PPS(34) out of one csd blob (Annex-B or a single NAL). */
    private void ingestHevcParameterSets(byte[] csd) {
        if (csd == null || WirelessScreenHeader.looksLikeHvcCRecord(csd)) {
            return;
        }
        for (byte[] nal : WirelessScreenHeader.nalUnits(csd)) {
            switch (WirelessScreenHeader.hevcNalType(nal)) {
                case 32:
                    if (mVps == null) {
                        mVps = nal;
                        Log.i(TAG, "VPS from csd: " + nal.length + "B");
                    }
                    break;
                case 33:
                    if (mCsd0 == null) {
                        mCsd0 = nal;
                        Log.i(TAG, "SPS from csd: " + nal.length + "B");
                    }
                    break;
                case 34:
                    if (mCsd1 == null) {
                        mCsd1 = nal;
                        Log.i(TAG, "PPS from csd: " + nal.length + "B");
                    }
                    break;
                default:
                    break;
            }
        }
    }

    private static String describe(byte[] data) {
        return data == null ? "none" : data.length + "B";
    }

    /** True once everything the CSD record needs has been seen for the active codec. */
    private boolean parameterSetsComplete() {
        if (mVideoCodec == WirelessScreenHeader.CODEC_HEVC) {
            return mHvcC != null || (mCsd0 != null && mCsd1 != null && mVps != null);
        }
        return mCsd0 != null && mCsd1 != null;
    }

    private void readCodecSpecificDataFromBuffer(ByteBuffer buffer, MediaCodec.BufferInfo info) {
        // Both paths can deliver the parameter sets (format change AND a
        // BUFFER_FLAG_CODEC_CONFIG buffer); only write them to the dump once.
        if (mVideoCodec == WirelessScreenHeader.CODEC_HEVC) {
            if (mHvcC != null || (mVps != null && mCsd0 != null && mCsd1 != null)) {
                return;
            }
            final int hevcOffset = Math.max(0, info.offset);
            final byte[] hevcData = new byte[info.size];
            buffer.position(hevcOffset);
            buffer.limit(hevcOffset + info.size);
            buffer.get(hevcData);
            ingestHevcParameterSets(hevcData);
            mStats.csd0 = mCsd0 == null ? -1 : mCsd0.length;
            mStats.csd1 = mCsd1 == null ? -1 : mCsd1.length;
            mStats.vps = mVps == null ? -1 : mVps.length;
            writeDumpBytes(hevcData);
            return;
        }
        if (mCsd0 != null && mCsd1 != null) {
            return;
        }
        final int offset = Math.max(0, info.offset);
        final byte[] data = new byte[info.size];
        buffer.position(offset);
        buffer.limit(offset + info.size);
        buffer.get(data);
        if (mCsd0 == null) {
            mCsd0 = data;
            mStats.csd0 = data.length;
        } else if (mCsd1 == null) {
            mCsd1 = data;
            mStats.csd1 = data.length;
        }
        writeDumpBytes(data);
    }

    private void writeDumpBytes(byte[] data) {
        final FileOutputStream dump = mDump;
        if (dump == null || data == null || data.length == 0) {
            return;
        }
        try {
            dump.write(data);
        } catch (IOException e) {
            Log.w(TAG, "dump write failed: " + e);
        }
    }

    private void writeDump(ByteBuffer buffer, MediaCodec.BufferInfo info) {
        final FileOutputStream dump = mDump;
        if (dump == null) {
            return;
        }
        try {
            final int offset = Math.max(0, info.offset);
            buffer.position(offset);
            buffer.limit(offset + info.size);
            final byte[] data = new byte[info.size];
            buffer.get(data);
            dump.write(data);
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "dump write failed: " + e);
        }
    }

    /**
     * Hands one encoded frame to the live sink, if any.
     *
     * A failing sink must never kill the encoder loop, so everything is caught and
     * reported through the stats line instead.
     */
    /**
     * With KEY_PREPEND_HEADER_TO_SYNC_FRAMES the encoder carries the parameter
     * sets inside the keyframe instead of handing them over as csd-0/csd-1, so
     * the avcC config packet we owe the receiver came out empty ("avcC sent: 11B
     * (sps=0B pps=0B)", measured 2026-09-26 §12.34) — and a receiver that never
     * gets a config packet has to rely on in-band headers.  Pull SPS (NAL 7) and
     * PPS (NAL 8) out of the Annex-B stream, without their start codes, so the
     * normal config packet can be sent again.  For HEVC the same trick pulls
     * VPS(32)/SPS(33)/PPS(34) instead.
     */
    private void extractParameterSets(byte[] data) {
        if (mVideoCodec == WirelessScreenHeader.CODEC_HEVC) {
            for (byte[] nal : WirelessScreenHeader.nalUnits(data)) {
                switch (WirelessScreenHeader.hevcNalType(nal)) {
                    case 32:
                        if (mVps == null) {
                            mVps = nal;
                            mStats.vps = nal.length;
                            Log.i(TAG, "VPS from keyframe: " + nal.length + "B");
                        }
                        break;
                    case 33:
                        if (mCsd0 == null) {
                            mCsd0 = nal;
                            mStats.csd0 = nal.length;
                            Log.i(TAG, "SPS from keyframe: " + nal.length + "B");
                        }
                        break;
                    case 34:
                        if (mCsd1 == null) {
                            mCsd1 = nal;
                            mStats.csd1 = nal.length;
                            Log.i(TAG, "PPS from keyframe: " + nal.length + "B");
                        }
                        break;
                    default:
                        break;
                }
            }
            return;
        }
        int i = 0;
        while (i + 4 < data.length) {
            int nalAt = -1;
            if (data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 1) {
                nalAt = i + 3;
            } else if (data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 0 && data[i + 3] == 1) {
                nalAt = i + 4;
            }
            if (nalAt < 0) {
                i++;
                continue;
            }
            final int type = data[nalAt] & 0x1F;
            if (type != 7 && type != 8) {
                i = nalAt + 1;
                continue;
            }
            int end = data.length;
            for (int j = nalAt + 1; j + 3 <= data.length; j++) {
                if (data[j] == 0 && data[j + 1] == 0
                        && (data[j + 2] == 1
                            || (data[j + 2] == 0 && j + 3 < data.length && data[j + 3] == 1))) {
                    end = j;
                    break;
                }
            }
            final byte[] nal = java.util.Arrays.copyOfRange(data, nalAt, end);
            if (type == 7 && mCsd0 == null) {
                mCsd0 = nal;
                mStats.csd0 = nal.length;
                Log.i(TAG, "SPS from keyframe: " + nal.length + "B");
            } else if (type == 8 && mCsd1 == null) {
                mCsd1 = nal;
                mStats.csd1 = nal.length;
                Log.i(TAG, "PPS from keyframe: " + nal.length + "B");
            }
            i = end;
        }
    }

    private void dispatchFrame(ByteBuffer buffer, MediaCodec.BufferInfo info) {
        final FrameSink sink = mFrameSink;
        if (sink == null) {
            return;
        }
        try {
            final int offset = Math.max(0, info.offset);
            buffer.position(offset);
            buffer.limit(offset + info.size);
            final byte[] data = new byte[info.size];
            buffer.get(data);
            if (!parameterSetsComplete()) {
                extractParameterSets(data);
            }
            sink.onFrame(data, info.flags, info.presentationTimeUs);
        } catch (RuntimeException e) {
            mStats.error = "frame sink: " + e;
            Log.w(TAG, "frame sink failed: " + e);
        }
    }

    public void stop() {
        mRunning.set(false);
        final Thread nudge = mNudgeThread;
        mNudgeThread = null;
        if (nudge != null) {
            nudge.interrupt();
        }
        final Thread thread = mDrainThread;
        mDrainThread = null;
        if (thread != null) {
            try {
                thread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (mVirtualDisplay != null) {
            final com.okcar.link.port.MirrorDisplayFactory factory = mDisplayFactory;
            mDisplayFactory = null;
            try {
                if (factory != null) {
                    /* App mode: the factory keeps the display (and the app on it) and only detaches
                     * our encoder surface, so the next session reuses the same display id. */
                    factory.releaseFromSource(mVirtualDisplay);
                } else {
                    mVirtualDisplay.release();
                }
            } catch (RuntimeException e) {
                Log.w(TAG, "virtual display release failed: " + e);
            }
            mVirtualDisplay = null;
        }
        if (mCodec != null) {
            try {
                mCodec.stop();
            } catch (RuntimeException e) {
                Log.w(TAG, "codec stop failed: " + e);
            }
            try {
                mCodec.release();
            } catch (RuntimeException e) {
                Log.w(TAG, "codec release failed: " + e);
            }
            mCodec = null;
        }
        if (mInputSurface != null) {
            mInputSurface.release();
            mInputSurface = null;
        }
        if (mDump != null) {
            try {
                mDump.close();
            } catch (IOException ignored) {
                /* nothing useful to do */
            }
            mDump = null;
        }
        /* The keyframe count is the one fact that decides whether a receiver can ever start
         * decoding: a stream of only non-key frames leaves every fresh decoder black (field
         * question 2026-09-30 — the sender logged key=true for frame 1 and then key=false on
         * every 60th sample, which cannot distinguish "no IDR after the first" from "the
         * sample simply missed them"). */
        Log.i(TAG, "mirror/encode stopped (" + mimeFor(mVideoCodec) + "): "
                + mStats.frames + " frames, " + mStats.keyFrames + " keyframes, "
                + mStats.bytes + "B");
    }
}
