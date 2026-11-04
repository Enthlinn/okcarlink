/*
 * OkcarOS link stack — the private OkCar broadcast protocol.
 *
 * These actions are emitted by services/usb/java/com/android/server/usb/
 * UsbDeviceManager.java on the kernel uevents the okcar USB driver raises:
 *
 *   PCCONNECT=ACCESSOTY_ATTACHED  -> android.intent.action.pc.PC_ATTACHED
 *   PCCONNECT=HID_IAP2_START      -> android.intent.action.pc.PC_HID_IAP_START
 *   USB_STATE=DISCONNECTED        -> ...pc.PC_USB_STATE_DISCONNECTED
 *   USB_STATE=CONFIGURED          -> ...pc.PC_USB_STATE_CONFIGURED
 *
 * The head unit sends vendor request 0x51 (rt=40 rq=51 wValue=0001) to start
 * the interconnect; the kernel turns that into the uevent, system_server turns
 * the uevent into these broadcasts.  Ground truth for the whole chain is in
 * the field-rework notes §12.13 H / §12.17 A.
 */

package com.okcar.link.usb;

public final class PcIntents {

    public static final String ACTION_PC_ATTACHED = "android.intent.action.pc.PC_ATTACHED";
    public static final String ACTION_PC_HID_IAP_START =
            "android.intent.action.pc.PC_HID_IAP_START";
    public static final String ACTION_PC_USB_STATE_CONFIGURED =
            "android.intent.action.pc.PC_USB_STATE_CONFIGURED";
    public static final String ACTION_PC_USB_STATE_DISCONNECTED =
            "android.intent.action.pc.PC_USB_STATE_DISCONNECTED";

    /** Actions the link service listens for, in the order they usually arrive. */
    public static final String[] ALL = {
            ACTION_PC_ATTACHED,
            ACTION_PC_HID_IAP_START,
            ACTION_PC_USB_STATE_CONFIGURED,
            ACTION_PC_USB_STATE_DISCONNECTED,
    };

    private PcIntents() {
    }
}
