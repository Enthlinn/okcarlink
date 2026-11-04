/*
 * OkcarOS link stack — display-2 RTSP: access-unit fan-out from one encoder to N RTSP clients.
 *
 * Pure Java (no android.*) so the queue/replay logic — "a client that joins in the middle still
 * gets SPS/PPS first" — is host-testable (the desktop rtsp2 harness).
 *
 * There is exactly ONE encoder for display 2.  A second client therefore cannot open a second
 * encoder; it replays the parameter sets and then joins the live stream.  Keeping the last
 * parameter sets (and only the last few access units) bounds memory: a client that falls further
 * behind than a handful of frames is served from the live edge, which is what a viewer wants and
 * what stops a stalled socket from growing the daemon's heap.
 */
package com.okcar.link.stream2;

import java.util.ArrayDeque;

public final class FrameHub {

    /** One encoded picture: one access unit, already in Annex-B-free NAL form. */
    public static final class AccessUnit {
        public final long index;
        public final long ptsUs;
        public final byte[] nal;
        public final boolean keyframe;

        public AccessUnit(long index, long ptsUs, byte[] nal, boolean keyframe) {
            this.index = index;
            this.ptsUs = ptsUs;
            this.nal = nal;
            this.keyframe = keyframe;
        }

        public int size() {
            return nal.length;
        }
    }

    private final int mCapacity;
    private final ArrayDeque<AccessUnit> mUnits = new ArrayDeque<AccessUnit>();
    private volatile byte[] mSps;
    private volatile byte[] mPps;
    private volatile long mEmitted;
    private volatile boolean mClosed;
    /** Raised when the encoder dies: a client must not wait forever on a stream that ended. */
    private volatile String mFailure;

    public FrameHub(int capacity) {
        mCapacity = Math.max(2, capacity);
    }

    /** Codec-specific data from the encoder's output format; safe to call once per format change. */
    public void setParameterSets(byte[] sps, byte[] pps) {
        mSps = sps == null ? null : sps.clone();
        mPps = pps == null ? null : pps.clone();
    }

    public byte[] sps() {
        final byte[] value = mSps;
        return value == null ? null : value.clone();
    }

    public byte[] pps() {
        final byte[] value = mPps;
        return value == null ? null : value.clone();
    }

    public boolean hasParameterSets() {
        final byte[] sps = mSps;
        final byte[] pps = mPps;
        return sps != null && sps.length > 0 && pps != null && pps.length > 0;
    }

    /** Publishes one access unit and drops the oldest when the queue is full. */
    public synchronized void publish(long ptsUs, byte[] nal, boolean keyframe) {
        if (nal == null || nal.length == 0) {
            return;
        }
        final long index = mEmitted++;
        mUnits.addLast(new AccessUnit(index, ptsUs, nal, keyframe));
        while (mUnits.size() > mCapacity) {
            mUnits.removeFirst();
        }
        notifyAll();
    }

    /**
     * Waits for the access unit after {@code afterIndex}, at most {@code timeoutMs}.
     *
     * @return the next unit, or null on timeout
     * @throws java.io.IOException when the encoder failed or the stream ended — a sender must not
     *     wait forever on a stream that will never produce another frame
     */
    public synchronized AccessUnit await(long afterIndex, long timeoutMs)
            throws InterruptedException, java.io.IOException {
        final long deadline = System.currentTimeMillis() + timeoutMs;
        while (true) {
            for (AccessUnit unit : mUnits) {
                if (unit.index > afterIndex) {
                    return unit;
                }
            }
            final String failure = mFailure;
            if (failure != null) {
                throw new java.io.IOException("encoder failed: " + failure);
            }
            if (mClosed) {
                throw new java.io.IOException("stream ended");
            }
            final long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                return null;
            }
            wait(remaining);
        }
    }

    /** The newest published access unit, or null when nothing was encoded yet. */
    public synchronized AccessUnit latest() {
        return mUnits.peekLast();
    }

    public synchronized int queued() {
        return mUnits.size();
    }

    public long emitted() {
        return mEmitted;
    }

    public boolean closed() {
        return mClosed;
    }

    public String failure() {
        return mFailure;
    }

    /** Normal end of stream (encoder stopped by `cast2 down`). */
    public synchronized void close() {
        mClosed = true;
        notifyAll();
    }

    /** Failed end of stream: the reason is reported to every waiter and into `cast2 status`. */
    public synchronized void fail(String reason) {
        mFailure = reason == null ? "unknown" : reason;
        mClosed = true;
        notifyAll();
    }
}
