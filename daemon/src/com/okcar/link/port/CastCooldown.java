package com.okcar.link.port;

/**
 * The stall cooldown state machine (pure Java, host-testable).
 *
 * <p>Why a class of its own: {@code LinkDaemon}'s stall watch stops the whole casting pipeline and
 * asks for a cooldown window, and the two things that go wrong around a window are both about
 * <em>time</em> rather than about Android -- the window must not be shortened by a later, smaller
 * request, and it must actually end (and say so). {@code StallResponse} was split out for the same
 * reason; the field history is what motivates this one:
 *
 * <ul>
 *   <li>2026-10-04/05: after the phone was starved, the accessory dialled straight back and the same
 *       load dragged it under again until the hardware watchdog reset it -- the cooldown exists to
 *       break that loop.</li>
 *   <li>2026-10-11: nothing ever cleared the window when it expired, so the last thing the log said
 *       was "casting suspended for 300s" and the panel looked as if the phone never came back. The
 *       listener had stayed up the whole time; only the window was stale.</li>
 * </ul>
 *
 * <p>The clock is passed in on every call, so the host regression can drive a whole window without
 * sleeping.
 */
public final class CastCooldown {

    /** Shortest window; a stall right after start would otherwise let the load come straight back. */
    public static final long MIN_MS = 30_000L;
    /** Longest window: 30 minutes, the point past which "suspended" is indistinguishable from "broken". */
    public static final long MAX_MS = 30L * 60L * 1000L;

    private volatile long untilMs;
    private volatile String reason = "";

    /**
     * Requests a window of {@code ms} from {@code nowMs}.
     *
     * <p>A later request may only <em>extend</em> the window: the stall that just happened is the one
     * that says how bad things are, and letting a smaller follow-up request shorten it would re-arm
     * the load earlier than the worst recent stall justified.
     *
     * @return the clamped length actually requested (ms)
     */
    public long suspendFor(long ms, String reason, long nowMs) {
        final long clamped = Math.max(MIN_MS, Math.min(MAX_MS, ms));
        final long until = nowMs + clamped;
        if (until > untilMs) {
            untilMs = until;
        }
        this.reason = reason == null ? "" : reason;
        return clamped;
    }

    public boolean suspended(long nowMs) {
        return nowMs < untilMs;
    }

    public long remainingMs(long nowMs) {
        final long left = untilMs - nowMs;
        return left > 0 ? left : 0L;
    }

    public String reason() {
        return reason;
    }

    /**
     * Ends the window if it is due, and reports it exactly once.
     *
     * <p>Returning the reason (rather than a boolean) is what lets the caller log <em>why</em> the
     * cooldown was entered when it ends -- the field symptom was a log whose last line about casting
     * was the suspension itself.
     *
     * @return the reason of the window that just ended, or null when nothing was due
     */
    public String expireIfDue(long nowMs) {
        final long until = untilMs;
        if (until == 0L || nowMs < until) {
            return null;
        }
        final String ended = reason;
        untilMs = 0L;
        reason = "";
        return ended;
    }

    /** Ends the window immediately (operator escape hatch). @return ms that were left */
    public long clear(long nowMs) {
        final long left = remainingMs(nowMs);
        untilMs = 0L;
        reason = "";
        return left;
    }
}
