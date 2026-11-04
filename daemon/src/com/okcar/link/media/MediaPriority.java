package com.okcar.link.media;

/**
 * Media thread priority policy (pure Java, host-testable).
 *
 * <p>Field evidence (2026-10-05): casting itself was already stable, but **during the moment a new
 * app is opened on the phone** the head unit picture stutters noticeably several times. What was
 * measured at that instant was not system saturation (systemui 13–17%, mpv 7–9%, SF 9–12%) but
 * **transient preemption by the launch animation**: our capture/encode/send threads were all at
 * default priority, got pushed aside for a few hundred ms, and the receiver's jitter buffer ran dry.
 *
 * <p>So the threads that really decide whether there is a picture are raised to display level, and
 * audio threads to audio level; the watchdog/keep-alive threads stay at default (they should never
 * compete with rendering for CPU anyway). The values match
 * {@code android.os.Process.THREAD_PRIORITY_*}: {@code URGENT_DISPLAY = -8}, {@code AUDIO = -16}.
 * They are deliberately written as integers so the host regression can pin the policy down on the
 * desktop, and so this class does not depend on the Android framework.
 */
public final class MediaPriority {

    /** Same nice value as {@code Process.THREAD_PRIORITY_URGENT_DISPLAY}. */
    public static final int VIDEO = -8;

    /** Same nice value as {@code Process.THREAD_PRIORITY_AUDIO}. */
    public static final int AUDIO = -16;

    /** Leave alone (watchdog, keep-alive, heartbeat and similar threads). */
    public static final int DEFAULT = 0;

    /** Video capture/encode/send thread name. */
    public static final String THREAD_VIDEO_ENCODE = "okcar-video-encode";

    /** Audio capture thread name. */
    public static final String THREAD_AUDIO_CAPTURE = "okcar-audio-capture";

    /** Wireless audio send thread name. */
    public static final String THREAD_AUDIO_TX = "okcar-wireless-audio-tx";

    private MediaPriority() {
    }

    /**
     * What priority this thread name should get.
     *
     * <p>Deciding by name rather than by call site is so that "a new thread forgot to set its
     * priority" can be caught in regression: an unregistered name returns {@link #DEFAULT}, and the
     * regression asserts every known name one by one.
     */
    public static int forThread(String name) {
        if (name == null) {
            return DEFAULT;
        }
        if (THREAD_VIDEO_ENCODE.equals(name)) {
            return VIDEO;
        }
        if (THREAD_AUDIO_CAPTURE.equals(name) || THREAD_AUDIO_TX.equals(name)) {
            return AUDIO;
        }
        return DEFAULT;
    }

    /** Whether it is worth actually setting: the default priority is not worth an extra syscall (it can also fail, and there is no point logging that). */
    public static boolean needsRaising(int priority) {
        return priority < DEFAULT;
    }
}
