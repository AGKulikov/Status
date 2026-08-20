/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** Release barriers for the HA1167 OEM rendering and current-link reconnect fixes. */
public final class Ha1167CurrentLinkAndRenderingContractTest {
    @Test public void notificationIconUsesARealAndroid9AlphaBitmap() throws Exception {
        String card = source("phone/PhoneNotificationCardView.java");
        String rounded = between(card, "private static final class AppleContinuousIconView",
                "private Drawable phoneAppIcon");

        assertTrue(rounded.contains("Bitmap.Config.ARGB_8888"));
        assertTrue(rounded.contains("new Canvas(mask).drawPath(outputPath, maskPaint)"));
        assertTrue(rounded.contains("IconAlphaMask.apply(pixels, alphaMask)"));
        assertTrue(rounded.contains("super.setImageBitmap(output)"));
        assertTrue(rounded.contains("AppleContinuousCornerPath.setIconMask("));
        assertTrue(rounded.contains("output.setHasAlpha(true)"));
        assertFalse(rounded.contains("roundedBitmap"));
        assertFalse(rounded.contains("canvas.saveLayer"));
        assertFalse(rounded.contains("BitmapShader"));
        assertFalse(rounded.contains("PorterDuffXfermode"));
    }

    @Test public void climateIsRecenteredFromTheCurrentPhysicalButtonBounds()
            throws Exception {
        String exact = source("driver/DriverExactCenterFrameLayout.java");
        String runtime = source("driver/DriverPanelOverlayController.java");
        String settings = source("DriverPanelSettingsActivity.java");

        assertTrue(exact.contains("protected void onLayout"));
        assertTrue(exact.contains("physicalHeight = Math.max(0, bottom - top)"));
        assertTrue(exact.contains("childTop = (physicalHeight - centeredHeightPx) / 2"));
        assertTrue(exact.contains("child.layout(childLeft, childTop"));
        assertTrue(runtime.contains("button.addExactlyCentered(icon, requested"));
        assertTrue(settings.contains("cell.addExactlyCentered(icon, iconSize"));
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
