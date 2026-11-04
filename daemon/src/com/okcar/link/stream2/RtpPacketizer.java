/*
 * OkcarOS link stack — display-2 RTP: H.264 payloader (RFC 6184), single NAL + FU-A.
 *
 * Pure Java (no android.*) so the desktop rtsp2 harness can fragment a real NAL and reassemble it byte for
 * byte without a device.  One instance belongs to one RTP destination stream: the sequence number
 * and SSRC are per-stream state and must not be shared between clients.
 *
 * No fallback shape: a NAL that cannot be packetized (empty, or larger than 65535 FU-A payload
 * fragments would need) throws instead of being dropped silently.
 */
package com.okcar.link.stream2;

import java.util.ArrayList;
import java.util.List;

public final class RtpPacketizer {

    /** Dynamic payload type the SDP advertises. */
    public static final int PAYLOAD_TYPE = 96;
    /** 90 kHz H.264 clock, the unit of the RTP timestamp. */
    public static final int CLOCK_RATE = 90000;
    /** Fragmentation unit header: FU-A. */
    public static final int FU_A = 28;

    private final int mMtu;
    private final int mSsrc;
    private int mSequence;

    /**
     * @param mtu  the largest UDP payload we are willing to emit (the MTU of the RTP packets);
     *             1400 is the value the field uses and the value the harness asserts on
     * @param ssrc random-but-stable synchronisation source for this stream
     */
    public RtpPacketizer(int mtu, int ssrc) {
        if (mtu < 32 || mtu > 65535) {
            throw new IllegalArgumentException("bad MTU: " + mtu);
        }
        mMtu = mtu;
        mSsrc = ssrc;
    }

    public int mtu() {
        return mMtu;
    }

    public int ssrc() {
        return mSsrc;
    }

    public int nextSequence() {
        return mSequence & 0xffff;
    }

    /** Payload bytes available to one FU-A fragment (everything except RTSP/RTP/FU headers). */
    public int maxFragmentPayload() {
        return mMtu - 12 - 2;
    }

    /**
     * Packetizes one NAL unit.
     *
     * @param timestamp 90 kHz RTP timestamp, shared by every packet of the access unit
     * @param nal       the raw NAL unit, without any start code
     * @param marker    whether the last emitted packet carries the marker bit (true when this NAL
     *                  ends its access unit)
     * @return one RTP packet when the NAL fits, otherwise an FU-A run in order
     */
    public List<byte[]> packetize(long timestamp, byte[] nal, boolean marker) {
        if (nal == null || nal.length == 0) {
            throw new IllegalArgumentException("empty NAL unit");
        }
        final List<byte[]> packets = new ArrayList<byte[]>();
        final int ts = (int) (timestamp & 0xffffffffL);
        if (nal.length <= mMtu) {
            final byte[] packet = new byte[12 + nal.length];
            writeRtpHeader(packet, ts, marker);
            System.arraycopy(nal, 0, packet, 12, nal.length);
            packets.add(packet);
            return packets;
        }
        final int maxFragment = maxFragmentPayload();
        final int nalHeader = nal[0] & 0xff;
        final int nri = nalHeader & 0x60;
        final int nalType = nalHeader & 0x1f;
        final int fuIndicator = nri | FU_A;
        int offset = 1;
        while (offset < nal.length) {
            final int size = Math.min(maxFragment, nal.length - offset);
            final boolean start = offset == 1;
            final boolean end = offset + size == nal.length;
            final byte[] packet = new byte[12 + 2 + size];
            /* Only the final packet of the access unit gets the marker bit; intermediate fragments
             * must not, or a client treats every fragment as a complete picture. */
            writeRtpHeader(packet, ts, end && marker);
            packet[12] = (byte) fuIndicator;
            packet[13] = (byte) ((start ? 0x80 : 0x00) | (end ? 0x40 : 0x00) | nalType);
            System.arraycopy(nal, offset, packet, 14, size);
            packets.add(packet);
            offset += size;
        }
        return packets;
    }

    /** One packet, for an access unit that is one NAL (the common case for MediaCodec here). */
    public List<byte[]> packetizeAccessUnit(long timestamp, byte[] nal) {
        return packetize(timestamp, nal, true);
    }

    private void writeRtpHeader(byte[] packet, int timestamp, boolean marker) {
        packet[0] = (byte) 0x80;                       /* V=2, P=0, X=0, CC=0 */
        packet[1] = (byte) ((marker ? 0x80 : 0x00) | PAYLOAD_TYPE);
        packet[2] = (byte) (mSequence >> 8);
        packet[3] = (byte) mSequence;
        packet[4] = (byte) (timestamp >> 24);
        packet[5] = (byte) (timestamp >> 16);
        packet[6] = (byte) (timestamp >> 8);
        packet[7] = (byte) timestamp;
        packet[8] = (byte) (mSsrc >> 24);
        packet[9] = (byte) (mSsrc >> 16);
        packet[10] = (byte) (mSsrc >> 8);
        packet[11] = (byte) mSsrc;
        mSequence = (mSequence + 1) & 0xffff;
    }
}
