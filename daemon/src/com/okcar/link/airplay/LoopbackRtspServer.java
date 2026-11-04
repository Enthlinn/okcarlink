/*
 * OkcarOS link stack — L4 session layer: a loopback RTSP peer for self tests.
 *
 * The point is to exercise AirPlaySession end to end without a car: accept one
 * connection and answer like the head unit does, with the ports that were
 * actually observed in the field (video 36113 / audio 36486, §12.17 C).
 *
 * This is test-support code that ships inside the app deliberately: it is how the
 * on-device self test proves the session walk (info → auth → SETUP → RECORD)
 * works, the same way Iap2SelfTest exercises the link state machine over a
 * loopback transport.
 */

package com.okcar.link.airplay;

import com.okcar.link.rtsp.RtspMessage;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class LoopbackRtspServer {

    /** The ports the production head unit handed out in the field (§12.17 C). */
    public static final int FIELD_VIDEO_PORT = 36113;
    public static final int FIELD_AUDIO_PORT = 36486;

    public interface Handler {
        /** Answers one request; return null to drop the connection. */
        RtspMessage answer(RtspMessage request) throws IOException;
    }

    private final ServerSocket mServer;
    private Thread mThread;
    private volatile boolean mRunning;
    private volatile int mRequests;
    private volatile String mError = "";

    private LoopbackRtspServer(ServerSocket server) {
        mServer = server;
    }

    /** Binds an ephemeral port on the loopback interface. */
    public static LoopbackRtspServer start() throws IOException {
        final ServerSocket server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress("127.0.0.1", 0));
        final LoopbackRtspServer instance = new LoopbackRtspServer(server);
        instance.serve(defaultHandler());
        return instance;
    }

    public int port() {
        return mServer.getLocalPort();
    }

    public int requestCount() {
        return mRequests;
    }

    public String error() {
        return mError;
    }

    private void serve(final Handler handler) {
        mRunning = true;
        mThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final Socket socket = mServer.accept();
                    socket.setTcpNoDelay(true);
                    final InputStream in = socket.getInputStream();
                    final OutputStream out = socket.getOutputStream();
                    while (mRunning) {
                        final RtspMessage request = RtspMessage.parse(in);
                        if (request == null) {
                            break;
                        }
                        mRequests++;
                        final RtspMessage reply = handler.answer(request);
                        if (reply == null) {
                            break;
                        }
                        reply.write(out);
                    }
                    socket.close();
                } catch (IOException e) {
                    if (mRunning) {
                        mError = String.valueOf(e);
                    }
                }
            }
        }, "okcar-loopback-rtsp");
        mThread.setDaemon(true);
        mThread.start();
    }

    public void stop() {
        mRunning = false;
        try {
            mServer.close();
        } catch (IOException ignored) {
            /* nothing useful to do */
        }
        final Thread thread = mThread;
        if (thread != null) {
            try {
                thread.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Answers like a receiver: /info, /auth-setup, SETUP (with the field ports), RECORD. */
    private static Handler defaultHandler() {
        return new Handler() {
            @Override
            public RtspMessage answer(RtspMessage request) throws IOException {
                final RtspMessage reply = RtspMessage.response(request, 200, "OK");
                final String method = request.method();
                final String uri = request.uri() == null ? "" : request.uri();
                if (method == null) {
                    return reply;
                }
                if (RtspMessage.METHOD_OPTIONS.equals(method)) {
                    reply.setHeader("Public", "ANNOUNCE, SETUP, RECORD, PAUSE, FLUSH, "
                            + "TEARDOWN, OPTIONS, GET_PARAMETER, SET_PARAMETER");
                    return reply;
                }
                if (uri.endsWith(AirPlay.PATH_INFO)) {
                    reply.setPlistBody(infoPlist());
                    return reply;
                }
                if (uri.endsWith(AirPlay.PATH_AUTH_SETUP)) {
                    final byte[] answer = new byte[33];
                    answer[0] = (byte) AirPlay.AUTH_SETUP_TAG;
                    new java.util.Random(42).nextBytes(answer);
                    reply.setBody(answer, "application/octet-stream");
                    return reply;
                }
                if (RtspMessage.METHOD_SETUP.equals(method)) {
                    reply.setPlistBody(fieldStreams());
                    return reply;
                }
                if (RtspMessage.METHOD_RECORD.equals(method)) {
                    reply.setHeader("Audio-Latency", "88200");
                    return reply;
                }
                return reply;
            }
        };
    }

    private static Map<String, Object> infoPlist() {
        final Map<String, Object> info = new LinkedHashMap<String, Object>();
        info.put(AirPlay.KEY_DEVICE_ID, "8e:0c:64:4f:bb:f7");
        info.put(AirPlay.KEY_MODEL, "the production head unit");
        info.put(AirPlay.KEY_MANUFACTURER, "Chery");
        info.put(AirPlay.KEY_SRCVERS, AirPlay.PROTOCOL_VERSION);
        info.put(AirPlay.KEY_FEATURES, AirPlay.FEATURES);
        return info;
    }

    /** Exactly the stream dictionaries the field session negotiated. */
    private static Map<String, Object> fieldStreams() {
        final List<StreamSetup.Stream> streams = new ArrayList<StreamSetup.Stream>();
        final StreamSetup.Stream video = new StreamSetup.Stream();
        video.type = AirPlay.STREAM_TYPE_VIDEO;
        video.dataPort = FIELD_VIDEO_PORT;
        video.controlPort = FIELD_VIDEO_PORT - 1;
        video.streamConnectionId = 1;
        streams.add(video);
        final StreamSetup.Stream audio = new StreamSetup.Stream();
        audio.type = AirPlay.STREAM_TYPE_AUDIO;
        audio.dataPort = FIELD_AUDIO_PORT;
        audio.controlPort = FIELD_AUDIO_PORT - 1;
        audio.audioFormat = AirPlay.MAIN_AUDIO_MEDIA_OUTPUT;
        streams.add(audio);
        return StreamSetup.streamsResponse(streams);
    }
}
