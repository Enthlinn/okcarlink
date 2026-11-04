package com.okcar.link.port;

/**
 * Keeper (display watch) action and cadence decisions (pure Java, host-testable).
 *
 * <p>Background: each keeper round asks "is the target app still on our virtual display". On 2026-10-05 it exposed two
 * problems; only the **decisions** live here, touching no Android API, so host regression can pin the boundaries:
 *
 * <ol>
 *   <li><b>No re-place after the system clears it</b>: after MIUI clears the target app from recents (`am_kill … SwipeUpClean`),
 *       the dump has no task at all, and the old code did a bare {@code continue} when {@code task <= 0},
 *       so the head-unit screen stayed dead. Now only "no task and the process is gone too" counts as really killed;
 *       re-place after two consecutive confirming samples.</li>
 *   <li><b>Poll cadence</b>: fast while confirming placement (the operator wants the picture right away), slow in steady
 *       state (don't fork {@code am stack list} every 1.5 seconds).</li>
 * </ol>
 */
public final class KeeperPolicy {

    /** Move only after two consecutive samples say "not on this display": a single sample may catch a task mid-move. */
    public static final int MISSES_BEFORE_MOVE = 2;

    /** Only after two consecutive samples say "neither task nor process" is it considered killed, so a starting app isn't treated as dead. */
    public static final int MISSES_BEFORE_REPLACE = 2;

    /** Cooldown after a successful re-place: don't fight the user switching apps manually. */
    public static final long REPLACE_COOLDOWN_MS = 15_000L;

    /** Cooldown after a failed re-place: failures usually mean the system is busy; wait longer before retrying to avoid a storm. */
    public static final long REPLACE_FAIL_COOLDOWN_MS = 60_000L;

    /**
     * How long "the process is alive but owns no task anywhere" may still count as "it is starting".
     *
     * <p>Measured on 217 (com.mcar.auto, 2026-10-27, wireless): the app finished its own activity
     * ({@code wm_finish_activity … app-request}) and kept its process — that state is not transient
     * at all. The keeper treated every 4-second sample as "it must be starting" (see
     * {@link #shouldReplace(boolean, boolean)}), the car screen froze on the last frame and every
     * injected touch was dropped with {@code NO_WINDOW} — for 80+ minutes. A genuine start gets its
     * task within seconds (the activity record exists before the window), so 10 seconds separates
     * "starting" from "finished itself" with margin on both sides.
     */
    public static final long LIVE_NO_TASK_GRACE_MS = 10_000L;

    /** Confirmation window right after placing. */
    public static final long FAST_WINDOW_MS = 20_000L;

    /** Sampling interval inside the confirmation window. */
    public static final long FAST_INTERVAL_MS = 1_500L;

    /** Steady-state sampling interval: `am stack list` measured 61ms, so once every 4 seconds is fast enough and light enough. */
    public static final long STEADY_INTERVAL_MS = 4_000L;

    private KeeperPolicy() {
    }

    /**
     * Whether it should re-place: only when there is no task and the process is gone too is it really killed.
     *
     * <p>A live process with no task means the app is starting/switching; re-placing would only create a second instance.
     */
    public static boolean shouldReplace(boolean hasTask, boolean processAlive) {
        return !hasTask && !processAlive;
    }

    /**
     * Whether a live process that owns no task anywhere is past the "it is starting" grace and
     * must be re-placed: past the grace the app finished its own activity while keeping its process
     * (measured on 217, 2026-10-27) — the virtual display stays empty, the picture freezes and
     * touches are dropped, so the keeper has to re-place it.
     */
    public static boolean shouldReplaceLiveWithoutTask(long liveNoTaskMs) {
        return liveNoTaskMs >= LIVE_NO_TASK_GRACE_MS;
    }

    /** Whether the cooldown has elapsed ({@code untilMs} of 0 means it never cooled down). */
    public static boolean cooldownElapsed(long nowMs, long untilMs) {
        return nowMs >= untilMs;
    }

    /** Next sampling interval: fast inside the placement-confirmation window, slow in steady state. */
    public static long intervalMs(long sincePlacementMs) {
        return sincePlacementMs >= 0 && sincePlacementMs < FAST_WINDOW_MS
                ? FAST_INTERVAL_MS : STEADY_INTERVAL_MS;
    }
}
