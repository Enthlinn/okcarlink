/*
 * OkcarOS link stack — the always-on link service.
 *
 * Responsibilities for now (the skeleton stage of stage 3):
 *   - own the link stack lifetime as a foreground service
 *   - listen to the private PC_* broadcasts (the kernel/head-unit handshake)
 *   - drive the USB role through /dev/okcar
 *   - watch usb0 + its IPv6 link-local address
 *   - run the protocol self test on demand
 *
 * Media (L4) is not wired in yet: the AirPlay/RTSP server, the H.264 video
 * session and the audio session are the next layer, and they need the iAP2
 * transport (the head unit's vendor interface, class 0xff) which is still a
 * TODO in Iap2Link.  Until then this service is observable but passive, which is
 * exactly what the field validation of the plumbing needs.
 */

package com.okcar.link;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbManager;
import android.os.IBinder;
import android.util.Log;

import com.okcar.link.airplay.AirPlay;
import com.okcar.link.airplay.AirPlaySelfTest;
import com.okcar.link.airplay.AirPlaySession;
import com.okcar.link.airplay.AirPlayStreamCrypto;
import com.okcar.link.airplay.AudioStreamSender;
import com.okcar.link.airplay.EventChannel;
import com.okcar.link.airplay.ScreenStreamSender;
import com.okcar.link.castlink.CastLinkServer;
import com.okcar.link.airplay.SessionTransport;
import com.okcar.link.airplay.TimingResponder;
import com.okcar.link.hid.HidReportDecoder;
import com.okcar.link.iap2.Iap2Frame;
import com.okcar.link.iap2.Iap2Identification;
import com.okcar.link.iap2.Iap2Link;
import com.okcar.link.iap2.Iap2SelfTest;
import com.okcar.link.iap2.Iap2SessionHandler;
import com.okcar.link.iap2.UsbIap2Transport;
import com.okcar.link.input.InputInjector;
import com.okcar.link.media.MediaProbe;
import com.okcar.link.media.MirrorVideoSource;
import com.okcar.link.media.PlaybackAudioSource;
import com.okcar.link.net.LinkNetworkMonitor;
import com.okcar.link.usb.PcIntents;
import com.okcar.link.usb.UsbModeController;
import com.okcar.link.usb.UsbTopology;

import java.io.IOException;
import java.util.List;

public final class OkCarLinkService extends Service {

    /** 联动模式下 APK 侧触摸的按下状态：用来把连续的 down 报告分成 DOWN/MOVE。 */
    private volatile boolean mApkTouchDown;

    private static final String TAG = "OkCarLink";

    public static final String ACTION_ENTER_HOST = "com.okcar.link.action.ENTER_HOST";
    public static final String ACTION_BACK_TO_DEVICE = "com.okcar.link.action.BACK_TO_DEVICE";
    public static final String ACTION_REFRESH_ROLE = "com.okcar.link.action.REFRESH_ROLE";
    public static final String ACTION_SELF_TEST = "com.okcar.link.action.SELF_TEST";
    public static final String ACTION_USB_TOPOLOGY = "com.okcar.link.action.USB_TOPOLOGY";
    public static final String ACTION_CLAIM_IAP2 = "com.okcar.link.action.CLAIM_IAP2";
    public static final String ACTION_RELEASE_IAP2 = "com.okcar.link.action.RELEASE_IAP2";
    public static final String ACTION_AUTO_CLAIM_IAP2 = "com.okcar.link.action.AUTO_CLAIM_IAP2";
    public static final String EXTRA_AUTO_CLAIM = "autoClaim";
    public static final String ACTION_SEND_PROBE = "com.okcar.link.action.SEND_PROBE";
    /** Diagnostics: where does Bluetooth-sourced audio actually go? (§12.35) */
    public static final String ACTION_BT_PROBE = "com.okcar.link.action.BT_PROBE";
    /** Diagnostics: which IPC path can system_app open for the BT PCM tap? (§12.37) */
    public static final String ACTION_FILE_PROBE = "com.okcar.link.action.FILE_PROBE";
    public static final String ACTION_FORCE_CLAIM_IAP2 = "com.okcar.link.action.FORCE_CLAIM_IAP2";
    public static final String ACTION_MEDIA_PROBE = "com.okcar.link.action.MEDIA_PROBE";
    public static final String EXTRA_MDNS_PORT = "mdnsPort";
    /**
     * Wireless CarPlay without touching the UI: adb can drive it with
     * {@code am startservice -a com.okcar.link.action.WIRELESS_START -n com.okcar.link/.OkCarLinkService}.
     * Starting through LinkActivity instead brings the activity to the front, which steals audio
     * focus from whatever is playing and pauses the very audio we are supposed to relay.
     */
    /**
     * Diagnostic tone: plays a 1 kHz stereo tone through AudioFlinger until stopped.  adb:
     * {@code am start -n com.okcar.link/.LinkActivity --es action toneOn} (and {@code toneOff}).
     * It exists so the playback-capture path can be exercised without any third-party player.
     */
    /**
     * Capture-only probe: reads REMOTE_SUBMIX for a few seconds *without* playing anything and
     * reports frames / non-zero samples / peak / active output devices.  It answers "can we hear
     * the phone's current playback at all?", which is the question the wireless audio relay hangs on.
     */
    public static final String ACTION_CAPTURE_PROBE = "com.okcar.link.action.CAPTURE_PROBE";

    public static final String ACTION_TONE_ON = "com.okcar.link.action.TONE_ON";
    public static final String ACTION_TONE_OFF = "com.okcar.link.action.TONE_OFF";

    public static final String ACTION_WIRELESS_START = "com.okcar.link.action.WIRELESS_START";
    public static final String ACTION_WIRELESS_STOP = "com.okcar.link.action.WIRELESS_STOP";
    /** Device-initiated dial of the accessory's own iAP2 service ({@code ...caff}); extra {@code mac}. */
    public static final String ACTION_CONNECT_ACCESSORY = "com.okcar.link.action.CONNECT_ACCESSORY";
    public static final String EXTRA_WIRELESS_DEVICE_ID = "wirelessDeviceId";

    /**
     * Bounded, adb-triggerable proof of the Wi-Fi revert: record the pre-join network,
     * join the accessory AP, hold, then run the full self-verifying revert.  It
     * deliberately drops the device's Wi-Fi adb for ~30-45 s.  adb:
     * {@code am start -n com.okcar.link/.LinkActivity --es action wifiRevertSelfTest --ei holdSeconds 25}
     */
    public static final String ACTION_WIFI_REVERT_SELF_TEST =
            "com.okcar.link.action.WIFI_REVERT_SELF_TEST";
    /** Manual revert (same code path the watchdog and the self-heal use). */
    public static final String ACTION_WIFI_REVERT = "com.okcar.link.action.WIFI_REVERT";
    /** Optional hold duration in seconds for the self-test (default 25 s). */
    public static final String EXTRA_WIFI_REVERT_HOLD_SECONDS = "holdSeconds";

    public static final String ACTION_SESSION_START = "com.okcar.link.action.SESSION_START";
    public static final String ACTION_SESSION_STOP = "com.okcar.link.action.SESSION_STOP";
    public static final String EXTRA_SESSION_HOST = "sessionHost";
    public static final String EXTRA_SESSION_PORT = "sessionPort";
    /** Optional: replay the measured eiv/ekey/et blob in the base SETUP (default OFF). */
    public static final String EXTRA_SESSION_ENCRYPTION = "sessionEncryption";
    /** Optional: start mirroring + the screen stream after the handshake (default ON). */
    public static final String EXTRA_SESSION_MEDIA = "sessionMedia";

    /**
     * `true` also opens the audio stream (UDP RTP, PT 100, L16 big-endian) to the
     * audio dataPort the receiver returns in SETUP(100).  Default OFF: the video
     * path is the verified one, audio needs its first field test.
     */
    public static final String EXTRA_SESSION_AUDIO = "sessionAudio";

    /**
     * `false` makes the iAP2 ACK/SYN-ACK acknowledge the sequence just received
     * instead of "next expected" (seq + 1) — the head unit's own ACK builder uses
     * the former, and a wrong ACK is what keeps its link from coming up.
     */
    public static final String EXTRA_ACK_NEXT_EXPECTED = "ackNextExpected";

    /** `false`: do not send our own SYN on DETECT, only answer the head unit's SYN. */
    public static final String EXTRA_DEVICE_SENDS_SYN = "deviceSendsSyn";

    /** `false`: SYN-ACK carries our own parameters instead of echoing the peer's. */
    public static final String EXTRA_SYN_ACK_ECHO_PARAMS = "synAckEchoParams";
    /**
     * Optional: fixed `/auth-setup` challenge as hex.  The measured sender reuses
     * the same ekey/eiv every session, so its challenge is very likely fixed too;
     * injecting the measured 32 bytes is what makes the stream key replayable.
     */
    public static final String EXTRA_SESSION_CHALLENGE = "sessionChallenge";
    public static final String EXTRA_MEDIA_SECONDS = "mediaSeconds";
    public static final String EXTRA_AUTO_HOST = "autoHost";

    private static final String CHANNEL_ID = "okcar_link";
    private static final int NOTIFICATION_ID = 1201;

    private static volatile OkCarLinkService sInstance;

    private LinkNetworkMonitor mNetworkMonitor;
    private BroadcastReceiver mPcReceiver;
    private InputInjector mInputInjector;
    private Iap2SessionHandler mSessionHandler;
    private Iap2Link mIap2Link;
    private volatile AirPlaySession mAirPlaySession;
    /** Bound while a session runs: the timing port we advertise in the base SETUP. */
    private volatile java.net.DatagramSocket mTimingSocket;
    /** NTP-style responder on that port; without it RECORD answers 500. */
    private volatile TimingResponder mTimingResponder;
    /** Optional fixed challenge injected by `sessionChallenge` (hex). */
    private volatile byte[] mPendingChallenge;
    /** The interface the wired flow discovers and connects on (the car's NCM link). */
    private static final String WIRED_IFACE = "usb0";
    /** How long one wired mDNS browse waits before giving up (one boot-cycle wake attempt). */
    private static final long WIRED_DISCOVERY_TIMEOUT_MS = 8000L;
    /**
     * Control port the last wired mDNS browse advertised.  The receiver negotiates it
     * (measured 7000, but advertised — never assume), so discovery has to carry it to
     * {@link #startSession}; an explicit port argument still wins.
     */
    private volatile int mDiscoveredPort = -1;
    /** Media path: mirror encoder -> 128-byte frame wrapper -> negotiated video dataPort. */
    private volatile MirrorVideoSource mMirrorVideo;
    private volatile ScreenStreamSender mScreenSender;
    private volatile AudioStreamSender mAudioSender;
    private volatile PlaybackAudioSource mAudioSource;

    /**
     * Switch to USB HOST as soon as the head unit asks to interconnect.  This is
     * the step that makes the car re-enumerate as a device (see §12.17 A).
     * Left switchable because it is the one genuinely intrusive action here.
     */
    private volatile boolean mAutoHostOnAttach = true;

    /**
     * When on, the head unit's vendor interface is claimed as soon as it shows
     * up, and re-claimed after every re-enumeration.  This is what a real
     * replacement needs (and what makes a session capturable), but it does take
     * the iAP2 endpoint away from the closed-source app, so it is off until the
     * user turns it on.
     */
    private volatile boolean mAutoClaimIap2;

    public static OkCarLinkService getInstance() {
        return sInstance;
    }

    /** Starts (or wakes) the service; safe to call from anywhere. */
    public static void start(Context context) {
        try {
            context.startForegroundService(new Intent(context, OkCarLinkService.class));
        } catch (RuntimeException e) {
            Log.w(TAG, "cannot start service: " + e);
        }
    }

    /** Sends an action plus a boolean extra (startForegroundService keeps it alive). */
    public static void sendAction(Context context, String action, String extra, boolean value) {
        final Intent intent = new Intent(context, OkCarLinkService.class);
        intent.setAction(action);
        intent.putExtra(extra, value);
        try {
            context.startForegroundService(intent);
        } catch (RuntimeException e) {
            Log.w(TAG, "cannot send " + action + ": " + e);
        }
    }

    public static void sendAction(Context context, String action) {
        final Intent intent = new Intent(context, OkCarLinkService.class);
        intent.setAction(action);
        try {
            context.startForegroundService(intent);
        } catch (RuntimeException e) {
            Log.w(TAG, "cannot send " + action + ": " + e);
        }
    }

    /**
     * Forwards an activity's extras (session host/port/encryption/media) to the
     * service.  Without this the `sessionStart` action arrives with no target and
     * the service logs "no target" instead of opening the control channel.
     */
    public static void sendAction(Context context, String action, Intent extras) {
        final Intent intent = new Intent(context, OkCarLinkService.class);
        intent.setAction(action);
        if (extras != null) {
            intent.putExtras(extras);
        }
        try {
            context.startForegroundService(intent);
        } catch (RuntimeException e) {
            Log.w(TAG, "cannot send " + action + ": " + e);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        LinkState.get().setServiceRunning(true);
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification());
        LinkState.get().log("svc", "OkCarLink service started");

        mNetworkMonitor = new LinkNetworkMonitor();
        mNetworkMonitor.start();

        // Port wiring (plan §2.1): install the root-backed backends when present
        // (uinput touch / wm size / shell Wi-Fi), keep OkCarOS defaults otherwise.
        // Must happen before the first session constructs its privileged pieces.
        try {
            /* The panel's unrotated size.  The uinput device's ABS range has to be the panel space,
             * because the input stack rotates a touchscreen's raw range into the display's current
             * rotation by itself; the injector keeps the car's coordinates in the logical space and
             * UinputTouchBackend undoes that rotation on the way in (TouchGeometry).  Passing the
             * logical size instead makes the device follow the rotation too, so every tap gets
             * rotated twice — landscape taps then land reflected across the panel (measured
             * 2026-10-02, mirror mode).  These values are only the fallback used when the panel size
             * cannot be read, which is also why installDefaults is called before any session. */
            final android.util.DisplayMetrics panel = new android.util.DisplayMetrics();
            ((android.view.WindowManager) getSystemService(WINDOW_SERVICE))
                    .getDefaultDisplay().getRealMetrics(panel);
            com.okcar.link.port.PortBootstrap.installDefaults(
                    this, panel.widthPixels, panel.heightPixels);
        } catch (RuntimeException e) {
            Log.w(TAG, "port bootstrap skipped: " + e);
        }
        mInputInjector = new InputInjector(this);
        mSessionHandler = new Iap2SessionHandler(mInputInjector);

        registerPcReceiver();
        refreshRole("service start");
        /* Both of these repair what a previous run left behind, and the radio one runs first: a
         * half-finished radio toggle leaves the phone with no network at all, which would also make
         * the Wi-Fi revert below pointless. */
        com.okcar.link.wireless.WifiGroupJoiner.restoreRadioIfPending(this);
        healWifiAfterProcessRestart();
        autoStartWirelessServer();
    }

    /**
     * Self-heal for a process that died while associated to the accessory's Wi-Fi:
     * if the joiner's persisted "we joined an accessory network" record is still set,
     * revert immediately (re-associate the saved network and verify) instead of
     * leaving the device off its own LAN until a human toggles Wi-Fi by hand.  Runs on
     * its own thread so a slow Wi-Fi recovery cannot delay service startup.
     */
    private void healWifiAfterProcessRestart() {
        final Context context = this;
        final Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                com.okcar.link.wireless.WifiGroupJoiner.revertPendingJoin(context);
            }
        }, "okcar-wifi-revert-heal");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Wireless CarPlay is accessory-initiated: the head unit dials our SDP UUID, so the RFCOMM
     * listener has to be up whenever this stack runs — with it down the accessory just reports
     * "cannot connect" and the phone shows nothing at all (measured: after a `wirelessStop`
     * cleanup, the emulator simply never got in).  Started here so opening the app is enough.
     *
     * <p>Default on.  The lab turns it off with
     * {@code settings put global okcar_wireless_autostart 0} when it wants to drive the session
     * by hand; starting twice is a no-op (see {@code WirelessCarPlaySession.start}).
     */
    private void autoStartWirelessServer() {
        boolean enabled = true;
        try {
            enabled = android.provider.Settings.Global.getInt(getContentResolver(),
                    "okcar_wireless_autostart", 1) == 1;
        } catch (Throwable t) {
            LinkState.get().log("wireless", "cannot read okcar_wireless_autostart: " + t);
        }
        if (!enabled) {
            LinkState.get().log("wireless", "autostart disabled (okcar_wireless_autostart=0)");
            return;
        }
        try {
            final boolean started = com.okcar.link.wireless.WirelessCarPlaySession.get()
                    .start(this, null);
            LinkState.get().log("wireless", (started ? "autostart listening: " : "autostart NOT listening: ")
                    + com.okcar.link.wireless.WirelessCarPlaySession.get().status());
        } catch (Throwable t) {
            LinkState.get().log("wireless", "autostart failed: " + t);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        final String action = intent == null ? null : intent.getAction();
        if (intent != null && intent.hasExtra(EXTRA_AUTO_CLAIM)) {
            final boolean enabled = intent.getBooleanExtra(EXTRA_AUTO_CLAIM, mAutoClaimIap2);
            mAutoClaimIap2 = enabled;
            LinkState.get().setAutoClaimIap2(enabled);
            LinkState.get().log("iap2", enabled
                    ? "auto-takeover ENABLED — the head unit's vendor interface will be claimed "
                            + "whenever it appears (stop com.okcar.autoconn first, or the two will fight)"
                    : "auto-takeover disabled — no automatic claim");
        }
        if (intent != null && intent.hasExtra(EXTRA_AUTO_HOST)) {
            mAutoHostOnAttach = intent.getBooleanExtra(EXTRA_AUTO_HOST, mAutoHostOnAttach);
            LinkState.get().log("svc", "autoHostOnAttach = " + mAutoHostOnAttach);
        }
        if (action != null) {
            Log.i(TAG, "onStartCommand " + action);
            if (ACTION_ENTER_HOST.equals(action)) {
                enterHost("requested");
            } else if (ACTION_BACK_TO_DEVICE.equals(action)) {
                backToDevice("requested");
            } else if (ACTION_SELF_TEST.equals(action)) {
                runSelfTest();
            } else if (ACTION_USB_TOPOLOGY.equals(action)) {
                logUsbTopology();
            } else if (ACTION_CLAIM_IAP2.equals(action)) {
                claimIap2(false);
            } else if (ACTION_FORCE_CLAIM_IAP2.equals(action)) {
                claimIap2(true);
            } else if (ACTION_RELEASE_IAP2.equals(action)) {
                releaseIap2();
            } else if (ACTION_AUTO_CLAIM_IAP2.equals(action)) {
                maybeAutoClaim("auto-takeover requested");
            } else if (ACTION_SEND_PROBE.equals(action)) {
                sendIap2Probe();
            } else if (ACTION_BT_PROBE.equals(action)) {
                probeBtAudio();
            } else if (ACTION_FILE_PROBE.equals(action)) {
                probeFileAccess();
            } else if (ACTION_CAPTURE_PROBE.equals(action)) {
                captureProbe();
            } else if (ACTION_TONE_ON.equals(action)) {
                startTone();
            } else if (ACTION_TONE_OFF.equals(action)) {
                stopTone();
            } else if (ACTION_WIRELESS_START.equals(action)) {
                final String deviceId = intent == null ? null
                        : intent.getStringExtra(EXTRA_WIRELESS_DEVICE_ID);
                final boolean started = com.okcar.link.wireless.WirelessCarPlaySession.get()
                        .start(this, deviceId);
                LinkState.get().log("wireless", (started ? "started via service: " : "NOT started via service: ")
                        + com.okcar.link.wireless.WirelessCarPlaySession.get().status());
            } else if (ACTION_WIRELESS_STOP.equals(action)) {
                com.okcar.link.wireless.WirelessCarPlaySession.get().stop();
                LinkState.get().log("wireless", "stopped via service");
            } else if (ACTION_CONNECT_ACCESSORY.equals(action)) {
                /* The device-initiated half of the pair (the production head unit and friends): the head unit waits
                 * for the phone to dial 00000000-deca-fade-deca-deafdecacaff before it hands over
                 * its Wi-Fi credentials.  It lives here rather than only in LinkActivity because
                 * single-app capture must never see our own activity come to the front — starting
                 * it from the foreground service keeps the shared app on screen. */
                final String accessory = intent == null ? null : intent.getStringExtra("mac");
                final boolean dialling = com.okcar.link.wireless.WirelessCarPlaySession.get()
                        .connectToAccessory(accessory);
                LinkState.get().log("wireless", "connectAccessory via service " + accessory + ": "
                        + (dialling ? "dialling" : "not started"));
            } else if (ACTION_WIFI_REVERT_SELF_TEST.equals(action)) {
                final int hold = intent == null
                        ? com.okcar.link.wireless.WifiGroupJoiner.SELF_TEST_DEFAULT_HOLD_SECONDS
                        : intent.getIntExtra(EXTRA_WIFI_REVERT_HOLD_SECONDS,
                                com.okcar.link.wireless.WifiGroupJoiner
                                        .SELF_TEST_DEFAULT_HOLD_SECONDS);
                LinkState.get().log("wireless", "wifi-revert-selftest: requested via the service"
                        + " (hold=" + hold + "s)");
                com.okcar.link.wireless.WifiGroupJoiner.runRevertSelfTest(this, hold);
            } else if (ACTION_WIFI_REVERT.equals(action)) {
                LinkState.get().log("wireless", "wifi-revert: manual revert requested via the"
                        + " service");
                final Context context = getApplicationContext();
                final Thread revert = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        com.okcar.link.wireless.WifiGroupJoiner.releaseAll(context);
                    }
                }, "okcar-wifi-revert-manual");
                revert.setDaemon(true);
                revert.start();
            } else if (ACTION_SESSION_START.equals(action)) {
                final String host = intent == null ? null
                        : intent.getStringExtra(EXTRA_SESSION_HOST);
                final int port = intent == null ? 0
                        : intent.getIntExtra(EXTRA_SESSION_PORT, 0);
                final boolean encryption = intent != null
                        && intent.getBooleanExtra(EXTRA_SESSION_ENCRYPTION, false);
                final boolean media = intent == null
                        || intent.getBooleanExtra(EXTRA_SESSION_MEDIA, true);
                final boolean audio = intent != null
                        && intent.getBooleanExtra(EXTRA_SESSION_AUDIO, false);
                final String challengeHex = intent == null ? null
                        : intent.getStringExtra(EXTRA_SESSION_CHALLENGE);
                if (intent != null && intent.hasExtra(EXTRA_DEVICE_SENDS_SYN)) {
                    final boolean sendSyn = intent.getBooleanExtra(EXTRA_DEVICE_SENDS_SYN, true);
                    Iap2Link.setDeviceSendsSyn(sendSyn);
                    LinkState.get().log("iap2", "device SYN on DETECT: " + sendSyn);
                }
                if (intent != null && intent.hasExtra(EXTRA_SYN_ACK_ECHO_PARAMS)) {
                    final boolean echo = intent.getBooleanExtra(EXTRA_SYN_ACK_ECHO_PARAMS, true);
                    Iap2Link.setSynAckEchoParams(echo);
                    LinkState.get().log("iap2", "SYN-ACK payload: "
                            + (echo ? "echo peer params" : "our own params"));
                }
                if (intent != null && intent.hasExtra(EXTRA_ACK_NEXT_EXPECTED)) {
                    final boolean nextExpected =
                            intent.getBooleanExtra(EXTRA_ACK_NEXT_EXPECTED, true);
                    Iap2Link.setAckNextExpected(nextExpected);
                    LinkState.get().log("iap2", "iAP2 ACK convention: ack="
                            + (nextExpected ? "seq+1 (next expected)" : "received seq"));
                }
                if (challengeHex != null && challengeHex.length() >= 8) {
                    mPendingChallenge = com.okcar.link.util.Hex.decode(challengeHex);
                } else {
                    mPendingChallenge = null;
                }
                startSession(host, port, encryption, media, audio);
            } else if (ACTION_SESSION_STOP.equals(action)) {
                stopSession();
            } else if (ACTION_MEDIA_PROBE.equals(action)) {
                final int seconds = intent == null ? 10
                        : intent.getIntExtra(EXTRA_MEDIA_SECONDS, 10);
                runMediaProbe(seconds);
            } else if (ACTION_REFRESH_ROLE.equals(action)) {
                refreshRole("requested");
            }
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        LinkState.get().log("svc", "OkCarLink service stopping");
        releaseIap2();
        if (mPcReceiver != null) {
            try {
                unregisterReceiver(mPcReceiver);
            } catch (RuntimeException e) {
                Log.w(TAG, "unregister failed: " + e);
            }
            mPcReceiver = null;
        }
        if (mNetworkMonitor != null) {
            mNetworkMonitor.stop();
            mNetworkMonitor = null;
        }
        stopSession();
        LinkState.get().setServiceRunning(false);
        sInstance = null;
        super.onDestroy();
    }

    /* ------------------------------------------------------------------- L3 */

    /**
     * Opt-in: claim the head unit's vendor interface and run the iAP2 link on it.
     *
     * NEVER call this automatically: claiming the interface takes it away from
     * the closed-source app, which is still the working baseline.
     */
    /**
     * One USB role cycle: the head unit re-enumerates, our stack re-claims the
     * vendor interface and the identification messages (0x1D00 + 0x1D02) go out
     * again — which is what makes its AirPlay service appear.
     */
    private void wakeHeadUnit(int attempt) {
        LinkState.get().log("session", "waking the head unit's AirPlay service (attempt "
                + attempt + ") via a USB role toggle");
        backToDevice("wake the head unit's AirPlay service (attempt " + attempt + ")");
        try {
            Thread.sleep(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        if (mIap2Link == null) {
            claimIap2(false);
        }
        try {
            Thread.sleep(4000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Device -> accessory 0x1D00: ask the head unit to identify itself. */
    private void sendIdentificationRequest() {
        final Iap2Link link = mIap2Link;
        if (link == null) {
            return;
        }
        final boolean sent = link.sendControl(0, Iap2Identification.MSG_IDENTIFICATION_REQUEST,
                new byte[0]);
        LinkState.get().log("iap2", "IdentificationRequest 0x1D00 " + (sent ? "sent"
                : "WRITE FAILED"));
    }

    /**
     * Sends our device identification (0x1D02) on the iAP2 control session.
     *
     * Parameter IDs and message ids come from the head unit's own
     * libiPodForiAP2.so (see Iap2Identification): its dispatcher handles
     * 0x1D00/0x1D02/0x1D03 and its IdentificationInformation helpers write
     * 0x0001 model / 0x0002 manufacturer / 0x0003 serial / 0x0004 firmware /
     * 0x0005 hardware / 0x0006 sent-bitmask / 0x0026 transport component.
     */
    private void sendDeviceIdentification() {
        final Iap2Link link = mIap2Link;
        if (link == null) {
            return;
        }
        final byte[] information = Iap2Identification.deviceIdentification(
                AirPlay.SENDER_NAME, AirPlay.SENDER_MODEL, "OkcarOS", "okcar-link-0001",
                AirPlay.SENDER_OS_BUILD, "1.0");
        final boolean sent = link.sendControl(0,
                Iap2Identification.MSG_IDENTIFICATION_INFORMATION, information);
        LinkState.get().log("iap2", "IdentificationInformation 0x1D02 " + information.length
                + "B " + (sent ? "sent" : "WRITE FAILED"));
    }

    public void claimIap2(boolean force) {
        if (mIap2Link != null) {
            LinkState.get().log("iap2", "iAP2 transport already claimed");
            return;
        }
        LinkState.get().log("iap2", force
                ? "FORCE-claiming the head unit's vendor interface — this displaces the "
                        + "closed-source app's claim and kills its session (§12.24)"
                : "claiming the head unit's vendor interface (no force: fails cleanly if the "
                        + "closed-source app holds it)");
        try {
            final UsbIap2Transport transport = UsbIap2Transport.open(this, force);
            /* Wrap the session handler: as soon as the link is up we must identify
             * ourselves (0x1D02), otherwise the head unit keeps treating the phone
             * as an unknown USB device and resets the link periodically
             * (2026-09-26 field finding: ~5 min, then Broken pipe/ENETUNREACH). */
            mIap2Link = new Iap2Link(transport, new Iap2Link.Listener() {
                @Override
                public void onLinkUp() {
                    mSessionHandler.onLinkUp();
                    /* Both messages matter, and each does something different in the
                     * head unit's dispatcher:
                     *   0x1D00 -> it builds its own IdentificationInformation and
                     *             answers 0x1D01,
                     *   0x1D02 -> its handler calls PlaybackController::
                     *             startPlaybackControl() and sends 0xDA00 — measured
                     *             2026-09-26: the AirPlay receiver (port 7000) only
                     *             appeared after a 0x1D02, not after 0x1D00 alone.
                     * Any 0x1D01 it answers is accepted with 0x1D03. */
                    sendIdentificationRequest();
                    sendDeviceIdentification();
                }

                @Override
                public void onLinkDown(String reason) {
                    mSessionHandler.onLinkDown(reason);
                }

                @Override
                public void onMessage(int sessionId, int messageId, byte[] payload) {
                    mSessionHandler.onMessage(sessionId, messageId, payload);
                    if (messageId == Iap2Identification.MSG_ACCESSORY_IDENTIFICATION) {
                        /* The accessory answered with its own information (0x1D01):
                         * accept it (our own info already went out on link up). */
                        final Iap2Link link = mIap2Link;
                        if (link != null) {
                            final boolean sent = link.sendControl(0,
                                    Iap2Identification.MSG_IDENTIFICATION_ACCEPTED,
                                    new byte[]{0x00});
                            LinkState.get().log("iap2", "IdentificationAccepted 0x1D03 "
                                    + (sent ? "sent" : "WRITE FAILED"));
                        }
                    }
                }

                @Override
                public void onFrame(Iap2Frame frame) {
                    mSessionHandler.onFrame(frame);
                }
            });
            mIap2Link.start();
            final boolean detectSent = mIap2Link.sendDetect();
            LinkState.get().log("iap2", "transport open: " + transport.describe()
                    + " — DETECT " + (detectSent ? "sent" : "WRITE FAILED")
                    + ", waiting for the head unit's SYN");
        } catch (IOException e) {
            mIap2Link = null;
            LinkState.get().log("iap2", "cannot claim iAP2 transport: " + e.getMessage());
        }
    }

    public void releaseIap2() {
        final Iap2Link link = mIap2Link;
        if (link == null) {
            return;
        }
        mIap2Link = null;
        link.stop();
        LinkState.get().log("iap2", "iAP2 transport released");
    }

    public boolean isIap2Claimed() {
        return mIap2Link != null;
    }

    /** Pokes the link: DETECT then SYN, so a field session can be driven by hand. */
    public void sendIap2Probe() {
        final Iap2Link link = mIap2Link;
        if (link == null) {
            LinkState.get().log("iap2", "probe ignored: the iAP2 transport is not claimed");
            return;
        }
        final boolean detect = link.sendDetect();
        final boolean syn = link.sendSyn();
        LinkState.get().log("iap2", "probe sent: DETECT=" + detect + " SYN=" + syn);
    }

    /**
     * Claims the link when auto-takeover is on and the head unit is on the bus.
     *
     * This is what makes a real session capturable: our first claim kills the
     * closed-source app's session, the head unit re-handshakes, and we claim the
     * freshly enumerated device instead of sitting on a dead handle (which is
     * exactly what happened in the first field test, §12.24).
     */
    private void maybeAutoClaim(String why) {
        if (!mAutoClaimIap2) {
            return;
        }
        if (isIap2Claimed()) {
            LinkState.get().log("iap2", "auto-takeover: already claimed, ignoring (" + why + ')');
            return;
        }
        if (UsbTopology.findHeadUnit(this) == null) {
            LinkState.get().log("iap2", "auto-takeover: head unit not on the bus yet (" + why + ')');
            return;
        }
        LinkState.get().log("iap2", "auto-takeover: claiming (" + why + ')');
        claimIap2(false);
    }

    /* ------------------------------------------------------------------- L1 */

    public void refreshRole(String why) {
        final int mode = UsbModeController.readMode();
        final String detail;
        if (mode < 0) {
            detail = UsbModeController.nodeExists()
                    ? "read failed"
                    : "/dev/okcar missing (okcar kernel driver not loaded)";
        } else {
            detail = why;
        }
        LinkState.get().setUsbMode(mode, detail);
        LinkState.get().log("usb", "role = " + LinkState.usbModeName(mode) + " (" + detail + ")");
    }

    public void enterHost(String why) {
        LinkState.get().log("usb", "switching to HOST (" + why + ')');
        if (UsbModeController.setMode(UsbModeController.MODE_HOST)) {
            LinkState.get().log("usb",
                    "usbmode,1,2 written — the head unit should re-enumerate as a device");
        } else {
            LinkState.get().log("usb", "HOST switch FAILED (write to /dev/okcar rejected)");
        }
        refreshRole("after host switch");
    }

    public void backToDevice(String why) {
        LinkState.get().log("usb", "switching to DEVICE (" + why + ')');
        if (UsbModeController.setMode(UsbModeController.MODE_DEVICE)) {
            LinkState.get().log("usb", "usbmode,1,1 written");
        } else {
            LinkState.get().log("usb", "DEVICE switch FAILED (write to /dev/okcar rejected)");
        }
        refreshRole("after device switch");
    }

    /* -------------------------------------------------------------- PC_* L5 */

    /**
     * Read-only USB report: which interfaces/endpoints the head unit exposes.
     * Never claims an interface, so it is safe next to the closed-source app.
     */
    public void logUsbTopology() {
        final String report = UsbTopology.describe(this);
        for (String line : report.split("\n")) {
            LinkState.get().log("usb-topo", line);
        }
    }

    private void registerPcReceiver() {
        mPcReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                handleBroadcast(intent);
            }
        };
        final IntentFilter filter = new IntentFilter();
        for (String action : PcIntents.ALL) {
            filter.addAction(action);
        }
        // The head unit only appears on the bus after the HOST role switch, so we
        // also want the plain USB attach/detach events.
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        try {
            registerReceiver(mPcReceiver, filter, Context.RECEIVER_EXPORTED);
            LinkState.get().log("svc", "listening for PC_* and USB attach broadcasts");
        } catch (RuntimeException e) {
            Log.w(TAG, "cannot register PC_* receiver: " + e);
            mPcReceiver = null;
        }
    }

    private void handleBroadcast(Intent intent) {
        final String action = intent.getAction();
        if (action == null) {
            return;
        }
        if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
            final UsbTopology.DeviceInfo device =
                    UsbTopology.of(intent.getParcelableExtra(UsbManager.EXTRA_DEVICE));
            if (device != null) {
                LinkState.get().log("usb", "attached: " + device.describe().replace("\n", " | "));
                if (device.isHeadUnit()) {
                    LinkState.get().setCarAttached(true);
                    logUsbTopology();
                    maybeAutoClaim("head unit attached");
                }
            }
            return;
        }
        if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
            final UsbTopology.DeviceInfo device =
                    UsbTopology.of(intent.getParcelableExtra(UsbManager.EXTRA_DEVICE));
            final boolean headUnit = device == null || device.isHeadUnit();
            LinkState.get().log("usb", "detached"
                    + (device == null ? "" : " (" + device.describe().split("\n")[0] + ')'));
            if (headUnit && isIap2Claimed()) {
                // The claim died with the device: release it, otherwise the stale
                // handle would keep polling and block the next attempt.
                LinkState.get().log("iap2", "head unit detached — releasing the iAP2 transport");
                releaseIap2();
            }
            return;
        }
        LinkState.get().log("pc", action);
        if (PcIntents.ACTION_PC_ATTACHED.equals(action)) {
            LinkState.get().setCarAttached(true);
            if (mAutoHostOnAttach) {
                enterHost("PC_ATTACHED");
            }
        } else if (PcIntents.ACTION_PC_USB_STATE_DISCONNECTED.equals(action)) {
            LinkState.get().setCarAttached(false);
        }
    }

    /* ------------------------------------------------------------ self test */

    public void runSelfTest() {
        LinkState.get().log("svc", "protocol self test starting");
        logUsbTopology();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<String> iap2Lines = Iap2SelfTest.run();
                final List<String> airplayLines = AirPlaySelfTest.run();
                final List<String> lines = new java.util.ArrayList<String>();
                lines.addAll(iap2Lines);
                lines.addAll(airplayLines);
                int passed = 0;
                for (String line : lines) {
                    LinkState.get().log("test", line);
                    if (line.startsWith("PASS")) {
                        passed++;
                    }
                }
                LinkState.get().setLastSelfTest(passed + "/" + lines.size() + " passed"
                        + " (iAP2 " + iap2Lines.size() + " + AirPlay " + airplayLines.size() + ')');
                LinkState.get().log("svc", "self test: " + passed + "/" + lines.size());
                Log.i(TAG, "self test: " + passed + "/" + lines.size());
            }
        }, "okcar-selftest").start();
    }

    /* ----------------------------------------------------------- L4 session */

    /**
     * Opens the RTSP control channel and walks the session steps.
     *
     * Host/port come from the caller, or from the last `_airplay._tcp` service the
     * mDNS browser resolved (press "Discover services" first).  The step ORDER is the part
     * still to be confirmed by a field capture, so each step logs exactly what was
     * sent and what came back instead of assuming it worked.
     */
    public void startSession(String resolvedHost, int port) {
        startSession(resolvedHost, port, false, true);
    }

    public void startSession(String resolvedHost, int port, boolean withEncryption) {
        startSession(resolvedHost, port, withEncryption, true);
    }

    /**
     * Opens the RTSP control channel and runs the MEASURED handshake
     * (the wire-capture notes §1, the recorded reference session):
     *
     *   POST /auth-setup -> base SETUP -> GET /info -> RECORD -> POST /command(modesChanged)
     *   -> POST /command(disableBluetooth) -> POST /feedback -> SETUP(110) -> SETUP(100)
     *
     * Ports are read out of the SETUP answers (measured 45319 / 34800 on this car);
     * nothing is hard-coded.  Host/port come from the caller or from the last
     * `_airplay._tcp` service the mDNS browser resolved.
     *
     * @param withEncryption replay the measured `eiv`/`ekey`/`et` blob in the base
     *                       SETUP.  Default OFF: if the receiver accepts a cleartext
     *                       H.264 stream, none of the key exchange is needed.
     */
    /**
     * Resolves the head unit's AirPlay address: the explicit one if given, else a native mDNS
     * browse of {@code _airplay._tcp} on usb0 (up to {@link #WIRED_DISCOVERY_TIMEOUT_MS}).
     *
     * Nothing here may be hard-coded: a head unit hands out a new IPv6 link-local address on
     * every boot (observed 2026-09-26: f8:a5ff:… → bcc9:23ff:… → d0e6:4c83:…) and different
     * units differ anyway.  The discovered address is returned with the {@code %usb0} scope a
     * link-local socket needs; the discovered **port** is kept in {@link #mDiscoveredPort},
     * because the receiver's control port is advertised, not fixed.  An explicit
     * {@code --es sessionHost <ip>%usb0} still wins.
     */
    private String resolveSessionHost(String explicit) {
        if (explicit != null && !explicit.isEmpty()) {
            return explicit;
        }
        final com.okcar.link.net.MdnsDiscovery.Service found =
                com.okcar.link.net.MdnsDiscovery.browseForAirPlay(WIRED_IFACE,
                        WIRED_DISCOVERY_TIMEOUT_MS);
        if (found == null) {
            LinkState.get().log("session", "no _airplay._tcp on " + WIRED_IFACE
                    + " after " + WIRED_DISCOVERY_TIMEOUT_MS + "ms (is the head unit awake?"
                    + " pass --es sessionHost <ip>%usb0 to skip discovery)");
            return null;
        }
        final String host = found.hostForSocket(WIRED_IFACE);
        if (host == null) {
            LinkState.get().log("session", "discovered " + found.describe()
                    + " but no usable address");
            return null;
        }
        mDiscoveredPort = found.port;
        LinkState.get().log("session", "mDNS: " + found.describe() + " -> " + host);
        return host;
    }

    public void startSession(String resolvedHost, int port, final boolean withEncryption,
                             final boolean withMedia) {
        startSession(resolvedHost, port, withEncryption, withMedia, false);
    }

    public void startSession(String resolvedHost, int port, final boolean withEncryption,
                             final boolean withMedia, final boolean withAudio) {
        if (mAirPlaySession != null) {
            /* A session object survives a link reset (its transport is dead but the
             * state still says RECORDING), which used to make a second attempt fail
             * with "session already running" until the user called sessionStop.
             * Replace it instead. */
            LinkState.get().log("session", "replacing previous session ("
                    + mAirPlaySession.state() + ')');
            stopSession();
        }
        String target = resolvedHost;
        int targetPort = port;
        if (target == null || target.isEmpty()) {
            LinkState.get().log("session", "no host given — pass --es sessionHost <ip>%usb0");
            return;
        }
        if (targetPort <= 0) {
            targetPort = AirPlay.PORT_AIRPLAY_DEFAULT;
            LinkState.get().log("session", "no port advertised, falling back to "
                    + targetPort + " (the port is negotiated, do not hard-code it — §12.17 C)");
        }
        final String hostFinal = target;
        final int portFinal = targetPort;
        LinkState.get().log("session", "starting control channel to "
                + (hostFinal == null || hostFinal.isEmpty() ? "(mDNS discovery)" : hostFinal)
                + ':' + portFinal + " (encryption=" + withEncryption + ")");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    /* Wake-up is racy: the head unit only publishes its AirPlay
                     * service once it has accepted our iAP2 link, so retry the USB
                     * role toggle until mDNS sees _airplay._tcp and the socket
                     * connects (field: sometimes the first takeover is ignored). */
                    String resolvedHost = resolveSessionHost(hostFinal);
                    SessionTransport transport = null;
                    for (int attempt = 1; attempt <= 3 && transport == null; attempt++) {
                        if (resolvedHost == null) {
                            if (attempt >= 3) {
                                break;
                            }
                            wakeHeadUnit(attempt);
                            resolvedHost = resolveSessionHost(hostFinal);
                            continue;
                        }
                        /* Port priority: explicit argument > what mDNS advertised > the
                         * AirPlay default.  The receiver's port is negotiated, never assumed. */
                        final int advertised = mDiscoveredPort;
                        final int tryPort = portFinal > 0 ? portFinal
                                : (advertised > 0 ? advertised : AirPlay.PORT_AIRPLAY_DEFAULT);
                        try {
                            transport = SessionTransport.TcpSessionTransport
                                    .outgoing(resolvedHost, tryPort, 5000);
                        } catch (IOException e) {
                            LinkState.get().log("session", "connect " + resolvedHost
                                    + " failed: " + e.getMessage());
                            resolvedHost = null;
                            if (attempt < 3) {
                                wakeHeadUnit(attempt);
                            }
                        }
                    }
                    if (transport == null || resolvedHost == null) {
                        LinkState.get().log("session", "no reachable AirPlay host after 3 attempts "
                                + "(pass --es sessionHost <ip>%usb0 if mDNS cannot see it)");
                        return;
                    }
                    final int port = portFinal > 0 ? portFinal
                            : (mDiscoveredPort > 0 ? mDiscoveredPort : AirPlay.PORT_AIRPLAY_DEFAULT);
                    final AirPlaySession session = new AirPlaySession(transport, null);
                    mAirPlaySession = session;
                    /* The receiver pushes its touch reports over the event channel:
                     * 5 bytes = [state][xLo][xHi][yLo][yHi] over 1920x720, with
                     * state 0xff = pressed/moving and 0x00 = released (measured on
                     * the recorded reference session: 23 real samples, incl. two drag gestures).  That is
                     * exactly HidReportDecoder's untyped 5-byte layout. */
                    /* The head unit drives us with its own commands on this channel
                     * too.  Measured 2026-09-26 (§12.34): when the driver leaves and
                     * re-enters CarPlay it sends {type=changeModes, ...} and then
                     * {type=requestUI}; its re-created H264 decoder stays black until
                     * the sender answers requestUI with a fresh avcC + an IDR. */
                    session.setEventCommandSink(new EventChannel.CommandSink() {
                        @Override
                        public void onCommand(String type, java.util.Map<String, Object> plist) {
                            LinkState.get().log("event", "head unit command: " + type);
                            if ("requestUI".equals(type)) {
                                resendUi();
                            }
                        }
                    });
                    session.setEventSink(new EventChannel.Sink() {                        @Override
                        public void onHidReport(byte[] report) {
                            final HidReportDecoder.Report decoded =
                                    HidReportDecoder.decode(report);
                            if (decoded == null) {
                                LinkState.get().log("event", "undecodable HID report ("
                                        + report.length + "B)");
                                return;
                            }
                            final boolean touch = decoded.type == HidReportDecoder.TYPE_TOUCH;
                            LinkState.get().onHidReport(decoded.describe(), touch, !touch);
                            if (!touch) {
                                /* Media/phone buttons: the payload shape is known but
                                 * the button encoding is not — log, do not guess. */
                                return;
                            }
                            /* 联动模式（CarWith/APK 拥有那块屏）：把触摸经 okcar-cast 回传给 APK，
                             * 由它用 (userId, displayId) 注入到 CarWith 自己的投屏屏上。启用标记就是
                             * 这个模式的开关——模块自己那条自建屏的路已经不再走。 */
                            if (com.okcar.link.castlink.CastLinkServer.enabled()) {
                                final int apkAction;
                                if (decoded.down) {
                                    apkAction = mApkTouchDown ? 2 : 0;
                                    mApkTouchDown = true;
                                } else {
                                    apkAction = 1;
                                    mApkTouchDown = false;
                                }
                                final int[] geo = com.okcar.link.daemon.LinkDaemon.apkGeometry();
                                final int targetWidth = geo[1] > 0 ? geo[1] : 1920;
                                final int targetHeight = geo[2] > 0 ? geo[2] : 720;
                                final int sourceWidth = decoded.sourceWidth > 0
                                        ? decoded.sourceWidth : targetWidth;
                                final int sourceHeight = decoded.sourceHeight > 0
                                        ? decoded.sourceHeight : targetHeight;
                                final float scaledX = com.okcar.link.carkit.CarwithContract
                                        .coordinate(decoded.x, sourceWidth, targetWidth);
                                final float scaledY = com.okcar.link.carkit.CarwithContract
                                        .coordinate(decoded.y, sourceHeight, targetHeight);
                                com.okcar.link.castlink.CastLinkServer.get()
                                        .sendTouch(apkAction, scaledX, scaledY, -1);
                                LinkState.get().log("event", "touch -> apk action=" + apkAction
                                        + " display=" + geo[0] + " " + scaledX + ',' + scaledY);
                                return;
                            }
                            if (!mSessionHandler.isInjectTouchEnabled()
                                    || mInputInjector == null) {
                                return;
                            }
                            if (!mInputInjector.injectTouch(decoded)) {
                                LinkState.get().log("event", "touch injection failed: "
                                        + decoded.describe());
                            }
                        }
                    });
                    final byte[] challenge = mPendingChallenge;
                    if (challenge != null) {
                        session.setChallenge(challenge);
                        LinkState.get().log("session", "using injected challenge ("
                                + challenge.length + "B)");
                    }
                    LinkState.get().log("session", "control channel up: " + transport.describe());
                    /* Bind the timing port we are about to advertise, so the peer's
                     * NTP-style packets (PT 210/211) have somewhere to land. */
                    if (mTimingSocket == null || mTimingSocket.isClosed()) {
                        mTimingSocket = new java.net.DatagramSocket(0);
                    }
                    final int timingPort = mTimingSocket.getLocalPort();
                    /* The receiver runs its clock from this port BEFORE it will
                     * record: start answering its PT=210 probes immediately. */
                    if (mTimingResponder == null) {
                        mTimingResponder = new TimingResponder(mTimingSocket);
                        mTimingResponder.start();
                    }
                    /* 19-digit session id, same shape the working sender uses. */
                    final String sessionId = Long.toString(1000000000000000000L
                            + (long) (Math.random() * 8000000000000000000L));
                    final boolean ok = session.runHandshake(resolvedHost, sessionId, timingPort,
                            withEncryption);
                    LinkState.get().log("session", "handshake " + (ok ? "complete" : "incomplete")
                            + ": video dataPort " + session.videoDataPort()
                            + ", audio dataPort " + session.audioDataPort()
                            + ", timing port " + timingPort);
                    if (ok && withMedia && session.videoDataPort() > 0) {
                        startScreenStream(resolvedHost, session.videoDataPort(),
                                session.videoStreamConnectionId(), withEncryption);
                    }
                    if (ok && withAudio && session.audioDataPort() > 0) {
                        unmutePlayback();
                        startAudioStream(resolvedHost, session.audioDataPort());
                    }
                } catch (IOException e) {
                    LinkState.get().log("session", "session failed: " + e.getMessage());
                    mAirPlaySession = null;
                }
            }
        }, "okcar-airplay-session").start();
    }

    /**
     * Starts the mirror encoder and pipes its frames into the negotiated video
     * dataPort, wrapped with the measured 128-byte header.
     *
     * The codec-config (avcC) frame goes out first, in the clear.  With
     * {@code withEncryption} every later frame's payload is XORed with the
     * session's continuous AES-128-CTR keystream, derived from the video SETUP's
     * `streamConnectionID` — the receiver derives the identical key/IV, and the
     * derivation is proved against the recorded reference session (the internal notes).
     */
    /**
     * Bluetooth-sourced audio never reaches the mixer on this ROM: while BT music
     * plays, every AudioFlinger output thread (including the speaker and the
     * r_submix one) stays in standby, because the A2DP-sink software bridge writes
     * PCM below AudioFlinger.  REMOTE_SUBMIX therefore cannot see it — this probe
     * answers the only question that matters for a fix: can a second capture leg
     * read the ROM's AUDIO_DEVICE_IN_BLUETOOTH_A2DP input at all?
     */
    private void probeBtAudio() {
        try {
            final android.media.AudioManager am = (android.media.AudioManager)
                    getSystemService(Context.AUDIO_SERVICE);
            if (am == null) {
                LinkState.get().log("bt-probe", "no AudioManager");
                return;
            }
            final android.media.AudioDeviceInfo[] inputs =
                    am.getDevices(android.media.AudioManager.GET_DEVICES_INPUTS);
            LinkState.get().log("bt-probe", "input devices: " + inputs.length);
            boolean sawA2dp = false;
            for (android.media.AudioDeviceInfo dev : inputs) {
                final String name = String.valueOf(dev.getProductName());
                final boolean a2dp = dev.getType()
                                == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                        || name.toUpperCase().contains("A2DP");
                LinkState.get().log("bt-probe", "in type=" + dev.getType() + " id=" + dev.getId()
                        + " name=" + name + " addr=" + dev.getAddress()
                        + (a2dp ? "  <== A2DP" : ""));
                if (a2dp) {
                    sawA2dp = true;
                }
            }
            if (!sawA2dp) {
                LinkState.get().log("bt-probe", "no A2DP input device exposed to apps");
            }
            logCapture("bt-probe", "control REMOTE_SUBMIX", null);
            for (android.media.AudioDeviceInfo dev : inputs) {
                final String name = String.valueOf(dev.getProductName());
                if (dev.getType() == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                        || name.toUpperCase().contains("A2DP")) {
                    logCapture("bt-probe", "A2DP input " + name, dev);
                }
            }
        } catch (Throwable t) {
            LinkState.get().log("bt-probe", "probe failed: " + t);
        }
    }

    /** Opens one capture leg for 2 s and reports whether it carried any signal. */
    private void logCapture(String tag, String label, android.media.AudioDeviceInfo dev) {
        android.media.AudioRecord rec = null;
        try {
            final int rate = 48000;
            final android.media.AudioFormat fmt = new android.media.AudioFormat.Builder()
                    .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(android.media.AudioFormat.CHANNEL_IN_STEREO)
                    .build();
            final int min = android.media.AudioRecord.getMinBufferSize(rate,
                    android.media.AudioFormat.CHANNEL_IN_STEREO,
                    android.media.AudioFormat.ENCODING_PCM_16BIT);
            rec = new android.media.AudioRecord.Builder()
                    .setAudioSource(dev == null
                            ? android.media.MediaRecorder.AudioSource.REMOTE_SUBMIX
                            : android.media.MediaRecorder.AudioSource.MIC)
                    .setAudioFormat(fmt)
                    .setBufferSizeInBytes(Math.max(min, rate * 4))
                    .build();
            final boolean preferred = dev == null || rec.setPreferredDevice(dev);
            rec.startRecording();
            final byte[] buf = new byte[rate * 4];
            long bytes = 0;
            long nonzero = 0;
            int peak = 0;
            final long end = android.os.SystemClock.elapsedRealtime() + 2000;
            while (android.os.SystemClock.elapsedRealtime() < end) {
                final int n = rec.read(buf, 0, buf.length);
                if (n <= 0) {
                    break;
                }
                bytes += n;
                for (int i = 0; i + 1 < n; i += 2) {
                    final int sample = (short) ((buf[i] & 0xFF) | (buf[i + 1] << 8));
                    if (sample != 0) {
                        nonzero++;
                    }
                    peak = Math.max(peak, Math.abs(sample));
                }
            }
            LinkState.get().log(tag, label + ": state=" + rec.getState() + " pref=" + preferred
                    + " bytes=" + bytes + " nonzero=" + nonzero + " peak=" + peak);
        } catch (Throwable t) {
            LinkState.get().log(tag, label + " capture failed: " + t);
        } finally {
            if (rec != null) {
                try {
                    rec.stop();
                } catch (Throwable ignored) {
                    /* not started */
                }
                rec.release();
            }
        }
    }

    /**
     * The A2DP-sink PCM is only reachable inside the Bluetooth stack, so the tap
     * needs a BT -> app IPC path, and SELinux decides which paths work.  Open each
     * candidate read-only + non-blocking (a plain FIFO open would block) and report
     * the exact errno, so the choice is measured rather than guessed.
     */
    private void probeFileAccess() {
        final String[] paths = {
                "/data/misc/bluedroid/okcar_bt.pcm",
                "/data/local/tmp/okcar_bt.pcm",
                "/data/system/okcar_bt.pcm",
                "/data/vendor/okcar/okcar_bt.pcm",
                "/dev/socket/okcar_bt.pcm",
        };
        LinkState.get().log("file-probe", "uid=" + android.os.Process.myUid()
                + " (expect 1000 system)");
        for (String path : paths) {
            java.io.FileDescriptor fd = null;
            try {
                // O_NONBLOCK so a FIFO open cannot block; an SELinux denial shows up
                // as ErrnoException(EACCES).
                fd = android.system.Os.open(path,
                        android.system.OsConstants.O_RDONLY
                                | android.system.OsConstants.O_NONBLOCK, 0);
                LinkState.get().log("file-probe", path + ": OPEN OK");
            } catch (Throwable t) {
                LinkState.get().log("file-probe", path + ": " + t);
            } finally {
                if (fd != null) {
                    try {
                        android.system.Os.close(fd);
                    } catch (Throwable ignored) {
                        /* nothing useful to do */
                    }
                }
            }
        }
    }

    private void startScreenStream(String resolvedHost, int dataPort, long streamConnectionId,
                                   boolean withEncryption) {
        try {
            final ScreenStreamSender sender = new ScreenStreamSender(resolvedHost, dataPort, 1920, 720);
            if (withEncryption) {
                final AirPlayStreamCrypto crypto = new AirPlayStreamCrypto(streamConnectionId);
                sender.setCrypto(crypto);
                LinkState.get().log("screen-stream", "AES-128-CTR from streamConnectionID "
                        + streamConnectionId + " key=" + hex16(crypto.key())
                        + " iv=" + hex16(crypto.iv()));
            }
            sender.connect(5000);
            mScreenSender = sender;
            // APK（CarWith 进程里的 LSPosed 模块）推来的编码帧直接进这条流；标记文件存在才启用
            CastLinkServer.get().attach(new CastLinkServer.Sink() {
                @Override
                public void config(byte[] sps, byte[] pps) throws IOException {
                    sender.sendConfig(sps, pps);
                }

                @Override
                public void frame(byte[] data, boolean keyFrame, long ptsMs) throws IOException {
                    sender.sendFrame(data, keyFrame, ptsMs * 1000L);
                }

                @Override
                public void audio(byte[] data, long ptsMs, int rate, int channels) throws IOException {
                    final AudioStreamSender audio = mAudioSender;
                    if (audio == null) {
                        LinkState.get().log("cast-link", "audio dropped: no audio stream yet ("
                                + data.length + " bytes @" + rate + "Hz/" + channels + "ch)");
                        return;
                    }
                    audio.send(data, 0, data.length);
                }
            });
            final MirrorVideoSource video = new MirrorVideoSource(this);
            mMirrorVideo = video;
            video.setFrameSink(new MirrorVideoSource.FrameSink() {
                @Override
                public void onFrame(byte[] data, int flags, long presentationTimeUs) {
                    try {
                        if (!sender.configSent()) {
                            sender.sendConfig(video.parameterSet0(), video.parameterSet1());
                        }
                        sender.sendFrame(data, (flags & 1) != 0, presentationTimeUs);
                    } catch (IOException e) {
                        LinkState.get().log("screen-stream", "send failed: " + e.getMessage());
                    }
                }
            });
            video.configure(1920, 720, 30, 8_000_000);
            if (video.start(null)) {
                LinkState.get().log("screen-stream", "mirroring -> " + resolvedHost + ':' + dataPort);
            } else {
                LinkState.get().log("screen-stream", "encoder start failed: " + video.stats().error);
            }
        } catch (IOException e) {
            LinkState.get().log("screen-stream", "start failed: " + e.getMessage());
        }
    }

    /**
     * The receiver asked for the UI again ({type=requestUI} after it re-entered
     * CarPlay).  Re-send the avcC configuration and force an IDR immediately, so
     * the decoder the receiver just created gets both SPS/PPS and a keyframe
     * instead of waiting for the next scheduled one (§12.34).
     */
    private void resendUi() {
        final MirrorVideoSource video = mMirrorVideo;
        final ScreenStreamSender sender = mScreenSender;
        if (video == null || sender == null) {
            LinkState.get().log("screen-stream", "requestUI ignored: no video stream");
            return;
        }
        try {
            sender.sendConfig(video.parameterSet0(), video.parameterSet1());
            video.requestSyncFrame();
            LinkState.get().log("screen-stream", "requestUI -> avcC + IDR resent");
        } catch (IOException e) {
            LinkState.get().log("screen-stream", "requestUI resend failed: " + e.getMessage());
        }
    }

    /**
     * The ROM ships with STREAM_MUSIC muted and the closed-source baseline unmutes
     * it when it connects (user-confirmed 2026-09-26).  With the stream muted,
     * REMOTE_SUBMIX hands us silence, so the car would play nothing even though
     * the audio stream itself is fine.
     */
    /* ------------------------------------------------------- capture-only probe */

    /** Logs what REMOTE_SUBMIX can actually hear right now, plus the active output routes. */
    private void captureProbe() {
        final android.media.AudioManager manager = (android.media.AudioManager)
                getSystemService(Context.AUDIO_SERVICE);
        final StringBuilder routes = new StringBuilder();
        if (manager != null) {
            for (android.media.AudioDeviceInfo device : manager.getDevices(
                    android.media.AudioManager.GET_DEVICES_OUTPUTS)) {
                routes.append(device.getType()).append('/').append(device.getProductName())
                        .append(' ');
            }
            routes.append("isMusicActive=").append(manager.isMusicActive());
            routes.append(" musicVol=").append(manager.getStreamVolume(
                    android.media.AudioManager.STREAM_MUSIC));
        }
        final com.okcar.link.media.PlaybackAudioSource source =
                new com.okcar.link.media.PlaybackAudioSource(this);
        final long[] frames = new long[1];
        final long[] nonZero = new long[1];
        final int[] peak = new int[1];
        source.setSink(new com.okcar.link.media.PlaybackAudioSource.AudioSink() {
            @Override
            public void onAudio(byte[] data, int length) {
                frames[0] += length / 4;
                for (int i = 0; i + 1 < length; i += 2) {
                    final short v = (short) ((data[i] & 0xFF) | (data[i + 1] << 8));
                    if (v != 0) {
                        nonZero[0]++;
                    }
                    if (Math.abs(v) > peak[0]) {
                        peak[0] = Math.abs(v);
                    }
                }
            }
        });
        if (!source.start(null)) {
            LinkState.get().log("capture", "probe could not open REMOTE_SUBMIX: "
                    + source.stats().error);
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(6000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                source.stop();
                LinkState.get().log("capture", "probe 6s: " + frames[0] + " frames, "
                        + nonZero[0] + " non-zero samples, peak " + peak[0]
                        + ", musicState=" + source.stats().musicState
                        + " | outputs: " + routes);
            }
        }, "okcar-capture-probe").start();
    }

    /* ------------------------------------------------------------- diagnostic tone */

    private volatile boolean mToneOn;
    private Thread mToneThread;

    /** Plays a 1 kHz stereo tone through STREAM_MUSIC until {@link #stopTone()}. */
    private void startTone() {
        if (mToneOn) {
            LinkState.get().log("tone", "already playing");
            return;
        }
        mToneOn = true;
        mToneThread = new Thread(new Runnable() {
            @Override
            public void run() {
                final int rate = 48000;
                final int min = android.media.AudioTrack.getMinBufferSize(rate,
                        android.media.AudioFormat.CHANNEL_OUT_STEREO,
                        android.media.AudioFormat.ENCODING_PCM_16BIT);
                final android.media.AudioTrack track = new android.media.AudioTrack(
                        android.media.AudioManager.STREAM_MUSIC, rate,
                        android.media.AudioFormat.CHANNEL_OUT_STEREO,
                        android.media.AudioFormat.ENCODING_PCM_16BIT,
                        Math.max(min, rate * 4), android.media.AudioTrack.MODE_STREAM);
                track.play();
                final short[] block = new short[4800 * 2];
                long blockIndex = 0;
                LinkState.get().log("tone", "1 kHz tone started (" + rate + " Hz stereo)");
                while (mToneOn) {
                    for (int i = 0; i < 4800; i++) {
                        final double phase = 2 * Math.PI * 1000.0 * (blockIndex * 4800 + i) / rate;
                        final short v = (short) (9000 * Math.sin(phase));
                        block[2 * i] = v;
                        block[2 * i + 1] = v;
                    }
                    track.write(block, 0, block.length);
                    blockIndex++;
                }
                track.stop();
                track.release();
                LinkState.get().log("tone", "tone stopped");
            }
        }, "okcar-test-tone");
        mToneThread.setDaemon(true);
        mToneThread.start();
    }

    private void stopTone() {
        mToneOn = false;
        mToneThread = null;
        LinkState.get().log("tone", "tone stopped by action");
    }

    private void unmutePlayback() {
        try {
            final android.media.AudioManager manager = (android.media.AudioManager)
                    getSystemService(Context.AUDIO_SERVICE);
            if (manager == null) {
                return;
            }
            final int stream = android.media.AudioManager.STREAM_MUSIC;
            manager.setStreamMute(stream, false);
            if (manager.getStreamVolume(stream) == 0) {
                final int max = manager.getStreamMaxVolume(stream);
                manager.setStreamVolume(stream, Math.max(1, max * 2 / 3), 0);
            }
            LinkState.get().log("audio-stream", "STREAM_MUSIC muted="
                    + manager.isStreamMute(stream) + " vol="
                    + manager.getStreamVolume(stream) + '/' + manager.getStreamMaxVolume(stream));
        } catch (RuntimeException e) {
            LinkState.get().log("audio-stream", "unmute failed: " + e);
        }
    }

    /**
     * Captures the phone's playback audio (AudioRecord REMOTE_SUBMIX, 48 kHz stereo)
     * and streams it to the audio dataPort as plaintext L16 RTP — the format the
     * receiver expects (PT 100, 360 frames/packet, big-endian, SSRC 0).
     */
    private void startAudioStream(String resolvedHost, int dataPort) {
        try {
            final AudioStreamSender sender = new AudioStreamSender(resolvedHost, dataPort);
            sender.open();
            mAudioSender = sender;
            final PlaybackAudioSource source = new PlaybackAudioSource(this);
            mAudioSource = source;
            source.setSink(new PlaybackAudioSource.AudioSink() {
                @Override
                public void onAudio(byte[] data, int length) {
                    sender.send(data, 0, length);
                }
            });
            if (source.start(null)) {
                LinkState.get().log("audio-stream", "playback capture -> " + resolvedHost + ':' + dataPort);
            } else {
                LinkState.get().log("audio-stream", "capture start failed: "
                        + source.stats().error);
                stopAudioStream();
            }
        } catch (IOException e) {
            LinkState.get().log("audio-stream", "audio stream failed: " + e.getMessage());
            stopAudioStream();
        }
    }

    private void stopAudioStream() {
        final PlaybackAudioSource source = mAudioSource;
        mAudioSource = null;
        if (source != null) {
            source.stop();
        }
        final AudioStreamSender sender = mAudioSender;
        mAudioSender = null;
        if (sender != null) {
            sender.close();
        }
    }

    private void stopScreenStream() {
        final MirrorVideoSource video = mMirrorVideo;
        mMirrorVideo = null;
        if (video != null) {
            video.stop();
        }
        final ScreenStreamSender sender = mScreenSender;
        CastLinkServer.get().detach();
        mScreenSender = null;
        if (sender != null) {
            LinkState.get().log("screen-stream", "closing after " + sender.frames() + " frames / "
                    + sender.bytes() + "B");
            sender.close();
        }
    }

    public void stopSession() {
        final AirPlaySession session = mAirPlaySession;
        if (session == null) {
            LinkState.get().log("session", "no session to stop");
            return;
        }
        mAirPlaySession = null;
        session.stopFeedbackLoop();
        stopScreenStream();
        stopAudioStream();
        session.teardown();
        final TimingResponder timingResponder = mTimingResponder;
        mTimingResponder = null;
        if (timingResponder != null) {
            timingResponder.stop();
        }
        final java.net.DatagramSocket timingSocket = mTimingSocket;
        if (timingSocket != null) {
            timingSocket.close();
            mTimingSocket = null;
        }
    }

    public boolean isSessionRunning() {
        return mAirPlaySession != null;
    }

    /* ------------------------------------------------------------- L4 media */

    /**
     * Proves the sender-side media pipeline without a car: mirrors+encodes the
     * screen and captures a self-generated tone back through REMOTE_SUBMIX.
     */
    public void runMediaProbe(final int seconds) {
        LinkState.get().log("media", "media probe starting (" + seconds + "s, video + audio)");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final MediaProbe.Result result =
                        MediaProbe.run(OkCarLinkService.this, seconds, true, true, true);
                for (String line : result.report().split("\n")) {
                    if (!line.trim().isEmpty()) {
                        LinkState.get().log("media", line.trim());
                    }
                }
                LinkState.get().setLastMediaProbe(result.video + " | " + result.audio);
            }
        }, "okcar-media-probe").start();
    }

    /* ---------------------------------------------------------- notification */

    private void createNotificationChannel() {
        final NotificationManager manager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }
        final NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                "OkCar Link", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Link between the phone and the head unit");
        manager.createNotificationChannel(channel);
    }

    private Notification buildNotification() {
        final PendingIntent contentIntent = PendingIntent.getActivity(this, 0,
                new Intent(this, LinkActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("OkCar Link")
                .setContentText("Link service is running")
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .build();
    }

    /** Lowercase hex of the first 16 bytes, for stream-key logging. */
    private static String hex16(byte[] bytes) {
        final StringBuilder out = new StringBuilder(32);
        for (int i = 0; i < 16 && i < bytes.length; i++) {
            out.append(String.format("%02x", bytes[i] & 0xFF));
        }
        return out.toString();
    }
}
