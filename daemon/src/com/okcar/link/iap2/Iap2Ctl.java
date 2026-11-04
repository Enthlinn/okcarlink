/*
 * OkcarOS link stack — L3: iAP2 CTRL field.
 *
 * Packet-name strings recovered from the head unit's libiPodForiAP2.so
 * (exeed findings A §5.8.1): SYN-ACK, SYN, EAK, RST, SUS, DATA, ACK, UNKNOWN.
 */

package com.okcar.link.iap2;

public final class Iap2Ctl {

    public static final int ACK = 0x40;
    public static final int EAK = 0x20;
    public static final int RST = 0x10;
    public static final int SUS = 0x08;
    public static final int SYN = 0x80;
    public static final int SYN_ACK = 0xC0;

    public static final int ACK_EAK = ACK | EAK;

    private Iap2Ctl() {
    }

    public static String name(int ctrl) {
        return name(ctrl, false);
    }

    /**
     * Packet name, following the head unit's own iAP2PacketName logic:
     * "other, len > 9" is DATA, "other" is UNKNOWN.
     */
    public static String name(int ctrl, boolean hasPayload) {
        switch (ctrl & 0xFF) {
            case SYN_ACK:
                return "SYN-ACK";
            case SYN:
                return "SYN";
            case ACK_EAK:
                return "ACK|EAK";
            case EAK:
                return "EAK";
            case ACK:
                return "ACK";
            case RST:
                return "RST";
            case SUS:
                return "SUS";
            case 0x00:
                return hasPayload ? "DATA" : "UNKNOWN";
            default:
                return "UNKNOWN";
        }
    }
}
