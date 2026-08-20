/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** Release gate for the reference KX11 trunk command and bounded iOS reconnect watchdog. */
public final class Ha1197TrunkAndReconnectContractTest {
    @Test public void trunkProtocolMatchesTheWorkingReferenceApk() throws Exception {
        String integration = project(
                "app/src/geely/java/dezz/status/widget/car/GeelyCarIntegration.java");

        assertTrue(integration.contains("TRUNK_FUNCTION_ID = 0x21020100"));
        assertFalse(integration.contains("TRUNK_FUNCTION_ID = 0x02210100"));
        assertTrue(integration.contains("TRUNK_ZONE = 0x20000000"));

        String availability = between(integration,
                "private CarControlDescriptor.Availability controlAvailability",
                "public void subscribeControlStates");
        assertTrue(availability.contains(
                "source.getFunctionValue(\n                        definition.functionId, definition.zone)"));

        String write = between(integration,
                "private boolean writeControlValue",
                "private Double readControlValue");
        assertTrue(write.contains(
                "source.setFunctionValue(definition.functionId, definition.zone, value)"));
    }

    @Test public void playPauseIsVectorAndUsesTheSameContentScaleAsTransportButtons()
            throws Exception {
        String play = project("app/src/main/res/drawable/ic_media_play.xml");
        String pause = project("app/src/main/res/drawable/ic_media_pause.xml");
        String previous = project("app/src/main/res/drawable/ic_media_previous.xml");
        String next = project("app/src/main/res/drawable/ic_media_next.xml");
        for (String vector : new String[] { play, pause, previous, next }) {
            assertTrue(vector.contains("<vector"));
            assertTrue(vector.contains("android:viewportWidth=\"24\""));
            assertTrue(vector.contains("android:viewportHeight=\"24\""));
        }
        // Play now occupies the same x=6..19 optical box as the previous/next symbols.
        assertTrue(play.contains("android:pathData=\"M6,5v14l13,-7z\""));

        String view = project(
                "app/src/main/java/dezz/status/widget/launcher/media/MediaPanelView.java");
        String elements = between(view, "case MediaPanelConfig.PREVIOUS:",
                "case MediaPanelConfig.VOLUME:");
        // Previous, play/pause, next and the new mSaver-compatible Like action all use the
        // same optical scaling path.
        assertEquals(4, occurrences(elements, "element.scalePercent"));
        String button = between(view, "private ImageButton button", "private void applySnapshot");
        assertTrue(button.contains("value.setScaleType(ImageView.ScaleType.FIT_CENTER)"));
        assertTrue(button.contains("Math.max(45, scalePercent)"));
    }

    private static String project(String relative) throws Exception {
        Path current = Paths.get("").toAbsolutePath();
        for (int depth = 0; depth < 8 && current != null;
             depth++, current = current.getParent()) {
            if (!Files.isRegularFile(current.resolve("settings.gradle"))) continue;
            Path candidate = current.resolve(relative);
            if (Files.isRegularFile(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
        }
        throw new IllegalStateException("Project file not found: " + relative);
    }

    private static String between(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from + Math.max(1, start.length()));
        assertTrue("Missing section start: " + start, from >= 0);
        assertTrue("Missing section end: " + end, to > from);
        return source.substring(from, to);
    }

    private static int occurrences(String source, String value) {
        int count = 0;
        int offset = 0;
        while ((offset = source.indexOf(value, offset)) >= 0) {
            count++;
            offset += value.length();
        }
        return count;
    }
}
