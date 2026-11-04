/*
 * OkcarOS link stack — small byte helpers.
 */

package com.okcar.link.util;

import java.util.Locale;

public final class Hex {

    private static final char[] DIGITS = "0123456789abcdef".toCharArray();

    private Hex() {
    }

    public static String encode(byte[] data) {
        return data == null ? "null" : encode(data, 0, data.length);
    }

    public static String encode(byte[] data, int offset, int length) {
        if (data == null) {
            return "null";
        }
        final int start = Math.max(0, offset);
        final int end = Math.min(data.length, start + Math.max(0, length));
        final StringBuilder sb = new StringBuilder((end - start) * 3);
        for (int i = start; i < end; i++) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            final int value = data[i] & 0xFF;
            sb.append(DIGITS[value >>> 4]).append(DIGITS[value & 0x0F]);
        }
        return sb.toString();
    }

    /** Parses "ff 55 02 00 ee 10" / "ff550200ee10" into bytes. */
    public static byte[] decode(String text) {
        if (text == null) {
            return new byte[0];
        }
        final StringBuilder clean = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            final char c = text.charAt(i);
            if (isHexDigit(c)) {
                clean.append(c);
            }
        }
        final int count = clean.length() / 2;
        final byte[] out = new byte[count];
        for (int i = 0; i < count; i++) {
            out[i] = (byte) Integer.parseInt(
                    clean.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private static boolean isHexDigit(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    public static String u16(int value) {
        return String.format(Locale.US, "0x%04x", value & 0xFFFF);
    }

    public static String u8(int value) {
        return String.format(Locale.US, "0x%02x", value & 0xFF);
    }
}
