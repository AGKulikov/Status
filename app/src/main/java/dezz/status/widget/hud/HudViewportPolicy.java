/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.hud;

import androidx.annotation.NonNull;

/**
 * Fixed OEM HUD plane used by mHUD 6.1 on the supported head unit.
 *
 * <p>The mHUD editor is a 1760x720 preview. Before a widget is sent to its external
 * {@code Presentation}, mHUD maps it to a 728x190 coordinate plane and adds a fixed 720 pixel
 * vertical offset. The total external display size is supplied by Android at runtime; it is not
 * hard-coded in the reference APK. Consequently 728x910 is the minimum surface which can contain
 * the complete HUD plane, while the only area this app is allowed to touch is
 * {@code [0,720]-[728,910]}.</p>
 */
public final class HudViewportPolicy {
    public static final int VERIFIED_DISPLAY_ID = 2;
    public static final int VERIFIED_DISPLAY_WIDTH = 1920;
    public static final int VERIFIED_DISPLAY_HEIGHT = 1080;
    public static final int REFERENCE_EDITOR_WIDTH = 1760;
    public static final int REFERENCE_EDITOR_HEIGHT = 720;
    public static final int REFERENCE_EDITOR_HUD_TOP = 130;
    public static final int REFERENCE_EDITOR_HUD_HEIGHT = 460;

    public static final int SAFE_LEFT = 0;
    public static final int SAFE_TOP = 720;
    public static final int SAFE_WIDTH = 728;
    public static final int SAFE_HEIGHT = 190;
    public static final int SAFE_RIGHT = SAFE_LEFT + SAFE_WIDTH;
    public static final int SAFE_BOTTOM = SAFE_TOP + SAFE_HEIGHT;

    public static final int MIN_SURFACE_WIDTH = SAFE_RIGHT;
    public static final int MIN_SURFACE_HEIGHT = SAFE_BOTTOM;

    private HudViewportPolicy() {}

    public static boolean containsCompleteHudPlane(int surfaceWidth, int surfaceHeight) {
        return surfaceWidth >= MIN_SURFACE_WIDTH && surfaceHeight >= MIN_SURFACE_HEIGHT;
    }

    /** Returns the intersection of the fixed HUD plane and the actual Presentation canvas. */
    @NonNull
    public static Bounds clipToSurface(int surfaceWidth, int surfaceHeight) {
        int right = Math.max(0, Math.min(SAFE_RIGHT, surfaceWidth));
        int bottom = Math.max(0, Math.min(SAFE_BOTTOM, surfaceHeight));
        int left = Math.max(0, Math.min(SAFE_LEFT, right));
        int top = Math.max(0, Math.min(SAFE_TOP, bottom));
        return new Bounds(left, top, right, bottom);
    }

    public static final class Bounds {
        public final int left;
        public final int top;
        public final int right;
        public final int bottom;

        Bounds(int left, int top, int right, int bottom) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }

        public int width() { return Math.max(0, right - left); }
        public int height() { return Math.max(0, bottom - top); }
        public boolean isEmpty() { return width() == 0 || height() == 0; }
    }
}
