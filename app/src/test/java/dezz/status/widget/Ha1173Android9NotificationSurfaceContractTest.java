/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** Regression barriers for the Android 9 card-background and application-icon failure. */
public final class Ha1173Android9NotificationSurfaceContractTest {
    @Test public void cardFillAndStrokeAreARealDirectDrawable() throws Exception {
        String card = source("phone/PhoneNotificationCardView.java");
        String surface = between(card,
                "private static final class AppleContinuousSurfaceDrawable",
                "private static final class AppleContinuousIconView");

        assertTrue(card.contains("setBackground(surface)"));
        assertTrue(card.contains("surface.configure(surfaceColor, value.cornerRadiusPx"));
        assertTrue(surface.contains("AppleContinuousCornerPath.set(fillPath"));
        assertTrue(surface.contains("AppleContinuousCornerPath.set(borderPath"));
        assertTrue(surface.contains("canvas.drawPath(fillPath, fillPaint)"));
        assertTrue(surface.contains("canvas.drawPath(borderPath, borderPaint)"));
        assertFalse(surface.contains("Bitmap"));
        assertFalse(surface.contains("setShader"));
        assertFalse(card.contains("setBackground(null)"));
    }

    @Test public void iconCornersArePublishedAsTheOnlyImageViewDrawable() throws Exception {
        String card = source("phone/PhoneNotificationCardView.java");
        String icon = between(card, "private static final class AppleContinuousIconView",
                "private Drawable phoneAppIcon");

        assertTrue(icon.contains("sourceBitmap = Bitmap.createBitmap"));
        assertTrue(icon.contains("Bitmap mask = Bitmap.createBitmap"));
        assertTrue(icon.contains("IconAlphaMask.apply(pixels, alphaMask)"));
        assertTrue(icon.contains("output.setPixels(pixels"));
        assertTrue(icon.contains("super.setImageBitmap(output)"));
        assertFalse(icon.contains("BitmapShader"));
        assertFalse(icon.contains("canvas.clipPath"));
        assertFalse(icon.contains("PorterDuffXfermode"));
    }

    @Test public void existingStyleKeysAndUpdateIdentityArePreserved() throws Exception {
        String config = source("phone/PhoneNotificationLayoutConfig.java");
        String build = project("build.gradle");
        if (!build.contains("String getVersionName()")) build = project("../build.gradle");

        assertTrue(config.contains("backgroundColor"));
        assertTrue(config.contains("backgroundAlpha"));
        assertTrue(config.contains("cornerRadiusPx"));
        assertTrue(config.contains("borderWidthPx"));
        assertTrue(config.contains("borderColor"));
        assertTrue(config.contains("iconCornerRadiusPx"));
        assertTrue(build.contains("return 'v2.8.2-ha1214'"));
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
