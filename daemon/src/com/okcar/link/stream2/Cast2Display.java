/*
 * OkcarOS link stack — display 2: the public virtual display that hosts one fixed app.
 *
 * Same privileged shape the mirroring stack already measured on the device (see
 * com.okcar.link.port.RootAppDisplayFactory): PUBLIC|PRESENTATION|OWN_CONTENT_ONLY|TRUSTED built by
 * a root process, sized from the head unit's declaration rather than from the phone's panel.
 *
 * It is deliberately a separate class, not a second mode of RootAppDisplayFactory: the base
 * mirroring stack is frozen and its display is its session's one screen.  Display 2 has its own
 * lifetime, its own id file and its own touch policy — see {@link #setTouchTarget()}: it is NOT
 * registered as the car's touch space, because `cast2` has no touch return path.
 */
package com.okcar.link.stream2;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.util.Log;
import android.view.Surface;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

public final class Cast2Display {

    private static final String TAG = "okcar-cast2";
    /** Display name as `dumpsys display` and the module WebUI show it. */
    public static final String DISPLAY_NAME = "OkCarLink-cast2";
    /** Panel height mapped onto a 720dp logical space, the head-unit convention. */
    private static final int REFERENCE_HEIGHT_DP = 720;
    private static final int MIN_DENSITY = 120;
    private static final int MAX_DENSITY = 480;

    private final Context mContext;
    private volatile VirtualDisplay mDisplay;
    private volatile int mDisplayId = -1;
    private volatile int mWidth;
    private volatile int mHeight;
    private volatile int mDensity;
    private volatile String mPlacement = "not created";

    public Cast2Display(Context context) {
        mContext = context;
        /* A daemon that just started owns no display; publishing -1 clears whatever a previous
         * lifetime left behind so a reader cannot pin work to a dead display id. */
        publishDisplayId(-1);
    }

    /**
     * Builds the display, or returns the running one when it already has the requested geometry.
     *
     * <p>There is no rebuild-into-a-different-shape path: display 2 is fixed at one size by
     * configuration, and `cast2 up WxH` on a live display is refused by {@link Cast2Controller}
     * before reaching here.  A release followed by a create is the only way the size changes.
     *
     * @throws IllegalStateException when the platform refuses the display; no fallback display kind
     *     is attempted, because a non-trusted or mirroring display cannot host the app correctly.
     */
    public synchronized VirtualDisplay create(int width, int height, Surface surface) {
        if (mDisplay != null) {
            if (mWidth == width && mHeight == height) {
                if (surface != null) {
                    mDisplay.setSurface(surface);
                }
                mPlacement = "reusing display " + mDisplayId;
                return mDisplay;
            }
            throw new IllegalStateException("display 2 already exists at " + mWidth + 'x' + mHeight
                    + "; release it before asking for " + width + 'x' + height);
        }
        final DisplayManager manager =
                (DisplayManager) mContext.getSystemService(Context.DISPLAY_SERVICE);
        if (manager == null) {
            throw new IllegalStateException("no DisplayManager in this process");
        }
        final int dpi = chooseDensity(height);
        /* OWN_CONTENT_ONLY is what clears the AUTO_MIRROR the platform forces onto every PUBLIC
         * display; without it the encoder sees the phone's screen (or black) instead of display 2's
         * own layer stack.  TRUSTED is what lets the hosted app's own activity starts land here
         * instead of being refused by ActivityTaskSupervisor.  Both are measured facts on this
         * tree, see the RootAppDisplayFactory javadoc. */
        final int flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
                | DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
                | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
                | DisplayManager.VIRTUAL_DISPLAY_FLAG_TRUSTED;
        final VirtualDisplay display;
        try {
            display = manager.createVirtualDisplay(DISPLAY_NAME, width, height, dpi, surface, flags);
        } catch (Throwable t) {
            mPlacement = "createVirtualDisplay failed: " + t;
            throw new IllegalStateException("cannot create display 2: " + t, t);
        }
        if (display == null || display.getDisplay() == null) {
            mPlacement = "createVirtualDisplay returned null";
            throw new IllegalStateException("cannot create display 2: the platform returned null");
        }
        mDisplay = display;
        mWidth = width;
        mHeight = height;
        mDensity = dpi;
        mDisplayId = display.getDisplay().getDisplayId();
        publishDisplayId(mDisplayId);
        mPlacement = "created display " + mDisplayId;
        Log.i(TAG, "display 2 created: id=" + mDisplayId + ' ' + width + 'x' + height + " dpi=" + dpi);
        return display;
    }

    /**
     * Detaches the encoder's surface and keeps the display.
     *
     * <p>The display is the hosted app's home: releasing it would move the app's task onto the
     * phone's screen and the next `cast2 up` would launch a second instance.  Only `cast2 down`
     * releases it.
     */
    public synchronized void detachSurface() {
        if (mDisplay == null) {
            return;
        }
        try {
            mDisplay.setSurface(null);
        } catch (Throwable t) {
            Log.w(TAG, "cannot detach the surface from display " + mDisplayId + ": " + t);
        }
    }

    /** Releases the display and everything published about it; safe to call repeatedly. */
    public synchronized void release() {
        final VirtualDisplay display = mDisplay;
        mDisplay = null;
        mDisplayId = -1;
        mWidth = 0;
        mHeight = 0;
        publishDisplayId(-1);
        mPlacement = "released";
        if (display != null) {
            try {
                display.release();
                Log.i(TAG, "display 2 released");
            } catch (Throwable t) {
                Log.w(TAG, "display 2 release: " + t);
            }
        }
    }

    public boolean isUp() {
        return mDisplay != null;
    }

    public int displayId() {
        return mDisplayId;
    }

    public int width() {
        return mWidth;
    }

    public int height() {
        return mHeight;
    }

    public int density() {
        return mDensity;
    }

    public String placement() {
        return mPlacement;
    }

    /** Publishes display 2's id for out-of-process readers (`-1` = none); never throws. */
    public static void publishDisplayId(int id) {
        Cast2Config.ensureDir();
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(Cast2Config.DISPLAY_ID_FILE);
            out.write(Integer.toString(id).getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (Throwable t) {
            Log.w(TAG, "cannot publish " + Cast2Config.DISPLAY_ID_FILE + ": " + t);
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (Throwable ignored) {
                    /* best effort */
                }
            }
        }
        new File(Cast2Config.DISPLAY_ID_FILE).setReadable(true, false);
    }

    private static int chooseDensity(int height) {
        if (height <= 0) {
            return 160;
        }
        return Math.max(MIN_DENSITY,
                Math.min(MAX_DENSITY, Math.round(160f * height / REFERENCE_HEIGHT_DP)));
    }
}
