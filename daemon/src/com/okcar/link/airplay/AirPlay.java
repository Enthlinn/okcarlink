/*
 * OkcarOS link stack — L4: AirPlay/CarPlay constants and the /auth-setup body.
 *
 * Every value here comes from an evidence-backed source, never from a guess —
 * the head-unit capture notes §2/§6 and the field-rework notes §12.16 D, §12.17 C,
 * §12.19 C:
 *
 *   features                    0x6104040280      (head unit AirPlay advertisement)
 *   MainAudio_Media_Output      0x8800
 *   MainHighAudio_Media_Output  0xc00000
 *   AltAudio                    0x40008800
 *   Compat                      0x8854
 *   protocol version            AirPlay/320.17.3
 *   stream type 110 / 100       video / audio data streams (field log §12.17 C)
 *   dataPort negotiation        NOT hard-coded: observed 36113 / 36486
 *   POST /auth-setup            body = 0x01 + challenge (app: g6/f.java)
 *   _carplay-ctrl._tcp          reverse control: POST /ctrl-int/1/<cmd> with
 *                               header AirPlay-Receiver-Device-ID: %llu
 *
 * Pure Java (no android.* imports) so it is testable on the host JVM.
 */

package com.okcar.link.airplay;

import java.util.Arrays;

public final class AirPlay {

    /* --- advertised capabilities --- */
    public static final long FEATURES = 0x6104040280L;
    public static final long MAIN_AUDIO_MEDIA_OUTPUT = 0x8800L;
    public static final long MAIN_HIGH_AUDIO_MEDIA_OUTPUT = 0xc00000L;
    public static final long ALT_AUDIO = 0x40008800L;
    public static final long COMPAT = 0x8854L;

    /**
     * Version string the production head unit advertises (exeed findings §5.3).
     *
     * NOTE: the string is implementation specific — a production automotive
     * receiver from another vendor (com.autochips.carplayapp) reports
     * "AirPlay/480.5" (the field-rework notes §12.25).  Always echo what the
     * peer advertises instead of hard-coding one of them.
     */
    public static final String PROTOCOL_VERSION = "AirPlay/320.17.3";

    /* --- mDNS service types --- */
    public static final String SERVICE_AIRPLAY = "_airplay._tcp";
    public static final String SERVICE_CARPLAY_CTRL = "_carplay-ctrl._tcp";
    public static final String SERVICE_RAOP = "_raop._tcp";

    /* --- well-known AirPlay ports (the ones we must NOT hard-code) --- */
    public static final int PORT_AIRPLAY_DEFAULT = 7000;
    public static final int PORT_RAOP_DEFAULT = 5000;

    /* --- stream types seen in the field log --- */
    public static final int STREAM_TYPE_VIDEO = 110;
    public static final int STREAM_TYPE_AUDIO = 100;

    /* --- control endpoints --- */
    public static final String PATH_INFO = "/info";
    public static final String PATH_FEEDBACK = "/feedback";
    public static final String PATH_AUTH_SETUP = "/auth-setup";
    public static final String PATH_PAIR_SETUP = "/pair-setup";
    public static final String PATH_PAIR_VERIFY = "/pair-verify";
    public static final String PATH_CTRL_INT_PREFIX = "/ctrl-int/1/";

    /** Header the head unit expects when it drives us through /ctrl-int. */
    public static final String HEADER_RECEIVER_DEVICE_ID = "AirPlay-Receiver-Device-ID";

    /* ------------------------------------------------------------------ *
     * Measured sender behaviour (the wire-capture notes §1/§2,    *
     * the recorded reference session, 2026-09-25).  The closed-source app presents itself as  *
     * an Apple sender; these are its literal values on the wire.          *
     * ------------------------------------------------------------------ */

    /** User-Agent the working sender sends (measured). */
    public static final String SENDER_USER_AGENT = "AirPlay/420.5";
    /** Value of X-Apple-ProtocolVersion on the measured `GET /info`. */
    public static final String APPLE_PROTOCOL_VERSION = "1";
    /** Spoofed sender identity the measured SETUP carried. */
    public static final String SENDER_MODEL = "iPhone10,1";
    public static final String SENDER_NAME = "OKCAR's box";
    public static final String SENDER_OS_BUILD = "18E212";
    public static final String SENDER_MAC = "00:11:22:33:44:55";
    public static final String SENDER_SOURCE_VERSION = "420.5";

    /** `/command` endpoint (measured: modesChanged / disableBluetooth / hidSendReport). */
    public static final String PATH_COMMAND = "/command";
    public static final String COMMAND_MODES_CHANGED = "modesChanged";
    public static final String COMMAND_DISABLE_BLUETOOTH = "disableBluetooth";
    public static final String COMMAND_HID_SEND_REPORT = "hidSendReport";

    /* plist keys the measured SETUP / SET_PARAMETER / command bodies use */
    public static final String KEY_EIV = "eiv";
    public static final String KEY_EKEY = "ekey";
    public static final String KEY_ET = "et";
    public static final String KEY_OS_BUILD_VERSION = "osBuildVersion";
    public static final String KEY_SOURCE_VERSION = "sourceVersion";
    public static final String KEY_UUID = "uuid";
    public static final String KEY_PARAMS = "params";
    public static final String KEY_APP_STATES = "appStates";
    public static final String KEY_RESOURCES = "resources";
    public static final String KEY_LATENCY_MS = "latencyMs";
    public static final String KEY_TIMESTAMP_INFO = "timestampInfo";
    public static final String KEY_AUDIO_LATENCY_MS = "audioLatencyMs";
    public static final String KEY_SUPPORTS_HIGH_ACCURACY_TIMESTAMPS = "supportsHighAccuracyTimestamps";
    public static final String KEY_AUDIO_TYPE = "audioType";
    public static final String AUDIO_TYPE_MEDIA = "media";
    public static final String KEY_HID_REPORT = "hidReport";

    /** Encryption type the measured SETUP carried (`et: 1`). */
    public static final int ET_MEASURED = 1;

    /** First byte of the /auth-setup body; the rest is the challenge. */
    public static final int AUTH_SETUP_TAG = 0x01;

    /*
     * Stream-dictionary keys.
     *
     * SETUP / SET_PARAMETER carry `streams` — an array of dictionaries — and
     * these are the keys inside them.  Cross-checked against a production
     * automotive AirPlay receiver (com.autochips.carplayapp, AirPlay/480.5):
     * every key below is present as a literal in its libcarplay_jni.so, and the
     * port keys line up with what the production head unit negotiates in the field
     * (dataPort 36113/36486, §12.17 C).  See the field-rework notes §12.25.
     */
    public static final String KEY_STREAMS = "streams";
    public static final String KEY_TYPE = "type";
    public static final String KEY_DATA_PORT = "dataPort";
    public static final String KEY_CONTROL_PORT = "controlPort";
    public static final String KEY_TIMING_PORT = "timingPort";
    public static final String KEY_EVENT_PORT = "eventPort";
    public static final String KEY_STREAM_CONNECTION_ID = "streamConnectionID";
    public static final String KEY_LATENCY_MIN = "latencyMin";
    public static final String KEY_LATENCY_MAX = "latencyMax";
    public static final String KEY_AUDIO_FORMAT = "audioFormat";
    public static final String KEY_DEVICE_ID = "deviceID";
    public static final String KEY_SESSION_UUID = "sessionUUID";
    public static final String KEY_FEATURES = "features";
    public static final String KEY_FLAGS = "flags";
    public static final String KEY_MODEL = "model";
    public static final String KEY_MANUFACTURER = "manufacturer";
    public static final String KEY_SERIAL_NUMBER = "serialNumber";
    public static final String KEY_SRCVERS = "srcvers";
    public static final String KEY_MAC_ADDRESS = "macAddress";
    public static final String KEY_NAME = "name";
    public static final String KEY_METHOD = "method";
    public static final String KEY_PUBLIC_KEY = "publicKey";

    /** Every stream-dictionary key above, for logging and validation. */
    public static final String[] STREAM_KEYS = {
            KEY_STREAMS, KEY_TYPE, KEY_DATA_PORT, KEY_CONTROL_PORT, KEY_TIMING_PORT,
            KEY_EVENT_PORT, KEY_STREAM_CONNECTION_ID, KEY_LATENCY_MIN, KEY_LATENCY_MAX,
            KEY_AUDIO_FORMAT, KEY_DEVICE_ID, KEY_SESSION_UUID, KEY_FEATURES, KEY_FLAGS,
            KEY_MODEL, KEY_MANUFACTURER, KEY_SERIAL_NUMBER, KEY_SRCVERS, KEY_MAC_ADDRESS,
            KEY_NAME, KEY_METHOD, KEY_PUBLIC_KEY,
    };

    private AirPlay() {
    }

    /*
     * MFi: sender/device side does NOT verify signatures (§12.19 — the app has no
     * verification primitives at all, and our phone has no MFi coprocessor yet the
     * whole chain works).  We therefore only *build* and *carry* the challenge /
     * answer bytes; there is deliberately no verification code here.
     */

    /** Builds the `POST /auth-setup` body: {@code 0x01 || challenge}. */
    public static byte[] authSetupBody(byte[] challenge) {
        final byte[] body = new byte[1 + (challenge == null ? 0 : challenge.length)];
        body[0] = (byte) AUTH_SETUP_TAG;
        if (challenge != null) {
            System.arraycopy(challenge, 0, body, 1, challenge.length);
        }
        return body;
    }

    /**
     * Extracts the challenge part of an `/auth-setup` body.
     *
     * @return the bytes after the tag, or null when the body is not tagged.
     */
    public static byte[] authSetupChallenge(byte[] body) {
        if (body == null || body.length < 1 || (body[0] & 0xFF) != AUTH_SETUP_TAG) {
            return null;
        }
        return Arrays.copyOfRange(body, 1, body.length);
    }

    public static String features() {
        return String.format("0x%x", FEATURES);
    }

    /** Endpoint for one reverse-control command, e.g. "play". */
    public static String ctrlIntPath(String command) {
        return PATH_CTRL_INT_PREFIX + command;
    }
}
