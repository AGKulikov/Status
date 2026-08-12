/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** Release barriers for the HA1169 fresh-GATT generation and background telemetry wake path. */
public final class Ha1169GattGenerationAndBackgroundWakeContractTest {
    @Test public void iconContinuousMaskIsBakedBeforeTheCardOverlayIsComposited()
            throws Exception {
        String card = source("phone/PhoneNotificationCardView.java");
        String rounded = between(card, "private static final class AppleContinuousIconView",
                "private Drawable phoneAppIcon");

        assertTrue(rounded.contains("Bitmap.Config.ARGB_8888"));
        assertTrue(rounded.contains("new Canvas(mask).drawPath(outputPath, maskPaint)"));
        assertTrue(rounded.contains("IconAlphaMask.apply(pixels, alphaMask)"));
        assertTrue(rounded.contains("super.setImageBitmap(output)"));
        assertTrue(rounded.contains("AppleContinuousCornerPath.setIconMask("));
        assertTrue(rounded.contains("output.setHasAlpha(true)"));
    }

    @Test public void multilineTextVerticalPlacementIsPersistedAndRendered() throws Exception {
        String config = source("phone/PhoneNotificationLayoutConfig.java");
        String card = source("phone/PhoneNotificationCardView.java");
        String editor = source("PhoneNotificationLayoutEditorActivity.java");

        assertTrue(config.contains("SCHEMA_VERSION = 4"));
        assertTrue(config.contains("TEXT_VERTICAL_TOP = \"top\""));
        assertTrue(config.contains(".put(\"verticalAlignment\", verticalAlignment)"));
        assertTrue(config.contains("target.verticalAlignment = origin.verticalAlignment"));
        assertTrue(editor.contains("Текст по центру поля по вертикали"));
        assertTrue(card.contains("element.verticalAlignment"));
        assertTrue(card.contains("? Gravity.TOP : Gravity.CENTER_VERTICAL"));
        assertTrue(card.contains("slotBottom - childTop - visibleHeight"));
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
