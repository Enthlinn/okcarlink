/*
 * OkcarOS link stack — L3: iAP2 link-layer frame codec.
 *
 * Recovered from the head unit's libiPodForiAP2.so (exeed findings A §5.8.1) and
 * matching the magic numbers our kernel HID tunnel sees:
 *
 *   byte 0  SOP1   = 0xFF
 *   byte 1  SOP2   = 0x5A (normal) | 0x55 (DETECT)
 *   byte 2  LEN1   \  big-endian TOTAL length (header + payload + checksum)
 *   byte 3  LEN2   /
 *   byte 4  CTRL
 *   byte 5  SEQ
 *   byte 6  ACK
 *   byte 7  SESSID
 *   byte 8  HDRCHK = two's complement of the 8-bit sum of bytes 0..7
 *   byte 9+ payload
 *   last    PAYLOADCHK = two's complement of the 8-bit sum of the payload
 *
 * Field order and the 9-byte header-only case were settled by disassembling the
 * the production head unit head unit's x86_64 libiPodForiAP2.so (exeed carplay-re, §12.28):
 *
 *   iAP2PacketCreate @0x4bd10   [4]=ctrl [5]=arg3 [6]=arg4 [7]=arg7(session)
 *   iAP2PacketCreateACKPacket   [5]=own seq, [6]=the acked seq
 *   iAP2PacketCreateRSTPacket   len = 9, [8] = 0 (filled by CalcHeaderChecksum)
 *   iAP2PacketCalcHeaderChecksum @0x4c930  [8] = -(sum of bytes 0..7)
 *   iAP2PacketCalcPayloadChecksum @0x4c9e0 payload starts at 9, length = LEN-10
 *
 * So: a frame with NO payload is exactly 9 bytes long (no payload checksum byte
 * at all), and payload length is LEN - 10 only when LEN > 9.  LEN == 0x200 and
 * LEN == 0x400 are magic: they mark the 6-byte DETECT packet (ff 55 02 00 ee 10)
 * and the 8-byte "DETECT BAD" packet (ff 55 04 00 02 04 ee 08).
 *
 * CTRL bits live in Iap2Ctl.
 */

package com.okcar.link.iap2;

import com.okcar.link.util.Hex;

import java.util.Arrays;

public final class Iap2Frame {

    public static final int SOP1 = 0xFF;
    public static final int SOP2_NORMAL = 0x5A;
    public static final int SOP2_DETECT = 0x55;

    public static final int HEADER_LEN = 9;
    /** Trailing payload checksum; absent when the frame carries no payload. */
    public static final int CHECKSUM_LEN = 1;
    /** A header-only frame (SYN/RST/ACK with no payload) is legal at 9 bytes. */
    public static final int MIN_TOTAL_LEN = HEADER_LEN;

    public static final int MAGIC_DETECT_LEN = 0x200;
    public static final int MAGIC_DETECT_BAD_LEN = 0x400;
    public static final int DETECT_LEN = 6;
    public static final int DETECT_BAD_LEN = 8;

    public static final int KIND_DETECT = 1;
    public static final int KIND_DETECT_BAD = 2;
    public static final int KIND_DATA = 3;

    /** The raw iAP2 DETECT packet (kIap2PacketDetectData in the head unit). */
    private static final byte[] DETECT = {
            (byte) 0xFF, (byte) 0x55, 0x02, 0x00, (byte) 0xEE, 0x10,
    };

    /** kIap2PacketDetectBadData — the "you are not an iAP2 accessory" reply. */
    private static final byte[] DETECT_BAD = {
            (byte) 0xFF, (byte) 0x55, 0x04, 0x00, 0x02, 0x04, (byte) 0xEE, 0x08,
    };

    public final int kind;
    public final int sop2;
    public final int lenField;
    public final int ctrl;
    public final int session;
    public final int seq;
    public final int ack;
    public final byte[] payload;
    public final byte[] raw;
    public final boolean headerChecksumOk;
    public final boolean payloadChecksumOk;

    private Iap2Frame(int kind, int sop2, int lenField, int ctrl, int session, int seq, int ack,
            byte[] payload, byte[] raw, boolean headerChecksumOk, boolean payloadChecksumOk) {
        this.kind = kind;
        this.sop2 = sop2;
        this.lenField = lenField;
        this.ctrl = ctrl;
        this.session = session;
        this.seq = seq;
        this.ack = ack;
        this.payload = payload;
        this.raw = raw;
        this.headerChecksumOk = headerChecksumOk;
        this.payloadChecksumOk = payloadChecksumOk;
    }

    /* ------------------------------------------------------------- checksums */

    /** 8-bit sum of a byte range (iAP2 verifies by adding and expecting 0). */
    public static int sum(byte[] data, int offset, int length) {
        int sum = 0;
        for (int i = offset; i < offset + length; i++) {
            sum += data[i] & 0xFF;
        }
        return sum & 0xFF;
    }

    /** Two's complement of the 8-bit sum — the one checksum iAP2 uses. */
    public static byte checksum(byte[] data, int offset, int length) {
        return (byte) (0 - sum(data, offset, length));
    }

    /* ---------------------------------------------------------------- encode */

    public static byte[] encodeDetect() {
        return DETECT.clone();
    }

    public static byte[] encodeDetectBad() {
        return DETECT_BAD.clone();
    }

    public static boolean isDetectPacket(byte[] data, int offset, int length) {
        return matches(data, offset, length, DETECT);
    }

    public static boolean isDetectBadPacket(byte[] data, int offset, int length) {
        return matches(data, offset, length, DETECT_BAD);
    }

    private static boolean matches(byte[] data, int offset, int length, byte[] expected) {
        if (data == null || offset < 0 || length < expected.length
                || offset + expected.length > data.length) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            if (data[offset + i] != expected[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * Builds a complete link-layer frame.
     *
     * The parameter order follows the wire order (ctrl, seq, ack, session) on
     * purpose: getting this transposed is exactly the bug this code used to have.
     *
     * @param ctrl    control byte (see {@link Iap2Ctl})
     * @param seq     sequence number
     * @param ack     acknowledgement number
     * @param session session id
     * @param payload payload bytes, may be null/empty (then the frame is 9 bytes)
     */
    public static byte[] encode(int ctrl, int seq, int ack, int session,
            byte[] payload, int payloadOffset, int payloadLength) {
        final int length = Math.max(0, payloadLength);
        final boolean hasPayload = length > 0 && payload != null;
        final int total = hasPayload ? HEADER_LEN + length + CHECKSUM_LEN : HEADER_LEN;
        final byte[] out = new byte[total];
        out[0] = (byte) SOP1;
        out[1] = (byte) SOP2_NORMAL;
        out[2] = (byte) ((total >> 8) & 0xFF);
        out[3] = (byte) (total & 0xFF);
        out[4] = (byte) ctrl;
        out[5] = (byte) seq;
        out[6] = (byte) ack;
        out[7] = (byte) session;
        out[8] = checksum(out, 0, 8);
        if (hasPayload) {
            System.arraycopy(payload, payloadOffset, out, HEADER_LEN, length);
            out[total - 1] = checksum(out, HEADER_LEN, length);
        }
        return out;
    }

    public static byte[] encode(int ctrl, int seq, int ack, int session, byte[] payload) {
        return encode(ctrl, seq, ack, session, payload, 0,
                payload == null ? 0 : payload.length);
    }

    /* ----------------------------------------------------------------- parse */

    /**
     * Returns the total wire length of the frame starting at {@code offset}.
     *
     * @return the length in bytes; 0 when the header is not complete yet;
     *         -1 when the bytes cannot be a frame (the caller should resync by
     *         skipping one byte).
     */
    public static int peekTotalLen(byte[] buffer, int offset, int available) {
        if (buffer == null || offset < 0 || available < 4) {
            return 0;
        }
        if ((buffer[offset] & 0xFF) != SOP1) {
            return -1;
        }
        final int sop2 = buffer[offset + 1] & 0xFF;
        if (sop2 != SOP2_NORMAL && sop2 != SOP2_DETECT) {
            return -1;
        }
        final int len = ((buffer[offset + 2] & 0xFF) << 8) | (buffer[offset + 3] & 0xFF);
        if (len == MAGIC_DETECT_LEN) {
            return DETECT_LEN;
        }
        if (len == MAGIC_DETECT_BAD_LEN) {
            return DETECT_BAD_LEN;
        }
        if (len < MIN_TOTAL_LEN) {
            return -1;
        }
        return len;
    }

    /**
     * Parses one frame.  Returns null when the buffer does not (yet) hold a
     * complete, well-formed frame — callers should use {@link #peekTotalLen}
     * to decide whether to wait for more bytes.
     */
    public static Iap2Frame parse(byte[] buffer, int offset, int available) {
        final int total = peekTotalLen(buffer, offset, available);
        if (total <= 0 || available < total || offset + total > buffer.length) {
            return null;
        }
        final int sop2 = buffer[offset + 1] & 0xFF;
        final int lenField = ((buffer[offset + 2] & 0xFF) << 8) | (buffer[offset + 3] & 0xFF);
        final byte[] raw = Arrays.copyOfRange(buffer, offset, offset + total);

        if (total == DETECT_LEN || total == DETECT_BAD_LEN) {
            final boolean bad = isDetectBadPacket(buffer, offset, available);
            final boolean good = isDetectPacket(buffer, offset, available);
            if (!bad && !good) {
                return null;
            }
            return new Iap2Frame(bad ? KIND_DETECT_BAD : KIND_DETECT, sop2, lenField,
                    0, 0, 0, 0, new byte[0], raw, true, true);
        }

        final int ctrl = buffer[offset + 4] & 0xFF;
        final int seq = buffer[offset + 5] & 0xFF;
        final int ack = buffer[offset + 6] & 0xFF;
        final int session = buffer[offset + 7] & 0xFF;
        final byte hdrChk = buffer[offset + 8];
        final boolean hasPayload = total > HEADER_LEN;
        final int payloadLength = hasPayload ? total - HEADER_LEN - CHECKSUM_LEN : 0;
        final byte[] payload = Arrays.copyOfRange(buffer, offset + HEADER_LEN,
                offset + HEADER_LEN + payloadLength);
        final byte expectedPayloadChk = hasPayload ? buffer[offset + total - 1] : 0;

        final boolean headerOk = (((hdrChk & 0xFF) + sum(buffer, offset, 8)) & 0xFF) == 0;
        final boolean payloadOk = !hasPayload || (((expectedPayloadChk & 0xFF)
                + sum(buffer, offset + HEADER_LEN, payloadLength)) & 0xFF) == 0;

        return new Iap2Frame(KIND_DATA, sop2, lenField, ctrl, session, seq, ack,
                payload, raw, headerOk, payloadOk);
    }

    /* --------------------------------------------------------------- display */

    public boolean isData() {
        return kind == KIND_DATA;
    }

    public String describe() {
        if (kind != KIND_DATA) {
            return (kind == KIND_DETECT ? "DETECT" : "DETECT-BAD")
                    + " [" + Hex.encode(raw) + ']';
        }
        final StringBuilder sb = new StringBuilder();
        sb.append(Iap2Ctl.name(ctrl, payload.length > 0))
                .append(" sess=").append(session)
                .append(" seq=").append(seq)
                .append(" ack=").append(ack)
                .append(" len=").append(payload.length);
        if (!headerChecksumOk || !payloadChecksumOk) {
            sb.append(" [checksum header=").append(headerChecksumOk)
                    .append(" payload=").append(payloadChecksumOk).append(']');
        }
        if (payload.length > 0) {
            sb.append(" payload=").append(Hex.encode(payload, 0, Math.min(payload.length, 32)));
            if (payload.length > 32) {
                sb.append(" …");
            }
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return "Iap2Frame{" + describe() + '}';
    }
}
