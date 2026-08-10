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

    @Test public void preAdoptionAliasRetirementRequiresExactUnownedDisconnect() {
        assertTrue(AncsRecoveryPolicy.shouldRetirePreAdoptionAliases(
                true, true, false, false, false));
        assertFalse(AncsRecoveryPolicy.shouldRetirePreAdoptionAliases(
                false, true, false, false, false));
        assertFalse(AncsRecoveryPolicy.shouldRetirePreAdoptionAliases(
                true, false, false, false, false));
        assertFalse(AncsRecoveryPolicy.shouldRetirePreAdoptionAliases(
                true, true, true, false, false));
        assertFalse(AncsRecoveryPolicy.shouldRetirePreAdoptionAliases(
                true, true, false, true, false));
        assertFalse(AncsRecoveryPolicy.shouldRetirePreAdoptionAliases(
                true, true, false, false, true));
    }

    @Test public void exactPairMayBindOnlyOneCurrentAnonymousAlias() {
        assertEquals(
                AncsRecoveryPolicy.PairFacadeBindDecision.BIND_SOLE_ANONYMOUS_ALIAS,
                AncsRecoveryPolicy.pairFacadeBindDecision(
                        false, true, true, true, false, false, false, 1));
        assertEquals(
                AncsRecoveryPolicy.PairFacadeBindDecision.BIND_EXACT_REQUEST_FRESH_EPOCH,
                AncsRecoveryPolicy.pairFacadeBindDecision(
                        false, true, true, true, false, false, false, 0));
        assertEquals(
                AncsRecoveryPolicy.PairFacadeBindDecision.ALREADY_CURRENT,
                AncsRecoveryPolicy.pairFacadeBindDecision(
                        true, true, true, true, false, false, true, 0));
    }

    @Test public void exactPairFacadeBindRejectsEveryAmbiguousOrStaleInput() {
        assertEquals(AncsRecoveryPolicy.PairFacadeBindDecision.REJECT_PUBLICATION,
                AncsRecoveryPolicy.pairFacadeBindDecision(
                        false, true, false, true, false, false, false, 1));
        assertEquals(AncsRecoveryPolicy.PairFacadeBindDecision.REJECT_PUBLICATION,
                AncsRecoveryPolicy.pairFacadeBindDecision(
                        true, true, false, true, false, false, false, 1));
        assertEquals(AncsRecoveryPolicy.PairFacadeBindDecision.REJECT_IDENTITY,
                AncsRecoveryPolicy.pairFacadeBindDecision(
                        false, true, true, false, false, false, false, 1));
        assertEquals(AncsRecoveryPolicy.PairFacadeBindDecision.REJECT_IDENTITY,
                AncsRecoveryPolicy.pairFacadeBindDecision(
                        true, true, true, false, false, false, false, 1));
        assertEquals(AncsRecoveryPolicy.PairFacadeBindDecision.REJECT_ROUTE,
                AncsRecoveryPolicy.pairFacadeBindDecision(
                        false, false, true, true, false, false, false, 1));
        assertEquals(AncsRecoveryPolicy.PairFacadeBindDecision.REJECT_ROUTE,
                AncsRecoveryPolicy.pairFacadeBindDecision(
                        true, false, true, true, false, false, false, 1));
        assertEquals(AncsRecoveryPolicy.PairFacadeBindDecision.REJECT_VERIFIED_CONFLICT,
                AncsRecoveryPolicy.pairFacadeBindDecision(
                        false, true, true, true, true, false, false, 1));
        assertEquals(AncsRecoveryPolicy.PairFacadeBindDecision.REJECT_VERIFIED_CONFLICT,
                AncsRecoveryPolicy.pairFacadeBindDecision(
                        true, true, true, true, true, false, false, 1));
        assertEquals(AncsRecoveryPolicy.PairFacadeBindDecision.REJECT_VERIFIED_CONFLICT,
                AncsRecoveryPolicy.pairFacadeBindDecision(
                        false, true, true, true, false, true, false, 0));
        assertEquals(AncsRecoveryPolicy.PairFacadeBindDecision.REJECT_VERIFIED_CONFLICT,
                AncsRecoveryPolicy.pairFacadeBindDecision(
                        true, true, true, true, false, true, false, 0));
        assertEquals(AncsRecoveryPolicy.PairFacadeBindDecision.REJECT_ALREADY_BOUND,
                AncsRecoveryPolicy.pairFacadeBindDecision(
                        false, true, true, true, false, false, true, 1));
        assertEquals(AncsRecoveryPolicy.PairFacadeBindDecision.REJECT_ANONYMOUS_ALIAS_COUNT,
                AncsRecoveryPolicy.pairFacadeBindDecision(
                        false, true, true, true, false, false, false, 2));
        assertEquals(AncsRecoveryPolicy.PairFacadeBindDecision.REJECT_ANONYMOUS_ALIAS_COUNT,
                AncsRecoveryPolicy.pairFacadeBindDecision(
                        true, true, true, true, false, false, false, 2));
    }

    @Test public void zeroAliasPairClearsOldProofsBeforeSuccessAndChallengesImmediateB3() {
        long securityEpoch = 17L;
        boolean secureAttConfirmed = true;
        boolean readyGateOpen = true;
        boolean linkSecurityChallengeIssued = true;
        int pairAttStatus = -1;

        AncsRecoveryPolicy.PairFacadeBindDecision decision =
                AncsRecoveryPolicy.pairFacadeBindDecision(
                        false, true, true, true, false, false, false, 0);
        assertEquals(AncsRecoveryPolicy.PairFacadeBindDecision
                        .BIND_EXACT_REQUEST_FRESH_EPOCH,
                decision);

        // Production runs this reset synchronously on main before returning PAIR ATT success.
        if (AncsRecoveryPolicy.beginsFreshSecurityEpoch(decision)) {
            securityEpoch++;
            secureAttConfirmed = false;
            readyGateOpen = false;
            linkSecurityChallengeIssued = false;
            pairAttStatus = 0;
        }
        assertEquals(18L, securityEpoch);
        assertFalse(secureAttConfirmed);
        assertFalse(readyGateOpen);
        assertEquals(0, pairAttStatus);
        assertEquals(AncsRecoveryPolicy.B3ReadAction.RETURN_ATT_STATUS_5,
                AncsRecoveryPolicy.b3ReadAction(linkSecurityChallengeIssued));
        assertFalse(AncsRecoveryPolicy.canAcceptAncsReadyProof(
                true, true, secureAttConfirmed, false, true, true));
    }
}
