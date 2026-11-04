/*
 * OkCarLink — mirror ONE specific display into an encoder input surface (2026-10-08).
 *
 * Why this exists: the wired ICCOA (AOA) attach makes CarWith enter a real cast session and create
 * its own cast virtual display (measured: owner com.miui.carlink, 1920x864, displayId 14).  Two
 * other ways of getting that picture were measured and do NOT work:
 *
 *   - the CarLife-slot surface handoff (`prepareCast` with our encoder input surface) is accepted by
 *     CarWith's binder 17/17 times but never routed into `CarlinkStateMachine`, so `onCastPrepared`
 *     never arrives;
 *   - `screencap -d 14` cannot see the display at all: `dumpsys SurfaceFlinger --display-id` lists
 *     only the physical HWC display, and any `-d <virtual id>` fails with `Status: -2`.
 *
 * What is left is display mirroring: `VirtualDisplayConfig.setDisplayIdToMirror(id)` plus our
 * encoder surface.  It is called reflectively because this payload is compiled against the Android
 * 12 framework classpath while it runs on Android 15 (where `VirtualDisplayConfig` — public API 34
 * — exists).  Reflection keeps one dex valid on both.
 *
 * Permission note: this needs CAPTURE_VIDEO_OUTPUT (and CAPTURE_SECURE_VIDEO_OUTPUT when the source
 * display is secure).  The daemon runs as uid 0, which holds both; a plain app uid would not.
 */
package com.okcar.link.port;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.util.Log;
import android.view.Display;
import android.view.Surface;

public final class DisplayMirror {

    private static final String TAG = "OkCarLink/DisplayMirror";

    /** android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC */
    private static final int FLAG_PUBLIC = 1 << 0;
    /**
     * Measured 2026-10-08 on 217: {@code setDisplayIdToMirror} only works when AUTO_MIRROR is NOT
     * set.  With {@code AUTO_MIRROR|PUBLIC} (24), {@code AUTO_MIRROR} alone (8) or no flags (0) the
     * display is created but produces zero buffers; with {@code PUBLIC} alone the encoder got frames
     * immediately (16 frames / 3 key frames / 101 KB from CarWith's cast display).  AUTO_MIRROR pins
     * the mirror to the default display, which is not what {@code setDisplayIdToMirror} needs.
     */
    private static final int DEFAULT_FLAGS = FLAG_PUBLIC;

    private DisplayMirror() {
    }

    /** The package that owns the cast display we mirror on the wired ICCOA path. */
    public static final String CARWITH_PACKAGE = "com.miui.carlink";

    /**
     * Sentinel for {@code setMirrorSourceDisplay}: resolve CarWith's cast display at every start.
     *
     * <p>CarWith creates a NEW virtual display for every wired session, so the id changes
     * (measured 2026-10-08: 14 → 22 → 29 → 9 → 14).  A pinned id therefore silently goes stale and
     * the cast degrades to something else — always prefer {@link #AUTO}.
     */
    public static final int AUTO = -2;

    /**
     * Finds the display CarWith created for its own cast.
     *
     * <p>The matched display reports {@code com.miui.carlink} as its name (the same string
     * {@code dumpsys display} calls it); the owner package is checked as a fallback because the name
     * is not contractual.  CarWith also creates a small {@code com.xiaomi.ucar.minimap} display, so
     * the largest match wins.
     *
     * @return the display id, or -1 when CarWith has no cast display right now
     */
    public static int findCarwithCastDisplay(Context context) {
        if (context == null) {
            return -1;
        }
        final DisplayManager manager =
                (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        if (manager == null) {
            return -1;
        }
        int best = -1;
        int bestArea = 0;
        try {
            for (Display display : manager.getDisplays()) {
                if (display == null || display.getDisplayId() == Display.DEFAULT_DISPLAY) {
                    continue;
                }
                final String name = display.getName();
                boolean match = CARWITH_PACKAGE.equals(name);
                if (!match) {
                    final String owner = ownerPackage(display);
                    match = CARWITH_PACKAGE.equals(owner)
                            && !"com.xiaomi.ucar.minimap".equals(name);
                }
                if (!match) {
                    continue;
                }
                final int[] size = sizeOf(context, display.getDisplayId());
                final int area = size == null ? 0 : size[0] * size[1];
                if (area > bestArea) {
                    bestArea = area;
                    best = display.getDisplayId();
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "scanning for CarWith's cast display failed: " + t);
        }
        if (best >= 0) {
            Log.i(TAG, "CarWith cast display resolved: displayId=" + best);
        }
        return best;
    }

    /** @return {width, height} of a display, or null when it cannot be resolved. */
    public static int[] sizeOf(Context context, int displayId) {
        if (context == null) {
            return null;
        }
        try {
            final DisplayManager manager =
                    (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
            final Display display = manager == null ? null : manager.getDisplay(displayId);
            if (display == null) {
                return null;
            }
            final android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
            display.getRealMetrics(metrics);
            if (metrics.widthPixels > 0 && metrics.heightPixels > 0) {
                return new int[] { metrics.widthPixels, metrics.heightPixels };
            }
        } catch (Throwable t) {
            Log.w(TAG, "size of display " + displayId + " failed: " + t);
        }
        return null;
    }

    /** {@code Display.getOwnerPackageName()} is @hide; reflection keeps this dex valid on A12. */
    private static String ownerPackage(Display display) {
        try {
            final Object owner = Display.class.getMethod("getOwnerPackageName").invoke(display);
            return owner == null ? null : String.valueOf(owner);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * @return the new virtual display (already mirroring {@code sourceDisplayId} onto
     *     {@code surface}), or null with the reason logged — never throws
     */
    public static VirtualDisplay create(Context context, int sourceDisplayId, String name,
                                        int width, int height, Surface surface, int flags) {
        if (context == null) {
            Log.w(TAG, "no context");
            return null;
        }
        final int effectiveFlags = flags >= 0 ? flags : DEFAULT_FLAGS;
        final DisplayManager manager =
                (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        if (manager == null) {
            Log.w(TAG, "no DisplayManager");
            return null;
        }
        int density = 0;
        final Display source = manager.getDisplay(sourceDisplayId);
        if (source != null) {
            final android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
            source.getMetrics(metrics);
            density = metrics.densityDpi;
        }
        if (density <= 0) {
            density = 160;
        }
        try {
            final Class<?> configClass = Class.forName("android.hardware.display.VirtualDisplayConfig");
            final Class<?> builderClass =
                    Class.forName("android.hardware.display.VirtualDisplayConfig$Builder");
            final Object builder = builderClass
                    .getConstructor(String.class, int.class, int.class, int.class)
                    .newInstance(name, width, height, density);
            builderClass.getMethod("setSurface", Surface.class).invoke(builder, surface);
            builderClass.getMethod("setDisplayIdToMirror", int.class).invoke(builder, sourceDisplayId);
            builderClass.getMethod("setFlags", int.class).invoke(builder, effectiveFlags);
            final Object config = builderClass.getMethod("build").invoke(builder);
            final Object created = DisplayManager.class
                    .getMethod("createVirtualDisplay", configClass).invoke(manager, config);
            if (created == null) {
                Log.w(TAG, "createVirtualDisplay(setDisplayIdToMirror=" + sourceDisplayId
                        + ", flags=" + effectiveFlags + ") returned null");
                return null;
            }
            Log.i(TAG, "mirroring display " + sourceDisplayId + " (flags=" + effectiveFlags + ") -> "
                    + width + 'x' + height + " dpi=" + density + " into the encoder surface");
            return (VirtualDisplay) created;
        } catch (Throwable t) {
            Log.w(TAG, "mirroring display " + sourceDisplayId + " failed (needs API 34"
                    + " VirtualDisplayConfig and CAPTURE_VIDEO_OUTPUT): " + t);
            return null;
        }
    }
}
