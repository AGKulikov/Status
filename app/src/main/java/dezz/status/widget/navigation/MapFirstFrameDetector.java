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

    /**
     * Content qualification is necessary, but not a substitute for the producer's map-ready ACK.
     * Call only from a posted task, never inline from onSurfaceTextureUpdated: Android 9 delivers
     * that callback inside TextureView.draw(), where getBitmap() is explicitly unsupported.
     */
    public static boolean hasRenderableContent(@NonNull TextureView texture) {
        return readback(texture, null) == Readback.CONTENT;
    }

    private enum Readback {
        CONTENT, NO_SURFACE, NO_BITMAP, EMPTY_BITMAP, REJECTED_PIXELS, COPY_FAILED
    }

    private static Readback readback(@NonNull TextureView texture, int[] summary) {
        if (!texture.isAvailable()) return Readback.NO_SURFACE;
        Bitmap sample = null;
        try {
            sample = texture.getBitmap(SAMPLE_WIDTH, SAMPLE_HEIGHT);
            if (sample == null || sample.isRecycled()) return Readback.NO_BITMAP;
            int width = sample.getWidth();
            int height = sample.getHeight();
            if (width <= 0 || height <= 0) return Readback.EMPTY_BITMAP;
            int[] pixels = new int[width * height];
            sample.getPixels(pixels, 0, width, 0, 0, width, height);
            return hasRenderableContent(pixels, pixels.length, summary)
                    ? Readback.CONTENT : Readback.REJECTED_PIXELS;
        } catch (RuntimeException | OutOfMemoryError ignored) {
            // Fail closed: exposing an unreadable first buffer would bring the white flash back.
            return Readback.COPY_FAILED;
        } finally {
            if (sample != null && !sample.isRecycled()) sample.recycle();
        }
    }

    /** Pure pixel policy kept package-visible for a local JVM regression test. */
    static boolean hasRenderableContent(@NonNull int[] pixels, int count) {
        return hasRenderableContent(pixels, count, null);
    }

    private static boolean hasRenderableContent(@NonNull int[] pixels, int count, int[] summary) {
        int boundedCount = Math.max(0, Math.min(count, pixels.length));
        if (boundedCount == 0) return false;
        int visible = 0, edges = 0, colored = 0, nonzeroAlpha = 0;
        int minimumLuma = 255, maximumLuma = 0, previous = -1;
        for (int index = 0; index < boundedCount; index++) {
            int pixel = pixels[index];
            int alpha = pixel >>> 24;
            if (alpha > 0) nonzeroAlpha++;
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
        if (summary != null) {
            summary[0] = nonzeroAlpha;
            summary[1] = visible;
            summary[2] = edges;
            summary[3] = colored;
            summary[4] = minimumLuma;
            summary[5] = maximumLuma;
        }
        int evidence = Math.max(3, boundedCount / 100);
        return visible >= evidence && maximumLuma - minimumLuma >= 18
                && (edges >= evidence || colored >= evidence);
    }

    /** Recreated surfaces require their own ACK and consecutive content-bearing buffers. */
    public static final class Gate {
        private int consecutive;
        private String lastResult = "reset";
        private final int[] sampleSummary = new int[6];
        public void reset() { consecutive = 0; lastResult = "reset"; }
        public boolean accept(boolean producerReady, @NonNull TextureView texture) {
            if (!producerReady) return acceptSample(false, false);
            Readback sample = readback(texture, sampleSummary);
            boolean accepted = acceptSample(true, sample == Readback.CONTENT);
            lastResult = sample.name();
            return accepted;
        }
        boolean acceptSample(boolean producerReady, boolean content) {
            consecutive = producerReady && content ? consecutive + 1 : 0;
            lastResult = !producerReady ? "awaiting-producer"
                    : !content ? "no-qualified-pixels" : "content";
            return consecutive >= 3;
        }
        public String diagnosticState() {
            String state = "result=" + lastResult + ", consecutive=" + consecutive;
            if ("CONTENT".equals(lastResult) || "REJECTED_PIXELS".equals(lastResult)) {
                state += ", alphaPixels=" + sampleSummary[0] + ", opaquePixels=" + sampleSummary[1]
                        + ", edges=" + sampleSummary[2] + ", colored=" + sampleSummary[3]
                        + ", luma=" + sampleSummary[4] + ".." + sampleSummary[5];
            }
            return state;
        }
    }

    /**
     * One posted readback per traversal. A copy can itself deliver a TextureView update, so
     * callbacks raised during the check must not create a self-sustaining readback loop.
     * Each owner cancels pending work when its Surface lease is replaced or revoked.
     */
    public static final class DeferredCheck {
        public interface Queue {
            boolean post(Runnable task);
            void remove(Runnable task);
        }
        private final Queue queue;
        private final Runnable check;
        private Runnable pending;
        private boolean checking;

        public DeferredCheck(Queue queue, Runnable check) {
            this.queue = queue;
            this.check = check;
        }

        public void onFrame() {
            if (pending != null || checking) return;
            Runnable task = new Runnable() {
                @Override public void run() {
                    // A removed callback can already be in a queue snapshot. It must neither
                    // inspect a replacement Surface nor clear that Surface's newer task.
                    if (pending != this) return;
                    pending = null;
                    checking = true;
                    try { check.run(); }
                    finally { checking = false; }
                }
            };
            pending = task;
            if (!queue.post(task) && pending == task) pending = null;
        }

        public void cancel() {
            Runnable task = pending;
            pending = null;
            if (task != null) queue.remove(task);
        }
    }
}
