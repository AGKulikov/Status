/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher.climate;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Wiring guard for the per-control 1→2→3 / 3→2→1 editor and confirmed SET path. */
public final class ClimateLevelCycleContractTest {
    @Test
    public void settingsExposeThePerElementCycleSelector() throws IOException {
        String settings = source("dezz/status/widget/ClimatePanelSettingsActivity.java");
        assertTrue(settings.contains("addLevelCycleOrderSelector(block, element.id)"));
        assertTrue(settings.contains("config.setLevelCycleOrder(elementId, selected)"));
        assertTrue(settings.contains("Уровни по нажатию"));
    }

    @Test
    public void tileUsesPlannerAndExplicitSetInsteadOfOptimisticCycle() throws IOException {
        String panel = source("dezz/status/widget/launcher/climate/ClimatePanelView.java");
        assertTrue(panel.contains("config.hasLevelCycleOrder(id)) cycleManualLevel(id, 1)"));
        assertTrue(panel.contains("ClimateLevelCyclePlanner.nextTarget"));
        assertTrue(panel.contains("execute(id, CarControlCommand.Operation.SET, target)"));
        assertTrue(panel.contains("pending.containsKey(id)"));
    }

    @Test
    public void schemaPersistsAllFiveDirections() throws IOException {
        String config = source("dezz/status/widget/launcher/climate/ClimatePanelConfig.java");
        String store = source("dezz/status/widget/launcher/climate/ClimatePanelConfigStore.java");
        assertTrue(config.contains("SEAT_HEAT_DRIVER, SEAT_HEAT_PASSENGER"));
        assertTrue(config.contains("SEAT_VENT_DRIVER, SEAT_VENT_PASSENGER, WHEEL_HEAT"));
        assertTrue(store.contains("SCHEMA_VERSION = 4"));
        assertTrue(store.contains("levelCycleOrders"));
    }

    private static String source(String relative) throws IOException {
        Path fromRoot = Paths.get("app", "src", "main", "java").resolve(relative);
        Path fromApp = Paths.get("src", "main", "java").resolve(relative);
        Path file = Files.isRegularFile(fromRoot) ? fromRoot : fromApp;
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }
}
