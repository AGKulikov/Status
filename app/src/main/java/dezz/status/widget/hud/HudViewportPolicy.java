/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.hud;

import androidx.annotation.NonNull;

/**
 * Fixed OEM HUD planes on the supported ECARX head unit.
 *
 * <p>The editable widget plane remains the exact 728x190 area used by mHUD 6.1. A supplied
 * SurfaceFlinger/window dump also shows that the stock {@code com.ecarx.hud/.hud.HomeActivity}
 * surface is cropped to {@code [0,720]-[808,986]} on display 2. The latter is used only by the
 * optional opaque mask: widgets can never escape the smaller safe plane.</p>
 */
public final class HudViewportPolicy {
    public static final int VERIFIED_DISPLAY_ID = 2;
    /**
     * SurfaceFlinger stack owned by {@link #VERIFIED_DISPLAY_ID} on the supplied ECARX unit.
     *
     * <p>Both {@code dumpsys display} and {@code dumpsys window displays} report
     * {@code local:2 -> layerStack 2}. Hidden-API access to
     * {@code Display#getLayerStack()} is blocked on this Android 9 build, so the bridge needs
     * this dump-verified fallback.</p>
     */
    public static final int VERIFIED_LAYER_STACK = 2;
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

    /** Dump-verified visible footprint of the stock car and speed HomeActivity on display 2. */
    public static final int STOCK_MASK_LEFT = 0;
    public static final int STOCK_MASK_TOP = 720;
    public static final int STOCK_MASK_WIDTH = 808;
    public static final int STOCK_MASK_HEIGHT = 266;
    public static final int STOCK_MASK_RIGHT = STOCK_MASK_LEFT + STOCK_MASK_WIDTH;
    public static final int STOCK_MASK_BOTTOM = STOCK_MASK_TOP + STOCK_MASK_HEIGHT;

    public static final int MIN_SURFACE_WIDTH = Math.max(SAFE_RIGHT, STOCK_MASK_RIGHT);
    public static final int MIN_SURFACE_HEIGHT = Math.max(SAFE_BOTTOM, STOCK_MASK_BOTTOM);

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

    /** Returns the stock-HUD mask intersection without widening the editable widget plane. */
    @NonNull
    public static Bounds clipStockMaskToSurface(int surfaceWidth, int surfaceHeight) {
        int right = Math.max(0, Math.min(STOCK_MASK_RIGHT, surfaceWidth));
        int bottom = Math.max(0, Math.min(STOCK_MASK_BOTTOM, surfaceHeight));
        int left = Math.max(0, Math.min(STOCK_MASK_LEFT, right));
        int top = Math.max(0, Math.min(STOCK_MASK_TOP, bottom));
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
