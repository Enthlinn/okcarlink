package com.okcar.link.wireless;

import android.content.Context;
import android.util.Log;

import com.okcar.link.LinkState;
import com.okcar.link.net.MdnsV4Responder;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Reverse CarPlay control channel — the <em>phone</em> side of
 * {@code _carplay-ctrl._tcp}.
 *
 * <p>Measured with Fruit Link ({@code com.autochips.carplayapp}) on 2026-09-27: after the
 * accessory dials our Wireless-iAP RFCOMM UUID and gets our DETECT/StartIDPS, it browses
 * mDNS for {@code _carplay-ctrl._tcp}, resolves the phone's address + port and runs
 * {@code CarPlayControl connect}.  With nothing published it retried three times, logged
 * {@code CarPlayControl command "connect" failed, error -6722}, then
 * {@code carplay:connecting timeout:N,errors:N} and finally killed itself asking for a
 * reboot.  The closed-source {@code com.okcar.autoconn} stack publishes exactly this
 * record (name "OKCAR", dynamic port, TXT srcvers/id), which is why the accessory used to
 * reach <em>it</em> on port 41097 while the BT/iAP2 link belonged to us.
 *
 * <p>So the wireless session publishes the same record and serves the port.  The payload
 * on this channel is Apple's CarPlay control plane; we log whatever arrives (hex + ASCII)
 * and answer RTSP-looking requests with a minimal 200 so the accessory's
 * {@code CarPlayControl connect} completes and we can see the next step.
 */
public class WirelessControlChannel {

    private static final String TAG = "OkCarLink/WirelessCtrl";

    private final Context mContext;
    private final String mDeviceId;
    private final String mServiceName;

    private volatile ServerSocket mServer;
    /** Our own IPv4 mDNS responder (see the class comment for why NsdManager is not used). */
    private volatile MdnsV4Responder mResponder;

    /** The SRV target we publish; the accessory resolves this name to our IPv4. */
    private static final String MDNS_HOST = "okcarlink.local";
    private volatile boolean mRunning;
    private volatile int mPort = -1;
    private volatile Thread mAcceptThread;
    private final AtomicInteger mConnections = new AtomicInteger();

    public WirelessControlChannel(Context context, String deviceId, String serviceName) {
        mContext = context;
        mDeviceId = deviceId == null ? "" : deviceId;
        mServiceName = serviceName == null ? "iPhone" : serviceName;
    }

    public int port() {
        return mPort;
    }

    public int connections() {
        return mConnections.get();
    }

    /** Opens the socket, publishes {@code _carplay-ctrl._tcp} and starts accepting. */
    public boolean start() {
        if (mRunning) {
            return true;
        }
        try {
            final ServerSocket server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(0));
            mServer = server;
            mPort = server.getLocalPort();
        } catch (Exception e) {
            LinkState.get().log("wireless", "carplay-ctrl: cannot listen: " + e);
            return false;
        }
        /* The accessory resolves this record to find us; without the advertisement it falls back to
         * whatever stale record is in its cache and never reaches us.  We publish it ourselves over
         * IPv4: NsdManager answered the accessory's query with an IPv6 address while the accessory has
         * only IPv4 on that link (`control resolved family=IPv6` + `NO_MATCHING_ADDRESS ipv4=1
         * ipv6=0`), and that failed probe is what made the emulator recycle its receiver every 1-2 minutes. */
        try {
            final java.util.LinkedHashMap<String, String> txt =
                    new java.util.LinkedHashMap<String, String>();
            txt.put("id", mDeviceId == null ? "" : mDeviceId);
            txt.put("srcvers", com.okcar.link.airplay.AirPlay.PROTOCOL_VERSION);
            mResponder = new MdnsV4Responder(
                    com.okcar.link.airplay.AirPlay.SERVICE_CARPLAY_CTRL,
                    mServiceName, MDNS_HOST, mPort, txt);
            final boolean advertised = mResponder.start();
            LinkState.get().log("wireless", "carplay-ctrl: listening on " + mPort
                    + ", advertised as " + mServiceName + " over IPv4 " + mResponder.addresses()
                    + " (as " + mResponder.host() + ", ok=" + advertised + ')');
        } catch (Throwable t) {
            LinkState.get().log("wireless", "carplay-ctrl: advertise failed: " + t);
        }
        mRunning = true;
        final Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                acceptLoop();
            }
        }, "okcar-carplay-ctrl");
        thread.setDaemon(true);
        mAcceptThread = thread;
        thread.start();
        return true;
    }

    public void stop() {
        mRunning = false;
        final ServerSocket server = mServer;
        mServer = null;
        if (server != null) {
            try {
                server.close();
            } catch (Exception ignored) {
                /* closing anyway */
            }
        }
        final MdnsV4Responder responder = mResponder;
        mResponder = null;
        if (responder != null) {
            try {
                responder.stop();
            } catch (Throwable ignored) {
                /* best effort */
            }
        }
        mPort = -1;
    }

    private void acceptLoop() {
        while (mRunning) {
            final ServerSocket server = mServer;
            if (server == null) {
                return;
            }
            Socket socket = null;
            try {
                socket = server.accept();
            } catch (Exception e) {
                if (mRunning) {
                    LinkState.get().log("wireless", "carplay-ctrl: accept ended: " + e);
                }
                return;
            }
            final int index = mConnections.incrementAndGet();
            LinkState.get().log("wireless", "carplay-ctrl: connection #" + index + " from "
                    + socket.getInetAddress().getHostAddress() + ':' + socket.getPort());
            final Socket accepted = socket;
            final Thread worker = new Thread(new Runnable() {
                @Override
                public void run() {
                    serve(accepted, index);
                }
            }, "okcar-carplay-ctrl-" + index);
            worker.setDaemon(true);
            worker.start();
        }
    }

    private void serve(Socket socket, int index) {
        OutputStream out = null;
        try {
            socket.setSoTimeout(0);
            final InputStream in = socket.getInputStream();
            out = socket.getOutputStream();
            final byte[] buffer = new byte[1024];
            int total = 0;
            int read;
            while ((read = in.read(buffer)) > 0) {
                if (read > 0) {
                    total += read;
                    if (total <= 4096) {
                        LinkState.get().log("carplay-ctrl#" + index, "rx " + read + "B "
                                + describe(buffer, read));
                        answerIfRtsp(out, buffer, read, index);
                    }
                }
            }
            LinkState.get().log("wireless", "carplay-ctrl: connection #" + index
                    + " closed after " + total + "B");
        } catch (Exception e) {
            LinkState.get().log("wireless", "carplay-ctrl: connection #" + index + " ended: " + e);
        } finally {
            try {
                socket.close();
            } catch (Exception ignored) {
                /* closing anyway */
            }
        }
    }

    /**
     * The protocol token of a request line ({@code HTTP/1.1}, {@code RTSP/1.0}, …), defaulting to
     * RTSP when the line cannot be read.
     */
    private static String requestProtocol(String text) {
        final int lineEnd = text.indexOf("\r\n");
        final String requestLine = lineEnd < 0 ? text : text.substring(0, lineEnd);
        final String[] parts = requestLine.split(" ");
        if (parts.length >= 3 && parts[2].startsWith("HTTP/")) {
            return parts[2];
        }
        if (parts.length >= 3 && parts[2].startsWith("RTSP/")) {
            return parts[2];
        }
        return "RTSP/1.0";
    }

    /** Minimal RTSP answer so the accessory's connect/handshake can complete. */
    private void answerIfRtsp(OutputStream out, byte[] buffer, int length, int index) {
        final String text = new String(buffer, 0, length,
                java.nio.charset.StandardCharsets.ISO_8859_1);
        final String upper = text.toUpperCase(java.util.Locale.US);
        if (!(upper.startsWith("OPTIONS ") || upper.startsWith("POST ") || upper.startsWith("GET ")
                || upper.startsWith("SETUP ") || upper.startsWith("RECORD ")
                || upper.startsWith("TEARDOWN ") || upper.startsWith("ANNOUNCE ")
                || upper.startsWith("SET_PARAMETER ") || upper.startsWith("FLUSH "))) {
            return;
        }
        String cseq = "";
        for (String line : text.split("\r\n")) {
            if (line.toLowerCase(java.util.Locale.US).startsWith("cseq:")) {
                cseq = line.substring(5).trim();
                break;
            }
        }
        /* Answer in the protocol the request used.  The reverse-control plane is plain HTTP
         * (`GET /ctrl-int/1/connect HTTP/1.1`) and the accessory parses the reply's status line with
         * `^HTTP/\d(?:\.\d)? (\d{3})` (the emulator, CarPlayBonjour.kt): answering `RTSP/1.0 200 OK` to an
         * HTTP request made its probe report `status=none` and never count as connected, which is
         * what kept its receiver recycling every 1-2 minutes. */
        final String protocol = requestProtocol(text);
        final String response = protocol + " 200 OK\r\n"
                + "Server: AirTunes/480.5\r\n"
                + (cseq.isEmpty() ? "" : "CSeq: " + cseq + "\r\n")
                + "Content-Length: 0\r\n\r\n";
        try {
            out.write(response.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
            out.flush();
            LinkState.get().log("carplay-ctrl#" + index, "tx " + protocol + " 200 OK (cseq "
                    + cseq + ')');
        } catch (Exception e) {
            Log.w(TAG, "cannot answer RTSP: " + e);
        }
    }

    private static String describe(byte[] buffer, int length) {
        final StringBuilder hex = new StringBuilder(length * 3);
        for (int i = 0; i < length && i < 256; i++) {
            hex.append(String.format(java.util.Locale.US, "%02x ", buffer[i]));
        }
        final String ascii = new String(buffer, 0, Math.min(length, 256),
                java.nio.charset.StandardCharsets.ISO_8859_1)
                .replaceAll("[^\\x20-\\x7e]", ".");
        return hex.toString().trim() + " | " + ascii;
    }
}
