package com.okcar.link.airplay;

import com.okcar.link.media.VideoQos;

/**
 * Video send pacing and buffering policy (pure Java, host-testable).
 *
 * <p>User direction, 2026-10-05: **add buffering rather than drop frames** — because
 * (1) the emulator's own audio delay measures ~300 ms, so video kept "arriving early" and audio/video drifted apart;
 * (2) the short backlog created when an app is opened on the phone should be absorbed by the buffer, not
 * chased back by dropping frames.
 *
 * <p>So the send side puts frames into a "send only when due" buffer: every frame records
 * {@code enqueue time + delay} and a dedicated pacing thread seals and sends it when due. That pins the delay
 * (video held back 300 ms = aligned with the accessory's audio delay) and moves "seal + write socket" off
 * the encoder thread (previously done synchronously on the encoder drain thread, the direct cause of the
 * burst at stream start).
 *
 * <p>The buffer is not unbounded: past `delay + slack` the oldest frames must still be dropped, or the
 * delay would keep growing and audio/video would drift further. Dropping is a **last resort**, and it
 * immediately requests an IDR to avoid smearing once the reference chain breaks.
 */
public final class VideoPacer {

    /** Default video delay: matches the emulator's audio delay (measured in the field at ~300 ms; "Fruit Link" defaults to 384 for a comparable buffer). */
    public static final long DEFAULT_DELAY_MS = VideoQos.VIDEO_DELAY_DEFAULT_MS;

    /** Delay ceiling: any larger stops "compensating for drift" and starts "manufacturing latency". */
    public static final long MAX_DELAY_MS = VideoQos.VIDEO_DELAY_MAX_MS;

    /** Slack on top of the target delay: headroom for bursts, so normal jitter does not drop frames either. */
    public static final long SLACK_MS = 200L;

    /** Pacing-thread poll period: an order of magnitude below one frame interval (30fps ≈ 33ms), accurate without busy-waiting. */
    public static final long TICK_MS = 4L;

    private VideoPacer() {
    }

    /** Clamp into the legal range; negative values are treated as 0 (no delay). */
    public static long clampDelay(long requestedMs) {
        if (requestedMs <= 0) {
            return 0L;
        }
        return Math.min(requestedMs, MAX_DELAY_MS);
    }

    /** Due time of this frame. */
    public static long dueMs(long enqueueMs, long delayMs) {
        return enqueueMs + clampDelay(delayMs);
    }

    /** Whether it is due ({@code >=} counts; the boundary itself counts as due). */
    public static boolean isDue(long nowMs, long dueMs) {
        return nowMs >= dueMs;
    }

    /** Total buffered duration ceiling (target delay + slack). */
    public static long capacityMs(long delayMs) {
        return clampDelay(delayMs) + SLACK_MS;
    }

    /**
     * How many frames the buffer holds (converted from the frame rate, at least 2).
     *
     * <p>Frames that fall outside this window are "already unrecoverable" — only the oldest can be dropped.
     */
    public static int capacityFrames(long delayMs, int fps) {
        final int safeFps = fps <= 0 ? 30 : fps;
        final int frames = (int) Math.ceil(capacityMs(delayMs) * safeFps / 1000.0);
        return Math.max(2, frames);
    }
}
