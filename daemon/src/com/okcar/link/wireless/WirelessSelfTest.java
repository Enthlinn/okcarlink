/*
 * OkcarOS link stack — wireless CarPlay self test.
 *
 * Everything here is checked against bytes that came off a real iPhone in a real
 * wireless CarPlay session (Bluetooth HCI snoop, 2026-09-26/27,
 * the recorded HCI snoop log).  The accessory in
 * that capture was a head-unit emulator, so the *device* messages (0x1D00,
 * 0x1D02, 0xAA00, 0xAA02, 0xAA05, 0x4E0D, 0x4E0E, 0x5702, 0x4300) are the
 * iPhone's own bytes — our encoders must reproduce them exactly, and our parsers
 * must extract every value the capture contains from the accessory's bytes
 * (0x1D01, 0x5703, 0x4301).
 *
 * Pure Java (no Android imports) so it also runs on the host JVM:
 *
 *     javac -d out src/com/okcar/link/iap2/Iap2Params.java \
 *               src/com/okcar/link/wireless/WirelessMessages.java \
 *               src/com/okcar/link/wireless/WirelessSelfTest.java
 *     java -cp out com.okcar.link.wireless.WirelessSelfTest
 */

package com.okcar.link.wireless;

import com.okcar.link.iap2.Iap2Params;

import java.util.Arrays;

public final class WirelessSelfTest {

    /* ---------------------------------------------------------------- *
     * Captured bodies (hex, verbatim from the 2026-09-26/27 snoop)
     * ---------------------------------------------------------------- */

    /** 0x1D01, accessory -> device, 313 bytes (head-unit emulator "DiPlay"). */
    static final String CAP_IDENTIFICATION =
            "000b00004469506c617900000b00014469506c617900000b00024469506c6179"
            + "00001800034449504c41592d37324637423035374439414400000a0004302e31"
            + "2e300000080005312e300000200006aa01aa035000500252005203ae00ae0241"
            + "574159415441564301570300240007aa00aa02aa04aa05ea00ea015001520152"
            + "02ae014158415543004e0d4e0e570200050008000006000900140028000a0005"
            + "000001001a0001636f6d2e7368696c6170692e7863657274706c617900000500"
            + "02000007000c656e000007000d656e00002e001100060000000000090001626c"
            + "75650000040002000a000372f7b057d9ad00090004626c75650000040005003b"
            + "0018000600000001001f00014449524543542d64703763316338653732346564"
            + "322d3848526e00000400020006000300010004000400040005";

    /** 0x5703, accessory -> device, 62 bytes. */
    static final String CAP_WIRELESS_INFO =
            "001f00014449524543542d64703763316338653732346564322d3848526e0000"
            + "150002687a48656465784b666a4b6d783236670000050003020005000495";

    /** 0x4301, accessory -> device, 207 bytes. */
    static final String CAP_START_SESSION =
            "00600001001f00004449524543542d64703763316338653732346564322d3848"
            + "526e0000150001687a48656465784b666a4b6d78323667000005000295001e00"
            + "03666538303a3a646539323a356261623a343332383a34383335000005000402"
            + "0008000200001b580016000337323a46373a42303a35373a44393a4144000045"
            + "0004316562383664326261333937623738366536653665643862663364393363"
            + "6430306164396136336432353339363062363836313537306537653933323138"
            + "643700000c00053935302e372e3100";

    /** 0x4300, device -> accessory, 69 bytes. */
    static final String CAP_AVAILABILITY =
            "002600000005000001001d000130303030383131303030303630323334304530"
            + "383430314500001f000100050000010016000132633a37633a66323a62613a61"
            + "363a663700";

    /** 0x4E0E, device -> accessory, 51 bytes. */
    static final String CAP_TRANSPORT_IDENTIFIER =
            "0016000032633a37633a66323a62613a61363a663700001d0001303030303831"
            + "31303030303630323334304530383430314500";

    /** 0x4E0D, device -> accessory, 5 bytes. */
    static final String CAP_WIRELESS_UPDATE = "0005000001";

    /** 0xAA02, device -> accessory, 36 bytes (32-byte challenge). */
    static final String CAP_AUTH_CHALLENGE =
            "00240000719ef6d964ba60269429b568fa67ec6ca8355e9c871fddfce057e79b6"
            + "39d6e11";

    /** Identifiers the captured iPhone used (same values appear in 0x4300/0x4E0E). */
    static final String CAP_BLUETOOTH_ID = "2c:7c:f2:ba:a6:f7";
    static final String CAP_DEVICE_ID = "00008110000602340E08401E";
    static final String CAP_SSID = "DIRECT-dp7c1c8e724ed2-8HRn";
    static final String CAP_PASSPHRASE = "hzHedexKfjKmx26g";
    static final String CAP_ACCESSORY_IP = "fe80::de92:5bab:4328:4835";
    static final String CAP_DEVICE_IDENTIFIER = "72:F7:B0:57:D9:AD";
    static final String CAP_SOURCE_VERSION = "950.7.1";

    public static final class Result {
        public int passed;
        public int failed;
        public final StringBuilder report = new StringBuilder();

        public boolean ok() {
            return failed == 0;
        }

        void check(String what, boolean condition, String detail) {
            if (condition) {
                passed++;
                report.append("PASS ").append(what).append('\n');
            } else {
                failed++;
                report.append("FAIL ").append(what).append(" — ").append(detail).append('\n');
            }
        }

        void note(String text) {
            report.append("     ").append(text).append('\n');
        }
    }

    private WirelessSelfTest() {
    }

    public static Result run() {
        final Result r = new Result();

        /* --- 1. accessory identification (0x1D01) -------------------- */
        final byte[] identBody = Iap2Params.fromHex(CAP_IDENTIFICATION);
        r.check("0x1D01 body is 313 bytes", identBody.length == 313,
                "length=" + identBody.length);
        r.check("0x1D01 parses as a whole parameter list",
                Iap2Params.isParamList(identBody), "trailing bytes");
        final WirelessMessages.Identification ident =
                WirelessMessages.parseIdentification(identBody);
        r.check("0x1D01 name/model/manufacturer",
                ident.name.equals("DiPlay") && ident.model.equals("DiPlay")
                        && ident.manufacturer.equals("DiPlay"),
                ident.describe());
        r.check("0x1D01 serial = DIPLAY-72F7B057D9AD",
                ident.serialNumber.equals("DIPLAY-72F7B057D9AD"), ident.serialNumber);
        r.check("0x1D01 firmware/hardware = 0.1.0 / 1.0",
                ident.firmwareVersion.equals("0.1.0") && ident.hardwareVersion.equals("1.0"),
                ident.firmwareVersion + " / " + ident.hardwareVersion);
        r.check("0x1D01 accessory advertises 0x5703",
                ident.advertisesByAccessory(WirelessMessages.MSG_WIRELESS_INFO),
                Arrays.toString(ident.messagesSentByAccessory));
        r.check("0x1D01 accessory also advertises 0x4301",
                ident.advertisesByAccessory(WirelessMessages.MSG_CARPLAY_START_SESSION),
                Arrays.toString(ident.messagesSentByAccessory));
        r.check("0x1D01 accessory expects 0x4300 from us",
                ident.expectsFromDevice(WirelessMessages.MSG_CARPLAY_AVAILABILITY),
                Arrays.toString(ident.messagesReceivedFromDevice));
        r.check("0x1D01 accessory expects 0x5702 / 0x4E0E from us",
                ident.expectsFromDevice(WirelessMessages.MSG_REQUEST_WIRELESS_INFO)
                        && ident.expectsFromDevice(
                                WirelessMessages.MSG_TRANSPORT_IDENTIFIER_NOTIFICATION),
                Arrays.toString(ident.messagesReceivedFromDevice));
        r.check("0x1D01 preferred app = com.shilapi.xcertplay",
                ident.preferredAppBundleId.equals("com.shilapi.xcertplay"),
                ident.preferredAppBundleId);
        r.check("0x1D01 languages = en/en",
                ident.currentLanguage.equals("en") && ident.supportedLanguage.equals("en"),
                ident.currentLanguage + "/" + ident.supportedLanguage);
        r.check("0x1D01 BluetoothTransportComponent (id 0, mac)",
                ident.bluetoothTransportId == WirelessMessages.TRANSPORT_ID_BLUETOOTH
                        && ident.bluetoothMac.equals("72f7b057d9ad"),
                "id=" + ident.bluetoothTransportId + " mac=" + ident.bluetoothMac);
        r.check("0x1D01 WirelessCarPlayTransportComponent (id 1, ssid)",
                ident.wirelessTransportId == WirelessMessages.TRANSPORT_ID_WIRELESS
                        && ident.wirelessSsid.equals(CAP_SSID),
                "id=" + ident.wirelessTransportId + " ssid=" + ident.wirelessSsid);
        r.check("0x1D01 power/current = 0 / 20",
                ident.powerProvidingCapability == 0 && ident.maximumCurrentDrawn == 20,
                ident.powerProvidingCapability + " / " + ident.maximumCurrentDrawn);

        /* --- 2. wireless credentials (0x5703) ----------------------- */
        final WirelessMessages.WirelessInfo info = WirelessMessages.parseWirelessInfo(
                Iap2Params.fromHex(CAP_WIRELESS_INFO));
        r.check("0x5703 ssid/passphrase", info.ssid.equals(CAP_SSID)
                        && info.passphrase.equals(CAP_PASSPHRASE),
                info.describe());
        r.check("0x5703 security = WPA/WPA2 (2)", info.securityType == 2,
                "security=" + info.securityType);
        r.check("0x5703 channel = 149 (5 GHz)", info.channel == 149,
                "channel=" + info.channel);

        /* --- 3. start session (0x4301) ------------------------------ */
        final WirelessMessages.StartSession session = WirelessMessages.parseStartSession(
                Iap2Params.fromHex(CAP_START_SESSION));
        r.check("0x4301 credentials agree with 0x5703",
                session.ssid.equals(info.ssid) && session.passphrase.equals(info.passphrase)
                        && session.channel == info.channel
                        && session.securityType == info.securityType,
                session.describe());
        r.check("0x4301 accessory address", session.accessoryAddress.equals(CAP_ACCESSORY_IP),
                session.accessoryAddress);
        r.check("0x4301 airPlayPort = 7000", session.airPlayPort == 7000,
                "port=" + session.airPlayPort);
        r.check("0x4301 deviceIdentifier", session.deviceIdentifier.equals(CAP_DEVICE_IDENTIFIER),
                session.deviceIdentifier);
        r.check("0x4301 public key = 64 hex chars (Ed25519)",
                session.publicKeyHex.length() == 64, "len=" + session.publicKeyHex.length());
        r.check("0x4301 sourceVersion = 950.7.1",
                session.sourceVersion.equals(CAP_SOURCE_VERSION), session.sourceVersion);
        r.note("accessory session: " + session.describe());

        /* --- 4. our encoders must reproduce the iPhone's bytes ------- */
        r.check("0x4E0D encoder is byte exact",
                Iap2Params.toHex(WirelessMessages.wirelessCarplayUpdate(true))
                        .equals(CAP_WIRELESS_UPDATE),
                Iap2Params.toHex(WirelessMessages.wirelessCarplayUpdate(true)));
        r.check("0x4E0E encoder is byte exact",
                Iap2Params.toHex(WirelessMessages.transportIdentifierNotification(
                        CAP_BLUETOOTH_ID, CAP_DEVICE_ID)).equals(CAP_TRANSPORT_IDENTIFIER),
                Iap2Params.toHex(WirelessMessages.transportIdentifierNotification(
                        CAP_BLUETOOTH_ID, CAP_DEVICE_ID)));
        r.check("0x4300 encoder is byte exact",
                Iap2Params.toHex(WirelessMessages.carPlayAvailability(
                        true, CAP_DEVICE_ID, CAP_BLUETOOTH_ID)).equals(CAP_AVAILABILITY),
                Iap2Params.toHex(WirelessMessages.carPlayAvailability(
                        true, CAP_DEVICE_ID, CAP_BLUETOOTH_ID)));

        final byte[] challengeBody = Iap2Params.fromHex(CAP_AUTH_CHALLENGE);
        final byte[] challenge = new byte[32];
        System.arraycopy(challengeBody, 4, challenge, 0, 32);
        r.check("0xAA02 encoder is byte exact", Iap2Params.toHex(
                        WirelessMessages.authChallengeRequest(challenge))
                        .equals(CAP_AUTH_CHALLENGE),
                Iap2Params.toHex(WirelessMessages.authChallengeRequest(challenge)));

        r.check("empty-body messages are empty",
                WirelessMessages.startIdps().length == 0
                        && WirelessMessages.identificationAccepted().length == 0
                        && WirelessMessages.authCertificateRequest().length == 0
                        && WirelessMessages.requestWirelessInfo().length == 0
                        && WirelessMessages.authSucceeded().length == 0,
                "one of 0x1D00/0x1D02/0xAA00/0x5702/0xAA05 is not empty");

        /* --- 5. parameter codec invariants -------------------------- */
        final byte[] round = Iap2Params.encode(Arrays.asList(
                Iap2Params.u8(0x0001, 7),
                Iap2Params.u16be(0x0002, 0xBEEF),
                Iap2Params.u32be(0x0003, 7000),
                Iap2Params.ascii(0x0004, "ok"),
                Iap2Params.voidParam(0x0005)));
        r.check("parameter round trip", Iap2Params.isParamList(round)
                        && Iap2Params.u8Of(Iap2Params.decode(round), 1, -1) == 7
                        && Iap2Params.u16beOf(Iap2Params.decode(round), 2, -1) == 0xBEEF
                        && Iap2Params.u32beOf(Iap2Params.decode(round), 3, -1) == 7000
                        && Iap2Params.asciiOf(Iap2Params.decode(round), 4, "?")
                                .equals("ok"),
                Iap2Params.toHex(round));

        /* Negative control: a truncated / over-long parameter must not be accepted. */
        final byte[] broken = Iap2Params.fromHex("0005000001" + "00ff" + "0002");
        r.check("truncated parameter list rejected", !Iap2Params.isParamList(broken),
                Iap2Params.toHex(broken));
        r.check("decoder stops instead of throwing",
                Iap2Params.decode(broken).size() == 1,
                "params=" + Iap2Params.decode(broken).size());

        return r;
    }

    public static void main(String[] args) {
        final Result r = run();
        System.out.print(r.report);
        System.out.println();
        System.out.println("wireless self test: " + r.passed + " passed, " + r.failed + " failed");
        if (!r.ok()) {
            System.exit(1);
        }
    }
}
