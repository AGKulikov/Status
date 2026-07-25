/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher.climate;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ClimatePowerStatePolicyTest {
    @Test
    public void onlyConfirmedInactiveMasterStateMeansOff() {
        assertTrue(ClimatePowerStatePolicy.isConfirmedOff(true, false));
        assertFalse(ClimatePowerStatePolicy.isConfirmedOff(true, true));
        assertFalse(ClimatePowerStatePolicy.isConfirmedOff(false, false));
        assertFalse(ClimatePowerStatePolicy.isConfirmedOff(false, true));
    }
}
