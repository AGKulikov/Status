/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package dezz.status.hudlab;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class HudVisualProbePlanTest {
    @Test
    public void acceptsOnlyDumpProvenProfilePens() {
        assertEquals(0, HudVisualProbePlan.requireProfilePen(0));
        assertEquals(1, HudVisualProbePlan.requireProfilePen(1));
        assertEquals(13, HudVisualProbePlan.requireProfilePen(13));
        assertThrows(IllegalArgumentException.class,
                () -> HudVisualProbePlan.requireProfilePen(14));
        assertThrows(IllegalArgumentException.class,
                () -> HudVisualProbePlan.requireProfilePen(15));
    }

    @Test
    public void sequenceStartsWithZeroAndOneBaselines() {
        assertArrayEquals(repeated(0), HudVisualProbePlan.step(0).values());
        assertArrayEquals(repeated(1), HudVisualProbePlan.step(1).values());
        assertFalse(HudVisualProbePlan.step(0).finalRestore);
        assertFalse(HudVisualProbePlan.step(1).finalRestore);
    }

    @Test
    public void sequenceTestsEveryFunctionAloneFromAllOneBaseline() {
        for (int index = 0; index < HudVisualProbePlan.FUNCTION_COUNT; index++) {
            HudVisualProbePlan.Step step = HudVisualProbePlan.step(index + 2);
            int[] expected = repeated(1);
            expected[index] = 0;
            assertEquals(index, step.functionIndex);
            assertArrayEquals(expected, step.values());
            assertFalse(step.finalRestore);
        }
    }

    @Test
    public void sequenceAlwaysEndsWithExplicitAllOneRestore() {
        assertEquals(23, HudVisualProbePlan.stepCount());
        HudVisualProbePlan.Step restore =
                HudVisualProbePlan.step(HudVisualProbePlan.stepCount() - 1);
        assertArrayEquals(repeated(1), restore.values());
        assertEquals(-1, restore.functionIndex);
        assertTrue(restore.finalRestore);
    }

    @Test
    public void stepValuesCannotBeMutatedByCaller() {
        HudVisualProbePlan.Step step = HudVisualProbePlan.step(2);
        int[] first = step.values();
        first[0] = 1;
        assertEquals(0, step.values()[0]);
    }

    private static int[] repeated(int value) {
        int[] values = new int[HudVisualProbePlan.FUNCTION_COUNT];
        java.util.Arrays.fill(values, value);
        return values;
    }
}
