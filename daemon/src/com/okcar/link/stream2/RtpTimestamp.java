/*
 * OkcarOS link stack — display-2 RTP: the 90 kHz frame clock.
 *
 * MediaCodec's presentation timestamps are microseconds and start wherever the display's first
 * frame happens to be; RTP wants a monotonically growing 90 kHz value.  Frames are therefore
 * numbered and their timestamp derived from the frame index and the configured frame rate, which
 * is what makes "30 fps -> 3000 ticks per frame" exact instead of accumulated rounding.
 */
package com.okcar.link.stream2;

public final class RtpTimestamp {

    public static final int CLOCK_RATE = RtpPacketizer.CLOCK_RATE;

    private final int mFps;
    private int mBase;
    private long mFrames;

    public RtpTimestamp(int fps) {
        if (fps <= 0) {
            throw new IllegalArgumentException("bad fps: " + fps);
        }
        mFps = fps;
    }

    /** 90 kHz ticks between two consecutive frames at the configured rate (30 fps -> 3000). */
    public int ticksPerFrame() {
        return (int) Math.round(CLOCK_RATE * 1.0 / mFps);
    }

    public int fps() {
        return mFps;
    }

    public long frames() {
        return mFrames;
    }

    /** The timestamp of frame {@code index}; the first frame is also the base. */
    public int timestampFor(long index) {
        final long ticks = index * ticksPerFrame();
        if (mFrames == 0 && index == 0) {
            return mBase;
        }
        return (int) ((mBase + ticks) & 0xffffffffL);
    }

    /** Advances one frame and returns its timestamp. */
    public int next() {
        final int value = timestampFor(mFrames);
        mFrames++;
        return value;
    }

    /** Jumps the sequence forward without inventing frames we never encoded. */
    public void skipTo(long index) {
        if (index > mFrames) {
            mFrames = index;
        }
    }

    /** Restarts the clock; the random base keeps a re-PLAY from reusing a stale timeline. */
    public void reset(int randomBase) {
        mBase = randomBase;
        mFrames = 0;
    }
}
