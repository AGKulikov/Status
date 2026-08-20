/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import static org.junit.Assert.assertFalse;

import org.junit.Test;

public final class PreferencesDefaultsTest {
    @Test public void vehicleInfoPanelIsOptInForExistingLauncherLayouts() {
        assertFalse(Preferences.DEFAULT_LAUNCHER_VEHICLE_INFO_VISIBLE);
    }
}
