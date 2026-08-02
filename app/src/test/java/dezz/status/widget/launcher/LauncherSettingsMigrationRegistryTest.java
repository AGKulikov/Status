/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Ensures flattening the settings UI never drops or mixes another display surface. */
public final class LauncherSettingsMigrationRegistryTest {
    @Test
    public void everyLegacyLauncherScreenHasOneAuditableDestination() {
        List<LauncherSettingsMigrationRegistry.Entry> entries =
                LauncherSettingsMigrationRegistry.entries();
        Set<String> ids = new HashSet<>();
        for (LauncherSettingsMigrationRegistry.Entry entry : entries) {
            assertTrue(ids.add(entry.oldDestinationId));
            assertFalse(entry.purpose.trim().isEmpty());
            assertFalse(entry.newLocation.trim().isEmpty());
            assertFalse(entry.storageKeys.isEmpty());
        }
        assertEquals(11, entries.size());
        assertTrue(ids.contains("all_apps"));
        assertTrue(ids.contains("panel_media"));
        assertTrue(ids.contains("panel_climate"));
        assertTrue(ids.contains("panel_actions"));
    }

    @Test
    public void snapshotKeysAreUniqueAndExcludeIndependentSurfaces() {
        List<String> keys = LauncherSettingsMigrationRegistry.storageKeys();
        assertEquals(keys.size(), new HashSet<>(keys).size());
        assertTrue(keys.contains("launcherLayoutJson"));
        assertTrue(keys.contains("launcherGlobalElementsJson"));
        assertTrue(keys.contains("launcherClockVisible"));
        assertTrue(keys.contains("launcherMediaFixedPlayerPackage"));
        assertTrue(keys.contains("launcherClimateConfigJson"));
        assertFalse(keys.contains("floatingClimateConfigJson"));
        assertFalse(keys.contains("climatePanelEnabled"));
        assertFalse(keys.contains("hudPanelConfigJson"));
        assertFalse(keys.contains("driverPanelShortcutsJson"));
    }
}
