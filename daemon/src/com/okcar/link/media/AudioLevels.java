package com.okcar.link.media;

/**
 * Capture level vs. "phone media volume" (pure Java, host-testable).
 *
 * <p>Why this exists: in the field on 2026-10-05, "muffled audio" had two faces — at phone media
 * volume 10/150 the capture is a very quiet signal (thin, with the noise floor lifted by the
 * backend), and pushing it to 120/150 **slams into full scale** (`peak=32768`, already clipped in
 * the capture, which no backend can undo). That is because {@code AudioRecord(REMOTE_SUBMIX)}
 * captures the **post-mix, media-volume-applied** signal, so the phone's own volume directly
 * determines the quality of what we send.
 *
 * <p>So at cast start we pull the volume into a safe band (remembering the original value and
 * restoring it on stop), and we also report "is it clipping" at all times. The thresholds here are
 * pure functions, pinned down on the desktop by a desktop harness.
 */
public final class AudioLevels {

    /** Capture sample full scale (int16); reaching it means already clipped. */
    public static final int FULL_SCALE = 32768;

    /** Threshold for "this sample is clipped": a little headroom, so 32767/-32768 jitter is not missed. */
    public static final int CLIP_THRESHOLD = 32760;

    /**
     * Calibration volume ratio (the target) used for capture.
     *
     * <p>Why a "fixed value" instead of dynamic gain: REMOTE_SUBMIX captures the **post-mix**
     * stream, and the volume has already been applied inside the mixer (which also explains why
     * "taking the data from memory" is still affected by the system volume — audio has no
     * framebuffer to read). With the phone media volume fixed at this ratio, the captured level is
     * in a **fixed, predictable proportion** to the content and needs no dynamic processing.
     * Empirical values: 0.8 (120/150) clips at full scale; 0.07 (10/150) is too thin to listen to;
     * 0.6 leaves headroom and is still loud enough.
     */
    public static final double SAFE_VOLUME_RATIO = 0.6;

    /** Lower edge of the safe band: below it the volume is pulled to the target (the field 10/150 is exactly this "too quiet"). */
    public static final double SAFE_VOLUME_LOW = 0.5;

    /** Upper edge of the safe band: above it the volume is pulled back to the target (the field 120/150 clips). */
    public static final double SAFE_VOLUME_HIGH = 0.7;

    /**
     * AGC target peak (about −2.7 dBFS): a little headroom, so it neither clips nor sounds quiet.
     *
     * <p>Why it is needed: REMOTE_SUBMIX captures a mix that **carries the phone media volume**,
     * and different content can differ by 20dB at the same volume setting — in the field, 70/150
     * measured a peak of only 2777 (about −21dBFS, "thin and muffled" to the ear), while 120/150
     * slams to 32768. A fixed volume cannot fit both, so the capture side does per-block peak
     * normalization (with limiting).
     */
    public static final int TARGET_PEAK = 24_000;

    /** Maximum gain: quiet content must not be lifted into noise (every 6dB is a factor of 2). */
    public static final double MAX_GAIN = 8.0;

    /* Gain smoothing must be done **per sample**, not per block: in the field, adjusting the gain
     * once per 240ms block made the jumps between blocks audible as "loud/quiet" pumping. The two
     * time constants below become one-pole coefficients at a 48k sample rate (smaller tau is faster).
     *   - attack: 10ms — pull back immediately on a peak, to prevent clipping;
     *   - release: 1000ms — gain recovers slowly, so it does not pump with the music. */
    public static final double ATTACK_TAU_S = 0.010;
    public static final double RELEASE_TAU_S = 1.000;

    /** Peak follower decay time constant: hold the recent peak and then fall slowly (about 300ms). */
    public static final double PEAK_TAU_S = 0.300;

    /** Sample rate (matches capture). */
    public static final int SAMPLE_RATE = 48_000;

    /** One-pole coefficient: 1 - exp(-1/(tau*fs)). */
    public static double onePole(double tauSeconds, int sampleRate) {
        if (tauSeconds <= 0 || sampleRate <= 0) {
            return 1.0;
        }
        return 1.0 - Math.exp(-1.0 / (tauSeconds * sampleRate));
    }

    /** Peak follow: a new peak is followed immediately, otherwise decays by {@link #PEAK_TAU_S}. */
    public static double followPeak(double peak, int magnitude) {
        final double decayed = peak * (1.0 - onePole(PEAK_TAU_S, SAMPLE_RATE));
        return Math.max(magnitude, decayed);
    }

    /**
     * Per-sample gain update: the target gain comes from the peak follower, and the gain moves
     * toward it with "fast attack, slow release".
     *
     * <p>A peak below {@link #FLOOR} counts as silence/very quiet and keeps the current gain
     * (otherwise the noise floor would be amplified all the way up).
     */
    public static double stepGain(double currentGain, double peak) {
        if (peak <= FLOOR) {
            return currentGain;
        }
        final double desired = Math.min(MAX_GAIN, TARGET_PEAK / peak);
        final double coefficient = desired < currentGain
                ? onePole(ATTACK_TAU_S, SAMPLE_RATE)
                : onePole(RELEASE_TAU_S, SAMPLE_RATE);
        return currentGain + (desired - currentGain) * coefficient;
    }

    /** Below this peak the gain is left alone (the silence/very-quiet noise floor must not be lifted). */
    public static final int FLOOR = 64;

    private AudioLevels() {
    }

    /** Scale one int16 sample by the gain, with limiting. */
    public static int applyGain(int sample, double gain) {
        final long scaled = Math.round(sample * gain);
        if (scaled > 32767) {
            return 32767;
        }
        if (scaled < -32768) {
            return -32768;
        }
        return (int) scaled;
    }

    /**
     * What to set the media volume to before capture: outside the safe band it returns the target,
     * already inside the band it returns -1 (do not touch the user's volume).
     *
     * @param current current media volume
     * @param max     upper end of this device's volume scale (15 on some models, 150 on others)
     */
    public static int safeCaptureVolume(int current, int max) {
        if (max <= 0 || current < 0) {
            return -1;
        }
        final int target = Math.max(1, (int) Math.floor(max * SAFE_VOLUME_RATIO));
        final double ratio = (double) current / max;
        if (ratio >= SAFE_VOLUME_LOW && ratio <= SAFE_VOLUME_HIGH) {
            return -1;      /* inside the band: leave the user's setting alone */
        }
        return target;      /* too low or too high: pull it to the calibration value */
    }

    /** Whether this sample magnitude (absolute value, 0..32768) counts as clipped. */
    public static boolean clipped(int magnitude) {
        return magnitude >= CLIP_THRESHOLD;
    }

    /** The peak magnitude and clipped count seen in one read; a plain struct, convenient for host assertions. */
    public static int[] scan(short[] samples, int length, int startPeak, int startClipped) {
        int peak = startPeak;
        int clipped = startClipped;
        for (int i = 0; i < length; i++) {
            final int sample = samples[i];
            if (sample == 0) {
                continue;
            }
            final int magnitude = sample == Short.MIN_VALUE ? FULL_SCALE : Math.abs(sample);
            if (magnitude > peak) {
                peak = magnitude;
            }
            if (clipped(magnitude)) {
                clipped++;
            }
        }
        return new int[] {peak, clipped};
    }
}
