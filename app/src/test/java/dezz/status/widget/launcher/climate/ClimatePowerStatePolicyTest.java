/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher.climate;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ClimatePowerStatePolicyTest {
    @Test
    public void directPowerWinsOverChildren() {
        assertFalse(ClimatePowerStatePolicy.isConfirmedOff(
                true, true, true, false, true, false));
        assertTrue(ClimatePowerStatePolicy.isConfirmedOff(
                true, false, true, true, true, true));
    }

    @Test
    public void stoppedFanOrAirflowCanConfirmOffWhenPowerIsUnavailable() {
        assertTrue(ClimatePowerStatePolicy.isConfirmedOff(
                false, false, true, false, false, false));
        assertTrue(ClimatePowerStatePolicy.isConfirmedOff(
                false, false, false, false, true, false));
        assertFalse(ClimatePowerStatePolicy.isConfirmedOff(
                false, false, true, true, true, true));
    }
}
