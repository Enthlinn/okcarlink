/*
 * OkcarOS link stack — L4 session layer: the AirPlay control session.
 *
 * This is the orchestration the closed-source app logs as
 * "[HttpControlSession] info step done / record step done" (§12.17 C): walk the
 * RTSP control channel through info → auth → stream setup → record, then hand the
 * negotiated ports to the media sources.
 *
 * Every step is explicit and logged, because the *order* and the payload details
 * are the part we cannot fully pin down yet: the field gives us the ports
 * (dataPort 36113 video / 36486 audio) and the endpoint names (/info, /feedback,
 * /auth-setup, /pair-setup, /pair-verify, /ctrl-int/1/<cmd>), but not yet the
 * exact request sequence.  So the session is a step machine that can be driven
 * in any order, and it reports what the peer actually answered instead of
 * assuming.
 *
 * What is certain and therefore implemented properly:
 *   - the RTSP framing and CSeq handling (RtspMessage, verified against python)
 *   - the binary plist bodies (BinaryPlist, verified against python plistlib)
 *   - the `streams` dictionaries and their port keys (StreamSetup, §12.25)
 *   - the /auth-setup body layout: 0x01 || challenge (§12.19 C)
 */

package com.okcar.link.airplay;

import android.util.Log;

import com.okcar.link.LinkState;
import com.okcar.link.rtsp.RtspMessage;
import com.okcar.link.util.Hex;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class AirPlaySession {

    private static final String TAG = "OkCarLink/Session";

    /** Session states; the names are what the UI/logs show. */
    public enum State {
        IDLE, CONNECTED, INFO_DONE, AUTH_DONE, STREAMS_READY, RECORDING, STOPPED, FAILED
    }

    public interface Listener {
        void onStateChanged(State state, String detail);
    }

    private final SessionTransport mTransport;
    private final Listener mListener;
    private final LinkState mState = LinkState.get();

    private final List<StreamSetup.Stream> mStreams = new ArrayList<StreamSetup.Stream>();
    private Map<String, Object> mInfo = new LinkedHashMap<String, Object>();

    private volatile State mStateValue = State.IDLE;
    private int mCseq;                       /* measured sender starts at CSeq 0 */

    /* The receiver derives the stream's AES-128-CTR key/IV from this number, so it
     * must be stable for the whole session (AirPlayStreamCrypto). Kept positive:
     * the KDF input is its decimal form. */
    private long mVideoStreamConnectionId = -1;
    private boolean mEncryption;
    private int mEventPort = -1;
    private EventChannel mEventChannel;
    private EventChannel.Sink mEventSink;
    private EventChannel.CommandSink mEventCommandSink;

    public AirPlaySession(SessionTransport transport, Listener listener) {
        mTransport = transport;
        mListener = listener;
    }

    public State state() {
        return mStateValue;
    }

    public String transportDescription() {
        return mTransport == null ? "(none)" : mTransport.describe();
    }

    public List<StreamSetup.Stream> streams() {
        return mStreams;
    }

    public Map<String, Object> info() {
        return mInfo;
    }

    /* ------------------------------------------------------------ step 1: info */

    /**
     * Asks the peer who it is.  AirPlay answers `/info` with a binary plist
     * (features, model, deviceID, srcvers, pk, ...).  The closed-source app calls
     * this the "info step".
     */
    public boolean queryInfo() throws IOException {
        final RtspMessage request = RtspMessage.request(RtspMessage.METHOD_POST,
                AirPlay.PATH_INFO);
        request.setHeader("CSeq", String.valueOf(mCseq++));
        request.setHeader("User-Agent", "OkCarLink/0.1");
        request.setHeader("X-Apple-ProtocolVersion", AirPlay.PROTOCOL_VERSION);
        // AirPlay senders announce themselves to the receiver with a plist body;
        // reusing the verified codec keeps that on the same path as everything else.
        request.setPlistBody(buildDeviceInfo());
        final RtspMessage reply = mTransport.exchange(request);
        mState.log("session", "info: " + request.summary() + " -> " + reply.summary());
        final Object plist = reply.plistBody();
        if (plist instanceof Map) {
            mInfo = castMap(plist);
            mState.log("session", "info plist: " + summarise(mInfo));
        }
        setState(State.INFO_DONE, "info step done");
        return true;
    }

    /** Identification the sender would present; keys from §12.25's key list. */
    private Map<String, Object> buildDeviceInfo() {
        final Map<String, Object> info = new LinkedHashMap<String, Object>();
        info.put(AirPlay.KEY_DEVICE_ID, "AA:BB:CC:DD:EE:FF");
        info.put(AirPlay.KEY_MODEL, "OkCarLink");
        info.put(AirPlay.KEY_MANUFACTURER, "OkCarOS");
        info.put(AirPlay.KEY_SRCVERS, AirPlay.PROTOCOL_VERSION);
        info.put(AirPlay.KEY_FEATURES, AirPlay.FEATURES);
        return info;
    }

    /* ------------------------------------------------------------ step 2: auth */

    /**
     * `POST /auth-setup` with body {@code 0x01 || challenge}.
     *
     * §12.19 established that the sender side does not verify anything: the MFi
     * exchange is driven by the accessory and we only carry the bytes.  This
     * method therefore sends a fresh challenge (or the caller's) and reports the
     * answer without pretending to validate it.
     */
    public boolean authSetup(byte[] challenge) throws IOException {
        final byte[] body = AirPlay.authSetupBody(challenge == null ? randomChallenge() : challenge);
        final RtspMessage request = RtspMessage.request(RtspMessage.METHOD_POST,
                AirPlay.PATH_AUTH_SETUP);
        request.setHeader("CSeq", String.valueOf(mCseq++));
        request.setHeader("User-Agent", "OkCarLink/0.1");
        request.setBody(body, "application/octet-stream");
        final RtspMessage reply = mTransport.exchange(request);
        mState.log("session", "auth-setup: body=" + Hex.encode(body, 0, Math.min(body.length, 16))
                + " -> " + reply.summary());
        final byte[] replyBody = reply.body();
        if (replyBody != null && replyBody.length > 0) {
            mState.log("session", "auth-setup reply body: "
                    + Hex.encode(replyBody, 0, Math.min(replyBody.length, 32))
                    + (replyBody.length > 32 ? " …" : ""));
        }
        setState(State.AUTH_DONE, "auth step done (" + reply.bodyLength() + "B answer)");
        return true;
    }

    private static byte[] randomChallenge() {
        /* Measured length: the working sender's /auth-setup body is 33 bytes =
         * 0x01 + a 32-byte challenge.  A 16-byte challenge gets 403 Forbidden from
         * the production receiver (seen on 2026-09-25), so the length is not cosmetic. */
        final byte[] challenge = new byte[32];
        new java.util.Random().nextBytes(challenge);
        return challenge;
    }

    /* --------------------------------------------------------- step 3: streams */

    /**
     * SETUP with our `streams` request; the peer answers with the ports it will
     * use (that is where 36113/36486 come from on this car).
     */
    public List<StreamSetup.Stream> setupStreams(boolean withVideo, boolean withAudio)
            throws IOException {
        final List<Map<String, Object>> requested = new ArrayList<Map<String, Object>>();
        if (withVideo) {
            requested.add(StreamSetup.videoStreamRequest(AirPlay.STREAM_TYPE_VIDEO));
        }
        if (withAudio) {
            requested.add(StreamSetup.audioStreamRequest(AirPlay.STREAM_TYPE_AUDIO,
                    AirPlay.MAIN_AUDIO_MEDIA_OUTPUT));
        }
        final RtspMessage request = RtspMessage.request(RtspMessage.METHOD_SETUP, "rtsp://airplay/stream");
        request.setHeader("CSeq", String.valueOf(mCseq++));
        request.setHeader("User-Agent", "OkCarLink/0.1");
        request.setHeader("X-Apple-ProtocolVersion", AirPlay.PROTOCOL_VERSION);
        request.setPlistBody(StreamSetup.streamsRequest(requested));
        final RtspMessage reply = mTransport.exchange(request);
        mState.log("session", "SETUP -> " + reply.summary());

        final Object plist = reply.plistBody();
        final List<StreamSetup.Stream> streams = StreamSetup.parseStreams(plist);
        mStreams.clear();
        mStreams.addAll(streams);
        if (streams.isEmpty()) {
            mState.log("session", "SETUP reply carried no streams (body " + reply.bodyLength() + "B)");
            setState(State.FAILED, "no streams in SETUP reply");
            return mStreams;
        }
        for (StreamSetup.Stream stream : streams) {
            // Mirrors the closed-source app's own log line: "Found Stream, type:%d dataPort:%d".
            mState.log("session", "Found Stream, type:" + stream.type
                    + " dataPort:" + stream.dataPort);
        }
        setState(State.STREAMS_READY, StreamSetup.describe(streams));
        return mStreams;
    }

    /* ---------------------------------------------------------- step 4: record */

    public boolean record() throws IOException {
        final RtspMessage request = RtspMessage.request(RtspMessage.METHOD_RECORD, "rtsp://airplay/stream");
        request.setHeader("CSeq", String.valueOf(mCseq++));
        request.setHeader("User-Agent", "OkCarLink/0.1");
        final RtspMessage reply = mTransport.exchange(request);
        mState.log("session", "RECORD -> " + reply.summary());
        final boolean ok = !reply.isRequest() && reply.statusCode() >= 200 && reply.statusCode() < 300;
        setState(ok ? State.RECORDING : State.FAILED,
                ok ? "recording" : "RECORD rejected: " + reply.statusCode());
        return ok;
    }

    /* ------------------------------------------------------------ bookkeeping */

    public boolean sendParameter(Map<String, Object> plist) throws IOException {
        final RtspMessage request = RtspMessage.request(RtspMessage.METHOD_SET_PARAMETER,
                "rtsp://airplay/stream");
        request.setHeader("CSeq", String.valueOf(mCseq++));
        request.setPlistBody(plist);
        final RtspMessage reply = mTransport.exchange(request);
        mState.log("session", "SET_PARAMETER -> " + reply.summary());
        return true;
    }

    public void teardown() {
        try {
            final RtspMessage request = RtspMessage.request(RtspMessage.METHOD_TEARDOWN,
                    "rtsp://airplay/stream");
            request.setHeader("CSeq", String.valueOf(mCseq++));
            final RtspMessage reply = mTransport.exchange(request);
            mState.log("session", "TEARDOWN -> " + (reply == null ? "eof" : reply.summary()));
        } catch (IOException e) {
            Log.w(TAG, "teardown failed: " + e);
        } finally {
            final EventChannel channel = mEventChannel;
            mEventChannel = null;
            if (channel != null) {
                channel.stop();
            }
            mTransport.close();
            setState(State.STOPPED, "session stopped");
        }
    }

    /** Raw media bytes towards the peer (interleaved or a data connection). */
    public void sendMedia(byte[] data, int offset, int length) throws IOException {
        mTransport.sendBytes(data, offset, length);
    }

    private void setState(State state, String detail) {
        mStateValue = state;
        mState.log("session", state + ": " + detail);
        if (mListener != null) {
            mListener.onStateChanged(state, detail);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }

    /** Compact one-line rendering of an info plist. */
    private static String summarise(Map<String, Object> plist) {
        final StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Object> entry : plist.entrySet()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            final Object value = entry.getValue();
            sb.append(entry.getKey()).append('=');
            if (value instanceof byte[]) {
                sb.append(((byte[]) value).length).append("B");
            } else if (value instanceof Map) {
                sb.append("map(").append(((Map<?, ?>) value).size()).append(')');
            } else if (value instanceof List) {
                sb.append("list(").append(((List<?>) value).size()).append(')');
            } else {
                sb.append(value);
            }
            if (sb.length() > 400) {
                sb.append(" …");
                break;
            }
        }
        return sb.toString();
    }

    /* ====================================================================== *
     * Measured handshake — OAKCAR_AIRPLAY_WIRE_CAPTURE.md §1 (the recorded reference session).
     *
     * Order on the wire, CSeq from 0:
     *    0  POST /auth-setup                     application/octet-stream, 0x01||challenge
     *    1  SETUP  rtsp://<peer>/<sessionId>     base plist: identity + timingPort
     *                                            (+ eiv/ekey/et only when the caller
     *                                            asks for encryption — we default off)
     *    2  GET /info                            X-Apple-ProtocolVersion: 1, NO body
     *    3  RECORD rtsp://<peer>/<sessionId>
     *    4  POST /command   modesChanged
     *    5  POST /command   disableBluetooth
     *    6  POST /feedback                       (then roughly once per second)
     *    8  SETUP ...       streams[{type:110}]  -> answer carries the video dataPort
     *   12  SETUP ...       streams[{type:100}]  -> answer carries the audio dataPort
     *
     * Ports are NEVER assumed: both data ports come out of the SETUP answers.
     * ====================================================================== */

    private final Object mTransportLock = new Object();
    private volatile boolean mFeedbackRunning;
    private Thread mFeedbackThread;
    private int mVideoDataPort = -1;
    private int mAudioDataPort = -1;

    public int videoDataPort() {
        return mVideoDataPort;
    }

    /**
     * The `streamConnectionID` this session put in its video SETUP request. The
     * receiver derives the AES-128-CTR key/IV from it, so the media path must use
     * the very same number (AirPlayStreamCrypto).
     */
    public long videoStreamConnectionId() {
        return mVideoStreamConnectionId;
    }

    /** True when the session advertised `eiv`/`ekey`/`et` and encrypts frame payloads. */
    public boolean encryptionEnabled() {
        return mEncryption;
    }

    /** Stable, positive per-session number: its decimal form is the KDF input. */
    private long streamConnectionId() {
        if (mVideoStreamConnectionId < 0) {
            mVideoStreamConnectionId = new java.util.Random().nextLong() >>> 1;
        }
        return mVideoStreamConnectionId;
    }

    public int audioDataPort() {
        return mAudioDataPort;
    }

    /** Runs the whole measured handshake; returns false when no stream was negotiated. */
    public boolean runHandshake(String host, String sessionId, int timingPort,
                                boolean withEncryption) throws IOException {
        mEncryption = withEncryption;
        setState(State.CONNECTED, "handshake to " + host + " (encryption=" + withEncryption + ")");
        authSetup(mChallenge);
        baseSetup(host, sessionId, timingPort, withEncryption);
        getInfo();
        record(host, sessionId);
        /* Resource takeover, exactly the measured progression (the request reference capture CSeq 4..13):
         * the receiver only switches its display once entity 2 has been released. */
        commandModesChanged(2, 2);          /* CSeq 4: both resources claimed at 2 */
        commandDisableBluetooth();
        feedback();
        commandModesChanged(1, 2);          /* CSeq 7: resource 1 released to entity 1 */
        final StreamSetup.Stream video = setupStream(host, sessionId, AirPlay.STREAM_TYPE_VIDEO);
        commandModesChanged(1, 2);          /* CSeq 9 */
        commandModesChanged(1, 1);          /* CSeq 10 */
        commandModesChanged(1, 1);          /* CSeq 11 */
        final StreamSetup.Stream audio = setupStream(host, sessionId, AirPlay.STREAM_TYPE_AUDIO);
        commandModesChanged(1, 1);          /* CSeq 13 */
        startFeedbackLoop();
        if (video == null && audio == null) {
            setState(State.FAILED, "no stream negotiated");
            return false;
        }
        setState(State.RECORDING, "streams ready: video dataPort " + mVideoDataPort
                + ", audio dataPort " + mAudioDataPort);
        return true;
    }

    /** Base SETUP: who we claim to be, plus the timing port the peer should talk to. */
    public boolean baseSetup(String host, String sessionId, int timingPort, boolean withEncryption)
            throws IOException {
        final Map<String, Object> plist = new LinkedHashMap<String, Object>();
        plist.put(AirPlay.KEY_DEVICE_ID, deviceId());
        if (withEncryption) {
            /* Measured values, replayed verbatim: the working sender reuses the same
             * 16-byte eiv/ekey in every session, so this is a fixed blob, not a
             * per-session key exchange.  Kept behind a switch because a cleartext
             * stream may be accepted as-is (then none of this is needed). */
            plist.put(AirPlay.KEY_EIV, ENCRYPTION_IV);
            plist.put(AirPlay.KEY_EKEY, ENCRYPTION_KEY);
            plist.put(AirPlay.KEY_ET, Long.valueOf(AirPlay.ET_MEASURED));
        }
        plist.put(AirPlay.KEY_MAC_ADDRESS, AirPlay.SENDER_MAC);
        plist.put(AirPlay.KEY_MODEL, AirPlay.SENDER_MODEL);
        plist.put(AirPlay.KEY_NAME, AirPlay.SENDER_NAME);
        plist.put(AirPlay.KEY_OS_BUILD_VERSION, AirPlay.SENDER_OS_BUILD);
        plist.put(AirPlay.KEY_SESSION_UUID, sessionUuid());
        plist.put(AirPlay.KEY_SOURCE_VERSION, AirPlay.SENDER_SOURCE_VERSION);
        plist.put(AirPlay.KEY_TIMING_PORT, Long.valueOf(timingPort));
        final RtspMessage request = RtspMessage.request(RtspMessage.METHOD_SETUP,
                sessionUrl(host, sessionId));
        applySenderHeaders(request);
        request.setPlistBody(plist);
        final RtspMessage reply = exchange(request);
        mState.log("session", "base SETUP -> " + reply.summary());
        openEventChannel(reply);
        return isOk(reply);
    }

    /**
     * Opens the receiver's event channel (`eventPort` in the base SETUP answer).
     *
     * The measured sender connects here right after the base SETUP and keeps it
     * open for the whole session; the head unit pushes its `POST /command`
     * hidSendReport messages (touch/buttons) over it.  Without the connection the
     * receiver also stalls our RECORD for ~10 s before answering 500 (enc3.pcap).
     */
    private void openEventChannel(RtspMessage baseSetupReply) {
        try {
            final Object plist = baseSetupReply == null ? null : baseSetupReply.plistBody();
            if (plist instanceof Map) {
                final Object port = ((Map<?, ?>) plist).get(AirPlay.KEY_EVENT_PORT);
                if (port instanceof Long) {
                    mEventPort = ((Long) port).intValue();
                }
            }
        } catch (IOException e) {
            mState.log("session", "base SETUP answer not readable: " + e);
        }
        if (mEventPort <= 0 || mEventChannel != null) {
            return;
        }
        final String peer = mTransport == null ? "" : mTransport.remoteAddress();
        final EventChannel channel =
                new EventChannel(peer, mEventPort, mEventSink, mEventCommandSink);
        try {
            channel.start(5000);
            mEventChannel = channel;
        } catch (IOException e) {
            mState.log("session", "event channel failed: " + e.getMessage());
        }
    }

    /** Consumer for the head unit's HID reports (touch/buttons), may be null. */
    public void setEventSink(EventChannel.Sink sink) {
        mEventSink = sink;
    }

    /**
     * Consumer for the head unit's non-HID /command messages (requestUI,
     * changeModes).  Measured §12.34: answering requestUI is what brings the
     * picture back after the driver re-enters CarPlay.
     */
    public void setEventCommandSink(EventChannel.CommandSink sink) {
        mEventCommandSink = sink;
    }

    /** The receiver's event port for this session, or -1. */
    public int eventPort() {
        return mEventPort;
    }

    public EventChannel eventChannel() {
        return mEventChannel;
    }

    /** `GET /info` — no body, protocol version header (measured). */
    public boolean getInfo() throws IOException {
        final RtspMessage request = RtspMessage.request(RtspMessage.METHOD_GET, AirPlay.PATH_INFO);
        applySenderHeaders(request);
        request.setHeader("X-Apple-ProtocolVersion", AirPlay.APPLE_PROTOCOL_VERSION);
        final RtspMessage reply = exchange(request);
        mState.log("session", "GET /info -> " + reply.summary());
        final Object plist = reply.plistBody();
        if (plist instanceof Map) {
            mInfo = castMap(plist);
            mState.log("session", "receiver info: " + summarise(mInfo));
        }
        setState(State.INFO_DONE, "info step done");
        return isOk(reply);
    }

    /** `RECORD rtsp://<peer>/<sessionId>`. */
    public boolean record(String host, String sessionId) throws IOException {
        final RtspMessage request = RtspMessage.request(RtspMessage.METHOD_RECORD,
                sessionUrl(host, sessionId));
        applySenderHeaders(request);
        final RtspMessage reply = exchange(request);
        mState.log("session", "RECORD -> " + reply.summary());
        final boolean ok = isOk(reply);
        if (!ok) {
            setState(State.FAILED, "RECORD rejected: " + reply.statusCode());
        }
        return ok;
    }

    /** `POST /command` with the measured `modesChanged` body. */
    /** Initial claim: both resources at entity 2 (measured CSeq 4). */
    public boolean commandModesChanged() throws IOException {
        return commandModesChanged(2, 2);
    }

    /** `POST /command` `disableBluetooth`. */
    public boolean commandDisableBluetooth() throws IOException {
        final Map<String, Object> params = new LinkedHashMap<String, Object>();
        params.put(AirPlay.KEY_DEVICE_ID, deviceId());
        final Map<String, Object> plist = new LinkedHashMap<String, Object>();
        plist.put(AirPlay.KEY_PARAMS, params);
        plist.put(AirPlay.KEY_TYPE, AirPlay.COMMAND_DISABLE_BLUETOOTH);
        return command(plist, AirPlay.COMMAND_DISABLE_BLUETOOTH);
    }

    private boolean command(Map<String, Object> plist, String label) throws IOException {
        final RtspMessage request = RtspMessage.request(RtspMessage.METHOD_POST, AirPlay.PATH_COMMAND);
        applySenderHeaders(request);
        request.setPlistBody(plist);
        final RtspMessage reply = exchange(request);
        mState.log("session", "command " + label + " -> " + reply.summary());
        return isOk(reply);
    }

    /** `POST /feedback` — empty request; the answer carries the audio clock. */
    public boolean feedback() throws IOException {
        final RtspMessage request = RtspMessage.request(RtspMessage.METHOD_POST, AirPlay.PATH_FEEDBACK);
        applySenderHeaders(request);
        final RtspMessage reply = exchange(request);
        final Object plist = reply.plistBody();
        if (plist instanceof Map) {
            mState.log("session", "feedback -> " + summarise(castMap(plist)));
        } else {
            mState.log("session", "feedback -> " + reply.summary());
        }
        return isOk(reply);
    }

    /** One stream's SETUP; the answering plist carries that stream's dataPort. */
    public StreamSetup.Stream setupStream(String host, String sessionId, int streamType)
            throws IOException {
        final List<Map<String, Object>> requested = new ArrayList<Map<String, Object>>();
        requested.add(streamType == AirPlay.STREAM_TYPE_VIDEO
                ? videoStreamRequest() : audioStreamRequest());
        final RtspMessage request = RtspMessage.request(RtspMessage.METHOD_SETUP,
                sessionUrl(host, sessionId));
        applySenderHeaders(request);
        request.setPlistBody(StreamSetup.streamsRequest(requested));
        final RtspMessage reply = exchange(request);
        mState.log("session", "SETUP type " + streamType + " -> " + reply.summary());
        final List<StreamSetup.Stream> streams = StreamSetup.parseStreams(reply.plistBody());
        StreamSetup.Stream match = null;
        for (StreamSetup.Stream stream : streams) {
            mState.log("session", "Found Stream, type:" + stream.type + " dataPort:" + stream.dataPort);
            if (stream.type == streamType) {
                match = stream;
            }
        }
        if (match != null) {
            if (streamType == AirPlay.STREAM_TYPE_VIDEO) {
                mVideoDataPort = match.dataPort;
            } else if (streamType == AirPlay.STREAM_TYPE_AUDIO) {
                mAudioDataPort = match.dataPort;
            }
            mStreams.add(match);
        }
        setState(match == null ? State.FAILED : State.STREAMS_READY,
                match == null ? ("SETUP type " + streamType + " carried no stream")
                        : ("type " + match.type + " dataPort " + match.dataPort));
        return match;
    }

    /** Measured video stream request: uuid + streamConnectionID + latency + timestampInfo. */
    private Map<String, Object> videoStreamRequest() {
        final Map<String, Object> stream = new LinkedHashMap<String, Object>();
        stream.put(AirPlay.KEY_TYPE, Long.valueOf(AirPlay.STREAM_TYPE_VIDEO));
        stream.put(AirPlay.KEY_UUID, sessionUuid());
        stream.put(AirPlay.KEY_STREAM_CONNECTION_ID, Long.valueOf(streamConnectionId()));
        stream.put(AirPlay.KEY_LATENCY_MS, Long.valueOf(25));
        final List<Map<String, Object>> timestampInfo = new ArrayList<Map<String, Object>>();
        for (String name : new String[] {"SubSu", "BePxT", "AfPxT", "BefEn", "EmEnc"}) {
            final Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put(AirPlay.KEY_NAME, name);
            timestampInfo.add(item);
        }
        stream.put(AirPlay.KEY_TIMESTAMP_INFO, timestampInfo);
        return stream;
    }

    /** Measured audio stream request: 32768 = 16-bit PCM, media type, high-accuracy timestamps. */
    private Map<String, Object> audioStreamRequest() {
        final Map<String, Object> stream = new LinkedHashMap<String, Object>();
        stream.put(AirPlay.KEY_AUDIO_FORMAT, Long.valueOf(32768));
        stream.put(AirPlay.KEY_AUDIO_LATENCY_MS, Long.valueOf(50));
        stream.put(AirPlay.KEY_SUPPORTS_HIGH_ACCURACY_TIMESTAMPS, Long.valueOf(1));
        stream.put(AirPlay.KEY_AUDIO_TYPE, AirPlay.AUDIO_TYPE_MEDIA);
        stream.put(AirPlay.KEY_TYPE, Long.valueOf(AirPlay.STREAM_TYPE_AUDIO));
        return stream;
    }

    /**
     * `modesChanged` with the measured resource takeover progression.
     *
     * The receiver only switches its display to our stream after it has handed the
     * screen resources over, and that handover is expressed by the `entity` values:
     * the reference sender walks r2/r1 from entity 2 down to entity 1 around the two
     * stream SETUPs (measured CSeq 4, 7, 9, 10, 11, 13 — see
     * the internal notes/).  A single `(2,2)` command, which is all
     * we used to send, leaves the head unit on its own home screen even though
     * RECORD and both SETUPs answered 200.
     *
     * @param resource1Entity entity value for resourceID 1
     * @param resource2Entity entity value for resourceID 2
     */
    public boolean commandModesChanged(int resource1Entity, int resource2Entity)
            throws IOException {
        final List<Map<String, Object>> appStates = new ArrayList<Map<String, Object>>();
        appStates.add(appState(2, 0, null));
        appStates.add(appState(1, 0, Long.valueOf(-1)));
        appStates.add(appState(3, 0, null));
        final List<Map<String, Object>> resources = new ArrayList<Map<String, Object>>();
        resources.add(resource(2, resource2Entity, resource2Entity));
        resources.add(resource(1, resource1Entity, resource1Entity));
        final Map<String, Object> params = new LinkedHashMap<String, Object>();
        params.put(AirPlay.KEY_APP_STATES, appStates);
        params.put(AirPlay.KEY_RESOURCES, resources);
        final Map<String, Object> plist = new LinkedHashMap<String, Object>();
        plist.put(AirPlay.KEY_PARAMS, params);
        plist.put(AirPlay.KEY_TYPE, AirPlay.COMMAND_MODES_CHANGED);
        return command(plist, AirPlay.COMMAND_MODES_CHANGED + "(r1=e" + resource1Entity
                + ", r2=e" + resource2Entity + ")");
    }

    private static Map<String, Object> appState(int id, int entity, Long speechMode) {
        final Map<String, Object> state = new LinkedHashMap<String, Object>();
        state.put("appStateID", Long.valueOf(id));
        state.put("entity", Long.valueOf(entity));
        if (speechMode != null) {
            state.put("speechMode", speechMode);
        }
        return state;
    }

    private static Map<String, Object> resource(int id, int entity, int permanent) {
        final Map<String, Object> res = new LinkedHashMap<String, Object>();
        res.put("resourceID", Long.valueOf(id));
        res.put("entity", Long.valueOf(entity));
        res.put("permanentEntity", Long.valueOf(permanent));
        return res;
    }

    /* ----------------------------------------------------- feedback loop */

    /** Starts the once-per-second `POST /feedback` loop (idempotent). */
    public void startFeedbackLoop() {
        if (mFeedbackRunning) {
            return;
        }
        mFeedbackRunning = true;
        mFeedbackThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (mFeedbackRunning) {
                    try {
                        feedback();
                    } catch (IOException e) {
                        mState.log("session", "feedback stopped: " + e);
                        break;
                    }
                    try {
                        Thread.sleep(1000L);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            }
        }, "okcar-airplay-feedback");
        mFeedbackThread.setDaemon(true);
        mFeedbackThread.start();
        mState.log("session", "feedback loop started");
    }

    public void stopFeedbackLoop() {
        mFeedbackRunning = false;
        final Thread thread = mFeedbackThread;
        if (thread != null) {
            thread.interrupt();
            mFeedbackThread = null;
        }
    }

    /* ------------------------------------------------------------ helpers */

    private static final byte[] ENCRYPTION_IV = {
            0x68, (byte) 0xea, 0x43, (byte) 0xcb, 0x68, (byte) 0xea, 0x43, (byte) 0xcb,
            0x68, (byte) 0xea, 0x43, (byte) 0xcb, 0x68, (byte) 0xea, 0x43, (byte) 0xcb,
    };
    private static final byte[] ENCRYPTION_KEY = {
            0x67, 0x64, 0x00, 0x20, (byte) 0xac, 0x1b, 0x1a, (byte) 0x80,
            0x50, 0x05, (byte) 0x8f, (byte) 0xba, 0x6a, 0x0a, 0x0f, (byte) 0xc8,
    };

    private String mSessionUuid;
    private String mDeviceId;
    /** Optional fixed challenge; the measured sender appears to reuse one. */
    private byte[] mChallenge;

    /**
     * Forces a specific `/auth-setup` challenge.
     *
     * The working sender sends the SAME `ekey`/`eiv` in every session, which only
     * makes sense if its challenge is fixed too — the receiver unwraps the stream
     * key against whatever challenge the session started with.  Replaying a random
     * challenge with that fixed key material is a mismatch (RECORD answers 500),
     * so the measured challenge can be injected here for the experiment.
     */
    public void setChallenge(byte[] challenge) {
        mChallenge = challenge;
    }

    private String sessionUuid() {
        if (mSessionUuid == null) {
            mSessionUuid = java.util.UUID.randomUUID().toString();
        }
        return mSessionUuid;
    }

    /** Sender device id: the measured identity uses the spoofed MAC. */
    private String deviceId() {
        if (mDeviceId == null) {
            mDeviceId = AirPlay.SENDER_MAC;
        }
        return mDeviceId;
    }

    public void setDeviceId(String deviceId) {
        mDeviceId = deviceId;
    }

    /**
     * The RTSP request URI names **our own** address, not the peer's.
     *
     * Measured on every working session (the recorded reference session):
     * `SETUP rtsp://fe80::8af:75ff:fea9:afb8/<id>` from the sender at that address
     * while the head unit is `fe80::f8:a5ff:fea3:badc`.  Using the peer's address
     * instead (what we did until 2026-09-26) makes the receiver answer `RECORD`
     * with 500 after a ~10 s wait even though auth/SETUP/info all answer 200.
     */
    private String sessionUrl(String host, String sessionId) {
        String urlHost = mTransport == null ? "" : mTransport.localAddress();
        if (urlHost == null || urlHost.isEmpty()) {
            final int scope = host == null ? -1 : host.indexOf('%');
            urlHost = scope > 0 ? host.substring(0, scope) : host;
        }
        return "rtsp://" + urlHost + "/" + sessionId;
    }

    private void applySenderHeaders(RtspMessage request) {
        request.setHeader("CSeq", String.valueOf(mCseq++));
        request.setHeader("User-Agent", AirPlay.SENDER_USER_AGENT);
    }

    private RtspMessage exchange(RtspMessage request) throws IOException {
        synchronized (mTransportLock) {
            return mTransport.exchange(request);
        }
    }

    private static boolean isOk(RtspMessage reply) {
        return reply != null && !reply.isRequest()
                && reply.statusCode() >= 200 && reply.statusCode() < 300;
    }
}
