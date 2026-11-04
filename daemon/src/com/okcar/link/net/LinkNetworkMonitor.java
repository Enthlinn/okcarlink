/*
 * OkcarOS link stack — L2: the USB-Ethernet bearer.
 *
 * Once the phone has switched to USB HOST, the head unit enumerates as an NCM
 * device and the kernel brings up usb0:
 *
 *   usb 1-1: New USB device found, idVendor=8087, idProduct=0a68, Product: the production head unit
 *   cdc_ncm 1-1:1.1 usb0: register 'cdc_ncm'
 *   IPv6: ADDRCONF(NETDEV_CHANGE): usb0: link becomes ready
 *
 * CarPlay then rides on usb0's IPv6 link-local address.  Two things matter for
 * the link to come up (both observed in the field, §12.17):
 *   1. the interface must exist and be UP, and
 *   2. its link-local address must have left the "tentative" state (DAD done),
 *      otherwise mDNS reports "Network was not found for interface usb0" and
 *      findLocalInetAddress() times out.
 *
 * /proc/net/if_inet6 carries the address flags, which is how we can tell
 * tentative apart from ready without any network permission.
 */

package com.okcar.link.net;

import android.util.Log;

import com.okcar.link.LinkState;
import com.okcar.link.usb.UsbModeController;

import java.io.BufferedReader;
import java.io.FileReader;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Locale;

public final class LinkNetworkMonitor {

    private static final String TAG = "OkCarLink/Net";

    public static final String IFACE = "usb0";

    /* linux/if_addr.h */
    private static final int IFA_F_DEPRECATED = 0x20;
    private static final int IFA_F_TENTATIVE = 0x40;

    private static final long POLL_INTERVAL_MS = 1000;

    private volatile boolean mRunning;
    private Thread mThread;

    /* Last published values, so we only log real transitions. */
    private boolean mLastPresent;
    private boolean mLastUp;
    private String mLastAddress = "";
    private boolean mLastTentative;

    public synchronized void start() {
        if (mRunning) {
            return;
        }
        mRunning = true;
        mThread = new Thread(new Runnable() {
            @Override
            public void run() {
                loop();
            }
        }, "okcar-net-monitor");
        mThread.setDaemon(true);
        mThread.start();
        Log.i(TAG, "network monitor started");
    }

    public synchronized void stop() {
        mRunning = false;
        final Thread thread = mThread;
        mThread = null;
        if (thread != null) {
            thread.interrupt();
        }
        Log.i(TAG, "network monitor stopped");
    }

    private void loop() {
        while (mRunning) {
            try {
                pollOnce();
            } catch (RuntimeException e) {
                Log.w(TAG, "poll failed: " + e);
            }
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void pollOnce() {
        final LinkState state = LinkState.get();

        final boolean present = interfaceExists();
        final String operstate = UsbModeController.readLine("/sys/class/net/" + IFACE + "/operstate");
        final boolean up = present && ("up".equals(operstate) || "unknown".equals(operstate));

        final Ipv6Address address = readLinkLocal();

        if (present != mLastPresent || up != mLastUp
                || !mLastAddress.equals(address.text) || mLastTentative != address.tentative) {
            mLastPresent = present;
            mLastUp = up;
            mLastAddress = address.text;
            mLastTentative = address.tentative;
            state.setUsb0(up, address.text, address.tentative);

            if (!present) {
                state.log("net", "usb0 disappeared (the head unit's NCM gadget is gone)");
            } else {
                state.log("net", "usb0 " + (up ? "up" : "down")
                        + (address.text.isEmpty() ? "" : ", link-local " + address.text
                        + (address.tentative ? " (tentative)" : " (ready)")));
            }
        }
    }

    /** True when the kernel created usb0 (i.e. cdc_ncm bound to the head unit). */
    public static boolean interfaceExists() {
        try {
            return NetworkInterface.getByName(IFACE) != null;
        } catch (Exception e) {
            return false;
        }
    }

    public static final class Ipv6Address {
        public final String text;
        public final boolean tentative;

        Ipv6Address(String text, boolean tentative) {
            this.text = text;
            this.tentative = tentative;
        }
    }

    /**
     * Returns the interface's IPv6 link-local address, preferring
     * /proc/net/if_inet6 because it also tells us whether DAD has finished.
     */
    public static Ipv6Address readLinkLocal() {
        final Ipv6Address fromProc = readLinkLocalFromProc();
        if (fromProc != null) {
            return fromProc;
        }
        return readLinkLocalFromJava();
    }

    /**
     * Port note: a normal app runs in an untrusted SELinux domain that cannot read
     * /proc/net/if_inet6, and this method is polled once a second — logging each
     * failure flooded logcat and buried every other OkCarLink event. Fail once,
     * remember it, and use the NetworkInterface path from then on.
     */
    private static volatile boolean sProcInet6Unavailable;

    private static Ipv6Address readLinkLocalFromProc() {
        if (sProcInet6Unavailable) {
            return null;
        }
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader("/proc/net/if_inet6"));
            String line;
            while ((line = reader.readLine()) != null) {
                // fe800000000000000000000000000000 02 40 20 40 usb0
                final String[] parts = line.trim().split("\\s+");
                if (parts.length < 6 || !IFACE.equals(parts[5])) {
                    continue;
                }
                final String hex = parts[0];
                if (hex.length() != 32) {
                    continue;
                }
                final int flags;
                try {
                    flags = Integer.parseInt(parts[4], 16);
                } catch (NumberFormatException e) {
                    continue;
                }
                return new Ipv6Address(formatIpv6(hex), (flags & IFA_F_TENTATIVE) != 0);
            }
        } catch (Exception e) {
            sProcInet6Unavailable = true;
            Log.w(TAG, "cannot read /proc/net/if_inet6 (using NetworkInterface instead): " + e);
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (Exception ignored) {
                    /* nothing useful to do */
                }
            }
        }
        return null;
    }

    private static Ipv6Address readLinkLocalFromJava() {
        try {
            final NetworkInterface nif = NetworkInterface.getByName(IFACE);
            if (nif == null) {
                return new Ipv6Address("", false);
            }
            final Enumeration<InetAddress> addresses = nif.getInetAddresses();
            for (final InetAddress address : Collections.list(addresses)) {
                if (address instanceof Inet6Address && address.isLinkLocalAddress()) {
                    return new Ipv6Address(stripScope(address.getHostAddress()), false);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "cannot enumerate " + IFACE + ": " + e);
        }
        return new Ipv6Address("", false);
    }

    private static String stripScope(String address) {
        final int percent = address.indexOf('%');
        return percent < 0 ? address : address.substring(0, percent);
    }

    /** Expands a 32-hex-digit address into the usual colon notation. */
    static String formatIpv6(String hex) {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 32; i += 4) {
            if (sb.length() > 0) {
                sb.append(':');
            }
            sb.append(hex.substring(i, i + 4).toLowerCase(Locale.US));
        }
        return sb.toString();
    }
}
