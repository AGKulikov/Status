/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** Release barrier for the KX11 compositor ignoring HA1173's onDraw-time icon mask. */
public final class Ha1174PublishedIconBitmapContractTest {
    @Test public void originalSquareDrawableIsNeverInstalledIntoTheImageView() throws Exception {
        String card = source("phone/PhoneNotificationCardView.java");
        String icon = between(card, "private static final class AppleContinuousIconView",
                "private Drawable phoneAppIcon");

        assertTrue(card.contains("badge.setSourceDrawable(icon)"));
        assertFalse(card.contains("badge.setImageDrawable(icon)"));
        assertTrue(icon.contains("IconAlphaMask.apply(pixels, alphaMask)"));
        assertTrue(icon.contains("output.setPixels(pixels"));
        assertTrue(icon.contains("output.setHasAlpha(true)"));
        assertTrue(icon.contains("super.setImageBitmap(output)"));
        assertFalse(icon.contains("protected void onDraw"));
        assertFalse(icon.contains("PorterDuff"));
    }

    @Test public void rebuildIsDrivenByPersistedRadiusAndExactFinalBounds() throws Exception {
        String card = source("phone/PhoneNotificationCardView.java");
        String icon = between(card, "private static final class AppleContinuousIconView",
                "private Drawable phoneAppIcon");

        assertTrue(card.contains("badge.setContinuousCornerRadiusPx(value.iconCornerRadiusPx)"));
        assertTrue(icon.contains("int width = getWidth()"));
        assertTrue(icon.contains("int height = getHeight()"));
        assertTrue(icon.contains("protected void onSizeChanged"));
        assertTrue(icon.contains("protected void onLayout"));
        assertTrue(icon.contains("AppleContinuousCornerPath.setIconMask("));
    }

    @Test public void releaseIdentityIsHa1174() throws Exception {
        String build = project("build.gradle");
        if (!build.contains("String getVersionName()")) build = project("../build.gradle");
        assertTrue(build.contains("return 'v2.8.2-ha1212'"));
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
