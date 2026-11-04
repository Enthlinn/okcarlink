/*
 * OkcarOS link stack — L3 preparation: what does the head unit's USB function
 * actually look like?
 *
 * The remaining gap before iAP2 can talk to the car is the transport: the head
 * unit exposes ONE vendor-class interface (class 0xff, driver usbfs) next to the
 * two cdc_ncm interfaces, and the closed-source app grabs it through libusb
 * (/dev/bus/usb).  To write our own transport we first need the exact interface
 * and endpoint layout — which is exactly what this class reads out, read-only.
 *
 * It never claims an interface, so it is safe to run while the baseline
 * (closed-source) app is connected to the car; it only reports facts:
 *
 *   vid/pid, product string, every interface (class/subclass/protocol) and
 *   every endpoint (address, direction, type, max packet size)
 *
 * Identity of the head unit comes from the field capture (§12.13 J / §12.17 A):
 *   idVendor=8087 idProduct=0a68, Product: the production head unit
 */

package com.okcar.link.usb;

import android.content.Context;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public final class UsbTopology {

    private static final String TAG = "OkCarLink/Usb";

    public static final int HEAD_UNIT_VENDOR_ID = 0x8087;

    /** Product id seen while the head unit is in its CarPlay mode. */
    public static final int HEAD_UNIT_PRODUCT_ID = 0x0A68;

    /**
     * Product id seen on a *cold* head unit, before anything identified it
     * (observed 2026-09-26).  Matching the product id exactly meant we never
     * claimed the interface in that state, so the head unit reset the link.
     * Vendor ids are the stable part: Intel 8087 with a vendor-spec interface.
     */
    public static final int HEAD_UNIT_PRODUCT_ID_COLD = 0x09EF;

    /** True for the head-unit ids we have seen (cold and CarPlay modes). */
    public static boolean isHeadUnitId(int vendorId, int productId) {
        return vendorId == HEAD_UNIT_VENDOR_ID
                && (productId == HEAD_UNIT_PRODUCT_ID
                        || productId == HEAD_UNIT_PRODUCT_ID_COLD);
    }

    /** USB_CLASS_VENDOR_SPEC — the interface the iAP2 tunnel runs on. */
    public static final int CLASS_VENDOR_SPEC = 0xFF;
    public static final int CLASS_CDC = 0x02;
    public static final int CLASS_CDC_DATA = 0x0A;

    /** Bulk endpoint type. */
    public static final int ENDPOINT_TYPE_BULK = 0x02;

    private UsbTopology() {
    }

    public static final class EndpointInfo {
        public final int address;
        public final int direction;
        public final int type;
        public final int maxPacketSize;
        public final int interval;

        EndpointInfo(UsbEndpoint endpoint) {
            this.address = endpoint.getAddress();
            this.direction = endpoint.getDirection();
            this.type = endpoint.getType();
            this.maxPacketSize = endpoint.getMaxPacketSize();
            this.interval = endpoint.getInterval();
        }

        public boolean isBulk() {
            return type == ENDPOINT_TYPE_BULK;
        }

        public String describe() {
            return String.format("ep 0x%02x %s %s maxPacket=%d", address,
                    direction == UsbConstants.USB_DIR_IN ? "IN " : "OUT",
                    isBulk() ? "bulk" : ("type" + type), maxPacketSize);
        }
    }

    public static final class InterfaceInfo {
        public final int id;
        public final int alternateSetting;
        public final int interfaceClass;
        public final int subClass;
        public final int protocol;
        public final List<EndpointInfo> endpoints = new ArrayList<EndpointInfo>();

        InterfaceInfo(UsbInterface iface) {
            this.id = iface.getId();
            this.alternateSetting = iface.getAlternateSetting();
            this.interfaceClass = iface.getInterfaceClass();
            this.subClass = iface.getInterfaceSubclass();
            this.protocol = iface.getInterfaceProtocol();
            for (int i = 0; i < iface.getEndpointCount(); i++) {
                final UsbEndpoint endpoint = iface.getEndpoint(i);
                if (endpoint != null) {
                    endpoints.add(new EndpointInfo(endpoint));
                }
            }
        }

        public boolean isVendorSpecific() {
            return interfaceClass == CLASS_VENDOR_SPEC;
        }

        /** Endpoints usable for the iAP2 byte pipe on this interface. */
        public EndpointInfo bulkIn() {
            for (EndpointInfo e : endpoints) {
                if (e.isBulk() && e.direction == UsbConstants.USB_DIR_IN) {
                    return e;
                }
            }
            return null;
        }

        public EndpointInfo bulkOut() {
            for (EndpointInfo e : endpoints) {
                if (e.isBulk() && e.direction == UsbConstants.USB_DIR_OUT) {
                    return e;
                }
            }
            return null;
        }

        public String describe() {
            final StringBuilder sb = new StringBuilder();
            sb.append(String.format("iface %d alt %d class 0x%02x/0x%02x/0x%02x",
                    id, alternateSetting, interfaceClass, subClass, protocol));
            for (EndpointInfo e : endpoints) {
                sb.append("\n      ").append(e.describe());
            }
            return sb.toString();
        }
    }

    public static final class DeviceInfo {
        public final int vendorId;
        public final int productId;
        public final String deviceName;
        public final String productName;
        public final String manufacturerName;
        public final String serialNumber;
        public final List<InterfaceInfo> interfaces = new ArrayList<InterfaceInfo>();

        private DeviceInfo(UsbDevice device) {
            this.vendorId = device.getVendorId();
            this.productId = device.getProductId();
            this.deviceName = device.getDeviceName();
            this.productName = safe(device.getProductName());
            this.manufacturerName = safe(device.getManufacturerName());
            this.serialNumber = safe(device.getSerialNumber());
            for (int i = 0; i < device.getInterfaceCount(); i++) {
                final UsbInterface iface = device.getInterface(i);
                if (iface != null) {
                    interfaces.add(new InterfaceInfo(iface));
                }
            }
        }

        /**
         * Intel-vendor device that actually exposes a vendor-spec interface.  The
         * product id is deliberately not required to match: the head unit uses at
         * least two of them (0x0a68 in CarPlay mode, 0x09ef when cold).
         */
        public boolean isHeadUnit() {
            return vendorId == HEAD_UNIT_VENDOR_ID && vendorInterface() != null;
        }

        /** The vendor-specific interface the iAP2 tunnel runs on, if present. */
        public InterfaceInfo vendorInterface() {
            for (InterfaceInfo iface : interfaces) {
                if (iface.isVendorSpecific()) {
                    return iface;
                }
            }
            return null;
        }

        public String describe() {
            final StringBuilder sb = new StringBuilder();
            sb.append(String.format("%s  %04x:%04x", deviceName, vendorId, productId));
            if (!productName.isEmpty()) {
                sb.append("  \"").append(productName).append('"');
            }
            if (!manufacturerName.isEmpty()) {
                sb.append("  by ").append(manufacturerName);
            }
            for (InterfaceInfo iface : interfaces) {
                sb.append("\n    ").append(iface.describe().replace("\n", "\n  "));
            }
            return sb.toString();
        }

        private static String safe(String value) {
            return value == null ? "" : value;
        }
    }

    /**
     * A stable local identifier for mDNS TXT records.  The closed-source app
     * advertises `id=8e:0c:64:4f:bb:f7` (not wlan0's MAC), so this is only a
     * placeholder until we match its derivation (§12.26).
     */
    public static String localId(Context context) {
        try {
            final java.net.NetworkInterface nif = java.net.NetworkInterface.getByName("wlan0");
            if (nif != null && nif.getHardwareAddress() != null) {
                final StringBuilder sb = new StringBuilder();
                for (byte b : nif.getHardwareAddress()) {
                    if (sb.length() > 0) {
                        sb.append(':');
                    }
                    sb.append(String.format("%02x", b));
                }
                return sb.toString();
            }
        } catch (Exception e) {
            Log.w(TAG, "cannot read wlan0 MAC: " + e);
        }
        return "00:00:00:00:00:00";
    }

    /** Wraps a device the framework handed us (e.g. from an attach broadcast). */
    public static DeviceInfo of(UsbDevice device) {
        return device == null ? null : new DeviceInfo(device);
    }

    /** All USB devices currently known to the framework. */
    public static List<DeviceInfo> list(Context context) {
        final List<DeviceInfo> out = new ArrayList<DeviceInfo>();
        try {
            final UsbManager manager =
                    (UsbManager) context.getSystemService(Context.USB_SERVICE);
            if (manager == null) {
                return out;
            }
            final Collection<UsbDevice> devices = manager.getDeviceList().values();
            for (UsbDevice device : devices) {
                out.add(new DeviceInfo(device));
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "cannot list usb devices: " + e);
        }
        return out;
    }

    /** The head unit, or null when it is not enumerated (i.e. no car attached). */
    public static DeviceInfo findHeadUnit(Context context) {
        for (DeviceInfo device : list(context)) {
            if (device.isHeadUnit()) {
                return device;
            }
        }
        return null;
    }

    /** Human-readable report; used by the UI and by the self test. */
    public static String describe(Context context) {
        final List<DeviceInfo> devices = list(context);
        final StringBuilder sb = new StringBuilder();
        if (devices.isEmpty()) {
            return "no USB device enumerated (head unit not attached)";
        }
        for (DeviceInfo device : devices) {
            sb.append(device.describe()).append('\n');
        }
        final DeviceInfo headUnit = findHeadUnit(context);
        if (headUnit == null) {
            sb.append("head unit (").append(String.format("%04x:%04x",
                    HEAD_UNIT_VENDOR_ID, HEAD_UNIT_PRODUCT_ID))
                    .append(") not present");
        } else {
            final InterfaceInfo vendor = headUnit.vendorInterface();
            if (vendor == null) {
                sb.append("head unit present but no vendor-specific (0xff) interface");
            } else {
                final EndpointInfo in = vendor.bulkIn();
                final EndpointInfo out = vendor.bulkOut();
                sb.append("head unit iAP2 interface: ")
                        .append(in == null ? "no bulk IN" : in.describe())
                        .append(" / ")
                        .append(out == null ? "no bulk OUT" : out.describe());
            }
        }
        return sb.toString();
    }
}
