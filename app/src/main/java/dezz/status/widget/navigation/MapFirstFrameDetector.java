/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.navigation;

import android.graphics.Bitmap;
import android.view.TextureView;

import androidx.annotation.NonNull;

/**
 * Qualifies the first {@link TextureView} buffer before a secondary-display map is revealed.
 *
 * <p>Android 9 on the KX11 can publish a uniform opaque-white bootstrap buffer and report
 * {@code onSurfaceTextureUpdated()} before MapKit has drawn any map content. The callback alone
 * is therefore not a first-frame acknowledgement. A tiny readback is used only while the map is
 * hidden; normal map frames never pay this cost.</p>
 */
public final class MapFirstFrameDetector {
    static final int SAMPLE_WIDTH = 32;
    static final int SAMPLE_HEIGHT = 18;
    private static final int OPAQUE_ALPHA_MIN = 240;
    private static final int WHITE_CHANNEL_MIN = 248;
    private static final int NEUTRAL_CHANNEL_SPREAD_MAX = 6;
    private static final int WHITE_PERCENT_MIN = 98;

    private MapFirstFrameDetector() {}

    /** Returns true only when the current producer buffer is not the KX11 white bootstrap frame. */
    public static boolean hasRenderableContent(@NonNull TextureView texture) {
        if (!texture.isAvailable()) return false;
        Bitmap sample = null;
        try {
            sample = texture.getBitmap(SAMPLE_WIDTH, SAMPLE_HEIGHT);
            if (sample == null || sample.isRecycled()) return false;
            int width = sample.getWidth();
            int height = sample.getHeight();
            if (width <= 0 || height <= 0) return false;
            int[] pixels = new int[width * height];
            sample.getPixels(pixels, 0, width, 0, 0, width, height);
            return hasRenderableContent(pixels, pixels.length);
        } catch (RuntimeException | OutOfMemoryError ignored) {
            // Fail closed: exposing an unreadable first buffer would bring the white flash back.
            return false;
        } finally {
            if (sample != null && !sample.isRecycled()) sample.recycle();
        }
    }

    /** Pure pixel policy kept package-visible for a local JVM regression test. */
    static boolean hasRenderableContent(@NonNull int[] pixels, int count) {
        int boundedCount = Math.max(0, Math.min(count, pixels.length));
        if (boundedCount == 0) return false;
        int opaqueWhite = 0;
        for (int index = 0; index < boundedCount; index++) {
            int pixel = pixels[index];
            int alpha = pixel >>> 24;
            int red = (pixel >>> 16) & 0xFF;
            int green = (pixel >>> 8) & 0xFF;
            int blue = pixel & 0xFF;
            int minimum = Math.min(red, Math.min(green, blue));
            int maximum = Math.max(red, Math.max(green, blue));
            if (alpha >= OPAQUE_ALPHA_MIN && minimum >= WHITE_CHANNEL_MIN
                    && maximum - minimum <= NEUTRAL_CHANNEL_SPREAD_MAX) {
                opaqueWhite++;
            }
        }
        return (long) opaqueWhite * 100L < (long) boundedCount * WHITE_PERCENT_MIN;
    }
}
