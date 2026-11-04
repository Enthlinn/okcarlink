package com.okcar.link.wireless;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.NetworkInfo;
import android.net.wifi.WpsInfo;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;

import com.okcar.link.LinkState;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Join the accessory's Wi-Fi Direct group on {@code p2p0} instead of joining its SSID as a
 * station on {@code wlan0}.
 *
 * <p>Why this exists: {@link WifiGroupJoiner} drives {@code WifiManager.addNetwork} /
 * {@code enableNetwork} / {@code addNetworkSuggestions}, i.e. it makes the phone a
 * <em>station</em> of the accessory's network.  The car head units that announce a real Wi-Fi
 * Direct group (the emulator creates one as GO at 192.0.2.1) put their group on a second virtual
 * interface.  Joining that group as a station has three consequences that were all observed on
 * 217 (2026-10-01):
 *
 * <ul>
 *   <li>the phone leaves the home LAN — {@code wlan0} loses 192.0.2.217 and Wi-Fi adb dies
 *       for the whole session, so the device can only be reached through a relay;</li>
 *   <li>the group network has no internet and no DNS, which the framework keeps
 *       re-evaluating; the session showed a teardown every ~1-2 minutes while the receiver
 *       logged {@code iAP2 link ended} / {@code control I/O failed: Socket closed};</li>
 *   <li>the accessory's credential handoff (iAP2 0x5703) carries the group's SSID and
 *       passphrase precisely so a peer can join the <em>group</em>, not a plain AP.</li>
 * </ul>
 *
 * <p>This class performs the framework Wi-Fi Direct flow: bring the P2P channel up (which is
 * what moves the stack out of {@code P2pDisabledState}), discover the GO, connect to it with
 * the announced credentials, and report whether the group actually formed.  The caller keeps
 * {@link WifiGroupJoiner} as the fallback for the head units that really are a SoftAP
 * (the integrator/the production head unit family), so the best path is chosen per accessory rather than assumed.
 *
 * <p>Every step is bounded by {@code timeoutMs} and every outcome is logged under the
 * {@code wireless} state tag, because the interesting question in the field is <em>which</em>
 * path joined and <em>why</em> the other one did not.
 */
public final class WifiP2pGroupJoiner {

    private static final String TAG = "OkCarLink/Wireless";

    /**
     * Split of the caller's budget: discovery takes the larger part.
     *
     * <p>the emulator puts its group on 5 GHz channel 149 (`P2P-GROUP-STARTED p2p0 GO … freq=5745` in
     * the 2026-09-26 capture), and {@code p2p_find} searches the social channels (1/6/11)
     * before progressively sweeping the rest, so a group owner that is not on a social channel
     * cannot answer within the first sweep.  The split is proportional to the budget the caller
     * grants rather than a fixed floor — a fixed floor would silently overrun a short probe and
     * make the joiner's own "bounded by timeoutMs" promise false (it did: a 12 s probe spent
     * 14 s + 10 s).
     */
    private static final int DISCOVERY_SHARE_PERCENT = 62;
    /** Floors so a very small budget still leaves both phases usable. */
    private static final long DISCOVERY_FLOOR_MS = 5_000L;
    private static final long CONNECT_FLOOR_MS = 3_000L;
    private static final long POLL_INTERVAL_MS = 200L;
    /**
     * How often the joiner re-asks for the peer list while the find is sweeping.  Short enough
     * that a GO appearing mid-sweep is picked up while it is still in the list.
     */
    private static final long PEER_POLL_INTERVAL_MS = 1_200L;
    /** How often the joiner asks the framework whether the group has formed. */
    private static final long GROUP_POLL_INTERVAL_MS = 1_500L;
    /** How long initialize() gets to publish the P2P channel before we call it dead. */
    private static final long CHANNEL_WAIT_MS = 4_000L;

    private WifiP2pGroupJoiner() {
    }

    public static final class Result {
        /** True only when the group really formed and we hold an address on it. */
        public final boolean ok;
        public final String detail;
        /** The group owner's address (the accessory), or null when it never formed. */
        public final InetAddress owner;

        Result(boolean ok, String detail, InetAddress owner) {
            this.ok = ok;
            this.detail = detail;
            this.owner = owner;
        }

        public String describe() {
            return (ok ? "joined: " : "failed: ") + detail;
        }
    }

    /**
     * Bring the Wi-Fi Direct channel up, find the GO that owns {@code ssid} and join its group.
     * Never throws: an unusable P2P stack is reported as a failed Result so the caller can fall
     * back to the station join.
     */
    public static Result join(Context context, String ssid, String passphrase, long timeoutMs) {
        if (ssid == null || ssid.isEmpty()) {
            return new Result(false, "no SSID", null);
        }
        if (context == null) {
            return new Result(false, "no context", null);
        }
        final WifiP2pManager manager;
        try {
            manager = (WifiP2pManager) context.getSystemService(Context.WIFI_P2P_SERVICE);
        } catch (Throwable t) {
            return new Result(false, "WIFI_P2P_SERVICE unavailable: " + t, null);
        }
        if (manager == null) {
            return new Result(false, "no WifiP2pManager", null);
        }

        HandlerThread thread = new HandlerThread("okcar-wifi-p2p");
        thread.start();
        final Looper looper = thread.getLooper();
        final P2pState state = new P2pState();
        BroadcastReceiver receiver = null;
        WifiP2pManager.Channel channel = null;
        boolean connected = false;
        try {
            channel = manager.initialize(context, looper, null);
            if (channel == null) {
                return new Result(false, "initialize() returned no channel", null);
            }
            final Handler handler = new Handler(looper);
            receiver = new P2pReceiver(manager, channel, state);
            final IntentFilter filter = new IntentFilter();
            filter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
            filter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
            filter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
            filter.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);
            /* Deliver on our own looper: the daemon's main looper is not guaranteed to be
             * pumping, and a P2P callback that never arrives looks exactly like a dead group. */
            context.registerReceiver(receiver, filter, null, handler);

            /* The stack can come up disabled (it did on 217: `P2pDisabledState`), so give the
             * channel a moment to publish an enabled state before discovering. */
            await(() -> state.stateKnown, CHANNEL_WAIT_MS);
            if (state.stateKnown && !state.enabled) {
                return new Result(false, "Wi-Fi Direct disabled in the framework", null);
            }

            final long discoveryBudget = Math.max(DISCOVERY_FLOOR_MS,
                    timeoutMs * DISCOVERY_SHARE_PERCENT / 100);
            final long connectBudget = Math.max(CONNECT_FLOOR_MS, timeoutMs - discoveryBudget);

            manager.discoverPeers(channel, new WifiP2pManager.ActionListener() {
                @Override
                public void onSuccess() {
                    Log.i(TAG, "p2p discoverPeers started");
                }

                @Override
                public void onFailure(int reason) {
                    state.discoverFailure = reason;
                    state.discoverDone = true;
                }
            });

            final Collection<WifiP2pDevice> peers = awaitPeers(manager, channel, state,
                    discoveryBudget);
            if (peers == null) {
                return new Result(false, "discovery found no peers"
                        + (state.discoverFailure != 0 ? " (discoverPeers reason="
                        + state.discoverFailure + ")" : " within " + discoveryBudget + "ms"),
                        null);
            }
            final WifiP2pDevice go = matchGroupOwner(ssid, peers);
            if (go == null) {
                return new Result(false, "no peer matches group SSID " + ssid + " among "
                        + peers.size() + " peer(s): " + describePeers(peers), null);
            }
            LinkState.get().log("wireless", "wifi p2p GO found: " + go.deviceName + " "
                    + go.deviceAddress + " ssid=" + ssid);

            final WifiP2pConfig config = new WifiP2pConfig();
            config.deviceAddress = go.deviceAddress;
            config.wps.setup = WpsInfo.PBC;
            /* We want the accessory to stay the group owner: our whole point is to be a client
             * on p2p0 while wlan0 keeps the home LAN. */
            config.groupOwnerIntent = 0;
            if (passphrase != null && !passphrase.isEmpty()) {
                /* The credential handoff named the group, so join that group instead of letting
                 * the framework negotiate a fresh one (which would need a WPS exchange the
                 * accessory does not run). */
                applyGroupCredentials(config, ssid, passphrase);
            }
            manager.connect(channel, config, new WifiP2pManager.ActionListener() {
                @Override
                public void onSuccess() {
                    Log.i(TAG, "p2p connect() accepted for " + config.deviceAddress);
                }

                @Override
                public void onFailure(int reason) {
                    state.connectFailure = reason;
                    state.connectDone = true;
                }
            });

            final WifiP2pInfo info = awaitGroup(manager, channel, state, connectBudget);
            if (info == null || !info.groupFormed) {
                final String ifaceAddress = p2pInterfaceAddress();
                if (ifaceAddress != null) {
                    connected = true;
                    return new Result(true, "ssid=" + ssid + " go=" + go.deviceAddress
                            + " formed (connection info never confirmed it; proved by "
                            + ifaceAddress + ')', null);
                }
                return new Result(false, "group never formed"
                        + (state.connectFailure != 0 ? " (connect reason=" + state.connectFailure
                        + ")" : " within " + connectBudget + "ms"), null);
            }
            connected = true;
            final String role = info.isGroupOwner ? "GO" : "client";
            final String detail = "ssid=" + ssid + " go=" + go.deviceAddress + " role=" + role
                    + " owner=" + (info.groupOwnerAddress == null ? "-"
                    : info.groupOwnerAddress.getHostAddress());
            return new Result(true, detail, info.groupOwnerAddress);
        } catch (Throwable t) {
            /* SecurityException / IllegalArgumentException / vendor rejection — all of them mean
             * "this accessory must use the station path", not "the session is broken". */
            Log.w(TAG, "wifi p2p join failed: " + t);
            return new Result(false, t.toString(), null);
        } finally {
            if (channel != null && !connected && p2pInterfaceAddress() == null) {
                try {
                    manager.cancelConnect(channel, null);
                } catch (Throwable ignored) {
                    /* Best effort: cancelConnect on a stack that never came up is not news. */
                }
            }
            if (receiver != null) {
                try {
                    context.unregisterReceiver(receiver);
                } catch (Throwable ignored) {
                    /* Already gone (context torn down) — nothing to undo. */
                }
            }
            thread.quitSafely();
        }
    }

    /**
     * Put the announced group credentials on the config.
     *
     * <p>{@code networkName} / {@code passphrase} are published by the wifi mainline module but
     * are not on the public SDK surface the port build compiles against (verified with
     * javap against {@code framework-wifi…classes.jar} and {@code sdk/33/public/android.jar}).
     * Reflection keeps the compile surface honest and degrades to a plain PBC negotiation when
     * the running module is older than the field — the caller sees that decision in the log
     * instead of a build that only works on one of the two jars.
     */
    static void applyGroupCredentials(WifiP2pConfig config, String ssid, String passphrase) {
        try {
            final java.lang.reflect.Field name =
                    WifiP2pConfig.class.getField("networkName");
            final java.lang.reflect.Field pass =
                    WifiP2pConfig.class.getField("passphrase");
            name.set(config, ssid);
            pass.set(config, passphrase);
            Log.i(TAG, "p2p joining named group " + ssid + " with the handed-over passphrase");
        } catch (Throwable t) {
            LinkState.get().log("wireless", "wifi p2p config carries no group credentials ("
                    + t + ") — negotiating with PBC instead");
        }
    }

    /** Live P2P state, written from the broadcast receiver and read from the joiner thread. */
    private static final class P2pState {
        volatile boolean stateKnown;
        volatile boolean enabled;
        volatile boolean peersChanged;
        volatile int discoverFailure;
        volatile boolean discoverDone;
        volatile NetworkInfo connection;
        volatile WifiP2pInfo info;
        volatile int connectFailure;
        volatile boolean connectDone;
    }

    private static final class P2pReceiver extends BroadcastReceiver {
        private final WifiP2pManager manager;
        private final WifiP2pManager.Channel channel;
        private final P2pState state;

        P2pReceiver(WifiP2pManager manager, WifiP2pManager.Channel channel, P2pState state) {
            this.manager = manager;
            this.channel = channel;
            this.state = state;
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (action == null) {
                return;
            }
            if (WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION.equals(action)) {
                final int wifiState = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1);
                state.enabled = wifiState == WifiP2pManager.WIFI_P2P_STATE_ENABLED;
                state.stateKnown = true;
                LinkState.get().log("wireless", "wifi p2p state=" + wifiState
                        + (state.enabled ? " (enabled)" : " (not enabled)"));
            } else if (WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION.equals(action)) {
                state.peersChanged = true;
            } else if (WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION.equals(action)) {
                state.connection = (NetworkInfo) intent.getParcelableExtra(
                        WifiP2pManager.EXTRA_NETWORK_INFO);
                state.info = (WifiP2pInfo) intent.getParcelableExtra(
                        WifiP2pManager.EXTRA_WIFI_P2P_INFO);
                Log.i(TAG, "p2p connection changed: " + state.connection);
            }
        }
    }

    /**
     * Wait for the peer list.
     *
     * <p>{@code WIFI_P2P_PEERS_CHANGED_ACTION} only says "ask again", so the list itself always
     * comes from {@code requestPeers}.  A find that is still sweeping answers with an <em>empty
     * list</em> rather than not answering, so asking once and waiting out the whole budget is a
     * guaranteed miss: the request has to be repeated until the budget runs out.  (That is
     * exactly how the first field run failed — one request, an empty list, 15 s of silence.)
     */
    private static Collection<WifiP2pDevice> awaitPeers(WifiP2pManager manager,
                                                        WifiP2pManager.Channel channel,
                                                        P2pState state,
                                                        long timeoutMs) {
        final long deadline = System.currentTimeMillis() + timeoutMs;
        final List<WifiP2pDevice> found = new ArrayList<WifiP2pDevice>();
        long nextAsk = 0L;
        while (System.currentTimeMillis() < deadline) {
            if (state.discoverFailure != 0) {
                return null;
            }
            final long now = System.currentTimeMillis();
            if (now >= nextAsk) {
                nextAsk = now + PEER_POLL_INTERVAL_MS;
                final boolean[] answered = new boolean[1];
                manager.requestPeers(channel, list -> {
                    synchronized (found) {
                        found.clear();
                        if (list != null) {
                            found.addAll(list.getDeviceList());
                        }
                    }
                    answered[0] = true;
                });
                /* requestPeers answers on the P2P looper; give it a slice of the budget. */
                await(() -> answered[0], 1_200L);
                state.peersChanged = false;
                synchronized (found) {
                    if (!found.isEmpty()) {
                        return new ArrayList<WifiP2pDevice>(found);
                    }
                }
            }
            sleep(POLL_INTERVAL_MS);
        }
        synchronized (found) {
            return found.isEmpty() ? null : new ArrayList<WifiP2pDevice>(found);
        }
    }

    /**
     * Wait for the group to form.
     *
     * <p>{@code requestConnectionInfo} is the authority, not the
     * {@code WIFI_P2P_CONNECTION_CHANGED_ACTION} broadcast: on the port target the group came up
     * and {@code p2p0} held {@code 192.0.2.125} while the broadcast never reached this
     * process, so gating the query on the broadcast reported a formed group as a failure (first
     * field run, 2026-10-01).  The query is therefore polled on its own schedule and the
     * broadcast is only a shortcut.
     */
    private static WifiP2pInfo awaitGroup(WifiP2pManager manager,
                                          WifiP2pManager.Channel channel,
                                          P2pState state,
                                          long timeoutMs) {
        final long deadline = System.currentTimeMillis() + timeoutMs;
        long nextAsk = 0L;
        while (System.currentTimeMillis() < deadline) {
            if (state.connectDone) {
                /* connect() refused outright: no point waiting for a group. */
                return null;
            }
            final long now = System.currentTimeMillis();
            if (now >= nextAsk) {
                nextAsk = now + GROUP_POLL_INTERVAL_MS;
                final WifiP2pInfo[] slot = new WifiP2pInfo[1];
                final boolean[] answered = new boolean[1];
                manager.requestConnectionInfo(channel, info -> {
                    slot[0] = info;
                    answered[0] = true;
                });
                await(() -> answered[0], 1_500L);
                if (slot[0] != null && slot[0].groupFormed) {
                    return slot[0];
                }
                final WifiP2pInfo broadcast = state.info;
                if (broadcast != null && broadcast.groupFormed) {
                    return broadcast;
                }
            }
            sleep(POLL_INTERVAL_MS);
        }
        return null;
    }

    /**
     * Last-resort proof that a group is up: a {@code p2p*} interface holding an address.
     *
     * <p>The connection-info path is a vendor surface, and on the port target it can disagree
     * with the kernel.  When it does, an address on the P2P interface is still decisive
     * evidence that the join happened and that {@code wlan0} was not disturbed — the whole
     * point of this path — so the caller gets a success it can act on rather than a timeout
     * that would send it back to the station join (which is what actually breaks the LAN).
     */
    private static String p2pInterfaceAddress() {
        try {
            final java.util.Enumeration<java.net.NetworkInterface> interfaces =
                    java.net.NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                final java.net.NetworkInterface iface = interfaces.nextElement();
                final String name = iface.getName();
                if (name == null || !name.startsWith("p2p") || !iface.isUp()) {
                    continue;
                }
                final java.util.Enumeration<java.net.InetAddress> addresses = iface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    final java.net.InetAddress address = addresses.nextElement();
                    if (address instanceof java.net.Inet4Address) {
                        return name + "=" + address.getHostAddress();
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "cannot enumerate p2p interfaces: " + t);
        }
        return null;
    }

    /**
     * Match the peer that owns {@code ssid}.
     *
     * <p>Three shapes have to be covered, because the accessories we have evidence for do not
     * all name their group the way wpa_supplicant does by default:
     * <ol>
     *   <li>{@code DIRECT-xy-<device name>} — the stock autonomous-GO form, matched on the
     *       device name after the two-character random prefix;</li>
     *   <li>{@code DIRECT-dp7c1c8e724ed2-DoYD} — what the emulator actually hands over in the iAP2
     *       0x5703 credential block: a stable middle token, then four random characters that
     *       change for every group ({@code DoYD}, {@code Gfsz}, {@code V34v}, …).  Matching the
     *       whole SSID against a device name can never hit, so the stable middle token is
     *       compared as well;</li>
     *   <li>a single visible {@code DIRECT-} peer — the peer list on a phone in a car is that
     *       peer in practice, and the credentials still have to be accepted by it, so this is a
     *       hint rather than a bypass.</li>
     * </ol>
     */
    static WifiP2pDevice matchGroupOwner(String ssid, Collection<WifiP2pDevice> peers) {
        if (peers == null || peers.isEmpty()) {
            return null;
        }
        final String middle = groupMiddleToken(ssid);
        for (WifiP2pDevice device : peers) {
            if (nameMatches(ssid, device.deviceName) || middleMatches(middle, device.deviceName)) {
                return device;
            }
        }
        if (peers.size() == 1 && ssid != null && ssid.length() > 7
                && ssid.regionMatches(true, 0, "DIRECT-", 0, 7)) {
            return peers.iterator().next();
        }
        return null;
    }

    /**
     * The stable middle token of a group SSID such as {@code DIRECT-dp7c1c8e724ed2-DoYD} —
     * everything between the {@code DIRECT-} prefix and the trailing random suffix, or null
     * when the SSID is not shaped that way.
     */
    static String groupMiddleToken(String ssid) {
        if (ssid == null || !ssid.regionMatches(true, 0, "DIRECT-", 0, 7)) {
            return null;
        }
        final int firstDash = ssid.indexOf('-', 7);
        if (firstDash < 0) {
            return null;
        }
        final int secondDash = ssid.indexOf('-', firstDash + 1);
        if (secondDash < 0) {
            return null;
        }
        final String middle = ssid.substring(firstDash + 1, secondDash);
        return middle.isEmpty() ? null : middle;
    }

    private static boolean middleMatches(String middle, String deviceName) {
        if (middle == null || deviceName == null || deviceName.isEmpty()) {
            return false;
        }
        return middle.equalsIgnoreCase(deviceName)
                || middle.toLowerCase().contains(deviceName.toLowerCase())
                || deviceName.toLowerCase().contains(middle.toLowerCase());
    }

    static boolean nameMatches(String ssid, String deviceName) {
        if (ssid == null || deviceName == null || deviceName.isEmpty()) {
            return false;
        }
        if (ssid.equalsIgnoreCase(deviceName)) {
            return true;
        }
        if (ssid.length() > 9 && ssid.regionMatches(true, 0, "DIRECT-", 0, 7)) {
            final String suffix = ssid.substring(9);
            if (suffix.equalsIgnoreCase(deviceName)) {
                return true;
            }
        }
        return ssid.toLowerCase().contains(deviceName.toLowerCase());
    }

    private static String describePeers(Collection<WifiP2pDevice> peers) {
        final StringBuilder sb = new StringBuilder();
        for (WifiP2pDevice device : peers) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(device.deviceName).append('@').append(device.deviceAddress);
        }
        return sb.length() == 0 ? "-" : sb.toString();
    }

    /** A condition the joiner thread polls; kept tiny so call sites read as a sentence. */
    private interface Condition {
        boolean met();
    }

    private static void await(Condition condition, long timeoutMs) {
        final long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.met()) {
                return;
            }
            sleep(POLL_INTERVAL_MS);
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
