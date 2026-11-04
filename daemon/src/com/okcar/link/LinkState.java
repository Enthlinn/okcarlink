/*
 * OkcarOS link stack — process-wide observable state.
 *
 * Everything the service learns (USB role, usb0 link state, iAP2 session state,
 * decoded HID input) is published here so the UI, the log ring and future
 * components can observe it without any cross-process plumbing.  The object is
 * a singleton living in the OkCarLink process and is intentionally dependency
 * free.
 */

package com.okcar.link;

import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

public final class LinkState {

    /* USB roles, as understood by /dev/okcar ("usbmode,1,N"). */
    public static final int USB_MODE_UNKNOWN = -1;
    public static final int USB_MODE_NONE = 0;
    public static final int USB_MODE_DEVICE = 1;
    public static final int USB_MODE_HOST = 2;

    /* iAP2 link states, named after the link FSM vocabulary in the head-unit
     * firmware report (exeed A §5.8.1): Detached, Detect, Idle, Connected. */
    public static final int IAP2_DETACHED = 0;
    public static final int IAP2_DETECT = 1;
    public static final int IAP2_IDLE = 2;
    public static final int IAP2_CONNECTED = 3;
    public static final int IAP2_FAILED = 4;

    public interface Listener {
        void onLinkStateChanged();
    }

    public static final class Event {
        public final long atMs;
        public final String tag;
        public final String text;

        Event(long atMs, String tag, String text) {
            this.atMs = atMs;
            this.tag = tag;
            this.text = text;
        }

        public String format() {
            final String time = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date(atMs));
            return time + " [" + tag + "] " + text;
        }
    }

    private static final int MAX_EVENTS = 400;
    private static final String LOG_TAG = "OkCarLink/Event";
    private static final LinkState INSTANCE = new LinkState();

    public static LinkState get() {
        return INSTANCE;
    }

    private final CopyOnWriteArrayList<Listener> mListeners = new CopyOnWriteArrayList<Listener>();
    private final ArrayDeque<Event> mEvents = new ArrayDeque<Event>();

    private volatile boolean mServiceRunning;
    private volatile boolean mAutoClaimIap2;
    private volatile int mUsbMode = USB_MODE_UNKNOWN;
    private volatile String mUsbModeDetail = "";
    private volatile boolean mCarAttached;
    private volatile boolean mUsb0Up;
    private volatile String mUsb0LinkLocal = "";
    private volatile boolean mUsb0LinkLocalTentative;
    private volatile int mIap2State = IAP2_DETACHED;
    private volatile String mIap2Detail = "";
    private volatile long mHidReports;
    private volatile long mTouchEvents;
    private volatile long mKeyEvents;
    private volatile String mLastHid = "";
    private volatile String mLastSelfTest = "not run";
    private volatile String mLastMediaProbe = "not run";

    private LinkState() {
    }

    /* ---------------------------------------------------------------- events */

    public void log(String tag, String text) {
        final Event event = new Event(System.currentTimeMillis(), tag, text);
        synchronized (mEvents) {
            mEvents.addLast(event);
            while (mEvents.size() > MAX_EVENTS) {
                mEvents.removeFirst();
            }
        }
        // Mirror into logcat as well: without this the stack is only observable
        // from its own UI, which is useless for field debugging.
        Log.i(LOG_TAG, tag + ": " + text);
        notifyChanged();
    }

    public List<Event> events() {
        synchronized (mEvents) {
            return new ArrayList<Event>(mEvents);
        }
    }

    public void clearEvents() {
        synchronized (mEvents) {
            mEvents.clear();
        }
        notifyChanged();
    }

    /* ------------------------------------------------------------- listeners */

    public void addListener(Listener listener) {
        mListeners.addIfAbsent(listener);
    }

    public void removeListener(Listener listener) {
        mListeners.remove(listener);
    }

    private void notifyChanged() {
        for (Listener listener : mListeners) {
            try {
                listener.onLinkStateChanged();
            } catch (RuntimeException ignored) {
                /* a broken observer must not break the stack */
            }
        }
    }

    /* ---------------------------------------------------------------- fields */

    public boolean isServiceRunning() {
        return mServiceRunning;
    }

    public void setServiceRunning(boolean running) {
        mServiceRunning = running;
        notifyChanged();
    }

    public boolean isAutoClaimIap2() {
        return mAutoClaimIap2;
    }

    public void setAutoClaimIap2(boolean enabled) {
        mAutoClaimIap2 = enabled;
        notifyChanged();
    }

    public int getUsbMode() {
        return mUsbMode;
    }

    public String getUsbModeDetail() {
        return mUsbModeDetail;
    }

    public void setUsbMode(int mode, String detail) {
        mUsbMode = mode;
        mUsbModeDetail = detail == null ? "" : detail;
        notifyChanged();
    }

    public boolean isCarAttached() {
        return mCarAttached;
    }

    public void setCarAttached(boolean attached) {
        mCarAttached = attached;
        notifyChanged();
    }

    public boolean isUsb0Up() {
        return mUsb0Up;
    }

    public String getUsb0LinkLocal() {
        return mUsb0LinkLocal;
    }

    public boolean isUsb0LinkLocalTentative() {
        return mUsb0LinkLocalTentative;
    }

    public void setUsb0(boolean up, String linkLocal, boolean tentative) {
        final String address = linkLocal == null ? "" : linkLocal;
        if (mUsb0Up == up && mUsb0LinkLocal.equals(address)
                && mUsb0LinkLocalTentative == tentative) {
            return;
        }
        mUsb0Up = up;
        mUsb0LinkLocal = address;
        mUsb0LinkLocalTentative = tentative;
        notifyChanged();
    }

    public int getIap2State() {
        return mIap2State;
    }

    public String getIap2Detail() {
        return mIap2Detail;
    }

    public void setIap2State(int state, String detail) {
        mIap2State = state;
        mIap2Detail = detail == null ? "" : detail;
        notifyChanged();
    }

    public long getHidReports() {
        return mHidReports;
    }

    public long getTouchEvents() {
        return mTouchEvents;
    }

    public long getKeyEvents() {
        return mKeyEvents;
    }

    public String getLastHid() {
        return mLastHid;
    }

    public void onHidReport(String description, boolean touch, boolean key) {
        mHidReports++;
        if (touch) {
            mTouchEvents++;
        }
        if (key) {
            mKeyEvents++;
        }
        mLastHid = description;
        notifyChanged();
    }

    public String getLastSelfTest() {
        return mLastSelfTest;
    }

    public String getLastMediaProbe() {
        return mLastMediaProbe;
    }

    public void setLastMediaProbe(String report) {
        mLastMediaProbe = report;
        notifyChanged();
    }

    public void setLastSelfTest(String report) {
        mLastSelfTest = report;
        notifyChanged();
    }

    /* -------------------------------------------------------------- reporting */

    public static String usbModeName(int mode) {
        switch (mode) {
            case USB_MODE_NONE:
                return "none";
            case USB_MODE_DEVICE:
                return "device";
            case USB_MODE_HOST:
                return "host";
            default:
                return "unknown";
        }
    }

    public static String iap2StateName(int state) {
        switch (state) {
            case IAP2_DETECT:
                return "detect";
            case IAP2_IDLE:
                return "idle";
            case IAP2_CONNECTED:
                return "connected";
            case IAP2_FAILED:
                return "failed";
            default:
                return "detached";
        }
    }

    /** Human-readable summary of everything the service knows, for the UI. */
    public String summary() {
        final StringBuilder sb = new StringBuilder();
        sb.append("service: ").append(mServiceRunning ? "running" : "stopped").append('\n');
        sb.append("usb role: ").append(usbModeName(mUsbMode));
        if (!mUsbModeDetail.isEmpty()) {
            sb.append("  (").append(mUsbModeDetail).append(')');
        }
        sb.append('\n');
        sb.append("car attached (PC_ATTACHED): ").append(mCarAttached ? "yes" : "no").append('\n');
        sb.append("usb0: ").append(mUsb0Up ? "up" : "down");
        if (!mUsb0LinkLocal.isEmpty()) {
            sb.append("  ").append(mUsb0LinkLocal);
            sb.append(mUsb0LinkLocalTentative ? " (tentative)" : " (ready)");
        }
        sb.append('\n');
        sb.append("iAP2 auto-takeover: ").append(mAutoClaimIap2 ? "ON" : "off").append('\n');
        sb.append("iAP2: ").append(iap2StateName(mIap2State));
        if (!mIap2Detail.isEmpty()) {
            sb.append("  ").append(mIap2Detail);
        }
        sb.append('\n');
        sb.append("HID reports: ").append(mHidReports)
                .append("  touch: ").append(mTouchEvents)
                .append("  keys: ").append(mKeyEvents).append('\n');
        if (!mLastHid.isEmpty()) {
            sb.append("last HID: ").append(mLastHid).append('\n');
        }
        sb.append("self test: ").append(mLastSelfTest).append('\n');
        sb.append("media probe: ").append(mLastMediaProbe).append('\n');
        return sb.toString();
    }
}
