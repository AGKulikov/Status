/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher.climate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import dezz.status.widget.car.CarControlDescriptor;

public final class ClimateLevelCyclePlannerTest {
    private static final double OFF = 0d;
    private static final double LEVEL_1 = 268_763_649d;
    private static final double LEVEL_2 = 268_763_650d;
    private static final double LEVEL_3 = 268_763_651d;
    private static final double AUTO = 268_763_652d;

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
