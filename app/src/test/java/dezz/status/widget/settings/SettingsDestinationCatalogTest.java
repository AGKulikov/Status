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
            "dezz.status.widget.DiagnosticsActivity",
            "dezz.status.widget.DriverPanelSettingsActivity",
            "dezz.status.widget.DriverFavoritesSettingsActivity",
            "dezz.status.widget.HomeAssistantSettingsActivity",
            "dezz.status.widget.HudPanelSettingsActivity",
            "dezz.status.widget.IntentScenarioSettingsActivity",
            "dezz.status.widget.LauncherSettingsActivity",
            "dezz.status.widget.MainActivity",
            "dezz.status.widget.MqttSettingsActivity",
            "dezz.status.widget.PhoneConnectorSettingsActivity",
            "dezz.status.widget.PhoneNotificationAutomationSettingsActivity",
            "dezz.status.widget.PopupSettingsActivity",
            "dezz.status.widget.PresetsActivity",
            "dezz.status.widget.ScenarioSettingsActivity",
            "dezz.status.widget.SprutHubSettingsActivity",
            "dezz.status.widget.VehicleControlActivity"
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
        assertSearchContains("музыка", "home_behavior");
        assertSearchContains("информация", "home_behavior");
        assertSearchContains("манёвр", "home_behavior");
        assertSearchContains("  РЕЗЕРВНАЯ   КОПИЯ ", "app_export");
        assertSearchContains("backup", "app_export");
        assertSearchContains("HOME ASSISTANT", "connector_ha");
        assertSearchContains("iphone", "connector_phone");
        assertSearchContains("sms", "connector_phone");
        assertSearchContains("android intent", "automation_intent");
        assertSearchContains("размеры", "home_behavior");
        assertSearchContains("позиции кнопок", "home_behavior");
        assertSearchContains("столбцы", "home_behavior");
        assertEquals(SettingsDestinationCatalog.all().size(),
                SettingsDestinationCatalog.search("  ").size());
        assertTrue(SettingsDestinationCatalog.search("несуществующий-запрос").isEmpty());
    }

    @Test
    public void launcherMetadataAdvertisesOneFlatHomeScreen() {
        SettingsDestinationCatalog.Destination launcher =
                SettingsDestinationCatalog.byId("home_behavior");
        assertNotNull(launcher);
        assertEquals(2, SettingsDestinationCatalog.forGroup(
                SettingsDestinationCatalog.Group.HOME).size());
        assertNotNull(SettingsDestinationCatalog.byId("vehicle_control"));
        assertTrue(launcher.subtitle.contains("Один плоский экран"));
        assertTrue(launcher.keywords.contains("размеры"));
        assertTrue(launcher.keywords.contains("позиции кнопок"));
    }

    private static void assertSearchContains(String query, String expectedId) {
        SettingsDestinationCatalog.Destination expected =
                SettingsDestinationCatalog.byId(expectedId);
        assertNotNull(expected);
        assertTrue("Search for \"" + query + "\" must include " + expectedId,
                SettingsDestinationCatalog.search(query).contains(expected));
    }
}
