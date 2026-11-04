package com.okcar.link.port;

/**
 * The **response policy** for a system stall (pure Java, host-testable).
 *
 * <p>Why a separate layer: in the two 2026-10-04/05 field incidents of "whole device starved → PMIC hard reset",
 * {@code okcar-linkd}'s stall watchdog did fire (once its own heartbeat was dragged by **319 seconds**), but it **only
 * `release()`d the virtual display**: encoder, audio, event channel and socket all stayed, and the app's video kept
 * decoding — none of the load was shed, so it reported every 30 seconds until the hardware watchdog bit the phone into a reboot.
 *
 * <p>The policy itself is simple: one stall first sheds the display; when **severe** (single ≥5s) or **repeated** (3 total),
 * the whole casting pipeline is stopped and **suspended for a while**, giving the phone a chance to recover instead of
 * being pulled right back up by the same accessory. Decision and action are split so {@code the desktop video-stall harness} can
 * pin down every branch on the desktop.
 */
public final class StallResponse {

    /** A single stall at this magnitude means we are in "the hardware watchdog will bite" territory. */
    public static final long SEVERE_MS = 5000L;

    /** Cumulative triggers reaching this count (including the current one) means the system is repeatedly disturbed; don't let it restart immediately. */
    public static final int REPEAT_LIMIT = 3;

    /** How long casting is suspended; also leaves the operator a window to "see what actually happened". */
    public static final long SUSPEND_MS = 5L * 60L * 1000L;

    private StallResponse() {
    }

    /** What this stall should do. */
    public static final class Actions {
        /** Release the virtual display (always done: it holds the app window and capture surface). */
        public final boolean releaseDisplay;
        /** Stop the whole session pipeline: encoder/audio/event channel/socket are all released. */
        public final boolean stopSession;
        /** Suspend new casting sessions for a while (also refuse the accessory if it dials again). */
        public final boolean suspend;
        /** Suspend duration (meaningful when suspend is true). */
        public final long suspendMs;
        /** Level for logging: 1=shed display, 2=stop pipeline, 3=stop pipeline+suspend. */
        public final int level;

        Actions(boolean releaseDisplay, boolean stopSession, boolean suspend, long suspendMs,
                int level) {
            this.releaseDisplay = releaseDisplay;
            this.stopSession = stopSession;
            this.suspend = suspend;
            this.suspendMs = suspendMs;
            this.level = level;
        }

        public String describe() {
            return "level=" + level + " releaseDisplay=" + releaseDisplay
                    + " stopSession=" + stopSession
                    + (suspend ? " suspend=" + (suspendMs / 1000L) + "s" : "");
        }
    }

    /**
     * Graded escalation: one mild jitter only sheds the display (the system may just be dropping frames or switching apps,
     * no need to tear the session down); a second one in the same round stops the pipeline; a single "severe" stall
     * (≥5s — the hardware watchdog is already watching you) or three cumulative stalls stops the pipeline and suspends.
     *
     * @param stalledMs  stall length measured this time (return value of {@code SystemStallGuard.beat})
     * @param totalStalls cumulative trigger count including this one ({@code SystemStallGuard.stalls()})
     */
    public static Actions forStall(long stalledMs, long totalStalls) {
        final boolean severe = stalledMs >= SEVERE_MS;
        final boolean repeated = totalStalls >= REPEAT_LIMIT;
        if (severe || repeated) {
            return new Actions(true, true, true, SUSPEND_MS, 3);
        }
        if (totalStalls >= 2) {
            /* Already the second one: stop encoder/audio/event channel, don't let a half-dead session keep holding the VPU. */
            return new Actions(true, true, false, 0L, 2);
        }
        /* First one and mild: only shed the display. */
        return new Actions(true, false, false, 0L, 1);
    }
}
