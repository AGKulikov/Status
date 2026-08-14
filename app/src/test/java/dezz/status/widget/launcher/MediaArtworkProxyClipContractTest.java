/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.launcher;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** Guards the pixels actually drawn on HOME, not only the hidden MediaPanelView source. */
public final class MediaArtworkProxyClipContractTest {
    @Test public void proxyClipsTheTransformedArtworkDrawableBounds() throws Exception {
        String proxy = source("launcher/LauncherGlobalElementProxyView.java");
        String draw = between(proxy, "private static void drawImageContent(",
                "/**\n     * The live media panel");
        assertTrue(draw.contains("clipMediaArtworkDrawable(canvas, image, drawable, viewport)"));
        assertTrue(draw.indexOf("clipMediaArtworkDrawable")
                < draw.indexOf("canvas.concat(image.getImageMatrix())"));

        String clip = between(proxy, "private static void clipMediaArtworkDrawable(",
                "private static boolean isMediaArtwork(");
        assertTrue(clip.contains("image.getImageMatrix().mapRect(drawn)"));
        assertTrue(clip.contains("drawn.offset(image.getPaddingLeft()"));
        assertTrue(clip.contains("drawn.intersect(viewport)"));
        assertTrue(clip.contains("outline.getRadius()"));
        assertTrue(clip.contains("float radius = Math.min(sourceRadius"));
        assertTrue(clip.contains("clip.addRoundRect(drawn, radius, radius"));
        assertTrue(clip.contains("canvas.clipPath(clip)"));
        assertFalse(clip.contains("Bitmap"));
        assertFalse(clip.contains("postDelayed"));
    }

    @Test public void clippingIsScopedToExactStableMediaArtworkId() throws Exception {
        String proxy = source("launcher/LauncherGlobalElementProxyView.java");
        String scope = between(proxy, "private static boolean isMediaArtwork(",
                "private static RectF imageViewport(");
        assertTrue(scope.contains("LauncherGlobalElementTag.from(image)"));
        assertTrue(scope.contains("LauncherLayoutStore.MEDIA"));
        assertTrue(scope.contains("MediaPanelConfig.ARTWORK"));
    }

    private static String source(String relative) throws Exception {
        Path current = Paths.get("").toAbsolutePath();
        for (int depth = 0; depth < 8 && current != null;
             depth++, current = current.getParent()) {
            Path candidate = current.resolve(
                    "app/src/main/java/dezz/status/widget").resolve(relative);
            if (Files.isRegularFile(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
        }
        throw new IllegalStateException("Source not found: " + relative);
    }

    private static String between(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, Math.max(0, from + start.length()));
        if (from < 0 || to <= from) throw new AssertionError("Missing source range");
        return source.substring(from, to);
    }
}
