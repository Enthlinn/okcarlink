/*
 * OkcarOS link stack — iAP2 parameter codec (transport independent, pure Java).
 *
 * Every iAP2 message body is a concatenation of parameters:
 *
 *     [BE16 length] [BE16 id] [value ...]        length = 4 + value.length
 *
 * ★ The order is **length first, then id**, and the length counts the 4-byte
 * header — taken from the bytes a real iPhone put on the wire in a wireless
 * CarPlay session (captured 2026-09-26/27, doc §12.38): the SSID parameter of
 * 0x5703 is `00 1f 00 01 "DIRECT-dp…8HRn" 00`, i.e. length 0x1f = 31 = 4 + 27.
 * Note that the older `Iap2Identification.parameter()` in this same package
 * writes [id][length] with the length *excluding* the header; that is what the
 * capture contradicts (see §12.38 D and the TODO in §6).
 *
 * A parameter whose value is itself a well-formed parameter list is a *group*
 * (the accessory's identification uses groups for the transport components).
 *
 * The layout is not guessed: the wireless CarPlay handshake was captured in the
 * clear over Bluetooth RFCOMM on 2026-09-26/27 and every field below was decoded
 * from those bytes (see the field-rework notes §12.38 and
 * the internal notes).
 *
 * Pure Java on purpose: the same class is exercised on the host JVM by
 * com.okcar.link.wireless.WirelessSelfTest, which re-decodes the captured bytes.
 */

package com.okcar.link.iap2;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class Iap2Params {

    public static final class Param {
        public final int id;
        public final byte[] value;

        public Param(int id, byte[] value) {
            this.id = id;
            this.value = value == null ? new byte[0] : value;
        }

        public int size() {
            return 4 + value.length;
        }

        public int u8() {
            return value.length > 0 ? value[0] & 0xFF : 0;
        }

        public int u16be() {
            return value.length >= 2 ? ((value[0] & 0xFF) << 8) | (value[1] & 0xFF) : 0;
        }

        public long u32be() {
            long r = 0;
            for (int i = 0; i < Math.min(4, value.length); i++) {
                r = (r << 8) | (value[i] & 0xFF);
            }
            return r;
        }

        /** ASCII string with the accessory's trailing NUL stripped. */
        public String ascii() {
            int n = value.length;
            while (n > 0 && value[n - 1] == 0) {
                n--;
            }
            return new String(value, 0, n, StandardCharsets.UTF_8);
        }

        public String hex() {
            return toHex(value);
        }

        /** Re-read this value as a parameter list (only meaningful for groups). */
        public List<Param> group() {
            return decode(value);
        }
    }

    private Iap2Params() {
    }

    public static Param of(int id, byte[] value) {
        return new Param(id, value);
    }

    public static Param u8(int id, int v) {
        return new Param(id, new byte[]{(byte) (v & 0xFF)});
    }

    public static Param u16be(int id, int v) {
        return new Param(id, new byte[]{(byte) ((v >> 8) & 0xFF), (byte) (v & 0xFF)});
    }

    public static Param u32be(int id, long v) {
        return new Param(id, new byte[]{
                (byte) ((v >> 24) & 0xFF), (byte) ((v >> 16) & 0xFF),
                (byte) ((v >> 8) & 0xFF), (byte) (v & 0xFF)});
    }

    /** ASCII (or UTF-8) string, NUL terminated — the accessory's own convention. */
    public static Param ascii(int id, String s) {
        final byte[] raw = s == null ? new byte[0] : s.getBytes(StandardCharsets.UTF_8);
        final byte[] out = new byte[raw.length + 1];
        System.arraycopy(raw, 0, out, 0, raw.length);
        return new Param(id, out);
    }

    /** An empty (void) parameter: length 4, no value — "isSupported" style flags. */
    public static Param voidParam(int id) {
        return new Param(id, new byte[0]);
    }

    public static Param group(int id, List<Param> sub) {
        return new Param(id, encode(sub));
    }

    public static byte[] encode(List<Param> params) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (final Param p : params) {
            final int len = 4 + p.value.length;
            out.write((len >> 8) & 0xFF);
            out.write(len & 0xFF);
            out.write((p.id >> 8) & 0xFF);
            out.write(p.id & 0xFF);
            out.write(p.value, 0, p.value.length);
        }
        return out.toByteArray();
    }

    public static List<Param> decode(byte[] body) {
        final List<Param> out = new ArrayList<Param>();
        if (body == null) {
            return out;
        }
        int i = 0;
        while (i + 4 <= body.length) {
            final int len = ((body[i] & 0xFF) << 8) | (body[i + 1] & 0xFF);
            final int id = ((body[i + 2] & 0xFF) << 8) | (body[i + 3] & 0xFF);
            if (len < 4 || i + len > body.length) {
                break;
            }
            final byte[] value = new byte[len - 4];
            System.arraycopy(body, i + 4, value, 0, value.length);
            out.add(new Param(id, value));
            i += len;
        }
        return out;
    }

    /** True when {@code body} is a complete, well formed parameter list. */
    public static boolean isParamList(byte[] body) {
        if (body == null || body.length == 0) {
            return false;
        }
        int i = 0;
        while (i + 4 <= body.length) {
            final int len = ((body[i] & 0xFF) << 8) | (body[i + 1] & 0xFF);
            if (len < 4 || i + len > body.length) {
                return false;
            }
            i += len;
        }
        return i == body.length;
    }

    public static Param find(List<Param> params, int id) {
        for (final Param p : params) {
            if (p.id == id) {
                return p;
            }
        }
        return null;
    }

    public static String asciiOf(List<Param> params, int id, String fallback) {
        final Param p = find(params, id);
        return p == null ? fallback : p.ascii();
    }

    public static int u8Of(List<Param> params, int id, int fallback) {
        final Param p = find(params, id);
        return p == null ? fallback : p.u8();
    }

    public static int u16beOf(List<Param> params, int id, int fallback) {
        final Param p = find(params, id);
        return p == null ? fallback : p.u16be();
    }

    public static long u32beOf(List<Param> params, int id, long fallback) {
        final Param p = find(params, id);
        return p == null ? fallback : p.u32be();
    }

    /** Big-endian u16 list (the "messages sent/received" sets inside identification). */
    public static int[] u16List(byte[] value) {
        if (value == null) {
            return new int[0];
        }
        final int[] out = new int[value.length / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = ((value[2 * i] & 0xFF) << 8) | (value[2 * i + 1] & 0xFF);
        }
        return out;
    }

    public static byte[] u16List(int... ids) {
        final byte[] out = new byte[ids.length * 2];
        for (int i = 0; i < ids.length; i++) {
            out[2 * i] = (byte) ((ids[i] >> 8) & 0xFF);
            out[2 * i + 1] = (byte) (ids[i] & 0xFF);
        }
        return out;
    }

    public static boolean contains(int[] ids, int wanted) {
        for (final int id : ids) {
            if (id == wanted) {
                return true;
            }
        }
        return false;
    }

    public static String toHex(byte[] data) {
        final StringBuilder sb = new StringBuilder(data.length * 2);
        for (final byte b : data) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    public static byte[] fromHex(String hex) {
        final String s = hex == null ? "" : hex.replace(":", "").replace(" ", "").replace("\n", "");
        final byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        }
        return out;
    }
}
