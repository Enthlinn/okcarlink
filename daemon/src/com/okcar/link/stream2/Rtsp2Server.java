/*
 * OkcarOS link stack — display-2 RTSP server (H.264 over UDP or interleaved TCP).
 *
 * ------------------------------------------------------------------ the car-side API
 *
 * URL:            rtsp://<phone-ip>:<rtsp_port>/cast2      (default port 8554)
 * Methods:        OPTIONS, DESCRIBE, SETUP, PLAY, PAUSE, TEARDOWN, GET_PARAMETER
 * Transports:     Transport: RTP/AVP/UDP;unicast;client_port=<n>-<n+1>      (SDP: RTP/AVP 96)
 *                 Transport: RTP/AVP/TCP;unicast;interleaved=0-1           (SDP: RTP/AVP/TCP)
 *
 * DESCRIBE answers 200 with an H.264 SDP: `a=rtpmap:96 H264/90000`,
 * `a=fmtp:96 packetization-mode=1;profile-level-id=...;sprop-parameter-sets=<b64 SPS>,<b64 PPS>`
 * and `a=control:cast2`.  The session id is a random 16-hex-digit string handed out in the DESCRIBE
 * and SETUP answers; every request after SETUP must echo it (`454` otherwise).  A DESCRIBE that
 * arrives before the encoder has published SPS/PPS is answered `503`, not with a parameter-less
 * SDP: a client must be told the stream is not ready instead of being handed a session that shows
 * nothing.
 *
 * Media: 12-byte RTP header, payload type 96, 90 kHz timestamps that advance exactly
 * 90000/fps ticks per encoded frame, marker bit on the last packet of an access unit,
 * random-but-stable SSRC, and NAL units larger than the MTU (1400) sent as FU-A.  The SPS/PPS from
 * the encoder's output format are replayed in band immediately before the first frame a session
 * receives, so a client that joins mid-stream decodes without waiting for a keyframe boundary; a
 * new PLAY replays them as well.
 *
 * INBOUND RTCP IS IGNORED: for a UDP client the server opens its own socket and drains the client's
 * RTCP port (client_port+1) so sender reports do not pile up, but it never parses them and never
 * answers an RR.  There is no congestion feedback loop; the bitrate is a configuration value.
 * (Stated here because it is an intentional omission, not an oversight.)
 *
 * There is NO reverse channel of any kind: no SET_PARAMETER input injection, no touch, no
 * back-channel payload in GET_PARAMETER (answered with an empty body, used only as a keep-alive).
 *
 * Threading: one accept loop, one control thread per connection, one sender thread per PLAYing
 * session.  Interleaved TCP needs the sender to own the socket writes while the control thread
 * keeps reading requests.  Every connection is an independent session, and they all share the one
 * encoder through {@link FrameHub}.
 */
package com.okcar.link.stream2;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class Rtsp2Server {

    /** Request URI path the media session lives at; the URL is rtsp://host:port/cast2. */
    public static final String TRACK_PATH = "/cast2";
    /** The `a=control:` value for that track. */
    public static final String TRACK_CONTROL = "cast2";
    /** Largest RTP packet we emit; NAL units above it are FU-A fragmented (RFC 6184). */
    public static final int MTU = 1400;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final FrameHub mHub;
    private final int mPort;

    private volatile ServerSocket mServer;
    private volatile Thread mAcceptThread;
    private volatile boolean mRunning;
    /**
     * The port actually bound.  It differs from {@link #mPort} when 0 was configured: an ephemeral
     * bind is how the host harness starts the server, and `status` must report the real number a
     * client can connect to.
     */
    private volatile int mBoundPort;
    private volatile int mWidth;
    private volatile int mHeight;
    private volatile int mFps = Cast2Config.DEFAULT_FPS;
    private final List<Session> mSessions = new ArrayList<Session>();

    public Rtsp2Server(FrameHub hub, int port) {
        mHub = hub;
        mPort = port;
    }

    /** Geometry for the SDP's `s=` line; a client reads it to size its own surface. */
    public void setGeometry(int width, int height, int fps) {
        mWidth = width;
        mHeight = height;
        if (fps > 0) {
            mFps = fps;
        }
    }

    /* --------------------------------------------------------------- lifecycle */

    /**
     * Binds and starts accepting.
     *
     * @throws IOException when the port cannot be bound; the caller reports that failure and does
     *     not start a stream it cannot serve.
     */
    public synchronized void start() throws IOException {
        if (mRunning) {
            return;
        }
        final ServerSocket server = new ServerSocket();
        try {
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(mPort));
        } catch (IOException e) {
            closeQuietly(server);
            throw new IOException("cannot listen on RTSP port " + mPort + ": " + e, e);
        }
        mServer = server;
        mBoundPort = server.getLocalPort();
        mRunning = true;
        mAcceptThread = new Thread(this::acceptLoop, "okcar-cast2-rtsp");
        mAcceptThread.setDaemon(true);
        mAcceptThread.start();
        log("listening on 0.0.0.0:" + mBoundPort + " track=rtsp://<ip>:" + mBoundPort
                + TRACK_PATH);
    }

    private void acceptLoop() {
        while (mRunning) {
            final ServerSocket server = mServer;
            if (server == null) {
                return;
            }
            final Socket socket;
            try {
                socket = server.accept();
            } catch (IOException e) {
                if (mRunning) {
                    log("accept failed: " + e);
                }
                continue;
            }
            final Session session = new Session(socket);
            synchronized (this) {
                mSessions.add(session);
            }
            final Thread thread = new Thread(session::controlLoop, "okcar-cast2-client");
            thread.setDaemon(true);
            session.mControlThread = thread;
            thread.start();
        }
    }

    /** Stops accepting and tears every session down; safe to call repeatedly. */
    public synchronized void stop() {
        mRunning = false;
        final ServerSocket server = mServer;
        mServer = null;
        closeQuietly(server);
        final List<Session> sessions = new ArrayList<Session>(mSessions);
        for (Session session : sessions) {
            session.close();
        }
        mSessions.clear();
        log("stopped");
    }

    public boolean isRunning() {
        return mRunning;
    }

    /** The bound port (after {@link #start}); the configured one before that. */
    public int port() {
        return mRunning ? mBoundPort : mPort;
    }

    /** Sessions that exist right now, PLAYing or not (a connected-but-idle client counts). */
    public synchronized int clientCount() {
        return mSessions.size();
    }

    /** Sessions that are receiving media. */
    public synchronized int playingCount() {
        int playing = 0;
        for (Session session : mSessions) {
            if (session.mPlaying) {
                playing++;
            }
        }
        return playing;
    }

    /** One line for `cast2 status`. */
    public synchronized String describe() {
        return "rtsp " + (mRunning ? "up :" + port() : "down") + " path=" + TRACK_PATH
                + " clients=" + mSessions.size() + " playing=" + playingCount();
    }

    private static void log(String message) {
        System.out.println("linkd: cast2 rtsp " + message);
    }

    /* ---------------------------------------------------------------- sessions */

    private final class Session {

        private final Socket mSocket;
        private volatile Thread mControlThread;
        private volatile Thread mSenderThread;
        private volatile boolean mClosed;
        private volatile boolean mPlaying;
        private String mSessionId;
        private boolean mTcpInterleaved;
        private InetAddress mRtpAddress;
        private int mRtpPort = -1;
        private int mRtcpPort = -1;
        private int mInterleavedRtp = 0;
        private int mInterleavedRtcp = 1;
        private DatagramSocket mRtpSocket;
        private boolean mParameterSetsSent;
        private RtpPacketizer mPacketizer;
        private long mSentFrames;

        Session(Socket socket) {
            mSocket = socket;
        }

        void controlLoop() {
            try {
                mSocket.setTcpNoDelay(true);
            } catch (Throwable ignored) {
                /* best effort: a delayed ACK only costs latency */
            }
            final String peer = peerText();
            try {
                final InputStream in = new BufferedInputStream(mSocket.getInputStream(), 8192);
                final OutputStream out = mSocket.getOutputStream();
                while (!mClosed) {
                    final RtspRequest request;
                    try {
                        request = RtspRequest.read(in);
                    } catch (RtspRequest.ProtocolException e) {
                        log(peer + ": malformed request (" + e.getMessage() + ") -> 400");
                        new RtspRequest.Response(400, "Bad Request", null).writeTo(out);
                        return;
                    }
                    if (request == null) {
                        return;
                    }
                    if (!dispatch(request, out)) {
                        return;
                    }
                }
            } catch (IOException e) {
                if (!mClosed) {
                    log(peer + ": connection ended: " + e);
                }
            } finally {
                close();
            }
        }

        /** @return false when the connection must close */
        private boolean dispatch(RtspRequest request, OutputStream out) throws IOException {
            final String method = request.method();
            final String cseq = request.header("CSeq");
            final String path = request.path();
            log("<- " + method + ' ' + request.uri() + " CSeq=" + cseq);
            if ("OPTIONS".equals(method)) {
                new RtspRequest.Response(200, "OK", cseq)
                        .header("Public", "OPTIONS, DESCRIBE, SETUP, PLAY, PAUSE, TEARDOWN, "
                                + "GET_PARAMETER")
                        .writeTo(out);
                return true;
            }
            if ("DESCRIBE".equals(method)) {
                if (!TRACK_PATH.equals(path) && !"*".equals(path)) {
                    notFound(cseq, out, path);
                    return true;
                }
                if (!mHub.hasParameterSets()) {
                    /* Loud failure instead of a parameter-less SDP: a client that gets 200 here
                     * would report "connected" and show nothing. */
                    log(peerText() + ": DESCRIBE before the encoder published SPS/PPS -> 503");
                    new RtspRequest.Response(503, "Service Unavailable", cseq)
                            .header("Retry-After", "1")
                            .body("text/plain", "the encoder has not produced SPS/PPS yet\n")
                            .writeTo(out);
                    return true;
                }
                new RtspRequest.Response(200, "OK", cseq)
                        .header("Content-Base", request.uri() + '/')
                        .header("Session", sessionId())
                        .body("application/sdp", SdpBuilder.build(new SdpBuilder.TrackSpec(
                                TRACK_CONTROL, mHub.sps(), mHub.pps(), mWidth, mHeight, mFps, false)))
                        .writeTo(out);
                return true;
            }
            if ("SETUP".equals(method)) {
                if (!sessionHeaderOk(request)) {
                    sessionNotFound(cseq, out);
                    return true;
                }
                return setup(request, cseq, out);
            }
            if ("PLAY".equals(method)) {
                if (!sessionHeaderOk(request)) {
                    sessionNotFound(cseq, out);
                    return true;
                }
                return play(request, cseq, out);
            }
            if ("PAUSE".equals(method)) {
                if (!sessionHeaderOk(request)) {
                    sessionNotFound(cseq, out);
                    return true;
                }
                stopSender();
                new RtspRequest.Response(200, "OK", cseq)
                        .header("Session", sessionId()).writeTo(out);
                return true;
            }
            if ("TEARDOWN".equals(method)) {
                if (!sessionHeaderOk(request)) {
                    sessionNotFound(cseq, out);
                    return true;
                }
                new RtspRequest.Response(200, "OK", cseq)
                        .header("Session", sessionId()).writeTo(out);
                return false;
            }
            if ("GET_PARAMETER".equals(method)) {
                if (!sessionHeaderOk(request)) {
                    sessionNotFound(cseq, out);
                    return true;
                }
                /* Keep-alive only: no parameter is ever written back and no reverse channel exists. */
                new RtspRequest.Response(200, "OK", cseq)
                        .header("Session", sessionId()).writeTo(out);
                return true;
            }
            new RtspRequest.Response(405, "Method Not Allowed", cseq)
                    .header("Allow", "OPTIONS, DESCRIBE, SETUP, PLAY, PAUSE, TEARDOWN, "
                            + "GET_PARAMETER")
                    .writeTo(out);
            return true;
        }

        private boolean setup(RtspRequest request, String cseq, OutputStream out) throws IOException {
            if (mPlaying) {
                log(peerText() + ": SETUP while PLAYing -> 455");
                new RtspRequest.Response(455, "Method Not Valid in This State", cseq)
                        .header("Session", sessionId()).writeTo(out);
                return true;
            }
            final String transport = request.header("Transport");
            if (transport == null) {
                new RtspRequest.Response(461, "Unsupported Transport", cseq).writeTo(out);
                return true;
            }
            final String lower = transport.toLowerCase(Locale.US);
            if (lower.contains("rtp/avp/tcp")) {
                final int[] channels = parseInterleaved(transport);
                if (channels == null) {
                    log(peerText() + ": TCP transport without interleaved= -> 461");
                    new RtspRequest.Response(461, "Unsupported Transport", cseq).writeTo(out);
                    return true;
                }
                mTcpInterleaved = true;
                mInterleavedRtp = channels[0];
                mInterleavedRtcp = channels[1];
                mRtpAddress = null;
                mRtpPort = -1;
                mRtcpPort = -1;
                new RtspRequest.Response(200, "OK", cseq)
                        .header("Session", sessionId())
                        .header("Transport", "RTP/AVP/TCP;unicast;interleaved=" + mInterleavedRtp
                                + '-' + mInterleavedRtcp)
                        .writeTo(out);
                log(peerText() + ": SETUP interleaved " + mInterleavedRtp + '-' + mInterleavedRtcp);
                return true;
            }
            /* Anything that is not interleaved TCP is the UDP profile: `RTP/AVP` alone is the
             * standard way a client writes it (RFC 2326 §12.39 — `RTP/AVP` means UDP, an explicit
             * `/UDP` suffix is optional), so requiring the word "udp" here refused real clients. */
            final int[] ports = parseClientPorts(transport);
            if (ports == null || ports[0] <= 0 || ports[0] + 1 > 65535) {
                log(peerText() + ": UDP transport without a usable client_port -> 461");
                new RtspRequest.Response(461, "Unsupported Transport", cseq).writeTo(out);
                return true;
            }
            mTcpInterleaved = false;
            mRtpAddress = mSocket.getInetAddress();
            mRtpPort = ports[0];
            mRtcpPort = ports.length > 1 && ports[1] > 0 ? ports[1] : ports[0] + 1;
            if (mRtpSocket == null) {
                mRtpSocket = new DatagramSocket();
                startRtcpDrain();
            }
            new RtspRequest.Response(200, "OK", cseq)
                    .header("Session", sessionId())
                    .header("Transport", "RTP/AVP;unicast;client_port=" + mRtpPort + '-' + mRtcpPort
                            + ";server_port=" + mRtpSocket.getLocalPort() + '-'
                            + (mRtpSocket.getLocalPort() + 1))
                    .writeTo(out);
            log(peerText() + ": SETUP udp client_port=" + mRtpPort + '-' + mRtcpPort);
            return true;
        }

        private boolean play(RtspRequest request, String cseq, OutputStream out) throws IOException {
            if (mRtpPort < 0 && !mTcpInterleaved) {
                log(peerText() + ": PLAY before SETUP -> 455");
                new RtspRequest.Response(455, "Method Not Valid in This State", cseq)
                        .header("Session", sessionId()).writeTo(out);
                return true;
            }
            stopSender();
            if (mPacketizer == null) {
                mPacketizer = new RtpPacketizer(MTU, RANDOM.nextInt());
            }
            final int sequence = mPacketizer.nextSequence();
            mSentFrames = 0;
            /* The first PLAY replays the parameter sets: a client that asked for PLAY before the
             * encoder's format change must still be able to decode. */
            mParameterSetsSent = false;
            mPlaying = true;
            new RtspRequest.Response(200, "OK", cseq)
                    .header("Session", sessionId())
                    .header("Range", "npt=0.000-")
                    .header("RTP-Info", "url=" + request.uri() + ";seq=" + sequence
                            + ";rtptime=0")
                    .writeTo(out);
            final Thread thread = new Thread(this::sendLoop, "okcar-cast2-send");
            thread.setDaemon(true);
            mSenderThread = thread;
            thread.start();
            log(peerText() + ": PLAY seq=" + sequence
                    + " transport=" + (mTcpInterleaved ? "tcp" : "udp/" + mRtpPort));
            return true;
        }

        /**
         * Media loop: parameter sets first, then every new access unit in order.
         *
         * <p>A session that joins between two frames starts from the live edge (the hub keeps only
         * the last few units), and one that falls behind skips forward rather than replaying an
         * ever-growing backlog — a viewer wants the current picture.  The RTP timestamp is the
         * session's own sent-frame counter times 90000/fps, so the 90 kHz clock advances by exactly
         * 3000 ticks per frame at 30 fps and does not inherit encoder or scheduler jitter.
         */
        private void sendLoop() {
            long lastIndex = -1;
            while (mPlaying && !mClosed) {
                try {
                    if (!mParameterSetsSent) {
                        final byte[] sps = mHub.sps();
                        final byte[] pps = mHub.pps();
                        if (sps != null && pps != null) {
                            /* The parameter sets are their own access unit: no marker bit, so a
                             * client cannot mistake them for a picture, and the first real frame
                             * then starts one frame later on the 90 kHz clock. */
                            final int ts = timestampFor(0);
                            send(mPacketizer.packetize(ts, sps, false));
                            send(mPacketizer.packetize(ts, pps, false));
                            mParameterSetsSent = true;
                            mSentFrames = 1;
                            log(peerText() + ": sent SPS/PPS in band (" + sps.length + '+'
                                    + pps.length + "B) as access unit ts=" + ts);
                        }
                    }
                    final FrameHub.AccessUnit unit = mHub.await(lastIndex, 500L);
                    if (unit == null) {
                        continue;
                    }
                    lastIndex = unit.index;
                    send(mPacketizer.packetize(timestampFor(mSentFrames), unit.nal, true));
                    mSentFrames++;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (IOException e) {
                    log(peerText() + ": media loop ended: " + e);
                    close();
                    return;
                } catch (Throwable t) {
                    log(peerText() + ": media loop failed: " + t);
                    close();
                    return;
                }
            }
        }

        private int timestampFor(long frameIndex) {
            return (int) ((frameIndex * (90000L / Math.max(1, mFps))) & 0xffffffffL);
        }

        private void send(List<byte[]> packets) throws IOException {
            if (mTcpInterleaved) {
                final OutputStream out = mSocket.getOutputStream();
                for (byte[] packet : packets) {
                    out.write('$');
                    out.write(mInterleavedRtp);
                    out.write((packet.length >> 8) & 0xff);
                    out.write(packet.length & 0xff);
                    out.write(packet);
                }
                out.flush();
                return;
            }
            final DatagramSocket socket = mRtpSocket;
            if (socket == null) {
                throw new IOException("no RTP socket");
            }
            for (byte[] packet : packets) {
                socket.send(new DatagramPacket(packet, packet.length, mRtpAddress, mRtpPort));
            }
        }

        /**
         * Drains inbound RTCP from the client's port.  Intentional no-op: see the class javadoc —
         * the server never answers a receiver report.
         */
        private void startRtcpDrain() {
            final DatagramSocket socket = mRtpSocket;
            if (socket == null) {
                return;
            }
            final Thread thread = new Thread(() -> {
                final byte[] buffer = new byte[1500];
                while (!mClosed && !socket.isClosed()) {
                    try {
                        socket.receive(new DatagramPacket(buffer, buffer.length));
                    } catch (IOException e) {
                        return;
                    }
                }
            }, "okcar-cast2-rtcp");
            thread.setDaemon(true);
            thread.start();
        }

        /**
         * Stops the media loop and waits for it to leave {@link #send}, so a following PLAY cannot
         * have two threads interleaving writes on the same socket.
         */
        private void stopSender() {
            mPlaying = false;
            final Thread thread = mSenderThread;
            mSenderThread = null;
            if (thread != null && thread != Thread.currentThread()) {
                thread.interrupt();
                try {
                    thread.join(1500L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        private String sessionId() {
            if (mSessionId == null) {
                mSessionId = String.format(Locale.US, "%016x", RANDOM.nextLong());
            }
            return mSessionId;
        }

        private boolean sessionHeaderOk(RtspRequest request) {
            final String header = request.header("Session");
            return header != null && sessionId().equals(header.trim());
        }

        private String peerText() {
            return String.valueOf(mSocket.getRemoteSocketAddress());
        }

        void close() {
            if (mClosed) {
                return;
            }
            mClosed = true;
            stopSender();
            closeQuietly(mSocket);
            closeQuietly(mRtpSocket);
            synchronized (Rtsp2Server.this) {
                mSessions.remove(this);
            }
            log(peerText() + ": session closed");
        }
    }

    private static void notFound(String cseq, OutputStream out, String path) throws IOException {
        new RtspRequest.Response(404, "Not Found", cseq)
                .body("text/plain", "unknown resource: " + path + "\n(use " + TRACK_PATH + ")\n")
                .writeTo(out);
    }

    private static void sessionNotFound(String cseq, OutputStream out) throws IOException {
        new RtspRequest.Response(454, "Session Not Found", cseq).writeTo(out);
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Throwable ignored) {
            /* best effort */
        }
    }

    /** {@code client_port=9000-9001}; null when the header has none. */
    static int[] parseClientPorts(String transport) {
        return parsePortPair(transport, "client_port=");
    }

    /** {@code interleaved=0-1}; null when the header has none. */
    static int[] parseInterleaved(String transport) {
        return parsePortPair(transport, "interleaved=");
    }

    private static int[] parsePortPair(String transport, String key) {
        final int at = transport.toLowerCase(Locale.US).indexOf(key);
        if (at < 0) {
            return null;
        }
        final String rest = transport.substring(at + key.length()).trim();
        final StringBuilder digits = new StringBuilder();
        int index = 0;
        while (index < rest.length() && (Character.isDigit(rest.charAt(index))
                || rest.charAt(index) == '-')) {
            digits.append(rest.charAt(index));
            index++;
        }
        final String[] parts = digits.toString().split("-");
        if (parts.length == 0 || parts[0].isEmpty()) {
            return null;
        }
        try {
            final int first = Integer.parseInt(parts[0]);
            final int second = parts.length > 1 && !parts[1].isEmpty()
                    ? Integer.parseInt(parts[1]) : first + 1;
            return new int[]{first, second};
        } catch (RuntimeException e) {
            return null;
        }
    }
}
