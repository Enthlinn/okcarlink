/*
 * OkcarOS link stack — the wireless CarPlay session owner.
 *
 * Responsibilities (wireless = our phone is the *device*, the head unit is the
 * accessory, §12.38):
 *
 *   1. publish the RFCOMM service record for
 *      00000000-deca-fade-deca-deafdecacafe (the accessory is the client and
 *      dials us — that dial *is* the "come and connect" notification),
 *   2. own the accept loop and hand each accepted socket to
 *      {@link RfcommIap2Transport} + {@link Iap2Link} + {@link WirelessCarPlayDriver},
 *   3. when the accessory hands us its network (0x5703 / 0x4301), join it.
 *
 * The link switches for the wireless role are set here and restored on stop, so
 * the field-verified wired configuration of the same objects is untouched:
 *   deviceSendsSyn  = false  (the *accessory* sends SYN first, we answer SYN-ACK)
 *   ackNextExpected = false  (ack = the seq just received; measured in the capture)
 *   synAckEchoParams= true   (echo the accessory's own SYN entries; set at :257-265,
 *                             restored at :719-722.  The old comment here claimed
 *                             false — it was stale, see §3.14.)
 */

package com.okcar.link.wireless;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.RouteInfo;
import android.os.SystemClock;
import android.util.Log;

import com.okcar.link.LinkState;
import com.okcar.link.airplay.WirelessAirPlayClient;
import com.okcar.link.iap2.Iap2Link;
import com.okcar.link.iap2.Iap2Syn;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.UUID;

public final class WirelessCarPlaySession implements WirelessCarPlayDriver.Callback {

    /* ------------------------------------------------ cooldown after a freeze (cross-session, in-process static) */

    /**
     * After the system has been starved once, suspend new casting sessions for a while.
     *
     * <p>Why here: {@code LinkDaemon}'s stall watch sheds the display and stops the pipeline, but if the accessory dials right back,
     * the same load immediately drags the phone under again — the two field "hard resets" were exactly this repeating until the hardware watchdog bit.
     * During the cooldown window we **keep listening** (the head unit won't think the phone is gone), only refusing this round of casting and stating the reason clearly.
     */
    private static final long SUSPEND_MIN_MS = com.okcar.link.port.CastCooldown.MIN_MS;
    private static final long SUSPEND_MAX_MS = com.okcar.link.port.CastCooldown.MAX_MS;
    /**
     * The window itself lives in {@link com.okcar.link.port.CastCooldown} (pure Java) so the host
     * regression can drive a whole window without sleeping; these are only the clock-bound views.
     */
    private static final com.okcar.link.port.CastCooldown sCooldown =
            new com.okcar.link.port.CastCooldown();

    /** Suspend casting for {@code ms} milliseconds (clamped between 30s and 30min). */
    public static void suspendCastsFor(long ms, String reason) {
        final long clamped = sCooldown.suspendFor(ms, reason, android.os.SystemClock.elapsedRealtime());
        LinkState.get().log("wireless", "casting suspended for " + (clamped / 1000L)
                + "s — " + sCooldown.reason() + " (the listener stays up; nothing new will be cast"
                + " until the window passes)");
        /* 到点要有人清（见 armSuspensionWatch）：以前没人清，窗口过了也没有任何一句日志说明，
         * 现场只能看到"停了就回不来"。 */
        armSuspensionWatch();
    }

    public static boolean castsSuspended() {
        return sCooldown.suspended(android.os.SystemClock.elapsedRealtime());
    }

    public static long suspendRemainingMs() {
        return sCooldown.remainingMs(android.os.SystemClock.elapsedRealtime());
    }

    /** Clear the cooldown (maintenance: for `okcar-port-ctl`/debugging to force a retry during the suspension). */
    public static void clearCastSuspension() {
        sCooldown.clear(android.os.SystemClock.elapsedRealtime());
    }

    /**
     * 冷却窗口到点后把 suspend 自己清掉，让会话重新可武装。
     *
     * <p>为什么需要它（2026-10-11 现场）：{@code LinkDaemon} 的 stall 守卫停掉会话并
     * {@link #suspendCastsFor} 之后，**没有任何东西负责在窗口结束时清掉 suspend** ——
     * {@code castsSuspended()} 只是随 elapsedRealtime 自然变假，但状态字段留着、日志里那条
     * "casting suspended for 300s" 也一直是最后一句。后果是面板/现场看起来"停了就再也回不来"：
     * 无线侧要等配件**重新拨入**才有下一次机会，有线侧（CarkitServiceHost 与它的 AirPlay）
     * 则是另一条独立管线，冷却对它本来就没作用，一起被误读成"整个模块挂了"。
     *
     * <p>这里做的是**只清冷却、不抢着起会话**：清掉之后监听照旧常驻，下一次拨入或本地恢复
     * （{@code scheduleSessionRecovery}）就能正常进行。刻意不去主动重连 —— 整机刚被饿过一次，
     * 由我们自己立刻把负载拉回来正是当初引入冷却要避免的事。
     */
    private static final long SUSPEND_WATCH_TICK_MS = 1000L;

    private static void armSuspensionWatch() {
        if (sSuspendWatch != null) {
            return;
        }
        final Thread watch = new Thread(new Runnable() {
            @Override
            public void run() {
                while (true) {
                    try {
                        Thread.sleep(SUSPEND_WATCH_TICK_MS);
                    } catch (InterruptedException e) {
                        return;
                    }
                    /* expireIfDue 只在真的到点时返回原因（并且只返回一次），所以这里不用先判
                     * "有没有窗口"：空窗永远返回 null，日志也不会每拍刷一行。 */
                    final String ended = sCooldown.expireIfDue(
                            android.os.SystemClock.elapsedRealtime());
                    if (ended == null) {
                        continue;
                    }
                    LinkState.get().log("wireless", "the stall cooldown is over (" + ended
                            + ") — casting is allowed again; the listener was up the whole time");
                }
            }
        }, "okcar-suspend-watch");
        watch.setDaemon(true);
        sSuspendWatch = watch;
        watch.start();
    }

    private static volatile Thread sSuspendWatch;

    private static final String TAG = "OkCarLink/Wireless";

    /** The RFCOMM service record the accessory dials (SCN 1 in the captured session). */
    private static final UUID RFCOMM_UUID = UUID.fromString(WirelessMessages.RFCOMM_UUID);
    private static final String SERVICE_NAME = "OkCarLink Wireless CarPlay";
    /** Service name of the decorative Apple CarPlay record; a real iPhone's is not known. */
    private static final String CARPLAY_SERVICE_NAME = "CarPlay";
    /** Upper bound on sockets parked on the CarPlay record so a chatty peer cannot pile up. */
    private static final int MAX_PARKED_CARPLAY_SOCKETS = 4;
    /**
     * Publishes the decorative Apple CarPlay SDP record alongside the wireless-iAP one.
     *
     * <p>Was off for the 2026-09-28 experiment ("do two app-owned 128-bit records interfere?" —
     * two head units had cached the 16-bit base UUID twice).  Back on because the integrator/the production head unit
     * head unit selects the phone from the SDP records it reads: with only the wireless-iAP
     * record published it reports no CarPlay support at all (2026-09-30, paired but "no carplay
     * support"), and a car that never sees an Apple CarPlay record never dials.  The record is
     * discovery-only — anything that connects to it is parked, never fed a half service.
     */
    private static final boolean ADVERTISE_APPLE_CARPLAY_RECORD = true;

    /**
     * How long a boot-time start waits for the Bluetooth adapter to appear.
     *
     * <p>Not a radio problem but an ordering one: {@code service.sh} runs this daemon in the
     * late_start service stage, which can beat the Bluetooth stack.  Measured on 217 after a
     * reboot — the boot start answered {@code no Bluetooth adapter} while
     * {@code settings get global bluetooth_on} was already 1, and a manual restart two minutes
     * later listened normally.  Waiting keeps the car dialable without anyone touching the WebUI.
     */
    private static final long ADAPTER_WAIT_MS = 60_000L;
    private static final long ADAPTER_POLL_MS = 2_000L;

    private static final WirelessCarPlaySession INSTANCE = new WirelessCarPlaySession();

    public static WirelessCarPlaySession get() {
        return INSTANCE;
    }

    private final Object mLock = new Object();

    /*
     * Lab knobs that stay on this device rather than describing a head unit.  Anything that
     * *is* about a unit (its screen, its codec) lives in that unit's stored config instead, so it
     * cannot travel to the next one — see pinVideoSize and pinVideoCodec.
     */
    /** Lab-only plaintext wire-dump path for the video face; empty disables it. */
    private volatile String mVideoDumpPath = "";
    /** Lab-only: force the bare codec-config record instead of a sample entry. */
    private volatile boolean mRawVideoConfig;
    /** Lab-only encoder name for the video face; empty keeps the platform default. */
    private volatile String mVideoEncoderName = "";

    /**
     * Pins the video codec for the head unit that is connected <em>now</em>, and only for it.
     *
     * <p>Same rule as {@link #pinVideoSize}: what a unit can decode is a fact about that unit, so
     * the pin goes into its stored config.  The session-wide field this replaced meant a
     * {@code codec hevc} typed while testing one accessory was still in force for the next one.
     * {@code AUTO} forgets the pin.
     */
    public String pinVideoCodec(AccessoryMemory.Codec codec) {
        final String peer = mPeerAddress;
        final boolean clearing = codec == null || codec == AccessoryMemory.Codec.AUTO;
        if (peer == null || peer.isEmpty() || "?".equals(peer)) {
            return clearing
                    ? "no accessory known yet — connect it once, then its codec can be cleared"
                    : "no accessory known yet — connect it once, then pin its codec";
        }
        AccessoryMemory.pinCodec(mAppContext, peer, codec);
        final WirelessAirPlayClient client = mAirPlay;
        if (client != null) {
            client.reloadCarConfig();
        }
        return clearing
                ? "codec pin cleared for " + peer + " — it follows its own advertisement"
                : "pinned codec " + codec.name() + " for " + peer;
    }

    /**
     * Pins the video size for the head unit that is connected <em>now</em>, and only for it.
     *
     * <p>Per unit, not per session: a size is a fact about one car's screen, so it is written into
     * that car's stored config (under the orientation the size itself names) and read back by every
     * later session of that car.  The version this replaced kept the value in a session-wide field,
     * so a size an operator typed while testing one accessory was still in force for the next one —
     * a landscape value silently pinning a different car, which is precisely the "last session
     * leaking into the next" failure this class now refuses to carry.
     *
     * <p>Without a known accessory there is nothing to attribute the size to, so the call is
     * refused with a message instead of being parked somewhere global.
     */
    public String pinVideoSize(int width, int height) {
        final String peer = mPeerAddress;
        if (peer == null || peer.isEmpty() || "?".equals(peer)) {
            return "no accessory known yet — connect it once, then pin its size";
        }
        AccessoryMemory.pinScreen(mAppContext, peer, width, height);
        final WirelessAirPlayClient client = mAirPlay;
        if (client != null) {
            client.reloadCarConfig();
        }
        return "pinned " + width + 'x' + height + " for " + peer + " (orientation "
                + AccessoryMemory.Orientation.of(width, height).key() + ')';
    }

    /** Forgets this unit's pinned/remembered screen, both orientations ({@code size auto}). */
    public String clearPinnedVideoSize() {
        final String peer = mPeerAddress;
        if (peer == null || peer.isEmpty() || "?".equals(peer)) {
            return "no accessory known yet — nothing to clear";
        }
        AccessoryMemory.clearScreens(mAppContext, peer);
        final WirelessAirPlayClient client = mAirPlay;
        if (client != null) {
            client.reloadCarConfig();
        }
        return "cleared the stored screen for " + peer + " — the next session follows the unit";
    }

    /** See {@link WirelessAirPlayClient#setVideoPlainDump}. */
    public void setVideoPlainDump(String path) {
        mVideoDumpPath = path == null ? "" : path;
    }

    /**
     * See {@link WirelessAirPlayClient#setRawVideoConfig}.  H.264 already sends the bare record
     * (the shape the wired capture proved); this exists because the HEVC branch sends a full
     * `hvc1` sample entry, and Fruit Link mis-reads that shape.
     */
    public void setRawVideoConfig(boolean raw) {
        mRawVideoConfig = raw;
    }

    /** See {@link WirelessAirPlayClient#setVideoEncoderName}. */
    public void setVideoEncoderName(String name) {
        mVideoEncoderName = name == null ? "" : name;
    }

    /** Applies the lab overrides to a freshly created client. */
    /**
     * The bootstrap DETECT as the accessory sends it: {@code FF 55 02 00 EE 10}.
     *
     * <p>It arrives on RFCOMM at the start of every session and, on the production head unit, again on the
     * AirPlay event channel 13 s in.  The answer is the same either way: identify ourselves
     * ({@link WirelessCarPlayDriver#onLinkUp()}), which is what keeps the session alive.
     */
    private static boolean isBootstrapDetect(byte[] payload) {
        return payload != null && payload.length >= 6
                && (payload[0] & 0xFF) == 0xFF && (payload[1] & 0xFF) == 0x55
                && (payload[4] & 0xFF) == 0xEE && (payload[5] & 0xFF) == 0x10;
    }

    /**
     * An iAP2 message the accessory pushed over the AirPlay event channel instead of RFCOMM.
     *
     * <p>Measured on the production head unit (2026-10-02): a live session was dismantled right after such a
     * message went unanswered.  Anything that is not a DETECT is logged only — no accessory has
     * been measured using this transport for anything else yet.
     */
    private void onEventChannelIapMessage(byte[] payload) {
        if (!isBootstrapDetect(payload)) {
            LinkState.get().log("wireless", "event-channel iAP message ("
                    + (payload == null ? 0 : payload.length) + "B) — no handler, logged only");
            return;
        }
        final WirelessCarPlayDriver driver = mDriver;
        LinkState.get().log("wireless", "the accessory re-sent DETECT on the event channel —"
                + (driver == null ? " no iAP2 link to answer on" : " re-identifying"));
        if (driver != null) {
            driver.onLinkUp();
        }
    }

    private void applyMediaOverrides(WirelessAirPlayClient client) {
        client.setAccessoryAddress(mPeerAddress);
        client.setAccessoryIapSink(new WirelessAirPlayClient.AccessoryIapSink() {
            @Override
            public void onAccessoryIapMessage(byte[] payload) {
                onEventChannelIapMessage(payload);
            }
        });
        client.setVideoPlainDump(mVideoDumpPath);
        client.setRawVideoConfig(mRawVideoConfig);
        client.setVideoEncoderName(mVideoEncoderName);
    }

    private BluetoothServerSocket mServer;
    /**
     * Second RFCOMM listener, registered only for its SDP side effect (see
     * {@link WirelessMessages#APPLE_CARPLAY_UUID}).  Nothing is served behind it; sockets
     * accepted here are parked so the record does not look like a dead end.
     */
    private BluetoothServerSocket mCarplayServer;
    private final java.util.List<BluetoothSocket> mCarplayParked =
            new java.util.ArrayList<BluetoothSocket>();
    private BluetoothSocket mSocket;

    /* ── a13 special package: proxy channel for Bluetooth listening ─────────────────────────────────────────────
     * On Android 13, a root app_process with no app attribution calling Bluetooth APIs makes the framework throw a
     * NullPointerException on the AppOps reporting path (measured on lmi), so in the special package listening is done
     * by the companion app (com.okcar.btproxy); this process only connects to the abstract namespace socket it exposes
     * and treats that byte stream as the iAP2 transport. The mainline (without --bt-proxy) never goes through here. */
    private volatile String mBtProxyName;
    private volatile com.okcar.link.iap2.Iap2Link.Transport mProxyTransport;
    private volatile String mProxyPeer = "";
    private volatile Thread mProxyThread;
    /**
     * The phone→accessory iAP2 RFCOMM channel ({@link WirelessMessages#IAP2_SERVICE_UUID}).
     * Speculative and never fatal: the accessory dialling us on the other UUID is what
     * actually carries the session, so a failure here is logged and dropped.
     */
    private volatile BluetoothSocket mCarIap2Socket;
    /**
     * Credentials the accessory announced on that socket (see
     * {@link AccessoryWifiAnnouncement}).  The SSID and the passphrase arrive as two
     * separate reports, so the join waits until both halves are in.
     */
    private volatile String mAnnouncedSsid;
    private volatile String mAnnouncedPassphrase;
    private Thread mCarIap2Reader;
    /** Attempts / spacing for {@link #dialAccessoryIap2Service}: the accessory registers
     * its listener only once its CarPlay app is up, which trails the moment it dials us. */
    private static final int DIAL_ATTEMPTS = 8;
    private static final long DIAL_RETRY_DELAY_MS = 700L;
    /** RFCOMM until the accessory drops it, then the sealed AirPlay event channel. */
    private com.okcar.link.iap2.Iap2Link.Transport mTransport;
    private Iap2Link mLink;
    private WirelessCarPlayDriver mDriver;
    private Thread mAcceptThread;
    /** The phone-side {@code _carplay-ctrl._tcp} endpoint the accessory dials (see class). */
    private WirelessControlChannel mControlChannel;

    private volatile boolean mRunning;
    /**
     * True while the always-on core — the RFCOMM listeners and their accept supervisor — must stay
     * available.  It outlives every session: only {@link #shutdown()} clears it.
     */
    private volatile boolean mKeepListening = true;
    /** The adapter the core re-arms itself on, and the arguments a restart needs. */
    private volatile BluetoothAdapter mListenerAdapter;
    private volatile Thread mDiscoverableThread;
    private volatile boolean mDiscoverableWarned;
    /** How often discoverability is re-armed while the core listens; the platform's own timeout is
     * far longer, this only has to win the race against it. */
    private static final long DISCOVERABLE_REARM_MS = 30_000L;
    /** The in-flight Wi-Fi revert, so a new dial can cancel it (see {@link #REVERT_GRACE_MS}). */
    private volatile Thread mRevertThread;
    /**
     * How long a finished session waits before reverting Wi-Fi.  A reconnect normally arrives
     * within seconds; reverting while the caller is already joining the accessory's network puts
     * the two in a race and the phone can end up on neither network (measured 2026-10-02: the
     * revert kept polling for "ONLY64" while the session sat on "car", and the AirPlay client
     * never came up).
     */
    private static final long REVERT_GRACE_MS = 5000L;
    /**
     * Grace before a failed accessory-announced handshake gives the accessory network back, so a
     * takeover session that is already starting keeps it (see
     * {@link #releaseAccessoryNetworkAfterFailure}).
     */
    private static final long FAILED_HANDSHAKE_RELEASE_MS = 3000L;
    private Context mLastContext;
    private String mLastDeviceId = "";
    /** The accessory's Bluetooth address: the key {@link AccessoryMemory} stores facts under. */
    private volatile String mPeerAddress = "";
    private volatile String mStatus = "idle";

    private String mDeviceId = "";
    private String mBluetoothId = "";
    private Context mAppContext;

    /** Association needs a moment before the accessory's AirPlay server answers. */
    private static final long AIRPLAY_SETTLE_MS = 6000;

    /*
     * Direct (iAP2-free) bootstrap for the wireless-iAP family (Fruit Link).
     *
     * A real iPhone completed CarPlay with this accessory using only DETECT round-trips on
     * Bluetooth — zero iAP2 session messages, hence no 0x4301.  The accessory is the AP: join
     * its hotspot and open AirPlay on it.  The credentials below are only the LAST-RESORT
     * defaults (the app's own defaults car/12345678); whenever the accessory tells us its
     * real AP (0x5703 or 0x4301) the values are refreshed from that, so the previous unit's
     * hardcoded leftovers can never hijack a session again.
     */
    /**
     * Credentials for the direct bootstrap, learned from the accessory that announced them.
     *
     * <p>Deliberately no defaults.  These used to start as the lab hotspot's {@code car} /
     * {@code 12345678}, and a unit that never announces credentials could then still be "joined" on
     * leftovers: either the previous unit's values (the fields were never cleared between sessions)
     * or that hardcoded pair.  An empty pair now stops the direct path and says so, instead of
     * guessing which network the accessory meant.
     */
    private volatile String mDirectSsid;
    private volatile String mDirectPassphrase;
    private static final int DIRECT_AIRPLAY_PORT = 7000;
    /** Only used when neither the default route nor mDNS yields a receiver address. */
    /** How long the accessory's 0x4301/0x5703 keeps priority before we join on our own. */
    private static final long DIRECT_GRACE_MS = 8000;
    /** How long a dead iAP2 link may stay dead before the session reverts the Wi-Fi. */
    private static final long LINK_LOSS_GRACE_MS = 20000;

    /** How often the session re-checks that the AirPlay streams it reported are still alive. */
    private static final long AIRPLAY_LIVENESS_POLL_MS = 10_000;

    /** Grace before this side re-offers the session itself (the accessory may just be recycling). */
    private static final long SESSION_RECOVERY_DELAY_MS = 3000;
    /**
     * Bounded failure window: if the AirPlay handshake has not come up within this
     * time the direct bootstrap still reverts itself, so a failing attempt can never
     * strand the phone on the accessory's hotspot.  A session that <em>did</em> come
     * up is disarmed instead of stopped — see {@link #armDirectWatchdog()}.
     */
    private static final long DIRECT_WATCHDOG_MS = 180000;
    /**
     * How often the watchdog re-reads {@link #mAirPlayReady} while the failure window
     * is open.  Short enough that a handshake completing just after arming disarms the
     * watchdog immediately instead of at the 180 s mark.
     */
    private static final long DIRECT_WATCHDOG_POLL_MS = 1000;
    /** How long the direct path waits for mDNS to name the AirPlay receiver. */
    /** On a retry the receiver address is already known, so a short settle is enough. */
    private static final long DIRECT_RETRY_SETTLE_MS = 2000;

    private volatile WirelessAirPlayClient mAirPlay;
    private WirelessMessages.WirelessInfo mWirelessInfo;
    private WirelessMessages.StartSession mStartSession;
    private String mLastError = "";

    /** True once the Wi-Fi join for this session has been claimed (direct or 0x4301). */
    private boolean mJoinStarted;
    /** True while the direct-bootstrap join (last-resort credentials) is in flight. */
    private boolean mDirectJoinInFlight;
    /**
     * SSID of the Wi-Fi join that is currently running.  Recorded so an authoritative 0x4301 for a
     * *different* network can tell "the accessory moved on" from "the same join is still busy".
     */
    private volatile String mJoinSsid;
    /** SSID of the last join that actually verified, or null while none has. */
    private String mJoinedSsid;
    /**
     * The accessory's own start session when it arrived while the fallback bootstrap was
     * busy.  Field failure (2026-09-27, the emulator): the grace window expired on a stale SSID,
     * the fallback held the join claim for the whole poll, and the accessory's real 0x4301
     * was refused with "already joined" — the receiver then spun on "connecting" forever.
     * The authoritative credentials are queued here and take over as soon as the fallback
     * settles.
     */
    /**
     * The AirPlay target the accessory named in its own start session ({@code 0x4301}).  The
     * announced-credentials path ({@code 0x5703}, and the announced network the production head unit pushes
     * down the iAP2 socket) names the network but <em>not</em> the receiver, so this is the only
     * source of the real AirPlay address.  Field failure (2026-09-29, the emulator): the 0x4301 that
     * followed 0x5703 by 20 ms was refused as "already joined" and its address dropped, so the
     * attempt dialled the gateway guess and the accessory refused it — {@code ECONNREFUSED} on
     * {@code 192.0.2.1:7000} while the receiver listened on
     * {@code fe80::3841:feff:fecc:bab5:7000}.
     */
    private volatile String mAuthoritativeAddress = "";
    private volatile String mAuthoritativeSsid = "";
    /** How long an announced-credentials session waits for the accessory's own address. */
    private static final long AUTHORITATIVE_ADDRESS_WAIT_MS = 3000;
    private WirelessMessages.StartSession mPendingAuthoritative;
    private volatile Thread mGraceThread;
    private volatile Thread mWatchdogThread;

    /**
     * The receiver the direct bootstrap resolved once (mDNS → default gateway →
     * constant).  Retries reuse it verbatim: they must never re-resolve mDNS or
     * re-read the default gateway.
     */
    private volatile String mDirectReceiverAddress;
    private volatile int mDirectReceiverPort = DIRECT_AIRPLAY_PORT;
    /**
     * True from the moment a direct-bootstrap AirPlay client is created until its
     * {@code run()} returns.  A wireless-iAP link-up arriving meanwhile is logged and
     * skipped so two handshakes can never overlap.
     */
    private volatile boolean mAirPlayAttemptInProgress;
    /** How many retry handshakes the direct bootstrap has started (link-up driven). */
    private volatile int mAirPlayRetries;

    /**
     * Set once an AirPlay handshake has actually succeeded (channels ready).  A later
     * Bluetooth link-up must never tear a live session down, so the direct-bootstrap
     * retry refuses to run while this is set.
     */
    private volatile boolean mAirPlayReady;

    /* Saved wired-path switches, restored on stop. */
    private boolean mSavedDeviceSendsSyn;
    private boolean mSavedAckNextExpected;
    private boolean mSavedSynAckEchoParams;

    private WirelessCarPlaySession() {
    }

    /* ------------------------------------------------------------------ start */

    /** a13 special package only: let Bluetooth listening be handled by the companion app; this process connects to its abstract namespace socket. */
    public void setBtProxy(String name) {
        if (name != null && !name.isEmpty()) {
            mBtProxyName = name;
        }
    }

    public boolean start(Context context, String deviceId) {
        if (castsSuspended()) {
            return fail("casting suspended for " + (suspendRemainingMs() / 1000L)
                    + "s after a system stall (" + sCooldown.reason() + ")");
        }
        synchronized (mLock) {
            if (mRunning) {
                /* Keep the informative "listening as ..." status instead of
                 * replacing it: the status action reads this text back. */
                return true;
            }
            if (context == null) {
                return fail("no context");
            }
            mAppContext = context.getApplicationContext();
            final BluetoothManager manager =
                    (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
            BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
            if (adapter == null) {
                /* See ADAPTER_WAIT_MS: the boot-time start races the Bluetooth stack. */
                final long deadline = System.currentTimeMillis() + ADAPTER_WAIT_MS;
                while (adapter == null && System.currentTimeMillis() < deadline) {
                    try {
                        Thread.sleep(ADAPTER_POLL_MS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    final BluetoothManager retry =
                            (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
                    adapter = retry == null ? null : retry.getAdapter();
                }
                if (adapter == null) {
                    return fail("no Bluetooth adapter (waited " + (ADAPTER_WAIT_MS / 1000) + "s)");
                }
                LinkState.get().log("wireless", "Bluetooth adapter appeared after the boot race "
                        + "(waited up to " + (ADAPTER_WAIT_MS / 1000) + "s)");
            }
            if (!adapter.isEnabled()) {
                /* A previous session turns Bluetooth off after the hand-off (the accessory asks
                 * for it and then only wants the Wi-Fi tunnel).  If that session ended without
                 * running its restore path — the app was force-stopped, it crashed, adb killed it
                 * — the radio is still off and the accessory can never dial us again.  Heal it
                 * here instead of failing: wireless CarPlay is unreachable without Bluetooth. */
                LinkState.get().log("wireless", "Bluetooth is off — re-enabling it for this session");
                adapter.enable();
                for (int i = 0; i < 20 && !adapter.isEnabled(); i++) {
                    try {
                        Thread.sleep(250L);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                if (!adapter.isEnabled()) {
                    return fail("Bluetooth could not be enabled");
                }
            }
            /* The accessory selects us by the OUI of this address (Fruit Link reads the MAC vendor
             * table), so a lost Apple OUI makes us silently unselectable — measured 2026-09-30:
             * a few Bluetooth restarts left the adapter at 24:D3:37:FB:68:1B while the pinned
             * device key still said F0:18:98:FB:68:1B.  Repair it here, before the listener is
             * published, so any radio cycle the repair needs cannot break a live listener. */
            if (com.okcar.link.port.PortBootstrap.hasRoot()) {
                /* This is a "best-effort" identity repair (OUI/DID), not a precondition for the session: under some ROM/SELinux
                 * configurations, reading the Bluetooth address or changing properties throws SecurityException, and an
                 * uncaught one takes the whole session and daemon process down with it (measured on Magisk/lmi: WirelessCarPlaySession.start
                 * propagated to main, showing as "the module is installed but the wireless session never listens"). A failure must be reported loudly but listening must still be established. */
                try {
                    final String identity =
                            com.okcar.link.port.PortBootstrap.assertAccessoryBtIdentity();
                    if (!identity.isEmpty()) {
                        LinkState.get().log("wireless", "adapter identity asserted: " + identity);
                    }
                } catch (Throwable t) {
                    LinkState.get().log("wireless", "BT identity assertion failed (continuing"
                            + " without it): " + t);
                }
            }
            try {
                mBluetoothId = adapter.getAddress();
            } catch (Throwable e) {
                /* Must be Throwable, not just catch SecurityException: on Android 13, when an app_process with no app
                 * attribution (Magisk/root running the dex directly) calls getAddress(), the framework itself throws
                 * NullPointerException on the "report permission denial" path (Parcel/AppOps), so the whole wireless
                 * session crashes together with the daemon — measured on lmi: the supervisor restarts endlessly and never publishes the listener. */
                mBluetoothId = "";
                LinkState.get().log("wireless", "cannot read the local BT address: " + e
                        + " (BLUETOOTH_CONNECT not granted?) — continuing without it");
            }
            /* Normal-phone port: Android 8+ masks getAddress() as 02:00:00:00:00:00 for
             * non-system apps. The identity we publish must be the controller's real
             * address (plan §1), so fall back to Settings and then the root shell. */
            if (mBluetoothId == null || mBluetoothId.isEmpty()
                    || "02:00:00:00:00:00".equals(mBluetoothId)) {
                final String real = readRealBluetoothAddress(context);
                if (!real.isEmpty()) {
                    LinkState.get().log("wireless", "masked BT address resolved to " + real);
                    mBluetoothId = real;
                }
            }
            mDeviceId = (deviceId == null || deviceId.isEmpty())
                    ? mBluetoothId.replace(":", "") : deviceId;

            /* Fresh session: the previous one may have left the direct-bootstrap claim set. */
            mJoinStarted = false;
            mGraceThread = null;
            mWatchdogThread = null;
            mDirectReceiverAddress = null;
            mDirectReceiverPort = DIRECT_AIRPLAY_PORT;
            mAirPlayAttemptInProgress = false;
            mAirPlayRetries = 0;
            mAirPlayReady = false;

            /* Wireless role switches + the SYN block a real iPhone answers with. */
            mSavedDeviceSendsSyn = Iap2Link.deviceSendsSyn();
            mSavedAckNextExpected = Iap2Link.ackNextExpected();
            mSavedSynAckEchoParams = Iap2Link.synAckEchoParams();
            applyWirelessSwitches();

            mLastContext = context;
            mLastDeviceId = mDeviceId;
            mKeepListening = true;
            try {
                ensureListening(adapter);
            } catch (Throwable t) {
                /* On some ROMs (Android 13 + app_process with no app attribution), when the Bluetooth permission check
                 * fails the framework itself throws an NPE on the AppOps reporting path — don't let the whole daemon crash
                 * into a restart loop; fail explicitly and write it to status so the field can see at a glance that "the listener can't be created" rather than "the module is flaky" (measured on Magisk/lmi). */
                restoreSwitches();
                return fail("the RFCOMM listener could not be created: " + t);
            }
            if (mServer == null && mBtProxyName == null) {
                restoreSwitches();
                return fail("the RFCOMM listener could not be created");
            }

            mRunning = true;
            mStatus = "listening as " + mBluetoothId + " (device id " + mDeviceId + ")";
            /* The accessory also browses for the phone's reverse control plane
             * (_carplay-ctrl._tcp) right after the BT bootstrap; publish and serve it so
             * it does not end up at the closed-source stack (or at a stale record) and
             * give up with "CarPlayControl command connect failed -6722". */
            try {
                final WirelessControlChannel control =
                        new WirelessControlChannel(context, mDeviceId, "iPhone");
                if (control.start()) {
                    mControlChannel = control;
                }
            } catch (Throwable t) {
                LinkState.get().log("wireless", "carplay-ctrl: cannot start: " + t);
            }
            LinkState.get().log("wireless", mStatus);
            Log.i(TAG, mStatus);
            return true;
        }
    }

    public void stop() {
        final Context context;
        synchronized (mLock) {
            /* Cancel the direct-bootstrap grace timer and its watchdog first: a stop() from
             * anywhere (the user, the watchdog itself, service shutdown) must win. */
            final Thread grace = mGraceThread;
            mGraceThread = null;
            if (grace != null && grace != Thread.currentThread()) {
                grace.interrupt();
            }
            final Thread watchdog = mWatchdogThread;
            mWatchdogThread = null;
            if (watchdog != null && watchdog != Thread.currentThread()) {
                watchdog.interrupt();
            }
            mJoinStarted = false;
            mDirectJoinInFlight = false;
            mJoinSsid = null;
            mJoinedSsid = null;
            mPendingAuthoritative = null;
            mAuthoritativeAddress = "";
            mAuthoritativeSsid = "";
            mRunning = false;
            mAirPlayAttemptInProgress = false;
            if (mLink != null) {
                mLink.stop();
                mLink = null;
            }
            if (mTransport != null) {
                mTransport.close();
                mTransport = null;
            }
            if (mSocket != null) {
                try {
                    mSocket.close();
                } catch (IOException ignored) {
                    /* already closed */
                }
                mSocket = null;
            }
            /* The RFCOMM listeners belong to the always-on core, not to the session.  Leaving
             * them up is what lets the next dial arrive without restarting the daemon; only
             * shutdown() takes them down. */
            for (BluetoothSocket parked : mCarplayParked) {
                try {
                    parked.close();
                } catch (IOException ignored) {
                    /* already closed */
                }
            }
            mCarplayParked.clear();
            if (mCarIap2Socket != null) {
                try {
                    mCarIap2Socket.close();
                } catch (IOException ignored) {
                    /* already closed */
                }
                mCarIap2Socket = null;
            }
            mCarIap2Reader = null;
            mAnnouncedSsid = null;
            mAnnouncedPassphrase = null;
            if (mControlChannel != null) {
                mControlChannel.stop();
                mControlChannel = null;
            }
            if (mAppContext != null) {
                stopAirPlayClient();
            }
            restoreSwitches();
            /* Nothing about the unit that just left may survive into the next session.  The peer
             * address is what selects which car's stored screen the next client reads, and the
             * announced credentials are what the direct bootstrap may join — leaving either behind
             * is exactly how one car's facts got applied to another (measured 2026-10-02: a
             * landscape screen and a leftover hotspot both travelled that way). */
            mPeerAddress = "";
            mStartSession = null;
            mWirelessInfo = null;
            mDirectSsid = null;
            mDirectPassphrase = null;
            mLastError = "";
            context = mAppContext;
            /* Arm the core for the next dial before the (slow) Wi-Fi revert, so a reconnect does
             * not have to wait for it and the phone is never undiallable. */
            ensureListening(null);
            /* ... and then let the status say what ensureListening actually achieved.  Setting
             * mStatus to "stopped" unconditionally was the bug behind "Listener not published"
             * appearing again and again while the listener was in fact still up: the WebUI decides
             * health from this one line, ensureListening re-arms the RFCOMM listener right here,
             * and nothing ever wrote "listening as ..." back — so the panel kept reporting a dead
             * listener (measured 2026-10-11 on 217, after the stall guard had stopped a session:
             * the accept and discoverable threads were both alive and Bluetooth was ON while the
             * status file said "stopped").  refreshIdleStatus() was written for exactly this
             * distinction and only reports "stopped" when the listener is really gone. */
            refreshIdleStatus("the session ended");
        }
        /* The revert is explicit and self-verifying now (re-associate the recorded
         * original network, poll, escalate), so it can take tens of seconds.  Run it
         * outside mLock: a slow Wi-Fi recovery must not wedge the session API.  It
         * stays synchronous for the caller, so the watchdog only prints "revert
         * complete" once the network verification has finished. */
        if (context != null) {
            /* Cleanup, not part of the handshake.  The revert can take tens of seconds
             * (re-associate the recorded network, poll, escalate), and a re-dial must not wait for
             * it: doing it inline is what made the second dial miss its SYN-ACK window and fail
             * (measured 2026-10-02, dial #1 PASS / dial #2 FAIL with the listener itself healthy). */
            final Context revertContext = context;
            final Thread revert = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Thread.sleep(REVERT_GRACE_MS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        LinkState.get().log("wireless", "revert cancelled: a session is starting");
                        return;
                    }
                    if (mRunning) {
                        LinkState.get().log("wireless", "revert skipped: a session is up again");
                        return;
                    }
                    WifiGroupJoiner.releaseAll(revertContext);
                    LinkState.get().log("wireless", "revert: withdrew the accessory Wi-Fi network"
                            + " (no earlier network is restored: the phone picks its own again)"
                            + " and released the saved iAP2 switches");
                }
            }, "okcar-wifi-revert");
            revert.setDaemon(true);
            mRevertThread = revert;
            revert.start();
        }
        LinkState.get().log("wireless", "stopped");
    }

    public String status() {
        final StringBuilder sb = new StringBuilder();
        sb.append(mStatus);
        if (mDriver != null) {
            final WirelessMessages.Identification id = mDriver.identification();
            if (id != null) {
                sb.append(" | accessory=").append(id.describe());
            }
        }
        if (mWirelessInfo != null) {
            sb.append(" | wifi=").append(mWirelessInfo.describe());
        }
        if (mStartSession != null) {
            sb.append(" | session=").append(mStartSession.describe());
        }
        if (!mLastError.isEmpty()) {
            sb.append(" | last error=").append(mLastError);
        }
        return sb.toString();
    }

    public boolean isRunning() {
        return mRunning;
    }

    /**
     * True only while media is actually flowing.
     *
     * <p>{@link #isRunning()} is not this: it is true as soon as the listener is up (and again as soon as a socket
     * attaches), i.e. with no AirPlay streams behind it. The stall guard must not shed what is not casting.
     */
    public boolean isCasting() {
        return airPlayStreamsAlive();
    }

    /* ----------------------------------------------------------------- accept */

    /**
     * Dials the accessory's own iAP2 service on request — the device-initiated half of the pair.
     *
     * <p>{@link #dialAccessoryIap2Service} otherwise runs only <em>after</em> the accessory has
     * dialled us on {@link WirelessMessages#RFCOMM_UUID}, so an accessory that waits for the phone
     * to move first would deadlock with us.  That is the head-unit flow: its Wi-Fi credentials arrive as
     * A5 5A frames (C12000/C22000) written to <em>this</em> socket, so dialling it is what starts the
     * session.  Everything after the dial is the ordinary announced-credentials path.
     *
     * @param mac accessory Bluetooth address (the paired head unit)
     * @return true when a dial was started; the outcome lands in the log asynchronously
     */
    public boolean connectToAccessory(String mac) {
        if (mAppContext == null) {
            mLastError = "no context to dial the accessory";
            return false;
        }
        if (mac == null || mac.trim().isEmpty()) {
            mLastError = "connectToAccessory needs the accessory MAC";
            LinkState.get().log("wireless", mLastError);
            return false;
        }
        try {
            final BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null) {
                mLastError = "no BluetoothAdapter";
                return false;
            }
            final android.bluetooth.BluetoothDevice device =
                    adapter.getRemoteDevice(mac.trim().toUpperCase(java.util.Locale.ROOT));
            LinkState.get().log("wireless", "dialling the accessory on request: " + mac);
            dialAccessoryIap2Service(device);
            return true;
        } catch (IllegalArgumentException e) {
            mLastError = "bad accessory MAC " + mac + ": " + e;
            LinkState.get().log("wireless", mLastError);
            return false;
        }
    }

    /**
     * Dials the accessory's own iAP2 RFCOMM service — the half of the pair we never did.
     *
     * <p>From the production head unit's production CarPlay code the accessory listens on
     * {@link WirelessMessages#IAP2_SERVICE_UUID} and dials the phone on
     * {@link WirelessMessages#RFCOMM_UUID}.  Mirroring that, the phone serves the latter and
     * connects the former; we only ever served, so this adds the missing direction.
     *
     * <p>Runs off the accept path and can never fail the session: the accessory's own dial is
     * what actually carries the link, so a refusal here costs one log line and nothing else.
     */
    private void dialAccessoryIap2Service(final android.bluetooth.BluetoothDevice device) {
        if (device == null) {
            return;
        }
        final Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                final String peer = device.getAddress();
                for (int attempt = 1; attempt <= DIAL_ATTEMPTS; attempt++) {
                    if (!mRunning) {
                        return;
                    }
                    BluetoothSocket socket = null;
                    try {
                        socket = device.createRfcommSocketToServiceRecord(
                                UUID.fromString(WirelessMessages.IAP2_SERVICE_UUID));
                        socket.connect();
                        synchronized (mLock) {
                            if (!mRunning) {
                                try {
                                    socket.close();
                                } catch (IOException ignored) {
                                    /* closing anyway */
                                }
                                return;
                            }
                            mCarIap2Socket = socket;
                        }
                        LinkState.get().log("wireless", "dialled the accessory iAP2 service "
                                + WirelessMessages.IAP2_SERVICE_UUID + " on " + peer
                                + " — connected (attempt " + attempt + ')');
                        readAccessoryAnnouncements(socket);
                        return;
                    } catch (IOException | SecurityException e) {
                        if (socket != null) {
                            try {
                                socket.close();
                            } catch (IOException ignored) {
                                /* closing anyway */
                            }
                        }
                        LinkState.get().log("wireless", "accessory iAP2 service "
                                + WirelessMessages.IAP2_SERVICE_UUID + " on " + peer
                                + " refused (attempt " + attempt + '/' + DIAL_ATTEMPTS
                                + "): " + e);
                    }
                    try {
                        Thread.sleep(DIAL_RETRY_DELAY_MS);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
        }, "okcar-car-iap2-dial");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Reads the phone→accessory channel until the peer closes it, decoding the accession's
     * Wi-Fi hand-off frames ({@link AccessoryWifiAnnouncement}).
     *
     * <p>For the production head unit this is the only channel that carries the SoftAP
     * credentials: {@code CarplayWifiConnectManage.ConnectedThread} writes {@code INITDATA}
     * and then the {@code C12000}/{@code C22000} reports straight onto the socket it
     * accepted.  Until this loop existed the socket was opened and never read, so the
     * accessory announced nothing the phone could act on and its AirPlay receiver timed
     * out 60 s later.
     */
    private void readAccessoryAnnouncements(BluetoothSocket socket) {
        final AccessoryWifiAnnouncement.Parser parser = new AccessoryWifiAnnouncement.Parser();
        final byte[] buffer = new byte[512];
        final java.io.InputStream in;
        try {
            in = socket.getInputStream();
        } catch (IOException e) {
            LinkState.get().log("wireless", "accessory iAP2 socket has no input stream: " + e);
            return;
        }
        synchronized (mLock) {
            mCarIap2Reader = Thread.currentThread();
        }
        LinkState.get().log("wireless", "listening on the accessory iAP2 channel for its "
                + "Wi-Fi hand-off frames");
        while (mRunning) {
            final int read;
            try {
                read = in.read(buffer);
            } catch (IOException e) {
                LinkState.get().log("wireless", "accessory iAP2 channel closed: " + e);
                return;
            }
            if (read < 0) {
                LinkState.get().log("wireless", "accessory iAP2 channel closed by the peer");
                return;
            }
            parser.feed(buffer, 0, read);
            byte[] payload;
            while ((payload = parser.poll()) != null) {
                onAccessoryWifiAnnouncement(payload);
            }
        }
    }

    /**
     * One decoded accessory report.  The SSID and the passphrase come as two separate
     * reports, so the join waits for both; {@link #claimJoin()} is shared with the
     * {@code 0x4301} path and the direct bootstrap, so whichever arrives first joins once.
     */
    private void onAccessoryWifiAnnouncement(byte[] payload) {
        final String report = AccessoryWifiAnnouncement.reportOf(payload, 0, payload.length);
        if (report == null) {
            LinkState.get().log("wireless", "accessory report ignored ("
                    + payload.length + "B)");
            return;
        }
        final String value = AccessoryWifiAnnouncement.valueOf(payload, 0, payload.length);
        if (AccessoryWifiAnnouncement.REPORT_SET_WIFI_SSID.equals(report)) {
            mAnnouncedSsid = value;
            LinkState.get().log("wireless", "accessory announced its Wi-Fi SSID: \"" + value + '"');
        } else if (AccessoryWifiAnnouncement.REPORT_SET_PASSPHRASE.equals(report)) {
            mAnnouncedPassphrase = value;
            LinkState.get().log("wireless", "accessory announced its Wi-Fi passphrase ("
                    + (value == null ? 0 : value.length()) + " chars)");
        } else {
            LinkState.get().log("wireless", "accessory report " + report
                    + (value == null || value.isEmpty() ? "" : ": \"" + value + '"'));
        }
        maybeJoinAnnouncedNetwork();
    }

    /**
     * Joins the network the accessory just announced, once both halves are in.  This is the
     * same join the {@code 0x4301} path performs; it is reached from the accessory's own
     * frames because the production head unit never sends {@code 0x4301}/{@code 0x5703} — it
     * pushes the credentials down this socket instead.
     */
    private void maybeJoinAnnouncedNetwork() {
        final String ssid = mAnnouncedSsid;
        final String passphrase = mAnnouncedPassphrase;
        if (ssid == null || ssid.isEmpty() || passphrase == null) {
            return;                 /* the other half has not arrived yet */
        }
        cancelGraceTimer();
        final WirelessMessages.StartSession session = new WirelessMessages.StartSession();
        session.ssid = ssid;
        session.passphrase = passphrase;
        session.securityType = WirelessMessages.SECURITY_WPA_WPA2;
        session.airPlayPort = DIRECT_AIRPLAY_PORT;
        /* accessoryAddress stays empty: the direct path derives it after the Wi-Fi join. */
        mStartSession = session;
        LinkState.get().log("wireless", "joining the network the accessory announced: " + ssid);
        startAuthoritative(session, "the announced network");
    }

    /**
     * Starts an authoritative start session — the accessory's own 0x4301/0x5703 credentials or
     * the network it announced on the iAP2 socket.  Such a session outranks the direct
     * bootstrap: when the fallback is still polling for a stale SSID the claim is held, so the
     * authoritative session is queued in {@link #mPendingAuthoritative} and resumed the moment
     * the fallback settles (see {@link #resumePendingAuthoritative}).  Only a genuine duplicate
     * of the join already running is refused, which keeps the one-join-per-session guarantee.
     */
    private void startAuthoritative(WirelessMessages.StartSession session, String source) {
        /* Remember the receiver the accessory named, even when the join it repeats is already
         * running: the address is what the AirPlay attempt dials, and the duplicate-join guard
         * below must not throw it away. */
        if (session.ssid != null && !session.ssid.isEmpty()
                && session.accessoryAddress != null && !session.accessoryAddress.isEmpty()) {
            mAuthoritativeAddress = session.accessoryAddress;
            mAuthoritativeSsid = session.ssid;
            LinkState.get().log("wireless", source + " named the AirPlay receiver: "
                    + session.accessoryAddress + ":"
                    + (session.airPlayPort > 0 ? session.airPlayPort : DIRECT_AIRPLAY_PORT));
        }
        /* Set when this call preempts the join in flight and runs the queued authoritative session
         * itself; the passed session is then already consumed as that queued one. */
        WirelessMessages.StartSession run = session;
        synchronized (mLock) {
            if (!mRunning) {
                return;
            }
            if (mJoinStarted && !mDirectJoinInFlight && session.ssid.equals(mJoinedSsid)) {
                if (mAirPlayReady && airPlayStreamsAlive()) {
                    LinkState.get().log("wireless", source + " for " + session.ssid
                            + " — already joined and AirPlay is up, not joining twice");
                    return;
                }
                /* The join on record is a leftover from an earlier session (the phone never left the
                 * accessory's hotspot — measured 23 minutes here) and there is no AirPlay session
                 * behind it.  Returning would drop the 0x4301 *including the receiver address it
                 * carries*, and the accessory then waits forever: that is exactly how the emulator got
                 * stuck on 2026-10-01 19:08, with this guard answering "already joined, not joining
                 * twice" to a start-session that named fe80::…:7000.  Fall through instead — the
                 * tail of this method is what starts AirPlay. */
                LinkState.get().log("wireless", source + " for " + session.ssid
                        + " — joined already but no AirPlay session; starting one");
                mJoinStarted = false;
                mJoinedSsid = null;
            }
            if (mJoinStarted) {
                mPendingAuthoritative = session;
                LinkState.get().log("wireless", source + " for " + session.ssid
                        + " arrived while another join is in flight — the authoritative join"
                        + " takes over when that one settles");
                /* The accessory regenerates its network for every attempt — the emulator hands out a new
                 * `DIRECT-…` suffix each time — so a join to the network it just left can never
                 * complete, and waiting for that join to settle burns the whole link-loss grace
                 * (measured 2026-09-30: six different the emulator SSIDs in one log, the phone never left
                 * its own LAN, and the session died on the grace).  When the authoritative session
                 * names a *different* SSID than the join in flight, preempt it instead of queueing:
                 * the stale join's own settle path finds nothing queued and does nothing. */
                if (mJoinSsid == null || session.ssid == null || session.ssid.equals(mJoinSsid)) {
                    return;
                }
                final WirelessMessages.StartSession preempted = takePendingAuthoritative();
                if (preempted == null) {
                    return;
                }
                LinkState.get().log("wireless", source + " moved to " + session.ssid
                        + " — abandoning the in-flight join to " + mJoinSsid);
                run = preempted;
                releaseJoinClaim();
            }
            mJoinStarted = true;
            /* The accessory's own session takes the phone off its LAN just like the direct
             * bootstrap, so it gets the same bounded failure window: without it a session that
             * never comes up strands the phone on the accessory's hotspot with no way back
             * (measured 2026-09-29: 217 sat on the emulator's group for minutes until the receiver AP
             * was torn down by hand). */
            if (mWatchdogThread == null) {
                armDirectWatchdog();
            }
        }
        mStatus = "session offered by the accessory";
        beginSession(run, false);
    }

    /**
     * Waits a bounded moment for the receiver address the accessory names in its own start
     * session.  Only paid when the session that started the join carried no address (the
     * announced-credentials paths), and it returns immediately when the 0x4301 has already
     * arrived — it follows the credentials by tens of milliseconds on every unit measured.
     */
    private String awaitAuthoritativeAddress(String ssid) {
        final long deadline = SystemClock.elapsedRealtime() + AUTHORITATIVE_ADDRESS_WAIT_MS;
        while (SystemClock.elapsedRealtime() < deadline) {
            final String address = mAuthoritativeAddress;
            if (address != null && !address.isEmpty()
                    && (ssid == null || ssid.isEmpty() || ssid.equals(mAuthoritativeSsid))) {
                return address;
            }
            try {
                Thread.sleep(50L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return "";
    }

    /**
     * Publishes the Apple CarPlay SDP record a real iPhone carries next to the wireless-iAP
     * one, so an accessory that looks for an iPhone before it dials sees the same set.
     *
     * <p>This record exists purely for discovery: the wireless-iAP UUID registered above is
     * the one an accessory actually connects to.  A failure here is therefore logged and
     * never fatal — refusing the extra UUID must not cost us the session.
     */
    /**
     * Keeps this phone discoverable while the wireless core listens.
     *
     * <p>Discoverability is what lets the car find and bond with us at all; Android clears it after
     * its timeout and after every Bluetooth stack restart, and this module used to leave it to the
     * system — so after a restart the car's scan came up empty.  Cheap to re-arm (the platform
     * skips the work when the mode is already what we ask for), so it rides the listener tick.
     */
    private void armDiscoverable(BluetoothAdapter adapter) {
        try {
            final Class<?> type = BluetoothAdapter.class;
            final Object current = type.getMethod("getScanMode").invoke(adapter);
            if (current instanceof Integer
                    && (Integer) current == BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE) {
                return;
            }
            /* The hidden overload differs between ROMs: AOSP carries setScanMode(int), while the
             * two-argument form is what an earlier attempt assumed — measured 2026-10-02 on 217,
             * where only setScanMode(int) exists at runtime and the two-argument reflection threw
             * NoSuchMethodException once every re-arm. */
            Object ok = null;
            try {
                ok = type.getMethod("setScanMode", int.class).invoke(
                        adapter, BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE);
            } catch (NoSuchMethodException single) {
                try {
                    ok = type.getMethod("setScanMode", int.class, int.class).invoke(
                            adapter, BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE, 3600);
                } catch (NoSuchMethodException pair) {
                    try {
                        ok = type.getMethod("setScanMode", int.class, long.class).invoke(
                                adapter, BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE, 3600L);
                    } catch (NoSuchMethodException none) {
                        if (!mDiscoverableWarned) {
                            mDiscoverableWarned = true;
                            LinkState.get().log("wireless", "core: this ROM exposes no setScanMode"
                                    + " overload — discoverability is left to the platform; pair from"
                                    + " the phone side instead of scanning from the car");
                        }
                        return;
                    }
                }
            }
            mDiscoverableWarned = false;
            LinkState.get().log("wireless", "core: discoverable mode re-armed=" + ok);
        } catch (Throwable t) {
            if (!mDiscoverableWarned) {
                mDiscoverableWarned = true;
                LinkState.get().log("wireless", "core: cannot set the discoverable scan mode (" + t + ')');
            }
        }
    }

    private void startAppleCarplayRecord(BluetoothAdapter adapter) {
        try {
            mCarplayServer = adapter.listenUsingRfcommWithServiceRecord(
                    CARPLAY_SERVICE_NAME, UUID.fromString(WirelessMessages.APPLE_CARPLAY_UUID));
        } catch (IOException | SecurityException e) {
            LinkState.get().log("wireless", "Apple CarPlay SDP record not registered: " + e);
            return;
        }
        LinkState.get().log("wireless", "advertising the Apple CarPlay SDP record "
                + WirelessMessages.APPLE_CARPLAY_UUID);
        final Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (mRunning) {
                    final BluetoothServerSocket server = mCarplayServer;
                    if (server == null) {
                        return;
                    }
                    BluetoothSocket socket;
                    try {
                        socket = server.accept();
                    } catch (IOException e) {
                        if (mRunning) {
                            LinkState.get().log("wireless", "CarPlay record accept ended: " + e);
                        }
                        return;
                    }
                    if (socket == null) {
                        continue;
                    }
                    synchronized (mLock) {
                        if (!mRunning) {
                            try {
                                socket.close();
                            } catch (IOException ignored) {
                                /* closing anyway */
                            }
                            return;
                        }
                        final String peer = socket.getRemoteDevice() == null
                                ? "?" : socket.getRemoteDevice().getAddress();
                        LinkState.get().log("wireless", "accessory dialled the Apple CarPlay "
                                + "record from " + peer + " (no service behind it; parked)");
                        mCarplayParked.add(socket);
                        while (mCarplayParked.size() > MAX_PARKED_CARPLAY_SOCKETS) {
                            try {
                                mCarplayParked.remove(0).close();
                            } catch (IOException ignored) {
                                /* closing anyway */
                            }
                        }
                    }
                }
            }
        }, "okcar-carplay-record");
        thread.setDaemon(true);
        thread.start();
    }

    /** a13 special package: connects to the companion app's proxy socket, resuming per session (each connection serves it only once). */
    private void startBtProxyLoop() {
        if (mProxyThread != null) {
            return;
        }
        final Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (mKeepListening || mRunning) {
                    LocalSocket socket = null;
                    try {
                        socket = new LocalSocket();
                        socket.connect(new LocalSocketAddress(mBtProxyName,
                                LocalSocketAddress.Namespace.ABSTRACT));
                        mProxyPeer = "";
                        mProxyTransport = new RfcommIap2Transport(socket.getInputStream(),
                                socket.getOutputStream(), "bt proxy (" + mBtProxyName + ')');
                        LinkState.get().log("wireless", "connected to the BT proxy " + mBtProxyName);
                        attach(null);
                        while (mRunning) {
                            Thread.sleep(500L);
                        }
                        /* Return to the "listening" steady state after the session ends: the panel/WebUI judges health from this line;
                         * any other wording would be misread as "not listening". */
                        mStatus = "listening as " + (mDeviceId == null || mDeviceId.isEmpty()
                                ? mBluetoothId : mDeviceId) + " via the bt proxy";
                        /* The file is written by the daemon: it reads session.status(); here we only maintain the status text. */
                    } catch (Throwable t) {
                        LinkState.get().log("wireless", "bt proxy connect failed: " + t);
                        sleepQuietly(1500L);
                    } finally {
                        closeProxy(socket);
                        mProxyTransport = null;
                    }
                }
            }
        }, "okcar-bt-proxy");
        thread.setDaemon(true);
        mProxyThread = thread;
        thread.start();
    }

    private static void closeProxy(LocalSocket socket) {
        if (socket == null) {
            return;
        }
        try {
            socket.close();
        } catch (IOException ignored) {
            /* closing anyway */
        }
    }

    /**
     * The always-on core: the RFCOMM listeners plus this accept supervisor.
     *
     * <p>The listener must not be tied to a session.  It used to be created in {@link #start} and
     * closed in {@link #stop}, and the accept loop simply {@code break}ed on the first IOException
     * — so one failed dial or a session teardown left the phone undiallable until the daemon was
     * restarted (field symptom: the emulator "can't connect any more", `read failed, socket might closed or timeout`).
     * It is idempotent and re-arms itself now; only {@link #shutdown()} takes it down.
     */
    private synchronized void ensureListening(BluetoothAdapter adapter) {
        if (mBtProxyName != null) {
            startBtProxyLoop();
            return;
        }
        if (adapter != null) {
            mListenerAdapter = adapter;
        }
        if (!mKeepListening) {
            return;
        }
        final BluetoothAdapter target = mListenerAdapter != null
                ? mListenerAdapter : BluetoothAdapter.getDefaultAdapter();
        if (target == null) {
            return;
        }
        /* The car has to *find* us before it can pair: discoverability is a timed setting the
         * system drops on its own (and every Bluetooth stack restart), and nothing else in this
         * module re-arms it — measured 2026-10-02: after a stack restart the EXEAD could no longer
         * see the phone at all.  Re-armed on every listener tick, which is once a second. */
        armDiscoverable(target);
        /* ... and keep it armed: the platform clears the mode when its timeout expires, and the
         * listener tick only runs while the core is (re)starting, so a one-shot arm silently lapsed
         * — measured 2026-10-02, the car then could not see the phone at all. */
        if (mDiscoverableThread == null || !mDiscoverableThread.isAlive()) {
            mDiscoverableThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    while (mKeepListening) {
                        final BluetoothAdapter adapter = mListenerAdapter != null
                                ? mListenerAdapter : BluetoothAdapter.getDefaultAdapter();
                        if (adapter != null) {
                            armDiscoverable(adapter);
                        }
                        try {
                            Thread.sleep(DISCOVERABLE_REARM_MS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                }
            }, "okcar-discoverable");
            mDiscoverableThread.setDaemon(true);
            mDiscoverableThread.start();
        }
        if (mServer == null) {
            try {
                mServer = target.listenUsingRfcommWithServiceRecord(SERVICE_NAME, RFCOMM_UUID);
                LinkState.get().log("wireless", "core: RFCOMM listener up");
            } catch (IOException | SecurityException e) {
                LinkState.get().log("wireless", "core: cannot listen (" + e + ") — will retry");
                return;
            }
        }
        if (mCarplayServer == null && ADVERTISE_APPLE_CARPLAY_RECORD) {
            startAppleCarplayRecord(target);
        }
        if (mAcceptThread == null || !mAcceptThread.isAlive()) {
            mAcceptThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    acceptLoop();
                }
            }, "okcar-wireless-accept");
            mAcceptThread.setDaemon(true);
            mAcceptThread.start();
        }
    }

    /** Takes the core down as well as the session: only the daemon's stop/quit does this. */
    public void shutdown() {
        mKeepListening = false;
        stop();
        closeListeners();
    }

    private synchronized void closeListeners() {
        if (mServer != null) {
            try {
                mServer.close();
            } catch (IOException ignored) {
                /* already closed */
            }
            mServer = null;
        }
        if (mCarplayServer != null) {
            try {
                mCarplayServer.close();
            } catch (IOException ignored) {
                /* already closed */
            }
            mCarplayServer = null;
        }
        mAcceptThread = null;
        LinkState.get().log("wireless", "core: RFCOMM listeners closed");
    }

    private void acceptLoop() {
        while (mKeepListening) {
            final BluetoothServerSocket server = mServer;
            if (server == null) {
                ensureListening(null);
                if (mServer == null) {
                    sleepQuietly(500L);
                }
                continue;
            }
            BluetoothSocket socket;
            try {
                socket = server.accept();
            } catch (IOException e) {
                if (!mKeepListening) {
                    break;
                }
                /* Re-arm instead of dying: the accessory may be mid-retry, and a closed listener
                 * must never be the reason the phone cannot be reached. */
                LinkState.get().log("wireless", "core: accept ended (" + e + ") — re-arming");
                synchronized (this) {
                    if (mServer == server) {
                        try {
                            server.close();
                        } catch (IOException ignored) {
                            /* already closed */
                        }
                        mServer = null;
                    }
                }
                ensureListening(null);
                sleepQuietly(300L);
                continue;
            }
            if (socket == null) {
                continue;
            }
            if (!mKeepListening) {
                closeSocketQuietly(socket);
                break;
            }
            synchronized (mLock) {
                if (mRunning && mSocket != null) {
                    /* The user's model: the core stays, the pipeline is restarted. */
                    LinkState.get().log("wireless", "the accessory dialled again — restarting the"
                            + " session pipeline (the listener is untouched)");
                    stop();
                }
                if (!mKeepListening) {
                    closeSocketQuietly(socket);
                    break;
                }
                if (castsSuspended()) {
                    /* The accessory is still dialling: the listener stays up, only this round is not cast (otherwise the load that just dragged the phone under would come right back). */
                    LinkState.get().log("wireless", "the accessory dialled while casting is"
                            + " suspended (" + (suspendRemainingMs() / 1000L) + "s left: "
                            + sCooldown.reason() + ") — dropping this dial, listener stays up");
                    closeSocketQuietly(socket);
                    continue;
                }
                attach(socket);
            }
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void closeSocketQuietly(BluetoothSocket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            /* closing anyway */
        }
    }

    /**
     * The iAP2 link profile the wireless phase needs.
     *
     * <p>It has to be applied on <em>every</em> connection, not once per process: {@link #stop()}
     * restores the saved defaults (the wired path shares these statics), so a session that starts
     * from a re-dial — the accessory dialling again while the core stayed up — used to run with the
     * defaults.  Symptom (2026-10-02): the first dial bootstrapped fine and the re-dial reached
     * 0x5702/0x4E0D/0x4E0E, after which the accessory's 0x5703 was never processed again.
     *
     * <p>The real iPhone answers the accessory's SYN with the accessory's own session entries and
     * then sends 0x1D00 on the accessory's control session id — the emulator's CarPlay link says 10, the
     * wireless-iAP block used by Fruit Link says 1.  Echoing is what makes both work (§12.55).
     */
    private void applyWirelessSwitches() {
        Iap2Link.setDeviceSendsSyn(false);
        Iap2Link.setAckNextExpected(false);
        Iap2Link.setSynAckEchoParams(true);
        Iap2Link.setSynTemplate(WirelessMessages.iphoneSynAck());
    }

    /** Cancels a pending Wi-Fi revert: a new dial means the accessory network is wanted again. */
    private void cancelPendingRevert() {
        final Thread revert = mRevertThread;
        if (revert != null && revert.isAlive()) {
            mRevertThread = null;
            revert.interrupt();
        }
    }

    private void attach(BluetoothSocket socket) {
        cancelPendingRevert();
        applyWirelessSwitches();
        /* A dial means a session is starting, and a re-dial arrives after stop() cleared this.
         * Without it every handshake step that guards on mRunning (the 0x4301 handler among them)
         * bails out silently: the bootstrap looked fine and then nothing happened at all
         * (measured 2026-10-02 — the emulator's re-dial), because the pipeline was "not running". */
        mRunning = true;
        mAirPlayReady = false;
        mJoinStarted = false;
        mDirectJoinInFlight = false;
        mJoinedSsid = null;
        mWirelessInfo = null;
        mStartSession = null;
        mAuthoritativeAddress = "";
        mAuthoritativeSsid = "";
        mLastError = "";
        mSocket = socket;
        final String peer = socket != null
                ? (socket.getRemoteDevice() == null ? "?" : socket.getRemoteDevice().getAddress())
                : (mProxyPeer.isEmpty() ? "bt-proxy" : mProxyPeer);
        mPeerAddress = peer;
        LinkState.get().log("wireless", AccessoryMemory.describe(mAppContext, peer));
        try {
            /* Bluetooth first, the sealed event channel after it: the accessory drops RFCOMM as soon
             * as the AirPlay session is up, and answering its iAP2 on that dead socket is what made
             * the production head unit time us out (~13 s) and drop the connection. */
            final com.okcar.link.iap2.Iap2Link.Transport rawTransport = mProxyTransport != null
                    ? mProxyTransport : new RfcommIap2Transport(socket);
            mTransport = new WirelessIap2Transport(rawTransport,
                    new WirelessIap2Transport.EventChannel() {
                        @Override
                        public boolean sendIap(byte[] frame) {
                            final WirelessAirPlayClient client = mAirPlay;
                            return client != null && client.sendAccessoryIap(frame);
                        }
                    });
        } catch (IOException e) {
            mLastError = "streams from " + peer + ": " + e;
            LinkState.get().log("wireless", mLastError);
            return;
        }
        mDriver = new WirelessCarPlayDriver(this, mBluetoothId, mDeviceId);
        mLink = new Iap2Link(mTransport, mDriver);
        mDriver.setLink(mLink);
        mStatus = "connected to " + peer + " (iAP2 starting)";
        LinkState.get().log("wireless", "accessory dialled us: " + peer);
        mLink.start();
        if (socket != null) {
            dialAccessoryIap2Service(socket.getRemoteDevice());
        } else {
            LinkState.get().log("wireless", "bt proxy mode: the accessory dial is left to it");
        }
    }

    private void restoreSwitches() {
        Iap2Link.setDeviceSendsSyn(mSavedDeviceSendsSyn);
        Iap2Link.setAckNextExpected(mSavedAckNextExpected);
        Iap2Link.setSynAckEchoParams(mSavedSynAckEchoParams);
        Iap2Link.setSynTemplate(null);
    }

    private boolean fail(String reason) {
        mLastError = reason;
        mStatus = "not running: " + reason;
        LinkState.get().log("wireless", mStatus);
        Log.w(TAG, mStatus);
        return false;
    }

    /* ------------------------------------------------------ driver callbacks */

    /**
     * The accessory handed us 0x4301 (or a retransmit of it).  This remains the authoritative
     * path: it joins with the credentials the accessory actually sent and opens AirPlay on the
     * address it advertised.  The direct bootstrap only fires when 0x4301 never arrives, and
     * {@link #startAuthoritative} makes sure a real 0x4301 is never dropped in favour of the
     * fallback's own credentials.
     */
    @Override
    public void onWirelessStartSession(WirelessMessages.StartSession session) {
        mStartSession = session;
        /* Refresh the direct-bootstrap fallback from the accessory's real credentials so a
         * later fallback can never join a previous unit's hardcoded hotspot again. */
        if (session.ssid != null && !session.ssid.isEmpty()) {
            mDirectSsid = session.ssid;
            if (session.passphrase != null) {
                mDirectPassphrase = session.passphrase;
            }
        }
        cancelGraceTimer();
        AccessoryMemory.rememberHandover(mAppContext, mPeerAddress, AccessoryMemory.Handover.IAP2);
        startAuthoritative(session, "0x4301");
    }

    /**
     * Arms or disarms the iAP2 idle watchdog.  While AirPlay is up the link is quiet by design (the
     * accessory's iAP2 rides the event channel), so declaring it dead on silence tore down healthy
     * sessions; before the handoff silence is exactly how the "accessory has moved on" wedge shows up.
     */
    private void setIdleWatchdog(boolean enabled) {
        final Iap2Link link = mLink;
        if (link != null) {
            link.setIdleLinkDownMs(enabled ? Iap2Link.DEFAULT_IDLE_LINK_DOWN_MS : 0L);
            LinkState.get().log("iap2", "idle watchdog " + (enabled ? "armed" : "disarmed")
                    + " (the wireless session " + (enabled ? "is not up" : "owns the link now") + ')');
        }
    }

    /** True when the recorded AirPlay session still has live media streams behind it. */
    private boolean airPlayStreamsAlive() {
        final WirelessAirPlayClient client = mAirPlay;
        return client != null && client.isStreaming();
    }

    /**
     * Re-establishes the wireless session after the AirPlay streams died.
     *
     * <p>The accessory re-offers a session only while its own retry loop is running.  Measured
     * 2026-10-01 19:26: the 0x4301 arrived 8 s after the video pipe broke, was refused because the
     * liveness poll had not run yet (the flag was still true), and the accessory then went silent for
     * the next four minutes — "stuck reconnecting" with the session parked at "waiting for the
     * accessory".  The receiver address and credentials are already in hand, so drive the handshake
     * from this side instead of waiting.
     */
    /**
     * After the session ends or the handshake fails, restore the status to "still listening, waiting for the accessory".
     *
     * <p>These paths used to set {@code mStatus} permanently to AirPlay text ("AirPlay handshake failed — see log",
     * "AirPlay session ended …") even though the RFCOMM listener was in fact still up. The WebUI then showed "listener gone/not listening",
     * and the user saw "stuck after disconnect" (measured 2026-10-03: after the emulator disconnected of its own accord it stayed in this state and needed a manual rebuild).
     * If the listening socket is still there, say it is still listening; only when it is really gone say stopped — so "should the listener be rebuilt" has a basis.
     */
    private void refreshIdleStatus(String why) {
        final BluetoothServerSocket server = mServer;
        if (mKeepListening && server != null) {
            mStatus = "listening as " + mBluetoothId + " (device id " + mDeviceId + ") — " + why;
        } else {
            mStatus = "stopped — " + why;
        }
        mLastError = null;
        LinkState.get().log("wireless", mStatus);
    }

    private void scheduleSessionRecovery() {
        final Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(SESSION_RECOVERY_DELAY_MS);
                } catch (InterruptedException e) {
                    return;
                }
                final WirelessMessages.StartSession session = mStartSession;
                if (!mRunning || mAirPlayReady || session == null) {
                    return;
                }
                LinkState.get().log("wireless", "recovering the session from this side: re-offering '"
                        + session.ssid + "' (the accessory went quiet)");
                try {
                    startAuthoritative(session, "local recovery");
                } catch (Throwable t) {
                    LinkState.get().log("wireless", "local recovery failed: " + t);
                }
            }
        }, "okcar-wireless-recovery");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Watches the AirPlay session that {@link #mAirPlayReady} claims is up.
     *
     * <p>The flag used to be write-once: the media streams could die ({@code video write failed …
     * Broken pipe} when the accessory went away) and the session went on believing it was up.  The
     * accessory then reconnected, sent its 0x4301 with the receiver address, and was answered with
     * "already joined and AirPlay is up" — the receiver retried in a loop and nothing ever started a
     * new session (measured 2026-10-01 19:13: video died at 19:13:41 while the emulator kept reconnecting;
     * the same staleness had blocked the 19:08 attempt).  The sender's own liveness is the fact that
     * matters, and it is cheap to poll.
     */
    private void monitorAirPlayLiveness(final WirelessAirPlayClient client) {
        final Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (mRunning) {
                    try {
                        Thread.sleep(AIRPLAY_LIVENESS_POLL_MS);
                    } catch (InterruptedException e) {
                        return;
                    }
                    if (!mAirPlayReady || mAirPlay != client) {
                        return;
                    }
                    if (client.isStreaming()) {
                        continue;
                    }
                    LinkState.get().log("wireless", "the AirPlay streams are gone ("
                            + (client.streamError() == null ? "no error reported" : client.streamError())
                            + ") — clearing the session state so the next 0x4301 starts a fresh one");
                    synchronized (mLock) {
                        mAirPlayReady = false;
                        mJoinStarted = false;
                        mJoinedSsid = null;
                        mDirectJoinInFlight = false;
                    }
                    refreshIdleStatus("the AirPlay session ended, waiting for the accessory");
                    /* No AirPlay behind it: silence on the iAP2 link means the accessory really may
                     * be gone, so the watchdog is meaningful again. */
                    setIdleWatchdog(true);
                    scheduleSessionRecovery();
                    return;
                }
            }
        }, "okcar-airplay-liveness");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * The accessory's iAP2 link died.  A session that came up has already disarmed its failure
     * watchdog (a live session must never be torn down by a timer), so this is the only place
     * that can notice the accessory went away — the phone would otherwise sit on a hotspot that
     * no longer exists, off its own LAN, until someone toggles Wi-Fi by hand (measured
     * 2026-09-30).  Give the link a moment to come back, then run the normal stop()/revert.
     */
    @Override
    public void onWirelessLinkDown(String reason) {
        /* A live AirPlay session owns the Wi-Fi link, not Bluetooth.  Some head units close the
         * iAP2/RFCOMM link seconds after they have handed the Wi-Fi credentials over — the production head unit
         * unit does exactly that (measured 2026-09-30 22:20:42, right after /auth-setup) — and
         * treating that as "the accessory went away" tore down a healthy mirror 20 s in.  While
         * AirPlay is up, keep the session: if the car really is gone, the AirPlay path fails on
         * its own (the media streams stop on a dead receiver) and the user or stop() ends it. */
        if (mAirPlayReady) {
            LinkState.get().log("wireless", "accessory link down: " + reason
                    + " — the AirPlay session is up, keeping it (the Wi-Fi link is the transport"
                    + " now, not Bluetooth)");
            return;
        }
        LinkState.get().log("wireless", "accessory link down: " + reason
                + " — reverting in " + LINK_LOSS_GRACE_MS + "ms unless it comes back");
        final Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(LINK_LOSS_GRACE_MS);
                } catch (InterruptedException e) {
                    return;
                }
                if (!mRunning) {
                    return;
                }
                /* The link can also drop *during* the handshake, before mAirPlayReady is set, and
                 * the session may well come up inside the grace window (measured 2026-09-30: the
                 * the production head unit unit closed the RFCOMM link at 22:27:51 while SETUP was still in flight,
                 * AirPlay was up by 22:28:01, and the grace fired at 22:28:11 killing it).  So ask
                 * again at the deadline, not only when the link went down. */
                if (mAirPlayReady) {
                    LinkState.get().log("wireless", "the accessory link did not come back, but the"
                            + " AirPlay session came up meanwhile — keeping it");
                    return;
                }
                LinkState.get().log("wireless", "the accessory link did not come back — stopping "
                        + "the session and restoring this phone's own network");
                stop();
            }
        }, "okcar-wireless-link-loss");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * The accessory's iAP2 link is up.  For the wireless-iAP family (control session 1 —
     * Fruit Link) a real iPhone needs no iAP2 session at all: it just joins the accessory's
     * hotspot and opens AirPlay on it.  Give its 0x4301 a short grace window and, if it does
     * not come, bootstrap from the known credentials.
     */
    @Override
    public void onWirelessLinkUp(boolean wirelessIapFamily) {
        if (!wirelessIapFamily) {
            return;
        }
        if (!mAirPlayReady) {
            /* A fresh accessory link with no AirPlay session behind it means the previous session's
             * join bookkeeping is stale — the phone can well have stayed on the accessory's hotspot
             * the whole time (measured 23 minutes of silence on 2026-10-01) while the accessory
             * reconnected.  Clearing it here is what lets the new 0x4301 start a session instead of
             * being answered with "already joined".  When AirPlay *is* up the link is only a
             * side-channel (head units close RFCOMM right after the credentials) and nothing is
             * cleared. */
            boolean stale;
            synchronized (mLock) {
                stale = mJoinStarted || mJoinedSsid != null || mDirectJoinInFlight;
                mJoinStarted = false;
                mJoinedSsid = null;
                mDirectJoinInFlight = false;
            }
            if (stale) {
                logDirect("new accessory link with no AirPlay session — cleared the stale join record");
            }
        }
        /* The accessory recycles its CarplayService roughly every 8 s (`carplay:connecting
         * timeout:N`) and each cycle brings up a fresh AirPlay receiver, so a later link-up
         * inside the bounded window is a new chance to hand-shake.  Once the Wi-Fi is joined
         * and the watchdog armed, only the AirPlay attempt is repeated: never a second join,
         * never a second watchdog, never two attempts at once. */
        if (mJoinStarted && mWatchdogThread != null) {
            retryDirectAirPlay();
            return;
        }
        if (mGraceThread != null) {
            logDirect("grace timer already running — not arming a second one");
            return;
        }
        final boolean knownDirect = AccessoryMemory.handover(mAppContext, mPeerAddress)
                == AccessoryMemory.Handover.DIRECT;
        logDirect("wireless-iAP link up — " + (knownDirect
                ? "this unit is known to hand over without 0x4301, skipping the "
                        + DIRECT_GRACE_MS + "ms grace"
                : DIRECT_GRACE_MS + "ms grace for the accessory's 0x4301"));
        final Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (!knownDirect) {
                        Thread.sleep(DIRECT_GRACE_MS);
                    }
                } catch (InterruptedException e) {
                    logDirect("grace window cancelled — the 0x4301 path claimed the session");
                    return;
                }
                if (!claimJoin()) {
                    logDirect("grace expired but the session is no longer startable/claimed");
                    return;
                }
                synchronized (mLock) {
                    mDirectJoinInFlight = true;
                }
                final WirelessMessages.StartSession session = directStartSession();
                if (session == null) {
                    /* No credentials were ever announced by this unit, and there is no default to
                     * fall back on any more: joining a guessed or leftover hotspot is worse than
                     * saying so. */
                    releaseJoinClaim();
                    synchronized (mLock) {
                        mDirectJoinInFlight = false;
                    }
                    mLastError = "the accessory never announced Wi-Fi credentials — direct"
                            + " bootstrap refused (no guessed hotspot)";
                    logDirect(mLastError);
                    return;
                }
                mStartSession = session;
                mStatus = "direct bootstrap: joining " + session.ssid;
                AccessoryMemory.rememberHandover(mAppContext, mPeerAddress,
                AccessoryMemory.Handover.DIRECT);
        logDirect("no 0x4301/0x5703 after " + DIRECT_GRACE_MS + "ms — joining " + session.ssid
                        + " (port " + DIRECT_AIRPLAY_PORT + ")");
                /* Arm the bounded failure window before touching Wi-Fi: joining the accessory
                 * AP drops the LAN and with it Wi-Fi adb, so a failing attempt must revert
                 * unconditionally.  A handshake that succeeds disarms the watchdog instead. */
                armDirectWatchdog();
                beginSession(session, true);
            }
        }, "okcar-wireless-direct-bootstrap");
        thread.setDaemon(true);
        mGraceThread = thread;
        thread.start();
    }

    /**
     * The synthetic StartSession for the accessory whose credentials we already know, or null when
     * it never announced any.  Null is a refusal, not an error: see {@link #mDirectSsid}.
     */
    private WirelessMessages.StartSession directStartSession() {
        if (mDirectSsid == null || mDirectSsid.isEmpty()) {
            return null;
        }
        final WirelessMessages.StartSession session = new WirelessMessages.StartSession();
        session.ssid = mDirectSsid;
        session.passphrase = mDirectPassphrase == null ? "" : mDirectPassphrase;
        session.securityType = WirelessMessages.SECURITY_WPA_WPA2;
        session.airPlayPort = DIRECT_AIRPLAY_PORT;
        /* accessoryAddress stays empty: it is derived after the Wi-Fi join. */
        return session;
    }

    /**
     * The one join + AirPlay-client path, shared by the 0x4301 handler and the direct
     * bootstrap.  {@code direct} only changes the logging prefix and the receiver address:
     * the 0x4301 path keeps using the address the accessory advertised, while the direct
     * path derives it from the joined network.
     */
    private static String target0(String address) {
        return address == null || address.isEmpty() ? "(unset)" : address;
    }

    private void beginSession(final WirelessMessages.StartSession session, final boolean direct) {
        mJoinSsid = session.ssid;
        final Context context = mAppContext;
        if (context == null) {
            finishDirectJoin();
            releaseJoinClaim();
            mLastError = "no context for the Wi-Fi join";
            return;
        }
        final WifiGroupJoiner.Result result = WifiGroupJoiner.join(context, session);
        mStatus = "joining " + session.ssid + " -> " + result.describe();
        if (direct) {
            logDirect("Wi-Fi join " + result.describe());
        } else {
            LinkState.get().log("wireless", mStatus);
        }
        if (result.submitted) {
            synchronized (mLock) {
                mJoinedSsid = session.ssid;
            }
        }
        if (direct) {
            finishDirectJoin();
        }
        /* The accessory's own credentials always win: if its start session arrived while this
         * fallback join was polling, run it now instead of carrying on with the fallback's. */
        if (resumePendingAuthoritative(session, direct)) {
            return;
        }
        if (!result.submitted) {
            mLastError = result.describe();
            releaseJoinClaim();
            return;
        }
        /* Step ④: once we are (or are about to be) on the accessory's network, open
         * the AirPlay session on it — GET /info, pair-setup (PIN 3939), pair-verify.
         * The crypto is in AirPlayPairing; this only drives the socket. */
        final WirelessAirPlayClient client;
        synchronized (mLock) {
            if (!mRunning) {
                releaseJoinClaim();
                mLastError = "session stopped before the AirPlay client was created";
                return;
            }
            stopAirPlayClient();
            mAirPlay = new WirelessAirPlayClient(context);
            applyMediaOverrides(mAirPlay);
            client = mAirPlay;
            /* Claim the single in-flight handshake slot before the settle sleep so a
             * link-up arriving meanwhile is logged and skipped instead of raced. */
            mAirPlayAttemptInProgress = true;
        }
        final int port = session.airPlayPort > 0 ? (int) session.airPlayPort : DIRECT_AIRPLAY_PORT;
        final String address = session.accessoryAddress;
        final Thread thread = new Thread(new Runnable() {
            /** Set when the handshake failed: the queued accessory session takes over. */
            private boolean startQueuedAfterFailure;
            @Override
            public void run() {
                try {
                    Thread.sleep(AIRPLAY_SETTLE_MS);
                    /* Target address: the address named by 0x4301 takes priority (it may be re-resolved below as needed). */
                    String target = address;
                    if (!mRunning || mAirPlay != client) {
                        /* It gave up silently for several rounds: the head unit closes RFCOMM by design after handing over the credentials, so whether
                         * this is "the session was stopped" or "replaced by a newer attempt" must be visible, otherwise the symptom is "nothing happened". */
                        LinkState.get().log("airplay", "not opening the client after the "
                                + (AIRPLAY_SETTLE_MS / 1000) + "s settle: "
                                + (!mRunning ? "the session was stopped"
                                             : "a newer attempt replaced this client")
                                + " (direct=" + direct + ", airplayReady=" + mAirPlayReady + ")");
                        if (direct) {
                            logDirect("session stopped before AirPlay — not opening a client");
                        }
                        return;
                    }
                    LinkState.get().log("airplay", "dialing the receiver at " + target0(target)
                            + " after the settle (direct=" + direct + ")");
                    if (target == null || target.isEmpty()) {
                        if (direct) {
                            final ReceiverAddress choice = resolveReceiverAddress(context);
                            if (choice == null) {
                                logDirect("no receiver address and no default route — nothing to"
                                        + " dial; not opening a client");
                                return;
                            }
                            target = choice.address;
                            logDirect("receiver address " + target + " (source=" + choice.source
                                    + (choice.gateway == null ? "" : ", gateway=" + choice.gateway)
                                    + ')');
                        } else {
                            /* 0x5703 announced the network but not the receiver, so wait for the
                             * accessory's own start session — it carries the address that actually
                             * accepts the connection.  Units that never send one (the production head unit) must be
                             * resolved exactly like the direct bootstrap is: DHCP gateway first,
                             * because on an accessory-hosted AP the gateway *is* the accessory and
                             * its address changes on every AP restart (measured .149/.185/.147/.49).
                             * Leaving it empty used to hand the job to the client's own x.x.x.1
                             * candidate list, which dialled the home gateway instead (2026-09-30:
                             * tried 192.0.2.4:7000 while the car sat at 192.0.2.49). */
                            target = awaitAuthoritativeAddress(session.ssid);
                            if (target.isEmpty()) {
                                final ReceiverAddress choice = resolveReceiverAddress(context);
                                if (choice == null) {
                                    LinkState.get().log("wireless", "the accessory named no receiver"
                                            + " address and there is no default route — not opening"
                                            + " a client");
                                    return;
                                }
                                target = choice.address;
                                LinkState.get().log("wireless", "the accessory named no receiver"
                                        + " address — using " + target + " (source="
                                        + choice.source + ')');
                            } else {
                                LinkState.get().log("wireless",
                                        "using the address the accessory named: " + target);
                            }
                        }
                    }
                    /* Cache the resolved receiver so a later link-up retry never re-resolves
                     * mDNS or re-reads the gateway (see retryDirectAirPlay). */
                    mDirectReceiverAddress = target;
                    mDirectReceiverPort = port;
                    final boolean ok = client.run(target, port);
                    mAirPlayReady = ok;
                    if (ok) {
                        setIdleWatchdog(false);
                        monitorAirPlayLiveness(client);
                    }
                    mStatus = ok ? "AirPlay channels ready (" + target + ":" + port + ")"
                            : "AirPlay handshake failed — see log";
                    if (!ok) {
                        refreshIdleStatus("the AirPlay handshake failed — see log");
                    }
                    if (direct) {
                        logDirect(mStatus);
                    } else {
                        LinkState.get().log("wireless", mStatus);
                    }
                    if (!ok) {
                        mLastError = mStatus;
                        /* A failed handshake must give the join claim back.  Otherwise every
                         * later 0x4301/0x5703 is queued behind a claim nothing will ever
                         * release and the session wedges — measured 2026-09-29: the accessory
                         * restarts its CarplayService with a NEW SSID after a failed attempt
                         * and that new session was never joined again. */
                        releaseJoinClaim();
                        startQueuedAfterFailure = true;
                        if (!direct) {
                            /* …and the accessory's network has to go back with it: nothing retries
                             * on it (the retry window only exists for the direct bootstrap), and a
                             * saved accessory config left behind is what let the phone re-join the
                             * accessory hotspot later, outside any session — measured 2026-10-02:
                             * a failed handshake left netId 265 "car" and join_active=true while the
                             * phone sat on its own network. */
                            releaseAccessoryNetworkAfterFailure(mStatus);
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    synchronized (mLock) {
                        mAirPlayAttemptInProgress = false;
                    }
                    if (startQueuedAfterFailure) {
                        /* The accessory has usually moved to a new network by now; run whatever
                         * its own start session queued while this attempt was busy. */
                        final WirelessMessages.StartSession pending = takePendingAuthoritative();
                        if (pending != null && !pending.ssid.equals(session.ssid) && claimJoin()) {
                            LinkState.get().log("wireless", "the accessory moved to " + pending.ssid
                                    + " after the failed handshake — starting that session now");
                            beginSession(pending, false);
                        }
                    }
                }
            }
        }, "okcar-wireless-airplay");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Gives the accessory's Wi-Fi network back after a handshake failure, so a failed session cannot
     * leave the phone able to return to the accessory's hotspot on its own.
     *
     * <p>Detached and guarded on purpose: a takeover session or a retry may legitimately still be
     * using the network, and taking it away under one would break it.  The grace period plus the
     * {@code mAirPlayReady}/{@code mAirPlayAttemptInProgress} check means the network is only
     * withdrawn when nothing else is using it.
     *
     * <p>Only the accessory-announced path needs this: the direct bootstrap has its own bounded
     * watchdog, and its retries re-handshake over the network the phone is already on.
     */
    private void releaseAccessoryNetworkAfterFailure(final String reason) {
        final Context context = mAppContext;
        if (context == null) {
            return;
        }
        final Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(FAILED_HANDSHAKE_RELEASE_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (mAirPlayReady || mAirPlayAttemptInProgress) {
                    LinkState.get().log("wireless", "not releasing the accessory Wi-Fi after \""
                            + reason + "\": something is still using it");
                    return;
                }
                WifiGroupJoiner.releaseAll(context);
                LinkState.get().log("wireless", "released the accessory Wi-Fi network after \""
                        + reason + '"');
            }
        }, "okcar-wifi-release-failed");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * A further wireless-iAP link-up inside the direct-bootstrap window: hand-shake again
     * against the receiver address that was already resolved, without touching Wi-Fi (the
     * join is claimed once and the watchdog is armed once).
     *
     * <p>Concurrency is deliberate: an attempt that is still running causes this link-up to
     * be logged and skipped, so two AirPlay handshakes never overlap; the previous client is
     * torn down through {@link #stopAirPlayClient()} before the new one is created.  The
     * whole thing stays inside the watchdog: once it fires, {@code mWatchdogThread} is null
     * and this refuses to start anything.
     */
    private void retryDirectAirPlay() {
        final Context context = mAppContext;
        if (context == null) {
            logRetry("no context — cannot retry the AirPlay handshake");
            return;
        }
        final WirelessAirPlayClient client;
        final String target;
        final int port;
        final int attempt;
        synchronized (mLock) {
            if (!mRunning || !mJoinStarted || mWatchdogThread == null) {
                logRetry("link-up, but the direct-bootstrap window is not active — skipping");
                return;
            }
            if (mAirPlayAttemptInProgress) {
                logRetry("previous handshake still in progress — skipping this link-up");
                return;
            }
            /* A successful session owns the receiver; a later link-up must not kill it. */
            if (mAirPlayReady) {
                logRetry("AirPlay session is already up — not restarting it");
                return;
            }
            target = mDirectReceiverAddress;
            if (target == null || target.isEmpty()) {
                logRetry("receiver address not resolved yet — skipping this link-up");
                return;
            }
            port = mDirectReceiverPort;
            attempt = ++mAirPlayRetries;
            /* Reuse the existing teardown so the previous (failed) client's sockets, tunnel
             * and Bluetooth restore all run exactly once before the new attempt. */
            stopAirPlayClient();
            mAirPlay = new WirelessAirPlayClient(context);
            applyMediaOverrides(mAirPlay);
            client = mAirPlay;
            mAirPlayAttemptInProgress = true;
        }
        logRetry("attempt " + attempt + " — new wireless-iAP link-up, reusing the resolved "
                + "receiver " + target + ":" + port);
        final Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    /* The address is known, so only let the fresh receiver start listening. */
                    Thread.sleep(DIRECT_RETRY_SETTLE_MS);
                    if (!mRunning || mAirPlay != client) {
                        logRetry("attempt " + attempt + " — session stopped before AirPlay");
                        return;
                    }
                    final boolean ok = client.run(target, port);
                    mAirPlayReady = ok;
                    if (ok) {
                        setIdleWatchdog(false);
                        monitorAirPlayLiveness(client);
                    }
                    mStatus = ok ? "AirPlay channels ready (" + target + ":" + port + ")"
                            : "AirPlay handshake failed — see log";
                    if (!ok) {
                        refreshIdleStatus("the AirPlay handshake failed — see log");
                    }
                    logRetry("attempt " + attempt + " — " + mStatus);
                    if (!ok) {
                        mLastError = mStatus;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    synchronized (mLock) {
                        mAirPlayAttemptInProgress = false;
                    }
                }
            }
        }, "okcar-wireless-airplay-retry");
        thread.setDaemon(true);
        thread.start();
    }

    /** The receiver address and, for the log, where it came from. */
    private static final class ReceiverAddress {
        final String address;
        final String source;
        final String gateway;

        ReceiverAddress(String address, String source, String gateway) {
            this.address = address;
            this.source = source;
            this.gateway = gateway;
        }
    }

    /**
     * Derive the accessory's AirPlay address after the Wi-Fi join, never hard-coded:
     * an mDNS resolve on the joined network wins, otherwise the joined network's
     * default-route gateway (the accessory is the AP), otherwise the known constant.
     */
    /**
     * Real controller BT address for a normal-phone build: Settings first, then
     * the root shell (the KernelSU backends). Empty when neither answers.
     */
    private static String readRealBluetoothAddress(Context context) {
        try {
            final String value = android.provider.Settings.Secure.getString(
                    context.getContentResolver(), "bluetooth_address");
            if (value != null && value.contains(":")) {
                return value.trim();
            }
        } catch (RuntimeException ignored) {
            // fall through to the root shell
        }
        try {
            final Process process = Runtime.getRuntime().exec(
                    new String[]{"su", "-c", "settings get secure bluetooth_address"});
            final java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(process.getInputStream()));
            final String line = reader.readLine();
            process.waitFor();
            if (line != null && line.contains(":")) {
                return line.trim();
            }
        } catch (Exception ignored) {
            // leave masked
        }
        return "";
    }

    private ReceiverAddress resolveReceiverAddress(Context context) {
        final String gateway = readDefaultGateway(context);
        /* The gateway is known the moment DHCP completes, and on an accessory-hosted AP it *is*
         * the accessory.  There is deliberately no mDNS browse here: measuring one cost a blocked
         * browse (~45 s) that pushed our first connect past the accessory's own receiver window
         * (2026-09-28: the car listened 22:22:27–22:23:23, we rang at 22:23:25.96, ECONNREFUSED). */
        if (gateway != null) {
            return new ReceiverAddress(gateway, "default-gateway", gateway);
        }
        /* No hard-coded receiver address any more: on an accessory-hosted AP the gateway *is* the
         * accessory, and a stale constant from someone's lab network only ever produced a connect
         * to a machine that is not there.  Failing to resolve means the direct path cannot start,
         * and it says so. */
        logDirect("no default route — cannot tell where the receiver is");
        return null;
    }

    /**
     * The default-route gateway of the network we are on.  After joining the field AP the Wi-Fi
     * network's gateway <em>is</em> the accessory; a Wi-Fi network is preferred so a
     * still-present LAN route cannot win the race.
     */
    private String readDefaultGateway(Context context) {
        try {
            final ConnectivityManager cm = (ConnectivityManager)
                    context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) {
                logDirect("no ConnectivityManager — cannot read the default route");
                return null;
            }
            String other = null;
            for (Network network : cm.getAllNetworks()) {
                final LinkProperties properties = cm.getLinkProperties(network);
                if (properties == null) {
                    continue;
                }
                final NetworkCapabilities capabilities = cm.getNetworkCapabilities(network);
                final boolean wifi = capabilities != null
                        && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
                for (RouteInfo route : properties.getRoutes()) {
                    if (!route.isDefaultRoute()) {
                        continue;
                    }
                    final InetAddress gateway = route.getGateway();
                    if (gateway == null || gateway.isAnyLocalAddress()
                            || !(gateway instanceof Inet4Address)) {
                        continue;
                    }
                    final String text = gateway.getHostAddress();
                    logDirect("default route on " + properties.getInterfaceName()
                            + (wifi ? " (wifi)" : "") + " gateway=" + text);
                    if (wifi) {
                        return text;
                    }
                    if (other == null) {
                        other = text;
                    }
                }
            }
            return other;
        } catch (Throwable t) {
            logDirect("reading the default route failed: " + t);
            return null;
        }
    }

    /**
     * The bounded failure window of the direct bootstrap.  Joining the accessory's hotspot takes
     * the phone off its own LAN (and drops Wi-Fi adb), so a <em>failing</em> attempt must revert
     * itself: while the AirPlay handshake has not succeeded, after {@link #DIRECT_WATCHDOG_MS} the
     * session runs its normal {@link #stop()} — releasing the Wi-Fi network and restoring the saved
     * iAP2 switches.
     *
     * <p>Once the AirPlay session is actually up ({@link #mAirPlayReady}) the watchdog disarms
     * itself and never calls {@link #stop()}: a verified working session (video confirmed on the
     * head unit) must not be torn down by a timer.  It re-checks the flag every
     * {@link #DIRECT_WATCHDOG_POLL_MS}, so a handshake that completes a moment after arming also
     * disarms it.  The Wi-Fi revert path is therefore reached only in the failure case, exactly as
     * before.
     */
    private void armDirectWatchdog() {
        final Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                final long armed = SystemClock.elapsedRealtime();
                logDirect("WATCHDOG armed — reverts the direct bootstrap in "
                        + DIRECT_WATCHDOG_MS + "ms unless the AirPlay session comes up");
                long waited = 0;
                while (waited < DIRECT_WATCHDOG_MS) {
                    try {
                        Thread.sleep(DIRECT_WATCHDOG_POLL_MS);
                    } catch (InterruptedException e) {
                        logDirect("watchdog cancelled by a normal stop()");
                        return;
                    }
                    waited = SystemClock.elapsedRealtime() - armed;
                    if (mAirPlayReady) {
                        /* The session is live: a timer must never stop it.  Disarming (rather than
                         * keeping a long safety net) is deliberate — nothing in this process can
                         * tell a live AirPlay session from a silently dead one, so any further
                         * firing would risk tearing down a working session, which is forbidden.
                         * The ordinary stop() paths (user, service shutdown, link loss handled
                         * elsewhere) stay in charge from here. */
                        logDirect("watchdog disarmed — AirPlay session is up after " + waited
                                + "ms; the watchdog will not stop it");
                        /* Hand the slot back.  Returning without clearing it left a finished thread
                         * in mWatchdogThread, and every later "arm one if there is none" check then
                         * saw a watchdog that was no longer running (measured 2026-10-02: the
                         * accessory vanished after a good session, the recovery re-offered its
                         * hotspot, and no deadline ever came back — 217 sat on the dead accessory
                         * network until it was reverted by hand). */
                        releaseWatchdogSlot();
                        return;
                    }
                }
                logDirect("WATCHDOG FIRED after " + waited
                        + "ms — AirPlay never came up, forcing the normal stop()/revert now");
                stop();
                logDirect("watchdog revert complete (status=" + mStatus + ")");
                /* stop() also closes the RFCOMM listener; but the phone-side session's job is to be "always discoverable", so the listener must be
                 * re-armed after the fallback revert. Previously this only called stop(), so while the phone was idle (the accessory long gone,
                 * nobody connecting) the listener was closed permanently and the status sat at stopped; the head unit could never find it again
                 * until a manual restart — field 2026-10-03 22:35: the watchdog was armed at 22:32:50 and fired 180 seconds later. */
                if (mKeepListening && mLastContext != null) {
                    logDirect("re-arming the RFCOMM listener after the watchdog revert");
                    start(mLastContext, mLastDeviceId);
                    logDirect("re-arm result: " + mStatus);
                }
            }
        }, "okcar-wireless-direct-watchdog");
        thread.setDaemon(true);
        mWatchdogThread = thread;
        thread.start();
    }

    /**
     * Marks this watchdog as no longer running, so a later recovery can arm a fresh one.
     *
     * <p>Only clears the slot if it still holds the calling thread: a newer watchdog that was armed
     * in the meantime must not be forgotten by an older one on its way out.
     */
    private void releaseWatchdogSlot() {
        synchronized (mLock) {
            if (mWatchdogThread == Thread.currentThread()) {
                mWatchdogThread = null;
            }
        }
    }

    /** Claims the single Wi-Fi join for this session; false when stopped or already claimed. */
    private boolean claimJoin() {
        synchronized (mLock) {
            if (!mRunning || mJoinStarted) {
                return false;
            }
            mJoinStarted = true;
            return true;
        }
    }

    /** A failed join must not block a later 0x4301 retransmit from trying. */
    private void releaseJoinClaim() {
        synchronized (mLock) {
            mJoinStarted = false;
        }
    }

    /** Leaves the direct-bootstrap window, whatever the fallback join did. */
    private void finishDirectJoin() {
        synchronized (mLock) {
            mDirectJoinInFlight = false;
        }
    }

    /**
     * Resumes the accessory's own start session that was queued while {@code joined} was being
     * joined.  Returns true when it has taken over, in which case the caller must not continue
     * with the fallback session.  A queued session that asks for exactly the network that was
     * just joined authoritatively is consumed and dropped — the join already happened.
     */
    /** Takes (and clears) the accessory session queued while another join was in flight. */
    private WirelessMessages.StartSession takePendingAuthoritative() {
        synchronized (mLock) {
            final WirelessMessages.StartSession pending = mPendingAuthoritative;
            mPendingAuthoritative = null;
            return pending;
        }
    }

    private boolean resumePendingAuthoritative(final WirelessMessages.StartSession joined,
                                               final boolean direct) {
        final WirelessMessages.StartSession pending;
        synchronized (mLock) {
            pending = mPendingAuthoritative;
            mPendingAuthoritative = null;
        }
        if (pending == null || pending.ssid == null || pending.ssid.isEmpty()) {
            return false;
        }
        if (!direct && pending.ssid.equals(joined.ssid)) {
            return false;               /* same join, nothing to redo */
        }
        releaseJoinClaim();
        if (!claimJoin()) {
            return false;               /* stopped, or a third caller got there first */
        }
        logDirect("the accessory asked for " + pending.ssid + " while " + joined.ssid
                + " was being joined — abandoning that fallback and joining the accessory's own network");
        mStatus = "joining " + pending.ssid + " (the accessory's own session)";
        beginSession(pending, false);
        return true;
    }

    private void cancelGraceTimer() {
        final Thread grace = mGraceThread;
        mGraceThread = null;
        if (grace != null && grace != Thread.currentThread()) {
            grace.interrupt();
        }
    }

    private static void logDirect(String message) {
        LinkState.get().log("wireless", "direct-bootstrap: " + message);
    }

    /** Retry lines stay greppable next to the direct-bootstrap ones. */
    private static void logRetry(String message) {
        LinkState.get().log("wireless", "airplay-retry: " + message);
    }

    private void stopAirPlayClient() {
        final WirelessAirPlayClient client = mAirPlay;
        mAirPlay = null;
        if (client != null) {
            client.stop();
        }
    }

        @Override
    public void onWirelessFailure(String reason) {
        mLastError = reason;
        LinkState.get().log("wireless", "failure: " + reason);
    }
}
