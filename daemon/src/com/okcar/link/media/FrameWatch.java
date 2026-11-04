package com.okcar.link.media;

/**
 * Policy for the session frame watchdog (pure Java, host-testable).
 *
 * <p>The point is to **tell "really stuck" apart from "a static page simply has no frames"** (the
 * user asked for this explicitly on 2026-10-05). The distinction rests on two mechanisms of ours:
 *
 * <ol>
 *   <li>the encoder is configured with {@code KEY_REPEAT_PREVIOUS_FRAME_AFTER = 250ms}: when the
 *       screen does not move the encoder still re-emits frames, so a normal static page runs at
 *       about 4fps and does not go zero-frame for long;</li>
 *   <li>the keep-alive thread asks for an IDR every {@code KEEPALIVE_INTERVAL_MS}
 *       ({@code requestSyncFrame}).</li>
 * </ol>
 *
 * <p>So the test is a <b>three-part conjunction</b>: the session must be up ⇒ <b>an IDR was
 * requested after the last frame came out</b> (proving that "there should be frames" is something
 * we asked for, not just a page that never moved) ⇒ and there is still no frame after the
 * threshold. Missing any part means not stuck — reporting stuck on "no frames" alone would
 * false-positive on any static page.
 */
public final class FrameWatch {

    /** At this duration, warn and ask for another IDR (do not tear down the pipeline). */
    public static final long WARN_MS = 3_000L;

    /**
     * Still no frame at this duration: record "the pipeline may be stuck" as an error-level log.
     *
     * <p><b>Note</b>: measured on 2026-10-05 with a static screen in app mode, the encoder
     * **simply emits no frames** — `requestSyncFrame()` does not guarantee a frame arrives (the
     * platform does not re-emit when the screen has no new content). So this threshold only
     * describes the symptom and **must not** be used to tear down the session: that would cut any
     * static screen, which in the field showed up exactly as "connects for a moment, then drops
     * and reconnects".
     */
    public static final long TEARDOWN_MS = 15_000L;

    /** The watchdog's own check period. */
    public static final long TICK_MS = 500L;

    private FrameWatch() {
    }

    /**
     * Whether the pipeline is judged "stuck".
     *
     * @param sessionUp         whether the session is up (with no session there is nothing to watch)
     * @param nowMs             current time (same time base as the two times below; monotonic is enough)
     * @param lastFrameMs       when the last frame left the encoder; use the session start time if none ever did
     * @param lastIdrRequestMs  when {@code requestSyncFrame} was last called
     * @param thresholdMs       threshold, either {@link #WARN_MS} or {@link #TEARDOWN_MS}
     */
    public static boolean stuck(boolean sessionUp, long nowMs, long lastFrameMs,
                                long lastIdrRequestMs, long thresholdMs) {
        if (!sessionUp) {
            return false;
        }
        /* No new IDR requested yet: this may be a purely static page, or keep-alive simply has not
         * come around, so the pipeline is not to blame. */
        if (lastIdrRequestMs <= lastFrameMs) {
            return false;
        }
        return nowMs - lastFrameMs >= thresholdMs;
    }

    /**
     * Whether to actually tear down/stop the pipeline: **must be explicitly enabled**
     * ({@code persist.okcar.frame_teardown=1}), and the three-part test of {@link #stuck} must
     * still hold. Off by default — a static screen producing no frames is not a fault.
     */
    public static boolean shouldTearDown(boolean teardownEnabled, boolean sessionUp, long nowMs,
                                         long lastFrameMs, long lastIdrRequestMs) {
        return teardownEnabled && stuck(sessionUp, nowMs, lastFrameMs, lastIdrRequestMs, TEARDOWN_MS);
    }

    /** Whether to ask for another IDR (at the warn level, try one self-rescue first instead of tearing down). */
    public static boolean shouldReRequestIdr(boolean sessionUp, long nowMs, long lastFrameMs,
                                             long lastIdrRequestMs, long thresholdMs) {
        return stuck(sessionUp, nowMs, lastFrameMs, lastIdrRequestMs, thresholdMs)
                && nowMs - lastIdrRequestMs >= TICK_MS;
    }
}
