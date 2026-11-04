/*
 * OkcarOS link stack — L4 media: the AirPlay audio sender.
 *
 * Measured on the wire (the wire-capture notes §3, the recorded reference session):
 *
 *   UDP to the audio `dataPort` returned by SETUP(100):
 *     12-byte RTP header  80 64 <seq BE16> <timestamp BE32> 00 00 00 00
 *     1440-byte payload   360 frames x 2 channels x 16-bit
 *     sequence +1 per packet, timestamp +360 per packet, SSRC 0, PT 100
 *
 * The payload is **plaintext 16-bit big-endian PCM (L16)** — the stream is not
 * encrypted (proved on the capture: long runs are exactly zero).  Android's
 * AudioRecord hands out little-endian 16-bit, so every sample is byte-swapped.
 *
 * Pure Java apart from the socket, so `packetize()` is host-testable
 * (.car-test/airplay-audio).
 */

package com.okcar.link.airplay;

import com.okcar.link.LinkState;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;

public final class AudioStreamSender {

    private static final String TAG = "audio-stream";

    /** Samples per channel per packet, measured 360 @ 48 kHz. */
    public static final int FRAMES_PER_PACKET = 360;
    /** 2 channels x 16 bit. */
    public static final int BYTES_PER_FRAME = FRAMES_PER_PACKET * 4;
    /** RTP payload type measured on the wire. */
    public static final int PAYLOAD_TYPE = 100;
    public static final int RTP_HEADER_BYTES = 12;

    private final String mHost;
    private final int mPort;
    private final LinkState mState = LinkState.get();

    private DatagramSocket mSocket;
    private InetSocketAddress mTarget;
    private int mSequence = 1;
    private long mTimestamp;
    private long mPackets;
    private long mBytes;
    private int mConsecutiveFailures;
    private volatile boolean mRunning;
    private volatile String mError = "";

    public AudioStreamSender(String host, int port) {
        mHost = host;
        mPort = port;
    }

    /** The audio dataPort we send to (from the SETUP answer, never hard-coded). */
    public int port() {
        return mPort;
    }

    public long packets() {
        return mPackets;
    }

    public long bytes() {
        return mBytes;
    }

    public String error() {
        return mError;
    }

    public void open() throws IOException {
        if (mRunning) {
            return;
        }
        mSocket = new DatagramSocket();
        mTarget = new InetSocketAddress(mHost, mPort);
        mRunning = true;
        mState.log(TAG, "audio RTP -> " + mHost + ':' + mPort + " (PT " + PAYLOAD_TYPE
                + ", " + FRAMES_PER_PACKET + " frames/packet, L16 BE)");
    }

    public boolean isRunning() {
        return mRunning;
    }

    /**
     * Sends one block of captured PCM (little-endian, 48 kHz stereo 16-bit),
     * split into the measured 1440-byte RTP packets.
     */
    public synchronized void send(byte[] pcm, int offset, int length) {
        if (!mRunning || mSocket == null || pcm == null || length <= 0) {
            return;
        }
        int at = Math.max(0, offset);
        final int end = Math.min(pcm.length, at + length);
        while (at < end) {
            final int chunk = Math.min(BYTES_PER_FRAME, end - at);
            final byte[] datagram = packetize(pcm, at, chunk, mSequence, mTimestamp);
            try {
                mSocket.send(new DatagramPacket(datagram, datagram.length, mTarget));
                mPackets++;
                mBytes += datagram.length;
            } catch (IOException e) {
                /* The link can vanish under us (measured: every packet then fails
                 * with ENETUNREACH).  Stop after a few tries instead of spamming. */
                if (++mConsecutiveFailures >= 3) {
                    mError = String.valueOf(e);
                    mState.log(TAG, "audio link dead after " + mConsecutiveFailures
                            + " failed sends: " + e);
                    close();
                }
                return;
            }
            mConsecutiveFailures = 0;
            mSequence = (mSequence + 1) & 0xFFFF;
            mTimestamp += chunk / 4;
            at += chunk;
        }
        if ((mPackets % 200) == 1) {
            mState.log(TAG, "sent " + mPackets + " audio packets (" + mBytes + "B), seq "
                    + mSequence + " ts " + mTimestamp);
        }
    }

    /**
     * Builds one RTP datagram: 12-byte header + payload, little-endian samples
     * swapped to the measured big-endian L16.
     *
     * @param pcmLittleEndian capture buffer
     * @param offset          first byte of this packet's audio
     * @param length          audio bytes (<= {@link #BYTES_PER_FRAME}; the tail is
     *                        zero-padded so every datagram is 1452 bytes, as measured)
     */
    public static byte[] packetize(byte[] pcmLittleEndian, int offset, int length,
                                  int sequence, long timestamp) {
        final byte[] out = new byte[RTP_HEADER_BYTES + BYTES_PER_FRAME];
        out[0] = (byte) 0x80;
        out[1] = (byte) PAYLOAD_TYPE;
        out[2] = (byte) ((sequence >> 8) & 0xFF);
        out[3] = (byte) (sequence & 0xFF);
        out[4] = (byte) ((timestamp >> 24) & 0xFF);
        out[5] = (byte) ((timestamp >> 16) & 0xFF);
        out[6] = (byte) ((timestamp >> 8) & 0xFF);
        out[7] = (byte) (timestamp & 0xFF);
        /* out[8:12] stays zero: the measurement shows SSRC 0. */
        final int count = Math.min(BYTES_PER_FRAME, Math.max(0, length));
        for (int i = 0; i + 1 < count; i += 2) {
            final byte lo = pcmLittleEndian[offset + i];
            final byte hi = pcmLittleEndian[offset + i + 1];
            out[RTP_HEADER_BYTES + i] = hi;         /* big-endian: MSB first */
            out[RTP_HEADER_BYTES + i + 1] = lo;
        }
        return out;
    }

    public synchronized void close() {
        mRunning = false;
        if (mSocket != null) {
            mSocket.close();
            mSocket = null;
        }
        mTarget = null;
    }
}
