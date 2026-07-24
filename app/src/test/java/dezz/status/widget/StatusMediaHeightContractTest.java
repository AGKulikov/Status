/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Keeps the status row's media geometry stable before the first post-boot track arrives. */
public final class StatusMediaHeightContractTest {
    @Test public void emptyMediaCannotAppearDuringDeferredBootRefresh() throws IOException {
        String source = widgetService();
        int start = source.indexOf("private void applyBrickVisibility(");
        int end = source.indexOf("private static final class BrickTarget", start);
        String visibility = source.substring(start, end);

        assertTrue(visibility.contains(
                "boolean mediaSessionActive = pickActiveMediaController() != null"));
        assertTrue(visibility.contains("|| !mediaSessionActive"));
    }

    @Test public void sourceRowGeometryIsAppliedBeforeMediaMetadata() throws IOException {
        String source = widgetService();
        int start = source.indexOf("private void applyMediaBrickSettings()");
        int end = source.indexOf("private void applyMediaStateIcon(", start);
        String settings = source.substring(start, end);

        assertTrue(settings.contains("binding.mediaSourceRow.setVisibility("));
        assertTrue(settings.contains(
                "prefs.media.showSource.get() ? View.VISIBLE : View.GONE"));
    }

    private static String widgetService() throws IOException {
        Path fromRoot = Paths.get("app", "src", "main", "java", "dezz", "status", "widget",
                "WidgetService.java");
        Path fromApp = Paths.get("src", "main", "java", "dezz", "status", "widget",
                "WidgetService.java");
        Path file = Files.isRegularFile(fromRoot) ? fromRoot : fromApp;
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }
}
