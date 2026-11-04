/*
 * OkcarOS link stack — L3: iAP2 messages.
 *
 * Message ids recovered by independent disassembly of the head unit's
 * libiPodForiAP2.so (exeed findings A §5.8).  Only the ones this stack needs to
 * build a working CarPlay *device/sender* session are listed, plus the auth and
 * HID trio that the touch path depends on.
 *
 * Control-session wire wrapper (iAP2SendMessage @ 0x35330 in that library):
 *
 *   40 40 LEN1 LEN2 MSGID_HI MSGID_LO <payload>       LEN = payload_len + 6
 *
 * A session type of 0x0C bypasses the wrapper and carries the message raw.
 */

package com.okcar.link.iap2;

import java.util.Arrays;

public final class Iap2Message {

    /* --- HID reporting (this is how the head unit sends touch / keys) --- */
    public static final int MSG_START_HID = 0x6800;
    public static final int MSG_HID_REPORT = 0x6802;
    public static final int MSG_STOP_HID = 0x6803;

    /* --- MFi authentication, device(sender) side of the exchange --- */
    public static final int MSG_AUTH_CERTIFICATE_REQUEST = 0xAA00;
    public static final int MSG_AUTH_CERTIFICATE_RESPONSE = 0xAA01;
    public static final int MSG_AUTH_SIGNATURE_REQUEST = 0xAA02;
    public static final int MSG_AUTH_FAILED = 0xAA04;
    public static final int MSG_AUTH_SUCCEEDED = 0xAA05;

    /* --- device identification / status --- */
    public static final int MSG_DEVICE_INFORMATION_UPDATE = 0x4E09;
    public static final int MSG_DEVICE_LANGUAGE_UPDATE = 0x4E0A;
    public static final int MSG_WIRELESS_CARPLAY_UPDATE = 0x4E0D;
    public static final int MSG_DEVICE_TRANSID_NOTIFICATION = 0x4E0E;

    /* --- media / telephony / vehicle status --- */
    public static final int MSG_NOW_PLAYING = 0x5000;
    public static final int MSG_SET_ELAPSED_TIME = 0x5003;
    public static final int MSG_START_CALL_STATES_UPDATE = 0x4154;
    public static final int MSG_VEHICLE_STATUS_UPDATE = 0xA101;

    /** Session type that skips the control wrapper. */
    public static final int SESSION_TYPE_RAW = 0x0C;

    private Iap2Message() {
    }

    public static final class Decoded {
        public final int messageId;
        public final byte[] payload;

        Decoded(int messageId, byte[] payload) {
            this.messageId = messageId;
            this.payload = payload;
        }
    }

    /** Wraps a message id + payload into the control-session body. */
    public static byte[] encode(int messageId, byte[] payload, int offset, int length) {
        final int payloadLength = Math.max(0, length);
        final int total = payloadLength + 6;
        final byte[] out = new byte[total];
        out[0] = 0x40;
        out[1] = 0x40;
        out[2] = (byte) ((total >> 8) & 0xFF);
        out[3] = (byte) (total & 0xFF);
        out[4] = (byte) ((messageId >> 8) & 0xFF);
        out[5] = (byte) (messageId & 0xFF);
        if (payloadLength > 0 && payload != null) {
            System.arraycopy(payload, offset, out, 6, payloadLength);
        }
        return out;
    }

    public static byte[] encode(int messageId, byte[] payload) {
        return encode(messageId, payload, 0, payload == null ? 0 : payload.length);
    }

    /** Decodes a control-session body; null when it does not have that shape. */
    public static Decoded decode(byte[] body, int offset, int length) {
        if (body == null || length < 6 || offset < 0 || offset + length > body.length) {
            return null;
        }
        final int messageId = ((body[offset + 4] & 0xFF) << 8) | (body[offset + 5] & 0xFF);
        final byte[] payload = Arrays.copyOfRange(body, offset + 6, offset + length);
        return new Decoded(messageId, payload);
    }

    public static Decoded decode(byte[] body) {
        return body == null ? null : decode(body, 0, body.length);
    }

    public static String name(int messageId) {
        switch (messageId) {
            case MSG_START_HID:
                return "startHID";
            case MSG_HID_REPORT:
                return "HIDReport";
            case MSG_STOP_HID:
                return "stopHID";
            case MSG_AUTH_CERTIFICATE_REQUEST:
                return "authCertificateRequest";
            case MSG_AUTH_CERTIFICATE_RESPONSE:
                return "authCertificateResponse";
            case MSG_AUTH_SIGNATURE_REQUEST:
                return "authSignatureRequest";
            case MSG_AUTH_FAILED:
                return "authFailed";
            case MSG_AUTH_SUCCEEDED:
                return "authSucceeded";
            case MSG_DEVICE_INFORMATION_UPDATE:
                return "deviceInformationUpdate";
            case MSG_DEVICE_LANGUAGE_UPDATE:
                return "deviceLanguageUpdate";
            case MSG_WIRELESS_CARPLAY_UPDATE:
                return "wirelessCarPlayUpdate";
            case MSG_DEVICE_TRANSID_NOTIFICATION:
                return "deviceTransIdNotification";
            case MSG_NOW_PLAYING:
                return "nowPlaying";
            case MSG_SET_ELAPSED_TIME:
                return "setElapsedTime";
            case MSG_START_CALL_STATES_UPDATE:
                return "startCallStatesUpdate";
            case MSG_VEHICLE_STATUS_UPDATE:
                return "vehicleStatusUpdate";
            default:
                return String.format("msg-0x%04X", messageId);
        }
    }
}
