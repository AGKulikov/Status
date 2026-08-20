/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** Release barriers for the HA1166 KX11 visual and reverse-BLE regressions. */
public final class Ha1166BleIdentityAndVisualCenteringContractTest {
    @Test public void applicationIconContinuousPathMasksTheActualDrawableOnAndroid9()
            throws Exception {
        String card = source("phone/PhoneNotificationCardView.java");

        assertTrue(card.contains("private static final class AppleContinuousIconView"));
        assertTrue(card.contains("badge.setContinuousCornerRadiusPx(value.iconCornerRadiusPx)"));
        assertTrue(card.contains("Bitmap.Config.ARGB_8888"));
        assertTrue(card.contains("new Canvas(mask).drawPath(outputPath, maskPaint)"));
        assertTrue(card.contains("IconAlphaMask.apply(pixels, alphaMask)"));
        assertTrue(card.contains("super.setImageBitmap(output)"));
        assertFalse(card.contains("PorterDuffXfermode"));
        assertTrue(card.contains("layoutElementsExactly(right - left, bottom - top)"));
        assertTrue(card.contains("placeExactly(badge, value.badge, width, height)"));
        assertTrue(card.contains("view.layout(childLeft, childTop"));
        assertTrue(card.contains("private static int gridCoordinate"));
        assertFalse(card.contains("badge.setClipToOutline"));
    }

    @Test public void captionlessClimateCanvasIsARealCenteredFrameChild()
            throws Exception {
        String driver = source("driver/DriverPanelOverlayController.java");
        String host = source("driver/DriverExactCenterFrameLayout.java");
        String placement = between(driver,
                "boolean directlyCenteredClimate = liveClimate && !shortcut.showTitle",
                "WidgetService widgetService");

        assertTrue(placement.contains("if (directlyCenteredClimate)"));
        assertTrue(placement.contains("button.addExactlyCentered(icon, requested"));
        assertTrue(host.contains("int physicalWidth = Math.max(0, right - left)"));
        assertTrue(host.contains("int physicalHeight = Math.max(0, bottom - top)"));
        assertTrue(host.contains("int childLeft = (physicalWidth - centeredWidthPx) / 2"));
        assertTrue(host.contains("int childTop = (physicalHeight - centeredHeightPx) / 2"));
        assertFalse(host.contains("int contentTop = getPaddingTop()"));
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
