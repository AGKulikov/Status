/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package dezz.status.hudlab;

import java.util.Arrays;
import java.util.Locale;

/**
 * Deterministic, recoverable signal-30816 characterization sequence.
 *
 * <p>The vendor framework proves profile identifiers 0 through 13. It does not define PEN 15
 * as an all-profile selector, so the plan rejects every value outside the proven range.</p>
 */
final class HudVisualProbePlan {
    static final int MIN_PROFILE_PEN = 0;
    static final int MAX_PROFILE_PEN = 13;
    static final int FUNCTION_COUNT = 20;

    private static final int BASELINE_ZERO_POSITION = 0;
    private static final int BASELINE_ONE_POSITION = 1;
    private static final int FIRST_SINGLE_OFF_POSITION = 2;
    private static final int RESTORE_POSITION = FIRST_SINGLE_OFF_POSITION + FUNCTION_COUNT;
    private static final int STEP_COUNT = RESTORE_POSITION + 1;

    private HudVisualProbePlan() {
    }

    static int requireProfilePen(int pen) {
        if (pen < MIN_PROFILE_PEN || pen > MAX_PROFILE_PEN) {
            throw new IllegalArgumentException("PEN=" + pen + " вне подтверждённого диапазона "
                    + MIN_PROFILE_PEN + "…" + MAX_PROFILE_PEN);
        }
        return pen;
    }

    static int stepCount() {
        return STEP_COUNT;
    }

    static Step step(int position) {
        if (position < 0 || position >= STEP_COUNT) {
            throw new IllegalArgumentException("position=" + position);
        }

        int[] values = new int[FUNCTION_COUNT];
        if (position == BASELINE_ZERO_POSITION) {
            return new Step("BASELINE all=0", -1, false, values);
        }

        Arrays.fill(values, 1);
        if (position == BASELINE_ONE_POSITION) {
            return new Step("BASELINE all=1", -1, false, values);
        }
        if (position == RESTORE_POSITION) {
            return new Step("RESTORE all=1", -1, true, values);
        }

        int functionIndex = position - FIRST_SINGLE_OFF_POSITION;
        values[functionIndex] = 0;
        return new Step(String.format(Locale.ROOT, "F%02d=0; остальные=1", functionIndex),
                functionIndex, false, values);
    }

    static final class Step {
        final String label;
        final int functionIndex;
        final boolean finalRestore;
        private final int[] values;

        Step(String label, int functionIndex, boolean finalRestore, int[] values) {
            this.label = label;
            this.functionIndex = functionIndex;
            this.finalRestore = finalRestore;
            this.values = values;
        }

        int[] values() {
            return values.clone();
        }
    }
}
