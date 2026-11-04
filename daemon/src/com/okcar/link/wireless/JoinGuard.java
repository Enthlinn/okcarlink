/*
 * OkcarOS link stack — the Wi-Fi join guard (circuit breaker + P2P channel pre-check).
 *
 * Both rules were written from the 2026-10-04 field failure and used to live as private
 * members of {@link WifiGroupJoiner}, where they were unreachable from a host harness.
 * They are pure bookkeeping (counters, a deadline, a two-key memory), so they live here
 * instead: no Android types, the clock is injected by the caller, and
 * `the desktop joinguard harness` exercises every branch on a desktop JVM.
 *
 * ---------------------------------------------------------------------------
 * Why the breaker exists (2026-10-04 field run)
 * ---------------------------------------------------------------------------
 * The accessory rebuilds its Wi-Fi Direct group on every reconnect, so its SSID suffix
 * changes every round and the old code retried forever: addNetwork, up to two escalations
 * (3 x 10 s polls each), then a fresh session — all of it hammering the Wi-Fi framework.
 * Measured on 217: that drove the framework until the system could not schedule input
 * (`am_anr com.miui.home … Input dispatching timed out`) — the phone froze, and even a
 * later session in hotspot mode died with it.  Three consecutive failures inside two
 * minutes therefore open a 60 s cooldown during which **not a single Wi-Fi call** is made:
 * the failure is reported as-is, the operator reconnects from the car, and the phone
 * recovers.  A success clears the counter immediately.
 *
 * ---------------------------------------------------------------------------
 * Why the channel pre-check exists (2026-10-04, measured from both ends)
 * ---------------------------------------------------------------------------
 * the emulator's `WifiP2pGroupManager` pins the group to the channel of its *own* station (that
 * is also why the group is rebuilt — and renamed — whenever its station moves).  A phone
 * whose station sits on another 5 GHz channel cannot run cross-channel P2P on this ROM, so
 * the join is *certain* to fail — and the framework then thrashes STA/P2P ("Wi-Fi drops,
 * comes back, drops again") until the system stalls.  The failure is knowable **before**
 * discovery: remember the station frequency of the last failed join for this accessory and
 * compare it with the current one.  A hit means: don't touch Wi-Fi, say why, and tell the
 * operator to put both ends on the same channel or use hotspot mode.
 *
 * The memory is keyed by the group's **stable prefix** (`DIRECT-dpba2998487eeb`): the
 * suffix changes on every rebuild, so keying by the full SSID would never hit.
 */

package com.okcar.link.wireless;

/**
 * Pure-Java guard for the accessory Wi-Fi join: a consecutive-failure circuit breaker plus a
 * per-accessory "this channel already failed" memory.
 *
 * <p>Time is injected ({@code nowMs} on the monotonic clock for the breaker, {@code wallNowMs}
 * on the wall clock for the durable memory), so the whole class is host-testable — see
 * {@code the desktop joinguard harness}. The caller owns persistence through {@link Store}; the two key
 * names produced here are stable on purpose, so an upgrade keeps the memory it learned.
 */
public final class JoinGuard {

    /** Consecutive failures inside {@link #FAILURE_WINDOW_MS} that open the cooldown. */
    public static final int FAILURE_LIMIT = 3;

    /** Failures further apart than this do not accumulate: the counter restarts. */
    public static final long FAILURE_WINDOW_MS = 120000L;

    /** How long the breaker refuses to touch Wi-Fi once it opens. */
    public static final long COOLDOWN_MS = 60000L;

    /**
     * How long a P2P channel failure is remembered.  After it, the pre-check stops blocking
     * on purpose: the operator may have moved to another AP/channel in the meantime.
     */
    public static final long P2P_FAIL_MEMORY_MS = 30L * 60L * 1000L;

    /** Prefix of the remembered station frequency key. */
    public static final String KEY_FAIL_MHZ_PREFIX = "p2p_fail_mhz_";

    /** Prefix of the "when it failed" key (wall clock, ms). */
    public static final String KEY_FAIL_AT_PREFIX = "p2p_fail_at_";

    /** Minimal persistence the memory needs; the production side is SharedPreferences. */
    public interface Store {
        int getInt(String key, int defValue);

        long getLong(String key, long defValue);

        void put(String key, int intValue, long longValue);

        void remove(String key);
    }

    /** What the guard decided, with the numbers the caller needs for its log line. */
    public static final class Decision {
        public enum Kind {
            /** Nothing known against this join — proceed. */
            ALLOW,
            /** The breaker is open; do not touch Wi-Fi. */
            BREAKER,
            /** This accessory already failed a P2P join from this station channel. */
            CHANNEL_MISMATCH
        }

        public final Kind kind;
        /** Seconds left in the cooldown (BREAKER only). */
        public final long cooldownLeftSec;
        /** Current station frequency in MHz (0 = unknown). */
        public final int stationMhz;
        /** Frequency the previous P2P join failed from (CHANNEL_MISMATCH only). */
        public final int failedMhz;
        /** Stable group key of the SSID (may be empty when the SSID was empty). */
        public final String groupKey;

        private Decision(Kind kind, long cooldownLeftSec, int stationMhz, int failedMhz,
                         String groupKey) {
            this.kind = kind;
            this.cooldownLeftSec = cooldownLeftSec;
            this.stationMhz = stationMhz;
            this.failedMhz = failedMhz;
            this.groupKey = groupKey;
        }

        public boolean allowed() {
            return kind == Kind.ALLOW;
        }
    }

    /* Breaker state.  Static in WifiGroupJoiner before; kept per instance so a harness can
     * run independent scenarios, and synchronized because joins run on session threads. */
    private int mFailures;
    private long mFirstFailureAtMs;
    private long mCooldownUntilMs;

    /** The cooldown check performed on every join entry (both join paths are subject to it). */
    public synchronized Decision cooldownDecision(long nowMs) {
        return cooldownDecisionLocked(nowMs);
    }

    private Decision cooldownDecisionLocked(long nowMs) {
        if (nowMs < mCooldownUntilMs) {
            final long left = (mCooldownUntilMs - nowMs) / 1000L;
            return new Decision(Decision.Kind.BREAKER, left, 0, 0, "");
        }
        return new Decision(Decision.Kind.ALLOW, 0, 0, 0, "");
    }

    /**
     * Records one failed join and reports whether that opened the breaker.  The counter is
     * reset when the breaker opens (so the next three failures can open it again) and the
     * caller keeps its own log line.
     */
    public synchronized boolean recordFailure(long nowMs) {
        if (mFailures == 0 || nowMs - mFirstFailureAtMs > FAILURE_WINDOW_MS) {
            mFailures = 1;
            mFirstFailureAtMs = nowMs;
        } else {
            mFailures++;
        }
        if (mFailures >= FAILURE_LIMIT) {
            mFailures = 0;
            mFirstFailureAtMs = 0;
            mCooldownUntilMs = nowMs + COOLDOWN_MS;
            return true;
        }
        return false;
    }

    /** A successful join clears the breaker state (both paths call this). */
    public synchronized void recordSuccess() {
        mFailures = 0;
        mFirstFailureAtMs = 0;
        mCooldownUntilMs = 0;
    }

    public synchronized long cooldownUntilMs() {
        return mCooldownUntilMs;
    }

    public synchronized int failures() {
        return mFailures;
    }

    /** The channel pre-check for a Wi-Fi Direct group; call it before any Wi-Fi work. */
    public Decision channelDecision(Store store, String ssid, int stationMhz, long wallNowMs) {
        final String key = groupKey(ssid);
        if (stationMhz <= 0 || key.isEmpty()) {
            return new Decision(Decision.Kind.ALLOW, 0, stationMhz, 0, key);
        }
        final int failedMhz = lastFailedFrequency(store, ssid, wallNowMs);
        if (failedMhz > 0 && failedMhz == stationMhz) {
            return new Decision(Decision.Kind.CHANNEL_MISMATCH, 0, stationMhz, failedMhz, key);
        }
        return new Decision(Decision.Kind.ALLOW, 0, stationMhz, failedMhz, key);
    }

    /**
     * The stable part of the accessory's group name: the emulator rebuilds the group on every
     * reconnect and changes the suffix (`DIRECT-dpba2998487eeb-<4 chars>`), so everything
     * after the last dash is dropped.  Quotes (WifiConfiguration wraps SSIDs in them) are
     * stripped first.
     */
    public static String groupKey(String ssid) {
        if (ssid == null) {
            return "";
        }
        final String bare = ssid.replace("\"", "");
        final int dash = bare.lastIndexOf('-');
        return dash > 0 ? bare.substring(0, dash) : bare;
    }

    public static String frequencyKey(String ssid) {
        return KEY_FAIL_MHZ_PREFIX + groupKey(ssid);
    }

    public static String failureAtKey(String ssid) {
        return KEY_FAIL_AT_PREFIX + groupKey(ssid);
    }

    /** Remembers "a P2P join for this accessory failed from a station on this frequency". */
    public void rememberFailure(Store store, String ssid, int stationMhz, long wallNowMs) {
        if (store == null || stationMhz <= 0 || groupKey(ssid).isEmpty()) {
            return;
        }
        store.put(frequencyKey(ssid), stationMhz, 0L);
        store.put(failureAtKey(ssid), 0, wallNowMs);
    }

    /** The remembered frequency, or 0 when nothing is remembered or the memory expired. */
    public static int lastFailedFrequency(Store store, String ssid, long wallNowMs) {
        if (store == null) {
            return 0;
        }
        final long at = store.getLong(failureAtKey(ssid), 0L);
        if (at <= 0 || wallNowMs - at > P2P_FAIL_MEMORY_MS) {
            return 0;
        }
        return store.getInt(frequencyKey(ssid), 0);
    }

    /** Forgets the memory (a successful join, or the caller deciding it is stale). */
    public static void clearFailure(Store store, String ssid) {
        if (store == null) {
            return;
        }
        store.remove(frequencyKey(ssid));
        store.remove(failureAtKey(ssid));
    }
}
