/*
 * OkcarOS link stack — L5: the accessory's own Wi-Fi hand-off frames.
 *
 * A CarPlay accessory that has to tell the phone which network to join sends the
 * credentials on a channel of its own.  Two production implementations were read
 * for this (see ODCAR_BT_CARPLAY_DETECT_STATUS.md §3.9):
 *
 *   1. AutoChips / Fruit Link (com.autochips.carplayapp) — an iAP2 message
 *      (Iap2Link::_AccessoryWiFiConfigurationInformation, msgid 0x5703, which
 *      the driver already decodes; its reply to our 0x5702).
 *
 *   2. the integrator / the production head unit (com.neusoft.carplay) — NOT iAP2.  Its
 *      CarplayWifiConnectManage listens on 00000000-deca-fade-deca-deafdecacaff,
 *      writes INITDATA (FF 55 02 00 EE 10) on the socket it accepted, and then
 *      calls CarPlayService.wifiConnected(), which pushes these reports out of
 *      CarPlayFeature.SndHIDMsgEx:
 *
 *        A5 5A <total length u32 big-endian> <payload> <checksum>
 *          checksum = sum of every preceding byte, mod 256
 *          total length counts the whole frame, i.e. 6 header + payload + 1 checksum
 *
 *        payload = ASCII name + value
 *          "C41000" + BT MAC      SetBluetoothIDs
 *          "C12000" + SSID        SetWiFiSSID
 *          "C22000" + passphrase  SetPassphrase
 *          "C71000"               SetupBTConnection
 *
 *      The frame is written straight onto the ...caff RFCOMM socket, so it is
 *      read as a byte stream — no iAP2 framing, no HID report descriptor.
 *
 * Nothing else on that link starts with A5 5A, and the length field makes each
 * frame self-delimiting, so the parser needs no heuristics beyond the markers.
 */

package com.okcar.link.wireless;

import android.util.Log;

public final class AccessoryWifiAnnouncement {

    private static final String TAG = "okcar-announce";

    /** How many checksum mismatches are logged before the stream goes quiet. */
    private static final int CHECKSUM_LOG_LIMIT = 5;

    private static int sChecksumMismatches;

    /** Frame markers: CarPlayFeature.CARPLAY_START_PCK_MSB / _LSB in the production head unit tree. */
    public static final int MARKER_0 = 0xA5;
    public static final int MARKER_1 = 0x5A;

    /** Bytes before the payload: two markers + a 4-byte big-endian length. */
    public static final int HEADER_BYTES = 6;

    /** What the length field counts: header + payload + the trailing checksum. */
    public static final int FRAME_OVERHEAD = 7;

    /** Upper bound accepted for one frame, so a corrupt length cannot grow the buffer. */
    public static final int MAX_FRAME = 4096;

    public static final String REPORT_SET_BLUETOOTH_IDS = "C41000";
    public static final String REPORT_SET_WIFI_SSID = "C12000";
    public static final String REPORT_SET_PASSPHRASE = "C22000";
    public static final String REPORT_SETUP_BT_CONNECTION = "C71000";

    private AccessoryWifiAnnouncement() {
    }

    /**
     * The ASCII report name a payload starts with, or null when it is not one of the
     * reports this class knows about.
     */
    public static String reportOf(byte[] payload, int offset, int length) {
        if (payload == null) {
            return null;
        }
        for (String name : new String[]{REPORT_SET_BLUETOOTH_IDS, REPORT_SET_WIFI_SSID,
                REPORT_SET_PASSPHRASE, REPORT_SETUP_BT_CONNECTION}) {
            if (startsWith(payload, offset, length, name)) {
                return name;
            }
        }
        return null;
    }

    /**
     * The value that follows a known report name, as ASCII, or null when the payload is
     * not one of ours.  Never null for a recognised name: an empty value is "".
     */
    public static String valueOf(byte[] payload, int offset, int length) {
        final String name = reportOf(payload, offset, length);
        if (name == null) {
            return null;
        }
        final int start = offset + name.length();
        final int count = length - name.length();
        if (count <= 0) {
            return "";
        }
        final StringBuilder out = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            final int b = payload[start + i] & 0xFF;
            if (b == 0) {
                break;               /* the sender NUL-pads the report buffer */
            }
            if (b < 0x20 || b > 0x7E) {
                break;               /* stop at the first non-printable */
            }
            out.append((char) b);
        }
        return out.toString();
    }

    private static boolean startsWith(byte[] data, int offset, int length, String ascii) {
        if (length < ascii.length() || offset < 0) {
            return false;
        }
        for (int i = 0; i < ascii.length(); i++) {
            if ((data[offset + i] & 0xFF) != (ascii.charAt(i) & 0xFF)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Reassembles {@code A5 5A} frames from a byte stream.  Feed whatever the socket
     * read returned, then poll until null; the buffer keeps a partial frame for the
     * next feed and resynchronises on the marker when a frame is corrupt.
     */
    public static final class Parser {

        private byte[] mBuf = new byte[256];
        private int mLen;

        public void reset() {
            mLen = 0;
        }

        /** Bytes currently held for a not-yet-complete frame; diagnostic only. */
        public int pending() {
            return mLen;
        }

        public void feed(byte[] data, int offset, int length) {
            if (data == null || length <= 0) {
                return;
            }
            if (mLen + length > mBuf.length) {
                int wanted = Math.max(mBuf.length * 2, mLen + length);
                if (wanted > MAX_FRAME + 64) {
                    wanted = MAX_FRAME + 64;
                }
                final byte[] grown = new byte[wanted];
                System.arraycopy(mBuf, 0, grown, 0, mLen);
                mBuf = grown;
            }
            final int room = Math.min(length, mBuf.length - mLen);
            System.arraycopy(data, offset, mBuf, mLen, room);
            mLen += room;
        }

        /** The next complete frame's payload, or null when more bytes are needed. */
        public byte[] poll() {
            while (true) {
                if (mLen < HEADER_BYTES) {
                    return null;
                }
                if ((mBuf[0] & 0xFF) != MARKER_0 || (mBuf[1] & 0xFF) != MARKER_1) {
                    if (!resync()) {
                        return null;
                    }
                    continue;
                }
                final int total = ((mBuf[2] & 0xFF) << 24) | ((mBuf[3] & 0xFF) << 16)
                        | ((mBuf[4] & 0xFF) << 8) | (mBuf[5] & 0xFF);
                if (total < FRAME_OVERHEAD || total > MAX_FRAME) {
                    drop(2);             /* keep looking for the next marker */
                    continue;
                }
                if (mLen < total) {
                    return null;         /* need the rest of the frame */
                }
                int sum = 0;
                for (int i = 0; i < total - 1; i++) {
                    sum += mBuf[i] & 0xFF;
                }
                final int checksum = mBuf[total - 1] & 0xFF;
                final int payloadLength = total - FRAME_OVERHEAD;
                final byte[] payload = new byte[payloadLength];
                System.arraycopy(mBuf, HEADER_BYTES, payload, 0, payloadLength);
                drop(total);
                if ((sum & 0xFF) != 0) {
                    /* The checksum convention could not be pinned against a real capture: the
                     * captures on hand contain no C12000/C22000 frame at all, and the single
                     * complete A5 5A frame they do contain belongs to the head unit's internal
                     * iAP2 tunnel and satisfies neither "sum of all bytes == 0" nor
                     * "checksum == sum of the preceding bytes".  Dropping on a mismatch is the
                     * one behaviour guaranteed to break the production head unit path, while a corrupt frame is
                     * still caught by the caller's strict ASCII report-name gate.  Accept it and
                     * say so, bounded so a hostile stream cannot flood logcat. */
                    if (sChecksumMismatches < CHECKSUM_LOG_LIMIT) {
                        sChecksumMismatches++;
                        Log.i(TAG, "A5 5A checksum mismatch (len=" + total + ", sum="
                                + (sum & 0xFF) + ", checksum=" + checksum
                                + ") — accepted on the report name");
                    }
                }
                return payload;
            }
        }

        /**
         * Drops bytes up to the next {@code A5 5A}.  Returns false when no marker is
         * buffered, in which case only the possible first half of a marker is kept.
         */
        private boolean resync() {
            for (int i = 1; i + 1 < mLen; i++) {
                if ((mBuf[i] & 0xFF) == MARKER_0 && (mBuf[i + 1] & 0xFF) == MARKER_1) {
                    drop(i);
                    return true;
                }
            }
            /* Keep a trailing A5 that may be the start of a split marker. */
            if (mLen > 1 && (mBuf[mLen - 1] & 0xFF) == MARKER_0) {
                mBuf[0] = mBuf[mLen - 1];
                mLen = 1;
            } else {
                mLen = 0;
            }
            return false;
        }

        private void drop(int count) {
            if (count <= 0) {
                return;
            }
            if (count >= mLen) {
                mLen = 0;
                return;
            }
            System.arraycopy(mBuf, count, mBuf, 0, mLen - count);
            mLen -= count;
        }
    }
}
