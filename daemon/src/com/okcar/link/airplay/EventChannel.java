/*
 * OkcarOS link stack — L4: the receiver's event channel.
 *
 * The base SETUP answer advertises `eventPort`.  The working sender immediately
 * opens a TCP connection there and keeps it for the whole session: measured in
 * the recorded reference session (sender 43630 <-> receiver eventPort 36897, 26/30 packets) it is the
 * **reverse control** path.  The head unit sends RTSP requests on it — every
 * measured one is `POST /command` with a binary plist
 *
 *     {'type': 'hidSendReport', 'uuid': '0', 'hidReport': <5 bytes>}
 *
 * and the sender answers `RTSP/1.0 200 OK` with `Date` + `Content-Length: 0`,
 * which is exactly what the capture shows.
 *
 * Why the channel matters beyond touch: with no connection to the advertised
 * eventPort the receiver sat on our `RECORD` for 10 s and then answered
 * `500 Internal Server Error` (enc3.pcap, 2026-09-26), while the same request
 * bytes replayed by hand — where the receiver's answer came immediately — were
 * accepted.  Opening the channel is therefore part of the session handshake.
 */

package com.okcar.link.airplay;

import com.okcar.link.LinkState;
import com.okcar.link.plist.BinaryPlist;
import com.okcar.link.rtsp.RtspMessage;
import com.okcar.link.util.Hex;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

public final class EventChannel {

    private static final String TAG = "event";

    /** What the head unit sends on this channel (measured). */
    public static final String KEY_HID_REPORT = "hidReport";
    public static final String TYPE_HID_SEND_REPORT = "hidSendReport";

    /** Consumer for decoded HID reports (may be null). */
    public interface Sink {
        void onHidReport(byte[] report);
    }

    /**
     * Consumer for the head unit's other /command messages (may be null).
     * Measured 2026-09-26 (§12.34): it sends {type=changeModes, ...} and
     * {type=requestUI} when the driver leaves and re-enters CarPlay, and the
     * screen stays black until the sender answers requestUI.
     */
    public interface CommandSink {
        void onCommand(String type, Map<String, Object> plist);
    }

    private final String mHost;
    private final int mPort;
    private final Sink mSink;
    private final CommandSink mCommandSink;
    private final LinkState mState = LinkState.get();

    private Socket mSocket;
    private InputStream mIn;
    private OutputStream mOut;
    private Thread mThread;
    private volatile boolean mRunning;
    private volatile long mMessages;
    private volatile String mError = "";
    /** Connect timeout handed to {@link #start}, reused by every reconnect. */
    private volatile int mTimeoutMs = 3000;

    /** Gap between an ended event channel and its reconnect attempt. */
    private static final long RECONNECT_DELAY_MS = 1000;

    public EventChannel(String host, int port, Sink sink) {
        this(host, port, sink, null);
    }

    public EventChannel(String host, int port, Sink sink, CommandSink commandSink) {
        mHost = host;
        mPort = port;
        mSink = sink;
        mCommandSink = commandSink;
    }

    public int port() {
        return mPort;
    }

    public long messages() {
        return mMessages;
    }

    public String error() {
        return mError;
    }

    public synchronized void start(int timeoutMs) throws IOException {
        if (mRunning) {
            return;
        }
        mTimeoutMs = timeoutMs;
        connect(timeoutMs);
        mRunning = true;
        mThread = new Thread(new Runnable() {
            @Override
            public void run() {
                supervise();
            }
        }, "okcar-airplay-event");
        mThread.setDaemon(true);
        mThread.start();
        mState.log(TAG, "event channel up: " + mHost + ':' + mPort);
    }

    /** Opens one connection; the caller owns {@code mSocket/mIn/mOut}. */
    private synchronized void connect(int timeoutMs) throws IOException {
        final Socket socket = new Socket();
        socket.connect(new InetSocketAddress(mHost, mPort), timeoutMs);
        socket.setTcpNoDelay(true);
        mSocket = socket;
        mIn = new BufferedInputStream(socket.getInputStream());
        mOut = socket.getOutputStream();
    }

    /**
     * Keeps the channel connected for as long as the session wants it.
     *
     * <p>The event channel is a separate TCP connection, and it dies with the accessory's receiver
     * process — which both the emulator and the head units recycle every couple of minutes.  It used to be
     * opened once per session, so the first recycle silently killed touch for the rest of the session:
     * measured 2026-10-01 19:17, the last {@code POST /command} arrived at 19:17:29 while the
     * accessory was still sending touches at 19:20 and the car screen simply stopped reacting.
     * Reconnecting is the whole fix — the host/port stay valid and every request is answered exactly
     * as before.
     */
    private void supervise() {
        int attempt = 0;
        while (mRunning) {
            loop();
            if (!mRunning) {
                return;
            }
            attempt++;
            mState.log(TAG, "event channel ended (" + (mError.isEmpty() ? "EOF" : mError)
                    + ") — reconnecting (attempt " + attempt + ')');
            try {
                Thread.sleep(RECONNECT_DELAY_MS);
            } catch (InterruptedException e) {
                return;
            }
            if (!mRunning) {
                return;
            }
            try {
                connect(mTimeoutMs);
                mError = "";
                mState.log(TAG, "event channel reconnected: " + mHost + ':' + mPort);
            } catch (IOException e) {
                mError = String.valueOf(e);
                mState.log(TAG, "event reconnect failed: " + e);
            }
        }
    }

    private void loop() {
        while (mRunning) {
            if (mIn == null) {
                return;
            }
            try {
                final RtspMessage request = RtspMessage.parse(mIn);
                if (request == null) {
                    break;
                }
                handle(request);
            } catch (IOException e) {
                if (mRunning) {
                    mError = String.valueOf(e);
                    mState.log(TAG, "event read failed: " + e);
                }
                break;
            }
        }
    }

    /**
     * The head unit uses this channel for more than touch: when CarPlay is left
     * and re-entered it sends /command messages that we used to log only as
     * "rx POST /command" — and the re-created decoder then stays black because
     * nothing answered them (§12.34).  Dump method, URI, body and plist so the
     * sender can reply correctly.
     */
    private static String describeBody(RtspMessage request) {
        final byte[] body = request.body();
        if (body == null || body.length == 0) {
            return " body=0B";
        }
        final StringBuilder sb = new StringBuilder(" body=").append(body.length).append("B ");
        sb.append(body.length <= 256 ? Hex.encode(body) : Hex.encode(body, 0, 256) + "…");
        final Map<String, Object> plist = decodePlist(body);
        if (plist != null) {
            sb.append(" plist=").append(plist);
        }
        return sb.toString();
    }

    /** Decodes a binary-plist body into a string-keyed map, or null. */
    private static Map<String, Object> decodePlist(byte[] body) {
        if (body == null || body.length == 0 || !BinaryPlist.looksLikePlist(body)) {
            return null;
        }
        try {
            final Object decoded = BinaryPlist.decode(body);
            if (decoded instanceof Map) {
                final Map<?, ?> raw = (Map<?, ?>) decoded;
                final Map<String, Object> out = new java.util.LinkedHashMap<String, Object>();
                for (Map.Entry<?, ?> entry : raw.entrySet()) {
                    out.put(String.valueOf(entry.getKey()), entry.getValue());
                }
                return out;
            }
        } catch (Exception e) {
            return null;
        }
        return null;
    }

    private void handle(RtspMessage request) throws IOException {        mMessages++;
        final byte[] report = extractHidReport(request);
        if (report != null) {
            mState.log(TAG, "hidReport " + report.length + "B " + Hex.encode(report)
                    + (mSink == null ? " (no sink)" : ""));
            if (mSink != null) {
                mSink.onHidReport(report);
            }
        } else {
            mState.log(TAG, "rx " + request.method() + ' ' + request.uri()
                    + describeBody(request));
            final Map<String, Object> plist = decodePlist(request.body());
            if (plist != null && mCommandSink != null) {
                final Object type = plist.get("type");
                mCommandSink.onCommand(type == null ? "" : String.valueOf(type), plist);
            }
        }
        final RtspMessage reply = RtspMessage.response(request, 200, "OK");
        reply.setHeader("Date", httpDate());
        reply.setHeader("Content-Length", "0");
        synchronized (this) {
            if (mOut != null) {
                reply.write(mOut);
                mOut.flush();
            }
        }
    }

    /** Pulls `hidReport` out of the measured `POST /command` plist. */
    @SuppressWarnings("unchecked")
    private static byte[] extractHidReport(RtspMessage request) {
        if (!request.isBinaryPlist()) {
            return null;
        }
        try {
            final Object body = request.plistBody();
            if (!(body instanceof Map)) {
                return null;
            }
            final Object report = ((Map<String, Object>) body).get(KEY_HID_REPORT);
            return report instanceof byte[] ? (byte[]) report : null;
        } catch (IOException e) {
            return null;
        }
    }

    /** The measured reply header: `Date: Fri, 25 09 2026 21:13:56 GMT+08:00`. */
    private static String httpDate() {
        final SimpleDateFormat fmt =
                new SimpleDateFormat("EEE, dd MM yyyy HH:mm:ss 'GMT'Z", Locale.US);
        fmt.setTimeZone(TimeZone.getDefault());
        return fmt.format(new Date());
    }

    /** The raw plist keys of the last request, for logs/tests. */
    static List<String> plistKeys(Map<String, Object> body) {
        return new java.util.ArrayList<String>(body.keySet());
    }

    public synchronized void stop() {
        mRunning = false;
        final Thread thread = mThread;
        mThread = null;
        if (thread != null) {
            thread.interrupt();
        }
        try {
            if (mSocket != null) {
                mSocket.close();
            }
        } catch (IOException ignored) {
            /* closing anyway */
        }
        mSocket = null;
        mIn = null;
        mOut = null;
    }
}
