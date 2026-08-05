/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** Release barriers for the HA1171 four-corner notification-icon rendering fix. */
public final class Ha1171IconRoundingContractTest {
    @Test public void iconMaskUsesTheCurrentFinalViewBoundsOnEveryDraw() throws Exception {
        String card = source("phone/PhoneNotificationCardView.java");
        String rounded = between(card, "private static final class RoundedIconView",
                "private Drawable phoneAppIcon");

        assertTrue(rounded.contains("ensureRenderBuffer(getWidth(), getHeight())"));
        assertTrue(rounded.contains("buffer.eraseColor(Color.TRANSPARENT)"));
        assertTrue(rounded.contains("super.onDraw(bufferCanvas)"));
        assertTrue(rounded.contains("Math.min(cornerRadiusPx, Math.min(getWidth(), getHeight()) / 2f)"));
        assertTrue(rounded.contains("canvas.drawRoundRect(outputBounds, radius, radius"));
        assertFalse(rounded.contains("roundedBitmap"));
        assertFalse(rounded.contains(".recycle()"));
    }

    @Test public void notificationEditorDoesNotPaintSquaresIntoTransparentCorners()
            throws Exception {
        String overlay = source("launcher/panels/PanelContentEditOverlay.java");
        String editor = source("PhoneNotificationLayoutEditorActivity.java");

        assertTrue(overlay.contains("default boolean drawItemFill()"));
        assertTrue(overlay.contains("if (current.drawItemFill()) canvas.drawRect"));
        assertTrue(editor.contains("@Override public boolean drawItemFill() { return false; }"));
    }

    @Test public void releaseIdentityIsHa1171() throws Exception {
        String build = project("build.gradle");
        if (!build.contains("String getVersionName()")) build = project("../build.gradle");
        assertTrue(build.contains("return 'v2.8.2-ha1171'"));
    }

    private static String source(String relative) throws Exception {
        return project("app/src/main/java/dezz/status/widget/" + relative);
    }

    private static String project(String relative) throws Exception {
        Path direct = Paths.get(relative);
        Path parent = Paths.get("..").resolve(relative).normalize();
        Path file = Files.isRegularFile(direct) ? direct : parent;
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    private static String between(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from + Math.max(1, start.length()));
        assertTrue("Missing section start: " + start, from >= 0);
        assertTrue("Missing section end: " + end, to > from);
        return source.substring(from, to);
    }
}
