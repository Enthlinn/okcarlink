/*
 * OkcarOS link stack — L4 session layer: the RTSP control channel.
 *
 * The direction is not settled by evidence yet (§12.24 D: it needs one field
 * capture), so this is an interface with both implementations:
 *
 *   TcpSessionTransport.outgoing()  — we connect to the peer's control port
 *   TcpSessionTransport.incoming()  — we listen and the peer connects to us
 *
 * Both speak the same RTSP/1.0 framing (RtspMessage) and both tolerate
 * interleaved binary frames on the control connection: RTSP-over-TCP prefixes
 * media with '$' + channel + 16-bit length, and if the peer uses that instead of
 * a separate data connection we must not choke on it.
 */

package com.okcar.link.airplay;

import com.okcar.link.rtsp.RtspMessage;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

public interface SessionTransport {

    /** Sends a request and waits for the reply carrying the same CSeq. */
    RtspMessage exchange(RtspMessage request) throws IOException;

    /** Sends a message without waiting for an answer. */
    void send(RtspMessage message) throws IOException;

    /** Next message, or null at EOF.  Blocks. */
    RtspMessage receive() throws IOException;

    /** Sends raw media bytes (interleaved or on a separate connection). */
    void sendBytes(byte[] data, int offset, int length) throws IOException;

    String describe();

    /**
     * The local address this connection is bound to (no IPv6 scope).  The RTSP
     * request URI must carry the sender's own address — every measured Apple
     * sender does (`rtsp://fe80::8af:75ff:fea9:afb8/<id>` while talking to the
     * head unit at a different address), and the receiver's RECORD handling
     * rejects a session whose URI names somebody else.
     */
    String localAddress();

    /** The peer address as handed to {@code connect()} — keeps the IPv6 scope. */
    String remoteAddress();

    void close();

    /* -------------------------------------------------------------------- */

    /** RTSP/1.0 over a single TCP connection, both directions. */
    final class TcpSessionTransport implements SessionTransport {

        private final Socket mSocket;
        private final InputStream mInput;
        private final OutputStream mOutput;
        private final String mDescription;
        private final String mLocalAddress;
        private final String mPeerHost;
        private final Object mWriteLock = new Object();
        private volatile boolean mClosed;
        private volatile int mInterleavedFrames;

        private TcpSessionTransport(Socket socket, String description, String peerHost)
                throws IOException {
            mSocket = socket;
            mSocket.setTcpNoDelay(true);
            mPeerHost = peerHost == null ? "" : peerHost;
            mLocalAddress = stripScope(socket.getLocalAddress());
            mInput = new BufferedInputStream(socket.getInputStream());
            mOutput = socket.getOutputStream();
            mDescription = description;
        }

        /** Client direction: connect out to the peer's control port. */
        public static TcpSessionTransport outgoing(String host, int port, int timeoutMs)
                throws IOException {
            final Socket socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            return new TcpSessionTransport(socket, "tcp " + host + ':' + port + " (outgoing)",
                    host);
        }

        /** Server direction: accept one connection on the given local port. */
        public static TcpSessionTransport incoming(int localPort, int timeoutMs)
                throws IOException {
            final ServerSocket server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(localPort));
            try {
                server.setSoTimeout(timeoutMs);
                final Socket socket = server.accept();
                return new TcpSessionTransport(socket,
                        "tcp *:" + localPort + " <- " + socket.getInetAddress() + " (incoming)",
                        String.valueOf(socket.getInetAddress()));
            } finally {
                server.close();
            }
        }

        public int interleavedFrameCount() {
            return mInterleavedFrames;
        }

        @Override
        public RtspMessage exchange(RtspMessage request) throws IOException {
            final int cseq = request.cseq();
            send(request);
            while (true) {
                final RtspMessage reply = receive();
                if (reply == null) {
                    throw new IOException("connection closed while waiting for CSeq " + cseq);
                }
                if (cseq < 0 || reply.cseq() == cseq || !reply.isRequest()) {
                    return reply;
                }
                // A reply for a different CSeq (or a stray request) is logged and
                // dropped; the session layer would rather see the real answer.
            }
        }

        @Override
        public void send(RtspMessage message) throws IOException {
            synchronized (mWriteLock) {
                message.write(mOutput);
            }
        }

        @Override
        public RtspMessage receive() throws IOException {
            while (!mClosed) {
                final int first = mInput.read();
                if (first < 0) {
                    return null;
                }
                if (first == '$') {
                    skipInterleavedFrame();
                    continue;
                }
                // RtspMessage.readLine() cannot push back, so re-feed the byte by
                // wrapping the stream: simplest is to read the line ourselves here.
                final String startLine = readLineWithFirstByte(first);
                if (startLine == null) {
                    return null;
                }
                return RtspMessage.parseAfterStartLine(mInput, startLine);
            }
            return null;
        }

        private String readLineWithFirstByte(int first) throws IOException {
            final StringBuilder sb = new StringBuilder();
            sb.append((char) first);
            int c;
            while ((c = mInput.read()) >= 0) {
                if (c == '\n') {
                    break;
                }
                if (c != '\r') {
                    sb.append((char) c);
                }
                if (sb.length() > 16 * 1024) {
                    throw new IOException("RTSP line too long");
                }
            }
            return sb.toString();
        }

        /** RTSP interleaved frame: '$' <channel> <len hi> <len lo> <payload>. */
        private void skipInterleavedFrame() throws IOException {
            final int channel = mInput.read();
            final int hi = mInput.read();
            final int lo = mInput.read();
            if (channel < 0 || hi < 0 || lo < 0) {
                throw new IOException("truncated interleaved frame header");
            }
            final int length = (hi << 8) | lo;
            int remaining = length;
            final byte[] scratch = new byte[4096];
            while (remaining > 0) {
                final int read = mInput.read(scratch, 0, Math.min(scratch.length, remaining));
                if (read < 0) {
                    throw new IOException("truncated interleaved frame body");
                }
                remaining -= read;
            }
            mInterleavedFrames++;
        }

        @Override
        public void sendBytes(byte[] data, int offset, int length) throws IOException {
            synchronized (mWriteLock) {
                mOutput.write(data, offset, length);
                mOutput.flush();
            }
        }

        @Override
        public String describe() {
            return mDescription;
        }

        @Override
        public String localAddress() {
            return mLocalAddress;
        }

        @Override
        public String remoteAddress() {
            return mPeerHost;
        }

        @Override
        public void close() {
            mClosed = true;
            try {
                mSocket.close();
            } catch (IOException ignored) {
                /* nothing useful to do */
            }
        }
    }

    /** "fe80::1%usb0" -> "fe80::1" (an RTSP URI must not carry the scope). */
    static String stripScope(java.net.InetAddress address) {
        if (address == null) {
            return "";
        }
        final String text = address.getHostAddress();
        final int scope = text.indexOf('%');
        return scope > 0 ? text.substring(0, scope) : text;
    }
}
