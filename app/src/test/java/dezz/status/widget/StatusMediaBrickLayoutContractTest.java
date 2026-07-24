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

/**
 * Guards the cold-start media layout: its configured line count must not depend on the first
 * MediaSession callback.
 */
public final class StatusMediaBrickLayoutContractTest {
    @Test public void preferencesApplyLineStructureBeforePlaybackStarts() throws IOException {
        String service = source();
        int settingsStart = service.indexOf("private void applyMediaBrickSettings()");
        int settingsEnd = service.indexOf("private void applyMediaLineStructure()", settingsStart);
        String settings = service.substring(settingsStart, settingsEnd);

        assertTrue(settings.contains("applyMediaLineStructure();"));
    }

    @Test public void disabledSourceAlsoRemovesItsInterLineGap() throws IOException {
        String service = source();
        int structureStart = service.indexOf("private void applyMediaLineStructure()");
        int structureEnd = service.indexOf("private void applyMediaStateIcon(", structureStart);
        String structure = service.substring(structureStart, structureEnd);

        assertTrue(structure.contains("showSource ? View.VISIBLE : View.GONE"));
        assertTrue(structure.contains("showSource ? prefs.media.lineGap.get() : 0"));
    }

    @Test public void mediaCallbacksReuseTheSameStructurePolicy() throws IOException {
        String service = source();
        int updateStart = service.indexOf("private void updateMediaInfo()");
        int updateEnd = service.indexOf("private static String formatTrackDuration(", updateStart);
        String update = service.substring(updateStart, updateEnd);

        assertTrue(update.contains("applyMediaLineStructure();"));
    }

    @Test public void existingMediaTrackerStillReconcilesEmptyContainer() throws IOException {
        String service = source();
        int enableStart = service.indexOf("private void enableMediaTracking()");
        int enableEnd = service.indexOf("private void disableMediaTracking()", enableStart);
        String enable = service.substring(enableStart, enableEnd);

        int existingManager = enable.indexOf("if (mediaSessionManager != null)");
        int createManager = enable.indexOf("mediaSessionManager = (MediaSessionManager)");
        int reconcile = enable.indexOf("updateMediaInfo();", existingManager);
        assertTrue(existingManager >= 0);
        assertTrue(reconcile > existingManager);
        assertTrue(createManager > reconcile);
    }

    @Test public void visibilityPolicyRequiresAnActiveMediaSession() throws IOException {
        String service = source();
        int visibilityStart = service.indexOf("private void applyBrickVisibility(");
        int visibilityEnd = service.indexOf("private void applyBrickTarget(", visibilityStart);
        String visibility = service.substring(visibilityStart, visibilityEnd);

        assertTrue(visibility.contains(
                "boolean mediaSessionActive = pickActiveMediaController() != null"));
        assertTrue(visibility.contains("|| !mediaSessionActive"));
    }

    @Test public void heightFloorIncludesOptionalDurationAndProgress() throws IOException {
        String service = source();
        int heightStart = service.indexOf("private int computeMinWidgetHeight(");
        int heightEnd = service.indexOf("private static int textLineHeight(", heightStart);
        String height = service.substring(heightStart, heightEnd);

        assertTrue(height.contains("prefs.media.showDuration.get()"));
        assertTrue(height.contains("prefs.media.durationFontSize.get()"));
        assertTrue(height.contains("prefs.media.progressBarEnabled.get()"));
        assertTrue(height.contains("binding.mediaProgressBar.getLayoutParams()"));
    }

    @Test public void mediaRefreshPreservesConfiguredKeepSpace() throws IOException {
        String service = source();
        int updateStart = service.indexOf("private void updateMediaInfo()");
        int updateEnd = service.indexOf("private static String formatTrackDuration(", updateStart);
        String update = service.substring(updateStart, updateEnd);

        assertTrue(update.contains("boolean mainMediaKeepsSpace"));
        assertTrue(update.contains("binding.mediaContainer.setAlpha(0f)"));
        assertTrue(update.contains("mainMediaVisible || mainMediaKeepsSpace"));
    }

    @Test public void becomingVisibleAlwaysReconcilesMetadata() throws IOException {
        String service = source();
        int visibilityStart = service.indexOf("private void applyBrickVisibility(");
        int visibilityEnd = service.indexOf("private void applyBrickTarget(", visibilityStart);
        String visibility = service.substring(visibilityStart, visibilityEnd);

        int applyTarget = visibility.indexOf("applyBrickTarget(mediaTarget");
        int visibleGate = visibility.indexOf("if (refreshVisibleMedia)", applyTarget);
        int reconcile = visibility.indexOf("updateMediaInfo();", visibleGate);
        assertTrue(applyTarget >= 0);
        assertTrue(visibleGate > applyTarget);
        assertTrue(reconcile > visibleGate);
    }

    private static String source() throws IOException {
        Path fromRoot = Paths.get("app", "src", "main", "java", "dezz", "status", "widget",
                "WidgetService.java");
        Path fromApp = Paths.get("src", "main", "java", "dezz", "status", "widget",
                "WidgetService.java");
        Path file = Files.isRegularFile(fromRoot) ? fromRoot : fromApp;
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }
}
