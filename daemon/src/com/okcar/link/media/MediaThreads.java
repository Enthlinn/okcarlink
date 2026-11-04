package com.okcar.link.media;

import android.util.Log;

/**
 * Applies the {@link MediaPriority} policy to real threads (the only place that touches the
 * Android API; the policy itself stays pure Java).
 *
 * <p>Failure never throws: setting the priority is only a bonus that "makes the picture steadier".
 * It fails without {@code CAP_SYS_NICE} (plain app identity); in that case we keep running as usual
 * and log once — the module runs as root, and in the field it was measured to work.
 */
public final class MediaThreads {

    private static final String TAG = "OkCarLink/MediaPriority";

    private static volatile boolean sReportedFailure;

    private MediaThreads() {
    }

    /** Raise the priority to the policy value for this thread name; silently return when it need not change or cannot be set. */
    public static void raise(String threadName) {
        final int priority = MediaPriority.forThread(threadName);
        if (!MediaPriority.needsRaising(priority)) {
            return;
        }
        try {
            android.os.Process.setThreadPriority(priority);
        } catch (Throwable t) {
            if (!sReportedFailure) {
                sReportedFailure = true;
                Log.w(TAG, "cannot raise " + threadName + " to " + priority + ": " + t
                        + " (cast continues at default priority)");
            }
        }
    }
}
