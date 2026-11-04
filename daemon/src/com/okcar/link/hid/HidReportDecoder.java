/*
 * OkcarOS link stack — L5: head-unit HID reports.
 *
 * The head unit sends its touchscreen / button events through the iAP2 HID trio
 * (0x6800 startHID, 0x6802 HIDReport, 0x6803 stopHID) — NOT through any HID
 * gadget: in the field the head unit exposes only a vendor interface plus two
 * cdc_ncm interfaces, and its own UI builds the reports itself.
 *
 * Report shapes, from the head unit's AppleService/CarPlayFeature plus the
 * Touch Screen descriptor in airplay.conf (exeed findings I §1.1):
 *
 *   touch : [0x54 'T', status, xLo, xHi, yLo, yHi]       (6 bytes on the tunnel)
 *   media : [0x4D 'M', button]                            (2 bytes)
 *   phone : [0x50 'P', b0, b1, b2]                        (4 bytes)
 *
 * The iAP2 HID descriptor is 5 bytes — 1 bit touch + 7 bit pad + X(16) +
 * Y(16), X in 0..1920 and Y in 0..720 — so an iAP2 report that does not carry
 * the leading type byte is decoded with that layout too.
 *
 * The wireless CarPlay *AirPlay* receiver is different: its own embedded Touch
 * Screen HID report descriptor (the `Fruit Link`/autochips app's
 * libcarplay_jni.so @0x15c738) declares one 6-byte finger slot
 *
 *   [report/contact id u8][Touch bit + 7 pad][X u16 LE][Y u16 LE]
 *
 * and a 12-byte payload is two such slots.  The descriptor advertises X/Y as
 * 16-bit logical values 0..0x7fff, but the receiver actually fills them with
 * <em>its own screen pixels</em> — see {@link #AIRPLAY_TOUCH_BIT} and the note on
 * the removed 32768 space below.  {@link #decodeWireless} classifies that layout by
 * payload length; {@link #decode} stays exactly as it was so the wired/known-good
 * iAP2 path cannot change.  The down/up bit and the slot layout are settled from
 * the receiver's own code plus real 217 bytes — see {@link #AIRPLAY_TOUCH_BIT}.
 */

package com.okcar.link.hid;

public final class HidReportDecoder {

    public static final int TYPE_TOUCH = 0x54; // 'T'
    public static final int TYPE_MEDIA = 0x4D; // 'M'
    public static final int TYPE_PHONE = 0x50; // 'P'

    /** Head-unit iAP2 touch space, straight out of its HID descriptor. */
    public static final int TOUCH_SPACE_WIDTH = 1920;
    public static final int TOUCH_SPACE_HEIGHT = 720;

    /**
     * AirPlay touch slots carry <em>no</em> usable 0..0x7fff logical range, despite
     * what the receiver's own Touch Screen descriptor advertises.
     *
     * <p>That descriptor ({@code HIDTouchScreenSingleCreateDescriptor} in
     * {@code libap-lib.so}) declares X and Y as 16-bit logical values 0..0x7fff, but
     * the receiver fills those fields with <em>its own screen pixels</em>.  Across the
     * 6926 AirPlay reports in the captured lmi session the extremes are (1012, 2337)
     * on a 1080x2400 panel (the head unit's {@code wm size} on 192.0.2.217), and the
     * observed span is exactly a subset of that panel — a receiver scaling into 0..0x7fff
     * would not cap x at 1012 while dragging across the screen.  Declaring 32768 shrank
     * every touch by ~30x into the top-left (x*1080/32768, y*2400/32768).
     *
     * <p>An AirPlay slot therefore reports {@code sourceWidth == sourceHeight == 0} and
     * lets {@code InputInjector} use the session's configured space (its
     * {@code setSourceSpace} value, installed by the wireless path from the video size:
     * 1080x2400 on this link).  That is the same space the receiver's pixels are in, so
     * a report is injected where the receiver intended.  Kept as a per-report decision
     * (not a global) so iAP2 1920x720 reports in the same session are unaffected.
     */
    public static final int AIRPLAY_TOUCH_SPACE_DELEGATED = 0;

    /** Byte length of one AirPlay finger slot (see the class comment). */
    public static final int AIRPLAY_SLOT_BYTES = 6;

    /**
     * The receiver's HID <em>Touch</em> usage inside the slot's status byte (slot[1]): bit 0.
     *
     * <p>Both ends of the path were read from the receiver's own code, so this is not a guess:
     * the report descriptor of {@code HIDTouchScreenSingleCreateDescriptor}
     * ({@code libap-lib.so}: {@code 05 0D 09 04 A1 01 / 05 0D 09 22 A1 02 / 05 0D 09 33 /
     * 15 00 25 01 75 01 95 01 81 02 / 75 07 95 01 81 01 / 05 01 09 30 ... / 09 31 ...}) declares
     * exactly one 1-bit field before X and Y — Usage <b>Touch</b> (0x33), logical 0..1 — followed
     * by 7 constant pad bits, X u16 LE (0..0x7fff) and Y u16 LE.  The generator,
     * {@code ttoxscreen123} in {@code libcarplay_jni.so}, writes the 12-byte payload as
     * {@code [0]=(press>>8)+1, [1]=press&0xff, [2..3]=X u16 LE, [4..5]=Y u16 LE}, so slot[1] is
     * the status byte whose bit 0 is the Touch field and slot[0] is only the report/contact id.
     * The live 217 receiver confirms it: every captured press/move report is {@code 01 01 ...}
     * and the releases decode as up (the deployed build logs
     * {@code HID from event channel [AirPlay 12-byte/6B]: touch up}); payload
     * {@code 01 00 a7 02 c5 08 00 00 00 00 00 00} is the release of the captured press
     * {@code 01 01 a7 02 c5 08 00 00 00 00 00 00}.
     *
     * <p>Bit 1 is <em>In Range</em> in the receiver's cancel descriptor
     * ({@code HIDTouchScreenSingleCancelCreateDescriptor}: Touch + In Range + 6 pad), which is
     * why a status of 0x02 must stay <b>up</b>.  A {@code slot[1] != 0} rule would call that
     * release a press and leave the finger stuck down; the Touch bit is the correct and the
     * strictly safer test.
     */
    public static final int AIRPLAY_TOUCH_BIT = 0x01;

    public static final class Report {
        /** One of TYPE_TOUCH / TYPE_MEDIA / TYPE_PHONE. */
        public final int type;
        public final boolean down;
        public final int x;
        public final int y;
        public final int button;
        public final int rawLength;
        public final boolean typed;
        /**
         * True when this report came off the receiver's AirPlay 6-byte finger-slot
         * layout ({@link #decodeWireless}), false for iAP2.  It exists so callers can
         * label the report without inferring the layout from the coordinate space,
         * which an AirPlay slot no longer declares (see
         * {@link #AIRPLAY_TOUCH_SPACE_DELEGATED}).
         */
        public final boolean airplay;
        /**
         * Coordinate space this report must be scaled from.  0 means "use the
         * injector's configured space" (legacy behaviour).  The iAP2 decoder sets it to
         * 1920x720 explicitly and an AirPlay slot leaves it 0, so both can be injected
         * in the same session without any mutable global deciding between them.
         */
        public final int sourceWidth;
        public final int sourceHeight;

        Report(int type, boolean down, int x, int y, int button, int rawLength, boolean typed) {
            this(type, down, x, y, button, rawLength, typed, false, 0, 0);
        }

        Report(int type, boolean down, int x, int y, int button, int rawLength, boolean typed,
                int sourceWidth, int sourceHeight) {
            this(type, down, x, y, button, rawLength, typed, false, sourceWidth, sourceHeight);
        }

        Report(int type, boolean down, int x, int y, int button, int rawLength, boolean typed,
                boolean airplay, int sourceWidth, int sourceHeight) {
            this.type = type;
            this.down = down;
            this.x = x;
            this.y = y;
            this.button = button;
            this.rawLength = rawLength;
            this.typed = typed;
            this.airplay = airplay;
            this.sourceWidth = sourceWidth;
            this.sourceHeight = sourceHeight;
        }

        /**
         * Builds one touch report.  Used by {@code InputInjector.injectTap} and the module
         * daemon's `tap` command: the bring-up probe has to go through the very same report
         * shape the receiver's own touches take, otherwise it proves nothing about that path.
         *
         * @param sourceWidth  the space the coordinates are in; 0 means "the injector's space"
         */
        public static Report touch(int x, int y, boolean down, int sourceWidth,
                                   int sourceHeight) {
            return new Report(TYPE_TOUCH, down, x, y, 0, 0, false, false,
                    sourceWidth, sourceHeight);
        }

        public String describe() {
            switch (type) {
                case TYPE_TOUCH:
                    return "touch " + (down ? "down" : "up") + " (" + x + "," + y + ')';
                case TYPE_MEDIA:
                    return "media button " + button;
                default:
                    return "phone button " + button;
            }
        }
    }

    private HidReportDecoder() {
    }

    /**
     * Decodes one iAP2 HID report (the wired / known-good layout).
     *
     * @return the decoded report, or null when the bytes are not a report we
     *         understand (the caller should log and ignore them).
     */
    public static Report decode(byte[] data, int offset, int length) {
        if (data == null || length <= 0 || offset < 0 || offset + length > data.length) {
            return null;
        }
        final int first = data[offset] & 0xFF;

        if (first == TYPE_TOUCH && length >= 6) {
            return decodeTouch(data, offset + 1, length - 1, true);
        }
        if (first == TYPE_MEDIA && length >= 2) {
            return new Report(TYPE_MEDIA, true, 0, 0, data[offset + 1] & 0xFF, length, true);
        }
        if (first == TYPE_PHONE && length >= 4) {
            final int button = ((data[offset + 1] & 0xFF) << 16)
                    | ((data[offset + 2] & 0xFF) << 8)
                    | (data[offset + 3] & 0xFF);
            return new Report(TYPE_PHONE, true, 0, 0, button, length, true);
        }
        // No tunnel type byte: the 5-byte HID descriptor layout.
        if (length >= 5) {
            return decodeTouch(data, offset, length, false);
        }
        return null;
    }

    public static Report decode(byte[] data) {
        return data == null ? null : decode(data, 0, data.length);
    }

    /**
     * Decodes one HID report as it arrives on the *wireless* (AirPlay) path.
     *
     * The receiver's Touch Screen descriptor is one 6-byte finger slot
     * `[contact/slot u8][Touch bit + 7 pad][X u16 LE][Y u16 LE]`, and a 12-byte
     * payload is two slots.  This method therefore classifies the AirPlay layout
     * by <em>payload length</em>, unlike the value-sniffing
     * {@link #looksLikeAirPlayTouch} heuristic the event channel used before.
     *
     * A 6-byte payload cannot be told apart from a `0x54`-typed 6-byte iAP2
     * report by length alone, so the typed iAP2 reports are dispatched exactly
     * as {@link #decode} does first; only untyped 6/12-byte payloads take the
     * AirPlay path.  The AirPlay slot's first byte is a 0/1-ish contact or slot
     * field, never 0x54/0x4D/0x50, so the two rules cannot collide.
     *
     * Used only by {@code WirelessAirPlayClient}; the wired path keeps calling
     * {@link #decode}.
     */
    public static Report decodeWireless(byte[] data, int offset, int length) {
        if (data == null || length <= 0 || offset < 0 || offset + length > data.length) {
            return null;
        }
        final int first = data[offset] & 0xFF;
        if ((first == TYPE_TOUCH && length >= 6)
                || (first == TYPE_MEDIA && length >= 2)
                || (first == TYPE_PHONE && length >= 4)) {
            return decode(data, offset, length);
        }
        if (length == AIRPLAY_SLOT_BYTES) {
            return decodeAirPlaySlot(data, offset);
        }
        if (length == 2 * AIRPLAY_SLOT_BYTES) {
            return decodeAirPlaySlots(data, offset, 2);
        }
        // Anything else keeps the old iAP2 5-byte behaviour (and null for junk).
        return decode(data, offset, length);
    }

    public static Report decodeWireless(byte[] data) {
        return data == null ? null : decodeWireless(data, 0, data.length);
    }

    /**
     * First finger that is down wins; when every slot is up the first slot still
     * carries the released position, which is what a lift event must inject.
     */
    private static Report decodeAirPlaySlots(byte[] data, int offset, int slots) {
        Report firstUp = null;
        for (int i = 0; i < slots; i++) {
            final Report report = decodeAirPlaySlot(data, offset + i * AIRPLAY_SLOT_BYTES);
            if (report == null) {
                break;
            }
            if (report.down) {
                return report;
            }
            if (firstUp == null) {
                firstUp = report;
            }
        }
        return firstUp;
    }

    /**
     * One AirPlay finger slot: `[report/contact id u8][Touch bit + 7 pad][X u16][Y u16]`.
     *
     * <p>Down/up is the slot's <em>Touch</em> bit ({@link #AIRPLAY_TOUCH_BIT}, bit 0 of slot[1]),
     * not the 8-bit first field: the receiver's generator writes slot[0] = (press&gt;&gt;8)+1, which
     * is the constant 1 for every single-finger report — press <em>and</em> release — so it
     * carries no down/up information at all.  See {@link #AIRPLAY_TOUCH_BIT} for the descriptor
     * and generator evidence, and for why `slot[1] != 0` is the wrong rule.
     */
    private static Report decodeAirPlaySlot(byte[] data, int offset) {
        if (offset < 0 || offset + AIRPLAY_SLOT_BYTES > data.length) {
            return null;
        }
        final int status = data[offset + 1] & 0xFF;
        final boolean down = (status & AIRPLAY_TOUCH_BIT) != 0;
        final int x = (data[offset + 2] & 0xFF) | ((data[offset + 3] & 0xFF) << 8);
        final int y = (data[offset + 4] & 0xFF) | ((data[offset + 5] & 0xFF) << 8);
        // Not clamped and not rescaled: these are the receiver's own screen pixels
        // (1080x2400 on this link).  The slot declares no source space, so the
        // injector scales from its configured session space (the video size), which is
        // the same space — see AIRPLAY_TOUCH_SPACE_DELEGATED.
        return new Report(TYPE_TOUCH, down, x, y, 0, AIRPLAY_SLOT_BYTES, false, true,
                AIRPLAY_TOUCH_SPACE_DELEGATED, AIRPLAY_TOUCH_SPACE_DELEGATED);
    }

    /**
     * True when the payload looks like the old value-sniffed AirPlay shape: an
     * even multiple of 6 bytes whose first slot has pointer id 0 and a 0/1 down
     * flag.  Kept for the harness / historical callers only — the wireless path
     * no longer uses it, because the 217 receiver's 6-byte slot has a non-zero
     * contact/slot byte on every report, so this returns false for its presses
     * and used to mis-route them into the 5-byte iAP2 decoder.
     */
    public static boolean looksLikeAirPlayTouch(byte[] data) {
        return data != null && data.length >= 6 && data.length % 6 == 0
                && (data[0] & 0xFF) == 0 && (data[1] & 0xFF) <= 1;
    }

    /**
     * Decodes the old value-sniffed AirPlay two-finger report (see
     * {@link #looksLikeAirPlayTouch}).  Kept for compatibility; the wireless path
     * uses {@link #decodeWireless} instead.
     */
    public static Report decodeAirPlayTouch(byte[] data) {
        if (data == null || data.length < 6) {
            return null;
        }
        Report first = null;
        for (int i = 0; i + 6 <= data.length && i < 12; i += 6) {
            final int down = data[i + 1] & 0xFF;
            final int x = (data[i + 2] & 0xFF) | ((data[i + 3] & 0xFF) << 8);
            final int y = (data[i + 4] & 0xFF) | ((data[i + 5] & 0xFF) << 8);
            final Report report = new Report(TYPE_TOUCH, down != 0, x, y, 0, data.length, false,
                    true, 0, 0);
            if (down != 0) {
                return report;
            }
            if (first == null) {
                first = report;
            }
        }
        return first;
    }

    private static Report decodeTouch(byte[] data, int offset, int available, boolean typed) {
        if (available < 5) {
            return null;
        }
        final int status = data[offset] & 0xFF;
        final boolean down = (status & 0x01) != 0;
        int x = (data[offset + 1] & 0xFF) | ((data[offset + 2] & 0xFF) << 8);
        int y = (data[offset + 3] & 0xFF) | ((data[offset + 4] & 0xFF) << 8);
        x = clamp(x, TOUCH_SPACE_WIDTH);
        y = clamp(y, TOUCH_SPACE_HEIGHT);
        return new Report(TYPE_TOUCH, down, x, y, 0, typed ? available + 1 : available, typed,
                TOUCH_SPACE_WIDTH, TOUCH_SPACE_HEIGHT);
    }

    private static int clamp(int value, int max) {
        if (value < 0) {
            return 0;
        }
        return value > max ? max : value;
    }
}
