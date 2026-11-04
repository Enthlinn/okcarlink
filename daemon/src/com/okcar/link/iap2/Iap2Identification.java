/*
 * OkcarOS link stack — L3: the device side of the iAP2 identification exchange.
 *
 * Why this exists: with only DETECT/SYN the head unit accepts the link but never
 * treats the phone as a *present Apple device* — it periodically resets the USB
 * link (observed 2026-09-26: `APPLE_DEVICE_DISCONNECTED` + repeated
 * `UsbHostManager: USB device attached`, our AirPlay session dying after ~5 min
 * with `Broken pipe` / `ENETUNREACH`), and a cold head unit never opens its
 * AirPlay receiver at all until the closed-source baseline has identified once.
 *
 * The parameter IDs and the message IDs are not guesses: the head unit's own
 * unstripped `libiPodForiAP2.so` was disassembled
 * (from a neighbor reverse-engineering project of the same stock head unit):
 *
 *   - its control-session dispatcher handles 0x1D00 / 0x1D02 / 0x1D03,
 *   - `IdentificationInformation::ModelIdentifier` writes parameter 0x0001,
 *     `Manufacturer` 0x0002, `SerialNumber` 0x0003 (0x300 big-endian),
 *     `FirmwareVersion` 0x0004, `HardwareVersion` 0x0005,
 *     `MessagesSentByAccessory` a 9-byte bitmask at 0x0006,
 *     `PowerProvidingCapability` 3 bytes at 0x0008 and the transport components
 *     a 15-byte block at 0x0026.
 *
 * Each parameter on the wire is `[BE16 id][BE16 length][value]`, concatenated;
 * the message itself is wrapped by Iap2Message.encode (40 40 len len msgid).
 *
 * Pure Java, so the encoding is checked on the host JVM
 * (.car-test/iap2-ident).
 */

package com.okcar.link.iap2;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

public final class Iap2Identification {

    /**
     * Message ids of the identification exchange, read out of the head unit's own
     * dispatcher (libiPodForiAP2.so): on receiving {@link #MSG_IDENTIFICATION_REQUEST}
     * its 0x1D00 branch builds its IdentificationInformation and answers 0x1D01,
     * which is why the device must *not* open with 0x1D02.
     */
    /** Device -> accessory: start identification ("identify yourself"). */
    public static final int MSG_IDENTIFICATION_REQUEST = 0x1D00;

    /** Accessory -> device: the accessory's identification information. */
    public static final int MSG_ACCESSORY_IDENTIFICATION = 0x1D01;

    /** Device -> accessory: my identification information. */
    public static final int MSG_IDENTIFICATION_INFORMATION = 0x1D02;

    /** Device -> accessory: your identification is accepted. */
    public static final int MSG_IDENTIFICATION_ACCEPTED = 0x1D03;

    /* Parameter IDs, as written by the head unit's own helpers. */
    public static final int PARAM_NAME = 0x0000;
    public static final int PARAM_MODEL_IDENTIFIER = 0x0001;
    public static final int PARAM_MANUFACTURER = 0x0002;
    public static final int PARAM_SERIAL_NUMBER = 0x0003;
    public static final int PARAM_FIRMWARE_VERSION = 0x0004;
    public static final int PARAM_HARDWARE_VERSION = 0x0005;
    public static final int PARAM_MESSAGES_SENT = 0x0006;
    public static final int PARAM_MESSAGES_RECEIVED = 0x0007;
    public static final int PARAM_POWER_PROVIDING = 0x0008;
    public static final int PARAM_CURRENT_LANGUAGE = 0x0009;
    public static final int PARAM_SUPPORTED_LANGUAGE = 0x000A;
    public static final int PARAM_USB_DEVICE_TRANSPORT = 0x0026;

    /** Bitmask size the head unit uses for its own message sets. */
    public static final int BITMASK_BYTES = 9;

    /**
     * The message-set parameters are **not** indexed by the raw message id: iAP2
     * numbers the messages in its own list and sets bit N for list entry N, which
     * is why the head unit's own bitmask is 9 bytes (72 entries).  Reconstructing
     * that list from the binary is not necessary for us to be accepted, so we
     * declare the whole set for both directions: the head unit may then send
     * messages we do not handle yet (Iap2SessionHandler logs them), which is far
     * better than having it refuse the device as "not a CarPlay device".
     */
    public static byte[] fullMessageSet() {
        final byte[] mask = new byte[BITMASK_BYTES];
        java.util.Arrays.fill(mask, (byte) 0xFF);
        return mask;
    }

    private Iap2Identification() {
    }

    /**
     * Builds the 0x1D02 payload: our identification information.
     *
     * @param name         user-visible device name
     * @param model        model identifier, e.g. "iPhone10,1"
     * @param manufacturer manufacturer string
     * @param serial       serial number string
     * @param firmware     firmware version string
     * @param hardware     hardware version string
     */
    public static byte[] deviceIdentification(String name, String model, String manufacturer,
                                              String serial, String firmware, String hardware) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream(160);
        try {
            out.write(parameter(PARAM_NAME, ascii(name)));
            out.write(parameter(PARAM_MODEL_IDENTIFIER, ascii(model)));
            out.write(parameter(PARAM_MANUFACTURER, ascii(manufacturer)));
            out.write(parameter(PARAM_SERIAL_NUMBER, ascii(serial)));
            out.write(parameter(PARAM_FIRMWARE_VERSION, ascii(firmware)));
            out.write(parameter(PARAM_HARDWARE_VERSION, ascii(hardware)));
            out.write(parameter(PARAM_MESSAGES_SENT, fullMessageSet()));
            out.write(parameter(PARAM_MESSAGES_RECEIVED, fullMessageSet()));
            out.write(parameter(PARAM_POWER_PROVIDING, new byte[]{0x01, 0x00, 0x00}));
            out.write(parameter(PARAM_CURRENT_LANGUAGE, ascii("en")));
            out.write(parameter(PARAM_SUPPORTED_LANGUAGE, ascii("en")));
            /* Transport component: 15-byte block, id + version + name, matching the
             * head unit's own component length. */
            out.write(parameter(PARAM_USB_DEVICE_TRANSPORT, new byte[]{
                    0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00,
                    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00}));
        } catch (IOException e) {
            throw new IllegalStateException("ByteArrayOutputStream cannot fail", e);
        }
        return out.toByteArray();
    }

    /** `[BE16 id][BE16 length][value]`. */
    public static byte[] parameter(int id, byte[] value) {
        final byte[] safe = value == null ? new byte[0] : value;
        final byte[] out = new byte[4 + safe.length];
        out[0] = (byte) ((id >> 8) & 0xFF);
        out[1] = (byte) (id & 0xFF);
        out[2] = (byte) ((safe.length >> 8) & 0xFF);
        out[3] = (byte) (safe.length & 0xFF);
        System.arraycopy(safe, 0, out, 4, safe.length);
        return out;
    }

    /** Bitmask over iAP2 **list indices** (not raw message ids). */
    public static byte[] bitmask(int... indices) {
        final byte[] mask = new byte[BITMASK_BYTES];
        if (indices == null) {
            return mask;
        }
        for (int index : indices) {
            if (index < 0 || index >= BITMASK_BYTES * 8) {
                continue;
            }
            mask[index / 8] |= (byte) (1 << (index % 8));
        }
        return mask;
    }

    public static boolean bitmaskContains(byte[] mask, int index) {
        return mask != null && index >= 0 && index < mask.length * 8
                && (mask[index / 8] & (1 << (index % 8))) != 0;
    }

    /** ASCII bytes (no terminator): iAP2 string parameters are length-prefixed. */
    private static byte[] ascii(String text) {
        return (text == null ? "" : text).getBytes(java.nio.charset.Charset.forName("US-ASCII"));
    }
}
