/*
 * OkcarOS link stack — L4 media plane: the Android Opus encoder behind the wireless audio path.
 *
 * MIME `audio/opus` is the payload the receiver's AltAudio jitter buffer expects.  This device
 * carries the AOSP software encoder
 * (`c2.android.opus.encoder`, media_codecs_google_c2_audio.xml: sample-rate ... 48000,
 * channel-count max 2, bitrate 500-512000), so 48000 Hz mono at 128 kbps is inside every limit.
 *
 * Failure is loud on purpose: {@link #create()} throws when no encoder exists or when it will
 * not start, and the caller stops the audio stream instead of falling back to PCM — the receiver
 * resolves the stream format from the SETUP bits, and a PCM payload on an
 * `audioFormat=0x40000000` stream would never be fed to its decoder.
 */
package com.okcar.link.media;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.util.Log;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public final class OpusAudioEncoder implements AudioEncoder {

    public static final String MIME = "audio/opus";

    private static final String TAG = "OkCarLink/Opus";

    /** How long to wait for a codec buffer before retrying; see {@link #STARVE_RETRIES}. */
    private static final long DEQUEUE_TIMEOUT_US = 20_000L;

    /**
     * How many extra {@link #DEQUEUE_TIMEOUT_US} waits one frame gets when the codec has no input
     * buffer ready.  Five retries is ~100 ms of patience for a 20 ms frame.
     */
    private static final int STARVE_RETRIES = 5;

    /**
     * Frames that may be dropped back-to-back before the encoder is called wedged (~4 s at 20 ms).
     *
     * <p>This tolerance exists because starvation used to be fatal and that killed whole sessions:
     * measured on 217, {@code opus encoder gave no input buffer within 20 ms} fired roughly every
     * 45 s, the audio sender recorded it as its error and stopped, and the receiver tore the AirPlay
     * session down with {@code iAP2 link ended} — the "periodic disconnect" that was blamed on the
     * receiver.  A codec that is merely busy for a few frames must cost a few dropped frames, not a
     * reconnection; only sustained starvation is a real failure.
     */
    private static final int STARVE_FRAMES_BEFORE_FAIL = 200;

    private final MediaCodec mCodec;
    private final String mCodecName;
    private final int mBitrateBps;

    /** Partial 20 ms frame carried between calls. */
    private final byte[] mCarry = new byte[FRAME_BYTES];
    private int mCarryUsed;
    private long mFramesQueued;
    private long mPacketsOut;
    /** Consecutive frames dropped because the codec handed out no input buffer. */
    private int mStarvedFrames;
    private long mDroppedFrames;
    private boolean mClosed;

    private final MediaCodec.BufferInfo mInfo = new MediaCodec.BufferInfo();

    private OpusAudioEncoder(MediaCodec codec, String codecName, int bitrateBps) {
        mCodec = codec;
        mCodecName = codecName;
        mBitrateBps = bitrateBps;
    }

    /** The `c2.android.opus.encoder`-style codec name, or null when the device has none. */
    public static String findEncoderName() {
        try {
            final MediaCodecList list = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
            for (MediaCodecInfo info : list.getCodecInfos()) {
                if (!info.isEncoder()) {
                    continue;
                }
                for (String type : info.getSupportedTypes()) {
                    if (MIME.equalsIgnoreCase(type)) {
                        return info.getName();
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "MediaCodecList probe failed: " + t);
        }
        return null;
    }

    public static boolean isAvailable() {
        return findEncoderName() != null;
    }

    /** Encoder at {@link AudioEncoder#DEFAULT_BITRATE_BPS}. */
    public static OpusAudioEncoder create() throws IOException {
        return create(AudioEncoder.DEFAULT_BITRATE_BPS);
    }

    /**
     * @throws IOException with a message naming the probe result when no `audio/opus` encoder is
     *                     present or it cannot be configured/started
     */
    public static OpusAudioEncoder create(int bitrateBps) throws IOException {
        final String name = findEncoderName();
        if (name == null) {
            throw new IOException("no MediaCodec `" + MIME + "` encoder on this device "
                    + "(MediaCodecList probe found none)");
        }
        final MediaFormat format = MediaFormat.createAudioFormat(MIME, SAMPLE_RATE, CHANNELS);
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps);
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, FRAME_BYTES);
        final MediaCodec codec;
        try {
            codec = MediaCodec.createByCodecName(name);
        } catch (Throwable t) {
            throw new IOException("cannot create MediaCodec " + name + ": " + t, t);
        }
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            codec.start();
        } catch (Throwable t) {
            try {
                codec.release();
            } catch (Throwable ignored) {
                /* the Throwable below is the useful one */
            }
            throw new IOException("cannot configure/start " + name + " as " + MIME + ' '
                    + SAMPLE_RATE + "Hz " + CHANNELS + "ch " + bitrateBps + "bps: " + t, t);
        }
        Log.i(TAG, "opus encoder " + name + " started (" + SAMPLE_RATE + "Hz " + CHANNELS
                + "ch " + bitrateBps + "bps, " + FRAME_MS + "ms/" + FRAME_SAMPLES + " samples)");
        return new OpusAudioEncoder(codec, name, bitrateBps);
    }

    @Override
    public String describe() {
        return "opus " + SAMPLE_RATE + "/mono " + mBitrateBps + "bps " + FRAME_MS + "ms/"
                + FRAME_SAMPLES + " samples (" + mCodecName + ')';
    }

    public long packetsEncoded() {
        return mPacketsOut;
    }

    @Override
    public void encode(byte[] monoLittleEndian, int length, List<byte[]> packetsOut) throws Exception {
        if (mClosed) {
            throw new IOException("opus encoder already closed");
        }
        if (monoLittleEndian == null || length <= 0) {
            return;
        }
        int offset = 0;
        if (mCarryUsed > 0) {
            final int take = Math.min(FRAME_BYTES - mCarryUsed, length);
            System.arraycopy(monoLittleEndian, 0, mCarry, mCarryUsed, take);
            mCarryUsed += take;
            offset = take;
            if (mCarryUsed == FRAME_BYTES) {
                queueFrame(mCarry, 0, packetsOut);
                mCarryUsed = 0;
            }
        }
        while (offset + FRAME_BYTES <= length) {
            queueFrame(monoLittleEndian, offset, packetsOut);
            offset += FRAME_BYTES;
        }
        final int rest = length - offset;
        if (rest > 0) {
            System.arraycopy(monoLittleEndian, offset, mCarry, mCarryUsed, rest);
            mCarryUsed += rest;
        }
    }
    private void queueFrame(byte[] pcm, int offset, List<byte[]> packetsOut) throws IOException {
        /* Snapshot once: a session restart releases the codec and nulls the field, and this runs on
         * the audio thread.  Dereferencing the field after that is the same failure that killed the
         * whole daemon from the video encode thread on 2026-10-02 — here it must simply end the
         * frame quietly. */
        final MediaCodec codec = mCodec;
        if (codec == null) {
            return;
        }
        int inIndex = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US);
        for (int retry = 0; inIndex < 0 && retry < STARVE_RETRIES; retry++) {
            /* Input starvation is usually our own output backlog: the drain below is non-blocking,
             * so while we sit here waiting nothing releases the codec's buffers.  Drain again and
             * retry — that is what turns a stall into a short hiccup instead of a dead stream. */
            drain(packetsOut);
            if (mCodec == null) {
                return;
            }
            inIndex = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US);
        }
        if (inIndex < 0) {
            /* Starved, not wedged (yet): drop this frame and keep the stream — and the session —
             * alive.  See STARVE_FRAMES_BEFORE_FAIL for what this replaced. */
            mStarvedFrames++;
            mDroppedFrames++;
            if (mStarvedFrames == STARVE_FRAMES_BEFORE_FAIL) {
                throw new IOException("opus encoder starved for " + mStarvedFrames + " frames (~"
                        + (STARVE_FRAMES_BEFORE_FAIL * FRAME_MS) + " ms): it never handed out an"
                        + " input buffer, so it is wedged, not busy");
            }
            if (mStarvedFrames == 1 || mStarvedFrames % 50 == 0) {
                Log.w(TAG, "opus encoder starved (" + mStarvedFrames + " frames dropped so far)");
            }
            return;
        }
        if (mStarvedFrames > 0) {
            Log.i(TAG, "opus encoder recovered after dropping " + mStarvedFrames + " frames");
            mStarvedFrames = 0;
        }
        final ByteBuffer buffer = codec.getInputBuffer(inIndex);
        if (buffer == null) {
            throw new IOException("opus encoder input buffer " + inIndex + " is null");
        }
        buffer.clear();
        buffer.put(pcm, offset, FRAME_BYTES);
        final long presentationTimeUs = mFramesQueued * 1_000_000L / SAMPLE_RATE;
        codec.queueInputBuffer(inIndex, 0, FRAME_BYTES, presentationTimeUs, 0);
        mFramesQueued += FRAME_SAMPLES;
        drain(packetsOut);
    }

    private void drain(List<byte[]> packetsOut) {
        while (true) {
            final int outIndex = mCodec.dequeueOutputBuffer(mInfo, 0);
            if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                return;
            }
            if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                continue;
            }
            if (outIndex < 0) {
                return;
            }
            try {
                if (mInfo.size > 0) {
                    final ByteBuffer buffer = mCodec.getOutputBuffer(outIndex);
                    if (buffer != null) {
                        final byte[] packet = new byte[mInfo.size];
                        buffer.position(mInfo.offset);
                        buffer.limit(mInfo.offset + mInfo.size);
                        buffer.get(packet);
                        packetsOut.add(packet);
                        mPacketsOut++;
                    }
                }
            } finally {
                mCodec.releaseOutputBuffer(outIndex, false);
            }
        }
    }

    @Override
    public void close() {
        if (mClosed) {
            return;
        }
        mClosed = true;
        final List<byte[]> ignored = new ArrayList<byte[]>();
        try {
            final int inIndex = mCodec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US);
            if (inIndex >= 0) {
                mCodec.queueInputBuffer(inIndex, 0, 0,
                        mFramesQueued * 1_000_000L / SAMPLE_RATE,
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                for (int i = 0; i < 8; i++) {
                    final int outIndex = mCodec.dequeueOutputBuffer(mInfo, DEQUEUE_TIMEOUT_US);
                    if (outIndex < 0) {
                        break;
                    }
                    mCodec.releaseOutputBuffer(outIndex, false);
                    if ((mInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        break;
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "opus encoder flush on close failed: " + t);
        }
        try {
            mCodec.stop();
        } catch (Throwable t) {
            Log.w(TAG, "opus encoder stop failed: " + t);
        }
        try {
            mCodec.release();
        } catch (Throwable t) {
            Log.w(TAG, "opus encoder release failed: " + t);
        }
        Log.i(TAG, "opus encoder " + mCodecName + " closed after " + mPacketsOut + " packets");
        ignored.clear();
    }
}
