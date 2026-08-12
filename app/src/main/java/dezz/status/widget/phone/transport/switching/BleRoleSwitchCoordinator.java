/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.phone.transport.switching;

import dezz.status.widget.phone.transport.switching.BleRoleSwitchReducer.Effect;
import dezz.status.widget.phone.transport.switching.BleRoleSwitchReducer.ControlFrame;
import dezz.status.widget.phone.transport.switching.BleRoleSwitchReducer.ControlTransmitResult;
import dezz.status.widget.phone.transport.switching.BleRoleSwitchReducer.Failure;
import dezz.status.widget.phone.transport.switching.BleRoleSwitchReducer.Outcome;
import dezz.status.widget.phone.transport.switching.BleRoleSwitchReducer.Phase;
import dezz.status.widget.phone.transport.switching.BleRoleSwitchReducer.Reduction;
import dezz.status.widget.phone.transport.switching.BleRoleSwitchReducer.RemoteCloseEvidence;
import dezz.status.widget.phone.transport.switching.BleRoleSwitchReducer.Role;
import dezz.status.widget.phone.transport.switching.BleRoleSwitchReducer.Sequence;
import dezz.status.widget.phone.transport.switching.BleRoleSwitchReducer.State;
import dezz.status.widget.phone.transport.switching.BleRoleSwitchSnapshotCodec.Kind;
import dezz.status.widget.phone.transport.switching.BleRoleSwitchSnapshotCodec.Snapshot;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Objects;

/**
 * Serialized, platform-neutral executor for {@link BleRoleSwitchReducer} effects.
 *
 * <p>The coordinator performs a write-ahead snapshot before every applied effect batch. If the
 * process dies after persistence but before, during, or after an effect, {@link #restore} starts a
 * new exact epoch and drains the only route that could still have an app owner. It never resumes
 * directly at STARTING or ACTIVE.</p>
 */
public final class BleRoleSwitchCoordinator {
    public static final int WIRE_TOKEN_BYTES = 16;

    /** Adapter assertion for the caller's one serialized executor/queue. */
    public interface SerializedExecutionGuard {
        void assertOnSerializedExecutor();
    }

    /** All side effects are implemented by the platform adapter, never by the reducer. */
    public interface EffectsPort {
        void persistSnapshot(String encodedSnapshot);

        void freezeSourceIngress(Owner source);

        void armStopTimeout(Owner source, long deadlineMillis);

        /**
         * Executes deterministic client-first teardown. If this endpoint owns the current GATT
         * client it disconnects now. If it owns the server it keeps the server alive and waits for
         * the exact current client to disconnect; only then may it report local terminal/owner0.
         */
        void stopLocalSource(Owner source);

        /**
         * Begins one exact asynchronous C/A attempt. Completion must be posted (never re-entered
         * synchronously) through {@link #onControlTransmitResult(ControlTransmit,
         * ControlTransmitResult, long)} with this exact descriptor.
         */
        void transmitControl(ControlTransmit transmit);

        /**
         * Arms a bounded, non-busy retry. When due, it posts
         * {@link #onControlTransmitRetry(ControlTransmit, long)} with this exact descriptor; it
         * does not transmit the frame itself and never extends the descriptor's stop deadline.
         */
        void scheduleControlTransmitRetry(ControlTransmit transmit);

        /** Cancels a previously scheduled exact retry after stronger wire evidence arrives. */
        void cancelControlTransmitRetry(ControlTransmit transmit);

        void verifyLocalOwners(Owner source);

        void cancelStopTimeout(Owner source);

        void armDrainDeadline(Owner source, long deadlineMillis);

        void cancelDrainDeadline(Owner source);

        void quiescentReached(Owner target);

        void startTarget(Owner target);

        void targetActive(Owner target);

        void failClosed(Owner owner, Failure failure);

        void closeAll(Owner owner);
    }

    /** Exact callback owner. The process nonce invalidates callbacks retained across hot update. */
    public static final class Owner {
        private final long processNonce;
        private final Sequence epoch;
        private final Sequence generation;
        private final Role role;

        public Owner(long processNonce, Sequence epoch, Sequence generation, Role role) {
            if (processNonce == 0L) {
                throw new IllegalArgumentException("processNonce must be non-zero");
            }
            this.processNonce = processNonce;
            this.epoch = Objects.requireNonNull(epoch, "epoch");
            this.generation = Objects.requireNonNull(generation, "generation");
            this.role = Objects.requireNonNull(role, "role");
        }

        public long processNonce() {
            return processNonce;
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

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Owner that)) {
                return false;
            }
            return processNonce == that.processNonce
                    && epoch.equals(that.epoch)
                    && generation.equals(that.generation)
                    && role == that.role;
        }

        @Override
        public int hashCode() {
            int result = Long.hashCode(processNonce);
            result = 31 * result + epoch.hashCode();
            result = 31 * result + generation.hashCode();
            result = 31 * result + role.hashCode();
            return result;
        }

        @Override
        public String toString() {
            return Long.toUnsignedString(processNonce, 16) + ':' + epoch + ':' + generation
                    + ':' + role;
        }
    }

    /**
     * Immutable identity of one C/A send attempt. Owner, frame, desired role, 128-bit token, and
     * attempt must all be echoed; accepting a callback for a partial identity is forbidden.
     */
    public static final class ControlTransmit {
        private final Owner owner;
        private final ControlFrame frame;
        private final Role desiredRole;
        private final WireSwitchToken wireToken;
        private final Sequence attempt;
        private final long stopDeadlineMillis;

        private ControlTransmit(
                Owner owner,
                ControlFrame frame,
                Role desiredRole,
                WireSwitchToken wireToken,
                Sequence attempt,
                long stopDeadlineMillis
        ) {
            this.owner = Objects.requireNonNull(owner, "owner");
            this.frame = Objects.requireNonNull(frame, "frame");
            this.desiredRole = Objects.requireNonNull(desiredRole, "desiredRole");
            this.wireToken = Objects.requireNonNull(wireToken, "wireToken");
            if (Objects.requireNonNull(attempt, "attempt").equals(Sequence.zero())) {
                throw new IllegalArgumentException("control attempt must be non-zero");
            }
            this.attempt = attempt;
            this.stopDeadlineMillis = stopDeadlineMillis;
        }

        public Owner owner() {
            return owner;
        }

        public ControlFrame frame() {
            return frame;
        }

        public Role desiredRole() {
            return desiredRole;
        }

        public WireSwitchToken wireToken() {
            return wireToken;
        }

        public Sequence attempt() {
            return attempt;
        }

        /** Original inclusive stop deadline; retries never replace or extend it. */
        public long stopDeadlineMillis() {
            return stopDeadlineMillis;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof ControlTransmit that)) {
                return false;
            }
            return stopDeadlineMillis == that.stopDeadlineMillis
                    && owner.equals(that.owner)
                    && frame == that.frame
                    && desiredRole == that.desiredRole
                    && wireToken.equals(that.wireToken)
                    && attempt.equals(that.attempt);
        }

        @Override
        public int hashCode() {
            return Objects.hash(
                    owner,
                    frame,
                    desiredRole,
                    wireToken,
                    attempt,
                    stopDeadlineMillis
            );
        }

        @Override
        public String toString() {
            return "ControlTransmit{" + owner + ", frame=" + frame
                    + ", desiredRole=" + desiredRole + ", attempt=" + attempt
                    + ", stopDeadlineMillis=" + stopDeadlineMillis + '}';
        }
    }

    /** Non-zero 128-bit identity echoed by the peer's C/A control exchange. */
    public static final class WireSwitchToken {
        private final byte[] bytes;

        public WireSwitchToken(byte[] bytes) {
            if (bytes == null || bytes.length != WIRE_TOKEN_BYTES || allZero(bytes)) {
                throw new IllegalArgumentException("wire token must be a non-zero 128-bit value");
            }
            this.bytes = bytes.clone();
        }

        public byte[] bytes() {
            return bytes.clone();
        }

        public boolean matches(byte[] candidate) {
            return candidate != null
                    && candidate.length == WIRE_TOKEN_BYTES
                    && MessageDigest.isEqual(bytes, candidate);
        }

        public String hex() {
            char[] encoded = new char[bytes.length * 2];
            char[] digits = "0123456789abcdef".toCharArray();
            for (int index = 0; index < bytes.length; index++) {
                int value = bytes[index] & 0xff;
                encoded[index * 2] = digits[value >>> 4];
                encoded[index * 2 + 1] = digits[value & 0xf];
            }
            return new String(encoded);
        }

        public static WireSwitchToken fromHex(String hex) {
            Objects.requireNonNull(hex, "hex");
            if (hex.length() != WIRE_TOKEN_BYTES * 2) {
                throw new IllegalArgumentException("wire token hex must contain 32 digits");
            }
            byte[] bytes = new byte[WIRE_TOKEN_BYTES];
            for (int index = 0; index < bytes.length; index++) {
                int high = Character.digit(hex.charAt(index * 2), 16);
                int low = Character.digit(hex.charAt(index * 2 + 1), 16);
                if (high < 0 || low < 0) {
                    throw new IllegalArgumentException("invalid wire token hex");
                }
                bytes[index] = (byte) ((high << 4) | low);
            }
            return new WireSwitchToken(bytes);
        }

        @Override
        public boolean equals(Object other) {
            return this == other
                    || (other instanceof WireSwitchToken that
                    && MessageDigest.isEqual(bytes, that.bytes));
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(bytes);
        }

        @Override
        public String toString() {
            return "WireSwitchToken[128-bit]";
        }
    }

    private final BleRoleSwitchReducer reducer;
    private final BleRoleSwitchSnapshotCodec codec;
    private final EffectsPort port;
    private final SerializedExecutionGuard guard;
    private final long processNonce;

    private State state;
    private WireSwitchToken wireToken;
    private BleRoleSwitchOrigin origin;
    private boolean applyingEffects;

    private BleRoleSwitchCoordinator(
            long processNonce,
            State state,
            WireSwitchToken wireToken,
            BleRoleSwitchOrigin origin,
            BleRoleSwitchReducer reducer,
            BleRoleSwitchSnapshotCodec codec,
            EffectsPort port,
            SerializedExecutionGuard guard
    ) {
        if (processNonce == 0L) {
            throw new IllegalArgumentException("processNonce must be non-zero");
        }
        this.processNonce = processNonce;
        this.state = Objects.requireNonNull(state, "state");
        this.wireToken = wireToken;
        this.origin = origin;
        this.reducer = Objects.requireNonNull(reducer, "reducer");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.port = Objects.requireNonNull(port, "port");
        this.guard = Objects.requireNonNull(guard, "guard");
    }

    /** Creates and immediately persists a fully active starting point. */
    public static BleRoleSwitchCoordinator active(
            long processNonce,
            Role activeRole,
            Sequence lastEpoch,
            Sequence activeGeneration,
            EffectsPort port,
            SerializedExecutionGuard guard
    ) {
        BleRoleSwitchReducer reducer = new BleRoleSwitchReducer();
        BleRoleSwitchSnapshotCodec codec = new BleRoleSwitchSnapshotCodec();
        State state = State.active(activeRole, lastEpoch, activeGeneration);
        BleRoleSwitchCoordinator coordinator = new BleRoleSwitchCoordinator(
                processNonce,
                state,
                null,
                null,
                reducer,
                codec,
                port,
                guard
        );
        coordinator.assertSerialized();
        coordinator.port.persistSnapshot(coordinator.encodedSnapshot());
        return coordinator;
    }

    /**
     * Restores either snapshot kind by allocating a fresh epoch and re-running the full drain.
     * A fresh process nonce and fresh caller-supplied wire token are mandatory.
     */
    public static BleRoleSwitchCoordinator restore(
            long newProcessNonce,
            String encodedSnapshot,
            WireSwitchToken newWireToken,
            long nowMillis,
            long stopTimeoutMillis,
            long drainDurationMillis,
            EffectsPort port,
            SerializedExecutionGuard guard
    ) {
        BleRoleSwitchReducer reducer = new BleRoleSwitchReducer();
        BleRoleSwitchSnapshotCodec codec = new BleRoleSwitchSnapshotCodec();
        Snapshot persisted = codec.decode(encodedSnapshot);
        if (newProcessNonce == 0L || newProcessNonce == persisted.processNonce()) {
            throw new IllegalArgumentException("restoration requires a fresh process nonce");
        }
        Objects.requireNonNull(newWireToken, "newWireToken");

        /*
         * CLOSED is a durable shutdown tombstone, not an interrupted role transition.  A fresh
         * process repeats closeAll so platform-restored owners are drained, but it must never
         * reinterpret shutdown as permission to start either target route.
         */
        if (persisted.kind() == Kind.DRAIN && persisted.phase() == Phase.CLOSED) {
            Role closedRole = persisted.sourceRole();
            Sequence closedGeneration = maximumGeneration(persisted).next();
            State activePlaceholder = State.active(
                    closedRole, persisted.epoch(), closedGeneration);
            BleRoleSwitchCoordinator closed = new BleRoleSwitchCoordinator(
                    newProcessNonce,
                    activePlaceholder,
                    null,
                    null,
                    reducer,
                    codec,
                    port,
                    guard
            );
            closed.assertSerialized();
            closed.commit(
                    reducer.close(activePlaceholder),
                    newWireToken,
                    BleRoleSwitchOrigin.LOCAL_ONLY_RESTORE
            );
            return closed;
        }

        Role drainRole;
        Sequence drainGeneration;
        if (persisted.kind() == Kind.ACTIVE) {
            drainRole = persisted.activeRole();
            drainGeneration = persisted.activeGeneration();
        } else if (persisted.phase() == Phase.STARTING
                || (persisted.phase() == Phase.FAILED
                && persisted.failure() == Failure.TARGET_START_FAILED)) {
            drainRole = persisted.targetRole();
            drainGeneration = persisted.targetGeneration();
        } else {
            drainRole = persisted.sourceRole();
            drainGeneration = persisted.sourceGeneration();
        }

        Sequence nextEpoch = persisted.epoch().next();
        Sequence nextGeneration = maximumGeneration(persisted).next();
        boolean targetOwnerMayExist = persisted.kind() == Kind.DRAIN
                && (persisted.phase() == Phase.STARTING
                || (persisted.phase() == Phase.FAILED
                && persisted.failure() == Failure.TARGET_START_FAILED));
        /* A partially started target is a same-role local owner. Replaying the persisted LOCAL or
         * REMOTE origin here would send a nonsensical same-role C/A on the target transport. */
        BleRoleSwitchOrigin restoreOrigin = persisted.kind() == Kind.ACTIVE || targetOwnerMayExist
                ? BleRoleSwitchOrigin.LOCAL_ONLY_RESTORE
                : persisted.origin();
        WireSwitchToken restoreToken = persisted.kind() == Kind.ACTIVE || targetOwnerMayExist
                ? newWireToken
                : persisted.wireToken();
        State placeholder = State.active(drainRole, persisted.epoch(), drainGeneration);
        BleRoleSwitchCoordinator coordinator = new BleRoleSwitchCoordinator(
                newProcessNonce,
                placeholder,
                null,
                null,
                reducer,
                codec,
                port,
                guard
        );
        coordinator.assertSerialized();
        Reduction restored;
        if (restoreOrigin == BleRoleSwitchOrigin.REMOTE) {
            restored = reducer.restoreDrainFromRemoteIntent(
                        drainRole,
                        persisted.desiredRole(),
                        nextEpoch,
                        drainGeneration,
                        nextGeneration,
                        nowMillis,
                        stopTimeoutMillis,
                        drainDurationMillis
                );
        } else if (restoreOrigin == BleRoleSwitchOrigin.LOCAL_ONLY_RESTORE) {
            restored = reducer.restoreDrainLocalOnly(
                    drainRole,
                    nextEpoch,
                    drainGeneration,
                    nextGeneration,
                    nowMillis,
                    stopTimeoutMillis,
                    drainDurationMillis
            );
        } else {
            restored = reducer.restoreDrain(
                        drainRole,
                        persisted.desiredRole(),
                        nextEpoch,
                        drainGeneration,
                        nextGeneration,
                        nowMillis,
                        stopTimeoutMillis,
                        drainDurationMillis
                );
        }
        coordinator.commit(restored, restoreToken, restoreOrigin);
        return coordinator;
    }

    public State state() {
        assertSerialized();
        return state;
    }

    public Owner sourceOwner() {
        assertSerialized();
        return state.sourceRole() == null ? null : new Owner(
                processNonce,
                state.epoch(),
                state.sourceGeneration(),
                state.sourceRole()
        );
    }

    public Owner targetOwner() {
        assertSerialized();
        Role role = state.phase() == Phase.ACTIVE ? state.activeRole() : state.targetRole();
        Sequence generation = state.phase() == Phase.ACTIVE
                ? state.activeGeneration()
                : state.targetGeneration();
        return role == null ? null : new Owner(processNonce, state.epoch(), generation, role);
    }

    public String encodedSnapshot() {
        assertSerialized();
        return codec.encode(snapshot(state, wireToken, origin));
    }

    public Outcome requestSwitch(
            Role desiredRole,
            long nowMillis,
            long stopTimeoutMillis,
            long drainDurationMillis,
            WireSwitchToken newWireToken
    ) {
        assertSerialized();
        Objects.requireNonNull(newWireToken, "newWireToken");
        Reduction reduction = reducer.requestSwitch(
                state,
                desiredRole,
                nowMillis,
                stopTimeoutMillis,
                drainDurationMillis
        );
        return commit(
                reduction,
                reduction.outcome() == Outcome.APPLIED ? newWireToken : wireToken,
                reduction.outcome() == Outcome.APPLIED ? BleRoleSwitchOrigin.LOCAL : origin
        );
    }

    /**
     * Runs a full same-topology owner replacement after radio loss, terminal retry exhaustion,
     * or another attributable route failure.  The fresh token is persisted as transaction
     * identity only; no C/A frame is emitted because the peer keeps the same role.
     */
    public Outcome requestSameRoleRestart(
            long nowMillis,
            long stopTimeoutMillis,
            long drainDurationMillis,
            WireSwitchToken newWireToken
    ) {
        assertSerialized();
        Objects.requireNonNull(newWireToken, "newWireToken");
        Reduction reduction = reducer.requestSameRoleRestart(
                state,
                nowMillis,
                stopTimeoutMillis,
                drainDurationMillis
        );
        return commit(
                reduction,
                reduction.outcome() == Outcome.APPLIED ? newWireToken : wireToken,
                reduction.outcome() == Outcome.APPLIED
                        ? BleRoleSwitchOrigin.LOCAL_ONLY_RESTORE : origin
        );
    }

    /**
     * Accepts an exact peer C frame. Its target and token are persisted before ingress freezes;
     * after freeze the coordinator emits A with the same values and never emits a second C.
     */
    public Outcome requestSwitchFromRemote(
            Owner receivingOwner,
            Role desiredRole,
            WireSwitchToken receivedWireToken,
            long nowMillis,
            long stopTimeoutMillis,
            long drainDurationMillis
    ) {
        assertSerialized();
        Objects.requireNonNull(receivedWireToken, "receivedWireToken");
        Owner expectedOwner = state.phase() == Phase.ACTIVE ? targetOwner() : sourceOwner();
        if (receivingOwner == null || !receivingOwner.equals(expectedOwner)) {
            return Outcome.STALE_CALLBACK;
        }
        if (state.phase() == Phase.ACTIVE && state.activeRole() == desiredRole) {
            return Outcome.REJECTED_CONFLICT;
        }
        if (state.phase() != Phase.ACTIVE) {
            if (origin == BleRoleSwitchOrigin.REMOTE
                    && state.targetRole() == desiredRole
                    && receivedWireToken.equals(wireToken)) {
                return commit(reducer.onDuplicateRemoteIntent(
                        state,
                        receivingOwner.epoch,
                        receivingOwner.generation,
                        receivingOwner.role,
                        nowMillis
                ), wireToken);
            }
            return Outcome.REJECTED_CONFLICT;
        }
        Reduction reduction = reducer.requestSwitchFromRemoteIntent(
                state,
                desiredRole,
                nowMillis,
                stopTimeoutMillis,
                drainDurationMillis
        );
        return commit(reduction, receivedWireToken, BleRoleSwitchOrigin.REMOTE);
    }

    public Outcome onIngressFrozen(Owner source, long nowMillis) {
        assertSerialized();
        if (!currentProcess(source)) {
            return Outcome.STALE_CALLBACK;
        }
        return commit(reducer.onIngressFrozen(
                state,
                source.epoch,
                source.generation,
                source.role,
                nowMillis
        ), wireToken);
    }

    /** Records a typed adapter/restoration failure instead of waiting for an unrelated timeout. */
    public Outcome onIngressFreezeFailed(Owner source) {
        assertSerialized();
        if (!currentProcess(source)) {
            return Outcome.STALE_CALLBACK;
        }
        return commit(reducer.onIngressFreezeFailed(
                state,
                source.epoch,
                source.generation,
                source.role
        ), wireToken);
    }

    /** Commits freeze plus typed no-control-peer evidence before any C/A effect can be emitted. */
    public Outcome onIngressFrozenWithoutRemoteOwner(Owner source, long nowMillis) {
        assertSerialized();
        if (!currentProcess(source)) {
            return Outcome.STALE_CALLBACK;
        }
        return commit(reducer.onIngressFrozenWithoutRemoteOwner(
                state,
                source.epoch,
                source.generation,
                source.role,
                nowMillis
        ), wireToken);
    }

    /** Completes the exact asynchronous send attempt supplied to {@link EffectsPort}. */
    public Outcome onControlTransmitResult(
            ControlTransmit transmit,
            ControlTransmitResult result,
            long nowMillis
    ) {
        assertSerialized();
        Objects.requireNonNull(result, "result");
        if (!matchesCurrentTransmit(transmit)) {
            return Outcome.STALE_CALLBACK;
        }
        return commit(reducer.onControlTransmitResult(
                state,
                transmit.owner.epoch,
                transmit.owner.generation,
                transmit.owner.role,
                transmit.frame,
                transmit.attempt,
                result,
                nowMillis
        ), wireToken);
    }

    /** Fires the exact retry descriptor previously emitted by the reducer. */
    public Outcome onControlTransmitRetry(ControlTransmit transmit, long nowMillis) {
        assertSerialized();
        if (!matchesCurrentTransmit(transmit)) {
            return Outcome.STALE_CALLBACK;
        }
        return commit(reducer.onControlTransmitRetry(
                state,
                transmit.owner.epoch,
                transmit.owner.generation,
                transmit.owner.role,
                transmit.frame,
                transmit.attempt,
                nowMillis
        ), wireToken);
    }

    public Outcome onLocalTerminal(Owner source, long nowMillis) {
        assertSerialized();
        if (!currentProcess(source)) {
            return Outcome.STALE_CALLBACK;
        }
        return commit(reducer.onLocalTerminal(
                state,
                source.epoch,
                source.generation,
                source.role,
                nowMillis
        ), wireToken);
    }

    public Outcome onLocalOwnerCount(Owner source, int ownerCount, long nowMillis) {
        assertSerialized();
        if (!currentProcess(source)) {
            return Outcome.STALE_CALLBACK;
        }
        return commit(reducer.onLocalOwnerCount(
                state,
                source.epoch,
                source.generation,
                source.role,
                ownerCount,
                nowMillis
        ), wireToken);
    }

    /** A proves peer persistence+freeze for this token; it does not prove physical owner closure. */
    public Outcome onRemoteCloseAck(
            Owner source,
            Role acknowledgedDesiredRole,
            byte[] echoedWireToken,
            long nowMillis
    ) {
        assertSerialized();
        if (!currentProcess(source)
                || wireToken == null
                || !wireToken.matches(echoedWireToken)) {
            return Outcome.STALE_CALLBACK;
        }
        Role claimedStoppedRole = opposite(Objects.requireNonNull(
                acknowledgedDesiredRole,
                "acknowledgedDesiredRole"
        ));
        return commit(reducer.onRemoteClosedEvidence(
                state,
                source.epoch,
                source.generation,
                claimedStoppedRole,
                RemoteCloseEvidence.CONFIRMED_ACK,
                nowMillis
        ), wireToken);
    }

    public Outcome onRemoteClosedWithoutAck(
            Owner source,
            RemoteCloseEvidence evidence,
            long nowMillis
    ) {
        assertSerialized();
        Objects.requireNonNull(evidence, "evidence");
        if (evidence == RemoteCloseEvidence.CONFIRMED_ACK) {
            throw new IllegalArgumentException("CONFIRMED_ACK requires exact wire token");
        }
        if (!currentProcess(source)) {
            return Outcome.STALE_CALLBACK;
        }
        return commit(reducer.onRemoteClosedEvidence(
                state,
                source.epoch,
                source.generation,
                source.role,
                evidence,
                nowMillis
        ), wireToken);
    }

    public Outcome onRadioOrPowerLoss(Owner source, long nowMillis) {
        assertSerialized();
        if (!currentProcess(source)) {
            return Outcome.STALE_CALLBACK;
        }
        return commit(reducer.onRadioOrPowerLoss(
                state,
                source.epoch,
                source.generation,
                source.role,
                nowMillis
        ), wireToken);
    }

    public Outcome onStopTimeout(Owner source, long nowMillis) {
        assertSerialized();
        if (!currentProcess(source)) {
            return Outcome.STALE_CALLBACK;
        }
        return commit(reducer.onStopTimeout(
                state,
                source.epoch,
                source.generation,
                source.role,
                nowMillis
        ), wireToken);
    }

    public Outcome onDrainDeadline(Owner source, long nowMillis) {
        assertSerialized();
        if (!currentProcess(source)) {
            return Outcome.STALE_CALLBACK;
        }
        return commit(reducer.onDrainDeadline(
                state,
                source.epoch,
                source.generation,
                source.role,
                nowMillis
        ), wireToken);
    }

    public Outcome beginTargetStart(Owner target) {
        assertSerialized();
        if (!currentProcess(target)) {
            return Outcome.STALE_CALLBACK;
        }
        return commit(reducer.beginTargetStart(
                state,
                target.epoch,
                target.generation,
                target.role
        ), wireToken);
    }

    public Outcome onTargetActive(Owner target) {
        assertSerialized();
        if (!currentProcess(target)) {
            return Outcome.STALE_CALLBACK;
        }
        return commit(reducer.onTargetActive(
                state,
                target.epoch,
                target.generation,
                target.role
        ), null);
    }

    public Outcome onTargetStartFailed(Owner target) {
        assertSerialized();
        if (!currentProcess(target)) {
            return Outcome.STALE_CALLBACK;
        }
        return commit(reducer.onTargetStartFailed(
                state,
                target.epoch,
                target.generation,
                target.role
        ), wireToken);
    }

    public Outcome close(WireSwitchToken closeToken) {
        assertSerialized();
        WireSwitchToken token = wireToken != null
                ? wireToken
                : Objects.requireNonNull(closeToken, "closeToken");
        return commit(
                reducer.close(state),
                token,
                origin == null ? BleRoleSwitchOrigin.LOCAL : origin
        );
    }

    /** Stable production-independent mapping, verified against v2.IphoneBleMode in tests. */
    public static int modeWireId(Role role) {
        return switch (Objects.requireNonNull(role, "role")) {
            case HELPER_PERIPHERAL_ANDROID_CENTRAL -> 1;
            case HELPER_CENTRAL_ANDROID_PERIPHERAL -> 2;
        };
    }

    public static String modeStableKey(Role role) {
        return switch (Objects.requireNonNull(role, "role")) {
            case HELPER_PERIPHERAL_ANDROID_CENTRAL -> "android_central";
            case HELPER_CENTRAL_ANDROID_PERIPHERAL -> "android_peripheral";
        };
    }

    public static Role opposite(Role role) {
        return switch (Objects.requireNonNull(role, "role")) {
            case HELPER_PERIPHERAL_ANDROID_CENTRAL ->
                    Role.HELPER_CENTRAL_ANDROID_PERIPHERAL;
            case HELPER_CENTRAL_ANDROID_PERIPHERAL ->
                    Role.HELPER_PERIPHERAL_ANDROID_CENTRAL;
        };
    }

    private Outcome commit(Reduction reduction, WireSwitchToken nextToken) {
        return commit(reduction, nextToken, origin);
    }

    private Outcome commit(
            Reduction reduction,
            WireSwitchToken nextToken,
            BleRoleSwitchOrigin nextOrigin
    ) {
        if (reduction.state() == state) {
            return reduction.outcome();
        }
        BleRoleSwitchOrigin committedOrigin = reduction.state().phase() == Phase.ACTIVE
                ? null
                : Objects.requireNonNull(nextOrigin, "drain origin");
        Snapshot nextSnapshot = snapshot(reduction.state(), nextToken, committedOrigin);
        String encoded = codec.encode(nextSnapshot);

        applyingEffects = true;
        try {
            // Write-ahead rule: state/effects are not committed if persistence fails.
            port.persistSnapshot(encoded);
            state = reduction.state();
            wireToken = state.phase() == Phase.ACTIVE ? null : nextToken;
            origin = committedOrigin;

            for (Effect effect : reduction.effects()) {
                dispatch(effect);
            }
        } finally {
            applyingEffects = false;
        }
        return reduction.outcome();
    }

    private void dispatch(Effect effect) {
        Owner owner = new Owner(
                processNonce,
                effect.epoch(),
                effect.generation(),
                effect.role()
        );
        switch (effect.type()) {
            case FREEZE_SOURCE_INGRESS -> port.freezeSourceIngress(owner);
            case ARM_STOP_TIMEOUT -> port.armStopTimeout(owner, effect.deadlineMillis());
            case STOP_LOCAL_SOURCE -> port.stopLocalSource(owner);
            case REQUEST_REMOTE_STOP, ACKNOWLEDGE_REMOTE_STOP ->
                    port.transmitControl(controlTransmit(owner, effect));
            case SCHEDULE_CONTROL_RETRY ->
                    port.scheduleControlTransmitRetry(controlTransmit(owner, effect));
            case CANCEL_CONTROL_RETRY ->
                    port.cancelControlTransmitRetry(controlTransmit(owner, effect));
            case VERIFY_LOCAL_OWNERS -> port.verifyLocalOwners(owner);
            case CANCEL_STOP_TIMEOUT -> port.cancelStopTimeout(owner);
            case ARM_DRAIN_DEADLINE -> port.armDrainDeadline(owner, effect.deadlineMillis());
            case CANCEL_DRAIN_DEADLINE -> port.cancelDrainDeadline(owner);
            case QUIESCENT_REACHED -> port.quiescentReached(owner);
            case START_TARGET -> port.startTarget(owner);
            case TARGET_ACTIVE -> port.targetActive(owner);
            case FAIL_CLOSED -> port.failClosed(owner, state.failure());
            case CLOSE_ALL -> port.closeAll(owner);
            default -> throw new AssertionError(effect.type());
        }
    }

    private Snapshot snapshot(
            State value,
            WireSwitchToken token,
            BleRoleSwitchOrigin snapshotOrigin
    ) {
        if (value.phase() == Phase.ACTIVE) {
            return Snapshot.active(
                    processNonce,
                    value.epoch(),
                    value.activeRole(),
                    value.activeGeneration()
            );
        }
        return Snapshot.drain(
                processNonce,
                Objects.requireNonNull(snapshotOrigin, "snapshotOrigin"),
                value.phase(),
                value.epoch(),
                value.desiredRole(),
                value.sourceRole(),
                value.sourceGeneration(),
                value.targetRole(),
                value.targetGeneration(),
                Objects.requireNonNull(token, "drain snapshot requires wire token"),
                value.failure()
        );
    }

    private WireSwitchToken requireWireToken() {
        return Objects.requireNonNull(wireToken, "switch wire token");
    }

    private ControlTransmit controlTransmit(Owner owner, Effect effect) {
        return new ControlTransmit(
                owner,
                Objects.requireNonNull(effect.controlFrame(), "control frame"),
                state.desiredRole(),
                requireWireToken(),
                effect.controlAttempt(),
                state.stopDeadlineMillis()
        );
    }

    private boolean matchesCurrentTransmit(ControlTransmit transmit) {
        return transmit != null
                && currentProcess(transmit.owner)
                && wireToken != null
                && wireToken.equals(transmit.wireToken)
                && state.sourceRole() == transmit.owner.role
                && state.sourceGeneration().equals(transmit.owner.generation)
                && state.epoch().equals(transmit.owner.epoch)
                && state.desiredRole() == transmit.desiredRole
                && state.controlFrame() == transmit.frame
                && state.controlAttempt().equals(transmit.attempt)
                && state.stopDeadlineMillis() == transmit.stopDeadlineMillis;
    }

    private boolean currentProcess(Owner owner) {
        return owner != null && owner.processNonce == processNonce;
    }

    private void assertSerialized() {
        guard.assertOnSerializedExecutor();
        if (applyingEffects) {
            throw new IllegalStateException("role-switch effects must not re-enter coordinator");
        }
    }

    private static Sequence maximumGeneration(Snapshot snapshot) {
        Sequence maximum = Sequence.zero();
        if (snapshot.activeGeneration() != null
                && snapshot.activeGeneration().compareTo(maximum) > 0) {
            maximum = snapshot.activeGeneration();
        }
        if (snapshot.sourceGeneration() != null
                && snapshot.sourceGeneration().compareTo(maximum) > 0) {
            maximum = snapshot.sourceGeneration();
        }
        if (snapshot.targetGeneration() != null
                && snapshot.targetGeneration().compareTo(maximum) > 0) {
            maximum = snapshot.targetGeneration();
        }
        return maximum;
    }

    private static boolean allZero(byte[] bytes) {
        int combined = 0;
        for (byte value : bytes) {
            combined |= value & 0xff;
        }
        return combined == 0;
    }
}
