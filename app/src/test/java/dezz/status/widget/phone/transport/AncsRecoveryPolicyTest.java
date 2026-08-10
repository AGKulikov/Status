/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.phone.transport;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class AncsRecoveryPolicyTest {
    @Test public void b4CompletionAlwaysResumesItsAcceptedDiscoveryBeforeRediscovery() {
        assertEquals(AncsRecoveryPolicy.PostTelemetryAction.CONTINUE_ACCEPTED_SERVICES,
                AncsRecoveryPolicy.afterHelperTelemetrySubscription(
                        false, true, true, true));
        assertEquals(AncsRecoveryPolicy.PostTelemetryAction.CONTINUE_ACCEPTED_SERVICES,
                AncsRecoveryPolicy.afterHelperTelemetrySubscription(
                        false, true, true, false));
    }

    @Test public void readyWinsAndOnlyAProvenCurrentOwnerMayRediscover() {
        assertEquals(AncsRecoveryPolicy.PostTelemetryAction.FINISH_READY,
                AncsRecoveryPolicy.afterHelperTelemetrySubscription(
                        true, true, true, true));
        assertEquals(AncsRecoveryPolicy.PostTelemetryAction.REDISCOVER_CURRENT_OWNER,
                AncsRecoveryPolicy.afterHelperTelemetrySubscription(
                        false, false, true, true));
        assertEquals(AncsRecoveryPolicy.PostTelemetryAction.WAIT,
                AncsRecoveryPolicy.afterHelperTelemetrySubscription(
                        false, false, true, false));
        assertEquals(AncsRecoveryPolicy.PostTelemetryAction.WAIT,
                AncsRecoveryPolicy.afterHelperTelemetrySubscription(
                        false, false, false, true));
    }

    @Test public void staleOwnerReplacementRequiresOneExactFreshProofSet() {
        assertTrue(AncsRecoveryPolicy.shouldReplaceStaleOwnerOnReady(
                true, 42L, 42L, true, false, true, true, true));
        assertFalse(AncsRecoveryPolicy.shouldReplaceStaleOwnerOnReady(
                false, 42L, 42L, true, false, true, true, true));
        assertFalse(AncsRecoveryPolicy.shouldReplaceStaleOwnerOnReady(
                true, 41L, 42L, true, false, true, true, true));
        assertFalse(AncsRecoveryPolicy.shouldReplaceStaleOwnerOnReady(
                true, 42L, 42L, false, false, true, true, true));
        assertFalse(AncsRecoveryPolicy.shouldReplaceStaleOwnerOnReady(
                true, 42L, 42L, true, true, true, true, true));
        assertFalse(AncsRecoveryPolicy.shouldReplaceStaleOwnerOnReady(
                true, 42L, 42L, true, false, false, true, true));
        assertFalse(AncsRecoveryPolicy.shouldReplaceStaleOwnerOnReady(
                true, 42L, 42L, true, false, true, false, true));
        assertFalse(AncsRecoveryPolicy.shouldReplaceStaleOwnerOnReady(
                true, 42L, 42L, true, false, true, true, false));
    }

    @Test public void consumedEpochIsAnExactEpochMatchNotAPermanentLatch() {
        assertTrue(AncsRecoveryPolicy.replacementConsumedForEpoch(42L, 42L));
        assertFalse(AncsRecoveryPolicy.replacementConsumedForEpoch(42L, 43L));
        assertFalse(AncsRecoveryPolicy.replacementConsumedForEpoch(0L, 0L));
    }

    @Test public void status22CoalescesOnlyAnExactCurrentPublishedFacade() {
        assertTrue(AncsRecoveryPolicy.status22MayUseAlreadyConnectedFacade(
                true, true, true, true));
        assertFalse(AncsRecoveryPolicy.status22MayUseAlreadyConnectedFacade(
                false, true, true, true));
        assertFalse(AncsRecoveryPolicy.status22MayUseAlreadyConnectedFacade(
                true, false, true, true));
        assertFalse(AncsRecoveryPolicy.status22MayUseAlreadyConnectedFacade(
                true, true, false, true));
        assertFalse(AncsRecoveryPolicy.status22MayUseAlreadyConnectedFacade(
                true, true, true, false));
    }
}
