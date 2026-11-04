/*
 * OkcarOS link stack — L4 session layer self test.
 *
 * Like Iap2SelfTest, this needs no car: it runs AirPlaySession against a loopback
 * RTSP peer (LoopbackRtspServer) that answers exactly like the head unit did in the
 * field, and checks that the session walks info → auth → SETUP → RECORD and ends
 * up with the ports the field session negotiated (36113 / 36486).
 *
 * It also covers the pure-Java part (StreamSetup) directly.
 */

package com.okcar.link.airplay;

import android.util.Log;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class AirPlaySelfTest {

    private static final String TAG = "OkCarLink/AirPlayTest";

    private AirPlaySelfTest() {
    }

    public static List<String> run() {
        final List<String> out = new ArrayList<String>();
        try {
            streamSetupParsing(out);
        } catch (Throwable t) {
            failed(out, "stream setup parsing", t);
        }
        try {
            infoPlistShape(out);
        } catch (Throwable t) {
            failed(out, "info plist shape", t);
        }
        try {
            sessionOverLoopback(out);
        } catch (Throwable t) {
            failed(out, "session over loopback RTSP", t);
        }
        for (String line : out) {
            Log.i(TAG, line);
        }
        return out;
    }

    /* ------------------------------------------------------------- the checks */

    private static void streamSetupParsing(List<String> out) {
        // Build the field response, decode it through the real plist codec, then
        // parse it with StreamSetup — the same path the live session uses.
        final Map<String, Object> plist = LoopbackFieldStreams();
        final List<StreamSetup.Stream> streams = StreamSetup.parseStreams(plist);
        final StreamSetup.Stream video = StreamSetup.findByType(streams,
                AirPlay.STREAM_TYPE_VIDEO);
        final StreamSetup.Stream audio = StreamSetup.findByType(streams,
                AirPlay.STREAM_TYPE_AUDIO);
        final boolean ok = streams.size() == 2 && video != null && audio != null
                && video.dataPort == LoopbackRtspServer.FIELD_VIDEO_PORT
                && audio.dataPort == LoopbackRtspServer.FIELD_AUDIO_PORT
                && audio.audioFormat == AirPlay.MAIN_AUDIO_MEDIA_OUTPUT;
        check(out, "stream setup parsing (field ports)", ok, StreamSetup.describe(streams));
    }

    private static void infoPlistShape(List<String> out) {
        final Map<String, Object> info = new LinkedHashMap<String, Object>();
        info.put(AirPlay.KEY_DEVICE_ID, "8e:0c:64:4f:bb:f7");
        info.put(AirPlay.KEY_FEATURES, AirPlay.FEATURES);
        info.put(AirPlay.KEY_SRCVERS, AirPlay.PROTOCOL_VERSION);
        info.put(AirPlay.KEY_MODEL, "the production head unit");
        // Round trip through the wire codec so key names are exercised for real.
        try {
            final byte[] encoded = com.okcar.link.plist.BinaryPlist.encode(info);
            final Object decoded = com.okcar.link.plist.BinaryPlist.decode(encoded);
            final boolean ok = decoded instanceof Map
                    && ((Map<?, ?>) decoded).containsKey(AirPlay.KEY_DEVICE_ID)
                    && ((Map<?, ?>) decoded).containsKey(AirPlay.KEY_FEATURES)
                    && ((Map<?, ?>) decoded).containsKey(AirPlay.KEY_SRCVERS);
            check(out, "info plist round trip (" + AirPlay.KEY_DEVICE_ID + "/"
                            + AirPlay.KEY_FEATURES + '/' + AirPlay.KEY_SRCVERS + ')',
                    ok, encoded.length + "B -> " + decoded);
        } catch (IOException e) {
            failed(out, "info plist round trip", e);
        }
    }

    private static void sessionOverLoopback(List<String> out) throws Exception {
        final LoopbackRtspServer server = LoopbackRtspServer.start();
        AirPlaySession session = null;
        final StringBuilder trace = new StringBuilder();
        try {
            final SessionTransport transport = SessionTransport.TcpSessionTransport
                    .outgoing("127.0.0.1", server.port(), 2000);
            session = new AirPlaySession(transport, new AirPlaySession.Listener() {
                @Override
                public void onStateChanged(AirPlaySession.State state, String detail) {
                    if (trace.length() > 0) {
                        trace.append(" -> ");
                    }
                    trace.append(state);
                }
            });
            session.queryInfo();
            session.authSetup(new byte[]{0x11, 0x22, 0x33, 0x44});
            final List<StreamSetup.Stream> streams = session.setupStreams(true, true);
            final boolean recorded = session.record();

            final StreamSetup.Stream video = StreamSetup.findByType(streams,
                    AirPlay.STREAM_TYPE_VIDEO);
            final boolean ok = recorded
                    && session.state() == AirPlaySession.State.RECORDING
                    && video != null && video.dataPort == LoopbackRtspServer.FIELD_VIDEO_PORT
                    && session.info().containsKey(AirPlay.KEY_MODEL);
            check(out, "session walk over loopback RTSP (info→auth→SETUP→RECORD)", ok,
                    trace + " | " + StreamSetup.describe(streams)
                            + " | server requests=" + server.requestCount());
        } finally {
            if (session != null) {
                session.teardown();
            }
            server.stop();
        }
    }

    /* ---------------------------------------------------------------- helpers */

    /** The same stream set the loopback server answers with (field ports). */
    private static Map<String, Object> LoopbackFieldStreams() {
        final List<Map<String, Object>> list = new ArrayList<Map<String, Object>>();
        final Map<String, Object> video = new LinkedHashMap<String, Object>();
        video.put(AirPlay.KEY_TYPE, (long) AirPlay.STREAM_TYPE_VIDEO);
        video.put(AirPlay.KEY_DATA_PORT, (long) LoopbackRtspServer.FIELD_VIDEO_PORT);
        video.put(AirPlay.KEY_CONTROL_PORT, (long) LoopbackRtspServer.FIELD_VIDEO_PORT - 1);
        list.add(video);
        final Map<String, Object> audio = new LinkedHashMap<String, Object>();
        audio.put(AirPlay.KEY_TYPE, (long) AirPlay.STREAM_TYPE_AUDIO);
        audio.put(AirPlay.KEY_DATA_PORT, (long) LoopbackRtspServer.FIELD_AUDIO_PORT);
        audio.put(AirPlay.KEY_CONTROL_PORT, (long) LoopbackRtspServer.FIELD_AUDIO_PORT - 1);
        audio.put(AirPlay.KEY_AUDIO_FORMAT, AirPlay.MAIN_AUDIO_MEDIA_OUTPUT);
        list.add(audio);
        final Map<String, Object> root = new LinkedHashMap<String, Object>();
        root.put(AirPlay.KEY_STREAMS, new ArrayList<Object>(list));
        return root;
    }

    private static void check(List<String> out, String name, boolean ok, String detail) {
        out.add((ok ? "PASS  " : "FAIL  ") + name + "  —  " + detail);
    }

    private static void failed(List<String> out, String name, Throwable t) {
        out.add("FAIL  " + name + "  —  " + t);
        Log.w(TAG, name + " threw", t);
    }
}
