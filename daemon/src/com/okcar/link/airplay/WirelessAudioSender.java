/*
 * Copyright (C) 2026 The OkCarOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.okcar.link.airplay;

import android.content.Context;

import com.okcar.link.LinkState;
import com.okcar.link.media.AudioEncoder;
import com.okcar.link.media.MediaPriority;
import com.okcar.link.media.MediaThreads;
import com.okcar.link.media.PlaybackAudioSource;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Wireless (AirPlay-2) audio: captures the phone's playback through REMOTE_SUBMIX, encodes it as
 * <b>Opus</b> (48000 Hz mono, 20 ms frames) and sends it to the accessory's audio `dataPort` as
 * sealed RTP.
 *
 * <p>The wire format is not guessed — it is the layout the receiver itself implements
 * (`xcertplay.AudioStream.runData`, decompiled from the emulator accessory on 217):
 *
 * <pre>
 *   [ 0:12]  RTP header: 0x80, payloadType, seq u16 BE, timestamp u32 BE, ssrc u32 BE
 *   [12:N-8] ChaCha20-Poly1305 ciphertext + 16-byte tag
 *   [N-8:N]  8-byte little-endian counter  -> the low half of the AEAD nonce
 * </pre>
 *
 * with
 * <ul>
 *   <li>AEAD nonce = 4 zero bytes ‖ the trailing 8-byte counter (what the receiver rebuilds),</li>
 *   <li>AAD = the RTP header bytes 4..11 (timestamp + ssrc) — the receiver uses exactly those,</li>
 *   <li>key = {@code pairing.dataStreamOutputKey(audioStreamConnectionID)} — the same
 *       {@code DataStream-Output-Encryption-Key} derivation the video stream uses; the receiver's
 *       {@code onAudio} calls {@code outputKey(session, stream)} for it,</li>
 *   <li>payload = <b>one raw Opus packet</b> (no length prefix, no L16 byte swap).</li>
 * </ul>
 *
 * <p><b>Why Opus and why 20 ms.</b>  The receiver does not "play PCM on stream 101"; it builds an
 * opus-&gt;lpcm pipeline and only feeds it when the stream is Opus.  Its own log of the audible
 * real-iPhone session (`the internal notes`) is
 * unambiguous:
 *
 * <pre>
 *   [AirPlay] Setting up session type:AltAudio, audioType:default
 *   AltAudio audio set up for OPUS/48000/1 on receive port [0 -&gt; 49156]
 *   [APSRTPJitterBuffer] Creating jitter buffer 'AltAudio' for input format: 6f707573 (opus),
 *                        output 6c70636d (lpcm), target size: 80 ms / 3840 samples
 *   AltAudio Recv packet seq 2, ts 960
 *   [APSRTPJitterBuffer] 'AltAudio' take packet Length 1920Bytes, ts 3840, seq 1
 *   AAudio(0) Len=[1920,0,0], pktTS=[3840,0,0]
 * </pre>
 *
 * The RTP timestamp advances <b>960 samples</b> per packet while the jitter buffer hands
 * <b>1920 bytes</b> of lpcm to AAudio — 960 mono 16-bit samples. 960/48000 = <b>20 ms</b>, so one
 * RTP packet carries exactly one 20 ms Opus frame; the 1920 B is the decoded size, not the wire
 * size (a 20 ms mono Opus packet is ~240-320 B at 96-128 kbps).  The iPhone's SETUP used
 * {@code audioFormat 0x40000000} = the receiver's {@code OPUS_48K}.  The receiver's own format
 * resolution (`xcertplay.AudioStreamCodec.fromFormatBits`) tests {@code (bits & 0x70000000) != 0}
 * first and maps any hit to {@code OPUS} / 48000 / 1 channel, and the emulator engine names that
 * constant {@code OPUS_48K = 1073741824}.
 *
 * <p>Everything except the payload and the SETUP's audio format is unchanged: the sealed UDP
 * transport, the 8-byte counter trailer, the {@code streamConnectionID}-derived key and the
 * pacing clock.  Pure-Java helpers ({@link #packetizeOpus}, {@link #setupStream},
 * {@link #Pacer}, {@link #paceDelayMs}, {@link #downmixToMono}) are checked on the host by
 * `.car-test/airplay-audio-wireless`.
 *
 * <p><b>Pacing is part of the wire format here.</b>  REMOTE_SUBMIX hands us a ~240 ms block at a
 * time, and the original pacing compared "frames sent" against "milliseconds since the capture
 * started" — but the captured audio is inherently one capture block behind that clock, so the
 * subtraction was always negative, every packet took the zero-delay path, and each 240 ms block
 * was dumped in one millisecond.  The accessory's own log of that session is unambiguous: a
 * burst of 8-15 packets every ~240 ms, each preceded by {@code Select took too long - 238 ms},
 * then {@code Triggering discard excess with enqueued sample count: 71039} and
 * {@code Overrun occurred} against its 80 ms / 3840-sample AltAudio jitter buffer.  {@link Pacer}
 * anchors the wall clock on the first packet actually sent, spreads each block over its own
 * duration, bounds the queue by frames, drops the startup/stall backlog, and re-anchors instead
 * of catching up.  A healthy stream is {@link #PACING_PACKETS_PER_SECOND} packets/s and logs
 * {@code airplay: audio pacing 50.x packets/s, target 50/s}.
 */
public final class WirelessAudioSender {

    private static final String TAG = "OkCarLink/WirelessAudio";

    /**
     * The Opus format the receiver resolves to OPUS/48000/1.  It is the exact value the captured
     * iPhone put in its AltAudio SETUP (`audioFormat: 1073741824`), and the emulator engine names it
     * `OPUS_48K`.
     */
    public static final int AUDIO_FORMAT_OPUS_48K_MONO = 0x40000000;

    /**
     * The receiver's Opus match mask: `xcertplay.AudioStreamCodec.OPUS_MONO = 1879048192`
     * (0x70000000), tested as {@code (bits & OPUS_MONO) != 0} before its PCM table.
     */
    public static final int AUDIO_FORMAT_OPUS_MASK = 0x70000000;

    /**
     * The audio stream <b>type</b> we ask for, and it is not 100.
     *
     * <p>This accessory classifies the stream from the SETUP `type` field and only the realtime
     * <i>AltAudio</i> stream is wired to the car's output.  The head unit logs the SETUP plist it
     * receives and the sessions are unambiguous:
     *
     * <ul>
     *   <li>a real <b>iPhone8</b> (peer 192.0.2.4, 00:04:26) sent
     *       {@code {"type":101,"audioType":"default","audioFormat":1073741824,
     *       "supportsHighAccuracyTimestamps":true,"supportsRTPPacketRedundancy":true,
     *       "audioLatencyMs":80}}; the accessory answered
     *       {@code Setting up session type:AltAudio, audioType:default},
     *       created {@code onAudioStreamCreate():streamType:101,samplingRate 48000,audioType:default},
     *       and PCM flowed;</li>
     *   <li>our own SETUP ({@code "type":100} and no {@code audioType}) was answered
     *       {@code Setting up session type:MainAudio, audioType:&lt;&lt;NULL&gt;&gt;}; the accessory
     *       built and started an AudioTrack but never released one PCM block, so the car stayed
     *       silent.</li>
     * </ul>
     */
    public static final int AUDIO_STREAM_TYPE = 101;

    /**
     * The `audioType` the accessory needs to route the stream to the speakers.  Without it the
     * stream is set up "for Unknown" and its AudioTrack is never fed.
     */
    public static final String AUDIO_TYPE_DEFAULT = "default";

    /**
     * The latency the real iPhone asked for.  The receiver reads exactly this key
     * (`CarPlayMediaEngine.onAudio`: `stream.get("audioLatencyMs")`) and the audio pipeline is
     * built around it (its AltAudio jitter buffer targets 80 ms).
     */
    public static final int AUDIO_LATENCY_MS = 80;

    /**
     * RTP payload type the real iPhone puts on the AltAudio stream.
     *
     * <p>Every audio datagram of the audible iPhone8 session in
     * `the internal notes` has RTP byte 1 = 0x69 (marker 0,
     * PT 105); the whole 344-packet flow is PT 105 and nothing else.  We used 100 before, which
     * was the only remaining difference in the unencrypted part of the packet.
     *
     * <p>The receiver's own packet parser is aware of the PT: `APSRTPPacketHandlerReadNewMessage`
     * tests {@code (rtp[1] & 0x7a) == 0x68}, which is true for 0x69 and false for 0x64.  That
     * branch only changes the layout for the "extended" redundancy form (it is further gated on a
     * packet-handler field that the handler's create path leaves at zero), so our plain sealed
     * layout keeps decrypting either way — but the header now matches the iPhone byte for byte.
     */
    public static final int PAYLOAD_TYPE = 105;

    public static final int RTP_HEADER_LEN = 12;
    /** 8-byte nonce/counter trailer the receiver reads off the end of every packet. */
    public static final int NONCE_TRAILER_LEN = 8;
    /** ChaCha20-Poly1305 tag length. */
    public static final int TAG_LEN = 16;

    /** Opus shape (see {@link AudioEncoder}); the receiver negotiated OPUS/48000/1. */
    public static final int OPUS_SAMPLE_RATE = AudioEncoder.SAMPLE_RATE;
    public static final int OPUS_CHANNELS = AudioEncoder.CHANNELS;
    public static final int OPUS_BYTES_PER_SAMPLE = AudioEncoder.BYTES_PER_SAMPLE;
    public static final int OPUS_FRAME_MS = AudioEncoder.FRAME_MS;
    public static final int OPUS_FRAMES_PER_PACKET = AudioEncoder.FRAME_SAMPLES;   /* 960 */
    public static final int OPUS_FRAME_BYTES = AudioEncoder.FRAME_BYTES;           /* 1920 */
    public static final int OPUS_BITRATE_BPS = AudioEncoder.DEFAULT_BITRATE_BPS;

    /**
     * LPCM entries of the accessory's exact-match PCM table, kept because the receiver's format
     * resolution still uses that table for the PCM cases and the harness locks its semantics:
     * {@code 4→8000/1, 8→8000/2, 16→16000/1, 32→16000/2, 64→24000/1, 128→24000/2, 256→32000/1,
     * 512→32000/2, 8192→44100/1, 16384→44100/2, 262144→48000/1, 32768→48000/2}.
     */
    public static final int AUDIO_FORMAT_LPCM_48K_STEREO = 0x8000;
    /** LPCM 48000 Hz mono — the one-channel entry of the same exact-match table. */
    public static final int AUDIO_FORMAT_LPCM_48K_MONO = 0x40000;

    /** Android capture is always 48000 Hz stereo 16-bit (see PlaybackAudioSource). */
    private static final int BYTES_PER_SAMPLE = 2;
    private static final int CAPTURE_BYTES_PER_FRAME = AudioEncoder.CHANNELS * BYTES_PER_SAMPLE * 2;

    /**
     * The accessory's exact-match PCM table, in the order its own lookup uses.  Kept as parallel
     * arrays so {@link #channelsForFormat(int)} and {@link #sampleRateForFormat(int)} can resolve
     * the channel count the receiver actually negotiated instead of us hardcoding stereo.  An
     * audioFormat the table does not contain exactly has no known PCM shape.
     */
    private static final int[] FORMAT_BITS = {
        0x00000004, 0x00000008, 0x00000010, 0x00000020, 0x00000040, 0x00000080,
        0x00000100, 0x00000200, 0x00002000, 0x00004000, 0x00040000, 0x00008000,
    };
    private static final int[] FORMAT_RATE = {
        8000, 8000, 16000, 16000, 24000, 24000, 32000, 32000, 44100, 44100, 48000, 48000,
    };
    private static final int[] FORMAT_CHANNELS = {
        1, 2, 1, 2, 1, 2, 1, 2, 1, 2, 1, 2,
    };

    /** How many captured blocks may wait for the socket before the oldest is dropped. */
    private static final int BLOCK_QUEUE_CAPACITY = 8;

    /**
     * Hard bound on how much captured audio may wait for the socket.  One REMOTE_SUBMIX block is
     * ~240 ms (a 46080-byte AudioRecord buffer), so the block count alone is a poor latency bound;
     * the queue drops its oldest blocks until it is back inside this window.  Live audio only:
     * the operator needs to hear now, not half a second ago.
     */
    private static final long MAX_QUEUED_FRAMES = OPUS_SAMPLE_RATE / 2L;      /* 500 ms */

    /**
     * The send rate a healthy Opus 20 ms stream has: one 960-sample packet every 20 ms at 48 kHz.
     * Anything much above this is a burst, and a burst is what overruns the accessory's 80 ms
     * AltAudio jitter buffer (see {@link Pacer} and the sender's class comment).
     */
    public static final int PACING_PACKETS_PER_SECOND = OPUS_SAMPLE_RATE / OPUS_FRAMES_PER_PACKET;

    /**
     * How far behind the wall clock the packet schedule may slip before {@link Pacer} re-anchors.
     * Deliberately smaller than the accessory's 80 ms jitter-buffer target so even the tolerated
     * slip cannot fill it: at 40 ms a late sender emits at most two back-to-back packets.
     */
    public static final long PACING_MAX_SLIP_MS = 40L;

    /**
     * How far behind the wall clock the sender must fall before the queued audio is treated as
     * stale and dropped.  Re-anchoring is cheap and happens on any slip over
     * {@link #PACING_MAX_SLIP_MS}, but dropping the queue costs up to a whole 240 ms block, so it
     * is reserved for a real stall (the pump was descheduled for ~100 ms or more) rather than for
     * ordinary capture jitter.
     */
    public static final long PACING_BACKLOG_DROP_SLIP_MS = 100L;

    /** How often the sender reports its effective rate (the "audio pacing … packets/s" line). */
    private static final long STATS_INTERVAL_MS = 2000L;

    /**
     * How many of the first outgoing datagrams are logged in full, plus one every 100 after
     * that.  A bounded, like-for-like view of what we put on the wire (size, PT, SSRC, seq, ts and
     * the Opus TOC bytes) is the only way to compare our framing with the captured iPhone's
     * without another capture.
     */
    private static final int DATAGRAM_LOG_COUNT = 6;

    /**
     * The SSRC the real iPhone put on the AltAudio stream: <b>zero</b>.  Every datagram of the
     * audible iPhone8 session carries SSRC 0.  The receiver takes the AEAD AAD from the packet's
     * own header ({@code pkt[4:12]}), so any value authenticates; zero is simply what the iPhone
     * sends and what the packet handler was written against.
     */
    public static final int SSRC = 0;

    private final Context mContext;
    private final InetAddress mAddress;
    private final int mDataPort;
    private final long mStreamConnectionId;
    private final AirPlayPairing mPairing;
    private final AudioEncoder mEncoder;
    private final LinkState mState = LinkState.get();
    private final AtomicBoolean mStopped = new AtomicBoolean();

    private volatile DatagramSocket mSocket;
    private volatile byte[] mKey;
    private volatile PlaybackAudioSource mSource;

    /**
     * Media volume pulled into the safe band when capture started (-1 = untouched).  Restored on stop, so the
     * user's volume is never permanently changed.  See {@link com.okcar.link.media.AudioLevels}: REMOTE_SUBMIX
     * captures the mix with media volume applied, and 120/150 pushes the capture straight to full scale.
     */
    private volatile int mSavedMediaVolume = -1;
    private volatile boolean mRunning;
    private volatile String mError = "";

    private int mSequence;
    private long mTimestamp;
    private long mCounter;
    private long mPackets;
    private long mBytes;
    private long mFramesSent;
    private long mOpusPackets;
    private int mWriteFailures;
    private long mLastStatsMs;
    private long mLastStatsPackets;
    private long mStartedAtMs;
    private long mDroppedBlocks;
    private long mResyncs;
    private volatile long mLastPaceDelayMs;
    private volatile long mPaceSlipMs;

    /**
     * The wall-clock packet clock.  One instance per stream; only the pump thread touches it.
     * See {@link Pacer} for why a cumulative-sleep rate is not enough.
     */
    private final Pacer mPacer = new Pacer(OPUS_SAMPLE_RATE, OPUS_FRAMES_PER_PACKET,
            PACING_MAX_SLIP_MS);

    /** Frames of captured audio currently waiting in {@link #mQueue} (for the latency bound). */
    private final java.util.concurrent.atomic.AtomicLong mQueuedFrames =
            new java.util.concurrent.atomic.AtomicLong();

    /**
     * Capture blocks waiting to go out.  The capture callback must never block: a slow socket or
     * a pause in the accessory would otherwise stall REMOTE_SUBMIX reads and lose audio upstream.
     * The queue is bounded by block count *and* by {@link #MAX_QUEUED_FRAMES}, and the *oldest*
     * block is dropped on overflow, which is what bounds latency — dropping the newest (what the
     * accessory does internally) would only add delay.
     */
    private final java.util.concurrent.ArrayBlockingQueue<byte[]> mQueue =
            new java.util.concurrent.ArrayBlockingQueue<byte[]>(BLOCK_QUEUE_CAPACITY);

    /** Reused sink for one encode call; only touched by the pump thread. */
    private final List<byte[]> mEncoded = new ArrayList<byte[]>();

    /**
     * @param encoder the Opus encoder to drive; the caller owns its creation so an unavailable
     *                encoder fails before any audio thread starts.  Never null.
     */
    public WirelessAudioSender(Context context, InetAddress address, int dataPort,
                               long streamConnectionId, AirPlayPairing pairing,
                               AudioEncoder encoder) {
        mContext = context.getApplicationContext();
        mAddress = address;
        mDataPort = dataPort;
        mStreamConnectionId = streamConnectionId;
        mPairing = pairing;
        mEncoder = encoder;
    }

    /** Sample rate the receiver's own format resolution gives {@code bits}, or 0 when unknown. */
    public static int sampleRateForFormat(int bits) {
        if (isOpusFormat(bits)) {
            return OPUS_SAMPLE_RATE;
        }
        for (int i = 0; i < FORMAT_BITS.length; i++) {
            if (FORMAT_BITS[i] == bits) {
                return FORMAT_RATE[i];
            }
        }
        return 0;
    }

    /** Channel count the receiver's own format resolution gives {@code bits}, or 0 when unknown. */
    public static int channelsForFormat(int bits) {
        if (isOpusFormat(bits)) {
            return OPUS_CHANNELS;
        }
        for (int i = 0; i < FORMAT_BITS.length; i++) {
            if (FORMAT_BITS[i] == bits) {
                return FORMAT_CHANNELS[i];
            }
        }
        return 0;
    }

    /**
     * True when the receiver resolves these format bits to Opus.  That is exactly its own test:
     * {@code (bits & OPUS_MONO) != 0} with {@code OPUS_MONO = 0x70000000}, checked before the PCM
     * table, so both 0x40000000 (the iPhone's value) and the 0x70000000 mask are Opus.
     */
    public static boolean isOpusFormat(int bits) {
        return (bits & AUDIO_FORMAT_OPUS_MASK) != 0;
    }

    /**
     * The format actually in force: the accessory's echo wins when it is one the receiver knows
     * (it may pick a different rate/channel count than we asked for), otherwise what we asked for
     * decides.  Never returns 0, so the sender always has a usable shape.
     */
    public static int negotiatedFormatBits(int requestedBits, int echoedBits) {
        if (isOpusFormat(echoedBits) || sampleRateForFormat(echoedBits) > 0) {
            return echoedBits;
        }
        return requestedBits != 0 ? requestedBits : AUDIO_FORMAT_OPUS_48K_MONO;
    }

    public boolean isRunning() {
        return mRunning;
    }

    public String error() {
        return mError;
    }

    /** Blocking: opens the UDP socket, starts the capture, sends until {@link #stop()}. */
    public void run() {
        if (mRunning || mStopped.get()) {
            return;
        }
        try {
            mKey = mPairing.dataStreamOutputKey(mStreamConnectionId);
            mSocket = new DatagramSocket();
            mSocket.connect(mAddress, mDataPort);
            mState.log(TAG, "audio RTP -> " + mAddress.getHostAddress() + ':' + mDataPort
                    + " (stream " + AUDIO_STREAM_TYPE + " AltAudio, audioType="
                    + AUDIO_TYPE_DEFAULT + ", " + mEncoder.describe()
                    + ", 1 Opus packet = " + OPUS_FRAME_MS + "ms/" + OPUS_FRAMES_PER_PACKET
                    + " samples, sealed)");
            final PlaybackAudioSource source = new PlaybackAudioSource(mContext);
            source.setSink(new PlaybackAudioSource.AudioSink() {
                @Override
                public void onAudio(byte[] data, int length) {
                    enqueue(data, length);
                }
            });
            guardCaptureVolume();
            if (!source.start(null)) {
                mError = "capture start failed: " + source.stats().error;
                mState.log(TAG, mError);
                return;
            }
            mSource = source;
            mRunning = true;
            mStartedAtMs = android.os.SystemClock.elapsedRealtime();
            mLastStatsMs = mStartedAtMs;
            mLastStatsPackets = mPackets;
            final Thread pump = new Thread(new Runnable() {
                @Override
                public void run() {
                    MediaThreads.raise(MediaPriority.THREAD_AUDIO_TX);
                    pump();
                }
            }, MediaPriority.THREAD_AUDIO_TX);
            pump.setDaemon(true);
            pump.start();
            while (mRunning) {
                try {
                    Thread.sleep(500L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                final long now = android.os.SystemClock.elapsedRealtime();
                /* Time-based, not packet-count-based: a "% 200" check almost never lands on a
                 * multiple with real capture block sizes, so it produced no evidence at all. */
                if (now - mLastStatsMs >= STATS_INTERVAL_MS) {
                    logPacing(source, now);
                    mLastStatsMs = now;
                    mLastStatsPackets = mPackets;
                }
            }
        } catch (Throwable t) {
            mError = String.valueOf(t);
            mState.log(TAG, "audio sender failed: " + t);
        } finally {
            stop();
        }
    }

    /**
     * During casting, pull the media volume into the safe band (only when above the ceiling, untouched inside it)
     * and remember the original value.  With the phone volume inside the band this is a no-op — in the vast
     * majority of cases the user's setting is never changed.
     */
    private void guardCaptureVolume() {
        try {
            final android.media.AudioManager audio =
                    (android.media.AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
            if (audio == null) {
                return;
            }
            final int max = audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC);
            final int current = audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC);
            final int target = com.okcar.link.media.AudioLevels.safeCaptureVolume(current, max);
            if (target < 0) {
                mState.log(TAG, "capture volume " + current + '/' + max
                        + " is inside the safe band — left alone");
                return;
            }
            mSavedMediaVolume = current;
            audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, target, 0);
            mState.log(TAG, "capture volume " + current + '/' + max + " would clip the capture —"
                    + " lowered to " + target + '/' + max + " for this session (restored on stop)");
        } catch (Throwable t) {
            mState.log(TAG, "capture volume guard unavailable: " + t);
        }
    }

    /** Restore the volume changed by the safe band; do nothing if it was never touched. */
    private void restoreCaptureVolume() {
        final int saved = mSavedMediaVolume;
        mSavedMediaVolume = -1;
        if (saved < 0) {
            return;
        }
        try {
            final android.media.AudioManager audio =
                    (android.media.AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
            if (audio != null) {
                audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, saved, 0);
                mState.log(TAG, "capture volume restored to " + saved);
            }
        } catch (Throwable t) {
            mState.log(TAG, "capture volume restore failed: " + t);
        }
    }

    private String averagePacketBytes() {
        return mPackets == 0 ? "0" : String.valueOf(mBytes / mPackets);
    }

    /**
     * The one line a future session is judged by: a healthy Opus 20 ms stream is
     * {@link #PACING_PACKETS_PER_SECOND} packets/s, so the measured rate is the whole story.
     * "airplay: audio pacing 50.1 packets/s, target 50/s" is healthy; 130/s is the burst defect.
     */
    private void logPacing(PlaybackAudioSource source, long nowMs) {
        final long windowMs = nowMs - mLastStatsMs;
        final long packets = mPackets - mLastStatsPackets;
        final double rate = windowMs > 0 ? packets * 1000.0 / windowMs : 0.0;
        mState.log(TAG, "airplay: audio pacing "
                + String.format(java.util.Locale.US, "%.1f", rate) + " packets/s, target "
                + PACING_PACKETS_PER_SECOND + "/s"
                + " (sent=" + mPackets + " packets / " + mFramesSent + " frames / " + mBytes + "B, "
                + mOpusPackets + " opus, " + averagePacketBytes() + "B avg, queued=" + mQueue.size()
                + " blocks/" + mQueuedFrames.get() + " frames, dropped=" + mDroppedBlocks
                + ", lateResyncs=" + mResyncs + ", lastDelay=" + mLastPaceDelayMs + "ms, slip="
                + mPaceSlipMs + "ms, up=" + ((nowMs - mStartedAtMs) / 1000L) + "s), captured frames="
                + source.stats().frames
                + ", nonZero=" + source.stats().nonZeroSamples + ", peak=" + source.stats().peak
                + ", peakWin=" + source.stats().windowPeak + ", clip="
                + source.stats().clippedSamples
                + (source.stats().clippedSamples > 0
                        ? " (capture is clipping: phone media volume too high, quality cannot be restored downstream)" : "")
                + ", music=" + source.stats().musicState);
        source.resetWindowPeak();
    }

    public void stop() {
        if (!mStopped.compareAndSet(false, true)) {
            return;
        }
        mRunning = false;
        final PlaybackAudioSource source = mSource;
        mSource = null;
        if (source != null) {
            source.stop();
        }
        mQueue.clear();
        mQueuedFrames.set(0L);
        final DatagramSocket socket = mSocket;
        mSocket = null;
        if (socket != null) {
            socket.close();
        }
        final AudioEncoder encoder = mEncoder;
        if (encoder != null) {
            encoder.close();
        }
        restoreCaptureVolume();
        mState.log(TAG, "stopped after " + mPackets + " audio packets / " + mFramesSent
                + " frames / " + mBytes + "B (" + mOpusPackets + " opus packets)"
                + (mError.isEmpty() ? "" : ", error=" + mError));
    }

    /* ------------------------------------------------------------------ sending */

    /** Stereo frames in one captured block (4 bytes each); 0 for a partial trailing frame. */
    private static long framesOf(byte[] block) {
        return block.length / CAPTURE_BYTES_PER_FRAME;
    }

    /** Called on the capture thread: never blocks, drops the oldest block if we fall behind. */
    private void enqueue(byte[] pcmLittleEndian, int length) {
        if (!mRunning || pcmLittleEndian == null || length <= 0) {
            return;
        }
        final byte[] block = java.util.Arrays.copyOf(pcmLittleEndian, length);
        if (!mQueue.offer(block)) {
            final byte[] dropped = mQueue.poll();
            if (dropped != null) {
                mQueuedFrames.addAndGet(-framesOf(dropped));
                mDroppedBlocks++;
            }
            if (!mQueue.offer(block)) {
                /* Another producer raced us; the newest block is the one worth keeping. */
                mDroppedBlocks++;
                return;
            }
        }
        mQueuedFrames.addAndGet(framesOf(block));
        /* Bound the latency, not just the block count: drop the oldest until we are back inside
         * MAX_QUEUED_FRAMES.  A 240 ms capture block is most of the budget, so the frame count is
         * the only bound that means anything.  Never blocks the capture callback. */
        while (mQueuedFrames.get() > MAX_QUEUED_FRAMES) {
            final byte[] dropped = mQueue.poll();
            if (dropped == null) {
                break;
            }
            mQueuedFrames.addAndGet(-framesOf(dropped));
            mDroppedBlocks++;
        }
    }

    /**
     * Drops every queued block but the newest.  Used at startup (audio captured before the pump
     * was scheduled is stale) and after a pacing resync (a stall built up a backlog that must not
     * be dumped into the accessory's 80 ms jitter buffer).  Live audio only.
     */
    private void discardBacklog() {
        byte[] newest = null;
        while (true) {
            final byte[] block = mQueue.poll();
            if (block == null) {
                break;
            }
            if (newest != null) {
                mQueuedFrames.addAndGet(-framesOf(newest));
                mDroppedBlocks++;
            }
            newest = block;
        }
        if (newest != null && !mQueue.offer(newest)) {
            mQueuedFrames.addAndGet(-framesOf(newest));
            mDroppedBlocks++;
        }
    }

    /** Takes the next block, keeping the queued-frame accounting straight. */
    private byte[] takeBlock() throws InterruptedException {
        final byte[] block = mQueue.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS);
        if (block != null) {
            mQueuedFrames.addAndGet(-framesOf(block));
        }
        return block;
    }

    /** Sends queued blocks on a real-time schedule (see {@link Pacer}). */
    private void pump() {
        /* Anything captured before this thread was scheduled is already stale. */
        discardBacklog();
        while (mRunning) {
            final byte[] block;
            try {
                block = takeBlock();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (block == null) {
                continue;
            }
            sendBlock(block, block.length);
        }
        /* Flush whatever is still queued so the tail is not lost on an orderly stop. */
        byte[] tail;
        while ((tail = mQueue.poll()) != null) {
            mQueuedFrames.addAndGet(-framesOf(tail));
            sendBlock(tail, tail.length);
        }
    }

    /**
     * Logs the outgoing datagram's unencrypted shape — the RTP header fields and the first Opus
     * bytes (TOC + frame count) — so our framing can be compared with the captured iPhone's
     * (PT 105, SSRC 0, 20 ms per packet, ~485 B plaintext) straight from the on-device log.
     * Bounded: the first {@link #DATAGRAM_LOG_COUNT} packets and then every 100th.
     */
    private void logDatagram(byte[] packet, byte[] opus) {
        if (mPackets >= DATAGRAM_LOG_COUNT && (mPackets % 100) != 0) {
            return;
        }
        final int seq = ((packet[2] & 0xFF) << 8) | (packet[3] & 0xFF);
        final long ts = ((packet[4] & 0xFFL) << 24) | ((packet[5] & 0xFFL) << 16)
                | ((packet[6] & 0xFFL) << 8) | (packet[7] & 0xFFL);
        final long ssrc = ((packet[8] & 0xFFL) << 24) | ((packet[9] & 0xFFL) << 16)
                | ((packet[10] & 0xFFL) << 8) | (packet[11] & 0xFFL);
        final StringBuilder head = new StringBuilder();
        for (int i = 0; i < Math.min(4, opus.length); i++) {
            head.append(String.format(java.util.Locale.US, "%02x", opus[i] & 0xFF));
        }
        /* The documented shape (see DATAGRAM_LOG_COUNT) is "the first few in full, then a sample":
         * one line per datagram at 50 packets/s was the loudest writer on the phone (logd measured
         * at 10% CPU on 217) for information the first packet plus a steady sample already carries.
         * The pacing/counter line still reports every packet. */
        if (mPackets > DATAGRAM_LOG_COUNT && mPackets % 100 != 0) {
            return;
        }
        mState.log(TAG, "airplay: audio datagram #" + mPackets + " " + packet.length + "B"
                + " (RTP12 + opus " + opus.length + "B + tag16 + counter8)"
                + ", pt=" + (packet[1] & 0xFF) + " ssrc=0x" + Long.toHexString(ssrc)
                + " seq=" + seq + " ts=" + ts + " step=" + OPUS_FRAMES_PER_PACKET
                + ", opusHead=" + head);
    }

    /**
     * Downmixes the stereo capture to mono and sends one sealed RTP packet per Opus packet the
     * encoder emits from it.  The encoder owns the 20 ms framing (it carries a partial frame
     * across blocks), so the packetisation never drifts off the receiver's 960-sample step.
     */
    private void sendBlock(byte[] pcmLittleEndian, int length) {
        final DatagramSocket socket = mSocket;
        if (!mRunning || socket == null || length <= 0) {
            return;
        }
        try {
            /* The capture is always stereo 48k; AltAudio is OPUS/48000/1, so fold it down before
             * encoding.  One packet then still means exactly one RTP timestamp step. */
            final byte[] mono = downmixToMono(pcmLittleEndian, length);
            if (mono.length == 0) {
                return;
            }
            mEncoded.clear();
            mEncoder.encode(mono, mono.length, mEncoded);
            final byte[] key = mKey;
            for (int i = 0; i < mEncoded.size(); i++) {
                final byte[] opus = mEncoded.get(i);
                if (opus == null || opus.length == 0) {
                    continue;
                }
                final byte[] packet = packetizeOpus(opus, opus.length, mSequence, mTimestamp, key,
                        mCounter);
                socket.send(new DatagramPacket(packet, packet.length, mAddress, mDataPort));
                logDatagram(packet, opus);
                mSequence = (mSequence + 1) & 0xFFFF;
                /* The encoder emits one 20 ms packet per 960-sample input frame, so the send
                 * order is the sample order and the receiver's ts step is exactly
                 * OPUS_FRAMES_PER_PACKET. */
                mTimestamp += OPUS_FRAMES_PER_PACKET;
                mCounter++;
                mPackets++;
                mOpusPackets++;
                mBytes += packet.length;
                mFramesSent += OPUS_FRAMES_PER_PACKET;
                /* Hold the wire on the wall clock: one 960-sample packet every 20 ms, anchored
                 * when the stream started sending — never on the sum of the sleeps already done,
                 * and never as a catch-up burst (see Pacer). */
                final long delay = mPacer.delayMs(mFramesSent,
                        android.os.SystemClock.elapsedRealtime());
                mLastPaceDelayMs = delay;
                mPaceSlipMs = mPacer.slipMs();
                if (mPacer.lastCallResynced()) {
                    mResyncs++;
                    if (mPaceSlipMs >= PACING_BACKLOG_DROP_SLIP_MS) {
                        /* A real stall: the audio that piled up is stale.  Dump it rather than
                         * bursting it into the accessory's 80 ms jitter buffer. */
                        discardBacklog();
                        mState.log(TAG, "airplay: audio pacing resync after a " + mPaceSlipMs
                                + " ms slip — stale backlog dropped (" + mResyncs + " resyncs)");
                    }
                }
                if (delay > 0) {
                    Thread.sleep(delay);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            mRunning = false;
        } catch (Exception e) {
            mError = "opus encode failed: " + e;
            mState.log(TAG, "audio encode failed — stopping the audio stream: " + e);
            mRunning = false;
        }
    }

    /**
     * Averages a little-endian 16-bit stereo block down to little-endian 16-bit mono.  AltAudio is
     * mono, so every block takes this path; a partial trailing frame is ignored.
     */
    public static byte[] downmixToMono(byte[] pcmLittleEndian, int length) {
        if (pcmLittleEndian == null || length < CAPTURE_BYTES_PER_FRAME) {
            return new byte[0];
        }
        final int frames = length / CAPTURE_BYTES_PER_FRAME;
        final byte[] out = new byte[frames * BYTES_PER_SAMPLE];
        for (int i = 0; i < frames; i++) {
            final int left = (short) ((pcmLittleEndian[i * 4] & 0xFF)
                    | (pcmLittleEndian[i * 4 + 1] << 8));
            final int right = (short) ((pcmLittleEndian[i * 4 + 2] & 0xFF)
                    | (pcmLittleEndian[i * 4 + 3] << 8));
            final int mono = (left + right) / 2;
            out[i * 2] = (byte) mono;
            out[i * 2 + 1] = (byte) (mono >> 8);
        }
        return out;
    }

    /**
     * Milliseconds to wait before sending the packet that follows {@code framesSent} frames of
     * audio: the wire should be at most {@code elapsedMs} ahead of the sample clock, never behind
     * it.  Pure, so the pacing arithmetic is regression-tested on the host.
     *
     * <p>This is the arithmetic, not the policy: it says nothing about <i>when</i> the clock
     * started.  Driving it with "milliseconds since the stream began" is the defect it was written
     * to prevent — the captured audio is inherently one capture block (~240 ms) behind that clock,
     * so the subtraction is always negative and every packet gets a zero delay, i.e. a burst.
     * Use {@link Pacer}, which anchors the clock on the first packet sent.
     *
     * @param framesSent frames already handed to the socket
     * @param elapsedMs  milliseconds since the stream started
     * @param sampleRate sample rate of those frames
     * @return 0 when we are at or behind the sample clock, else the delay to hold the rate
     */
    public static long paceDelayMs(long framesSent, long elapsedMs, int sampleRate) {
        if (sampleRate <= 0) {
            return 0;
        }
        final long scheduledMs = framesSent * 1000L / sampleRate;
        final long delay = scheduledMs - elapsedMs;
        return delay > 0 ? delay : 0;
    }

    /**
     * Wall-clock anchored packet pacer: one packet per {@code packetFrames / sampleRate} seconds,
     * with no catch-up.
     *
     * <p>The anchor is established by the first packet, which goes out immediately; every later
     * packet is due one packet period after the previous one, measured on the wall clock
     * ({@link android.os.SystemClock#elapsedRealtime()}) and matched against the frames actually
     * handed to the socket.  A cumulative sleep budget drifts and — worse — hides a stall: it
     * cannot tell "we are on schedule" from "we slept too long and owe ourselves a burst".
     *
     * <p>When the schedule has slipped more than {@code maxSlipMs} behind the wall clock the
     * anchor moves forward to now (a <i>resync</i>, reported by {@link #lastCallResynced()}) and
     * the caller drops the stale backlog.  The wire then continues at the audio rate from that
     * point instead of repaying the stall all at once — a burst is exactly what overruns the
     * accessory's 80 ms AltAudio jitter buffer.
     *
     * <p>Pure Java and host-testable: `.car-test/airplay-audio-wireless` drives it with a
     * synthetic clock and synthetic 240 ms capture blocks.
     */
    public static final class Pacer {

        private final int mSampleRate;
        private final int mPacketFrames;
        private final long mMaxSlipMs;
        private boolean mAnchored;
        private long mAnchorMs;
        private long mAnchorFrames;
        private long mResyncs;
        private long mSlipMs;
        private boolean mLastResynced;

        public Pacer(int sampleRate, int packetFrames, long maxSlipMs) {
            mSampleRate = sampleRate;
            mPacketFrames = packetFrames;
            mMaxSlipMs = maxSlipMs;
        }

        /**
         * Milliseconds to wait before handing the packet that ends at cumulative frame index
         * {@code framesSent} (frames already sent, including this packet) to the socket.
         *
         * @param nowMs {@link android.os.SystemClock#elapsedRealtime()} at the decision point
         * @return 0 when the packet is due (or late within {@code maxSlipMs}), else the wait
         */
        public long delayMs(long framesSent, long nowMs) {
            mLastResynced = false;
            if (mSampleRate <= 0 || mPacketFrames <= 0) {
                return 0;
            }
            if (!mAnchored) {
                mAnchored = true;
                mAnchorMs = nowMs;
                mAnchorFrames = framesSent;
                mSlipMs = 0;
                return 0;
            }
            final long frames = framesSent - mAnchorFrames;
            final long elapsedMs = Math.max(0L, nowMs - mAnchorMs);
            final long delay = paceDelayMs(frames, elapsedMs, mSampleRate);
            final long scheduledMs = frames * 1000L / mSampleRate;
            mSlipMs = elapsedMs > scheduledMs ? elapsedMs - scheduledMs : 0L;
            if (delay <= 0 && mSlipMs > mMaxSlipMs) {
                /* Too late to be "on schedule": re-anchor here.  The caller decides whether the
                 * backlog is stale enough to drop (see PACING_BACKLOG_DROP_SLIP_MS); either way
                 * the skipped media is never repaid as a burst.  mSlipMs keeps the triggering
                 * value so the caller can report it; the next call measures from the new anchor. */
                mAnchorMs = nowMs;
                mAnchorFrames = framesSent;
                mResyncs++;
                mLastResynced = true;
            }
            return delay;
        }

        /** How many times {@link #delayMs} has moved the anchor forward. */
        public long resyncs() {
            return mResyncs;
        }

        /**
         * How late the most recent {@link #delayMs} call found the sender (0 = on schedule).  On a
         * resync this is the slip that triggered it, so the caller can compare it with
         * {@link #PACING_BACKLOG_DROP_SLIP_MS}.
         */
        public long slipMs() {
            return mSlipMs;
        }

        /** True when the most recent {@link #delayMs} call re-anchored. */
        public boolean lastCallResynced() {
            return mLastResynced;
        }
    }

    /**
     * The stream dictionary our audio SETUP sends — the real iPhone's AltAudio shape, and nothing
     * else.  Pure so the host harness can lock it: this dict has been wrong three times now —
     * wrong format bits (0x20 resolved to 16000 Hz on the accessory, 0xffffffff resolved to
     * 44100), and, until now, the wrong stream (type 100 with no {@code audioType} made the
     * accessory set up "MainAudio, audioType:&lt;&lt;NULL&gt;&gt;").
     *
     * <p>What the captured iPhone sent (the receiver logs the whole plist):
     * {@code {"type":101,"audioType":"default","audioFormat":1073741824,
     * "supportsHighAccuracyTimestamps":true,"supportsRTPPacketRedundancy":true,
     * "audioLatencyMs":80,"streamConnectionID":...}}.
     *
     * <p><b>Why we now claim {@code supportsHighAccuracyTimestamps}.</b>  It is the one SETUP key
     * that changes the receiver's audio pipeline, and its receiver log is unambiguous:
     *
     * <pre>
     *   iPhone: [AirPlayReceiverSessionAudio] Enabling high-accuracy timestamps
     *           [APSRTPJitterBuffer] Creating jitter buffer 'AltAudio' ... legacy buffering mode: N
     *           [AirPlayReceiverSessionAudio] Sender supports, and hence enabling RFC2198 redundancy
     *   ours:   [AirPlayReceiverSessionAudio] Switching jitter buffer to legacy mode
     *           [APSRTPJitterBuffer] Creating jitter buffer 'AltAudio' ... legacy buffering mode: Y
     *           [AirPlayReceiverSessionAudio] Sender does not support RFC2198 redundancy; disabling support
     * </pre>
     *
     * The receiver's {@code _MainAltAudioSetup} reads exactly this key (and a session capability
     * flag) to choose between the legacy buffering mode and the mode every working session on this
     * accessory used.  In legacy mode our stream is accepted, decrypted and enqueued — the
     * receiver even logs {@code Triggering discard excess with enqueued sample count: 70079} — yet
     * its PCM ring is never drained ({@code Underruns: 0}, no {@code take packet}, no
     * {@code AAudio(0) Len=[...]}), which is the defect this fixes.
     *
     * <p>We still do <b>not</b> claim {@code supportsRTPPacketRedundancy}: claiming it makes the
     * receiver build the redundant packet-handler layout (an extra queue per extra block) and
     * expect RFC2198 blocks we do not send.  Its parser keeps the plain layout for us
     * ({@code APSRTPPacketHandlerReadNewMessage}'s extended branch is gated on a handler field its
     * create path zeroes), so a raw Opus frame stays correct; asserting a capability we do not
     * exercise would only ask the receiver to act on a lie.
     *
     * @param streamConnectionId the id the accessory derives the stream key from
     * @param audioFormatBits    the format bits (see {@link #AUDIO_FORMAT_OPUS_48K_MONO})
     */
    public static java.util.Map<String, Object> setupStream(long streamConnectionId,
                                                            int audioFormatBits) {
        final java.util.Map<String, Object> stream = new java.util.LinkedHashMap<String, Object>();
        stream.put("type", Integer.valueOf(AUDIO_STREAM_TYPE));
        stream.put("streamConnectionID", Long.valueOf(streamConnectionId));
        stream.put("audioFormat", Integer.valueOf(audioFormatBits));
        stream.put("audioType", AUDIO_TYPE_DEFAULT);
        stream.put("controlPort", Integer.valueOf(0));
        stream.put("audioLatencyMs", Long.valueOf(AUDIO_LATENCY_MS));
        /* The iPhone's own high-accuracy declaration; it selects the non-legacy jitter buffer. */
        stream.put("supportsHighAccuracyTimestamps", Boolean.TRUE);
        return stream;
    }

    /**
     * The bits we ask for, given what the accessory advertised (0 = it listed none).
     *
     * <p>Opus, unconditionally.  The receiver resolves {@code (bits & 0x70000000) != 0} to
     * OPUS/48000/1 and this is the exact value the captured real iPhone sent; the advertised mask
     * is kept for the log/evidence only.  PCM — what this path used to ask for — is recognised by
     * the receiver but never fed to the AltAudio jitter buffer, which is the defect this fixes.
     */
    public static int chooseAudioFormatBits(int advertisedBits) {
        return AUDIO_FORMAT_OPUS_48K_MONO;
    }

    /**
     * One wire packet: {@code rtp ‖ seal(opus) ‖ u64 le counter}.  Pure and host-testable.
     *
     * @param opus     one whole Opus packet exactly as the encoder emitted it
     * @param length   its length (no padding, no length prefix, no byte swap)
     * @param sequence RTP sequence number (wraps at 65536)
     * @param timestamp RTP timestamp in samples at 48 kHz (steps by 960 per packet)
     * @param key      data-stream output key for this audio stream
     * @param counter  per-packet counter; also the low half of the AEAD nonce
     */
    public static byte[] packetizeOpus(byte[] opus, int length, int sequence, long timestamp,
                                       byte[] key, long counter) {
        if (opus == null || key == null || length < 0 || length > opus.length) {
            throw new IllegalArgumentException("bad packetizeOpus arguments");
        }
        final byte[] rtp = new byte[RTP_HEADER_LEN];
        rtp[0] = (byte) 0x80;
        rtp[1] = (byte) PAYLOAD_TYPE;
        putU16be(rtp, 2, sequence & 0xFFFF);
        putU32be(rtp, 4, timestamp);
        putU32be(rtp, 8, SSRC);

        /* The receiver authenticates with RTP bytes 4..11 (timestamp + ssrc). */
        final byte[] aad = new byte[8];
        System.arraycopy(rtp, 4, aad, 0, 8);

        final byte[] sealed = AirPlayPairing.chachaSeal(key,
                AirPlayPairing.nonceForCounter(counter), java.util.Arrays.copyOf(opus, length), aad);
        final byte[] out = new byte[RTP_HEADER_LEN + sealed.length + NONCE_TRAILER_LEN];
        System.arraycopy(rtp, 0, out, 0, RTP_HEADER_LEN);
        System.arraycopy(sealed, 0, out, RTP_HEADER_LEN, sealed.length);
        for (int i = 0; i < 8; i++) {
            out[RTP_HEADER_LEN + sealed.length + i] = (byte) (counter >>> (8 * i));
        }
        return out;
    }

    private static void putU16be(byte[] target, int offset, int value) {
        target[offset] = (byte) ((value >>> 8) & 0xFF);
        target[offset + 1] = (byte) (value & 0xFF);
    }

    private static void putU32be(byte[] target, int offset, long value) {
        target[offset] = (byte) ((value >>> 24) & 0xFF);
        target[offset + 1] = (byte) ((value >>> 16) & 0xFF);
        target[offset + 2] = (byte) ((value >>> 8) & 0xFF);
        target[offset + 3] = (byte) (value & 0xFF);
    }
}
