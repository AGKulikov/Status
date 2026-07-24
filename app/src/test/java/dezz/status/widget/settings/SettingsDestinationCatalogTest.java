/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Pure JVM contract for the single, searchable settings destination catalog. */
public final class SettingsDestinationCatalogTest {
    private static final Set<String> USER_FACING_ACTIVITIES = new HashSet<>(Arrays.asList(
            "dezz.status.widget.AboutActivity",
            "dezz.status.widget.AutomationSettingsActivity",
            "dezz.status.widget.ClimatePanelSettingsActivity",
            "dezz.status.widget.FavoriteAppsSettingsActivity",
            "dezz.status.widget.FavoriteRoutesSettingsActivity",
            "dezz.status.widget.HomeAssistantSettingsActivity",
            "dezz.status.widget.InformationPanelSettingsActivity",
            "dezz.status.widget.IntentScenarioSettingsActivity",
            "dezz.status.widget.LauncherSettingsActivity",
            "dezz.status.widget.LauncherShortcutSettingsActivity",
            "dezz.status.widget.MainActivity",
            "dezz.status.widget.MediaPanelSettingsActivity",
            "dezz.status.widget.MqttSettingsActivity",
            "dezz.status.widget.NavigationPanelSettingsActivity",
            "dezz.status.widget.PanelElementSettingsActivity",
            "dezz.status.widget.PopupSettingsActivity",
            "dezz.status.widget.PresetsActivity",
            "dezz.status.widget.ScenarioSettingsActivity",
            "dezz.status.widget.SprutHubSettingsActivity",
            "dezz.status.widget.VehicleInfoPanelSettingsActivity"
    ));

    @Test
    public void destinationIdsAreUniqueAndRoundTrip() {
        Set<String> ids = new HashSet<>();
        for (SettingsDestinationCatalog.Destination destination
                : SettingsDestinationCatalog.all()) {
            assertTrue("Duplicate destination id: " + destination.id,
                    ids.add(destination.id));
            assertEquals(destination, SettingsDestinationCatalog.byId(destination.id));
            assertTrue("Destination must have exactly one launch target: " + destination.id,
                    destination.isActivity() ^ destination.action != null);
        }
        assertEquals(SettingsDestinationCatalog.all().size(), ids.size());
    }

    @Test
    public void everyCanonicalUserFacingActivityAppearsExactlyOnce() {
        Map<String, Integer> occurrences = new HashMap<>();
        for (SettingsDestinationCatalog.Destination destination
                : SettingsDestinationCatalog.all()) {
            if (destination.activityClassName == null) continue;
            occurrences.put(destination.activityClassName,
                    occurrences.getOrDefault(destination.activityClassName, 0) + 1);
        }

        assertEquals(USER_FACING_ACTIVITIES, occurrences.keySet());
        for (String activity : USER_FACING_ACTIVITIES) {
            assertEquals(activity + " must have one canonical destination",
                    Integer.valueOf(1), occurrences.get(activity));
        }
        assertEquals(USER_FACING_ACTIVITIES,
                SettingsDestinationCatalog.activityClassNames());
    }

    @Test
    public void everyGroupIsNonEmpty() {
        for (SettingsDestinationCatalog.Group group
                : SettingsDestinationCatalog.Group.values()) {
            List<SettingsDestinationCatalog.Destination> destinations =
                    SettingsDestinationCatalog.forGroup(group);
            assertFalse(group + " must contain at least one destination",
                    destinations.isEmpty());
            for (SettingsDestinationCatalog.Destination destination : destinations) {
                assertEquals(group, destination.group);
            }
        }
    }

    @Test
    public void searchSupportsRussianEnglishAndNormalizedSynonyms() {
        assertSearchContains("музыка", "panel_media");
        assertSearchContains("информация", "panel_information");
        assertSearchContains("манёвр", "panel_navigation");
        assertSearchContains("  РЕЗЕРВНАЯ   КОПИЯ ", "app_export");
        assertSearchContains("backup", "app_export");
        assertSearchContains("HOME ASSISTANT", "connector_ha");
        assertSearchContains("android intent", "automation_intent");
        assertEquals(SettingsDestinationCatalog.all().size(),
                SettingsDestinationCatalog.search("  ").size());
        assertTrue(SettingsDestinationCatalog.search("несуществующий-запрос").isEmpty());
    }

    private static void assertSearchContains(String query, String expectedId) {
        SettingsDestinationCatalog.Destination expected =
                SettingsDestinationCatalog.byId(expectedId);
        assertNotNull(expected);
        assertTrue("Search for \"" + query + "\" must include " + expectedId,
                SettingsDestinationCatalog.search(query).contains(expected));
    }
}
