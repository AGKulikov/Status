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

    @Test public void ha1211PhysicalFacadeTopologyReplaysAThenBThenPairCFailClosed() {
        assertEquals(AncsRecoveryPolicy.PhysicalFacadeTopologyDecision
                        .USE_SOLE_ANONYMOUS,
                AncsRecoveryPolicy.physicalFacadeTopologyDecision(
                        1, 1, 0, false));
        assertEquals(AncsRecoveryPolicy.PhysicalFacadeTopologyDecision
                        .USE_SOLE_MATCHING,
                AncsRecoveryPolicy.physicalFacadeTopologyDecision(
                        1, 0, 1, false));
        assertEquals(AncsRecoveryPolicy.PhysicalFacadeTopologyDecision
                        .MERGE_ANONYMOUS_AND_MATCHING,
                AncsRecoveryPolicy.physicalFacadeTopologyDecision(
                        2, 1, 1, false));
        assertEquals(AncsRecoveryPolicy.PhysicalFacadeTopologyDecision
                        .CREATE_FROM_PAIR_ATT,
                AncsRecoveryPolicy.physicalFacadeTopologyDecision(
                        0, 0, 0, false));
        assertEquals(AncsRecoveryPolicy.PhysicalFacadeTopologyDecision.REJECT,
                AncsRecoveryPolicy.physicalFacadeTopologyDecision(
                        3, 1, 1, false));
        assertEquals(AncsRecoveryPolicy.PhysicalFacadeTopologyDecision.REJECT,
                AncsRecoveryPolicy.physicalFacadeTopologyDecision(
                        2, 2, 0, false));
        assertEquals(AncsRecoveryPolicy.PhysicalFacadeTopologyDecision.REJECT,
                AncsRecoveryPolicy.physicalFacadeTopologyDecision(
                        2, 1, 1, true));
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

    @Test public void addressEqualNewRawFacadeStartsFreshChallengeInsteadOfInheritingReady() {
        assertEquals(AncsRecoveryPolicy.PairFacadeBindDecision
                        .BIND_EXACT_REQUEST_FRESH_EPOCH,
                AncsRecoveryPolicy.pairFacadeBindDecision(
                        true, false, true, true, true,
                        false, false, true, 0));

        boolean oldSecure = true;
        boolean oldReady = true;
        boolean challengeIssued = true;
        AncsRecoveryPolicy.PairFacadeBindDecision decision =
                AncsRecoveryPolicy.pairFacadeBindDecision(
                        true, false, true, true, true,
                        false, false, true, 0);
        if (AncsRecoveryPolicy.beginsFreshSecurityEpoch(decision)) {
            oldSecure = false;
            oldReady = false;
            challengeIssued = false;
        }
        assertFalse(oldSecure);
        assertFalse(oldReady);
        assertEquals(AncsRecoveryPolicy.B3ReadAction.RETURN_ATT_STATUS_5,
                AncsRecoveryPolicy.b3ReadAction(challengeIssued));
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
                true, true, true, secureAttConfirmed, false,
                true, true, true));
    }

    @Test public void managedRouteCannotUsePlainAncsWriteAsB3Proof() {
        assertFalse(AncsRecoveryPolicy.allowsB3WriteProof(true));
        assertTrue(AncsRecoveryPolicy.allowsB3WriteProof(false));
    }

    @Test public void inboundAttWrapperMayChangeOnlyWithinOneStablePairTranscript() {
        assertTrue(AncsRecoveryPolicy.acceptsInboundAttTranscriptCallback(
                true, true, true, true, true,
                7L, 7L, 3L, 3L, 1209L, 1209L, true));

        // A later Binder wrapper is safe only because it maps to the same stable server record.
        assertFalse(AncsRecoveryPolicy.acceptsInboundAttTranscriptCallback(
                true, true, true, false, true,
                7L, 7L, 3L, 3L, 1209L, 1209L, true));
        assertFalse(AncsRecoveryPolicy.acceptsInboundAttTranscriptCallback(
                true, false, true, true, true,
                7L, 7L, 3L, 3L, 1209L, 1209L, true));
        assertFalse(AncsRecoveryPolicy.acceptsInboundAttTranscriptCallback(
                true, true, true, true, true,
                7L, 7L, 4L, 3L, 1209L, 1209L, true));
        assertFalse(AncsRecoveryPolicy.acceptsInboundAttTranscriptCallback(
                true, true, true, true, true,
                7L, 7L, 3L, 3L, 1210L, 1209L, true));
        assertFalse(AncsRecoveryPolicy.acceptsInboundAttTranscriptCallback(
                true, true, true, true, false,
                7L, 7L, 3L, 3L, 1209L, 1209L, true));
        assertFalse(AncsRecoveryPolicy.acceptsInboundAttTranscriptCallback(
                false, true, true, true, true,
                7L, 7L, 3L, 3L, 1209L, 1209L, true));
        assertFalse(AncsRecoveryPolicy.acceptsInboundAttTranscriptCallback(
                true, true, false, true, true,
                7L, 7L, 3L, 3L, 1209L, 1209L, true));
        assertFalse(AncsRecoveryPolicy.acceptsInboundAttTranscriptCallback(
                true, true, true, true, true,
                0L, 0L, 3L, 3L, 1209L, 1209L, true));
        assertFalse(AncsRecoveryPolicy.acceptsInboundAttTranscriptCallback(
                true, true, true, true, true,
                7L, 6L, 3L, 3L, 1209L, 1209L, true));
        assertFalse(AncsRecoveryPolicy.acceptsInboundAttTranscriptCallback(
                true, true, true, true, true,
                7L, 7L, 0L, 0L, 1209L, 1209L, true));
        assertFalse(AncsRecoveryPolicy.acceptsInboundAttTranscriptCallback(
                true, true, true, true, true,
                7L, 7L, 3L, 3L, 0L, 0L, true));
        assertFalse(AncsRecoveryPolicy.acceptsInboundAttTranscriptCallback(
                true, true, true, true, true,
                7L, 7L, 3L, 3L, 1209L, 1209L, false));
    }

    @Test public void trace2342CannotAllocateClientIfBeforePairB3AndReady() {
        long session = 7L;
        long epoch = 3L;
        long publication = 1207L;
        long pairSession = 0L;
        long pairEpoch = 0L;
        long pairPublication = 0L;
        boolean exactRawFacade = false;
        boolean secure = false;
        boolean ready = false;
        int attempts = 0;

        // F04 publication and exact bonded CONNECTED only save a candidate.
        assertFalse(AncsRecoveryPolicy.canStartReverseClientAttach(
                true, true, session, pairSession, epoch, pairEpoch,
                publication, pairPublication, exactRawFacade, true,
                secure, 0L, ready, 0L));
        assertEquals(0, attempts);

        // PAIR owns the exact raw facade but still cannot allocate clientIf.
        pairSession = session;
        pairEpoch = epoch;
        pairPublication = publication;
        exactRawFacade = true;
        assertFalse(AncsRecoveryPolicy.canStartReverseClientAttach(
                true, true, session, pairSession, epoch, pairEpoch,
                publication, pairPublication, exactRawFacade, true,
                secure, 0L, ready, 0L));
        assertEquals(0, attempts);

        // Current encrypted B3 alone also remains pre-ready.
        secure = true;
        assertFalse(AncsRecoveryPolicy.canStartReverseClientAttach(
                true, true, session, pairSession, epoch, pairEpoch,
                publication, pairPublication, exactRawFacade, true,
                secure, publication, ready, 0L));
        assertEquals(0, attempts);

        // Only same-tuple READY opens attempt #1.
        ready = true;
        assertTrue(AncsRecoveryPolicy.canStartReverseClientAttach(
                true, true, session, pairSession, epoch, pairEpoch,
                publication, pairPublication, exactRawFacade, true,
                secure, publication, ready, publication));
        attempts++;
        assertEquals(1, attempts);
    }

    @Test public void attachAndCallbacksRequireExactSessionEpochPublicationAndRawFacade() {
        assertFalse(AncsRecoveryPolicy.canStartReverseClientAttach(
                true, true, 7L, 7L, 3L, 2L,
                1207L, 1207L, true, true,
                true, 1207L, true, 1207L));
        assertFalse(AncsRecoveryPolicy.canStartReverseClientAttach(
                true, true, 7L, 7L, 3L, 3L,
                1207L, 1207L, false, true,
                true, 1207L, true, 1207L));

        assertTrue(AncsRecoveryPolicy.acceptsReverseClientCallback(
                true, true, 7L, 7L, 3L, 3L,
                1207L, 1207L, true, true, true, true));
        assertFalse(AncsRecoveryPolicy.acceptsReverseClientCallback(
                true, true, 7L, 7L, 4L, 3L,
                1207L, 1207L, true, true, true, true));
        assertFalse(AncsRecoveryPolicy.acceptsReverseClientCallback(
                true, false, 7L, 7L, 3L, 3L,
                1207L, 1207L, true, true, true, true));
        assertFalse(AncsRecoveryPolicy.acceptsReverseClientCallback(
                true, true, 7L, 7L, 3L, 3L,
                1208L, 1207L, true, true, true, true));
        assertFalse(AncsRecoveryPolicy.acceptsReverseClientCallback(
                true, true, 7L, 7L, 3L, 3L,
                1207L, 1207L, true, true, true, false));
        assertFalse(AncsRecoveryPolicy.acceptsReverseClientCallback(
                true, true, 7L, 7L, 3L, 3L,
                1207L, 1207L, true, false, true, true));
        assertFalse(AncsRecoveryPolicy.acceptsReverseClientCallback(
                true, true, 7L, 7L, 3L, 3L,
                1207L, 1207L, true, true, false, true));
    }

    @Test public void duplicatePairAndReadyDoNotRefillAttemptsOrArmAnotherImmediateAttach() {
        int attempts = 2;
        boolean firstPairProof = false;
        boolean firstReadyProof = false;
        boolean readyAttachLatchAlreadyArmed = true;

        if (firstPairProof) attempts = 0;
        if (firstReadyProof) attempts = 0;
        boolean armAnotherImmediateAttach = !readyAttachLatchAlreadyArmed;

        assertEquals(2, attempts);
        assertFalse(armAnotherImmediateAttach);
    }

    @Test public void disconnectBeforeReadyBarrierMakesCapturedAttachANoop() {
        assertFalse(AncsRecoveryPolicy.canStartReverseClientAttach(
                true, true, 7L, 7L, 3L, 3L,
                1207L, 1207L, true, false,
                true, 1207L, true, 1207L));
    }

    @Test public void retainedOwnerRearmIsZeroBeforeReadyAndOneAfterExactReady() {
        boolean ready = false;
        int rawRearmCommands = 0;
        if (AncsRecoveryPolicy.acceptsReverseClientCallback(
                true, true, 7L, 7L, 3L, 3L,
                1207L, 1207L, true, true, true, ready)) {
            rawRearmCommands++;
        }
        assertEquals(0, rawRearmCommands);

        ready = true;
        if (AncsRecoveryPolicy.acceptsReverseClientCallback(
                true, true, 7L, 7L, 3L, 3L,
                1207L, 1207L, true, true, true, ready)) {
            rawRearmCommands++;
        }
        assertEquals(1, rawRearmCommands);
    }

    @Test public void capturedReadyTaskExclusivelyOwnsFirstCommandAcrossResponseBarrier() {
        assertFalse(AncsRecoveryPolicy.mayIssueReverseClientCommand(
                true, true, true, true));
        assertFalse(AncsRecoveryPolicy.mayIssueReverseClientCommand(
                true, false, true, false));
        assertTrue(AncsRecoveryPolicy.mayIssueReverseClientCommand(
                true, false, true, true));
        assertTrue(AncsRecoveryPolicy.mayIssueReverseClientCommand(
                true, false, false, false));
        assertFalse(AncsRecoveryPolicy.mayIssueReverseClientCommand(
                false, false, false, false));
    }

    @Test public void reverseTupleHasOneOpportunisticOpenAndNoFallbackState() {
        assertEquals(AncsRecoveryPolicy.ReverseClientOpenAction.OPPORTUNISTIC,
                AncsRecoveryPolicy.reverseClientOpenAction(true, true, false));
        assertEquals(AncsRecoveryPolicy.ReverseClientOpenAction.STOP,
                AncsRecoveryPolicy.reverseClientOpenAction(true, true, true));
        assertEquals(AncsRecoveryPolicy.ReverseClientOpenAction.STOP,
                AncsRecoveryPolicy.reverseClientOpenAction(true, false, false));
        assertEquals(AncsRecoveryPolicy.ReverseClientOpenAction.STOP,
                AncsRecoveryPolicy.reverseClientOpenAction(false, true, false));
    }

    @Test public void frameworkMainCallbackCommitsBeforeQueuedAttachTimeout() {
        assertEquals(AncsRecoveryPolicy.GattCallbackDispatchAction.INLINE,
                AncsRecoveryPolicy.gattCallbackDispatchAction(true));
        assertEquals(AncsRecoveryPolicy.GattCallbackDispatchAction.POST_TO_TRANSPORT_LOOPER,
                AncsRecoveryPolicy.gattCallbackDispatchAction(false));

        assertCallbackCancelsQueuedTimeoutBeforeItCanCloseOwner();
    }

    @Test public void discoveryCallbackBeforeWatchdogCannotPoisonTheOwner() {
        assertCallbackCancelsQueuedTimeoutBeforeItCanCloseOwner();
    }

    @Test public void descriptorCallbackBeforeWatchdogCannotPoisonTheOwner() {
        assertCallbackCancelsQueuedTimeoutBeforeItCanCloseOwner();
    }

    @Test public void rssiCallbackBeforeWatchdogCannotPoisonTheOwner() {
        assertCallbackCancelsQueuedTimeoutBeforeItCanCloseOwner();
    }

    private static void assertCallbackCancelsQueuedTimeoutBeforeItCanCloseOwner() {
        boolean[] timeoutArmed = {true};
        boolean[] wrapperClosed = {false};
        Runnable stackCallback = () -> timeoutArmed[0] = false;
        Runnable timeout = () -> {
            if (timeoutArmed[0]) wrapperClosed[0] = true;
        };

        // Framework callback A is already executing on main before queued timeout T. HA1210
        // commits every shared-GATT completion inline, so T sees its cancelled operation latch.
        stackCallback.run();
        timeout.run();
        assertFalse(wrapperClosed[0]);
    }
}
