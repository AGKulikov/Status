/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** Retained driver-control and popup-rendering coverage. */
public final class Ha1155SingleLinkTelemetryContractTest {
    @Test public void driverControlInsetsAreInsideNaturalHeightButtons() throws Exception {
        String overlay = source("driver/DriverPanelOverlayController.java");
        String settings = source("DriverPanelSettingsActivity.java");
        assertTrue(overlay.contains("button.setPadding(button.getPaddingLeft(), internalTop"));
        assertTrue(overlay.contains("itemParams.setMargins(4, 0, 4, 0)"));
        assertTrue(overlay.contains("ViewGroup.LayoutParams.WRAP_CONTENT"));
        assertTrue(overlay.contains("DriverControlSpacingPolicy.resolve("));
        assertTrue(settings.contains("Внутренний отступ сверху"));
        assertTrue(settings.contains("Внутренний отступ снизу"));
        assertTrue(settings.contains("авто (равномерный режим)"));
        assertTrue(settings.contains("DriverControlSpacingPolicy.resolve("));
        assertFalse(settings.contains("boolean compactSpacing"));
    }

    @Test public void popupRefreshUsesFrameSafeDoubleBufferInsteadOfBlinking() throws Exception {
        String popup = source("popup/PopupOverlayController.java");
        String render = between(popup, "private void renderItems()",
                "/**\n     * Adds launcher-style edit chrome");
        assertFalse(render.contains("detachRootImmediately();"));
        assertTrue(render.contains("retireOlderRootsAfterFirstDraw(root)"));
        assertTrue(popup.contains("addOnPreDrawListener"));
        assertTrue(popup.contains("attachedRoots.add(root)"));
    }

    private static String source(String relative) throws Exception {
        Path root = Paths.get("app", "src", "main", "java", "dezz", "status", "widget")
                .resolve(relative);
        Path app = Paths.get("src", "main", "java", "dezz", "status", "widget")
                .resolve(relative);
        Path file = Files.isRegularFile(root) ? root : app;
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
