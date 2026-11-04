/*
 * OkcarOS link stack — L3/L5 bridge: what to do with messages that arrive on the
 * iAP2 link.
 *
 * This is the piece §12.18 A / §12.19 C asked for: the head unit sends its
 * touchscreen and button events over iAP2 HID messages (0x6800 startHID,
 * 0x6802 HIDReport, 0x6803 stopHID) — there is no HID interface on the bus at
 * all — so the replacement has to receive them, decode them and inject them into
 * the local input system.
 *
 * Authentication messages (0xAA00..0xAA05) are logged but not verified: §12.19
 * established that the sender/device side does not verify signatures (the
 * closed-source app has no verification primitives, and the phone has no MFi
 * coprocessor while the whole chain still works).
 *
 * Button *encodings* (media 0x4D / phone 0x50 payloads) are not yet known, so
 * those reports are logged but not injected — injecting guessed key codes would
 * be worse than doing nothing.  Touch is known exactly and is injected.
 */

package com.okcar.link.iap2;

import android.util.Log;

import com.okcar.link.LinkState;
import com.okcar.link.hid.HidReportDecoder;
import com.okcar.link.input.InputInjector;
import com.okcar.link.util.Hex;

public final class Iap2SessionHandler implements Iap2Link.Listener {

    private static final String TAG = "OkCarLink/Iap2";

    private final LinkState mState = LinkState.get();
    private final InputInjector mInjector;
    private volatile boolean mInjectTouch = true;

    public Iap2SessionHandler(InputInjector injector) {
        mInjector = injector;
    }

    public void setInjectTouch(boolean enabled) {
        mInjectTouch = enabled;
        mState.log("iap2", "touch injection " + (enabled ? "enabled" : "disabled"));
    }

    public boolean isInjectTouchEnabled() {
        return mInjectTouch;
    }

    @Override
    public void onLinkUp() {
        mState.log("iap2", "link up — iAP2 session ready");
    }

    @Override
    public void onLinkDown(String reason) {
        mState.log("iap2", "link down: " + reason);
    }

    @Override
    public void onFrame(Iap2Frame frame) {
        /* Frame-level detail is already logged by Iap2Link; nothing to add. */
    }

    @Override
    public void onMessage(int sessionId, int messageId, byte[] payload) {
        switch (messageId) {
            case Iap2Message.MSG_START_HID:
                mState.log("iap2", "startHID (session " + sessionId + ")");
                break;

            case Iap2Message.MSG_HID_REPORT:
                onHidReport(payload);
                break;

            case Iap2Message.MSG_STOP_HID:
                mState.log("iap2", "stopHID (session " + sessionId + ")");
                break;

            case Iap2Message.MSG_AUTH_CERTIFICATE_REQUEST:
            case Iap2Message.MSG_AUTH_CERTIFICATE_RESPONSE:
            case Iap2Message.MSG_AUTH_SIGNATURE_REQUEST:
            case Iap2Message.MSG_AUTH_FAILED:
            case Iap2Message.MSG_AUTH_SUCCEEDED:
                // No verification on the sender side (§12.19); carry the exchange.
                mState.log("iap2", Iap2Message.name(messageId) + " (" + payload.length + "B)");
                break;

            case Iap2Message.MSG_DEVICE_INFORMATION_UPDATE:
            case Iap2Message.MSG_DEVICE_LANGUAGE_UPDATE:
            case Iap2Message.MSG_VEHICLE_STATUS_UPDATE:
            case Iap2Message.MSG_NOW_PLAYING:
                mState.log("iap2", Iap2Message.name(messageId) + " (" + payload.length + "B)");
                break;

            default:
                mState.log("iap2", "unhandled " + Iap2Message.name(messageId)
                        + " (" + payload.length + "B) " + Hex.encode(payload, 0,
                        Math.min(16, payload.length)));
                break;
        }
    }

    private void onHidReport(byte[] payload) {
        final HidReportDecoder.Report report = HidReportDecoder.decode(payload);
        if (report == null) {
            mState.log("iap2", "HID report not understood: " + Hex.encode(payload, 0,
                    Math.min(16, payload.length)));
            return;
        }
        final boolean touch = report.type == HidReportDecoder.TYPE_TOUCH;
        mState.onHidReport(report.describe(), touch,
                report.type != HidReportDecoder.TYPE_TOUCH);

        if (!touch) {
            // Media / phone buttons: the payload shape is known ("M"/"P"), the
            // button encoding is not — log and leave injection for later.
            mState.log("iap2", "button report (not injected yet): " + report.describe());
            return;
        }
        if (!mInjectTouch || mInjector == null) {
            return;
        }
        final boolean injected = mInjector.injectTouch(report);
        if (!injected) {
            Log.w(TAG, "touch injection failed: " + report.describe());
        }
    }
}
