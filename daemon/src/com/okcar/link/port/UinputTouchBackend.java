package com.okcar.link.port;

import android.util.Log;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.MotionEvent;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Normal-phone {@link EventInjectBackend}: drives the okcar-uinputd root daemon
 * (the desktop uinputd harness) over its stdin protocol instead of the hidden InputManager.
 * Verified live on 217: the virtual touch device registers with INPUT_PROP_DIRECT
 * and the injected slot events arrive intact.
 *
 * <p>Key translation: {@code KeyEvent} carries Android keycodes while uinput
 * wants Linux {@code KEY_*} codes; the table below covers the keys the reverse
 * control channel actually sends. Unknown keys are refused, never guessed.
 *
 * <p>The ABS range this device declares is the panel's <em>unrotated</em> size, and every injected
 * point is converted into that space first — see {@link #rawPoint} and
 * {@link com.okcar.link.input.TouchGeometry}.  Getting either half wrong rotates car touches a
 * second time, which is invisible while the phone is portrait and glaring the moment it turns
 * landscape.
 */
public final class UinputTouchBackend implements EventInjectBackend, java.io.Closeable {

    private static final int[][] KEYCODES = {
            /* Android keycode, Linux KEY_* */
            {KeyEvent.KEYCODE_BACK, 158},
            {KeyEvent.KEYCODE_HOME, 172},
            {KeyEvent.KEYCODE_MENU, 139},
            {KeyEvent.KEYCODE_SEARCH, 217},
            {KeyEvent.KEYCODE_DPAD_UP, 103},
            {KeyEvent.KEYCODE_DPAD_DOWN, 108},
            {KeyEvent.KEYCODE_DPAD_LEFT, 105},
            {KeyEvent.KEYCODE_DPAD_RIGHT, 106},
            {KeyEvent.KEYCODE_DPAD_CENTER, 353},
            {KeyEvent.KEYCODE_ENTER, 28},
            {KeyEvent.KEYCODE_ESCAPE, 1},
            {KeyEvent.KEYCODE_VOLUME_UP, 115},
            {KeyEvent.KEYCODE_VOLUME_DOWN, 114},
            {KeyEvent.KEYCODE_POWER, 116},
            {KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 164},
            {KeyEvent.KEYCODE_MEDIA_STOP, 166},
            {KeyEvent.KEYCODE_MEDIA_NEXT, 163},
            {KeyEvent.KEYCODE_MEDIA_PREVIOUS, 165},
    };

    private final String mBinaryPath;
    private final int mWidth;
    private final int mHeight;
    private Process mProcess;
    private OutputStream mOut;

    /**
     * @param binaryPath device path of okcar-uinputd (module installs it under
     *                   /system/bin; /data/local/tmp works for bring-up)
     * @param width      display width in pixels (ABS range)
     * @param height     display height in pixels
     */
    private static final String TAG = "OkCarLink/Uinput";

    /** Where the helper's stdout/stderr go; it is the only record of why it refuses to run. */
    public static final String HELPER_LOG = "/data/local/tmp/okcar-uinputd.log";
    private boolean mWarnedAboutHelper;

    public UinputTouchBackend(String binaryPath, int width, int height) {
        mBinaryPath = binaryPath;
        mWidth = width;
        mHeight = height;
    }

    @Override
    public synchronized boolean injectInputEvent(InputEvent event) {
        if (event instanceof MotionEvent) {
            return injectTouch((MotionEvent) event);
        }
        if (event instanceof KeyEvent) {
            return injectKey((KeyEvent) event);
        }
        return false;
    }

    private boolean injectTouch(MotionEvent event) {
        final int action = event.getActionMasked();
        final StringBuilder line = new StringBuilder(32);
        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                final int i = event.getActionIndex();
                final int[] raw = rawPoint(event.getX(i), event.getY(i));
                line.append("D ").append(event.getPointerId(i)).append(' ')
                        .append(raw[0]).append(' ').append(raw[1]);
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                // uinputd takes one slot per line; send every moved pointer.
                boolean ok = true;
                for (int i = 0; i < event.getPointerCount(); i++) {
                    final int[] raw = rawPoint(event.getX(i), event.getY(i));
                    ok &= writeLine("M " + event.getPointerId(i) + ' '
                            + raw[0] + ' ' + raw[1]);
                }
                return ok;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP: {
                line.append("U ").append(event.getPointerId(event.getActionIndex()));
                break;
            }
            default:
                return false;
        }
        return writeLine(line.toString());
    }

    /**
     * One logical point as the kernel device needs it: the display's rotation undone once, then
     * clamped to this device's {@code ABS_MT_POSITION_*} range.
     *
     * <p>These are not the same space.  The caller hands over the display's <em>current logical</em>
     * coordinates (the space the car's taps were scaled into, e.g. {@code 2400x1080} on a landscape
     * head unit), while the device was created with the panel's <em>unrotated</em> range
     * ({@code 1080x2400}) and the input stack rotates that range into the display for us.  Writing
     * logical coordinates straight in therefore rotates twice, and the tap arrives at a point
     * reflected across the panel — measured: a car tap at {@code (104,858)} reached the app at
     * {@code (858,976)}, and in portrait, where the rotation is the identity, the same code was
     * correct.  See {@link com.okcar.link.input.TouchGeometry}.
     */
    private int[] rawPoint(float logicalX, float logicalY) {
        final int[] raw = com.okcar.link.input.TouchGeometry.devicePoint(
                Ports.displayRotation(), mWidth, mHeight,
                (int) logicalX, (int) logicalY);
        raw[0] = clamp(raw[0], mWidth);
        raw[1] = clamp(raw[1], mHeight);
        return raw;
    }

    private boolean injectKey(KeyEvent event) {
        final int linux = linuxKeycode(event.getKeyCode());
        if (linux < 0) {
            return false;
        }
        final int down = event.getAction() == KeyEvent.ACTION_DOWN ? 1 : 0;
        return writeLine("K " + linux + ' ' + down);
    }

    private static int linuxKeycode(int androidKeycode) {
        for (int[] pair : KEYCODES) {
            if (pair[0] == androidKeycode) {
                return pair[1];
            }
        }
        return -1;
    }

    private static int clamp(int value, int max) {
        return value < 0 ? 0 : Math.min(value, max - 1);
    }

    private boolean writeLine(String line) {
        if (!ensure()) {
            return false;
        }
        try {
            mOut.write((line + "\n").getBytes("UTF-8"));
            mOut.flush();
            return true;
        } catch (IOException e) {
            Log.w(TAG, "uinput write failed (" + e + ") — the helper is gone; see " + HELPER_LOG);
            close();
            return false;
        }
    }

    private boolean ensure() {
        if (mProcess != null) {
            if (mWarnedAboutHelper) {
                return true;
            }
            /* The helper's output used to be pipes nobody read, so when it died (no /dev/uinput,
             * SELinux, missing binary) every write still went into a dead pipe and returned true:
             * the car's taps silently did nothing (measured 2026-10-03 on Android 17).  Remember
             * the exit status and log the first failure instead. */
            try {
                final int exit = mProcess.exitValue();
                mProcess = null;
                mOut = null;
                Log.w(TAG, "uinput helper exited with rc=" + exit
                        + " — see " + HELPER_LOG);
                mWarnedAboutHelper = true;
            } catch (IllegalThreadStateException alive) {
                return true;
            }
        }
        try {
            /* A root shell here may have no /system/bin on PATH (measured), and the helper's own
             * complaints have to survive the spawn, so give it an explicit PATH and a log file. */
            mProcess = Runtime.getRuntime().exec(new String[]{"su", "-c",
                    "PATH=/system/bin:/system/xbin:/vendor/bin:$PATH exec " + mBinaryPath
                            + " --width " + mWidth + " --height " + mHeight
                            + " >> " + HELPER_LOG + " 2>&1"});
            mOut = mProcess.getOutputStream();
            mWarnedAboutHelper = false;
            Log.i(TAG, "uinput helper started: " + mBinaryPath
                    + " " + mWidth + "x" + mHeight + " (log " + HELPER_LOG + ")");
            return true;
        } catch (IOException e) {
            Log.w(TAG, "uinput helper could not be spawned: " + e);
            mProcess = null;
            return false;
        }
    }

    /** Stops the daemon; safe to call repeatedly. */
    public synchronized void close() {
        if (mProcess != null) {
            try {
                mOut.write("Q\n".getBytes("UTF-8"));
                mOut.flush();
            } catch (IOException ignored) {
                // best effort
            }
            mProcess.destroy();
            mProcess = null;
            mOut = null;
        }
    }
}
