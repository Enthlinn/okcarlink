/*
 * OkcarOS link stack — L4 session layer: stream dictionaries.
 *
 * SETUP / SET_PARAMETER carry a `streams` array; each element describes one
 * media channel and, crucially, the ports the peer will use.  The field log from
 * the closed-source app shows exactly what we have to end up with (§12.17 C):
 *
 *   [HttpControlSession] Found Stream, type:110 dataPort:36113   <- video
 *   [HttpControlSession] Found Stream, type:100 dataPort:36486   <- audio
 *
 * The key names are cross-checked against a production automotive AirPlay
 * receiver (com.autochips.carplayapp, §12.25) and against the production head unit's
 * AirPlay contract (the head-unit capture notes §6.5).
 *
 * Pure Java on purpose: it runs on the host JVM, so the parsing/building logic is
 * tested without a car (see .car-test/plistcheck/).
 */

package com.okcar.link.airplay;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class StreamSetup {

    private StreamSetup() {
    }

    /** One entry of the `streams` array.  Missing fields stay at their defaults. */
    public static final class Stream {
        public int type;
        public int dataPort;
        public int controlPort;
        public int timingPort;
        public int eventPort;
        public long streamConnectionId;
        public int latencyMin;
        public int latencyMax;
        public long audioFormat;

        public boolean isVideo() {
            return type == AirPlay.STREAM_TYPE_VIDEO;
        }

        public boolean isAudio() {
            return type == AirPlay.STREAM_TYPE_AUDIO;
        }

        public String describe() {
            final StringBuilder sb = new StringBuilder();
            sb.append("type=").append(type)
                    .append(" dataPort=").append(dataPort);
            if (controlPort > 0) {
                sb.append(" controlPort=").append(controlPort);
            }
            if (timingPort > 0) {
                sb.append(" timingPort=").append(timingPort);
            }
            if (eventPort > 0) {
                sb.append(" eventPort=").append(eventPort);
            }
            if (streamConnectionId != 0) {
                sb.append(" streamConnectionID=").append(streamConnectionId);
            }
            if (latencyMin != 0 || latencyMax != 0) {
                sb.append(" latency=").append(latencyMin).append("..").append(latencyMax);
            }
            if (audioFormat != 0) {
                sb.append(" audioFormat=0x").append(Long.toHexString(audioFormat));
            }
            return sb.toString();
        }
    }

    /** Reads the `streams` array out of a decoded binary plist. */
    public static List<Stream> parseStreams(Object plist) {
        final List<Stream> streams = new ArrayList<Stream>();
        if (!(plist instanceof Map)) {
            return streams;
        }
        final Object value = ((Map<?, ?>) plist).get(AirPlay.KEY_STREAMS);
        if (!(value instanceof List)) {
            return streams;
        }
        for (Object item : (List<?>) value) {
            if (!(item instanceof Map)) {
                continue;
            }
            streams.add(parseStream((Map<?, ?>) item));
        }
        return streams;
    }

    private static Stream parseStream(Map<?, ?> map) {
        final Stream stream = new Stream();
        stream.type = (int) asLong(map.get(AirPlay.KEY_TYPE), 0);
        stream.dataPort = (int) asLong(map.get(AirPlay.KEY_DATA_PORT), 0);
        stream.controlPort = (int) asLong(map.get(AirPlay.KEY_CONTROL_PORT), 0);
        stream.timingPort = (int) asLong(map.get(AirPlay.KEY_TIMING_PORT), 0);
        stream.eventPort = (int) asLong(map.get(AirPlay.KEY_EVENT_PORT), 0);
        stream.streamConnectionId = asLong(map.get(AirPlay.KEY_STREAM_CONNECTION_ID), 0);
        stream.latencyMin = (int) asLong(map.get(AirPlay.KEY_LATENCY_MIN), 0);
        stream.latencyMax = (int) asLong(map.get(AirPlay.KEY_LATENCY_MAX), 0);
        stream.audioFormat = asLong(map.get(AirPlay.KEY_AUDIO_FORMAT), 0);
        return stream;
    }

    /** First stream of the given type, or null. */
    public static Stream findByType(List<Stream> streams, int type) {
        for (Stream stream : streams) {
            if (stream.type == type) {
                return stream;
            }
        }
        return null;
    }

    public static String describe(List<Stream> streams) {
        if (streams.isEmpty()) {
            return "(no streams)";
        }
        final StringBuilder sb = new StringBuilder();
        for (Stream stream : streams) {
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append(stream.describe());
        }
        return sb.toString();
    }

    /* ---------------------------------------------------------------- build */

    /** A video stream request: what we intend to send, before the peer answers. */
    public static Map<String, Object> videoStreamRequest(int type) {
        final Map<String, Object> stream = new LinkedHashMap<String, Object>();
        stream.put(AirPlay.KEY_TYPE, (long) type);
        stream.put(AirPlay.KEY_LATENCY_MIN, 0L);
        stream.put(AirPlay.KEY_LATENCY_MAX, 0L);
        return stream;
    }

    public static Map<String, Object> audioStreamRequest(int type, long audioFormat) {
        final Map<String, Object> stream = new LinkedHashMap<String, Object>();
        stream.put(AirPlay.KEY_TYPE, (long) type);
        stream.put(AirPlay.KEY_AUDIO_FORMAT, audioFormat);
        stream.put(AirPlay.KEY_LATENCY_MIN, 0L);
        stream.put(AirPlay.KEY_LATENCY_MAX, 0L);
        return stream;
    }

    /** Wraps stream dictionaries into the top-level `streams` dictionary. */
    public static Map<String, Object> streamsRequest(List<Map<String, Object>> streams) {
        final Map<String, Object> root = new LinkedHashMap<String, Object>();
        root.put(AirPlay.KEY_STREAMS, new ArrayList<Object>(streams));
        return root;
    }

    /**
     * Builds the plist we would answer a stream setup with, once the peer told us
     * which ports it listens on.
     */
    public static Map<String, Object> streamsResponse(List<Stream> streams) {
        final List<Object> list = new ArrayList<Object>();
        for (Stream stream : streams) {
            final Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put(AirPlay.KEY_TYPE, (long) stream.type);
            item.put(AirPlay.KEY_DATA_PORT, (long) stream.dataPort);
            if (stream.controlPort > 0) {
                item.put(AirPlay.KEY_CONTROL_PORT, (long) stream.controlPort);
            }
            if (stream.timingPort > 0) {
                item.put(AirPlay.KEY_TIMING_PORT, (long) stream.timingPort);
            }
            if (stream.eventPort > 0) {
                item.put(AirPlay.KEY_EVENT_PORT, (long) stream.eventPort);
            }
            if (stream.streamConnectionId != 0) {
                item.put(AirPlay.KEY_STREAM_CONNECTION_ID, stream.streamConnectionId);
            }
            if (stream.audioFormat != 0) {
                item.put(AirPlay.KEY_AUDIO_FORMAT, stream.audioFormat);
            }
            list.add(item);
        }
        final Map<String, Object> root = new LinkedHashMap<String, Object>();
        root.put(AirPlay.KEY_STREAMS, list);
        return root;
    }

    private static long asLong(Object value, long fallback) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value instanceof String) {
            try {
                return Long.parseLong((String) value);
            } catch (NumberFormatException e) {
                return fallback;
            }
        }
        return fallback;
    }
}
