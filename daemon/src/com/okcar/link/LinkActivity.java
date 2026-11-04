/*
 * OkcarOS link stack — operator UI.
 *
 * No resources on purpose (like OkCarCallTest): the whole screen is built in
 * code, which keeps this module buildable without any resource merging and makes
 * it easy to drive from adb:
 *
 *   adb shell am start -n com.okcar.link/.LinkActivity
 *   adb shell am start -n com.okcar.link/.LinkActivity --es action selfTest
 *   adb shell am start -n com.okcar.link/.LinkActivity --es action enterHost
 *   adb shell am start -n com.okcar.link/.LinkActivity --es action backToDevice
 *
 * Wireless CarPlay (our phone is the device, the head unit the accessory, §12.38):
 *   adb shell am start -n com.okcar.link/.LinkActivity --es action wirelessSelfTest
 *   adb shell am start -n com.okcar.link/.LinkActivity --es action wirelessStart
 *   adb shell am start -n com.okcar.link/.LinkActivity --es action wirelessStatus
 *   adb shell am start -n com.okcar.link/.LinkActivity --es action wirelessStop
 *
 * Wi-Fi revert self-test (joins the accessory AP, then proves the revert; drops
 * Wi-Fi adb for ~30-45 s on purpose):
 *   adb shell am start -n com.okcar.link/.LinkActivity --es action wifiRevertSelfTest --ei holdSeconds 25
 */

package com.okcar.link;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.okcar.link.usb.UsbModeController;
import com.okcar.link.airplay.CryptoProbe;
import com.okcar.link.wireless.WirelessCarPlaySession;
import com.okcar.link.wireless.WirelessSelfTest;
import com.okcar.link.wireless.WifiGroupJoiner;

import java.util.List;

public final class LinkActivity extends Activity implements LinkState.Listener {

    private static final String TAG = "OkCarLink/Activity";
    private static final long REFRESH_INTERVAL_MS = 1000;
    private static final int LOG_LINES = 40;

    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private TextView mStatusView;
    private TextView mLogView;

    private final Runnable mRefresh = new Runnable() {
        @Override
        public void run() {
            refresh();
            mHandler.postDelayed(this, REFRESH_INTERVAL_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());

        /* The lab pins must be in place before the service starts: the accessory may dial us
         * while the autostart listener is coming up, and that session creates its media client
         * from whatever this process already holds — a pin that only arrives with a later
         * `wirelessStart` intent is read too late. */
        applyMediaExtras(getIntent());
        OkCarLinkService.start(this);
        LinkState.get().addListener(this);
        /* What the *next* consent dialog asks for: whole screen, or one app the user picks. */
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        mHandler.removeCallbacks(mRefresh);
        mHandler.post(mRefresh);
    }

    @Override
    protected void onPause() {
        mHandler.removeCallbacks(mRefresh);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        LinkState.get().removeListener(this);
        super.onDestroy();
    }

    @Override
    public void onLinkStateChanged() {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                refresh();
            }
        });
    }

    private void handleIntent(Intent intent) {
        final String action = intent == null ? null : intent.getStringExtra("action");
        if (action == null) {
            return;
        }
        if ("selfTest".equals(action)) {
            OkCarLinkService.sendAction(this, OkCarLinkService.ACTION_SELF_TEST);
        } else if ("usbTopology".equals(action)) {
            OkCarLinkService.sendAction(this, OkCarLinkService.ACTION_USB_TOPOLOGY);
        } else if ("forceClaimIap2".equals(action)) {
            OkCarLinkService.sendAction(this, OkCarLinkService.ACTION_FORCE_CLAIM_IAP2);
        } else if ("claimIap2".equals(action)) {
            OkCarLinkService.sendAction(this, OkCarLinkService.ACTION_CLAIM_IAP2);
        } else if ("autoClaimOn".equals(action) || "autoClaimOff".equals(action)) {
            OkCarLinkService.sendAction(this, OkCarLinkService.ACTION_AUTO_CLAIM_IAP2,
                    OkCarLinkService.EXTRA_AUTO_CLAIM, "autoClaimOn".equals(action));
        } else if ("sessionStart".equals(action)) {
            /* Forward the target the caller passed: without these extras the service
             * has no host and answers "no target" (measured on 2026-09-25). */
            final Intent sessionExtras = new Intent();
            sessionExtras.putExtra(OkCarLinkService.EXTRA_SESSION_HOST,
                    getIntent().getStringExtra("sessionHost"));
            sessionExtras.putExtra(OkCarLinkService.EXTRA_SESSION_PORT,
                    getIntent().getIntExtra("sessionPort", 0));
            sessionExtras.putExtra(OkCarLinkService.EXTRA_SESSION_ENCRYPTION,
                    getIntent().getBooleanExtra("sessionEncryption", false));
            sessionExtras.putExtra(OkCarLinkService.EXTRA_SESSION_MEDIA,
                    getIntent().getBooleanExtra("sessionMedia", true));
            sessionExtras.putExtra(OkCarLinkService.EXTRA_SESSION_AUDIO,
                    getIntent().getBooleanExtra("sessionAudio", false));
            sessionExtras.putExtra(OkCarLinkService.EXTRA_DEVICE_SENDS_SYN,
                    getIntent().getBooleanExtra("deviceSendsSyn", true));
            sessionExtras.putExtra(OkCarLinkService.EXTRA_SYN_ACK_ECHO_PARAMS,
                    getIntent().getBooleanExtra("synAckEchoParams", true));
            sessionExtras.putExtra(OkCarLinkService.EXTRA_ACK_NEXT_EXPECTED,
                    getIntent().getBooleanExtra("ackNextExpected", true));
            sessionExtras.putExtra(OkCarLinkService.EXTRA_SESSION_CHALLENGE,
                    getIntent().getStringExtra("sessionChallenge"));
            OkCarLinkService.sendAction(this, OkCarLinkService.ACTION_SESSION_START, sessionExtras);
        } else if ("sessionStop".equals(action)) {
            OkCarLinkService.sendAction(this, OkCarLinkService.ACTION_SESSION_STOP);
        } else if ("mediaProbe".equals(action)) {
            OkCarLinkService.sendAction(this, OkCarLinkService.ACTION_MEDIA_PROBE);
        } else if ("probe".equals(action)) {
            OkCarLinkService.sendAction(this, OkCarLinkService.ACTION_SEND_PROBE);
        } else if ("btProbe".equals(action)) {
            OkCarLinkService.sendAction(this, OkCarLinkService.ACTION_BT_PROBE);
        } else if ("fileProbe".equals(action)) {
            OkCarLinkService.sendAction(this, OkCarLinkService.ACTION_FILE_PROBE);
        } else if ("releaseIap2".equals(action)) {
            OkCarLinkService.sendAction(this, OkCarLinkService.ACTION_RELEASE_IAP2);
        } else if ("enterHost".equals(action)) {
            OkCarLinkService.sendAction(this, OkCarLinkService.ACTION_ENTER_HOST);
        } else if ("backToDevice".equals(action)) {
            OkCarLinkService.sendAction(this, OkCarLinkService.ACTION_BACK_TO_DEVICE);
        } else if ("captureProbe".equals(action)) {
            OkCarLinkService.sendAction(this, OkCarLinkService.ACTION_CAPTURE_PROBE);
        } else if ("toneOn".equals(action)) {
            OkCarLinkService.sendAction(this, OkCarLinkService.ACTION_TONE_ON);
        } else if ("toneOff".equals(action)) {
            OkCarLinkService.sendAction(this, OkCarLinkService.ACTION_TONE_OFF);
        } else if ("wirelessStart".equals(action)) {
            startWireless(getIntent().getStringExtra("deviceId"));
        } else if ("connectAccessory".equals(action)) {
            /* Device-initiated start (the production head unit): dial the accessory's own iAP2 service, where an
             * the production head unit head unit writes its Wi-Fi credentials as A5 5A frames. */
            final String accessory = getIntent().getStringExtra("mac");
            final boolean dialling = WirelessCarPlaySession.get().connectToAccessory(accessory);
            logWireless("connectAccessory " + accessory + ": "
                    + (dialling ? "dialling" : "not started"));
        } else if ("wirelessStop".equals(action)) {
            WirelessCarPlaySession.get().stop();
            logWireless("stopped");
        } else if ("wirelessStatus".equals(action)) {
            logWireless(WirelessCarPlaySession.get().status());
        } else if ("wirelessSelfTest".equals(action)) {
            wirelessSelfTest();
        } else if ("wifiRevertSelfTest".equals(action)) {
            /* Bounded revert self-test: hold is optional, default 25 s. */
            final int hold = getIntent().getIntExtra("holdSeconds",
                    WifiGroupJoiner.SELF_TEST_DEFAULT_HOLD_SECONDS);
            final Intent testExtras = new Intent();
            testExtras.putExtra(OkCarLinkService.EXTRA_WIFI_REVERT_HOLD_SECONDS, hold);
            OkCarLinkService.sendAction(this, OkCarLinkService.ACTION_WIFI_REVERT_SELF_TEST,
                    testExtras);
        } else if ("wifiRevert".equals(action)) {
            OkCarLinkService.sendAction(this, OkCarLinkService.ACTION_WIFI_REVERT);
        } else if ("cryptoProbe".equals(action)) {
            final String report = CryptoProbe.run();
            for (String line : report.split("\n")) {
                Log.i(TAG, "crypto probe: " + line);
            }
        }
    }

    /* ------------------------------------------------- wireless CarPlay (§12.38) */

    private void startWireless(String deviceId) {
        applyMediaExtras(getIntent());
        final boolean started = WirelessCarPlaySession.get().start(this, deviceId);
        logWireless((started ? "started: " : "NOT started: ")
                + WirelessCarPlaySession.get().status());
    }

    /**
     * `--es videoCodec auto|h264|hevc` and `--es videoSize 2400x1080` pin the media face for one
     * test run.  The car app owns both choices (Fruit Link's own settings drive what it advertises),
     * so these stay unset for product runs and are only used to isolate a variable — e.g. forcing
     * HEVC when the accessory's /info body arrives truncated and advertises nothing.
     */
    private void applyMediaExtras(Intent intent) {
        if (intent == null) {
            return;
        }
        final String codec = intent.getStringExtra("videoCodec");
        if (codec != null) {
            final com.okcar.link.wireless.AccessoryMemory.Codec pin =
                    "hevc".equalsIgnoreCase(codec) || "h265".equalsIgnoreCase(codec)
                            ? com.okcar.link.wireless.AccessoryMemory.Codec.HEVC
                            : "h264".equalsIgnoreCase(codec) || "avc".equalsIgnoreCase(codec)
                                    ? com.okcar.link.wireless.AccessoryMemory.Codec.H264
                                    : com.okcar.link.wireless.AccessoryMemory.Codec.AUTO;
            logWireless(WirelessCarPlaySession.get().pinVideoCodec(pin));
        }
        final String size = intent.getStringExtra("videoSize");
        if (size != null) {
            if ("auto".equalsIgnoreCase(size.trim())) {
                logWireless(WirelessCarPlaySession.get().clearPinnedVideoSize());
            } else {
                final int[] parsed = parseSize(size);
                if (parsed != null) {
                    logWireless(WirelessCarPlaySession.get().pinVideoSize(parsed[0], parsed[1]));
                } else {
                    logWireless("videoSize not understood: " + size + " (want WxH or auto)");
                }
            }
        }
        final String encoder = intent.getStringExtra("videoEncoder");
        if (encoder != null) {
            WirelessCarPlaySession.get().setVideoEncoderName(
                    "soft".equalsIgnoreCase(encoder) ? "c2.android.hevc.encoder" : encoder);
            logWireless("video encoder pinned to " + encoder);
        }
        if (intent.hasExtra("rawVideoConfig")) {
            final boolean raw = intent.getBooleanExtra("rawVideoConfig", true);
            WirelessCarPlaySession.get().setRawVideoConfig(raw);
            logWireless("video config record: " + (raw ? "bare record" : "sample entry"));
        }
        final String dump = intent.getStringExtra("videoDump");
        if (dump != null) {
            WirelessCarPlaySession.get().setVideoPlainDump(dump);
            logWireless("video wire dump -> " + dump);
        }
    }

    /** @return {width, height} for a `WxH` extra, or null when it is malformed. */
    private static int[] parseSize(String text) {
        final int sep = text.indexOf('x');
        if (sep <= 0) {
            return null;
        }
        try {
            final int width = Integer.parseInt(text.substring(0, sep).trim());
            final int height = Integer.parseInt(text.substring(sep + 1).trim());
            return width > 0 && height > 0 ? new int[] {width & ~1, height & ~1} : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void wirelessSelfTest() {
        final WirelessSelfTest.Result result = WirelessSelfTest.run();
        Log.i(TAG, "wireless self test: " + result.passed + " passed, "
                + result.failed + " failed");
        for (String line : result.report.toString().split("\n")) {
            Log.i(TAG, "wireless self test: " + line);
        }
        logWireless("self test: " + result.passed + " passed, " + result.failed + " failed");
    }

    private void logWireless(String text) {
        LinkState.get().log("wireless", text);
        Log.i(TAG, "wireless: " + text);
    }

    private void refresh() {
        final LinkState state = LinkState.get();
        mStatusView.setText(state.summary());

        final List<LinkState.Event> events = state.events();
        final StringBuilder sb = new StringBuilder();
        final int from = Math.max(0, events.size() - LOG_LINES);
        for (int i = from; i < events.size(); i++) {
            sb.append(events.get(i).format()).append('\n');
        }
        mLogView.setText(sb.toString());
    }

    /* -------------------------------------------------------------------- UI */

    private View buildUi() {
        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        final int pad = dp(12);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(Color.WHITE);

        root.addView(title("OkCar Link (a skeleton replacing com.okcar.autoconn)"));

        mStatusView = new TextView(this);
        mStatusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        mStatusView.setTypeface(Typeface.MONOSPACE);
        mStatusView.setTextColor(Color.BLACK);
        mStatusView.setPadding(0, dp(8), 0, dp(8));
        root.addView(mStatusView);

        // Row 1 — roles
        root.addView(buttonRow(
                button("Read role", new Runnable() {
                    @Override
                    public void run() {
                        OkCarLinkService.sendAction(LinkActivity.this,
                                OkCarLinkService.ACTION_REFRESH_ROLE);
                    }
                }),
                button("Enter link (Host)", new Runnable() {
                    @Override
                    public void run() {
                        OkCarLinkService.sendAction(LinkActivity.this,
                                OkCarLinkService.ACTION_ENTER_HOST);
                    }
                })));

        // Row 2 — back to device / self test
        root.addView(buttonRow(
                button("Back to device (Device)", new Runnable() {
                    @Override
                    public void run() {
                        OkCarLinkService.sendAction(LinkActivity.this,
                                OkCarLinkService.ACTION_BACK_TO_DEVICE);
                    }
                }),
                button("Protocol self-test", new Runnable() {
                    @Override
                    public void run() {
                        OkCarLinkService.sendAction(LinkActivity.this,
                                OkCarLinkService.ACTION_SELF_TEST);
                    }
                })));

        // Row 3 — L4 media probe (no car needed) / USB topology
        root.addView(buttonRow(
                button("Media self-test (10s)", new Runnable() {
                    @Override
                    public void run() {
                        OkCarLinkService.sendAction(LinkActivity.this,
                                OkCarLinkService.ACTION_MEDIA_PROBE);
                    }
                }),
                button("USB topology", new Runnable() {
                    @Override
                    public void run() {
                        OkCarLinkService.sendAction(LinkActivity.this,
                                OkCarLinkService.ACTION_USB_TOPOLOGY);
                    }
                })));

        // Row 4 — iAP2 poke / service
        root.addView(buttonRow(
                button("Probe DETECT/SYN", new Runnable() {
                    @Override
                    public void run() {
                        OkCarLinkService.sendAction(LinkActivity.this,
                                OkCarLinkService.ACTION_SEND_PROBE);
                    }
                }),
                button("Start/wake service", new Runnable() {
                    @Override
                    public void run() {
                        OkCarLinkService.start(LinkActivity.this);
                    }
                })));

        // Row 5 — iAP2 takeover toggles
        root.addView(buttonRow(
                button("Auto iAP2 takeover on/off", new Runnable() {
                    @Override
                    public void run() {
                        final boolean enable = !LinkState.get().isAutoClaimIap2();
                        OkCarLinkService.sendAction(LinkActivity.this,
                                OkCarLinkService.ACTION_AUTO_CLAIM_IAP2,
                                OkCarLinkService.EXTRA_AUTO_CLAIM, enable);
                    }
                }),
                button("Claim iAP2 (no force)", new Runnable() {
                    @Override
                    public void run() {
                        OkCarLinkService.sendAction(LinkActivity.this,
                                OkCarLinkService.ACTION_CLAIM_IAP2);
                    }
                })));

        // Row 6 — force takeover (kills the baseline) / release
        root.addView(buttonRow(
                button("Force takeover (kick out the baseline)", new Runnable() {
                    @Override
                    public void run() {
                        OkCarLinkService.sendAction(LinkActivity.this,
                                OkCarLinkService.ACTION_FORCE_CLAIM_IAP2);
                    }
                }),
                button("Release iAP2 / auto off", new Runnable() {
                    @Override
                    public void run() {
                        OkCarLinkService.sendAction(LinkActivity.this,
                                OkCarLinkService.ACTION_AUTO_CLAIM_IAP2,
                                OkCarLinkService.EXTRA_AUTO_CLAIM, false);
                        OkCarLinkService.sendAction(LinkActivity.this,
                                OkCarLinkService.ACTION_RELEASE_IAP2);
                    }
                })));

        // Row 8 — L4 session walk (uses the last mDNS-resolved AirPlay endpoint)
        root.addView(buttonRow(
                button("Open session (info→RECORD)", new Runnable() {
                    @Override
                    public void run() {
                        OkCarLinkService.sendAction(LinkActivity.this,
                                OkCarLinkService.ACTION_SESSION_START);
                    }
                }),
                button("Stop session (TEARDOWN)", new Runnable() {
                    @Override
                    public void run() {
                        OkCarLinkService.sendAction(LinkActivity.this,
                                OkCarLinkService.ACTION_SESSION_STOP);
                    }
                })));

        // Row 9 — advertise the reverse-control service / log
        root.addView(buttonRow(
                button("Clear log", new Runnable() {
                    @Override
                    public void run() {
                        LinkState.get().clearEvents();
                        refresh();
                    }
                }),
                button("Dump output path", new Runnable() {
                    @Override
                    public void run() {
                        LinkState.get().log("media", "dump dir: "
                                + LinkActivity.this.getExternalFilesDir(null));
                    }
                })));

        root.addView(title("Events (" + UsbModeController.DEVICE_NODE + " -> "
                + (UsbModeController.nodeExists() ? "present" : "missing") + ")"));

        mLogView = new TextView(this);
        mLogView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        mLogView.setTypeface(Typeface.MONOSPACE);
        mLogView.setTextColor(Color.DKGRAY);

        final ScrollView scroll = new ScrollView(this);
        scroll.addView(mLogView, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        return root;
    }

    private TextView title(String text) {
        final TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setTextColor(Color.BLACK);
        view.setPadding(0, dp(8), 0, dp(4));
        return view;
    }

    private View buttonRow(Button left, Button right) {
        final LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(left, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(right, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    private Button button(String text, final Runnable action) {
        final Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                action.run();
            }
        });
        return button;
    }


    private int dp(int value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }
}
