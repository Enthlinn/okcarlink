/*
 * OkcarOS link stack — L1: USB role / gadget control.
 *
 * The okcar kernel exports a single character device, /dev/okcar
 * (drivers/usb/okcar/okcar.c):
 *
 *   read  4 bytes  -> little-endian int: current role (0 none / 1 device / 2 host)
 *   write "usbmode,1,N\n"  -> switch role, N = 1 device, N = 2 host
 *
 * Writing mode 2 is what makes the phone become USB HOST so the head unit can
 * re-enumerate as a device (idVendor 8087 / idProduct 0a68, "the production head unit"), which is
 * the whole point of the interconnect handshake.  The actual host controller
 * work is in okcar_usbmode_toggle() in dwc3-msm.c (replicated 1:1 from the
 * official kernel in session 4, see the field-rework notes §12.13 A/B).
 *
 * SELinux: system_app is allowed to read/write okcarapi_device
 * (okcar/sepolicy/okcar/okcar.te), which is why this app runs with
 * sharedUserId=android.uid.system.
 */

package com.okcar.link.usb;

import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

public final class UsbModeController {

    private static final String TAG = "OkCarLink/Usb";

    public static final String DEVICE_NODE = "/dev/okcar";

    /** Role values accepted by the driver (enum OKCAR_USB_MODE in okcar.c). */
    public static final int MODE_NONE = 0;
    public static final int MODE_DEVICE = 1;
    public static final int MODE_HOST = 2;

    private UsbModeController() {
    }

    public static boolean nodeExists() {
        return new File(DEVICE_NODE).exists();
    }

    /**
     * Reads the current role from /dev/okcar.
     *
     * @return the role, or -1 when the device node is missing / unreadable
     *         (which also tells us the okcar kernel driver is not loaded).
     */
    public static int readMode() {
        FileInputStream in = null;
        try {
            in = new FileInputStream(DEVICE_NODE);
            final byte[] buffer = new byte[4];
            int read = 0;
            while (read < 4) {
                final int n = in.read(buffer, read, 4 - read);
                if (n <= 0) {
                    break;
                }
                read += n;
            }
            if (read < 4) {
                Log.w(TAG, "short read from " + DEVICE_NODE + ": " + read);
                return -1;
            }
            return (buffer[0] & 0xFF)
                    | ((buffer[1] & 0xFF) << 8)
                    | ((buffer[2] & 0xFF) << 16)
                    | ((buffer[3] & 0xFF) << 24);
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "read " + DEVICE_NODE + " failed: " + e);
            return -1;
        } finally {
            closeQuietly(in);
        }
    }

    /**
     * Switches the USB role.  Mirrors {@code echo "usbmode,1,N" > /dev/okcar}.
     *
     * @return true when the write was accepted by the driver.
     */
    public static boolean setMode(int mode) {
        if (mode != MODE_DEVICE && mode != MODE_HOST && mode != MODE_NONE) {
            Log.w(TAG, "refusing invalid usb mode " + mode);
            return false;
        }
        FileOutputStream out = null;
        try {
            // NOTE: FileOutputStream opens with O_WRONLY|O_CREAT|O_TRUNC; on a
            // character device the truncate flag is a no-op, exactly like the
            // shell redirection the driver documents.
            out = new FileOutputStream(DEVICE_NODE);
            out.write(("usbmode,1," + mode + "\n").getBytes("UTF-8"));
            out.flush();
            Log.i(TAG, "usbmode -> " + mode);
            return true;
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "write " + DEVICE_NODE + " failed: " + e);
            return false;
        } finally {
            closeQuietly(out);
        }
    }

    /** Reads a single-line sysfs/procfs value, trimmed; "" on failure. */
    public static String readLine(String path) {
        InputStream in = null;
        try {
            in = new FileInputStream(path);
            final StringBuilder sb = new StringBuilder();
            int c;
            while ((c = in.read()) >= 0) {
                if (c == '\n') {
                    break;
                }
                sb.append((char) c);
            }
            return sb.toString().trim();
        } catch (IOException | RuntimeException e) {
            return "";
        } finally {
            closeQuietly(in);
        }
    }

    /** Writes a single-line sysfs/procfs value; false on failure. */
    public static boolean writeLine(String path, String value) {
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(path);
            out.write(value.getBytes("UTF-8"));
            out.flush();
            return true;
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "write " + path + " failed: " + e);
            return false;
        } finally {
            closeQuietly(out);
        }
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException ignored) {
            /* nothing useful to do */
        }
    }
}
