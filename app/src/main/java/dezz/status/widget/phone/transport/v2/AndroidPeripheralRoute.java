/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package dezz.status.widget.phone.transport.v2;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Pure lifecycle reducer for Android-peripheral / iPhone-Helper-central.
 *
 * <p>The GATT server, advertiser, inbound peer facade, and one reverse ANCS-client observer have
 * separate owner identities.  The ECARX observer is an adapter detail: this reducer accepts one
 * exact owner or fails closed, and never races a public/hidden or other-topology fallback.</p>
 */
public final class AndroidPeripheralRoute {
    public static final long SERVER_OPEN_TIMEOUT_MS = 5_000L;
    public static final long SERVICE_ADD_TIMEOUT_MS = 5_000L;
    public static final long ADVERTISE_START_TIMEOUT_MS = 5_000L;
    public static final long INBOUND_TIMEOUT_MS = 30_000L;
    public static final long PEER_PROOF_TIMEOUT_MS = 5_000L;
    public static final long REVERSE_OWNER_TIMEOUT_MS = 8_000L;
    public static final long DISCOVERY_TIMEOUT_MS = 8_000L;
    public static final long CCCD_TIMEOUT_MS = 5_000L;
    public static final long STOP_TIMEOUT_MS = 4_000L;
    public static final long MAX_RETRY_MS = 15_000L;
    public static final int MAX_ATTEMPTS_PER_EPOCH = 6;

    public enum Phase {
        WAIT_RADIO,
        OPENING_SERVER,
        ADDING_SERVICE,
        STARTING_ADVERTISEMENT,
        ADVERTISING,
        WAITING_CONTROL_SUBSCRIPTION,
        WAITING_PEER_PROOF,
        WAITING_REVERSE_OWNER,
        DISCOVERING_ANCS,
        SUBSCRIBING_SERVICE_CHANGED,
        SUBSCRIBING_NOTIFICATION_SOURCE,
        SUBSCRIBING_DATA_SOURCE,
        WAIT_AUTHORIZATION,
        WAIT_ANCS,
        /** ANCS is absent and no Service Changed indication can make this link recoverable. */
        NEEDS_FRESH_LINK,
        READY,
        RETRY_DRAINING,
        RETRY_WAIT,
        /** Confirmed C/A switch: wait for the Helper Central to close inbound first. */
        SWITCH_WAIT_INBOUND_TERMINAL,
        STOPPING,
        STOPPED,
        FAILED
    }

    public enum AuthorizationStep {
        NONE,
        ROUTE_CONTROL_CCCD,
        SERVICE_CHANGED_CCCD,
        NOTIFICATION_SOURCE_CCCD,
        DATA_SOURCE_CCCD
    }

    public static final class State {
        public final BleRouteEpoch epoch;
        public final String selectedSystemBondAddress;
        public final String helperInstallationId;
        public final Phase phase;
        public final BleRouteToken expected;
        public final BleRouteToken serverOwner;
        public final BleRouteToken advertiserOwner;
        public final BleRouteToken inboundOwner;
        public final BleRouteToken reverseOwner;
        public final long nextOwnerId;
        public final int consecutiveFailures;
        public final boolean ancsAvailable;
        public final boolean serviceChangedArmed;
        public final AuthorizationStep authorizationStep;
        public final int authorizationRetries;
        public final int invalidHandleRediscoveries;
        public final String detail;

        private State(BleRouteEpoch epoch, String selectedSystemBondAddress,
                      String helperInstallationId, Phase phase, BleRouteToken expected,
                      BleRouteToken serverOwner, BleRouteToken advertiserOwner,
                      BleRouteToken inboundOwner, BleRouteToken reverseOwner,
                      long nextOwnerId, int consecutiveFailures,
                      boolean ancsAvailable, String detail) {
            this(epoch, selectedSystemBondAddress, helperInstallationId,
                    phase, expected, serverOwner, advertiserOwner,
                    inboundOwner, reverseOwner, nextOwnerId, consecutiveFailures,
                    ancsAvailable, false, AuthorizationStep.NONE, 0, 0, detail);
        }

        private State(BleRouteEpoch epoch, String selectedSystemBondAddress,
                      String helperInstallationId, Phase phase, BleRouteToken expected,
                      BleRouteToken serverOwner, BleRouteToken advertiserOwner,
                      BleRouteToken inboundOwner, BleRouteToken reverseOwner,
                      long nextOwnerId, int consecutiveFailures, boolean ancsAvailable,
                      boolean serviceChangedArmed,
                      AuthorizationStep authorizationStep, int authorizationRetries,
                      int invalidHandleRediscoveries, String detail) {
            this.epoch = epoch;
            this.selectedSystemBondAddress = selectedSystemBondAddress;
            this.helperInstallationId = helperInstallationId;
            this.phase = phase;
            this.expected = expected;
            this.serverOwner = serverOwner;
            this.advertiserOwner = advertiserOwner;
            this.inboundOwner = inboundOwner;
            this.reverseOwner = reverseOwner;
            this.nextOwnerId = nextOwnerId;
            this.consecutiveFailures = consecutiveFailures;
            this.ancsAvailable = ancsAvailable;
            this.serviceChangedArmed = serviceChangedArmed;
            this.authorizationStep = authorizationStep;
            this.authorizationRetries = authorizationRetries;
            this.invalidHandleRediscoveries = invalidHandleRediscoveries;
            this.detail = detail == null ? "" : detail;
        }

        public boolean isReady() {
            return phase == Phase.READY;
        }
    }

    private AndroidPeripheralRoute() {
    }

    /** Package-private deterministic boundary fixture; never used by a framework adapter. */
    static State withCursorsForTesting(State state, long nextOwnerId,
                                       long expectedOperationId) {
        BleRouteToken expected = state.expected == null ? null
                : token(state, state.expected.ownerId, expectedOperationId);
        return new State(state.epoch, state.selectedSystemBondAddress,
                state.helperInstallationId, state.phase, expected, state.serverOwner,
                state.advertiserOwner, state.inboundOwner, state.reverseOwner, nextOwnerId,
                state.consecutiveFailures, state.ancsAvailable, state.serviceChangedArmed,
                state.authorizationStep, state.authorizationRetries,
                state.invalidHandleRediscoveries, state.detail);
    }

    public static BleRouteTransition<State> start(IphoneTransportStartRequest request) {
        Objects.requireNonNull(request, "request");
        State initial = new State(request.epoch, request.selectedSystemBondAddress,
                request.helperInstallationId,
                request.radioEnabled ? Phase.OPENING_SERVER : Phase.WAIT_RADIO,
                null, null, null, null, null, 1L, 0, false,
                request.radioEnabled ? "" : "radio off; fresh epoch required after radio on");
        return request.radioEnabled ? beginServer(initial) : BleRouteTransition.accepted(initial);
    }

    public static BleRouteTransition<State> serverOpened(State state, BleRouteToken token,
                                                          boolean success) {
        if (!expects(state, Phase.OPENING_SERVER, token)) return BleRouteTransition.ignored(state);
        if (!success) return retry(state, token, "GATT server open failed");
        BleRouteToken add = nextOperation(token);
        if (add == null) return counterExhausted(state, token, "operation");
        State next = copy(state, Phase.ADDING_SERVICE, add, add, null, null, null,
                state.nextOwnerId, state.consecutiveFailures, "server open");
        return step(next, token, add, BleRouteEffect.Type.ADD_V2_SERVER_SERVICE,
                SERVICE_ADD_TIMEOUT_MS, "add fixed Android-peripheral v2 service once");
    }

    public static BleRouteTransition<State> serviceAdded(State state, BleRouteToken token,
                                                          boolean success) {
        if (!expects(state, Phase.ADDING_SERVICE, token)) return BleRouteTransition.ignored(state);
        if (!success) return retry(state, token, "v2 server service add failed");
        if (!canAllocateOwner(state.nextOwnerId)) {
            return counterExhausted(state, token, "owner");
        }
        long owner = state.nextOwnerId;
        BleRouteToken advertiser = token(state, owner, 1L);
        State next = copy(state, Phase.STARTING_ADVERTISEMENT, advertiser,
                state.serverOwner, advertiser, null, null, afterOwnerAllocation(owner),
                state.consecutiveFailures, "service published");
        return BleRouteTransition.accepted(next,
                op(BleRouteEffect.Type.CANCEL_DEADLINE, token, "service added"),
                BleRouteEffect.advertise(advertiser, advertisement(next)),
                BleRouteEffect.deadline(advertiser, ADVERTISE_START_TIMEOUT_MS));
    }

    public static BleRouteTransition<State> advertisingStarted(State state, BleRouteToken token,
                                                                boolean success) {
        if (!expects(state, Phase.STARTING_ADVERTISEMENT, token)) {
            return BleRouteTransition.ignored(state);
        }
        if (!success) return retry(state, token, "advertising start failed");
        if (!canAllocateOwner(state.nextOwnerId)) {
            return counterExhausted(state, token, "owner");
        }
        long timerOwner = state.nextOwnerId;
        BleRouteToken timer = token(state, timerOwner, 1L);
        State next = copy(state, Phase.ADVERTISING, timer, state.serverOwner,
                state.advertiserOwner, null, null, afterOwnerAllocation(timerOwner),
                state.consecutiveFailures, "waiting exact Helper Central");
        return BleRouteTransition.accepted(next,
                op(BleRouteEffect.Type.CANCEL_DEADLINE, token, "advertising started"),
                BleRouteEffect.deadline(timer, INBOUND_TIMEOUT_MS));
    }

    /** First inbound connection wins; all later server callbacks require its new exact token. */
    public static BleRouteTransition<State> inboundConnected(State state,
                                                              BleRouteToken serverCallbackOwner) {
        if (state == null || state.phase != Phase.ADVERTISING
                || state.serverOwner == null
                || !state.serverOwner.sameOwner(serverCallbackOwner)) {
            return BleRouteTransition.ignored(state);
        }
        if (!canAllocateOwner(state.nextOwnerId)) {
            return counterExhausted(state, state.expected, "owner");
        }
        long owner = state.nextOwnerId;
        BleRouteToken inbound = token(state, owner, 1L);
        State next = copy(state, Phase.WAITING_CONTROL_SUBSCRIPTION, inbound, state.serverOwner,
                state.advertiserOwner, inbound, null, afterOwnerAllocation(owner),
                state.consecutiveFailures,
                "inbound facade; require route control CCCD before hello");
        return BleRouteTransition.accepted(next,
                op(BleRouteEffect.Type.CANCEL_DEADLINE, state.expected, "inbound connected"),
                op(BleRouteEffect.Type.STOP_ADVERTISING, state.advertiserOwner,
                        "one inbound owner per publication epoch"),
                op(BleRouteEffect.Type.BIND_INBOUND_PEER, inbound,
                        "tag all peer callbacks with exact inbound token"),
                BleRouteEffect.deadline(inbound, PEER_PROOF_TIMEOUT_MS));
    }

    public static BleRouteTransition<State> routeControlSubscribed(
            State state, BleRouteToken token, GattResultV2 result) {
        if (!expects(state, Phase.WAITING_CONTROL_SUBSCRIPTION, token)) {
            return BleRouteTransition.ignored(state);
        }
        if (result != GattResultV2.SUCCESS) {
            return gattFailure(state, token, result, AuthorizationStep.ROUTE_CONTROL_CCCD);
        }
        BleRouteToken proof = nextOperation(token);
        if (proof == null) return counterExhausted(state, token, "operation");
        State next = copy(state, Phase.WAITING_PEER_PROOF, proof, state.serverOwner,
                state.advertiserOwner, state.inboundOwner, null, state.nextOwnerId,
                state.consecutiveFailures, "route control subscribed; wait exact H proof");
        return BleRouteTransition.accepted(next,
                op(BleRouteEffect.Type.CANCEL_DEADLINE, token, "control subscribed"),
                BleRouteEffect.deadline(proof, PEER_PROOF_TIMEOUT_MS));
    }

    /** Accepts the shared H/PEER_PROOF frame only on the exact encrypted inbound owner. */
    public static BleRouteTransition<State> peerProof(State state, BleRouteToken token,
                                                       IphoneBlePeerProof proof) {
        if (!expects(state, Phase.WAITING_PEER_PROOF, token)) {
            return BleRouteTransition.ignored(state);
        }
        if (proof == null || !proof.matches(state.helperInstallationId,
                BlePeerRole.IPHONE_HELPER_CENTRAL,
                IphoneBleMode.ANDROID_PERIPHERAL)) {
            return retry(state, token, "Helper Central H proof rejected");
        }
        boolean learned = state.helperInstallationId.isEmpty();
        State identityState = learned ? withHelperInstallationId(state, proof.peerId) : state;
        if (!canAllocateOwner(state.nextOwnerId)) {
            return counterExhausted(identityState, token, "owner");
        }
        long owner = state.nextOwnerId;
        BleRouteToken observer = token(state, owner, 1L);
        State next = copy(identityState, Phase.WAITING_REVERSE_OWNER, observer,
                state.serverOwner, state.advertiserOwner, state.inboundOwner, observer,
                afterOwnerAllocation(owner),
                state.consecutiveFailures, "encrypted exact-owner Helper proof accepted");
        List<BleRouteEffect> effects = new ArrayList<>();
        effects.add(op(BleRouteEffect.Type.CANCEL_DEADLINE, token, "H proof accepted"));
        if (learned) {
            effects.add(op(BleRouteEffect.Type.REPORT_HELPER_ID_LEARNED,
                    token, proof.peerId));
        }
        effects.add(op(BleRouteEffect.Type.OBSERVE_REVERSE_CLIENT, observer,
                "one exact physical-facade owner; RequiresANCS is proven by ANCS discovery; "
                        + "no public/hidden/topology fallback"));
        effects.add(BleRouteEffect.deadline(observer, REVERSE_OWNER_TIMEOUT_MS));
        return new BleRouteTransition<>(next, effects, true);
    }

    public static BleRouteTransition<State> reverseOwnerObserved(State state,
                                                                  BleRouteToken token,
                                                                  boolean sameCapturedInboundPhysicalFacade,
                                                                  boolean exactlyOneOwner) {
        if (!expects(state, Phase.WAITING_REVERSE_OWNER, token)) {
            return BleRouteTransition.ignored(state);
        }
        if (!exactlyOneOwner || !sameCapturedInboundPhysicalFacade) {
            return retry(state, token,
                    "reverse owner ambiguous or not the captured inbound physical facade");
        }
        BleRouteToken discover = nextOperation(token);
        if (discover == null) return counterExhausted(state, token, "operation");
        State next = copy(state, Phase.DISCOVERING_ANCS, discover, state.serverOwner,
                state.advertiserOwner, state.inboundOwner, discover, state.nextOwnerId,
                state.consecutiveFailures, "one reverse owner accepted");
        return step(next, token, discover, BleRouteEffect.Type.DISCOVER_ANCS,
                DISCOVERY_TIMEOUT_MS, "discover fresh ANCS objects on exact reverse owner");
    }

    /**
     * A terminal callback for the captured inbound server facade invalidates the whole route.
     * Republishing is forbidden until the adapter proves every retained owner terminal.
     */
    public static BleRouteTransition<State> inboundDisconnected(
            State state, BleRouteToken inboundOwnerCallback, String reason) {
        if (state == null || state.inboundOwner == null || inboundOwnerCallback == null
                || !state.inboundOwner.sameOwner(inboundOwnerCallback)) {
            return BleRouteTransition.ignored(state);
        }
        if (state.phase == Phase.SWITCH_WAIT_INBOUND_TERMINAL) {
            BleRouteToken teardown = state.expected;
            State stopping = copy(state, Phase.STOPPING, teardown, state.serverOwner,
                    null, null, state.reverseOwner, state.nextOwnerId,
                    state.consecutiveFailures,
                    "Helper Central closed first; close-only remaining local owners");
            List<BleRouteEffect> effects = new ArrayList<>();
            effects.add(op(BleRouteEffect.Type.CANCEL_DEADLINE, teardown,
                    "exact inbound terminal observed"));
            if (state.reverseOwner != null) {
                effects.add(op(BleRouteEffect.Type.CLOSE_REVERSE_CLIENT,
                        state.reverseOwner,
                        "close-only observer after Helper Central terminal"));
            }
            if (state.serverOwner != null) {
                effects.add(op(BleRouteEffect.Type.CLOSE_GATT_SERVER,
                        state.serverOwner,
                        "close server only after exact inbound terminal"));
            }
            effects.add(BleRouteEffect.deadline(teardown, STOP_TIMEOUT_MS));
            return new BleRouteTransition<>(stopping, effects, true);
        }
        if (!acceptsOwnerLoss(state.phase)) return BleRouteTransition.ignored(state);
        return retry(state, inboundOwnerCallback,
                "exact inbound owner lost: " + safe(reason));
    }

    /**
     * The reverse ANCS observer is a required owner, not an opportunistic attachment. Its loss
     * drains the server, advertiser, inbound facade, and reverse client as one route generation.
     */
    public static BleRouteTransition<State> reverseOwnerLost(
            State state, BleRouteToken reverseOwnerCallback, String reason) {
        if (state == null || state.reverseOwner == null || reverseOwnerCallback == null
                || !state.reverseOwner.sameOwner(reverseOwnerCallback)
                || !acceptsOwnerLoss(state.phase)) {
            return BleRouteTransition.ignored(state);
        }
        return retry(state, reverseOwnerCallback,
                "exact reverse owner lost: " + safe(reason));
    }

    public static BleRouteTransition<State> ancsDiscovered(State state, BleRouteToken token,
                                                            IphoneGattInventoryV2 inventory) {
        if (!expects(state, Phase.DISCOVERING_ANCS, token)) {
            return BleRouteTransition.ignored(state);
        }
        if (inventory == null) {
            return retry(state, token, "null GATT inventory");
        }
        State discovered = withGattInventory(state, inventory.completeAncs(), false);
        if (inventory.serviceChangedIndicatable) {
            BleRouteToken subscribe = nextOperation(token);
            if (subscribe == null) return counterExhausted(state, token, "operation");
            State next = copy(discovered, Phase.SUBSCRIBING_SERVICE_CHANGED, subscribe,
                    state.serverOwner, state.advertiserOwner, state.inboundOwner, subscribe,
                    state.nextOwnerId, state.consecutiveFailures,
                    "subscribe Service Changed before ANCS readiness");
            return step(next, token, subscribe,
                    BleRouteEffect.Type.SUBSCRIBE_GATT_SERVICE_CHANGED,
                    CCCD_TIMEOUT_MS, "enable exact-owner GATT Service Changed indication");
        }
        return proceedAfterDiscovery(discovered, token);
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
        return proceedAfterDiscovery(withServiceChangedArmed(state, true), token);
    }

    /** One exact indication schedules one same-owner rediscovery; a duplicate is ignored. */
    public static BleRouteTransition<State> serviceChanged(State state,
                                                            BleRouteToken ownerCallback) {
        if (state == null || state.reverseOwner == null || ownerCallback == null
                || !state.reverseOwner.sameOwner(ownerCallback)) {
            return BleRouteTransition.ignored(state);
        }
        if (state.phase != Phase.WAIT_ANCS && state.phase != Phase.READY) {
            if (state.expected != null && handlesInFlight(state.phase)) {
                return retry(state, state.expected,
                        "Service Changed invalidated an in-flight raw object");
            }
            return BleRouteTransition.ignored(state);
        }
        BleRouteToken discover = nextOperation(state.reverseOwner);
        if (discover == null) return counterExhausted(state, ownerCallback, "operation");
        State next = copyPolicy(withGattInventory(state, false, false),
                Phase.DISCOVERING_ANCS, discover, state.serverOwner, state.advertiserOwner,
                state.inboundOwner, discover, state.nextOwnerId, state.consecutiveFailures,
                AuthorizationStep.NONE, 0, 0,
                "Service Changed; rediscover same reverse owner once");
        return BleRouteTransition.accepted(next,
                op(BleRouteEffect.Type.RESET_SESSION_STATE, ownerCallback,
                        "ANCS epoch invalidated; clear all session-local data"),
                op(BleRouteEffect.Type.DISCOVER_ANCS, discover,
                        "one same-owner rediscovery; no polling"),
                BleRouteEffect.deadline(discover, DISCOVERY_TIMEOUT_MS));
    }

    private static BleRouteTransition<State> proceedAfterDiscovery(State state,
                                                                    BleRouteToken token) {
        if (!state.ancsAvailable) {
            if (!state.serviceChangedArmed) {
                State blocked = copy(state, Phase.NEEDS_FRESH_LINK, null, state.serverOwner,
                        state.advertiserOwner, state.inboundOwner, token, state.nextOwnerId,
                        state.consecutiveFailures,
                        "ANCS absent and Service Changed unavailable; fresh link required");
                return BleRouteTransition.accepted(blocked,
                        op(BleRouteEffect.Type.CANCEL_DEADLINE, token,
                                "discovery completed"),
                        op(BleRouteEffect.Type.REPORT_DOWN, token,
                                "ANCS absent without Service Changed; explicit fresh link required"));
            }
            State waiting = copy(state, Phase.WAIT_ANCS, null, state.serverOwner,
                    state.advertiserOwner, state.inboundOwner, token, state.nextOwnerId,
                    state.consecutiveFailures,
                    "ANCS not published; wait for Service Changed without polling");
            return BleRouteTransition.accepted(waiting,
                    op(BleRouteEffect.Type.CANCEL_DEADLINE, token, "discovery completed"),
                    op(BleRouteEffect.Type.REPORT_DOWN, token,
                            "ANCS not published; no owner replacement"));
        }
        BleRouteToken subscribe = nextOperation(token);
        if (subscribe == null) return counterExhausted(state, token, "operation");
        State next = copy(state, Phase.SUBSCRIBING_NOTIFICATION_SOURCE, subscribe,
                state.serverOwner, state.advertiserOwner, state.inboundOwner, subscribe,
                state.nextOwnerId, state.consecutiveFailures, "ANCS discovered");
        return BleRouteTransition.accepted(next,
                op(BleRouteEffect.Type.CANCEL_DEADLINE, token, "discovery completed"),
                op(BleRouteEffect.Type.ARM_ANCS_PARSER, subscribe,
                        "reset/arm parser before Notification Source CCCD"),
                op(BleRouteEffect.Type.SUBSCRIBE_ANCS_NOTIFICATION_SOURCE, subscribe,
                        "mandatory ANCS Notification Source CCCD"),
                BleRouteEffect.deadline(subscribe, CCCD_TIMEOUT_MS));
    }

    public static BleRouteTransition<State> notificationSourceSubscribed(
            State state, BleRouteToken token, GattResultV2 result) {
        if (!expects(state, Phase.SUBSCRIBING_NOTIFICATION_SOURCE, token)) {
            return BleRouteTransition.ignored(state);
        }
        if (result != GattResultV2.SUCCESS) {
            return gattFailure(state, token, result,
                    AuthorizationStep.NOTIFICATION_SOURCE_CCCD);
        }
        BleRouteToken subscribe = nextOperation(token);
        if (subscribe == null) return counterExhausted(state, token, "operation");
        State next = copy(state, Phase.SUBSCRIBING_DATA_SOURCE, subscribe,
                state.serverOwner, state.advertiserOwner, state.inboundOwner, subscribe,
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
            return gattFailure(state, token, result, AuthorizationStep.DATA_SOURCE_CCCD);
        }
        State ready = copyPolicy(state, Phase.READY, null, state.serverOwner,
                state.advertiserOwner, state.inboundOwner, state.reverseOwner,
                state.nextOwnerId, 0, AuthorizationStep.NONE, 0, 0, "ready");
        return BleRouteTransition.accepted(ready,
                op(BleRouteEffect.Type.CANCEL_DEADLINE, token, "ready"),
                op(BleRouteEffect.Type.REPORT_READY, token,
                        "exact reverse-client owner ready"));
    }

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
            case ROUTE_CONTROL_CCCD:
                phase = Phase.WAITING_CONTROL_SUBSCRIPTION;
                effect = BleRouteEffect.Type.SUBSCRIBE_ROUTE_CONTROL;
                break;
            case SERVICE_CHANGED_CCCD:
                phase = Phase.SUBSCRIBING_SERVICE_CHANGED;
                effect = BleRouteEffect.Type.SUBSCRIBE_GATT_SERVICE_CHANGED;
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
        State retrying = copyPolicy(state, phase, operation, state.serverOwner,
                state.advertiserOwner, state.inboundOwner, state.reverseOwner,
                state.nextOwnerId, state.consecutiveFailures, state.authorizationStep,
                state.authorizationRetries + 1, state.invalidHandleRediscoveries,
                "authorization changed; one exact-operation retry");
        return BleRouteTransition.accepted(retrying,
                op(effect, operation, "one explicit authorization retry"),
                BleRouteEffect.deadline(operation, CCCD_TIMEOUT_MS));
    }

    /** Telemetry is accepted on the authenticated server facade but never gates ANCS readiness. */
    public static boolean acceptsTelemetry(State state, BleRouteToken inboundToken) {
        return state != null && state.inboundOwner != null
                && state.inboundOwner.sameOwner(inboundToken)
                && state.phase != Phase.WAITING_CONTROL_SUBSCRIPTION
                && state.phase != Phase.WAITING_PEER_PROOF
                && state.phase != Phase.RETRY_DRAINING
                && state.phase != Phase.RETRY_WAIT
                && state.phase != Phase.SWITCH_WAIT_INBOUND_TERMINAL
                && state.phase != Phase.STOPPING
                && state.phase != Phase.STOPPED
                && state.phase != Phase.FAILED;
    }

    public static BleRouteTransition<State> deadline(State state, BleRouteToken token) {
        if (state == null || state.expected == null || !state.expected.equals(token)) {
            return BleRouteTransition.ignored(state);
        }
        if (state.phase == Phase.STOPPING || state.phase == Phase.RETRY_DRAINING
                || state.phase == Phase.SWITCH_WAIT_INBOUND_TERMINAL) {
            State failed = copy(state, Phase.FAILED, null, state.serverOwner,
                    state.advertiserOwner, state.inboundOwner, state.reverseOwner,
                    state.nextOwnerId, state.consecutiveFailures,
                    "teardown deadline expired; restart/switch denied");
            return BleRouteTransition.accepted(failed,
                    op(BleRouteEffect.Type.REPORT_ERROR, token,
                            "local teardown not proven; fail closed"));
        }
        return retry(state, token, state.phase + " callback timeout");
    }

    public static BleRouteTransition<State> attemptTeardownComplete(State state,
                                                                    BleRouteToken token) {
        if (!expects(state, Phase.RETRY_DRAINING, token)) {
            return BleRouteTransition.ignored(state);
        }
        if (state.consecutiveFailures >= MAX_ATTEMPTS_PER_EPOCH) {
            State failed = copy(state, Phase.FAILED, null, null, null, null, null,
                    state.nextOwnerId, state.consecutiveFailures,
                    "attempt budget exhausted; fresh epoch required");
            return BleRouteTransition.accepted(failed,
                    op(BleRouteEffect.Type.CANCEL_DEADLINE, token, "owners terminal"),
                    op(BleRouteEffect.Type.REPORT_ERROR, token,
                            "bounded retry budget exhausted; no automatic topology fallback"));
        }
        if (!canAllocateOwner(state.nextOwnerId)) {
            return counterExhausted(state, token, "owner");
        }
        long timerOwner = state.nextOwnerId;
        BleRouteToken timer = token(state, timerOwner, 1L);
        State waiting = copy(state, Phase.RETRY_WAIT, timer, null, null, null, null,
                afterOwnerAllocation(timerOwner), state.consecutiveFailures, state.detail);
        return BleRouteTransition.accepted(waiting,
                op(BleRouteEffect.Type.CANCEL_DEADLINE, token, "owners terminal"),
                BleRouteEffect.retry(timer, retryDelayMillis(state.consecutiveFailures),
                        state.detail));
    }

    /** Republishing is allowed only after all owners from the prior publication are terminal. */
    public static BleRouteTransition<State> retryElapsed(State state, BleRouteToken token,
                                                          boolean radioEnabled) {
        if (!expects(state, Phase.RETRY_WAIT, token)) return BleRouteTransition.ignored(state);
        if (!radioEnabled) {
            State waiting = copy(state, Phase.WAIT_RADIO, null, null, null, null, null,
                    state.nextOwnerId, state.consecutiveFailures,
                    "radio off; start again with a fresh route epoch");
            return BleRouteTransition.accepted(waiting,
                    op(BleRouteEffect.Type.CANCEL_DEADLINE, token, "radio off"));
        }
        State base = new State(state.epoch, state.selectedSystemBondAddress,
                state.helperInstallationId,
                Phase.OPENING_SERVER, null, null, null, null, null,
                state.nextOwnerId, state.consecutiveFailures, false,
                "retry with fresh publication");
        return beginServer(base);
    }

    public static BleRouteTransition<State> radioOff(State state, BleRouteEpoch epoch) {
        if (state == null || !state.epoch.equals(epoch)
                || state.phase == Phase.STOPPED || state.phase == Phase.FAILED) {
            return BleRouteTransition.ignored(state);
        }
        List<BleRouteEffect> effects = closeEffects(state, "radio off");
        State waiting = copy(state, Phase.WAIT_RADIO, null, null, null, null, null,
                state.nextOwnerId, state.consecutiveFailures,
                "fresh route epoch required after radio on");
        effects.add(report(state, BleRouteEffect.Type.REPORT_DOWN, "radio off"));
        return new BleRouteTransition<>(waiting, effects, true);
    }

    public static BleRouteTransition<State> stop(State state, BleRouteEpoch epoch, String reason) {
        if (state == null || !state.epoch.equals(epoch)
                || state.phase == Phase.STOPPED || state.phase == Phase.STOPPING) {
            return BleRouteTransition.ignored(state);
        }
        if (!canAllocateOwner(state.nextOwnerId)) {
            return counterExhausted(state, state.expected, "owner");
        }
        long owner = state.nextOwnerId;
        BleRouteToken teardown = token(state, owner, 1L);
        List<BleRouteEffect> effects = closeEffects(state, "stop: " + safe(reason));
        effects.add(BleRouteEffect.deadline(teardown, STOP_TIMEOUT_MS));
        State stopping = copy(state, Phase.STOPPING, teardown, state.serverOwner,
                state.advertiserOwner, state.inboundOwner, state.reverseOwner,
                afterOwnerAllocation(owner), state.consecutiveFailures, safe(reason));
        return new BleRouteTransition<>(stopping, effects, true);
    }

    /**
     * Starts passive Route-B teardown only after the exact C/A transaction is confirmed. The
     * Helper Central is the deterministic first closer; this transition never disconnects it.
     */
    public static BleRouteTransition<State> switchStop(
            State state, BleRouteEpoch epoch, String reason) {
        if (state == null || !state.epoch.equals(epoch)
                || state.phase == Phase.STOPPED
                || state.phase == Phase.STOPPING
                || state.phase == Phase.SWITCH_WAIT_INBOUND_TERMINAL
                || state.inboundOwner == null) {
            return BleRouteTransition.ignored(state);
        }
        if (!canAllocateOwner(state.nextOwnerId)) {
            return counterExhausted(state, state.expected, "owner");
        }
        long owner = state.nextOwnerId;
        BleRouteToken teardown = token(state, owner, 1L);
        List<BleRouteEffect> effects = new ArrayList<>();
        if (state.expected != null) {
            effects.add(op(BleRouteEffect.Type.CANCEL_DEADLINE,
                    state.expected, "confirmed switch freeze"));
        }
        effects.add(op(BleRouteEffect.Type.RESET_SESSION_STATE, reportToken(state),
                "freeze source ingress after exact C/A"));
        if (state.advertiserOwner != null) {
            effects.add(op(BleRouteEffect.Type.STOP_ADVERTISING,
                    state.advertiserOwner, "confirmed switch freeze"));
        }
        effects.add(BleRouteEffect.deadline(teardown, STOP_TIMEOUT_MS));
        State waiting = copy(state, Phase.SWITCH_WAIT_INBOUND_TERMINAL, teardown,
                state.serverOwner, state.advertiserOwner, state.inboundOwner,
                state.reverseOwner, afterOwnerAllocation(owner),
                state.consecutiveFailures, safe(reason));
        return new BleRouteTransition<>(waiting, effects, true);
    }

    public static BleRouteTransition<State> localTeardownComplete(State state,
                                                                   BleRouteToken token) {
        if (!expects(state, Phase.STOPPING, token)) return BleRouteTransition.ignored(state);
        State stopped = copy(state, Phase.STOPPED, null, null, null, null, null,
                state.nextOwnerId, state.consecutiveFailures, "local terminal");
        return BleRouteTransition.accepted(stopped,
                op(BleRouteEffect.Type.CANCEL_DEADLINE, token, "teardown complete"),
                op(BleRouteEffect.Type.REPORT_LOCAL_TERMINAL, token,
                        "safe for switch coordinator localTerminal"));
    }

    public static IphoneBleAdvertisement advertisement(State state) {
        return new IphoneBleAdvertisement(IphoneBleProtocolV2.ANDROID_PERIPHERAL_SERVICE,
                IphoneBleProtocolV2.VERSION, BlePeerRole.ANDROID_PERIPHERAL,
                true, false);
    }

    public static long retryDelayMillis(int consecutiveFailures) {
        int shift = Math.max(0, Math.min(6, consecutiveFailures - 1));
        return Math.min(MAX_RETRY_MS, 500L << shift);
    }

    private static BleRouteTransition<State> beginServer(State base) {
        if (!canAllocateOwner(base.nextOwnerId)) {
            return counterExhausted(base, base.expected, "owner");
        }
        long owner = base.nextOwnerId;
        BleRouteToken server = token(base, owner, 1L);
        State opening = copy(base, Phase.OPENING_SERVER, server, server, null, null, null,
                afterOwnerAllocation(owner), base.consecutiveFailures,
                "new GATT server owner");
        return BleRouteTransition.accepted(opening,
                op(BleRouteEffect.Type.OPEN_GATT_SERVER, server,
                        "new server object; fixed v2 service; no inherited handles"),
                BleRouteEffect.deadline(server, SERVER_OPEN_TIMEOUT_MS));
    }

    private static BleRouteTransition<State> retry(State state, BleRouteToken failed,
                                                    String reason) {
        if (!canAllocateOwner(state.nextOwnerId)) {
            return counterExhausted(state, failed, "owner");
        }
        int failures = Math.min(Integer.MAX_VALUE, state.consecutiveFailures + 1);
        long owner = state.nextOwnerId;
        BleRouteToken teardown = token(state, owner, 1L);
        List<BleRouteEffect> effects = closeEffects(state, reason);
        effects.add(BleRouteEffect.deadline(teardown, STOP_TIMEOUT_MS));
        effects.add(op(BleRouteEffect.Type.REPORT_ERROR, failed, reason));
        State draining = copyPolicy(state, Phase.RETRY_DRAINING, teardown, state.serverOwner,
                state.advertiserOwner, state.inboundOwner, state.reverseOwner,
                afterOwnerAllocation(owner), failures, AuthorizationStep.NONE, 0, 0, reason);
        return new BleRouteTransition<>(draining, effects, true);
    }

    private static List<BleRouteEffect> closeEffects(State state, String reason) {
        List<BleRouteEffect> effects = new ArrayList<>();
        if (state.expected != null) {
            effects.add(op(BleRouteEffect.Type.CANCEL_DEADLINE, state.expected, reason));
        }
        BleRouteToken reportToken = reportToken(state);
        effects.add(op(BleRouteEffect.Type.RESET_SESSION_STATE, reportToken,
                "clear UID queues/parsers/fragments before owners close"));
        if (state.advertiserOwner != null) {
            effects.add(op(BleRouteEffect.Type.STOP_ADVERTISING,
                    state.advertiserOwner, reason));
        }
        if (state.reverseOwner != null) {
            effects.add(op(BleRouteEffect.Type.CLOSE_REVERSE_CLIENT,
                    state.reverseOwner, reason));
        }
        if (state.inboundOwner != null) {
            effects.add(op(BleRouteEffect.Type.DISCONNECT_INBOUND_PEER,
                    state.inboundOwner, reason));
        }
        if (state.serverOwner != null) {
            effects.add(op(BleRouteEffect.Type.CLOSE_GATT_SERVER,
                    state.serverOwner, reason));
        }
        return effects;
    }

    private static BleRouteTransition<State> step(State next, BleRouteToken completed,
                                                   BleRouteToken operation,
                                                   BleRouteEffect.Type type, long timeout,
                                                   String detail) {
        return BleRouteTransition.accepted(next,
                op(BleRouteEffect.Type.CANCEL_DEADLINE, completed, "operation completed"),
                op(type, operation, detail), BleRouteEffect.deadline(operation, timeout));
    }

    private static BleRouteTransition<State> gattFailure(State state, BleRouteToken token,
                                                          GattResultV2 result,
                                                          AuthorizationStep step) {
        if (result == null) result = GattResultV2.TRANSIENT_FAILURE;
        if (result.authorizationFailure()) {
            State waiting = copyPolicy(state, Phase.WAIT_AUTHORIZATION, token,
                    state.serverOwner, state.advertiserOwner, state.inboundOwner,
                    state.reverseOwner, state.nextOwnerId, state.consecutiveFailures, step,
                    state.authorizationRetries, state.invalidHandleRediscoveries,
                    result + "; retain exact owners and wait for authorization change");
            return BleRouteTransition.accepted(waiting,
                    op(BleRouteEffect.Type.CANCEL_DEADLINE, token, "authorization gate"),
                    op(BleRouteEffect.Type.REPORT_DOWN, token,
                            result + ": no reconnect storm"));
        }
        if (result == GattResultV2.INVALID_HANDLE
                && step != AuthorizationStep.ROUTE_CONTROL_CCCD) {
            if (state.invalidHandleRediscoveries == 0 && state.reverseOwner != null) {
                BleRouteToken discover = nextOperation(state.reverseOwner);
                if (discover == null) {
                    return counterExhausted(state, token, "operation");
                }
                State rediscovering = copyPolicy(state, Phase.DISCOVERING_ANCS, discover,
                        state.serverOwner, state.advertiserOwner, state.inboundOwner, discover,
                        state.nextOwnerId, state.consecutiveFailures, AuthorizationStep.NONE,
                        0, 1, "invalid handle; one same-owner rediscovery");
                return BleRouteTransition.accepted(rediscovering,
                        op(BleRouteEffect.Type.CANCEL_DEADLINE, token, "invalid handle"),
                        op(BleRouteEffect.Type.RESET_SESSION_STATE, token,
                                "invalidate all discovered objects"),
                        op(BleRouteEffect.Type.DISCOVER_ANCS, discover,
                                "single same-owner invalid-handle recovery"),
                        BleRouteEffect.deadline(discover, DISCOVERY_TIMEOUT_MS));
            }
            return retry(state, token, "repeated invalid handle after rediscovery");
        }
        return retry(state, token, "transient GATT failure during " + step);
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
        State failed = copyPolicy(state, Phase.FAILED, null, state.serverOwner,
                state.advertiserOwner, state.inboundOwner, state.reverseOwner,
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
        if (state.reverseOwner != null) return state.reverseOwner;
        if (state.inboundOwner != null) return state.inboundOwner;
        if (state.serverOwner != null) return state.serverOwner;
        return token(state, 1L, 1L);
    }

    private static BleRouteToken token(State state, long ownerId, long operationId) {
        return new BleRouteToken(IphoneBleMode.ANDROID_PERIPHERAL,
                state.epoch, ownerId, operationId);
    }

    private static BleRouteEffect op(BleRouteEffect.Type type, BleRouteToken token,
                                     String detail) {
        return BleRouteEffect.operation(type, token, detail);
    }

    private static BleRouteEffect report(State state, BleRouteEffect.Type type, String detail) {
        return op(type, reportToken(state), detail);
    }

    private static BleRouteToken reportToken(State state) {
        if (state.expected != null) return state.expected;
        if (state.reverseOwner != null) return state.reverseOwner;
        if (state.inboundOwner != null) return state.inboundOwner;
        if (state.serverOwner != null) return state.serverOwner;
        return token(state, 1L, 1L);
    }

    private static State copy(State state, Phase phase, BleRouteToken expected,
                              BleRouteToken serverOwner, BleRouteToken advertiserOwner,
                              BleRouteToken inboundOwner, BleRouteToken reverseOwner,
                              long nextOwnerId, int failures, String detail) {
        return new State(state.epoch, state.selectedSystemBondAddress,
                state.helperInstallationId,
                phase, expected, serverOwner, advertiserOwner,
                inboundOwner, reverseOwner, nextOwnerId, failures, state.ancsAvailable,
                state.serviceChangedArmed,
                state.authorizationStep, state.authorizationRetries,
                state.invalidHandleRediscoveries, detail);
    }

    private static State copyPolicy(State state, Phase phase, BleRouteToken expected,
                                    BleRouteToken serverOwner,
                                    BleRouteToken advertiserOwner,
                                    BleRouteToken inboundOwner,
                                    BleRouteToken reverseOwner,
                                    long nextOwnerId, int failures,
                                    AuthorizationStep authorizationStep,
                                    int authorizationRetries,
                                    int invalidHandleRediscoveries,
                                    String detail) {
        return new State(state.epoch, state.selectedSystemBondAddress,
                state.helperInstallationId,
                phase, expected, serverOwner, advertiserOwner,
                inboundOwner, reverseOwner, nextOwnerId, failures, state.ancsAvailable,
                state.serviceChangedArmed,
                authorizationStep, authorizationRetries, invalidHandleRediscoveries, detail);
    }

    private static State withGattInventory(State state, boolean ancsAvailable,
                                           boolean serviceChangedArmed) {
        return new State(state.epoch, state.selectedSystemBondAddress,
                state.helperInstallationId,
                state.phase, state.expected, state.serverOwner,
                state.advertiserOwner, state.inboundOwner, state.reverseOwner,
                state.nextOwnerId, state.consecutiveFailures, ancsAvailable,
                serviceChangedArmed,
                state.authorizationStep, state.authorizationRetries,
                state.invalidHandleRediscoveries, state.detail);
    }

    private static State withServiceChangedArmed(State state, boolean armed) {
        return withGattInventory(state, state.ancsAvailable, armed);
    }

    private static State withHelperInstallationId(State state, String helperInstallationId) {
        return new State(state.epoch, state.selectedSystemBondAddress,
                IphoneBleAdvertisement.normalizePeerId(helperInstallationId),
                state.phase, state.expected, state.serverOwner, state.advertiserOwner,
                state.inboundOwner, state.reverseOwner, state.nextOwnerId,
                state.consecutiveFailures, state.ancsAvailable, state.serviceChangedArmed,
                state.authorizationStep, state.authorizationRetries,
                state.invalidHandleRediscoveries, state.detail);
    }

    private static boolean handlesInFlight(Phase phase) {
        return phase == Phase.DISCOVERING_ANCS
                || phase == Phase.SUBSCRIBING_SERVICE_CHANGED
                || phase == Phase.SUBSCRIBING_NOTIFICATION_SOURCE
                || phase == Phase.SUBSCRIBING_DATA_SOURCE;
    }

    private static boolean acceptsOwnerLoss(Phase phase) {
        return phase != Phase.WAIT_RADIO
                && phase != Phase.RETRY_DRAINING
                && phase != Phase.RETRY_WAIT
                && phase != Phase.SWITCH_WAIT_INBOUND_TERMINAL
                && phase != Phase.STOPPING
                && phase != Phase.STOPPED
                && phase != Phase.FAILED;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
