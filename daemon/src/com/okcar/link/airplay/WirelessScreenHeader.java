/*
 * OkcarOS link stack — wireless AirPlay screen stream: the 128-byte record header,
 * the codec-config (CSD) payload and the Annex-B → length-prefix conversion.
 *
 * Every layout below comes from the real wireless capture
 * (the internal notes, analysed in
 * the session-analysis notes §3.4 and the pairing-crypto notes §6) — not from
 * the wired head-unit path, whose record header is a *different* shape (nanosecond
 * timestamp + an int32 type field, see ScreenStreamWriter):
 *
 *   record := header(128 B) || payload
 *
 *   header[0:4]    u32 little-endian payload size
 *   header[4]      payload type: 0 = video frame, 1 = codec config, 2 = heartbeat
 *   header[5]      0x10 on the first frame that follows a config record
 *   header[6:8]    codec-correlated marker: real H.264 config records carry 0x14 0x01,
 *                  the wireless HEVC ones 0x1e 0x01 (see CONFIG_CODEC_BYTE_AVC)
 *   header[8:16]   u64 little-endian 32.32 fixed-point seconds
 *   header[16:24]  width, height as float32 little-endian } three copies of the
 *   header[40:48]  width, height as float32 little-endian } display size, as the
 *   header[56:64]  width, height as float32 little-endian } config record carried them
 *                 (all six are 1080.0 / 2400.0 in the capture; video frames leave
 *                 those bytes zero)
 *
 * A heartbeat is an all-zero header with byte 4 = 0x02 (capture: 46 per 46 s
 * session, ≈1 Hz, all-zero 64-bit timestamp).  Video frames are sealed
 * (ChaCha20-Poly1305, the whole 128-byte header is the AAD — the pairing-crypto notes
 * §6); config records are PLAINTEXT.
 *
 * Codec config payload: the capture's three type-1 records each carried one
 * clear-text ISO-BMFF sample entry — `hvc1` (78-byte VisualSampleEntry) with an
 * `hvcC` child and a `colr`/`nclc` child, box size 248.  This class builds the
 * same shape for HEVC and its H.264 analogue `avc1` + `avcC` (H.264 wireless CSD
 * was never captured — see configPayload()).
 *
 * Pure Java (no android.*), so the host JVM verifies it:
 *   java -cp <out> com.okcar.link.airplay.WirelessScreenHeader
 * runs main()'s self-test (header/timestamp packing, Annex-B conversion, and the
 * AVC/HEVC config records against real bytes from the capture and the wired dump).
 */

package com.okcar.link.airplay;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class WirelessScreenHeader {

    /** Fixed record header size (measured). */
    public static final int HEADER_SIZE = 128;

    /** Payload type of a (sealed) video frame. */
    public static final int TYPE_FRAME = 0;

    /** Payload type of a (plaintext) codec-config / CSD record. */
    public static final int TYPE_CONFIG = 1;

    /** Payload type of the empty ≈1 Hz heartbeat. */
    public static final int TYPE_HEARTBEAT = 2;

    /** Video codecs this stack can send. */
    public static final int CODEC_AVC = 0;
    public static final int CODEC_HEVC = 1;

    /** header[5] flag the real sender set on the first frame after a config record. */
    public static final int FLAG_PARAMETER_SETS_IN_BAND = 0x10;

    /**
     * header[6] of a config record is codec-correlated, and the accessory keys its parser off
     * it: with byte 6 = 0x1e in front of a bare H.264 {@code avcC}, Fruit Link answered
     * {@code [ScreenStream] Stream is unknown format} and dropped every record
     * ({@code process data failed: -6705}) even though the payload itself was well formed.
     *
     * <p>Measured values: every real H.264 config record seen carries 0x14 — the production head unit dump
     * {@code video-stream-head.bin} (1920x720, 34-byte {@code avcC}) and, after this change,
     * our own accepted stream; the wireless HEVC capture carries 0x1e.  It is <em>not</em> a
     * plain two-valued codec id: one of that capture's three config records carries 0x5e, so
     * treat 0x14/0x1e as the measured values for the two codecs this stack sends.
     */
    public static final int CONFIG_CODEC_BYTE_AVC = 0x14;
    public static final int CONFIG_CODEC_BYTE_HEVC = 0x1e;

    /** Fixed 16-byte ChaCha20-Poly1305 tag overhead of a sealed frame. */
    public static final int SEAL_OVERHEAD = 16;

    private WirelessScreenHeader() {
    }

    /* ---------------------------------------------------------------- codec */

    /**
     * The single codec decision, and it belongs to the accessory: the head unit is the
     * side that has to decode our stream, so its advertisement wins.
     *
     * <p>HEVC is sent only when the accessory advertises HEVC <em>and</em> this device
     * really has an HEVC surface encoder at the negotiated size.  Anything else — no
     * HEVC in {@code enabledFeatures}/{@code hevcInfo}, or an accessory that asks for
     * HEVC while our encoder cannot do it — falls back to H.264, which every CarPlay
     * accessory can decode.  There is deliberately no way for the caller to pin a
     * codec: a wrong pin would produce a stream the accessory cannot decode.
     *
     * @param accessoryAdvertisesHevc what the accessory asked for (its
     *                                {@code enabledFeatures} / {@code hevcInfo})
     * @param hevcEncoderAvailable    whether {@code video/hevc} can really be encoded here
     * @return {@link #CODEC_HEVC} or {@link #CODEC_AVC}
     */
    public static int chooseCodec(boolean accessoryAdvertisesHevc, boolean hevcEncoderAvailable) {
        /* Default H.264; HEVC only when the accessory advertises it (and this device can encode
         * it).  Measured 2026-10-02: forcing HEVC for Fruit Link (which advertises nothing) made
         * the head unit classify our stream as H.265/HEVC but render garbage, so the pin stays
         * off and the advertisement decides. */
        return accessoryAdvertisesHevc && hevcEncoderAvailable ? CODEC_HEVC : CODEC_AVC;
    }

    /** Canonical codec name for logs. */
    public static String codecName(int codec) {
        return codec == CODEC_HEVC ? "HEVC" : "H.264";
    }

    /** Frame rates this stack encodes. */
    public static final int FPS_30 = 30;
    public static final int FPS_60 = 60;

    /**
     * The frame rate, and like the codec it belongs to the accessory: a head unit that
     * advertises 60 fps in its {@code /info} display entry is asking for a 60 fps stream,
     * one that says nothing (or 30) gets 30.  Only the two rates the encoder is set up
     * for are produced — an advertisement of, say, 49 or 90 is snapped, never passed on
     * verbatim, because the encoder's rate has to stay a fixed value it was configured
     * with.  There is no manual pin here either.
     *
     * @param advertisedFps the accessory's advertised maximum, or 0/negative if silent
     * @return {@link #FPS_60} or {@link #FPS_30}
     */
    public static int chooseFps(int advertisedFps) {
        return advertisedFps >= 50 ? FPS_60 : FPS_30;
    }

    /**
     * Applies the phone's own optional ceiling to the negotiated rate.  {@code cap <= 0} means
     * "no phone-side cap" and the negotiated value comes back untouched — that is the default, so
     * a head unit advertising 60 fps really gets 60.  A positive cap clamps.
     *
     * <p>This is split out of {@code WirelessAirPlayClient.resolveVideoFps} so it can be asserted
     * without a device: the bug it fixes (2026-10-21) was a hard-coded default ceiling of 30 that
     * silently overrode the negotiation for everyone, and a self-test is the cheapest guard
     * against "no cap by default" regressing again.
     */
    public static int applyEncoderCap(int chosen, int cap) {
        return cap > 0 && cap < chosen ? cap : chosen;
    }

    /** Encoder bitrate: 60 fps carries twice the frames, so it gets twice the bits. */
    public static int bitrateForFps(int fps) {
        return fps >= FPS_60 ? 16_000_000 : 8_000_000;
    }

    /* ------------------------------------------------------------- timestamps */

    /**
     * 32.32 fixed-point seconds (integer part in the high 32 bits) from a
     * monotonic nanosecond clock.  The real sender's values decode to a device
     * *uptime* (784063 s ≈ 9 days in the capture), so the wireless sender passes
     * {@code SystemClock.elapsedRealtimeNanos()}.
     */
    public static long timestamp3232(long monotonicNanos) {
        final long nanos = monotonicNanos < 0 ? 0 : monotonicNanos;
        final long seconds = nanos / 1000000000L;
        final long fraction = ((nanos % 1000000000L) << 32) / 1000000000L;
        return (seconds << 32) | (fraction & 0xFFFFFFFFL);
    }

    /** Inverse of {@link #timestamp3232(long)} — for logs and host checks. */
    public static double timestampSeconds(long fixed3232) {
        return (fixed3232 >>> 32) + ((fixed3232 & 0xFFFFFFFFL) / 4294967296.0);
    }

    /* ---------------------------------------------------------------- headers */

    /**
     * Record body length that follows a frame header: the plaintext plus the AEAD tag.
     * The accessory reads exactly this many bytes off the video socket and authenticates
     * them, so the header's size field must never be the bare plaintext length.
     */
    public static int sealedLength(int plaintextLength) {
        return plaintextLength + SEAL_OVERHEAD;
    }

    /**
     * Header of one sealed video frame.
     *
     * @param payloadLength         bytes that follow the header on the wire (ciphertext + 16-byte tag)
     * @param timestamp3232         {@link #timestamp3232(long)} of this frame
     * @param parameterSetsInBand   true on the first frame after a config record
     */
    public static byte[] frameHeader(int payloadLength, long timestamp3232,
                                     boolean parameterSetsInBand) {
        final byte[] header = new byte[HEADER_SIZE];
        putU32le(header, 0, payloadLength);
        header[4] = (byte) TYPE_FRAME;
        if (parameterSetsInBand) {
            header[5] = (byte) FLAG_PARAMETER_SETS_IN_BAND;
        }
        putU64le(header, 8, timestamp3232);
        return header;
    }

    /**
     * Header of one plaintext codec-config record.  Byte 6/7 (the codec marker) and the
     * repeated width/height pairs reproduce the measured first config record byte for byte.
     *
     * @param codec {@link #CODEC_AVC} or {@link #CODEC_HEVC}; it selects byte 6 and must
     *              match the sample entry {@link #configPayload} put in the payload
     */
    public static byte[] configHeader(int codec, int payloadLength, long timestamp3232,
                                      int width, int height) {
        final byte[] header = new byte[HEADER_SIZE];
        putU32le(header, 0, payloadLength);
        header[4] = (byte) TYPE_CONFIG;
        header[6] = (byte) (codec == CODEC_HEVC ? CONFIG_CODEC_BYTE_HEVC : CONFIG_CODEC_BYTE_AVC);
        header[7] = 0x01;
        putU64le(header, 8, timestamp3232);
        putFloatLe(header, 16, width);
        putFloatLe(header, 20, height);
        putFloatLe(header, 40, width);
        putFloatLe(header, 44, height);
        putFloatLe(header, 56, width);
        putFloatLe(header, 60, height);
        return header;
    }

    /** Header of the empty heartbeat: all zero apart from byte 4 = 0x02. */
    public static byte[] heartbeatHeader() {
        final byte[] header = new byte[HEADER_SIZE];
        header[4] = (byte) TYPE_HEARTBEAT;
        return header;
    }

    /* ------------------------------------------------------------- NAL units */

    /**
     * Splits an encoded access unit (or a csd blob) into bare NAL units, without
     * their start codes or length prefixes.  Annex-B (3- or 4-byte start codes)
     * and 4-byte big-endian length-prefixed input are both accepted; a buffer that
     * is neither is returned as a single NAL, which is how MediaCodec delivers a
     * lone SPS/PPS in `csd-0`/`csd-1`.
     */
    public static List<byte[]> nalUnits(byte[] data) {
        final List<byte[]> out = new ArrayList<byte[]>();
        if (data == null || data.length == 0) {
            return out;
        }
        if (!hasStartCode(data)) {
            int i = 0;
            while (i + 4 <= data.length) {
                final int length = ((data[i] & 0xFF) << 24) | ((data[i + 1] & 0xFF) << 16)
                        | ((data[i + 2] & 0xFF) << 8) | (data[i + 3] & 0xFF);
                if (length <= 0 || i + 4 + length > data.length) {
                    break;
                }
                out.add(Arrays.copyOfRange(data, i + 4, i + 4 + length));
                i += 4 + length;
            }
            if (out.isEmpty()) {
                out.add(data);
            }
            return out;
        }
        int i = 0;
        while (i < data.length) {
            final int startCode = startCodeLength(data, i);
            if (startCode > 0) {
                i += startCode;
                final int nalStart = i;
                while (i < data.length && startCodeLength(data, i) == 0) {
                    i++;
                }
                if (i > nalStart) {
                    out.add(Arrays.copyOfRange(data, nalStart, i));
                }
            } else {
                i++;
            }
        }
        return out;
    }

    /**
     * Converts one encoded access unit to the 4-byte big-endian length-prefixed
     * form the wire carries (the capture's decrypted first frame begins
     * `00 00 04 0b` followed by the NAL — the session-analysis notes §3.4).
     * Input already in that form is returned unchanged.
     */
    public static byte[] toLengthPrefixed(byte[] accessUnit) {
        if (accessUnit == null || accessUnit.length == 0) {
            return accessUnit;
        }
        if (!hasStartCode(accessUnit)) {
            return accessUnit;
        }
        final ByteArrayOutputStream out = new ByteArrayOutputStream(accessUnit.length + 16);
        for (byte[] nal : nalUnits(accessUnit)) {
            out.write((nal.length >>> 24) & 0xFF);
            out.write((nal.length >>> 16) & 0xFF);
            out.write((nal.length >>> 8) & 0xFF);
            out.write(nal.length & 0xFF);
            out.write(nal, 0, nal.length);
        }
        return out.toByteArray();
    }

    /** H.264 NAL unit type (low 5 bits of the NAL header). */
    public static int avcNalType(byte[] nal) {
        return nal == null || nal.length == 0 ? -1 : (nal[0] & 0x1F);
    }

    /** HEVC NAL unit type (bits 1..6 of the 2-byte NAL header). */
    public static int hevcNalType(byte[] nal) {
        return nal == null || nal.length == 0 ? -1 : ((nal[0] >> 1) & 0x3F);
    }

    /** First NAL of the given type in {@code data}, or null. */
    public static byte[] findNal(byte[] data, int codec, int type) {
        for (byte[] nal : nalUnits(data)) {
            final int found = codec == CODEC_HEVC ? hevcNalType(nal) : avcNalType(nal);
            if (found == type) {
                return nal;
            }
        }
        return null;
    }

    /* ---------------------------------------------------------- codec config */

    /**
     * The codec-config record payload.
     *
     * @param codec    {@link #CODEC_AVC} or {@link #CODEC_HEVC}
     * @param vps      HEVC VPS (NAL 32), may be null
     * @param sps      AVC SPS (NAL 7) or HEVC SPS (NAL 33)
     * @param pps      AVC PPS (NAL 8) or HEVC PPS (NAL 34)
     * @param readyHvcC a ready-made HEVCDecoderConfigurationRecord from the encoder, or null
     * @param width    encoder width (written into the sample entry)
     * @param height   encoder height
     * @param raw      true to emit the bare avcC/hvcC record instead of the ISO-BMFF
     *                 sample entry; the capture proves the sample entry for HEVC
     * @return the payload, or null while the parameter sets are not known yet
     */
    public static byte[] configPayload(int codec, byte[] vps, byte[] sps, byte[] pps,
                                       byte[] readyHvcC, int width, int height, boolean raw) {
        if (codec == CODEC_HEVC) {
            final byte[] hvcc = hevcDecoderConfigurationRecord(vps, sps, pps, readyHvcC);
            if (hvcc == null) {
                return null;
            }
            return raw ? hvcc : sampleEntry("hvc1", "HEVC", box("hvcC", hvcc), width, height);
        }
        final byte[] avcc = avcDecoderConfigurationRecord(sps, pps);
        if (avcc == null) {
            return null;
        }
        return raw ? avcc : sampleEntry("avc1", "AVC", box("avcC", avcc), width, height);
    }

    /**
     * AVCDecoderConfigurationRecord (ISO/IEC 14496-15 §5.2.4.1.1), exactly the
     * bytes MediaCodec's decoder wants as `csd-0`.  The measured wired record for
     * SPS `67 64 00 1f …` / PPS `68 ee 06 f2 c0` is reproduced byte for byte.
     */
    public static byte[] avcDecoderConfigurationRecord(byte[] sps, byte[] pps) {
        final byte[] s = bareNal(sps);
        final byte[] p = bareNal(pps);
        if (s == null || s.length < 4 || p == null || p.length == 0) {
            return null;
        }
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(1);                          // configurationVersion
        out.write(s[1] & 0xFF);                // AVCProfileIndication
        out.write(s[2] & 0xFF);                // profile_compatibility
        out.write(s[3] & 0xFF);                // AVCLevelIndication
        out.write(0xFF);                       // 6 bits reserved | lengthSizeMinusOne = 3
        out.write(0xE1);                       // 3 bits reserved | numOfSPS = 1
        writeU16(out, s.length);
        out.write(s, 0, s.length);
        out.write(1);                          // numOfPPS
        writeU16(out, p.length);
        out.write(p, 0, p.length);
        return out.toByteArray();
    }

    /**
     * Drops Annex-B framing from a parameter set.  MediaCodec usually hands csd-0/csd-1
     * over as bare NALs, but an encoder that prepends 00 00 00 01 must not corrupt the
     * configuration record (the wired path's ScreenStreamWriter strips it for the same
     * reason).
     */
    public static byte[] bareNal(byte[] data) {
        if (data == null || data.length == 0) {
            return data;
        }
        final List<byte[]> nals = nalUnits(data);
        if (nals.isEmpty()) {
            return data;
        }
        if (nals.size() == 1) {
            return nals.get(0);
        }
        return data;
    }

    /**
     * HEVCDecoderConfigurationRecord (ISO/IEC 14496-15 §8.3.3.1).  The 12-byte
     * general profile/tier/level block is parsed out of the SPS's
     * profile_tier_level (with emulation-prevention bytes removed), and the three
     * NAL arrays are VPS(32)/SPS(33)/PPS(34) with `array_completeness = 1`.
     *
     * A ready-made record from the encoder is passed straight through.
     */
    public static byte[] hevcDecoderConfigurationRecord(byte[] vps, byte[] sps, byte[] pps,
                                                        byte[] readyHvcC) {
        if (looksLikeHvcCRecord(readyHvcC)) {
            return readyHvcC;
        }
        /* Some encoders hand csd-0 over as a complete `hvcC` box rather than the bare
         * record; use its payload when that is what it is. */
        final byte[] unwrapped = unwrapConfigBox(readyHvcC, "hvcC");
        if (unwrapped != null) {
            return unwrapped;
        }
        if (sps == null || sps.length < 14) {
            return null;
        }
        final byte[] ptl = hevcProfileTierLevel(sps);
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(1);                          // configurationVersion
        out.write(ptl[0]);                     // profile_space(2) tier(1) profile_idc(5)
        out.write(ptl, 1, 4);                  // general_profile_compatibility_flags
        out.write(ptl, 5, 6);                  // general_constraint_indicator_flags (48 bits)
        out.write(ptl[11]);                    // general_level_idc
        writeU16(out, 0xF000);                 // min_spatial_segmentation_idc (4 reserved bits set)
        out.write(0xFC);                       // parallelismType (6 reserved bits set)
        out.write(0xFD);                       // chromaFormat = 1 (4:2:0, 6 reserved bits set)
        out.write(0xF8);                       // bitDepthLumaMinus8 = 0 (5 reserved bits set)
        out.write(0xF8);                       // bitDepthChromaMinus8 = 0
        writeU16(out, 0);                      // avgFrameRate
        out.write(0x0B);                       // constFR=0, numTemporalLayers=1, nested=0, lengthSize=3
        out.write(3);                          // numOfArrays
        writeHevcArray(out, 32, vps);
        writeHevcArray(out, 33, sps);
        writeHevcArray(out, 34, pps);
        return out.toByteArray();
    }

    /**
     * The 12-byte general profile_tier_level block of an HEVC SPS: after the
     * 2-byte NAL header and the 8-bit
     * {@code sps_video_parameter_set_id/max_sub_layers_minus1/nesting} field, the
     * next 96 bits are profile_space(2) tier(1) profile_idc(5), 32 compatibility
     * flags, 48 constraint flags and general_level_idc(8).
     *
     * Falls back to a conservative Main-profile/level-4.0 block when the SPS is
     * too short to carry it (never observed, but the config record must still be
     * well formed).
     */
    public static byte[] hevcProfileTierLevel(byte[] spsNal) {
        final byte[] defaultPtl = {
                (byte) 0x01, (byte) 0x60, (byte) 0x00, (byte) 0x00,
                (byte) 0x00, (byte) 0xB0, (byte) 0x00, (byte) 0x00,
                (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x78};
        if (spsNal == null || spsNal.length < 2 + 1 + 12) {
            return defaultPtl;
        }
        final byte[] rbsp = removeEmulationPrevention(spsNal, 2);
        if (rbsp.length < 1 + 12) {
            return defaultPtl;
        }
        final byte[] ptl = new byte[12];
        System.arraycopy(rbsp, 1, ptl, 0, 12);
        return ptl;
    }

    /** True when {@code data} already is a HEVCDecoderConfigurationRecord. */
    public static boolean looksLikeHvcCRecord(byte[] data) {
        if (data == null || data.length < 23) {
            return false;
        }
        final int arrays = data[22] & 0xFF;
        return (data[0] & 0xFF) == 1 && (data[21] & 0x03) == 3 && arrays >= 1 && arrays <= 8;
    }

    /**
     * Payload of a complete ISO-BMFF box when {@code data} is one (u32 size + 4-char
     * type), else null.  Handles an encoder that hands csd-0 over as a boxed record.
     */
    public static byte[] unwrapConfigBox(byte[] data, String type) {
        if (data == null || data.length < 9) {
            return null;
        }
        final int size = ((data[0] & 0xFF) << 24) | ((data[1] & 0xFF) << 16)
                | ((data[2] & 0xFF) << 8) | (data[3] & 0xFF);
        if (size < 8 || size > data.length) {
            return null;
        }
        final byte[] fourCc = type.getBytes(java.nio.charset.Charset.forName("US-ASCII"));
        for (int i = 0; i < 4; i++) {
            if (data[4 + i] != fourCc[i]) {
                return null;
            }
        }
        return Arrays.copyOfRange(data, 8, size);
    }

    /**
     * ISO-BMFF VisualSampleEntry (ISO/IEC 14496-12 §12.1.3, 78 bytes) followed by
     * the codec-config child box and a `colr`/`nclc` child — the exact shape of
     * the capture's 248-byte `hvc1` record.
     */
    public static byte[] sampleEntry(String fourCc, String compressorName,
                                     byte[] configBox, int width, int height) {
        final byte[] colr = colourBox();
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final int size = 8 + 78 + configBox.length + colr.length;
        writeU32(out, size);
        writeAscii(out, fourCc);
        writeZeros(out, 6);                    // reserved
        writeU16(out, 1);                      // data_reference_index
        writeU16(out, 0);                      // pre_defined
        writeU16(out, 0);                      // reserved
        writeU32(out, 0);                      // pre_defined[0]
        writeU32(out, 0);                      // pre_defined[1]
        writeU32(out, 0);                      // pre_defined[2]
        writeU16(out, width & 0xFFFF);
        writeU16(out, height & 0xFFFF);
        writeU32(out, 0x00480000);             // horizresolution 72 dpi
        writeU32(out, 0x00480000);             // vertresolution 72 dpi
        writeU32(out, 0);                      // reserved
        writeU16(out, 1);                      // frame_count
        final byte[] name = new byte[32];
        final byte[] ascii = compressorName.getBytes(java.nio.charset.Charset.forName("US-ASCII"));
        name[0] = (byte) Math.min(31, ascii.length);
        System.arraycopy(ascii, 0, name, 1, Math.min(31, ascii.length));
        out.write(name, 0, name.length);
        writeU16(out, 24);                     // depth
        writeU16(out, 0xFFFF);                 // pre_defined = -1
        out.write(configBox, 0, configBox.length);
        out.write(colr, 0, colr.length);
        return out.toByteArray();
    }

    /** `colr` box with an `nclc` (BT.709) colour type, as measured. */
    public static byte[] colourBox() {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeU32(out, 18);
        writeAscii(out, "colr");
        writeAscii(out, "nclc");
        writeU16(out, 1);                      // primaries: BT.709
        writeU16(out, 1);                      // transfer: BT.709
        writeU16(out, 1);                      // matrix: BT.709
        return out.toByteArray();
    }

    /** One ISO-BMFF box: u32 size, 4-char type, payload. */
    public static byte[] box(String type, byte[] payload) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeU32(out, 8 + payload.length);
        writeAscii(out, type);
        out.write(payload, 0, payload.length);
        return out.toByteArray();
    }

    /* ---------------------------------------------------------------- helpers */

    private static void writeHevcArray(ByteArrayOutputStream out, int nalType, byte[] nal) {
        out.write(0x80 | (nalType & 0x3F));    // array_completeness = 1
        if (nal == null || nal.length == 0) {
            writeU16(out, 0);
            return;
        }
        writeU16(out, 1);
        writeU16(out, nal.length);
        out.write(nal, 0, nal.length);
    }

    /** True when a 3- or 4-byte Annex-B start code occurs anywhere in the buffer. */
    public static boolean hasStartCode(byte[] data) {
        for (int i = 0; i + 3 <= data.length; i++) {
            if (startCodeLength(data, i) > 0) {
                return true;
            }
        }
        return false;
    }

    /** Length of the Annex-B start code at {@code offset}, or 0 when there is none. */
    public static int startCodeLength(byte[] data, int offset) {
        if (offset + 3 <= data.length && data[offset] == 0 && data[offset + 1] == 0) {
            if (data[offset + 2] == 1) {
                return 3;
            }
            if (offset + 4 <= data.length && data[offset + 2] == 0 && data[offset + 3] == 1) {
                return 4;
            }
        }
        return 0;
    }

    /** Removes the 0x03 bytes NAL emulation prevention inserted after `00 00`. */
    public static byte[] removeEmulationPrevention(byte[] nal, int from) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        int zeros = 0;
        for (int i = Math.max(0, from); i < nal.length; i++) {
            final byte b = nal[i];
            if (zeros >= 2 && b == 0x03) {
                zeros = 0;
                continue;
            }
            out.write(b);
            zeros = b == 0 ? zeros + 1 : 0;
        }
        return out.toByteArray();
    }

    private static void putU32le(byte[] out, int offset, int value) {
        out[offset] = (byte) value;
        out[offset + 1] = (byte) (value >>> 8);
        out[offset + 2] = (byte) (value >>> 16);
        out[offset + 3] = (byte) (value >>> 24);
    }

    private static void putU64le(byte[] out, int offset, long value) {
        for (int i = 0; i < 8; i++) {
            out[offset + i] = (byte) (value >>> (8 * i));
        }
    }

    private static void putFloatLe(byte[] out, int offset, float value) {
        final int bits = Float.floatToIntBits(value);
        out[offset] = (byte) bits;
        out[offset + 1] = (byte) (bits >>> 8);
        out[offset + 2] = (byte) (bits >>> 16);
        out[offset + 3] = (byte) (bits >>> 24);
    }

    private static void writeU16(ByteArrayOutputStream out, int value) {
        out.write((value >>> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    private static void writeU32(ByteArrayOutputStream out, int value) {
        out.write((value >>> 24) & 0xFF);
        out.write((value >>> 16) & 0xFF);
        out.write((value >>> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    private static void writeAscii(ByteArrayOutputStream out, String text) {
        final byte[] bytes = text.getBytes(java.nio.charset.Charset.forName("US-ASCII"));
        out.write(bytes, 0, bytes.length);
    }

    private static void writeZeros(ByteArrayOutputStream out, int count) {
        for (int i = 0; i < count; i++) {
            out.write(0);
        }
    }

    /* -------------------------------------------------------------- self-test */

    private static int sPassed;
    private static int sFailed;

    private static void check(String what, boolean ok) {
        if (ok) {
            sPassed++;
        } else {
            sFailed++;
            System.out.println("FAIL " + what);
        }
    }

    private static void checkEquals(String what, byte[] expected, byte[] actual) {
        check(what + " (expected " + hex(expected) + ", got " + hex(actual) + ")",
                Arrays.equals(expected, actual));
    }

    private static String hex(byte[] data) {
        if (data == null) {
            return "(null)";
        }
        final StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /** Real SPS from the capture's hvcC (NAL 33). */
    private static final byte[] CAPTURE_SPS = {
            (byte) 0x42, (byte) 0x01, (byte) 0x01, (byte) 0x01, (byte) 0x60, (byte) 0x00,
            (byte) 0x00, (byte) 0x03, (byte) 0x00, (byte) 0xb0, (byte) 0x00, (byte) 0x00,
            (byte) 0x03, (byte) 0x00, (byte) 0x00, (byte) 0x03, (byte) 0x00, (byte) 0x96,
            (byte) 0xa0, (byte) 0x02, (byte) 0x20, (byte) 0x80, (byte) 0x09, (byte) 0x61,
            (byte) 0xcb, (byte) 0xe2, (byte) 0x03, (byte) 0x3b, (byte) 0x91, (byte) 0x48,
            (byte) 0x62, (byte) 0xe7, (byte) 0xf1, (byte) 0x3f, (byte) 0x0b, (byte) 0xfa,
            (byte) 0x1b, (byte) 0xf5, (byte) 0x0f, (byte) 0xfa, (byte) 0xa0, (byte) 0x8f,
            (byte) 0xd5, (byte) 0x41, (byte) 0x3f, (byte) 0xaa, (byte) 0xa0, (byte) 0xaf,
            (byte) 0xd5, (byte) 0x54, (byte) 0x17, (byte) 0xfa, (byte) 0xaa, (byte) 0xa0,
            (byte) 0xcf, (byte) 0xd5, (byte) 0x55, (byte) 0x4a, (byte) 0x6e, (byte) 0x02,
            (byte) 0x02, (byte) 0x02, (byte) 0x01};

    /** Real VPS from the capture's hvcC (NAL 32). */
    private static final byte[] CAPTURE_VPS = {
            (byte) 0x40, (byte) 0x01, (byte) 0x0c, (byte) 0x01, (byte) 0xff, (byte) 0xff,
            (byte) 0x01, (byte) 0x60, (byte) 0x00, (byte) 0x00, (byte) 0x03, (byte) 0x00,
            (byte) 0xb0, (byte) 0x00, (byte) 0x00, (byte) 0x03, (byte) 0x00, (byte) 0x00,
            (byte) 0x03, (byte) 0x00, (byte) 0x96, (byte) 0x0c, (byte) 0xc0, (byte) 0x90};

    /** Real PPS from the capture's hvcC (NAL 34). */
    private static final byte[] CAPTURE_PPS = {
            (byte) 0x44, (byte) 0x01, (byte) 0xc0, (byte) 0x72, (byte) 0xf0, (byte) 0x5b,
            (byte) 0x24};

    /** The capture's hvcC child box payload (132 bytes), byte for byte. */
    private static final byte[] CAPTURE_HVCC = {
            (byte) 0x01, (byte) 0x01, (byte) 0x60, (byte) 0x00, (byte) 0x00, (byte) 0x00,
            (byte) 0xb0, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
            (byte) 0x96, (byte) 0xf0, (byte) 0x00, (byte) 0xfc, (byte) 0xfd, (byte) 0xf8,
            (byte) 0xf8, (byte) 0x00, (byte) 0x00, (byte) 0x0b, (byte) 0x03, (byte) 0xa0,
            (byte) 0x00, (byte) 0x01, (byte) 0x00, (byte) 0x18, (byte) 0x40, (byte) 0x01,
            (byte) 0x0c, (byte) 0x01, (byte) 0xff, (byte) 0xff, (byte) 0x01, (byte) 0x60,
            (byte) 0x00, (byte) 0x00, (byte) 0x03, (byte) 0x00, (byte) 0xb0, (byte) 0x00,
            (byte) 0x00, (byte) 0x03, (byte) 0x00, (byte) 0x00, (byte) 0x03, (byte) 0x00,
            (byte) 0x96, (byte) 0x0c, (byte) 0xc0, (byte) 0x90, (byte) 0xa1, (byte) 0x00,
            (byte) 0x01, (byte) 0x00, (byte) 0x3f, (byte) 0x42, (byte) 0x01, (byte) 0x01,
            (byte) 0x01, (byte) 0x60, (byte) 0x00, (byte) 0x00, (byte) 0x03, (byte) 0x00,
            (byte) 0xb0, (byte) 0x00, (byte) 0x00, (byte) 0x03, (byte) 0x00, (byte) 0x00,
            (byte) 0x03, (byte) 0x00, (byte) 0x96, (byte) 0xa0, (byte) 0x02, (byte) 0x20,
            (byte) 0x80, (byte) 0x09, (byte) 0x61, (byte) 0xcb, (byte) 0xe2, (byte) 0x03,
            (byte) 0x3b, (byte) 0x91, (byte) 0x48, (byte) 0x62, (byte) 0xe7, (byte) 0xf1,
            (byte) 0x3f, (byte) 0x0b, (byte) 0xfa, (byte) 0x1b, (byte) 0xf5, (byte) 0x0f,
            (byte) 0xfa, (byte) 0xa0, (byte) 0x8f, (byte) 0xd5, (byte) 0x41, (byte) 0x3f,
            (byte) 0xaa, (byte) 0xa0, (byte) 0xaf, (byte) 0xd5, (byte) 0x54, (byte) 0x17,
            (byte) 0xfa, (byte) 0xaa, (byte) 0xa0, (byte) 0xcf, (byte) 0xd5, (byte) 0x55,
            (byte) 0x4a, (byte) 0x6e, (byte) 0x02, (byte) 0x02, (byte) 0x02, (byte) 0x01,
            (byte) 0xa2, (byte) 0x00, (byte) 0x01, (byte) 0x00, (byte) 0x07, (byte) 0x44,
            (byte) 0x01, (byte) 0xc0, (byte) 0x72, (byte) 0xf0, (byte) 0x5b, (byte) 0x24};

    /** Real AVC SPS/PPS and the avcC they produced, from the wired capture dump. */
    private static final byte[] WIRED_SPS = {
            (byte) 0x67, (byte) 0x64, (byte) 0x00, (byte) 0x1f, (byte) 0xac, (byte) 0xb4,
            (byte) 0x03, (byte) 0xc0, (byte) 0x2d, (byte) 0xd2, (byte) 0xb0, (byte) 0x50,
            (byte) 0x60, (byte) 0x60, (byte) 0x6d, (byte) 0x0a, (byte) 0x13, (byte) 0x50};

    private static final byte[] WIRED_PPS = {
            (byte) 0x68, (byte) 0xee, (byte) 0x06, (byte) 0xf2, (byte) 0xc0};

    private static final byte[] WIRED_AVCC = {
            (byte) 0x01, (byte) 0x64, (byte) 0x00, (byte) 0x1f, (byte) 0xff, (byte) 0xe1,
            (byte) 0x00, (byte) 0x12, (byte) 0x67, (byte) 0x64, (byte) 0x00, (byte) 0x1f,
            (byte) 0xac, (byte) 0xb4, (byte) 0x03, (byte) 0xc0, (byte) 0x2d, (byte) 0xd2,
            (byte) 0xb0, (byte) 0x50, (byte) 0x60, (byte) 0x60, (byte) 0x6d, (byte) 0x0a,
            (byte) 0x13, (byte) 0x50, (byte) 0x01, (byte) 0x00, (byte) 0x05, (byte) 0x68,
            (byte) 0xee, (byte) 0x06, (byte) 0xf2, (byte) 0xc0};

    public static void main(String[] args) {
        /* timestamp packing: 1.5 s -> 0x0000000180000000 */
        check("timestamp3232(0)", timestamp3232(0) == 0L);
        check("timestamp3232(1.5s)",
                timestamp3232(1500000000L) == ((1L << 32) | 0x80000000L));
        check("timestampSeconds round trip",
                Math.abs(timestampSeconds(timestamp3232(784063639583291L)) - 784063.639583291) < 1e-6);
        check("timestamp3232 negative clamps", timestamp3232(-1L) == 0L);
        final long captureTs = timestamp3232(784063L * 1000000000L + 639583291L);
        check("capture timestamp integer part", (captureTs >>> 32) == 784063L);
        check("capture timestamp fraction (within 1 LSB of 0xa3bbbb08)",
                Math.abs((captureTs & 0xFFFFFFFFL) - 0xa3bbbb08L) <= 4L);

        /* frame header: the capture's first frame header, minus its [5] flag */
        final byte[] frameHeader = frameHeader(1055, captureTs, true);
        check("frame header size", frameHeader.length == HEADER_SIZE);
        checkEquals("frame header size field", new byte[] {0x1f, 0x04, 0x00, 0x00}, slice(frameHeader, 0, 4));
        check("frame header type frame", frameHeader[4] == 0);
        check("frame header in-band flag", frameHeader[5] == 0x10);
        checkEquals("frame header timestamp", le64(captureTs), slice(frameHeader, 8, 8));
        check("frame header tail zero", isZero(frameHeader, 16, HEADER_SIZE));
        check("frame header without in-band flag", frameHeader(1055, captureTs, false)[5] == 0);

        /* config header: every non-zero byte the capture's first config record had.  That
         * record carried an hvc1 sample entry, so its byte 6 is the HEVC codec marker. */
        final byte[] configHeader = configHeader(CODEC_HEVC, 248, captureTs, 1080, 2400);
        final byte[] captureConfigHeader = new byte[HEADER_SIZE];
        captureConfigHeader[0] = (byte) 0xf8;                      // payload size 248, LE
        captureConfigHeader[4] = 0x01;                             // type: config
        captureConfigHeader[6] = (byte) CONFIG_CODEC_BYTE_HEVC;    // codec marker
        captureConfigHeader[7] = 0x01;
        System.arraycopy(le64(captureTs), 0, captureConfigHeader, 8, 8);
        final byte[] wh = {0x00, 0x00, (byte) 0x87, 0x44, 0x00, 0x00, 0x16, 0x45};
        System.arraycopy(wh, 0, captureConfigHeader, 16, 8);       // 1080.0 / 2400.0 LE
        System.arraycopy(wh, 0, captureConfigHeader, 40, 8);       // repeated
        System.arraycopy(wh, 0, captureConfigHeader, 56, 8);       // repeated
        checkEquals("config header matches the capture", captureConfigHeader, configHeader);

        /* H.264: same header, byte 6 = 0x14, the value video-stream-head.bin carries
         * (its config record starts 01 00 14 01) */
        check("codec marker bytes", CONFIG_CODEC_BYTE_AVC == 0x14 && CONFIG_CODEC_BYTE_HEVC == 0x1e);
        final byte[] avcConfigHeader = captureConfigHeader.clone();
        avcConfigHeader[6] = (byte) CONFIG_CODEC_BYTE_AVC;
        checkEquals("AVC config header differs only in the codec byte", avcConfigHeader,
                configHeader(CODEC_AVC, 248, captureTs, 1080, 2400));

        /* heartbeat: all zero but byte 4 */
        final byte[] heartbeat = heartbeatHeader();
        check("heartbeat size", heartbeat.length == HEADER_SIZE);
        check("heartbeat type", heartbeat[4] == 2);
        check("heartbeat otherwise zero", isZero(heartbeat, 0, 4) && isZero(heartbeat, 5, HEADER_SIZE));

        /* Annex-B -> 4-byte big-endian length prefixes */
        final byte[] annexB = hexToBytes(
                "000000016764001facb403c02dd2b05060606d0a13500000000168ee06f2c0");
        final byte[] avcc = toLengthPrefixed(annexB);
        checkEquals("annex-b -> length prefixed", hexToBytes(
                "000000126764001facb403c02dd2b05060606d0a1350" + "0000000568ee06f2c0"), avcc);
        checkEquals("3-byte start codes accepted", avcc,
                toLengthPrefixed(hexToBytes("0000016764001facb403c02dd2b05060606d0a1350"
                        + "00000168ee06f2c0")));
        checkEquals("already length prefixed is untouched", avcc, toLengthPrefixed(avcc));
        final List<byte[]> nals = nalUnits(annexB);
        check("nalUnits annex-b count", nals.size() == 2);
        check("nalUnits annex-b types", avcNalType(nals.get(0)) == 7 && avcNalType(nals.get(1)) == 8);
        check("nalUnits length-prefixed count", nalUnits(avcc).size() == 2);
        check("nalUnits single bare nal", nalUnits(WIRED_PPS).size() == 1);
        check("findNal finds the PPS", Arrays.equals(WIRED_PPS, findNal(annexB, CODEC_AVC, 8)));

        /* AVC config record against the wired dump's real avcC */
        checkEquals("avcC from real SPS/PPS", WIRED_AVCC,
                avcDecoderConfigurationRecord(WIRED_SPS, WIRED_PPS));
        check("avcC needs both parameter sets",
                avcDecoderConfigurationRecord(WIRED_SPS, null) == null);
        final byte[] avc1 = configPayload(CODEC_AVC, null, WIRED_SPS, WIRED_PPS, null, 1920, 720, false);
        check("avc1 box size field", readU32be(avc1, 0) == avc1.length);
        check("avc1 fourcc", new String(avc1, 4, 4).equals("avc1"));
        check("avc1 carries avcC", indexOf(avc1, "avcC") == 8 + 78 + 4);
        check("avc1 width/height", readU16be(avc1, 32) == 1920 && readU16be(avc1, 34) == 720);
        checkEquals("raw avcC mode", WIRED_AVCC,
                configPayload(CODEC_AVC, null, WIRED_SPS, WIRED_PPS, null, 1920, 720, true));

        /* HEVC: rebuild the capture's hvcC from its real VPS/SPS/PPS */
        check("a bare SPS is not mistaken for an hvcC record", !looksLikeHvcCRecord(CAPTURE_SPS));
        checkEquals("hvcC from real VPS/SPS/PPS", CAPTURE_HVCC,
                hevcDecoderConfigurationRecord(CAPTURE_VPS, CAPTURE_SPS, CAPTURE_PPS, null));
        check("hevc NAL types", hevcNalType(CAPTURE_VPS) == 32
                && hevcNalType(CAPTURE_SPS) == 33 && hevcNalType(CAPTURE_PPS) == 34);
        check("hevc profile_tier_level parsed from the SPS",
                Arrays.equals(slice(CAPTURE_HVCC, 1, 12), hevcProfileTierLevel(CAPTURE_SPS)));
        check("ready-made hvcC passes through", Arrays.equals(CAPTURE_HVCC,
                hevcDecoderConfigurationRecord(null, null, null, CAPTURE_HVCC)));
        checkEquals("a boxed hvcC record is unwrapped", CAPTURE_HVCC,
                hevcDecoderConfigurationRecord(null, null, null, box("hvcC", CAPTURE_HVCC)));
        check("unwrapConfigBox rejects a non-box", unwrapConfigBox(CAPTURE_HVCC, "hvcC") == null);
        check("hevc config without an SPS is deferred",
                configPayload(CODEC_HEVC, null, null, null, null, 1080, 2400, false) == null);
        final byte[] hvc1 = configPayload(CODEC_HEVC, CAPTURE_VPS, CAPTURE_SPS, CAPTURE_PPS,
                null, 1080, 2400, false);
        check("hvc1 box size field", readU32be(hvc1, 0) == hvc1.length);
        check("hvc1 fourcc", new String(hvc1, 4, 4).equals("hvc1"));
        check("hvc1 carries hvcC at offset 86", indexOf(hvc1, "hvcC") == 86 + 4);
        check("hvc1 carries colr", indexOf(hvc1, "colr") > 0);
        check("hvc1 width/height", readU16be(hvc1, 32) == 1080 && readU16be(hvc1, 34) == 2400);
        check("hvc1 compressor name", new String(hvc1, 51, 4).equals("HEVC"));

        /* Sealing: AAD = the whole 128-byte header, nonce = 4 zeros || u64 LE counter.
         * This is the exact construction WirelessVideoSender puts on the wire, checked
         * end to end (seal -> open, plus a tampered header must fail). */
        final byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) {
            key[i] = (byte) i;
        }
        final byte[] framePayload = toLengthPrefixed(annexB);
        final byte[] sealedHeader = frameHeader(sealedLength(framePayload.length), captureTs, true);
        final byte[] sealed = AirPlayPairing.chachaSeal(key,
                AirPlayPairing.nonceForCounter(0L), framePayload, sealedHeader);
        check("sealed frame overhead is 16B", sealed.length == framePayload.length + SEAL_OVERHEAD);
        check("header size field matches the sealed payload",
                readU32le(sealedHeader, 0) == sealed.length);
        /* Regression lock for the off-by-16 that made the accessory fail the MAC check on
         * every frame: the size field must be `plain + 16`, not `plain`.  The capture's
         * own numbers are used as the KAT (receiver logged sealed=1055 plain=1039). */
        check("sealedLength(1039) is the capture's 1055", sealedLength(1039) == 1055);
        check("plaintext length must not be written as the size field",
                readU32le(frameHeader(framePayload.length, captureTs, true), 0)
                        != sealed.length);
        byte[] opened = null;
        try {
            opened = AirPlayPairing.chachaOpen(key, AirPlayPairing.nonceForCounter(0L),
                    sealed, sealedHeader);
        } catch (javax.crypto.AEADBadTagException e) {
            check("sealed frame round trip failed: " + e, false);
        }
        checkEquals("sealed frame round trips", framePayload, opened);
        boolean headerAuthenticated = false;
        final byte[] tamperedHeader = sealedHeader.clone();
        tamperedHeader[9] ^= 0x01;
        try {
            AirPlayPairing.chachaOpen(key, AirPlayPairing.nonceForCounter(0L), sealed,
                    tamperedHeader);
        } catch (javax.crypto.AEADBadTagException e) {
            headerAuthenticated = true;
        }
        check("a tampered header fails authentication", headerAuthenticated);
        boolean counterMatters = false;
        try {
            AirPlayPairing.chachaOpen(key, AirPlayPairing.nonceForCounter(1L), sealed, sealedHeader);
        } catch (javax.crypto.AEADBadTagException e) {
            counterMatters = true;
        }
        check("the per-frame counter is part of the nonce", counterMatters);

        /* The codec is chosen automatically from the accessory's advertisement.  Both
         * faces have to be reachable without anyone pinning a codec, so all four
         * combinations are asserted here (this is the only codec decision in the app). */
        check("accessory asks HEVC + encoder available -> HEVC",
                chooseCodec(true, true) == CODEC_HEVC);
        check("accessory does not ask HEVC -> H.264",
                chooseCodec(false, false) == CODEC_AVC);
        check("accessory silent but our encoder does HEVC -> still H.264",
                chooseCodec(false, true) == CODEC_AVC);
        check("accessory asks HEVC but our encoder cannot -> H.264",
                chooseCodec(true, false) == CODEC_AVC);
        check("codec names for the log", codecName(CODEC_HEVC).equals("HEVC")
                && codecName(CODEC_AVC).equals("H.264"));
        /* The frame rate is chosen from the accessory's advertisement exactly like the
         * codec is, and only the two rates the encoder is configured for may come out. */
        check("accessory asks 60fps -> 60", chooseFps(60) == FPS_60);
        check("accessory asks 50 -> 60", chooseFps(50) == FPS_60);
        check("accessory asks 30 -> 30", chooseFps(30) == FPS_30);
        check("accessory asks 24 -> 30", chooseFps(24) == FPS_30);
        check("accessory asks 49 -> 30", chooseFps(49) == FPS_30);
        check("accessory asks 90 -> 60", chooseFps(90) == FPS_60);
        check("accessory silent (0) -> 30", chooseFps(0) == FPS_30);
        check("accessory silent (-1) -> 30", chooseFps(-1) == FPS_30);
        check("60fps gets the bigger bitrate", bitrateForFps(FPS_60) > bitrateForFps(FPS_30));
        /* The phone's own ceiling is opt-in.  Unset (0) must NOT clamp: that hard-coded default of
         * 30 was exactly the bug that made a head unit advertising 60 fps stream at 29.9 fps. */
        check("no phone-side cap -> negotiated value untouched",
                applyEncoderCap(FPS_60, 0) == FPS_60);
        check("negative cap is also 'no cap'", applyEncoderCap(FPS_60, -1) == FPS_60);
        check("a positive cap clamps", applyEncoderCap(FPS_60, 30) == 30);
        check("a cap above the negotiated value changes nothing",
                applyEncoderCap(FPS_30, 60) == FPS_30);
        /* Both faces must also have a real CSD builder, and the assertions above already
         * cover them: avc1+avcC for H.264 (from the wired dump's real parameter sets) and
         * hvc1+hvcC for HEVC (reproducing the wireless capture byte for byte). */

        System.out.println("WirelessScreenHeader self-test: " + sPassed + " passed, "
                + sFailed + " failed");
        if (sFailed > 0) {
            System.exit(1);
        }
    }

    private static byte[] slice(byte[] data, int offset, int length) {
        return Arrays.copyOfRange(data, offset, offset + length);
    }

    private static boolean isZero(byte[] data, int from, int to) {
        for (int i = from; i < to; i++) {
            if (data[i] != 0) {
                return false;
            }
        }
        return true;
    }

    private static byte[] le64(long value) {
        final byte[] out = new byte[8];
        putU64le(out, 0, value);
        return out;
    }

    private static int readU32be(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 24) | ((data[offset + 1] & 0xFF) << 16)
                | ((data[offset + 2] & 0xFF) << 8) | (data[offset + 3] & 0xFF);
    }

    private static int readU16be(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
    }

    private static int readU32le(byte[] data, int offset) {
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8)
                | ((data[offset + 2] & 0xFF) << 16) | ((data[offset + 3] & 0xFF) << 24);
    }

    private static int indexOf(byte[] haystack, String needle) {
        final byte[] pattern = needle.getBytes(java.nio.charset.Charset.forName("US-ASCII"));
        outer:
        for (int i = 0; i + pattern.length <= haystack.length; i++) {
            for (int j = 0; j < pattern.length; j++) {
                if (haystack[i + j] != pattern[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private static byte[] hexToBytes(String hex) {
        final byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
        }
        return out;
    }
}
