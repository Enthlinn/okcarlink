/*
 * OkcarOS link stack — display-2 RTSP: H.264 session description.
 *
 * Pure Java (no android.*), so the exact fmtp/rtpmap lines the head-unit program will parse are
 * pinned by a host test (the desktop rtsp2 harness) instead of by a live device.
 */
package com.okcar.link.stream2;

import java.util.Base64;

/** Builds the SDP an RTSP DESCRIBE answers with. */
public final class SdpBuilder {

    /** Payload type the RTP packets use; must match {@link RtpPacketizer#PAYLOAD_TYPE}. */
    public static final int PAYLOAD_TYPE = RtpPacketizer.PAYLOAD_TYPE;
    /** H.264 RTP clock, fixed by RFC 6184. */
    public static final int CLOCK_RATE = 90000;

    private SdpBuilder() {
    }

    /** Everything the SDP text depends on. */
    public static final class TrackSpec {
        public final String trackUrl;
        public final byte[] sps;
        public final byte[] pps;
        public final int width;
        public final int height;
        public final int fps;
        public final boolean tcpInterleaved;

        public TrackSpec(String trackUrl, byte[] sps, byte[] pps, int width, int height, int fps,
                         boolean tcpInterleaved) {
            this.trackUrl = trackUrl;
            this.sps = sps == null ? new byte[0] : sps.clone();
            this.pps = pps == null ? new byte[0] : pps.clone();
            this.width = width;
            this.height = height;
            this.fps = fps;
            this.tcpInterleaved = tcpInterleaved;
        }
    }

    /**
     * The SDP body.
     *
     * <p>{@code s=} and {@code m=} follow the transport the client asked for: an interleaved TCP
     * client gets {@code m=video 0 RTP/AVP/TCP}, a UDP client gets {@code m=video 0 RTP/AVP 96}
     * (the standard's own form: the media port is in the SETUP response, and 0 here means "see the
     * Transport header").
     *
     * @param spec track parameters; {@code sps}/{@code pps} may be empty when the encoder has not
     *             produced codec-specific data yet — the fmtp line then carries empty parameter
     *             sets, which is a visible failure a client reports instead of a silent no-picture.
     */
    public static String build(TrackSpec spec) {
        final String newline = "\r\n";
        final StringBuilder sdp = new StringBuilder();
        sdp.append("v=0").append(newline);
        sdp.append("o=- ").append(System.currentTimeMillis()).append(' ')
                .append(System.currentTimeMillis()).append(" IN IP4 0.0.0.0").append(newline);
        sdp.append("s=OkCarLink cast2 ").append(spec.width).append('x').append(spec.height)
                .append(newline);
        sdp.append("i=OkCarLink display 2 (H.264)").append(newline);
        sdp.append("t=0 0").append(newline);
        sdp.append("a=control:*").append(newline);
        sdp.append("m=video 0 ").append(spec.tcpInterleaved ? "RTP/AVP/TCP" : "RTP/AVP " + PAYLOAD_TYPE)
                .append(newline);
        sdp.append("a=rtpmap:").append(PAYLOAD_TYPE).append(" H264/").append(CLOCK_RATE)
                .append(newline);
        sdp.append("a=fmtp:").append(PAYLOAD_TYPE).append(" packetization-mode=1")
                .append(";profile-level-id=").append(profileLevelId(spec.sps))
                .append(";sprop-parameter-sets=").append(base64(spec.sps)).append(',')
                .append(base64(spec.pps)).append(newline);
        sdp.append("a=control:").append(spec.trackUrl).append(newline);
        if (spec.fps > 0) {
            /* Not part of RFC 6184; harmless to clients that ignore it, useful to a human reading
             * the DESCRIBE answer while debugging. */
            sdp.append("a=framerate:").append(spec.fps).append(".0").append(newline);
        }
        return sdp.toString();
    }

    static String base64(byte[] data) {
        return data == null || data.length == 0
                ? "" : Base64.getEncoder().encodeToString(data);
    }

    /**
     * The SPS's {@code profile_idc}/{@code constraint_flags}/{@code level_idc} bytes as hex, which
     * is what RFC 6184 puts in {@code profile-level-id}.  Empty SPS answers {@code 000000} rather
     * than throwing: DESCRIBE must still answer 200 so the client can report the real problem.
     */
    static String profileLevelId(byte[] sps) {
        if (sps == null || sps.length < 4) {
            return "000000";
        }
        final StringBuilder hex = new StringBuilder();
        for (int i = 1; i <= 3; i++) {
            final String part = Integer.toHexString(sps[i] & 0xff);
            if (part.length() < 2) {
                hex.append('0');
            }
            hex.append(part);
        }
        return hex.toString();
    }
}
