/*
 * OkcarOS link stack — on-device self test.
 *
 * CarPlay needs a car; the wire protocol does not.  Everything that can be
 * verified without the head unit is verified here, and the result is printed to
 * logcat and to the LinkActivity screen:
 *
 *   - iAP2 checksum parity between the Java codec and the native library
 *   - link-layer frame encode/parse round trip + checksum verification
 *   - DETECT / DETECT-BAD magic packet recognition and incremental peeking
 *   - control-session wrapper (40 40 LEN LEN MSGID payload) round trip
 *   - HID report decoding for the head unit's T/M/P frames (1920x720 touchspace)
 *   - a full SYN -> SYN-ACK handshake against a loopback transport
 *
 * Run it from the UI, or:
 *   adb shell am start -n com.okcar.link/.LinkActivity --es action selfTest
 */

package com.okcar.link.iap2;

import android.util.Log;

import com.okcar.link.NativeBridge;
import com.okcar.link.airplay.AirPlay;
import com.okcar.link.hid.HidReportDecoder;
import com.okcar.link.plist.BinaryPlist;
import com.okcar.link.rtsp.RtspMessage;
import com.okcar.link.util.Hex;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class Iap2SelfTest {

    private static final String TAG = "OkCarLink/SelfTest";

    private Iap2SelfTest() {
    }

    /** Runs every check; returns one human-readable line per check. */
    public static List<String> run() {
        final List<String> out = new ArrayList<String>();
        try {
            checksumParity(out);
        } catch (Throwable t) {
            failed(out, "checksum parity", t);
        }
        try {
            frameRoundTrip(out);
        } catch (Throwable t) {
            failed(out, "frame round trip", t);
        }
        try {
            detectPackets(out);
        } catch (Throwable t) {
            failed(out, "detect packets", t);
        }
        try {
            synPayload(out);
        } catch (Throwable t) {
            failed(out, "SYN payload", t);
        }
        try {
            synParams(out);
        } catch (Throwable t) {
            failed(out, "SYN params", t);
        }
        try {
            controlMessageRoundTrip(out);
        } catch (Throwable t) {
            failed(out, "control message", t);
        }
        try {
            hidDecoding(out);
        } catch (Throwable t) {
            failed(out, "HID decoding", t);
        }
        try {
            linkHandshake(out);
        } catch (Throwable t) {
            failed(out, "link handshake", t);
        }
        try {
            plistCodec(out);
        } catch (Throwable t) {
            failed(out, "binary plist codec", t);
        }
        try {
            rtspCodec(out);
        } catch (Throwable t) {
            failed(out, "RTSP codec", t);
        }
        try {
            authSetup(out);
        } catch (Throwable t) {
            failed(out, "AirPlay auth-setup", t);
        }
        for (String line : out) {
            Log.i(TAG, line);
        }
        return out;
    }

    /* ------------------------------------------------------------- the checks */

    private static void checksumParity(List<String> out) {
        final byte[] data = {0x01, 0x02, 0x03};
        final int java = Iap2Frame.checksum(data, 0, data.length) & 0xFF;
        if (!NativeBridge.isAvailable()) {
            check(out, "checksum parity", java == 0xFA,
                    "java=0x" + Integer.toHexString(java) + ", jni unavailable ("
                            + NativeBridge.loadError() + ")");
            return;
        }
        final int jni = NativeBridge.checksum(data, 0, data.length);
        check(out, "checksum parity", java == jni,
                "java=0x" + Integer.toHexString(java) + " jni=0x" + Integer.toHexString(jni)
                        + " (" + NativeBridge.version() + ')');
    }

    private static void frameRoundTrip(List<String> out) {
        final byte[] payload = {0x11, 0x22, 0x33};
        // Wire order is ctrl, seq, ack, session (§12.28) — the signature says so.
        final byte[] raw = Iap2Frame.encode(0x00, 7, 3, 1, payload);
        final Iap2Frame frame = Iap2Frame.parse(raw, 0, raw.length);
        boolean ok = raw.length == 13 && frame != null && frame.isData()
                && frame.seq == 7 && frame.ack == 3 && frame.session == 1
                && Arrays.equals(payload, frame.payload)
                && frame.headerChecksumOk && frame.payloadChecksumOk;
        final int verify = (Iap2Frame.sum(raw, 0, raw.length - 1) + (raw[raw.length - 1] & 0xFF))
                & 0xFF;
        ok = ok && verify == 0;

        // Pin the byte order independently of the encoder: build the frame by
        // hand and compare.  This is the check that would have caught the
        // SEQ/ACK/SESSID transposition.
        final byte[] expected = Hex.decode("ff 5a 00 0d 00 07 03 01 8f 11 22 33 9a");
        final boolean orderOk = Arrays.equals(expected, raw);

        check(out, "frame round trip", ok,
                "len=" + raw.length + " " + Hex.encode(raw) + " -> "
                        + (frame == null ? "null" : frame.describe()));
        check(out, "frame field order ctrl,seq,ack,session", orderOk,
                "encoder=" + Hex.encode(raw) + " expected=" + Hex.encode(expected));

        // Header-only control frame: 9 bytes, no payload checksum.
        final byte[] rst = Iap2Frame.encode(Iap2Ctl.RST, 5, 0, 0, null);
        final Iap2Frame parsedRst = Iap2Frame.parse(rst, 0, rst.length);
        final boolean rstOk = rst.length == 9 && parsedRst != null && parsedRst.isData()
                && parsedRst.ctrl == Iap2Ctl.RST && parsedRst.seq == 5
                && parsedRst.payload.length == 0 && parsedRst.headerChecksumOk
                && parsedRst.payloadChecksumOk;
        check(out, "header-only frame is 9 bytes (no payload checksum)", rstOk,
                Hex.encode(rst) + " -> " + (parsedRst == null ? "null" : parsedRst.describe()));
    }

    /**
     * SYN payload layout: 10 fixed bytes + N x 3-byte session entries, verified
     * against the head unit's iAP2PacketCreateSYNPacket (§12.28).  The 16-byte
     * sample below is the real one from the neighbor project.
     */
    private static void synPayload(List<String> out) {
        final byte[] sample = Hex.decode("01 05 20 00 00 00 00 00 00 00 01 00 01 02 00 00");
        final Iap2Syn syn = Iap2Syn.parse(sample);
        final boolean ok = syn != null && syn.a == 0x01 && syn.b == 0x05 && syn.c == 0x20
                && syn.d == 0 && syn.e == 0 && syn.f == 0 && syn.g == 0 && syn.h == 0
                && syn.entries.size() == 2
                && Arrays.equals(new byte[]{0x01, 0x00, 0x01}, syn.entries.get(0))
                && Arrays.equals(new byte[]{0x02, 0x00, 0x00}, syn.entries.get(1))
                && Arrays.equals(sample, syn.encode())
                // 10 + 3N only
                && Iap2Syn.parse(new byte[11]) == null
                && Iap2Syn.parse(new byte[9]) == null;
        check(out, "SYN payload 10+3N structure (head-unit layout)", ok,
                syn == null ? "null" : syn.describe() + " | re-encode="
                        + Hex.encode(syn.encode()));
    }

    /**
     * §12.33 — the head unit's own SYN, captured live, is
     *   01 7f ff ff | 07 d0 | 03 e8 | 1e 03
     * and both its parser (iAP2PacketParseSYNData @0x4c3f0) and its encoder
     * (iAP2PacketCreateSYNPacket @0x4bf90) treat the three u16 fields as
     * BIG-ENDIAN.  We send our own SYN on every DETECT, so that payload must
     * also satisfy every rule of its iAP2LinkIsValidSynParam (@0x46c50) —
     * the little-endian encoding used before this fix put cumAckTimeout=59395
     * (> 0x7fff) on the wire and was rejected as "Invalid SYN Params".
     */
    private static void synParams(List<String> out) {
        final byte[] live = Hex.decode("01 7f ff ff 07 d0 03 e8 1e 03");
        final Iap2Syn peer = Iap2Syn.parse(live);
        check(out, "head-unit SYN decodes as BE u16 params (e=2000 f=1000)",
                peer != null && peer.a == 0x01 && peer.b == 0x7F
                        && peer.e == 2000 && peer.f == 1000
                        && peer.g == 0x1E && peer.h == 0x03
                        && Arrays.equals(live, peer.encode()),
                peer == null ? "null" : peer.describe());

        final byte[] emitted = new Iap2Syn().encode();
        final byte[] expected = Hex.decode("01 7f ff ff 07 d0 03 e8 1e 03 0a 00 02 0b 01 01");
        check(out, "our default SYN reproduces the head unit's live params",
                Arrays.equals(expected, emitted), "got " + Hex.encode(emitted));

        final byte[] oldLittleEndian =
                Hex.decode("01 7f ff ff d0 07 e8 03 1e 03 0a 00 02 0b 01 01");
        check(out, "head-unit validator rejects the old LE encoding",
                !headUnitAcceptsSyn(oldLittleEndian),
                "cumAckTimeout=" + u16be(oldLittleEndian, 6) + " (> 0x7fff)");
        check(out, "head-unit validator accepts our default SYN",
                headUnitAcceptsSyn(emitted), new Iap2Syn().describe());
    }

    /**
     * Local replication of the head unit's iAP2LinkIsValidSynParam @0x46c50.
     * Struct order, which is also the order of its "SYN negotiated parameters"
     * log block: version, maxOutstandingPackets, maxRetransmissions, maxCumAck,
     * maxPacketSize, retransmitTimeout, cumAckTimeout, then session entries.
     */
    private static boolean headUnitAcceptsSyn(byte[] w) {
        if (w == null || w.length < 10 || (w.length - 10) % 3 != 0) {
            return false;
        }
        if (w[1] <= 0) return false;                 // signed byte > 0
        if (u16be(w, 2) < 24) return false;          // maxPacketSize
        if (u16be(w, 4) < 20) return false;          // retransmitTimeout
        final int cumAckTimeout = u16be(w, 6);
        if (cumAckTimeout < 10 || cumAckTimeout > 0x7FFF) return false;
        final int maxRetransmissions = w[8] & 0xFF;
        if (maxRetransmissions < 1 || maxRetransmissions > 0x1E) return false;
        if ((w[9] & 0xFF) >= 0x80) return false;     // maxCumAck
        final int count = (w.length - 10) / 3;
        if (count == 0) return false;
        boolean sawTypeZero = false;
        for (int i = 0; i < count; i++) {
            if ((w[10 + 3 * i] & 0xFF) == 0) return false; // session id
            final int type = w[11 + 3 * i] & 0xFF;
            if (type > 2) return false;
            if (type == 0) sawTypeZero = true;
        }
        return sawTypeZero;
    }

    private static int u16be(byte[] b, int off) {
        return ((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF);
    }

    private static void detectPackets(List<String> out) {
        final byte[] detect = Iap2Frame.encodeDetect();
        final byte[] detectBad = Iap2Frame.encodeDetectBad();
        final Iap2Frame d = Iap2Frame.parse(detect, 0, detect.length);
        final Iap2Frame b = Iap2Frame.parse(detectBad, 0, detectBad.length);
        final int shortPeek = Iap2Frame.peekTotalLen(detect, 0, 3);
        boolean ok = d != null && d.kind == Iap2Frame.KIND_DETECT
                && b != null && b.kind == Iap2Frame.KIND_DETECT_BAD
                && Iap2Frame.peekTotalLen(detect, 0, 6) == Iap2Frame.DETECT_LEN
                && shortPeek == 0;
        check(out, "detect packets", ok,
                "detect=" + Hex.encode(detect) + " bad=" + Hex.encode(detectBad)
                        + " shortPeek=" + shortPeek);
    }

    private static void controlMessageRoundTrip(List<String> out) {
        final byte[] hidPayload = {0x54, 0x01, 0x10, 0x02, 0x20, 0x01};
        final byte[] body = Iap2Message.encode(Iap2Message.MSG_HID_REPORT, hidPayload);
        final Iap2Message.Decoded decoded = Iap2Message.decode(body);
        final int lenField = ((body[2] & 0xFF) << 8) | (body[3] & 0xFF);
        final boolean ok = body[0] == 0x40 && body[1] == 0x40
                && lenField == hidPayload.length + 6
                && decoded != null && decoded.messageId == Iap2Message.MSG_HID_REPORT
                && Arrays.equals(hidPayload, decoded.payload)
                && "HIDReport".equals(Iap2Message.name(decoded.messageId));
        check(out, "control message", ok,
                Hex.encode(body) + " -> " + (decoded == null ? "null"
                        : Iap2Message.name(decoded.messageId) + " payload="
                        + Hex.encode(decoded.payload)));
    }

    private static void hidDecoding(List<String> out) {
        final HidReportDecoder.Report typed = HidReportDecoder.decode(
                new byte[]{0x54, 0x01, 0x10, 0x02, 0x20, 0x01});
        final HidReportDecoder.Report untyped = HidReportDecoder.decode(
                new byte[]{0x01, 0x10, 0x02, 0x20, 0x01});
        final HidReportDecoder.Report up = HidReportDecoder.decode(
                new byte[]{0x54, 0x00, 0x10, 0x02, 0x20, 0x01});
        final HidReportDecoder.Report media = HidReportDecoder.decode(new byte[]{0x4D, 0x0C});
        final boolean ok = typed != null && typed.down && typed.x == 0x0210 && typed.y == 0x0120
                && untyped != null && untyped.down && untyped.x == 0x0210 && untyped.y == 0x0120
                && up != null && !up.down
                && media != null && media.type == HidReportDecoder.TYPE_MEDIA && media.button == 12;
        check(out, "HID decoding", ok,
                "typed=" + describe(typed) + " untyped=" + describe(untyped)
                        + " up=" + describe(up) + " media=" + describe(media));
    }

    private static void linkHandshake(List<String> out) throws IOException, InterruptedException {
        final LoopbackTransport transport = new LoopbackTransport();
        final boolean[] up = {false};
        final Iap2Link link = new Iap2Link(transport, new Iap2Link.Listener() {
            @Override
            public void onLinkUp() {
                up[0] = true;
            }

            @Override
            public void onLinkDown(String reason) {
                up[0] = false;
            }

            @Override
            public void onMessage(int sessionId, int messageId, byte[] payload) {
                /* nothing to do for the handshake test */
            }

            @Override
            public void onFrame(Iap2Frame frame) {
                /* nothing to do for the handshake test */
            }
        });
        link.start();
        try {
            Thread.sleep(150);
            transport.feed(Iap2Frame.encode(Iap2Ctl.SYN, 1, 0, 0, new Iap2Syn().encode()));

            boolean sawSynAck = false;
            for (int i = 0; i < 20 && (!up[0] || !sawSynAck); i++) {
                Thread.sleep(50);
                for (byte[] chunk : transport.sent()) {
                    final Iap2Frame frame = Iap2Frame.parse(chunk, 0, chunk.length);
                    if (frame != null && frame.isData() && frame.ctrl == Iap2Ctl.SYN_ACK
                            && frame.ack == 2) {
                        sawSynAck = true;
                    }
                }
            }
            check(out, "link handshake (SYN -> SYN-ACK)", up[0] && sawSynAck,
                    "linkUp=" + up[0] + " sentSynAck=" + sawSynAck
                            + " txFrames=" + transport.sent().size());
        } finally {
            link.stop();
            transport.close();
        }
    }

    /**
     * Binary plist: round trip plus a fixed vector produced by python's plistlib
     * (an independent implementation) — a CarPlay-style stream setup carrying the
     * ports observed in the field (video 36113 / audio 36486, §12.17 C).
     */
    @SuppressWarnings("unchecked")
    private static void plistCodec(List<String> out) throws IOException {
        final java.util.Map<String, Object> root = new java.util.LinkedHashMap<String, Object>();
        root.put("deviceID", "AA:BB:CC:DD:EE:FF");
        root.put("audioPort", Long.valueOf(36486));
        root.put("videoPort", Long.valueOf(36113));
        root.put("enabled", Boolean.TRUE);
        root.put("blob", new byte[]{0x01, 0x02, 0x03, (byte) 0xFF});
        final java.util.List<Object> list = new java.util.ArrayList<Object>();
        for (int i = 0; i < 20; i++) {
            list.add(Long.valueOf(i * 1000));
        }
        root.put("many", list);
        root.put("wide", "\u5bbd\u5b57\u7b26-\u6d4b\u8bd5");
        final byte[] encoded = BinaryPlist.encode(root);
        final Object decoded = BinaryPlist.decode(encoded);
        final boolean roundTrip = decoded instanceof java.util.Map
                && "AA:BB:CC:DD:EE:FF".equals(((java.util.Map<String, Object>) decoded).get("deviceID"))
                && Long.valueOf(36486L).equals(
                        ((java.util.Map<String, Object>) decoded).get("audioPort"))
                && "\u5bbd\u5b57\u7b26-\u6d4b\u8bd5".equals(
                        ((java.util.Map<String, Object>) decoded).get("wide"))
                && ((java.util.List<?>) ((java.util.Map<String, Object>) decoded).get("many")).size() == 20;

        // Fixed vector: bplist00 produced by python3 plistlib with
        // {"deviceID": "AA:BB:CC:DD:EE:FF", "streams": [{"type": 110, "dataPort": 36113}]}
        //   python3 -c "import plistlib,binascii; print(binascii.hexlify(plistlib.dumps(
        //       {'deviceID':'AA:BB:CC:DD:EE:FF','streams':[{'type':110,'dataPort':36113}]},
        //       fmt=plistlib.FMT_BINARY)).decode())"
        final byte[] vector = Hex.decode(
                "62706c6973743030d2010203045864657669636549445773747265616d735f1011"
                        + "41413a42423a43433a44443a45453a4646a105d2060708095864617461506f"
                        + "72745474797065118d11106e080d161e32343942474a000000000000010100"
                        + "0000000000000a0000000000000000000000000000004c");
        boolean vectorOk = false;
        String vectorDetail;
        try {
            final Object parsed = BinaryPlist.decode(vector);
            vectorOk = String.valueOf(parsed).contains("dataPort=36113");
            vectorDetail = String.valueOf(parsed);
        } catch (IOException e) {
            vectorDetail = "vector decode failed: " + e;
        }

        check(out, "binary plist codec", roundTrip,
                "round trip " + encoded.length + "B -> " + (decoded instanceof java.util.Map
                        ? ((java.util.Map<?, ?>) decoded).size() + " keys" : String.valueOf(decoded)));
        check(out, "binary plist fixed vector (python plistlib)", vectorOk, vectorDetail);
    }

    private static void rtspCodec(List<String> out) throws IOException {
        final RtspMessage request = RtspMessage.request(RtspMessage.METHOD_SETUP,
                "rtsp://fe80::1/stream");
        request.setHeader("CSeq", "4")
                .setHeader("Transport", "RTP/AVP/TCP;interleaved=0-1");
        final java.util.Map<String, Object> plist = new java.util.LinkedHashMap<String, Object>();
        final java.util.List<Object> streams = new java.util.ArrayList<Object>();
        final java.util.Map<String, Object> video = new java.util.LinkedHashMap<String, Object>();
        video.put("type", Long.valueOf(AirPlay.STREAM_TYPE_VIDEO));
        video.put("dataPort", Long.valueOf(36113));
        streams.add(video);
        plist.put("streams", streams);
        request.setPlistBody(plist);

        final RtspMessage parsed = RtspMessage.parse(
                new ByteArrayInputStream(request.toByteArray()));
        boolean ok = parsed != null && parsed.isRequest()
                && RtspMessage.METHOD_SETUP.equals(parsed.method())
                && parsed.cseq() == 4 && parsed.isBinaryPlist();
        String detail = parsed == null ? "null" : parsed.summary();
        if (ok) {
            final Object body = parsed.plistBody();
            ok = String.valueOf(body).contains("dataPort=36113");
            detail += " -> " + body;
        }
        check(out, "RTSP codec (request + plist body)", ok, detail);

        final RtspMessage response = RtspMessage.response(request, 200, "OK");
        final RtspMessage parsedResponse = RtspMessage.parse(
                new ByteArrayInputStream(response.toByteArray()));
        check(out, "RTSP codec (response)", parsedResponse != null && !parsedResponse.isRequest()
                        && parsedResponse.statusCode() == 200 && parsedResponse.cseq() == 4,
                parsedResponse == null ? "null" : parsedResponse.summary());
    }

    private static void authSetup(List<String> out) {
        final byte[] challenge = {0x11, 0x22, 0x33, 0x44};
        final byte[] body = AirPlay.authSetupBody(challenge);
        final byte[] back = AirPlay.authSetupChallenge(body);
        final boolean ok = body.length == 5 && (body[0] & 0xFF) == AirPlay.AUTH_SETUP_TAG
                && java.util.Arrays.equals(challenge, back)
                && AirPlay.authSetupChallenge(new byte[]{0x02, 0x00}) == null;
        check(out, "AirPlay auth-setup body (0x01 + challenge)", ok,
                "body=" + Hex.encode(body) + " challenge=" + Hex.encode(back)
                        + " features=" + AirPlay.features()
                        + " ctrl-int=" + AirPlay.ctrlIntPath("play"));
    }

    /* ---------------------------------------------------------------- helpers */

    private static String describe(HidReportDecoder.Report report) {
        return report == null ? "null" : report.describe();
    }

    private static void check(List<String> out, String name, boolean ok, String detail) {
        out.add((ok ? "PASS  " : "FAIL  ") + name + "  —  " + detail);
    }

    private static void failed(List<String> out, String name, Throwable t) {
        out.add("FAIL  " + name + "  —  " + t);
        Log.w(TAG, name + " threw", t);
    }

    /** In-memory byte pipe used to exercise the link state machine. */
    private static final class LoopbackTransport implements Iap2Link.Transport {

        private final ArrayDeque<byte[]> mInbox = new ArrayDeque<byte[]>();
        private final List<byte[]> mOutbox = new ArrayList<byte[]>();
        private byte[] mPending;
        private int mPendingOffset;
        private boolean mClosed;

        synchronized void feed(byte[] data) {
            mInbox.add(data);
            notifyAll();
        }

        synchronized List<byte[]> sent() {
            return new ArrayList<byte[]>(mOutbox);
        }

        @Override
        public synchronized int read(byte[] buffer, int offset, int length) throws IOException {
            while (mPending == null && mInbox.isEmpty() && !mClosed) {
                try {
                    wait(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted");
                }
            }
            if (mPending == null) {
                if (mInbox.isEmpty()) {
                    return mClosed ? -1 : 0;
                }
                mPending = mInbox.poll();
                mPendingOffset = 0;
            }
            final int count = Math.min(length, mPending.length - mPendingOffset);
            System.arraycopy(mPending, mPendingOffset, buffer, offset, count);
            mPendingOffset += count;
            if (mPendingOffset >= mPending.length) {
                mPending = null;
                mPendingOffset = 0;
            }
            return count;
        }

        @Override
        public synchronized void write(byte[] buffer, int offset, int length) {
            mOutbox.add(Arrays.copyOfRange(buffer, offset, offset + length));
        }

        @Override
        public synchronized void close() {
            mClosed = true;
            notifyAll();
        }

        @Override
        public String describe() {
            return "loopback";
        }
    }
}
