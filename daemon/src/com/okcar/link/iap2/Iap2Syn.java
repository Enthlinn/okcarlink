/*
 * OkcarOS link stack — L3: iAP2 SYN parameters.
 *
 * The payload layout used to be a guess here (TODO(wire)).  It is now read
 * straight out of the production head unit's unstripped x86_64 libiPodForiAP2.so —
 * a neighbor reverse-engineering project's findings for the same head unit,
 * absorbed in §12.28:
 *
 *   iAP2PacketCreateSYNPacket @0x4bf90 builds the payload on the stack:
 *
 *     [0] = arg4 low       (link+0xB8)      ┐
 *     [1] = arg5           (link+0xE5)      │
 *     [2] = arg4 high      (0 on this path) │ fixed block
 *     [3] = arg6 low       (link+0xE8)      │ 10 bytes
 *     [4..5] = arg7 u16 BE (link+0xEA)      │
 *     [6..7] = arg8 u16 BE (link+0xEC)      │
 *     [8] = arg9           (link+0xE6)      │
 *     [9] = arg10          (link+0xE7)      ┘
 *     then N × 3-byte entries copied from link+0xF2, N = link+0xEE (the copy
 *     loop is capped at 10 entries), and the length handed to iAP2PacketCreate
 *     is 10 + 3*N.
 *
 * A real 16-byte SYN payload seen by the neighbor project lines up exactly with
 * this structure (16 = 10 + 3*2):
 *
 *     01 05 20 00 00 00 00 00 00 00 | 01 00 01 | 02 00 00
 *
 * What is NOT known yet: the *semantics* of the ten fixed bytes (only their
 * source offsets are visible) and which byte of an entry is the id — the debug
 * strings there speak of "session %u [id=%u type=%u ver=%u]", so entries are
 * per-session parameter triples.
 *
 * Therefore: parsing and encoding follow the verified structure, the outgoing SYN
 * uses the one real sample as its template, and a SYN-ACK echoes what the peer
 * sent — which is what a negotiation reply has to do anyway.  When the field
 * capture arrives, only the template needs revisiting.
 */

package com.okcar.link.iap2;

import com.okcar.link.util.Hex;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class Iap2Syn {

    /** Fixed block before the per-session entries. */
    public static final int FIXED_LEN = 10;
    /** Each session entry is 3 bytes. */
    public static final int ENTRY_LEN = 3;
    /** The peer library's copy loop is capped at 10 entries. */
    public static final int MAX_ENTRIES = 10;

    /**
     * The ten fixed bytes, named structurally because only their source offsets
     * in the peer's struct are known.  Defaults are the head unit's own values.
     *
     * SEMANTICS (settled 2026-09-26, §12.33) — decoded from three independent
     * places in the head unit's own code, which agree byte for byte:
     *
     *   (a) its log format        "SYN negotiated parameters: version (%) /
     *       maxOutstandingPackets / maxRetransmissions / maxCumAck /
     *       maxPacketSize / retransmitTimeout / cumAckTimeout" — that order is
     *       exactly the struct order produced by (b);
     *   (b) iAP2PacketParseSYNData @0x4c3f0 — it reads w[2..3], w[4..5] and
     *       w[6..7] as BIG-ENDIAN u16 and w[8], w[9] as single bytes;
     *   (c) iAP2PacketCreateSYNPacket @0x4bf90 — the encoder stores `ah` before
     *       `al` (0x4bfe6/0x4bfea) and `bh` before `bl` (0x4bff2/0x4bff6), i.e.
     *       the same big-endian order.
     *
     *   w[0] a = version                1..0x1E            (validator)
     *   w[1] b = maxOutstandingPackets  > 0, <= 0x7F       (signed byte > 0)
     *   w[2] c ┐ maxPacketSize, BE u16  >= 24
     *   w[3] d ┘
     *   w[4..5] e = retransmitTimeout, BE u16  >= 20
     *   w[6..7] f = cumAckTimeout,     BE u16  >= 10 and < 0x8000
     *   w[8] g = maxRetransmissions     1..30
     *   w[9] h = maxCumAck              < 0x80
     *
     * Then (len-10)/3 session entries, each {id, type, ver}: id != 0, type <= 2,
     * and at least one entry with type == 0 — all four rules enforced by
     * iAP2LinkIsValidSynParam @0x46c50, which logs
     * "Invalid SYN Params detected" when any of them fails.
     *
     * BOTH u16 FIELDS ARE BIG-ENDIAN ON THE WIRE.  Encoding them little-endian
     * (the bug fixed here) put 53255/59395 on the wire where 2000/1000 belonged;
     * cumAckTimeout then had bit 15 set, which is precisely the ">= 0x8000" case
     * the validator rejects — so the SYN we sent on every DETECT advertised
     * parameters the head unit treats as invalid.  Echoing the peer's SYN-ACK
     * payload hid it: parse+encode in the same (wrong) order is byte-identical.
     */
    /* Defaults are the parameters the production head unit advertises in its own SYN,
     * measured 2026-09-26: 01 7f ff ff | 07 d0 | 03 e8 | 1e 03
     * (version 1, maxOutstandingPackets 127, maxPacketSize 65535,
     *  retransmitTimeout 2000, cumAckTimeout 1000, maxRetransmissions 30,
     *  maxCumAck 3).  Our earlier all-zero template is what its
     * iAP2LinkValidateSynParam logs as "Invalid SYN Params detected"; using the
     * peer's own advertised values is the only choice it accepts by construction. */
    public byte a = 0x01;
    public byte b = 0x7F;
    public byte c = (byte) 0xFF;
    public byte d = (byte) 0xFF;
    public int e = 0x07D0; // retransmitTimeout, big endian on the wire
    public int f = 0x03E8; // cumAckTimeout, big endian on the wire
    public byte g = 0x1E;
    public byte h = 0x03;

    /** Per-session parameter triples (kept encoded, 3 bytes each). */
    public final List<byte[]> entries = new ArrayList<byte[]>();

    public Iap2Syn() {
        // The head unit's own SYN carries these two session entries (0x0a, 0x0b).
        entries.add(new byte[]{0x0A, 0x00, 0x02});
        entries.add(new byte[]{0x0B, 0x01, 0x01});
    }

    public byte[] encode() {
        final int count = Math.min(entries.size(), MAX_ENTRIES);
        final byte[] out = new byte[FIXED_LEN + ENTRY_LEN * count];
        out[0] = a;
        out[1] = b;
        out[2] = c;
        out[3] = d;
        out[4] = (byte) ((e >> 8) & 0xFF); // big endian, see the field docs
        out[5] = (byte) (e & 0xFF);
        out[6] = (byte) ((f >> 8) & 0xFF);
        out[7] = (byte) (f & 0xFF);
        out[8] = g;
        out[9] = h;
        for (int i = 0; i < count; i++) {
            System.arraycopy(entries.get(i), 0, out, FIXED_LEN + i * ENTRY_LEN, ENTRY_LEN);
        }
        return out;
    }

    /**
     * Parses a SYN payload.
     *
     * @return the parameters, or null when the bytes cannot be a SYN payload
     *         (shorter than the fixed block, or not of the form 10 + 3N).
     */
    public static Iap2Syn parse(byte[] data, int offset, int length) {
        if (data == null || length < FIXED_LEN || offset < 0
                || offset + length > data.length) {
            return null;
        }
        if ((length - FIXED_LEN) % ENTRY_LEN != 0) {
            return null;
        }
        final Iap2Syn syn = new Iap2Syn();
        syn.entries.clear();
        syn.a = data[offset];
        syn.b = data[offset + 1];
        syn.c = data[offset + 2];
        syn.d = data[offset + 3];
        syn.e = ((data[offset + 4] & 0xFF) << 8) | (data[offset + 5] & 0xFF);
        syn.f = ((data[offset + 6] & 0xFF) << 8) | (data[offset + 7] & 0xFF);
        syn.g = data[offset + 8];
        syn.h = data[offset + 9];
        final int count = (length - FIXED_LEN) / ENTRY_LEN;
        for (int i = 0; i < count && i < MAX_ENTRIES; i++) {
            syn.entries.add(Arrays.copyOfRange(data, offset + FIXED_LEN + i * ENTRY_LEN,
                    offset + FIXED_LEN + (i + 1) * ENTRY_LEN));
        }
        return syn;
    }

    public static Iap2Syn parse(byte[] data) {
        return data == null ? null : parse(data, 0, data.length);
    }

    /** Copies the peer's parameters, so a SYN-ACK answers with what was offered. */
    public static Iap2Syn from(Iap2Syn other) {
        final Iap2Syn syn = new Iap2Syn();
        syn.entries.clear();
        if (other == null) {
            return syn;
        }
        syn.a = other.a;
        syn.b = other.b;
        syn.c = other.c;
        syn.d = other.d;
        syn.e = other.e;
        syn.f = other.f;
        syn.g = other.g;
        syn.h = other.h;
        for (byte[] entry : other.entries) {
            syn.entries.add(entry.clone());
        }
        return syn;
    }

    /**
     * Session id of the control session this block declares: the id of the first
     * entry whose type is 0, or 0 when the block carries no control entry.
     *
     * Entry layout is {id, type, ver} and type 0 is the control session.  Two
     * accessory generations disagree on the id, and both are real:
     *
     *   - the emulator head unit on its CarPlay link offered {@code 0a 00 02} (id 10);
     *   - the wireless-iAP accessory — the emulator's *early* links and Fruit Link both
     *     send the byte-identical block
     *     {@code 01 05 20 00 00 00 00 00 00 00 01 00 01 02 01 02} — offers
     *     {@code 01 00 01} (id 1).
     *
     * A real iPhone answers with the peer's entries and then sends 0x1D00 on the
     * *peer's* control session id, so this must never be hardcoded (§12.55).
     */
    public int controlSessionId() {
        for (final byte[] entry : entries) {
            if (entry.length == ENTRY_LEN && entry[1] == 0) {
                return entry[0] & 0xFF;
            }
        }
        return 0;
    }

    public String describe() {
        final StringBuilder sb = new StringBuilder();
        sb.append(String.format("fixed[%02x %02x %02x %02x | e=%d f=%d %02x %02x]",
                a, b, c, d, e, f, g, h));
        sb.append(" entries(").append(entries.size()).append("):");
        for (byte[] entry : entries) {
            sb.append(' ').append(Hex.encode(entry));
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return "Iap2Syn{" + describe() + '}';
    }
}
