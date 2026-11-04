package com.okcar.link.airplay;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Decides "has a single socket write hung".
 *
 * <p>Background (field, 2026-10-04): the video data socket **deliberately** uses a small {@code SO_SNDBUF} with blocking
 * writes to suppress latency (see {@link WirelessVideoSender#connect()}): when the receiver reads slowly the write must
 * stall the encoder drain so MediaCodec drops old frames at its input, keeping the picture in step with live audio.
 * But when the receiver **dies** (the emulator crashes and restarts, or the process stays alive but stops reading), the write
 * blocks forever: once the kernel send buffer fills, {@code write()} never returns, and the only escape — {@code stop()},
 * which does {@code close(socket)} — is never called because the heartbeat thread is stuck on the same {@code mWriteLock}.
 * The session still looks alive ({@code mRunning} is true), the {@code WirelessCarPlaySession} liveness poll keeps
 * assuming frames are flowing, and the picture freezes until the process is killed and restarted.
 *
 * <p>This class does pure time accounting and never touches the socket, so it regresses directly on the host with a JDK:
 * call {@link #begin(long)} before a write and {@link #end()} after it; the watchdog periodically asks
 * {@link #stalled(long)}. A stall verdict leaves {@code close(socket)} to the caller to unblock the write.
 *
 * <p>{@code stallMs <= 0} disables the protection (back to the old behaviour), for field A/B comparison.
 */
public final class WriteStallGuard {

    /** Default threshold: a single write hung for 5 seconds means the peer is considered dead. */
    public static final long DEFAULT_STALL_MS = 5000L;

    private final long mStallMs;
    /** Start of the current write ({@code SystemClock.elapsedRealtime()} clock); 0 = no write in flight. */
    private final AtomicLong mInFlightSinceMs = new AtomicLong();
    /** Cumulative stall verdicts, for logging and for "report only once". */
    private final AtomicLong mStalls = new AtomicLong();

    public WriteStallGuard(long stallMs) {
        mStallMs = stallMs;
    }

    /** Threshold in milliseconds; {@code <=0} disables the guard. */
    public long stallMs() {
        return mStallMs;
    }

    public boolean enabled() {
        return mStallMs > 0;
    }

    /** Begin one write. Called serially under the same lock, so no counter is needed — at most one write is ever in flight. */
    public void begin(long nowMs) {
        if (enabled()) {
            mInFlightSinceMs.set(nowMs);
        }
    }

    /** One write (including flush) has finished. */
    public void end() {
        if (enabled()) {
            mInFlightSinceMs.set(0L);
        }
    }

    /** How long this write has been in flight (ms); 0 when nothing is in flight. */
    public long inFlightMs(long nowMs) {
        final long since = mInFlightSinceMs.get();
        return since <= 0 ? 0L : Math.max(0L, nowMs - since);
    }

    /** Watchdog verdict: a write is in flight and has exceeded the threshold. */
    public boolean stalled(long nowMs) {
        if (!enabled()) {
            return false;
        }
        final long since = mInFlightSinceMs.get();
        return since > 0 && nowMs - since > mStallMs;
    }

    /** Record one stall handling (after the caller closed the socket); returns the running count. */
    public long noteStallHandled() {
        return mStalls.incrementAndGet();
    }

    public long stalls() {
        return mStalls.get();
    }
}
