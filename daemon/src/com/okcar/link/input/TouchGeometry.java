package com.okcar.link.input;

/**
 * Coordinate geometry between what a head unit sends and what the kernel's touch device needs.
 *
 * <p>Two different spaces are involved and confusing them is the whole bug this class exists to
 * prevent:
 *
 * <ol>
 *   <li><b>the logical display space</b> — the space the car's coordinates are expressed in after
 *       being scaled to the screen we are streaming ({@code 2400x1080} on a landscape head unit,
 *       {@code 1080x2400} when the phone is portrait).  This is where a tap <em>is</em>;</li>
 *   <li><b>the device space</b> of {@code okcar-uinput-touch} — the raw {@code ABS_MT_POSITION_*}
 *       range, fixed when the device is created and equal to the panel's <em>unrotated</em> size
 *       ({@code 1080x2400}).  This is the space a tap must be <em>written</em> in.</li>
 * </ol>
 *
 * <p>The input stack already rotates the device space into the display's logical space, exactly as
 * it does for the phone's own panel.  A writer that hands it logical coordinates therefore gets
 * that rotation applied a second time.  Measured on 217 (2026-10-02, mirror mode, phone landscape,
 * display {@code cur=2400x1080}, device range {@code X max 1079 / Y max 2399}): writing the
 * receiver's {@code (104,858)} produced the app-visible point {@code (858,976)}, and a requested
 * centre came out at the top-left corner.  In portrait the rotation is the identity, which is why
 * the same code is only wrong once the picture turns landscape.
 *
 * <p>The four cases below are the inverse of that rotation: hand the kernel the raw point.
 * Rotations 0 and 1 are measured on device; 2 and 3 are the same rotation continued and share the
 * handset's convention.
 */
public final class TouchGeometry {

    private TouchGeometry() {
    }

    /**
     * Raw device point for one logical point.
     *
     * @param rotation     {@code android.view.Surface.ROTATION_*} of the display the touch is
     *                     delivered to; anything outside 0..3 is treated as 0
     * @param deviceWidth  the touch device's {@code ABS_MT_POSITION_X} range (panel width)
     * @param deviceHeight the touch device's {@code ABS_MT_POSITION_Y} range (panel height)
     * @param x            logical x in the display's current space
     * @param y            logical y in the display's current space
     * @return {@code {rawX, rawY}}; the caller clamps to the device range
     */
    public static int[] devicePoint(int rotation, int deviceWidth, int deviceHeight, int x, int y) {
        switch (rotation) {
            case 1:
                return new int[] {deviceWidth - y, x};
            case 2:
                return new int[] {deviceWidth - x, deviceHeight - y};
            case 3:
                return new int[] {y, deviceHeight - x};
            case 0:
            default:
                return new int[] {x, y};
        }
    }

    /**
     * Maps one point of the pictic frame the accessory reports in onto the content inside it.
     *
     * <p>The frame is not always the content.  Mirroring a 2400x1080 phone screen into the
     * accessory's 1920x720 frame fits it by scale, leaving 160 px bars on both sides; a touch at the
     * frame's x=200 is therefore the content's x=60, not the 250 a plain stretch would give.  The
     * centre happens to agree, which is why this only ever looked "a little off" — measured on the
     * the production head unit car 2026-10-02 with taps injected on the car itself.
     *
     * <p>App mode changes nothing: the app display and the frame are the same 1920x720, so the fit
     * is 1 and both offsets are 0.
     *
     * @return {@code {x, y}} in the target space, unclamped (the caller clamps to the display)
     */
    public static float[] contentPoint(int sourceWidth, int sourceHeight,
                                       int targetWidth, int targetHeight, float x, float y) {
        if (sourceWidth <= 0 || sourceHeight <= 0 || targetWidth <= 0 || targetHeight <= 0) {
            return new float[] {x, y};
        }
        /* How the content was fitted INTO the frame: scale first, then centre. */
        final float fit = Math.min((float) sourceWidth / targetWidth,
                (float) sourceHeight / targetHeight);
        if (fit <= 0f) {
            return new float[] {x, y};
        }
        final float offsetX = (sourceWidth - targetWidth * fit) / 2f;
        final float offsetY = (sourceHeight - targetHeight * fit) / 2f;
        return new float[] {(x - offsetX) / fit, (y - offsetY) / fit};
    }

    /**
     * The inverse of {@link #devicePoint}: logical point (in the display's current space) for one
     * raw device point.  Used by the harness to prove the pair is a round trip.
     */
    public static int[] logicalPoint(int rotation, int deviceWidth, int deviceHeight,
                                     int rawX, int rawY) {
        switch (rotation) {
            case 1:
                return new int[] {rawY, deviceWidth - rawX};
            case 2:
                return new int[] {deviceWidth - rawX, deviceHeight - rawY};
            case 3:
                return new int[] {deviceHeight - rawY, rawX};
            case 0:
            default:
                return new int[] {rawX, rawY};
        }
    }
}
