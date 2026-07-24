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
    @Test public void configuredGeometryIsAppliedBeforeWindowManagerSeesTheTree()
            throws IOException {
        String source = widgetService();
        int start = source.indexOf("private void createOverlayView()");
        int end = source.indexOf("private void prepareOverlayGeometryBeforeAttach()", start);
        String create = source.substring(start, end);

        int normalize = create.indexOf("prepareOverlayGeometryBeforeAttach();");
        int attach = create.indexOf("windowManager.addView(binding.getRoot(), params)");
        assertTrue(normalize >= 0);
        assertTrue(attach > normalize);

        int preflightStart = source.indexOf(
                "private void prepareOverlayGeometryBeforeAttach()");
        int preflightEnd = source.indexOf(
                "private void removeStatusOverlaySafely(", preflightStart);
        String preflight = source.substring(preflightStart, preflightEnd);
        assertTrue(preflight.contains("applyTimeBrickSettings();"));
        assertTrue(preflight.contains("applyWifiBrickSettings();"));
        assertTrue(preflight.contains("applyMediaBrickSettings();"));
        assertTrue(preflight.contains("binding.overlayContainer.setPadding("));
        assertTrue(preflight.contains("binding.overlayContainer.setMinimumHeight("));
    }

    @Test public void detachedGeometryPassCannotStartTransitions() throws IOException {
        String source = widgetService();
        int start = source.indexOf("private void applyBrickVisibility(");
        int end = source.indexOf("private static final class BrickTarget", start);
        String visibility = source.substring(start, end);

        assertTrue(visibility.contains(
                "if (!visibilityFlips.isEmpty() && overlayAttached)"));
        assertTrue(visibility.contains(
                "overlayAttached && visibilityFlips.contains(t)"));

        int targetStart = source.indexOf("private void applyBrickTarget(");
        int targetEnd = source.indexOf("private void beginVisibilityTransition(", targetStart);
        String target = source.substring(targetStart, targetEnd);
        assertTrue(target.contains("if (!overlayAttached)"));
        assertTrue(target.contains("target.view.setVisibility(target.visibility)"));
    }

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
        int end = source.indexOf("private void applyMediaLineStructure()", start);
        String settings = source.substring(start, end);

        int lineStructure = settings.indexOf("applyMediaLineStructure();");
        int durationGeometry = settings.indexOf("binding.mediaDurationText.setTypeface(");
        assertTrue(lineStructure >= 0);
        assertTrue(durationGeometry > lineStructure);

        int structureStart = source.indexOf("private void applyMediaLineStructure()");
        int structureEnd = source.indexOf("private void applyMediaStateIcon(", structureStart);
        String structure = source.substring(structureStart, structureEnd);
        assertTrue(structure.contains("boolean showSource = prefs.media.showSource.get()"));
        assertTrue(structure.contains(
                "int sourceVisibility = showSource ? View.VISIBLE : View.GONE"));
        assertTrue(structure.contains("binding.mediaSourceRow.setVisibility(sourceVisibility)"));
    }

    @Test public void emptyDurationCannotInflateBootstrapRow() throws IOException {
        String source = widgetService();
        int start = source.indexOf("private void applyMediaBrickSettings()");
        int end = source.indexOf("private void applyMediaStateIcon(", start);
        String settings = source.substring(start, end);

        assertTrue(settings.contains(
                "if (!prefs.media.showDuration.get()"));
        assertTrue(settings.contains(
                "TextUtils.isEmpty(binding.mediaDurationText.getText())"));
        assertTrue(settings.contains("binding.mediaDurationText.setVisibility(View.GONE)"));
        assertTrue(settings.contains(
                "if (!prefs.media.progressBarEnabled.get() || lastMediaSubtitle == null)"));
        assertTrue(settings.contains("binding.mediaProgressBar.setVisibility(View.GONE)"));
    }

    @Test public void optionalMediaChildrenStartGoneInRuntimeXml() throws IOException {
        String layout = overlayLayout();
        int durationStart = layout.indexOf("android:id=\"@+id/mediaDurationText\"");
        int durationEnd = layout.indexOf("/>", durationStart);
        String duration = layout.substring(durationStart, durationEnd);

        assertTrue(duration.contains("android:visibility=\"gone\""));

        int progressStart = layout.indexOf("android:id=\"@+id/mediaProgressBar\"");
        int progressEnd = layout.indexOf("/>", progressStart);
        String progress = layout.substring(progressStart, progressEnd);

        assertTrue(progress.contains("android:visibility=\"gone\""));
    }

    @Test public void firstVisibleFrameIsPopulatedWithoutWaitingForPlayerCallback()
            throws IOException {
        String source = widgetService();
        int start = source.indexOf("private void applyBrickVisibility(");
        int end = source.indexOf("private static final class BrickTarget", start);
        String visibility = source.substring(start, end);

        assertTrue(visibility.contains("boolean refreshVisibleMedia ="));
        assertTrue(visibility.contains("if (refreshVisibleMedia)"));
        assertTrue(visibility.contains("updateMediaInfo();"));
    }

    @Test public void stableHeightIncludesMetadataDependentMediaChildren() throws IOException {
        String source = widgetService();
        int start = source.indexOf("private int computeMinWidgetHeight(");
        int end = source.indexOf("private static int textLineHeight(", start);
        String height = source.substring(start, end);

        assertTrue(height.contains("prefs.media.showDuration.get()"));
        assertTrue(height.contains("binding.mediaDurationText"));
        assertTrue(height.contains("prefs.media.progressBarEnabled.get()"));
        assertTrue(height.contains("binding.mediaProgressBar.getLayoutParams()"));
    }

    private static String widgetService() throws IOException {
        Path fromRoot = Paths.get("app", "src", "main", "java", "dezz", "status", "widget",
                "WidgetService.java");
        Path fromApp = Paths.get("src", "main", "java", "dezz", "status", "widget",
                "WidgetService.java");
        Path file = Files.isRegularFile(fromRoot) ? fromRoot : fromApp;
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    private static String overlayLayout() throws IOException {
        Path fromRoot = Paths.get("app", "src", "main", "res", "layout",
                "overlay_status_widget.xml");
        Path fromApp = Paths.get("src", "main", "res", "layout",
                "overlay_status_widget.xml");
        Path file = Files.isRegularFile(fromRoot) ? fromRoot : fromApp;
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }
}
