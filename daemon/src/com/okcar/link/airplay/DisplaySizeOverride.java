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

import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.WindowManager;

import com.okcar.link.LinkState;

import java.lang.reflect.Method;

/**
 * Makes the phone render in the aspect ratio of the car display for the duration of a wireless
 * session, then puts the previous size back.
 *
 * <p>Why: this ROM runs car mode with a forced logical size of 1920x720 on a 1080x2400 panel, and
 * the reference accessory (like the panel) is 2400x1080.  A mirror taken at 1920x720 therefore lands as
 * letterboxed black bars inside the 2400x1080 video — visible on the car *and* on the phone — and
 * every touch coordinate is offset by exactly those bars.  Rendering the phone at the car's size
 * removes the bars and leaves the touch mapping a plain scale.
 *
 * <p>{@code setForcedDisplaySize} is the same call `wm size` makes; it needs
 * {@code WRITE_SECURE_SETTINGS}, which a platform-signed privileged app already holds.
 */
final class DisplaySizeOverride {

    private static final String TAG = "okcar-wireless-display";

    private final LinkState mState = LinkState.get();

    /** Natural (unrotated) size to restore, or 0 when nothing is overridden. */
    private int mRestoreWidth;
    private int mRestoreHeight;
    private boolean mApplied;

    /**
     * Forces the display to the accessory's size so the mirrored content fills it.
     *
     * @param accessoryWidth  the accessory's displayed width (its {@code /info} displays[0])
     * @param accessoryHeight the accessory's displayed height
     * @return true when the override is in place
     */
    boolean apply(WindowManager windowManager, int accessoryWidth, int accessoryHeight) {
        if (mApplied) {
            return true;
        }
        if (accessoryWidth <= 0 || accessoryHeight <= 0) {
            return false;
        }
        try {
            final Point current = currentSize(windowManager);
            if (current == null) {
                return false;
            }
            /* The override is expressed in the display's natural orientation: car mode rotates it,
             * so 1920x720 logical is stored as 720x1920.  Keep that convention. */
            mRestoreWidth = Math.min(current.x, current.y);
            mRestoreHeight = Math.max(current.x, current.y);
            final int naturalWidth = Math.min(accessoryWidth, accessoryHeight);
            final int naturalHeight = Math.max(accessoryWidth, accessoryHeight);
            if (naturalWidth == mRestoreWidth && naturalHeight == mRestoreHeight) {
                mState.log(TAG, "display already matches the accessory (" + accessoryWidth + 'x'
                        + accessoryHeight + ") — nothing to override");
                return true;
            }
            if (!setForcedDisplaySize(0, naturalWidth, naturalHeight)) {
                return false;
            }
            mApplied = true;
            mState.log(TAG, "display forced to " + accessoryWidth + 'x' + accessoryHeight
                    + " for this session (was " + current.x + 'x' + current.y
                    + ", will restore to " + mRestoreWidth + 'x' + mRestoreHeight + ')');
            return true;
        } catch (Throwable t) {
            mState.log(TAG, "cannot match the display to the accessory: " + t);
            return false;
        }
    }

    /** Puts the previous size back; safe to call when nothing was applied. */
    void restore() {
        if (!mApplied) {
            return;
        }
        mApplied = false;
        try {
            if (setForcedDisplaySize(0, mRestoreWidth, mRestoreHeight)) {
                mState.log(TAG, "display restored to " + mRestoreWidth + 'x' + mRestoreHeight);
            }
        } catch (Throwable t) {
            mState.log(TAG, "cannot restore the display size: " + t);
        }
    }

    private static Point currentSize(WindowManager windowManager) {
        if (windowManager == null) {
            return null;
        }
        final Display display = windowManager.getDefaultDisplay();
        if (display == null) {
            return null;
        }
        final Point size = new Point();
        display.getRealSize(size);
        return size.x > 0 && size.y > 0 ? size : null;
    }

    /**
     * {@code IWindowManager.setForcedDisplaySize(int displayId, int width, int height)} — the
     * hidden RPC behind `wm size`, reached through WindowManagerGlobal.  A port build can
     * replace the privileged call entirely via {@link com.okcar.link.port.Ports}.
     */
    private boolean setForcedDisplaySize(int displayId, int width, int height) throws Exception {
        final com.okcar.link.port.DisplaySizeBackend backend =
                com.okcar.link.port.Ports.displaySizeBackend();
        if (backend != null) {
            return backend.setForcedDisplaySize(displayId, width, height);
        }
        final Class<?> global = Class.forName("android.view.WindowManagerGlobal");
        final Method getService = global.getDeclaredMethod("getWindowManagerService");
        getService.setAccessible(true);
        final Object service = getService.invoke(null);
        if (service == null) {
            mState.log(TAG, "no window manager service");
            return false;
        }
        final Method set = service.getClass().getMethod("setForcedDisplaySize",
                int.class, int.class, int.class);
        set.invoke(service, displayId, width, height);
        return true;
    }
}
