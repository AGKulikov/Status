/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.car;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class HudModeEnforcementPolicyTest {
    @Test public void speedIsAnEdgeTriggerAndRearmsBelowFifteen() {
        HudModeEnforcementPolicy policy = new HudModeEnforcementPolicy();
        assertFalse(policy.onRawSpeed(rawSpeed(19)));
        assertTrue(policy.onRawSpeed(rawSpeed(21)));
        assertFalse(policy.onRawSpeed(rawSpeed(40)));
        assertFalse(policy.onRawSpeed(rawSpeed(14)));
        assertTrue(policy.onRawSpeed(rawSpeed(21)));
    }

    @Test public void writesHaveMinimumGapAndFivePerMinuteBudget() {
        HudModeEnforcementPolicy policy = new HudModeEnforcementPolicy();
        long now = 100_000L;
        for (int index = 0; index < 5; index++) {
            assertTrue(policy.delayBeforeWrite(now) == 0L);
            policy.recordWriteAttempt(now);
            now += HudModeEnforcementPolicy.MIN_WRITE_INTERVAL_MS;
        }
        assertTrue(policy.delayBeforeWrite(now) > 0L);
        now += HudModeEnforcementPolicy.WRITE_BUDGET_WINDOW_MS;
        assertTrue(policy.delayBeforeWrite(now) == 0L);
    }

    @Test public void threeFailuresOpenCircuitBreaker() {
        HudModeEnforcementPolicy policy = new HudModeEnforcementPolicy();
        long now = 1_000L;
        policy.recordWriteFailure(now);
        policy.recordWriteFailure(now);
        assertTrue(policy.delayBeforeWrite(now) == 0L);
        policy.recordWriteFailure(now);
        assertTrue(policy.delayBeforeWrite(now) >=
                HudModeEnforcementPolicy.CIRCUIT_BREAK_MS);
    }

    private static int rawSpeed(int kmh) {
        return (int) Math.ceil(kmh / HudModeEnforcementPolicy.SPEED_SCALE_KMH);
    }
}
