/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** Regression barriers for the photographed icon barrel and reverse-route teardown loop. */
public final class Ha1175CircularIconAndSamePeerHandoffContractTest {
    @Test public void maximumIconRadiusIsAnExactCircleInsideCenteredSquareBounds()
            throws Exception {
        String card = source("phone/PhoneNotificationCardView.java");
        String path = source("phone/AppleContinuousCornerPath.java");
        String config = source("phone/PhoneNotificationLayoutConfig.java");
        String icon = between(card, "private static final class AppleContinuousIconView",
                "private Drawable phoneAppIcon");

        assertTrue(config.contains("ICON_CORNER_RADIUS_MAX_PX = 120"));
        assertTrue(config.contains("clamp(iconCornerRadiusPx, 0, "
                + "ICON_CORNER_RADIUS_MAX_PX)"));
        assertTrue(icon.contains("float iconSide = Math.min(width, height)"));
        assertTrue(icon.contains("float iconLeft = (width - iconSide) / 2f"));
        assertTrue(icon.contains("float iconTop = (height - iconSide) / 2f"));
        assertTrue(icon.contains("canvas.clipRect(iconBounds)"));
        assertTrue(icon.contains("AppleContinuousCornerPath.setIconMask("));
        assertTrue(path.contains("if (safeRadius >= safeMaximum)"));
        assertTrue(path.contains("target.addOval(squareBounds, Path.Direction.CW)"));
        assertFalse(icon.contains("outputBounds.set(0f, 0f, width, height)"));
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
