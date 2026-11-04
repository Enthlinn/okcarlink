package com.okcar.link.media;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Adjustable bitrate and frame rate knobs (wireless and wired stored separately).
 *
 * <p>Why two sets: a wireless link (AirPlay over Wi-Fi / p2p / hotspot) can bear far less bitrate
 * than a wired one (USB), so {@code wireless_*} and {@code wired_*} are independent; once the wired
 * path comes up it reads its own set, with no interference.
 *
 * <p>The values are written in this conf (the WebUI sliders and manual input both write it):
 * <pre>
 *   wireless_bitrate=8000   # kbps; 0/false/auto/off means no override, use the value negotiated with the head unit
 *   wireless_fps=30         # fps, same as above
 *   wired_bitrate=20000
 *   wired_fps=60
 * </pre>
 *
 * <p>The frame rate cap takes effect **before** encoding: call {@code Surface.setFrameRate} on the
 * encoder input surface and set {@code max-fps-to-encoder}. Never drop encoded frames here — that
 * breaks the HEVC/H.264 reference chain and the receiver shows garbage until the next keyframe
 * (measured 2026-10-03).
 */
public final class VideoQos {

    /** Config file agreed upon by the WebUI and the daemon. */
    public static final String CONF_PATH = "/data/local/tmp/okcar-port-video.conf";

    /**
     * Where the daemon records what it <em>actually</em> applied to the encoder.
     *
     * <p>The WebUI writes the conf (the wish), but what reaches the encoder is decided later by
     * {@code MirrorVideoSource.setQos} after {@code configure()} has had its say. When those two
     * disagree the panel used to show only the wish, which reads as "the slider did nothing" — and
     * the causes are real and different: a session that started before the conf was saved, a value
     * clamped into range, or {@code persist.okcar.max_fps} capping an fps wish. Writing the applied
     * values next to the conf lets the panel show both and name the difference.
     */
    public static final String APPLIED_PATH = "/data/local/tmp/okcar-port-video.applied";

    public static final String TRANSPORT_WIRELESS = "wireless";
    public static final String TRANSPORT_WIRED = "wired";

    /* The limits are estimates, and the reasoning is written here so they can be tuned to
     * measurements later:
     *  - wireless bitrate 1–30 Mbps: H.264 for a 1080p~2400x1080 phone screen commonly uses 6–12 Mbps;
     *    on a p2p/hotspot link 30 Mbps is already a comfortable ceiling (higher only piles up jitter).
     *  - wireless frame rate 15–60: the head unit usually negotiates 30 or 60 (see
     *    WirelessScreenHeader.chooseFps).
     *  - wired bitrate 2–100 Mbps: USB 2.0 HS usable bandwidth is far above this, wired CarPlay
     *    1080p60 commonly uses 20–40 Mbps, and a ceiling of 100 leaves plenty of room (for the
     *    future wired path).
     *  - wired frame rate 15–60: the CarPlay side caps at 60 (a faster panel does not change this
     *    protocol limit). */
    public static final int WIRELESS_BITRATE_MIN_KBPS = 1_000;
    public static final int WIRELESS_BITRATE_MAX_KBPS = 30_000;
    public static final int WIRELESS_FPS_MIN = 15;
    public static final int WIRELESS_FPS_MAX = 60;
    public static final int WIRED_BITRATE_MIN_KBPS = 2_000;
    public static final int WIRED_BITRATE_MAX_KBPS = 100_000;
    public static final int WIRED_FPS_MIN = 15;
    public static final int WIRED_FPS_MAX = 60;

    /* Audio/video compensation buffer (ms): delay video by this much before sending, to align with
     * the accessory's own audio latency.
     * Field evidence (2026-10-05): the emulator's audio latency measured about 300ms, and "Fruit Link"
     * makes a similar buffer adjustable with a default of 384 — so this knob exists and 0..2000 are
     * accepted. The default is 0 (no delay): on 2026-10-07 the user asked for no delay by default
     * (commit ee604a4), and the extra buffer costs latency in every session. The knob is therefore
     * "0 = off", which is a legitimate value — not "no override"; the WebUI slider and the CGI must
     * agree on that (they used to fall back to 300, which made the panel disagree with the daemon). */
    public static final int VIDEO_DELAY_DEFAULT_MS = 0;
    public static final int VIDEO_DELAY_MIN_MS = 0;
    public static final int VIDEO_DELAY_MAX_MS = 2_000;
    public static final String VIDEO_DELAY_KEY = "video_delay_ms";

    /* Render scale for app mode (percent): 100 = create the virtual display at the resolution the
     * head unit declared (default, no change in visual result).
     * Lowering it also cuts SF composition, GPU scaling and encoding cost (§12.68 measured the
     * source side stalling ~0.6s when an app opens), at the price of the app UI becoming a
     * "small-screen version" upscaled by the head unit — so the default is 100 and the user decides. */
    public static final int APP_SCALE_DEFAULT = 100;
    public static final int APP_SCALE_MIN = 25;
    public static final String APP_SCALE_KEY = "app_render_scale";

    /** Settings for one transport direction; 0 means "no override, use the negotiated value". */
    public static final class Settings {
        public final String transport;
        public final int bitrateKbps;
        public final int fpsCap;
        /** Video buffering / audio-video compensation (ms); default {@link #VIDEO_DELAY_DEFAULT_MS}. */
        public final int videoDelayMs;

        Settings(String transport, int bitrateKbps, int fpsCap, int videoDelayMs) {
            this.transport = transport;
            this.bitrateKbps = bitrateKbps;
            this.fpsCap = fpsCap;
            this.videoDelayMs = videoDelayMs;
        }

        /** Whether there is anything to override. */
        public boolean overridesBitrate() {
            return bitrateKbps > 0;
        }

        public boolean overridesFps() {
            return fpsCap > 0;
        }

        public String describe() {
            return transport + ": bitrate=" + (overridesBitrate() ? bitrateKbps + "kbps" : "auto")
                    + " fps=" + (overridesFps() ? String.valueOf(fpsCap) : "auto")
                    + " videoDelay=" + videoDelayMs + "ms";
        }
    }

    private VideoQos() {
    }

    /** Reads the default location; if the file is missing or unreadable it yields "override nothing" and says so. */
    public static Settings load(String transport) {
        return load(new File(CONF_PATH), transport);
    }

    public static Settings load(File file, String transport) {
        final Map<String, String> values = new LinkedHashMap<>();
        if (file != null && file.isFile()) {
            try {
                final byte[] raw = readAll(file);
                for (String line : new String(raw, "UTF-8").split("\n")) {
                    final int hash = line.indexOf('#');
                    final String body = (hash >= 0 ? line.substring(0, hash) : line).trim();
                    final int eq = body.indexOf('=');
                    if (eq <= 0) {
                        continue;
                    }
                    values.put(body.substring(0, eq).trim().toLowerCase(),
                            body.substring(eq + 1).trim());
                }
            } catch (IOException e) {
                /* Unreadable means "no settings", but the caller must be told why — do not pretend defaults were read. */
                System.err.println("VideoQos: cannot read " + file + ": " + e);
            }
        }
        final boolean wired = TRANSPORT_WIRED.equals(transport);
        final int minKbps = wired ? WIRED_BITRATE_MIN_KBPS : WIRELESS_BITRATE_MIN_KBPS;
        final int maxKbps = wired ? WIRED_BITRATE_MAX_KBPS : WIRELESS_BITRATE_MAX_KBPS;
        final int minFps = wired ? WIRED_FPS_MIN : WIRELESS_FPS_MIN;
        final int maxFps = wired ? WIRED_FPS_MAX : WIRELESS_FPS_MAX;
        return new Settings(transport,
                clamp(parse(values.get(( wired ? "wired" : "wireless") + "_bitrate")), minKbps, maxKbps),
                clamp(parse(values.get((wired ? "wired" : "wireless") + "_fps")), minFps, maxFps),
                clampVideoDelay(values.get(VIDEO_DELAY_KEY)));
    }

    /** Parses one value; auto/off/false/empty/non-numeric all give 0 (= no override). */
    public static int parse(String text) {
        if (text == null) {
            return 0;
        }
        final String t = text.trim().toLowerCase();
        if (t.isEmpty() || t.equals("auto") || t.equals("off") || t.equals("false") || t.equals("none")) {
            return 0;
        }
        try {
            final int v = Integer.parseInt(t);
            return Math.max(0, v);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Render scale: missing/invalid/auto all give 100; clamped to 25..100 (never upscales, which would be pointless). */
    public static int clampAppScale(String text) {
        if (text == null) {
            return APP_SCALE_DEFAULT;
        }
        final String t = text.trim().toLowerCase();
        if (t.isEmpty() || t.equals("auto") || t.equals("default") || t.equals("100%")) {
            return APP_SCALE_DEFAULT;
        }
        try {
            final int v = Integer.parseInt(t.replace("%", ""));
            return Math.max(APP_SCALE_MIN, Math.min(APP_SCALE_DEFAULT, v));
        } catch (NumberFormatException e) {
            return APP_SCALE_DEFAULT;
        }
    }

    /** What scale to create the app-mode virtual display at now (reads the same video.conf). */
    public static int appRenderScalePercent() {
        return appRenderScalePercent(new File(CONF_PATH));
    }

    public static int appRenderScalePercent(File file) {
        if (file == null || !file.isFile()) {
            return APP_SCALE_DEFAULT;
        }
        try {
            final String text = new String(readAll(file), "UTF-8");
            for (String line : text.split("\n")) {
                final int hash = line.indexOf('#');
                final String body = (hash >= 0 ? line.substring(0, hash) : line).trim();
                final int eq = body.indexOf('=');
                if (eq > 0 && APP_SCALE_KEY.equals(body.substring(0, eq).trim().toLowerCase())) {
                    return clampAppScale(body.substring(eq + 1));
                }
            }
        } catch (Throwable ignored) {
            /* unreadable: use the default */
        }
        return APP_SCALE_DEFAULT;
    }

    /** 0 stays 0 (no override); only a non-zero value is clamped into range. */
    public static int clamp(int value, int min, int max) {
        if (value <= 0) {
            return 0;
        }
        return Math.max(min, Math.min(max, value));
    }

    /**
     * Video buffering: missing/non-numeric/negative all use {@link #VIDEO_DELAY_DEFAULT_MS} (0 = no
     * delay). This knob differs from bitrate/fps: 0 is a legal value meaning "no delay", not
     * "no override". The upper bound is clamped by {@link #VIDEO_DELAY_MAX_MS}.
     */
    public static int clampVideoDelay(String text) {
        if (text == null) {
            return VIDEO_DELAY_DEFAULT_MS;
        }
        final String t = text.trim().toLowerCase();
        if (t.isEmpty() || t.equals("auto") || t.equals("default")) {
            return VIDEO_DELAY_DEFAULT_MS;
        }
        try {
            final int v = Integer.parseInt(t);
            return Math.max(VIDEO_DELAY_MIN_MS, Math.min(VIDEO_DELAY_MAX_MS, v));
        } catch (NumberFormatException e) {
            return VIDEO_DELAY_DEFAULT_MS;
        }
    }

    /** Builds a conf text that can be written back (the WebUI uses the same key order when saving). */
    public static String toConf(int wirelessBitrateKbps, int wirelessFps,
                               int wiredBitrateKbps, int wiredFps) {
        return toConf(wirelessBitrateKbps, wirelessFps, wiredBitrateKbps, wiredFps,
                VIDEO_DELAY_DEFAULT_MS);
    }

    /** The conf text including the audio/video compensation buffer. */
    public static String toConf(int wirelessBitrateKbps, int wirelessFps,
                               int wiredBitrateKbps, int wiredFps, int videoDelayMs) {
        final StringBuilder sb = new StringBuilder();
        sb.append("# okcar-port video bitrate and frame rate (0 = no override, use the value negotiated with the head unit)\n");
        sb.append("# wireless and wired are separate: a wireless link bears much less than a wired one.\n");
        sb.append("wireless_bitrate=").append(clamp(wirelessBitrateKbps,
                WIRELESS_BITRATE_MIN_KBPS, WIRELESS_BITRATE_MAX_KBPS)).append('\n');
        sb.append("wireless_fps=").append(clamp(wirelessFps, WIRELESS_FPS_MIN, WIRELESS_FPS_MAX)).append('\n');
        sb.append("wired_bitrate=").append(clamp(wiredBitrateKbps,
                WIRED_BITRATE_MIN_KBPS, WIRED_BITRATE_MAX_KBPS)).append('\n');
        sb.append("wired_fps=").append(clamp(wiredFps, WIRED_FPS_MIN, WIRED_FPS_MAX)).append('\n');
        sb.append(VIDEO_DELAY_KEY).append('=').append(VideoQos.clampVideoDelay(String.valueOf(videoDelayMs)))
                .append('\n');
        sb.append(APP_SCALE_KEY).append('=').append(APP_SCALE_DEFAULT).append('\n');
        return sb.toString();
    }

    /**
     * The "what the encoder is really running" snapshot, written next to the conf so the panel can
     * show the wish and the fact side by side.
     *
     * @param transport      {@link #TRANSPORT_WIRELESS} or {@link #TRANSPORT_WIRED}
     * @param bitrateBps     the bitrate handed to MediaCodec (bits/s, not kbps)
     * @param fps            the frame rate handed to MediaCodec
     * @param videoDelayMs   the pacing buffer actually in use
     */
    public static String appliedConf(String transport, int bitrateBps, int fps, int videoDelayMs) {
        return "# written by the daemon: what the encoder was really configured with\n"
                + "transport=" + (TRANSPORT_WIRED.equals(transport) ? TRANSPORT_WIRED : TRANSPORT_WIRELESS) + '\n'
                + "bitrate_kbps=" + Math.max(0, bitrateBps / 1000) + '\n'
                + "fps=" + Math.max(0, fps) + '\n'
                + VIDEO_DELAY_KEY + '=' + Math.max(0, videoDelayMs) + '\n';
    }

    /** Best-effort write of {@link #appliedConf}; a failure here must never disturb casting. */
    public static void writeApplied(File file, String transport, int bitrateBps, int fps, int videoDelayMs) {
        if (file == null) {
            return;
        }
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(file)) {
            out.write(appliedConf(transport, bitrateBps, fps, videoDelayMs).getBytes("UTF-8"));
        } catch (Throwable t) {
            System.err.println("VideoQos: cannot write applied snapshot " + file + ": " + t);
        }
    }

    private static byte[] readAll(File file) throws IOException {
        try (FileInputStream in = new FileInputStream(file)) {
            final byte[] buf = new byte[(int) Math.max(1, Math.min(file.length(), 8192))];
            int off = 0;
            while (off < buf.length) {
                final int n = in.read(buf, off, buf.length - off);
                if (n < 0) {
                    break;
                }
                off += n;
            }
            if (off == buf.length) {
                return buf;
            }
            final byte[] out = new byte[off];
            System.arraycopy(buf, 0, out, 0, off);
            return out;
        }
    }
}
