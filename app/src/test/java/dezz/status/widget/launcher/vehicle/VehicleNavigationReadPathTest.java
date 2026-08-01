/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher.vehicle;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Prevents the one-second vehicle tick from regressing to full navigation bitmap/JSON reads. */
public final class VehicleNavigationReadPathTest {
    @Test
    public void vehiclePanelUsesScalarNavigationStatus() throws IOException {
        String source = source("dezz/status/widget/launcher/vehicle/VehicleInfoPanelView.java");
        assertTrue(source.contains("NavigationDataRepository.readRouteStatus(getContext())"));
        assertFalse(source.contains("NavigationDataRepository.read(getContext())"));
    }

    @Test
    public void scalarStatusReaderCannotLoadGraphicsOrParseLaneTrafficJson() throws IOException {
        String source = source("dezz/status/widget/launcher/NavigationDataRepository.java");
        int start = source.indexOf("public static RouteStatus readRouteStatus");
        int end = source.indexOf("/** Main-route evidence", start);
        assertTrue("route status reader must remain present", start >= 0 && end > start);
        String body = source.substring(start, end);
        assertFalse(body.contains("NavigationGraphicStore"));
        assertFalse(body.contains("loadFreshGraphic"));
        assertFalse(body.contains("readTrafficLights"));
        assertFalse(body.contains("readLaneRecords"));
        assertFalse(body.contains("JSONObject") || body.contains("JSONArray"));
    }

    private static String source(String relative) throws IOException {
        Path fromRoot = Paths.get("app", "src", "main", "java").resolve(relative);
        Path fromApp = Paths.get("src", "main", "java").resolve(relative);
        Path file = Files.isRegularFile(fromRoot) ? fromRoot : fromApp;
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }
}
