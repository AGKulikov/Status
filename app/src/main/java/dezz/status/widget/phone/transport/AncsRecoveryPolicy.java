/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.phone.transport;

/** Pure decisions shared by the two ANCS recovery state machines. */
final class AncsRecoveryPolicy {
    enum PostTelemetryAction {
        FINISH_READY,
        CONTINUE_ACCEPTED_SERVICES,
        REDISCOVER_CURRENT_OWNER,
        WAIT
    }

    /** Decision for reconciling the bonded facade carried by an exact current-F04 PAIR write. */
    enum PairFacadeBindDecision {
        ALREADY_CURRENT,
        BIND_EXACT_REQUEST_FRESH_EPOCH,
        BIND_SOLE_ANONYMOUS_ALIAS,
        REJECT_ROUTE,
        REJECT_PUBLICATION,
        REJECT_IDENTITY,
        REJECT_VERIFIED_CONFLICT,
        REJECT_ALREADY_BOUND,
        REJECT_ANONYMOUS_ALIAS_COUNT
    }

    /** Fail-closed topology for carrying one immutable server transport facade into PAIR. */
    enum PhysicalFacadeTopologyDecision {
        CREATE_FROM_PAIR_ATT,
        USE_SOLE_ANONYMOUS,
        USE_SOLE_MATCHING,
        MERGE_ANONYMOUS_AND_MATCHING,
        REJECT
    }

    enum B3ReadAction {
        RETURN_ATT_STATUS_5,
        CONTINUE_SECURE_PROOF
    }

    /** One exact post-READY tuple may allocate one non-holding opportunistic registration. */
    enum ReverseClientOpenAction {
        OPPORTUNISTIC,
        STOP
    }

    enum GattCallbackDispatchAction {
        INLINE,
        POST_TO_TRANSPORT_LOOPER
    }

    private AncsRecoveryPolicy() {
    }

    /**
     * An optional B4 operation must first resume the discovery callback it interrupted. Only an
     * owner with no accepted service lineage may request a later serialized rediscovery.
     */
    static PostTelemetryAction afterHelperTelemetrySubscription(
            boolean gattReady,
            boolean ownsAcceptedDiscoveryLineage,
            boolean iphonePeripheralMode,
            boolean currentRouteMayRediscover) {
        if (gattReady) return PostTelemetryAction.FINISH_READY;
        if (ownsAcceptedDiscoveryLineage) {
            return PostTelemetryAction.CONTINUE_ACCEPTED_SERVICES;
        }
        if (iphonePeripheralMode && currentRouteMayRediscover) {
            return PostTelemetryAction.REDISCOVER_CURRENT_OWNER;
        }
        return PostTelemetryAction.WAIT;
    }

    /** Fresh status=22 recovery is legal only after all proofs belong to one later link epoch. */
    static boolean shouldReplaceStaleOwnerOnReady(
            boolean replacementPending,
            long replacementEpoch,
            long currentEpoch,
            boolean staleSlotIsExact,
            boolean clientConnected,
            boolean exactCurrentFacade,
            boolean currentSecureProof,
            boolean currentReadyProof) {
        return replacementPending
                && replacementEpoch != 0L
                && replacementEpoch == currentEpoch
                && staleSlotIsExact
                && !clientConnected
                && exactCurrentFacade
                && currentSecureProof
                && currentReadyProof;
    }

    static boolean replacementConsumedForEpoch(long consumedEpoch, long currentEpoch) {
        return currentEpoch != 0L && consumedEpoch == currentEpoch;
    }

    /** Coalesces the server-CONNECTED -> client-status=22 callback inversion. */
    static boolean status22MayUseAlreadyConnectedFacade(
            boolean exactFacadeConnected,
            boolean selectedBondedFacade,
            boolean currentSecurityEpoch,
            boolean currentF04Publication) {
        return exactFacadeConnected
                && selectedBondedFacade
                && currentSecurityEpoch
                && currentF04Publication;
    }

    /**
     * A pre-adoption disconnect is allowed to retire the anonymous aliases of the same incoming
     * security epoch only when no verified or client-role owner can still hold that physical link.
     */
    static boolean shouldRetirePreAdoptionAliases(
            boolean managedIncomingMode,
            boolean selectedBondedDisconnect,
            boolean verifiedPeerPresent,
            boolean establishedClientHandoff,
            boolean pendingClientHandoff) {
        return managedIncomingMode
                && selectedBondedDisconnect
                && !verifiedPeerPresent
                && !establishedClientHandoff
                && !pendingClientHandoff;
    }

    /**
     * A current B2 request is live ATT evidence, but it may only coalesce the one anonymous facade
     * which Android 9 emitted for that same link. It never weakens PAIR, B3 or READY themselves.
     */
    /** Compatibility overload: legacy callers treated any current facade as exact. */
    static PairFacadeBindDecision pairFacadeBindDecision(
            boolean currentPeerPresent,
            boolean managedIncomingMode,
            boolean currentF04Publication,
            boolean selectedBondedPeer,
            boolean conflictingVerifiedPeer,
            boolean conflictingCurrentPeer,
            boolean bindAlreadyConsumed,
            int currentAnonymousAliasCount) {
        return pairFacadeBindDecision(currentPeerPresent, currentPeerPresent,
                managedIncomingMode, currentF04Publication, selectedBondedPeer,
                conflictingVerifiedPeer, conflictingCurrentPeer, bindAlreadyConsumed,
                currentAnonymousAliasCount);
    }

    static PairFacadeBindDecision pairFacadeBindDecision(
            boolean currentPeerPresent,
            boolean exactCurrentRawFacade,
            boolean managedIncomingMode,
            boolean currentF04Publication,
            boolean selectedBondedPeer,
            boolean conflictingVerifiedPeer,
            boolean conflictingCurrentPeer,
            boolean bindAlreadyConsumed,
            int currentAnonymousAliasCount) {
        if (!managedIncomingMode) return PairFacadeBindDecision.REJECT_ROUTE;
        if (!currentF04Publication) return PairFacadeBindDecision.REJECT_PUBLICATION;
        if (!selectedBondedPeer) return PairFacadeBindDecision.REJECT_IDENTITY;
        if (conflictingVerifiedPeer) {
            return PairFacadeBindDecision.REJECT_VERIFIED_CONFLICT;
        }
        if (conflictingCurrentPeer) {
            return PairFacadeBindDecision.REJECT_VERIFIED_CONFLICT;
        }
        if (currentAnonymousAliasCount > 1) {
            return PairFacadeBindDecision.REJECT_ANONYMOUS_ALIAS_COUNT;
        }
        if (currentPeerPresent) {
            // Address equality is not raw callback ownership. Only the exact object is
            // idempotent; a new framework facade starts a clean B3/READY challenge epoch.
            return exactCurrentRawFacade
                    ? PairFacadeBindDecision.ALREADY_CURRENT
                    : PairFacadeBindDecision.BIND_EXACT_REQUEST_FRESH_EPOCH;
        }
        if (bindAlreadyConsumed) return PairFacadeBindDecision.REJECT_ALREADY_BOUND;
        if (currentAnonymousAliasCount == 0) {
            return PairFacadeBindDecision.BIND_EXACT_REQUEST_FRESH_EPOCH;
        }
        return PairFacadeBindDecision.BIND_SOLE_ANONYMOUS_ALIAS;
    }

    static boolean beginsFreshSecurityEpoch(PairFacadeBindDecision decision) {
        return decision == PairFacadeBindDecision.BIND_EXACT_REQUEST_FRESH_EPOCH
                || decision == PairFacadeBindDecision.BIND_SOLE_ANONYMOUS_ALIAS;
    }

    /**
     * The field sequence may contain A anonymous plus B bonded before PAIR wrapper C. These are
     * the only two-record aliases that may coalesce; every foreign or duplicate shape fails
     * closed before a clientIf budget is spent. A live PAIR ATT request may itself create the new
     * physical record after a confirmed prior-link DISCONNECTED when ECARX omits CONNECTED.
     */
    static PhysicalFacadeTopologyDecision physicalFacadeTopologyDecision(
            int currentRecordCount, int anonymousRecordCount,
            int matchingRecordCount, boolean conflictingRecord) {
        if (conflictingRecord || currentRecordCount < 0
                || anonymousRecordCount < 0 || matchingRecordCount < 0) {
            return PhysicalFacadeTopologyDecision.REJECT;
        }
        if (currentRecordCount == 0
                && anonymousRecordCount == 0 && matchingRecordCount == 0) {
            return PhysicalFacadeTopologyDecision.CREATE_FROM_PAIR_ATT;
        }
        if (currentRecordCount == 1 && anonymousRecordCount == 1
                && matchingRecordCount <= 1) {
            return PhysicalFacadeTopologyDecision.USE_SOLE_ANONYMOUS;
        }
        if (currentRecordCount == 1 && anonymousRecordCount == 0
                && matchingRecordCount == 1) {
            return PhysicalFacadeTopologyDecision.USE_SOLE_MATCHING;
        }
        if (currentRecordCount == 2 && anonymousRecordCount == 1
                && matchingRecordCount == 1) {
            return PhysicalFacadeTopologyDecision.MERGE_ANONYMOUS_AND_MATCHING;
        }
        return PhysicalFacadeTopologyDecision.REJECT;
    }

    static B3ReadAction b3ReadAction(boolean linkSecurityChallengeIssued) {
        return linkSecurityChallengeIssued
                ? B3ReadAction.CONTINUE_SECURE_PROOF
                : B3ReadAction.RETURN_ATT_STATUS_5;
    }

    /** Managed reverse ownership is proven only by the status-5 -> encrypted B3 READ sequence. */
    static boolean allowsB3WriteProof(boolean managedIncomingMode) {
        return !managedIncomingMode;
    }

    /**
     * Fluoride may allocate a new {@code BluetoothDevice} Java wrapper for each inbound ATT
     * request.  The wrapper is therefore not the server-link identity.  A B3/READY callback may
     * continue the PAIR transcript only when it resolves to the very same stable server-peer
     * record and every security/publication coordinate is still current.  The raw PAIR facade
     * remains identity-strict for the later app-owned client role.
     */
    static boolean acceptsInboundAttTranscriptCallback(
            boolean managedIncomingMode,
            boolean selectedBondedCallback,
            boolean acceptedPeerPresent,
            boolean callbackMapsToAcceptedPeer,
            boolean acceptedPeerOwnsRawPairFacade,
            long currentSession,
            long acceptedSession,
            long currentEpoch,
            long acceptedEpoch,
            long currentPublicationToken,
            long acceptedPublicationToken,
            boolean currentF04Publication) {
        return managedIncomingMode
                && selectedBondedCallback
                && acceptedPeerPresent
                && callbackMapsToAcceptedPeer
                && acceptedPeerOwnsRawPairFacade
                && currentSession != 0L
                && acceptedSession == currentSession
                && currentEpoch != 0L
                && acceptedEpoch == currentEpoch
                && currentPublicationToken != 0L
                && acceptedPublicationToken == currentPublicationToken
                && currentF04Publication;
    }

    static boolean canAcceptAncsReadyProof(
            boolean managedIncomingMode,
            boolean currentF04Publication,
            boolean currentPairProof,
            boolean secureAttConfirmed,
            boolean secureAttPublicationMatches,
            boolean verifiedPeer,
            boolean currentServerPeer,
            boolean exactPairRawFacade) {
        return managedIncomingMode
                && currentF04Publication
                && currentPairProof
                && secureAttConfirmed
                && secureAttPublicationMatches
                && verifiedPeer
                && currentServerPeer
                && exactPairRawFacade;
    }

    /**
     * The reverse managed route may allocate its first Android clientIf only after every proof
     * belongs to one exact F04 publication, security epoch and raw PAIR facade.
     */
    static boolean canStartReverseClientAttach(
            boolean managedIncomingMode,
            boolean currentF04Publication,
            long currentSession,
            long pairSession,
            long currentEpoch,
            long pairEpoch,
            long currentPublicationToken,
            long pairPublicationToken,
            boolean exactPairRawFacade,
            boolean currentServerPeer,
            boolean secureAttConfirmed,
            long securePublicationToken,
            boolean ancsReadyGateOpen,
            long readyPublicationToken) {
        return managedIncomingMode
                && currentF04Publication
                && currentSession != 0L
                && pairSession == currentSession
                && currentEpoch != 0L
                && pairEpoch == currentEpoch
                && currentPublicationToken != 0L
                && pairPublicationToken == currentPublicationToken
                && exactPairRawFacade
                && currentServerPeer
                && secureAttConfirmed
                && securePublicationToken == currentPublicationToken
                && ancsReadyGateOpen
                && readyPublicationToken == currentPublicationToken;
    }

    /** A BluetoothGatt callback is authoritative only for the exact post-READY attempt lineage. */
    static boolean acceptsReverseClientCallback(
            boolean callbackIsActiveWrapper,
            boolean callbackUsesAttemptRawFacade,
            long currentSession,
            long attemptSession,
            long currentEpoch,
            long attemptEpoch,
            long currentPublicationToken,
            long attemptPublicationToken,
            boolean currentF04Publication,
            boolean currentPairProof,
            boolean currentSecureProof,
            boolean currentReadyProof) {
        return callbackIsActiveWrapper
                && callbackUsesAttemptRawFacade
                && currentSession != 0L
                && attemptSession == currentSession
                && currentEpoch != 0L
                && attemptEpoch == currentEpoch
                && currentPublicationToken != 0L
                && attemptPublicationToken == currentPublicationToken
                && currentF04Publication
                && currentPairProof
                && currentSecureProof
                && currentReadyProof;
    }

    /** Attempt #1 is a capability owned only by the delayed, checked-READY task. */
    static boolean mayIssueReverseClientCommand(
            boolean currentTuple,
            boolean readyResponseBarrierPending,
            boolean firstAttachAttempt,
            boolean capturedFirstAttachAuthorization) {
        return currentTuple
                && !readyResponseBarrierPending
                && (!firstAttachAttempt || capturedFirstAttachAuthorization);
    }

    /**
     * Chooses the only legal allocation path for an exact captured PAIR/B3/READY tuple. Failure
     * cannot fall through to a public open: that would turn this opportunistic observer into another
     * physical-link owner and can tear down the still-working inbound server link on Android 9.
     */
    static ReverseClientOpenAction reverseClientOpenAction(
            boolean currentTuple,
            boolean capturedPostReadyAuthorization,
            boolean opportunisticAttempted) {
        if (!currentTuple || !capturedPostReadyAuthorization) {
            return ReverseClientOpenAction.STOP;
        }
        if (!opportunisticAttempted) {
            return ReverseClientOpenAction.OPPORTUNISTIC;
        }
        return ReverseClientOpenAction.STOP;
    }

    /** A framework callback already delivered on the transport looper must beat its timeout. */
    static GattCallbackDispatchAction gattCallbackDispatchAction(
            boolean callbackAlreadyOnTransportLooper) {
        return callbackAlreadyOnTransportLooper
                ? GattCallbackDispatchAction.INLINE
                : GattCallbackDispatchAction.POST_TO_TRANSPORT_LOOPER;
    }
}
