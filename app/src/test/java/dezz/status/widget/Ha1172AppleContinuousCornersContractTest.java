/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** Release barriers for the HA1172 Apple-continuous notification silhouettes. */
public final class Ha1172AppleContinuousCornersContractTest {
    @Test public void oneExponentFivePathDefinesTheCardBorderAndApplicationIcon()
            throws Exception {
        String helper = source("phone/AppleContinuousCornerPath.java");
        String card = source("phone/PhoneNotificationCardView.java");

        assertTrue(helper.contains("EXPONENT = 5.0d"));
        assertTrue(helper.contains("CONTINUOUS_EXTENT_MULTIPLIER = 1.5286648f"));
        assertTrue(helper.contains("SAMPLES_PER_CORNER = 32"));
        assertTrue(helper.contains("target.addRect(bounds, Path.Direction.CW)"));
        assertTrue(helper.contains("target.close()"));
        assertFalse(helper.contains("addRoundRect"));

        assertTrue(card.contains("AppleContinuousCornerPath.set(fillPath"));
        assertTrue(card.contains("AppleContinuousCornerPath.set(borderPath"));
        assertTrue(card.contains("AppleContinuousCornerPath.set("));
        assertTrue(card.contains("canvas.drawPath(fillPath, fillPaint)"));
        assertTrue(card.contains("canvas.drawPath(borderPath, borderPaint)"));
        assertTrue(card.contains("new Canvas(mask).drawPath(outputPath, maskPaint)"));
    }

    @Test public void finalPixelsAreMaskedWithoutRoundRectOutlineOrBlackCornerCovers()
            throws Exception {
        String card = source("phone/PhoneNotificationCardView.java");
        String surface = between(card, "private static final class AppleContinuousSurfaceDrawable",
                "private static final class AppleContinuousIconView");
        String icon = between(card, "private static final class AppleContinuousIconView",
                "private Drawable phoneAppIcon");

        assertTrue(surface.contains("canvas.drawPath(fillPath, fillPaint)"));
        assertTrue(surface.contains("canvas.drawPath(borderPath, borderPaint)"));
        assertFalse(surface.contains("clipPath"));
        assertFalse(surface.contains("drawRoundRect"));
        assertTrue(icon.contains("IconAlphaMask.apply(pixels, alphaMask)"));
        assertTrue(icon.contains("output.setHasAlpha(true)"));
        assertTrue(icon.contains("super.setImageBitmap(output)"));
        assertFalse(icon.contains("canvas.clipPath"));
        assertFalse(icon.contains("drawRoundRect"));
        assertFalse(icon.contains("PorterDuff"));
        assertFalse(card.contains("BitmapShader"));
        assertFalse(card.contains("setBackground(null)"));
        assertFalse(card.contains("GradientDrawable surface ="));
    }

    @Test public void settingsDescribeAppleGeometryAndReleaseIdentityIsHa1172()
            throws Exception {
        String editor = source("PhoneNotificationLayoutEditorActivity.java");
        String build = project("build.gradle");
        if (!build.contains("String getVersionName()")) build = project("../build.gradle");

        assertTrue(editor.contains("Радиус карточки Apple"));
        assertTrue(editor.contains("Радиус иконки Apple"));
        assertTrue(build.contains("return 'v2.8.2-ha1181'"));
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
