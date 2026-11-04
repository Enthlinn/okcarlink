package com.okcar.link.port;

/**
 * System stall detection: measures how long the daemon itself (i.e. the whole phone) has been "starved".
 *
 * <p>Why: 2026-10-04 field incidents repeatedly showed "device freezes after connecting → watchdog/RCU stall reboots the
 * phone" (`kernel.panic_on_rcu_stall=1`). We cannot stop the source of that collapse from our side, but **we can exit
 * first**: once our own scheduling is delayed by more than 2 seconds, proactively release the mirror display and end this
 * session — turning "whole phone freezes or even reboots" into "casting drops, phone survives". This is the display-side
 * half of "at least make sure the receiver can't take us down" (the write side already has {@code WriteStallGuard}).
 *
 * <p>Pure Java, time injected by the caller, host-regression testable ({@code the desktop video-stall harness}).
 * Threshold 0 disables it; after a trigger it stays silent for 30 seconds, so logs and release actions don't repeat while the stall persists.
 */
public final class SystemStallGuard {

    /** Default threshold: scheduling delayed by more than 2 seconds means the system is collapsing. */
    public static final long DEFAULT_THRESHOLD_MS = 2000L;

    private static final long REARM_SUPPRESS_MS = 30000L;

    private final long mThresholdMs;
    private long mLastBeatMs;
    private long mSuppressUntilMs;
    private long mStalls;
    private long mWorstMs;

    public SystemStallGuard(long thresholdMs, long startMs) {
        mThresholdMs = thresholdMs;
        mLastBeatMs = startMs;
    }

    public boolean enabled() {
        return mThresholdMs > 0;
    }

    public long thresholdMs() {
        return mThresholdMs;
    }

    /**
     * Called periodically (250ms recommended). Returns the stall in ms measured over this interval; 0 if the threshold was
     * not exceeded, the guard is disabled, or we are still in the 30-second silent window. Any non-zero value means release the mirror.
     */
    public long beat(long nowMs) {
        final long gap = nowMs - mLastBeatMs;
        mLastBeatMs = nowMs;
        if (!enabled() || gap <= mThresholdMs || nowMs < mSuppressUntilMs) {
            return 0;
        }
        mSuppressUntilMs = nowMs + REARM_SUPPRESS_MS;
        mStalls++;
        if (gap > mWorstMs) {
            mWorstMs = gap;
        }
        return gap;
    }

    /**
     * Re-bases the beat clock and forgets past stalls, without counting this interval as a stall.
     *
     * <p>Called while nothing is casting. The guard exists to shed a live pipeline before the phone collapses; with no
     * pipeline there is nothing to shed, and a multi-second gap is just boot/background load (measured 2026-10-20 on 217:
     * 18.3 s and 5.8 s while the ROM dumped 58 apps, with no session up — that escalated to level 3 and suspended casting
     * for 300 s right when the user plugged the cable in). Skipping beats outright would be wrong too: the next real beat
     * would then charge the whole idle period as one stall.
     */
    public void idle(long nowMs) {
        mLastBeatMs = nowMs;
        mSuppressUntilMs = 0L;
        mStalls = 0L;
        mWorstMs = 0L;
    }

    /** Cumulative stall trigger count (excluding those suppressed during the silent window). */
    public long stalls() {
        return mStalls;
    }

    /** The longest stall ever seen. */
    public long worstMs() {
        return mWorstMs;
    }
}
