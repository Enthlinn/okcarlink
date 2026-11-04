/*
 * OkcarOS link stack — join the accessory's Wi-Fi group (wireless CarPlay step 3).
 *
 * The accessory does not rely on Bonjour: it hands the device everything it needs
 * inside the iAP2 messages (0x5703 and again in 0x4301) — SSID, cleartext
 * passphrase, security type and channel.  Measured on a working session
 * (2026-09-26/27): 5 GHz, channel 149, security type 2 (WPA/WPA2).
 *
 * Android's supported way for a privileged app to join a specific network without
 * a user dialog is a network *suggestion*; `cmd wifi connect-network` and
 * wpa_supplicant reflect the same request, so the suggestion is submitted first
 * and the failure is reported verbatim (some builds additionally need
 * NEARBY_WIFI_DEVICES / location for the scan result to be selectable).
 *
 * ---------------------------------------------------------------------------
 * The verified join (2026-09-28 field failure)
 * ---------------------------------------------------------------------------
 * `enableNetwork(netId, true)` returning true only means the framework accepted the
 * request.  When the accessory's AP was down the framework logged `Network not found
 * event received: network: "<field AP SSID>"`, never associated, and the phone stayed on its home
 * LAN — while this class reported `submitted` and the direct bootstrap went on to
 * resolve the *home* LAN's default gateway as the AirPlay receiver.  In the same window
 * the accessory's hotspot was torn down mid-session and the phone was deauthenticated
 * (`reason=1`) a few seconds after a successful association.
 *
 * {@link #join} therefore does what the revert already does: it verifies.  The legacy
 * join re-enables/reconnects, polls the active SSID until it is the accessory's, escalates
 * once by re-enabling and once by re-adding the config with the credentials the accessory
 * sent, and only then reports success.  A failure reports whether the AP was even visible
 * in the scan results, and `Result.submitted` never again means "request accepted".
 *
 * {@link WifiManager#saveConfiguration()} returns false on this build (the app holds
 * NETWORK_SETTINGS, not CONFIG_OVERRIDE), so the config is in-memory only; the join
 * re-adds it whenever it is gone and never depends on it persisting.
 *
 * ---------------------------------------------------------------------------
 * Connect records, disconnect removes (2026-09-27 field failure run B, revised 2026-10-02)
 * ---------------------------------------------------------------------------
 * Joining the accessory's hotspot takes the phone off its own LAN, and this device's adb
 * is over Wi-Fi, so cleanup matters.  The original `releaseAll()` only withdrew the
 * suggestion / removed the netId and never removed the accessory's saved network — and with
 * the legacy join path (addNetwork + enableNetwork) the suggestion list was empty, so it
 * returned before removing anything at all.  The watchdog then logged success while the
 * device stayed on the accessory network, and a human had to toggle Wi-Fi.
 *
 * An intermediate version tried to put the phone *back* on the network it had left, verified
 * that, and escalated twice (ending in a Wi-Fi radio toggle).  The operator's rule replaced it:
 * **record on connect, remove that network on disconnect**.  A car that has driven away cannot be
 * returned to the earlier network anyway, the phone re-picks its own on the next scan, and the
 * forced restore is what made disconnects slow, noisy and (once) radio-hostile.
 *
 * So cleanup is now:
 *   1. on connect, the active network's netId + SSID are recorded (SharedPreferences + a
 *      best-effort system property) purely so the log and any later start can name what was
 *      joined and what was left — nothing restores it;
 *   2. on disconnect, the accessory network is disabled + removed (suggestion *and* netId *and*
 *      any configured network carrying the accessory SSID), then swept again and re-checked
 *      through the framework, because a removal can report success and leave the config listed
 *      and a recovery join can add a fresh netId while this runs;
 *   3. the "we joined an accessory network" flag is cleared once the accessory network is really
 *      gone, so a replaced process does not act on a join that is already over.
 */

package com.okcar.link.wireless;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;

import com.okcar.link.port.WifiOps;
import android.net.wifi.WifiNetworkSuggestion;
import android.os.SystemClock;
import android.util.Log;

import com.okcar.link.LinkState;

import java.lang.reflect.Method;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

public final class WifiGroupJoiner {

    private static final String TAG = "OkCarLink/Wireless";

    /** Suggestions we submitted, so they can be withdrawn when the session stops. */
    private static final List<WifiNetworkSuggestion> SUGGESTIONS =
            new ArrayList<WifiNetworkSuggestion>();

    /** netIds added through the legacy API, so they can be removed on stop. */
    private static final List<Integer> SUGGESTIONS_NET_IDS = new ArrayList<Integer>();

    /* ---------------------------------------------------- durable revert record */

    /** Durable record: survives the app process being replaced while off-LAN. */
    private static final String PREFS = "okcar_wifi_revert";
    private static final String KEY_JOIN_ACTIVE = "join_active";
    private static final String KEY_ORIGINAL_SSID = "original_ssid";
    private static final String KEY_ORIGINAL_NET_ID = "original_net_id";
    private static final String KEY_ACCESSORY_SSID = "accessory_ssid";
    private static final String KEY_ACCESSORY_NET_ID = "accessory_net_id";

    /**
     * The same facts mirrored into system properties (best effort, via reflection on
     * the framework's SystemProperties class — the pattern this tree uses elsewhere
     * for hidden platform APIs).  Prefs are the primary record; the properties are
     * the belt-and-braces copy for a process that died before prefs were flushed.
     */
    private static final String PROP_PREFIX = "persist.okcar.wifi_revert.";
    private static final String PROP_JOIN_ACTIVE = PROP_PREFIX + "active";
    private static final String PROP_ORIGINAL_SSID = PROP_PREFIX + "original_ssid";
    private static final String PROP_ORIGINAL_NET_ID = PROP_PREFIX + "original_net_id";
    private static final String PROP_ACCESSORY_SSID = PROP_PREFIX + "accessory_ssid";
    private static final String PROP_ACCESSORY_NET_ID = PROP_PREFIX + "accessory_net_id";

    /* In-memory copy so a failed prefs write cannot lose the original network. */
    private static boolean sJoinActive;
    private static String sOriginalSsid = "";
    private static int sOriginalNetId = -1;
    private static String sAccessorySsid = "";
    private static int sAccessoryNetId = -1;

    /**
     * Direct-join precheck and join circuit breaker: counters, cooldown deadline, and the memory of "this accessory
     * failed on this station channel". The logic itself is pure Java, extracted into {@link JoinGuard} and covered by
     * host regression ({@code the desktop joinguard harness}); this only provides its persistence surface (this class's
     * SharedPreferences), with key names byte-for-byte identical to the old implementation so upgrades don't lose memory.
     */
    private static final JoinGuard sJoinGuard = new JoinGuard();

    /** Production implementation of {@link JoinGuard.Store}: stored in this module's revert prefs.
     *  A persistence failure should only lose one precheck, never make the join itself fail, so every method swallows its own exceptions. */
    private static JoinGuard.Store guardStore(final Context context) {
        final SharedPreferences prefs = prefs(context);
        return new JoinGuard.Store() {
            @Override
            public int getInt(String key, int defValue) {
                try {
                    return prefs.getInt(key, defValue);
                } catch (Throwable t) {
                    return defValue;
                }
            }

            @Override
            public long getLong(String key, long defValue) {
                try {
                    return prefs.getLong(key, defValue);
                } catch (Throwable t) {
                    return defValue;
                }
            }

            @Override
            public void put(String key, int intValue, long longValue) {
                try {
                    prefs.edit().putInt(key, intValue).putLong(key, longValue).apply();
                } catch (Throwable ignored) {
                    /* Failing to remember just costs one precheck */
                }
            }

            @Override
            public void remove(String key) {
                try {
                    prefs.edit().remove(key).apply();
                } catch (Throwable ignored) {
                    /* Failing to clear it doesn't affect the join */
                }
            }
        };
    }

    /** {@link #guardStore}, but a broken Context degrades to JoinGuard's "no memory" path. */
    private static JoinGuard.Store guardStoreQuiet(Context context) {
        try {
            return guardStore(context);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Shared text while the breaker is open (used by both the entry and legacy paths; neither touches Wi-Fi). */
    private static String breakerText(long leftSec) {
        return "join paused for " + leftSec + "s (circuit breaker after "
                + JoinGuard.FAILURE_LIMIT + " consecutive failures) — reconnect from the car";
    }

    /**
     * Step ① of the join: how long a single poll waits for the accessory SSID to become the
     * active network before the join escalates.  The 2026-09-28 field logs show the framework
     * completes an association to the accessory AP in ~1.5 s, so 10 s is generous while keeping
     * the worst case (retry + re-add = 3 polls) inside the direct-bootstrap watchdog.
     */
    private static final long JOIN_POLL_TIMEOUT_MS = 10000L;
    private static final long JOIN_POLL_INTERVAL_MS = 500L;

    /**
     * Budget for the shell join path (`cmd wifi connect-network` + the `cmd wifi status` poll).  It
     * starts before the command runs, so it must cover the connect itself plus a full association;
     * 15 s is the shell path's own deadline and it never holds the caller past it.
     */
    private static final long SHELL_JOIN_TIMEOUT_MS = 15000L;

    /** Step ②: how long a single poll waits for the original SSID to come back. */
    private static final long REVERT_POLL_INTERVAL_MS = 1000L;
    /** Step ③: the hard recovery gives the radio this long to come back up. */
    private static final long RADIO_UP_TIMEOUT_MS = 15000L;

    /* ------------------------------------------- bounded revert self-test (adb) */

    /**
     * The accessory AP the bounded self-test should join: credentials are **supplied by the operator at runtime**, no longer compiled into the code.
     *
     * <p>This used to hold the field accessory AP's SSID and passphrase constants — so every
     * outbound dex carried a real set of wireless credentials. Now it reads two lines from {@link #SELF_TEST_CONF}
     * ({@code ssid=…} / {@code passphrase=…}), so neither the repo nor shipped packages contain credentials; the experiment just writes this file first:
     *
     * <pre>printf 'ssid=…\npassphrase=…\n' &gt; /data/local/tmp/okcar-wifi-selftest.conf</pre>
     *
     * When the file is missing or has no ssid, the self-test **refuses explicitly** and logs one line; it never falls back to any built-in value.
     */
    public static final String SELF_TEST_CONF = "/data/local/tmp/okcar-wifi-selftest.conf";
    public static final int SELF_TEST_DEFAULT_HOLD_SECONDS = 25;
    private static final long SELF_TEST_JOIN_TIMEOUT_MS = 15000L;

    /**
     * Budget for the Wi-Fi Direct attempt.  It has to cover a progressive {@code p2p_find}
     * sweep (the group owner sits on 5 GHz channel 149, outside the social channels a find
     * reaches first) plus group formation.  An accessory that names a group and then never
     * accepts a client is a case the station fallback still has to reach, so this is a budget
     * the joiner spends at most once per credential exchange.
     */
    private static final long P2P_JOIN_TIMEOUT_MS = 26000L;

    /** True when the SSID names a Wi-Fi Direct group rather than an access point. */
    static boolean looksLikeP2pGroup(String ssid) {
        return ssid != null && ssid.length() > 7 && ssid.regionMatches(true, 0, "DIRECT-", 0, 7);
    }

    public static final class Result {
        public final boolean submitted;
        public final String detail;
        /** The netId the accessory network got, or -1 when it is suggestion-only. */
        public final int netId;

        Result(boolean submitted, String detail) {
            this(submitted, detail, -1);
        }

        Result(boolean submitted, String detail, int netId) {
            this.submitted = submitted;
            this.detail = detail;
            this.netId = netId;
        }

        public String describe() {
            return (submitted ? "submitted: " : "failed: ") + detail;
        }
    }

    private WifiGroupJoiner() {
    }

    public static synchronized Result join(Context context,
                                           WirelessMessages.StartSession session) {
        if (session == null || session.ssid.isEmpty()) {
            return new Result(false, "no SSID in the session");
        }
        /* The breaker sits at the **entry**: both paths (true Wi-Fi Direct and station) are subject to it. Previously it only counted on the legacy
         * path, while it was the P2P→station path that actually caused "217 disconnects Wi-Fi, reconnects, disconnects again" in the field, so the
         * breaker never took effect at all (measured 2026-10-04). */
        final long nowMs = android.os.SystemClock.elapsedRealtime();
        final JoinGuard.Decision entry = sJoinGuard.cooldownDecision(nowMs);
        if (entry.kind == JoinGuard.Decision.Kind.BREAKER) {
            final String paused = breakerText(entry.cooldownLeftSec);
            LinkState.get().log("wireless", "wifi join " + paused);
            return new Result(false, paused);
        }
        /* Path 0 — the accessory may be a Wi-Fi Direct group owner.
         *
         * the emulator hands over `DIRECT-dp7c1c8e724ed2-<4 random chars>` plus a 16-character
         * passphrase in the iAP2 0x5703 credential block, and its own log shows
         * `P2P-GROUP-STARTED p2p0 GO ssid="DIRECT-…" freq=5745` — the group lives on p2p0, not
         * on an AP that wlan0 can associate with.  Joining it through WifiManager makes the
         * phone a *station*: it gives up the home LAN (Wi-Fi adb dies for the whole session,
         * 2026-10-01) and the framework keeps re-evaluating a network with no internet, which
         * showed up as a teardown every ~1-2 minutes.  Wi-Fi Direct keeps wlan0 exactly where
         * it is and puts the group on p2p0.
         *
         * The `DIRECT-` prefix is the gate, and it is a *measured* gate now, not a guess.
         * An earlier revision also probed every non-`DIRECT-` SSID, on the theory that a GO is
         * not obliged to name its network that way.  That was removed on 2026-10-01 after the
         * A/B on the test rig settled it: the same accessory, the same real iPhone, works in
         * Manual-hotspot mode (`backend=Manual hotspot iface=wlan1`) and stalls forever in
         * Wi-Fi Direct mode, and a plain SoftAP is invisible to `discoverPeers` by protocol —
         * its probe response carries no P2P IE.  So the probe could only ever burn its whole
         * budget (12 s on every connection) and then fall through to the station join anyway.
         * The the production head unit family is therefore a station join by nature; keeping wlan0 on the
         * LAN for those needs a second concurrent station interface, which no public
         * Wi-Fi API can bring up. */
        /* The gate is the announced SSID alone (measured 2026-10-01): there is no switch. */
        if (looksLikeP2pGroup(session.ssid)) {
            /* Precheck: this accessory is known to have failed to join on this station channel (within 30 minutes) → it will certainly fail, say so
             * plainly and don't touch Wi-Fi (this round takes a dozen seconds in the field and also tears the STA down and reconnects it). */
            final int staMhz = stationFrequencyMhz(context);
            final JoinGuard.Decision channel = sJoinGuard.channelDecision(
                    guardStoreQuiet(context), session.ssid, staMhz, System.currentTimeMillis());
            if (channel.kind == JoinGuard.Decision.Kind.CHANNEL_MISMATCH) {
                final String pre = "pre-check: a P2P join for this car already failed from a "
                        + channel.failedMhz
                        + "MHz station less than 30 minutes ago (the car pins its group to its own"
                        + " station's channel, and cross-channel P2P does not work here) — not"
                        + " retrying from the same channel. Connect both ends to the same AP/channel,"
                        + " or use hotspot mode.";
                LinkState.get().log("wireless", "wifi join " + pre);
                Log.i(TAG, "wifi join " + pre);
                return new Result(false, pre);
            }
            final WifiP2pGroupJoiner.Result p2p = WifiP2pGroupJoiner.join(
                    context, session.ssid, session.passphrase, P2P_JOIN_TIMEOUT_MS);
            if (p2p.ok) {
                final String detail = "p2p " + p2p.detail;
                LinkState.get().log("wireless", "wifi join " + detail);
                Log.i(TAG, "wifi join " + detail);
                /* Nothing on wlan0 changed, so there is no original network to capture and
                 * nothing to revert: the whole revert bookkeeping below is for the station
                 * path only. */
                sJoinGuard.recordSuccess();
                JoinGuard.clearFailure(guardStoreQuiet(context), session.ssid);
                return new Result(true, detail);
            }
            recordJoinFailure(session.ssid);
            sJoinGuard.rememberFailure(guardStoreQuiet(context), session.ssid, staMhz,
                    System.currentTimeMillis());
            /* Key: do **not** degrade into "join the DIRECT- group as if it were a normal AP" via station.
             *
             * That path (the old behavior) makes the phone abandon the home LAN to associate with a group that has no internet: the framework then
             * tears it down every 1–2 minutes, which is exactly the operator-visible "217 disconnects Wi-Fi → reconnects a while later → drops again,
             * repeating forever"; after a few rounds the Wi-Fi stack is churned until the system freezes — even a later session that fell back to
             * hotspot dies with it (measured 2026-10-04). Non-DIRECT- accessories (the production head unit ones that use SoftAP) never go through this
             * branch, so they are unaffected. */
            final String detail = "p2p path failed (" + p2p.detail + ")" + stationChannelHint(context)
                    + " and a station join on a DIRECT- group is exactly the loop that drops Wi-Fi"
                    + " every 1~2 minutes — refusing it; reconnect from the car";
            LinkState.get().log("wireless", "wifi join failed: " + detail);
            Log.w(TAG, "wifi join failed: " + detail);
            return new Result(false, detail);
        }
        final WifiOps wifi = wifiOpsOf(context);
        if (wifi == null) {
            return new Result(false, "no WifiManager");
        }
        /* Capture the network we are about to leave *before* any change.  A second
         * join (0x4301 retransmit / a new accessory) must not overwrite the record
         * with the accessory network we are still on — but a record left over from a
         * session that is already over must not stop this join from capturing the real
         * original network either (see dropStaleRecord). */
        dropStaleRecord(context);
        if (!isJoinRecorded(context)) {
            captureOriginalNetwork(context, wifi, session.ssid);
        }
        /* Path 1 — legacy system-app API.  A *suggestion* only puts the network in
         * the candidate list: while the phone is happily connected to its saved
         * network the framework will not switch, which is exactly what happened with
         * the real emulator accessory (2026-09-27 00:55: `status=0` and then nothing).
         * `addNetwork` + `enableNetwork(netId, true)` makes the framework actually
         * associate, and a platform app (we run as android.uid.system) is allowed to
         * use it.
         *
         * `enableNetwork` returning true only means "the request was accepted"; it does
         * not mean the phone associated.  The 2026-09-28 field logs show the framework
         * answering a join with `Network not found event received: network: "<field AP SSID>"` (the
         * accessory AP was down) and the phone silently staying on its home LAN, while
         * this method had already reported success.  The join therefore verifies the
         * association the same way the revert verifies the way back. */
        /* Path 0 — our own `cmd wifi connect-network` with the security type the accessory actually
         * announced, verified against `cmd wifi status`.
         *
         * Why this exists (2026-10-08): the credentials in 0x5703/0x4301 were parsed into a log line
         * and then left to the framework's auto-join of an already-saved network.  With 217's saved
         * "K30" configuration gone the whole wireless chain parked at "credentials received": the
         * receiver had opened its hotspot and AirPlay server, and nobody ever joined.  The framework
         * path below cannot be that action — it derives the keyword from "is there a passphrase"
         * (so a WPA3 hand-off was joined as wpa2 and an unknown securityType was silently treated as
         * WPA2), and it reports "submitted" for a request the supplicant may never act on.
         *
         * The shell path is bounded, never throws, and returns a numbered error.  It escalates to
         * the framework path (which is also what registers the netId + handle this class reverts
         * later) only when that path can express the same security type — see
         * {@link WirelessMessages.Security#legacyPathExpresses(int)}; a WEP or WPA3 hand-off whose
         * shell join failed is refused instead of being retried with a wpa2 keyword. */
        /* Respect the same circuit breaker the framework path consults: when it is open, no join of
         * any kind touches the radio (that is what "stop hammering Wi-Fi so the stack recovers"
         * means).  joinWithLegacyApi then reports the break in its own words. */
        final JoinGuard.Decision shellEntry =
                sJoinGuard.cooldownDecision(android.os.SystemClock.elapsedRealtime());
        final AccessoryWifiJoin.Result shell = shellEntry.kind == JoinGuard.Decision.Kind.BREAKER
                ? null : joinViaShell(session);
        if (shell != null) {
            if (shell.connected) {
                recordAccessoryNetwork(context, session.ssid, shell.netId);
                sJoinGuard.recordSuccess();
                JoinGuard.clearFailure(guardStoreQuiet(context), session.ssid);
                logJoin("shell join OK: " + shell.describe());
                LinkState.get().log("wireless", "wifi join (cmd wifi) " + shell.describe());
                return new Result(true, "cmd wifi: " + shell.describe(), shell.netId);
            }
            recordAccessoryNetwork(context, session.ssid, -1);
            if (!shell.retryable() || !WirelessMessages.Security.legacyPathExpresses(
                    session.securityType)) {
                /* Nothing to retry with — or nothing the framework path could express: it derives
                 * the keyword from "is there a passphrase" and only ever sends open/wpa2, so
                 * escalating a WEP or WPA3 hand-off would join with the wrong keyword.  That is the
                 * guess this whole path exists to stop, so refuse.  Report and let the session's
                 * bounded watchdog put the radio back; no loop is started here. */
                final String why = shell.retryable()
                        ? shell.describe() + " — the framework path cannot express security="
                                + WirelessMessages.Security.nameOf(session.securityType)
                                + " (it only emits open|wpa2), so it is not tried"
                        : shell.describe();
                logJoin("shell join REFUSED: " + why);
                LinkState.get().log("wireless", "wifi join refused: " + why);
                return new Result(false, why, -1);
            }
            logJoin("shell join failed (" + shell.describe()
                    + ") — escalating to the framework API path");
        }

        final Result legacy = joinWithLegacyApi(context, wifi, session);
        /* Record the accessory network either way: the revert must remove whatever this
         * join created (netId and SSID) even when the association did not complete. */
        recordAccessoryNetwork(context, session.ssid, legacy.netId);
        if (legacy.submitted) {
            LinkState.get().log("wireless", "wifi join (legacy API) " + legacy.detail);
            return legacy;
        }
        if (legacy.netId >= 0) {
            /* The legacy API itself worked — a config exists and enableNetwork accepted it —
             * so the failure is an association failure, not an API failure.  A suggestion
             * would not associate any better, and pretending otherwise is what made the
             * bootstrap resolve the receiver from the home LAN's gateway.  Report it. */
            LinkState.get().log("wireless", "wifi join (legacy API) NOT ASSOCIATED: "
                    + legacy.detail);
            return legacy;
        }
        LinkState.get().log("wireless", "legacy wifi join failed: " + legacy.detail
                + " — falling back to a suggestion");

        try {
            final WifiNetworkSuggestion.Builder builder =
                    new WifiNetworkSuggestion.Builder().setSsid(session.ssid);
            if (!session.passphrase.isEmpty()) {
                builder.setWpa2Passphrase(session.passphrase);
            }
            final WifiNetworkSuggestion suggestion = builder.build();
            /* Only the List form is public framework API (the single-suggestion
             * overload is @SystemApi with a package-name parameter). */
            final int status = wifi.addNetworkSuggestions(
                    java.util.Collections.singletonList(suggestion));
            final boolean ok = status == WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS;
            if (ok) {
                SUGGESTIONS.add(suggestion);
            }
            final String detail = "ssid=" + session.ssid
                    + " channel=" + session.channel
                    + " security=" + session.securityType
                    + " status=" + status + (ok ? "" : " (suggestion rejected)");
            LinkState.get().log("wireless", "wifi join " + detail);
            Log.i(TAG, "wifi join " + detail);
            return new Result(ok, detail);
        } catch (Throwable t) {
            /* SecurityException (missing runtime permission) or a vendor rejection. */
            Log.w(TAG, "wifi suggestion failed: " + t);
            LinkState.get().log("wireless", "wifi suggestion failed: " + t);
            return new Result(false, t.toString());
        }
    }

    /**
     * The shell join: `cmd wifi connect-network <ssid> <open|wep|wpa2|wpa3> [<passphrase>]` with the
     * security type the accessory announced (mapped by {@link WirelessMessages.Security}), then
     * `cmd wifi status` until it prints {@code Wifi is connected to "<ssid>"}.
     *
     * <p>Bounded by {@link #SHELL_JOIN_TIMEOUT_MS}, and it never throws into the caller: a join
     * failure is a {@link AccessoryWifiJoin.Result} with a numbered error.  Null (not a Result) only
     * when the shell path cannot run at all, which keeps the framework path available.
     */
    private static AccessoryWifiJoin.Result joinViaShell(WirelessMessages.StartSession session) {
        try {
            return AccessoryWifiJoin.join(session.ssid, session.securityType, session.passphrase,
                    SHELL_JOIN_TIMEOUT_MS, AccessoryWifiJoin.DEFAULT_POLL_MS,
                    AccessoryWifiJoin.rootShell(), AccessoryWifiJoin.systemClock());
        } catch (Throwable t) {
            /* join() is written not to throw; if a JVM-level failure escapes (no /bin/sh, security
             * manager), say so and let the framework path run rather than dying inside the join. */
            logJoin("shell join threw (" + t + ") — using the framework API path");
            return null;
        }
    }

    /**
     * addNetwork (or reuse of an already-configured accessory network) + enableNetwork +
     * reconnect, followed by an <em>explicit verification</em> that the accessory SSID really
     * became the active network: a request the framework accepted is not a join.
     *
     * <p>Escalation, in order, all inside {@link #JOIN_POLL_TIMEOUT_MS} per poll:
     * <ol>
     *   <li>re-{@code enableNetwork} + {@code reconnect} (the framework may have dropped the
     *       request because it was mid-scan or the candidate list was stale);</li>
     *   <li>remove the config and add it again with the credentials the accessory handed us
     *       (a lingering config for the same SSID may carry a different passphrase), then
     *       enable + reconnect.</li>
     * </ol>
     *
     * <p>{@code saveConfiguration()} is attempted but deliberately never required: on this
     * build it returns {@code false} (the app holds {@code NETWORK_SETTINGS} but not
     * {@code CONFIG_OVERRIDE}), so the config lives only in the in-memory store.  The join
     * therefore never relies on a persisted config, and the revert still removes it by netId
     * and by SSID.
     */
    private static Result joinWithLegacyApi(Context context, WifiOps wifi,
                                            WirelessMessages.StartSession session) {
        final long entryNow = android.os.SystemClock.elapsedRealtime();
        final JoinGuard.Decision entry = sJoinGuard.cooldownDecision(entryNow);
        if (entry.kind == JoinGuard.Decision.Kind.BREAKER) {
            /* Breaker open: don't touch Wi-Fi, fail immediately. */
            final String detail = breakerText(entry.cooldownLeftSec);
            logJoin("CIRCUIT BREAKER ACTIVE: " + display(session.ssid) + " — " + detail);
            return new Result(false, detail, -1);
        }
        try {
            int netId = findConfiguredNetId(wifi, session.ssid);
            final boolean reused = netId >= 0;
            if (reused) {
                logJoin("found an existing config for " + display(session.ssid) + ": netId="
                        + netId + " — reusing it instead of adding a second one");
                /* Tell the adapter about it: a shell-backed one only knows the handles it added
                 * itself, so without this the reuse branch degenerates into enableNetwork=false. */
                wifi.adoptNetwork(netId, session.ssid, session.passphrase);
            } else {
                dropStaleDirectConfigs(wifi, session.ssid);
                netId = addAccessoryConfig(wifi, session);
                if (netId < 0) {
                    return new Result(false, "addNetwork -> " + netId, -1);
                }
                logJoin("addNetwork -> netId=" + netId + " ssid=" + display(session.ssid));
            }
            /* Register for the revert before anything else can fail: whatever we created or
             * enabled must be removable. */
            SUGGESTIONS_NET_IDS.add(netId);

            final boolean enabled = wifi.enableNetwork(netId, true);
            boolean reconnected = false;
            try {
                reconnected = wifi.reconnect();
            } catch (Throwable t) {
                logJoin("reconnect() threw: " + t);
            }
            final boolean saved = wifi.saveConfiguration();
            logJoin("enableNetwork(netId=" + netId + ", disableOthers=true)=" + enabled
                    + " reconnect=" + reconnected + " saveConfiguration=" + saved
                    + " (save=false only means the config is not persisted — the join does not"
                    + " depend on it)");
            if (!enabled) {
                return new Result(false, "enableNetwork(" + netId + ") = false", netId);
            }

            boolean associated = awaitActiveSsid(wifi, session.ssid, JOIN_POLL_TIMEOUT_MS,
                    "join-poll");
            int escalations = 0;
            if (!associated) {
                escalations++;
                logJoin("ESCALATION 1: " + display(session.ssid) + " is not active after "
                        + JOIN_POLL_TIMEOUT_MS + "ms — re-enabling netId=" + netId
                        + " and reconnecting");
                wifi.enableNetwork(netId, true);
                wifi.reconnect();
                associated = awaitActiveSsid(wifi, session.ssid, JOIN_POLL_TIMEOUT_MS,
                        "join-retry-poll");
            }
            if (!associated) {
                escalations++;
                logJoin("ESCALATION 2: still not on " + display(session.ssid)
                        + " — removing netId=" + netId + " and adding it again with the"
                        + " credentials from the accessory");
                try {
                    wifi.disableNetwork(netId);
                    wifi.removeNetwork(netId);
                } catch (Throwable t) {
                    logJoin("removing netId=" + netId + " before the re-add threw: " + t);
                }
                SUGGESTIONS_NET_IDS.remove(Integer.valueOf(netId));
                final int fresh = addAccessoryConfig(wifi, session);
                if (fresh >= 0) {
                    SUGGESTIONS_NET_IDS.add(fresh);
                    netId = fresh;
                    final boolean freshEnabled = wifi.enableNetwork(netId, true);
                    wifi.reconnect();
                    logJoin("re-added as netId=" + netId + " enableNetwork=" + freshEnabled);
                    associated = awaitActiveSsid(wifi, session.ssid, JOIN_POLL_TIMEOUT_MS,
                            "join-readd-poll");
                }
            }

            final String detail = "netId=" + netId + " enableNetwork=" + enabled
                    + " save=" + saved + " reused=" + reused + " escalations=" + escalations
                    + " verified=" + associated + " ssid=" + session.ssid;
            if (associated) {
                sJoinGuard.recordSuccess();
                return new Result(true, detail, netId);
            }
            recordJoinFailure(session.ssid);
            return new Result(false, detail + " — the framework never made "
                    + display(session.ssid) + " the active network: "
                    + joinFailureReason(wifi, session.ssid), netId);
        } catch (Throwable t) {
            recordJoinFailure(session.ssid);
            return new Result(false, "addNetwork threw " + t, -1);
        }
    }

    /** Record one failure; reaching the limit within the window opens the cooldown (and resets the count so another round is allowed after it). */
    private static void recordJoinFailure(String ssid) {
        if (sJoinGuard.recordFailure(android.os.SystemClock.elapsedRealtime())) {
            logJoin("CIRCUIT BREAKER: " + JoinGuard.FAILURE_LIMIT + " consecutive join failures for "
                    + display(ssid) + " — pausing joins for " + (JoinGuard.COOLDOWN_MS / 1000L)
                    + "s so the Wi-Fi framework (and the phone) recovers");
        }
    }

    /**
     * Clear stale P2P group configs we left behind that no longer correspond to any session. The receiver changes its SSID suffix on every
     * reconnect; without cleanup, saved networks pile up round after round and the framework must scan through them each time. Bounded (at most 3
     * per call), and it never touches the target SSID or the currently active network.
     */
    private static void dropStaleDirectConfigs(WifiOps wifi, String keepSsid) {
        try {
            final String active = wifi.activeSsid();
            int dropped = 0;
            for (WifiConfiguration config : configuredNetworks(wifi)) {
                if (dropped >= 3) {
                    break;
                }
                final String ssid = config.SSID;
                if (ssid == null || !ssid.replace("\"", "").startsWith("DIRECT-")) {
                    continue;
                }
                if (sameSsid(ssid, keepSsid) || sameSsid(ssid, active)) {
                    continue;
                }
                try {
                    wifi.disableNetwork(config.networkId);
                    if (wifi.removeNetwork(config.networkId)) {
                        SUGGESTIONS_NET_IDS.remove(Integer.valueOf(config.networkId));
                        dropped++;
                    }
                } catch (Throwable ignored) {
                    /* A single failure doesn't affect the join */
                }
            }
            if (dropped > 0) {
                logJoin("dropped " + dropped + " stale DIRECT- config(s) before adding "
                        + display(keepSsid));
            }
        } catch (Throwable t) {
            logJoin("stale DIRECT- cleanup skipped: " + t);
        }
    }

    /**
     * Field conclusion (2026-10-04, measured on both ends): the head unit **pins its P2P group to its station's channel**
     * (the emulator's WifiP2pGroupManager "follows the station" — it rebuilds the group when the station changes channel, which is also
     * why the group-name suffix changes every round). If the phone is on another 5GHz channel (especially a 160MHz-wide one),
     * cross-channel concurrency is impossible on this ROM → join fails → the framework repeatedly reconciles STA/P2P (showing as
     * "Wi-Fi drops, reconnects, drops") → the system is dragged until input dispatch times out and the launcher is ANR-killed (what the user sees as "opening an app freezes the phone").
     *
     * <p>Put this sentence into the failure detail: next time it is obvious at a glance that "the channels don't match", instead of digging for two more hours.
     *
     * <p>Current station frequency (MHz), or 0 if unreadable.
     */
    private static int stationFrequencyMhz(Context context) {
        try {
            final android.net.wifi.WifiManager wifi =
                    (android.net.wifi.WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            if (wifi == null || wifi.getConnectionInfo() == null) {
                return 0;
            }
            final int mhz = wifi.getConnectionInfo().getFrequency();
            return mhz > 0 ? mhz : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static String stationChannelHint(Context context) {
        try {
            final int mhz = stationFrequencyMhz(context);
            if (mhz <= 0) {
                return "";
            }
            return " (this phone's station is on " + mhz + "MHz; the car pins its P2P group to its"
                    + " own station's channel, so both ends must be on the same channel — or use"
                    + " hotspot mode)";
        } catch (Throwable t) {
            return "";
        }
    }

    /* The P2P group frequency "precheck memory" itself has been extracted into JoinGuard (pure Java, host regression the desktop joinguard harness):
     * the key uses the group name's stable prefix, memory lasts 30 minutes, and a hit with the same current station frequency blocks this join round. */

    /** The netId of an already-configured network with this SSID, or -1. */
    private static int findConfiguredNetId(WifiOps wifi, String ssid) {
        for (WifiConfiguration config : configuredNetworks(wifi)) {
            if (sameSsid(config.SSID, ssid)) {
                return config.networkId;
            }
        }
        return -1;
    }

    /** Adds the accessory network with the credentials the accessory supplied. */
    private static int addAccessoryConfig(WifiOps wifi,
                                          WirelessMessages.StartSession session) {
        final WifiConfiguration config = new WifiConfiguration();
        config.SSID = quote(session.ssid);
        if (!session.passphrase.isEmpty()) {
            config.preSharedKey = quote(session.passphrase);
            config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
        } else {
            config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
        }
        config.hiddenSSID = false;
        /* MIUI randomizes the MAC for a freshly added network, and an accessory SoftAP that
         * only knows the phone it paired with rejects that association outright — measured
         * 2026-09-30 on the production head unit: level2FailureCode=ASSOCIATION_REJECTION with
         * useRandomizedMac=true, while the same join with the device MAC succeeded first try
         * (192.0.2.16 on the accessory AP).  An accessory AP is a closed point-to-point link
         * that hands out its own credentials, so the device address is the correct choice
         * here; leaving randomization on simply cannot associate. */
        config.setMacRandomizationSetting(WifiConfiguration.RANDOMIZATION_NONE);
        return wifi.addNetwork(config);
    }

    /**
     * Polls the active SSID until it equals {@code wantSsid}.  This is what makes
     * {@link Result#submitted} mean "we are on the accessory's network", not "the framework
     * accepted the request".
     */
    private static boolean awaitActiveSsid(WifiOps wifi, String wantSsid, long timeoutMs,
                                           String phase) {
        final String want = normalize(wantSsid);
        final long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        int round = 0;
        while (true) {
            round++;
            final WifiInfo info = safeConnectionInfo(wifi);
            /* Port build: the framework redacts SSID without location permission, which
             * read as "join failed" and tore down good associations. Prefer the backend's
             * own view (shell-backed on a rooted phone) and only fall back to WifiInfo. */
            String active = normalize(wifi.activeSsid());
            if (active.isEmpty() && info != null) {
                active = normalize(info.getSSID());
            }
            logJoin(phase + " " + round + ": active ssid=" + display(active)
                    + " netId=" + (info == null ? -1 : info.getNetworkId())
                    + (info == null ? "" : " state=" + info.getSupplicantState())
                    + " want=" + display(want));
            if (!want.isEmpty() && want.equals(active)) {
                return true;
            }
            if (SystemClock.elapsedRealtime() >= deadline) {
                return false;
            }
            sleep(JOIN_POLL_INTERVAL_MS);
        }
    }

    /**
     * Why the verified join failed: whether the accessory AP was even in the scan results
     * separates "the accessory is not there" from "it is there but we did not associate".
     */
    private static String joinFailureReason(WifiOps wifi, String ssid) {
        try {
            final List<ScanResult> results = wifi.getScanResults();
            if (results != null) {
                for (ScanResult result : results) {
                    if (sameSsid(result.SSID, ssid)) {
                        return "the AP was in the scan results (" + result.BSSID + " ch"
                                + result.frequency + " rssi=" + result.level
                                + ") but the association did not complete";
                    }
                }
            }
        } catch (Throwable t) {
            return "reading the scan results failed: " + t;
        }
        return "the AP was not in the scan results — the accessory's hotspot is not up";
    }

    private static String quote(String text) {
        return '"' + text + '"';
    }

    /* ------------------------------------------------------- capture + persist */

    /**
     * Records the network we are about to leave, so the revert has somewhere to go back to.
     *
     * <p>Refuses to record the accessory's own SSID.  The phone can already be on the accessory
     * network when a join starts — the accessory's hotspot came up first and the framework
     * auto-joined a config from an earlier session — and recording that as "the network to go back
     * to" made the revert verify instantly against the very network it was supposed to leave
     * (measured 2026-10-02: {@code original="car" netId=258 … VERIFIED after 181ms}).  With no
     * original recorded the revert's job is exactly what it should be: leave the accessory network
     * and end up on any real network.
     */
    private static void captureOriginalNetwork(Context context, WifiOps wifi, String accessorySsid) {
        final WifiInfo info = safeConnectionInfo(wifi);
        String ssid = info == null ? "" : normalize(info.getSSID());
        int netId = info == null ? -1 : info.getNetworkId();
        if (isUnknownSsid(ssid)) {
            ssid = "";
            netId = -1;
        } else if (sameSsid(ssid, accessorySsid)) {
            logRevert("the phone is already on the accessory network " + display(ssid)
                    + " — recording no original (the revert only has to leave it)");
            ssid = "";
            netId = -1;
        }
        sJoinActive = true;
        sOriginalSsid = ssid;
        sOriginalNetId = netId;
        final SharedPreferences.Editor editor = prefs(context).edit();
        editor.putBoolean(KEY_JOIN_ACTIVE, true);
        editor.putString(KEY_ORIGINAL_SSID, ssid);
        editor.putInt(KEY_ORIGINAL_NET_ID, netId);
        /* commit(), not apply(): the process may be killed by the Wi-Fi change. */
        editor.commit();
        setProp(PROP_JOIN_ACTIVE, "1");
        setProp(PROP_ORIGINAL_SSID, ssid);
        setProp(PROP_ORIGINAL_NET_ID, Integer.toString(netId));
        logRevert("captured the pre-join network: ssid=" + display(ssid) + " netId=" + netId
                + " (persisted so a replaced process can still revert)");
    }

    private static void recordAccessoryNetwork(Context context, String ssid, int netId) {
        sAccessorySsid = ssid == null ? "" : ssid;
        sAccessoryNetId = netId;
        final SharedPreferences.Editor editor = prefs(context).edit();
        editor.putString(KEY_ACCESSORY_SSID, sAccessorySsid);
        editor.putInt(KEY_ACCESSORY_NET_ID, netId);
        editor.commit();
        setProp(PROP_ACCESSORY_SSID, sAccessorySsid);
        setProp(PROP_ACCESSORY_NET_ID, Integer.toString(netId));
    }

    private static boolean isJoinRecorded(Context context) {
        if (sJoinActive) {
            return true;
        }
        if (context != null && prefs(context).getBoolean(KEY_JOIN_ACTIVE, false)) {
            return true;
        }
        return "1".equals(getProp(PROP_JOIN_ACTIVE, "0"));
    }

    /**
     * The persisted record, merged from memory, SharedPreferences and the system
     * property copy so a partially-lost record still reverts as well as possible.
     */
    private static void loadRecord(Context context, RevertState state) {
        final SharedPreferences p = prefs(context);
        state.joinActive = sJoinActive
                || p.getBoolean(KEY_JOIN_ACTIVE, false)
                || "1".equals(getProp(PROP_JOIN_ACTIVE, "0"));
        state.originalSsid = firstNonEmpty(sOriginalSsid,
                p.getString(KEY_ORIGINAL_SSID, ""), getProp(PROP_ORIGINAL_SSID, ""));
        state.originalNetId = firstNonNegative(sOriginalNetId,
                p.getInt(KEY_ORIGINAL_NET_ID, -1), toInt(getProp(PROP_ORIGINAL_NET_ID, "-1"), -1));
        state.accessorySsid = firstNonEmpty(sAccessorySsid,
                p.getString(KEY_ACCESSORY_SSID, ""), getProp(PROP_ACCESSORY_SSID, ""));
        state.accessoryNetId = firstNonNegative(sAccessoryNetId,
                p.getInt(KEY_ACCESSORY_NET_ID, -1), toInt(getProp(PROP_ACCESSORY_NET_ID, "-1"), -1));
    }

    /**
     * Forgets the whole record, original network included.
     *
     * <p>The original network is part of the same record: once the join it belongs to is over (or
     * never happened), keeping it is how a <em>later</em> session ends up reverting to a network an
     * <em>earlier</em> one left — a leftover, not recovery information.  A new join captures the
     * original again before it changes anything.
     */
    private static void clearRecord(Context context) {
        sJoinActive = false;
        sOriginalSsid = "";
        sOriginalNetId = -1;
        sAccessorySsid = "";
        sAccessoryNetId = -1;
        final SharedPreferences.Editor editor = prefs(context).edit();
        editor.putBoolean(KEY_JOIN_ACTIVE, false);
        editor.putString(KEY_ORIGINAL_SSID, "");
        editor.putInt(KEY_ORIGINAL_NET_ID, -1);
        editor.putString(KEY_ACCESSORY_SSID, "");
        editor.putInt(KEY_ACCESSORY_NET_ID, -1);
        editor.commit();
        setProp(PROP_JOIN_ACTIVE, "0");
        setProp(PROP_ORIGINAL_SSID, "");
        setProp(PROP_ORIGINAL_NET_ID, "-1");
        setProp(PROP_ACCESSORY_SSID, "");
        setProp(PROP_ACCESSORY_NET_ID, "-1");
        logRevert("cleared the persisted accessory-join record");
    }

    /**
     * Whether the persisted record still describes where this phone actually is.
     *
     * <p>A record only means something while we are on the accessory network it names: its whole
     * purpose is to take us back to the network we left.  Once the phone is anywhere else — the
     * previous session already reverted, the user switched networks, the accessory AP is gone — it
     * is a leftover with two costs, both measured on 217:
     *
     * <ul>
     *   <li>the next {@link #join} sees {@link #isJoinRecorded} and skips capturing the original
     *       network, so the record keeps naming a network the phone left long ago;</li>
     *   <li>the service-start self-heal acts on it (re-associating that old network, escalating to
     *       a radio toggle) for a join that is already over.</li>
     * </ul>
     *
     * <p>Conservative by construction: with no association to judge by (Wi-Fi off, or the framework
     * reporting {@code <unknown ssid>}) it says "not stale" and keeps the record, because it is then
     * the only way back.
     */
    private static boolean recordIsStale(Context context, RevertState state) {
        if (!state.joinActive || state.accessorySsid == null || state.accessorySsid.isEmpty()) {
            return false;
        }
        final WifiOps wifi = wifiOpsOf(context);
        if (wifi == null) {
            return false;
        }
        final WifiInfo info = safeConnectionInfo(wifi);
        final String active = info == null ? "" : normalize(info.getSSID());
        if (active.isEmpty() || isUnknownSsid(active)) {
            return false;
        }
        return !active.equalsIgnoreCase(state.accessorySsid);
    }

    /**
     * Drops a record that no longer describes reality, so neither the next join nor the next
     * service-start heal can act on a previous session's facts.
     */
    private static void dropStaleRecord(Context context) {
        final RevertState state = new RevertState();
        loadRecord(context, state);
        if (!recordIsStale(context, state)) {
            return;
        }
        logRevert("the recorded accessory join is stale: we are on " + describeActive(context)
                + ", not on " + display(state.accessorySsid)
                + " — forgetting it instead of acting on it");
        clearRecord(context);
    }

    /* ------------------------------------------------------------- the revert */

    /**
     * Disconnect cleanup: withdraw the accessory's Wi-Fi network, so the phone neither holds it nor
     * can silently auto-return to the accessory's hotspot after the session.
     *
     * <p>It deliberately does <em>not</em> try to restore the network the phone was on before the
     * session.  The operator's rule is "record on connect, remove that network on disconnect": once
     * the car has driven away the phone could not reach the earlier network from there anyway, and
     * waiting for it is what made a disconnect take 1.9-64s, escalate twice, and (before that path
     * was fixed) switch the radio off while the shell that asked for it was already gone.  With the
     * accessory config removed the phone re-picks its own network from the next scan on its own —
     * measured 2026-10-02: it re-associated with its home SSID without help.
     */
    public static synchronized void releaseAll(Context context) {
        if (context == null) {
            return;
        }
        final WifiOps wifi = wifiOpsOf(context);
        if (wifi == null) {
            logRevert("no WifiManager — cannot undo the accessory join");
            return;
        }
        final RevertState state = new RevertState();
        loadRecord(context, state);
        final boolean inMemory = !SUGGESTIONS.isEmpty() || !SUGGESTIONS_NET_IDS.isEmpty();
        if (!state.joinActive && !inMemory) {
            logRevert("nothing to undo: no accessory join is recorded");
            return;
        }
        final long startedAt = SystemClock.elapsedRealtime();
        logRevert("REVERT START: accessory=" + display(state.accessorySsid)
                + "/netId=" + state.accessoryNetId
                + " active=" + describeActive(context));

        /* ① withdraw the accessory network: suggestion, legacy netIds, the persisted netId, and
         * anything else configured with the accessory's SSID. */
        withdrawSuggestions(wifi);
        removeAccessoryNetworks(wifi, state);
        /* ② sweep again once the removal has run: a config can appear while this is executing (a
         * recovery join adds a fresh netId), and a removal can report success while leaving the
         * config listed — both were measured on 2026-10-02. */
        final boolean accessoryStillConfigured = !state.accessorySsid.isEmpty()
                && sweepAccessoryNetworks(wifi, state.accessorySsid, "final sweep");
        final long elapsed = SystemClock.elapsedRealtime() - startedAt;

        if (!accessoryStillConfigured) {
            logRevert("the accessory network is removed after " + elapsed + "ms — no earlier network"
                    + " is restored on purpose: the phone picks its own again from the next scan");
            clearRecord(context);
        } else {
            logRevert("the accessory network is STILL configured after " + elapsed + "ms — keeping"
                    + " the record so the next start retries");
        }
    }

    /**
     * Service-start self-heal: when a previous process died while on the accessory
     * network, run the revert immediately instead of waiting for a human to toggle
     * Wi-Fi.  The pending flag is cleared after the attempt so a service created
     * again and again cannot loop on the same failure.
     */
    public static synchronized void revertPendingJoin(Context context) {
        if (context == null) {
            return;
        }
        if (!isJoinRecorded(context)) {
            logRevert("service-start self-heal: no pending accessory join");
            return;
        }
        /* A record from a session that is already over is not a reason to touch the radio: the
         * phone is not on that network any more, so there is nothing to recover from. */
        dropStaleRecord(context);
        if (!isJoinRecorded(context)) {
            logRevert("service-start self-heal: the pending join was a leftover — nothing to revert");
            return;
        }
        logRevert("service-start self-heal: a persisted accessory join is pending — reverting now");
        releaseAll(context);
        if (isJoinRecorded(context)) {
            logRevert("service-start self-heal: the accessory network could not be removed —"
                    + " clearing the pending record anyway (the next join re-captures it)");
            clearRecord(context);
        }
    }

    private static void withdrawSuggestions(WifiOps wifi) {
        if (SUGGESTIONS.isEmpty()) {
            return;
        }
        try {
            final int status = wifi.removeNetworkSuggestions(
                    new ArrayList<WifiNetworkSuggestion>(SUGGESTIONS));
            logRevert("withdrew " + SUGGESTIONS.size() + " accessory network suggestion(s):"
                    + " status=" + status);
        } catch (Throwable t) {
            logRevert("removeNetworkSuggestions failed: " + t);
        }
        SUGGESTIONS.clear();
    }

    private static void removeAccessoryNetworks(WifiOps wifi, RevertState state) {
        final List<Integer> doomed = new ArrayList<Integer>();
        /* add() through this helper: the same netId arrives from several sources (the suggestion
         * list, the recorded netId, and the SSID scan), and hitting it three times ran the same
         * forget-network three times and logged three removals for one network. */
        for (Integer netId : SUGGESTIONS_NET_IDS) {
            if (netId != null && netId >= 0 && !doomed.contains(netId)) {
                doomed.add(netId);
            }
        }
        if (state.accessoryNetId >= 0 && !doomed.contains(state.accessoryNetId)) {
            doomed.add(state.accessoryNetId);
        }
        if (!state.accessorySsid.isEmpty()) {
            for (WifiConfiguration config : configuredNetworks(wifi)) {
                if (sameSsid(config.SSID, state.accessorySsid)
                        && !doomed.contains(config.networkId)) {
                    logRevert("accessory SSID " + display(state.accessorySsid)
                            + " is also configured as netId=" + config.networkId
                            + " — adding it to the removal list");
                    doomed.add(config.networkId);
                }
            }
        }
        for (Integer netId : doomed) {
            if (netId == null || netId < 0) {
                continue;
            }
            try {
                final boolean disabled = wifi.disableNetwork(netId);
                final boolean removed = wifi.removeNetwork(netId);
                logRevert("accessory network netId=" + netId + ": disableNetwork=" + disabled
                        + " removeNetwork=" + removed);
            } catch (Throwable t) {
                logRevert("removing accessory network netId=" + netId + " failed: " + t);
            }
        }
        SUGGESTIONS_NET_IDS.clear();
        try {
            wifi.saveConfiguration();
        } catch (Throwable t) {
            logRevert("saving after removal failed: " + t);
        }
    }

    /**
     * Removes every configured network whose SSID is the accessory's.
     *
     * <p>Step ① of the revert does the same scan for the network it recorded; this runs it again
     * after the whole attempt, because a config can be created while the revert is still running
     * (see the final sweep in {@link #releaseAll}).  A sweep that finds nothing is a perfectly good
     * outcome and says so — an operator reading the log must be able to tell "nothing left behind"
     * from "could not clean up".
     *
     * @return true when an accessory-named network is still configured after the sweep
     */
    private static boolean sweepAccessoryNetworks(WifiOps wifi, String accessorySsid, String phase) {
        final List<Integer> doomed = new ArrayList<Integer>();
        try {
            for (WifiConfiguration config : configuredNetworks(wifi)) {
                if (sameSsid(config.SSID, accessorySsid) && !doomed.contains(config.networkId)) {
                    doomed.add(config.networkId);
                }
            }
        } catch (Throwable t) {
            logRevert(phase + ": cannot list the configured networks: " + t);
            return true;
        }
        if (doomed.isEmpty()) {
            logRevert(phase + ": no saved network is left for " + display(accessorySsid));
            return false;
        }
        for (Integer netId : doomed) {
            try {
                final boolean disabled = wifi.disableNetwork(netId);
                final boolean removed = wifi.removeNetwork(netId);
                logRevert(phase + ": removed leftover accessory netId=" + netId
                        + " disableNetwork=" + disabled + " removeNetwork=" + removed);
            } catch (Throwable t) {
                logRevert(phase + ": removing leftover netId=" + netId + " failed: " + t);
            }
        }
        try {
            wifi.saveConfiguration();
        } catch (Throwable t) {
            logRevert(phase + ": saving after removal failed: " + t);
        }
        /* Ask the framework rather than trusting the calls above: a removal can report success and
         * still leave the config listed (that is exactly the bug this sweep exists for). */
        boolean stillThere = false;
        try {
            for (WifiConfiguration config : configuredNetworks(wifi)) {
                if (sameSsid(config.SSID, accessorySsid)) {
                    stillThere = true;
                    logRevert(phase + ": netId=" + config.networkId + " is still configured for "
                            + display(accessorySsid) + " after the sweep");
                }
            }
        } catch (Throwable t) {
            logRevert(phase + ": cannot re-check the configured networks: " + t);
            stillThere = true;
        }
        return stillThere;
    }

    /* ------------------------------------------------------- radio restore safety */

    /** Prefs key for "a radio toggle was started and never finished". */
    private static final String KEY_RADIO_RESTORE = "radio_restore_pending";
    /** The same fact mirrored into a system property, for a process killed before prefs flushed. */
    private static final String PROP_RADIO_RESTORE = PROP_PREFIX + "radio_restore";
    /** In-memory copy of the marker, so a failed prefs write cannot lose it. */
    private static boolean sRadioRestorePending;

    private static void clearRadioRestore(Context context) {
        sRadioRestorePending = false;
        try {
            final SharedPreferences.Editor editor = prefs(context).edit();
            editor.putBoolean(KEY_RADIO_RESTORE, false);
            editor.commit();
        } catch (Throwable ignored) {
            /* the in-memory copy is already cleared */
        }
        setProp(PROP_RADIO_RESTORE, "0");
    }

    /**
     * Repairs a half-finished radio toggle left behind by an <em>earlier build</em>: if the marker
     * is set, the radio is switched back on and the marker cleared.  Called at every process start,
     * so a killed toggle costs one restart at most — never a manual tap on the phone.
     *
     * <p>Nothing in this class switches the radio off any more: the restore-the-original-network
     * path that did was removed on 2026-10-02 at the operator's request ("returning to the original
     * network is not necessary").  Old markers survive in the prefs and system properties of devices
     * that ran those builds, and the adb-side `okcar-port-ctl net-recover` can still cycle the radio,
     * so repairing one at startup is worth the single check.
     */
    public static void restoreRadioIfPending(Context context) {
        if (context == null) {
            return;
        }
        boolean pending = sRadioRestorePending;
        try {
            pending = pending || prefs(context).getBoolean(KEY_RADIO_RESTORE, false);
        } catch (Throwable ignored) {
            /* fall through to the property */
        }
        pending = pending || "1".equals(getProp(PROP_RADIO_RESTORE, "0"));
        if (!pending) {
            return;
        }
        final WifiOps wifi = wifiOpsOf(context);
        if (wifi == null) {
            logRevert("a previous run left a radio toggle unfinished, but there is no WifiManager");
            return;
        }
        if (!wifi.isWifiEnabled()) {
            logRevert("a previous run left the radio off — switching Wi-Fi back on");
            wifi.setWifiEnabled(true);
            final long deadline = SystemClock.elapsedRealtime() + RADIO_UP_TIMEOUT_MS;
            while (!wifi.isWifiEnabled() && SystemClock.elapsedRealtime() < deadline) {
                sleep(250L);
            }
        }
        if (wifi.isWifiEnabled()) {
            clearRadioRestore(context);
            logRevert("radio restore finished: wifi enabled, marker cleared");
        } else {
            logRevert("radio restore: Wi-Fi is still off — keeping the marker for the next start");
        }
    }

    /**
     * Polls the active Wi-Fi SSID every second.  Success means the active SSID equals
     * {@code wantSsid}; when no original network was recorded (the phone was not on
     * Wi-Fi before the join) it means "no longer on the accessory network".
     */
    private static boolean awaitSsid(Context context, WifiOps wifi, String wantSsid,
                                     String accessorySsid, long timeoutMs, String phase,
                                     boolean selfTest) {
        final String want = normalize(wantSsid);
        final String accessory = normalize(accessorySsid);
        final long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        int round = 0;
        while (true) {
            round++;
            final WifiInfo info = safeConnectionInfo(wifi);
            final String active = info == null ? "" : normalize(info.getSSID());
            final String line = phase + " " + round + ": active ssid=" + display(active)
                    + " netId=" + (info == null ? -1 : info.getNetworkId())
                    + (info == null ? "" : " state=" + info.getSupplicantState())
                    + " want=" + display(want) + " ip=" + activeIpv4(context);
            if (selfTest) {
                logSelfTest(line);
            } else {
                logRevert(line);
            }
            if (matches(want, accessory, active)) {
                if (!want.isEmpty() && !active.isEmpty() && !want.equals(active)
                        && !want.equals(accessory)) {
                    logRevert(phase + ": back on " + display(active) + " — not the recorded "
                            + display(want) + ", but off the accessory network (one router can"
                            + " publish several SSIDs); treating this as recovered");
                }
                return true;
            }
            if (SystemClock.elapsedRealtime() >= deadline) {
                break;
            }
            sleep(REVERT_POLL_INTERVAL_MS);
        }
        final String line = phase + ": timed out after " + timeoutMs + "ms waiting for "
                + display(want) + " (active " + display(currentSsid(context)) + ")";
        if (selfTest) {
            logSelfTest(line);
        } else {
            logRevert(line);
        }
        return false;
    }

    private static boolean matches(String want, String accessory, String active) {
        if (want.equals(accessory)) {
            /* "Are we on this network?" — the self-test asks the question this way.  Strict. */
            return want.equals(active);
        }
        if (isUnknownSsid(active)) {
            /* No real association.  This is a transient state while the radio comes back up —
             * measured 2026-10-02: right after the toggle the framework reports "<unknown ssid>" /
             * DISCONNECTED, and counting that as "recovered" declared success while the phone had
             * no network at all.  Acceptable only when there was nothing recorded to go back to. */
            return want.isEmpty();
        }
        if (!accessory.isEmpty() && accessory.equals(active)) {
            /* Still on the accessory network: never a success, whatever was recorded. */
            return false;
        }
        /* The recorded original, or any other real association: both mean the phone left the
         * accessory network, which is what the revert is for.  Demanding the one recorded SSID made
         * a perfectly recovered phone read as FAILED, because one router publishes several SSIDs
         * (measured 2026-10-02: the record said ONLY149 while the phone came back on ONLY64 — the
         * same home network — so every revert escalated to a radio toggle it did not need, and that
         * toggle is what kept switching the phone's Wi-Fi off). */
        return true;
    }

    /* ------------------------------------------------- bounded self-test (adb) */

    /**
     * The adb-triggerable bounded revert self-test: record the pre-join network, join the
     * accessory AP named by the operator's {@link #SELF_TEST_CONF} through the exact same
     * {@link #join} path, hold for {@code holdSeconds}, then run the full {@link #releaseAll}
     * revert.  It deliberately takes the device off its LAN for ~30-45 s (Wi-Fi adb drops with
     * it), which is exactly what it verifies.
     *
     * <p>Refuses (one log line, no Wi-Fi call) when the operator has not supplied credentials:
     * there is deliberately no built-in SSID/passphrase any more — see {@link #SELF_TEST_CONF}.
     */
    public static void runRevertSelfTest(final Context context, final int holdSeconds) {
        if (context == null) {
            return;
        }
        final String[] credentials = selfTestCredentials();
        if (credentials == null) {
            logSelfTest("SKIP: no credentials — write \"" + SELF_TEST_CONF
                    + "\" with `ssid=…` and `passphrase=…` first");
            return;
        }
        final String ssid = credentials[0];
        final String passphrase = credentials[1];
        final int hold = holdSeconds > 0 ? holdSeconds : SELF_TEST_DEFAULT_HOLD_SECONDS;
        final Context app = context.getApplicationContext() == null
                ? context : context.getApplicationContext();
        final Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                final long startedAt = SystemClock.elapsedRealtime();
                logSelfTest("START hold=" + hold + "s — pre-join network: " + describeActive(app));
                final WirelessMessages.StartSession session = new WirelessMessages.StartSession();
                session.ssid = ssid;
                session.passphrase = passphrase;
                session.securityType = WirelessMessages.SECURITY_WPA_WPA2;
                session.airPlayPort = 7000;
                final Result joined = join(app, session);
                logSelfTest("after join: " + joined.describe() + " — active "
                        + describeActive(app));
                final WifiOps wifi = wifiOpsOf(app);
                final boolean onAccessory = wifi != null && awaitSsid(app, wifi, ssid,
                        ssid, SELF_TEST_JOIN_TIMEOUT_MS, "join-poll", true);
                logSelfTest("after join: onAccessory=" + onAccessory + " — active "
                        + describeActive(app));
                logSelfTest("holding " + hold + "s off the LAN");
                sleep(hold * 1000L);
                logSelfTest("hold complete after " + (SystemClock.elapsedRealtime() - startedAt)
                        + "ms — running the full revert");
                releaseAll(app);
                logSelfTest("final: active " + describeActive(app) + " endedOnSsid="
                        + display(currentSsid(app)) + " ip=" + activeIpv4(app)
                        + " elapsed=" + (SystemClock.elapsedRealtime() - startedAt) + "ms");
            }
        }, "okcar-wifi-revert-selftest");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Reads {@code ssid=…} / {@code passphrase=…} from {@link #SELF_TEST_CONF}; {@code null} when the
     * file is missing or carries no SSID.  The passphrase may legitimately be empty (open AP), so it
     * is returned as an empty string rather than treated as missing.
     */
    static String[] selfTestCredentials() {
        java.io.BufferedReader reader = null;
        try {
            reader = new java.io.BufferedReader(new java.io.InputStreamReader(
                    new java.io.FileInputStream(SELF_TEST_CONF), "UTF-8"));
            String ssid = null;
            String passphrase = "";
            String line;
            while ((line = reader.readLine()) != null) {
                final String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                final int equals = trimmed.indexOf('=');
                if (equals <= 0) {
                    continue;
                }
                final String key = trimmed.substring(0, equals).trim().toLowerCase(java.util.Locale.US);
                final String value = trimmed.substring(equals + 1).trim();
                if ("ssid".equals(key)) {
                    ssid = value;
                } else if ("passphrase".equals(key) || "password".equals(key)) {
                    passphrase = value;
                }
            }
            if (ssid == null || ssid.isEmpty()) {
                return null;
            }
            return new String[] {ssid, passphrase};
        } catch (Throwable t) {
            return null;
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (Throwable ignored) {
                    /* read-only use */
                }
            }
        }
    }

    /* ---------------------------------------------------------------- helpers */

    private static final class RevertState {
        boolean joinActive;
        String originalSsid = "";
        int originalNetId = -1;
        String accessorySsid = "";
        int accessoryNetId = -1;
    }

    private static WifiManager wifiOf(Context context) {
        return (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
    }

    /** The port adapter installed at daemon start; uid 0 does not use the framework APIs. */
    private static WifiOps wifiOpsOf(Context context) {
        final WifiOps installed = com.okcar.link.port.Ports.wifiOps();
        if (installed != null) {
            return installed;
        }
        final WifiManager manager = wifiOf(context);
        return manager == null ? null : new com.okcar.link.port.ShellWifiOps(manager);
    }

    private static WifiInfo safeConnectionInfo(WifiOps wifi) {
        try {
            return wifi.getConnectionInfo();
        } catch (Throwable t) {
            logRevert("getConnectionInfo failed: " + t);
            return null;
        }
    }

    private static List<WifiConfiguration> configuredNetworks(WifiOps wifi) {
        try {
            final List<WifiConfiguration> list = wifi.getConfiguredNetworks();
            return list == null ? new ArrayList<WifiConfiguration>() : list;
        } catch (Throwable t) {
            logRevert("getConfiguredNetworks failed: " + t);
            return new ArrayList<WifiConfiguration>();
        }
    }

    /** The active Wi-Fi SSID, unquoted; "" when not connected / unknown. */
    private static String currentSsid(Context context) {
        final WifiOps wifi = wifiOpsOf(context);
        if (wifi == null) {
            return "";
        }
        final WifiInfo info = safeConnectionInfo(wifi);
        if (info == null) {
            return "";
        }
        final String ssid = normalize(info.getSSID());
        return isUnknownSsid(ssid) ? "" : ssid;
    }

    private static String describeActive(Context context) {
        final WifiOps wifi = wifiOpsOf(context);
        if (wifi == null) {
            return "ssid=? netId=? ip=?";
        }
        final WifiInfo info = safeConnectionInfo(wifi);
        final String ssid = info == null ? "" : normalize(info.getSSID());
        return "ssid=" + display(isUnknownSsid(ssid) ? "" : ssid)
                + " netId=" + (info == null ? -1 : info.getNetworkId())
                + " state=" + (info == null ? "?" : info.getSupplicantState())
                + " ip=" + activeIpv4(context);
    }

    /** The IPv4 address of the active network, for the log evidence. */
    private static String activeIpv4(Context context) {
        try {
            final ConnectivityManager cm = (ConnectivityManager)
                    context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) {
                return "?";
            }
            final Network active = cm.getActiveNetwork();
            if (active == null) {
                return "none";
            }
            final LinkProperties properties = cm.getLinkProperties(active);
            if (properties == null) {
                return "?";
            }
            for (LinkAddress address : properties.getLinkAddresses()) {
                final InetAddress inet = address.getAddress();
                if (inet instanceof Inet4Address && !inet.isLoopbackAddress()) {
                    return inet.getHostAddress();
                }
            }
            return "no-ipv4";
        } catch (Throwable t) {
            return "?";
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static void logRevert(String message) {
        LinkState.get().log("wireless", "wifi-revert: " + message);
    }

    /** The verified join's own lines stay greppable next to the revert's. */
    private static void logJoin(String message) {
        LinkState.get().log("wireless", "wifi-join: " + message);
    }

    private static void logSelfTest(String message) {
        LinkState.get().log("wireless", "wifi-revert-selftest: " + message);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String normalize(String ssid) {
        if (ssid == null) {
            return "";
        }
        String text = ssid.trim();
        if (text.length() >= 2 && text.charAt(0) == '"' && text.charAt(text.length() - 1) == '"') {
            text = text.substring(1, text.length() - 1);
        }
        return text;
    }

    private static boolean sameSsid(String a, String b) {
        final String left = normalize(a);
        final String right = normalize(b);
        return !left.isEmpty() && left.equals(right);
    }

    private static boolean isUnknownSsid(String ssid) {
        return ssid.isEmpty() || "<unknown ssid>".equalsIgnoreCase(ssid)
                || "<unknown>".equalsIgnoreCase(ssid) || "0x".equalsIgnoreCase(ssid);
    }

    private static String display(String ssid) {
        return ssid == null || ssid.isEmpty() ? "<none>" : "\"" + ssid + "\"";
    }

    private static String firstNonEmpty(String first, String second, String third) {
        if (first != null && !first.isEmpty()) {
            return first;
        }
        if (second != null && !second.isEmpty()) {
            return second;
        }
        return third == null ? "" : third;
    }

    private static int firstNonNegative(int first, int second, int third) {
        if (first >= 0) {
            return first;
        }
        if (second >= 0) {
            return second;
        }
        return third;
    }

    private static int toInt(String text, int fallback) {
        try {
            return Integer.parseInt(text);
        } catch (Throwable t) {
            return fallback;
        }
    }

    /* ------------------------------------ best-effort SystemProperties access */

    private static void setProp(String key, String value) {
        try {
            final Class<?> cls = Class.forName("android.os.SystemProperties");
            final Method set = cls.getMethod("set", String.class, String.class);
            set.invoke(null, key, value);
        } catch (Throwable t) {
            /* The prefs record is the primary one; the property is a mirror. */
        }
    }

    private static String getProp(String key, String fallback) {
        try {
            final Class<?> cls = Class.forName("android.os.SystemProperties");
            final Method get = cls.getMethod("get", String.class, String.class);
            final Object value = get.invoke(null, key, fallback);
            return value == null ? fallback : value.toString();
        } catch (Throwable t) {
            return fallback;
        }
    }
}
