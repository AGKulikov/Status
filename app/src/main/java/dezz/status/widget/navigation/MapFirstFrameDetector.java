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

    private MapFirstFrameDetector() {}

    /** Content qualification is necessary, but not a substitute for the producer's map-ready ACK. */
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
        int visible = 0, edges = 0, colored = 0;
        int minimumLuma = 255, maximumLuma = 0, previous = -1;
        for (int index = 0; index < boundedCount; index++) {
            int pixel = pixels[index];
            int alpha = pixel >>> 24;
            int red = (pixel >>> 16) & 0xFF;
            int green = (pixel >>> 8) & 0xFF;
            int blue = pixel & 0xFF;
            int minimum = Math.min(red, Math.min(green, blue));
            int maximum = Math.max(red, Math.max(green, blue));
            if (alpha < OPAQUE_ALPHA_MIN) {
                if (alpha < 16) minimumLuma = 0;
                previous = -1;
                continue;
            }
            visible++;
            int luma = (red * 54 + green * 183 + blue * 19) >> 8;
            minimumLuma = Math.min(minimumLuma, luma);
            maximumLuma = Math.max(maximumLuma, luma);
            if (maximum - minimum >= 24) colored++;
            if (previous >= 0 && index % SAMPLE_WIDTH != 0
                    && Math.abs(luma - previous) >= 24) edges++;
            previous = luma;
        }
        // Reject transparent, dark/gray uniform and smooth neutral startup gradients as well
        // as white. Sparse opaque roads on a transparent background remain valid content.
        int evidence = Math.max(3, boundedCount / 100);
        return visible >= evidence && maximumLuma - minimumLuma >= 18
                && (edges >= evidence || colored >= evidence);
    }

    /** Recreated surfaces require their own ACK and consecutive content-bearing buffers. */
    public static final class Gate {
        private int consecutive;
        public void reset() { consecutive = 0; }
        public boolean accept(boolean producerReady, @NonNull TextureView texture) {
            return acceptSample(producerReady, producerReady && hasRenderableContent(texture));
        }
        boolean acceptSample(boolean producerReady, boolean content) {
            consecutive = producerReady && content ? consecutive + 1 : 0;
            return consecutive >= 3;
        }
    }
}
