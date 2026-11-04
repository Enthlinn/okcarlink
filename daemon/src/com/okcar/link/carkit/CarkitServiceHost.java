package com.okcar.link.carkit;

import android.net.LocalServerSocket;
import android.net.LocalSocket;

import com.okcar.link.LinkState;
import com.okcar.link.iap2.Iap2Link;
import com.okcar.link.airplay.WirelessAirPlayClient;
import com.okcar.link.daemon.LinkDaemon;
import com.okcar.link.wireless.WirelessCarPlayDriver;
import com.okcar.link.wireless.WirelessMessages;

import java.io.IOException;

/**
 * Owns the iAP2 device role for wired CarPlay (217 pretending to be the iPhone).
 *
 * <p>Why a local socket: {@code okcar-ffs-usbmux} answers {@code StartService
 * com.apple.carkit.service} with a Port and then bridges that port's bytes to the abstract socket
 * {@link #SOCKET_NAME}. Accepting a connection here therefore means "the host actually opened the
 * CarPlay control channel", which is exactly when the device-side iAP2 session should start.
 *
 * <p>The message flow is the one the real iPhone 8 uses, replayed by
 * {@link WirelessCarPlayDriver} in its device role: send {@code 0x1D00}, accept the accessory's
 * {@code 0x1D01}, answer once with an <b>empty</b> {@code 0x1D02}, then the MFi exchange
 * ({@code 0xAA00}/{@code 0xAA02}) and the wireless/start-session messages. Records:
 * {@code the iAP2d cross-check} §2.9.
 *
 * <p>Scope: this class brings up and supervises the iAP2 control link. The AirPlay half of wired
 * CarPlay (the NCM/IPv6 transport) is not implemented here; {@link #onWirelessStartSession} logs
 * what the host asked for instead of pretending to serve it.
 */
public final class CarkitServiceHost implements WirelessCarPlayDriver.Callback {

    /** Name the native daemon connects to; keep in sync with CARKIT_SOCKET_NAME in okcar-ffs-usbmux.c. */
    public static final String SOCKET_NAME = "okcar-carkit";

    private static final String TAG = "carkit";

    private final LinkState mState = LinkState.get();
    private final Object mLock = new Object();

    private LocalServerSocket mServer;
    private Thread mAcceptThread;
    private volatile boolean mRunning;

    /* The live session (at most one at a time). */
    private LocalSocket mSocket;
    private CarkitSocketTransport mTransport;
    private Iap2Link mLink;
    private WirelessCarPlayDriver mDriver;

    /** Did the accessory's SYN declare the control session id 1 (the family that never sends 0x4301)? */
    private volatile String mLastEnd = "never";

    /**
     * Interface the wired AirPlay session runs on.  The NCM function's ifname is usb0 (see
     * okcar-carkit's NCM_IFNAME); an IPv6 link-local address is unreachable without a scope, so the
     * target the accessory hands us in 0x4301 (measured: fe80::2) is dialled as fe80::2%usb0.
     */
    private static final String NCM_IFACE = "usb0";
    private static final int AIRPLAY_PORT_DEFAULT = 7000;

    /** The wired AirPlay sender, one at a time (same "at most one session" rule as the control link). */
    private WirelessAirPlayClient mAirPlay;
    /** Re-asserts the carrier route while a wired AirPlay session is up (see its starter). */
    private Thread mRouteKeeper;

    /** Address the wired routing rule was installed for, so teardown removes exactly that one. */
    private String mWiredRouteAddress;

    /* The iAP2 role flags are process-wide statics and the wireless listener is a first-class path in
     * this same process, so a wired session saves and restores them instead of leaving them changed. */
    private boolean mFlagsSaved;
    private boolean mSavedDeviceSendsSyn;
    private boolean mSavedAckNextExpected;
    private boolean mSavedSynAckEchoParams;

    public boolean isRunning() {
        return mRunning;
    }

    /** True only while the wired AirPlay sender has live media streams. */
    public boolean isCasting() {
        final WirelessAirPlayClient client = mAirPlay;
        return client != null && client.isStreaming();
    }

    /** Starts listening.  Returns false when the socket is already taken or the platform refuses it. */
    public boolean start() {
        synchronized (mLock) {
            if (mRunning) {
                return true;
            }
            try {
                // LocalServerSocket(String) binds LocalSocketAddress(name), whose default namespace
                // is ABSTRACT - the same sun_path[0]='\0' form the native daemon connects to.
                mServer = new LocalServerSocket(SOCKET_NAME);
            } catch (IOException e) {
                mState.log(TAG, "cannot listen on " + SOCKET_NAME + ": " + e);
                mServer = null;
                return false;
            }
            mRunning = true;
            mAcceptThread = new Thread(this::acceptLoop, "okcar-carkit-accept");
            mAcceptThread.setDaemon(true);
            mAcceptThread.start();
            mState.log(TAG, "listening on abstract socket " + SOCKET_NAME
                    + " - the wired control channel starts on accept");
            return true;
        }
    }

    public void stop() {
        Thread accept;
        synchronized (mLock) {
            if (!mRunning) {
                return;
            }
            mRunning = false;
            accept = mAcceptThread;
            mAcceptThread = null;
        }
        closeSession("host stopped");
        if (mServer != null) {
            try {
                mServer.close();
            } catch (IOException ignored) {
                // nothing useful to do while shutting down
            }
            mServer = null;
        }
        if (accept != null) {
            accept.interrupt();
        }
        mState.log(TAG, "stopped");
    }

    public String status() {
        final String line;
        synchronized (mLock) {
            if (!mRunning) {
                line = "carkit host: off";
            } else if (mLink == null) {
                line = "carkit host: listening on " + SOCKET_NAME + ", no session yet";
            } else {
                line = "carkit host: session up (iAP2 connected=" + mLink.isConnected()
                        + ", last end: " + mLastEnd + ")";
            }
        }
        return line;
    }

    private void acceptLoop() {
        while (mRunning) {
            LocalSocket socket;
            try {
                socket = mServer.accept();
            } catch (IOException e) {
                if (mRunning) {
                    mState.log(TAG, "accept failed: " + e);
                }
                break;
            }
            if (!mRunning) {
                closeQuietly(socket);
                break;
            }
            /* One broken session must never take the listener with it: an exception thrown while
             * setting up (or tearing down) a session used to leave the process with no listener at
             * all, and every later attempt failed with `no listener on abstract socket
             * 'okcar-carkit' (errno=111)` until the daemon was restarted. */
            try {
                beginSession(socket);
            } catch (Throwable t) {
                mState.log(TAG, "session setup failed: " + t);
                closeQuietly(socket);
                try {
                    closeSession("session setup failed: " + t);
                } catch (Throwable ignored) {
                    mState.log(TAG, "cleanup after a failed setup also failed: " + ignored);
                }
            }
        }
    }

    private void beginSession(LocalSocket socket) {
        final Iap2Link link;
        final WirelessCarPlayDriver driver;
        final CarkitSocketTransport transport;
        try {
            transport = new CarkitSocketTransport(socket);
        } catch (IOException e) {
            mState.log(TAG, "cannot open the accepted socket: " + e);
            closeQuietly(socket);
            return;
        }
        closeSession("a new wired session arrived");
        saveRoleFlags();
        applyWiredRoleFlags();
        // Empty identifiers on purpose: the wired path's 0x4E0E / 0x4300 carry the NCM-side identity,
        // which the gadget autoconfigures per enumeration (a baked-in value would be wrong rather
        // than merely a placeholder).  The NCM carrier itself is dialled with the accessory's own
        // address from 0x4301 -- see startWiredAirPlay.
        driver = new WirelessCarPlayDriver(this, "", "");
        driver.setWired(true);
        link = new Iap2Link(transport, driver);
        // The wired control link is quiet between CSM messages; an idle timer would kill a healthy
        // session (same reason the wireless tunnel disables it).
        link.setIdleLinkDownMs(0);
        driver.setLink(link);
        synchronized (mLock) {
            mSocket = socket;
            mTransport = transport;
            mDriver = driver;
            mLink = link;
            mLastEnd = "session opened";
        }
        mState.log(TAG, "wired control channel accepted - starting the iAP2 device role");
        link.start();
        // The real iPhone answers the host's marker with its own six-byte marker before SYN-ACK;
        // the emulator discards it in DETECTING and negotiates, so emitting it is safe and faithful.
        link.sendDetect();
    }

    private void closeSession(String reason) {        final Iap2Link link;
        final CarkitSocketTransport transport;
        synchronized (mLock) {
            link = mLink;
            transport = mTransport;
            mLink = null;
            mDriver = null;
            mTransport = null;
            mSocket = null;
            if (link != null || transport != null) {
                mLastEnd = reason;
            }
        }
        if (link != null) {
            link.stop();
        }
        if (transport != null) {
            transport.close();
        }
        restoreRoleFlags();
        if (link != null || transport != null) {
            mState.log(TAG, "wired control channel closed: " + reason);
        }
        /* The AirPlay sender only makes sense while the control link that announced it is up.  A
         * failure here must not escape: closeSession also runs from the accept loop. */
        try {
            stopWiredAirPlay();
        } catch (Throwable t) {
            mState.log(TAG, "wired AirPlay teardown failed: " + t);
        }
    }

    private void saveRoleFlags() {
        synchronized (mLock) {
            if (mFlagsSaved) {
                return;
            }
            mSavedDeviceSendsSyn = Iap2Link.deviceSendsSyn();
            mSavedAckNextExpected = Iap2Link.ackNextExpected();
            mSavedSynAckEchoParams = Iap2Link.synAckEchoParams();
            mFlagsSaved = true;
        }
    }

    /**
     * The role configuration the wireless device path already uses against the same peer
     * implementation: the host opens with SYN so we must not (<code>deviceSendsSyn=false</code>),
     * it answers our SYN-ACK with an ACK so the next frame is not expected to be one
     * (<code>ackNextExpected=false</code>), and the SYN-ACK echoes the host's own parameter block
     * (<code>synAckEchoParams=true</code>) - substituting our own window made the production head unit accessory
     * ping-pong SYN-ACK forever (the field-rework notes 12.55).
     */
    private void applyWiredRoleFlags() {
        Iap2Link.setDeviceSendsSyn(false);
        Iap2Link.setAckNextExpected(false);
        Iap2Link.setSynAckEchoParams(true);
        Iap2Link.setSynTemplate(WirelessMessages.iphoneSynAck());
    }

    private void restoreRoleFlags() {
        synchronized (mLock) {
            if (!mFlagsSaved) {
                return;
            }
            Iap2Link.setDeviceSendsSyn(mSavedDeviceSendsSyn);
            Iap2Link.setAckNextExpected(mSavedAckNextExpected);
            Iap2Link.setSynAckEchoParams(mSavedSynAckEchoParams);
            Iap2Link.setSynTemplate(null);
            mFlagsSaved = false;
        }
    }

    private void closeQuietly(LocalSocket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // best effort
        }
    }

    /* --------------------------------------------------- WirelessCarPlayDriver.Callback */

    @Override
    public void onWirelessLinkUp(boolean wirelessIapFamily) {
        mState.log(TAG, "iAP2 link up (accessory SYN family="
                + (wirelessIapFamily ? "control session 1" : "10/12") + ")");
    }

    @Override
    public void onWirelessStartSession(WirelessMessages.StartSession session) {
        mState.log(TAG, "accessory asked to start the CarPlay session: " + session.describe());
        if (session.wired) {
            startWiredAirPlay(session);
            return;
        }
        /* The Wi-Fi hand-off (0x5703/0x4301 with credentials) is the wireless path's job, driven by
         * WirelessCarPlaySession; a wired control link only ever gets the wired hand-off. */
        mState.log(TAG, "not the wired hand-off - the Wi-Fi session is started by the wireless path");
    }

    /**
     * Opens the AirPlay session the accessory's 0x4301 pointed at.  The iPhone is the AirPlay
     * *sender* here (the head unit is the receiver), which is the same client the wireless path uses --
     * only the carrier differs: no Wi-Fi join, the NCM interface, and a scope on the link-local
     * address the accessory named.
     */
    private void startWiredAirPlay(WirelessMessages.StartSession session) {
        final android.content.Context context = LinkDaemon.appContext();
        if (context == null) {
            mState.log(TAG, "wired AirPlay: no system context yet - not starting the sender");
            return;
        }
        String target = session.accessoryAddress == null ? "" : session.accessoryAddress.trim();
        if (target.isEmpty()) {
            mState.log(TAG, "wired AirPlay: 0x4301 carried no wired address - not starting the sender");
            return;
        }
        /* The accessory's address may carry a scope already; the client adds the wired one. */
        final int percent = target.indexOf('%');
        if (percent >= 0) {
            target = target.substring(0, percent);
        }
        /* The stall cooldown applies to the wired AirPlay half too.  The control link (iAP2 over
         * USBMUX) is cheap and may stay up -- it is what the head unit watches to decide whether the
         * phone is there -- but the AirPlay sender is the load that dragged the phone under, and
         * starting it during the window is exactly what the cooldown exists to prevent.  Without
         * this check a wired session ignored the cooldown entirely and pulled the encoder straight
         * back up (the wireless acceptLoop has had this check since the cooldown was introduced).
         * We do not start it later either: the accessory re-dials its own AirPlay when it wants it,
         * and WirelessCarPlaySession clears the window when it expires (armSuspensionWatch). */
        if (com.okcar.link.wireless.WirelessCarPlaySession.castsSuspended()) {
            mState.log(TAG, "wired AirPlay: casting is suspended for another "
                    + (com.okcar.link.wireless.WirelessCarPlaySession.suspendRemainingMs() / 1000L)
                    + "s after a system stall - not starting the sender (the control link stays up)");
            return;
        }
        routeToWiredCarrier(target);
        final int port = session.airPlayPort > 0 ? (int) session.airPlayPort : AIRPLAY_PORT_DEFAULT;
        stopWiredAirPlay();
        final WirelessAirPlayClient client = new WirelessAirPlayClient(context);
        client.setCarrierInterface(NCM_IFACE);
        mAirPlay = client;
        /* AFTER stopWiredAirPlay(): that method stops the keeper (it tears the previous session down),
         * so starting it before the call made the keeper die within the same method and the route was
         * never maintained -- measured 2026-10-06 23:38: the log said "keeper started" and the dial
         * still came back ENETUNREACH four seconds later because the rule was already gone. */
        startCarrierRouteKeeper(target);
        final String dial = target;
        final Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                mState.log(TAG, "wired AirPlay: dialing [" + dial + "]:" + port
                        + " (carrier " + NCM_IFACE + ")");
                boolean ok = false;
                try {
                    ok = client.run(dial, port);
                } catch (Throwable t) {
                    mState.log(TAG, "wired AirPlay: " + t);
                }
                mState.log(TAG, "wired AirPlay: session ended ok=" + ok
                        + " " + (ok ? "" : client.streamError()));
            }
        }, "carkit-airplay");
        thread.start();
    }

    /**
     * Makes the accessory's link-local address routable over the NCM interface.
     *
     * <p>Android keeps a link-local route in the per-interface table (measured: table 1032 for usb0)
     * and its policy rules send an unbound socket to the phone's current default network instead, so
     * a connect to the wired address failed with {@code ENETUNREACH} and the neighbour entry was only
     * ever created on wlan0 ({@code fe80::2 dev wlan0 FAILED}).  A route in the main table plus a
     * rule that selects it for exactly this address fixes it:
     *
     * <pre>
     *   ip -6 route replace fe80::2/128 dev usb0 table main
     *   ip -6 rule  add     to fe80::2/128 lookup main pref 110
     * </pre>
     *
     * <p>Targeted on purpose: a blanket {@code fe80::/64} rule would also divert the Wi-Fi paths'
     * link-local traffic, which the wireless sessions depend on.
     */
    private void routeToWiredCarrier(String address) {
        if (address.indexOf(':') < 0) {
            return;                                        /* an IPv4 hand-off needs no routing help */
        }
        clearWiredRouteRule();
        for (int attempt = 1; attempt <= 8; attempt++) {
            /* EXPLICIT -6 on every call: `ip route` infers the family from the address, but `ip rule`
             * defaults to IPv4 (measured 2026-10-06 on the fresh install: the rule command answered
             * rc=2 "RTNETLINK answers: Invalid argument" for fe80::2/128, so the rule was never
             * installed, the lookup fell through to wlan0 and the AirPlay dial died -- the log only
             * said "cannot route ... will fail with ENETUNREACH"). */
            runIp("-6", "route", "replace", address + "/128", "dev", NCM_IFACE, "table", "main");
            /* EEXIST is fine here: it means the rule we want is already installed -- and reporting
             * that as a fault is what made this log noisy, see runIpToleratingEexist(). */
            runIpToleratingEexist("-6", "rule", "add", "to", address + "/128", "lookup", "main", "pref", "110");
            final String got = ipRouteGet(address);
            if (got != null && got.contains(NCM_IFACE)) {
                mWiredRouteAddress = address;
                mState.log(TAG, "wired AirPlay: routed " + address + " via " + NCM_IFACE
                        + (attempt > 1 ? " (attempt " + attempt + ")" : ""));
                return;
            }
            mState.log(TAG, "wired AirPlay: routing " + address + " via " + NCM_IFACE
                    + " not effective yet (attempt " + attempt + ")"
                    + (got == null || got.trim().isEmpty() ? "" : ": " + got.trim()));
            sleepQuiet(500);
        }
        /* Saying "routed" without checking was a real trap: the AirPlay connect then failed with an
         * instant ENETUNREACH and the log claimed the route was in place. */
        mState.log(TAG, "wired AirPlay: cannot route " + address + " via " + NCM_IFACE
                + " - the AirPlay connect will fail with ENETUNREACH");
    }

    /** Output of `ip -6 route get <address>`, or null when the command could not run. */
    private String ipRouteGet(String address) {
        return runIp("-6", "route", "get", address);
    }

    private void sleepQuiet(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void clearWiredRouteRule() {
        final String address = mWiredRouteAddress;
        mWiredRouteAddress = null;
        if (address != null) {
            runIp("-6", "rule", "del", "to", address + "/128", "lookup", "main", "pref", "110");
        }
    }

    /**
     * Runs one `ip` invocation and reports anything unusual.  Returns the process output (stdout)
     * when it ran, or null when it could not be started.
     */
    /**
     * Keeps the carrier route alive for as long as a wired session is up.
     *
     * <p>One shot is not enough: every re-enumeration of the gadget (the framework re-applying its USB
     * config, our descriptor guard, a host retry) flushes the interface's routes, so the rule we added
     * survives but the route in {@code main} does not -- and the connect then fails instantly with
     * {@code ENETUNREACH} even though it was routed a moment earlier (measured 2026-10-06 23:35: the
     * daemon logged "routed fe80::2 via usb0" at 23:35:10 and the dial still came back ENETUNREACH).
     * Re-asserting both every 2 s while the session lives costs two `ip` calls and removes the race.
     */
    private void startCarrierRouteKeeper(final String address) {
        stopCarrierRouteKeeper();
        mRouteKeeper = new Thread(new Runnable() {
            @Override
            public void run() {
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException e) {
                        return;
                    }
                    if (mAirPlay == null) {
                        return;                       /* the session is gone */
                    }
                    runIp("-6", "route", "replace", address + "/128", "dev", NCM_IFACE, "table", "main");
                    /* The rule survives a re-enumeration but the route in main does not, so this is
                     * re-asserted every 2 s -- and the duplicate answers EEXIST while it is in place.
                     * A keeper steady state must not be logged as a fault. */
                    runIpToleratingEexist("-6", "rule", "add", "to", address + "/128", "lookup", "main", "pref", "110");
                }
            }
        }, "carkit-route-keeper");
        mRouteKeeper.setDaemon(true);
        mRouteKeeper.start();
        mState.log(TAG, "wired AirPlay: carrier route keeper started for " + address);
    }

    private void stopCarrierRouteKeeper() {
        final Thread t = mRouteKeeper;
        mRouteKeeper = null;
        if (t != null) {
            t.interrupt();
        }
    }

    private String runIp(String... args) {
        return runIp(args, false);
    }

    /**
     * Runs one ip invocation whose failure is expected: re-asserting an installed rule add answers
     * rc=2 "RTNETLINK answers: File exists".  That is the steady state of the carrier route keeper,
     * so the answer is not a fault and must not be reported as one (measured 2026-10-26: at the
     * keeper 2 s cadence it was one log line every 2 s).  Any other non-zero answer is still reported.
     */
    private String runIpToleratingEexist(String... args) {
        return runIp(args, true);
    }

    private String runIp(String[] args, boolean eexistOk) {
        final String[] command = new String[args.length + 1];
        command[0] = "/system/bin/ip";
        System.arraycopy(args, 0, command, 1, args.length);
        try {
            final Process process = Runtime.getRuntime().exec(command);
            /* Read both pipes before waiting: a small pipe buffer plus a non-draining parent is the
             * classic way to hang on the child's write. */
            final String stdout = readAll(process.getInputStream());
            final String stderr = readAll(process.getErrorStream());
            final int status = process.waitFor();
            if (status != 0 || !stderr.trim().isEmpty()) {
                if (!(eexistOk && IpAnswer.isExpectedEexist(args, status, stderr))) {
                    mState.log(TAG, "wired AirPlay: ip " + String.join(" ", args) + " rc=" + status
                            + (stderr.trim().isEmpty() ? "" : " err=" + stderr.trim()));
                }
            }
            return stdout;
        } catch (Throwable t) {
            mState.log(TAG, "wired AirPlay: ip " + String.join(" ", args) + " failed: " + t);
            return null;
        }
    }

    /* The decision itself lives in IpAnswer so the host gate needs no device and no framework. */

    private String readAll(java.io.InputStream in) {
        final StringBuilder text = new StringBuilder();
        try {
            final byte[] buffer = new byte[512];
            int read;
            while ((read = in.read(buffer)) > 0) {
                text.append(new String(buffer, 0, read, java.nio.charset.StandardCharsets.UTF_8));
            }
            in.close();
        } catch (Throwable ignored) {
            /* the exit status is what matters; a truncated read is still useful */
        }
        return text.toString();
    }

    private void stopWiredAirPlay() {
        stopCarrierRouteKeeper();
        clearWiredRouteRule();
        final WirelessAirPlayClient client = mAirPlay;
        mAirPlay = null;
        if (client != null) {
            try {
                client.stop();
            } catch (Throwable t) {
                mState.log(TAG, "wired AirPlay: stop failed: " + t);
            }
        }
    }

    @Override
    public void onWirelessFailure(String reason) {
        mState.log(TAG, "wired control channel failed: " + reason);
        closeSession("failure: " + reason);
    }

    @Override
    public void onWirelessLinkDown(String reason) {
        closeSession("link down: " + reason);
    }
}
