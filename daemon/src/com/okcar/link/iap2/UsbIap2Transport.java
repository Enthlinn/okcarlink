/*
 * OkcarOS link stack — the missing L3 piece: iAP2 over the head unit's USB
 * vendor interface.
 *
 * Topology (field-verified, §12.17 B): once the phone has switched to USB HOST,
 * the head unit enumerates as 8087:0a68 with exactly three interfaces —
 *
 *   1-1:1.0  class 0xff (vendor)  driver usbfs   <- iAP2 runs here
 *   1-1:1.1  class 0x02 (CDC)     driver cdc_ncm
 *   1-1:1.2  class 0x0a (CDC data) driver cdc_ncm
 *
 * The closed-source app takes the vendor interface through libusb
 * (/dev/bus/usb).  We use the framework API instead (UsbDeviceConnection), which
 * is the same thing underneath without shipping our own libusb: open the device,
 * claim the vendor interface and move bytes over its bulk endpoints.
 *
 * Endpoints are discovered at runtime rather than hard-coded, because we have
 * never seen this device's descriptors — `UsbTopology` prints them when the car
 * is attached, and `describe()` below records what this instance actually got.
 *
 * IMPORTANT — claiming and "force":
 *
 * The closed-source app holds a normal usbfs claim on this interface, so a plain
 * claim from us fails with EBUSY.  Passing force=true uses
 * USBDEVFS_DISCONNECT_CLAIM, which displaces the other claim: the baseline app's
 * subsequent bulk transfers then run *without* a claim (the kernel floods
 * "usbfs: did not claim interface 0 before use") and its CarPlay session dies
 * within seconds.  Measured in the field: §12.24.
 *
 * So the default here is force=false — we fail cleanly instead of breaking a
 * working baseline — and force is an explicit, opt-in escalation.
 */

package com.okcar.link.iap2;

import android.content.Context;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.util.Log;

import com.okcar.link.usb.UsbTopology;

import java.io.IOException;

public final class UsbIap2Transport implements Iap2Link.Transport {

    private static final String TAG = "OkCarLink/Usb";

    /** bulkTransfer timeout: also the read poll interval. */
    private static final int TIMEOUT_MS = 500;

    private final UsbDeviceConnection mConnection;
    private final UsbInterface mInterface;
    private final UsbEndpoint mIn;
    private final UsbEndpoint mOut;
    private final String mDescription;

    private volatile boolean mClosed;

    private UsbIap2Transport(UsbDeviceConnection connection, UsbInterface usbInterface,
            UsbEndpoint in, UsbEndpoint out, String description) {
        mConnection = connection;
        mInterface = usbInterface;
        mIn = in;
        mOut = out;
        mDescription = description;
    }

    /** Claims without force: fails cleanly when the baseline app holds the interface. */
    public static UsbIap2Transport open(Context context) throws IOException {
        return open(context, false);
    }

    /**
     * Finds the head unit, claims its vendor interface and returns the transport.
     *
     * @param force true to displace an existing claim (kills the closed-source
     *              app's session — see the class comment).
     * @throws IOException with a human-readable reason when anything is missing
     *         (no car, no permission, no vendor interface, interface busy).
     */
    public static UsbIap2Transport open(Context context, boolean force) throws IOException {
        final UsbManager manager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        if (manager == null) {
            throw new IOException("no UsbManager");
        }
        final UsbDevice device = findHeadUnit(manager);
        if (device == null) {
            throw new IOException(String.format("head unit %04x:%04x is not on the bus",
                    UsbTopology.HEAD_UNIT_VENDOR_ID, UsbTopology.HEAD_UNIT_PRODUCT_ID));
        }
        ensurePermission(manager, device, context.getPackageName());

        final UsbInterface usbInterface = findVendorInterface(device);
        if (usbInterface == null) {
            throw new IOException("head unit has no vendor-specific (0xff) interface");
        }
        final UsbEndpoint in = findBulk(usbInterface, UsbConstants.USB_DIR_IN);
        final UsbEndpoint out = findBulk(usbInterface, UsbConstants.USB_DIR_OUT);
        if (in == null || out == null) {
            throw new IOException("vendor interface " + usbInterface.getId()
                    + " has no bulk IN/OUT pair");
        }

        final UsbDeviceConnection connection = manager.openDevice(device);
        if (connection == null) {
            throw new IOException("openDevice failed (permission or driver problem)");
        }
        if (!connection.claimInterface(usbInterface, force)) {
            connection.close();
            if (force) {
                throw new IOException("forced claimInterface(" + usbInterface.getId()
                        + ") failed");
            }
            throw new IOException("claimInterface(" + usbInterface.getId() + ") refused: the "
                    + "interface is held by another process (the closed-source app holds a usbfs "
                    + "claim). Stop it first — pm disable-user --user 0 com.okcar.autoconn — or "
                    + "escalate with force");
        }
        final String description = String.format("%04x:%04x iface %d alt %d, ep IN 0x%02x/OUT 0x%02x",
                device.getVendorId(), device.getProductId(), usbInterface.getId(),
                usbInterface.getAlternateSetting(), in.getAddress(), out.getAddress());
        Log.i(TAG, "iAP2 transport open: " + description);
        return new UsbIap2Transport(connection, usbInterface, in, out, description);
    }

    private static UsbDevice findHeadUnit(UsbManager manager) {
        for (UsbDevice device : manager.getDeviceList().values()) {
            if (UsbTopology.isHeadUnitId(device.getVendorId(), device.getProductId())
                    && findVendorInterface(device) != null) {
                return device;
            }
        }
        return null;
    }

    private static void ensurePermission(UsbManager manager, UsbDevice device, String packageName)
            throws IOException {
        if (manager.hasPermission(device)) {
            return;
        }
        try {
            // @hide, requires android.permission.MANAGE_USB (declared in the
            // manifest and whitelisted in privapp-permissions-okcar-link.xml).
            manager.grantPermission(device, packageName);
        } catch (RuntimeException e) {
            Log.w(TAG, "grantPermission failed: " + e);
        }
        if (!manager.hasPermission(device)) {
            throw new IOException("no USB permission for the head unit "
                    + "(needs MANAGE_USB to self-grant)");
        }
    }

    private static UsbInterface findVendorInterface(UsbDevice device) {
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            final UsbInterface usbInterface = device.getInterface(i);
            if (usbInterface != null
                    && usbInterface.getInterfaceClass() == UsbTopology.CLASS_VENDOR_SPEC) {
                return usbInterface;
            }
        }
        return null;
    }

    private static UsbEndpoint findBulk(UsbInterface usbInterface, int direction) {
        for (int i = 0; i < usbInterface.getEndpointCount(); i++) {
            final UsbEndpoint endpoint = usbInterface.getEndpoint(i);
            if (endpoint != null
                    && endpoint.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK
                    && endpoint.getDirection() == direction) {
                return endpoint;
            }
        }
        return null;
    }

    /* -------------------------------------------------------------- Transport */

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        if (mClosed) {
            return -1;
        }
        if (length <= 0) {
            return 0;
        }
        final int read = mConnection.bulkTransfer(mIn, buffer, offset, length, TIMEOUT_MS);
        if (read < 0) {
            // Negative = timeout or a short error; treat as "no data yet" so the
            // link keeps polling, exactly like a socket read timeout.
            return 0;
        }
        return read;
    }

    @Override
    public void write(byte[] buffer, int offset, int length) throws IOException {
        if (mClosed) {
            throw new IOException("transport closed");
        }
        int written = 0;
        while (written < length) {
            final int chunk = Math.min(length - written, 16 * 1024);
            final int sent = mConnection.bulkTransfer(mOut, buffer, offset + written, chunk,
                    TIMEOUT_MS);
            if (sent <= 0) {
                throw new IOException("bulk OUT failed after " + written + '/' + length
                        + " bytes (ret=" + sent + ')');
            }
            written += sent;
        }
    }

    @Override
    public void close() {
        if (mClosed) {
            return;
        }
        mClosed = true;
        try {
            mConnection.releaseInterface(mInterface);
        } catch (RuntimeException e) {
            Log.w(TAG, "releaseInterface failed: " + e);
        }
        try {
            mConnection.close();
        } catch (RuntimeException e) {
            Log.w(TAG, "connection close failed: " + e);
        }
        Log.i(TAG, "iAP2 transport closed");
    }

    @Override
    public String describe() {
        return "usb iAP2 (" + mDescription + ')';
    }
}
