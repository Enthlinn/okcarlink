/*
 * OkcarOS link stack — L4 media: the AirPlay screen-stream frame wrapper.
 *
 * Measured on the wire (the wire-capture notes §7, the recorded reference session):
 *
 *   frame := header(128 B) || payload
 *
 *   [ 0: 4] payload length, little endian
 *   [ 4: 8] payload type/flags  (codec-config frame = 01 00 14 01, plain frame = 0,
 *                               all-zero heartbeat frame = 05 00 00 00)
 *   [ 8:16] timestamp, 64-bit little endian nanoseconds
 *   [16:20] width  as little-endian float32 (measured 1920.0 -> 00 00 f0 44)
 *   [20:24] height as little-endian float32 (measured  720.0 -> 00 00 34 44)
 *   [24:128] zero (some frames repeat the width/height pair here)
 *
 * The first payload the sender puts on the video connection is an
 * AVCDecoderConfigurationRecord (avcC) sent **in the clear**; every later frame
 * carries an encrypted payload when the session negotiated encryption.  Our own
 * sender defaults to no encryption (see AirPlaySession.withEncryption) — if the
 * receiver accepts a cleartext H.264 stream, the entire key-exchange problem
 * disappears.
 *
 * Pure Java, no android.* imports, so it is unit-testable on the host JVM.
 */

package com.okcar.link.airplay;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class ScreenStreamWriter {

    /** Fixed header size measured on the wire. */
    public static final int HEADER_SIZE = 128;

    /** Payload type of the codec-configuration frame (avcC), from the capture. */
    public static final int TYPE_CONFIG = 0x01140001;

    /** Payload type of a plain video frame. */
    public static final int TYPE_FRAME = 0;

    /** Payload type of the 194-byte all-zero heartbeat frame seen in the capture. */
    public static final int TYPE_HEARTBEAT = 0x05;

    private ScreenStreamWriter() {
    }

    /**
     * Builds one frame: 128-byte header followed by {@code payload}.
     *
     * @param payload     media bytes (avcC for the config frame, H.264 for frames)
     * @param type        {@link #TYPE_CONFIG}, {@link #TYPE_FRAME} or {@link #TYPE_HEARTBEAT}
     * @param timestampNs 64-bit nanosecond timestamp the peer uses for sync
     * @param width       display width, written as little-endian float32
     * @param height      display height, written as little-endian float32
     */
    public static byte[] wrap(byte[] payload, int type, long timestampNs, int width, int height) {
        final byte[] out = new byte[HEADER_SIZE + (payload == null ? 0 : payload.length)];
        final ByteBuffer buf = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(0, payload == null ? 0 : payload.length);          // [0:4] length (LE)
        buf.putInt(4, type);                                          // [4:8] type/flags
        buf.putLong(8, timestampNs);                                  // [8:16] ns (LE)
        /* width/height: the measured bytes are 00 00 f0 44 (= 1920.0f as a
         * little-endian float32), so write the bits least-significant first. */
        putLittleEndianFloat(out, 16, width);
        putLittleEndianFloat(out, 20, height);
        if (payload != null) {
            System.arraycopy(payload, 0, out, HEADER_SIZE, payload.length);
        }
        return out;
    }

    private static void putLittleEndianFloat(byte[] out, int offset, float value) {
        final int bits = Float.floatToIntBits(value);
        out[offset] = (byte) bits;
        out[offset + 1] = (byte) (bits >>> 8);
        out[offset + 2] = (byte) (bits >>> 16);
        out[offset + 3] = (byte) (bits >>> 24);
    }

    /**
     * Builds the AVCDecoderConfigurationRecord that opens the video stream.
     *
     * MediaCodec hands out csd-0/csd-1 in Annex-B form (leading 00 00 00 01), while
     * the record stores bare NALs — the measured reference avcC starts
     * {@code 01 64 00 1f ff e1 00 12 67 64 ...}, i.e. the SPS begins at its NAL
     * header 0x67, not at a start code.  Strip one when present.
     *
     * @param sps sequence parameter set from MediaCodec csd-0
     * @param pps picture parameter set from MediaCodec csd-1
     */
    public static byte[] buildAvcC(byte[] sps, byte[] pps) {
        final byte[] s = stripStartCode(sps);
        final byte[] p = stripStartCode(pps);
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(1);                                   // configurationVersion
        out.write(s != null && s.length > 1 ? s[1] & 0xFF : 0x64); // AVCProfileIndication
        out.write(s != null && s.length > 2 ? s[2] & 0xFF : 0x00); // profile_compatibility
        out.write(s != null && s.length > 3 ? s[3] & 0xFF : 0x1F); // AVCLevelIndication
        out.write(0xFF);                                // 6 bits reserved | lengthSizeMinusOne = 3
        out.write(0xE1);                                // 3 bits reserved | numOfSPS = 1
        writeU16(out, s == null ? 0 : s.length);
        if (s != null) {
            out.write(s, 0, s.length);
        }
        out.write(1);                                   // numOfPPS
        writeU16(out, p == null ? 0 : p.length);
        if (p != null) {
            out.write(p, 0, p.length);
        }
        return out.toByteArray();
    }

    /** Drops a leading 3- or 4-byte Annex-B start code, if the buffer has one. */
    public static byte[] stripStartCode(byte[] nal) {
        if (nal == null) {
            return null;
        }
        final int at = startCodeLength(nal, 0);
        if (at <= 0) {
            return nal;
        }
        final byte[] out = new byte[nal.length - at];
        System.arraycopy(nal, at, out, 0, out.length);
        return out;
    }

    /** Length of the Annex-B start code at {@code offset}, or 0 when there is none. */
    private static int startCodeLength(byte[] data, int offset) {
        if (offset + 3 <= data.length && data[offset] == 0 && data[offset + 1] == 0) {
            if (data[offset + 2] == 1) {
                return 3;
            }
            if (offset + 4 <= data.length && data[offset + 2] == 0 && data[offset + 3] == 1) {
                return 4;
            }
        }
        return 0;
    }

    /**
     * Converts one MediaCodec access unit to the length-prefixed form the avcC
     * declares ({@code lengthSizeMinusOne = 3}) — the measured sender puts exact
     * AVCC chains on the wire.  Buffers that carry no start code are returned
     * unchanged, so an encoder that already emits AVCC is safe too.
     */
    public static byte[] toAvcc(byte[] annexB) {
        if (annexB == null || annexB.length == 0) {
            return annexB;
        }
        if (startCodeLength(annexB, 0) == 0 && !hasStartCodeLater(annexB)) {
            return annexB;
        }
        final ByteArrayOutputStream out = new ByteArrayOutputStream(annexB.length + 16);
        int i = 0;
        while (i < annexB.length) {
            final int at = startCodeLength(annexB, i);
            if (at > 0) {
                i += at;
                final int nalStart = i;
                while (i < annexB.length && startCodeLength(annexB, i) == 0) {
                    i++;
                }
                final int nalLength = i - nalStart;
                if (nalLength > 0) {
                    out.write((nalLength >>> 24) & 0xFF);
                    out.write((nalLength >>> 16) & 0xFF);
                    out.write((nalLength >>> 8) & 0xFF);
                    out.write(nalLength & 0xFF);
                    out.write(annexB, nalStart, nalLength);
                }
            } else {
                i++;
            }
        }
        return out.toByteArray();
    }

    private static boolean hasStartCodeLater(byte[] data) {
        for (int i = 1; i + 3 <= data.length; i++) {
            if (startCodeLength(data, i) > 0) {
                return true;
            }
        }
        return false;
    }

    private static void writeU16(ByteArrayOutputStream out, int value) {
        out.write((value >>> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    /** Writes one wrapped frame to a stream (the negotiated video dataPort socket). */
    public static void writeFrame(OutputStream out, byte[] payload, int type, long timestampNs,
                                  int width, int height) throws IOException {
        out.write(wrap(payload, type, timestampNs, width, height));
        out.flush();
    }

    /**
     * Current time as the 64-bit nanosecond timestamp the header carries.
     *
     * <p>{@code elapsedRealtimeNanos} (device uptime, suspend included), not {@code nanoTime}:
     * {@link WirelessScreenHeader#timestamp3232} documents that the real sender's values decode to a
     * device uptime, and the two clocks drift apart by every suspend the phone takes.  A timestamp
     * the accessory reads as "in the future" is exactly what makes it answer "Delaying decode".
     */
    public static long nowNanos() {
        return android.os.SystemClock.elapsedRealtimeNanos();
    }
}
