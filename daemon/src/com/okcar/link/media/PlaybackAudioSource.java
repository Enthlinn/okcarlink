/*
 * OkcarOS link stack — L4 media plane: the audio return source.
 *
 * CarPlay sends the phone's media audio to the head unit, which is exactly what
 * the closed-source app does with a bare AudioRecord(REMOTE_SUBMIX) — and what
 * §12.15 proved is an AOSP built-in mechanism on this device: the engine matches
 * the empty address, startInput() opens OUT_REMOTE_SUBMIX("0") and the MEDIA
 * policy duplicates playback into it.  No policy mix, no framework patch.
 *
 * Two ways exist to get playback audio:
 *   (a) AudioPlaybackCaptureConfiguration — the "proper" API, but it requires a
 *       MediaProjection token (user consent), which a headless link service has
 *       no way to obtain;
 *   (b) AudioRecord(REMOTE_SUBMIX) — what the shipped app uses and what is
 *       field-verified here (§12.15 / §12.17 D), gated by CAPTURE_AUDIO_OUTPUT.
 * This class uses (b), with the same constructor arguments as the closed-source
 * implementation (48000 Hz, stereo, PCM16) so the captured stream is identical.
 *
 * Caveat carried over from the field: if STREAM_MUSIC is muted, the capture
 * yields silence — which looks exactly like "audio return is broken".
 */

package com.okcar.link.media;

import android.annotation.SuppressLint;
import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicBoolean;

public final class PlaybackAudioSource {

    /** Receives each captured PCM block (little-endian 16-bit, 48 kHz stereo). */
    public interface AudioSink {
        void onAudio(byte[] data, int length);
    }

    private static final String TAG = "OkCarLink/Audio";

    /** MediaRecorder.AudioSource.REMOTE_SUBMIX — the playback-capture source. */
    private static final int SOURCE_REMOTE_SUBMIX = 8;

    private static final int SAMPLE_RATE = 48_000;
    private static final int CHANNEL_MASK = AudioFormat.CHANNEL_IN_STEREO;
    private static final int ENCODING = AudioFormat.ENCODING_PCM_16BIT;
    private static final int BYTES_PER_FRAME = 4; // stereo * 16 bit

    /** Clears the window peak (called by the sampling side after printing a stats line). */
    public void resetWindowPeak() {
        mStats.windowPeak = 0;
    }

    public static final class Stats {
        /** STREAM_MUSIC state at capture start — silence is usually caused by it. */
        public volatile String musicState = "";
        public volatile long frames;
        public volatile long nonZeroSamples;
        public volatile int peak;
        /** Peak magnitude in the latest stats window (cleared by the sampling side each window). */
        public volatile int windowPeak;
        /** Cumulative clipped sample count (magnitude ≥ {@link AudioLevels#CLIP_THRESHOLD}) — >0 means the capture is already distorting. */
        public volatile long clippedSamples;
        /** Current AGC gain (stored as an integer ×100, for easy logging and assertions). */
        public volatile int gainX100 = 100;
        public volatile boolean silence = true;
        public volatile String error = "";

        public String describe(long elapsedMs) {
            final double seconds = Math.max(0.001, elapsedMs / 1000.0);
            if (!error.isEmpty()) {
                return "capture failed: " + error;
            }
            return String.format("%d Hz stereo: %d frames (%.1f s), %d non-zero samples, "
                            + "peak %d, %s -> %s",
                    SAMPLE_RATE, frames, frames / (double) SAMPLE_RATE, nonZeroSamples, peak,
                    musicState.isEmpty() ? "STREAM_MUSIC ?" : musicState,
                    silence ? "SILENCE (first check that STREAM_MUSIC is not muted/0)"
                            : "AUDIO PRESENT");
        }
    }

    private final Context mContext;
    private final Stats mStats = new Stats();
    private final AtomicBoolean mRunning = new AtomicBoolean();

    private AudioRecord mRecord;
    private volatile AudioSink mSink;
    private Thread mReadThread;
    private FileOutputStream mDump;
    private int mBufferBytes;

    /** Whether AGC is enabled: off by default (the volume is fixed by the safe band, which is enough); only persist.okcar.agc=1 turns it on. */
    private volatile boolean mAgcEnabled = true;

    /** Current AGC gain (starts at 1.0) and the peak follower. */
    private volatile double mAgcGain = 1.0;
    private volatile double mAgcPeak = 0.0;

    public PlaybackAudioSource(Context context) {
        mContext = context.getApplicationContext();
    }

    /** Attaches the consumer (the AirPlay audio sender); null restores dump-only. */
    /**
     * The one playback capture: the privileged REMOTE_SUBMIX record (uid 0 holds
     * {@code CAPTURE_AUDIO_OUTPUT}).  The MediaProjection consent path is gone.
     */
    private AudioRecord createPlaybackRecord() throws IOException {
        return new AudioRecord(SOURCE_REMOTE_SUBMIX, SAMPLE_RATE, CHANNEL_MASK, ENCODING,
                mBufferBytes);
    }

    public void setSink(AudioSink sink) {
        mSink = sink;
    }

    public Stats stats() {
        return mStats;
    }

    public boolean isRunning() {
        return mRunning.get();
    }

    @SuppressLint("MissingPermission") // CAPTURE_AUDIO_OUTPUT / RECORD_AUDIO, granted privapp perms
    public boolean start(File dumpFile) {
        if (mRunning.get()) {
            return true;
        }
        try {
            final int minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_MASK, ENCODING);
            if (minBuffer <= 0) {
                throw new IOException("AudioRecord.getMinBufferSize -> " + minBuffer);
            }
            /* The capture cushion IS the audio latency floor: whatever AudioRecord holds has not
             * reached the receiver yet.  It used to be pinned at 46080 B = 240 ms while the stream
             * advertised 80 ms, so the car heard everything a quarter second late and a backlog was
             * shed in 240 ms gulps (audible as stutter).  Match the advertised latency instead, and
             * keep the dial for a device whose capture cannot keep up that tight.
             *   persist.okcar.audio.capture_ms   clamp 20..240, default 80 */
            int cushionMs = 80;
            try {
                final String raw = android.os.SystemProperties.get(
                        "persist.okcar.audio.capture_ms", "80");
                cushionMs = Math.max(20, Math.min(240, Integer.parseInt(raw.trim())));
            } catch (Throwable ignored) {
                /* keep the default */
            }
            final int cushionBytes = (int) ((long) SAMPLE_RATE * 2 /* channels */
                    * 2 /* bytes per sample */ * cushionMs / 1000L);
            mBufferBytes = Math.max(minBuffer, cushionBytes);
            Log.i(TAG, "audio capture cushion " + cushionMs + "ms -> " + mBufferBytes + "B"
                    + " (min " + minBuffer + "B)");
            mStats.musicState = describeMusicStream();
            try {
                /* **Off** by default: the capture volume is already fixed at 60% by the safe band, so
                 * the captured level is in a fixed, predictable proportion to the content; dynamic
                 * gain is only needed for especially quiet content, and persist.okcar.agc=1 enables it. */
                mAgcEnabled = "1".equals(android.os.SystemProperties.get("persist.okcar.agc", "0"));
            } catch (Throwable ignored) {
                mAgcEnabled = true;
            }

            mRecord = createPlaybackRecord();
            if (mRecord == null || mRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                throw new IOException("AudioRecord(REMOTE_SUBMIX) not initialized (state="
                        + (mRecord == null ? "null" : mRecord.getState()) + ')');
            }
            if (dumpFile != null) {
                mDump = new FileOutputStream(dumpFile);
                Log.i(TAG, "dumping PCM to " + dumpFile);
            }
            mRecord.startRecording();
            mRunning.set(true);
            mReadThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    MediaThreads.raise(MediaPriority.THREAD_AUDIO_CAPTURE);
                    readLoop();
                }
            }, MediaPriority.THREAD_AUDIO_CAPTURE);
            mReadThread.setDaemon(true);
            mReadThread.start();
            Log.i(TAG, "remote-submix capture started (buffer " + mBufferBytes + "B)");
            return true;
        } catch (IOException | RuntimeException e) {
            mStats.error = String.valueOf(e);
            Log.w(TAG, "cannot start audio capture: " + e);
            stop();
            return false;
        }
    }

    private void readLoop() {
        final byte[] buffer = new byte[mBufferBytes];
        while (mRunning.get()) {
            /* Snapshot, never dereference the field.  stop() clears mRecord while this loop is
             * still running, and the resulting null dereference used to take the whole daemon down:
             * measured 2026-10-02, the SIGSEGV landed inside ART's own NPE reporting
             * (ThrowNullPointerExceptionForFieldAccess -> ArtField::PrettyField), so the catch
             * below never got a chance to run and the CarPlay session died with the process. */
            final AudioRecord record = mRecord;
            if (record == null) {
                break;
            }
            final int read;
            try {
                read = record.read(buffer, 0, buffer.length);
            } catch (Throwable e) {
                if (mRunning.get()) {
                    mStats.error = String.valueOf(e);
                    Log.w(TAG, "audio read failed: " + e);
                }
                break;
            }
            if (read <= 0) {
                continue;
            }
            mStats.frames += read / BYTES_PER_FRAME;
            final ByteBuffer samples = ByteBuffer.wrap(buffer, 0, read).order(ByteOrder.LITTLE_ENDIAN);
            int peak = mStats.peak;
            int windowPeak = mStats.windowPeak;
            long nonZero = mStats.nonZeroSamples;
            long clipped = mStats.clippedSamples;
            while (samples.remaining() >= 2) {
                final int sample = samples.getShort();
                if (sample != 0) {
                    nonZero++;
                    final int magnitude = sample == Short.MIN_VALUE ? AudioLevels.FULL_SCALE
                            : Math.abs(sample);
                    if (magnitude > peak) {
                        peak = magnitude;
                    }
                    if (magnitude > windowPeak) {
                        windowPeak = magnitude;
                    }
                    if (AudioLevels.clipped(magnitude)) {
                        clipped++;
                    }
                }
            }
            mStats.nonZeroSamples = nonZero;
            mStats.peak = peak;
            mStats.windowPeak = windowPeak;
            mStats.clippedSamples = clipped;
            /* Per-block normalization (AGC): the peak measured in this block sets the gain the next
             * block starts with. It must happen here — the phone media volume is "captured with
             * volume applied", and different content can differ by 20dB at the same setting (measured
             * peak 2777 at 70/150, slamming to 32768 at 120/150), which a fixed volume cannot cover. */
            /* Per-sample AGC: peak follow + fast attack, slow release. Adjusting the gain per block
             * produces roughly 4Hz pumping (the field's "sound goes loud and quiet"), so the gain must
             * vary continuously at the sample level; the only cost is two double multiply-adds per
             * sample. */
            if (mAgcEnabled) {
                double gain = mAgcGain;
                double agcPeak = mAgcPeak;
                for (int i = 0; i + 1 < read; i += 2) {
                    final int sample = (short) ((buffer[i] & 0xFF) | (buffer[i + 1] << 8));
                    final int magnitude = sample == Short.MIN_VALUE ? 32768 : Math.abs(sample);
                    agcPeak = AudioLevels.followPeak(agcPeak, magnitude);
                    gain = AudioLevels.stepGain(gain, agcPeak);
                    final int scaled = AudioLevels.applyGain(sample, gain);
                    buffer[i] = (byte) (scaled & 0xFF);
                    buffer[i + 1] = (byte) ((scaled >> 8) & 0xFF);
                }
                mAgcGain = gain;
                mAgcPeak = agcPeak;
                mStats.gainX100 = (int) Math.round(gain * 100);
            } else {
                mStats.gainX100 = 100;
            }
            mStats.silence = nonZero == 0;
            writeDump(buffer, read);
            final AudioSink sink = mSink;
            if (sink != null) {
                sink.onAudio(buffer, read);
            }
        }
    }

    private void writeDump(byte[] data, int length) {
        final FileOutputStream dump = mDump;
        if (dump == null) {
            return;
        }
        try {
            dump.write(data, 0, length);
        } catch (IOException e) {
            Log.w(TAG, "audio dump write failed: " + e);
        }
    }

    public void stop() {
        mRunning.set(false);
        final Thread thread = mReadThread;
        mReadThread = null;
        if (thread != null) {
            try {
                thread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (mRecord != null) {
            try {
                if (mRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    mRecord.stop();
                }
            } catch (RuntimeException e) {
                Log.w(TAG, "AudioRecord.stop failed: " + e);
            }
            mRecord.release();
            mRecord = null;
        }
        if (mDump != null) {
            try {
                mDump.close();
            } catch (IOException ignored) {
                /* nothing useful to do */
            }
            mDump = null;
        }
        Log.i(TAG, "audio capture stopped");
    }

    /** e.g. "STREAM_MUSIC vol=12/15 muted=false" — the §12.15 silence trap. */
    private String describeMusicStream() {
        try {
            final android.media.AudioManager manager =
                    (android.media.AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
            if (manager == null) {
                return "";
            }
            return "STREAM_MUSIC vol=" + manager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
                    + '/' + manager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
                    + " muted=" + manager.isStreamMute(android.media.AudioManager.STREAM_MUSIC);
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** Where probe dumps go (adb-pullable without any storage permission). */
    public static File dumpFile(Context context, String name) {
        final File dir = context.getExternalFilesDir(null);
        return dir == null ? null : new File(dir, name);
    }

    /** Wall-clock helper so a probe can report how long it actually ran. */
    public static long now() {
        return SystemClock.elapsedRealtime();
    }
}
