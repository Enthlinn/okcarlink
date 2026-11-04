/*
 * OkcarOS link stack — display 2: the H.264 encoder that renders that display into its input
 * surface.
 *
 * The encoder is the RTP stream's only source (the mirroring stack's own encoder has its own
 * session and is untouched).  It is H.264 only: if `video/avc` cannot be created the failure is
 * raised and the caller refuses the whole `cast2 up`; there is deliberately no HEVC/software
 * fallback, because a silent codec switch is exactly what leaves an operator with a stream that
 * "works" and a client that shows nothing.
 *
 * NAL parsing is start-code scanning rather than a CODECCONFIG flag check: MediaCodec's AVC output
 * is Annex-B and may put several NAL units (SEI + IDR, or SPS/PPS/IDR for a client that joined
 * mid-stream) into one buffer, and a stored frame must be emitted whole for the FU-A packetizer to
 * see the real picture boundary.
 */
package com.okcar.link.stream2;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.util.Log;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public final class Cast2Encoder {

    public static final String MIME = "video/avc";
    private static final String TAG = "okcar-cast2";
    /** MediaCodec dequeue timeouts; small so a stop is observed promptly. */
    private static final long DEQUEUE_TIMEOUT_US = 10_000L;
    /** A NAL bigger than this is refused with a log instead of being buffered unbounded. */
    private static final int MAX_NAL_BYTES = 1 << 20;

    private final int mWidth;
    private final int mHeight;
    private final int mBitrateBps;
    private final int mFps;
    private final FrameHub mHub;

    private MediaCodec mCodec;
    private Surface mInputSurface;
    private Thread mThread;
    private volatile boolean mRunning;
    private volatile long mFrames;
    private volatile long mKeyFrames;
    private volatile long mDropped;
    private volatile long mBytes;
    private volatile String mFailure;

    public Cast2Encoder(int width, int height, int bitrateBps, int fps, FrameHub hub) {
        mWidth = width;
        mHeight = height;
        mBitrateBps = bitrateBps;
        mFps = fps;
        mHub = hub;
    }

    /**
     * Creates the codec and its input surface, then starts draining.
     *
     * @throws IllegalStateException when `video/avc` is unavailable or misconfigured.  The message
     *     says which precondition failed; nothing is substituted for it.
     */
    public synchronized void start() {
        if (mCodec != null) {
            throw new IllegalStateException("encoder already started");
        }
        final MediaFormat format = MediaFormat.createVideoFormat(MIME, mWidth, mHeight);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, mBitrateBps);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, mFps);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        format.setInteger(MediaFormat.KEY_BITRATE_MODE,
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR);
        final MediaCodec codec;
        try {
            codec = MediaCodec.createEncoderByType(MIME);
        } catch (Throwable t) {
            throw new IllegalStateException("this device has no " + MIME + " encoder: " + t, t);
        }
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            mInputSurface = codec.createInputSurface();
            codec.start();
        } catch (Throwable t) {
            try {
                codec.release();
            } catch (Throwable ignored) {
                /* best effort */
            }
            throw new IllegalStateException("cannot start the " + MIME + " encoder "
                    + mWidth + 'x' + mHeight + " at " + mBitrateBps + "bps: " + t, t);
        }
        mCodec = codec;
        mRunning = true;
        mThread = new Thread(this::drain, "okcar-cast2-encode");
        mThread.setDaemon(true);
        mThread.start();
        Log.i(TAG, "encoder started: " + MIME + ' ' + mWidth + 'x' + mHeight
                + " bitrate=" + mBitrateBps + " fps=" + mFps);
    }

    /** The surface display 2 must render into. */
    public Surface inputSurface() {
        return mInputSurface;
    }

    private void drain() {
        final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (mRunning) {
            final MediaCodec codec = mCodec;
            if (codec == null) {
                return;
            }
            try {
                final int index = codec.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US);
                if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    continue;
                }
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    onFormatChanged(codec.getOutputFormat());
                    continue;
                }
                if (index < 0) {
                    continue;
                }
                final ByteBuffer buffer = codec.getOutputBuffer(index);
                if (info.size > 0 && buffer != null) {
                    final byte[] chunk = new byte[info.size];
                    buffer.position(info.offset);
                    buffer.limit(info.offset + info.size);
                    buffer.get(chunk);
                    emitAccessUnit(chunk, info.presentationTimeUs, (info.flags
                            & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0);
                }
                codec.releaseOutputBuffer(index, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    Log.i(TAG, "encoder reported end of stream");
                    return;
                }
            } catch (Throwable t) {
                fail("encoder drain failed: " + t);
                return;
            }
        }
    }

    /** Splits one Annex-B buffer into complete access units and publishes each of them whole. */
    private void emitAccessUnit(byte[] chunk, long ptsUs, boolean keyframe) {
        final List<int[]> spans = nalSpans(chunk);
        if (spans.isEmpty()) {
            mDropped++;
            Log.w(TAG, "encoder buffer of " + chunk.length + "B has no NAL start code; dropped");
            return;
        }
        for (int i = 0; i < spans.size(); i++) {
            final int start = spans.get(i)[0];
            final int end = spans.get(i)[1];
            final int length = end - start;
            if (length <= 0) {
                mDropped++;
                continue;
            }
            if (length > MAX_NAL_BYTES) {
                mDropped++;
                mHub.fail("encoded NAL of " + length + "B exceeds " + MAX_NAL_BYTES + 'B');
                return;
            }
            final byte[] nal = new byte[length];
            System.arraycopy(chunk, start, nal, 0, length);
            mHub.publish(ptsUs, nal, keyframe && i == spans.size() - 1);
            mFrames++;
            mBytes += length;
            if (keyframe) {
                mKeyFrames++;
            }
        }
    }

    /**
     * Byte spans of the NAL units in an Annex-B buffer: each span starts at the NAL header byte
     * (after the 3- or 4-byte start code) and ends where the next start code begins.
     */
    static List<int[]> nalSpans(byte[] data) {
        final List<int[]> spans = new ArrayList<int[]>();
        int offset = 0;
        int current = -1;
        while (offset + 2 < data.length) {
            if (data[offset] == 0 && data[offset + 1] == 0 && data[offset + 2] == 1) {
                if (current >= 0) {
                    spans.add(new int[]{current, offset});
                }
                offset += 3;
                current = offset;
                continue;
            }
            offset++;
        }
        if (current >= 0 && current < data.length) {
            spans.add(new int[]{current, data.length});
        }
        return spans;
    }

    private void onFormatChanged(MediaFormat format) {
        try {
            final byte[] sps = firstNal(format.getByteBuffer("csd-0"));
            final byte[] pps = firstNal(format.getByteBuffer("csd-1"));
            if (sps == null || pps == null) {
                fail("encoder output format carries no csd-0/csd-1 parameter sets");
                return;
            }
            mHub.setParameterSets(sps, pps);
            Log.i(TAG, "encoder parameter sets: SPS " + sps.length + "B, PPS " + pps.length + 'B');
        } catch (Throwable t) {
            fail("cannot read the encoder's output format: " + t);
        }
    }

    /** csd-0/csd-1 are usually Annex-B; the SDP and the in-band SEI-less AU want the bare NAL. */
    static byte[] firstNal(ByteBuffer buffer) {
        if (buffer == null) {
            return null;
        }
        final ByteBuffer copy = buffer.duplicate();
        final byte[] raw = new byte[copy.remaining()];
        copy.get(raw);
        final List<int[]> spans = nalSpans(raw);
        if (spans.isEmpty()) {
            return raw.length == 0 ? null : raw;
        }
        final int[] span = spans.get(0);
        final byte[] nal = new byte[span[1] - span[0]];
        System.arraycopy(raw, span[0], nal, 0, nal.length);
        return nal;
    }

    /** Stops the drain thread and releases the codec and its surface. */
    public synchronized void release() {
        mRunning = false;
        final Thread thread = mThread;
        mThread = null;
        if (thread != null) {
            try {
                thread.join(1500L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        final MediaCodec codec = mCodec;
        mCodec = null;
        if (codec != null) {
            try {
                codec.stop();
            } catch (Throwable t) {
                Log.w(TAG, "encoder stop: " + t);
            }
            try {
                codec.release();
            } catch (Throwable t) {
                Log.w(TAG, "encoder release: " + t);
            }
        }
        final Surface surface = mInputSurface;
        mInputSurface = null;
        if (surface != null) {
            try {
                surface.release();
            } catch (Throwable t) {
                Log.w(TAG, "encoder surface release: " + t);
            }
        }
        Log.i(TAG, "encoder released after " + mFrames + " frames");
    }

    private void fail(String reason) {
        mFailure = reason;
        mRunning = false;
        Log.w(TAG, reason);
        mHub.fail(reason);
    }

    public boolean running() {
        return mRunning;
    }

    public long frames() {
        return mFrames;
    }

    public long keyFrames() {
        return mKeyFrames;
    }

    public long dropped() {
        return mDropped;
    }

    public long bytes() {
        return mBytes;
    }

    public int bitrateBps() {
        return mBitrateBps;
    }

    public int fps() {
        return mFps;
    }

    public int width() {
        return mWidth;
    }

    public int height() {
        return mHeight;
    }

    public String failure() {
        return mFailure;
    }
}
