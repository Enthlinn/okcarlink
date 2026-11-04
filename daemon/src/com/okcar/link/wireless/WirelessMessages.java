/*
 * OkcarOS link stack — wireless CarPlay (iAP2 over Bluetooth RFCOMM).
 *
 * Who does what: for *wireless* CarPlay the accessory (head unit) is the Wi-Fi
 * Direct group owner and the AirPlay server; the device (our phone, the iPhone
 * role) is the one that must
 *
 *   1. publish the RFCOMM service UUID 00000000-deca-fade-deca-deafdecacafe so the
 *      accessory can dial us (the accessory is the RFCOMM client: observed
 *      `btsock_rfc_connect ... is_server=false` in the accessory's Bluetooth log),
 *   2. run the iAP2 link over that socket (DETECT both ways, we send the SYN),
 *   3. perform identification + MFi authentication,
 *   4. ask for the Wi-Fi credentials (0x5702), announce availability (0x4300),
 *   5. receive 0x5703 (credentials) and 0x4301 (start session: credentials again
 *      plus the accessory's AirPlay address/port and identity) and then join the
 *      network and open the AirPlay session on it.
 *
 * Every byte layout in this class was decoded from a real working session
 * captured on 2026-09-26/27 (Bluetooth HCI snoop of a head-unit emulator while an
 * iPhone connected wirelessly) — see the field-rework notes §12.38.  The exact
 * captured bodies are re-decoded by WirelessSelfTest, so a regression in this
 * file fails the self test on the host JVM as well as on the device.
 *
 * Pure Java: no Android imports, so it can be compiled and tested on the host.
 */

package com.okcar.link.wireless;

import com.okcar.link.iap2.Iap2Params;
import com.okcar.link.iap2.Iap2Params.Param;
import com.okcar.link.iap2.Iap2Syn;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class WirelessMessages {

    /** RFCOMM service record the accessory dials (SCN 1 in the captured session). */
    public static final String RFCOMM_UUID = "00000000-deca-fade-deca-deafdecacafe";

    /**
     * The second Apple-specific service record a real iPhone advertises next to the
     * wireless-iAP UUID above, and the one we never used to publish.
     *
     * <p>Measured on the head unit (217) with a real iPhone 8 bonded: its stored SDP UUID
     * list is {@code 00000000-deca-fade-deca-deafdecacafe, 110a, 110e, 1116, 111f, 112f,
     * 1132, 02030302-1d19-415f-86f2-22a2106a0a77}.  Ours carried the same profile UUIDs but
     * <em>neither</em> Apple one, which is the only thing an accessory can still see besides
     * the class of device: the SDP Device ID already matches byte for byte (vendor 0x004C /
     * product 0x710A / version 0x1070 in bt_did.conf).  Published only so a head unit that
     * checks for an iPhone before it dials sees the same record set — we serve nothing on it.
     */
    public static final String APPLE_CARPLAY_UUID = "02030302-1d19-415f-86f2-22a2106a0a77";

    /**
     * The other half of the pair {@link #RFCOMM_UUID} belongs to: the accessory's own iAP2
     * RFCOMM service, which the <em>phone</em> is expected to dial.
     *
     * <p>Read out of the production head unit's own CarPlay code
     * ({@code com.neusoft.carplay.CarplayWifiConnectManage}), which is the accessory side of
     * exactly this protocol and is unambiguous about the two roles:
     *
     * <pre>
     *   listenUsingRfcommWithServiceRecord(name, IAP2_UUID)  // "...caff" — the car listens
     *   device.createRfcommSocketToServiceRecord(IAP1_UUID)  // "...cafe" — the car dials
     * </pre>
     *
     * So the accessory dials us on {@code ...cafe} (which we already serve) and expects the
     * phone to dial it back on {@code ...caff}.  Only the {@code ...cafe} half was ever
     * implemented here, which is the one structural difference from a real iPhone we have
     * evidence for straight from an accessory's production code.
     */
    public static final String IAP2_SERVICE_UUID = "00000000-deca-fade-deca-deafdecacaff";

    /* Message ids (control session 10, wrapped in the CSM 40 40 len len msgid header). */
    public static final int MSG_START_IDPS = 0x1D00;
    public static final int MSG_IDENTIFICATION = 0x1D01;
    public static final int MSG_IDENTIFICATION_ACCEPTED = 0x1D02;

    public static final int MSG_AUTH_CERTIFICATE_REQUEST = 0xAA00;
    public static final int MSG_AUTH_CERTIFICATE_RESPONSE = 0xAA01;
    public static final int MSG_AUTH_CHALLENGE_REQUEST = 0xAA02;
    public static final int MSG_AUTH_CHALLENGE_RESPONSE = 0xAA03;
    public static final int MSG_AUTH_SUCCEEDED = 0xAA05;

    public static final int MSG_WIRELESS_CARPLAY_UPDATE = 0x4E0D;
    public static final int MSG_TRANSPORT_IDENTIFIER_NOTIFICATION = 0x4E0E;
    /** 0x4E0C — the phone's wireless-session UUID (captured from a real iPhone). */
    public static final int MSG_SESSION_UUID = 0x4E0C;
    /** 0x4E0A — opaque session-info word the real iPhone sends between 4E0C and 4E0D. */
    public static final int MSG_SESSION_INFO = 0x4E0A;
    public static final int MSG_REQUEST_WIRELESS_INFO = 0x5702;
    public static final int MSG_WIRELESS_INFO = 0x5703;
    public static final int MSG_CARPLAY_AVAILABILITY = 0x4300;
    public static final int MSG_CARPLAY_START_SESSION = 0x4301;

    /* 0x5703 WirelessCarPlayInfo (accessory -> device). */
    public static final int P_INFO_SSID = 0x0001;
    public static final int P_INFO_PASSPHRASE = 0x0002;
    public static final int P_INFO_SECURITY_TYPE = 0x0003;
    public static final int P_INFO_CHANNEL = 0x0004;

    /* 0x4301 CarPlayStartSession (accessory -> device). */
    /* 0x4300/0x4301 groups, in the CarPlay layer's numbering (verified against the emulator's own catalog,
     * Iap2Endpoint.kt): group 0 = wired, group 1 = wireless, group 2 = theme assets.  The older
     * P_AVAIL_* names below are that same numbering, only named from the wireless side. */
    public static final int P_SESSION_WIRED = 0x0000;
    public static final int P_WIRED_IP = 0x0000;
    public static final int P_SESSION_WIRELESS = 0x0001;
    public static final int P_SESSION_AIRPLAY_PORT = 0x0002;
    public static final int P_SESSION_DEVICE_IDENTIFIER = 0x0003;
    public static final int P_SESSION_PUBLIC_KEY = 0x0004;
    public static final int P_SESSION_SOURCE_VERSION = 0x0005;

    /* ... its nested wireless group. */
    public static final int P_WIRELESS_SSID = 0x0000;
    public static final int P_WIRELESS_PASSPHRASE = 0x0001;
    public static final int P_WIRELESS_CHANNEL = 0x0002;
    public static final int P_WIRELESS_IP = 0x0003;
    public static final int P_WIRELESS_SECURITY_TYPE = 0x0004;

    /* 0x4300 CarPlayAvailability (device -> accessory): two groups. */
    public static final int P_AVAIL_WIRELESS_GROUP = 0x0000;
    public static final int P_AVAIL_BLUETOOTH_GROUP = 0x0001;
    public static final int P_AVAIL_STATUS = 0x0000;
    public static final int P_AVAIL_IDENTIFIER = 0x0001;

    /* 0x4E0E DeviceTransportIdentifierNotification (device -> accessory). */
    public static final int P_TIN_BLUETOOTH_ID = 0x0000;
    public static final int P_TIN_DEVICE_ID = 0x0001;

    /* 0x4E0D WirelessCarPlayUpdate. */
    public static final int P_UPDATE_STATUS = 0x0000;

    /* 0x4E0C WirelessSessionUUID / 0x4E0A session info (both param 0). */
    public static final int P_SESSION_UUID = 0x0000;
    public static final int P_SESSION_INFO = 0x0000;

    /* 0x1D01 accessory identification parameters. */
    public static final int P_ID_NAME = 0x0000;
    public static final int P_ID_MODEL = 0x0001;
    public static final int P_ID_MANUFACTURER = 0x0002;
    public static final int P_ID_SERIAL = 0x0003;
    public static final int P_ID_FIRMWARE = 0x0004;
    public static final int P_ID_HARDWARE = 0x0005;
    public static final int P_ID_MESSAGES_SENT = 0x0006;
    public static final int P_ID_MESSAGES_RECEIVED = 0x0007;
    public static final int P_ID_POWER_PROVIDING = 0x0008;
    public static final int P_ID_MAX_CURRENT = 0x0009;
    public static final int P_ID_PREFERRED_APP = 0x000A;
    public static final int P_ID_CURRENT_LANGUAGE = 0x000C;
    public static final int P_ID_SUPPORTED_LANGUAGE = 0x000D;
    public static final int P_ID_BLUETOOTH_TRANSPORT = 0x0011;
    public static final int P_ID_WIRELESS_TRANSPORT = 0x0018;

    /* Transport component sub-parameters (both groups share the shape). */
    public static final int P_TC_TRANSPORT_IDENTIFIER = 0x0000;
    public static final int P_TC_NAME = 0x0001;
    public static final int P_TC_IS_SUPPORTED = 0x0002;
    public static final int P_TC_BLUETOOTH_MAC = 0x0003;
    public static final int P_TC_TRANSPORT_TYPE_NAME = 0x0004;
    public static final int P_TC_IS_AVAILABLE = 0x0005;
    public static final int P_TC_IS_ENABLED = 0x0005;

    /**
     * Wireless security type as carried in 0x5703 / 0x4301 (captured value: 2).
     *
     * <p>Kept as the name of the one value the capture pins; the table itself — and the mapping to
     * the {@code cmd wifi connect-network} keyword — is {@link Security}, because a missing or
     * unknown value must be reported, not defaulted to this one.
     */
    public static final int SECURITY_WPA_WPA2 = Security.WPA_WPA2;

    /**
     * The security-type table for the accessory's Wi-Fi hand-off.
     *
     * <p>iAP2 0x5703 carries the security type as a one-byte value at parameter id 3.  The numbers
     * are not ours to invent, and they are not inferred from the passphrase:
     *
     * <ul>
     *   <li>the peer that produced our captured 0x5703 declares the wire table itself — the emulator,
     *       {@code Iap2WirelessControlClient.kt} (the emulator-side wireless-control client)
     *       ({@code enum class Iap2WirelessSecurity}, lines 311-317): NONE(0), WEP(1), WPA_WPA2(2),
     *       WPA3_TRANSITION(3), WPA3_ONLY(4);</li>
     *   <li>the captured body agrees: {@code … 0005 0003 02  0005 0004 95} = securityType 2,
     *       channel 149 (src/OkCarWirelessSim/.../AccessoryScript.java BODY_5703, and
     *       the builder's own key store line 43: "SSID K30 / securityType 2").</li>
     * </ul>
     *
     * <p>The keyword on the right is the device's own, read out of {@code cmd wifi help} on 217
     * (Android 15): {@code connect-network <ssid> open|owe|wpa2|wpa3|wep [<passphrase>]}.  Two
     * mappings deserve their note: WPA3_TRANSITION has no keyword of its own on this platform, so it
     * maps to {@code wpa3} (a transition AP is a SAE AP that still serves WPA2-PSK clients, and
     * {@code wpa3} on Android means SAE, which such an AP accepts); WEP is supported by the shell's
     * own {@code wep} keyword, so it is mapped rather than refused.
     *
     * <p>Anything outside 0..4 is UNKNOWN, and UNKNOWN is never guessed into WPA2 — the visible
     * failure mode this table exists to end is "0x5703 carried a security type we did not know and
     * the phone silently tried WPA2 anyway".  The join ({@link AccessoryWifiJoin}) refuses it and
     * says so.
     */
    public static final class Security {

        /** Wire values carried in 0x5703 parameter id 3 / 0x4301's nested group id 4. */
        public static final int NONE = 0;
        public static final int WEP = 1;
        public static final int WPA_WPA2 = 2;
        public static final int WPA3_TRANSITION = 3;
        public static final int WPA3_ONLY = 4;

        /** A security type that is not in the table — reported, never guessed. */
        public static final int UNKNOWN = -1;

        /** One row: the wire value, its name, the shell keyword and its passphrase rule. */
        public static final class Mapping {
            /** As carried on the wire (or {@link #UNKNOWN}). */
            public final int wireValue;
            /** {@code cmd wifi connect-network} security keyword, or null when unknown. */
            public final String token;
            /** Human name for logs, e.g. {@code wpa2}. */
            public final String name;
            /** True when the keyword needs a passphrase argument. */
            public final boolean passphraseRequired;
            /** False only for {@link #UNKNOWN}. */
            public final boolean known;

            Mapping(int wireValue, String token, String name, boolean passphraseRequired,
                    boolean known) {
                this.wireValue = wireValue;
                this.token = token;
                this.name = name;
                this.passphraseRequired = passphraseRequired;
                this.known = known;
            }
        }

        private static final Mapping UNKNOWN_MAPPING =
                new Mapping(UNKNOWN, null, "unknown", false, false);

        /*
         * The table, in wire order.  `cmd wifi help` on 217 lists the keywords exactly like this:
         * open (no passphrase), wep / wpa2 / wpa3 (passphrase required).
         */
        private static final Mapping[] TABLE = {
                new Mapping(NONE, "open", "none", false, true),
                new Mapping(WEP, "wep", "wep", true, true),
                new Mapping(WPA_WPA2, "wpa2", "wpa2", true, true),
                new Mapping(WPA3_TRANSITION, "wpa3", "wpa3-transition", true, true),
                new Mapping(WPA3_ONLY, "wpa3", "wpa3-only", true, true),
        };

        private Security() {
        }

        /** The row for a wire value; an unknown value yields the UNKNOWN row (never a default). */
        public static Mapping of(int wireValue) {
            for (Mapping m : TABLE) {
                if (m.wireValue == wireValue) {
                    return m;
                }
            }
            return UNKNOWN_MAPPING;
        }

        public static boolean known(int wireValue) {
            return of(wireValue).known;
        }

        /** The shell keyword for a wire value, or null when the value is unknown. */
        public static String tokenOf(int wireValue) {
            return of(wireValue).token;
        }

        /** Name for logs: {@code wpa2} for 2, {@code unknown(7)} outside the table. */
        public static String nameOf(int wireValue) {
            final Mapping m = of(wireValue);
            return m.known ? m.name : ("unknown(" + wireValue + ")");
        }

        /** The whole table as one line, so an unknown value can be reported with its options. */
        public static String table() {
            final StringBuilder sb = new StringBuilder();
            for (Mapping m : TABLE) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(m.wireValue).append('=').append(m.name);
            }
            return sb.toString();
        }

        /**
         * True when the framework join path ({@code WifiGroupJoiner.joinWithLegacyApi} through
         * {@code ShellWifiOps}) expresses this security type correctly.
         *
         * <p>That path derives the keyword from "is there a passphrase" and therefore only ever
         * emits {@code open} or {@code wpa2}: {@link #NONE} and {@link #WPA_WPA2} are fine, while
         * WEP and both WPA3 flavours are not.  The shell path is the only one that can name those
         * keywords, so when it fails for them the join must refuse instead of falling through to a
         * wrong-keyword attempt — measured 2026-10-08: 217 (Android 15) has
         * {@code open|owe|wpa2|wpa3|wep} while lmi (Android 12) has {@code open|owe|wpa2|wpa3},
         * i.e. even the keyword set differs per platform.
         */
        public static boolean legacyPathExpresses(int wireValue) {
            return wireValue == NONE || wireValue == WPA_WPA2;
        }
    }

    public static final int TRANSPORT_ID_BLUETOOTH = 0;
    public static final int TRANSPORT_ID_WIRELESS = 1;

    private WirelessMessages() {
    }

    /**
     * The iAP2 link SYN block a real iPhone answered with, captured on the wire
     * (2026-09-26/27):
     * {@code 01 7f ff ff 0f a0 01 f4 04 03 | 0a 00 02 | 0b 02 01 | 0c 01 02}
     * — identical to the accessory's own block except maxOutgoing (0x7F vs 0x04).
     * The wireless session injects this with {@code Iap2Link.setSynTemplate} so the
     * wired path keeps the head unit's parameters (§12.33).
     */
    public static Iap2Syn iphoneSynAck() {
        final Iap2Syn syn = new Iap2Syn();
        syn.a = 0x01;
        syn.b = 0x7F;          /* maxOutgoing */
        syn.c = (byte) 0xFF;
        syn.d = (byte) 0xFF;
        syn.e = 0x0FA0;        /* retransmit timeout, 4000 ms */
        syn.f = 0x01F4;        /* cumulative ack timeout, 500 ms */
        syn.g = 0x04;          /* maxRetrans */
        syn.h = 0x03;          /* maxAcks */
        syn.entries.clear();
        syn.entries.add(new byte[]{0x0A, 0x00, 0x02});
        syn.entries.add(new byte[]{0x0B, 0x02, 0x01});
        syn.entries.add(new byte[]{0x0C, 0x01, 0x02});
        return syn;
    }

    /* ------------------------------------------------------------------ *
     * Device -> accessory messages
     * ------------------------------------------------------------------ */

    /** 0x1D00 StartIDPS — we ask the accessory to identify itself. */
    public static byte[] startIdps() {
        return new byte[0];
    }

    /** 0x1D02 — we accept (and acknowledge) the accessory's identification. */
    public static byte[] identificationAccepted() {
        return new byte[0];
    }

    /** 0xAA00 — request the accessory's MFi certificate. */
    public static byte[] authCertificateRequest() {
        return new byte[0];
    }

    /** 0xAA02 — 32-byte challenge for the accessory's MFi signature. */
    public static byte[] authChallengeRequest(byte[] challenge) {
        return Iap2Params.encode(Arrays.asList(Iap2Params.of(0x0000, challenge)));
    }

    /** 0xAA05 — authentication succeeded. */
    public static byte[] authSucceeded() {
        return new byte[0];
    }

    /** 0x4E0D — "wireless CarPlay is available/enabled" (captured body: 00 05 00 00 01). */
    public static byte[] wirelessCarplayUpdate(boolean available) {
        return Iap2Params.encode(Arrays.asList(Iap2Params.u8(P_UPDATE_STATUS, available ? 1 : 0)));
    }

    /**
     * 0x4E0E — our Bluetooth identifier and the iAP2 device identifier.
     * Captured: param 0 = "2c:7c:f2:ba:a6:f7", param 1 = "00008110000602340E08401E".
     */
    public static byte[] transportIdentifierNotification(String bluetoothId, String deviceId) {
        final List<Param> params = new ArrayList<Param>();
        params.add(Iap2Params.ascii(P_TIN_BLUETOOTH_ID, bluetoothId));
        params.add(Iap2Params.ascii(P_TIN_DEVICE_ID, deviceId));
        return Iap2Params.encode(params);
    }

    /** 0x5702 — "give me the Wi-Fi credentials I must join". */
    public static byte[] requestWirelessInfo() {
        return new byte[0];
    }

    /**
     * 0x4E0C — the phone's wireless-session UUID.  Captured from a real iPhone talking to
     * Fruit Link (2026-09-27 17:18): one parameter, id 0, value = the UUID text with a
     * trailing NUL ("410C658E-42DB-4A9B-A7FA-96AF4045A2D0\0", param length 0x29).
     * The accessory sends this message too (0x4E0C 0029 0000 <uuid>), so both sides
     * announce one; we send ours right after the identification hand-shake.
     */
    public static byte[] wirelessSessionUuid(String uuid) {
        return Iap2Params.encode(Arrays.asList(
                Iap2Params.ascii(P_SESSION_UUID, (uuid == null ? "" : uuid) + "\u0000")));
    }

    /**
     * 0x4E0A — opaque "session information" the real iPhone sent between 0x4E0C and
     * 0x4E0D.  Captured body: {@code 00 07 00 00 7a 68 00 32} (one 4-byte parameter).
     */
    public static byte[] wirelessSessionInfo() {
        return Iap2Params.encode(Arrays.asList(
                Iap2Params.of(P_SESSION_INFO, new byte[] {0x7a, 0x68, 0x00, 0x32})));
    }

    /**
     * 0x4300 CarPlayAvailability.  Captured body builds two groups, each
     * {status = 1, identifier}; group 0 carries the iAP2 device identifier, group 1
     * the Bluetooth identifier.
     */
    public static byte[] carPlayAvailability(boolean available, String deviceId, String bluetoothId) {
        final int status = available ? 1 : 0;
        final List<Param> wireless = Arrays.asList(
                Iap2Params.u8(P_AVAIL_STATUS, status),
                Iap2Params.ascii(P_AVAIL_IDENTIFIER, deviceId));
        final List<Param> bluetooth = Arrays.asList(
                Iap2Params.u8(P_AVAIL_STATUS, status),
                Iap2Params.ascii(P_AVAIL_IDENTIFIER, bluetoothId));
        final List<Param> params = new ArrayList<Param>();
        params.add(Iap2Params.group(P_AVAIL_WIRELESS_GROUP, wireless));
        params.add(Iap2Params.group(P_AVAIL_BLUETOOTH_GROUP, bluetooth));
        return Iap2Params.encode(params);
    }

    /**
     * 0x4300 for the wired path: the USB side is up and Wi-Fi is explicitly not (wiredAvailable=1,
     * wirelessAvailable=0).  The wired path never receives 0x5703, so the availability used to be
     * gated behind a Wi-Fi configuration that can never arrive -- the emulator then sat in RunningControl
     * waiting for 0x4300 and never sent its 0x4301 CarPlayStartSession.
     */
    public static byte[] carPlayAvailabilityWired(String usbIdentifier) {
        final List<Param> wired = Arrays.asList(
                Iap2Params.u8(P_AVAIL_STATUS, 1),
                Iap2Params.ascii(P_AVAIL_IDENTIFIER, usbIdentifier == null ? "" : usbIdentifier));
        final List<Param> wireless = Arrays.asList(
                Iap2Params.u8(P_AVAIL_STATUS, 0),
                Iap2Params.ascii(P_AVAIL_IDENTIFIER, ""));
        final List<Param> params = new ArrayList<Param>();
        params.add(Iap2Params.group(P_SESSION_WIRED, wired));
        params.add(Iap2Params.group(P_SESSION_WIRELESS, wireless));
        return Iap2Params.encode(params);
    }

    /* ------------------------------------------------------------------ *
     * Accessory -> device messages
     * ------------------------------------------------------------------ */

    /** Accessory identification: what it is and which transports it offers. */
    public static final class Identification {
        public String name = "";
        public String model = "";
        public String manufacturer = "";
        public String serialNumber = "";
        public String firmwareVersion = "";
        public String hardwareVersion = "";
        public int[] messagesSentByAccessory = new int[0];
        public int[] messagesReceivedFromDevice = new int[0];
        public int powerProvidingCapability;
        public int maximumCurrentDrawn;
        public String preferredAppBundleId = "";
        public String currentLanguage = "";
        public String supportedLanguage = "";
        public int bluetoothTransportId = TRANSPORT_ID_BLUETOOTH;
        public String bluetoothTransportName = "";
        public String bluetoothMac = "";
        public boolean bluetoothAvailable;
        public int wirelessTransportId = TRANSPORT_ID_WIRELESS;
        public String wirelessSsid = "";
        public boolean wirelessSupported;
        public boolean wirelessAvailable;
        public boolean wirelessEnabled;

        public boolean advertisesByAccessory(int messageId) {
            return Iap2Params.contains(messagesSentByAccessory, messageId);
        }

        public boolean expectsFromDevice(int messageId) {
            return Iap2Params.contains(messagesReceivedFromDevice, messageId);
        }

        public String describe() {
            return name + "/" + model + " (" + manufacturer + ") sn=" + serialNumber
                    + " fw=" + firmwareVersion + " hw=" + hardwareVersion
                    + " btMac=" + bluetoothMac + " ssid=" + wirelessSsid
                    + " wireless(supported=" + wirelessSupported + ", available=" + wirelessAvailable
                    + ", enabled=" + wirelessEnabled + ")";
        }
    }

    public static Identification parseIdentification(byte[] body) {
        final List<Param> p = Iap2Params.decode(body);
        final Identification id = new Identification();
        id.name = Iap2Params.asciiOf(p, P_ID_NAME, "");
        id.model = Iap2Params.asciiOf(p, P_ID_MODEL, "");
        id.manufacturer = Iap2Params.asciiOf(p, P_ID_MANUFACTURER, "");
        id.serialNumber = Iap2Params.asciiOf(p, P_ID_SERIAL, "");
        id.firmwareVersion = Iap2Params.asciiOf(p, P_ID_FIRMWARE, "");
        id.hardwareVersion = Iap2Params.asciiOf(p, P_ID_HARDWARE, "");

        final Param sent = Iap2Params.find(p, P_ID_MESSAGES_SENT);
        if (sent != null) {
            id.messagesSentByAccessory = Iap2Params.u16List(sent.value);
        }
        final Param received = Iap2Params.find(p, P_ID_MESSAGES_RECEIVED);
        if (received != null) {
            id.messagesReceivedFromDevice = Iap2Params.u16List(received.value);
        }
        id.powerProvidingCapability = Iap2Params.u8Of(p, P_ID_POWER_PROVIDING, 0);
        id.maximumCurrentDrawn = Iap2Params.u16beOf(p, P_ID_MAX_CURRENT, 0);
        id.currentLanguage = Iap2Params.asciiOf(p, P_ID_CURRENT_LANGUAGE, "");
        id.supportedLanguage = Iap2Params.asciiOf(p, P_ID_SUPPORTED_LANGUAGE, "");

        final Param app = Iap2Params.find(p, P_ID_PREFERRED_APP);
        if (app != null) {
            id.preferredAppBundleId = Iap2Params.asciiOf(app.group(), 0x0001, "");
        }

        final Param bt = Iap2Params.find(p, P_ID_BLUETOOTH_TRANSPORT);
        if (bt != null) {
            final List<Param> g = bt.group();
            id.bluetoothTransportId = Iap2Params.u16beOf(g, P_TC_TRANSPORT_IDENTIFIER,
                    TRANSPORT_ID_BLUETOOTH);
            id.bluetoothTransportName = Iap2Params.asciiOf(g, P_TC_NAME, "");
            final Param mac = Iap2Params.find(g, P_TC_BLUETOOTH_MAC);
            id.bluetoothMac = mac == null ? "" : mac.hex();
            id.bluetoothAvailable = Iap2Params.find(g, P_TC_IS_SUPPORTED) != null;
        }

        final Param wifi = Iap2Params.find(p, P_ID_WIRELESS_TRANSPORT);
        if (wifi != null) {
            final List<Param> g = wifi.group();
            id.wirelessTransportId = Iap2Params.u16beOf(g, P_TC_TRANSPORT_IDENTIFIER,
                    TRANSPORT_ID_WIRELESS);
            id.wirelessSsid = Iap2Params.asciiOf(g, P_TC_NAME, "");
            id.wirelessSupported = Iap2Params.find(g, P_TC_IS_SUPPORTED) != null;
            id.wirelessAvailable = Iap2Params.find(g, P_TC_IS_AVAILABLE) != null;
            id.wirelessEnabled = Iap2Params.find(g, P_TC_IS_ENABLED) != null;
        }
        return id;
    }

    /**
     * The marker every loggable form uses in place of the access point's key.  The passphrase is
     * needed to join the group and for nothing else: it must never reach a log line, an event, the
     * WebUI or the logs `okcar-logup` persists and uploads (2026-10-08).  Anything that formats a
     * credential for humans uses this marker instead of the value.
     */
    public static final String REDACTED = "<redacted>";

    /** 0x5703 — the Wi-Fi group the device must join. */
    public static final class WirelessInfo {
        public String ssid = "";
        public String passphrase = "";
        /**
         * The wire value from parameter id 3, or {@link Security#UNKNOWN} when the
         * accessory did not carry one.  It is deliberately not defaulted to WPA2: the join refuses
         * an unknown value with an explicit error (see {@link Security}).
         */
        public int securityType = Security.UNKNOWN;
        public int channel;

        /** The mapped name for logs: {@code wpa2}, or {@code unknown(7)}. */
        public String securityName() {
            return Security.nameOf(securityType);
        }

        public boolean securityKnown() {
            return Security.known(securityType);
        }

        /** True when the hand-off carried a key at all; the value itself stays out of logs. */
        public boolean hasPassphrase() {
            return passphrase != null && !passphrase.isEmpty();
        }

        /**
         * One-line, loggable summary.  The passphrase appears only as a redaction marker or as
         * "absent" — never as the value.  This is the line {@code WirelessCarPlayDriver} logs when
         * 0x5703 arrives, so a real credential must not be reachable through it (the field symptom
         * on 2026-10-08 was a cleartext {@code passphrase=…} in logcat, persisted by okcar-logup).
         */
        public String describe() {
            return "ssid=" + ssid + " security=" + securityName() + "(" + securityType + ')'
                    + (securityKnown() ? "" : " NOT-IN-TABLE") + " channel=" + channel
                    + " passphrase=" + (hasPassphrase() ? REDACTED : "<none>");
        }
    }

    public static WirelessInfo parseWirelessInfo(byte[] body) {
        final List<Param> p = Iap2Params.decode(body);
        final WirelessInfo info = new WirelessInfo();
        info.ssid = Iap2Params.asciiOf(p, P_INFO_SSID, "");
        info.passphrase = Iap2Params.asciiOf(p, P_INFO_PASSPHRASE, "");
        /* No fallback value: an absent securityType is "unknown" and the join says so instead of
         * guessing WPA2 (the field symptom of guessing was a silent wpa2 association attempt
         * against whatever the accessory actually announced). */
        final Param security = Iap2Params.find(p, P_INFO_SECURITY_TYPE);
        info.securityType = security == null
                ? Security.UNKNOWN : security.u8();
        info.channel = Iap2Params.u8Of(p, P_INFO_CHANNEL, 0);
        return info;
    }

    /** 0x4301 — start the session: credentials, accessory AirPlay address and identity. */
    public static final class StartSession {
        /** The 0x4301 came in over the wired (group 0) hand-off rather than as Wi-Fi credentials. */
        public boolean wired;
        public String ssid = "";
        public String passphrase = "";
        public int channel;
        public int securityType = SECURITY_WPA_WPA2;
        public String accessoryAddress = "";
        public long airPlayPort;
        public String deviceIdentifier = "";
        public String publicKeyHex = "";
        public String sourceVersion = "";

        public String describe() {
            return (wired ? "WIRED " : "ssid=" + ssid + " channel=" + channel + " security=" + securityType + " ")
                    + " address=" + accessoryAddress + ":" + airPlayPort
                    + " deviceId=" + deviceIdentifier + " sourceVersion=" + sourceVersion
                    + " publicKey=" + publicKeyHex.length() + "hex";
        }
    }

    public static StartSession parseStartSession(byte[] body) {
        final List<Param> p = Iap2Params.decode(body);
        final StartSession s = new StartSession();
        final Param wired = Iap2Params.find(p, P_SESSION_WIRED);
        if (wired != null) {
            final List<Param> w = wired.group();
            /* The wired hand-off carries the accessory's address(es) for the NCM side; a real iPhone
             * gets several (IPv6 link-local plus the configured one), so keep them comma-joined. */
            s.accessoryAddress = Iap2Params.asciiOf(w, P_WIRED_IP, "");
            s.wired = !s.accessoryAddress.isEmpty();
        }
        final Param wireless = Iap2Params.find(p, P_SESSION_WIRELESS);
        if (wireless != null) {
            final List<Param> w = wireless.group();
            s.ssid = Iap2Params.asciiOf(w, P_WIRELESS_SSID, "");
            s.passphrase = Iap2Params.asciiOf(w, P_WIRELESS_PASSPHRASE, "");
            s.channel = Iap2Params.u8Of(w, P_WIRELESS_CHANNEL, 0);
            s.accessoryAddress = Iap2Params.asciiOf(w, P_WIRELESS_IP, "");
            /* Same rule as 0x5703: an absent security type is unknown, not WPA2 (the wired group 0
             * of 0x4301 has no wireless sub-group at all, and that path never joins). */
            s.securityType = Iap2Params.u8Of(w, P_WIRELESS_SECURITY_TYPE,
                    Security.UNKNOWN);
        }
        s.airPlayPort = Iap2Params.u32beOf(p, P_SESSION_AIRPLAY_PORT, 0);
        s.deviceIdentifier = Iap2Params.asciiOf(p, P_SESSION_DEVICE_IDENTIFIER, "");
        s.publicKeyHex = Iap2Params.asciiOf(p, P_SESSION_PUBLIC_KEY, "");
        s.sourceVersion = Iap2Params.asciiOf(p, P_SESSION_SOURCE_VERSION, "");
        return s;
    }

    public static String name(int messageId) {
        switch (messageId) {
            case MSG_START_IDPS: return "StartIDPS";
            case MSG_IDENTIFICATION: return "IdentificationInformation";
            case MSG_IDENTIFICATION_ACCEPTED: return "IdentificationAccepted";
            case MSG_AUTH_CERTIFICATE_REQUEST: return "AuthCertificateRequest";
            case MSG_AUTH_CERTIFICATE_RESPONSE: return "AuthCertificateResponse";
            case MSG_AUTH_CHALLENGE_REQUEST: return "AuthChallengeRequest";
            case MSG_AUTH_CHALLENGE_RESPONSE: return "AuthChallengeResponse";
            case MSG_AUTH_SUCCEEDED: return "AuthSucceeded";
            case MSG_WIRELESS_CARPLAY_UPDATE: return "WirelessCarPlayUpdate";
            case MSG_TRANSPORT_IDENTIFIER_NOTIFICATION: return "DeviceTransportIdentifierNotification";
            case MSG_REQUEST_WIRELESS_INFO: return "RequestWirelessCarPlayInfo";
            case MSG_WIRELESS_INFO: return "WirelessCarPlayInfo";
            case MSG_CARPLAY_AVAILABILITY: return "CarPlayAvailability";
            case com.okcar.link.iap2.Iap2Message.MSG_HID_REPORT: return "HIDReport";
            case MSG_CARPLAY_START_SESSION: return "CarPlayStartSession";
            default: return "0x" + Integer.toHexString(messageId);
        }
    }
}
