/*
 * OkcarOS link stack — L3: iAP2 link state machine.
 *
 * This is the byte-level peer of the head unit's iAP2 stack: it drives the
 * Detached -> Detect -> Idle/Connected handshake described in exeed findings A
 * §5.8.1 and hands decoded control messages to a listener.
 *
 * The transport is deliberately abstract.  In the CarPlay-over-USB topology the
 * head unit's iAP2 function is the vendor interface (class 0xff, driver usbfs)
 * that the closed-source app grabs through libusb, so the production transport
 * will be a libusb/UsbDeviceConnection bulk-endpoint pipe; a loopback transport
 * is used by the self test so this code is exercised without a car.
 *
 * Sequencing is intentionally simple (one outstanding packet, cumulative ACK,
 * no retransmission) — the defaults in Iap2Syn say the real link behaves the
 * same way for the traffic we care about.  Retransmission is a TODO.
 */

package com.okcar.link.iap2;

import android.os.SystemClock;
import android.util.Log;

import com.okcar.link.LinkState;
import com.okcar.link.util.Hex;

import java.io.IOException;

public final class Iap2Link {

    private static final String TAG = "OkCarLink/Iap2";

    private static final int RX_CAPACITY = 64 * 1024;

    /** How often to say "still waiting for the peer" while nothing arrives. */
    private static final long HEARTBEAT_MS = 10_000;

    /**
     * Silence after which an <em>open</em> iAP2 link is declared dead.
     *
     * <p>The heartbeat above only reported the silence; the link stayed "connected" for as long as the
     * socket did not error, so a peer that went away quietly left the session wedged (measured
     * 2026-10-01: {@code no iAP2 data for 1418s (14086 empty reads)} while the accessory had long
     * moved on).  Declaring it down lets the session's own link-loss path run — which keeps a live
     * AirPlay session (some head units close RFCOMM right after the credentials) and reverts the
     * phone's network when nothing was up.
     */
    public static final long DEFAULT_IDLE_LINK_DOWN_MS = 60_000;

    private static final long IDLE_LINK_DOWN_MS = DEFAULT_IDLE_LINK_DOWN_MS;

    /**
     * The idle threshold in force; {@code 0} disables it.  The wireless session switches it off once
     * AirPlay is up: the iAP2 link that rides the type-130 tunnel is <b>quiet by design</b> (the
     * accessory's iAP2 arrives over the event channel and is injected here), so silence on this link
     * means nothing — measured 2026-10-01 20:17: our own idle rule declared the link dead 60 s into a
     * healthy session, we stopped acknowledging the accessory, and it rebuilt the whole stack
     * ("Reconnecting after <failure>: iAP2 link ended" — the accessory prints its reason word in
     * Chinese in the raw log) roughly every 50 s.
     */
    private volatile long mIdleLinkDownMs = IDLE_LINK_DOWN_MS;

    /** 0 disables the idle watchdog; the default is {@link #IDLE_LINK_DOWN_MS}. */
    public void setIdleLinkDownMs(long millis) {
        mIdleLinkDownMs = millis;
    }

    /** Byte pipe to the head unit's iAP2 interface. */
    public interface Transport {
        int read(byte[] buffer, int offset, int length) throws IOException;

        void write(byte[] buffer, int offset, int length) throws IOException;

        void close();

        String describe();
    }

    public interface Listener {
        void onLinkUp();

        void onLinkDown(String reason);

        /** A control-session message arrived (already unwrapped). */
        void onMessage(int sessionId, int messageId, byte[] payload);

        /** Every frame, before it is interpreted — useful for logging. */
        void onFrame(Iap2Frame frame);
    }

    private final Transport mTransport;
    private final Listener mListener;
    /** Our SYN parameter block; assigned in the constructor from the template. */
    private final Iap2Syn mSyn;

    private volatile boolean mRunning;
    private volatile boolean mConnected;
    private Thread mThread;

    private int mSeq = 1;
    private int mAck = 0;

    /** Control-session id the peer declared in its SYN; 0 until a SYN has been seen. */
    private volatile int mPeerControlSession;

    /**
     * How the ACK/SYN-ACK "acknowledged sequence" is encoded.
     *
     * The head unit's own `iAP2LinkActionSendACK` (libiPodForiAP2.so @0x461f0) loads
     * the received packet's byte[5] (its SEQ) straight into the ACK's ack field, i.e.
     * it acknowledges the sequence it just received.  Our original reading used
     * "next expected" (seq + 1); this switch lets a field test decide which one the
     * head unit accepts, because a wrong ACK is what keeps its link from coming up
     * (it answers DETECT and keeps retransmitting SYN but never delivers our DATA).
     */
    private static volatile boolean sAckNextExpected = true;

    public static void setAckNextExpected(boolean nextExpected) {
        sAckNextExpected = nextExpected;
    }

    public static boolean ackNextExpected() {
        return sAckNextExpected;
    }

    /**
     * Whether the device sends its own SYN when the head unit announces itself with
     * DETECT (original behaviour), or waits for the head unit's SYN and only answers
     * with SYN-ACK.  The head unit is the side that opens with SYN, so the extra SYN
     * may be what keeps it from establishing the link (it answers DETECT but silently
     * drops our DATA).
     */
    private static volatile boolean sDeviceSendsSyn = true;

    /** SYN-ACK payload: echo the peer's SYN parameters (original) or send our own. */
    private static volatile boolean sSynAckEchoParams = true;

    public static void setDeviceSendsSyn(boolean value) {
        sDeviceSendsSyn = value;
    }

    public static boolean deviceSendsSyn() {
        return sDeviceSendsSyn;
    }

    public static void setSynAckEchoParams(boolean value) {
        sSynAckEchoParams = value;
    }

    public static boolean synAckEchoParams() {
        return sSynAckEchoParams;
    }

    /**
     * SYN parameter template, copied into each link instance at construction.
     *
     * The wired path keeps {@link Iap2Syn}'s defaults (the head unit's own block,
     * §12.33).  The *wireless* path must instead answer an accessory's SYN with the
     * block a real iPhone sent — captured 2026-09-26/27:
     * {@code 01 7f ff ff 0f a0 01 f4 04 03 | 0a 00 02 | 0b 02 01 | 0c 01 02}
     * (maxOutgoing 0x7F where the accessory itself sends 0x04), see §12.38.  A
     * static template is enough because only one role is active at a time and the
     * wireless session restores it to null when it stops.
     */
    private static volatile Iap2Syn sSynTemplate;

    public static void setSynTemplate(Iap2Syn template) {
        sSynTemplate = template == null ? null : Iap2Syn.from(template);
    }

    public Iap2Link(Transport transport, Listener listener) {
        mTransport = transport;
        mListener = listener;
        mSyn = sSynTemplate != null ? Iap2Syn.from(sSynTemplate) : new Iap2Syn();
    }

    public boolean isConnected() {
        return mConnected;
    }

    /**
     * Control session to address: the id the peer declared in its SYN when it
     * declared one, otherwise {@code fallback}.
     *
     * The id is not a constant across accessories — the emulator's CarPlay link uses 10
     * while the wireless-iAP block (the emulator early links, Fruit Link) uses 1 — so a
     * session-layer message sent on the wrong id is dropped while the link layer
     * still acknowledges it, which looks exactly like a silent peer (§12.55).
     */
    public int controlSession(int fallback) {
        final int peer = mPeerControlSession;
        return peer != 0 ? peer : fallback;
    }

    public synchronized void start() {
        if (mRunning) {
            return;
        }
        mRunning = true;
        mThread = new Thread(new Runnable() {
            @Override
            public void run() {
                readLoop();
            }
        }, "okcar-iap2-rx");
        mThread.setDaemon(true);
        mThread.start();
        LinkState.get().setIap2State(LinkState.IAP2_DETACHED,
                mTransport == null ? "no transport" : mTransport.describe());
        Log.i(TAG, "iAP2 link started on " + (mTransport == null ? "null" : mTransport.describe()));
    }

    public synchronized void stop() {
        mRunning = false;
        final Thread thread = mThread;
        mThread = null;
        if (thread != null) {
            thread.interrupt();
        }
        if (mTransport != null) {
            mTransport.close();
        }
        mConnected = false;
        LinkState.get().setIap2State(LinkState.IAP2_DETACHED, "stopped");
    }

    /* -------------------------------------------------------------- transmit */

    public boolean sendDetect() {
        return sendRaw(Iap2Frame.encodeDetect());
    }

    public boolean sendSyn() {
        return sendFrame(Iap2Ctl.SYN, mSeq++, mAck, 0, mSyn.encode());
    }

    /** SYN-ACK; carries our own fixed block and the peer's session entries. */
    public boolean sendSynAck(int ack, Iap2Syn peer) {
        final Iap2Syn params;
        if (sSynAckEchoParams && peer != null && !peer.entries.isEmpty()) {
            if (peer.controlSessionId() == 1) {
                /* wireless-iAP family.  The accessory asks with
                 * 01 05 20 00 00 00 00 00 00 00 and a real iPhone answers
                 * 01 05 ff ff 00 00 00 00 00 00 with the same session entries
                 * (the emulator 2026-09-26 capture, its session-1 links).  Echo the whole
                 * block and only raise maxPacketSize; substituting our own
                 * window/timeouts was the last byte difference between our SYN-ACK
                 * and the phone's, and this accessory stays silent on it (§12.55). */
                params = Iap2Syn.from(peer);
                params.c = (byte) 0xFF;
                params.d = (byte) 0xFF;
            } else {
                /* Echo the peer's whole block, parameters included.
                 *
                 * The the production head unit accessory insists on its own 0x07D0/0x03E8/0x1E/0x03
                 * and re-sends SYN-ACK until we agree; answering with our own
                 * window (0x0FA0/0x01F4/0x04) made the two of us ping-pong
                 * SYN-ACK forever, and the accessory never advanced to the
                 * control session — no 0x1D01, so no 0x5703 (§3.15).  A real
                 * iPhone SE3 answers byte-for-byte with the accessory's block,
                 * so do the same.  The session-1 family keeps the c/d = 0xFF
                 * tweak in the branch above. */
                params = Iap2Syn.from(peer);
            }
        } else {
            params = Iap2Syn.from(mSyn);
        }
        return sendFrame(Iap2Ctl.SYN_ACK, mSeq++, ack, 0, params.encode());
    }

    public boolean sendSynAck(int ack) {
        return sendSynAck(ack, null);
    }

    public boolean sendAck(int ack) {
        return sendFrame(Iap2Ctl.ACK, mSeq, ack, 0, null);
    }

    /** RST is a header-only frame: 9 bytes, no payload checksum (§12.28). */
    public boolean sendRst() {
        return sendFrame(Iap2Ctl.RST, 0, 0, 0, null);
    }

    /**
     * Control byte for outgoing data frames: {@code 0x40} (ACK).
     *
     * Measured on the production head unit car's own HCI snoop (2026-09-28, §3.14): a real
     * iPhone SE3 sends <em>every</em> data frame with {@code ctrl = 0x40}, and the
     * head unit's own {@code 0x1D01}/{@code 0xAA01}/{@code 0xAA03}/{@code 0x5703}
     * do the same.  We used to send {@code 0x00} while still writing {@code mAck}
     * into the ack field — self-contradictory, naming an ack number without
     * announcing that the frame acks anything.  The car parsed our link layer
     * (it answered SYN-ACK-ACK) but silently dropped every one of our data
     * frames, so the session never got past {@code 0x1D00} and {@code 0x5703}
     * never arrived, even though the credentials were already inside its native
     * stack (see §3.13).
     */
    private static final int DATA_CTRL = Iap2Ctl.ACK;

    /** Sends a control-session message (the normal path). */
    public boolean sendControl(int sessionId, int messageId, byte[] payload) {
        final byte[] body = Iap2Message.encode(messageId, payload);
        return sendFrame(DATA_CTRL, mSeq++, mAck, sessionId, body);
    }

    /** Sends a message body verbatim (session type 0x0C bypasses the wrapper). */
    public boolean sendSessionRaw(int sessionId, byte[] body) {
        return sendFrame(DATA_CTRL, mSeq++, mAck, sessionId, body);
    }

    /**
     * Note the wire order: ctrl, seq, ack, session (§12.28 — the head unit's
     * {@code iAP2PacketCreate} writes {@code [4]=ctrl [5]=seq [6]=ack [7]=session}).
     * Every caller must pass this order; the pre-§12.28 {@code (ctrl, session, seq,
     * ack)} order put the sequence into the session byte.
     */
    private boolean sendFrame(int ctrl, int seq, int ack, int session, byte[] payload) {
        final byte[] frame = Iap2Frame.encode(ctrl, seq, ack, session, payload);
        return sendRaw(frame);
    }

    public boolean sendRaw(byte[] bytes) {
        if (mTransport == null) {
            Log.w(TAG, "no transport, dropping " + bytes.length + " bytes");
            return false;
        }
        try {
            mTransport.write(bytes, 0, bytes.length);
            return true;
        } catch (IOException e) {
            Log.w(TAG, "write failed: " + e);
            return false;
        }
    }

    /* ---------------------------------------------------------------- receive */

    private void readLoop() {
        final byte[] buffer = new byte[RX_CAPACITY];
        int have = 0;
        long lastDataAt = SystemClock.elapsedRealtime();
        long lastHeartbeatAt = lastDataAt;
        long emptyReads = 0;
        while (mRunning) {
            int read;
            try {
                read = mTransport.read(buffer, have, buffer.length - have);
            } catch (IOException e) {
                if (mRunning) {
                    Log.w(TAG, "read failed: " + e);
                    LinkState.get().log("iap2", "transport read failed: " + e);
                }
                break;
            }
            if (read < 0) {
                break;
            }
            if (read == 0) {
                // A bulk read that timed out.  Say so periodically: without this
                // "nothing is happening" is indistinguishable from a hang.
                emptyReads++;
                final long now = SystemClock.elapsedRealtime();
                if (now - lastHeartbeatAt >= HEARTBEAT_MS) {
                    lastHeartbeatAt = now;
                    LinkState.get().log("iap2", "no iAP2 data for "
                            + ((now - lastDataAt) / 1000) + "s (" + emptyReads + " empty reads)"
                            + (mConnected ? "" : " — still waiting for the head unit"));
                }
                final long idleLimit = mIdleLinkDownMs;
                if (mConnected && idleLimit > 0 && now - lastDataAt >= idleLimit) {
                    LinkState.get().log("iap2", "no iAP2 data for " + ((now - lastDataAt) / 1000)
                            + "s — the link is dead in practice; declaring it down");
                    break;
                }
                continue;
            }
            lastDataAt = SystemClock.elapsedRealtime();
            emptyReads = 0;
            have += read;

            int consumed = 0;
            while (true) {
                final int total = Iap2Frame.peekTotalLen(buffer, consumed, have - consumed);
                if (total == 0) {
                    break;
                }
                if (total < 0) {
                    // Not a frame start: resync one byte at a time.
                    consumed++;
                    continue;
                }
                if (have - consumed < total) {
                    break;
                }
                final Iap2Frame frame = Iap2Frame.parse(buffer, consumed, have - consumed);
                consumed += total;
                if (frame != null) {
                    handleFrame(frame);
                }
            }

            if (consumed > 0) {
                System.arraycopy(buffer, consumed, buffer, 0, have - consumed);
                have -= consumed;
            }
            if (have >= buffer.length) {
                LinkState.get().log("iap2", "rx buffer overflow, resynchronising");
                have = 0;
            }
        }
        if (mRunning) {
            onDown("transport closed");
        }
    }

    private void handleFrame(Iap2Frame frame) {
        final LinkState state = LinkState.get();
        if (mListener != null) {
            mListener.onFrame(frame);
        }
        Log.i(TAG, "rx " + frame.describe());

        if (!frame.isData()) {
            state.log("iap2", "rx " + frame.describe());
            state.setIap2State(LinkState.IAP2_DETECT, "detect seen");
            // The peer announced itself; answer with our SYN (switchable, see above).
            if (sDeviceSendsSyn) {
                sendSyn();
            }
            return;
        }

        if (!frame.headerChecksumOk || !frame.payloadChecksumOk) {
            state.log("iap2", "checksum mismatch: " + frame.describe());
        }

        switch (frame.ctrl) {
            case Iap2Ctl.SYN:
            case Iap2Ctl.SYN_ACK: {
                mAck = sAckNextExpected ? (frame.seq + 1) & 0xFF : frame.seq & 0xFF;
                // The SYN payload is 10 fixed bytes + N x 3-byte session entries
                // (§12.28) — log it verbatim, it is the negotiation input.
                final Iap2Syn peerSyn = Iap2Syn.parse(frame.payload);
                if (peerSyn != null) {
                    if (peerSyn.controlSessionId() != 0) {
                        mPeerControlSession = peerSyn.controlSessionId();
                    }
                    state.log("iap2", "peer SYN params: " + peerSyn.describe()
                            + " (raw " + Hex.encode(frame.payload) + ')');
                    state.log("iap2", "peer control session: " + mPeerControlSession);
                } else if (frame.payload.length > 0) {
                    state.log("iap2", "peer SYN payload is not 10+3N: "
                            + Hex.encode(frame.payload));
                }
                /* The SYN-ACK must reach the wire BEFORE any session-layer message.
                 * Both captures agree on the order: a real iPhone answers
                 * DETECT -> SYN(head unit) -> SYN-ACK(phone) -> 0x1D00(phone).
                 * Notifying the listener first dispatched 0x1D00 two milliseconds
                 * *ahead* of our own SYN-ACK (seen in the head unit's HCI snoop),
                 * and Fruit Link then only ever answered a bare link-layer ACK —
                 * its session layer stayed shut for the whole 7 s window and the
                 * app timed out, asked for a reboot and killed itself. */
                final boolean justUp = !mConnected;
                mConnected = true;
                if (justUp) {
                    state.setIap2State(LinkState.IAP2_CONNECTED,
                            Iap2Ctl.name(frame.ctrl) + " from head unit");
                }
                sendSynAck(mAck, peerSyn);
                if (justUp) {
                    state.log("iap2", "link up (" + Iap2Ctl.name(frame.ctrl) + ")");
                    if (mListener != null) {
                        mListener.onLinkUp();
                    }
                }
                break;
            }

            case Iap2Ctl.RST:
                onDown("peer sent RST");
                break;

            case Iap2Ctl.SUS:
                state.log("iap2", "peer suspended the link");
                break;

            default:
                if (frame.payload.length > 0) {
                    /* Cumulative ACK, same rule as the SYN path above: acknowledge
                     * the next sequence number we expect from the peer.  Without
                     * this every ACK kept repeating the SYN's number while the head
                     * unit kept sending, which its seq-gap logic
                     * (iAP2PacketCalcSeqGap, §12.28) reads as an unacknowledged
                     * window.  Idempotent for a retransmitted seq. */
                    mAck = sAckNextExpected ? (frame.seq + 1) & 0xFF : frame.seq & 0xFF;
                    deliver(frame);
                    sendAck(mAck);
                }
                break;
        }
    }

    private void deliver(Iap2Frame frame) {
        final LinkState state = LinkState.get();
        final Iap2Message.Decoded decoded = Iap2Message.decode(frame.payload);
        if (decoded == null) {
            state.log("iap2", "unparsable session body, " + frame.payload.length + " bytes");
            return;
        }
        state.log("iap2", "rx " + Iap2Message.name(decoded.messageId)
                + " sess=" + frame.session + " len=" + decoded.payload.length);
        if (mListener != null) {
            mListener.onMessage(frame.session, decoded.messageId, decoded.payload);
        }
    }

    private void onDown(String reason) {
        if (!mConnected && !mRunning) {
            return;
        }
        mConnected = false;
        LinkState.get().setIap2State(LinkState.IAP2_DETACHED, reason);
        LinkState.get().log("iap2", "link down: " + reason);
        if (mListener != null) {
            mListener.onLinkDown(reason);
        }
    }
}
