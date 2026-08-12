/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package dezz.status.widget.phone.transport.switching;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Pure fail-closed state machine for switching between the two mutually exclusive BLE routes.
 *
 * <p>The reducer deliberately knows nothing about Android, Bluetooth, threads, or wall clocks.
 * Its caller executes the returned effects and feeds completion events back with the exact epoch
 * and generation copied from those effects. An event from any other owner is a no-op.</p>
 *
 * <p>A target route can only be started after all of these independent gates have completed:</p>
 *
 * <ol>
 *   <li>new ingress into the source route is frozen;</li>
 *   <li>the local source owner is terminal;</li>
 *   <li>the platform adapter proves that all app-owned local BLE owners are gone;</li>
 *   <li>the exact C/A handshake is complete: local-origin teardown received A, remote-origin
 *       teardown received adapter acceptance for A, or typed evidence proves no peer owner;</li>
 *   <li>the drain deadline has elapsed;</li>
 *   <li>the caller explicitly consumes the resulting quiescent state.</li>
 * </ol>
 */
public final class BleRoleSwitchReducer {
    public enum Role {
        HELPER_PERIPHERAL_ANDROID_CENTRAL,
        HELPER_CENTRAL_ANDROID_PERIPHERAL
    }

    public enum Phase {
        ACTIVE,
        FREEZING,
        WAITING_CONTROL_HANDSHAKE,
        WAITING_LOCAL_TERMINAL,
        WAITING_REMOTE_ACK,
        DRAINING,
        QUIESCENT,
        STARTING,
        FAILED,
        CLOSED
    }

    public enum Outcome {
        APPLIED,
        COALESCED,
        REJECTED_CONFLICT,
        REJECTED_TERMINAL,
        STALE_CALLBACK,
        NOT_DUE
    }

    public enum Failure {
        NONE,
        INGRESS_FREEZE_FAILED,
        STOP_TIMEOUT,
        CONTRADICTORY_REMOTE_EVIDENCE,
        CONTRADICTORY_LOCAL_OWNER_EVIDENCE,
        IMPOSSIBLE_LOCAL_OWNER_COUNT,
        CONTROL_TRANSMIT_FAILED,
        TARGET_START_FAILED
    }

    /** The only two control frames that can authorize teardown of the current route. */
    public enum ControlFrame {
        CLOSE_REQUEST,
        CLOSE_ACK
    }

    /** Result of one exact, adapter-owned asynchronous control-frame transmission. */
    public enum ControlTransmitResult {
        /** Client write-with-response succeeded, or the server indication was accepted/queued. */
        ACCEPTED,
        /** The same frame/token may be retried without extending the original stop deadline. */
        RETRYABLE_FAILURE,
        /** The adapter knows that retrying cannot succeed on this owner. */
        TERMINAL_FAILURE
    }

    private enum ControlTransmitStatus {
        IDLE,
        IN_FLIGHT,
        RETRY_WAIT,
        ACCEPTED
    }

    /**
     * Typed peer commitment evidence accepted by the exact switch epoch. CONFIRMED_ACK means the
     * peer persisted and froze the switch and is the local-origin permission to request teardown;
     * physical safety still comes from deterministic client-first closure, local terminal,
     * explicit localOwnersZero, and the drain interval.
     */
    public enum RemoteCloseEvidence {
        CONFIRMED_ACK,
        PEER_COMMITTED_INTENT,
        PEER_SAME_ROLE_RETAINED,
        NO_REMOTE_OWNER,
        REMOTE_ALREADY_TERMINAL,
        RADIO_OR_POWER_LOSS
    }

    public enum EffectType {
        FREEZE_SOURCE_INGRESS,
        ARM_STOP_TIMEOUT,
        STOP_LOCAL_SOURCE,
        REQUEST_REMOTE_STOP,
        ACKNOWLEDGE_REMOTE_STOP,
        SCHEDULE_CONTROL_RETRY,
        CANCEL_CONTROL_RETRY,
        VERIFY_LOCAL_OWNERS,
        CANCEL_STOP_TIMEOUT,
        ARM_DRAIN_DEADLINE,
        CANCEL_DRAIN_DEADLINE,
        QUIESCENT_REACHED,
        START_TARGET,
        TARGET_ACTIVE,
        FAIL_CLOSED,
        CLOSE_ALL
    }

    /**
     * An unbounded monotonically increasing token. BigInteger avoids silent ownership reuse when
     * a process survives past {@link Long#MAX_VALUE}; wrapping a primitive generation is forbidden.
     */
    public static final class Sequence implements Comparable<Sequence> {
        private static final Sequence ZERO = new Sequence(BigInteger.ZERO);

        private final BigInteger value;

        private Sequence(BigInteger value) {
            if (value.signum() < 0) {
                throw new IllegalArgumentException("sequence must be non-negative");
            }
            this.value = value;
        }

        public static Sequence zero() {
            return ZERO;
        }

        public static Sequence of(long value) {
            if (value < 0L) {
                throw new IllegalArgumentException("sequence must be non-negative");
            }
            return value == 0L ? ZERO : new Sequence(BigInteger.valueOf(value));
        }

        public static Sequence parse(String value) {
            Objects.requireNonNull(value, "value");
            BigInteger parsed = new BigInteger(value);
            return parsed.signum() == 0 ? ZERO : new Sequence(parsed);
        }

        public Sequence next() {
            return new Sequence(value.add(BigInteger.ONE));
        }

        public BigInteger asBigInteger() {
            return value;
        }

        @Override
        public int compareTo(Sequence other) {
            return value.compareTo(Objects.requireNonNull(other, "other").value);
        }

        @Override
        public boolean equals(Object other) {
            return this == other || (other instanceof Sequence that && value.equals(that.value));
        }

        @Override
        public int hashCode() {
            return value.hashCode();
        }

        @Override
        public String toString() {
            return value.toString();
        }
    }

    /**
     * A command whose epoch, generation, and role must all be echoed by its completion callback.
     */
    public static final class Effect {
        private final EffectType type;
        private final Sequence epoch;
        private final Sequence generation;
        private final Role role;
        private final long deadlineMillis;
        private final ControlFrame controlFrame;
        private final Sequence controlAttempt;

        private Effect(
                EffectType type,
                Sequence epoch,
                Sequence generation,
                Role role,
                long deadlineMillis,
                ControlFrame controlFrame,
                Sequence controlAttempt
        ) {
            this.type = Objects.requireNonNull(type, "type");
            this.epoch = Objects.requireNonNull(epoch, "epoch");
            this.generation = Objects.requireNonNull(generation, "generation");
            this.role = Objects.requireNonNull(role, "role");
            this.deadlineMillis = deadlineMillis;
            this.controlFrame = controlFrame;
            this.controlAttempt = Objects.requireNonNull(controlAttempt, "controlAttempt");
        }

        public EffectType type() {
            return type;
        }

        public Sequence epoch() {
            return epoch;
        }

        public Sequence generation() {
            return generation;
        }

        public Role role() {
            return role;
        }

        /** Returns the absolute deadline for ARM effects, or {@code -1} for other effects. */
        public long deadlineMillis() {
            return deadlineMillis;
        }

        public ControlFrame controlFrame() {
            return controlFrame;
        }

        /** Exact unbounded attempt token, or zero for a non-control effect. */
        public Sequence controlAttempt() {
            return controlAttempt;
        }

        @Override
        public String toString() {
            return "Effect{" + type + ", epoch=" + epoch + ", generation=" + generation
                    + ", role=" + role + ", deadlineMillis=" + deadlineMillis + '}';
        }
    }

    /** Immutable reducer state. */
    public static final class State {
        private final Phase phase;
        private final Sequence epoch;
        private final Role desiredRole;
        private final Role activeRole;
        private final Sequence activeGeneration;
        private final Role sourceRole;
        private final Sequence sourceGeneration;
        private final Role targetRole;
        private final Sequence targetGeneration;
        private final boolean ingressFrozen;
        private final boolean localTerminal;
        private final boolean localOwnersZero;
        private final RemoteCloseEvidence remoteCloseEvidence;
        private final long stopDeadlineMillis;
        private final long drainDurationMillis;
        private final long drainDeadlineMillis;
        private final Failure failure;
        private final ControlFrame controlFrame;
        private final Sequence controlAttempt;
        private final ControlTransmitStatus controlTransmitStatus;
        private final boolean controlTransmitAccepted;
        private final boolean localStopRequested;

        private State(
                Phase phase,
                Sequence epoch,
                Role desiredRole,
                Role activeRole,
                Sequence activeGeneration,
                Role sourceRole,
                Sequence sourceGeneration,
                Role targetRole,
                Sequence targetGeneration,
                boolean ingressFrozen,
                boolean localTerminal,
                boolean localOwnersZero,
                RemoteCloseEvidence remoteCloseEvidence,
                long stopDeadlineMillis,
                long drainDurationMillis,
                long drainDeadlineMillis,
                Failure failure,
                ControlFrame controlFrame,
                Sequence controlAttempt,
                ControlTransmitStatus controlTransmitStatus,
                boolean controlTransmitAccepted,
                boolean localStopRequested
        ) {
            this.phase = Objects.requireNonNull(phase, "phase");
            this.epoch = Objects.requireNonNull(epoch, "epoch");
            this.desiredRole = desiredRole;
            this.activeRole = activeRole;
            this.activeGeneration = activeGeneration;
            this.sourceRole = sourceRole;
            this.sourceGeneration = sourceGeneration;
            this.targetRole = targetRole;
            this.targetGeneration = targetGeneration;
            this.ingressFrozen = ingressFrozen;
            this.localTerminal = localTerminal;
            this.localOwnersZero = localOwnersZero;
            this.remoteCloseEvidence = remoteCloseEvidence;
            this.stopDeadlineMillis = stopDeadlineMillis;
            this.drainDurationMillis = drainDurationMillis;
            this.drainDeadlineMillis = drainDeadlineMillis;
            this.failure = Objects.requireNonNull(failure, "failure");
            this.controlFrame = controlFrame;
            this.controlAttempt = Objects.requireNonNull(controlAttempt, "controlAttempt");
            this.controlTransmitStatus = Objects.requireNonNull(
                    controlTransmitStatus,
                    "controlTransmitStatus"
            );
            this.controlTransmitAccepted = controlTransmitAccepted;
            this.localStopRequested = localStopRequested;
        }

        public static State active(Role role) {
            return active(role, Sequence.zero(), Sequence.of(1L));
        }

        public static State active(Role role, Sequence lastEpoch, Sequence generation) {
            if (Objects.requireNonNull(generation, "generation").equals(Sequence.zero())) {
                throw new IllegalArgumentException("a real active owner requires non-zero generation");
            }
            return new State(
                    Phase.ACTIVE,
                    Objects.requireNonNull(lastEpoch, "lastEpoch"),
                    Objects.requireNonNull(role, "role"),
                    Objects.requireNonNull(role, "role"),
                    generation,
                    null,
                    null,
                    null,
                    null,
                    false,
                    false,
                    false,
                    null,
                    -1L,
                    -1L,
                    -1L,
                    Failure.NONE,
                    null,
                    Sequence.zero(),
                    ControlTransmitStatus.IDLE,
                    false,
                    false
            );
        }

        public Phase phase() {
            return phase;
        }

        /** Last allocated epoch in ACTIVE, otherwise the exact current transition epoch. */
        public Sequence epoch() {
            return epoch;
        }

        /** Persisted selection; unlike activeRole it remains the target throughout teardown. */
        public Role desiredRole() {
            return desiredRole;
        }

        /** Non-null only while an owner is fully ACTIVE. */
        public Role activeRole() {
            return activeRole;
        }

        /** Non-null only while an owner is fully ACTIVE. */
        public Sequence activeGeneration() {
            return activeGeneration;
        }

        public Role sourceRole() {
            return sourceRole;
        }

        public Sequence sourceGeneration() {
            return sourceGeneration;
        }

        public Role targetRole() {
            return targetRole;
        }

        public Sequence targetGeneration() {
            return targetGeneration;
        }

        public boolean ingressFrozen() {
            return ingressFrozen;
        }

        public boolean localTerminal() {
            return localTerminal;
        }

        /** True only after an explicit platform owner-registry/adapter proof. */
        public boolean localOwnersZero() {
            return localOwnersZero;
        }

        public boolean remoteAcknowledged() {
            return remoteCloseEvidence != null;
        }

        public RemoteCloseEvidence remoteCloseEvidence() {
            return remoteCloseEvidence;
        }

        public long stopDeadlineMillis() {
            return stopDeadlineMillis;
        }

        public long drainDeadlineMillis() {
            return drainDeadlineMillis;
        }

        public Failure failure() {
            return failure;
        }

        public ControlFrame controlFrame() {
            return controlFrame;
        }

        public Sequence controlAttempt() {
            return controlAttempt;
        }

        public boolean controlTransmitAccepted() {
            return controlTransmitAccepted;
        }

        public boolean localStopRequested() {
            return localStopRequested;
        }
    }

    public static final class Reduction {
        private final State state;
        private final Outcome outcome;
        private final List<Effect> effects;

        private Reduction(State state, Outcome outcome, List<Effect> effects) {
            this.state = Objects.requireNonNull(state, "state");
            this.outcome = Objects.requireNonNull(outcome, "outcome");
            this.effects = Collections.unmodifiableList(new ArrayList<>(effects));
        }

        public State state() {
            return state;
        }

        public Outcome outcome() {
            return outcome;
        }

        public List<Effect> effects() {
            return effects;
        }
    }

    public Reduction requestSwitch(
            State state,
            Role targetRole,
            long nowMillis,
            long stopTimeoutMillis,
            long drainDurationMillis
    ) {
        return requestSwitchInternal(
                state,
                targetRole,
                nowMillis,
                stopTimeoutMillis,
                drainDurationMillis,
                null,
                ControlFrame.CLOSE_REQUEST,
                false
        );
    }

    /**
     * Replaces a terminal or otherwise unusable framework owner without changing topology.
     *
     * <p>The peer deliberately retains the same role, so this transition never emits C/A.  It
     * still runs the complete local safety boundary: freeze ingress, stop the exact source,
     * observe its terminal callback, prove zero app-owned owners, wait the drain interval, and
     * allocate a fresh target generation.  Reusing a cached GATT/manager is forbidden.</p>
     */
    public Reduction requestSameRoleRestart(
            State state,
            long nowMillis,
            long stopTimeoutMillis,
            long drainDurationMillis
    ) {
        requireState(state);
        if (state.phase != Phase.ACTIVE) {
            return state.phase == Phase.FAILED || state.phase == Phase.CLOSED
                    ? unchanged(state, Outcome.REJECTED_TERMINAL)
                    : unchanged(state, Outcome.COALESCED);
        }
        return requestSwitchInternal(
                state,
                state.activeRole,
                nowMillis,
                stopTimeoutMillis,
                drainDurationMillis,
                RemoteCloseEvidence.PEER_SAME_ROLE_RETAINED,
                null,
                true
        );
    }

    /** Accepts a peer C frame as committed intent while retaining every local drain gate. */
    public Reduction requestSwitchFromRemoteIntent(
            State state,
            Role targetRole,
            long nowMillis,
            long stopTimeoutMillis,
            long drainDurationMillis
    ) {
        return requestSwitchInternal(
                state,
                targetRole,
                nowMillis,
                stopTimeoutMillis,
                drainDurationMillis,
                RemoteCloseEvidence.PEER_COMMITTED_INTENT,
                ControlFrame.CLOSE_ACK,
                false
        );
    }

    private Reduction requestSwitchInternal(
            State state,
            Role targetRole,
            long nowMillis,
            long stopTimeoutMillis,
            long drainDurationMillis,
            RemoteCloseEvidence initialRemoteEvidence,
            ControlFrame controlFrame,
            boolean allowSameRoleRestart
    ) {
        requireState(state);
        Objects.requireNonNull(targetRole, "targetRole");
        requireNonNegative("nowMillis", nowMillis);
        if (stopTimeoutMillis <= 0L) {
            throw new IllegalArgumentException("stopTimeoutMillis must be positive");
        }
        if (drainDurationMillis <= 0L) {
            throw new IllegalArgumentException("drainDurationMillis must be positive");
        }

        if (state.phase == Phase.FAILED || state.phase == Phase.CLOSED) {
            return unchanged(state, Outcome.REJECTED_TERMINAL);
        }
        if (state.phase != Phase.ACTIVE) {
            return targetRole == state.targetRole
                    ? unchanged(state, Outcome.COALESCED)
                    : unchanged(state, Outcome.REJECTED_CONFLICT);
        }
        if (targetRole == state.activeRole && !allowSameRoleRestart) {
            return unchanged(
                    state,
                    initialRemoteEvidence == RemoteCloseEvidence.PEER_COMMITTED_INTENT
                            ? Outcome.REJECTED_CONFLICT
                            : Outcome.COALESCED
            );
        }

        Sequence epoch = state.epoch.next();
        Sequence targetGeneration = state.activeGeneration.next();
        long stopDeadline = saturatingAdd(nowMillis, stopTimeoutMillis);
        State next = new State(
                Phase.FREEZING,
                epoch,
                targetRole,
                null,
                null,
                state.activeRole,
                state.activeGeneration,
                targetRole,
                targetGeneration,
                false,
                false,
                false,
                initialRemoteEvidence,
                stopDeadline,
                drainDurationMillis,
                -1L,
                Failure.NONE,
                controlFrame,
                Sequence.zero(),
                ControlTransmitStatus.IDLE,
                false,
                false
        );
        return applied(
                next,
                effect(EffectType.FREEZE_SOURCE_INGRESS, next, false, -1L),
                effect(EffectType.ARM_STOP_TIMEOUT, next, false, stopDeadline)
        );
    }

    /**
     * Re-enters teardown after process restoration. The restored source owner is drain-only: this
     * method never emits START_TARGET and does not treat a persisted "disconnected" bit as proof.
     */
    public Reduction restoreDrain(
            Role sourceRole,
            Role desiredRole,
            Sequence epoch,
            Sequence sourceGeneration,
            Sequence targetGeneration,
            long nowMillis,
            long stopTimeoutMillis,
            long drainDurationMillis
    ) {
        return restoreDrainInternal(
                sourceRole,
                desiredRole,
                epoch,
                sourceGeneration,
                targetGeneration,
                nowMillis,
                stopTimeoutMillis,
                drainDurationMillis,
                null,
                ControlFrame.CLOSE_REQUEST
        );
    }

    /** Restores a persisted peer-originated C without converting it into a local C request. */
    public Reduction restoreDrainFromRemoteIntent(
            Role sourceRole,
            Role desiredRole,
            Sequence epoch,
            Sequence sourceGeneration,
            Sequence targetGeneration,
            long nowMillis,
            long stopTimeoutMillis,
            long drainDurationMillis
    ) {
        return restoreDrainInternal(
                sourceRole,
                desiredRole,
                epoch,
                sourceGeneration,
                targetGeneration,
                nowMillis,
                stopTimeoutMillis,
                drainDurationMillis,
                RemoteCloseEvidence.PEER_COMMITTED_INTENT,
                ControlFrame.CLOSE_ACK
        );
    }

    /** Restores one process owner while the peer intentionally keeps the same route role. */
    public Reduction restoreDrainLocalOnly(
            Role role,
            Sequence epoch,
            Sequence sourceGeneration,
            Sequence targetGeneration,
            long nowMillis,
            long stopTimeoutMillis,
            long drainDurationMillis
    ) {
        return restoreDrainInternal(
                role,
                role,
                epoch,
                sourceGeneration,
                targetGeneration,
                nowMillis,
                stopTimeoutMillis,
                drainDurationMillis,
                RemoteCloseEvidence.PEER_SAME_ROLE_RETAINED,
                null
        );
    }

    private Reduction restoreDrainInternal(
            Role sourceRole,
            Role desiredRole,
            Sequence epoch,
            Sequence sourceGeneration,
            Sequence targetGeneration,
            long nowMillis,
            long stopTimeoutMillis,
            long drainDurationMillis,
            RemoteCloseEvidence initialRemoteEvidence,
            ControlFrame controlFrame
    ) {
        Objects.requireNonNull(sourceRole, "sourceRole");
        Objects.requireNonNull(desiredRole, "desiredRole");
        requireNonZero("epoch", epoch);
        requireNonZero("sourceGeneration", sourceGeneration);
        requireNonZero("targetGeneration", targetGeneration);
        requireNonNegative("nowMillis", nowMillis);
        if (stopTimeoutMillis <= 0L || drainDurationMillis <= 0L) {
            throw new IllegalArgumentException("restoration deadlines must be positive");
        }

        long stopDeadline = saturatingAdd(nowMillis, stopTimeoutMillis);
        State restored = new State(
                Phase.FREEZING,
                epoch,
                desiredRole,
                null,
                null,
                sourceRole,
                sourceGeneration,
                desiredRole,
                targetGeneration,
                false,
                false,
                false,
                initialRemoteEvidence,
                stopDeadline,
                drainDurationMillis,
                -1L,
                Failure.NONE,
                controlFrame,
                Sequence.zero(),
                ControlTransmitStatus.IDLE,
                false,
                false
        );
        return applied(
                restored,
                effect(EffectType.FREEZE_SOURCE_INGRESS, restored, false, -1L),
                effect(EffectType.ARM_STOP_TIMEOUT, restored, false, stopDeadline)
        );
    }

    public Reduction onIngressFrozen(
            State state,
            Sequence epoch,
            Sequence sourceGeneration,
            Role sourceRole,
            long nowMillis
    ) {
        requireState(state);
        requireNonNegative("nowMillis", nowMillis);
        if (!ownsSource(state, epoch, sourceGeneration, sourceRole)) {
            return unchanged(state, Outcome.STALE_CALLBACK);
        }
        if (!isStopping(state.phase)) {
            return unchanged(state, Outcome.STALE_CALLBACK);
        }
        Reduction timedOut = timeoutIfDue(state, nowMillis);
        if (timedOut != null) {
            return timedOut;
        }
        if (state.phase != Phase.FREEZING) {
            return unchanged(state, state.ingressFrozen ? Outcome.COALESCED : Outcome.STALE_CALLBACK);
        }

        State next = copyEvidence(
                state,
                true,
                state.localTerminal,
                state.localOwnersZero,
                state.remoteCloseEvidence
        );
        List<Effect> effects = new ArrayList<>();
        if (!next.localTerminal
                && next.controlFrame == ControlFrame.CLOSE_REQUEST
                && next.remoteCloseEvidence == null) {
            next = beginControlTransmit(next);
            effects.add(controlEffect(EffectType.REQUEST_REMOTE_STOP, next, -1L));
        } else if (!next.localTerminal
                && next.controlFrame == ControlFrame.CLOSE_ACK
                && !next.controlTransmitAccepted) {
            next = beginControlTransmit(next);
            effects.add(controlEffect(EffectType.ACKNOWLEDGE_REMOTE_STOP, next, -1L));
        } else if (!next.localTerminal && !next.localStopRequested) {
            next = copyControl(
                    next,
                    next.controlAttempt,
                    next.controlTransmitStatus,
                    next.controlTransmitAccepted,
                    true
            );
            effects.add(effect(EffectType.STOP_LOCAL_SOURCE, next, false, -1L));
        }
        return settleEvidence(next, nowMillis, effects);
    }

    /** Fails immediately when the platform cannot fence the exact persisted source owner. */
    public Reduction onIngressFreezeFailed(
            State state,
            Sequence epoch,
            Sequence sourceGeneration,
            Role sourceRole
    ) {
        requireState(state);
        if (!ownsSource(state, epoch, sourceGeneration, sourceRole)
                || state.phase != Phase.FREEZING) {
            return unchanged(state, Outcome.STALE_CALLBACK);
        }
        State failed = copyPhase(state, Phase.FAILED, Failure.INGRESS_FREEZE_FAILED);
        return applied(failed, effect(EffectType.FAIL_CLOSED, failed, false, -1L));
    }

    /**
     * Atomically records a frozen acquisition boundary and typed absence of a control peer.
     *
     * <p>This must be a single reducer input: calling {@link #onIngressFrozen} first would emit a
     * C/A attempt before the adapter's stronger no-peer proof was committed.  Existing remote
     * committed-intent evidence from an inbound C is retained.</p>
     */
    public Reduction onIngressFrozenWithoutRemoteOwner(
            State state,
            Sequence epoch,
            Sequence sourceGeneration,
            Role sourceRole,
            long nowMillis
    ) {
        requireState(state);
        requireNonNegative("nowMillis", nowMillis);
        if (!ownsSource(state, epoch, sourceGeneration, sourceRole)
                || state.phase != Phase.FREEZING) {
            return unchanged(state, Outcome.STALE_CALLBACK);
        }
        Reduction timedOut = timeoutIfDue(state, nowMillis);
        if (timedOut != null) return timedOut;

        RemoteCloseEvidence evidence = state.remoteCloseEvidence != null
                ? state.remoteCloseEvidence : RemoteCloseEvidence.NO_REMOTE_OWNER;
        State next = copyEvidence(
                state,
                true,
                state.localTerminal,
                state.localOwnersZero,
                evidence
        );
        next = copyControl(
                next,
                next.controlAttempt,
                ControlTransmitStatus.ACCEPTED,
                true,
                true
        );
        List<Effect> effects = new ArrayList<>();
        if (!next.localTerminal) {
            effects.add(effect(EffectType.STOP_LOCAL_SOURCE, next, false, -1L));
        }
        return settleEvidence(next, nowMillis, effects);
    }

    /**
     * Completes one exact asynchronous C/A transmission attempt. A successful C transmission is
     * not permission to close: only the exact inbound A is. A successful A transmission is the
     * remote-origin permission to begin deterministic client-first teardown.
     */
    public Reduction onControlTransmitResult(
            State state,
            Sequence epoch,
            Sequence sourceGeneration,
            Role sourceRole,
            ControlFrame frame,
            Sequence attempt,
            ControlTransmitResult result,
            long nowMillis
    ) {
        requireState(state);
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(attempt, "attempt");
        Objects.requireNonNull(result, "result");
        requireNonNegative("nowMillis", nowMillis);
        if (!ownsSource(state, epoch, sourceGeneration, sourceRole)
                || !isStopping(state.phase)
                || state.controlFrame != frame
                || !state.controlAttempt.equals(attempt)
                || state.controlTransmitStatus != ControlTransmitStatus.IN_FLIGHT) {
            return unchanged(state, Outcome.STALE_CALLBACK);
        }
        Reduction timedOut = timeoutIfDue(state, nowMillis);
        if (timedOut != null) {
            return timedOut;
        }

        if (result == ControlTransmitResult.TERMINAL_FAILURE) {
            if (!state.controlTransmitAccepted) {
                State failed = copyPhase(state, Phase.FAILED, Failure.CONTROL_TRANSMIT_FAILED);
                return applied(failed, effect(EffectType.FAIL_CLOSED, failed, false, -1L));
            }
            State retained = copyControl(
                    state,
                    state.controlAttempt,
                    ControlTransmitStatus.ACCEPTED,
                    true,
                    state.localStopRequested
            );
            return settleEvidence(retained, nowMillis, new ArrayList<>());
        }

        if (result == ControlTransmitResult.ACCEPTED) {
            State next = copyControl(
                    state,
                    state.controlAttempt,
                    ControlTransmitStatus.ACCEPTED,
                    true,
                    state.localStopRequested
            );
            List<Effect> effects = new ArrayList<>();
            if (frame == ControlFrame.CLOSE_ACK
                    && !next.localTerminal
                    && !next.localStopRequested) {
                next = copyControl(
                        next,
                        next.controlAttempt,
                        next.controlTransmitStatus,
                        true,
                        true
                );
                effects.add(effect(EffectType.STOP_LOCAL_SOURCE, next, false, -1L));
            }
            return settleEvidence(next, nowMillis, effects);
        }

        /* A failed duplicate A cannot revoke an earlier accepted A. It may still be retried while
         * this exact source owner exists, but it never regresses the teardown gates. */
        Sequence retryAttempt = state.controlAttempt.next();
        State retry = copyControl(
                state,
                retryAttempt,
                ControlTransmitStatus.RETRY_WAIT,
                state.controlTransmitAccepted,
                state.localStopRequested
        );
        return settleEvidence(
                retry,
                nowMillis,
                new ArrayList<>(List.of(controlEffect(
                        EffectType.SCHEDULE_CONTROL_RETRY,
                        retry,
                        state.stopDeadlineMillis
                )))
        );
    }

    /** Fires the exact retry previously armed by {@link #onControlTransmitResult}. */
    public Reduction onControlTransmitRetry(
            State state,
            Sequence epoch,
            Sequence sourceGeneration,
            Role sourceRole,
            ControlFrame frame,
            Sequence attempt,
            long nowMillis
    ) {
        requireState(state);
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(attempt, "attempt");
        requireNonNegative("nowMillis", nowMillis);
        if (!ownsSource(state, epoch, sourceGeneration, sourceRole)
                || !isStopping(state.phase)
                || state.controlFrame != frame
                || !state.controlAttempt.equals(attempt)
                || state.controlTransmitStatus != ControlTransmitStatus.RETRY_WAIT) {
            return unchanged(state, Outcome.STALE_CALLBACK);
        }
        Reduction timedOut = timeoutIfDue(state, nowMillis);
        if (timedOut != null) {
            return timedOut;
        }
        State inFlight = copyControl(
                state,
                state.controlAttempt,
                ControlTransmitStatus.IN_FLIGHT,
                state.controlTransmitAccepted,
                state.localStopRequested
        );
        EffectType effectType = frame == ControlFrame.CLOSE_REQUEST
                ? EffectType.REQUEST_REMOTE_STOP
                : EffectType.ACKNOWLEDGE_REMOTE_STOP;
        return new Reduction(
                inFlight,
                Outcome.APPLIED,
                List.of(controlEffect(effectType, inFlight, -1L))
        );
    }

    /** Re-emits A for one exact duplicate C without changing the committed token or deadline. */
    public Reduction onDuplicateRemoteIntent(
            State state,
            Sequence epoch,
            Sequence sourceGeneration,
            Role sourceRole,
            long nowMillis
    ) {
        requireState(state);
        requireNonNegative("nowMillis", nowMillis);
        if (!ownsSource(state, epoch, sourceGeneration, sourceRole)
                || !isStopping(state.phase)
                || state.controlFrame != ControlFrame.CLOSE_ACK) {
            return unchanged(state, Outcome.STALE_CALLBACK);
        }
        Reduction timedOut = timeoutIfDue(state, nowMillis);
        if (timedOut != null) {
            return timedOut;
        }
        if (!state.ingressFrozen || state.localTerminal) {
            return unchanged(state, Outcome.COALESCED);
        }

        /* Android and CoreBluetooth identify characteristic write completion only by the current
         * wrapper/characteristic; they do not echo our application attempt number.  Starting a
         * second A while the first is still in flight would make the first callback ambiguous and
         * could strand both peers.  The existing attempt already covers this duplicate: success
         * proves delivery, failure schedules the same-token retry. */
        if (state.controlTransmitStatus == ControlTransmitStatus.IN_FLIGHT) {
            return unchanged(state, Outcome.COALESCED);
        }

        Sequence nextAttempt = state.controlAttempt.equals(Sequence.zero())
                ? Sequence.of(1L)
                : state.controlAttempt.next();
        State retransmitting = copyControl(
                state,
                nextAttempt,
                ControlTransmitStatus.IN_FLIGHT,
                state.controlTransmitAccepted,
                state.localStopRequested
        );
        List<Effect> effects = new ArrayList<>();
        if (state.controlTransmitStatus == ControlTransmitStatus.RETRY_WAIT) {
            effects.add(controlEffect(EffectType.CANCEL_CONTROL_RETRY, state, -1L));
        }
        effects.add(controlEffect(EffectType.ACKNOWLEDGE_REMOTE_STOP, retransmitting, -1L));
        return new Reduction(retransmitting, Outcome.APPLIED, effects);
    }

    public Reduction onLocalTerminal(
            State state,
            Sequence epoch,
            Sequence sourceGeneration,
            Role sourceRole,
            long nowMillis
    ) {
        requireState(state);
        requireNonNegative("nowMillis", nowMillis);
        if (!ownsSource(state, epoch, sourceGeneration, sourceRole)) {
            return unchanged(state, Outcome.STALE_CALLBACK);
        }
        if (!isStopping(state.phase)) {
            return unchanged(state, Outcome.STALE_CALLBACK);
        }
        Reduction timedOut = timeoutIfDue(state, nowMillis);
        if (timedOut != null) {
            return timedOut;
        }
        if (state.localTerminal) {
            return unchanged(state, Outcome.COALESCED);
        }

        State next = copyEvidence(
                state,
                state.ingressFrozen,
                true,
                state.localOwnersZero,
                state.remoteCloseEvidence
        );
        next = copyControl(
                next,
                next.controlAttempt,
                next.controlTransmitStatus,
                next.controlTransmitAccepted,
                true
        );
        List<Effect> effects = new ArrayList<>();
        if (!state.localOwnersZero) {
            effects.add(effect(EffectType.VERIFY_LOCAL_OWNERS, next, false, -1L));
        }
        return settleEvidence(next, nowMillis, effects);
    }

    /**
     * Supplies an explicit platform-adapter owner count. A count of zero is the only registry
     * evidence accepted; merely calling close or observing no connected device is not evidence.
     */
    public Reduction onLocalOwnerCount(
            State state,
            Sequence epoch,
            Sequence sourceGeneration,
            Role sourceRole,
            int ownerCount,
            long nowMillis
    ) {
        requireState(state);
        requireNonNegative("nowMillis", nowMillis);
        if (ownerCount < 0) {
            throw new IllegalArgumentException("ownerCount must be non-negative");
        }
        if (!ownsSource(state, epoch, sourceGeneration, sourceRole)) {
            return unchanged(state, Outcome.STALE_CALLBACK);
        }
        if (!acceptsLocalOwnerEvidence(state.phase)) {
            return unchanged(state, Outcome.STALE_CALLBACK);
        }
        if (isStopping(state.phase)) {
            Reduction timedOut = timeoutIfDue(state, nowMillis);
            if (timedOut != null) {
                return timedOut;
            }
        }
        if (ownerCount > 1) {
            State failed = copyPhase(state, Phase.FAILED, Failure.IMPOSSIBLE_LOCAL_OWNER_COUNT);
            return applied(failed, effect(EffectType.FAIL_CLOSED, failed, false, -1L));
        }
        if (state.localOwnersZero && ownerCount != 0) {
            State failed = copyPhase(
                    state,
                    Phase.FAILED,
                    Failure.CONTRADICTORY_LOCAL_OWNER_EVIDENCE
            );
            return applied(failed, effect(EffectType.FAIL_CLOSED, failed, false, -1L));
        }
        if (ownerCount == 1 || state.localOwnersZero) {
            return unchanged(state, Outcome.COALESCED);
        }

        State next = copyEvidence(
                state,
                state.ingressFrozen,
                state.localTerminal,
                true,
                state.remoteCloseEvidence
        );
        return settleEvidence(next, nowMillis, new ArrayList<>());
    }

    /** Records peer switch commitment/no-owner evidence; it never sets local terminal evidence. */
    public Reduction onRemoteClosedEvidence(
            State state,
            Sequence epoch,
            Sequence sourceGeneration,
            Role stoppedRole,
            RemoteCloseEvidence evidence,
            long nowMillis
    ) {
        requireState(state);
        Objects.requireNonNull(evidence, "evidence");
        requireNonNegative("nowMillis", nowMillis);
        if (!ownsSourceTokens(state, epoch, sourceGeneration) || !isStopping(state.phase)) {
            return unchanged(state, Outcome.STALE_CALLBACK);
        }
        Reduction timedOut = timeoutIfDue(state, nowMillis);
        if (timedOut != null) {
            return timedOut;
        }
        if (stoppedRole != state.sourceRole) {
            State failed = copyPhase(
                    state,
                    Phase.FAILED,
                    Failure.CONTRADICTORY_REMOTE_EVIDENCE
            );
            return applied(failed, effect(EffectType.FAIL_CLOSED, failed, false, -1L));
        }
        if (state.remoteCloseEvidence != null) {
            if (state.remoteCloseEvidence != evidence) {
                State failed = copyPhase(
                        state,
                        Phase.FAILED,
                        Failure.CONTRADICTORY_REMOTE_EVIDENCE
                );
                return applied(failed, effect(EffectType.FAIL_CLOSED, failed, false, -1L));
            }
            return unchanged(state, Outcome.COALESCED);
        }

        State next = copyEvidence(
                state,
                state.ingressFrozen,
                state.localTerminal,
                state.localOwnersZero,
                evidence
        );
        List<Effect> effects = new ArrayList<>();
        if (state.controlFrame == ControlFrame.CLOSE_REQUEST) {
            if (state.controlTransmitStatus == ControlTransmitStatus.RETRY_WAIT) {
                effects.add(controlEffect(EffectType.CANCEL_CONTROL_RETRY, state, -1L));
            }
            next = copyControl(
                    next,
                    next.controlAttempt,
                    ControlTransmitStatus.ACCEPTED,
                    true,
                    next.localStopRequested
            );
            if (next.ingressFrozen && !next.localTerminal && !next.localStopRequested) {
                next = copyControl(
                        next,
                        next.controlAttempt,
                        next.controlTransmitStatus,
                        true,
                        true
                );
                effects.add(effect(EffectType.STOP_LOCAL_SOURCE, next, false, -1L));
            }
        }
        return settleEvidence(next, nowMillis, effects);
    }

    /**
     * Records radio/power loss as terminal-link evidence. It never proves localOwnersZero by
     * itself: the caller must separately provide an exact owner-registry count of zero.
     */
    public Reduction onRadioOrPowerLoss(
            State state,
            Sequence epoch,
            Sequence sourceGeneration,
            Role sourceRole,
            long nowMillis
    ) {
        requireState(state);
        requireNonNegative("nowMillis", nowMillis);
        if (!ownsSource(state, epoch, sourceGeneration, sourceRole) || !isStopping(state.phase)) {
            return unchanged(state, Outcome.STALE_CALLBACK);
        }
        Reduction timedOut = timeoutIfDue(state, nowMillis);
        if (timedOut != null) {
            return timedOut;
        }
        if (state.localTerminal && state.remoteCloseEvidence != null) {
            return unchanged(state, Outcome.COALESCED);
        }

        State next = copyEvidence(
                state,
                state.ingressFrozen,
                true,
                state.localOwnersZero,
                state.remoteCloseEvidence != null
                        ? state.remoteCloseEvidence
                        : RemoteCloseEvidence.RADIO_OR_POWER_LOSS
        );
        next = copyControl(
                next,
                next.controlAttempt,
                ControlTransmitStatus.ACCEPTED,
                true,
                true
        );
        List<Effect> effects = new ArrayList<>();
        if (!state.localOwnersZero) {
            effects.add(effect(EffectType.VERIFY_LOCAL_OWNERS, next, false, -1L));
        }
        return settleEvidence(next, nowMillis, effects);
    }

    public Reduction onStopTimeout(
            State state,
            Sequence epoch,
            Sequence sourceGeneration,
            Role sourceRole,
            long nowMillis
    ) {
        requireState(state);
        requireNonNegative("nowMillis", nowMillis);
        if (!ownsSource(state, epoch, sourceGeneration, sourceRole) || !isStopping(state.phase)) {
            return unchanged(state, Outcome.STALE_CALLBACK);
        }
        if (nowMillis < state.stopDeadlineMillis) {
            return unchanged(state, Outcome.NOT_DUE);
        }

        State failed = copyPhase(state, Phase.FAILED, Failure.STOP_TIMEOUT);
        return applied(failed, effect(EffectType.FAIL_CLOSED, failed, false, -1L));
    }

    public Reduction onDrainDeadline(
            State state,
            Sequence epoch,
            Sequence sourceGeneration,
            Role sourceRole,
            long nowMillis
    ) {
        requireState(state);
        requireNonNegative("nowMillis", nowMillis);
        if (!ownsSource(state, epoch, sourceGeneration, sourceRole)
                || state.phase != Phase.DRAINING) {
            return unchanged(state, Outcome.STALE_CALLBACK);
        }
        if (nowMillis < state.drainDeadlineMillis) {
            return unchanged(state, Outcome.NOT_DUE);
        }

        State quiescent = copyPhase(state, Phase.QUIESCENT, Failure.NONE);
        return applied(
                quiescent,
                effect(EffectType.CANCEL_DRAIN_DEADLINE, quiescent, false, -1L),
                effect(EffectType.QUIESCENT_REACHED, quiescent, true, -1L)
        );
    }

    /** The sole transition that is allowed to emit START_TARGET. */
    public Reduction beginTargetStart(
            State state,
            Sequence epoch,
            Sequence targetGeneration,
            Role targetRole
    ) {
        requireState(state);
        if (!ownsTarget(state, epoch, targetGeneration, targetRole)
                || state.phase != Phase.QUIESCENT) {
            return unchanged(state, Outcome.STALE_CALLBACK);
        }

        State starting = copyPhase(state, Phase.STARTING, Failure.NONE);
        return applied(starting, effect(EffectType.START_TARGET, starting, true, -1L));
    }

    public Reduction onTargetActive(
            State state,
            Sequence epoch,
            Sequence targetGeneration,
            Role targetRole
    ) {
        requireState(state);
        if (!ownsTarget(state, epoch, targetGeneration, targetRole)
                || state.phase != Phase.STARTING) {
            return unchanged(state, Outcome.STALE_CALLBACK);
        }

        State active = new State(
                Phase.ACTIVE,
                state.epoch,
                state.targetRole,
                state.targetRole,
                state.targetGeneration,
                null,
                null,
                null,
                null,
                false,
                false,
                false,
                null,
                -1L,
                -1L,
                -1L,
                Failure.NONE,
                null,
                Sequence.zero(),
                ControlTransmitStatus.IDLE,
                false,
                false
        );
        return applied(active, new Effect(
                EffectType.TARGET_ACTIVE,
                state.epoch,
                state.targetGeneration,
                state.targetRole,
                -1L,
                null,
                Sequence.zero()
        ));
    }

    public Reduction onTargetStartFailed(
            State state,
            Sequence epoch,
            Sequence targetGeneration,
            Role targetRole
    ) {
        requireState(state);
        if (!ownsTarget(state, epoch, targetGeneration, targetRole)
                || state.phase != Phase.STARTING) {
            return unchanged(state, Outcome.STALE_CALLBACK);
        }

        State failed = copyPhase(state, Phase.FAILED, Failure.TARGET_START_FAILED);
        return applied(failed, effect(EffectType.FAIL_CLOSED, failed, true, -1L));
    }

    /** Explicit terminal shutdown. CLOSED never accepts another route request or callback. */
    public Reduction close(State state) {
        requireState(state);
        if (state.phase == Phase.CLOSED) {
            return unchanged(state, Outcome.COALESCED);
        }

        Role storedSourceRole = state.phase == Phase.ACTIVE ? state.activeRole : state.sourceRole;
        Sequence storedSourceGeneration = state.phase == Phase.ACTIVE
                ? state.activeGeneration
                : state.sourceGeneration;
        Role storedTargetRole = state.phase == Phase.ACTIVE ? state.activeRole : state.targetRole;
        Sequence storedTargetGeneration = state.phase == Phase.ACTIVE
                ? state.activeGeneration.next()
                : state.targetGeneration;
        Sequence closeEpoch = state.phase == Phase.ACTIVE ? state.epoch.next() : state.epoch;
        State closed = new State(
                Phase.CLOSED,
                closeEpoch,
                state.desiredRole,
                null,
                null,
                storedSourceRole,
                storedSourceGeneration,
                storedTargetRole,
                storedTargetGeneration,
                true,
                state.localTerminal,
                state.localOwnersZero,
                state.remoteCloseEvidence,
                -1L,
                -1L,
                -1L,
                state.failure,
                state.controlFrame,
                state.controlAttempt,
                state.controlTransmitStatus,
                state.controlTransmitAccepted,
                true
        );
        return applied(closed, new Effect(
                EffectType.CLOSE_ALL,
                closeEpoch,
                storedSourceGeneration,
                storedSourceRole,
                -1L,
                null,
                Sequence.zero()
        ));
    }

    private static Reduction settleEvidence(State state, long nowMillis, List<Effect> effects) {
        if (!state.ingressFrozen) {
            return new Reduction(copyPhase(state, Phase.FREEZING, Failure.NONE), Outcome.APPLIED, effects);
        }
        if (!controlHandshakeSatisfied(state) || !state.localStopRequested) {
            return new Reduction(
                    copyPhase(state, Phase.WAITING_CONTROL_HANDSHAKE, Failure.NONE),
                    Outcome.APPLIED,
                    effects
            );
        }
        if (!state.localTerminal || !state.localOwnersZero) {
            return new Reduction(
                    copyPhase(state, Phase.WAITING_LOCAL_TERMINAL, Failure.NONE),
                    Outcome.APPLIED,
                    effects
            );
        }
        if (state.remoteCloseEvidence == null) {
            return new Reduction(
                    copyPhase(state, Phase.WAITING_REMOTE_ACK, Failure.NONE),
                    Outcome.APPLIED,
                    effects
            );
        }

        long drainDeadline = saturatingAdd(nowMillis, state.drainDurationMillis);
        State draining = new State(
                Phase.DRAINING,
                state.epoch,
                state.desiredRole,
                null,
                null,
                state.sourceRole,
                state.sourceGeneration,
                state.targetRole,
                state.targetGeneration,
                true,
                true,
                true,
                state.remoteCloseEvidence,
                state.stopDeadlineMillis,
                state.drainDurationMillis,
                drainDeadline,
                Failure.NONE,
                state.controlFrame,
                state.controlAttempt,
                state.controlTransmitStatus,
                state.controlTransmitAccepted,
                state.localStopRequested
        );
        if (state.controlTransmitStatus == ControlTransmitStatus.RETRY_WAIT) {
            effects.add(controlEffect(EffectType.CANCEL_CONTROL_RETRY, state, -1L));
        }
        effects.add(effect(EffectType.CANCEL_STOP_TIMEOUT, draining, false, -1L));
        effects.add(effect(EffectType.ARM_DRAIN_DEADLINE, draining, false, drainDeadline));
        return new Reduction(draining, Outcome.APPLIED, effects);
    }

    private static State copyEvidence(
            State state,
            boolean ingressFrozen,
            boolean localTerminal,
            boolean localOwnersZero,
            RemoteCloseEvidence remoteCloseEvidence
    ) {
        return new State(
                state.phase,
                state.epoch,
                state.desiredRole,
                state.activeRole,
                state.activeGeneration,
                state.sourceRole,
                state.sourceGeneration,
                state.targetRole,
                state.targetGeneration,
                ingressFrozen,
                localTerminal,
                localOwnersZero,
                remoteCloseEvidence,
                state.stopDeadlineMillis,
                state.drainDurationMillis,
                state.drainDeadlineMillis,
                state.failure,
                state.controlFrame,
                state.controlAttempt,
                state.controlTransmitStatus,
                state.controlTransmitAccepted,
                state.localStopRequested
        );
    }

    private static State copyControl(
            State state,
            Sequence controlAttempt,
            ControlTransmitStatus controlTransmitStatus,
            boolean controlTransmitAccepted,
            boolean localStopRequested
    ) {
        return new State(
                state.phase,
                state.epoch,
                state.desiredRole,
                state.activeRole,
                state.activeGeneration,
                state.sourceRole,
                state.sourceGeneration,
                state.targetRole,
                state.targetGeneration,
                state.ingressFrozen,
                state.localTerminal,
                state.localOwnersZero,
                state.remoteCloseEvidence,
                state.stopDeadlineMillis,
                state.drainDurationMillis,
                state.drainDeadlineMillis,
                state.failure,
                state.controlFrame,
                controlAttempt,
                controlTransmitStatus,
                controlTransmitAccepted,
                localStopRequested
        );
    }

    private static State copyPhase(State state, Phase phase, Failure failure) {
        return new State(
                phase,
                state.epoch,
                state.desiredRole,
                state.activeRole,
                state.activeGeneration,
                state.sourceRole,
                state.sourceGeneration,
                state.targetRole,
                state.targetGeneration,
                state.ingressFrozen,
                state.localTerminal,
                state.localOwnersZero,
                state.remoteCloseEvidence,
                state.stopDeadlineMillis,
                state.drainDurationMillis,
                state.drainDeadlineMillis,
                failure,
                state.controlFrame,
                state.controlAttempt,
                state.controlTransmitStatus,
                state.controlTransmitAccepted,
                state.localStopRequested
        );
    }

    private static Effect effect(EffectType type, State state, boolean target, long deadlineMillis) {
        return new Effect(
                type,
                state.epoch,
                target ? state.targetGeneration : state.sourceGeneration,
                target ? state.targetRole : state.sourceRole,
                deadlineMillis,
                null,
                Sequence.zero()
        );
    }

    private static Effect controlEffect(EffectType type, State state, long deadlineMillis) {
        return new Effect(
                type,
                state.epoch,
                state.sourceGeneration,
                state.sourceRole,
                deadlineMillis,
                state.controlFrame,
                state.controlAttempt
        );
    }

    private static State beginControlTransmit(State state) {
        Sequence attempt = state.controlAttempt.equals(Sequence.zero())
                ? Sequence.of(1L)
                : state.controlAttempt.next();
        return copyControl(
                state,
                attempt,
                ControlTransmitStatus.IN_FLIGHT,
                state.controlTransmitAccepted,
                state.localStopRequested
        );
    }

    private static boolean controlHandshakeSatisfied(State state) {
        if (state.controlFrame == null) {
            return true;
        }
        if (state.controlFrame == ControlFrame.CLOSE_REQUEST) {
            return state.remoteCloseEvidence != null;
        }
        return state.controlTransmitAccepted;
    }

    private static Reduction applied(State state, Effect... effects) {
        List<Effect> list = new ArrayList<>(effects.length);
        Collections.addAll(list, effects);
        return new Reduction(state, Outcome.APPLIED, list);
    }

    private static Reduction unchanged(State state, Outcome outcome) {
        return new Reduction(state, outcome, Collections.emptyList());
    }

    private static boolean ownsSource(
            State state,
            Sequence epoch,
            Sequence generation,
            Role role
    ) {
        return ownsSourceTokens(state, epoch, generation) && role == state.sourceRole;
    }

    private static boolean ownsSourceTokens(State state, Sequence epoch, Sequence generation) {
        return epoch != null
                && generation != null
                && epoch.equals(state.epoch)
                && generation.equals(state.sourceGeneration);
    }

    private static boolean ownsTarget(
            State state,
            Sequence epoch,
            Sequence generation,
            Role role
    ) {
        return epoch != null
                && generation != null
                && epoch.equals(state.epoch)
                && generation.equals(state.targetGeneration)
                && role == state.targetRole;
    }

    private static boolean isStopping(Phase phase) {
        return phase == Phase.FREEZING
                || phase == Phase.WAITING_CONTROL_HANDSHAKE
                || phase == Phase.WAITING_LOCAL_TERMINAL
                || phase == Phase.WAITING_REMOTE_ACK;
    }

    private static boolean acceptsLocalOwnerEvidence(Phase phase) {
        return isStopping(phase)
                || phase == Phase.DRAINING
                || phase == Phase.QUIESCENT
                || phase == Phase.STARTING;
    }

    private static long saturatingAdd(long left, long right) {
        if (right > Long.MAX_VALUE - left) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    private static void requireState(State state) {
        Objects.requireNonNull(state, "state");
    }

    private static void requireNonNegative(String name, long value) {
        if (value < 0L) {
            throw new IllegalArgumentException(name + " must be non-negative");
        }
    }

    private static void requireNonZero(String name, Sequence sequence) {
        if (Objects.requireNonNull(sequence, name).equals(Sequence.zero())) {
            throw new IllegalArgumentException(name + " must be non-zero");
        }
    }

    private static Reduction timeoutIfDue(State state, long nowMillis) {
        if (nowMillis < state.stopDeadlineMillis) {
            return null;
        }
        State failed = copyPhase(state, Phase.FAILED, Failure.STOP_TIMEOUT);
        return applied(failed, effect(EffectType.FAIL_CLOSED, failed, false, -1L));
    }
}
