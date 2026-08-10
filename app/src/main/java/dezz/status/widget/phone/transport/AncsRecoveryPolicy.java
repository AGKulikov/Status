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
}
