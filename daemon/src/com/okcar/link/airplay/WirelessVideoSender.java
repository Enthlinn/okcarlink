/*
 * OkcarOS link stack — the wireless AirPlay screen-stream sender (the video face of
 * step ④/⑤ over Wi-Fi).
 *
 * Given the video `dataPort` the accessory answered in its stream-110 SETUP, the
 * `streamConnectionID` we sent, and the pair-verify'd AirPlayPairing, this class:
 *
 *   1. opens a plain TCP connection to `<accessory>:<dataPort>` (the accessory only
 *      listens — the pairing-crypto notes §8 gotcha 11);
 *   2. writes the codec-config (CSD) record first: 128-byte header with byte 4 = 1,
 *      PLAINTEXT ISO-BMFF sample entry (`hvc1`+`hvcC` for HEVC, `avc1`+`avcC` for
 *      H.264) — exactly the shape the real wireless capture carried;
 *   3. seals every video frame with ChaCha20-Poly1305: key =
 *      `pairing.dataStreamOutputKey(streamConnectionID)`, nonce = 4 zero bytes || u64
 *      LE frame counter (counter advances per *frame*, never for config/heartbeat),
 *      AAD = the whole 128-byte header, payload = 4-byte big-endian length-prefixed
 *      NAL units (Annex-B converted);
 *   4. sends an empty type-2 heartbeat about once per second (the real sender sent
 *      one per second carrying nothing at all);
 *   5. relies on MirrorVideoSource's own 2 s requestSyncFrame keep-alive plus
 *      KEY_PREPEND_HEADER_TO_SYNC_FRAMES, so a static screen still re-emits an IDR
 *      with the parameter sets in-band — the same behaviour the wired head-unit path
 *      proved in the field.
 *
 * Everything is caught: a dead dataPort or a failing encode stops this stream and
 * is reported, but can never take the AirPlay session (or the type-130 tunnel) down.
 *
 * Wire layouts and the crypto come from the real capture
 * (the internal notes, the session-analysis notes
 * §3.4, the pairing-crypto notes §6) and are implemented in WirelessScreenHeader.
 */

package com.okcar.link.airplay;

import android.content.Context;
import android.media.MediaCodec;
import android.os.SystemClock;
import android.util.Log;

import com.okcar.link.LinkState;
import com.okcar.link.media.FrameWatch;
import com.okcar.link.media.MirrorVideoSource;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public final class WirelessVideoSender {

    private static final String TAG = "OkCarLink/WirelessVideo";

    private static final int CONNECT_TIMEOUT_MS = 4000;

    /**
     * Lab-only mirror of the wire, in its PLAINTEXT view: each 128-byte record header followed by
     * the record's pre-sealing bytes (the sample entry for the config record, the length-prefixed
     * access unit for a frame).  Sealing happens after this point, so what lands here is what the
     * accessory decrypts into — the only way to check the NAL framing and the HEVC config record
     * without a decoder on the host.
     */
    private java.io.FileOutputStream mPlainDump;
    private long mPlainDumped;
    private static final long PLAIN_DUMP_LIMIT = 4L * 1024 * 1024;

    /** Lab-only encoder name for the mirror source; empty keeps the platform default. */
    private String mEncoderName = "";

    public void setEncoderName(String name) {
        mEncoderName = name == null ? "" : name;
    }

    /** Starts mirroring the wire to {@code path}; null or empty disables it. */
    public void setPlainDump(String path) {
        if (path == null || path.isEmpty()) {
            return;
        }
        try {
            mPlainDump = new java.io.FileOutputStream(path, false);
            mState.log(TAG, "plaintext wire dump -> " + path);
        } catch (Throwable t) {
            mState.log(TAG, "plaintext wire dump unavailable: " + t);
        }
    }

    private void dumpPlain(byte[] header, byte[] body) {
        if (mPlainDump == null || mPlainDumped >= PLAIN_DUMP_LIMIT) {
            return;
        }
        try {
            mPlainDump.write(header);
            mPlainDump.write(body);
            mPlainDumped += header.length + body.length;
            mPlainDump.flush();
        } catch (Throwable t) {
            mPlainDump = null;
        }
    }
    /** Fallback frame rate when the accessory advertises none (see chooseFps). */
    private static final int DEFAULT_FPS = WirelessScreenHeader.FPS_30;

    /**
     * SO_SNDBUF for the video socket — the in-flight (unread) video we allow ourselves.
     * See {@link #connect()}: this is what keeps the picture from lagging the realtime
     * audio by whatever the accessory has not consumed yet.  64 KiB ≈ a dozen frames.
     */
    private static final int SEND_BUFFER_BYTES = 64 * 1024;
    /** The real sender's type-2 heartbeats arrived at ≈1 Hz. */
    private static final long HEARTBEAT_INTERVAL_MS = 1000L;
    private static final int MAX_WRITE_FAILURES = 3;
    /**
     * Watchdog check interval.  The granularity does not affect correctness, only how promptly a dead peer is noticed.
     */
    private static final long STALL_POLL_MS = 250L;
    /** How long a single write may hang before the peer is considered dead; overridable via persist.okcar.video_stall_ms, <=0 disables. */
    private static final String PROP_STALL_MS = "persist.okcar.video_stall_ms";

    private final Context mContext;
    private final String mAccessoryAddress;
    private final int mDataPort;
    private final long mStreamConnectionId;
    private final AirPlayPairing mPairing;
    private final int mCodec;
    /* Not final: in CarWith-mirror mode startEncoder() re-pins them to the mirrored display's own
     * geometry (2026-10-08), because what the accessory negotiated is not necessarily the picture
     * size — a receiver in a small window advertises its window instead. */
    private volatile int mWidth;
    private volatile int mHeight;
    private final int mFps;
    /** Timestamp (µs) of the last frame actually sent; used to enforce the frame-rate ceiling on the send cadence. */
    private long mLastSentPtsUs = Long.MIN_VALUE;
    /** Frames dropped because they exceeded the negotiated frame rate; printed with the stats line. */
    private long mDroppedByFps;
    private final LinkState mState = LinkState.get();
    private final AtomicBoolean mStopped = new AtomicBoolean();

    private volatile Socket mSocket;
    private OutputStream mOut;
    private byte[] mKey;
    private volatile MirrorVideoSource mVideo;
    private Thread mHeartbeatThread;
    private volatile Thread mPacerThread;
    /**
     * "Peer stopped reading" protection: a dedicated thread watches whether a write has hung and **never touches
     * mWriteLock** (while a write hangs that lock is held by the blocked writer, so anyone grabbing it gets stuck
     * too).  A stall verdict closes the socket outright — the blocked write() then returns immediately with an
     * IOException, the sender thread exits, and the liveness poll above sees isStreaming()=false, tears the
     * session down and re-arms the listener (no need to kill the process).
     */
    private final WriteStallGuard mStallGuard = new WriteStallGuard(readStallMs());
    private Thread mStallThread;

    private volatile boolean mRunning;
    private final Object mWriteLock = new Object();

    /** Pending-frame queue plus its lock (encoder thread enqueues, pacer thread dequeues). */
    private final java.util.ArrayDeque<PendingFrame> mPending = new java.util.ArrayDeque<>();
    private final Object mPendingLock = new Object();
    /** Current video delay (ms), refreshed live from {@link VideoPacer#DELAY_PROPERTY}. */
    private volatile long mDelayMs = VideoPacer.DEFAULT_DELAY_MS;
    private volatile long mDroppedByPace;

    /* Content-liveness metrics: the count and longest duration of consecutive frames "byte-identical to the previous
     * one".  The send-side frame rate is steady anyway, so a stuttering picture can only mean the **content** is not
     * updating (composition starved / app not redrawing), while duplicate frames are still sent as usual.  With these
     * two numbers the next field run can tell "our pipeline stalled" from "the upstream picture stopped". */
    private long mIdenticalRun;
    private long mMaxIdenticalRun;
    private long mIdenticalRunSinceMs;
    private long mMaxIdenticalRunMs;
    private int mPreviousFrameHash;
    private long mFrameCounter;
    private long mFrames;
    private long mBytes;
    private long mConfigRecords;
    private long mHeartbeats;
    private long mDeferredConfigs;
    private int mWriteFailures;
    /** SystemClock.elapsedRealtime() when the encoder started, for the fps measurement. */
    private volatile long mStartedAtMs;
    private boolean mConfigSent;
    /** Written by the event thread on requestUI, read by the encoder thread — hence volatile. */
    private volatile boolean mParameterSetsInBand;
    private volatile boolean mRawConfig;
    /** CarWith source mode: handed to the encoder so the surface goes to the component, not a display. */
    private volatile MirrorVideoSource.ExternalDisplay mExternalDisplay;
    /**
     * CarWith mirror mode (2026-10-08): mirror this display into the encoder instead of handing the
     * surface out.  -1 = off, and it takes precedence over {@link #mExternalDisplay}.
     */
    private volatile int mMirrorSourceDisplay = -1;
    /**
     * Cast-link source (2026-10-09): in coordinated mode the APK inside CarWith takes over
     * CarWith's cast surface and pushes the encoded frames over the {@code okcar-cast} socket.
     * This sender then becomes that socket's sink, because the code path that used to own the
     * sink was the wired/local one — a coordinated wireless session therefore sent nothing but
     * heartbeats and the receiver painted a black screen (measured on 217 + the emulator:
     * {@code Receive: video packets=5 bytes=640} for a whole session while audio flowed).
     */
    private volatile boolean mCastSource;
    private volatile byte[] mCastSps;
    private volatile byte[] mCastPps;
    private volatile long mCastConfigs;
    private long mCastFrames;
    private boolean mCastAudioNoted;
    private volatile String mError = "";

    public WirelessVideoSender(Context context, String accessoryAddress, int dataPort,
                               long streamConnectionId, AirPlayPairing pairing, int codec,
                               int width, int height) {
        this(context, accessoryAddress, dataPort, streamConnectionId, pairing, codec,
                width, height, DEFAULT_FPS);
    }

    /**
     * @param fps the frame rate already resolved by the caller — the accessory's advertisement
     *            snapped by {@link WirelessScreenHeader#chooseFps} and, where applicable, the
     *            user's explicit {@code wired_fps}/{@code wireless_fps} override.  30 when the
     *            accessory advertised none.
     */
    public WirelessVideoSender(Context context, String accessoryAddress, int dataPort,
                               long streamConnectionId, AirPlayPairing pairing, int codec,
                               int width, int height, int fps) {
        mContext = context.getApplicationContext();
        mAccessoryAddress = accessoryAddress;
        mDataPort = dataPort;
        mStreamConnectionId = streamConnectionId;
        mPairing = pairing;
        mCodec = codec == WirelessScreenHeader.CODEC_HEVC
                ? WirelessScreenHeader.CODEC_HEVC : WirelessScreenHeader.CODEC_AVC;
        mWidth = width;
        mHeight = height;
        /* Do NOT snap again: chooseFps() collapses anything below 50 to 30, which would silently
         * throw away an explicit 45 fps from the panel and make the knob look broken.  The caller
         * (WirelessAirPlayClient.resolveVideoFps) has already done the snapping for the automatic
         * path; here we only guard against a nonsense value. */
        mFps = fps > 0 ? fps : WirelessScreenHeader.chooseFps(0);
    }

    /**
     * Emit the bare avcC/hvcC record instead of the ISO-BMFF sample entry.  Only
     * for A/B testing: the capture proves the sample entry is what the real sender
     * put on the wire, so the default is the sample entry.
     */
    public void setRawConfig(boolean raw) {
        mRawConfig = raw;
    }

    /**
     * CarWith source mode: when set, the encoder's input surface is published to the CarWith
     * component instead of being attached to a mirror display (see
     * {@link MirrorVideoSource#useExternalDisplay}).
     */
    public void setExternalDisplayProvider(MirrorVideoSource.ExternalDisplay display) {
        mExternalDisplay = display;
    }

    /**
     * CarWith mirror mode (2026-10-08): mirror {@code sourceDisplayId} (CarWith's own cast display)
     * into this stream's encoder.  Set before the stream starts; takes precedence over
     * {@link #setExternalDisplayProvider}.  Measured on 217: this is the only route that yields
     * CarWith's wired-ICCOA picture — see {@link com.okcar.link.port.DisplayMirror}.
     */
    public void setMirrorSourceDisplay(int sourceDisplayId) {
        mMirrorSourceDisplay = sourceDisplayId;
    }

    /**
     * The accessory asked for a keyframe (`{type=forceKeyFrame}`): its decoder just recovered or
     * was recreated and would otherwise show stale/black frames until the next scheduled IDR.
     */
    public void requestSyncFrame() {
        final MirrorVideoSource video = mVideo;
        if (video == null || !mRunning) {
            return;
        }
        video.requestSyncFrame();
        mState.log(TAG, "keyframe requested by the accessory ({type=forceKeyFrame})");
    }

    /**
     * The accessory asked for the UI again (`{type=requestUI}`, sent when it re-enters CarPlay).
     * Its freshly created decoder stays black until it sees both the CSD and an IDR, so clear the
     * config latch, flag the header as carrying parameter sets in band, and force the keyframe
     * now instead of waiting for the 2 s keep-alive.
     */
    public void requestUi() {
        final MirrorVideoSource video = mVideo;
        if (video == null || !mRunning) {
            return;
        }
        synchronized (mWriteLock) {
            mConfigSent = false;
        }
        mParameterSetsInBand = true;
        video.requestSyncFrame();
        mState.log(TAG, "accessory asked for the UI again — re-sending CSD + IDR");
    }

    public boolean isRunning() {
        return mRunning;
    }

    public String error() {
        return mError;
    }

    /** Blocking: connects, starts the encoder and then serves heartbeats until stop(). */
    public void run() {
        if (mRunning || mStopped.get()) {
            return;
        }
        mState.log(TAG, "starting: codec=" + (mCodec == WirelessScreenHeader.CODEC_HEVC ? "HEVC" : "H.264")
                + ' ' + mWidth + 'x' + mHeight + " -> " + mAccessoryAddress + ':' + mDataPort
                + " streamConnectionID=" + Long.toUnsignedString(mStreamConnectionId));
        try {
            mKey = mPairing.dataStreamOutputKey(mStreamConnectionId);
            if (!connect()) {
                mError = "no connection to the video dataPort " + mDataPort;
                return;
            }
            mRunning = true;
            mStartedAtMs = SystemClock.elapsedRealtime();
            startHeartbeat();
            startStallWatchdog();
            startEncoder();
            startPacer();
            while (mRunning) {
                try {
                    Thread.sleep(500L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        } catch (Throwable t) {
            mError = String.valueOf(t);
            mState.log(TAG, "video sender failed: " + t);
            Log.w(TAG, "wireless video sender failed", t);
        } finally {
            stop();
        }
    }

    /**
     * The dataPort lives on the same accessory the control connection is already up
     * to, so the candidate list the control channel built is reused here (link-local
     * IPv6 needs its `%p2p0` scope, and only the accessory listens).
     */
    /** Wired carrier interface (usb0); null on the Wi-Fi paths.  See WirelessAirPlayClient. */
    private String mCarrierInterface;

    /** Pins the interface the accessory is reachable through (the NCM function on a wired session). */
    /**
     * Which WebUI knob set this session must obey.
     *
     * <p>The wired sender is the only one whose carrier is the NCM interface, so it reads
     * {@code wired_bitrate}/{@code wired_fps} while the wireless sender keeps
     * {@code wireless_bitrate}/{@code wireless_fps}.  This was documented but never wired up: every load
     * site below hard-coded TRANSPORT_WIRELESS, so the panel's "wired" sliders did nothing to a wired
     * CarPlay stream (reported 2026-10-07).
     */
    private String qosTransport() {
        final String c = mCarrierInterface;
        if (c != null && (c.startsWith("usb") || c.equals("ncm0"))) {
            return com.okcar.link.media.VideoQos.TRANSPORT_WIRED;
        }
        return com.okcar.link.media.VideoQos.TRANSPORT_WIRELESS;
    }

    public void setCarrierInterface(String carrierInterface) {
        mCarrierInterface = carrierInterface;
    }

    private boolean connect() {
        final List<String> candidates =
                WirelessAirPlayClient.candidates(mAccessoryAddress, mCarrierInterface);
        mState.log(TAG, "trying video dataPort candidates " + candidates + ":" + mDataPort);
        for (String host : candidates) {
            if (mStopped.get()) {
                return false;
            }
            final Socket socket = new Socket();
            try {
                socket.connect(new InetSocketAddress(host, mDataPort), CONNECT_TIMEOUT_MS);
                socket.setTcpNoDelay(true);
                socket.setSoTimeout(0);
                /* Keep the video backlog bounded.  The accessory reads this socket at its
                 * own pace; when it is slower than we encode, the surplus goes into the
                 * kernel send/receive buffers and our writes keep succeeding, so the
                 * picture silently falls seconds behind the realtime audio stream (and the
                 * accessory keeps playing that stale backlog after the phone stops) — see
                 * the codec-byte notes §A/V.  A small SO_SNDBUF turns
                 * "the accessory has not read yet" into a blocking write, which stalls the
                 * encoder drain loop and makes MediaCodec drop stale frames at the input
                 * surface: latency stays bounded, freshness wins over frame count.  64 KiB
                 * still allows >1 MB/s at a 20 ms RTT, far above this stream's ~100 KB/s. */
                try {
                    socket.setSendBufferSize(SEND_BUFFER_BYTES);
                } catch (Throwable t) {
                    mState.log(TAG, "send buffer not settable: " + t);
                }
                mSocket = socket;
                mOut = new BufferedOutputStream(socket.getOutputStream(), 1 << 16);
                mState.log(TAG, "video dataPort " + mDataPort + " connected via " + host
                        + " (SO_SNDBUF=" + socket.getSendBufferSize() + "B)");
                return true;
            } catch (Throwable t) {
                closeQuietly(socket);
                mState.log(TAG, "  video " + host + ':' + mDataPort + " -> " + t);
            }
        }
        return false;
    }

    /**
     * Takes the APK's frames from the {@code okcar-cast} socket and feeds them into this stream.
     *
     * <p>Why here and not at the old wiring point: {@code OkCarLinkService.startScreenStream} attaches
     * the sink for the wired/local AirPlay session only.  A coordinated <em>wireless</em> session runs
     * this sender instead, so the APK's frames were accepted (and counted) by nobody — the live stream
     * carried heartbeats only and the accessory showed black.  One sink is enough: the frames are
     * already encoded by the APK, so they go straight through the same pacer/sealer as our own
     * encoder's output ({@link #sendFrame}), including the Annex-B to length-prefixed conversion.
     */
    private void armCastSource() {
        if (mCastSource) {
            return;
        }
        mCastSource = true;
        com.okcar.link.castlink.CastLinkServer.get().attach(
                new com.okcar.link.castlink.CastLinkServer.Sink() {
                    @Override
                    public void config(byte[] sps, byte[] pps) {
                        mCastSps = ScreenStreamWriter.stripStartCode(sps);
                        mCastPps = ScreenStreamWriter.stripStartCode(pps);
                        mCastConfigs++;
                        pinCastGeometry();
                        mState.log(TAG, "cast source: config sps=" + size(sps) + " pps="
                                + size(pps) + " (CarWith cast display geometry "
                                + mWidth + 'x' + mHeight + ')');
                    }

                    @Override
                    public void frame(byte[] data, boolean keyFrame, long ptsMs) {
                        mCastFrames++;
                        /* presentationTimeUs: the APK sends the encoder's pts in ms. */
                        sendFrame(data, keyFrame, ptsMs * 1000L);
                    }

                    @Override
                    public void audio(byte[] data, long ptsMs, int rate, int channels) {
                        /* The session's own audio path (REMOTE_SUBMIX -> WirelessAudioSender) is
                         * already live and decrypts fine on the accessory; forwarding this second
                         * copy would duplicate the stream.  Say it once, then stay quiet. */
                        if (!mCastAudioNoted) {
                            mCastAudioNoted = true;
                            mState.log(TAG, "cast source: audio frames ignored — the session's"
                                    + " own audio path carries them");
                        }
                    }
                });
        mState.log(TAG, "cast source armed: video frames come from okcar-cast (APK in CarWith)");
    }

    /**
     * Pins the record header's geometry to CarWith's cast display.  The session's negotiated size is
     * the accessory's window, not necessarily the picture: with the APK as the source the encoder runs
     * at the cast display's size, and a header that disagrees makes the accessory scale a picture whose
     * SPS it is already decoding at another size.
     */
    private void pinCastGeometry() {
        try {
            final int display = com.okcar.link.port.DisplayMirror.findCarwithCastDisplay(mContext);
            if (display < 0) {
                return;
            }
            final int[] size = com.okcar.link.port.DisplayMirror.sizeOf(mContext, display);
            if (size != null && size[0] > 0 && size[1] > 0
                    && (size[0] != mWidth || size[1] != mHeight)) {
                mState.log(TAG, "cast source: record geometry " + mWidth + 'x' + mHeight
                        + " -> " + size[0] + 'x' + size[1] + " (CarWith cast display " + display + ')');
                mWidth = size[0];
                mHeight = size[1];
                com.okcar.link.port.Ports.setTouchDisplay(display, mWidth, mHeight);
            }
        } catch (Throwable t) {
            mState.log(TAG, "cast source: geometry probe failed: " + t);
        }
    }

    /** Owns the encoder; MirrorVideoSource is reused exactly as the wired path uses it. */
    private void startEncoder() {
        final MirrorVideoSource video = new MirrorVideoSource(mContext);
        video.setEncoderName(mEncoderName);
        /* Coordinated mode (2026-10-09): the enable marker is set while the APK inside CarWith is
         * the picture source — it takes CarWith's cast surface and encodes it.  In that mode this
         * sender takes its frames from the okcar-cast socket instead of from its own encoder; see
         * armCastSource(). */
        final boolean castSource = com.okcar.link.castlink.CastLinkServer.enabled();
        /* CarWith as the picture source (2026-10-07): the encoder input surface is handed to the
         * CarWith component instead of being attached to a mirror display, so CarWith's own virtual
         * display becomes the content and everything downstream (frame sink, pacer, RTP) is
         * unchanged.  Set before start(); a null provider keeps the mirroring behaviour. */
        int mirrorDisplay = mMirrorSourceDisplay;
        final MirrorVideoSource.ExternalDisplay carwith = mExternalDisplay;
        if (mirrorDisplay == com.okcar.link.port.DisplayMirror.AUTO) {
            mirrorDisplay = com.okcar.link.port.DisplayMirror.findCarwithCastDisplay(mContext);
            if (mirrorDisplay < 0) {
                mState.log(TAG, "carwith mirror auto: CarWith's cast display not found right now"
                        + " (is the wired ICCOA session up?) — falling back to the plain source");
            }
        }
        if (mirrorDisplay >= 0) {
            /* Pin the picture geometry to the mirrored display instead of what this accessory
             * negotiated: a receiver started in a small window advertises its window (measured
             * 2400x2400 on 2026-10-08) and that stretched CarWith's car UI. */
            final int[] srcSize = com.okcar.link.port.DisplayMirror.sizeOf(mContext, mirrorDisplay);
            if (srcSize != null) {
                mWidth = srcSize[0];
                mHeight = srcSize[1];
            }
            video.setMirrorSourceDisplay(mirrorDisplay);
            /* Point the car's touches at the same display: this runs per session, so it follows the id
             * CarWith re-creates for every session (14 → 22 → … ) without the operator re-arming. */
            com.okcar.link.port.Ports.setTouchDisplay(mirrorDisplay, mWidth, mHeight);
            mState.log(TAG, "carwith source mode: mirroring CarWith's cast display " + mirrorDisplay
                    + " (" + mWidth + 'x' + mHeight + "), touch target follows");
        } else if (carwith != null) {
            video.useExternalDisplay(carwith);
            mState.log(TAG, "carwith source mode: this stream's encoder input surface goes to CarWith");
        }
        mVideo = video;
        video.setVideoCodec(mCodec);
        video.setFrameSink(new MirrorVideoSource.FrameSink() {
            @Override
            public void onFrame(byte[] data, int flags, long presentationTimeUs) {
                if (castSource) {
                    /* Coordinated mode: the APK owns CarWith's cast surface and its encoder, so our
                     * own encoder has no producer.  If it ever did emit a frame it would interleave a
                     * second H.264 stream (different SPS) into the same record stream and the
                     * accessory would decode neither — the reason this hand-off is one-way. */
                    return;
                }
                sendFrame(data, (flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0, presentationTimeUs);
            }
        });
        /* On a genuine stall (session up, an IDR has been requested and still no frame for a long time — the
         * criterion lives in FrameWatch, a static page does not count), end this stream: the client already watches
         * for "the video thread ended", the session watchdog then tears the session down and re-arms the listener,
         * and the accessory dialling again gets a fresh pipeline.  Reuse the existing path; add no new mechanism. */
        video.setFrameStallListener(new MirrorVideoSource.FrameStallListener() {
            @Override
            public void onFrameStall(long stalledMs) {
                mError = "video pipeline produced no frame for " + stalledMs + "ms";
                mState.log(TAG, mError + " despite IDR requests — stopping the video stream so"
                        + " the session watchdog can re-arm it");
                mRunning = false;
                closeQuietly(mSocket);
            }
        });
        video.configure(mWidth, mHeight, mFps, WirelessScreenHeader.bitrateForFps(mFps));
        /* The wireless side reads its own bitrate/frame-rate knobs (wired will read the wired_* set; the two do not
         * interfere).  Order is critical: configure() overwrites mBitrate/mFps from its arguments, so setQos before
         * configure is silently discarded — the log showed qos bitrate=2000000 while the encoder still ran at the
         * 8M/16M from bitrateForFps() (measured 2026-10-04: set 2000kbps, the emulator measured ~8.6Mbps).  setQos must
         * come after configure and before start(); the frame-rate cap is the same (applyFrameRateCap reads the mFps
         * set there). */
        try {
            video.setQos(com.okcar.link.media.VideoQos.load(qosTransport()));
            Log.i(TAG, "video qos applied from the " + qosTransport() + " knobs ("
                    + com.okcar.link.media.VideoQos.load(qosTransport()).describe() + ")");
        } catch (Throwable t) {
            Log.w(TAG, "cannot apply video qos: " + t);
        }
        video.setSessionUp(true);
        if (video.start(null)) {
            mState.log(TAG, "mirroring " + mWidth + 'x' + mHeight + '@' + mFps
                    + " via " + video.stats().codecName);
        } else {
            mError = "encoder start failed: " + video.stats().error;
            mState.log(TAG, mError);
            mRunning = false;
        }
        /* Coordinated mode is detected from the same enable marker the APK's session uses:
         * when it is set, the picture comes from the APK's encoder over okcar-cast, not from
         * this sender's own encoder (which has no producer once the APK takes the surface). */
        if (castSource) {
            armCastSource();
        }
    }

    /* ------------------------------------------------------------------ frames */

    /**
     * One sealed video frame.  Called inline from the encoder's drain loop, so it
     * must not block for long: the write is a few hundred kB at most and the socket
     * is `TCP_NODELAY`.
     */
    /** One pending frame: sealed and sent only at {@code dueMs}, which absorbs bursts and pins the A/V delay. */
    private static final class PendingFrame {
        final byte[] data;
        final boolean keyFrame;
        final long dueMs;

        PendingFrame(byte[] data, boolean keyFrame, long dueMs) {
            this.data = data;
            this.keyFrame = keyFrame;
            this.dueMs = dueMs;
        }
    }

    /**
     * The encoder thread only enqueues (it no longer writes the socket synchronously): sealing + writing is handed
     * to {@link #pacerLoop()}.  That way "the moment an app is opened on the phone" only blocks enqueueing, frames
     * do not pile up inside the encoder and get flushed all at once, and the delay is pinned to
     * {@link VideoPacer#DEFAULT_DELAY_MS}, which exactly compensates the emulator's audio delay.
     */
    private void sendFrame(byte[] data, boolean keyFrame, long presentationTimeUs) {
        if (!mRunning || data == null || data.length == 0) {
            return;
        }
        mLastSentPtsUs = presentationTimeUs;
        final long nowMs = SystemClock.elapsedRealtime();
        trackContentLiveness(data, nowMs);
        final long delayMs = mDelayMs;
        final int capacity = VideoPacer.capacityFrames(delayMs, mFps);
        synchronized (mPendingLock) {
            while (mPending.size() >= capacity) {
                final PendingFrame dropped = mPending.pollFirst();
                if (dropped == null) {
                    break;
                }
                mDroppedByPace++;
                mState.log(TAG, "video pace buffer full (" + capacity + " frames / "
                        + VideoPacer.capacityMs(delayMs) + "ms) — dropping the oldest frame");
                if (dropped.keyFrame) {
                    /* If the dropped frame was an IDR the reference chain is broken: request another immediately so the receiver does not smear until the next key frame. */
                    final MirrorVideoSource video = mVideo;
                    if (video != null) {
                        video.requestSyncFrame();
                    }
                }
            }
            mPending.addLast(new PendingFrame(data, keyFrame, VideoPacer.dueMs(nowMs, delayMs)));
            mPendingLock.notifyAll();
        }
    }

    /** Pending queue length (for logging; read under the lock to avoid racing the enqueueing thread). */
    private int mPendingSize() {
        synchronized (mPendingLock) {
            return mPending.size();
        }
    }

    /**
     * Record whether this frame is byte-identical to the previous one.  Identical means the content did not move
     * (static page / stalled picture), yet such frames are still sent at 30fps — so frame-rate stats show no
     * stutter while these two numbers do.
     */
    private void trackContentLiveness(byte[] data, long nowMs) {
        int hash = 1;
        for (int i = 0; i < data.length; i++) {
            hash = 31 * hash + data[i];
        }
        if (hash == mPreviousFrameHash && data.length > 0) {
            if (mIdenticalRun == 0) {
                mIdenticalRunSinceMs = nowMs;
            }
            mIdenticalRun++;
            final long runMs = nowMs - mIdenticalRunSinceMs;
            if (mIdenticalRun > mMaxIdenticalRun) {
                mMaxIdenticalRun = mIdenticalRun;
            }
            if (runMs > mMaxIdenticalRunMs) {
                mMaxIdenticalRunMs = runMs;
            }
        } else {
            mIdenticalRun = 0;
        }
        mPreviousFrameHash = hash;
    }

    /** Pacer thread: only due frames are actually sealed + sent; it also refreshes the delay property once per second (live-tunable in the field). */
    private void pacerLoop() {
        long lastPropertyReadMs = 0L;
        while (mRunning) {
            PendingFrame frame = null;
            synchronized (mPendingLock) {
                final long nowMs = SystemClock.elapsedRealtime();
                final PendingFrame head = mPending.peekFirst();
                if (head != null && VideoPacer.isDue(nowMs, head.dueMs)) {
                    frame = mPending.pollFirst();
                } else {
                    try {
                        mPendingLock.wait(VideoPacer.TICK_MS);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                if (nowMs - lastPropertyReadMs >= 1_000L) {
                    lastPropertyReadMs = nowMs;
                    try {
                        /* Single source of truth: the video.conf saved by the WebUI (video_delay_ms).  Read once per
                         * second, so a panel change takes effect within a second without reopening the session. */
                        final long requested = com.okcar.link.media.VideoQos.load(
                                qosTransport()).videoDelayMs;
                        final long clamped = VideoPacer.clampDelay(requested);
                        if (clamped != mDelayMs) {
                            mState.log(TAG, "video delay " + mDelayMs + "ms -> " + clamped + "ms");
                            mDelayMs = clamped;
                        }
                    } catch (Throwable ignored) {
                        /* Unreadable: keep the current value */
                    }
                }
            }
            if (frame != null) {
                sendFrameNow(frame.data, frame.keyFrame);
            }
        }
    }

    /** Actually seal a frame and send it to the accessory (formerly sendFrame; now called only by the pacer thread). */
    private void sendFrameNow(byte[] data, boolean keyFrame) {
        if (!mRunning || data == null || data.length == 0) {
            return;
        }
        /* Rate-limiting by dropping frames is **not** done here (lesson of 2026-10-03): dropping after encoding
         * breaks HEVC's reference chain — dropped frames are still referenced by later frames, and the receiver
         * smears until the next key frame (that is exactly what happened in the field).  To honour a negotiated
         * frame rate the throttle must come **before** encoding (render fewer frames into the encoder input
         * surface), not by discarding encoder output; until that is fixed, this stays as it is: send every frame
         * the encoder emits. */
        synchronized (mWriteLock) {
            if (!mRunning || mOut == null) {
                return;
            }
            if (!mConfigSent && !sendConfigLocked()) {
                mDeferredConfigs++;
                return;
            }
            try {
                /* MediaCodec hands out Annex-B access units; the wire carries 4-byte
                 * big-endian NAL lengths (capture: the decrypted first frame begins
                 * 00 00 04 0b). */
                final byte[] payload = WirelessScreenHeader.toLengthPrefixed(data);
                /* The record's size field is the length of the SEALED body that follows the
                 * header — plaintext + the 16-byte AEAD tag.  The accessory reads exactly
                 * `size` bytes and hands those bytes to ChaCha20-Poly1305, so a size that
                 * stops at the plaintext end drops the tag and every frame fails its MAC
                 * check (observed on the emulator: "screen stream ended type=110 reason=mac check
                 * in ChaCha20Poly1305 failed").  The capture agrees: the first frame's size
                 * field (1055) equals the receiver's `sealed`, 16 more than its `plain`. */
                final byte[] header = WirelessScreenHeader.frameHeader(
                        WirelessScreenHeader.sealedLength(payload.length),
                        WirelessScreenHeader.timestamp3232(SystemClock.elapsedRealtimeNanos()),
                        mParameterSetsInBand);
                final byte[] sealed = AirPlayPairing.chachaSeal(mKey,
                        AirPlayPairing.nonceForCounter(mFrameCounter), payload, header);
                mParameterSetsInBand = false;
                dumpPlain(header, payload);
                mStallGuard.begin(SystemClock.elapsedRealtime());
                mOut.write(header);
                mOut.write(sealed);
                mOut.flush();
                mStallGuard.end();
                mFrameCounter++;
                mFrames++;
                mBytes += header.length + sealed.length;
                mWriteFailures = 0;
                if ((mFrames % 60) == 1) {
                    final MirrorVideoSource.Stats sourceStats =
                            mVideo == null ? null : mVideo.stats();
                    mState.log(TAG, "sent " + mFrames + " frames (" + mBytes + "B, droppedByFps="
                            + mDroppedByFps
                            + (sourceStats == null ? "" : ", droppedByBacklog="
                                    + sourceStats.droppedByBacklog + ", maxGapMs="
                                    + sourceStats.maxGapMs + ", paceMs=" + mDelayMs
                                    + ", queued=" + mPendingSize() + ", droppedByPace="
                                    + mDroppedByPace + ", dupRun=" + mMaxIdenticalRun
                                    + "/" + mMaxIdenticalRunMs + "ms, stalledMs="
                                    + (android.os.SystemClock.elapsedRealtime()
                                            - sourceStats.lastFrameMs)
                                    + (FrameWatch.stuck(sourceStats.sessionUp,
                                            android.os.SystemClock.elapsedRealtime(),
                                            sourceStats.lastFrameMs, sourceStats.lastIdrRequestMs,
                                            FrameWatch.WARN_MS) ? " STALLED" : ""))
                            + ") in " + elapsedMs()
                            + "ms = " + measuredFps() + " fps, last "
                            + payload.length + "B plain / " + sealed.length + "B sealed, key="
                            + keyFrame + ", counter=" + mFrameCounter);
                    if (sourceStats != null) {
                        sourceStats.maxGapMs = 0;
                    }
                    mMaxIdenticalRun = 0;
                    mMaxIdenticalRunMs = 0;
                }
            } catch (IOException e) {
                onWriteFailure(e);
            } catch (RuntimeException e) {
                onWriteFailure(e);
            }
        }
    }

    /**
     * The type-1 config record: PLAINTEXT sample entry, written once before the
     * first frame (and again if a new CSD ever shows up).  Returns false while the
     * encoder has not produced the parameter sets yet, in which case the frame is
     * skipped rather than sent without a configured decoder.
     */
    private boolean sendConfigLocked() {
        final MirrorVideoSource video = mVideo;
        if (video == null || mOut == null) {
            return false;
        }
        /* Cast source: the parameter sets arrive in the APK's config frame, because the APK's
         * encoder is the one that produced them.  Our own encoder's csd would describe a stream
         * nobody sends. */
        final byte[] castSps = mCastSps;
        final byte[] castPps = mCastPps;
        final boolean fromCast = mCastSource && castSps != null && castPps != null;
        final byte[] csd = WirelessScreenHeader.configPayload(mCodec,
                fromCast ? null : video.parameterSetVps(),
                fromCast ? castSps : video.parameterSet0(),
                fromCast ? castPps : video.parameterSet1(),
                fromCast ? null : video.hvcCRecord(),
                mWidth, mHeight, mRawConfig);
        if (csd == null || csd.length == 0) {
            if ((mDeferredConfigs % 30) == 0) {
                mState.log(TAG, "config record deferred: CSD not available yet");
            }
            return false;
        }
        try {
            final byte[] header = WirelessScreenHeader.configHeader(mCodec, csd.length,
                    WirelessScreenHeader.timestamp3232(SystemClock.elapsedRealtimeNanos()),
                    mWidth, mHeight);
            dumpPlain(header, csd);
            mStallGuard.begin(SystemClock.elapsedRealtime());
            mOut.write(header);
            mOut.write(csd);
            mOut.flush();
            mStallGuard.end();
        } catch (IOException e) {
            onWriteFailure(e);
            return false;
        }
        mConfigSent = true;
        /* The real sender set header[5] = 0x10 on the first frame after a config record. */
        mParameterSetsInBand = true;
        mConfigRecords++;
        mState.log(TAG, "config record sent: " + csd.length + "B "
                + (mRawConfig ? "raw" : "ISO-BMFF sample entry") + " ("
                + (mCodec == WirelessScreenHeader.CODEC_HEVC ? "HEVC" : "H.264") + ")"
                + " vps=" + size(fromCast ? null : video.parameterSetVps())
                + " sps=" + size(fromCast ? castSps : video.parameterSet0())
                + " pps=" + size(fromCast ? castPps : video.parameterSet1())
                + (fromCast ? " [cast source]" : ""));
        return true;
    }

    private static String size(byte[] data) {
        return data == null ? "-" : data.length + "B";
    }

    /**
     * Field knob for the single-write stall threshold.  Defaults to {@link WriteStallGuard#DEFAULT_STALL_MS};
     * {@code <=0} disables the protection (back to the old "receiver dies and it hangs forever" behaviour, only
     * for controlled A/B experiments).
     */
    private static long readStallMs() {
        try {
            final Class<?> props = Class.forName("android.os.SystemProperties");
            final java.lang.reflect.Method get = props.getMethod("get", String.class, String.class);
            final Object raw = get.invoke(null, PROP_STALL_MS,
                    String.valueOf(WriteStallGuard.DEFAULT_STALL_MS));
            final long value = Long.parseLong(String.valueOf(raw).trim());
            return value;
        } catch (Throwable ignored) {
            return WriteStallGuard.DEFAULT_STALL_MS;
        }
    }

    /**
     * "Receiver stopped reading" watchdog: the real deadlock is a {@code write()} that never returns, and the only
     * {@code close()} that can release it has to come from someone else.  This thread never takes {@code mWriteLock}
     * (while the write blocks, the writer holds it); it only reads {@link WriteStallGuard}'s timing verdict and
     * closes the socket on timeout.
     */
    private void startStallWatchdog() {
        if (!mStallGuard.enabled()) {
            mState.log(TAG, "write-stall watchdog disabled (" + PROP_STALL_MS + "="
                    + mStallGuard.stallMs() + ')');
            return;
        }
        final Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (mRunning) {
                    try {
                        Thread.sleep(STALL_POLL_MS);
                    } catch (InterruptedException e) {
                        return;
                    }
                    if (!mRunning) {
                        return;
                    }
                    final long now = SystemClock.elapsedRealtime();
                    if (!mStallGuard.stalled(now)) {
                        continue;
                    }
                    final long waited = mStallGuard.inFlightMs(now);
                    final long n = mStallGuard.noteStallHandled();
                    mError = "video dataPort write stalled " + waited + "ms";
                    mState.log(TAG, "video write stalled " + waited + "ms (receiver stopped reading?)"
                            + " — closing the dataPort so the session can re-arm [stall #" + n
                            + ", threshold=" + mStallGuard.stallMs() + "ms]");
                    /* Order: let the loop exit first, then close the socket.  Closing makes the blocked write()
                     * throw IOException immediately; that path goes through onWriteFailure and logs, so there is
                     * no need — and no reason — to wait here. */
                    mRunning = false;
                    closeQuietly(mSocket);
                    return;
                }
            }
        }, "okcar-wireless-video-stall");
        thread.setDaemon(true);
        mStallThread = thread;
        thread.start();
    }

    private void startPacer() {
        try {
            mDelayMs = VideoPacer.clampDelay(com.okcar.link.media.VideoQos.load(
                    qosTransport()).videoDelayMs);
        } catch (Throwable ignored) {
            mDelayMs = VideoPacer.DEFAULT_DELAY_MS;
        }
        final Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                com.okcar.link.media.MediaThreads.raise(
                        com.okcar.link.media.MediaPriority.THREAD_VIDEO_ENCODE);
                pacerLoop();
            }
        }, "okcar-video-pace");
        thread.setDaemon(true);
        mPacerThread = thread;
        thread.start();
        mState.log(TAG, "video pacer started (delay=" + mDelayMs + "ms, capacity="
                + VideoPacer.capacityFrames(mDelayMs, mFps) + " frames)");
    }

    /** Empty type-2 record, ≈1 Hz (the capture's heartbeat carries no payload at all). */
    private void startHeartbeat() {
        final Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (mRunning) {
                    try {
                        Thread.sleep(HEARTBEAT_INTERVAL_MS);
                    } catch (InterruptedException e) {
                        return;
                    }
                    if (!mRunning) {
                        return;
                    }
                    synchronized (mWriteLock) {
                        try {
                            mStallGuard.begin(SystemClock.elapsedRealtime());
                            mOut.write(WirelessScreenHeader.heartbeatHeader());
                            mOut.flush();
                            mStallGuard.end();
                            mHeartbeats++;
                        } catch (Throwable t) {
                            onWriteFailure(t);
                            return;
                        }
                    }
                }
            }
        }, "okcar-wireless-video-beat");
        thread.setDaemon(true);
        mHeartbeatThread = thread;
        thread.start();
    }

    /**
     * A dead dataPort must not spam the log or kill the session: give up after a few
     * consecutive failures, which tears this stream down on the sender thread.
     */
    private void onWriteFailure(Throwable t) {
        if (++mWriteFailures >= MAX_WRITE_FAILURES) {
            mError = String.valueOf(t);
            mState.log(TAG, "video dataPort write failed " + mWriteFailures
                    + " times: " + t + " — stopping the video stream");
            mRunning = false;
            closeQuietly(mSocket);
        } else {
            mState.log(TAG, "video write failed (" + mWriteFailures + "): " + t);
        }
    }

    public void stop() {
        if (!mStopped.compareAndSet(false, true)) {
            return;
        }
        mRunning = false;
        if (mCastSource) {
            /* Give the socket back: the next session (wireless or wired) arms its own sink, and a
             * stale sink would keep enqueueing frames into a sender that is already stopped. */
            com.okcar.link.castlink.CastLinkServer.get().detach();
            mState.log(TAG, "cast source detached after " + mCastFrames + " frames / "
                    + mCastConfigs + " config record(s)");
        }
        final Thread heartbeat = mHeartbeatThread;
        mHeartbeatThread = null;
        if (heartbeat != null) {
            heartbeat.interrupt();
        }
        final Thread stall = mStallThread;
        mStallThread = null;
        if (stall != null) {
            stall.interrupt();
        }
        mStallGuard.end();
        /* Close the socket *before* waiting for the write lock: a socket write that is
         * blocked on a full TCP receive window (an accessory that stopped reading) only
         * returns once the socket is closed, and the encoder thread may be holding
         * mWriteLock while it is blocked. */
        closeQuietly(mSocket);
        final MirrorVideoSource video = mVideo;
        mVideo = null;
        if (video != null) {
            video.setSessionUp(false);
        }
        if (video != null) {
            video.stop();
        }
        synchronized (mWriteLock) {
            mSocket = null;
            mOut = null;
        }
        mState.log(TAG, "stopped after " + mFrames + " frames / " + mBytes + "B in "
                + elapsedMs() + "ms (" + measuredFps() + " fps measured, config="
                + mConfigRecords + ", heartbeats=" + mHeartbeats + ", deferred="
                + mDeferredConfigs + ')'
                + (mError.isEmpty() ? "" : ", error=" + mError));
    }

    /** Wall time since the encoder started; 0 before that. */
    private long elapsedMs() {
        return mStartedAtMs <= 0 ? 0 : SystemClock.elapsedRealtime() - mStartedAtMs;
    }

    /**
     * Frames really put on the wire per second.  The encoder's configured rate is only a
     * request — static content legitimately produces almost no frames — so this measured
     * number is the honest one for "are we actually running at 60fps".
     */
    private String measuredFps() {
        final long elapsed = elapsedMs();
        if (elapsed <= 0) {
            return "n/a";
        }
        return String.format(java.util.Locale.US, "%.1f", mFrames * 1000.0 / elapsed);
    }

    private static void closeQuietly(Socket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                /* closing anyway */
            }
        }
    }
}
