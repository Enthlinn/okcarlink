/*
 * OkcarOS link stack — display-2 HTTP control API (the reserved head-unit-side program API).
 *
 * A single-threaded raw-socket HTTP/1.1 server on 0.0.0.0:<http_port> (default 18080), started only
 * by `cast2 listen on` and OFF by default.  No third-party library and no Android HTTP client:
 * java.net.ServerSocket, one connection at a time, `Connection: close` on every answer.
 *
 * ------------------------------------------------------------------ endpoints
 *
 *   GET /status                    -> 200 text/plain, the same multi-line body `cast2 status`
 *                                     prints (display/encoder/RTSP/HTTP listener state).
 *   GET /cast2/start[?size=WxH&app=pkg]
 *                                  -> 200 "ok" after display 2, the encoder and the RTSP server are
 *                                     up and the configured app was placed.  `size` and `app` are
 *                                     persisted first (they are `cast2 up WxH` / `cast2 app <pkg>`).
 *                                     Starting an already-running display is idempotent and answers
 *                                     200 with the unchanged state; a size that differs from the
 *                                     live display is a 500 with the reason, never a silent resize.
 *   GET /cast2/stop                -> 200 "ok" after display 2, the encoder and every RTSP session
 *                                     are released (the RTSP listener itself stays up: `rtsp down`
 *                                     stops that).
 *   GET /rtsp/start                -> 200 "ok" after the RTSP listener is bound.
 *   GET /rtsp/stop                 -> 200 "ok" after the listener and its sessions are gone.
 *   anything else                  -> 404 with a text body naming the path.
 *
 * A failure answers 500 with the failure text (for example "cannot create display 2: ..." or
 * "this device has no video/avc encoder: ...").  There is no fallback endpoint and no "ok" that
 * hides a failure: the caller is expected to read the body.
 *
 * ------------------------------------------------------------ equivalent FIFO commands
 *
 * The head-unit program may talk to the daemon's stdin FIFO (/data/local/tmp/okcar-linkd.in)
 * instead of HTTP; the two surfaces are the same calls:
 *
 *   GET /status         <-> `status` (the `linkd: cast2 ...` lines) or `cast2 status`
 *   GET /cast2/start    <-> `cast2 up [WxH]`   (size=WxH is that optional argument)
 *   GET /cast2/stop     <-> `cast2 down`
 *   GET /rtsp/start     <-> `rtsp up`
 *   GET /rtsp/stop      <-> `rtsp down`
 *   (no endpoint)       <-> `cast2 app <pkg>`, `cast2 listen on|off`, `rtsp status`
 *
 * The listener is not a daemon-management channel: it cannot start or stop the daemon itself, and
 * it never injects input into display 2 (there is no touch return path at all).
 */
package com.okcar.link.stream2;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public final class Cast2HttpControl {

    private final int mPort;
    private final Cast2Controller mController;

    private volatile ServerSocket mServer;
    private volatile Thread mAcceptThread;
    private volatile boolean mRunning;
    /** The port actually bound; differs from {@link #mPort} only when 0 was configured. */
    private volatile int mBoundPort;
    private volatile long mRequests;
    private volatile long mFailures;

    public Cast2HttpControl(int port, Cast2Controller controller) {
        mPort = port;
        mController = controller;
    }

    /**
     * Binds and starts accepting.
     *
     * @throws IOException when the port cannot be bound; the caller reports the failure and leaves
     *     the listener OFF (`cast2 listen on` then answers with the exception, not with "on").
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
            throw new IOException("cannot listen on HTTP port " + mPort + ": " + e, e);
        }
        mServer = server;
        mBoundPort = server.getLocalPort();
        mRunning = true;
        mAcceptThread = new Thread(this::acceptLoop, "okcar-cast2-http");
        mAcceptThread.setDaemon(true);
        mAcceptThread.start();
        log("control API listening on 0.0.0.0:" + mBoundPort);
    }

    public synchronized void stop() {
        mRunning = false;
        final ServerSocket server = mServer;
        mServer = null;
        closeQuietly(server);
        final Thread thread = mAcceptThread;
        mAcceptThread = null;
        if (thread != null) {
            thread.interrupt();
        }
        log("control API stopped");
    }

    public boolean isRunning() {
        return mRunning;
    }

    /** The bound port (after {@link #start}); the configured one before that. */
    public int port() {
        return mRunning ? mBoundPort : mPort;
    }

    public long requests() {
        return mRequests;
    }

    public long failures() {
        return mFailures;
    }

    public String describe() {
        return "listen=" + (mRunning ? "on :" + port() : "off") + " port=" + port()
                + " requests=" + mRequests;
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
            /* One request at a time, on its own thread: a `cast2 start` blocks for as long as the
             * display and codec take, and the accept loop must stay able to answer /status. */
            final Thread thread = new Thread(() -> serve(socket), "okcar-cast2-http-req");
            thread.setDaemon(true);
            thread.start();
        }
    }

    private void serve(Socket socket) {
        try {
            socket.setSoTimeout(10_000);
            final InputStream in = new BufferedInputStream(socket.getInputStream(), 4096);
            final OutputStream out = socket.getOutputStream();
            final String requestLine = readLine(in);
            if (requestLine == null || requestLine.isEmpty()) {
                respond(out, 400, "Bad Request", "empty request\n");
                return;
            }
            /* Drain the headers: HTTP/1.1 clients send Host, and some send a body header. */
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                /* nothing in the headers changes the answer */
            }
            final String[] parts = requestLine.split("\\s+");
            if (parts.length < 2) {
                respond(out, 400, "Bad Request", "malformed request line\n");
                return;
            }
            final String method = parts[0].toUpperCase(Locale.US);
            if (!"GET".equals(method) && !"HEAD".equals(method)) {
                respond(out, 405, "Method Not Allowed", "only GET is served\n");
                return;
            }
            final String target = parts[1];
            final String path = pathOf(target);
            final String query = queryOf(target);
            mRequests++;
            handle(path, query, out);
        } catch (Throwable t) {
            mFailures++;
            log("request failed: " + t);
            try {
                respond(socket.getOutputStream(), 500, "Internal Server Error",
                        String.valueOf(t) + '\n');
            } catch (Throwable ignored) {
                /* the connection is already gone */
            }
        } finally {
            closeQuietly(socket);
        }
    }

    private void handle(String path, String query, OutputStream out) throws IOException {
        if ("/status".equals(path) || "/".equals(path)) {
            final StringBuilder body = new StringBuilder();
            for (String line : mController.statusLines()) {
                body.append(line).append('\n');
            }
            respond(out, 200, "OK", body.toString());
            return;
        }
        if ("/cast2/start".equals(path)) {
            try {
                final String size = queryValue(query, "size");
                if (size != null && !size.isEmpty()) {
                    final int[] parsed = Cast2Config.parseSize(size);
                    if (parsed == null) {
                        mFailures++;
                        respond(out, 500, "Internal Server Error",
                                "bad size '" + size + "' (want WxH)\n");
                        return;
                    }
                    mController.setSize(parsed[0], parsed[1]);
                }
                final String app = queryValue(query, "app");
                if (app != null && !app.isEmpty()) {
                    mController.setApp(app);
                }
                mController.start();
                respond(out, 200, "OK", "ok\n");
            } catch (Throwable t) {
                mFailures++;
                respond(out, 500, "Internal Server Error", failure("cast2 start", t));
            }
            return;
        }
        if ("/cast2/stop".equals(path)) {
            try {
                mController.stopCast2();
                respond(out, 200, "OK", "ok\n");
            } catch (Throwable t) {
                mFailures++;
                respond(out, 500, "Internal Server Error", failure("cast2 stop", t));
            }
            return;
        }
        if ("/rtsp/start".equals(path)) {
            try {
                mController.startRtsp();
                respond(out, 200, "OK", "ok\n");
            } catch (Throwable t) {
                mFailures++;
                respond(out, 500, "Internal Server Error", failure("rtsp start", t));
            }
            return;
        }
        if ("/rtsp/stop".equals(path)) {
            try {
                mController.stopRtsp();
                respond(out, 200, "OK", "ok\n");
            } catch (Throwable t) {
                mFailures++;
                respond(out, 500, "Internal Server Error", failure("rtsp stop", t));
            }
            return;
        }
        mFailures++;
        respond(out, 404, "Not Found", "unknown path: " + path
                + "\nGET /status | /cast2/start[?size=WxH&app=pkg] | /cast2/stop | /rtsp/start"
                + " | /rtsp/stop\n");
    }

    private static String failure(String what, Throwable t) {
        final Throwable cause = t.getCause() == null ? t : t.getCause();
        return what + " failed: " + cause + '\n';
    }

    private static void respond(OutputStream out, int status, String reason, String body)
            throws IOException {
        final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        final StringBuilder head = new StringBuilder();
        head.append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n");
        head.append("Content-Type: text/plain; charset=utf-8\r\n");
        head.append("Content-Length: ").append(bytes.length).append("\r\n");
        head.append("Connection: close\r\n");
        head.append("\r\n");
        out.write(head.toString().getBytes(StandardCharsets.US_ASCII));
        out.write(bytes);
        out.flush();
    }

    static String pathOf(String target) {
        final int query = target.indexOf('?');
        return query < 0 ? target : target.substring(0, query);
    }

    static String queryOf(String target) {
        final int query = target.indexOf('?');
        return query < 0 ? "" : target.substring(query + 1);
    }

    /** Value of {@code key} in a {@code a=b&c=d} query, or null when absent. */
    static String queryValue(String query, String key) {
        if (query == null || query.isEmpty()) {
            return null;
        }
        for (String pair : query.split("&")) {
            final int equals = pair.indexOf('=');
            if (equals <= 0) {
                continue;
            }
            if (pair.substring(0, equals).equals(key)) {
                return urlDecode(pair.substring(equals + 1));
            }
        }
        return null;
    }

    /** Enough percent-decoding for a package name or a size; no '+' handling (not a form). */
    static String urlDecode(String value) {
        final StringBuilder decoded = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            final char c = value.charAt(i);
            if (c == '%' && i + 2 < value.length()) {
                try {
                    decoded.append((char) Integer.parseInt(value.substring(i + 1, i + 3), 16));
                    i += 2;
                    continue;
                } catch (NumberFormatException ignored) {
                    /* keep the literal '%' */
                }
            }
            decoded.append(c);
        }
        return decoded.toString();
    }

    private static String readLine(InputStream in) throws IOException {
        final StringBuilder line = new StringBuilder();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') {
                break;
            }
            if (c == '\r') {
                continue;
            }
            line.append((char) c);
            if (line.length() > 8 * 1024) {
                throw new IOException("request line too long");
            }
        }
        if (c < 0 && line.length() == 0) {
            return null;
        }
        return line.toString();
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

    private static void log(String message) {
        System.out.println("linkd: cast2 http " + message);
    }
}
