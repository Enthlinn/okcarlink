/*
 * OkcarOS link stack — HAP record framing and RTSP message reassembly for the sealed
 * AirPlay control / event channel.
 *
 * WHY THE REASSEMBLY EXISTS (2026-09-27 field failure):
 *
 * A HAP record is `[u16 LE ciphertext length][ciphertext][16-byte tag]`, and a record is
 * NOT a message boundary: a sender seals each chunk of the plaintext separately (the HAP
 * ceiling is 1024 bytes; Apple's own senders chunk at 16384), so one RTSP reply can span
 * several records.  Reading exactly one record per message desynchronises the stream: the
 * leftover chunk of the previous reply is then handed to the parser as if it were the next
 * reply.
 *
 * Measured against the reference receiver (AirTunes/480.5): its own verbose log gives the
 * /auth-setup 200 as `RTSP/1.0 200 OK / Content-Length: 1076 /
 * Content-Type: application/octet-stream / Server: AirTunes/480.5 / CSeq: 5` — a 114-byte
 * header block, i.e. 1190 plaintext bytes — and it went on the wire as records of 1024 and
 * 166 bytes.  The single-record reader parsed the 1024-byte record (reporting `body=910B`
 * instead of 1076B) and left the 166-byte tail queued; 1 ms later the next sealed exchange
 * (the base SETUP) consumed that tail, decrypted it fine, found no `\r\n\r\n` and collapsed
 * to `-1  body=0B` — even though the receiver had processed the SETUP and answered.  So a
 * sealed reply must be reassembled by its RTSP Content-Length, exactly like the clear-text
 * path does, and any over-read must be kept for the following message.
 *
 * Kept free of Android and of the pairing crypto so the host JVM test in
 * .car-test/airplay-sealed/ drives the exact code the device runs.
 */

package com.okcar.link.airplay;

import java.io.IOException;
import java.io.InputStream;

final class SealedControlFraming {

    /** Unseals one whole wire record (2-byte length prefix + ciphertext + 16-byte tag). */
    interface Unsealer {
        byte[] unseal(byte[] record) throws IOException;
    }

    /** Log sink; an interface so this class carries no Android dependency. */
    interface Log {
        void log(String message);
    }

    private final int mMaxMessageBytes;
    private final Log mLog;
    /** Plaintext already read but not yet consumed by one RTSP message. */
    private byte[] mPending = new byte[0];
    /** How many wire records the last assembled message came from (for the log). */
    private int mLastRecords;

    SealedControlFraming(int maxMessageBytes, Log log) {
        mMaxMessageBytes = maxMessageBytes;
        mLog = log;
    }

    /** A fresh TCP connection always starts a fresh sealed byte stream. */
    void reset() {
        mPending = new byte[0];
        mLastRecords = 0;
    }

    int lastRecords() {
        return mLastRecords;
    }

    /** Plaintext buffered but not yet consumed — the useful "what was expected" detail. */
    int pendingBytes() {
        return mPending.length;
    }

    /**
     * Reads exactly one complete RTSP message: records are read and unsealed until the
     * header block and the Content-Length body it declares are both present.  Whatever is
     * left of the last record stays buffered for the next call.
     */
    byte[] readMessage(InputStream in, Unsealer unsealer) throws IOException {
        mLastRecords = 0;
        while (true) {
            final int headerEnd = indexOf(mPending, "\r\n\r\n");
            if (headerEnd >= 0) {
                final int total = headerEnd + 4 + contentLengthOf(mPending, headerEnd);
                if (total <= mPending.length) {
                    final byte[] message = java.util.Arrays.copyOf(mPending, total);
                    mPending = java.util.Arrays.copyOfRange(mPending, total, mPending.length);
                    return message;
                }
            }
            final byte[] record = readRecord(in);
            mLastRecords++;
            mPending = concat(mPending, unsealer.unseal(record));
            if (mPending.length > mMaxMessageBytes) {
                throw new IOException("sealed reply reached " + mPending.length + " bytes over "
                        + mLastRecords + " record(s) without a complete RTSP message — refusing "
                        + "to buffer more");
            }
        }
    }

    /**
     * The RTSP body length for a message whose header block ends at {@code headerEnd}:
     * 0 when there is no Content-Length and 0 (with a log line) when it is unparseable —
     * the same tolerance the clear-text path has.  Never throws.
     */
    private int contentLengthOf(byte[] data, int headerEnd) {
        final String head = new String(data, 0, headerEnd, java.nio.charset.StandardCharsets.US_ASCII);
        for (String line : head.split("\r\n")) {
            if (line.regionMatches(true, 0, "Content-Length:", 0, 15)) {
                try {
                    return Math.max(0, Integer.parseInt(line.substring(15).trim()));
                } catch (NumberFormatException e) {
                    mLog.log("sealed reply has an unparseable Content-Length: " + line.trim());
                    return 0;
                }
            }
        }
        return 0;
    }

    /** `[u16 LE ciphertext length][ciphertext][16-byte tag]`. */
    static byte[] readRecord(InputStream in) throws IOException {
        final byte[] lengthBytes = readFully(in, 2);
        final int length = (lengthBytes[0] & 0xFF) | ((lengthBytes[1] & 0xFF) << 8);
        return concat(lengthBytes, readFully(in, length + 16));
    }

    static byte[] readFully(InputStream in, int length) throws IOException {
        final byte[] out = new byte[length];
        int read = 0;
        while (read < length) {
            final int n = in.read(out, read, length - read);
            if (n < 0) {
                throw new IOException("connection closed after " + read + "/" + length + " bytes");
            }
            read += n;
        }
        return out;
    }

    static byte[] concat(byte[] a, byte[] b) {
        final byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    static int indexOf(byte[] data, String needle) {
        final byte[] pattern = needle.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        outer:
        for (int i = 0; i + pattern.length <= data.length; i++) {
            for (int j = 0; j < pattern.length; j++) {
                if (data[i + j] != pattern[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    /** Bounded, log-safe rendering of a reply fragment (printable ascii + hex). */
    static String previewOf(byte[] data) {
        final int n = Math.min(data.length, 32);
        final StringBuilder ascii = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            final int b = data[i] & 0xFF;
            ascii.append(b >= 0x20 && b < 0x7F ? (char) b : '.');
        }
        return data.length + "B (\"" + ascii + "\" hex=" + hex(data, n) + ')';
    }

    private static String hex(byte[] data, int n) {
        final StringBuilder sb = new StringBuilder(n * 2);
        for (int i = 0; i < n; i++) {
            final int b = data[i] & 0xFF;
            sb.append(Character.forDigit(b >> 4, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
