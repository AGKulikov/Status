/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package dezz.status.hudlab;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class HudModeEnforcementPolicyTest {
    @Test
    public void speedTriggerUsesHysteresisAndDoesNotFireForEverySample() {
        HudModeEnforcementPolicy policy = new HudModeEnforcementPolicy();
        int belowTrigger = rawSpeed(19.0d);
        int aboveTrigger = rawSpeed(21.0d);
        int belowRearm = rawSpeed(14.0d);

        assertFalse(policy.onRawSpeed(belowTrigger));
        assertTrue(policy.onRawSpeed(aboveTrigger));
        assertFalse(policy.onRawSpeed(aboveTrigger));
        assertFalse(policy.onRawSpeed(rawSpeed(16.0d)));
        assertFalse(policy.onRawSpeed(belowRearm));
        assertTrue(policy.onRawSpeed(aboveTrigger));
    }

    @Test
    public void minimumWriteIntervalIsEnforced() {
        HudModeEnforcementPolicy policy = new HudModeEnforcementPolicy();
        policy.recordWriteAttempt(10_000L);

        assertEquals(4_000L, policy.delayBeforeWrite(10_000L));
        assertEquals(1_000L, policy.delayBeforeWrite(13_000L));
        assertEquals(0L, policy.delayBeforeWrite(14_000L));
    }

    @Test
    public void fiveWritesPerMinuteOpenBudgetDelay() {
        HudModeEnforcementPolicy policy = new HudModeEnforcementPolicy();
        for (int index = 0; index < HudModeEnforcementPolicy.MAX_WRITES_PER_WINDOW; index++) {
            policy.recordWriteAttempt(index * 5_000L);
        }

        assertEquals(40_000L, policy.delayBeforeWrite(20_000L));
        assertEquals(0L, policy.delayBeforeWrite(60_000L));
    }

    @Test
    public void threeFailuresOpenCircuitForOneMinute() {
        HudModeEnforcementPolicy policy = new HudModeEnforcementPolicy();
        policy.recordWriteFailure(1_000L);
        policy.recordWriteFailure(2_000L);
        assertEquals(0L, policy.delayBeforeWrite(3_000L));

        policy.recordWriteFailure(3_000L);
        assertEquals(60_000L, policy.delayBeforeWrite(3_000L));
        assertEquals(1L, policy.delayBeforeWrite(62_999L));
        assertEquals(0L, policy.delayBeforeWrite(63_000L));
    }

    private static int rawSpeed(double kmh) {
        return (int) Math.ceil(kmh / HudModeEnforcementPolicy.SPEED_SCALE_KMH);
    }
}
