/*
 * OkcarOS link stack — L4 media plane: the payload encoder the wireless audio sender drives.
 *
 * PURE JAVA on purpose (no android.*): the host harnesses compile it, and the negotiated Opus
 * shape lives here as the single source of truth for both the Android encoder and the sender.
 *
 * The wireless receiver's AltAudio path is an opus -> lpcm jitter buffer, so the wire payload
 * is one raw Opus packet per RTP packet.  The shape is not a guess; it is what the receiver
 * logs (and what the real iPhone sent) in the internal notes:
 *
 *   AltAudio audio set up for OPUS/48000/1 on receive port ...
 *   APSRTPJitterBuffer 'AltAudio' take packet Length 1920Bytes, ts 3840, seq 1
 *   AAudio(0) Len=[1920,0,0], pktTS=[3840,0,0]
 *
 * `Length 1920Bytes` is the jitter buffer's *lpcm output* for one packet: 960 mono samples of
 * 16 bit = 20 ms.  The receiver's RTP timestamps step 960 (`Recv packet seq 2, ts 960`), so one
 * packet is exactly 20 ms of 48 kHz mono audio.  The Opus payload itself is ~240-320 B at the
 * 96-128 kbps a mono music stream needs; 1920 B is what it decodes *to*.
 */
package com.okcar.link.media;

import java.util.List;

public interface AudioEncoder {

    /** Sample rate the receiver negotiated for AltAudio (`OPUS/48000/1` in its own log). */
    int SAMPLE_RATE = 48_000;

    /** Channel count the receiver negotiated for AltAudio. */
    int CHANNELS = 1;

    int BYTES_PER_SAMPLE = 2;

    /**
     * Opus frame duration.  The receiver's RTP timestamps advance 960 samples per packet
     * (`AltAudio Recv packet seq 2, ts 960` while it decodes the real iPhone), and
     * 960 / 48000 = 20 ms, so the encoder must emit one 20 ms packet per RTP packet.
     */
    int FRAME_MS = 20;

    /** 960 samples per packet at 48 kHz. */
    int FRAME_SAMPLES = SAMPLE_RATE * FRAME_MS / 1000;

    /** One 20 ms mono 16-bit PCM frame: the encoder's input quantum. */
    int FRAME_BYTES = FRAME_SAMPLES * BYTES_PER_SAMPLE;

    /** Opus bitrate; the receiver decodes opus itself, so this is our side of the SDP. */
    int DEFAULT_BITRATE_BPS = 128_000;

    /**
     * Encodes mono 16-bit little-endian PCM (as {@link PlaybackAudioSource} hands it over after
     * the downmix) and appends whole Opus packets to {@code packetsOut}.  Any number of input
     * bytes is accepted; a partial 20 ms frame is carried into the next call.
     *
     * @throws Exception when the platform encoder fails — the caller must stop the stream, not
     *                   silently fall back to another payload
     */
    void encode(byte[] monoLittleEndian, int length, List<byte[]> packetsOut) throws Exception;

    /** Human-readable codec/config for the `airplay: audio encoder=...` log line. */
    String describe();

    void close();
}
