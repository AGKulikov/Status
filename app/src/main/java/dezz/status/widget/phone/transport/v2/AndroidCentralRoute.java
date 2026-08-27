/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package dezz.status.widget.phone.transport.v2;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Pure lifecycle reducer for Android-central / iPhone-Helper-peripheral.
 *
 * <p>There is one public-GATT owner per attempt.  Every platform callback carries the exact
 * epoch/owner/operation token which emitted it.  No device name, BLE address, hidden API, or
 * alternate topology is ever used as a fallback.</p>
 */
public final class AndroidCentralRoute {
    public static final long SCAN_TIMEOUT_MS = 15_000L;
    /** A saved identity recovery scan is only a presence probe, never a long idle state. */
    public static final long ENROLLED_SCAN_TIMEOUT_MS = 8_000L;
    /** Start exact enrolled-presence recovery sooner than Android's late 30 s GATT 133 callback. */
    public static final long CONNECT_TIMEOUT_MS = 8_000L;
    public static final long DISCOVERY_TIMEOUT_MS = 8_000L;
    public static final long PROOF_TIMEOUT_MS = 5_000L;
    public static final long CCCD_TIMEOUT_MS = 5_000L;
    public static final long STOP_TIMEOUT_MS = 4_000L;
    /** Lets Android P retire the replaced APK process's native client before a fresh registration. */
    public static final long STARTUP_QUIET_MS = 3_000L;
    public static final long MAX_RETRY_MS = 15_000L;
    /** Autonomous retained-owner probe after the bounded fast reassert ladder is exhausted. */
    public static final long WAIT_SYSTEM_RECOVERY_MS = 180_000L;
    /** Enrolled Route A must recover during one ignition cycle, not after multi-minute silence. */
    public static final long ENROLLED_WAIT_SYSTEM_RECOVERY_MS = 5_000L;
    /** A registered Android-P wrapper is retried without allocating another clientIf. */
    public static final long REGISTERED_ERROR_RECOVERY_MS = 5_000L;
    public static final int MAX_ATTEMPTS_PER_EPOCH = 6;
    private static final long[] SAME_OWNER_REASSERT_MS = {30_000L, 60_000L, 120_000L};
    private static final long[] REGISTERED_ERROR_REASSERT_MS = {1_000L, 3_000L};
    /** Two fast reassertions plus one stack-recovery window, then retire the proven wrapper. */
    private static final int REGISTERED_ERROR_RETIRE_AFTER_REASSERTIONS = 3;

    public enum Phase {
        WAIT_RADIO,
        STARTUP_QUIET,
        SCANNING,
        CONNECTING,
        DISCOVERING,
        SUBSCRIBING_SERVICE_CHANGED,
        VERIFYING_PEER,
        SUBSCRIBING_ROUTE_CONTROL,
        SUBSCRIBING_TELEMETRY,
        SUBSCRIBING_NOTIFICATION_SOURCE,
        SUBSCRIBING_DATA_SOURCE,
        WAIT_AUTHORIZATION,
        WAIT_ANCS,
        /** ANCS is absent and no Service Changed indication can make this link recoverable. */
        NEEDS_FRESH_LINK,
        READY,
        WAIT_REASSERT,
        /** Sole public owner is retained; Android may complete its private registration later. */
        WAIT_SYSTEM_CONNECTION,
        RETRY_DRAINING,
        RETRY_WAIT,
        STOPPING,
        STOPPED,
        FAILED
    }

    public enum AuthorizationStep {
        NONE,
        SERVICE_CHANGED_CCCD,
        PEER_PROOF_READ,
        ROUTE_CONTROL_CCCD,
        NOTIFICATION_SOURCE_CCCD,
        DATA_SOURCE_CCCD
    }

    public final static class State {
        public final BleRouteEpoch epoch;
        public final String selectedSystemBondAddress;
        public final String helperInstallationId;
        public final IphoneAcquisitionModeV2 acquisitionMode;
        public final Phase phase;
        public final BleRouteToken expected;
        public final long activeOwnerId;
        public final long nextOwnerId;
        public final int consecutiveFailures;
        public final int sameOwnerReassertions;
        public final boolean ancsAvailable;
        public final boolean serviceChangedArmed;
        public final long ownerOperationCursor;
        public final AuthorizationStep authorizationStep;
        public final int authorizationRetries;
        public final int invalidHandleRediscoveries;
        public final String detail;

        private State(BleRouteEpoch epoch, String selectedSystemBondAddress,
                      String helperInstallationId,
                      IphoneAcquisitionModeV2 acquisitionMode, Phase phase,
                      BleRouteToken expected, long activeOwnerId, long nextOwnerId,
                      int consecutiveFailures, int sameOwnerReassertions,
                      boolean ancsAvailable, long ownerOperationCursor, String detail) {
            this(epoch, selectedSystemBondAddress, helperInstallationId, acquisitionMode,
                    phase, expected, activeOwnerId, nextOwnerId, consecutiveFailures,
                    sameOwnerReassertions, ancsAvailable, false, ownerOperationCursor,
                    AuthorizationStep.NONE, 0, 0, detail);
        }

        private State(BleRouteEpoch epoch, String selectedSystemBondAddress,
                      String helperInstallationId, IphoneAcquisitionModeV2 acquisitionMode,
                      Phase phase, BleRouteToken expected, long activeOwnerId, long nextOwnerId,
                      int consecutiveFailures, int sameOwnerReassertions,
                      boolean ancsAvailable, boolean serviceChangedArmed,
                      long ownerOperationCursor,
                      AuthorizationStep authorizationStep, int authorizationRetries,
                      int invalidHandleRediscoveries, String detail) {
            this.epoch = epoch;
            this.selectedSystemBondAddress = selectedSystemBondAddress;
            this.helperInstallationId = helperInstallationId;
            this.acquisitionMode = acquisitionMode;
            this.phase = phase;
            this.expected = expected;
            this.activeOwnerId = activeOwnerId;
            this.nextOwnerId = nextOwnerId;
            this.consecutiveFailures = consecutiveFailures;
            this.sameOwnerReassertions = sameOwnerReassertions;
            this.ancsAvailable = ancsAvailable;
            this.serviceChangedArmed = serviceChangedArmed;
            this.ownerOperationCursor = ownerOperationCursor;
            this.authorizationStep = authorizationStep;
            this.authorizationRetries = authorizationRetries;
            this.invalidHandleRediscoveries = invalidHandleRediscoveries;
            this.detail = detail == null ? "" : detail;
        }

        public boolean isReady() {
            return phase == Phase.READY;
        }
    }

    private AndroidCentralRoute() {
    }

    /** Package-private deterministic boundary fixture; never used by a framework adapter. */
    static State withCursorsForTesting(State state, long nextOwnerId,
                                       long expectedOperationId) {
        BleRouteToken expected = state.expected == null ? null
                : token(state, state.expected.ownerId, expectedOperationId);
        return new State(state.epoch, state.selectedSystemBondAddress,
                state.helperInstallationId, state.acquisitionMode, state.phase, expected,
                state.activeOwnerId, nextOwnerId, state.consecutiveFailures,
                state.sameOwnerReassertions, state.ancsAvailable, state.serviceChangedArmed,
                expectedOperationId, state.authorizationStep, state.authorizationRetries,
                state.invalidHandleRediscoveries, state.detail);
    }

    public static BleRouteTransition<State> start(IphoneTransportStartRequest request) {
        Objects.requireNonNull(request, "request");
        if (!request.radioEnabled) {
            State state = new State(request.epoch, request.selectedSystemBondAddress,
                    request.helperInstallationId,
                    request.acquisitionMode, Phase.WAIT_RADIO, null, 0L, 1L, 0, 0,
                    false, 0L,
                    "radio off; fresh epoch required after radio on");
            return BleRouteTransition.accepted(state);
        }
        State initial = new State(request.epoch, request.selectedSystemBondAddress,
                request.helperInstallationId, request.acquisitionMode,
                Phase.STARTUP_QUIET, null, 0L, 1L, 0, 0, false, 0L, "");
        return beginAcquisition(initial, true);
    }

    public static BleRouteTransition<State> advertisement(State state, BleRouteToken token,
                                                           IphoneBleAdvertisement advertisement) {
        if (!expects(state, Phase.SCANNING, token) || advertisement == null
                || !advertisement.matchesForAndroidCentral()) {
            return BleRouteTransition.ignored(state);
        }
        if (!canAllocateOwner(state.nextOwnerId)) {
            return counterExhausted(state, token, "owner");
        }
        long gattOwner = state.nextOwnerId;
        BleRouteToken connect = token(state, gattOwner, 1L);
        State next = copyWithReassertions(state, Phase.CONNECTING, connect,
                gattOwner, afterOwnerAllocation(gattOwner), state.consecutiveFailures, 0,
                "exact v2 Helper advertisement");
        return BleRouteTransition.accepted(next,
                op(BleRouteEffect.Type.CANCEL_DEADLINE, token, "scan matched"),
                op(BleRouteEffect.Type.STOP_SCAN, token, "first exact match wins"),
                op(BleRouteEffect.Type.CONNECT_GATT, connect,
                        "public GATT; autoConnect=false; no address/name fallback"),
                BleRouteEffect.deadline(connect, CONNECT_TIMEOUT_MS));
    }

    public static BleRouteTransition<State> connected(State state, BleRouteToken token,
                                                       boolean success) {
        boolean exactConnect = state != null && state.phase == Phase.CONNECTING
                && state.expected != null && state.expected.sameOwner(token);
        boolean retainedOwnerCallback = state != null
                && (state.phase == Phase.WAIT_REASSERT
                    || state.phase == Phase.WAIT_SYSTEM_CONNECTION)
                && state.expected != null && state.expected.sameOwner(token);
        if (!exactConnect && !retainedOwnerCallback) return BleRouteTransition.ignored(state);
        BleRouteToken completed = state.expected;
        if (!success) return retry(state, completed,
                "connection callback failed; public registration is now proven");
        BleRouteToken discover = nextOperation(completed);
        if (discover == null) return counterExhausted(state, completed, "operation");
        State next = copy(state, Phase.DISCOVERING, discover, token.ownerId,
                state.nextOwnerId, state.consecutiveFailures, "connected");
        if (state.phase == Phase.WAIT_SYSTEM_CONNECTION
                && state.acquisitionMode == IphoneAcquisitionModeV2.ENROLLED_LE_IDENTITY) {
            return BleRouteTransition.accepted(next,
                    op(BleRouteEffect.Type.CANCEL_DEADLINE, completed,
                            "late system connection callback"),
                    op(BleRouteEffect.Type.STOP_SCAN, completed,
                            "retained owner connected; stop presence scan"),
                    op(BleRouteEffect.Type.DISCOVER_SERVICES, discover,
                            "discover exact public GATT database"),
                    BleRouteEffect.deadline(discover, DISCOVERY_TIMEOUT_MS));
        }
        return step(next, completed, discover, BleRouteEffect.Type.DISCOVER_SERVICES,
                DISCOVERY_TIMEOUT_MS, "discover exact public GATT database");
    }

    /** Android 9 status 133 proves that this exact wrapper owns a registered clientIf. */
    public static BleRouteTransition<State> registeredConnectionError133(
            State state, BleRouteToken ownerCallback) {
        return registeredConnectionFailure(state, ownerCallback,
                "Android GATT status 133");
    }

    /**
     * Android 9 can also register {@code mClientIf} while withholding every public callback.
     * Treat that positive registration proof exactly like status 133: retain and reassert the
     * sole wrapper instead of refreshing the cache, closing it, and allocating another client.
     */
    public static BleRouteTransition<State> registeredSilentConnection(
            State state, BleRouteToken ownerCallback) {
        return registeredConnectionFailure(state, ownerCallback,
                "Android registered GATT privately but withheld its callback");
    }

    private static BleRouteTransition<State> registeredConnectionFailure(
            State state, BleRouteToken ownerCallback, String failureDetail) {
        if (state == null || ownerCallback == null || state.expected == null
                || ownerCallback.mode != IphoneBleMode.ANDROID_CENTRAL
                || !state.epoch.equals(ownerCallback.epoch)
                || state.activeOwnerId != ownerCallback.ownerId
                || !state.expected.sameOwner(ownerCallback)
                || (state.phase != Phase.CONNECTING
                    && state.phase != Phase.WAIT_REASSERT
                    && state.phase != Phase.WAIT_SYSTEM_CONNECTION)) {
            return BleRouteTransition.ignored(state);
        }
        BleRouteToken completed = state.expected;
        if (state.sameOwnerReassertions >= REGISTERED_ERROR_RETIRE_AFTER_REASSERTIONS) {
            // mClientIf>0 or status=133 proves that close() can unregister this exact public
            // wrapper. Keeping it forever after an in-place APK update creates a permanent
            // CONNECTING→WAIT_SYSTEM_CONNECTION loop. Retire it once, wait for adapter teardown,
            // then let the ordinary bounded retry path allocate exactly one replacement owner.
            return retry(state, completed,
                    failureDetail + "; bounded registered wrapper retirement");
        }
        if (state.sameOwnerReassertions < REGISTERED_ERROR_REASSERT_MS.length) {
            int retryIndex = state.sameOwnerReassertions;
            BleRouteToken timer = nextOperation(completed);
            if (timer == null) return counterExhausted(state, completed, "operation");
            State waiting = copyWithReassertions(state, Phase.WAIT_REASSERT, timer,
                    ownerCallback.ownerId, state.nextOwnerId, state.consecutiveFailures,
                    retryIndex + 1,
                    failureDetail + "; same-wrapper retry scheduled");
            List<BleRouteEffect> effects = new ArrayList<>();
            effects.add(op(BleRouteEffect.Type.CANCEL_DEADLINE, completed,
                    "registered connection recovery"));
            if (retryIndex == 0) {
                effects.add(op(BleRouteEffect.Type.REPORT_ERROR, completed,
                        failureDetail + "; retaining the registered wrapper"));
            }
            effects.add(BleRouteEffect.retry(timer,
                    REGISTERED_ERROR_REASSERT_MS[retryIndex],
                    "same registered wrapper; no clientIf churn"));
            return new BleRouteTransition<>(waiting, effects, true);
        }

        BleRouteToken recovery = nextOperation(completed);
        if (recovery == null) return counterExhausted(state, completed, "operation");
        State waiting = copyWithReassertions(state, Phase.WAIT_SYSTEM_CONNECTION, recovery,
                ownerCallback.ownerId, state.nextOwnerId, state.consecutiveFailures,
                state.sameOwnerReassertions,
                failureDetail + " persists; retain one wrapper for stack recovery");
        List<BleRouteEffect> effects = new ArrayList<>();
        effects.add(op(BleRouteEffect.Type.CANCEL_DEADLINE, completed,
                "registered connection recovery"));
        if (state.sameOwnerReassertions == REGISTERED_ERROR_REASSERT_MS.length) {
            effects.add(op(BleRouteEffect.Type.REPORT_DOWN, completed,
                    "Android Bluetooth stack is retaining the old registration; "
                            + "automatic same-wrapper recovery remains active"));
        }
        if (state.acquisitionMode == IphoneAcquisitionModeV2.ENROLLED_LE_IDENTITY) {
            effects.add(op(BleRouteEffect.Type.START_SCAN, recovery,
                    "exact enrolled presence scan while retaining the registered wrapper"));
        }
        effects.add(BleRouteEffect.retry(recovery, REGISTERED_ERROR_RECOVERY_MS,
                "periodic same-wrapper recovery; radio reset remains the final fallback"));
        return new BleRouteTransition<>(waiting, effects, true);
    }

    /** Selected Classic facade cannot be used as an LE identity; fail without retry churn. */
    public static BleRouteTransition<State> selectedBondLeUnavailable(
            State state, BleRouteToken token, String detail) {
        if (!expects(state, Phase.CONNECTING, token) || state.activeOwnerId != token.ownerId) {
            return BleRouteTransition.ignored(state);
        }
        State failed = copyPolicy(state, Phase.FAILED, null, 0L, state.nextOwnerId,
                state.consecutiveFailures, AuthorizationStep.NONE, 0, 0, safe(detail));
        return BleRouteTransition.accepted(failed,
                op(BleRouteEffect.Type.CANCEL_DEADLINE, token,
                        "selected bond has no proven LE transport"));
    }

    /** Enrolled locator failed after attribution; require explicit recovery and close sole owner. */
    public static BleRouteTransition<State> enrolledLeUnavailable(
            State state, BleRouteToken token, String detail) {
        if (state == null || token == null || state.expected == null
                || !state.expected.sameOwner(token)
                || state.acquisitionMode != IphoneAcquisitionModeV2.ENROLLED_LE_IDENTITY
                || (state.phase != Phase.CONNECTING && state.phase != Phase.DISCOVERING
                && state.phase != Phase.VERIFYING_PEER)) {
            return BleRouteTransition.ignored(state);
        }
        State failed = copyPolicy(state, Phase.FAILED, null, 0L, state.nextOwnerId,
                state.consecutiveFailures, AuthorizationStep.NONE, 0, 0, safe(detail));
        List<BleRouteEffect> effects = new ArrayList<>();
        effects.add(op(BleRouteEffect.Type.CANCEL_DEADLINE, token,
                "enrolled LE route requires explicit recovery"));
        if (state.activeOwnerId != 0L) {
            effects.add(op(BleRouteEffect.Type.CLOSE_GATT, token,
                    "close sole enrolled LE owner"));
        }
        return new BleRouteTransition<>(failed, effects, true);
    }

    /** A real disconnect callback proves registration and permits bounded terminal replacement. */
    public static BleRouteTransition<State> linkLost(State state,
                                                      BleRouteToken ownerCallback,
                                                      String reason) {
        if (state == null || ownerCallback == null
                || ownerCallback.mode != IphoneBleMode.ANDROID_CENTRAL
                || !state.epoch.equals(ownerCallback.epoch)
                || state.activeOwnerId != ownerCallback.ownerId
                || !ownsGatt(state.phase)
                || state.phase == Phase.STOPPING || state.phase == Phase.RETRY_DRAINING) {
            return BleRouteTransition.ignored(state);
        }
        BleRouteToken failed = state.expected != null ? state.expected : ownerCallback;
        return retry(state, failed, "link lost: " + safe(reason));
    }

    public static BleRouteTransition<State> servicesDiscovered(State state, BleRouteToken token,
                                                                IphoneGattInventoryV2 inventory) {
        if (!expects(state, Phase.DISCOVERING, token)) return BleRouteTransition.ignored(state);
        if (inventory == null || !inventory.completeHelperV2()) {
            return retry(state, token, "incomplete Helper-v2 service graph");
        }
        State discovered = withGattInventory(state, inventory.completeAncs(), false);
        if (inventory.serviceChangedIndicatable) {
            BleRouteToken subscribe = nextOperation(token);
            if (subscribe == null) return counterExhausted(state, token, "operation");
            State next = copy(discovered, Phase.SUBSCRIBING_SERVICE_CHANGED, subscribe,
                    token.ownerId, state.nextOwnerId, state.consecutiveFailures,
                    "services complete; subscribe Service Changed");
            return step(next, token, subscribe,
                    BleRouteEffect.Type.SUBSCRIBE_GATT_SERVICE_CHANGED,
                    CCCD_TIMEOUT_MS, "enable exact-owner GATT Service Changed indication");
        }
        return beginPeerProof(discovered, token);
    }

    public static BleRouteTransition<State> serviceChangedSubscribed(
            State state, BleRouteToken token, GattResultV2 result) {
        if (!expects(state, Phase.SUBSCRIBING_SERVICE_CHANGED, token)) {
            return BleRouteTransition.ignored(state);
        }
        if (result != GattResultV2.SUCCESS) {
            return gattFailure(state, token, result,
                    AuthorizationStep.SERVICE_CHANGED_CCCD);
        }
        return beginPeerProof(withServiceChangedArmed(state, true), token);
    }

    public static BleRouteTransition<State> peerProof(State state, BleRouteToken token,
                                                       IphoneBlePeerProof proof,
                                                       GattResultV2 result) {
        if (!expects(state, Phase.VERIFYING_PEER, token)) {
            return BleRouteTransition.ignored(state);
        }
        if (result != GattResultV2.SUCCESS) {
            return gattFailure(state, token, result, AuthorizationStep.PEER_PROOF_READ);
        }
        if (proof == null || !proof.matches(state.helperInstallationId,
                BlePeerRole.IPHONE_HELPER_PERIPHERAL, IphoneBleMode.ANDROID_CENTRAL)) {
            return retry(state, token, "peer identity/role/protocol proof rejected");
        }
        boolean learned = state.helperInstallationId.isEmpty();
        State identityState = learned ? withHelperInstallationId(state, proof.peerId) : state;
        List<BleRouteEffect> effects = new ArrayList<>();
        effects.add(op(BleRouteEffect.Type.CANCEL_DEADLINE, token, "peer proof completed"));
        if (learned) {
            effects.add(op(BleRouteEffect.Type.REPORT_HELPER_ID_LEARNED, token, proof.peerId));
        }
        BleRouteToken subscribe = nextOperation(token);
        if (subscribe == null) return counterExhausted(state, token, "operation");
        State next = copy(identityState, Phase.SUBSCRIBING_ROUTE_CONTROL, subscribe,
                token.ownerId, state.nextOwnerId, state.consecutiveFailures,
                "peer verified; establish route close/ack channel");
        effects.add(op(BleRouteEffect.Type.SUBSCRIBE_ROUTE_CONTROL, subscribe,
                "mandatory v2 C/A control indication CCCD before switchable readiness"));
        effects.add(BleRouteEffect.deadline(subscribe, CCCD_TIMEOUT_MS));
        return new BleRouteTransition<>(next, effects, true);
    }

    public static BleRouteTransition<State> routeControlSubscribed(
            State state, BleRouteToken token, GattResultV2 result) {
        if (!expects(state, Phase.SUBSCRIBING_ROUTE_CONTROL, token)) {
            return BleRouteTransition.ignored(state);
        }
        if (result != GattResultV2.SUCCESS) {
            return gattFailure(state, token, result, AuthorizationStep.ROUTE_CONTROL_CCCD);
        }
        return beginTelemetrySubscription(state, token);
    }

    public static BleRouteTransition<State> telemetrySubscribed(
            State state, BleRouteToken token, GattResultV2 result) {
        if (!expects(state, Phase.SUBSCRIBING_TELEMETRY, token)) {
            return BleRouteTransition.ignored(state);
        }
        if (result != GattResultV2.SUCCESS) {
            BleRouteTransition<State> next = afterTelemetrySubscription(state, token);
            if (!next.accepted) return next;
            List<BleRouteEffect> effects = new ArrayList<>(next.effects);
            effects.add(op(BleRouteEffect.Type.REPORT_ERROR, token,
                    "optional Route-A telemetry CCCD unavailable: " + result));
            return new BleRouteTransition<>(next.state, effects, true);
        }
        return afterTelemetrySubscription(state, token);
    }

    private static BleRouteTransition<State> afterTelemetrySubscription(
            State state, BleRouteToken token) {
        if (!state.ancsAvailable) {
            if (!state.serviceChangedArmed) {
                State blocked = copy(state, Phase.NEEDS_FRESH_LINK, null, token.ownerId,
                        state.nextOwnerId, state.consecutiveFailures,
                        "ANCS absent and Service Changed unavailable; fresh link required");
                return BleRouteTransition.accepted(blocked,
                        op(BleRouteEffect.Type.CANCEL_DEADLINE, token,
                                "telemetry subscribed"),
                        op(BleRouteEffect.Type.REPORT_DOWN, token,
                                "ANCS absent without Service Changed; explicit fresh link required"));
            }
            State waiting = copy(state, Phase.WAIT_ANCS, null, token.ownerId,
                    state.nextOwnerId, state.consecutiveFailures,
                    "control ready; wait for Service Changed/ANCS");
            return BleRouteTransition.accepted(waiting,
                    op(BleRouteEffect.Type.CANCEL_DEADLINE, token, "telemetry subscribed"),
                    op(BleRouteEffect.Type.REPORT_DOWN, token,
                            "ANCS not published; no polling and no owner replacement"));
        }
        return beginNotificationSubscription(state, token);
    }

    /** Accepts telemetry only after the exact encrypted H/control/CCCD sequence is complete. */
    public static boolean acceptsTelemetry(State state, BleRouteToken ownerCallback) {
        if (state == null || ownerCallback == null
                || ownerCallback.mode != IphoneBleMode.ANDROID_CENTRAL
                || !state.epoch.equals(ownerCallback.epoch)
                || state.activeOwnerId != ownerCallback.ownerId) {
            return false;
        }
        if (state.phase == Phase.SUBSCRIBING_NOTIFICATION_SOURCE
                || state.phase == Phase.SUBSCRIBING_DATA_SOURCE
                || state.phase == Phase.WAIT_ANCS
                || state.phase == Phase.NEEDS_FRESH_LINK
                || state.phase == Phase.READY) {
            return true;
        }
        return state.phase == Phase.WAIT_AUTHORIZATION
                && (state.authorizationStep == AuthorizationStep.NOTIFICATION_SOURCE_CCCD
                    || state.authorizationStep == AuthorizationStep.DATA_SOURCE_CCCD);
    }

    /** One exact Service Changed indication schedules one serialized same-owner rediscovery. */
    public static BleRouteTransition<State> serviceChanged(State state,
                                                            BleRouteToken ownerCallback) {
        if (state == null || ownerCallback == null
                || ownerCallback.mode != IphoneBleMode.ANDROID_CENTRAL
                || !state.epoch.equals(ownerCallback.epoch)
                || state.activeOwnerId != ownerCallback.ownerId) {
            return BleRouteTransition.ignored(state);
        }
        if (state.phase != Phase.WAIT_ANCS && state.phase != Phase.READY) {
            if (state.expected != null && handlesInFlight(state.phase)) {
                return retry(state, state.expected,
                        "Service Changed invalidated an in-flight raw object");
            }
            return BleRouteTransition.ignored(state);
        }
        if (!hasOperationSuccessor(state.ownerOperationCursor)) {
            return counterExhausted(state, ownerCallback, "operation");
        }
        BleRouteToken discover = token(state, state.activeOwnerId,
                nextOperationId(state.ownerOperationCursor));
        State next = copyPolicy(withGattInventory(state, false, false), Phase.DISCOVERING,
                discover, state.activeOwnerId, state.nextOwnerId, state.consecutiveFailures,
                AuthorizationStep.NONE, 0, 0,
                "Service Changed; rediscover same owner once");
        return BleRouteTransition.accepted(next,
                op(BleRouteEffect.Type.RESET_SESSION_STATE, ownerCallback,
                        "ANCS epoch invalidated; clear all session-local data"),
                op(BleRouteEffect.Type.DISCOVER_SERVICES, discover,
                        "one same-owner rediscovery; no polling"),
                BleRouteEffect.deadline(discover, DISCOVERY_TIMEOUT_MS));
    }

    public static BleRouteTransition<State> notificationSourceSubscribed(
            State state, BleRouteToken token, GattResultV2 result) {
        if (!expects(state, Phase.SUBSCRIBING_NOTIFICATION_SOURCE, token)) {
            return BleRouteTransition.ignored(state);
        }
        if (result != GattResultV2.SUCCESS) {
            if (result == null || result == GattResultV2.TRANSIENT_FAILURE) {
                return retryAncsSubscriptionOnSameOwner(state, token,
                        AuthorizationStep.NOTIFICATION_SOURCE_CCCD,
                        Phase.SUBSCRIBING_NOTIFICATION_SOURCE,
                        BleRouteEffect.Type.SUBSCRIBE_ANCS_NOTIFICATION_SOURCE,
                        "Notification Source", "transient Notification Source CCCD failure");
            }
            return gattFailure(state, token, result,
                    AuthorizationStep.NOTIFICATION_SOURCE_CCCD);
        }
        BleRouteToken subscribe = nextOperation(token);
        if (subscribe == null) return counterExhausted(state, token, "operation");
        State next = copy(state, Phase.SUBSCRIBING_DATA_SOURCE, subscribe, token.ownerId,
                state.nextOwnerId, state.consecutiveFailures, "Notification Source subscribed");
        return step(next, token, subscribe, BleRouteEffect.Type.SUBSCRIBE_ANCS_DATA_SOURCE,
                CCCD_TIMEOUT_MS, "mandatory ANCS Data Source CCCD");
    }

    public static BleRouteTransition<State> dataSourceSubscribed(State state,
                                                                  BleRouteToken token,
                                                                  GattResultV2 result) {
        if (!expects(state, Phase.SUBSCRIBING_DATA_SOURCE, token)) {
            return BleRouteTransition.ignored(state);
        }
        if (result != GattResultV2.SUCCESS) {
            if (result == null || result == GattResultV2.TRANSIENT_FAILURE) {
                return retryAncsSubscriptionOnSameOwner(state, token,
                        AuthorizationStep.DATA_SOURCE_CCCD,
                        Phase.SUBSCRIBING_DATA_SOURCE,
                        BleRouteEffect.Type.SUBSCRIBE_ANCS_DATA_SOURCE,
                        "Data Source", "transient Data Source CCCD failure");
            }
            return gattFailure(state, token, result, AuthorizationStep.DATA_SOURCE_CCCD);
        }
        State next = copyPolicy(state, Phase.READY, null, token.ownerId, state.nextOwnerId,
                0, AuthorizationStep.NONE, 0, 0, "ready");
        return BleRouteTransition.accepted(next,
                op(BleRouteEffect.Type.CANCEL_DEADLINE, token, "ready"),
                op(BleRouteEffect.Type.REPORT_READY, token, "exact public-GATT owner ready"));
    }

    /** Explicit bond/authorization change retries the exact blocked operation at most once. */
    public static BleRouteTransition<State> authorizationChanged(State state,
                                                                  BleRouteToken token) {
        if (!expects(state, Phase.WAIT_AUTHORIZATION, token)
                || state.authorizationStep == AuthorizationStep.NONE
                || state.authorizationRetries >= 1) {
            return BleRouteTransition.ignored(state);
        }
        BleRouteToken operation = nextOperation(token);
        if (operation == null) return counterExhausted(state, token, "operation");
        Phase phase;
        BleRouteEffect.Type effect;
        switch (state.authorizationStep) {
            case SERVICE_CHANGED_CCCD:
                phase = Phase.SUBSCRIBING_SERVICE_CHANGED;
                effect = BleRouteEffect.Type.SUBSCRIBE_GATT_SERVICE_CHANGED;
                break;
            case PEER_PROOF_READ:
                phase = Phase.VERIFYING_PEER;
                effect = BleRouteEffect.Type.READ_PEER_PROOF;
                break;
            case ROUTE_CONTROL_CCCD:
                phase = Phase.SUBSCRIBING_ROUTE_CONTROL;
                effect = BleRouteEffect.Type.SUBSCRIBE_ROUTE_CONTROL;
                break;
            case NOTIFICATION_SOURCE_CCCD:
                phase = Phase.SUBSCRIBING_NOTIFICATION_SOURCE;
                effect = BleRouteEffect.Type.SUBSCRIBE_ANCS_NOTIFICATION_SOURCE;
                break;
            case DATA_SOURCE_CCCD:
                phase = Phase.SUBSCRIBING_DATA_SOURCE;
                effect = BleRouteEffect.Type.SUBSCRIBE_ANCS_DATA_SOURCE;
                break;
            default:
                return BleRouteTransition.ignored(state);
        }
        State retrying = copyPolicy(state, phase, operation, token.ownerId,
                state.nextOwnerId, state.consecutiveFailures, state.authorizationStep,
                state.authorizationRetries + 1, state.invalidHandleRediscoveries,
                "authorization changed; one exact-operation retry");
        return BleRouteTransition.accepted(retrying,
                op(effect, operation, "one explicit authorization retry"),
                BleRouteEffect.deadline(operation,
                        state.authorizationStep == AuthorizationStep.PEER_PROOF_READ
                                ? PROOF_TIMEOUT_MS : CCCD_TIMEOUT_MS));
    }

    /** Handles a connect/discovery/CCCD/scan watchdog; no operation may remain pending forever. */
    public static BleRouteTransition<State> deadline(State state, BleRouteToken token) {
        if (state.expected == null || !state.expected.equals(token)) {
            return BleRouteTransition.ignored(state);
        }
        if (state.phase == Phase.SUBSCRIBING_NOTIFICATION_SOURCE) {
            return retryAncsSubscriptionOnSameOwner(state, token,
                    AuthorizationStep.NOTIFICATION_SOURCE_CCCD,
                    Phase.SUBSCRIBING_NOTIFICATION_SOURCE,
                    BleRouteEffect.Type.SUBSCRIBE_ANCS_NOTIFICATION_SOURCE,
                    "Notification Source", "Notification Source CCCD callback timeout");
        }
        if (state.phase == Phase.SUBSCRIBING_DATA_SOURCE) {
            return retryAncsSubscriptionOnSameOwner(state, token,
                    AuthorizationStep.DATA_SOURCE_CCCD,
                    Phase.SUBSCRIBING_DATA_SOURCE,
                    BleRouteEffect.Type.SUBSCRIBE_ANCS_DATA_SOURCE,
                    "Data Source", "Data Source CCCD callback timeout");
        }
        if (state.phase == Phase.CONNECTING) {
            // A saved enrolled locator can be stale after iOS rotates its RPA.  Do not spend
            // the 30/60/120 second public-owner ladder against that silent address: retain the
            // sole GATT wrapper and let the already-implemented unfiltered exact-identity scan
            // resolve current presence immediately after the first connect watchdog.
            if (state.acquisitionMode == IphoneAcquisitionModeV2.ENROLLED_LE_IDENTITY
                    && state.sameOwnerReassertions == 0) {
                BleRouteToken recovery = nextOperation(token);
                if (recovery == null) return counterExhausted(state, token, "operation");
                State blocked = copyWithReassertions(state, Phase.WAIT_SYSTEM_CONNECTION,
                        recovery, token.ownerId, state.nextOwnerId,
                        state.consecutiveFailures, SAME_OWNER_REASSERT_MS.length,
                        "enrolled owner silent; presence scan armed after first watchdog");
                return BleRouteTransition.accepted(blocked,
                        op(BleRouteEffect.Type.CANCEL_DEADLINE, token, "connect watchdog"),
                        op(BleRouteEffect.Type.START_SCAN, recovery,
                                "unfiltered presence scan after first enrolled deadline; "
                                        + "retain sole GATT wrapper"),
                        BleRouteEffect.retry(recovery, waitSystemRecoveryMillis(state),
                                "autonomous retained-owner recovery; Classic is not required"));
            }
            if (state.sameOwnerReassertions < SAME_OWNER_REASSERT_MS.length) {
                BleRouteToken timer = nextOperation(token);
                if (timer == null) return counterExhausted(state, token, "operation");
                long delay = SAME_OWNER_REASSERT_MS[state.sameOwnerReassertions];
                State waiting = copyWithReassertions(state, Phase.WAIT_REASSERT, timer,
                        token.ownerId, state.nextOwnerId, state.consecutiveFailures,
                        state.sameOwnerReassertions + 1,
                        "sole public owner silent; bounded same-owner reassert scheduled");
                return BleRouteTransition.accepted(waiting,
                        op(BleRouteEffect.Type.CANCEL_DEADLINE, token, "connect watchdog"),
                        BleRouteEffect.retry(timer, delay,
                                "retain sole wrapper; never close while clientIf is unknown"));
            }
            BleRouteToken recovery = nextOperation(token);
            if (recovery == null) return counterExhausted(state, token, "operation");
            State blocked = copy(state, Phase.WAIT_SYSTEM_CONNECTION, recovery, token.ownerId,
                    state.nextOwnerId, state.consecutiveFailures,
                    "sole background owner retained; autonomous same-owner recovery armed");
            List<BleRouteEffect> effects = new ArrayList<>();
            effects.add(op(BleRouteEffect.Type.CANCEL_DEADLINE, token,
                    "connect watchdog"));
            effects.add(op(BleRouteEffect.Type.REPORT_ERROR, token,
                    "OWNER_UNPROVABLE: no second wrapper; periodic recovery remains armed"));
            if (state.acquisitionMode == IphoneAcquisitionModeV2.ENROLLED_LE_IDENTITY
                    && state.sameOwnerReassertions <= SAME_OWNER_REASSERT_MS.length) {
                effects.add(op(BleRouteEffect.Type.START_SCAN, recovery,
                        "one enrolled presence scan while retaining the sole GATT wrapper"));
            }
            effects.add(BleRouteEffect.retry(recovery, waitSystemRecoveryMillis(state),
                    "autonomous retained-owner recovery; Classic is not required"));
            return new BleRouteTransition<>(blocked, effects, true);
        }
        if (state.phase == Phase.STOPPING || state.phase == Phase.RETRY_DRAINING) {
            State failed = copy(state, Phase.FAILED, null, 0L, state.nextOwnerId,
                    state.consecutiveFailures, "teardown deadline expired; restart denied");
            return BleRouteTransition.accepted(failed,
                    op(BleRouteEffect.Type.REPORT_ERROR, token,
                            "local teardown not proven; fail closed"));
        }
        if (state.phase == Phase.SCANNING
                && state.acquisitionMode == IphoneAcquisitionModeV2.ENROLLED_LE_IDENTITY) {
            return retry(state, token,
                    "recovery scan timed out; unlock iPhone and open Helper; "
                            + "existing pair retained; do not re-enroll");
        }
        return retry(state, token, state.phase + " callback timeout");
    }

    public static BleRouteTransition<State> sameOwnerReassertElapsed(State state,
                                                                     BleRouteToken token) {
        if (!expects(state, Phase.WAIT_REASSERT, token)) {
            return BleRouteTransition.ignored(state);
        }
        BleRouteToken reconnect = nextOperation(token);
        if (reconnect == null) return counterExhausted(state, token, "operation");
        State connecting = copy(state, Phase.CONNECTING, reconnect, token.ownerId,
                state.nextOwnerId, state.consecutiveFailures,
                "same public BluetoothGatt.connect() reassertion");
        return BleRouteTransition.accepted(connecting,
                op(BleRouteEffect.Type.CANCEL_DEADLINE, token, "reassert timer"),
                op(BleRouteEffect.Type.REASSERT_SAME_GATT, reconnect,
                        "same wrapper only; false return is diagnostic, never create replacement"),
                BleRouteEffect.deadline(reconnect, CONNECT_TIMEOUT_MS));
    }

    /** Periodically reasserts an unprovable sole wrapper without allocating a second clientIf. */
    public static BleRouteTransition<State> systemConnectionRecoveryElapsed(
            State state, BleRouteToken token) {
        if (!expects(state, Phase.WAIT_SYSTEM_CONNECTION, token)) {
            return BleRouteTransition.ignored(state);
        }
        BleRouteToken reconnect = nextOperation(token);
        if (reconnect == null) return counterExhausted(state, token, "operation");
        int reassertions = state.acquisitionMode == IphoneAcquisitionModeV2.ENROLLED_LE_IDENTITY
                ? Math.min(Integer.MAX_VALUE, state.sameOwnerReassertions + 1)
                : state.sameOwnerReassertions;
        State connecting = copyWithReassertions(state, Phase.CONNECTING, reconnect,
                token.ownerId, state.nextOwnerId, state.consecutiveFailures, reassertions,
                "autonomous retained-owner recovery; exact enrolled identity");
        List<BleRouteEffect> effects = new ArrayList<>();
        effects.add(op(BleRouteEffect.Type.CANCEL_DEADLINE, token,
                "retained-owner recovery timer"));
        if (state.acquisitionMode == IphoneAcquisitionModeV2.ENROLLED_LE_IDENTITY) {
            effects.add(op(BleRouteEffect.Type.STOP_SCAN, token,
                    "presence window complete"));
        }
        effects.add(op(BleRouteEffect.Type.REASSERT_SAME_GATT, reconnect,
                "same wrapper only; recovery does not depend on Classic"));
        effects.add(BleRouteEffect.deadline(reconnect, CONNECT_TIMEOUT_MS));
        return new BleRouteTransition<>(connecting, effects, true);
    }

    private static long waitSystemRecoveryMillis(State state) {
        return state.acquisitionMode == IphoneAcquisitionModeV2.ENROLLED_LE_IDENTITY
                ? ENROLLED_WAIT_SYSTEM_RECOVERY_MS : WAIT_SYSTEM_RECOVERY_MS;
    }

    /** A stack-resolved enrolled advertisement brings forward the retained-owner reassert. */
    public static BleRouteTransition<State> systemConnectionAdvertisement(
            State state, BleRouteToken token) {
        if (!expects(state, Phase.WAIT_SYSTEM_CONNECTION, token)
                || state.acquisitionMode != IphoneAcquisitionModeV2.ENROLLED_LE_IDENTITY) {
            return BleRouteTransition.ignored(state);
        }
        BleRouteToken reconnect = nextOperation(token);
        if (reconnect == null) return counterExhausted(state, token, "operation");
        State connecting = copy(state, Phase.CONNECTING, reconnect, token.ownerId,
                state.nextOwnerId, state.consecutiveFailures,
                "stack-resolved enrolled advertisement; reassert sole wrapper");
        return BleRouteTransition.accepted(connecting,
                op(BleRouteEffect.Type.CANCEL_DEADLINE, token,
                        "enrolled device observed"),
                op(BleRouteEffect.Type.STOP_SCAN, token,
                        "first exact enrolled presence wins"),
                op(BleRouteEffect.Type.REASSERT_SAME_GATT, reconnect,
                        "same wrapper after stack identity resolution"),
                BleRouteEffect.deadline(reconnect, CONNECT_TIMEOUT_MS));
    }

    /**
     * Uses an exact selected-phone Classic edge only as a liveness prompt for the sole GATT owner.
     *
     * <p>The Classic profile is not LE identity proof.  It therefore cannot select a device,
     * allocate a wrapper, or advance authentication.  It may only bring forward the already
     * scheduled same-wrapper reassertion, including the first CONNECTING attempt and after the
     * ordinary silent-owner budget has entered {@link Phase#WAIT_SYSTEM_CONNECTION}.</p>
     */
    public static BleRouteTransition<State> selectedPhonePresent(State state) {
        if (state == null || state.expected == null
                || (state.phase != Phase.CONNECTING
                && state.phase != Phase.WAIT_REASSERT
                && state.phase != Phase.WAIT_SYSTEM_CONNECTION)) {
            return BleRouteTransition.ignored(state);
        }
        BleRouteToken previous = state.expected;
        BleRouteToken reconnect = nextOperation(previous);
        if (reconnect == null) return counterExhausted(state, previous, "operation");
        State connecting = copy(state, Phase.CONNECTING, reconnect, previous.ownerId,
                state.nextOwnerId, state.consecutiveFailures,
                "exact Classic presence; same public BluetoothGatt.connect() prompt");
        List<BleRouteEffect> effects = new ArrayList<>();
        effects.add(op(BleRouteEffect.Type.CANCEL_DEADLINE, previous,
                "selected phone is present"));
        if (state.phase == Phase.WAIT_SYSTEM_CONNECTION
                && state.acquisitionMode == IphoneAcquisitionModeV2.ENROLLED_LE_IDENTITY) {
            effects.add(op(BleRouteEffect.Type.STOP_SCAN, previous,
                    "Classic liveness prompt supersedes presence scan"));
        }
        effects.add(op(BleRouteEffect.Type.REASSERT_SAME_GATT, reconnect,
                "liveness only; retain sole wrapper and exact enrolled identity"));
        effects.add(BleRouteEffect.deadline(reconnect, CONNECT_TIMEOUT_MS));
        return new BleRouteTransition<>(connecting, effects, true);
    }

    public static BleRouteTransition<State> retryElapsed(State state, BleRouteToken token,
                                                          boolean radioEnabled) {
        if (!expects(state, Phase.RETRY_WAIT, token)) return BleRouteTransition.ignored(state);
        if (!radioEnabled) {
            State waiting = copy(state, Phase.WAIT_RADIO, null, 0L, state.nextOwnerId,
                    state.consecutiveFailures, "radio off; start again with a fresh epoch");
            return BleRouteTransition.accepted(waiting,
                    op(BleRouteEffect.Type.CANCEL_DEADLINE, token, "radio off"));
        }
        State base = copy(state, Phase.STARTUP_QUIET, null, 0L, state.nextOwnerId,
                state.consecutiveFailures, "retry");
        return beginAcquisition(base, false);
    }

    public static BleRouteTransition<State> startupQuietElapsed(State state,
                                                                 BleRouteToken token,
                                                                 boolean radioEnabled) {
        if (!expects(state, Phase.STARTUP_QUIET, token)) {
            return BleRouteTransition.ignored(state);
        }
        if (!radioEnabled) {
            State waiting = copy(state, Phase.WAIT_RADIO, null, 0L, state.nextOwnerId,
                    state.consecutiveFailures, "radio off; start again with a fresh epoch");
            return BleRouteTransition.accepted(waiting,
                    op(BleRouteEffect.Type.CANCEL_DEADLINE, token, "radio off"));
        }
        return beginDirectConnect(copy(state, Phase.CONNECTING, null, 0L,
                state.nextOwnerId, state.consecutiveFailures, "startup quiet complete"));
    }

    /** Proof that the failed scan/GATT Java owner is now terminal and cannot be reused. */
    public static BleRouteTransition<State> attemptTeardownComplete(State state,
                                                                    BleRouteToken token) {
        if (!expects(state, Phase.RETRY_DRAINING, token)) {
            return BleRouteTransition.ignored(state);
        }
        if (state.consecutiveFailures >= MAX_ATTEMPTS_PER_EPOCH) {
            State failed = copy(state, Phase.FAILED, null, 0L, state.nextOwnerId,
                    state.consecutiveFailures, "attempt budget exhausted; fresh epoch required");
            return BleRouteTransition.accepted(failed,
                    op(BleRouteEffect.Type.CANCEL_DEADLINE, token, "owner terminal"),
                    op(BleRouteEffect.Type.REPORT_ERROR, token,
                            "bounded retry budget exhausted after direct/scan reacquisition"));
        }
        if (!canAllocateOwner(state.nextOwnerId)) {
            return counterExhausted(state, token, "owner");
        }
        long timerOwner = state.nextOwnerId;
        BleRouteToken timer = token(state, timerOwner, 1L);
        State waiting = copy(state, Phase.RETRY_WAIT, timer, timerOwner,
                afterOwnerAllocation(timerOwner),
                state.consecutiveFailures, state.detail);
        return BleRouteTransition.accepted(waiting,
                op(BleRouteEffect.Type.CANCEL_DEADLINE, token, "old owner terminal"),
                BleRouteEffect.retry(timer, retryDelayMillis(state.consecutiveFailures),
                        state.detail));
    }

    public static BleRouteTransition<State> radioOff(State state, BleRouteEpoch epoch) {
        if (state == null || !state.epoch.equals(epoch)
                || state.phase == Phase.STOPPED || state.phase == Phase.FAILED) {
            return BleRouteTransition.ignored(state);
        }
        List<BleRouteEffect> effects = closeEffects(state, "radio off");
        State waiting = copy(state, Phase.WAIT_RADIO, null, 0L, state.nextOwnerId,
                state.consecutiveFailures, "fresh epoch required after radio on");
        effects.add(report(BleRouteEffect.Type.REPORT_DOWN, state, "radio off"));
        return new BleRouteTransition<>(waiting, effects, true);
    }

    public static BleRouteTransition<State> stop(State state, BleRouteEpoch epoch, String reason) {
        if (state == null || !state.epoch.equals(epoch)
                || state.phase == Phase.STOPPED || state.phase == Phase.STOPPING) {
            return BleRouteTransition.ignored(state);
        }
        if (state.phase == Phase.CONNECTING
                || state.phase == Phase.WAIT_SYSTEM_CONNECTION
                || state.phase == Phase.WAIT_REASSERT) {
            State failed = copy(state, Phase.FAILED, state.expected, state.activeOwnerId,
                    state.nextOwnerId, state.consecutiveFailures,
                    "unprovable public client owner blocks role switch");
            return BleRouteTransition.accepted(failed,
                    op(BleRouteEffect.Type.REPORT_ERROR, state.expected,
                            "OWNER_UNPROVABLE: no localTerminal may be emitted"));
        }
        if (!canAllocateOwner(state.nextOwnerId)) {
            return counterExhausted(state, state.expected, "owner");
        }
        long teardownOwner = state.nextOwnerId;
        BleRouteToken teardown = token(state, teardownOwner, 1L);
        List<BleRouteEffect> effects = closeEffects(state, "stop: " + safe(reason));
        effects.add(BleRouteEffect.deadline(teardown, STOP_TIMEOUT_MS));
        State stopping = copy(state, Phase.STOPPING, teardown, teardownOwner,
                afterOwnerAllocation(teardownOwner), state.consecutiveFailures, safe(reason));
        return new BleRouteTransition<>(stopping, effects, true);
    }

    /** Called only after the adapter has nulled every Java owner and stopped callbacks. */
    public static BleRouteTransition<State> localTeardownComplete(State state,
                                                                   BleRouteToken token) {
        if (!expects(state, Phase.STOPPING, token)) return BleRouteTransition.ignored(state);
        State stopped = copy(state, Phase.STOPPED, null, 0L, state.nextOwnerId,
                state.consecutiveFailures, "local terminal");
        return BleRouteTransition.accepted(stopped,
                op(BleRouteEffect.Type.CANCEL_DEADLINE, token, "teardown complete"),
                op(BleRouteEffect.Type.REPORT_LOCAL_TERMINAL, token,
                        "safe for switch coordinator localTerminal"));
    }

    private static BleRouteTransition<State> beginScan(State base) {
        if (!canAllocateOwner(base.nextOwnerId)) {
            return counterExhausted(base, base.expected, "owner");
        }
        long owner = base.nextOwnerId;
        BleRouteToken scan = token(base, owner, 1L);
        boolean enrolledRecovery = base.acquisitionMode
                == IphoneAcquisitionModeV2.ENROLLED_LE_IDENTITY;
        State state = copy(base, Phase.SCANNING, scan, owner, afterOwnerAllocation(owner),
                base.consecutiveFailures, enrolledRecovery
                        ? "unfiltered enrolled recovery scan; exact saved bonded identity only"
                        : "strict v2 scan");
        return BleRouteTransition.accepted(state,
                op(BleRouteEffect.Type.START_SCAN, scan,
                        enrolledRecovery
                                ? "unfiltered scan; accept only stack-resolved exact saved public "
                                + "identity + bonded facade"
                                : "service UUID + protocol + iPhone-Helper-peripheral role; "
                                + "no name match"),
                BleRouteEffect.deadline(scan,
                        enrolledRecovery ? ENROLLED_SCAN_TIMEOUT_MS : SCAN_TIMEOUT_MS));
    }

    private static BleRouteTransition<State> beginAcquisition(State base, boolean startup) {
        if (base.acquisitionMode == IphoneAcquisitionModeV2.EXPLICIT_BOOTSTRAP_SCAN) {
            return beginScan(base);
        }
        if (!startup) {
            // A registered callback proves the prior owner terminal. Give a stable saved identity
            // one fresh direct attempt before paying for an unfiltered presence window; a second
            // registered failure enters the exact-identity scan. A completely silent clientIf
            // follows the retained-owner path above instead and never allocates a second wrapper.
            if (base.acquisitionMode == IphoneAcquisitionModeV2.ENROLLED_LE_IDENTITY
                    && base.consecutiveFailures >= 2
                    && base.consecutiveFailures % 2 == 0) {
                return beginScan(base);
            }
            return beginDirectConnect(base);
        }
        if (!canAllocateOwner(base.nextOwnerId)) {
            return counterExhausted(base, base.expected, "owner");
        }
        long timerOwner = base.nextOwnerId;
        BleRouteToken timer = token(base, timerOwner, 1L);
        State quiet = copy(base, Phase.STARTUP_QUIET, timer, timerOwner,
                afterOwnerAllocation(timerOwner),
                base.consecutiveFailures, "startup/role-switch quiet");
        return BleRouteTransition.accepted(quiet,
                BleRouteEffect.retry(timer, STARTUP_QUIET_MS,
                        "do not inherit the replaced process's GATT owner"));
    }

    private static BleRouteTransition<State> beginDirectConnect(State base) {
        if (!canAllocateOwner(base.nextOwnerId)) {
            return counterExhausted(base, base.expected, "owner");
        }
        long owner = base.nextOwnerId;
        BleRouteToken connect = token(base, owner, 1L);
        State state = copyWithReassertions(base, Phase.CONNECTING, connect,
                owner, afterOwnerAllocation(owner), base.consecutiveFailures, 0,
                "selected-bond public GATT");
        return BleRouteTransition.accepted(state,
                op(BleRouteEffect.Type.CONNECT_SELECTED_BOND, connect,
                        "one public owner for selectedSystemBondAddress; autoConnect=false"
                                + " on the fast exact active attempt;"
                                + " enrolled retries may use exact-identity recovery scan"),
                BleRouteEffect.deadline(connect, CONNECT_TIMEOUT_MS));
    }

    private static BleRouteTransition<State> retry(State state, BleRouteToken failed,
                                                    String reason) {
        if (!canAllocateOwner(state.nextOwnerId)) {
            return counterExhausted(state, failed, "owner");
        }
        int failures = Math.min(Integer.MAX_VALUE, state.consecutiveFailures + 1);
        long teardownOwner = state.nextOwnerId;
        BleRouteToken teardown = token(state, teardownOwner, 1L);
        List<BleRouteEffect> effects = closeEffects(state, reason);
        effects.add(BleRouteEffect.deadline(teardown, STOP_TIMEOUT_MS));
        effects.add(op(BleRouteEffect.Type.REPORT_ERROR, failed, reason));
        State next = copyPolicy(state, Phase.RETRY_DRAINING, teardown,
                teardownOwner, afterOwnerAllocation(teardownOwner),
                failures, AuthorizationStep.NONE, 0, 0, reason);
        return new BleRouteTransition<>(next, effects, true);
    }

    public static long retryDelayMillis(int consecutiveFailures) {
        int shift = Math.max(0, Math.min(6, consecutiveFailures - 1));
        return Math.min(MAX_RETRY_MS, 500L << shift);
    }

    private static BleRouteTransition<State> step(State next, BleRouteToken completed,
                                                   BleRouteToken operation,
                                                   BleRouteEffect.Type type, long timeout,
                                                   String detail) {
        return BleRouteTransition.accepted(next,
                op(BleRouteEffect.Type.CANCEL_DEADLINE, completed, "operation completed"),
                op(type, operation, detail), BleRouteEffect.deadline(operation, timeout));
    }

    private static BleRouteTransition<State> beginPeerProof(State state,
                                                             BleRouteToken completed) {
        BleRouteToken proof = nextOperation(completed);
        if (proof == null) return counterExhausted(state, completed, "operation");
        State next = copy(state, Phase.VERIFYING_PEER, proof, completed.ownerId,
                state.nextOwnerId, state.consecutiveFailures, "services complete");
        return step(next, completed, proof, BleRouteEffect.Type.READ_PEER_PROOF,
                PROOF_TIMEOUT_MS,
                "read Helper installation record on exact encrypted bond owner");
    }

    private static BleRouteTransition<State> beginNotificationSubscription(
            State state, BleRouteToken completed) {
        BleRouteToken subscribe = nextOperation(completed);
        if (subscribe == null) return counterExhausted(state, completed, "operation");
        State next = copy(state, Phase.SUBSCRIBING_NOTIFICATION_SOURCE, subscribe,
                completed.ownerId, state.nextOwnerId, state.consecutiveFailures,
                "route control ready; begin ANCS subscriptions");
        return BleRouteTransition.accepted(next,
                op(BleRouteEffect.Type.CANCEL_DEADLINE, completed,
                        "telemetry subscribed"),
                op(BleRouteEffect.Type.ARM_ANCS_PARSER, subscribe,
                        "reset/arm parser before Notification Source CCCD"),
                op(BleRouteEffect.Type.SUBSCRIBE_ANCS_NOTIFICATION_SOURCE, subscribe,
                        "mandatory ANCS Notification Source CCCD"),
                BleRouteEffect.deadline(subscribe, CCCD_TIMEOUT_MS));
    }

    private static BleRouteTransition<State> beginTelemetrySubscription(
            State state, BleRouteToken completed) {
        BleRouteToken subscribe = nextOperation(completed);
        if (subscribe == null) return counterExhausted(state, completed, "operation");
        State next = copy(state, Phase.SUBSCRIBING_TELEMETRY, subscribe,
                completed.ownerId, state.nextOwnerId, state.consecutiveFailures,
                "route control ready; subscribe exact Helper telemetry");
        return step(next, completed, subscribe, BleRouteEffect.Type.SUBSCRIBE_TELEMETRY,
                CCCD_TIMEOUT_MS,
                "fixed eight-byte telemetry notification CCCD on authenticated owner");
    }

    private static BleRouteTransition<State> gattFailure(State state, BleRouteToken token,
                                                          GattResultV2 result,
                                                          AuthorizationStep step) {
        if (result == null) result = GattResultV2.TRANSIENT_FAILURE;
        if (result.authorizationFailure()) {
            State waiting = copyPolicy(state, Phase.WAIT_AUTHORIZATION, token,
                    token.ownerId, state.nextOwnerId, state.consecutiveFailures, step,
                    state.authorizationRetries, state.invalidHandleRediscoveries,
                    result + "; retain sole owner and wait for explicit authorization change");
            return BleRouteTransition.accepted(waiting,
                    op(BleRouteEffect.Type.CANCEL_DEADLINE, token, "authorization gate"),
                    op(BleRouteEffect.Type.REPORT_DOWN, token,
                            result + ": no reconnect storm"));
        }
        if (result == GattResultV2.INVALID_HANDLE) {
            if (state.invalidHandleRediscoveries == 0) {
                BleRouteToken discover = nextOperation(token);
                if (discover == null) return counterExhausted(state, token, "operation");
                State rediscovering = copyPolicy(state, Phase.DISCOVERING, discover,
                        token.ownerId, state.nextOwnerId, state.consecutiveFailures,
                        AuthorizationStep.NONE, 0, 1,
                        "invalid handle; one same-owner rediscovery");
                return BleRouteTransition.accepted(rediscovering,
                        op(BleRouteEffect.Type.CANCEL_DEADLINE, token, "invalid handle"),
                        op(BleRouteEffect.Type.RESET_SESSION_STATE, token,
                                "invalidate all discovered objects"),
                        op(BleRouteEffect.Type.DISCOVER_SERVICES, discover,
                                "single same-owner invalid-handle recovery"),
                        BleRouteEffect.deadline(discover, DISCOVERY_TIMEOUT_MS));
            }
            return retry(state, token, "repeated invalid handle after same-owner rediscovery");
        }
        return retry(state, token, "transient GATT failure during " + step);
    }

    /**
     * Android 9 can return one late transient result while enabling either mandatory ANCS CCCD,
     * even though the authenticated CONTROL/telemetry owner is still usable. Re-serialize that
     * exact operation once. A repeated failure stays explicitly down without closing the owner
     * or manufacturing a false READY transition.
     */
    private static BleRouteTransition<State> retryAncsSubscriptionOnSameOwner(
            State state, BleRouteToken completed, AuthorizationStep step, Phase retryPhase,
            BleRouteEffect.Type retryEffect, String sourceName, String reason) {
        int retries = state.authorizationStep == step
                ? state.authorizationRetries : 0;
        if (retries >= 1) {
            State waiting = copyPolicy(state, Phase.WAIT_ANCS, null,
                    completed.ownerId, state.nextOwnerId, state.consecutiveFailures,
                    step, 1,
                    state.invalidHandleRediscoveries,
                    reason + "; exact owner retained for Service Changed recovery");
            return BleRouteTransition.accepted(waiting,
                    op(BleRouteEffect.Type.CANCEL_DEADLINE, completed,
                            "bounded " + sourceName + " retry exhausted"),
                    op(BleRouteEffect.Type.REPORT_ERROR, completed,
                            reason + "; CONTROL/telemetry retained; ANCS remains down"),
                    op(BleRouteEffect.Type.REPORT_DOWN, completed,
                            "ANCS " + sourceName + " unavailable; wait for Service Changed"));
        }
        BleRouteToken retry = nextOperation(completed);
        if (retry == null) return counterExhausted(state, completed, "operation");
        State retrying = copyPolicy(state, retryPhase, retry,
                completed.ownerId, state.nextOwnerId, state.consecutiveFailures,
                step, 1,
                state.invalidHandleRediscoveries,
                reason + "; one same-owner retry");
        return BleRouteTransition.accepted(retrying,
                op(BleRouteEffect.Type.CANCEL_DEADLINE, completed,
                        "serialize same-owner " + sourceName + " retry"),
                op(retryEffect, retry,
                        "one bounded " + sourceName + " CCCD retry on existing owner"),
                BleRouteEffect.deadline(retry, CCCD_TIMEOUT_MS));
    }

    private static List<BleRouteEffect> closeEffects(State state, String reason) {
        List<BleRouteEffect> effects = new ArrayList<>();
        if (state.expected != null) {
            effects.add(op(BleRouteEffect.Type.CANCEL_DEADLINE, state.expected, reason));
        }
        if (state.activeOwnerId > 0L) {
            BleRouteToken owner = token(state, state.activeOwnerId, 1L);
            if (state.phase == Phase.SCANNING) {
                effects.add(op(BleRouteEffect.Type.STOP_SCAN, owner, reason));
            } else if (ownsGatt(state.phase)) {
                effects.add(op(BleRouteEffect.Type.RESET_SESSION_STATE, owner,
                        "clear UID queues/parsers/fragments before owner close"));
                effects.add(op(BleRouteEffect.Type.CLOSE_GATT, owner, reason));
            }
        }
        return effects;
    }

    private static BleRouteEffect report(BleRouteEffect.Type type, State state, String detail) {
        long owner = state.activeOwnerId > 0L ? state.activeOwnerId : 1L;
        return op(type, token(state, owner, 1L), detail);
    }

    private static BleRouteEffect op(BleRouteEffect.Type type, BleRouteToken token, String detail) {
        return BleRouteEffect.operation(type, token, detail);
    }

    private static boolean expects(State state, Phase phase, BleRouteToken token) {
        return state != null && state.phase == phase && state.expected != null
                && state.expected.equals(token);
    }

    private static BleRouteToken nextOperation(BleRouteToken current) {
        if (current == null || !hasOperationSuccessor(current.operationId)) return null;
        return new BleRouteToken(current.mode, current.epoch,
                current.ownerId, nextOperationId(current.operationId));
    }

    private static boolean canAllocateOwner(long nextOwnerId) {
        return nextOwnerId > 0L && nextOwnerId < Long.MAX_VALUE;
    }

    private static long afterOwnerAllocation(long nextOwnerId) {
        if (!canAllocateOwner(nextOwnerId)) throw new AssertionError("unguarded owner cursor");
        return nextOwnerId + 1L;
    }

    private static boolean hasOperationSuccessor(long operationId) {
        return operationId > 0L && operationId < Long.MAX_VALUE - 1L;
    }

    private static long nextOperationId(long operationId) {
        if (!hasOperationSuccessor(operationId)) {
            throw new AssertionError("unguarded operation cursor");
        }
        return operationId + 1L;
    }

    private static BleRouteTransition<State> counterExhausted(
            State state, BleRouteToken evidence, String kind) {
        String detail = kind + " cursor exhausted; fresh route epoch required";
        BleRouteToken report = evidence != null ? evidence : diagnosticToken(state);
        State failed = copyPolicy(state, Phase.FAILED, null, state.activeOwnerId,
                state.nextOwnerId, state.consecutiveFailures, AuthorizationStep.NONE,
                0, state.invalidHandleRediscoveries, detail);
        List<BleRouteEffect> effects = new ArrayList<>();
        if (state.expected != null) {
            effects.add(op(BleRouteEffect.Type.CANCEL_DEADLINE,
                    state.expected, "counter exhausted"));
        }
        effects.add(op(BleRouteEffect.Type.REPORT_ERROR, report, detail));
        return new BleRouteTransition<>(failed, effects, true);
    }

    private static BleRouteToken diagnosticToken(State state) {
        long owner = state.activeOwnerId > 0L ? state.activeOwnerId : 1L;
        return token(state, owner, 1L);
    }

    private static BleRouteToken token(State state, long owner, long operation) {
        return new BleRouteToken(IphoneBleMode.ANDROID_CENTRAL,
                state.epoch, owner, operation);
    }

    private static State copy(State state, Phase phase, BleRouteToken expected,
                              long activeOwnerId, long nextOwnerId, int failures,
                              String detail) {
        long cursor = activeOwnerId == state.activeOwnerId ? state.ownerOperationCursor : 0L;
        if (expected != null && expected.ownerId == activeOwnerId) {
            cursor = Math.max(cursor, expected.operationId);
        }
        return new State(state.epoch, state.selectedSystemBondAddress,
                state.helperInstallationId, state.acquisitionMode,
                phase, expected,
                activeOwnerId, nextOwnerId, failures, state.sameOwnerReassertions,
                state.ancsAvailable, state.serviceChangedArmed, cursor,
                state.authorizationStep,
                state.authorizationRetries, state.invalidHandleRediscoveries, detail);
    }

    private static State copyPolicy(State state, Phase phase, BleRouteToken expected,
                                    long activeOwnerId, long nextOwnerId, int failures,
                                    AuthorizationStep authorizationStep,
                                    int authorizationRetries,
                                    int invalidHandleRediscoveries,
                                    String detail) {
        long cursor = activeOwnerId == state.activeOwnerId ? state.ownerOperationCursor : 0L;
        if (expected != null && expected.ownerId == activeOwnerId) {
            cursor = Math.max(cursor, expected.operationId);
        }
        return new State(state.epoch, state.selectedSystemBondAddress,
                state.helperInstallationId, state.acquisitionMode, phase, expected,
                activeOwnerId, nextOwnerId, failures, state.sameOwnerReassertions,
                state.ancsAvailable, state.serviceChangedArmed, cursor,
                authorizationStep, authorizationRetries,
                invalidHandleRediscoveries, detail);
    }

    private static State copyWithReassertions(State state, Phase phase,
                                              BleRouteToken expected, long activeOwnerId,
                                              long nextOwnerId, int failures, int reassertions,
                                              String detail) {
        return new State(state.epoch, state.selectedSystemBondAddress,
                state.helperInstallationId, state.acquisitionMode, phase, expected,
                activeOwnerId, nextOwnerId, failures, reassertions,
                state.ancsAvailable, state.serviceChangedArmed,
                expected != null && expected.ownerId == activeOwnerId
                        ? Math.max(activeOwnerId == state.activeOwnerId
                            ? state.ownerOperationCursor : 0L, expected.operationId)
                        : activeOwnerId == state.activeOwnerId
                            ? state.ownerOperationCursor : 0L,
                state.authorizationStep, state.authorizationRetries,
                state.invalidHandleRediscoveries, detail);
    }

    private static State withHelperInstallationId(State state, String helperInstallationId) {
        return new State(state.epoch, state.selectedSystemBondAddress,
                IphoneBleAdvertisement.normalizePeerId(helperInstallationId),
                state.acquisitionMode, state.phase, state.expected, state.activeOwnerId,
                state.nextOwnerId, state.consecutiveFailures, state.sameOwnerReassertions,
                state.ancsAvailable, state.serviceChangedArmed,
                state.ownerOperationCursor, state.authorizationStep,
                state.authorizationRetries, state.invalidHandleRediscoveries, state.detail);
    }

    private static State withGattInventory(State state, boolean ancsAvailable,
                                           boolean serviceChangedArmed) {
        return new State(state.epoch, state.selectedSystemBondAddress,
                state.helperInstallationId, state.acquisitionMode, state.phase, state.expected,
                state.activeOwnerId, state.nextOwnerId, state.consecutiveFailures,
                state.sameOwnerReassertions, ancsAvailable, serviceChangedArmed,
                state.ownerOperationCursor,
                state.authorizationStep, state.authorizationRetries,
                state.invalidHandleRediscoveries, state.detail);
    }

    private static State withServiceChangedArmed(State state, boolean armed) {
        return withGattInventory(state, state.ancsAvailable, armed);
    }

    private static boolean ownsGatt(Phase phase) {
        return phase == Phase.CONNECTING || phase == Phase.DISCOVERING
                || phase == Phase.SUBSCRIBING_SERVICE_CHANGED
                || phase == Phase.VERIFYING_PEER
                || phase == Phase.SUBSCRIBING_ROUTE_CONTROL
                || phase == Phase.SUBSCRIBING_TELEMETRY
                || phase == Phase.SUBSCRIBING_NOTIFICATION_SOURCE
                || phase == Phase.SUBSCRIBING_DATA_SOURCE || phase == Phase.WAIT_ANCS
                || phase == Phase.NEEDS_FRESH_LINK
                || phase == Phase.WAIT_AUTHORIZATION
                || phase == Phase.READY
                || phase == Phase.WAIT_REASSERT || phase == Phase.WAIT_SYSTEM_CONNECTION;
    }

    private static boolean handlesInFlight(Phase phase) {
        return phase == Phase.DISCOVERING || phase == Phase.SUBSCRIBING_SERVICE_CHANGED
                || phase == Phase.VERIFYING_PEER
                || phase == Phase.SUBSCRIBING_ROUTE_CONTROL
                || phase == Phase.SUBSCRIBING_TELEMETRY
                || phase == Phase.SUBSCRIBING_NOTIFICATION_SOURCE
                || phase == Phase.SUBSCRIBING_DATA_SOURCE;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
