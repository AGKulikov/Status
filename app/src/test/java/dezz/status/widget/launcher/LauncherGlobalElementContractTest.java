/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class LauncherGlobalElementContractTest {
    @Test
    public void homeEditorUsesOneScreenWideElementLayer() throws Exception {
        String activity = read("LauncherActivity.java");

        assertTrue(activity.contains("globalElementFrames"));
        assertTrue(activity.contains("LauncherGlobalElementProxyView"));
        assertTrue(activity.contains("migrateSourceGeometry"));
        assertTrue(activity.contains("workspace.addView(frame, params)"));
        assertTrue(activity.contains(
                "Тащите любой элемент по всему HOME"));
        assertTrue(activity.contains("frame.setEditMode(false, snap)"));
    }

    @Test
    public void everyRichPanelMarksItsLiveChildrenWithStableIds() throws Exception {
        assertTrue(read("launcher/media/MediaPanelView.java")
                .contains("LauncherGlobalElementTag.attach"));
        assertTrue(read("launcher/climate/ClimatePanelView.java")
                .contains("LauncherGlobalElementTag.attach"));
        assertTrue(read("launcher/vehicle/VehicleInfoPanelView.java")
                .contains("LauncherGlobalElementTag.attach"));
        assertTrue(read("launcher/information/InformationPanelView.java")
                .contains("LauncherGlobalElementTag.attach"));
        assertTrue(read("launcher/routes/FavoriteRoutesPanelView.java")
                .contains("LauncherGlobalElementTag.attach"));
    }

    private static String read(String relative) throws Exception {
        Path current = Paths.get("").toAbsolutePath();
        for (int depth = 0; depth < 8 && current != null; depth++, current = current.getParent()) {
            Path candidate = current.resolve(
                    "app/src/main/java/dezz/status/widget").resolve(relative);
            if (Files.isRegularFile(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
        }
        throw new IllegalStateException("Source not found: " + relative);
    }
}
