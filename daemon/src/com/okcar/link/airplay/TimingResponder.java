/*
 * OkcarOS link stack — L4: the AirPlay timing (NTP-style) responder.
 *
 * Measured on the wire (the wire-capture notes §3, the recorded reference session):
 *
 *   receiver -> sender  32 B: RTP header 80 d2 <seq> 00000000, 12 zero bytes,
 *                       then the origin stamp as NTP 32.32 fixed point (8 B)
 *   sender   -> receiver 1444 B: 80 d3 <seq echoed> 00000000,
 *                       origin (echoed) / receive / transmit, each NTP 32.32,
 *                       then zero padding
 *
 * The payload type is the 7-bit RTP field with the marker bit set: the wire
 * bytes are 0xd2/0xd3 = M | PT 82/83, NOT "PT 210/211".  Comparing the masked
 * field against 210 is what made the first field run answer nothing at all
 * (91 probes arrived, 0 replies, RECORD answered 500 after ~10 s).
 *
 * The receiver keeps its own clock from this exchange, so without a live,
 * correctly-laid-out responder on the `timingPort` we advertise in the base
 * SETUP, the session never reaches a recordable state.
 */

package com.okcar.link.airplay;

import com.okcar.link.LinkState;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketAddress;

public final class TimingResponder {

    private static final String TAG = "timing";
    /** RTP marker/version bits measured on the wire. */
    private static final int RTP_VERSION_2 = 0x80;
    private static final int MARKER_BIT = 0x80;
    /** 7-bit RTP payload types; the wire bytes are 0xd2 (request) / 0xd3 (reply). */
    public static final int PT_TIMING_REQUEST = 0x52;
    public static final int PT_TIMING_REPLY = 0x53;
    /** Reply size measured in the capture. */
    private static final int REPLY_BYTES = 1444;
    /** Seconds between 1900-01-01 and 1970-01-01 (NTP epoch offset). */
    private static final long NTP_EPOCH_OFFSET = 2208988800L;

    private final DatagramSocket mSocket;
    private final LinkState mState = LinkState.get();
    private volatile boolean mRunning;
    private Thread mThread;
    private volatile long mRequests;
    private volatile String mError = "";

    public TimingResponder(DatagramSocket socket) {
        mSocket = socket;
    }

    public void start() {
        if (mRunning) {
            return;
        }
        mRunning = true;
        mThread = new Thread(new Runnable() {
            @Override
            public void run() {
                loop();
            }
        }, "okcar-airplay-timing");
        mThread.setDaemon(true);
        mThread.start();
        mState.log(TAG, "timing responder on port " + mSocket.getLocalPort());
    }

    private void loop() {
        final byte[] in = new byte[2048];
        while (mRunning) {
            try {
                final DatagramPacket packet = new DatagramPacket(in, in.length);
                mSocket.receive(packet);
                final int length = packet.getLength();
                if (length < 12 || (in[0] & 0xFF) != RTP_VERSION_2) {
                    continue;
                }
                final int payloadType = in[1] & 0x7F;
                if (payloadType != PT_TIMING_REQUEST) {
                    continue;
                }
                reply(packet.getSocketAddress(), in, length);
            } catch (IOException e) {
                if (mRunning) {
                    mError = String.valueOf(e);
                    mState.log(TAG, "timing receive failed: " + e);
                }
                break;
            }
        }
    }

    /**
     * Answers one timing request with the measured reply layout:
     * {@code 80 d3 <seq> 00000000 | origin | receive | transmit | zeros}.
     *
     * The request carries the origin at its last 8 bytes; the reply echoes it at
     * offset 8 (NOT at 12 — the reference sender's stamps start right after the
     * 8-byte header), followed by our receive and transmit stamps.
     */
    private void reply(SocketAddress to, byte[] request, int length) throws IOException {
        final byte[] out = new byte[REPLY_BYTES];
        out[0] = (byte) RTP_VERSION_2;
        out[1] = (byte) (MARKER_BIT | PT_TIMING_REPLY);
        out[2] = request[2];
        out[3] = request[3];
        /* out[4:8] stays zero: measured 00 00 00 00. */
        final long receive = ntpNow();
        long origin = 0L;
        if (length >= 8) {
            for (int i = length - 8; i < length; i++) {
                origin = (origin << 8) | (request[i] & 0xFFL);
            }
        }
        putNtp(out, 8, origin);
        putNtp(out, 16, receive);
        putNtp(out, 24, ntpNow());
        final DatagramPacket reply = new DatagramPacket(out, out.length, to);
        mSocket.send(reply);
        mRequests++;
        if ((mRequests % 20) == 1) {
            mState.log(TAG, "timing replies sent: " + mRequests);
        }
    }

    /** NTP 32.32 fixed point: seconds since 1900 in the high half. */
    private static long ntpNow() {
        final long nowMs = System.currentTimeMillis();
        final long seconds = nowMs / 1000L + NTP_EPOCH_OFFSET;
        final long fraction = (nowMs % 1000L) * 0x100000000L / 1000L;
        return (seconds << 32) | (fraction & 0xFFFFFFFFL);
    }

    private static void putNtp(byte[] out, int offset, long value) {
        out[offset] = (byte) (value >>> 56);
        out[offset + 1] = (byte) (value >>> 48);
        out[offset + 2] = (byte) (value >>> 40);
        out[offset + 3] = (byte) (value >>> 32);
        out[offset + 4] = (byte) (value >>> 24);
        out[offset + 5] = (byte) (value >>> 16);
        out[offset + 6] = (byte) (value >>> 8);
        out[offset + 7] = (byte) value;
    }

    public long requests() {
        return mRequests;
    }

    public String error() {
        return mError;
    }

    public void stop() {
        mRunning = false;
        final Thread thread = mThread;
        if (thread != null) {
            thread.interrupt();
            mThread = null;
        }
    }
}
