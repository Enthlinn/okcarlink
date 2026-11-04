/*
 * OkcarOS link stack — wireless AirPlay client (the missing half of step ④).
 *
 * After the accessory hands us its network in iAP2 0x4301 (SSID/passphrase/IP/port)
 * and we have actually associated to it (WifiGroupJoiner), the *device* has to open
 * the AirPlay session on the accessory: TCP <accessory ip>:<airPlayPort> → GET /info
 * → POST /pair-setup (M1..M6) → POST /pair-verify (M1..M4) → (then the encrypted
 * control channel, which the session layer takes over via AirPlayPairing.sealControl).
 *
 * Wire facts taken from a real wireless session captured on 2026-09-26/27
 * (see the field-rework notes §12.38 and the pairing-crypto notes):
 *   - /pair-setup:  Content-Type: application/x-apple-binary-plist, X-Apple-HKP: 0
 *   - /pair-verify: Content-Type: application/octet-stream, X-Apple-HKP: 2, X-Apple-PD: 1
 *   - the real iPhone sends User-Agent: AirPlay/710.79.1 and CSeq starting at 0/1
 *   - a *link-local* accessory address must be scoped to the interface (fe80::…%p2p0)
 *
 * Step ⑤ (the last piece) hands iAP2 over to the accessory's Wi-Fi side exactly like a real
 * iPhone: sealed POST /command {"type":"disableBluetooth"} -> a type-130 stream SETUP -> a second
 * TCP connection to its dataPort, sealed with the DataStream output key and carrying raw iAP2 CSM
 * messages inside 32-byte big-endian APTransportPackage("comm") records.  Its outcome is reported
 * (RTSP, mirroring, timing, the event channel) is usable on its own, so a failure there never
 * throws out of run().
 *
 * The crypto lives in AirPlayPairing (pure Java, host-tested); this class is only
 * the socket + RTSP plumbing, so it stays readable and never logs key material.
 */

package com.okcar.link.airplay;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.okcar.link.LinkState;
import com.okcar.link.hid.HidReportDecoder;
import com.okcar.link.input.InputInjector;
import com.okcar.link.iap2.Iap2Message;
import com.okcar.link.media.AudioEncoder;
import com.okcar.link.media.MirrorVideoSource;
import com.okcar.link.media.OpusAudioEncoder;
import com.okcar.link.plist.BinaryPlist;
import com.okcar.link.wireless.WirelessCarPlayDriver;
import com.okcar.link.wireless.WirelessMessages;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

public final class WirelessAirPlayClient {

    private static final String TAG = "OkCarLink/AirPlay";
    private static final String PREFS = "okcar_airplay_identity";
    private static final String KEY_IDENTITY = "identity";

    private static final String USER_AGENT = AirPlayPairing.USER_AGENT;
    private static final int CONNECT_TIMEOUT_MS = 4000;
    /** How long to keep sweeping candidates while the group settles. */
    private static final int CONNECT_WINDOW_MS = 45000;
    private static final int READ_TIMEOUT_MS = 15000;
    /**
     * Read timeout for one <em>sealed</em> control exchange, applied to the control socket
     * around the read and restored afterwards.  Named and deliberately generous: the receiver
     * does MFi work before it answers /auth-setup and then builds the session for the base
     * SETUP, while the accessory itself recycles the whole CarPlay session every ~4-6 s.
     * Measured on this receiver: /auth-setup is answered in ~0-3 s and the base SETUP right
     * after it.  (Before this constant existed the sealed read simply inherited the control
     * socket's {@link #READ_TIMEOUT_MS}; the 2026-09-27 failure was <em>not</em> a timeout —
     * see {@link SealedControlFraming#readMessage}.)
     */
    private static final int SEALED_READ_TIMEOUT_MS = 10000;
    /**
     * Ceiling for one reassembled sealed RTSP message.  A sender that never terminates its
     * header block (or whose Content-Length never arrives) must not make the reader grow
     * without bound.
     */
    private static final int MAX_SEALED_MESSAGE_BYTES = 256 * 1024;
    private static final int MAX_HEADER_BYTES = 64 * 1024;

    /** One RTSP/HTTP response: status, headers and body. */
    public static final class Response {
        public int status;
        public String reason = "";
        public String headers = "";
        public byte[] body = new byte[0];
        /**
         * Why a reply could not be parsed as RTSP; empty for a well-formed status line.
         * Set by {@link #parseResponse} so a broken reply is never reported as a bare `-1`.
         */
        public String problem = "";

        public String header(String name) {
            final String needle = name.toLowerCase(Locale.US) + ':';
            for (String line : headers.split("\r\n")) {
                final String lower = line.toLowerCase(Locale.US);
                if (lower.startsWith(needle)) {
                    return line.substring(needle.length()).trim();
                }
            }
            return null;
        }

        public String describe() {
            return status + " " + reason + " body=" + body.length + "B"
                    + (problem.isEmpty() ? "" : " — " + problem);
        }
    }

    private final Context mContext;
    private final LinkState mState = LinkState.get();

    /* The timing port we advertise in the base SETUP must be a socket we really own and
     * answer on.  This accessory probes it (`_TimingNegotiate`) while it sets up every
     * stream and rejects the stream SETUP with 400 when the probe cannot be delivered.
     * Measured 2026-09-27 with a hardcoded port nothing was bound to: the accessory logged
     *   `_TimingNegotiate() error(111/0x6F) take 22ms` -> `setup session ... error(111)`
     *   -> `SETUP(110)` answered `RTSP/1.0 400 Bad Request`.
     * errno 111 is ECONNREFUSED, i.e. the accessory's UDP probe to our advertised port got
     * an ICMP port-unreachable because no socket existed.  Every real iPhone advertises a
     * free UDP port it owns (the session-analysis notes #5/#13), and the working wired
     * path does the same, so bind ephemeral and advertise the bound port. */
    private volatile DatagramSocket mTimingSocket;
    private volatile TimingResponder mTimingResponder;
    /** Constant across every captured session (the session-analysis notes). */
    private static final String STREAM_UUID = "b7e6c5a0-1111-4000-8000-000000000001";

    /* ------------------------------------------------------------------ video */

    /*
     * There is no codec knob here on purpose.  The accessory is the decoding side, so
     * its advertisement (`enabledFeatures` / `hevcInfo`) selects the codec and this
     * stack only intersects it with what the local encoder can really do — see
     * WirelessScreenHeader.chooseCodec().  A pin could only produce a stream the
     * accessory cannot decode.
     */

    /** `POST /feedback` cadence — the real iPhone sent one every ≈2.0 s. */
    private static final long FEEDBACK_INTERVAL_MS = 2000L;

    private volatile Socket mSocket;
    private volatile Socket mDataStreamSocket;
    /**
     * The accessory's event channel.  Kept in a field so {@link #stop()} can end it
     * deterministically: it must live as long as the *session*, not just the handshake
     * ({@code mRunning} is cleared by {@code run()}'s finally as soon as the streams are up).
     */
    private volatile Socket mEventSocket;
    private volatile InputStream mControlIn;
    private volatile OutputStream mControlOut;
    /** Serialises every write/read pair on the sealed control channel. */
    private final Object mControlLock = new Object();
    /**
     * Reassembles one RTSP message out of the sealed control channel's HAP records and keeps
     * any over-read for the next message.  Only touched while {@link #mControlLock} is held.
     */
    private final SealedControlFraming mSealed = new SealedControlFraming(MAX_SEALED_MESSAGE_BYTES,
            new SealedControlFraming.Log() {
                @Override
                public void log(String message) {
                    mState.log("airplay", message);
                }
            });
    /** Serialises sealed replies on the event connection (single reader thread today). */
    private final Object mEventWriteLock = new Object();
    private volatile boolean mRunning;
    private final AtomicInteger mCseq = new AtomicInteger(1);
    private final java.security.SecureRandom mSessionRandom = new java.security.SecureRandom();
    private String mSessionId = "";

    /* video face (the codec itself is chosen in resolveVideoCodec) */
    private volatile AirPlayPairing mPairing;
    private volatile WirelessVideoSender mVideoSender;
    private volatile WirelessAudioSender mAudioSender;
    private volatile Thread mAudioThread;
    private volatile Thread mVideoThread;
    private volatile Thread mFeedbackThread;
    private volatile boolean mFeedbackRunning;
    private int mVideoDataPort = -1;
    private long mStreamConnectionId;
    /** `enabledFeatures` arrays seen in the base and stream-110 SETUP answers. */
    private volatile List<String> mSetupEnabledFeatures = Collections.emptyList();
    private volatile List<String> mStreamEnabledFeatures = Collections.emptyList();
    /** Best-effort /info evidence (its 30 kB body is often truncated on the wire). */
    private volatile boolean mInfoHevcKey;
    private volatile boolean mInfoHevcText;
    private volatile boolean mRawVideoConfig;
    /** Touch injection: the same decoder + injector the wired path uses. */
    private volatile InputInjector mInjector;
    private volatile boolean mInjectTouch = true;
    private volatile int mTouchSpaceWidth = -1;
    private volatile int mTouchSpaceHeight = -1;
    /** The size the live stream was opened at, so a late size change can compare against it. */
    private volatile int[] mCurrentVideoSize;
    /**
     * Why the video stream was refused this session (no screen known for the head unit), or null.
     * Kept apart from the sender's own error because there is no sender in that case, and "no
     * picture" without a reason is exactly the kind of failure that used to be papered over.
     */
    private volatile String mNoScreenReason;
    /** One log line per session for touch reports dropped because no screen space is known. */
    private volatile boolean mTouchSpaceMissingLogged;
    /** One adoption per session: the accessory's space cannot change mid-session. */
    private final java.util.concurrent.atomic.AtomicBoolean mSizeAdoptionStarted =
            new java.util.concurrent.atomic.AtomicBoolean();
    /** The screen this unit is known to be driven at, loaded from its stored config. */
    private volatile int mAccessorySpaceWidth = -1;
    private volatile int mAccessorySpaceHeight = -1;
    /** The video stream's parameters, kept so a size change can re-open it. */
    private volatile String mStreamHost;
    private volatile int mStreamDataPort;
    private volatile AirPlayPairing mStreamPairing;
    /** Lab-only plaintext wire dump path; empty disables it. */
    private volatile String mPlainDumpPath = "";
    /** Lab-only encoder name for the video face; empty keeps the platform default. */
    private volatile String mEncoderName = "";

    /**
     * Lab-only: mirror the video wire in its plaintext view to {@code path} (128-byte record
     * headers plus the pre-sealing bodies).  Used to check the HEVC config record and NAL framing
     * against a decoder without one being available on the host.
     */
    public void setVideoPlainDump(String path) {
        mPlainDumpPath = path == null ? "" : path;
    }

    /** Lab-only: pick a specific media encoder by name (see MirrorVideoSource#setEncoderName). */
    public void setVideoEncoderName(String name) {
        mEncoderName = name == null ? "" : name;
    }

    /* Task 1 diagnostic: bounded raw HID dump so a live session can settle the
     * receiver's down/up flag from real bytes instead of from a hypothesis.  Log
     * the first HID_RAW_FIRST reports plus every later report whose first byte,
     * status byte (byte 1 = the decoded down/up flag) or length changes, capped at
     * HID_RAW_MAX extra lines so a long session cannot flood the log.  The status
     * byte is in the trigger on purpose: the first deployed dump keyed only on
     * byte 0 + length, and a release keeps both (slot[0] = (press>>8)+1 stays 1,
     * the payload stays 12 B), so no release was ever logged.  Watching byte 1
     * makes the next press/release pair visible.  "airplay:"-prefixed via
     * mState.log. */
    private static final int HID_RAW_FIRST = 8;
    private static final int HID_RAW_MAX = 64;
    private int mHidReports;
    private int mHidRawLogged;
    private int mHidRawLastFirst = -1;
    private int mHidRawLastSecond = -1;
    private int mHidRawLastLength = -1;
    private volatile int mInfoWidth = -1;
    private volatile int mInfoHeight = -1;
    /** Advertised frame-rate ceiling from `/info` (displays[0].maxFPS), or -1. */
    private volatile int mInfoMaxFps = -1;
    /** Where the frame-rate evidence came from, for the log. */
    private volatile String mInfoFpsSource = "";
    /** Frame-rate ceiling from the base SETUP answer, or -1. */
    private volatile int mSetupMaxFps = -1;
    /**
     * The audio-format bits the accessory advertises for stream type 100 in its `/info`
     * (`audioFormats[].audioFormat`).  We ask for exactly those instead of guessing: asking for
     * a format outside its advertised set is accepted but never played.
     */
    private volatile int mInfoAudioFormatBits;

    /** Interface the wired (NCM) carrier uses; null on the Wi-Fi paths.  See connect(). */
    private String mCarrierInterface;

    /**
     * Pins the interface the session's carrier lives on.  A wired CarPlay session runs over the NCM
     * function, whose link-local IPv6 address is only reachable through that interface's scope and
     * whose route sits in Android's per-interface table rather than the main one.
     */
    public void setCarrierInterface(String carrierInterface) {
        mCarrierInterface = carrierInterface;
    }

    public WirelessAirPlayClient(Context context) {
        mContext = context.getApplicationContext();
    }

    /**
     * Where an accessory iAP2 message that arrives on the <em>event channel</em> is handed.
     *
     * <p>The event channel is a second iAP2 transport: measured on the production head unit (2026-10-02) it
     * re-sent the bootstrap DETECT ({@code FF 55 02 00 EE 10}) there 13 s into a live session.
     * Until this existed the client only logged such messages, the car got no answer and tore the
     * session down — the "it connects, then drops after a few seconds" field report.
     */
    public interface AccessoryIapSink {
        void onAccessoryIapMessage(byte[] payload);
    }

    private volatile AccessoryIapSink mAccessoryIapSink;

    /** The accessory's Bluetooth address, which is how {@link AccessoryMemory} keys its records. */
    private volatile String mAccessoryAddress;

    /** The address of the accessory this client is serving (used for the per-device memory). */
    public void setAccessoryAddress(String address) {
        mAccessoryAddress = address;
    }

    /** Installs the sink that consumes accessory iAP2 messages arriving on the event channel. */
    public void setAccessoryIapSink(AccessoryIapSink sink) {
        mAccessoryIapSink = sink;
    }

    /**
     * Sends one iAP2 frame to the accessory over the sealed event channel.
     *
     * <p>This is the outbound half of the event-channel iAP2 transport: once the accessory has moved
     * its iAP2 off Bluetooth (the production head unit closes that socket as soon as the AirPlay session starts), the
     * sealed event channel is the only path left, and this is the shape the real sender uses —
     * {@code POST /command} + {@code {"type":"iAPSendMessage","params":{"data":…}}}.
     *
     * @return true when the accessory answered 200; false when the session is not far enough along
     *         or the send failed (the caller reports it rather than treating it as delivered)
     */
    public boolean sendAccessoryIap(byte[] payload) {
        final InputStream in = mControlIn;
        final OutputStream out = mControlOut;
        final AirPlayPairing pairing = mPairing;
        if (payload == null || payload.length == 0 || in == null || out == null || pairing == null) {
            return false;
        }
        try {
            final Response reply = sealedExchange(in, out, pairing, "POST", "/command",
                    "application/x-apple-binary-plist", null,
                    BinaryPlist.encode(EventChannelIap.sendBody(payload)));
            final boolean ok = reply != null && reply.status == 200;
            mState.log("airplay", "event-channel iAPSendMessage out (" + payload.length + "B) -> "
                    + (reply == null ? "no reply" : reply.describe()));
            return ok;
        } catch (Throwable t) {
            mState.log("airplay", "event-channel iAPSendMessage out (" + payload.length
                    + "B) failed: " + t);
            return false;
        }
    }

    /** The screen stream, once the type-110 SETUP has answered a dataPort. */
    public WirelessVideoSender videoSender() {
        return mVideoSender;
    }

    /**
     * Emit the config record as the bare avcC/hvcC record instead of the ISO-BMFF
     * sample entry (`avc1`+`avcC` / `hvc1`+`hvcC`).  The capture proves the sample
     * entry for HEVC; this is the A/B escape hatch if a receiver turns out to want
     * the bare record (the wired head-unit path sends one).
     */
    public void setRawVideoConfig(boolean raw) {
        mRawVideoConfig = raw;
    }

        public boolean isRunning() {
        return mRunning;
    }

    /**
     * True while the media streams started by the last handshake are still alive.
     *
     * <p>{@link #isRunning()} cannot answer this: it covers the handshake/control session, which
     * returns as soon as the streams are set up (see the {@code finally { mRunning = false; }} in
     * {@link #run}).  The senders own the long-lived half of the session, so their own liveness is
     * the fact a session watchdog needs — a stream that died (measured 2026-10-01: {@code video
     * write failed … Broken pipe} once the accessory went away) must not leave the caller believing
     * the session is still up, or the next start-session request is refused as a duplicate and the
     * receiver reconnects in a loop forever.
     */
    public boolean isStreaming() {
        final WirelessVideoSender video = mVideoSender;
        if (video != null) {
            return video.isRunning();
        }
        final WirelessAudioSender audio = mAudioSender;
        return audio != null && audio.isRunning();
    }

    /** The media stream's failure text ({@code null} while healthy) for the watchdog's log line. */
    public String streamError() {
        final WirelessVideoSender video = mVideoSender;
        if (video == null) {
            return mNoScreenReason;
        }
        return video.error();
    }

    public void stop() {
        mRunning = false;
        /* Hand back any finger the receiver still had down.  Without this a session that ends
         * mid-gesture leaves the dispatcher holding a pointer on our display, and the app there
         * stops accepting touch for good — the "it worked, then stopped reacting" failure. */
        final InputInjector injector = mInjector;
        if (injector != null) {
            try {
                injector.releasePending();
            } catch (Throwable t) {
                Log.w(TAG, "could not release the pending touch: " + t);
            }
        }
        stopVideo();
        /* The audio sender owns the capture source (AudioRecord / REMOTE_SUBMIX).  Its stop() is
         * what releases it; leaving it out kept the capture alive until its socket happened to
         * die, and stop() is exactly the moment the session is over. */
        stopAudio();
        stopFeedbackLoop();
        stopTimingResponder();
        closeQuietly(mSocket);
        closeQuietly(mDataStreamSocket);
        closeQuietly(mEventSocket);
        mEventSocket = null;
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

    /**
     * Binds the free UDP port we will advertise as `timingPort` and starts answering the
     * accessory's NTP-style probes on it.  Must run before the base SETUP goes out: the
     * accessory only learns the port from that plist, and it re-probes it while setting up
     * each stream (a silent/dead port makes the stream SETUP fail with 400).
     *
     * @return false when no socket could be bound; the caller must not run the handshake then
     */
    private boolean startTimingResponder() {
        final DatagramSocket existing = mTimingSocket;
        if (existing != null && !existing.isClosed() && mTimingResponder != null) {
            return true;
        }
        try {
            mTimingSocket = new DatagramSocket(0);
        } catch (IOException e) {
            mTimingSocket = null;
            mState.log("airplay", "cannot bind the timing port: " + e);
            return false;
        }
        mTimingResponder = new TimingResponder(mTimingSocket);
        mTimingResponder.start();
        mState.log("airplay", "timing port " + mTimingSocket.getLocalPort()
                + " bound and answering (advertised in the base SETUP)");
        return true;
    }

    private void stopTimingResponder() {
        final TimingResponder responder = mTimingResponder;
        mTimingResponder = null;
        if (responder != null) {
            responder.stop();
        }
        final DatagramSocket socket = mTimingSocket;
        mTimingSocket = null;
        if (socket != null) {
            socket.close();
        }
    }

    /** The live UDP port to advertise as `timingPort`. */
    private int timingPort() {
        final DatagramSocket socket = mTimingSocket;
        return socket == null ? 0 : socket.getLocalPort();
    }

    /**
     * Runs the whole cleartext + pairing handshake and then, after RECORD, the step-⑤ iAP2
     * hand-off.  Blocking (the event channel and the iAP2 tunnel each run on their own
     * background thread); call from a worker thread.  Returns true once the base session is up
     * (through RECORD).  A failure *inside* the hand-off is reported through {@link #handoff()}
     * and never by throwing or by turning this into false.
     */
    public boolean run(String accessoryAddress, int port) {
        mRunning = true;
        /* A fresh control connection always starts a fresh sealed byte stream. */
        mSealed.reset();
        try {
            final Socket socket = connect(accessoryAddress, port);
            if (socket == null) {
                return false;
            }
            mSocket = socket;
            final InputStream in = socket.getInputStream();
            final OutputStream out = socket.getOutputStream();
            mControlIn = in;
            mControlOut = out;
            mState.log("airplay", "connected to " + socket.getInetAddress().getHostAddress()
                    + ":" + port);

            /* Bind and start answering the timing port *before* the base SETUP advertises it:
             * the accessory probes it while setting up each stream, and a dead port turns the
             * type-110 stream SETUP into `RTSP/1.0 400 Bad Request` (measured 2026-09-27). */
            if (!startTimingResponder()) {
                mState.log("airplay", "no timing responder — aborting before the base SETUP");
                return false;
            }

            /* 1. /info — cleartext, tells us what the accessory is.  The body is also
             * the first place HEVC support can show up (a top-level `hevcInfo` key), but
             * it is ~30 kB and the accessory often stalls the read, so anything it tells
             * us here is best-effort evidence only (the SETUP answers are authoritative). */
            try {
                final Response info = exchange(in, out, "GET", "/info",
                        "application/x-apple-binary-plist", null, null);
                mState.log("airplay", "/info -> " + (info == null ? "no reply" : info.describe()));
                if (info != null) {
                    inspectInfo(info.body);
                }
            } catch (Throwable t) {
                /* /info is informational: a slow or truncated body (measured: the emulator
                 * answered 30497B and we had read 17645B when the read timed out) must
                 * not stop us from pairing. */
                mState.log("airplay", "/info not usable (" + t + ") — continuing to pair-setup");
            }

            /* 2. pair-setup: SRP with PIN 3939, six messages. */
            final AirPlayPairing pairing =
                    new AirPlayPairing(new PrefsIdentityStore(mContext));
            mPairing = pairing;
            final Response m1 = exchange(in, out, "POST", "/pair-setup",
                    AirPlayPairing.CONTENT_TYPE_PAIR_SETUP, "X-Apple-HKP: 0", pairing.pairSetupM1());
            if (m1 == null || m1.status != 200) {
                mState.log("airplay", "pair-setup M1 failed: " + (m1 == null ? "no reply" : m1.describe()));
                return false;
            }
            final Response m2 = exchange(in, out, "POST", "/pair-setup",
                    AirPlayPairing.CONTENT_TYPE_PAIR_SETUP, "X-Apple-HKP: 0", pairing.pairSetupM3(m1.body));
            if (m2 == null || m2.status != 200) {
                mState.log("airplay", "pair-setup M3 failed: " + (m2 == null ? "no reply" : m2.describe()));
                return false;
            }
            final Response m3 = exchange(in, out, "POST", "/pair-setup",
                    AirPlayPairing.CONTENT_TYPE_PAIR_SETUP, "X-Apple-HKP: 0", pairing.pairSetupM5(m2.body));
            if (m3 == null || m3.status != 200) {
                mState.log("airplay", "pair-setup M5 failed: " + (m3 == null ? "no reply" : m3.describe()));
                return false;
            }
            pairing.pairSetupM6(m3.body);
            mState.log("airplay", "pair-setup done — accessory " + pairing.describe());

            /* 3. pair-verify: ephemeral X25519 + Ed25519, four messages. */
            final Response v1 = exchange(in, out, "POST", "/pair-verify",
                    AirPlayPairing.CONTENT_TYPE_PAIR_VERIFY,
                    "X-Apple-HKP: 2\r\nX-Apple-PD: 1", pairing.pairVerifyM1());
            if (v1 == null || v1.status != 200) {
                mState.log("airplay", "pair-verify M1 failed: " + (v1 == null ? "no reply" : v1.describe()));
                return false;
            }
            final Response v2 = exchange(in, out, "POST", "/pair-verify",
                    AirPlayPairing.CONTENT_TYPE_PAIR_VERIFY,
                    "X-Apple-HKP: 2\r\nX-Apple-PD: 1", pairing.pairVerifyM3(v1.body));
            if (v2 == null || v2.status != 200) {
                mState.log("airplay", "pair-verify M3 failed: " + (v2 == null ? "no reply" : v2.describe()));
                return false;
            }
            pairing.pairVerifyM4(v2.body);
            mState.log("airplay", "pair-verify done — channelsReady=" + pairing.channelsReady()
                    + " controlWrite=" + (pairing.controlWriteKey() == null ? 0
                    : pairing.controlWriteKey().length) + "B");

            if (!pairing.channelsReady()) {
                mState.log("airplay", "pair-verify reported no channel keys");
                return false;
            }
            mState.log("airplay", "encrypted control channel ready");

            /* 4. /auth-setup on the sealed channel.  The real iPhone sent exactly 33
             * bytes: 0x01 followed by its ephemeral X25519 public key (captured
             * session, cseq 5, Content-Type: application/octet-stream).  We do not
             * verify the accessory's MFi answer — the sender side never did
             * (the field-rework notes §12.19). */
            final byte[] auth = new byte[33];
            auth[0] = 0x01;
            final byte[] ephemeralPublic = x25519PublicKey();
            if (ephemeralPublic == null) {
                mState.log("airplay", "/auth-setup skipped: no X25519 keypair available");
            } else {
                System.arraycopy(ephemeralPublic, 0, auth, 1, 32);
                final Response authResp = sealedExchange(in, out, pairing, "POST", "/auth-setup",
                        "application/octet-stream", null, auth);
                mState.log("airplay", "/auth-setup -> "
                        + (authResp == null ? "no reply" : authResp.describe()));
            }
            /* 5. Base SETUP: who we claim to be + the timing port the accessory may
             * talk to.  The real iPhone's body was 492 B and carried NO et/eiv/ekey
             * (those belong to the wired encryption path); it DID carry deviceID,
             * macAddress, model, osBuildVersion, sourceVersion, sessionUUID, name,
             * timingPort.  The URI host is *our own* address on the control connection,
             * not the accessory's (see sessionUriHost): the measured Apple sender wrote
             * `rtsp://fe80::8af:75ff:fea9:afb8/<id>` while the receiver was at another
             * address, and naming the peer makes the receiver drop the SETUP. */
            final String sessionId = Long.toString(Math.abs(mSessionRandom.nextLong()));
            final String uri = "rtsp://" + sessionUriHost(socket, accessoryAddress)
                    + "/" + sessionId;
            final Map<String, Object> base = new LinkedHashMap<String, Object>();
            base.put("deviceID", AirPlay.SENDER_MAC);
            base.put("macAddress", AirPlay.SENDER_MAC);
            base.put("model", AirPlay.SENDER_MODEL);
            base.put("name", AirPlay.SENDER_NAME);
            base.put("osBuildVersion", AirPlay.SENDER_OS_BUILD);
            base.put("sourceVersion", AirPlay.SENDER_SOURCE_VERSION);
            base.put("sessionUUID", java.util.UUID.randomUUID().toString());
            base.put("timingPort", Long.valueOf(timingPort()));
            final Response baseReply = sealedExchange(in, out, pairing, "SETUP", uri,
                    "application/x-apple-binary-plist", null, BinaryPlist.encode(base));
            mState.log("airplay", "base SETUP -> " + baseReply.describe());
            if (baseReply.status != 200) {
                return false;
            }
            /* The base SETUP answer is where this accessory family advertises its codecs:
             * the emulator answered `enabledFeatures: ['hevc', 'iAPChannel',
             * 'viewAreas']` (the session-analysis notes §2.4), which is the strongest
             * HEVC evidence in the whole handshake. */
            mSetupEnabledFeatures = stringListFromPlist(baseReply.body, "enabledFeatures");
            if (!mSetupEnabledFeatures.isEmpty()) {
                mState.log("airplay", "base SETUP enabledFeatures=" + mSetupEnabledFeatures);
            }
            /* Some accessories state the frame-rate ceiling in the base SETUP answer
             * instead of /info; keep it as the second-choice evidence. */
            mState.log("airplay", "base SETUP answer keys: " + describeKeys(baseReply.body));
            mSetupMaxFps = intFromPlist(baseReply.body, "maxFPS");
            final int eventPort = intFromPlist(baseReply.body, "eventPort");
            if (eventPort > 0) {
                openEventChannel(accessoryAddress, eventPort, pairing);
            }

            /* 5b. /info — now in the position the real iPhone uses, and that ordering is the
             * whole point: the captured sender runs `auth-setup → SETUP → GET /info → RECORD`
             * (the internal notes, CSeq 0-3), and its /info
             * carries `X-Apple-ProtocolVersion: 1`.  Answering that request is how a receiver
             * states its own screen: that transcript's 200 OK body has
             * `displays: [{widthPixels: 1920, heightPixels: 720, maxFPS: 30, …}]`.
             *
             * We used to send this right after pair-verify, i.e. *before* SETUP, and Fruit Link
             * answered `455 Method Not Valid In This State` — its RTSP state machine does not
             * serve /info until the session is set up.  With no `displays[0]` the size fell back
             * to this unit's stored config, and a blind receiver in the other orientation got the
             * last session's value (measured 2026-10-02: landscape head unit, 1080x2400 portrait
             * stream).  Sending it here, before the stream SETUP resolves the size, is what makes
             * a receiver that declares nothing still size the picture correctly.
             *
             * Best effort by design: a receiver that still refuses must not stop the session. */
            try {
                final Response info2 = sealedExchange(in, out, pairing, "GET", "/info", null,
                        "X-Apple-ProtocolVersion: 1", null);
                mState.log("airplay", "post-setup /info -> "
                        + (info2 == null ? "no reply" : info2.describe()));
                if (info2 != null && info2.body != null && info2.body.length > 0) {
                    inspectInfo(info2.body);
                    if (mInfoWidth > 0 && mInfoHeight > 0) {
                        mState.log("airplay", "head unit declares its screen: "
                                + mInfoWidth + 'x' + mInfoHeight + " (/info displays[0])");
                    } else {
                        mState.log("airplay", "post-setup /info declared no displays[] — the size"
                                + " chain keeps its next source");
                    }
                }
            } catch (Throwable t) {
                mState.log("airplay", "post-setup /info not usable (" + t
                        + ") — keeping the size chain as is");
            }

            /* 6. Stream SETUP for the screen (type 110) — the real iPhone sent 252 B
             * with streamConnectionID, uuid b7e6c5a0-1111-4000-8000-000000000001,
             * latencyMs 75 and timestampInfo. */
            final Map<String, Object> stream = new LinkedHashMap<String, Object>();
            stream.put("type", Long.valueOf(110));
            final long streamConnectionId = mSessionRandom.nextLong();
            stream.put("streamConnectionID", Long.valueOf(streamConnectionId));
            stream.put("uuid", STREAM_UUID);
            stream.put("latencyMs", Long.valueOf(75));
            /* Measured iPhone shape (the session-analysis notes §2.4 #25): an array of
             * `{name: <string>}` dicts, NOT bare strings.  Byte-length proof against the
             * receiver's own `Content-Length`: the 5 bare names encode to exactly 226 B
             * (what we used to send) while the 5 dicts encode to exactly 252 B (the real
             * iPhone's SETUP, cseq 12).  Same five names, in the measured order. */
            final java.util.List<Map<String, Object>> timestamps =
                    new java.util.ArrayList<Map<String, Object>>();
            for (String timestampName : new String[] {
                    "SubSu", "BePxT", "AfPxT", "BefEn", "EmEnc" }) {
                timestamps.add(java.util.Collections.singletonMap(
                        AirPlay.KEY_NAME, (Object) timestampName));
            }
            stream.put("timestampInfo", timestamps);
            final Map<String, Object> streams = new LinkedHashMap<String, Object>();
            streams.put("streams", java.util.Collections.singletonList(stream));
            final Response streamReply = sealedExchange(in, out, pairing, "SETUP", uri,
                    "application/x-apple-binary-plist", null, BinaryPlist.encode(streams));
            final int dataPort = streamIntFromPlist(streamReply.body, "dataPort");
            mState.log("airplay", "stream SETUP -> " + streamReply.describe()
                    + " dataPort=" + dataPort);
            if (streamReply.status != 200) {
                return false;
            }
            mState.log("airplay", "stream SETUP answer keys: " + describeKeys(streamReply.body));
            /* Size hints: some receivers answer the screen-stream SETUP with the geometry they
             * want instead of declaring `displays[0]` in /info (which Fruit Link refuses outright:
             * 401 before pairing, 455 after) — keep every source the wire actually offers. */
            final int setupWidth = streamIntFromPlist(streamReply.body, "width");
            final int setupHeight = streamIntFromPlist(streamReply.body, "height");
            if (setupWidth > 0 && setupHeight > 0
                    && (mInfoWidth <= 0 || mInfoHeight <= 0)) {
                mInfoWidth = setupWidth;
                mInfoHeight = setupHeight;
                mState.log("airplay", "head unit declares its screen in the stream SETUP: "
                        + setupWidth + 'x' + setupHeight);
            }
            /* Stream-level SETUP answers carry no enabledFeatures in the capture, but read
             * them anyway: on another accessory they are free extra evidence. */
            mStreamEnabledFeatures = stringListFromPlist(streamReply.body, "enabledFeatures");
            final boolean screenStreamReady = dataPort > 0;
            if (screenStreamReady) {
                mVideoDataPort = dataPort;
                mStreamConnectionId = streamConnectionId;
            } else {
                mState.log("airplay", "stream SETUP carried no dataPort — no video will be sent");
            }

            /* 7. RECORD turns the session on. */
            final Response record = sealedExchange(in, out, pairing, "RECORD", uri,
                    null, null, null);
            mState.log("airplay", "RECORD -> " + record.describe());
            mSessionId = sessionId;
            if (record.status != 200) {
                return false;
            }
            /* The screen stream may only carry data once the receiver has processed RECORD.
             * Starting the sender straight after the type-110 SETUP answer (which is what this
             * code used to do) makes the receiver pick the frames up on a screen session that
             * has not been prepared yet — measured on the production head unit 2026-09-28:
             *   [ASSERT] ScreenUtilsStub.c:421 "ScreenStreamProcessData", "me->annexBHeaderPtr"
             *   [ScreenStream] Screen stream process data failed: -6745 kNotPreparedErr
             *   [ASSERT] AirPlayReceiverSessionScreen.c:547 _AirPlayReceiverSessionScreen_ProcessFrame
             * The codec-config record is the thing that fills `annexBHeaderPtr`, so losing it
             * here leaves the screen stream permanently without parameter sets.  RECORD is what
             * turns the session on: open the data port and push frames only after it answers. */
            if (screenStreamReady) {
                startVideo(accessoryAddress, dataPort, streamConnectionId, pairing);
            }
            /* The real iPhone then sent `POST /feedback` every ≈2.0 s for the rest of the
             * session (23 of them in a 46 s capture).  It is cheap and must never be fatal. */
            startFeedbackLoop();

            /* 7b. Audio: one more SETUP for stream type 100.  Without it the phone's playback
             * stays on its own speaker (measured on the reference accessory).  LPCM 48k stereo is
             * the format the accessory's own table maps 0x20 to. */
            final long audioStreamConnectionId = mSessionRandom.nextLong();
            startAudioStream(in, out, pairing, uri, accessoryAddress, audioStreamConnectionId);

            /* 8. No type-130 iAP2 tunnel.  Measured: no captured real session (iPhone <-> the emulator,
             * the only accessory we hold a wireless capture of) ever brought one up, and the emulator's
             * own code calls it optional.  Ours made the emulator close the whole session ~30-50 s in
             * (`AirPlayIapTunnelStream.onClosed` -> `session.close()`), so the tunnel, its switch
             * and its hand-off are gone; the wireless media plane is self-sufficient
             * (the session-analysis notes §4). */
            return true;
        } catch (Throwable t) {
            mState.log("airplay", "wireless AirPlay handshake failed: " + t);
            Log.w(TAG, "handshake failed", t);
            return false;
        } finally {
            mRunning = false;
        }
    }

    /**
     * One request/response on the *sealed* control channel: the plaintext RTSP message
     * is sealed with AirPlayPairing and written as `[u16 LE len][ciphertext][16B tag]`
     * (measured framing, both directions), the reply is read as the same record shape
     * and unsealed before parsing.
     *
     * <p>Every failure carries its real cause: an exception is logged with its class and
     * message plus the elapsed time before it propagates, and a reply that decrypts but
     * does not parse is reported through {@link Response#problem} instead of a bare `-1`.
     */
    /**
     * One line of "what the accessory actually answered": top-level keys with a short value.
     *
     * <p>Both SETUP answers are the only place a receiver can state facts we did not ask for,
     * and they are sealed, so a tcpdump cannot see them.  Without this line, "the head unit
     * never declared its screen" and "we failed to read the declaration" look identical.
     */
    private String describeKeys(byte[] body) {
        if (body == null || body.length == 0) {
            return "(empty)";
        }
        try {
            final Object decoded = BinaryPlist.decode(body);
            if (decoded instanceof Map) {
                final StringBuilder sb = new StringBuilder();
                for (Map.Entry<?, ?> entry : ((Map<?, ?>) decoded).entrySet()) {
                    if (sb.length() > 0) {
                        sb.append(", ");
                    }
                    final String value = String.valueOf(entry.getValue());
                    sb.append(entry.getKey()).append('=')
                            .append(value.length() > 48 ? value.substring(0, 48) + "…" : value);
                }
                return sb.toString();
            }
        } catch (Throwable t) {
            /* fall through: the bytes are logged as unparsed */
        }
        return "unparsed " + body.length + "B";
    }

    private Response sealedExchange(InputStream in, OutputStream out, AirPlayPairing pairing,
                                    String method, String uri, String contentType,
                                    String extraHeaders, byte[] body) throws IOException {
        /* Seal, write, read and unseal all under one lock: AirPlayPairing's channel
         * counters advance at seal/unseal time, so two threads overlapping here would
         * put records on the wire in a different order from the nonces they used.  The
         * feedback loop shares this channel with the handshake, hence the lock. */
        final Response response;
        synchronized (mControlLock) {
            final byte[] plain = buildRequest(method, uri, contentType, extraHeaders, body);
            final byte[] sealed = pairing.sealControl(plain);
            final long startedAt = System.currentTimeMillis();
            out.write(sealed);
            out.flush();
            /* The receiver answers /auth-setup only after its MFi round and the base SETUP
             * after it has built the session, so give the sealed read a named, generous
             * budget of its own instead of the control socket's generic default. */
            setControlReadTimeout(SEALED_READ_TIMEOUT_MS);
            mState.log("airplay", "sealed " + method + " waiting up to "
                    + SEALED_READ_TIMEOUT_MS + "ms for a response");
            final byte[] answer;
            try {
                answer = mSealed.readMessage(in, new SealedControlFraming.Unsealer() {
                    @Override
                    public byte[] unseal(byte[] record) throws IOException {
                        return pairing.unsealControl(record);
                    }
                });
            } catch (Throwable t) {
                final long elapsed = System.currentTimeMillis() - startedAt;
                mState.log("airplay", "sealed " + method + " read failed after " + elapsed
                        + "ms: " + t.getClass().getName() + ": " + t.getMessage()
                        + " (records=" + mSealed.lastRecords() + ", buffered="
                        + mSealed.pendingBytes() + "B, expected one complete RTSP message, waited "
                        + SEALED_READ_TIMEOUT_MS + "ms)");
                Log.w(TAG, "sealed " + method + " read failed", t);
                if (t instanceof IOException) {
                    throw (IOException) t;
                }
                throw new IOException("sealed " + method + " failed: " + t, t);
            } finally {
                setControlReadTimeout(READ_TIMEOUT_MS);
            }
            response = parseResponse(answer);
            mState.log("airplay", "sealed tx " + method + " " + uri + " (" + plain.length + "B) -> "
                    + response.describe() + " (" + (System.currentTimeMillis() - startedAt)
                    + "ms, " + mSealed.lastRecords() + " record"
                    + (mSealed.lastRecords() == 1 ? "" : "s") + ")");
        }
        return response;
    }

    /**
     * Applies a read timeout to the control socket; a failure is logged and never fatal
     * (the exchange then simply keeps the timeout the socket already had).
     */
    private void setControlReadTimeout(int timeoutMs) {
        final Socket socket = mSocket;
        if (socket == null || socket.isClosed()) {
            return;
        }
        try {
            socket.setSoTimeout(timeoutMs);
        } catch (java.net.SocketException e) {
            mState.log("airplay", "cannot set the control read timeout to " + timeoutMs
                    + "ms: " + e);
        }
    }

    private byte[] buildRequest(String method, String uri, String contentType,
                                String extraHeaders, byte[] body) {
        final byte[] payload = body == null ? new byte[0] : body;
        final StringBuilder head = new StringBuilder();
        head.append(method).append(' ').append(uri).append(" RTSP/1.0\r\n");
        head.append("CSeq: ").append(mCseq.getAndIncrement()).append("\r\n");
        head.append("User-Agent: ").append(USER_AGENT).append("\r\n");
        if (contentType != null) {
            head.append("Content-Type: ").append(contentType).append("\r\n");
        }
        if (extraHeaders != null && !extraHeaders.isEmpty()) {
            head.append(extraHeaders).append("\r\n");
        }
        head.append("Content-Length: ").append(payload.length).append("\r\n\r\n");
        return SealedControlFraming.concat(head.toString().getBytes(StandardCharsets.US_ASCII), payload);
    }

    private Response parseResponse(byte[] message) {
        final Response response = new Response();
        final int end = SealedControlFraming.indexOf(message, "\r\n\r\n");
        if (end < 0) {
            /* Not a bare -1: say why, with a bounded readable preview.  A sealed reply that
             * lands here used to be a framing desynchronisation (see SealedControlFraming). */
            response.status = -1;
            response.headers = new String(message, StandardCharsets.US_ASCII);
            response.problem = "no RTSP header terminator in " + SealedControlFraming.previewOf(message);
            return response;
        }
        response.headers = new String(message, 0, end, StandardCharsets.US_ASCII);
        final String[] lines = response.headers.split("\r\n");
        if (lines.length > 0) {
            final String[] parts = lines[0].split(" ", 3);
            if (parts.length >= 2) {
                try {
                    response.status = Integer.parseInt(parts[1]);
                } catch (NumberFormatException ignored) {
                    response.status = -1;
                    response.problem = "status line is not 'RTSP/1.0 <code>': " + lines[0];
                }
                response.reason = parts.length > 2 ? parts[2] : "";
            }
        }
        final int bodyStart = end + 4;
        if (message.length > bodyStart) {
            response.body = new byte[message.length - bodyStart];
            System.arraycopy(message, bodyStart, response.body, 0, response.body.length);
        }
        return response;
    }

    /**
     * Raw 32-byte X25519 public key for /auth-setup.
     *
     * The JCA path is tried first, but on this platform Conscrypt has **no X25519
     * KeyPairGenerator at all** (crypto probe on lmi: `default KeyPairGenerator X25519
     * -> NoSuchAlgorithmException`, `KeyAgreement X25519 -> NoSuchAlgorithmException`,
     * BC likewise), so the pure implementation is the normal path here.
     */
    private byte[] x25519PublicKey() {
        try {
            final java.security.KeyPairGenerator generator =
                    java.security.KeyPairGenerator.getInstance("X25519");
            final java.security.KeyPair pair = generator.generateKeyPair();
            final byte[] spki = pair.getPublic().getEncoded();
            if (spki.length >= 32) {
                final byte[] raw = new byte[32];
                System.arraycopy(spki, spki.length - 32, raw, 0, 32);
                return raw;
            }
        } catch (Throwable t) {
            mState.log("airplay", "JCA X25519 unavailable (" + t.getClass().getSimpleName()
                    + ") — using the pure implementation");
        }
        try {
            return X25519Pure.generateKeyPair().publicKey();
        } catch (Throwable t) {
            mState.log("airplay", "X25519 keypair unavailable: " + t);
            return null;
        }
    }

    /** `fe80::1%p2p0` / `[fe80::1]` -> the bare host an RTSP URI can carry. */
    private static String uriHost(String address) {
        if (address == null || address.isEmpty()) {
            return "airplay";
        }
        String host = address.split("%")[0];
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        return host;
    }

    /**
     * The host the RTSP request URI must carry: the **local** address of the control
     * connection we just opened, never the peer's (the wired path does the same in
     * {@code AirPlaySession.sessionUrl} via {@code SessionTransport.localAddress()}).
     *
     * <p>Measured: a working Apple sender puts its own address in the URI —
     * `SETUP rtsp://fe80::8af:75ff:fea9:afb8/<id>` while talking to the head unit at a
     * different address.  The wireless path used to build the URI from the accessory's
     * address; the 2026-09-27 field run then failed with `sealed tx SETUP
     * rtsp://192.0.2.189/<id> -> -1`, i.e. the receiver dropped the connection
     * instead of answering.  Falls back to the target host only when the socket cannot
     * name a usable local address, and logs which one won so the trace stays greppable.
     */
    private String sessionUriHost(Socket control, String accessoryAddress) {
        String local = null;
        try {
            if (control != null && control.getLocalAddress() != null) {
                local = uriHost(control.getLocalAddress().getHostAddress());
            }
        } catch (Throwable t) {
            mState.log("airplay", "cannot read the local address of the control socket: " + t);
        }
        if (local == null || local.isEmpty() || "0.0.0.0".equals(local) || "::".equals(local)) {
            final String fallback = uriHost(accessoryAddress);
            mState.log("airplay", "session URI host=" + fallback + " source=fallback");
            return fallback;
        }
        mState.log("airplay", "session URI host=" + local + " source=local");
        return local;
    }

    /**
     * Step ⑤, exactly in the order the real iPhone used it (the session-analysis notes §2,
     * the pairing-crypto notes §7): ask the accessory to move iAP2 to Wi-Fi, request the
     * type-130 stream, connect to the `dataPort` it answers with, then seal that socket with the
     * DataStream output key and write a first iAP2 StartIDPS inside an APTransportPackage.
        /**
     * 32-byte big-endian APTransportPackage header + payload (the pairing-crypto notes §7):
     * u32 total size (header included), 12 unknown bytes, `"comm"`, u64 BE sequence number,
     * u32 zero.  xcertplay (the working accessory) reads only size@0 and the "comm" magic@16 and
     * hands buf[32:size] to iAP2; pyatv fills @4 with `"sync"+8×0x00` for requests and
     * `"rply"+8×0x00` for replies.  We deliberately send twelve zero bytes there, the choice the
     * task asked for, because no capture we hold shows the iPhone's value and nothing parses it.
     */
    static byte[] apTransportPackage(byte[] payload, long sequence) {
        final byte[] out = new byte[32 + payload.length];
        final int size = out.length;
        out[0] = (byte) (size >>> 24);
        out[1] = (byte) (size >>> 16);
        out[2] = (byte) (size >>> 8);
        out[3] = (byte) size;
        /* offset 4..16: deliberately 0x00 (see above) */
        out[16] = 'c';
        out[17] = 'o';
        out[18] = 'm';
        out[19] = 'm';
        for (int i = 0; i < 8; i++) { /* u64 big-endian sequence number */
            out[20 + i] = (byte) ((sequence >>> (8 * (7 - i))) & 0xFF);
        }
        /* offset 28..32: zero padding */
        System.arraycopy(payload, 0, out, 32, payload.length);
        return out;
    }

    /** 4 zero bytes || u64 little-endian counter — the DataStream nonce shape. */
    /** The 2 length bytes of the record are the whole AAD, exactly as on the control channel. */
    /** `[u16 LE ciphertext len][ciphertext][16-byte tag]` for a DataStream record. */
    /** `eventPort` / `dataPort` out of a (sealed) plist answer. */
    private int intFromPlist(byte[] body, String key) {
        try {
            final Object decoded = BinaryPlist.decode(body);
            if (decoded instanceof Map) {
                final Object value = ((Map<?, ?>) decoded).get(key);
                if (value instanceof Number) {
                    return ((Number) value).intValue();
                }
            }
        } catch (Throwable t) {
            mState.log("airplay", "cannot read " + key + " from the answer: " + t);
        }
        return -1;
    }

    /**
     * `streams[0].dataPort` / `.streamID` out of a (sealed) per-stream SETUP answer: the
     * accessory nests the port under the first stream, it is not a top-level key
     * (the pairing-crypto notes §6/§7).
     */
    private int streamIntFromPlist(byte[] body, String key) {
        try {
            final Object decoded = BinaryPlist.decode(body);
            if (decoded instanceof Map) {
                final Object streams = ((Map<?, ?>) decoded).get("streams");
                if (streams instanceof List && !((List<?>) streams).isEmpty()) {
                    final Object first = ((List<?>) streams).get(0);
                    if (first instanceof Map) {
                        final Object value = ((Map<?, ?>) first).get(key);
                        if (value instanceof Number) {
                            return ((Number) value).intValue();
                        }
                    }
                }
            }
        } catch (Throwable t) {
            mState.log("airplay", "cannot read streams[0]." + key + " from the answer: " + t);
        }
        return -1;
    }

    /* --------------------------------------------------------------- video face */

    /**
     * Best-effort extraction of what {@code /info} says.  Its body is ~30 kB and the
     * accessory frequently stalls the read (measured: 17645 of 30497 bytes before the
     * read timed out), so this never throws and never gates the session: the base
     * SETUP answer is the authoritative codec evidence.
     */
    private void inspectInfo(byte[] body) {
        if (body == null || body.length == 0) {
            return;
        }
        /* A binary plist keeps its key table as plain ASCII, so a raw mention of
         * "hevc" is still evidence even when the body was truncated too early for
         * BinaryPlist.decode to parse it.  It is the weakest of the signals. */
        mInfoHevcText = SealedControlFraming.indexOf(body, "hevc") >= 0
                || SealedControlFraming.indexOf(body, "HEVC") >= 0;
        try {
            final Object decoded = BinaryPlist.decode(body);
            if (decoded instanceof Map) {
                final Map<?, ?> map = (Map<?, ?>) decoded;
                boolean hevc = map.containsKey("hevcInfo");
                final Object features = map.get("enabledFeatures");
                if (features instanceof List && containsHevc(stringList(features))) {
                    hevc = true;
                }
                mInfoHevcKey = hevc;
                final Object displays = map.get("displays");
                if (displays instanceof List && !((List<?>) displays).isEmpty()) {
                    final Object first = ((List<?>) displays).get(0);
                    if (first instanceof Map) {
                        final Map<?, ?> display = (Map<?, ?>) first;
                        mInfoWidth = intOf(display.get("widthPixels"));
                        mInfoHeight = intOf(display.get("heightPixels"));
                        /* The head unit states its own frame-rate ceiling here (the
                         * the emulator accessory: `maxFPS` = 60 once switched to 60 fps
                         * mode).  We follow it automatically, like the codec. */
                        final int fps = intOf(display.get("maxFPS"));
                        if (fps > 0) {
                            mInfoMaxFps = fps;
                            mInfoFpsSource = "/info displays[0].maxFPS";
                        }
                        mState.log("airplay", "/info displays[0]=" + display);
                    }
                }
                if (mInfoMaxFps <= 0) {
                    final int fps = intOf(map.get("maxFPS"));
                    if (fps > 0) {
                        mInfoMaxFps = fps;
                        mInfoFpsSource = "/info maxFPS";
                    }
                }
                mState.log("airplay", "/info keys=" + map.keySet());
                readAdvertisedAudioFormats(map);
            }
        } catch (Throwable t) {
            mState.log("airplay", "/info plist not decodable (" + t.getClass().getSimpleName()
                    + ") — falling back to the raw text check");
        }
        mState.log("airplay", "/info evidence: hevcInfo=" + mInfoHevcKey
                + " rawHevcMention=" + mInfoHevcText
                + " display=" + mInfoWidth + 'x' + mInfoHeight
                + " maxFPS=" + mInfoMaxFps + (mInfoFpsSource.isEmpty() ? "" : " (" + mInfoFpsSource + ')'));
    }

    /**
     * Reads the accessory's advertised audio formats and keeps the one for the stream type we
     * actually SETUP — {@link WirelessAudioSender#AUDIO_STREAM_TYPE} (101, AltAudio), not 100.
     * The type-100 entry is kept as a fallback for accessories that only advertise that one.
     * The value is logged as evidence only: the AltAudio path is Opus and the SETUP asks for
     * {@link WirelessAudioSender#AUDIO_FORMAT_OPUS_48K_MONO} whatever the mask says.
     */
    private void readAdvertisedAudioFormats(Map<?, ?> info) {
        final Object formats = info.get("audioFormats");
        if (!(formats instanceof List)) {
            return;
        }
        final StringBuilder seen = new StringBuilder();
        int forStream = 0;
        int forLegacy100 = 0;
        for (Object entry : (List<?>) formats) {
            if (!(entry instanceof Map)) {
                continue;
            }
            final Map<?, ?> format = (Map<?, ?>) entry;
            final int type = intOf(format.get("type"));
            final int bits = intOf(format.get("audioFormat"));
            seen.append(type).append(":0x").append(Integer.toHexString(bits)).append(' ');
            if (bits == 0) {
                continue;
            }
            if (type == WirelessAudioSender.AUDIO_STREAM_TYPE) {
                forStream = bits;
            } else if (type == 100) {
                forLegacy100 = bits;
            }
        }
        mInfoAudioFormatBits = forStream != 0 ? forStream : forLegacy100;
        mState.log("airplay", "/info audioFormats = " + seen + "-> using 0x"
                + Integer.toHexString(mInfoAudioFormatBits) + " for stream "
                + WirelessAudioSender.AUDIO_STREAM_TYPE + " AltAudio"
                + (forStream == 0 && forLegacy100 != 0 ? " (from the type-100 advertisement)" : ""));
    }

    /** `enabledFeatures` (or any string array) out of a sealed plist answer. */
    private List<String> stringListFromPlist(byte[] body, String key) {
        try {
            final Object decoded = BinaryPlist.decode(body);
            if (decoded instanceof Map) {
                final Object value = ((Map<?, ?>) decoded).get(key);
                if (value instanceof List) {
                    return stringList(value);
                }
            }
        } catch (Throwable t) {
            mState.log("airplay", "cannot read " + key + " from the answer: " + t);
        }
        return Collections.emptyList();
    }

    private static List<String> stringList(Object value) {
        final List<String> out = new ArrayList<String>();
        for (Object item : (List<?>) value) {
            if (item != null) {
                out.add(String.valueOf(item));
            }
        }
        return out;
    }

    /** Any "hevc"/"h265" entry in an advertised feature list. */
    private static boolean containsHevc(List<String> features) {
        for (String feature : features) {
            final String name = feature.toLowerCase(Locale.US);
            if (name.startsWith("hevc") || name.startsWith("h265") || name.startsWith("h.265")) {
                return true;
            }
        }
        return false;
    }

    private static int intOf(Object value) {
        return value instanceof Number ? ((Number) value).intValue() : -1;
    }

    /**
     * The codec decision, and it is automatic: the accessory decodes our stream, so what
     * it advertises selects the codec — HEVC when it asks for HEVC and this device has an
     * HEVC surface encoder at that size, H.264 otherwise.  Evidence, in the order the
     * answers arrive:
     *   - `enabledFeatures` of the base SETUP answer (the captured the emulator:
     *     `['hevc', 'iAPChannel', 'viewAreas']` — the session-analysis notes §2.4),
     *   - `enabledFeatures` of the stream-110 SETUP answer when it carries one,
     *   - `/info`'s `hevcInfo` key, or a raw "hevc" mention in a truncated /info body.
     * The only override is the pin stored for this head unit (see
     * {@link com.okcar.link.wireless.AccessoryMemory#codec}); the decision itself is
     * {@link WirelessScreenHeader#chooseCodec}.
     */
    private int resolveVideoCodec(int width, int height) {
        final boolean hevcEncoder = MirrorVideoSource.isVideoCodecAvailable(
                MirrorVideoSource.MIME_HEVC, width, height);
        /* A pin belongs to the head unit it was made for (see AccessoryMemory#codec): reading it
         * here, from that unit's own record, is what keeps one car's codec test out of another's
         * session. */
        final com.okcar.link.wireless.AccessoryMemory.Codec pinned =
                com.okcar.link.wireless.AccessoryMemory.codec(mContext, mAccessoryAddress);
        if (pinned == com.okcar.link.wireless.AccessoryMemory.Codec.H264) {
            mState.log("airplay", "video codec H.264: pinned for this head unit (HEVC encoder at "
                    + width + 'x' + height + '=' + hevcEncoder + ')');
            return WirelessScreenHeader.CODEC_AVC;
        }
        if (pinned == com.okcar.link.wireless.AccessoryMemory.Codec.HEVC) {
            if (hevcEncoder) {
                mState.log("airplay", "video codec HEVC: pinned for this head unit");
                return WirelessScreenHeader.CODEC_HEVC;
            }
            mState.log("airplay", "video codec HEVC pinned for this head unit but this device has no "
                    + "video/hevc surface encoder at " + width + 'x' + height + " — sending H.264");
            return WirelessScreenHeader.CODEC_AVC;
        }
        final StringBuilder evidence = new StringBuilder();
        boolean hevc = false;
        if (containsHevc(mSetupEnabledFeatures)) {
            hevc = true;
            evidence.append("base SETUP enabledFeatures=").append(mSetupEnabledFeatures).append("; ");
        }
        if (containsHevc(mStreamEnabledFeatures)) {
            hevc = true;
            evidence.append("stream-110 SETUP enabledFeatures=").append(mStreamEnabledFeatures)
                    .append("; ");
        }
        /* The /info hints are recorded but deliberately do NOT select HEVC any more: they are a
         * mention, not a capability.  Fruit Link's /info carries an `hevcInfo` key while its own
         * decoder says `### HEVC supported: N`, and acting on that mention sent it an HEVC stream
         * its decoder refused — `[AirPlayReceiverSessionScreen] Screen thread exit at line 410,
         * error -6752` and a torn picture (measured 2026-10-02 17:49).  Its H.264 records, by
         * contrast, decode fine.  A receiver that really wants HEVC says so in the SETUP answers
         * (the emulator: `enabledFeatures=[hevc, iAPChannel, viewAreas]`). */
        if (mInfoHevcKey || mInfoHevcText) {
            evidence.append("(/info hinted HEVC — key=").append(mInfoHevcKey).append(", text=")
                    .append(mInfoHevcText).append(" — not acted on); ");
        }
        if (!hevc) {
            evidence.append("no HEVC advertisement (base/stream enabledFeatures=")
                    .append(mSetupEnabledFeatures).append('/').append(mStreamEnabledFeatures)
                    .append("); ");
        }
        /*
         * One automatic decision, taken from what the accessory advertises — see
         * WirelessScreenHeader.chooseCodec().  No pin: the head unit decodes, so its
         * capability (intersected with our encoder) decides.
         */
        final int chosen = WirelessScreenHeader.chooseCodec(hevc, hevcEncoder);
        if (chosen == WirelessScreenHeader.CODEC_HEVC) {
            mState.log("airplay", "video codec HEVC (accessory advertised it; device encoder "
                    + "available): " + evidence);
        } else if (!hevc) {
            mState.log("airplay", "video codec H.264: accessory did not advertise HEVC "
                    + "(enabledFeatures=" + mSetupEnabledFeatures + '/' + mStreamEnabledFeatures
                    + ", /info hevcInfo=" + mInfoHevcKey + ')');
        } else {
            mState.log("airplay", "video codec H.264: accessory advertised HEVC (" + evidence
                    + ") but this device has no video/hevc surface encoder at "
                    + width + 'x' + height);
        }
        return chosen;
    }

    /**
     * Encoder/display size for the next stream, or null when this phone has nothing honest to size
     * it with.
     *
     * <p>Two sources, both about the head unit, and neither of them a guess:
     *
     * <ol>
     *   <li>what this unit declared on the wire in this session ({@code /info displays[0]}, or the
     *       screen stream SETUP's {@code width}/{@code height});</li>
     *   <li>the screen this unit was last driven at, read from its own stored config — split by
     *       orientation, so a landscape record is never handed to a portrait session.</li>
     * </ol>
     *
     * <p>What used to be here, and is deliberately gone (2026-10-02): this phone's own panel in
     * landscape, the projection capture region, and a bare 1280x720.  Each one guessed the head
     * unit's screen from something that is not the head unit, and the first two were measured to
     * produce a wrong-aspect picture whose touch mapping was wrong by exactly the same factor.  A
     * unit we know nothing about now fails loudly, naming the operator command that fixes it,
     * instead of being handed a stream that cannot be right.
     */
    /** 触摸回退空间：车机声明/记忆的屏幕尺寸优先，没有才退回视频几何。 */
    private int[] resolveTouchSpace(int[] videoSize) {
        loadAccessorySpace();
        if (usable(mInfoWidth) && usable(mInfoHeight)) {
            return new int[] {mInfoWidth, mInfoHeight};
        }
        if (usable(mAccessorySpaceWidth) && usable(mAccessorySpaceHeight)) {
            return new int[] {mAccessorySpaceWidth, mAccessorySpaceHeight};
        }
        return videoSize;
    }

    private int[] resolveVideoSize() {
        /* 联动模式（CarWith 交面）优先：这时画面是 CarWith 那块投屏屏，几何就是我们 arm() 时给它的
         * 尺寸。车机声明的只是它**自己的屏幕**——按它建流会把横屏帧塞进竖屏会话，接收端于是把整幅
         * 画面转 90°：2026-10-09 现场会话按 1080x2400 建、帧是 1920x720，车机屏文字竖排；而 10-08
         * 按画面尺寸（1920x864）建流时画面是正的。 */
        final com.okcar.link.carkit.CarwithCastClient carwith =
                com.okcar.link.carkit.CarwithCastClient.get();
        if (carwith.armed()) {
            final int castWidth = carwith.castWidth();
            final int castHeight = carwith.castHeight();
            if (usable(castWidth) && usable(castHeight)) {
                return finishSize(castWidth, castHeight,
                        "from the CarWith cast picture (coordination armed)");
            }
        }
        loadAccessorySpace();
        if (usable(mInfoWidth) && usable(mInfoHeight)) {
            return rememberAccessoryScreen(mInfoWidth, mInfoHeight,
                    com.okcar.link.wireless.AccessoryMemory.Source.DECLARED,
                    "declared by the head unit");
        }
        if (usable(mAccessorySpaceWidth) && usable(mAccessorySpaceHeight)) {
            return finishSize(mAccessorySpaceWidth, mAccessorySpaceHeight,
                    "from this unit's stored config");
        }
        mState.log("airplay", "no screen known for this head unit"
                + (mAccessoryAddress == null || mAccessoryAddress.isEmpty()
                        ? " (no accessory address yet)" : " " + mAccessoryAddress)
                + " — refusing to guess; pin one with `okcar-linkd send size WxH`");
        return null;
    }

    /** Records a screen for this unit, under the orientation the size itself names. */
    private int[] rememberAccessoryScreen(int width, int height, String source, String why) {
        com.okcar.link.wireless.AccessoryMemory.rememberScreen(mContext, mAccessoryAddress,
                width, height, source);
        mAccessorySpaceWidth = width;
        mAccessorySpaceHeight = height;
        return finishSize(width, height, why);
    }

    /**
     * Loads the screen this unit's stored config holds, if any.
     *
     * <p>Reloaded on every stream start rather than cached for the session: a pin applied while a
     * session runs has to be visible to the next stream, and the store is the only place a size
     * lives now.  The in-memory value is cleared first, so a pin or a different unit can never be
     * masked by whatever the previous stream happened to use.
     */
    private void loadAccessorySpace() {
        mAccessorySpaceWidth = -1;
        mAccessorySpaceHeight = -1;
        final com.okcar.link.wireless.AccessoryMemory.Screen known =
                com.okcar.link.wireless.AccessoryMemory.lastScreen(mContext, mAccessoryAddress);
        if (known == null) {
            return;
        }
        mAccessorySpaceWidth = known.width;
        mAccessorySpaceHeight = known.height;
        mState.log("airplay", com.okcar.link.wireless.AccessoryMemory.describe(mContext,
                mAccessoryAddress));
    }

    /**
     * Re-reads this unit's stored config, so a pin or a clear made mid-session takes effect on the
     * next stream instead of being masked by what the current stream already resolved.  Both the
     * screen and the codec are read per stream from this unit's record.
     */
    public void reloadCarConfig() {
        loadAccessorySpace();
        mNoScreenReason = null;
        final com.okcar.link.wireless.AccessoryMemory.Codec codec =
                com.okcar.link.wireless.AccessoryMemory.codec(mContext, mAccessoryAddress);
        mState.log("airplay", "car config reloaded: screen "
                + (usable(mAccessorySpaceWidth) && usable(mAccessorySpaceHeight)
                        /* "x", not 'x': a char here makes Java add the three ints instead of
                         * concatenating (1080 + 120 + 2400 = "3600"), which is what this line logged
                         * before. */
                        ? mAccessorySpaceWidth + "x" + mAccessorySpaceHeight
                                + " (orientation " + com.okcar.link.wireless.AccessoryMemory
                                        .Orientation.of(mAccessorySpaceWidth, mAccessorySpaceHeight).key() + ')'
                        : "not stored for this unit")
                + ", codec " + (codec == null ? "auto (follows the unit)" : codec.name()));
    }

    /** Shared tail of {@link #resolveVideoSize()}: even dimensions and one log line. */
    private int[] finishSize(int width, int height, String source) {
        width &= ~1;
        height &= ~1;
        mState.log("airplay", "video size " + width + 'x' + height + " " + source
                + " (orientation " + com.okcar.link.wireless.AccessoryMemory.Orientation
                        .of(width, height).key()
                + ", accessory displays[0]=" + mInfoWidth + 'x' + mInfoHeight + ')');
        return new int[] {width, height};
    }

    private static boolean usable(int dimension) {
        return dimension >= 320 && dimension <= 4096;
    }

    /* ------------------------------------------------------------------- touch */

    /** Off switch for on-device testing; the wired path has the same knob. */
    public void setInjectTouch(boolean enabled) {
        mInjectTouch = enabled;
        mState.log("airplay", "touch injection " + (enabled ? "enabled" : "disabled"));
    }

    /**
     * Session fallback space for reports that do not declare their own.  The iAP2
     * decoder carries 1920x720 per report, and an AirPlay slot carries nothing
     * because the receiver sends its own screen pixels (1080x2400 on this link), so
     * this video-sized value is exactly the space those pixels are in.
     */
    private void setTouchSpace(int width, int height) {
        if (width <= 0 || height <= 0) {
            return;
        }
        mTouchSpaceWidth = width;
        mTouchSpaceHeight = height;
        final InputInjector injector = mInjector;
        if (injector != null) {
            injector.setSourceSpace(width, height);
        }
        mState.log("airplay", "injector fallback space = " + width + 'x' + height
                + " (touch reports carry their own source space)");
    }

    private InputInjector injector() {
        InputInjector injector = mInjector;
        if (injector == null) {
            synchronized (this) {
                if (mInjector == null) {
                    mInjector = new InputInjector(mContext);
                    if (mTouchSpaceWidth > 0 && mTouchSpaceHeight > 0) {
                        mInjector.setSourceSpace(mTouchSpaceWidth, mTouchSpaceHeight);
                    }
                }
                injector = mInjector;
            }
        }
        return injector;
    }

    /**
     * Adopt the space an accessory's own HID report declares.
     *
     * <p>The unit is the authority on its own screen, so a space it reports is recorded for it —
     * under the orientation that space itself names, so a landscape report can never be handed to
     * a portrait session later.  This is also the only way a unit that declares nothing in `/info`
     * ever gets a config: it teaches us here once, and every later session starts correct.
     *
     * <p>The picture is already on the car's screen by the time the first report arrives, and
     * re-opening a live stream was measured on the production head unit (2026-10-02) to land touch on the
     * wrong pixels and get the session dropped within ~10 s, so the value is stored for the next
     * session instead of swapping geometry under a live decoder.
     */
    private void noteAccessorySpace(int width, int height) {
        if (!usable(width) || !usable(height) || width > 8192 || height > 8192) {
            return;
        }
        if (width == mAccessorySpaceWidth && height == mAccessorySpaceHeight) {
            return;
        }
        final boolean alreadyKnown = usable(mAccessorySpaceWidth) && usable(mAccessorySpaceHeight);
        mAccessorySpaceWidth = width;
        mAccessorySpaceHeight = height;
        if (!alreadyKnown) {
            mState.log("airplay", "the accessory's own space is " + width + 'x' + height
                    + " (from its HID reports, orientation "
                    + com.okcar.link.wireless.AccessoryMemory.Orientation.of(width, height).key()
                    + ')');
        }
        final int[] current = mCurrentVideoSize;
        if (current != null && current[0] == width && current[1] == height) {
            return;
        }
        mState.log("airplay", "the accessory's own space " + width + 'x' + height
                + " is stored for the next session (the live stream is left alone)");
        com.okcar.link.wireless.AccessoryMemory.rememberScreen(mContext, mAccessoryAddress,
                width, height, com.okcar.link.wireless.AccessoryMemory.Source.REPORTED);
    }

    /**
     * One HID report from the accessory, whichever channel it arrived on (the iAP2 link's
     * 0x6802 HIDReport or an AirPlay `hidReport` command on the event channel).  Touch is
     * injected; media/phone key reports are only recorded, because their payload encoding
     * is still unknown and a wrong key injection is worse than none.
     */
    private void injectHidReport(byte[] payload, String source) {
        if (payload == null || payload.length == 0) {
            mState.log("airplay", "HID from " + source + ": empty payload, ignored");
            return;
        }
        dumpHidRaw(payload, source);
        /* Length-classified AirPlay path; typed iAP2 reports inside decodeWireless
         * fall back to the unchanged decode().  The wired path still calls decode(). */
        final HidReportDecoder.Report report = HidReportDecoder.decodeWireless(payload);
        if (report == null) {
            mState.log("airplay", "HID from " + source + ": undecodable " + payload.length
                    + "B, ignored");
            return;
        }
        final boolean airplayLayout = report.airplay;
        mState.log("airplay", "HID from " + source + " [" + (airplayLayout
                ? "AirPlay " + payload.length + "-byte/" + report.rawLength + "B"
                : "iAP2 5-byte") + "]: " + report.describe());
        if (report.type != HidReportDecoder.TYPE_TOUCH) {
            mState.log("airplay", "non-touch HID recorded only (type=0x"
                    + Integer.toHexString(report.type) + ')');
            return;
        }
        if (!mInjectTouch) {
            mState.log("airplay", "touch injection disabled — report dropped");
            return;
        }
        noteAccessorySpace(report.sourceWidth, report.sourceHeight);
        /* A report that declares its own space needs nothing from us; one that does not (the
         * AirPlay slot layout declares none) can only be scaled once this session knows the
         * screen.  Without that knowledge the injection would be silently wrong — the same missing
         * knowledge that already refused the picture — so refuse the touch as well, and say it
         * once per session instead of once per report. */
        if (report.sourceWidth <= 0 && (mTouchSpaceWidth <= 0 || mTouchSpaceHeight <= 0)) {
            if (!mTouchSpaceMissingLogged) {
                mTouchSpaceMissingLogged = true;
                mState.log("airplay", "no known screen space for this head unit — touch reports are"
                        + " not injected (pin the screen with `okcar-linkd send size WxH`)");
            }
            return;
        }
        final boolean ok = injector().injectTouch(report);
        mState.log("airplay", "touch injected=" + ok + " down=" + report.down + " at "
                + report.x + ',' + report.y + " (space "
                + (report.sourceWidth > 0 ? report.sourceWidth : mTouchSpaceWidth) + 'x'
                + (report.sourceHeight > 0 ? report.sourceHeight : mTouchSpaceHeight) + ')');
    }

    /**
     * Bounded raw dump of one wireless HID report.  This is Task 1 evidence: the
     * receiver's Touch Screen descriptor is 6 bytes per finger but the captured
     * session only ever produced decoded values, never the bytes, so the down/up
     * flag could not be read off it.  Logging the first few reports and every
     * later report whose first byte or length changes captures a press + release
     * pair without flooding the session.
     */
    private void dumpHidRaw(byte[] payload, String source) {
        final int first = payload[0] & 0xFF;
        final int second = payload.length > 1 ? payload[1] & 0xFF : -1;
        final boolean wanted = mHidReports < HID_RAW_FIRST
                || first != mHidRawLastFirst
                || second != mHidRawLastSecond
                || payload.length != mHidRawLastLength;
        if (wanted && mHidRawLogged < HID_RAW_MAX) {
            mHidRawLogged++;
            mState.log("airplay", "HID raw #" + mHidReports + " " + payload.length
                    + "B first=0x" + Integer.toHexString(first) + " second=0x"
                    + Integer.toHexString(second) + " from " + source + ": "
                    + hex(payload));
        }
        mHidReports++;
        mHidRawLastFirst = first;
        mHidRawLastSecond = second;
        mHidRawLastLength = payload.length;
    }

    private static String hex(byte[] data) {
        final StringBuilder out = new StringBuilder(data.length * 3);
        for (int i = 0; i < data.length; i++) {
            if (i > 0) {
                out.append(' ');
            }
            final int value = data[i] & 0xFF;
            if (value < 0x10) {
                out.append('0');
            }
            out.append(Integer.toHexString(value));
        }
        return out.toString();
    }

    /** The HID bytes of an AirPlay `hidReport` command, tolerating a couple of shapes. */
    private static byte[] hidReportBytes(Map<?, ?> map) {
        Object value = map.get("hidReport");
        final Object params = map.get("params");
        if (value == null && params instanceof Map) {
            value = ((Map<?, ?>) params).get("hidReport");
        }
        if (value == null && params instanceof Map) {
            value = ((Map<?, ?>) params).get("data");
        }
        if (value == null) {
            value = map.get("data");
        }
        if (value instanceof byte[]) {
            return (byte[]) value;
        }
        if (value instanceof List) {
            final List<?> list = (List<?>) value;
            final byte[] out = new byte[list.size()];
            for (int i = 0; i < out.length; i++) {
                out[i] = (byte) intOf(list.get(i));
            }
            return out;
        }
        return null;
    }

    /**
     * The frame rate, automatic and owned by the accessory exactly like the codec:
     * its advertised ceiling (`/info` displays[0].maxFPS, else a base-SETUP `maxFPS`)
     * selects 60 fps when it asks for 50 or more, otherwise 30.  A silent accessory
     * gets 30.  Snapping happens in {@link WirelessScreenHeader#chooseFps(int)}.
     *
     * <p>There used to be a second, silent cap here — {@code Math.min(chosen, maxEncoderFps())}
     * with a hard-coded default ceiling of 30 — and because {@code persist.okcar.max_fps} is
     * normally unset, that default quietly overrode the negotiation for everyone.  Measured on
     * 217 with the emulator (2026-10-21): the accessory advertised {@code maxFPS=60} in
     * {@code /info displays[0]}, the log said "video fps 30: accessory advertised maxFPS=60 …
     * capped by persist.okcar.max_fps", and the stream ran 29.9 fps.  "Restore automatic" could
     * therefore never follow the head unit.  The ceiling is now opt-in (see
     * {@link #maxEncoderFps()}).
     */
    private int resolveVideoFps() {
        final int advertised = mInfoMaxFps > 0 ? mInfoMaxFps : mSetupMaxFps;
        final String source = mInfoMaxFps > 0 ? mInfoFpsSource
                : (mSetupMaxFps > 0 ? "base SETUP maxFPS" : "");
        final int chosen = WirelessScreenHeader.chooseFps(advertised);
        final int cap = maxEncoderFps();
        final int fps = WirelessScreenHeader.applyEncoderCap(chosen, cap);
        if (advertised > 0) {
            mState.log("airplay", "video fps " + fps + ": accessory advertised maxFPS="
                    + advertised + " (" + source + ')'
                    + (fps < chosen ? " — capped by persist.okcar.max_fps=" + cap
                            : " — following the accessory"));
        } else {
            mState.log("airplay", "video fps " + fps
                    + ": accessory advertised no frame rate (/info maxFPS=" + mInfoMaxFps
                    + ", base SETUP maxFPS=" + mSetupMaxFps + ')');
        }
        return fps;
    }

    /**
     * The phone's own ceiling, independent of what the accessory advertises.
     *
     * <p><b>No cap by default.</b>  Unset {@code persist.okcar.max_fps} (or 0) means the accessory
     * decides, which is the entire point of the automatic path: a head unit advertising 60 fps gets
     * 60.  A positive value caps the rate.
     *
     * <p>It used to default to 30, because on 217 60 fps of 2400x1080 H.264 plus per-frame sealing
     * once took the system with it: right after a successful cast the phone froze and the ANR traces
     * show system_server timing out on input dispatch (waited 15339 ms for a MotionEvent) while the
     * encoder, a Flutter app and MIUI shared the CPU (2026-10-01, see
     * the field acceptance log §4.5).  That incident is what the graded stall
     * response now covers (see {@code LinkDaemon.startSystemStallWatch}): a starved phone sheds the
     * display, then the whole pipeline, then suspends casting for a recovery window — so the frame
     * rate no longer has to be given up front to stay safe.  A phone that still cannot keep up sets
     * {@code setprop persist.okcar.max_fps 30} explicitly.
     */
    private static int maxEncoderFps() {
        try {
            final Class<?> sp = Class.forName("android.os.SystemProperties");
            final Object value = sp.getMethod("get", String.class, String.class)
                    .invoke(null, "persist.okcar.max_fps", "0");
            final int fps = Integer.parseInt(String.valueOf(value).trim());
            return fps > 0 ? fps : 0;                    /* 0 = no phone-side cap */
        } catch (Throwable t) {
            return 0;                                    /* 0 = no phone-side cap */
        }
    }

    /**
     * The audio stream (type 101, the accessory's realtime "AltAudio"): its own SETUP, its own
     * {@code dataPort} (UDP) and its own data-stream key.  The accessory answers with the port it
     * listens on; where the answer carries no port we log it and leave audio alone rather than
     * guessing.
     *
     * <p>The stream type and the {@code audioType} are not cosmetic: this accessory only wires the
     * type-101 "AltAudio" stream to the car's output, and a SETUP without {@code audioType} is set
     * up "for Unknown" and never fed (see {@link WirelessAudioSender#AUDIO_STREAM_TYPE}).
     */
    private void startAudioStream(java.io.InputStream in, java.io.OutputStream out,
                                  AirPlayPairing pairing, String uri, String accessoryAddress,
                                  long streamConnectionId) {
        try {
            /*
             * Opus, because this receiver's AltAudio path is an opus->lpcm jitter buffer: it sets
             * the stream up from the SETUP bits, and PCM on type 101 is recognised but never fed
             * (see WirelessAudioSender's class comment for the receiver's own log lines).  The
             * advertised mask is logged for evidence; the request is the iPhone's exact value.
             */
            final int advertised = mInfoAudioFormatBits;
            final int audioFormatBits = WirelessAudioSender.chooseAudioFormatBits(advertised);
            final Map<String, Object> stream =
                    WirelessAudioSender.setupStream(streamConnectionId, audioFormatBits);
            mState.log("airplay", "audio SETUP asks for streamType="
                    + WirelessAudioSender.AUDIO_STREAM_TYPE + " AltAudio"
                    + " audioType=" + WirelessAudioSender.AUDIO_TYPE_DEFAULT
                    + " audioFormat=0x" + Integer.toHexString(audioFormatBits)
                    + " (" + WirelessAudioSender.sampleRateForFormat(audioFormatBits) + "Hz "
                    + WirelessAudioSender.channelsForFormat(audioFormatBits) + "ch opus)"
                    + " audioLatencyMs=" + WirelessAudioSender.AUDIO_LATENCY_MS
                    + " (advertised 0x" + Integer.toHexString(advertised) + ')');
            final Map<String, Object> streams = new LinkedHashMap<String, Object>();
            streams.put("streams", java.util.Collections.singletonList(stream));
            final Response reply = sealedExchange(in, out, pairing, "SETUP", uri,
                    "application/x-apple-binary-plist", null, BinaryPlist.encode(streams));
            final int dataPort = streamIntFromPlist(reply.body, "dataPort");
            final int chosenFormat = streamIntFromPlist(reply.body, "audioFormat");
            /* The accessory's echo wins when its own resolution knows it; else what we asked for. */
            final int negotiated = WirelessAudioSender.negotiatedFormatBits(
                    audioFormatBits, chosenFormat);
            final int channels = WirelessAudioSender.channelsForFormat(negotiated);
            final int sampleRate = WirelessAudioSender.sampleRateForFormat(negotiated);
            mState.log("airplay", "audio SETUP -> " + reply.describe() + " dataPort=" + dataPort
                    + " audioFormat=0x" + Integer.toHexString(chosenFormat)
                    + " -> negotiated=0x" + Integer.toHexString(negotiated)
                    + " streamType=" + WirelessAudioSender.AUDIO_STREAM_TYPE
                    + " audioType=" + WirelessAudioSender.AUDIO_TYPE_DEFAULT
                    + " channels=" + (channels > 0 ? channels : 1)
                    + " sampleRate=" + (sampleRate > 0 ? sampleRate : 48000)
                    + " controlPort=" + streamIntFromPlist(reply.body, "controlPort"));
            if (reply.status != 200 || dataPort <= 0) {
                mState.log("airplay", "audio SETUP gave no dataPort — playback stays on the phone");
                return;
            }
            /* The Opus encoder is a hard requirement: no encoder, no stream.  There is no PCM
             * fallback, because the SETUP just told the receiver the stream is Opus. */
            final String opusCodec = OpusAudioEncoder.findEncoderName();
            final AudioEncoder encoder;
            try {
                encoder = OpusAudioEncoder.create();
            } catch (Throwable t) {
                mState.log("airplay", "audio encoder=opus UNAVAILABLE (probe="
                        + (opusCodec == null ? "none" : opusCodec) + "): " + t
                        + " — no audio stream started");
                return;
            }
            mState.log("airplay", "audio encoder=" + encoder.describe());
            /* REMOTE_SUBMIX captures nothing while STREAM_MUSIC is muted — the car ROM ships it
             * muted, so the wireless path unmutes exactly like the wired session does
             * (OkCarLinkService.unmutePlayback). */
            unmuteMusicStream();
            /* The control socket is already connected to the accessory, scope and all, so its
             * address is the one UDP must use (a bare link-local without %scope cannot send). */
            final java.net.InetAddress address = mSocket != null
                    ? mSocket.getInetAddress() : java.net.InetAddress.getByName(accessoryAddress);
            final WirelessAudioSender sender = new WirelessAudioSender(mContext, address, dataPort,
                    streamConnectionId, pairing, encoder);
            mAudioSender = sender;
            final Thread thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    sender.run();
                }
            }, "okcar-wireless-audio");
            thread.setDaemon(true);
            mAudioThread = thread;
            thread.start();
            mState.log("airplay", "audio stream starting on " + address.getHostAddress() + ':'
                    + dataPort + " (stream " + WirelessAudioSender.AUDIO_STREAM_TYPE
                    + " AltAudio, audioType=" + WirelessAudioSender.AUDIO_TYPE_DEFAULT
                    + ", " + encoder.describe() + ", sealed RTP)");
        } catch (Throwable t) {
            mState.log("airplay", "audio stream failed to start: " + t);
        }
    }

    /**
     * Starts the wireless screen stream on its own thread, immediately after the
     * stream-110 SETUP answer gave us the dataPort (the encoder takes a moment to
     * produce its first IDR, which is also when RECORD goes out on the control
     * channel).  The type-130 tunnel is untouched by any of this.
     */

    private void startVideo(String accessoryAddress, int dataPort, long streamConnectionId,
                            AirPlayPairing pairing) {
        stopVideo();
        mStreamHost = accessoryAddress;
        mStreamDataPort = dataPort;
        mStreamPairing = pairing;   /* mStreamConnectionId is set by the SETUP handler */
        final int[] size = resolveVideoSize();
        if (size == null) {
            /* No guessing here any more: without a size the picture would be wrong and touch would
             * be scaled by that same wrong space.  Refuse the stream, keep the reason where the
             * watchdog and `okcar-linkd status` can print it, and leave the session itself alone so
             * audio and the control channel are unaffected. */
            mNoScreenReason = "no screen size known for this head unit"
                    + (mAccessoryAddress == null || mAccessoryAddress.isEmpty()
                            ? "" : " (" + mAccessoryAddress + ')')
                    + " — video refused; pin one with `okcar-linkd send size WxH`";
            mState.log("airplay", mNoScreenReason);
            return;
        }
        mNoScreenReason = null;
        mCurrentVideoSize = size;
        final int codec = resolveVideoCodec(size[0], size[1]);
        final int fps = resolveVideoFps();
        /* 触摸回退空间跟"车机自己的屏幕"走，不跟视频几何走：AirPlay 槽的触摸报的是接收端像素
         * （实测 1080x2400），而联动模式下视频几何是 CarWith 那块屏（1920x720）。 */
        final int[] touchSpace = resolveTouchSpace(size);
        setTouchSpace(touchSpace[0], touchSpace[1]);
        /* Letterboxing has to be avoided, but there are two ways to do it and only one is right
         * per platform:
         *
         *  - OkCarOS mirrors the *physical* screen, so the phone's own logical size has to be the
         *    car's size or the 1920x720 car UI lands letterboxed inside the 2400x1080 video.
         *  - The port build hands the mirror display to Ports (MediaProjection), which creates the
         *    virtual display at the car's size itself — the projection scales the phone's content
         *    into it.  Resizing the phone's display as well is then not merely redundant: it
         *    squashes the real UI (measured 1080x2182 on a 1080x2400 panel), breaks gesture
         *    navigation, and survives the process if it dies mid-session.
         *
         * So only the platform that owns the physical screen gets its size changed. */
        /* The root backend builds the display at the car's size itself, so the phone's own
         * display is never resized (that legacy path squashed the real UI and survived crashes). */
        mState.log("airplay", "mirror display is created by the root backend at " + size[0]
                + 'x' + size[1] + " — leaving the phone's own display size alone");
        final String host = mSocket != null
                ? mSocket.getInetAddress().getHostAddress() : accessoryAddress;
        final WirelessVideoSender sender = new WirelessVideoSender(mContext, host, dataPort,
                streamConnectionId, pairing, codec, size[0], size[1], fps);
        sender.setCarrierInterface(mCarrierInterface);
        sender.setPlainDump(mPlainDumpPath);
        sender.setEncoderName(mEncoderName);
        /* CarWith as the picture source (2026-10-07): when the daemon is armed for CarWith, this
         * session's encoder input surface is what CarWith renders its virtual display into, so the
         * car sees CarWith's own composition instead of a mirror of the phone screen.  Decided here
         * because the sender — and therefore the encoder — is created per session. */
        final com.okcar.link.carkit.CarwithCastClient carwith =
                com.okcar.link.carkit.CarwithCastClient.get();
        if (carwith.armed()) {
            /* Mirror mode wins (2026-10-08): CarWith's own wired-ICCOA cast display is the picture,
             * because the CarLife-slot surface handoff is accepted but never routed into
             * CarlinkStateMachine, and that display has no SurfaceFlinger capture token. */
            final int mirror = carwith.mirrorDisplay();
            if (mirror >= 0 || mirror == com.okcar.link.port.DisplayMirror.AUTO) {
                sender.setMirrorSourceDisplay(mirror);
            } else {
                sender.setExternalDisplayProvider(carwith.externalDisplay());
            }
        }
        /* H.264: reuse the shape the wired path was measured to send.
         *
         * The wired the production head unit capture (WIRED_AVCC in WirelessScreenHeader's self-test) is a bare
         * AVCDecoderConfigurationRecord, and avcDecoderConfigurationRecord() reproduces it byte
         * for byte.  What we send over wireless instead is `avc1`+`avcC` — an extrapolation from
         * the captured `hvc1` sample entry, because the wireless H.264 CSD was never captured.
         * This head unit is H.264-only and old, and the wired path on it is proven, so prefer
         * the shape that is known to work rather than the guessed one.
         *
         * A receiver that did hand us a captured sample entry (HEVC) keeps it. */
        if (mRawVideoConfig || codec == WirelessScreenHeader.CODEC_AVC) {
            sender.setRawConfig(true);
        }
        mVideoSender = sender;
        final Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                sender.run();
                /* The video thread ending means the whole stream is gone — this used to say nothing, so the log could
                 * not explain "why the picture froze" until the process was killed and restarted (field, 2026-10-04).
                 * The liveness poll (monitorAirPlayLiveness) judges by isStreaming(); all that is needed here is to
                 * state the fact: the session watchdog will tear the session down and re-arm the listener. */
                mState.log("airplay", "video stream ended ("
                        + (sender.error().isEmpty() ? "stopped" : sender.error())
                        + ") — the session watchdog will tear the session down and re-arm the listener");
            }
        }, "okcar-wireless-video");
        thread.setDaemon(true);
        mVideoThread = thread;
        thread.start();
        mState.log("airplay", "video stream starting on " + host + ':' + dataPort + " ("
                + (codec == WirelessScreenHeader.CODEC_HEVC ? "HEVC" : "H.264") + ' '
                + size[0] + 'x' + size[1] + '@' + fps + ')');
    }

    /** See the wired path's OkCarLinkService.unmutePlayback: same reason, same effect. */
    private void unmuteMusicStream() {
        try {
            final android.media.AudioManager manager = (android.media.AudioManager)
                    mContext.getSystemService(android.content.Context.AUDIO_SERVICE);
            if (manager == null) {
                return;
            }
            final int stream = android.media.AudioManager.STREAM_MUSIC;
            manager.setStreamMute(stream, false);
            if (manager.getStreamVolume(stream) == 0) {
                final int max = manager.getStreamMaxVolume(stream);
                manager.setStreamVolume(stream, Math.max(1, max * 2 / 3), 0);
            }
            mState.log("airplay", "music stream unmuted (volume " + manager.getStreamVolume(stream)
                    + '/' + manager.getStreamMaxVolume(stream) + ')');
        } catch (Throwable t) {
            mState.log("airplay", "cannot unmute the music stream: " + t);
        }
    }

    private void stopAudio() {
        final WirelessAudioSender sender = mAudioSender;
        mAudioSender = null;
        mAudioThread = null;
        if (sender != null) {
            sender.stop();
        }
    }

    private void stopVideo() {
        final WirelessVideoSender sender = mVideoSender;
        mVideoSender = null;
        final Thread thread = mVideoThread;
        mVideoThread = null;
        if (sender != null) {
            sender.stop();
        }
        if (thread != null) {
            thread.interrupt();
        }
    }

    /* ------------------------------------------------------------ feedback loop */

    /**
     * `POST /feedback` every ≈2 s on the sealed control channel, exactly like the real
     * iPhone (23 of them in the 46 s capture).  Cheap, serialised with every other
     * control exchange, and never fatal: the first failure stops the loop only.
     */
    private void startFeedbackLoop() {
        if (mFeedbackRunning) {
            return;
        }
        mFeedbackRunning = true;
        final Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (mFeedbackRunning) {
                    try {
                        Thread.sleep(FEEDBACK_INTERVAL_MS);
                    } catch (InterruptedException e) {
                        return;
                    }
                    if (!mFeedbackRunning) {
                        return;
                    }
                    try {
                        sendFeedback();
                    } catch (Throwable t) {
                        mState.log("airplay", "feedback stopped: " + t);
                        mFeedbackRunning = false;
                        return;
                    }
                }
            }
        }, "okcar-airplay-feedback");
        thread.setDaemon(true);
        mFeedbackThread = thread;
        mState.log("airplay", "feedback loop started (" + (FEEDBACK_INTERVAL_MS / 1000L) + "s)");
        thread.start();
    }

    private void stopFeedbackLoop() {
        mFeedbackRunning = false;
        final Thread thread = mFeedbackThread;
        mFeedbackThread = null;
        if (thread != null) {
            thread.interrupt();
        }
    }

    private void sendFeedback() throws IOException {
        final InputStream in = mControlIn;
        final OutputStream out = mControlOut;
        final AirPlayPairing pairing = mPairing;
        if (in == null || out == null || pairing == null) {
            return;
        }
        final Map<String, Object> stream = new LinkedHashMap<String, Object>();
        stream.put("type", Long.valueOf(110));
        if (mVideoSender != null && mVideoDataPort > 0) {
            stream.put("streamConnectionID", Long.valueOf(mStreamConnectionId));
            stream.put("timestampRawNs", Long.valueOf(System.nanoTime()));
            stream.put("timestamp", Long.valueOf(0));
            stream.put("sampleRate", Long.valueOf(0));
            stream.put("sampleTime", Long.valueOf(0));
        }
        final Map<String, Object> params = new LinkedHashMap<String, Object>();
        params.put("streams", java.util.Collections.singletonList(stream));
        final Response reply = sealedExchange(in, out, pairing, "POST", "/feedback",
                "application/x-apple-binary-plist", null, BinaryPlist.encode(params));
        if (reply.status != 200) {
            mState.log("airplay", "feedback -> " + reply.describe());
        }
    }

    /**
     * The accessory pushes its commands (requestUI/forceKeyFrame/setNightMode/
     * iAPSendMessage) over the event channel and expects us to keep it open; without
     * it the wired receiver even stalled RECORD by ~10 s before answering 500.
     */
    private void openEventChannel(String host, int port, AirPlayPairing pairing) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final Socket socket = connect(host, port);
                    if (socket == null) {
                        mState.log("airplay", "event channel: no connection to " + port);
                        return;
                    }
                    mState.log("airplay", "event channel connected to port " + port);
                    /* Measured 2026-10-02 with a probe build of the accessory: its
                     * `runEventRead` treats EOF as fatal (`AirPlaySession.kt:740` ->
                     * `close()` -> the whole wireless session is rebuilt).  The accessory only
                     * writes here occasionally (touch, forceKeyFrame, iAPSendMessage), so a
                     * quiet stretch is normal — but this loop used to inherit the 15 s control
                     * timeout, and one quiet stretch killed the thread, the socket was
                     * collected, the resulting FIN looked like a clean shutdown to the
                     * accessory and the session died with it (cycles of 50-190 s, i.e. the
                     * time until the next >15 s gap).  Block indefinitely instead and only
                     * close when the session really ends. */
                    socket.setSoTimeout(0);
                    mEventSocket = socket;
                    final java.io.InputStream in = socket.getInputStream();
                    final java.io.OutputStream out = socket.getOutputStream();
                    /* Lives as long as the session: stop() closes mEventSocket, which is what
                     * ends this loop.  Do NOT key it on mRunning — that flag is cleared by the
                     * handshake's finally, and keying on it closed the channel ~2.5 s into every
                     * session, which the accessory reads as EOF and answers with session close. */
                    while (!Thread.currentThread().isInterrupted()) {
                        final byte[] record;
                        try {
                            record = SealedControlFraming.readRecord(in);
                        } catch (java.net.SocketTimeoutException timeout) {
                            continue;
                        }
                        if (record == null) {
                            mState.log("airplay", "event channel reached EOF — the accessory"
                                    + " closed it (its session is ending)");
                            break;
                        }
                        final byte[] plain = pairing.unsealEvent(record);
                        mState.log("airplay", "event <- " + plain.length + "B "
                                + new String(plain, 0, Math.min(plain.length, 48),
                                        StandardCharsets.US_ASCII).replace('\r', ' ')
                                        .replace('\n', ' '));
                        logIapSendMessage(plain);
                        /* RTSP is request/response: the accessory's POST /command wants a
                         * 200 back, sealed with the event-write key.  Earlier we answered
                         * nothing at all, which left the accessory's event requests (touch,
                         * forceKeyFrame, ...) unanswered. */
                        answerEventRequest(plain, out, pairing);
                    }
                    mState.log("airplay", "event channel loop finished (interrupted="
                            + Thread.currentThread().isInterrupted()
                            + ") — closing the socket ourselves");
                } catch (Throwable t) {
                    if (mRunning) {
                        mState.log("airplay", "event channel failed: " + t);
                    }
                }
            }
        }, "okcar-airplay-event").start();
    }

    /**
     * The accessory sends *its* iAP2 messages back as event-channel
     * {@code {"type":"iAPSendMessage","params":{"data":[...]}}} plists, not on the type-130
     * socket (the pairing-crypto notes §7).  Log the message id and byte count so an
     * on-device run shows whether the hand-off actually made iAP2 flow; the plist may carry
     * the bytes either as a data blob or as an array of numbers, so both are handled.
     */
    /**
     * The fields of an accessory event, minus bulk payloads, for the log.  Needed because the
     * event channel is sealed: without this the only way to learn what geometry a receiver that
     * refuses `/info` (Fruit Link answers 401 then 455) actually announces is a guess.
     */
    private static String describeEventFields(Map<?, ?> fields) {
        if (fields == null) {
            return "null";
        }
        final StringBuilder out = new StringBuilder("{");
        for (Map.Entry<?, ?> entry : fields.entrySet()) {
            final Object value = entry.getValue();
            if (value instanceof byte[]) {
                out.append(entry.getKey()).append("=<").append(((byte[]) value).length)
                        .append("B>,");
            } else {
                out.append(entry.getKey()).append('=').append(value).append(',');
            }
        }
        out.append('}');
        return out.length() > 600 ? out.substring(0, 600) + "…" : out.toString();
    }

    /**
     * The accessory's non-iAP2 event commands.  These exist to keep its video decoder usable, and
     * were logged-only until now — that is exactly the case where the car shows a black screen
     * instead of the phone's UI.
     */
    private void handleEventCommand(String type, Map<?, ?> fields) {
        final WirelessVideoSender sender = mVideoSender;
        mState.log("airplay", "event command: type=" + type
                + (sender == null ? " (no video stream yet)" : "")
                + " fields=" + describeEventFields(fields));
        if (sender == null) {
            return;
        }
        if ("forceKeyFrame".equals(type)) {
            sender.requestSyncFrame();
        } else if ("requestUI".equals(type)) {
            sender.requestUi();
        }
    }

    /**
     * Replies `200 OK` to one sealed event-channel RTSP request, echoing its CSeq.  The
     * reply goes back through the same sealed framing we read with (event-write key).
     */
    private void answerEventRequest(byte[] plain, java.io.OutputStream out,
                                    AirPlayPairing pairing) {
        try {
            final String head = new String(plain, 0, Math.min(plain.length, 512),
                    StandardCharsets.US_ASCII);
            if (!(head.startsWith("POST ") || head.startsWith("GET ")
                    || head.startsWith("SETUP ") || head.startsWith("OPTIONS "))) {
                return; /* not a request (the accessory may also send a reply here) */
            }
            String cseq = "0";
            final String[] lines = head.split("\r\n");
            for (String line : lines) {
                if (line.regionMatches(true, 0, "CSeq:", 0, 5)) {
                    cseq = line.substring(5).trim();
                    break;
                }
            }
            final byte[] reply = ("RTSP/1.0 200 OK\r\nCSeq: " + cseq
                    + "\r\nContent-Length: 0\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
            final byte[] sealed = pairing.sealEvent(reply);
            synchronized (mEventWriteLock) {
                out.write(sealed);
                out.flush();
            }
            mState.log("airplay", "event -> 200 OK cseq=" + cseq);
        } catch (Throwable t) {
            mState.log("airplay", "event reply failed: " + t);
        }
    }

    private void logIapSendMessage(byte[] plain) {
        try {
            final int bodyStart = SealedControlFraming.indexOf(plain, "\r\n\r\n");
            if (bodyStart < 0) {
                return;
            }
            final byte[] body = java.util.Arrays.copyOfRange(plain, bodyStart + 4, plain.length);
            final Object decoded = BinaryPlist.decode(body);
            if (!(decoded instanceof Map)) {
                return;
            }
            final Map<?, ?> map = (Map<?, ?>) decoded;
            if ("hidReport".equals(map.get("type")) || "hidSendReport".equals(map.get("type"))) {
                /* AirPlay-native touch: the accessory posts the 5-byte HID report straight
                 * on the event channel.  Same decoder + injector as the iAP2 path. */
                injectHidReport(hidReportBytes(map), "event channel");
                return;
            }
            if (!"iAPSendMessage".equals(map.get("type"))) {
                handleEventCommand(String.valueOf(map.get("type")), map);
                return;
            }
            final Object params = map.get("params");
            final Object data = params instanceof Map ? ((Map<?, ?>) params).get("data") : null;
            if (data instanceof byte[]) {
                final byte[] bytes = (byte[]) data;
                final int messageId = bytes.length >= 6
                        ? (((bytes[4] & 0xFF) << 8) | (bytes[5] & 0xFF)) : -1;
                mState.log("airplay", "event iAPSendMessage: " + bytes.length + "B"
                        + (messageId >= 0 ? " " + Iap2Message.name(messageId) : "")
                        + (mAccessoryIapSink == null ? " (no sink installed — dropped)" : ""));
                final AccessoryIapSink sink = mAccessoryIapSink;
                if (sink != null) {
                    sink.onAccessoryIapMessage(bytes);
                }
            } else if (data instanceof List) {
                mState.log("airplay", "event iAPSendMessage: " + ((List<?>) data).size()
                        + " one-byte data elements");
            } else {
                mState.log("airplay", "event iAPSendMessage (no decodable byte payload)");
            }
        } catch (Throwable t) {
            mState.log("airplay", "event iAPSendMessage could not be decoded: " + t);
        }
    }

    /* ----------------------------------------------------------------- sockets */

    private Socket connect(String accessoryAddress, int port) {
        final List<String> candidates = candidates(accessoryAddress, mContext, mCarrierInterface);
        mState.log("airplay", "trying " + candidates + ":" + port);
        /* The P2P group and its addresses settle a few seconds after the join, so
         * keep sweeping the candidate list instead of giving up after one pass
         * (measured: the successful /info in the 2026-09-27 01:50 run happened ~80 s
         * after 0x4301 — much of that was waiting for the interface to come up). */
        final long deadline = System.currentTimeMillis() + CONNECT_WINDOW_MS;
        int attempt = 0;
        while (System.currentTimeMillis() < deadline) {
            for (String host : candidates) {
                attempt++;
                Socket socket = new Socket();
                try {
                    /* A link-local target only works when the SCOPE reaches the socket.  Passing
                     * "fe80::2%usb0" as a *host string* is not enough on this ROM: the wired dial
                     * failed with `ConnectException ... from /:: (port 0)` for 45 s while a plain
                     * `nc fe80::2%usb0 7000` from the root shell connected instantly (measured
                     * 2026-10-06 22:55).  Resolve the scope explicitly (Inet6Address with the
                     * NetworkInterface) and BIND the socket to that interface's own link-local
                     * address, which also pins the route without relying on the policy rule. */
                    final java.net.InetSocketAddress target = resolveScoped(host, port);
                    if (target.getAddress() instanceof java.net.Inet6Address
                            && target.getAddress().isLinkLocalAddress()
                            && ((java.net.Inet6Address) target.getAddress()).getScopeId() == 0) {
                        mState.log("airplay", "  " + host
                                + " -> no scope id resolved (interface missing?); the kernel will"
                                + " reject this connect with EINVAL");
                    }
                    boolean boundLocal = false;
                    final java.net.InetAddress local = carrierLocalAddress(target);
                    if (local != null) {
                        try {
                            socket.bind(new java.net.InetSocketAddress(local, 0));
                            boundLocal = true;
                            mState.log("airplay", "  " + host + " bound local "
                                    + local.getHostAddress());
                        } catch (java.net.BindException e) {
                            /* The carrier interface's own link-local address flaps on every gadget
                             * re-enumeration, and we can read the outgoing one right before it is
                             * gone: binding a stale address fails with EADDRNOTAVAIL and used to drop
                             * the whole wired candidate (measured 2026-10-10: "fe80::2%usb0 ->
                             * BindException: bind failed: EADDRNOTAVAIL" while `ip -6 route get
                             * fe80::2` already answered `dev usb0` and rule 110 was in place).  The
                             * scope on the *target* is what pins the interface, so keep the candidate
                             * and connect with the scope only instead of losing the wired carrier over
                             * a local-bind optimisation. */
                            mState.log("airplay", "  " + host + " local bind "
                                    + local.getHostAddress() + " failed (" + e.getMessage()
                                    + ") - connecting with the scope only");
                        }
                    }
                    long connectStart = System.nanoTime();
                    try {
                        socket.connect(target, CONNECT_TIMEOUT_MS);
                    } catch (java.io.IOException first) {
                        /* A connect that dies INSTANTLY with a routing errno was refused
                         * before a single SYN left: the oif-pinned lookup found no usable
                         * route at that moment.  The wired host route lives in `main` and
                         * the gadget's re-enumerations flush it (the keeper re-asserts it
                         * every 2 s), so the dial can land exactly in a flushed window --
                         * measured 2026-11-05 00:58: the keeper verified `route get` ->
                         * usb0 at .097 and the same command answered `dev wlan0` at .179,
                         * and the scoped connect failed with ENETUNREACH in ~40 ms.
                         * Re-assert the carrier route in place, wait out the flap, and
                         * retry on a fresh socket before the candidate is given up. */
                        java.io.IOException failure = first;
                        boolean reconnected = false;
                        for (int round = 0; round < 3; round++) {
                            if (!instantUnreach(failure, System.nanoTime() - connectStart)) {
                                break;
                            }
                            reassertCarrierRoute(host, mCarrierInterface);
                            try {
                                Thread.sleep(400L);
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                                break;
                            }
                            try {
                                socket.close();
                            } catch (java.io.IOException ignored) {
                                /* replaced below */
                            }
                            socket = new Socket();
                            final java.net.InetAddress rebound = carrierLocalAddress(target);
                            if (rebound != null) {
                                try {
                                    socket.bind(new java.net.InetSocketAddress(rebound, 0));
                                } catch (java.net.BindException be) {
                                    /* the scope on the target pins the interface */
                                }
                            }
                            connectStart = System.nanoTime();
                            try {
                                socket.connect(target, CONNECT_TIMEOUT_MS);
                                reconnected = true;
                                break;
                            } catch (java.io.IOException again) {
                                failure = again;
                            }
                        }
                        if (!reconnected) {
                            throw failure;
                        }
                        mState.log("airplay", "  " + host + " recovered after in-dial"
                                + " route re-assert (" + failure.getMessage() + ")");
                    }
                    socket.setSoTimeout(READ_TIMEOUT_MS);
                    mState.log("airplay", "connected via candidate " + host
                            + " (attempt " + attempt + ")");
                    return socket;
                } catch (Throwable t) {
                    try {
                        socket.close();
                    } catch (IOException ignored) {
                        /* next candidate */
                    }
                    mState.log("airplay", "  " + host + " -> " + t);
                }
            }
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        mState.log("airplay", "no candidate accepted a connection within "
                + (CONNECT_WINDOW_MS / 1000) + "s");
        return null;
    }

    /* A connect that dies within a second carrying a routing errno was refused
     * locally before any packet could be sent -- that is the local-bind/policy-
     * routing interaction on raw gadget interfaces, not a network failure
     * (2026-11-04: ENETUNREACH in ~2 ms while the same target+scope connected
     * instantly from an unbound nc).  Timeouts and refused dials must not match. */
    private static boolean instantUnreach(java.io.IOException e, long elapsedNanos) {
        if (elapsedNanos > 1_000_000_000L) {
            return false;
        }
        final String m = String.valueOf(e.getMessage());
        return m.contains("ENETUNREACH") || m.contains("EINVAL")
                || m.contains("EHOSTUNREACH") || m.contains("ENETDOWN");
    }

    /**
     * Re-asserts the wired carrier's host route and its policy rule in the main table.
     *
     * <p>The same two `ip` calls the carkit route keeper runs, issued from inside the dial:
     * when the scoped connect dies instantly with a routing errno the route is usually gone at
     * that very moment (gadget re-enumeration flushes interface routes), and waiting for the
     * keeper's next 2 s tick wastes the candidate.  Best effort -- a failure here just leaves
     * the retry to the next pass over the candidate list.
     */
    private static void reassertCarrierRoute(String host, String carrierInterface) {
        if (carrierInterface == null || carrierInterface.isEmpty() || host.indexOf(':') < 0) {
            return;
        }
        final String bare = host.split("%")[0];
        try {
            Runtime.getRuntime().exec(new String[]{"/system/bin/ip", "-6", "route",
                    "replace", bare + "/128", "dev", carrierInterface, "table", "main"});
        } catch (Throwable ignored) {
            /* best effort */
        }
        try {
            Runtime.getRuntime().exec(new String[]{"/system/bin/ip", "-6", "rule",
                    "add", "to", bare + "/128", "lookup", "main", "pref", "110"});
        } catch (Throwable ignored) {
            /* best effort */
        }
    }

    /**
     * Candidates with the network's <em>real</em> default-route gateway first.
     *
     * <p>The accessory is the AP, so its address <em>is</em> that gateway — but the gateway
     * is not always {@code x.x.x.1}.  Measured on the production head unit: the phone sits at
     * 192.0.2.229 and the gateway is 192.0.2.149.  The AirPlay path only ever tried
     * the {@code .1} guesses below, so it went to 192.0.2.1 and got
     * {@code EHOSTUNREACH} every time, and the receiver on .149 was never contacted (§3.20).
     */
    /**
     * Interface index for a name, read straight from sysfs.
     *
     * <p>This deliberately does NOT use {@link java.net.NetworkInterface#getByName}: inside the module's
     * app_process that call returned null for a perfectly existing {@code usb0} (measured 2026-10-06
     * 23:10 -- the wired dial then fell back to an unscoped address and failed with the same
     * {@code ConnectException} as before the fix, while `nc fe80::2%usb0 7000` from the root shell
     * connected instantly).  A plain file read cannot be denied to us.
     *
     * @return the index, or 0 when the interface does not exist.
     */
    static int ifIndex(String ifname) {
        if (ifname == null || ifname.isEmpty()) {
            return 0;
        }
        /* /proc/net/if_inet6 first: it carries the index in field 2 for every interface that has an
         * IPv6 address, and on this ROM /sys/class/net/<if>/ifindex does NOT exist at all (measured
         * 2026-10-06 23:17 from a root shell: "No such file or directory") -- which is exactly why the
         * first version of this fix still resolved no scope. */
        try {
            for (String line : java.nio.file.Files.readAllLines(
                    java.nio.file.Paths.get("/proc/net/if_inet6"))) {
                final String[] f = line.trim().split("\\s+");
                if (f.length >= 6 && ifname.equals(f[5])) {
                    /* HEX, not decimal: the kernel prints every numeric field of this file with
                     * %02x ("fe80… 21 40 20 80 usb0" for usb0).  Reading field 2 as decimal turned
                     * usb0's ifindex 33 (0x21) into 21, which belongs to rmnet_data4 -- the wired
                     * dial then scoped fe80::2 to rmnet_data4 and died with ENETUNREACH while the
                     * log claimed the usb0 candidate was being tried (measured 2026-10-10). */
                    return Integer.parseInt(f[1], 16);
                }
            }
        } catch (Throwable ignored) {
            /* try sysfs below */
        }
        try {
            final byte[] raw = java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get("/sys/class/net/" + ifname + "/ifindex"));
            return Integer.parseInt(new String(raw, "UTF-8").trim());
        } catch (Throwable ignored) {
            /* this ROM has no ifindex there at all */
        }
        /* Last resort, and the only one that also works while the NCM link has no IPv6 address yet
         * (the address flaps when the gadget is re-enumerated): ask iproute2.  `ip -o link show usb0`
         * prints "27: usb0: <...>". */
        try {
            final Process pr = Runtime.getRuntime().exec(
                    new String[] {"/system/bin/ip", "-o", "link", "show", ifname});
            final java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(pr.getInputStream(), "UTF-8"));
            final String line = r.readLine();
            pr.waitFor();
            if (line != null) {
                final int colon = line.indexOf(':');
                if (colon > 0) {
                    return Integer.parseInt(line.substring(0, colon).trim());
                }
            }
        } catch (Throwable ignored) {
            /* give up */
        }
        return 0;
    }

    /**
     * Our own link-local address on that interface, parsed from {@code /proc/net/if_inet6}
     * ({@code <32 hex digits> <ifindex> <prefixlen> <scope> <flags> <ifname>}).
     *
     * @return the scoped address, or null when the interface has no link-local address.
     */
    static java.net.Inet6Address ourLinkLocal(String ifname, int index) {
        if (ifname == null || ifname.isEmpty()) {
            return null;
        }
        try {
            for (String line : java.nio.file.Files.readAllLines(
                    java.nio.file.Paths.get("/proc/net/if_inet6"))) {
                final String[] f = line.trim().split("\\s+");
                if (f.length < 6 || !ifname.equals(f[5])) {
                    continue;
                }
                final byte[] bytes = new byte[16];
                for (int i = 0; i < 16; i++) {
                    bytes[i] = (byte) Integer.parseInt(f[0].substring(i * 2, i * 2 + 2), 16);
                }
                /* fe80::/10, and skip the all-zero address. */
                if ((bytes[0] & 0xff) != 0xfe || (bytes[1] & 0xc0) != 0x80) {
                    continue;
                }
                return java.net.Inet6Address.getByAddress(null, bytes, index);
            }
        } catch (Throwable ignored) {
            /* fall through */
        }
        return null;
    }

    /** Resolves a candidate that may carry a {@code %iface} scope id into a scoped address. */
    static java.net.InetSocketAddress resolveScoped(String host, int port)
            throws java.io.IOException {
        final int pct = host == null ? -1 : host.indexOf('%');
        if (pct < 0) {
            return new java.net.InetSocketAddress(host, port);
        }
        final String bare = host.substring(0, pct);
        final String ifname = host.substring(pct + 1);
        final java.net.InetAddress addr = java.net.InetAddress.getByName(bare);
        if (addr instanceof java.net.Inet6Address && !ifname.isEmpty()) {
            final int index = ifIndex(ifname);
            if (index > 0) {
                final java.net.Inet6Address scoped =
                        java.net.Inet6Address.getByAddress(null, addr.getAddress(), index);
                if (scoped != null) {
                    return new java.net.InetSocketAddress(scoped, port);
                }
            }
        }
        return new java.net.InetSocketAddress(addr, port);
    }

    /** @return that interface's own link-local address, or null when the target is not scoped. */
    static java.net.InetAddress carrierLocalAddress(java.net.InetSocketAddress target) {
        final java.net.InetAddress remote = target.getAddress();
        if (!(remote instanceof java.net.Inet6Address) || !remote.isLinkLocalAddress()) {
            return null;
        }
        final int index = ((java.net.Inet6Address) remote).getScopeId();
        if (index <= 0) {
            return null;
        }
        try {
            for (String line : java.nio.file.Files.readAllLines(
                    java.nio.file.Paths.get("/proc/net/if_inet6"))) {
                final String[] f = line.trim().split("\\s+");
                /* Field 2 is hex here too (see ifIndex): comparing it as decimal matched the wrong
                 * interface's address, so the local bind used an address that is not on the carrier. */
                if (f.length < 6 || Integer.parseInt(f[1], 16) != index) {
                    continue;
                }
                final byte[] bytes = new byte[16];
                for (int i = 0; i < 16; i++) {
                    bytes[i] = (byte) Integer.parseInt(f[0].substring(i * 2, i * 2 + 2), 16);
                }
                if ((bytes[0] & 0xff) == 0xfe && (bytes[1] & 0xc0) == 0x80) {
                    return java.net.Inet6Address.getByAddress(null, bytes, index);
                }
            }
        } catch (Throwable ignored) {
            /* fall through to iproute2 */
        }
        /* The same flapping problem: /proc/net/if_inet6 only lists an interface while its IPv6
         * address is registered, so read the link-local straight from `ip -6 addr show dev usb0`. */
        try {
            final Process pr = Runtime.getRuntime().exec(
                    new String[] {"/system/bin/ip", "-6", "addr", "show", "dev", ifnameOf(index)});
            final java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(pr.getInputStream(), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) {
                final String t = line.trim();
                final int i = t.indexOf("inet6 ");
                if (i < 0) {
                    continue;
                }
                final String addr = t.substring(i + 6).split("/")[0].trim();
                final java.net.InetAddress parsed = java.net.InetAddress.getByName(addr);
                if (parsed instanceof java.net.Inet6Address && parsed.isLinkLocalAddress()) {
                    final java.net.Inet6Address scoped = java.net.Inet6Address.getByAddress(
                            null, parsed.getAddress(), index);
                    if (scoped != null) {
                        pr.waitFor();
                        return scoped;
                    }
                }
            }
            pr.waitFor();
        } catch (Throwable ignored) {
            /* connect without a local bind */
        }
        return null;
    }

    /** Reverse lookup of an interface name from its index (for the iproute2 fallback above). */
    static String ifnameOf(int index) {
        try {
            for (String line : java.nio.file.Files.readAllLines(
                    java.nio.file.Paths.get("/proc/net/if_inet6"))) {
                final String[] f = line.trim().split("\\s+");
                /* Hex field 2, same as ifIndex(): a decimal compare here answered rmnet_data4 for
                 * usb0's index, so the fallback read the wrong interface's addresses. */
                if (f.length >= 6 && Integer.parseInt(f[1], 16) == index) {
                    return f[5];
                }
            }
        } catch (Throwable ignored) {
            /* fall through */
        }
        try {
            final java.io.File[] n = new java.io.File("/sys/class/net").listFiles();
            if (n != null) {
                for (java.io.File d : n) {
                    final byte[] raw = java.nio.file.Files.readAllBytes(
                            java.nio.file.Paths.get(d.getAbsolutePath() + "/ifindex"));
                    if (Integer.parseInt(new String(raw, "UTF-8").trim()) == index) {
                        return d.getName();
                    }
                }
            }
        } catch (Throwable ignored) {
            /* give up */
        }
        return "usb0";
    }

    static List<String> candidates(String accessoryAddress, Context context, String carrierInterface) {
        final String bare = accessoryAddress == null ? "" : accessoryAddress.split("%")[0];
        final List<String> ordered = new ArrayList<String>();
        if (!bare.isEmpty()) {
            if (isLinkLocalV6(bare)) {
                /* The wired carrier (the NCM function's usb0) is the one interface that can reach the
                 * address the accessory named in 0x4301; without its scope the kernel answers
                 * `connect failed: EINVAL` for the bare form and `ENETUNREACH` for every Wi-Fi guess.
                 * It goes first because it is the only non-guess. */
                if (carrierInterface != null && !carrierInterface.isEmpty()) {
                    ordered.add(bare + "%" + carrierInterface);
                }
                /* A bare link-local address can never connect: the kernel needs the scope id,
                 * and on the port target the measured result was
                 * `connect failed: EINVAL (Invalid argument)` after burning the whole 4 s
                 * connect timeout (2026-10-01 14:58:57).  The scoped forms carry it instead —
                 * offering the bare form only delayed the candidates that can work. */
                ordered.add(bare + "%p2p0");
                ordered.add(bare + "%wlan0");
            } else {
                ordered.add(bare);      /* an explicit address from 0x4301 wins */
            }
        }
        /* The accessory's own network outranks every other network of the phone.  With a Wi-Fi
         * Direct group, wlan0 still holds the home LAN's default route, so `defaultGateway()`
         * answers with the home router — and the connect loop then "succeeded" against it and
         * spent the handshake there, leaving the group owner and the scoped address untried:
         *
         *   airplay: trying [fe80::…, 192.0.2.4, fe80::…%p2p0, …, 192.0.2.1]:7000
         *   airplay: connected via candidate 192.0.2.4 (attempt 2)
         *   airplay: /info not usable (connection closed while reading the response)
         *
         * The group owner is derived from the live p2p interface rather than guessed, so this
         * stays silent when there is no group (a plain accessory hotspot, where the DHCP
         * gateway really is the accessory and must keep its place). */
        for (String member : groupOwnerCandidates()) {
            if (!ordered.contains(member)) {
                ordered.add(member);
            }
        }
        /* The head unit is the AP, so its address is this network's DHCP server/gateway; but when the head-unit
         * hotspot provides no default route, `defaultGateway()` cannot find it (measured on lmi: on the head-unit
         * hotspot `ip route` only had a subnet route), leaving only `x.x.x.1` as a guess and AirPlay was never
         * tried (measured: the head unit was at .40 and .1 was unreachable).  The DHCP address is authoritative. */
        final String dhcp = p2pInterfacePresent() ? null : dhcpServerAddress(context);
        if (dhcp != null && !dhcp.isEmpty() && !ordered.contains(dhcp)) {
            ordered.add(dhcp);
        }
        final String gateway = p2pInterfacePresent() ? null : defaultGateway(context);
        if (gateway != null && !gateway.isEmpty() && !ordered.contains(gateway)) {
            ordered.add(gateway);
        }
        for (String candidate : candidates(accessoryAddress)) {
            if (!ordered.contains(candidate)) {
                ordered.add(candidate);
            }
        }
        return ordered;
    }

    /** True for an IPv6 link-local address, which is only usable together with an interface scope. */
    static boolean isLinkLocalV6(String host) {
        return host != null && host.regionMatches(true, 0, "fe80:", 0, 5);
    }

    /**
     * True when the phone has a Wi-Fi Direct interface at all.
     *
     * <p>While one exists, the phone is in — or entering — a group, and the Wi-Fi transport's
     * default route belongs to a <em>different</em> network: the home LAN.  Leaving that gateway
     * in the candidate list looks harmless because it is only a fallback, but it is not, and
     * this is the third time the same mistake has cost a session: the home router accepts the
     * TCP connection on the port and then closes it, so the connect loop counts it as a success
     * and the attempt ends there.
     *
     * <pre>
     *   airplay: connected via candidate 192.0.2.4 (attempt 2)
     *   airplay: /info not usable (connection closed while reading the response)
     * </pre>
     *
     * <p>The gateway keeps its place for a plain accessory hotspot, where there is no p2p
     * interface and the gateway really is the accessory.
     */
    static boolean p2pInterfacePresent() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                final String name = ni.getName();
                if (name != null && name.startsWith("p2p")) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            /* Unenumerable interfaces: behave as before rather than dropping the gateway blindly. */
        }
        return false;
    }

    /**
     * The group owner of every live {@code p2p*} interface, derived from that interface's own
     * address.  Empty when the phone is not in a Wi-Fi Direct group — the caller relies on that
     * to leave a plain accessory hotspot's gateway ordering alone.
     */
    static List<String> groupOwnerCandidates() {
        final List<String> out = new ArrayList<String>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                final String name = ni.getName();
                if (name == null || !name.startsWith("p2p") || !ni.isUp()) {
                    continue;
                }
                for (InetAddress addr : Collections.list(ni.getInetAddresses())) {
                    if (!(addr instanceof Inet4Address) || addr.isLoopbackAddress()) {
                        continue;
                    }
                    final String host = addr.getHostAddress();
                    final int dot = host == null ? -1 : host.lastIndexOf('.');
                    if (dot > 0) {
                        final String owner = host.substring(0, dot) + ".1";
                        if (!out.contains(owner)) {
                            out.add(owner);
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
            /* No enumerable interfaces: the tail of candidates() still guesses the group address. */
        }
        return out;
    }

    /**
     * The default-route gateway of the network we are on, Wi-Fi preferred so a still-present
     * LAN route cannot win the race.  Same rules as
     * {@code WirelessCarPlaySession.readDefaultGateway}; no logging here because this runs
     * on the AirPlay connect path, which logs the whole candidate list anyway.
     */
    /**
     * Gateway/server address handed out by this network's DHCP.
     *
     * <p>For a head-unit hotspot the DHCP server <em>is</em> the head unit itself, and it is not necessarily
     * {@code x.x.x.1}: measured, the phone was at 192.0.2.226 and the head unit at 192.0.2.40.
     * {@link #defaultGateway} relies on the default route, which a head-unit hotspot without internet access
     * does not provide (measured: `ip route` only had a subnet route), leaving only the {@code .1} guess and
     * AirPlay was never tried.  The DHCP result is used here as the authoritative source, not a guess.
     */
    static String dhcpServerAddress(Context context) {
        if (context == null) {
            return null;
        }
        try {
            final android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager)
                    context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                final android.net.DhcpInfo info = wm.getDhcpInfo();
                if (info != null) {
                    if (info.gateway != 0) {
                        return intToIpv4(info.gateway);
                    }
                    if (info.serverAddress != 0) {
                        return intToIpv4(info.serverAddress);
                    }
                }
            }
        } catch (Throwable ignored) {
            /* With no Wi-Fi service, fall through to the LinkProperties path below */
        }
        try {
            final android.net.ConnectivityManager cm = (android.net.ConnectivityManager)
                    context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                for (android.net.Network network : cm.getAllNetworks()) {
                    final android.net.NetworkCapabilities capabilities =
                            cm.getNetworkCapabilities(network);
                    if (capabilities == null
                            || !capabilities.hasTransport(
                                    android.net.NetworkCapabilities.TRANSPORT_WIFI)) {
                        continue;
                    }
                    final android.net.LinkProperties properties = cm.getLinkProperties(network);
                    final InetAddress server = properties == null
                            ? null : properties.getDhcpServerAddress();
                    if (server instanceof Inet4Address) {
                        return server.getHostAddress();
                    }
                }
            }
        } catch (Throwable ignored) {
            /* This method does not exist below API 30; the DHCP path above already covers that */
        }
        return null;
    }

    /** The integer form of {@code android.net.DhcpInfo} is little-endian IPv4. */
    static String intToIpv4(int value) {
        return (value & 0xFF) + "." + ((value >> 8) & 0xFF) + "."
                + ((value >> 16) & 0xFF) + "." + ((value >> 24) & 0xFF);
    }

    static String defaultGateway(Context context) {
        if (context == null) {
            return null;
        }
        try {
            final android.net.ConnectivityManager cm = (android.net.ConnectivityManager)
                    context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) {
                return null;
            }
            String other = null;
            for (android.net.Network network : cm.getAllNetworks()) {
                final android.net.LinkProperties properties = cm.getLinkProperties(network);
                if (properties == null) {
                    continue;
                }
                final android.net.NetworkCapabilities capabilities =
                        cm.getNetworkCapabilities(network);
                final boolean wifi = capabilities != null
                        && capabilities.hasTransport(
                                android.net.NetworkCapabilities.TRANSPORT_WIFI);
                for (android.net.RouteInfo route : properties.getRoutes()) {
                    if (!route.isDefaultRoute()) {
                        continue;
                    }
                    final InetAddress gateway = route.getGateway();
                    if (gateway == null || gateway.isAnyLocalAddress()
                            || !(gateway instanceof Inet4Address)) {
                        continue;
                    }
                    final String text = gateway.getHostAddress();
                    if (wifi) {
                        return text;
                    }
                    if (other == null) {
                        other = text;
                    }
                }
            }
            return other;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Address candidates in the order they can plausibly work: the accessory's own
     * address (scoped for link-local IPv6), the same address through the Wi-Fi
     * Direct interface, and the group owner address of every IPv4 we currently own.
     */
    static List<String> candidates(String accessoryAddress) {
        return candidates(accessoryAddress, null);
    }

    /**
     * Same list, with the wired carrier's scope in front when there is one.  The video dataPort is a
     * separate TCP connection to the same accessory, and on a wired session only usb0 can reach it --
     * the Wi-Fi scopes below failed with ENETUNREACH/EINVAL while the control connection (which does
     * know the carrier) was already up.
     */
    static List<String> candidates(String accessoryAddress, String carrierInterface) {
        final Set<String> out = new LinkedHashSet<String>();
        if (accessoryAddress != null && !accessoryAddress.isEmpty()) {
            final String bare = accessoryAddress.split("%")[0];
            if (bare.contains(":")) {
                if (carrierInterface != null && !carrierInterface.isEmpty()) {
                    out.add(bare + "%" + carrierInterface);
                }
                out.add(bare + "%p2p0");
                out.add(bare + "%wlan0");
            }
            out.add(bare);
        }
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                for (java.net.InetAddress addr : Collections.list(ni.getInetAddresses())) {
                    final String host = addr.getHostAddress();
                    if (host != null && host.contains(".") && !addr.isLoopbackAddress()) {
                        final int dot = host.lastIndexOf('.');
                        out.add(host.substring(0, dot) + ".1");
                    }
                }
            }
        } catch (Throwable ignored) {
            /* fall through to the hard-coded candidate */
        }
        /* Android's standard P2P group-owner address: when the phone joins an accessory-hosted
         * group but the GO address is not on any interface yet, this is where it will be. */
        out.add("192.168.49.1");
        return new ArrayList<String>(out);
    }

    /* -------------------------------------------------------------- RTSP/HTTP */

    private Response exchange(InputStream in, OutputStream out, String method, String uri,
                              String contentType, String extraHeaders, byte[] body)
            throws IOException {
        final byte[] payload = body == null ? new byte[0] : body;
        final StringBuilder head = new StringBuilder();
        head.append(method).append(' ').append(uri).append(" RTSP/1.0\r\n");
        head.append("CSeq: ").append(mCseq.getAndIncrement()).append("\r\n");
        head.append("User-Agent: ").append(USER_AGENT).append("\r\n");
        if (contentType != null) {
            head.append("Content-Type: ").append(contentType).append("\r\n");
        }
        if (extraHeaders != null && !extraHeaders.isEmpty()) {
            head.append(extraHeaders).append("\r\n");
        }
        head.append("Content-Length: ").append(payload.length).append("\r\n\r\n");
        out.write(head.toString().getBytes(StandardCharsets.US_ASCII));
        if (payload.length > 0) {
            out.write(payload);
        }
        out.flush();

        final Response response = new Response();
        final ByteArrayOutputStream headerBuf = new ByteArrayOutputStream();
        int state = 0; /* count of CRLF pairs seen */
        while (state < 2) {
            final int b = in.read();
            if (b < 0) {
                throw new IOException("connection closed while reading the response");
            }
            headerBuf.write(b);
            if (headerBuf.size() > MAX_HEADER_BYTES) {
                throw new IOException("response header too large");
            }
            if (b == '\r') {
                continue;
            }
            if (b == '\n') {
                state = (state == 1 || state == 3) ? state + 1 : 1;
            } else {
                state = 0;
            }
        }
        response.headers = new String(headerBuf.toByteArray(), StandardCharsets.US_ASCII);
        final String[] lines = response.headers.split("\r\n");
        if (lines.length > 0) {
            final String[] parts = lines[0].split(" ", 3);
            if (parts.length >= 2) {
                try {
                    response.status = Integer.parseInt(parts[1]);
                } catch (NumberFormatException ignored) {
                    response.status = -1;
                }
                response.reason = parts.length > 2 ? parts[2] : "";
            }
        }
        final String lengthHeader = response.header("Content-Length");
        int length = 0;
        if (lengthHeader != null) {
            try {
                length = Integer.parseInt(lengthHeader.trim());
            } catch (NumberFormatException ignored) {
                length = 0;
            }
        }
        if (length > 0) {
            final byte[] buf = new byte[length];
            int read = 0;
            while (read < length) {
                final int n = in.read(buf, read, length - read);
                if (n < 0) {
                    throw new IOException("connection closed after " + read + "/" + length + " body bytes");
                }
                read += n;
            }
            response.body = buf;
        }
        mState.log("airplay", "tx " + method + " " + uri + " (" + payload.length + "B) -> "
                + response.describe());
        return response;
    }

    /* --------------------------------------------------------------- identity */

    /** Persists the long-term Ed25519 identity (and the accessory's) in prefs. */
    static final class PrefsIdentityStore implements AirPlayPairing.IdentityStore {
        private final SharedPreferences mPrefs;

        PrefsIdentityStore(Context context) {
            mPrefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        }

        @Override
        public byte[] load() {
            final String stored = mPrefs.getString(KEY_IDENTITY, null);
            return stored == null ? null : hexToBytes(stored);
        }

        @Override
        public void save(byte[] blob) {
            mPrefs.edit().putString(KEY_IDENTITY, bytesToHex(blob)).apply();
        }
    }

    static String bytesToHex(byte[] data) {
        final StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    static byte[] hexToBytes(String hex) {
        final byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
        }
        return out;
    }
}
