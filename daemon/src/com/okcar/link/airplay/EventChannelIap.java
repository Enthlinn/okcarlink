package com.okcar.link.airplay;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The body the phone uses to push one iAP2 message to the accessory over the AirPlay event channel.
 *
 * <p>Once a wireless session is up the accessory moves its iAP2 control channel off Bluetooth: on
 * the production head unit the RFCOMM socket is closed the moment the AirPlay session starts, and every later
 * iAP2 message of ours written to that dead socket is lost.  The transport the real sender uses in
 * that phase is the sealed event channel, as an RTSP {@code POST /command} whose binary plist body
 * is this shape (reverse-engineered from the emulator's receiver —
 * {@code the field acceptance log} §630-638 — and measured again on the
 * real car in {@code the field acceptance log} §4.25):
 *
 * <pre>
 * POST /command  RTSP/1.0
 * {"type": "iAPSendMessage", "params": {"data": &lt;bytes&gt;}}
 * </pre>
 *
 * <p>Pure on purpose: the harness asserts this shape without an Android runtime.
 */
public final class EventChannelIap {

    /** Plist key holding the message type, and the value that means "a raw iAP2 frame follows". */
    public static final String TYPE_KEY = "type";
    public static final String TYPE_IAP_SEND_MESSAGE = "iAPSendMessage";
    public static final String PARAMS_KEY = "params";
    public static final String DATA_KEY = "data";

    private EventChannelIap() {
    }

    /**
     * @param payload one complete iAP2 frame as it would have been written to the transport
     * @return the plist body for the event-channel request
     */
    public static Map<String, Object> sendBody(byte[] payload) {
        final Map<String, Object> params = new LinkedHashMap<String, Object>();
        params.put(DATA_KEY, payload);
        final Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put(TYPE_KEY, TYPE_IAP_SEND_MESSAGE);
        body.put(PARAMS_KEY, params);
        return body;
    }

    /**
     * @return the iAP2 frame inside one inbound event-channel body, or null when the body is not an
     *         {@code iAPSendMessage} (the event channel also carries {@code hidSendReport} and the
     *         session commands)
     */
    @SuppressWarnings("unchecked")
    public static byte[] payloadOf(Map<String, Object> body) {
        if (body == null || !TYPE_IAP_SEND_MESSAGE.equals(body.get(TYPE_KEY))) {
            return null;
        }
        final Object params = body.get(PARAMS_KEY);
        if (!(params instanceof Map)) {
            return null;
        }
        final Object data = ((Map<String, Object>) params).get(DATA_KEY);
        return data instanceof byte[] ? (byte[]) data : null;
    }
}
