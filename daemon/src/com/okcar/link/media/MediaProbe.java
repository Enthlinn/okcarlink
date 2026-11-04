/*
 * OkcarOS link stack — L4 media plane: the media self test.
 *
 * The protocol half of the link stack can be verified without a car (§12.22);
 * this does the same for the media half:
 *
 *   video : mirror the screen into MediaCodec (H.264) and count access units
 *   audio : play a 1 kHz tone as USAGE_MEDIA and capture it back through
 *           REMOTE_SUBMIX — exactly the loop §12.15 proved works on this device
 *
 * Running both at once answers "does the sender side actually produce a stream?"
 * with byte counts and frame counts, no head unit involved.  The tone matters:
 * without playback the capture legitimately returns silence, and silence is
 * indistinguishable from a broken return path (the trap from §12.15 C).
 */

package com.okcar.link.media;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;

public final class MediaProbe {

    private static final String TAG = "OkCarLink/Media";

    private static final int TONE_HZ = 1000;
    private static final int TONE_AMPLITUDE = 12_000; // same order as the §12.15 probe

    private MediaProbe() {
    }

    public static final class Result {
        public String video = "(skipped)";
        public String audio = "(skipped)";
        public String tone = "(skipped)";
        public String dumps = "";
        public long elapsedMs;

        public String report() {
            final StringBuilder sb = new StringBuilder();
            sb.append("media probe ").append(String.format("%.1f s", elapsedMs / 1000.0)).append('\n');
            sb.append("  video: ").append(video).append('\n');
            sb.append("  audio: ").append(audio).append('\n');
            sb.append("  tone : ").append(tone).append('\n');
            if (!dumps.isEmpty()) {
                sb.append("  dump : ").append(dumps).append('\n');
            }
            return sb.toString();
        }
    }

    /**
     * Runs the probe; blocks for {@code seconds}.
     *
     * @param withVideo mirror + encode the screen
     * @param withAudio capture REMOTE_SUBMIX while playing a tone
     * @param dump      write the encoded H.264 / captured PCM next to the app's
     *                  external files dir so they can be pulled with adb
     */
    public static Result run(Context context, int seconds, boolean withVideo, boolean withAudio,
            boolean dump) {
        final Result result = new Result();
        final long startedAt = SystemClock.elapsedRealtime();

        MirrorVideoSource video = null;
        PlaybackAudioSource audio = null;
        TonePlayer tone = null;

        try {
            if (withVideo) {
                video = new MirrorVideoSource(context);
                final File dumpFile = dump ? PlaybackAudioSource.dumpFile(context, "mirror.h264") : null;
                if (!video.start(dumpFile)) {
                    result.video = "encoder start failed";
                }
            }
            if (withAudio) {
                // Play first, then capture: the §12.15 probe showed both orders work,
                // but starting playback first removes a startup race.
                tone = new TonePlayer();
                if (!tone.start()) {
                    result.tone = "tone playback start failed";
                } else {
                    SystemClock.sleep(200);
                }
                audio = new PlaybackAudioSource(context);
                final File dumpFile = dump ? PlaybackAudioSource.dumpFile(context, "capture.pcm") : null;
                if (!audio.start(dumpFile)) {
                    result.audio = "capture start failed";
                }
            }

            SystemClock.sleep(Math.max(1, seconds) * 1000L);
        } finally {
            final long elapsed = SystemClock.elapsedRealtime() - startedAt;
            result.elapsedMs = elapsed;

            if (video != null) {
                result.video = video.stats().describe(elapsed);
                video.stop();
            }
            if (audio != null) {
                result.audio = audio.stats().describe(elapsed);
                audio.stop();
            }
            if (tone != null) {
                result.tone = tone.describe(elapsed);
                tone.stop();
            }
            if (dump) {
                final StringBuilder sb = new StringBuilder();
                appendDump(sb, context, "mirror.h264");
                appendDump(sb, context, "capture.pcm");
                result.dumps = sb.toString().trim();
            }
        }
        Log.i(TAG, result.report().replace('\n', ' '));
        return result;
    }

    private static void appendDump(StringBuilder sb, Context context, String name) {
        final File file = PlaybackAudioSource.dumpFile(context, name);
        if (file != null && file.exists()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(file.getAbsolutePath()).append(" (").append(file.length()).append("B)");
        }
    }

    /**
     * Plays a 1 kHz stereo sine as USAGE_MEDIA / CONTENT_TYPE_MUSIC, i.e. the
     * audio the MEDIA policy duplicates into the remote submix.
     */
    private static final class TonePlayer {

        private static final int SAMPLE_RATE = 48_000;
        private static final int FRAMES_PER_CHUNK = 960; // 20 ms

        private AudioTrack mTrack;
        private Thread mThread;
        private volatile boolean mRunning;
        private volatile long mFramesWritten;
        private volatile String mError = "";

        boolean start() {
            try {
                final int minBuffer = AudioTrack.getMinBufferSize(SAMPLE_RATE,
                        AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
                mTrack = new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                .build())
                        .setAudioFormat(new AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(SAMPLE_RATE)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                                .build())
                        .setBufferSizeInBytes(Math.max(minBuffer, FRAMES_PER_CHUNK * 4 * 4))
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .build();
                mTrack.play();
                mRunning = true;
                mThread = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        writeLoop();
                    }
                }, "okcar-tone");
                mThread.setDaemon(true);
                mThread.start();
                return true;
            } catch (RuntimeException e) {
                mError = String.valueOf(e);
                Log.w(TAG, "tone start failed: " + e);
                return false;
            }
        }

        private void writeLoop() {
            final short[] chunk = new short[FRAMES_PER_CHUNK * 2];
            double phase = 0;
            final double step = 2 * Math.PI * TONE_HZ / SAMPLE_RATE;
            while (mRunning) {
                for (int frame = 0; frame < FRAMES_PER_CHUNK; frame++) {
                    final short value = (short) (Math.sin(phase) * TONE_AMPLITUDE);
                    chunk[frame * 2] = value;
                    chunk[frame * 2 + 1] = value;
                    phase += step;
                    if (phase > 2 * Math.PI) {
                        phase -= 2 * Math.PI;
                    }
                }
                final int written = mTrack.write(chunk, 0, chunk.length);
                if (written > 0) {
                    mFramesWritten += written / 2;
                }
            }
        }

        String describe(long elapsedMs) {
            if (!mError.isEmpty()) {
                return "playback failed: " + mError;
            }
            final double seconds = Math.max(0.001, elapsedMs / 1000.0);
            return String.format("%d Hz tone as USAGE_MEDIA, %d frames written (%.1f fps)",
                    TONE_HZ, mFramesWritten, mFramesWritten / seconds);
        }

        void stop() {
            mRunning = false;
            final Thread thread = mThread;
            mThread = null;
            if (thread != null) {
                try {
                    thread.join(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (mTrack != null) {
                try {
                    mTrack.stop();
                } catch (RuntimeException ignored) {
                    /* already stopped */
                }
                mTrack.release();
                mTrack = null;
            }
        }
    }
}
