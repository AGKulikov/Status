/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package dezz.status.widget.phone.transport.switching;

import static dezz.status.widget.phone.transport.switching.BleRoleSwitchReducer.EffectType.ARM_DRAIN_DEADLINE;
import static dezz.status.widget.phone.transport.switching.BleRoleSwitchReducer.EffectType.ACKNOWLEDGE_REMOTE_STOP;
import static dezz.status.widget.phone.transport.switching.BleRoleSwitchReducer.EffectType.FAIL_CLOSED;
import static dezz.status.widget.phone.transport.switching.BleRoleSwitchReducer.EffectType.START_TARGET;
import static dezz.status.widget.phone.transport.switching.BleRoleSwitchReducer.EffectType.VERIFY_LOCAL_OWNERS;
import static dezz.status.widget.phone.transport.switching.BleRoleSwitchReducer.Failure.CONTRADICTORY_REMOTE_EVIDENCE;
import static dezz.status.widget.phone.transport.switching.BleRoleSwitchReducer.Failure.CONTRADICTORY_LOCAL_OWNER_EVIDENCE;
import static dezz.status.widget.phone.transport.switching.BleRoleSwitchReducer.Failure.IMPOSSIBLE_LOCAL_OWNER_COUNT;
import static dezz.status.widget.phone.transport.switching.BleRoleSwitchReducer.Failure.STOP_TIMEOUT;
import static dezz.status.widget.phone.transport.switching.BleRoleSwitchReducer.Outcome.APPLIED;
import static dezz.status.widget.phone.transport.switching.BleRoleSwitchReducer.Outcome.COALESCED;
import static dezz.status.widget.phone.transport.switching.BleRoleSwitchReducer.Outcome.NOT_DUE;
import static dezz.status.widget.phone.transport.switching.BleRoleSwitchReducer.Outcome.REJECTED_CONFLICT;
import static dezz.status.widget.phone.transport.switching.BleRoleSwitchReducer.Outcome.REJECTED_TERMINAL;
import static dezz.status.widget.phone.transport.switching.BleRoleSwitchReducer.Outcome.STALE_CALLBACK;
import static dezz.status.widget.phone.transport.switching.BleRoleSwitchReducer.Phase.ACTIVE;
import static dezz.status.widget.phone.transport.switching.BleRoleSwitchReducer.Phase.DRAINING;
import static dezz.status.widget.phone.transport.switching.BleRoleSwitchReducer.Phase.FAILED;
import static dezz.status.widget.phone.transport.switching.BleRoleSwitchReducer.Phase.FREEZING;
import static dezz.status.widget.phone.transport.switching.BleRoleSwitchReducer.Phase.QUIESCENT;
import static dezz.status.widget.phone.transport.switching.BleRoleSwitchReducer.Phase.STARTING;
import static dezz.status.widget.phone.transport.switching.BleRoleSwitchReducer.Phase.WAITING_CONTROL_HANDSHAKE;
import static dezz.status.widget.phone.transport.switching.BleRoleSwitchReducer.Phase.WAITING_LOCAL_TERMINAL;
import static dezz.status.widget.phone.transport.switching.BleRoleSwitchReducer.Phase.WAITING_REMOTE_ACK;
import static dezz.status.widget.phone.transport.switching.BleRoleSwitchReducer.RemoteCloseEvidence.CONFIRMED_ACK;
import static dezz.status.widget.phone.transport.switching.BleRoleSwitchReducer.RemoteCloseEvidence.NO_REMOTE_OWNER;
import static dezz.status.widget.phone.transport.switching.BleRoleSwitchReducer.Role.HELPER_CENTRAL_ANDROID_PERIPHERAL;
import static dezz.status.widget.phone.transport.switching.BleRoleSwitchReducer.Role.HELPER_PERIPHERAL_ANDROID_CENTRAL;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import dezz.status.widget.phone.transport.switching.BleRoleSwitchReducer.Effect;
import dezz.status.widget.phone.transport.switching.BleRoleSwitchReducer.ControlFrame;
import dezz.status.widget.phone.transport.switching.BleRoleSwitchReducer.ControlTransmitResult;
import dezz.status.widget.phone.transport.switching.BleRoleSwitchReducer.EffectType;
import dezz.status.widget.phone.transport.switching.BleRoleSwitchReducer.Reduction;
import dezz.status.widget.phone.transport.switching.BleRoleSwitchReducer.RemoteCloseEvidence;
import dezz.status.widget.phone.transport.switching.BleRoleSwitchReducer.Role;
import dezz.status.widget.phone.transport.switching.BleRoleSwitchReducer.Sequence;
import dezz.status.widget.phone.transport.switching.BleRoleSwitchReducer.State;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public final class BleRoleSwitchReducerTest {
    private static final long REQUEST_AT = 1_000L;
    private static final long STOP_AT = 1_100L;
    private static final long DRAIN_MILLIS = 20L;

    private final BleRoleSwitchReducer reducer = new BleRoleSwitchReducer();

    @Test
    public void allTwentyFourEvidenceOrdersRequireEveryGate() {
        List<int[]> permutations = new ArrayList<>();
        permute(new int[]{0, 1, 2, 3}, 0, permutations);
        assertEquals(24, permutations.size());

        for (int[] order : permutations) {
            State state = requested().state();
            long now = REQUEST_AT + 1L;
            for (int index = 0; index < order.length; index++) {
                Reduction reduction = applyEvidence(state, order[index], now++);
                assertEquals("order=" + Arrays.toString(order), APPLIED, reduction.outcome());
                assertFalse(hasEffect(reduction, START_TARGET));
                state = reduction.state();
                if (index < order.length - 1) {
                    assertFalse("drained before all gates: " + Arrays.toString(order),
                            state.phase() == DRAINING);
                }
            }
            assertEquals("order=" + Arrays.toString(order), DRAINING, state.phase());
            assertTrue(state.ingressFrozen());
            assertTrue(state.localTerminal());
            assertTrue(state.localOwnersZero());
            assertEquals(CONFIRMED_ACK, state.remoteCloseEvidence());
        }
    }

    @Test
    public void targetStartIsImpossibleBeforeDrainDeadlineAndExplicitQuiescentConsume() {
        State state = completeEvidence(requested().state(), CONFIRMED_ACK, REQUEST_AT + 1L);
        assertEquals(DRAINING, state.phase());

        Reduction early = reducer.onDrainDeadline(
                state,
                state.epoch(),
                state.sourceGeneration(),
                state.sourceRole(),
                state.drainDeadlineMillis() - 1L
        );
        assertSame(state, early.state());
        assertEquals(NOT_DUE, early.outcome());
        assertFalse(hasEffect(early, START_TARGET));

        Reduction elapsed = reducer.onDrainDeadline(
                state,
                state.epoch(),
                state.sourceGeneration(),
                state.sourceRole(),
                state.drainDeadlineMillis()
        );
        assertEquals(QUIESCENT, elapsed.state().phase());
        assertFalse(hasEffect(elapsed, START_TARGET));

        Reduction start = reducer.beginTargetStart(
                elapsed.state(),
                elapsed.state().epoch(),
                elapsed.state().targetGeneration(),
                elapsed.state().targetRole()
        );
        assertEquals(STARTING, start.state().phase());
        assertTrue(hasEffect(start, START_TARGET));

        Reduction active = reducer.onTargetActive(
                start.state(),
                start.state().epoch(),
                start.state().targetGeneration(),
                start.state().targetRole()
        );
        assertEquals(ACTIVE, active.state().phase());
        assertEquals(HELPER_CENTRAL_ANDROID_PERIPHERAL, active.state().activeRole());
        assertEquals(active.state().desiredRole(), active.state().activeRole());
        assertNull(active.state().sourceRole());
    }

    @Test
    public void disconnectedDoesNotImplyLocalOwnerRegistryIsEmpty() {
        State state = requested().state();
        state = freeze(state, REQUEST_AT + 1L).state();
        Reduction terminal = localTerminal(state, REQUEST_AT + 2L);
        assertEquals(WAITING_CONTROL_HANDSHAKE, terminal.state().phase());
        assertTrue(terminal.state().localTerminal());
        assertFalse(terminal.state().localOwnersZero());
        assertTrue(hasEffect(terminal, VERIFY_LOCAL_OWNERS));

        Reduction remote = remote(terminal.state(), CONFIRMED_ACK, REQUEST_AT + 3L);
        assertEquals(WAITING_LOCAL_TERMINAL, remote.state().phase());
        assertFalse(hasEffect(remote, ARM_DRAIN_DEADLINE));

        Reduction oneOwner = owners(remote.state(), 1, REQUEST_AT + 4L);
        assertSame(remote.state(), oneOwner.state());
        assertEquals(COALESCED, oneOwner.outcome());

        Reduction zeroOwners = owners(remote.state(), 0, REQUEST_AT + 5L);
        assertEquals(DRAINING, zeroOwners.state().phase());
        assertTrue(hasEffect(zeroOwners, ARM_DRAIN_DEADLINE));
    }

    @Test
    public void radioLossIsEpochScopedAndStillDoesNotProveOwnersZero() {
        State state = requested().state();
        Sequence staleEpoch = state.epoch().next();
        Reduction stale = reducer.onRadioOrPowerLoss(
                state,
                staleEpoch,
                state.sourceGeneration(),
                state.sourceRole(),
                REQUEST_AT + 1L
        );
        assertNoop(state, stale, STALE_CALLBACK);

        Reduction radio = reducer.onRadioOrPowerLoss(
                state,
                state.epoch(),
                state.sourceGeneration(),
                state.sourceRole(),
                REQUEST_AT + 2L
        );
        assertEquals(FREEZING, radio.state().phase());
        assertTrue(radio.state().localTerminal());
        assertTrue(radio.state().remoteAcknowledged());
        assertFalse(radio.state().localOwnersZero());
        assertTrue(hasEffect(radio, VERIFY_LOCAL_OWNERS));

        State frozen = freeze(radio.state(), REQUEST_AT + 3L).state();
        assertEquals(WAITING_LOCAL_TERMINAL, frozen.phase());
        State draining = owners(frozen, 0, REQUEST_AT + 4L).state();
        assertEquals(DRAINING, draining.phase());
    }

    @Test
    public void noRemoteOwnerIsTypedSeparateEvidence() {
        State state = requested().state();
        state = freeze(state, REQUEST_AT + 1L).state();
        state = localTerminal(state, REQUEST_AT + 2L).state();
        state = owners(state, 0, REQUEST_AT + 3L).state();
        assertEquals(WAITING_CONTROL_HANDSHAKE, state.phase());
        assertFalse(state.remoteAcknowledged());

        Reduction noPeer = remote(state, NO_REMOTE_OWNER, REQUEST_AT + 4L);
        assertEquals(DRAINING, noPeer.state().phase());
        assertEquals(NO_REMOTE_OWNER, noPeer.state().remoteCloseEvidence());
    }

    @Test
    public void remoteIntentRequiresAcceptedAckBeforeStopAndSameRoleCIsRejected() {
        State active = State.active(HELPER_PERIPHERAL_ANDROID_CENTRAL);
        Reduction remote = reducer.requestSwitchFromRemoteIntent(
                active,
                HELPER_CENTRAL_ANDROID_PERIPHERAL,
                REQUEST_AT,
                STOP_AT - REQUEST_AT,
                DRAIN_MILLIS
        );
        assertEquals(APPLIED, remote.outcome());
        assertEquals(
                BleRoleSwitchReducer.RemoteCloseEvidence.PEER_COMMITTED_INTENT,
                remote.state().remoteCloseEvidence()
        );
        Reduction frozen = freeze(remote.state(), REQUEST_AT + 1L);
        assertEquals(ACKNOWLEDGE_REMOTE_STOP, frozen.effects().get(0).type());
        assertEquals(1, frozen.effects().size());
        assertFalse(hasEffect(frozen, BleRoleSwitchReducer.EffectType.STOP_LOCAL_SOURCE));

        Reduction accepted = reducer.onControlTransmitResult(
                frozen.state(),
                frozen.state().epoch(),
                frozen.state().sourceGeneration(),
                frozen.state().sourceRole(),
                ControlFrame.CLOSE_ACK,
                frozen.state().controlAttempt(),
                ControlTransmitResult.ACCEPTED,
                REQUEST_AT + 2L
        );
        assertTrue(hasEffect(accepted, BleRoleSwitchReducer.EffectType.STOP_LOCAL_SOURCE));
        assertTrue(accepted.state().localStopRequested());

        Reduction mismatch = reducer.requestSwitchFromRemoteIntent(
                active,
                active.activeRole(),
                REQUEST_AT,
                STOP_AT - REQUEST_AT,
                DRAIN_MILLIS
        );
        assertNoop(active, mismatch, REJECTED_CONFLICT);
    }

    @Test
    public void localRequestNeverStopsForSendAcceptanceOnlyExactAck() {
        Reduction frozen = freeze(requested().state(), REQUEST_AT + 1L);
        assertEquals(WAITING_CONTROL_HANDSHAKE, frozen.state().phase());
        assertTrue(hasEffect(frozen, BleRoleSwitchReducer.EffectType.REQUEST_REMOTE_STOP));
        assertFalse(hasEffect(frozen, BleRoleSwitchReducer.EffectType.STOP_LOCAL_SOURCE));

        State state = frozen.state();
        Reduction sent = reducer.onControlTransmitResult(
                state,
                state.epoch(),
                state.sourceGeneration(),
                state.sourceRole(),
                ControlFrame.CLOSE_REQUEST,
                state.controlAttempt(),
                ControlTransmitResult.ACCEPTED,
                REQUEST_AT + 2L
        );
        assertEquals(WAITING_CONTROL_HANDSHAKE, sent.state().phase());
        assertTrue(sent.state().controlTransmitAccepted());
        assertFalse(sent.state().localStopRequested());
        assertFalse(hasEffect(sent, BleRoleSwitchReducer.EffectType.STOP_LOCAL_SOURCE));

        Reduction ack = remote(sent.state(), CONFIRMED_ACK, REQUEST_AT + 3L);
        assertEquals(WAITING_LOCAL_TERMINAL, ack.state().phase());
        assertTrue(ack.state().localStopRequested());
        assertTrue(hasEffect(ack, BleRoleSwitchReducer.EffectType.STOP_LOCAL_SOURCE));
    }

    @Test
    public void retryableControlFailureAllocatesExactNextAttemptWithoutNewDeadline() {
        State state = freeze(requested().state(), REQUEST_AT + 1L).state();
        Sequence firstAttempt = state.controlAttempt();
        long originalDeadline = state.stopDeadlineMillis();
        Reduction failed = reducer.onControlTransmitResult(
                state,
                state.epoch(),
                state.sourceGeneration(),
                state.sourceRole(),
                ControlFrame.CLOSE_REQUEST,
                firstAttempt,
                ControlTransmitResult.RETRYABLE_FAILURE,
                REQUEST_AT + 2L
        );
        assertEquals(firstAttempt.next(), failed.state().controlAttempt());
        assertEquals(originalDeadline, failed.state().stopDeadlineMillis());
        assertTrue(hasEffect(failed, BleRoleSwitchReducer.EffectType.SCHEDULE_CONTROL_RETRY));
        assertEquals(originalDeadline, failed.effects().get(0).deadlineMillis());

        Reduction oldAttempt = reducer.onControlTransmitResult(
                failed.state(),
                failed.state().epoch(),
                failed.state().sourceGeneration(),
                failed.state().sourceRole(),
                ControlFrame.CLOSE_REQUEST,
                firstAttempt,
                ControlTransmitResult.ACCEPTED,
                REQUEST_AT + 3L
        );
        assertNoop(failed.state(), oldAttempt, STALE_CALLBACK);

        Reduction retry = reducer.onControlTransmitRetry(
                failed.state(),
                failed.state().epoch(),
                failed.state().sourceGeneration(),
                failed.state().sourceRole(),
                ControlFrame.CLOSE_REQUEST,
                failed.state().controlAttempt(),
                REQUEST_AT + 4L
        );
        assertTrue(hasEffect(retry, BleRoleSwitchReducer.EffectType.REQUEST_REMOTE_STOP));
        assertEquals(originalDeadline, retry.state().stopDeadlineMillis());
    }

    @Test
    public void terminalControlFailureFailsClosedBeforeAnyLocalStop() {
        State state = freeze(requested().state(), REQUEST_AT + 1L).state();
        Reduction failed = reducer.onControlTransmitResult(
                state,
                state.epoch(),
                state.sourceGeneration(),
                state.sourceRole(),
                ControlFrame.CLOSE_REQUEST,
                state.controlAttempt(),
                ControlTransmitResult.TERMINAL_FAILURE,
                REQUEST_AT + 2L
        );
        assertEquals(FAILED, failed.state().phase());
        assertEquals(
                BleRoleSwitchReducer.Failure.CONTROL_TRANSMIT_FAILED,
                failed.state().failure()
        );
        assertTrue(hasEffect(failed, FAIL_CLOSED));
        assertFalse(hasEffect(failed, BleRoleSwitchReducer.EffectType.STOP_LOCAL_SOURCE));
    }

    @Test
    public void exactContradictoryRemoteRoleFailsClosed() {
        State state = freeze(requested().state(), REQUEST_AT + 1L).state();
        Reduction contradiction = reducer.onRemoteClosedEvidence(
                state,
                state.epoch(),
                state.sourceGeneration(),
                state.targetRole(),
                CONFIRMED_ACK,
                REQUEST_AT + 2L
        );
        assertEquals(FAILED, contradiction.state().phase());
        assertEquals(CONTRADICTORY_REMOTE_EVIDENCE, contradiction.state().failure());
        assertTrue(hasEffect(contradiction, FAIL_CLOSED));
        assertCannotStartTarget(contradiction.state());
    }

    @Test
    public void changingRemoteEvidenceInsideSameEpochFailsClosed() {
        State state = freeze(requested().state(), REQUEST_AT + 1L).state();
        state = remote(state, CONFIRMED_ACK, REQUEST_AT + 2L).state();
        Reduction contradiction = remote(state, NO_REMOTE_OWNER, REQUEST_AT + 3L);
        assertEquals(FAILED, contradiction.state().phase());
        assertEquals(CONTRADICTORY_REMOTE_EVIDENCE, contradiction.state().failure());
    }

    @Test
    public void impossibleOwnerMultiplicityFailsClosed() {
        State state = freeze(requested().state(), REQUEST_AT + 1L).state();
        Reduction impossible = owners(state, 2, REQUEST_AT + 2L);
        assertEquals(FAILED, impossible.state().phase());
        assertEquals(IMPOSSIBLE_LOCAL_OWNER_COUNT, impossible.state().failure());
        assertTrue(hasEffect(impossible, FAIL_CLOSED));
        assertCannotStartTarget(impossible.state());
    }

    @Test
    public void exactNonzeroOwnerAfterAcceptedZeroFailsClosedUntilTargetIsActive() {
        List<State> zeroProvenStates = new ArrayList<>();

        State waitingRemote = requested().state();
        waitingRemote = freeze(waitingRemote, REQUEST_AT + 1L).state();
        waitingRemote = localTerminal(waitingRemote, REQUEST_AT + 2L).state();
        waitingRemote = owners(waitingRemote, 0, REQUEST_AT + 3L).state();
        zeroProvenStates.add(waitingRemote);

        State draining = remote(waitingRemote, CONFIRMED_ACK, REQUEST_AT + 4L).state();
        zeroProvenStates.add(draining);

        State quiescent = drainElapsed(draining).state();
        zeroProvenStates.add(quiescent);

        State starting = reducer.beginTargetStart(
                quiescent,
                quiescent.epoch(),
                quiescent.targetGeneration(),
                quiescent.targetRole()
        ).state();
        zeroProvenStates.add(starting);

        for (State state : zeroProvenStates) {
            Reduction contradiction = owners(state, 1, REQUEST_AT + 10L);
            assertEquals("phase=" + state.phase(), FAILED, contradiction.state().phase());
            assertEquals(
                    "phase=" + state.phase(),
                    CONTRADICTORY_LOCAL_OWNER_EVIDENCE,
                    contradiction.state().failure()
            );
            assertTrue(hasEffect(contradiction, FAIL_CLOSED));
            assertCannotStartTarget(contradiction.state());
        }
    }

    @Test
    public void stopTimeoutFailsClosedFromEveryPreDrainPhase() {
        List<State> states = new ArrayList<>();
        State freezing = requested().state();
        states.add(freezing);
        State waitingLocal = freeze(freezing, REQUEST_AT + 1L).state();
        states.add(waitingLocal);
        State terminalButOwned = localTerminal(waitingLocal, REQUEST_AT + 2L).state();
        states.add(terminalButOwned);
        State waitingRemote = owners(terminalButOwned, 0, REQUEST_AT + 3L).state();
        states.add(waitingRemote);

        for (State state : states) {
            Reduction timeout = reducer.onStopTimeout(
                    state,
                    state.epoch(),
                    state.sourceGeneration(),
                    state.sourceRole(),
                    state.stopDeadlineMillis()
            );
            assertEquals(FAILED, timeout.state().phase());
            assertEquals(STOP_TIMEOUT, timeout.state().failure());
            assertTrue(hasEffect(timeout, FAIL_CLOSED));
            assertCannotStartTarget(timeout.state());
        }
    }

    @Test
    public void callbackAtStopDeadlineLosesToTimeout() {
        State state = requested().state();
        Reduction boundary = reducer.onIngressFrozen(
                state,
                state.epoch(),
                state.sourceGeneration(),
                state.sourceRole(),
                state.stopDeadlineMillis()
        );
        assertEquals(FAILED, boundary.state().phase());
        assertEquals(STOP_TIMEOUT, boundary.state().failure());
    }

    @Test
    public void oldTimeoutAfterDrainIsAStaleNoop() {
        State draining = completeEvidence(requested().state(), CONFIRMED_ACK, REQUEST_AT + 1L);
        Reduction late = reducer.onStopTimeout(
                draining,
                draining.epoch(),
                draining.sourceGeneration(),
                draining.sourceRole(),
                STOP_AT + 50L
        );
        assertNoop(draining, late, STALE_CALLBACK);
    }

    @Test
    public void everyWrongEpochOrGenerationCallbackIsANoop() {
        State state = requested().state();
        Sequence wrongEpoch = state.epoch().next();
        Sequence wrongGeneration = state.sourceGeneration().next();
        List<Reduction> stale = List.of(
                reducer.onIngressFrozen(state, wrongEpoch, state.sourceGeneration(),
                        state.sourceRole(), REQUEST_AT + 1L),
                reducer.onLocalTerminal(state, state.epoch(), wrongGeneration,
                        state.sourceRole(), REQUEST_AT + 1L),
                reducer.onLocalOwnerCount(state, wrongEpoch, state.sourceGeneration(),
                        state.sourceRole(), 0, REQUEST_AT + 1L),
                reducer.onRemoteClosedEvidence(state, wrongEpoch, state.sourceGeneration(),
                        state.targetRole(), CONFIRMED_ACK, REQUEST_AT + 1L),
                reducer.onRadioOrPowerLoss(state, state.epoch(), wrongGeneration,
                        state.sourceRole(), REQUEST_AT + 1L),
                reducer.onStopTimeout(state, wrongEpoch, state.sourceGeneration(),
                        state.sourceRole(), STOP_AT)
        );
        for (Reduction reduction : stale) {
            assertNoop(state, reduction, STALE_CALLBACK);
        }

        State draining = completeEvidence(state, CONFIRMED_ACK, REQUEST_AT + 2L);
        assertNoop(
                draining,
                reducer.onDrainDeadline(
                        draining,
                        wrongEpoch,
                        draining.sourceGeneration(),
                        draining.sourceRole(),
                        draining.drainDeadlineMillis()
                ),
                STALE_CALLBACK
        );
    }

    @Test
    public void sameRequestCoalescesAndConflictingRequestIsRejectedInEveryPhase() {
        List<State> inFlight = inFlightStates();
        for (State state : inFlight) {
            Reduction same = reducer.requestSwitch(
                    state,
                    state.targetRole(),
                    REQUEST_AT + 10L,
                    100L,
                    20L
            );
            assertNoop(state, same, COALESCED);

            Reduction conflict = reducer.requestSwitch(
                    state,
                    state.sourceRole(),
                    REQUEST_AT + 10L,
                    100L,
                    20L
            );
            assertNoop(state, conflict, REJECTED_CONFLICT);
        }
        State active = State.active(HELPER_PERIPHERAL_ANDROID_CENTRAL);
        assertEquals(COALESCED, reducer.requestSwitch(
                active,
                active.activeRole(),
                REQUEST_AT,
                100L,
                20L
        ).outcome());
    }

    @Test
    public void sameRoleRestartUsesFullLocalDrainWithoutWireControl() {
        State active = State.active(HELPER_PERIPHERAL_ANDROID_CENTRAL);
        Reduction requested = reducer.requestSameRoleRestart(
                active, REQUEST_AT, STOP_AT - REQUEST_AT, DRAIN_MILLIS);

        assertEquals(APPLIED, requested.outcome());
        assertEquals(FREEZING, requested.state().phase());
        assertEquals(active.activeRole(), requested.state().sourceRole());
        assertEquals(active.activeRole(), requested.state().targetRole());
        assertEquals(RemoteCloseEvidence.PEER_SAME_ROLE_RETAINED,
                requested.state().remoteCloseEvidence());
        assertNull(requested.state().controlFrame());
        assertFalse(hasEffect(requested, EffectType.REQUEST_REMOTE_STOP));
        assertFalse(hasEffect(requested, ACKNOWLEDGE_REMOTE_STOP));

        Reduction frozen = freeze(requested.state(), REQUEST_AT + 1L);
        assertEquals(WAITING_LOCAL_TERMINAL, frozen.state().phase());
        assertTrue(hasEffect(frozen, EffectType.STOP_LOCAL_SOURCE));

        State state = localTerminal(frozen.state(), REQUEST_AT + 2L).state();
        state = owners(state, 0, REQUEST_AT + 3L).state();
        assertEquals(DRAINING, state.phase());
        Reduction quiescent = drainElapsed(state);
        assertEquals(QUIESCENT, quiescent.state().phase());
        Reduction starting = reducer.beginTargetStart(
                quiescent.state(),
                quiescent.state().epoch(),
                quiescent.state().targetGeneration(),
                quiescent.state().targetRole());
        assertEquals(STARTING, starting.state().phase());
        assertTrue(hasEffect(starting, START_TARGET));
    }

    @Test
    public void exactFreezeFailureIsImmediateAndFailClosed() {
        State freezing = requested().state();
        Reduction failed = reducer.onIngressFreezeFailed(
                freezing,
                freezing.epoch(),
                freezing.sourceGeneration(),
                freezing.sourceRole());
        assertEquals(APPLIED, failed.outcome());
        assertEquals(FAILED, failed.state().phase());
        assertEquals(BleRoleSwitchReducer.Failure.INGRESS_FREEZE_FAILED,
                failed.state().failure());
        assertTrue(hasEffect(failed, FAIL_CLOSED));

        State active = State.active(HELPER_PERIPHERAL_ANDROID_CENTRAL);
        assertNoop(active, reducer.onIngressFreezeFailed(
                active,
                active.epoch(),
                active.activeGeneration(),
                active.activeRole()), STALE_CALLBACK);
    }

    @Test
    public void atomicNoRemoteFreezeNeverEmitsControlAttempt() {
        State freezing = requested().state();
        Reduction noPeer = reducer.onIngressFrozenWithoutRemoteOwner(
                freezing,
                freezing.epoch(),
                freezing.sourceGeneration(),
                freezing.sourceRole(),
                REQUEST_AT + 1L);
        assertEquals(APPLIED, noPeer.outcome());
        assertEquals(WAITING_LOCAL_TERMINAL, noPeer.state().phase());
        assertEquals(NO_REMOTE_OWNER, noPeer.state().remoteCloseEvidence());
        assertTrue(noPeer.state().ingressFrozen());
        assertTrue(hasEffect(noPeer, EffectType.STOP_LOCAL_SOURCE));
        assertFalse(hasEffect(noPeer, EffectType.REQUEST_REMOTE_STOP));
        assertFalse(hasEffect(noPeer, ACKNOWLEDGE_REMOTE_STOP));
    }

    @Test
    public void failedAndClosedStatesRejectAllFutureRequests() {
        State state = requested().state();
        State failed = reducer.onStopTimeout(
                state,
                state.epoch(),
                state.sourceGeneration(),
                state.sourceRole(),
                STOP_AT
        ).state();
        assertEquals(REJECTED_TERMINAL, reducer.requestSwitch(
                failed,
                failed.targetRole(),
                STOP_AT + 1L,
                100L,
                20L
        ).outcome());

        Reduction closed = reducer.close(failed);
        assertEquals(BleRoleSwitchReducer.Phase.CLOSED, closed.state().phase());
        assertTrue(hasEffect(closed, EffectType.CLOSE_ALL));
        assertEquals(REJECTED_TERMINAL, reducer.requestSwitch(
                closed.state(),
                HELPER_PERIPHERAL_ANDROID_CENTRAL,
                STOP_AT + 2L,
                100L,
                20L
        ).outcome());
        assertEquals(COALESCED, reducer.close(closed.state()).outcome());
    }

    @Test
    public void restoredOwnerAlwaysReentersFullDrain() {
        Reduction restored = reducer.restoreDrain(
                HELPER_PERIPHERAL_ANDROID_CENTRAL,
                HELPER_CENTRAL_ANDROID_PERIPHERAL,
                Sequence.of(41L),
                Sequence.of(7L),
                Sequence.of(8L),
                REQUEST_AT,
                100L,
                20L
        );
        assertEquals(FREEZING, restored.state().phase());
        assertNull(restored.state().activeRole());
        assertEquals(HELPER_CENTRAL_ANDROID_PERIPHERAL, restored.state().desiredRole());
        assertFalse(hasEffect(restored, START_TARGET));

        State draining = completeEvidence(restored.state(), NO_REMOTE_OWNER, REQUEST_AT + 1L);
        assertEquals(DRAINING, draining.phase());
        Reduction elapsed = drainElapsed(draining);
        assertEquals(QUIESCENT, elapsed.state().phase());
        assertFalse(hasEffect(elapsed, START_TARGET));
    }

    @Test
    public void activeOwnerAndRestoredTransitionRequireNonZeroOwnershipTokens() {
        try {
            State.active(
                    HELPER_PERIPHERAL_ANDROID_CENTRAL,
                    Sequence.zero(),
                    Sequence.zero()
            );
            fail("zero active generation accepted");
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected.getMessage());
        }
        try {
            reducer.restoreDrain(
                    HELPER_PERIPHERAL_ANDROID_CENTRAL,
                    HELPER_CENTRAL_ANDROID_PERIPHERAL,
                    Sequence.zero(),
                    Sequence.of(1L),
                    Sequence.of(2L),
                    REQUEST_AT,
                    100L,
                    20L
            );
            fail("zero transition epoch accepted");
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected.getMessage());
        }

        State requested = requested().state();
        assertTrue(requested.epoch().compareTo(Sequence.zero()) > 0);
        assertTrue(requested.sourceGeneration().compareTo(Sequence.zero()) > 0);
        assertTrue(requested.targetGeneration().compareTo(Sequence.zero()) > 0);
    }

    @Test
    public void epochAndGenerationNeverWrapAtLongMaxValue() {
        Sequence max = Sequence.of(Long.MAX_VALUE);
        State active = State.active(HELPER_PERIPHERAL_ANDROID_CENTRAL, max, max);
        State first = reducer.requestSwitch(
                active,
                HELPER_CENTRAL_ANDROID_PERIPHERAL,
                REQUEST_AT,
                100L,
                20L
        ).state();
        BigInteger onePastLong = BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE);
        assertEquals(onePastLong, first.epoch().asBigInteger());
        assertEquals(onePastLong, first.targetGeneration().asBigInteger());

        State draining = completeEvidence(first, CONFIRMED_ACK, REQUEST_AT + 1L);
        State quiescent = drainElapsed(draining).state();
        State starting = reducer.beginTargetStart(
                quiescent,
                quiescent.epoch(),
                quiescent.targetGeneration(),
                quiescent.targetRole()
        ).state();
        State secondActive = reducer.onTargetActive(
                starting,
                starting.epoch(),
                starting.targetGeneration(),
                starting.targetRole()
        ).state();
        State second = reducer.requestSwitch(
                secondActive,
                HELPER_PERIPHERAL_ANDROID_CENTRAL,
                REQUEST_AT + 100L,
                100L,
                20L
        ).state();
        assertEquals(onePastLong.add(BigInteger.ONE), second.epoch().asBigInteger());
        assertEquals(onePastLong.add(BigInteger.ONE), second.targetGeneration().asBigInteger());
    }

    @Test
    public void absoluteDeadlinesSaturateInsteadOfOverflowing() {
        State state = reducer.requestSwitch(
                State.active(HELPER_PERIPHERAL_ANDROID_CENTRAL),
                HELPER_CENTRAL_ANDROID_PERIPHERAL,
                Long.MAX_VALUE - 5L,
                100L,
                20L
        ).state();
        assertEquals(Long.MAX_VALUE, state.stopDeadlineMillis());
    }

    @Test
    public void crossLanguageTransitionFixtureCoversCanonicalSafetyCases() throws Exception {
        Path fixture = Path.of(
                "app/src/main/java/dezz/status/widget/phone/transport/switching/"
                        + "ble-role-switch-transition-vectors.json"
        );
        assertTrue(Files.isRegularFile(fixture));
        String json = new String(Files.readAllBytes(fixture), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"schema\": \"ble-role-switch/v2\""));
        assertTrue(json.contains("\"confirmed_ack_happy_path\""));
        assertTrue(json.contains("\"radio_loss_requires_separate_zero_owner_proof\""));
        assertTrue(json.contains("\"deadline_wins_callback_race\""));
        assertTrue(json.contains("\"same_target_coalesces_conflict_rejected\""));
        assertTrue(json.contains("\"wrong_epoch_is_noop_wrong_role_is_contradiction\""));
        assertTrue(json.contains("\"zero_owner_then_exact_one_fails_closed\""));
        assertTrue(json.contains(
                "\"remote_c_waits_for_async_ack_acceptance_before_local_stop\""));
        assertTrue(json.contains("\"retry_uses_exact_next_attempt_and_original_deadline\""));
        assertTrue(json.contains(
                "\"duplicate_exact_remote_c_while_a_in_flight_coalesces\""));
        assertTrue(json.contains("\"starting_restore_is_same_role_local_only\""));
        assertTrue(json.contains("\"restoration_is_drain_only\""));
    }

    private Reduction requested() {
        return reducer.requestSwitch(
                State.active(HELPER_PERIPHERAL_ANDROID_CENTRAL),
                HELPER_CENTRAL_ANDROID_PERIPHERAL,
                REQUEST_AT,
                STOP_AT - REQUEST_AT,
                DRAIN_MILLIS
        );
    }

    private Reduction freeze(State state, long now) {
        return reducer.onIngressFrozen(
                state,
                state.epoch(),
                state.sourceGeneration(),
                state.sourceRole(),
                now
        );
    }

    private Reduction localTerminal(State state, long now) {
        return reducer.onLocalTerminal(
                state,
                state.epoch(),
                state.sourceGeneration(),
                state.sourceRole(),
                now
        );
    }

    private Reduction owners(State state, int count, long now) {
        return reducer.onLocalOwnerCount(
                state,
                state.epoch(),
                state.sourceGeneration(),
                state.sourceRole(),
                count,
                now
        );
    }

    private Reduction remote(State state, RemoteCloseEvidence evidence, long now) {
        return reducer.onRemoteClosedEvidence(
                state,
                state.epoch(),
                state.sourceGeneration(),
                state.sourceRole(),
                evidence,
                now
        );
    }

    private State completeEvidence(State initial, RemoteCloseEvidence evidence, long now) {
        State state = initial;
        state = freeze(state, now++).state();
        state = localTerminal(state, now++).state();
        state = owners(state, 0, now++).state();
        state = remote(state, evidence, now).state();
        return state;
    }

    private Reduction drainElapsed(State draining) {
        return reducer.onDrainDeadline(
                draining,
                draining.epoch(),
                draining.sourceGeneration(),
                draining.sourceRole(),
                draining.drainDeadlineMillis()
        );
    }

    private Reduction applyEvidence(State state, int operation, long now) {
        return switch (operation) {
            case 0 -> freeze(state, now);
            case 1 -> localTerminal(state, now);
            case 2 -> owners(state, 0, now);
            case 3 -> remote(state, CONFIRMED_ACK, now);
            default -> throw new AssertionError("unknown operation " + operation);
        };
    }

    private List<State> inFlightStates() {
        List<State> states = new ArrayList<>();
        State state = requested().state();
        states.add(state);
        state = freeze(state, REQUEST_AT + 1L).state();
        states.add(state);
        state = localTerminal(state, REQUEST_AT + 2L).state();
        states.add(state);
        state = owners(state, 0, REQUEST_AT + 3L).state();
        states.add(state);
        state = remote(state, CONFIRMED_ACK, REQUEST_AT + 4L).state();
        states.add(state);
        state = drainElapsed(state).state();
        states.add(state);
        state = reducer.beginTargetStart(
                state,
                state.epoch(),
                state.targetGeneration(),
                state.targetRole()
        ).state();
        states.add(state);
        return states;
    }

    private void assertCannotStartTarget(State failed) {
        Reduction begin = reducer.beginTargetStart(
                failed,
                failed.epoch(),
                failed.targetGeneration(),
                failed.targetRole()
        );
        assertNoop(failed, begin, STALE_CALLBACK);
        assertFalse(hasEffect(begin, START_TARGET));
    }

    private static void assertNoop(
            State expected,
            Reduction reduction,
            BleRoleSwitchReducer.Outcome outcome
    ) {
        assertSame(expected, reduction.state());
        assertEquals(outcome, reduction.outcome());
        assertTrue(reduction.effects().isEmpty());
    }

    private static boolean hasEffect(Reduction reduction, EffectType type) {
        for (Effect effect : reduction.effects()) {
            if (effect.type() == type) {
                return true;
            }
        }
        return false;
    }

    private static void permute(int[] values, int index, List<int[]> output) {
        if (index == values.length) {
            output.add(values.clone());
            return;
        }
        for (int swap = index; swap < values.length; swap++) {
            int value = values[index];
            values[index] = values[swap];
            values[swap] = value;
            permute(values, index + 1, output);
            value = values[index];
            values[index] = values[swap];
            values[swap] = value;
        }
    }
}
