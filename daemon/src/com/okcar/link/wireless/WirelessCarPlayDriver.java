/*
 * OkcarOS link stack — wireless CarPlay bring-up state machine.
 *
 * Mirrors exactly what a real iPhone does on the RFCOMM iAP2 link (captured
 * 2026-09-26/27, doc §12.38 — every message, its order and its body was taken
 * from that capture, and the encoders/parsers were regression tested byte for
 * byte against it by WirelessSelfTest):
 *
 *   accessory            device (us)
 *   DETECT          ->
 *                   <-  DETECT
 *   SYN  (ctrl 0x80) ->
 *                   <-  SYN-ACK (ctrl 0xC0)
 *   0x1D01 identification ->
 *                   <-  0x1D02 accepted
 *                   <-  0xAA00 certificate request
 *   0xAA01 cert     ->
 *                   <-  0xAA02 32-byte challenge
 *   0xAA03 signature ->
 *                   <-  0xAA05 authenticated
 *                   <-  0x4E0D wireless update(1)
 *                   <-  0x4E0E {bluetooth id, device id}
 *                   <-  0x5702 "give me the Wi-Fi credentials"
 *   0x5703 credentials ->
 *                   <-  0x4300 CarPlayAvailability(1)
 *   0x4301 start session ->  (join that network, then open AirPlay on it)
 *
 * Note the alternative order: an iPhone that already cached the credentials
 * answers nothing and goes straight to the media session, so a missing 0x5702 /
 * 0x4300 round is not an error (observed with a stable-SSID accessory).
 */

package com.okcar.link.wireless;

import android.util.Log;

import com.okcar.link.LinkState;
import com.okcar.link.iap2.Iap2Frame;
import com.okcar.link.iap2.Iap2Link;
import com.okcar.link.iap2.Iap2Params;
import com.okcar.link.iap2.Iap2Params.Param;

import java.security.SecureRandom;
import java.util.List;

public final class WirelessCarPlayDriver implements Iap2Link.Listener {

    private static final String TAG = "OkCarLink/Wireless";

    /** Control session; the link's session entries declare 10/11/12 (§12.38 C.1). */
    private static final int CONTROL_SESSION = 10;

    public interface Callback {
        /** The accessory gave us everything needed to start AirPlay on its network. */
        void onWirelessStartSession(WirelessMessages.StartSession session);

        /** Bring-up failed in a way the operator should see. */
        void onWirelessFailure(String reason);

        /**
         * The iAP2 link that carries this session went away.  A session that already came up has
         * no failure watchdog left (it disarms on success), so without this the phone would stay
         * on the accessory's network after the accessory's hotspot disappears (measured
         * 2026-09-30) — stranded off its own LAN until a human intervenes.
         */
        void onWirelessLinkDown(String reason);

        /**
         * The iAP2 link just came up.  {@code wirelessIapFamily} is true when the
         * accessory's SYN declared control session 1 — the Fruit Link family that never
         * sends 0x4301 and therefore needs the session owner to bootstrap the
         * Wi-Fi/AirPlay path on its own.
         *
         * <p>Default no-op on purpose: the iAP2 tunnel driver inside
         * {@link com.okcar.link.airplay.WirelessAirPlayClient} implements this same
         * callback and must not start a second bootstrap.
         */
        default void onWirelessLinkUp(boolean wirelessIapFamily) {
        }
    }

    private Iap2Link mLink;
    private final Callback mCallback;
    private final SecureRandom mRandom = new SecureRandom();

    /** Cap on waiting for the accessory's 0x1D01; without it there are no credentials to talk about, so fail explicitly rather than force ahead. */
    private static final long IDENTIFY_WAIT_MS = 8000L;
    private final String mBluetoothId;
    private final String mDeviceId;
    private boolean mWired;   /* set by the wired host before announce() */

    private final LinkState mState = LinkState.get();

    /* What we learned from the accessory. */
    private WirelessMessages.Identification mIdentification;
    private WirelessMessages.WirelessInfo mWirelessInfo;
    private WirelessMessages.StartSession mStartSession;

    /* Guards so a retransmitted message cannot trigger a second exchange. */
    private boolean mAuthenticationStarted;
    private boolean mAvailabilityAnnounced;

    /**
     * The AirPlay receiver port the accessory's own receiver listens on (the production head unit: 7000);
     * used when 0x5703 hands us credentials without an address.
     */
    private static final int DIRECT_AIRPLAY_PORT = 7000;

    /**
     * 0x5703 is the credential hand-off, so it must join exactly once.
     *
     * <p>An earlier version of this driver parsed the credentials out of 0x5703, answered
     * 0x4300, and stopped there — the join was only wired to 0x4301 and to the {@code A5 5A}
     * announcement socket.  The the production head unit head unit never sends 0x4301, so nothing ever joined
     * its AP: the accessory brought up its AirPlay receiver, Bonjour-advertised it, and then
     * logged {@code [AirPlay] ### Failure: kTimeoutErr} / {@code AirPlayReceiverServerStop}
     * because nobody came (§3.19).
     */
    private volatile boolean mWirelessJoinHandedOff;
    private boolean mDetectSent;
    private volatile boolean mIdentified;

    /* 0x1D02 may be sent only once per session: a second one during the MFi phase makes the accessory report
     * "Unexpected iAP2 MFi message 0x1d02" and abort the whole bring-up (measured with 217 as the head unit and
     * lmi as the iPhone — it failed here every time). The driver is built once per session, so an instance field is session-scoped. */
    private final java.util.concurrent.atomic.AtomicBoolean mIdentAcceptedSent =
            new java.util.concurrent.atomic.AtomicBoolean();
    private volatile boolean mChallengeSent;
    private volatile boolean mAuthSucceeded;
    private volatile boolean mAnnounced;
    private volatile boolean mRunning;
    /** 0x4E0C session UUID, minted once per link. */
    private final String mSessionUuid =
            java.util.UUID.randomUUID().toString().toUpperCase(java.util.Locale.US);

    public WirelessCarPlayDriver(Callback callback, String bluetoothId, String deviceId) {
        mCallback = callback;
        mBluetoothId = bluetoothId == null ? "" : bluetoothId;
        mDeviceId = deviceId == null ? "" : deviceId;
    }

    /**
     * Wired role (the CarPlay service stream over USBMUX/NCM rather than a Wi-Fi join).  It changes
     * what {@link #announce()} sends: 0x4300 CarPlayAvailability with wiredAvailable=1 and
     * wirelessAvailable=0, and no 0x5702 Wi-Fi request -- there is no Wi-Fi to configure, and the
     * request only invited the accessory to answer 0x5703, which on a wired link it never does.
     */
    public void setWired(boolean wired) {
        mWired = wired;
    }

    /** The link this driver drives; set right after the link is constructed. */
    public void setLink(Iap2Link link) {
        mLink = link;
    }

    public WirelessMessages.Identification identification() {
        return mIdentification;
    }

    public WirelessMessages.WirelessInfo wirelessInfo() {
        return mWirelessInfo;
    }

    public WirelessMessages.StartSession startSession() {
        return mStartSession;
    }

    /* ------------------------------------------------------------ link events */

    @Override
    public void onLinkUp() {
        /*
         * Measured against Fruit Link (com.autochips.carplayapp), 2026-09-27:
         *  - StartIDPS (0x1D00) alone right after the SYN keeps the accessory's RFCOMM
         *    socket open for minutes: the app logs "read OK 6Bytes:FF,55", asks its
         *    licensing server for a trial key (183.36.35.43:1551) and waits.
         *  - Sending the MFi certificate request (0xAA00) *first* on link-up made it
         *    close the socket ~110 ms later ("bt socket closed") and fall back to dialing
         *    unrelated LE addresses.
         * So: ask for identification first; the auth request goes out from the 0x1D01
         * handler (onIdentification) exactly as the emulator-verified flow does.
         */
        mRunning = true;
        final boolean wirelessIap = wirelessIapFamily();
        if (mCallback != null) {
            /* Read-only notification: the session owner decides whether the known-credential
             * direct bootstrap applies (see WirelessCarPlaySession.onWirelessLinkUp). */
            mCallback.onWirelessLinkUp(wirelessIap);
        }
        if (wirelessIap) {
            /* This accessory's SYN is the byte-identical wireless-iAP block
             * (01 05 20 00 … 01 00 01 02 01 02) and a real iPhone answers that block
             * with AUTH FIRST — see bootstrapWirelessIap() (§12.55). */
            mState.log("wireless", "wireless-iAP accessory (control session 1) — authenticating first");
            send(WirelessMessages.MSG_AUTH_CERTIFICATE_REQUEST,
                    WirelessMessages.authCertificateRequest());
            startBootstrapWatchdog();
            return;
        }
        mState.log("wireless", "iAP2 link up — asking the accessory to identify (0x1D00)");
        send(WirelessMessages.MSG_START_IDPS, WirelessMessages.startIdps());
        startBootstrapWatchdog();
    }

    /**
     * True when the accessory's SYN declared control session 1, i.e. it belongs to the
     * wireless-iAP family — Fruit Link, and the emulator's early links — whose SYN block is
     * byte-identical: {@code 01 05 20 00 00 00 00 00 00 00 01 00 01 02 01 02}.
     *
     * The family matters because it changes both the session id to address and the
     * message order (see {@link #bootstrapWirelessIap()}); the emulator's CarPlay link
     * declares 10 and uses the identification-first order instead.
     */
    private boolean wirelessIapFamily() {
        return mLink.controlSession(CONTROL_SESSION) == 1;
    }

    /** Marks the link as gone so the watchdog stops talking into a dead socket. */
    public void onLinkClosed() {
        mRunning = false;
    }

    /**
     * The real iPhone does not wait for the accessory before it runs the MFi hand-shake and
     * announces the wireless session; Fruit Link never sends 0x1D01 at all (it ACKs our
     * StartIDPS and waits, showing "applying for trial certification").  A strictly reactive driver therefore
     * deadlocks with it: the app retries, counts {@code carplay:connecting timeout:N},
     * then asks for a reboot and kills itself.  This watchdog fills the sequence ~300 ms
     * apart, skipping every step the accessory already answered:
     *
     * <pre>
     *   0x1D00 -> 0x1D02 -> 0xAA00 -> 0xAA02 -> 0xAA05
     *   -> 0x4E0C(session uuid) -> 0x4E0A -> 0x4E0D -> 0x4E0E -> 0x5702
     * </pre>
     */
    private void startBootstrapWatchdog() {
        final Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                if (wirelessIapFamily()) {
                    bootstrapWirelessIap();
                    return;
                }
                /* Must wait for the accessory's 0x1D01; no more "sleep 300ms then blindly send 0x1D02": the accessory side
                 * (e.g. the emulator's Iap2IdentificationClient.identify) receives 0x1D00 → replies 0x1D01 → expects 0x1D02; if we
                 * push 0x1D02 first it returns immediately and enters MFi authentication, but the channel that authentication
                 * needs has already been treated by us as a "hand-off", so it fails on read and its UI stays at "preparing"
                 * (measured 217↔lmi). Without 0x1D01 there is no 0x5703 credential (§3.15), so on timeout report an explicit error instead of forcing ahead. */
                final long identifyDeadline = System.currentTimeMillis() + IDENTIFY_WAIT_MS;
                while (mRunning && !mIdentified && System.currentTimeMillis() < identifyDeadline) {
                    sleep(100);
                }
                if (!mRunning) {
                    return;
                }
                if (!mIdentified) {
                    mState.log("wireless", "no 0x1D01 from the accessory after "
                            + (IDENTIFY_WAIT_MS / 1000) + "s — the accessory never identified,"
                            + " so it will not send the Wi-Fi credentials either (0x5703)");
                    return;
                }
                sendIdentificationAcceptedOnce();
                sleep(300);
                if (!mRunning) {
                    return;
                }
                if (!mAuthenticationStarted) {
                    mAuthenticationStarted = true;
                    send(WirelessMessages.MSG_AUTH_CERTIFICATE_REQUEST,
                            WirelessMessages.authCertificateRequest());
                }
                sleep(400);
                if (!mRunning) {
                    return;
                }
                if (!mChallengeSent) {
                    mChallengeSent = true;
                    final byte[] challenge = new byte[32];
                    mRandom.nextBytes(challenge);
                    send(WirelessMessages.MSG_AUTH_CHALLENGE_REQUEST,
                            WirelessMessages.authChallengeRequest(challenge));
                }
                sleep(400);
                if (!mRunning) {
                    return;
                }
                if (!mAuthSucceeded) {
                    mAuthSucceeded = true;
                    send(WirelessMessages.MSG_AUTH_SUCCEEDED, WirelessMessages.authSucceeded());
                }
                announce();
            }
        }, "okcar-wireless-bootstrap");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * The CarPlay-session announcements, in the order the real iPhone used on the
     * session-10 the emulator link (capture 2026-09-26): {@code 0x5702 -> 0x4E0D -> 0x4E0E};
     * idempotent.  The wireless-iAP family uses {@link #bootstrapWirelessIap()}
     * instead, whose order additionally carries 0x4E0C/0x4E0A and repeats 0x5702.
     */
    private void announce() {
        if (mAnnounced || !mRunning) {
            return;
        }
        mAnnounced = true;
        if (mWired) {
            /* The accessory waits for this before it will hand over its CarPlay session (0x4301). */
            send(WirelessMessages.MSG_CARPLAY_AVAILABILITY,
                    WirelessMessages.carPlayAvailabilityWired(mDeviceId));
            send(WirelessMessages.MSG_TRANSPORT_IDENTIFIER_NOTIFICATION,
                    WirelessMessages.transportIdentifierNotification(mBluetoothId, mDeviceId));
            mState.log("carkit", "wired: announced CarPlay availability 0x4300 (wired=1 wireless=0)");
            return;
        }
        send(WirelessMessages.MSG_REQUEST_WIRELESS_INFO,
                WirelessMessages.requestWirelessInfo());
        send(WirelessMessages.MSG_WIRELESS_CARPLAY_UPDATE,
                WirelessMessages.wirelessCarplayUpdate(true));
        send(WirelessMessages.MSG_TRANSPORT_IDENTIFIER_NOTIFICATION,
                WirelessMessages.transportIdentifierNotification(mBluetoothId, mDeviceId));
        mState.log("wireless", "announced 0x4E0D/0x4E0E + asked for Wi-Fi (0x5702)");
    }

    /**
     * The message order the wireless-iAP family expects, read off the real iPhone in the
     * 2026-09-26 the emulator capture (its control-session-1 links):
     *
     * <pre>
     *   AA00 -> AA02 -> AA05 -> 1D00 -> 1D02 -> 4E0C -> 5702
     *        -> 4E0D -> 4E0E -> 4E0A -> 5702
     * </pre>
     *
     * Identification deliberately comes <em>after</em> authentication here — the reverse
     * of the CarPlay-session family — and 0x5702 is sent twice.  0xAA00 already went out
     * from {@code onLinkUp()}, so this method continues from the challenge.
     */
    private void bootstrapWirelessIap() {
        sleep(300);
        if (!mRunning) {
            return;
        }
        if (!mChallengeSent) {
            mChallengeSent = true;
            final byte[] challenge = new byte[32];
            mRandom.nextBytes(challenge);
            send(WirelessMessages.MSG_AUTH_CHALLENGE_REQUEST,
                    WirelessMessages.authChallengeRequest(challenge));
        }
        sleep(400);
        if (!mRunning) {
            return;
        }
        if (!mAuthSucceeded) {
            mAuthSucceeded = true;
            send(WirelessMessages.MSG_AUTH_SUCCEEDED, WirelessMessages.authSucceeded());
        }
        sleep(300);
        if (!mRunning) {
            return;
        }
        send(WirelessMessages.MSG_START_IDPS, WirelessMessages.startIdps());
        sleep(300);
        if (!mRunning) {
            return;
        }
        sendIdentificationAcceptedOnce();
        if (mAnnounced) {
            return;
        }
        mAnnounced = true;
        send(WirelessMessages.MSG_SESSION_UUID,
                WirelessMessages.wirelessSessionUuid(mSessionUuid));
        send(WirelessMessages.MSG_REQUEST_WIRELESS_INFO,
                WirelessMessages.requestWirelessInfo());
        send(WirelessMessages.MSG_WIRELESS_CARPLAY_UPDATE,
                WirelessMessages.wirelessCarplayUpdate(true));
        send(WirelessMessages.MSG_TRANSPORT_IDENTIFIER_NOTIFICATION,
                WirelessMessages.transportIdentifierNotification(mBluetoothId, mDeviceId));
        send(WirelessMessages.MSG_SESSION_INFO, WirelessMessages.wirelessSessionInfo());
        send(WirelessMessages.MSG_REQUEST_WIRELESS_INFO,
                WirelessMessages.requestWirelessInfo());
        mState.log("wireless", "wireless-iAP announcement sent (4E0C/5702/4E0D/4E0E/4E0A/5702)");
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void onLinkDown(String reason) {
        mRunning = false;
        mState.log("wireless", "link down: " + reason);
        final Callback callback = mCallback;
        if (callback != null) {
            callback.onWirelessLinkDown(reason);
        }
    }

    @Override
    public void onFrame(Iap2Frame frame) {
        /*
         * The captured iPhone answers the accessory's DETECT with its own DETECT
         * (1223.659 accessory DETECT -> 1223.674 device DETECT -> 1223.676 accessory
         * SYN), so we mirror that instead of staying silent until the SYN arrives.
         */
        if (frame.kind == Iap2Frame.KIND_DETECT && !mDetectSent) {
            mDetectSent = true;
            mLink.sendDetect();
            mState.log("wireless", "tx DETECT");
        }
    }

    /**
     * Where the accessory's HID/touch reports go.  The wireless stack hands them to the
     * same decoder+injector the wired path uses (Iap2SessionHandler), so touch behaves
     * identically on both link types.
     */
    public interface HidSink {
        /** @param payload the HID report, exactly as it arrived (no iAP2 parameter header) */
        void onHidReport(byte[] payload);
    }

    private volatile HidSink mHidSink;

    public void setHidSink(HidSink sink) {
        mHidSink = sink;
    }

    @Override
    public void onMessage(int sessionId, int messageId, byte[] payload) {
        if (messageId == com.okcar.link.iap2.Iap2Message.MSG_HID_REPORT) {
            final HidSink sink = mHidSink;
            mState.log("wireless", "accessory sent HIDReport (" + payload.length + "B) "
                    + (sink == null ? "(no sink)" : "-> injector"));
            if (sink != null) {
                sink.onHidReport(payload);
            }
            return;
        }
        switch (messageId) {
            case WirelessMessages.MSG_IDENTIFICATION:
                onIdentification(payload);
                break;

            case WirelessMessages.MSG_AUTH_CERTIFICATE_RESPONSE:
                onCertificate(payload);
                break;

            case WirelessMessages.MSG_AUTH_CHALLENGE_RESPONSE:
                onSignature(payload);
                break;

            case WirelessMessages.MSG_WIRELESS_INFO:
                onWirelessInfo(payload);
                break;

            case WirelessMessages.MSG_CARPLAY_START_SESSION:
                onStartSession(payload);
                break;

            case WirelessMessages.MSG_WIRELESS_CARPLAY_UPDATE:
            case WirelessMessages.MSG_TRANSPORT_IDENTIFIER_NOTIFICATION:
            case WirelessMessages.MSG_REQUEST_WIRELESS_INFO:
            case WirelessMessages.MSG_CARPLAY_AVAILABILITY:
                mState.log("wireless", "accessory sent " + WirelessMessages.name(messageId)
                        + " (" + payload.length + "B)");
                break;

            default:
                mState.log("wireless", "unhandled " + WirelessMessages.name(messageId)
                        + " sess=" + sessionId + " (" + payload.length + "B) "
                        + Iap2Params.toHex(payload));
                break;
        }
    }

    /* -------------------------------------------------------------- the steps */

    /** 0x1D02 exactly once; a repeat makes the accessory report Unexpected during the MFi stage and abort. */
    private void sendIdentificationAcceptedOnce() {
        if (!mIdentAcceptedSent.compareAndSet(false, true)) {
            return;
        }
        send(WirelessMessages.MSG_IDENTIFICATION_ACCEPTED,
                WirelessMessages.identificationAccepted());
    }

    private void onIdentification(byte[] payload) {
        mIdentified = true;
        mIdentification = WirelessMessages.parseIdentification(payload);
        mState.log("wireless", "accessory identification: " + mIdentification.describe());
        if (!mIdentification.advertisesByAccessory(WirelessMessages.MSG_WIRELESS_INFO)) {
            mState.log("wireless", "WARNING: accessory does not advertise 0x5703 "
                    + "(wireless CarPlay may not be offered)");
        }
        sendIdentificationAcceptedOnce();
        if (!mAuthenticationStarted) {
            mAuthenticationStarted = true;
            send(WirelessMessages.MSG_AUTH_CERTIFICATE_REQUEST,
                    WirelessMessages.authCertificateRequest());
        }
    }

    private void onCertificate(byte[] payload) {
        final List<Param> params = Iap2Params.decode(payload);
        final Param cert = Iap2Params.find(params, 0x0000);
        final int length = cert == null ? 0 : cert.value.length;
        mState.log("wireless", "accessory MFi certificate: " + length + "B");
        final byte[] challenge = new byte[32];
        mRandom.nextBytes(challenge);
        mChallengeSent = true;
        send(WirelessMessages.MSG_AUTH_CHALLENGE_REQUEST,
                WirelessMessages.authChallengeRequest(challenge));
    }

    private void onSignature(byte[] payload) {
        final List<Param> params = Iap2Params.decode(payload);
        final Param sig = Iap2Params.find(params, 0x0000);
        mState.log("wireless", "accessory MFi signature: "
                + (sig == null ? 0 : sig.value.length) + "B");
        mAuthSucceeded = true;
        send(WirelessMessages.MSG_AUTH_SUCCEEDED, WirelessMessages.authSucceeded());
        announce();
    }

    private void onWirelessInfo(byte[] payload) {
        mWirelessInfo = WirelessMessages.parseWirelessInfo(payload);
        mState.log("wireless", "accessory Wi-Fi: " + mWirelessInfo.describe());
        if (!mWirelessInfo.securityKnown()) {
            /* Reported here and again by the join, which refuses: an unknown securityType is not
             * something to guess at (a wpa2 attempt against an OWE/WPA3-only AP spends the whole
             * grace window and fails), and it must be visible as a status line, not a debug detail. */
            mState.log("wireless", "0x5703 securityType=" + mWirelessInfo.securityType
                    + " is not in the mapping table (" + WirelessMessages.Security.table()
                    + ") — not guessing; the Wi-Fi join will report this as an error");
        }
        if (mWirelessInfo.ssid.isEmpty()) {
            mState.log("wireless", "credentials are empty — cannot join a network");
            if (mCallback != null) {
                mCallback.onWirelessFailure("0x5703 carried no SSID");
            }
            return;
        }
        if (!mAvailabilityAnnounced) {
            mAvailabilityAnnounced = true;
            send(WirelessMessages.MSG_CARPLAY_AVAILABILITY,
                    WirelessMessages.carPlayAvailability(true, mDeviceId, mBluetoothId));
        }
        /*
         * 0x5703 *is* the hand-off: this is the accessory telling us which network to
         * join.  The the production head unit unit never sends 0x4301, so wiring the join only to that
         * message left the session parked at "credentials received".  Hand it to the
         * session exactly like 0x4301 does; claimJoin() keeps the two paths from
         * joining twice.  accessoryAddress stays empty — the direct path derives it
         * after the Wi-Fi join, the same as the A5 5A announcement path.
         */
        if (!mWirelessJoinHandedOff) {
            mWirelessJoinHandedOff = true;
            final WirelessMessages.StartSession session = new WirelessMessages.StartSession();
            session.ssid = mWirelessInfo.ssid;
            session.passphrase = mWirelessInfo.passphrase;
            session.securityType = mWirelessInfo.securityType;
            session.channel = mWirelessInfo.channel;
            session.airPlayPort = DIRECT_AIRPLAY_PORT;
            mState.log("wireless", "handing the accessory's Wi-Fi to the session: joining "
                    + session.ssid + " (port " + DIRECT_AIRPLAY_PORT + ")");
            if (mCallback != null) {
                mCallback.onWirelessStartSession(session);
            }
        }
    }

    private void onStartSession(byte[] payload) {
        mStartSession = WirelessMessages.parseStartSession(payload);
        mState.log("wireless", "accessory start session: " + mStartSession.describe());
        if (mCallback != null) {
            mCallback.onWirelessStartSession(mStartSession);
        }
    }

    /* ----------------------------------------------------------------- helpers */

    private void send(int messageId, byte[] payload) {
        /* Address the control session the accessory declared in its SYN.  the emulator's
         * CarPlay link uses 10, but the wireless-iAP block that Fruit Link sends
         * declares 01 00 01 — sending on 10 there is dropped by its session layer
         * while its link layer still ACKs, which reads as a silent accessory
         * (§12.55).  CONTROL_SESSION stays the fallback for an accessory that
         * declares no control entry. */
        final int session = mLink.controlSession(CONTROL_SESSION);
        final boolean ok = mLink.sendControl(session, messageId, payload);
        mState.log("wireless", "tx " + WirelessMessages.name(messageId)
                + " (" + payload.length + "B) sess=" + session + (ok ? "" : " FAILED"));
        Log.i(TAG, "tx " + WirelessMessages.name(messageId) + " " + payload.length + "B ok=" + ok);
    }
}
