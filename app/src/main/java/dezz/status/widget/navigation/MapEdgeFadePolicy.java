/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.navigation;

/** Physical-pixel edge geometry shared by the GPU mask and its regression tests. */
public final class MapEdgeFadePolicy {
    private MapEdgeFadePolicy() {}

    public static boolean enabled(boolean enabled, float width, float height, int size, int strength) {
        return enabled && width > 0f && height > 0f && size > 0 && strength > 0;
    }

    public static float[] stops(float extent, int size) {
        // Keep a (possibly very small) sharp centre even when the selected band exceeds half size.
        float fraction = extent <= 0f ? 0f : Math.min(.4999f, Math.max(0, size) / extent);
        return new float[]{0f, fraction, 1f - fraction, 1f};
    }

    public static int edgeAlpha(int strength) {
        return Math.round(255f * (1f - Math.max(0, Math.min(100, strength)) / 100f));
    }
}
