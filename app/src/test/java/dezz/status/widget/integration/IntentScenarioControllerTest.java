/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.integration;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class IntentScenarioControllerTest {
    @Test public void gateDebouncesPerRuleAndBlocksWhileInFlight() {
        IntentScenarioController.ClaimTracker gate =
                new IntentScenarioController.ClaimTracker(750L);

        assertTrue(gate.tryClaim("gate", 1_000L));
        assertFalse(gate.tryClaim("gate", 2_000L)); // still in flight, despite elapsed debounce
        assertTrue(gate.tryClaim("light", 2_000L)); // independent rule

        gate.release("gate");
        assertTrue(gate.tryClaim("gate", 2_001L));
        gate.release("gate");
        assertFalse(gate.tryClaim("gate", 2_500L));
        assertTrue(gate.tryClaim("gate", 2_751L));
    }

    @Test public void monotonicClockResetDoesNotCreateNegativeDebounceLockout() {
        IntentScenarioController.ClaimTracker gate =
                new IntentScenarioController.ClaimTracker(750L);
        assertTrue(gate.tryClaim("gate", 10_000L));
        gate.release("gate");

        assertTrue(gate.tryClaim("gate", 100L));
    }

    @Test public void hardDeadlineExpiresEvenIfConnectorLaterBecomesReady() {
        assertFalse(IntentScenarioController.isExpired(14_999L, 15_000L));
        assertTrue(IntentScenarioController.isExpired(15_000L, 15_000L));
        assertTrue(IntentScenarioController.isExpired(600_000L, 15_000L));
    }

    @Test public void coldStartKeepsOriginalBoundedBroadcastDeadline() {
        long deadline = IntentScenarioController.deadlineAfter(1_000L);
        assertTrue(IntentScenarioController.isAcceptableDeadline(1_001L, deadline));
        assertTrue(IntentScenarioController.isAcceptableDeadline(15_999L, deadline));
        assertFalse(IntentScenarioController.isAcceptableDeadline(16_000L, deadline));
        assertFalse(IntentScenarioController.isAcceptableDeadline(1_000L,
                deadline + 1L));
        assertFalse(IntentScenarioController.isAcceptableDeadline(1_000L, 1_000L));
    }
}
