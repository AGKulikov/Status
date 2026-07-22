/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.launcher.panels;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;

import dezz.status.widget.launcher.LauncherLayoutStore;

public final class PanelElementConfigStoreTest {
    @Test public void savedNavigationLayoutKeepsNewDefinitionsDisabled() {
        PanelElementConfigStore.Panel migrated = PanelElementConfigStore.defaults(
                LauncherLayoutStore.NAVIGATION);
        PanelElementConfigStore.disableMissingSavedDefinitions(migrated,
                LauncherLayoutStore.NAVIGATION, new HashSet<>(Arrays.asList(
                        PanelElementConfigStore.NAV_ARRIVAL,
                        PanelElementConfigStore.NAV_DURATION,
                        PanelElementConfigStore.NAV_DISTANCE,
                        PanelElementConfigStore.NAV_INACTIVE)));

        assertTrue(migrated.isEnabled(PanelElementConfigStore.NAV_ARRIVAL));
        assertTrue(migrated.isEnabled(PanelElementConfigStore.NAV_INACTIVE));
        assertFalse(migrated.isEnabled(PanelElementConfigStore.NAV_MANEUVER));
        assertFalse(migrated.isEnabled(PanelElementConfigStore.NAV_SPEED_LIMIT));
        assertFalse(migrated.isEnabled(PanelElementConfigStore.NAV_TRAFFIC_LIGHT));
    }

    @Test public void freshNavigationLayoutRetainsProductDefaults() {
        PanelElementConfigStore.Panel fresh = PanelElementConfigStore.defaults(
                LauncherLayoutStore.NAVIGATION);
        assertTrue(fresh.isEnabled(PanelElementConfigStore.NAV_MANEUVER));
        assertTrue(fresh.isEnabled(PanelElementConfigStore.NAV_SPEED_LIMIT));
        assertTrue(fresh.isEnabled(PanelElementConfigStore.NAV_TRAFFIC_LIGHT));
        assertFalse(fresh.isEnabled(PanelElementConfigStore.NAV_COMBINED));
    }
}
