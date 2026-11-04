/*
 * OkcarOS link stack — L4 media: the AirPlay screen-stream sender.
 *
 * Measured wire behaviour (the wire-capture notes §3/§7, the recorded reference session):
 *   - the sender opens a plain TCP connection to the video `dataPort` the receiver
 *     returned in its SETUP answer (measured 45319 on this car — never hard-coded);
 *   - the first payload is an AVCDecoderConfigurationRecord sent **in the clear**;
 *   - every later payload is one H.264 access unit, wrapped in the 128-byte header
 *     ScreenStreamWriter builds.
 *
 * Encryption: the released sender encrypts the plain-frame payloads with
 * AES-128-CTR, key/IV derived from the video SETUP's `streamConnectionID`
 * (AirPlayStreamCrypto, proved against the recorded reference session — the base SETUP's `eiv`/`ekey`
 * are decorative).  The avcC configuration frame stays cleartext.  `setCrypto`
 * is the single switch; without it the stream is cleartext.
 */

package com.okcar.link.airplay;

import com.okcar.link.LinkState;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

public final class ScreenStreamSender {

    private static final String TAG = "screen-stream";

    private final String mHost;
    private final int mPort;
    private final int mWidth;
    private final int mHeight;
    private final LinkState mState = LinkState.get();

    private Socket mSocket;
    private OutputStream mOut;
    private AirPlayStreamCrypto mCrypto;
    private volatile boolean mConnected;
    private volatile boolean mConfigSent;
    private long mFrames;
    private long mBytes;
    private int mConsecutiveFailures;
    private volatile String mError = "";

    public ScreenStreamSender(String host, int port, int width, int height) {
        mHost = host;
        mPort = port;
        mWidth = width;
        mHeight = height;
    }

    /** Attaches the session cipher; pass null for a cleartext stream. */
    public void setCrypto(AirPlayStreamCrypto crypto) {
        mCrypto = crypto;
        if (crypto != null) {
            mState.log(TAG, "frame payloads encrypted (AES-128-CTR, continuous keystream)");
        }
    }

    /** Opens the media connection. Ports come from the SETUP answer, never a constant. */
    public void connect(int timeoutMs) throws IOException {
        final Socket socket = new Socket();
        socket.connect(new InetSocketAddress(mHost, mPort), timeoutMs);
        socket.setTcpNoDelay(true);
        mSocket = socket;
        mOut = new BufferedOutputStream(socket.getOutputStream(), 1 << 16);
        mConnected = true;
        mState.log(TAG, "media connection up: " + mHost + ':' + mPort);
    }

    public boolean isConnected() {
        return mConnected;
    }

    public String error() {
        return mError;
    }

    public long frames() {
        return mFrames;
    }

    public long bytes() {
        return mBytes;
    }

    /**
     * Sends the codec configuration frame that must open the stream.
     *
     * @param sps MediaCodec csd-0 (sequence parameter set)
     * @param pps MediaCodec csd-1 (picture parameter set)
     */
    public synchronized boolean sendConfig(byte[] sps, byte[] pps) throws IOException {
        if (!mConnected) {
            throw new IOException("media connection not open");
        }
        if (sps == null || sps.length == 0 || pps == null || pps.length == 0) {
            // The encoder had not produced csd yet: sending an 11-byte "avcC" with
            // empty parameter sets (measured §12.34) both wastes the one config the
            // receiver waits for and can wedge its decoder.  Wait for real data.
            mState.log(TAG, "avcC deferred: SPS/PPS not available yet");
            return false;
        }
        final byte[] avcC = ScreenStreamWriter.buildAvcC(sps, pps);
        final byte[] frame = ScreenStreamWriter.wrap(avcC, ScreenStreamWriter.TYPE_CONFIG,
                ScreenStreamWriter.nowNanos(), mWidth, mHeight);
        mOut.write(frame);
        mOut.flush();
        mConfigSent = true;
        mState.log(TAG, "avcC sent: " + avcC.length + "B (sps=" + sps.length
                + "B pps=" + pps.length + "B)");
        return true;
    }

    public boolean configSent() {
        return mConfigSent;
    }

    /**
     * Sends one encoded access unit (MediaCodec gives H.264 already length-prefixed,
     * matching the avcC's lengthSizeMinusOne = 3).  With {@link #setCrypto} the
     * payload goes through the session's continuous AES-128-CTR keystream first;
     * the 128-byte header itself stays cleartext, as measured.
     */
    public synchronized void sendFrame(byte[] data, boolean keyFrame, long presentationTimeUs)
            throws IOException {
        if (!mConnected || data == null || data.length == 0) {
            return;
        }
        final long timestampNs = presentationTimeUs > 0 ? presentationTimeUs * 1000L
                : ScreenStreamWriter.nowNanos();
        /* MediaCodec hands out Annex-B access units; the avcC we send declares
         * 4-byte NAL lengths, so convert before encrypting. */
        final byte[] avcc = ScreenStreamWriter.toAvcc(data);
        final byte[] payload = mCrypto == null ? avcc : mCrypto.encrypt(avcc);
        final byte[] frame = ScreenStreamWriter.wrap(payload, ScreenStreamWriter.TYPE_FRAME,
                timestampNs, mWidth, mHeight);
        try {
            mOut.write(frame);
            mOut.flush();
            mConsecutiveFailures = 0;
        } catch (IOException e) {
            /* The dataPort socket dies with the link (measured: "Broken pipe" every
             * frame after a USB re-enumeration).  Give up after a few tries instead
             * of spamming the log forever. */
            if (++mConsecutiveFailures >= 3) {
                mError = String.valueOf(e);
                mState.log(TAG, "media connection dead after " + mConsecutiveFailures
                        + " failed writes: " + e);
                close();
            }
            throw e;
        }
        mFrames++;
        mBytes += frame.length;
        if ((mFrames % 60) == 1) {
            mState.log(TAG, "sent " + mFrames + " frames (" + mBytes + "B), last "
                    + data.length + "B key=" + keyFrame
                    + (mCrypto == null ? " (cleartext)" : " (aes-ctr)"));
        }
    }

    public synchronized void close() {
        mConnected = false;
        try {
            if (mOut != null) {
                mOut.flush();
            }
        } catch (IOException ignored) {
            /* closing anyway */
        }
        try {
            if (mSocket != null) {
                mSocket.close();
            }
        } catch (IOException ignored) {
            /* closing anyway */
        }
        mOut = null;
        mSocket = null;
    }
}
