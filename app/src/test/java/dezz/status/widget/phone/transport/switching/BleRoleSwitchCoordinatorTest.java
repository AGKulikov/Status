/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.phone.transport.switching;

import static dezz.status.widget.phone.transport.switching.BleRoleSwitchReducer.Outcome.APPLIED;
import static dezz.status.widget.phone.transport.switching.BleRoleSwitchReducer.Outcome.COALESCED;
import static dezz.status.widget.phone.transport.switching.BleRoleSwitchReducer.Outcome.STALE_CALLBACK;
import static dezz.status.widget.phone.transport.switching.BleRoleSwitchReducer.Phase.ACTIVE;
import static dezz.status.widget.phone.transport.switching.BleRoleSwitchReducer.Phase.CLOSED;
import static dezz.status.widget.phone.transport.switching.BleRoleSwitchReducer.Phase.DRAINING;
import static dezz.status.widget.phone.transport.switching.BleRoleSwitchReducer.Phase.FAILED;
import static dezz.status.widget.phone.transport.switching.BleRoleSwitchReducer.Phase.FREEZING;
import static dezz.status.widget.phone.transport.switching.BleRoleSwitchReducer.Phase.WAITING_CONTROL_HANDSHAKE;
import static dezz.status.widget.phone.transport.switching.BleRoleSwitchReducer.Phase.QUIESCENT;
import static dezz.status.widget.phone.transport.switching.BleRoleSwitchReducer.Phase.STARTING;
import static dezz.status.widget.phone.transport.switching.BleRoleSwitchReducer.Phase.WAITING_LOCAL_TERMINAL;
import static dezz.status.widget.phone.transport.switching.BleRoleSwitchReducer.Phase.WAITING_REMOTE_ACK;
import static dezz.status.widget.phone.transport.switching.BleRoleSwitchReducer.RemoteCloseEvidence.NO_REMOTE_OWNER;
import static dezz.status.widget.phone.transport.switching.BleRoleSwitchReducer.Role.HELPER_CENTRAL_ANDROID_PERIPHERAL;
import static dezz.status.widget.phone.transport.switching.BleRoleSwitchReducer.Role.HELPER_PERIPHERAL_ANDROID_CENTRAL;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import dezz.status.widget.phone.transport.switching.BleRoleSwitchCoordinator.EffectsPort;
import dezz.status.widget.phone.transport.switching.BleRoleSwitchCoordinator.ControlTransmit;
import dezz.status.widget.phone.transport.switching.BleRoleSwitchCoordinator.Owner;
import dezz.status.widget.phone.transport.switching.BleRoleSwitchCoordinator.SerializedExecutionGuard;
import dezz.status.widget.phone.transport.switching.BleRoleSwitchCoordinator.WireSwitchToken;
import dezz.status.widget.phone.transport.switching.BleRoleSwitchReducer.Failure;
import dezz.status.widget.phone.transport.switching.BleRoleSwitchReducer.ControlFrame;
import dezz.status.widget.phone.transport.switching.BleRoleSwitchReducer.ControlTransmitResult;
import dezz.status.widget.phone.transport.switching.BleRoleSwitchReducer.Outcome;
import dezz.status.widget.phone.transport.switching.BleRoleSwitchReducer.Phase;
import dezz.status.widget.phone.transport.switching.BleRoleSwitchReducer.Role;
import dezz.status.widget.phone.transport.switching.BleRoleSwitchReducer.Sequence;
import dezz.status.widget.phone.transport.v2.IphoneBleMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public final class BleRoleSwitchCoordinatorTest {
    private static final long PROCESS_ONE = 0x1111L;
    private static final long PROCESS_TWO = 0x2222L;
    private static final long REQUEST_AT = 1_000L;
    private static final long STOP_TIMEOUT = 100L;
    private static final long DRAIN_DURATION = 20L;

    @Test
    public void everyEffectBatchIsPersistedFirstAndInReducerOrder() {
        FakeGuard guard = new FakeGuard();
        FakePort port = new FakePort();
        BleRoleSwitchCoordinator coordinator = active(port, guard);
        port.clear();

        assertEquals(APPLIED, coordinator.requestSwitch(
                HELPER_CENTRAL_ANDROID_PERIPHERAL,
                REQUEST_AT,
                STOP_TIMEOUT,
                DRAIN_DURATION,
                token(1)
        ));
        assertLog(port, "persist:FREEZING", "freeze", "armStop:1100");

        Owner source = coordinator.sourceOwner();
        port.clear();
        assertEquals(APPLIED, coordinator.onIngressFrozen(source, REQUEST_AT + 1L));
        assertLog(port, "persist:WAITING_CONTROL_HANDSHAKE", "sendC:1");
        assertEquals(HELPER_CENTRAL_ANDROID_PERIPHERAL, port.sentDesiredRole);
        assertEquals(token(1), port.sentToken);
        assertFalse(port.log.contains("stopLocal"));

        port.clear();
        assertEquals(APPLIED, coordinator.onRemoteCloseAck(
                source,
                HELPER_CENTRAL_ANDROID_PERIPHERAL,
                token(1).bytes(),
                REQUEST_AT + 2L
        ));
        assertLog(port, "persist:WAITING_LOCAL_TERMINAL", "stopLocal");

        port.clear();
        assertEquals(APPLIED, coordinator.onLocalTerminal(source, REQUEST_AT + 3L));
        assertLog(port, "persist:WAITING_LOCAL_TERMINAL", "verifyOwners");

        port.clear();
        assertEquals(APPLIED, coordinator.onLocalOwnerCount(source, 0, REQUEST_AT + 4L));
        assertLog(port, "persist:DRAINING", "cancelStop", "armDrain:1024");

        port.clear();
        assertEquals(APPLIED, coordinator.onDrainDeadline(source, 1_024L));
        assertLog(port, "persist:QUIESCENT", "cancelDrain", "quiescent");

        Owner target = coordinator.targetOwner();
        port.clear();
        assertEquals(APPLIED, coordinator.beginTargetStart(target));
        assertLog(port, "persist:STARTING", "startTarget");

        port.clear();
        assertEquals(APPLIED, coordinator.onTargetActive(target));
        assertLog(port, "persist:ACTIVE", "targetActive");
        assertEquals(ACTIVE, coordinator.state().phase());
        assertEquals(HELPER_CENTRAL_ANDROID_PERIPHERAL, coordinator.state().activeRole());
        assertTrue(guard.assertionCount > 0);
    }

    @Test
    public void persistenceFailureCommitsNeitherStateNorEffects() {
        FakeGuard guard = new FakeGuard();
        FakePort port = new FakePort();
        BleRoleSwitchCoordinator coordinator = active(port, guard);
        port.clear();
        port.failPersistence = true;

        try {
            coordinator.requestSwitch(
                    HELPER_CENTRAL_ANDROID_PERIPHERAL,
                    REQUEST_AT,
                    STOP_TIMEOUT,
                    DRAIN_DURATION,
                    token(2)
            );
            fail("persistence failure was swallowed");
        } catch (IllegalStateException expected) {
            assertEquals("persist failed", expected.getMessage());
        }

        assertEquals(ACTIVE, coordinator.state().phase());
        assertEquals(List.of("persistAttempt"), port.log);
    }

    @Test
    public void noopDoesNotRewriteSnapshotOrExtendDeadline() {
        FakePort port = new FakePort();
        BleRoleSwitchCoordinator coordinator = active(port, new FakeGuard());
        coordinator.requestSwitch(
                HELPER_CENTRAL_ANDROID_PERIPHERAL,
                REQUEST_AT,
                STOP_TIMEOUT,
                DRAIN_DURATION,
                token(3)
        );
        long deadline = coordinator.state().stopDeadlineMillis();
        port.clear();

        assertEquals(COALESCED, coordinator.requestSwitch(
                HELPER_CENTRAL_ANDROID_PERIPHERAL,
                REQUEST_AT + 50L,
                STOP_TIMEOUT,
                DRAIN_DURATION,
                token(4)
        ));
        assertTrue(port.log.isEmpty());
        assertEquals(deadline, coordinator.state().stopDeadlineMillis());
    }

    @Test
    public void sameRoleRecoveryIsDurableLocalOnlyAndNeverSendsControl() {
        FakePort port = new FakePort();
        BleRoleSwitchCoordinator coordinator = active(port, new FakeGuard());
        port.clear();

        assertEquals(APPLIED, coordinator.requestSameRoleRestart(
                REQUEST_AT, STOP_TIMEOUT, DRAIN_DURATION, token(44)));
        assertLog(port, "persist:FREEZING", "freeze", "armStop:1100");
        BleRoleSwitchSnapshotCodec.Snapshot snapshot =
                new BleRoleSwitchSnapshotCodec().decode(coordinator.encodedSnapshot());
        assertEquals(BleRoleSwitchOrigin.LOCAL_ONLY_RESTORE, snapshot.origin());
        assertEquals(snapshot.sourceRole(), snapshot.targetRole());

        Owner source = coordinator.sourceOwner();
        port.clear();
        assertEquals(APPLIED, coordinator.onIngressFrozen(source, REQUEST_AT + 1L));
        assertLog(port, "persist:WAITING_LOCAL_TERMINAL", "stopLocal");
        assertFalse(hasPrefix(port.log, "sendC:"));
        assertFalse(hasPrefix(port.log, "sendA:"));

        port.clear();
        coordinator.onLocalTerminal(source, REQUEST_AT + 2L);
        assertLog(port, "persist:WAITING_LOCAL_TERMINAL", "verifyOwners");
        port.clear();
        coordinator.onLocalOwnerCount(source, 0, REQUEST_AT + 3L);
        assertLog(port, "persist:DRAINING", "cancelStop", "armDrain:1023");
    }

    @Test
    public void adapterFreezeFailurePersistsBeforeFailClosedEffect() {
        FakePort port = new FakePort();
        BleRoleSwitchCoordinator coordinator = active(port, new FakeGuard());
        coordinator.requestSwitch(
                HELPER_CENTRAL_ANDROID_PERIPHERAL,
                REQUEST_AT,
                STOP_TIMEOUT,
                DRAIN_DURATION,
                token(45));
        Owner source = coordinator.sourceOwner();
        port.clear();

        assertEquals(APPLIED, coordinator.onIngressFreezeFailed(source));
        assertLog(port, "persist:FAILED", "fail:INGRESS_FREEZE_FAILED");
        assertEquals(FAILED, coordinator.state().phase());
    }

    @Test
    public void typedNoRemoteFreezePersistsBeforeStopAndSkipsWire() {
        FakePort port = new FakePort();
        BleRoleSwitchCoordinator coordinator = active(port, new FakeGuard());
        coordinator.requestSwitch(
                HELPER_CENTRAL_ANDROID_PERIPHERAL,
                REQUEST_AT,
                STOP_TIMEOUT,
                DRAIN_DURATION,
                token(46));
        Owner source = coordinator.sourceOwner();
        port.clear();

        assertEquals(APPLIED, coordinator.onIngressFrozenWithoutRemoteOwner(
                source, REQUEST_AT + 1L));
        assertLog(port, "persist:WAITING_LOCAL_TERMINAL", "stopLocal");
        assertFalse(hasPrefix(port.log, "sendC:"));
        assertFalse(hasPrefix(port.log, "sendA:"));
        assertEquals(NO_REMOTE_OWNER, coordinator.state().remoteCloseEvidence());
    }

    @Test
    public void wrongProcessNonceAndWrongWireTokenAreTrueNoops() {
        FakePort port = new FakePort();
        BleRoleSwitchCoordinator coordinator = active(port, new FakeGuard());
        coordinator.requestSwitch(
                HELPER_CENTRAL_ANDROID_PERIPHERAL,
                REQUEST_AT,
                STOP_TIMEOUT,
                DRAIN_DURATION,
                token(5)
        );
        Owner current = coordinator.sourceOwner();
        Owner oldProcess = new Owner(
                PROCESS_TWO,
                current.epoch(),
                current.generation(),
                current.role()
        );
        port.clear();

        assertEquals(STALE_CALLBACK, coordinator.onIngressFrozen(oldProcess, REQUEST_AT + 1L));
        assertEquals(STALE_CALLBACK, coordinator.onRemoteCloseAck(
                current,
                HELPER_CENTRAL_ANDROID_PERIPHERAL,
                token(6).bytes(),
                REQUEST_AT + 1L
        ));
        assertTrue(port.log.isEmpty());
        assertEquals(FREEZING, coordinator.state().phase());
    }

    @Test
    public void staleOwnerCannotInjectRemoteCIntoActiveCoordinator() {
        FakePort port = new FakePort();
        BleRoleSwitchCoordinator coordinator = active(port, new FakeGuard());
        Owner activeOwner = coordinator.targetOwner();
        Owner staleProcessOwner = new Owner(
                PROCESS_TWO,
                activeOwner.epoch(),
                activeOwner.generation(),
                activeOwner.role()
        );
        port.clear();
        assertEquals(STALE_CALLBACK, coordinator.requestSwitchFromRemote(
                staleProcessOwner,
                HELPER_CENTRAL_ANDROID_PERIPHERAL,
                token(61),
                REQUEST_AT,
                STOP_TIMEOUT,
                DRAIN_DURATION
        ));
        assertEquals(ACTIVE, coordinator.state().phase());
        assertTrue(port.log.isEmpty());
    }

    @Test
    public void ownerRegistryReplayFromZeroBackToOneFailsClosedAndIsPersistedFirst() {
        FakePort port = new FakePort();
        BleRoleSwitchCoordinator coordinator = active(port, new FakeGuard());
        coordinator.requestSwitch(
                HELPER_CENTRAL_ANDROID_PERIPHERAL,
                REQUEST_AT,
                STOP_TIMEOUT,
                DRAIN_DURATION,
                token(7)
        );
        Owner source = coordinator.sourceOwner();
        coordinator.onIngressFrozen(source, REQUEST_AT + 1L);
        coordinator.onLocalTerminal(source, REQUEST_AT + 2L);
        coordinator.onLocalOwnerCount(source, 0, REQUEST_AT + 3L);
        coordinator.onRemoteClosedWithoutAck(source, NO_REMOTE_OWNER, REQUEST_AT + 4L);
        assertEquals(DRAINING, coordinator.state().phase());
        port.clear();

        assertEquals(APPLIED, coordinator.onLocalOwnerCount(source, 1, STOP_TIMEOUT + REQUEST_AT));
        assertEquals(FAILED, coordinator.state().phase());
        assertEquals(
                Failure.CONTRADICTORY_LOCAL_OWNER_EVIDENCE,
                coordinator.state().failure()
        );
        assertLog(
                port,
                "persist:FAILED",
                "fail:" + Failure.CONTRADICTORY_LOCAL_OWNER_EVIDENCE
        );
        assertFalse(port.log.contains("startTarget"));
    }

    @Test
    public void twoCoordinatorsCompleteOneCOneAHandshakeAndBothReachQuiescent() {
        FakePort localPort = new FakePort();
        FakePort remotePort = new FakePort();
        BleRoleSwitchCoordinator local = active(localPort, new FakeGuard());
        BleRoleSwitchCoordinator remote = BleRoleSwitchCoordinator.active(
                PROCESS_TWO,
                HELPER_PERIPHERAL_ANDROID_CENTRAL,
                Sequence.zero(),
                Sequence.of(1L),
                remotePort,
                new FakeGuard()
        );
        WireSwitchToken switchToken = token(8);

        local.requestSwitch(
                HELPER_CENTRAL_ANDROID_PERIPHERAL,
                REQUEST_AT,
                STOP_TIMEOUT,
                DRAIN_DURATION,
                switchToken
        );
        Owner localSource = local.sourceOwner();
        localPort.clear();
        local.onIngressFrozen(localSource, REQUEST_AT + 1L);
        assertLog(localPort, "persist:WAITING_CONTROL_HANDSHAKE", "sendC:1");
        assertFalse(localPort.log.contains("stopLocal"));

        remotePort.clear();
        assertEquals(APPLIED, remote.requestSwitchFromRemote(
                remote.targetOwner(),
                localPort.sentDesiredRole,
                localPort.sentToken,
                REQUEST_AT + 2L,
                STOP_TIMEOUT,
                DRAIN_DURATION
        ));
        assertLog(remotePort, "persist:FREEZING", "freeze", "armStop:1102");
        BleRoleSwitchSnapshotCodec.Snapshot committed = remotePort.codec.decode(
                remotePort.lastSnapshot
        );
        assertEquals(BleRoleSwitchOrigin.REMOTE, committed.origin());
        assertEquals(switchToken, committed.wireToken());

        Owner remoteSource = remote.sourceOwner();
        remotePort.clear();
        remote.onIngressFrozen(remoteSource, REQUEST_AT + 3L);
        assertLog(remotePort, "persist:WAITING_CONTROL_HANDSHAKE", "sendA:1");
        assertEquals(HELPER_CENTRAL_ANDROID_PERIPHERAL, remotePort.sentAckDesiredRole);
        assertEquals(switchToken, remotePort.sentAckToken);
        assertFalse(hasPrefix(remotePort.log, "sendC:"));
        assertFalse(remotePort.log.contains("stopLocal"));

        ControlTransmit ackTransmit = remotePort.lastTransmit;
        remotePort.clear();
        assertEquals(APPLIED, remote.onControlTransmitResult(
                ackTransmit,
                ControlTransmitResult.ACCEPTED,
                REQUEST_AT + 4L
        ));
        assertLog(remotePort, "persist:WAITING_LOCAL_TERMINAL", "stopLocal");

        localPort.clear();
        local.onRemoteCloseAck(
                localSource,
                ackTransmit.desiredRole(),
                ackTransmit.wireToken().bytes(),
                REQUEST_AT + 5L
        );
        assertLog(localPort, "persist:WAITING_LOCAL_TERMINAL", "stopLocal");
        local.onLocalTerminal(localSource, REQUEST_AT + 6L);
        local.onLocalOwnerCount(localSource, 0, REQUEST_AT + 7L);
        remote.onLocalTerminal(remoteSource, REQUEST_AT + 6L);
        remote.onLocalOwnerCount(remoteSource, 0, REQUEST_AT + 7L);
        assertEquals(DRAINING, local.state().phase());
        assertEquals(DRAINING, remote.state().phase());

        local.onDrainDeadline(localSource, local.state().drainDeadlineMillis());
        remote.onDrainDeadline(remoteSource, remote.state().drainDeadlineMillis());
        assertEquals(QUIESCENT, local.state().phase());
        assertEquals(QUIESCENT, remote.state().phase());
    }

    @Test
    public void acceptedCStillCannotStopUntilExactInboundA() {
        FakePort port = new FakePort();
        BleRoleSwitchCoordinator coordinator = active(port, new FakeGuard());
        coordinator.requestSwitch(
                HELPER_CENTRAL_ANDROID_PERIPHERAL,
                REQUEST_AT,
                STOP_TIMEOUT,
                DRAIN_DURATION,
                token(81)
        );
        Owner source = coordinator.sourceOwner();
        port.clear();
        coordinator.onIngressFrozen(source, REQUEST_AT + 1L);
        ControlTransmit request = port.lastTransmit;

        port.clear();
        assertEquals(APPLIED, coordinator.onControlTransmitResult(
                request,
                ControlTransmitResult.ACCEPTED,
                REQUEST_AT + 2L
        ));
        assertLog(port, "persist:WAITING_CONTROL_HANDSHAKE");
        assertFalse(coordinator.state().localStopRequested());
        assertFalse(port.log.contains("stopLocal"));

        port.clear();
        assertEquals(APPLIED, coordinator.onRemoteCloseAck(
                source,
                HELPER_CENTRAL_ANDROID_PERIPHERAL,
                token(81).bytes(),
                REQUEST_AT + 3L
        ));
        assertLog(port, "persist:WAITING_LOCAL_TERMINAL", "stopLocal");
        assertTrue(coordinator.state().localStopRequested());
    }

    @Test
    public void retryIsExactAndCannotExtendOriginalStopDeadline() {
        FakePort port = new FakePort();
        BleRoleSwitchCoordinator receiver = active(port, new FakeGuard());
        WireSwitchToken peerToken = token(82);
        receiver.requestSwitchFromRemote(
                receiver.targetOwner(),
                HELPER_CENTRAL_ANDROID_PERIPHERAL,
                peerToken,
                REQUEST_AT,
                STOP_TIMEOUT,
                DRAIN_DURATION
        );
        Owner source = receiver.sourceOwner();
        port.clear();
        receiver.onIngressFrozen(source, REQUEST_AT + 1L);
        ControlTransmit first = port.lastTransmit;
        assertEquals(REQUEST_AT + STOP_TIMEOUT, first.stopDeadlineMillis());

        port.clear();
        assertEquals(APPLIED, receiver.onControlTransmitResult(
                first,
                ControlTransmitResult.RETRYABLE_FAILURE,
                REQUEST_AT + 2L
        ));
        assertLog(port, "persist:WAITING_CONTROL_HANDSHAKE", "scheduleRetry:2");
        ControlTransmit retry = port.scheduledRetry;
        assertEquals(first.stopDeadlineMillis(), retry.stopDeadlineMillis());
        assertEquals(first.attempt().next(), retry.attempt());

        port.clear();
        assertEquals(STALE_CALLBACK, receiver.onControlTransmitResult(
                first,
                ControlTransmitResult.ACCEPTED,
                REQUEST_AT + 3L
        ));
        assertTrue(port.log.isEmpty());

        assertEquals(APPLIED, receiver.onControlTransmitRetry(retry, REQUEST_AT + 4L));
        assertLog(port, "persist:WAITING_CONTROL_HANDSHAKE", "sendA:2");
        ControlTransmit second = port.lastTransmit;

        port.clear();
        assertEquals(APPLIED, receiver.onControlTransmitResult(
                second,
                ControlTransmitResult.ACCEPTED,
                REQUEST_AT + 5L
        ));
        assertLog(port, "persist:WAITING_LOCAL_TERMINAL", "stopLocal");
    }

    @Test
    public void exactDuplicateRemoteCWhileAIsInFlightCoalescesWithoutParallelWrite() {
        FakePort port = new FakePort();
        BleRoleSwitchCoordinator receiver = active(port, new FakeGuard());
        WireSwitchToken peerToken = token(83);
        receiver.requestSwitchFromRemote(
                receiver.targetOwner(),
                HELPER_CENTRAL_ANDROID_PERIPHERAL,
                peerToken,
                REQUEST_AT,
                STOP_TIMEOUT,
                DRAIN_DURATION
        );
        Owner source = receiver.sourceOwner();
        receiver.onIngressFrozen(source, REQUEST_AT + 1L);
        ControlTransmit first = port.lastTransmit;

        port.clear();
        assertEquals(COALESCED, receiver.requestSwitchFromRemote(
                source,
                HELPER_CENTRAL_ANDROID_PERIPHERAL,
                peerToken,
                REQUEST_AT + 2L,
                STOP_TIMEOUT,
                DRAIN_DURATION
        ));
        assertTrue(port.log.isEmpty());
        assertEquals(first, port.lastTransmit);

        port.clear();
        assertEquals(APPLIED, receiver.onControlTransmitResult(
                first,
                ControlTransmitResult.ACCEPTED,
                REQUEST_AT + 3L
        ));
        assertLog(port, "persist:WAITING_LOCAL_TERMINAL", "stopLocal");
    }

    @Test
    public void duplicateRemoteCAfterAcceptedAStillReemitsA() {
        FakePort port = new FakePort();
        BleRoleSwitchCoordinator receiver = active(port, new FakeGuard());
        WireSwitchToken peerToken = token(831);
        receiver.requestSwitchFromRemote(
                receiver.targetOwner(),
                HELPER_CENTRAL_ANDROID_PERIPHERAL,
                peerToken,
                REQUEST_AT,
                STOP_TIMEOUT,
                DRAIN_DURATION
        );
        Owner source = receiver.sourceOwner();
        receiver.onIngressFrozen(source, REQUEST_AT + 1L);
        receiver.onControlTransmitResult(
                port.lastTransmit,
                ControlTransmitResult.ACCEPTED,
                REQUEST_AT + 2L
        );
        assertTrue(receiver.state().localStopRequested());

        port.clear();
        assertEquals(APPLIED, receiver.requestSwitchFromRemote(
                source,
                HELPER_CENTRAL_ANDROID_PERIPHERAL,
                peerToken,
                REQUEST_AT + 3L,
                STOP_TIMEOUT,
                DRAIN_DURATION
        ));
        assertLog(port, "persist:WAITING_LOCAL_TERMINAL", "sendA:2");
        assertTrue(receiver.state().controlTransmitAccepted());
        assertTrue(receiver.state().localStopRequested());
    }

    @Test
    public void exactInboundACancelsPendingCRetryBeforeStop() {
        FakePort port = new FakePort();
        BleRoleSwitchCoordinator coordinator = active(port, new FakeGuard());
        WireSwitchToken switchToken = token(832);
        coordinator.requestSwitch(
                HELPER_CENTRAL_ANDROID_PERIPHERAL,
                REQUEST_AT,
                STOP_TIMEOUT,
                DRAIN_DURATION,
                switchToken
        );
        Owner source = coordinator.sourceOwner();
        coordinator.onIngressFrozen(source, REQUEST_AT + 1L);
        coordinator.onControlTransmitResult(
                port.lastTransmit,
                ControlTransmitResult.RETRYABLE_FAILURE,
                REQUEST_AT + 2L
        );
        ControlTransmit scheduled = port.scheduledRetry;

        port.clear();
        assertEquals(APPLIED, coordinator.onRemoteCloseAck(
                source,
                HELPER_CENTRAL_ANDROID_PERIPHERAL,
                switchToken.bytes(),
                REQUEST_AT + 3L
        ));
        assertLog(
                port,
                "persist:WAITING_LOCAL_TERMINAL",
                "cancelRetry:" + scheduled.attempt(),
                "stopLocal"
        );
        assertEquals(scheduled, port.canceledRetry);
    }

    @Test
    public void retryAtOriginalDeadlineFailsClosed() {
        FakePort port = new FakePort();
        BleRoleSwitchCoordinator receiver = active(port, new FakeGuard());
        receiver.requestSwitchFromRemote(
                receiver.targetOwner(),
                HELPER_CENTRAL_ANDROID_PERIPHERAL,
                token(84),
                REQUEST_AT,
                STOP_TIMEOUT,
                DRAIN_DURATION
        );
        Owner source = receiver.sourceOwner();
        receiver.onIngressFrozen(source, REQUEST_AT + 1L);
        receiver.onControlTransmitResult(
                port.lastTransmit,
                ControlTransmitResult.RETRYABLE_FAILURE,
                REQUEST_AT + 2L
        );
        ControlTransmit retry = port.scheduledRetry;
        port.clear();

        assertEquals(APPLIED, receiver.onControlTransmitRetry(
                retry,
                REQUEST_AT + STOP_TIMEOUT
        ));
        assertLog(port, "persist:FAILED", "fail:" + Failure.STOP_TIMEOUT);
        assertEquals(FAILED, receiver.state().phase());
    }

    @Test
    public void remoteOriginCrashBeforeAndAfterAckReplaysAWithPersistedTokenNeverC() {
        FakePort port = new FakePort();
        BleRoleSwitchCoordinator receiver = BleRoleSwitchCoordinator.active(
                PROCESS_ONE,
                HELPER_PERIPHERAL_ANDROID_CENTRAL,
                Sequence.zero(),
                Sequence.of(1L),
                port,
                new FakeGuard()
        );
        WireSwitchToken peerToken = token(9);
        receiver.requestSwitchFromRemote(
                receiver.targetOwner(),
                HELPER_CENTRAL_ANDROID_PERIPHERAL,
                peerToken,
                REQUEST_AT,
                STOP_TIMEOUT,
                DRAIN_DURATION
        );
        String beforeAck = receiver.encodedSnapshot();

        FakePort beforePort = new FakePort();
        BleRoleSwitchCoordinator beforeRestored = BleRoleSwitchCoordinator.restore(
                PROCESS_TWO,
                beforeAck,
                token(90),
                REQUEST_AT + 10L,
                STOP_TIMEOUT,
                DRAIN_DURATION,
                beforePort,
                new FakeGuard()
        );
        assertEquals(peerToken, beforePort.codec.decode(beforePort.lastSnapshot).wireToken());
        assertEquals(BleRoleSwitchOrigin.REMOTE,
                beforePort.codec.decode(beforePort.lastSnapshot).origin());
        Owner beforeSource = beforeRestored.sourceOwner();
        beforePort.clear();
        beforeRestored.onIngressFrozen(beforeSource, REQUEST_AT + 11L);
        assertLog(beforePort, "persist:WAITING_CONTROL_HANDSHAKE", "sendA:1");
        assertEquals(peerToken, beforePort.sentAckToken);
        assertFalse(hasPrefix(beforePort.log, "sendC:"));
        assertFalse(beforePort.log.contains("stopLocal"));

        beforePort.clear();
        beforeRestored.onControlTransmitResult(
                beforePort.lastTransmit,
                ControlTransmitResult.ACCEPTED,
                REQUEST_AT + 12L
        );
        assertLog(beforePort, "persist:WAITING_LOCAL_TERMINAL", "stopLocal");

        String afterAck = beforeRestored.encodedSnapshot();
        FakePort afterPort = new FakePort();
        BleRoleSwitchCoordinator afterRestored = BleRoleSwitchCoordinator.restore(
                0x3333L,
                afterAck,
                token(91),
                REQUEST_AT + 20L,
                STOP_TIMEOUT,
                DRAIN_DURATION,
                afterPort,
                new FakeGuard()
        );
        Owner afterSource = afterRestored.sourceOwner();
        afterPort.clear();
        afterRestored.onIngressFrozen(afterSource, REQUEST_AT + 21L);
        assertLog(afterPort, "persist:WAITING_CONTROL_HANDSHAKE", "sendA:1");
        assertEquals(peerToken, afterPort.sentAckToken);
        assertFalse(hasPrefix(afterPort.log, "sendC:"));
        assertFalse(afterPort.log.contains("stopLocal"));
    }

    @Test
    public void remoteCThatNamesCurrentActiveRoleIsRejectedWithoutTeardown() {
        FakePort port = new FakePort();
        BleRoleSwitchCoordinator receiver = active(port, new FakeGuard());
        port.clear();
        assertEquals(Outcome.REJECTED_CONFLICT, receiver.requestSwitchFromRemote(
                receiver.targetOwner(),
                HELPER_PERIPHERAL_ANDROID_CENTRAL,
                token(12),
                REQUEST_AT,
                STOP_TIMEOUT,
                DRAIN_DURATION
        ));
        assertEquals(ACTIVE, receiver.state().phase());
        assertTrue(port.log.isEmpty());
    }

    @Test
    public void crashRestoreAtEveryPhaseStartsOnlyFreshDrain() {
        Map<Phase, String> snapshots = snapshotsForEveryPhase();
        assertEquals(
                List.of(ACTIVE, FREEZING, WAITING_CONTROL_HANDSHAKE,
                        WAITING_LOCAL_TERMINAL,
                        DRAINING, QUIESCENT, STARTING, FAILED, CLOSED),
                new ArrayList<>(snapshots.keySet())
        );

        long nonce = 0x3000L;
        int tokenSeed = 20;
        for (Map.Entry<Phase, String> entry : snapshots.entrySet()) {
            if (entry.getKey() == CLOSED) continue;
            FakePort restoredPort = new FakePort();
            BleRoleSwitchCoordinator restored = BleRoleSwitchCoordinator.restore(
                    nonce++,
                    entry.getValue(),
                    token(tokenSeed++),
                    5_000L,
                    STOP_TIMEOUT,
                    DRAIN_DURATION,
                    restoredPort,
                    new FakeGuard()
            );
            assertEquals("crashed phase " + entry.getKey(), FREEZING, restored.state().phase());
            assertLog(restoredPort, "persist:FREEZING", "freeze", "armStop:5100");
            assertFalse("crashed phase " + entry.getKey(), restoredPort.log.contains("startTarget"));
            assertTrue(restored.state().epoch().compareTo(Sequence.zero()) > 0);
        }
    }

    @Test
    public void closedSnapshotRestoresAsShutdownTombstoneAndNeverStartsTarget() {
        String closedSnapshot = snapshotsForEveryPhase().get(CLOSED);
        FakePort restoredPort = new FakePort();
        BleRoleSwitchCoordinator restored = BleRoleSwitchCoordinator.restore(
                0x3999L,
                closedSnapshot,
                token(39),
                5_000L,
                STOP_TIMEOUT,
                DRAIN_DURATION,
                restoredPort,
                new FakeGuard()
        );

        assertEquals(CLOSED, restored.state().phase());
        assertTrue(restoredPort.log.contains("closeAll"));
        assertFalse(restoredPort.log.contains("startTarget"));
        assertEquals(BleRoleSwitchReducer.Outcome.REJECTED_TERMINAL,
                restored.requestSwitch(
                        HELPER_CENTRAL_ANDROID_PERIPHERAL,
                        5_001L,
                        STOP_TIMEOUT,
                        DRAIN_DURATION,
                        token(40)));
    }

    @Test
    public void restoredProcessNeedsFreshZeroOwnerAndRemoteEvidenceBeforeStart() {
        FakePort originalPort = new FakePort();
        BleRoleSwitchCoordinator original = active(originalPort, new FakeGuard());
        String activeSnapshot = original.encodedSnapshot();

        FakePort restoredPort = new FakePort();
        BleRoleSwitchCoordinator restored = BleRoleSwitchCoordinator.restore(
                PROCESS_TWO,
                activeSnapshot,
                token(40),
                REQUEST_AT,
                STOP_TIMEOUT,
                DRAIN_DURATION,
                restoredPort,
                new FakeGuard()
        );
        Owner source = restored.sourceOwner();
        restoredPort.clear();
        restored.onIngressFrozen(source, REQUEST_AT + 1L);
        restored.onLocalTerminal(source, REQUEST_AT + 2L);
        assertEquals(WAITING_LOCAL_TERMINAL, restored.state().phase());
        assertEquals(
                BleRoleSwitchReducer.RemoteCloseEvidence.PEER_SAME_ROLE_RETAINED,
                restored.state().remoteCloseEvidence()
        );
        assertFalse(restoredPort.log.contains("startTarget"));

        restored.onLocalOwnerCount(source, 0, REQUEST_AT + 3L);
        assertEquals(DRAINING, restored.state().phase());
        assertFalse(restoredPort.log.contains("startTarget"));

        restored.onDrainDeadline(source, restored.state().drainDeadlineMillis());
        assertEquals(QUIESCENT, restored.state().phase());
        assertFalse(restoredPort.log.contains("startTarget"));

        Owner target = restored.targetOwner();
        restored.beginTargetStart(target);
        assertEquals(STARTING, restored.state().phase());
        assertTrue(restoredPort.log.contains("startTarget"));
    }

    @Test
    public void restoringStartingOrTargetFailureDrainsTargetLocallyWithoutSameRoleWireFrame() {
        Map<Phase, String> snapshots = snapshotsForEveryPhase();
        long nonce = 0x4a00L;
        for (Phase crashed : List.of(STARTING, FAILED)) {
            FakePort port = new FakePort();
            BleRoleSwitchCoordinator restored = BleRoleSwitchCoordinator.restore(
                    nonce++,
                    snapshots.get(crashed),
                    token(85 + crashed.ordinal()),
                    6_000L,
                    STOP_TIMEOUT,
                    DRAIN_DURATION,
                    port,
                    new FakeGuard()
            );
            BleRoleSwitchSnapshotCodec.Snapshot snapshot = port.codec.decode(port.lastSnapshot);
            assertEquals(BleRoleSwitchOrigin.LOCAL_ONLY_RESTORE, snapshot.origin());
            assertEquals(snapshot.sourceRole(), snapshot.desiredRole());

            Owner source = restored.sourceOwner();
            port.clear();
            assertEquals(APPLIED, restored.onIngressFrozen(source, 6_001L));
            assertLog(port, "persist:WAITING_LOCAL_TERMINAL", "stopLocal");
            assertFalse(hasPrefix(port.log, "sendC:"));
            assertFalse(hasPrefix(port.log, "sendA:"));
        }
    }

    @Test
    public void restorationRejectsReusedProcessNonce() {
        FakePort port = new FakePort();
        BleRoleSwitchCoordinator coordinator = active(port, new FakeGuard());
        try {
            BleRoleSwitchCoordinator.restore(
                    PROCESS_ONE,
                    coordinator.encodedSnapshot(),
                    token(50),
                    REQUEST_AT,
                    STOP_TIMEOUT,
                    DRAIN_DURATION,
                    new FakePort(),
                    new FakeGuard()
            );
            fail("reused process nonce accepted");
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    @Test
    public void snapshotCodecRoundTripsActiveAndDrainAndRejectsCorruption() {
        BleRoleSwitchSnapshotCodec codec = new BleRoleSwitchSnapshotCodec();
        FakePort port = new FakePort();
        BleRoleSwitchCoordinator coordinator = active(port, new FakeGuard());
        String active = coordinator.encodedSnapshot();
        assertEquals(active, codec.encode(codec.decode(active)));

        coordinator.requestSwitch(
                HELPER_CENTRAL_ANDROID_PERIPHERAL,
                REQUEST_AT,
                STOP_TIMEOUT,
                DRAIN_DURATION,
                token(60)
        );
        String drain = coordinator.encodedSnapshot();
        assertEquals(drain, codec.encode(codec.decode(drain)));
        assertTrue(drain.contains(token(60).hex()));

        try {
            codec.decode(drain.replace("BRS2", "BRS0"));
            fail("unknown snapshot version accepted");
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    @Test
    public void wireTokenIsDefensivelyCopiedAndStrictlyDecoded() {
        byte[] input = tokenBytes(70);
        WireSwitchToken token = new WireSwitchToken(input);
        byte original = token.bytes()[0];
        input[0] ^= 0x7f;
        assertEquals(original, token.bytes()[0]);
        byte[] returned = token.bytes();
        returned[0] ^= 0x7f;
        assertEquals(original, token.bytes()[0]);
        assertEquals(token, WireSwitchToken.fromHex(token.hex()));
        assertFalse(token.toString().contains(token.hex()));
        assertNotEquals(token, token(71));
    }

    @Test
    public void serializedGuardIsCheckedBeforeAnyOperation() {
        FakeGuard guard = new FakeGuard();
        FakePort port = new FakePort();
        BleRoleSwitchCoordinator coordinator = active(port, guard);
        guard.allowed = false;
        port.clear();
        try {
            coordinator.state();
            fail("off-executor access accepted");
        } catch (IllegalStateException expected) {
            assertEquals("wrong executor", expected.getMessage());
        }
        assertTrue(port.log.isEmpty());
    }

    @Test
    public void neutralRoleMappingMatchesV2ModeContractWithoutProductionDependency() {
        assertEquals(
                IphoneBleMode.ANDROID_CENTRAL.wireId,
                BleRoleSwitchCoordinator.modeWireId(HELPER_PERIPHERAL_ANDROID_CENTRAL)
        );
        assertEquals(
                IphoneBleMode.ANDROID_CENTRAL.stableKey,
                BleRoleSwitchCoordinator.modeStableKey(HELPER_PERIPHERAL_ANDROID_CENTRAL)
        );
        assertEquals(
                IphoneBleMode.ANDROID_PERIPHERAL.wireId,
                BleRoleSwitchCoordinator.modeWireId(HELPER_CENTRAL_ANDROID_PERIPHERAL)
        );
        assertEquals(
                IphoneBleMode.ANDROID_PERIPHERAL.stableKey,
                BleRoleSwitchCoordinator.modeStableKey(HELPER_CENTRAL_ANDROID_PERIPHERAL)
        );
    }

    private Map<Phase, String> snapshotsForEveryPhase() {
        Map<Phase, String> snapshots = new EnumMap<>(Phase.class);
        FakePort port = new FakePort();
        BleRoleSwitchCoordinator coordinator = active(port, new FakeGuard());
        snapshots.put(ACTIVE, coordinator.encodedSnapshot());

        coordinator.requestSwitch(
                HELPER_CENTRAL_ANDROID_PERIPHERAL,
                REQUEST_AT,
                STOP_TIMEOUT,
                DRAIN_DURATION,
                token(10)
        );
        snapshots.put(FREEZING, coordinator.encodedSnapshot());
        Owner source = coordinator.sourceOwner();

        coordinator.onIngressFrozen(source, REQUEST_AT + 1L);
        snapshots.put(WAITING_CONTROL_HANDSHAKE, coordinator.encodedSnapshot());
        coordinator.onRemoteCloseAck(
                source,
                HELPER_CENTRAL_ANDROID_PERIPHERAL,
                token(10).bytes(),
                REQUEST_AT + 2L
        );
        snapshots.put(WAITING_LOCAL_TERMINAL, coordinator.encodedSnapshot());
        coordinator.onLocalTerminal(source, REQUEST_AT + 3L);
        coordinator.onLocalOwnerCount(source, 0, REQUEST_AT + 4L);
        snapshots.put(DRAINING, coordinator.encodedSnapshot());
        coordinator.onDrainDeadline(source, coordinator.state().drainDeadlineMillis());
        snapshots.put(QUIESCENT, coordinator.encodedSnapshot());
        Owner target = coordinator.targetOwner();
        coordinator.beginTargetStart(target);
        snapshots.put(STARTING, coordinator.encodedSnapshot());

        coordinator.onTargetStartFailed(target);
        snapshots.put(FAILED, coordinator.encodedSnapshot());

        FakePort closePort = new FakePort();
        BleRoleSwitchCoordinator closing = active(closePort, new FakeGuard());
        closing.close(token(11));
        snapshots.put(CLOSED, closing.encodedSnapshot());
        return snapshots;
    }

    private static BleRoleSwitchCoordinator active(FakePort port, FakeGuard guard) {
        return BleRoleSwitchCoordinator.active(
                PROCESS_ONE,
                HELPER_PERIPHERAL_ANDROID_CENTRAL,
                Sequence.zero(),
                Sequence.of(1L),
                port,
                guard
        );
    }

    private static WireSwitchToken token(int seed) {
        return new WireSwitchToken(tokenBytes(seed));
    }

    private static byte[] tokenBytes(int seed) {
        byte[] bytes = new byte[BleRoleSwitchCoordinator.WIRE_TOKEN_BYTES];
        for (int index = 0; index < bytes.length; index++) {
            bytes[index] = (byte) (seed + index + 1);
        }
        return bytes;
    }

    private static void assertLog(FakePort port, String... expected) {
        assertEquals(Arrays.asList(expected), port.log);
        assertFalse(port.log.isEmpty());
        assertTrue("persist must precede every effect", port.log.get(0).startsWith("persist:"));
    }

    private static boolean hasPrefix(List<String> values, String prefix) {
        for (String value : values) {
            if (value.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static final class FakeGuard implements SerializedExecutionGuard {
        boolean allowed = true;
        int assertionCount;

        @Override
        public void assertOnSerializedExecutor() {
            assertionCount++;
            if (!allowed) {
                throw new IllegalStateException("wrong executor");
            }
        }
    }

    private static final class FakePort implements EffectsPort {
        final List<String> log = new ArrayList<>();
        final BleRoleSwitchSnapshotCodec codec = new BleRoleSwitchSnapshotCodec();
        boolean failPersistence;
        String lastSnapshot;
        Role sentDesiredRole;
        WireSwitchToken sentToken;
        WireSwitchToken sentAckToken;
        Role sentAckDesiredRole;
        ControlTransmit lastTransmit;
        ControlTransmit scheduledRetry;
        ControlTransmit canceledRetry;

        void clear() {
            log.clear();
        }

        @Override
        public void persistSnapshot(String encodedSnapshot) {
            if (failPersistence) {
                log.add("persistAttempt");
                throw new IllegalStateException("persist failed");
            }
            lastSnapshot = encodedSnapshot;
            Phase phase = codec.decode(encodedSnapshot).phase();
            log.add("persist:" + phase);
        }

        @Override public void freezeSourceIngress(Owner source) { log.add("freeze"); }

        @Override public void armStopTimeout(Owner source, long deadlineMillis) {
            log.add("armStop:" + deadlineMillis);
        }

        @Override public void stopLocalSource(Owner source) { log.add("stopLocal"); }

        @Override
        public void transmitControl(ControlTransmit transmit) {
            lastTransmit = transmit;
            if (transmit.frame() == ControlFrame.CLOSE_REQUEST) {
                sentDesiredRole = transmit.desiredRole();
                sentToken = transmit.wireToken();
                log.add("sendC:" + transmit.attempt());
            } else {
                sentAckDesiredRole = transmit.desiredRole();
                sentAckToken = transmit.wireToken();
                log.add("sendA:" + transmit.attempt());
            }
        }

        @Override
        public void scheduleControlTransmitRetry(ControlTransmit transmit) {
            scheduledRetry = transmit;
            log.add("scheduleRetry:" + transmit.attempt());
        }

        @Override
        public void cancelControlTransmitRetry(ControlTransmit transmit) {
            canceledRetry = transmit;
            log.add("cancelRetry:" + transmit.attempt());
        }

        @Override public void verifyLocalOwners(Owner source) { log.add("verifyOwners"); }

        @Override public void cancelStopTimeout(Owner source) { log.add("cancelStop"); }

        @Override public void armDrainDeadline(Owner source, long deadlineMillis) {
            log.add("armDrain:" + deadlineMillis);
        }

        @Override public void cancelDrainDeadline(Owner source) { log.add("cancelDrain"); }

        @Override public void quiescentReached(Owner target) { log.add("quiescent"); }

        @Override public void startTarget(Owner target) { log.add("startTarget"); }

        @Override public void targetActive(Owner target) { log.add("targetActive"); }

        @Override public void failClosed(Owner owner, Failure failure) { log.add("fail:" + failure); }

        @Override public void closeAll(Owner owner) { log.add("closeAll"); }
    }
}
