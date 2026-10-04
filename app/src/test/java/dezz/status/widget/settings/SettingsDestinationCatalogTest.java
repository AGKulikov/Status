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
            "dezz.status.widget.FullBackupActivity",
            "dezz.status.widget.HudLcaPatchActivity",
            "dezz.status.widget.SettingsAppearanceActivity",
            "dezz.status.widget.MediaPanelSettingsActivity",
            "dezz.status.widget.FavoriteRoutesSettingsActivity",
            "dezz.status.widget.AdbSettingsActivity",
            "dezz.status.widget.LanTransferActivity",
            "dezz.status.widget.drivemode.ui.MainActivity",
            "dezz.status.widget.servicemode.MainActivity",
            "dezz.status.widget.AutomationSettingsActivity",
            "dezz.status.widget.ClimatePanelSettingsActivity",
            "dezz.status.widget.DiagnosticsActivity",
            "dezz.status.widget.DimMenuPanelSettingsActivity",
            "dezz.status.widget.DriverPanelSettingsActivity",
            "dezz.status.widget.DriverFavoritesSettingsActivity",
            "dezz.status.widget.HomeAssistantSettingsActivity",
            "dezz.status.widget.HudPanelSettingsActivity",
            "dezz.status.widget.IntentScenarioSettingsActivity",
            "dezz.status.widget.InstrumentPanelSettingsActivity",
            "dezz.status.widget.LauncherSettingsActivity",
            "dezz.status.widget.PassengerLauncherSettingsActivity",
            "dezz.status.widget.PassengerPanelSettingsActivity",
            "dezz.status.widget.PassengerFavoritesSettingsActivity",
            "dezz.status.widget.MainActivity",
            "dezz.status.widget.MediaButtonsSettingsActivity",
            "dezz.status.widget.MqttSettingsActivity",
            "dezz.status.widget.NavigatorWindowSettingsActivity",
            "dezz.status.widget.PhoneConnectorSettingsActivity",
            "dezz.status.widget.PhoneAppIconsActivity",
            "dezz.status.widget.PhoneNotificationAutomationSettingsActivity",
            "dezz.status.widget.PopupSettingsActivity",
            "dezz.status.widget.PresetsActivity",
            "dezz.status.widget.ScenarioSettingsActivity",
            "dezz.status.widget.SprutHubSettingsActivity",
            "dezz.status.widget.shade.SystemShadeSettingsActivity",
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
        assertSearchContains("скругление", "navigator_window");
        assertSearchContains("зафиксировать", "navigator_window");
        assertSearchContains("приборка", "panel_instrument_cluster");
        assertSearchContains("кнопками руля", "panel_dim_menu");
        assertEquals(SettingsDestinationCatalog.all().size(),
                SettingsDestinationCatalog.search("  ").size());
        assertTrue(SettingsDestinationCatalog.search("несуществующий-запрос").isEmpty());
    }

    @Test
    public void launcherMetadataAdvertisesOneFlatHomeScreen() {
        SettingsDestinationCatalog.Destination launcher =
                SettingsDestinationCatalog.byId("home_behavior");
        assertNotNull(launcher);
        assertEquals(4, SettingsDestinationCatalog.forGroup(
                SettingsDestinationCatalog.Group.HOME).size());
        assertNotNull(SettingsDestinationCatalog.byId("vehicle_control"));
        assertTrue(launcher.subtitle.contains("Один плоский экран"));
        assertTrue(launcher.keywords.contains("размеры"));
        assertTrue(launcher.keywords.contains("позиции кнопок"));
    }

    @Test public void elevenSectionsKeepOldDeepLinksAndMoveOwnership() {
        assertEquals(11,SettingsDestinationCatalog.Group.values().length);
        assertEquals(SettingsDestinationCatalog.Group.HOME,SettingsDestinationCatalog.Group.fromId("status"));
        assertEquals(SettingsDestinationCatalog.Group.PHONE,SettingsDestinationCatalog.byId("connector_phone").group);
        assertEquals(SettingsDestinationCatalog.Group.VEHICLE,SettingsDestinationCatalog.byId("media_buttons").group);
        assertEquals(SettingsDestinationCatalog.Group.BACKUP,SettingsDestinationCatalog.byId("full_backup").group);
    }

    private static void assertSearchContains(String query, String expectedId) {
        SettingsDestinationCatalog.Destination expected =
                SettingsDestinationCatalog.byId(expectedId);
        assertNotNull(expected);
        assertTrue("Search for \"" + query + "\" must include " + expectedId,
                SettingsDestinationCatalog.search(query).contains(expected));
    }
}
