/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone.transport.v2;

import static dezz.status.widget.phone.transport.switching.BleRoleSwitchReducer.ControlTransmitResult.ACCEPTED;
import static dezz.status.widget.phone.transport.switching.BleRoleSwitchReducer.Phase.ACTIVE;
import static dezz.status.widget.phone.transport.switching.BleRoleSwitchReducer.Phase.FAILED;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import dezz.status.widget.phone.transport.switching.BleRoleSwitchCoordinator.ControlTransmit;
import dezz.status.widget.phone.transport.switching.BleRoleSwitchCoordinator.Owner;
import dezz.status.widget.phone.transport.switching.BleRoleSwitchReducer.ControlFrame;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.junit.Test;

public final class IphoneDualTransportRuntimeV2Test {
    private static final long PROCESS = 0x1234L;

    @Test public void absentSnapshotRunsDrainOnlyMigrationBeforeFreshTarget() {
        Fixture fixture = new Fixture(false, "");
        fixture.start(IphoneBleMode.ANDROID_CENTRAL);

        assertEquals(1, fixture.factory.created.size());
        FakeTransport drain = fixture.factory.created.get(0);
        assertTrue(drain.preparedRestoration);
        assertEquals(0, drain.startCount);
        assertEquals(1, drain.stopCount);

        fixture.scheduler.advanceBy(10L);
        assertEquals(2, fixture.factory.created.size());
        FakeTransport active = fixture.factory.created.get(1);
        assertFalse(active.preparedRestoration);
        assertEquals(1, active.startCount);
        assertEquals(IphoneBleMode.ANDROID_CENTRAL, active.mode());
        assertEquals(ACTIVE, fixture.listener.lastDual.switchPhase);
        assertEquals(IphoneBleMode.ANDROID_CENTRAL, fixture.listener.lastDual.activeMode);
        assertTrue(fixture.store.hasSwitchSnapshot());
    }

    @Test public void connectedSwitchWaitsExactAckTerminalZeroAndDrainBeforeNewRoute() {
        Fixture fixture = new Fixture(false, "");
        fixture.start(IphoneBleMode.ANDROID_CENTRAL);
        fixture.scheduler.advanceBy(10L);
        FakeTransport source = fixture.factory.created.get(1);
        source.remoteControl = true;

        fixture.runtime.requestMode(IphoneBleMode.ANDROID_PERIPHERAL);
        fixture.scheduler.drain();
        assertNotNull(source.lastTransmit);
        assertEquals(ControlFrame.CLOSE_REQUEST, source.lastTransmit.frame());
        assertEquals(2, fixture.factory.created.size());

        source.deliverControl(new IphoneRoleControlV2(
                IphoneRoleControlV2.Type.CLOSE_ACK,
                IphoneBleMode.ANDROID_PERIPHERAL,
                source.lastTransmit.wireToken().bytes()));
        fixture.scheduler.drain();
        assertEquals(1, source.stopCount);
        assertTrue(source.terminalDelivered);
        assertEquals(2, fixture.factory.created.size());

        fixture.scheduler.advanceBy(10L);
        assertEquals(3, fixture.factory.created.size());
        FakeTransport target = fixture.factory.created.get(2);
        assertEquals(IphoneBleMode.ANDROID_PERIPHERAL, target.mode());
        assertEquals(1, target.startCount);
        assertEquals(ACTIVE, fixture.listener.lastDual.switchPhase);
        assertEquals(IphoneBleMode.ANDROID_PERIPHERAL, fixture.listener.lastDual.activeMode);
        assertEquals(1, source.closeCount);
    }

    @Test public void typedNoRemoteSwitchSkipsControlAndStillNeverOverlaps() {
        Fixture fixture = new Fixture(false, "");
        fixture.start(IphoneBleMode.ANDROID_CENTRAL);
        fixture.scheduler.advanceBy(10L);
        FakeTransport source = fixture.factory.created.get(1);
        source.remoteControl = false;
        source.ownerCount = 0;

        fixture.runtime.requestMode(IphoneBleMode.ANDROID_PERIPHERAL);
        fixture.scheduler.drain();
        assertNull(source.lastTransmit);
        assertEquals(1, source.stopCount);
        assertEquals(2, fixture.factory.created.size());

        fixture.scheduler.advanceBy(10L);
        assertEquals(3, fixture.factory.created.size());
        assertEquals(IphoneBleMode.ANDROID_PERIPHERAL,
                fixture.factory.created.get(2).mode());
    }

    @Test public void ordinaryTerminalUsesSameRoleFreshGenerationWithoutControl() {
        Fixture fixture = new Fixture(false, "");
        fixture.start(IphoneBleMode.ANDROID_CENTRAL);
        fixture.scheduler.advanceBy(10L);
        FakeTransport source = fixture.factory.created.get(1);

        source.deliverOrdinaryTerminal();
        fixture.scheduler.drain();
        assertNull(source.lastTransmit);
        assertEquals(2, fixture.factory.created.size());
        fixture.scheduler.advanceBy(10L);

        assertEquals(3, fixture.factory.created.size());
        FakeTransport replacement = fixture.factory.created.get(2);
        assertEquals(source.mode(), replacement.mode());
        assertFalse(source.startRequest.epoch.equals(replacement.startRequest.epoch));
        assertEquals(ACTIVE, fixture.listener.lastDual.switchPhase);
    }

    @Test public void presentCorruptSnapshotFailsClosedAndStartsNothing() {
        Fixture fixture = new Fixture(true, "not-a-BRS2-snapshot");
        fixture.start(IphoneBleMode.ANDROID_CENTRAL);

        assertTrue(fixture.factory.created.isEmpty());
        assertNotNull(fixture.listener.lastError);
        assertEquals(FAILED, fixture.listener.lastDual.switchPhase);
        assertNull(fixture.listener.lastDual.activeMode);
    }

    @Test public void radioOffDrainsAndRadioOnStartsFreshSameRoleOwner() {
        Fixture fixture = new Fixture(false, "");
        fixture.start(IphoneBleMode.ANDROID_CENTRAL);
        fixture.scheduler.advanceBy(10L);
        FakeTransport first = fixture.factory.created.get(1);

        fixture.runtime.radioChanged(false);
        fixture.scheduler.drain();
        assertTrue(first.terminalDelivered);
        fixture.scheduler.advanceBy(10L);
        assertEquals(2, fixture.factory.created.size());

        fixture.runtime.radioChanged(true);
        fixture.scheduler.drain();
        assertEquals(3, fixture.factory.created.size());
        FakeTransport replacement = fixture.factory.created.get(2);
        assertEquals(first.mode(), replacement.mode());
        assertFalse(first.startRequest.epoch.equals(replacement.startRequest.epoch));
        assertEquals(ACTIVE, fixture.listener.lastDual.switchPhase);
    }

    @Test public void ordinaryControllerCloseRetainsDurableRestorationIntent() {
        Fixture fixture = new Fixture(false, "");
        fixture.start(IphoneBleMode.ANDROID_CENTRAL);
        fixture.scheduler.advanceBy(10L);
        String before = fixture.store.snapshot;
        FakeTransport active = fixture.factory.created.get(1);

        fixture.runtime.close();
        fixture.scheduler.drain();

        assertEquals(before, fixture.store.snapshot);
        assertEquals(1, active.closeCount);
    }

    @Test public void permanentCloseIsAnExplicitDurableTombstone() {
        Fixture fixture = new Fixture(false, "");
        fixture.start(IphoneBleMode.ANDROID_CENTRAL);
        fixture.scheduler.advanceBy(10L);

        fixture.runtime.closePermanently();
        fixture.scheduler.drain();

        assertTrue(fixture.store.snapshot.contains("CLOSED"));
        assertEquals(1, fixture.factory.created.get(1).closeCount);
    }

    @Test public void postInitializePersistenceFailureIsContainedAndClosesExactOwner() {
        Fixture fixture = new Fixture(false, "");
        fixture.start(IphoneBleMode.ANDROID_CENTRAL);
        fixture.scheduler.advanceBy(10L);
        FakeTransport active = fixture.factory.created.get(1);
        fixture.store.failSwitchCommit = true;

        fixture.runtime.requestMode(IphoneBleMode.ANDROID_PERIPHERAL);
        fixture.scheduler.drain();

        assertNotNull(fixture.listener.lastError);
        assertFalse(fixture.listener.lastError.retryable);
        assertEquals(IphoneTransportErrorV2.Kind.TEARDOWN,
                fixture.listener.lastError.kind);
        assertEquals(FAILED, fixture.listener.lastDual.switchPhase);
        assertEquals(1, active.closeCount);
    }

    @Test public void helperIdentityOfferCompletesOnlyAfterDurableCommitAndReread() {
        Fixture fixture = new Fixture(false, "");
        fixture.start(IphoneBleMode.ANDROID_CENTRAL);
        fixture.scheduler.advanceBy(10L);
        FakeTransport active = fixture.factory.created.get(1);

        active.offerHelper("8f04fe8d-11c2-4b3a-9ab7-f4512aa2a21d");
        fixture.scheduler.drain();

        assertTrue(active.identityAccepted);
        assertEquals("8f04fe8d-11c2-4b3a-9ab7-f4512aa2a21d", fixture.store.helperId);
        assertEquals(fixture.store.helperId, fixture.listener.learnedHelperId);
    }

    @Test public void conflictingInFlightRoleRequestDoesNotOverwriteCommittedTarget() {
        Fixture fixture = new Fixture(false, "");
        fixture.start(IphoneBleMode.ANDROID_CENTRAL);
        fixture.scheduler.advanceBy(10L);
        fixture.factory.created.get(1).remoteControl = true;

        fixture.runtime.requestMode(IphoneBleMode.ANDROID_PERIPHERAL);
        fixture.scheduler.drain();
        fixture.runtime.requestMode(IphoneBleMode.ANDROID_CENTRAL);
        fixture.scheduler.drain();

        assertEquals(IphoneBleMode.ANDROID_PERIPHERAL,
                fixture.listener.lastDual.desiredMode);
        assertEquals(2, fixture.factory.created.size());
    }

    @Test public void modeRequestQueuedBeforeStartBecomesInitialRestorationRole() {
        Fixture fixture = new Fixture(false, "");

        fixture.runtime.requestMode(IphoneBleMode.ANDROID_PERIPHERAL);
        fixture.runtime.start(new IphoneDualTransportRuntimeV2.Config(
                "11:22:33:44:55:66",
                IphoneBleMode.ANDROID_CENTRAL,
                true,
                true), fixture.listener);
        fixture.scheduler.drain();

        assertEquals(1, fixture.factory.created.size());
        assertEquals(IphoneBleMode.ANDROID_PERIPHERAL,
                fixture.factory.created.get(0).mode());
        fixture.scheduler.advanceBy(10L);
        assertEquals(IphoneBleMode.ANDROID_PERIPHERAL,
                fixture.factory.created.get(1).mode());
        assertEquals(IphoneBleMode.ANDROID_PERIPHERAL,
                fixture.listener.lastDual.desiredMode);
    }

    @Test public void radioOffQueuedBeforeStartCannotStartAPlatformOwner() {
        Fixture fixture = new Fixture(false, "");

        fixture.runtime.radioChanged(false);
        fixture.runtime.start(new IphoneDualTransportRuntimeV2.Config(
                "11:22:33:44:55:66",
                IphoneBleMode.ANDROID_CENTRAL,
                true,
                true), fixture.listener);
        fixture.scheduler.drain();

        assertEquals(1, fixture.factory.created.size());
        assertTrue(fixture.factory.created.get(0).preparedRestoration);
        fixture.scheduler.advanceBy(10L);
        // The restoration owner was drained, but radio=false prevents a target adapter from
        // being constructed.  A later radio-on event is the sole start trigger.
        assertEquals(1, fixture.factory.created.size());

        fixture.runtime.radioChanged(true);
        fixture.scheduler.drain();
        assertEquals(2, fixture.factory.created.size());
        assertEquals(1, fixture.factory.created.get(1).startCount);
    }

    private static final class Fixture {
        final FakeScheduler scheduler = new FakeScheduler();
        final FakeStore store;
        final FakeFactory factory = new FakeFactory(scheduler);
        final FakeListener listener = new FakeListener();
        final IphoneDualTransportRuntimeV2 runtime;

        Fixture(boolean present, String snapshot) {
            store = new FakeStore(present, snapshot);
            runtime = new IphoneDualTransportRuntimeV2(
                    PROCESS, scheduler, store, factory, new SecureRandom(new byte[]{1, 2, 3}),
                    100L, 10L);
        }

        void start(IphoneBleMode mode) {
            runtime.start(new IphoneDualTransportRuntimeV2.Config(
                    "11:22:33:44:55:66", mode, true, true), listener);
            scheduler.drain();
        }
    }

    private static final class FakeScheduler
            implements IphoneDualTransportRuntimeV2.SerializedScheduler {
        private static final class Timer implements IphoneDualTransportRuntimeV2.Cancellable {
            final long deadline;
            final Runnable action;
            boolean canceled;

            Timer(long deadline, Runnable action) {
                this.deadline = deadline;
                this.action = action;
            }

            @Override public void cancel() { canceled = true; }
        }

        final ArrayDeque<Runnable> queue = new ArrayDeque<>();
        final List<Timer> timers = new ArrayList<>();
        long now = 1_000L;
        boolean current;

        @Override public long nowMillis() { return now; }
        @Override public boolean isCurrent() { return current; }
        @Override public void execute(Runnable action) { queue.add(action); }
        @Override public IphoneDualTransportRuntimeV2.Cancellable scheduleAt(
                long deadline, Runnable action) {
            Timer timer = new Timer(deadline, action);
            timers.add(timer);
            return timer;
        }

        void drain() {
            boolean prior = current;
            current = true;
            try {
                while (!queue.isEmpty()) queue.removeFirst().run();
            } finally {
                current = prior;
            }
        }

        void advanceBy(long delta) {
            now += delta;
            timers.sort(Comparator.comparingLong(value -> value.deadline));
            for (Timer timer : new ArrayList<>(timers)) {
                if (!timer.canceled && timer.deadline <= now) {
                    timer.canceled = true;
                    queue.add(timer.action);
                }
            }
            drain();
        }
    }

    private static final class FakeStore implements IphoneDualTransportStateStoreV2 {
        boolean present;
        String snapshot;
        String androidId = "";
        String helperId = "";
        boolean failSwitchCommit;

        FakeStore(boolean present, String snapshot) {
            this.present = present;
            this.snapshot = snapshot;
        }

        @Override public boolean hasSwitchSnapshot() { return present; }
        @Override public String switchSnapshot() { return snapshot; }
        @Override public void persistSwitchSnapshot(String encodedSnapshot) {
            if (failSwitchCommit) throw new IllegalStateException("durable write failed");
            present = true;
            snapshot = encodedSnapshot;
        }
        @Override public String androidInstallationId() { return androidId; }
        @Override public boolean commitAndroidInstallationId(String value) {
            androidId = value;
            return true;
        }
        @Override public String helperInstallationId() { return helperId; }
        @Override public boolean commitHelperInstallationId(String value) {
            helperId = value;
            return true;
        }
    }

    private static final class FakeFactory
            implements IphoneDualTransportRuntimeV2.TransportFactory {
        final FakeScheduler scheduler;
        final List<FakeTransport> created = new ArrayList<>();

        FakeFactory(FakeScheduler scheduler) { this.scheduler = scheduler; }

        @Override public IphoneSwitchTransportV2 create(
                IphoneBleMode mode, java.util.UUID androidInstallationId) {
            assertNotNull(androidInstallationId);
            FakeTransport transport = new FakeTransport(mode, scheduler);
            created.add(transport);
            return transport;
        }
    }

    private static final class FakeTransport implements IphoneSwitchTransportV2 {
        final IphoneBleMode mode;
        final FakeScheduler scheduler;
        IphoneTransportSessionListenerV2 listener;
        IphoneTransportStartRequest startRequest;
        Owner restorationOwner;
        RestorationDrainCompletion restorationCompletion;
        boolean preparedRestoration;
        boolean remoteControl = true;
        int ownerCount;
        int startCount;
        int stopCount;
        int closeCount;
        boolean terminalDelivered;
        boolean identityAccepted;
        ControlTransmit lastTransmit;

        FakeTransport(IphoneBleMode mode, FakeScheduler scheduler) {
            this.mode = mode;
            this.scheduler = scheduler;
        }

        @Override public IphoneBleMode mode() { return mode; }
        @Override public void radioOff(BleRouteEpoch epoch) {
            if (startRequest != null && startRequest.epoch.equals(epoch)) {
                deliverOrdinaryTerminal();
            }
        }
        @Override public void start(IphoneTransportStartRequest request,
                                    IphoneTransportSessionListenerV2 listener) {
            this.startRequest = request;
            this.listener = listener;
            startCount++;
            ownerCount = 1;
            scheduler.execute(() -> listener.onStatus(new IphoneTransportStatusV2(
                    mode, request.epoch, IphoneTransportLifecycle.READY,
                    request.selectedSystemBondAddress, request.helperInstallationId,
                    "ready", 0)));
        }
        @Override public void stop(BleRouteEpoch epoch, IphoneTransportStopReason reason) {
            ownerCount = 0;
            deliverOrdinaryTerminal();
        }
        @Override public IphoneTransportStatusV2 status() { return null; }
        @Override public void close() { closeCount++; ownerCount = 0; }
        @Override public void prepareRestorationDrain(
                Owner source, RestorationDrainCompletion completion) {
            preparedRestoration = true;
            restorationOwner = source;
            restorationCompletion = completion;
            ownerCount = 0;
            scheduler.execute(() -> completion.onPrepared(source, true));
        }
        @Override public void freezeIngress(Owner source, FreezeCompletion completion) {
            FreezeResult result = remoteControl && ownerCount > 0
                    ? FreezeResult.FROZEN_WITH_REMOTE_CONTROL
                    : FreezeResult.FROZEN_NO_REMOTE_OWNER;
            scheduler.execute(() -> completion.onFrozen(source, result));
        }
        @Override public void transmitControl(
                ControlTransmit transmit, ControlCompletion completion) {
            lastTransmit = transmit;
            scheduler.execute(() -> completion.onComplete(transmit, ACCEPTED));
        }
        @Override public void scheduleControlRetry(ControlTransmit transmit, RetryDue callback) {
            scheduler.execute(() -> callback.onDue(transmit));
        }
        @Override public void cancelControlRetry(ControlTransmit transmit) { }
        @Override public void beginConfirmedModeSwitchStop(Owner source) {
            stopCount++;
            ownerCount = 0;
            scheduler.execute(() -> {
                terminalDelivered = true;
                if (preparedRestoration) restorationCompletion.onLocalTerminal(source);
                else listener.onLocalTerminal(mode, startRequest.epoch);
            });
        }
        @Override public int appOwnedOwnerCount(Owner source) { return ownerCount; }

        void deliverControl(IphoneRoleControlV2 control) {
            scheduler.execute(() -> listener.onRoleControl(control));
        }

        void offerHelper(String helperId) {
            scheduler.execute(() -> listener.offerHelperInstallationId(
                    helperId, accepted -> identityAccepted = accepted));
        }

        void deliverOrdinaryTerminal() {
            ownerCount = 0;
            scheduler.execute(() -> {
                terminalDelivered = true;
                listener.onLocalTerminal(mode, startRequest.epoch);
            });
        }
    }

    private static final class FakeListener implements IphoneDualTransportListenerV2 {
        IphoneDualTransportStatusV2 lastDual;
        IphoneTransportErrorV2 lastError;
        String learnedHelperId = "";
        @Override public void onDualTransportStatus(IphoneDualTransportStatusV2 status) {
            lastDual = status;
        }
        @Override public void onStatus(IphoneTransportStatusV2 status) { }
        @Override public void onTelemetry(IphoneTelemetryV2 telemetry) { }
        @Override public void onNotificationEvent(IphoneNotificationEventV2 event) { }
        @Override public void onNotification(IphoneNotificationV2 notification) { }
        @Override public void onAppName(IphoneAppNameV2 appName) { }
        @Override public void onHelperInstallationIdLearned(String helperInstallationId) {
            learnedHelperId = helperInstallationId;
        }
        @Override public void onRoleControl(IphoneRoleControlV2 control) { }
        @Override public void onRoleControlWriteResult(
                IphoneRoleControlV2 control, boolean success) { }
        @Override public void onLocalTerminal(IphoneBleMode mode, BleRouteEpoch epoch) { }
        @Override public void onError(IphoneTransportErrorV2 error) { lastError = error; }
    }
}
