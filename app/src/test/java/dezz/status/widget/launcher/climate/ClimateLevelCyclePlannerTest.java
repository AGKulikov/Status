/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher.climate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import dezz.status.widget.car.CarControlDescriptor;

public final class ClimateLevelCyclePlannerTest {
    private static final double OFF = 0d;
    private static final double LEVEL_1 = 268_763_649d;
    private static final double LEVEL_2 = 268_763_650d;
    private static final double LEVEL_3 = 268_763_651d;
    private static final double AUTO = 268_763_663d;
    // ECARX maps these non-monotonic public IDs to raw PA fan positions 10..14 in this order.
    private static final double AUTO_QUIETER = 268_567_044d;
    private static final double AUTO_SILENT = 268_567_041d;
    private static final double AUTO_NORMAL = 268_567_042d;
    private static final double AUTO_HIGH = 268_567_043d;
    private static final double AUTO_HIGHER = 268_567_045d;

    private static final List<CarControlDescriptor.Option> OPTIONS = Arrays.asList(
            option(LEVEL_2, "2"), option(OFF, "Выкл"), option(AUTO, "Auto"),
            option(LEVEL_3, "3"), option(LEVEL_1, "1"));

    @Test
    public void ascendingPressesCycleOffOneTwoThreeOff() {
        assertCycle(ClimatePanelConfig.LevelCycleOrder.ASCENDING,
                new double[]{OFF, LEVEL_1, LEVEL_2, LEVEL_3, OFF});
    }

    @Test
    public void descendingPressesCycleOffThreeTwoOneOff() {
        assertCycle(ClimatePanelConfig.LevelCycleOrder.DESCENDING,
                new double[]{OFF, LEVEL_3, LEVEL_2, LEVEL_1, OFF});
    }

    @Test
    public void autoAndUnknownVendorValuesConvergeToOffBeforeManualCycle() {
        assertTarget(OFF, ClimateLevelCyclePlanner.nextTarget(OPTIONS, AUTO,
                ClimatePanelConfig.LevelCycleOrder.ASCENDING, 1));
        assertTarget(OFF, ClimateLevelCyclePlanner.nextTarget(OPTIONS, 123_456d,
                ClimatePanelConfig.LevelCycleOrder.DESCENDING, 1));
    }

    @Test
    public void oppositeButtonWalksAgainstConfiguredDirection() {
        assertTarget(LEVEL_3, ClimateLevelCyclePlanner.nextTarget(OPTIONS, OFF,
                ClimatePanelConfig.LevelCycleOrder.ASCENDING, -1));
        assertTarget(LEVEL_1, ClimateLevelCyclePlanner.nextTarget(OPTIONS, OFF,
                ClimatePanelConfig.LevelCycleOrder.DESCENDING, -1));
    }

    @Test
    public void fanPolicyWalksAllFiveAutoProfilesInRawIntensityOrder() {
        List<CarControlDescriptor.Option> fan = Arrays.asList(
                option(OFF, "Выкл"), option(LEVEL_1, "1"), option(LEVEL_2, "2"),
                option(AUTO_QUIETER, "AUTO · тише"),
                option(AUTO_SILENT, "AUTO · тихо"),
                option(AUTO_NORMAL, "AUTO · обычно"),
                option(AUTO_HIGH, "AUTO · интенсивно"),
                option(AUTO_HIGHER, "AUTO · выше"));

        assertTarget(AUTO_SILENT, ClimateLevelCyclePlanner.nextStepperTarget(
                fan, AUTO_QUIETER, 1, true));
        assertTarget(AUTO_NORMAL, ClimateLevelCyclePlanner.nextStepperTarget(
                fan, AUTO_SILENT, 1, true));
        assertTarget(AUTO_HIGH, ClimateLevelCyclePlanner.nextStepperTarget(
                fan, AUTO_NORMAL, 1, true));
        assertTarget(AUTO_HIGHER, ClimateLevelCyclePlanner.nextStepperTarget(
                fan, AUTO_HIGH, 1, true));
        assertTarget(AUTO_HIGHER, ClimateLevelCyclePlanner.nextStepperTarget(
                fan, AUTO_HIGHER, 1, true));
        assertTarget(AUTO_QUIETER, ClimateLevelCyclePlanner.nextStepperTarget(
                fan, AUTO_QUIETER, -1, true));
    }

    @Test
    public void nonFanPolicyStillLeavesAutoThroughOff() {
        assertTarget(OFF, ClimateLevelCyclePlanner.nextStepperTarget(
                OPTIONS, AUTO, 1, false));
    }

    @Test
    public void manualFanBoundaryNeverSelectsAnAutoProfile() {
        List<CarControlDescriptor.Option> fan = Arrays.asList(
                option(OFF, "Выкл"), option(LEVEL_1, "1"), option(LEVEL_2, "2"),
                option(AUTO_NORMAL, "AUTO · обычно"));
        assertTarget(LEVEL_2, ClimateLevelCyclePlanner.nextStepperTarget(
                fan, LEVEL_2, 1, true));
    }

    @Test
    public void everyVisibleLevelOneDecrementRequestsClimatePowerOff() {
        List<CarControlDescriptor.Option> fan = Arrays.asList(
                option(OFF, "Выкл"), option(LEVEL_1, "1"), option(LEVEL_2, "2"),
                option(AUTO_QUIETER, "AUTO · тише"),
                option(AUTO_NORMAL, "AUTO · обычно"));
        assertTrue(ClimateLevelCyclePlanner.shouldPowerOffClimateOnFanDecrease(
                fan, LEVEL_1, -1));
        assertTrue(ClimateLevelCyclePlanner.shouldPowerOffClimateOnFanDecrease(
                fan, AUTO_QUIETER, -1));
        assertFalse(ClimateLevelCyclePlanner.shouldPowerOffClimateOnFanDecrease(
                fan, LEVEL_1, 1));
        assertFalse(ClimateLevelCyclePlanner.shouldPowerOffClimateOnFanDecrease(
                fan, LEVEL_2, -1));
        assertFalse(ClimateLevelCyclePlanner.shouldPowerOffClimateOnFanDecrease(
                fan, AUTO_NORMAL, -1));
    }

    private static void assertCycle(ClimatePanelConfig.LevelCycleOrder order,
                                    double[] expected) {
        double current = expected[0];
        for (int index = 1; index < expected.length; index++) {
            Double target = ClimateLevelCyclePlanner.nextTarget(OPTIONS, current, order, 1);
            assertTarget(expected[index], target);
            current = target;
        }
    }

    private static void assertTarget(double expected, Double actual) {
        assertNotNull(actual);
        assertEquals(expected, actual, 0d);
    }

    private static CarControlDescriptor.Option option(double value, String label) {
        return new CarControlDescriptor.Option(value, label);
    }
}
