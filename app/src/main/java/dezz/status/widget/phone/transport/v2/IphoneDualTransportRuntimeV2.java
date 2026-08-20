/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone.transport.v2;

import dezz.status.widget.phone.transport.switching.BleRoleSwitchCoordinator;
import dezz.status.widget.phone.transport.switching.BleRoleSwitchCoordinator.ControlTransmit;
import dezz.status.widget.phone.transport.switching.BleRoleSwitchCoordinator.EffectsPort;
import dezz.status.widget.phone.transport.switching.BleRoleSwitchCoordinator.Owner;
import dezz.status.widget.phone.transport.switching.BleRoleSwitchCoordinator.SerializedExecutionGuard;
import dezz.status.widget.phone.transport.switching.BleRoleSwitchCoordinator.WireSwitchToken;
import dezz.status.widget.phone.transport.switching.BleRoleSwitchReducer.ControlTransmitResult;
import dezz.status.widget.phone.transport.switching.BleRoleSwitchReducer.Failure;
import dezz.status.widget.phone.transport.switching.BleRoleSwitchReducer.Outcome;
import dezz.status.widget.phone.transport.switching.BleRoleSwitchReducer.Phase;
import dezz.status.widget.phone.transport.switching.BleRoleSwitchReducer.Role;
import dezz.status.widget.phone.transport.switching.BleRoleSwitchReducer.Sequence;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Sole owner of the two rewritten Android ANCS transports.
 *
 * <p>The runtime is platform-neutral apart from its injected transports and serialized scheduler.
 * A route adapter is never reused after quiescence.  Process restoration first constructs a
 * drain-only adapter, while a target start always constructs a distinct fresh adapter.</p>
 */
public final class IphoneDualTransportRuntimeV2 implements AutoCloseable, EffectsPort,
        SerializedExecutionGuard {
    public static final long DEFAULT_STOP_TIMEOUT_MS = 20_000L;
    public static final long DEFAULT_DRAIN_DURATION_MS = 500L;

    public interface Cancellable {
        void cancel();
    }

    /** One FIFO used by the coordinator, all adapter callbacks, and every timer callback. */
    public interface SerializedScheduler {
        long nowMillis();

        boolean isCurrent();

        void execute(Runnable action);

        Cancellable scheduleAt(long absoluteDeadlineMillis, Runnable action);
    }

    public interface TransportFactory {
        IphoneSwitchTransportV2 create(IphoneBleMode mode, UUID androidInstallationId);
    }

    public static final class Config {
        public final String selectedSystemBondAddress;
        public final IphoneBleMode desiredMode;
        public final boolean radioEnabled;
        public final boolean explicitBootstrapRequested;
        /** True when the selected-iPhone controller owns bounded top-level recovery timing. */
        public final boolean externallyManagedRecovery;
        /** Route B stays compiled but can be entered only by an explicit diagnostics session. */
        public final boolean allowExperimentalRouteB;

        public Config(String selectedSystemBondAddress, IphoneBleMode desiredMode,
                      boolean radioEnabled, boolean explicitBootstrapRequested) {
            this(selectedSystemBondAddress, desiredMode, radioEnabled,
                    explicitBootstrapRequested, false, true);
        }

        public Config(String selectedSystemBondAddress, IphoneBleMode desiredMode,
                      boolean radioEnabled, boolean explicitBootstrapRequested,
                      boolean externallyManagedRecovery,
                      boolean allowExperimentalRouteB) {
            this.selectedSystemBondAddress = IphoneBleAdvertisement.normalizeSystemBondAddress(
                    selectedSystemBondAddress);
            if (this.selectedSystemBondAddress.isEmpty()) {
                throw new IllegalArgumentException("selected system bond is required");
            }
            this.desiredMode = Objects.requireNonNull(desiredMode, "desiredMode");
            this.radioEnabled = radioEnabled;
            this.explicitBootstrapRequested = explicitBootstrapRequested;
            this.externallyManagedRecovery = externallyManagedRecovery;
            this.allowExperimentalRouteB = allowExperimentalRouteB;
            if (!allowExperimentalRouteB && desiredMode == IphoneBleMode.ANDROID_PERIPHERAL) {
                throw new IllegalArgumentException("Route B requires explicit diagnostics");
            }
        }

        Config withDesiredMode(IphoneBleMode mode) {
            return new Config(selectedSystemBondAddress, mode, radioEnabled,
                    explicitBootstrapRequested, externallyManagedRecovery,
                    allowExperimentalRouteB);
        }

        Config withRadioEnabled(boolean enabled) {
            return new Config(selectedSystemBondAddress, desiredMode, enabled,
                    explicitBootstrapRequested, externallyManagedRecovery,
                    allowExperimentalRouteB);
        }
    }

    private static final class Slot {
        final IphoneSwitchTransportV2 transport;
        final Owner routeOwner;
        final boolean restorationOnly;
        boolean terminalObserved;
        boolean targetFailureReported;
        String targetFailureDetail = "";
        IphoneTransportStatusV2 status;

        Slot(IphoneSwitchTransportV2 transport, Owner routeOwner, boolean restorationOnly) {
            this.transport = transport;
            this.routeOwner = routeOwner;
            this.restorationOnly = restorationOnly;
        }

        boolean matchesRouteGeneration(Owner owner) {
            return owner != null
                    && routeOwner.processNonce() == owner.processNonce()
                    && routeOwner.generation().equals(owner.generation())
                    && routeOwner.role() == owner.role();
        }
    }

    private final long processNonce;
    private final SerializedScheduler scheduler;
    private final IphoneDualTransportStateStoreV2 store;
    private final IphoneBleIdentityRegistryV2 identities;
    private final TransportFactory factory;
    private final SecureRandom random;
    private final long stopTimeoutMillis;
    private final long drainDurationMillis;
    private final Map<Owner, Cancellable> stopTimers = new HashMap<>();
    private final Map<Owner, Cancellable> drainTimers = new HashMap<>();

    private BleRoleSwitchCoordinator coordinator;
    private Config config;
    private IphoneDualTransportListenerV2 listener;
    private Slot slot;
    private UUID androidInstallationId;
    private boolean initialized;
    private boolean closed;
    private boolean poisoned;
    private IphoneBleMode pendingPreInitializeMode;
    private Boolean pendingPreInitializeRadioEnabled;
    private boolean pendingSelectedPhonePresence;
    private String fatalDetail = "";

    public IphoneDualTransportRuntimeV2(
            long processNonce,
            SerializedScheduler scheduler,
            IphoneDualTransportStateStoreV2 store,
            TransportFactory factory,
            SecureRandom random
    ) {
        this(processNonce, scheduler, store, factory, random,
                DEFAULT_STOP_TIMEOUT_MS, DEFAULT_DRAIN_DURATION_MS);
    }

    IphoneDualTransportRuntimeV2(
            long processNonce,
            SerializedScheduler scheduler,
            IphoneDualTransportStateStoreV2 store,
            TransportFactory factory,
            SecureRandom random,
            long stopTimeoutMillis,
            long drainDurationMillis
    ) {
        if (processNonce == 0L) throw new IllegalArgumentException("processNonce is required");
        if (stopTimeoutMillis <= 0L || drainDurationMillis <= 0L) {
            throw new IllegalArgumentException("runtime deadlines must be positive");
        }
        this.processNonce = processNonce;
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.store = Objects.requireNonNull(store, "store");
        this.factory = Objects.requireNonNull(factory, "factory");
        this.random = Objects.requireNonNull(random, "random");
        this.identities = new IphoneBleIdentityRegistryV2(store);
        this.stopTimeoutMillis = stopTimeoutMillis;
        this.drainDurationMillis = drainDurationMillis;
    }

    public void start(Config initialConfig, IphoneDualTransportListenerV2 runtimeListener) {
        Objects.requireNonNull(initialConfig, "initialConfig");
        Objects.requireNonNull(runtimeListener, "runtimeListener");
        enqueue(() -> initialize(initialConfig, runtimeListener));
    }

    public void requestMode(IphoneBleMode desiredMode) {
        Objects.requireNonNull(desiredMode, "desiredMode");
        enqueue(() -> requestModeOnSerialized(desiredMode));
    }

    /** Enqueues one C5 response/state frame on the currently authenticated route owner. */
    public void sendCarRemoteFrame(byte[] frame) {
        if (frame == null || frame.length != IphoneCarRemoteProtocolV1.FRAME_BYTES) return;
        byte[] exact = frame.clone();
        enqueue(() -> {
            if (closed || slot == null || slot.terminalObserved || slot.status == null
                    || slot.status.lifecycle != IphoneTransportLifecycle.READY) return;
            slot.transport.sendCarRemoteFrame(exact);
        });
    }

    /** Requests a fresh owner of the current topology after an attributable route loss. */
    public void requestSameModeRecovery() {
        enqueue(this::requestSameModeRecoveryOnSerialized);
    }

    /**
     * Prompts only the currently active route after the exact selected phone appears on Classic.
     * The route adapter is responsible for treating this as liveness rather than peer evidence.
     */
    public void selectedPhonePresent() {
        enqueue(this::selectedPhonePresentOnSerialized);
    }

    public void radioChanged(boolean enabled) {
        enqueue(() -> {
            if (closed) return;
            if (config == null) {
                if (!initialized) pendingPreInitializeRadioEnabled = enabled;
                return;
            }
            config = config.withRadioEnabled(enabled);
            if (!enabled) {
                pendingSelectedPhonePresence = false;
                if (slot != null) {
                    slot.transport.radioOff(routeEpoch(slot.routeOwner));
                }
                if (coordinator != null && coordinator.state().phase() != Phase.ACTIVE) {
                    Owner source = coordinator.sourceOwner();
                    if (source != null) coordinator.onRadioOrPowerLoss(source, now());
                }
            } else if (coordinator != null) {
                if (coordinator.state().phase() == Phase.STARTING && slot == null) {
                    startTarget(coordinator.targetOwner());
                } else if (!config.externallyManagedRecovery
                        && coordinator.state().phase() == Phase.ACTIVE
                        && (slot == null || slot.terminalObserved)) {
                    requestSameModeRecoveryOnSerialized();
                }
            }
            publishDualStatus("radio=" + enabled);
        });
    }

    public IphoneDualTransportStatusV2 status() {
        if (!scheduler.isCurrent()) return null;
        return snapshotStatus(fatalDetail);
    }

    /**
     * Releases this process-owned facade without changing the durable switch intent.
     *
     * <p>Controller recreation, package replacement, and feature disable/enable are ordinary
     * lifecycle boundaries, not permanent protocol shutdown.  The next process/runtime restores
     * the last write-ahead snapshot and drains any OS owner that outlived this Java facade before
     * starting a fresh generation.</p>
     */
    @Override public void close() {
        enqueue(() -> {
            if (closed) return;
            closed = true;
            cancelAllRuntimeTimers();
            closeSlot();
            publishDualStatus("process facade released; durable restoration retained");
        });
    }

    /** Explicit irreversible protocol tombstone; ordinary controller code must never call it. */
    public void closePermanently() {
        enqueue(() -> {
            if (closed) return;
            closed = true;
            cancelAllRuntimeTimers();
            if (coordinator != null) coordinator.close(newWireToken());
            else closeSlot();
            publishDualStatus("permanently closed");
        });
    }

    private void initialize(Config initialConfig,
                            IphoneDualTransportListenerV2 runtimeListener) {
        assertOnSerializedExecutor();
        if (initialized || closed) return;
        initialized = true;
        IphoneBleMode initialMode = pendingPreInitializeMode == null
                ? initialConfig.desiredMode : pendingPreInitializeMode;
        boolean suppressedPendingRouteB = !initialConfig.allowExperimentalRouteB
                && initialMode == IphoneBleMode.ANDROID_PERIPHERAL;
        if (suppressedPendingRouteB) {
            initialMode = IphoneBleMode.ANDROID_CENTRAL;
        }
        boolean initialRadioEnabled = pendingPreInitializeRadioEnabled == null
                ? initialConfig.radioEnabled : pendingPreInitializeRadioEnabled;
        pendingPreInitializeMode = null;
        pendingPreInitializeRadioEnabled = null;
        config = new Config(
                initialConfig.selectedSystemBondAddress,
                initialMode,
                initialRadioEnabled,
                initialConfig.explicitBootstrapRequested,
                initialConfig.externallyManagedRecovery,
                initialConfig.allowExperimentalRouteB);
        listener = runtimeListener;
        try {
            androidInstallationId = identities.loadOrCreateAndroidIdentity(random);
            if (store.hasSwitchSnapshot()) {
                String encoded = store.switchSnapshot();
                if (encoded == null || encoded.trim().isEmpty()) {
                    throw new IllegalStateException("present v2 switch snapshot is empty");
                }
                coordinator = BleRoleSwitchCoordinator.restore(
                        processNonce,
                        encoded,
                        newWireToken(),
                        now(),
                        stopTimeoutMillis,
                        drainDurationMillis,
                        this,
                        this
                );
            } else {
                /* Upgrade and first install use the same safe boundary.  A synthetic prior ACTIVE
                 * snapshot is committed first, then restored under this process nonce. */
                Role legacyRole = role(config.desiredMode);
                long migrationNonce = distinctMigrationNonce(processNonce);
                BleRoleSwitchCoordinator migration = BleRoleSwitchCoordinator.active(
                        migrationNonce,
                        legacyRole,
                        Sequence.zero(),
                        Sequence.of(1L),
                        this,
                        this
                );
                coordinator = BleRoleSwitchCoordinator.restore(
                        processNonce,
                        migration.encodedSnapshot(),
                        newWireToken(),
                        now(),
                        stopTimeoutMillis,
                        drainDurationMillis,
                        this,
                        this
                );
            }
            publishDualStatus(suppressedPendingRouteB
                    ? "pre-start Route B request rejected; production Route A drain started"
                    : "restoration drain started");
        } catch (RuntimeException error) {
            failInitialization("v2 state unavailable: " + safeMessage(error));
        }
    }

    private void requestModeOnSerialized(IphoneBleMode desiredMode) {
        assertOnSerializedExecutor();
        if (closed) return;
        if (desiredMode == IphoneBleMode.ANDROID_PERIPHERAL
                && config != null && !config.allowExperimentalRouteB) {
            publishDualStatus("Route B rejected: explicit diagnostics disabled");
            return;
        }
        if (config == null || coordinator == null) {
            if (!initialized) pendingPreInitializeMode = desiredMode;
            return;
        }
        Outcome outcome = coordinator.requestSwitch(
                role(desiredMode),
                now(),
                stopTimeoutMillis,
                drainDurationMillis,
                newWireToken()
        );
        if (outcome == Outcome.APPLIED || outcome == Outcome.COALESCED) {
            config = config.withDesiredMode(desiredMode);
        }
        publishDualStatus("mode request " + desiredMode + ": " + outcome);
    }

    private void requestSameModeRecoveryOnSerialized() {
        assertOnSerializedExecutor();
        if (closed || coordinator == null) return;
        Outcome outcome;
        if (coordinator.state().phase() == Phase.FAILED) {
            if (!coordinator.canRetryFailed()) {
                publishDualStatus("same-mode recovery: " + Outcome.REJECTED_TERMINAL);
                return;
            }
            // Keep an attributable source slot attached: retryFailed() emits a fresh-epoch
            // freeze and freezeSourceIngress() must drain that exact framework owner when it is
            // still present. A failed target was already detached by failBoundTarget(), so that
            // case naturally constructs a restoration-only drain adapter instead.
            cancelAllRuntimeTimers();
            outcome = coordinator.retryFailed(
                    now(), stopTimeoutMillis, drainDurationMillis, newWireToken());
        } else if (coordinator.state().phase() == Phase.ACTIVE) {
            outcome = coordinator.requestSameRoleRestart(
                    now(), stopTimeoutMillis, drainDurationMillis, newWireToken());
        } else {
            return;
        }
        publishDualStatus("same-mode recovery: " + outcome);
    }

    private void selectedPhonePresentOnSerialized() {
        assertOnSerializedExecutor();
        if (closed) return;
        pendingSelectedPhonePresence = true;
        forwardSelectedPhonePresenceIfPossible();
    }

    /**
     * Delivers the Classic liveness hint only to the sole fresh target.  The hint may arrive
     * while process-restoration drain or startup quiet is still in progress, so it is retained
     * until that exact target slot exists; it is never sent to a restoration-only adapter.
     */
    private void forwardSelectedPhonePresenceIfPossible() {
        if (!pendingSelectedPhonePresence || coordinator == null || slot == null
                || slot.restorationOnly || slot.terminalObserved) return;
        Phase phase = coordinator.state().phase();
        if (phase != Phase.STARTING && phase != Phase.ACTIVE) return;
        Owner target = coordinator.targetOwner();
        if (!slot.matchesRouteGeneration(target)) return;
        forwardSelectedPhonePresenceTo(slot);
    }

    private void forwardSelectedPhonePresenceTo(Slot exact) {
        if (!pendingSelectedPhonePresence || exact == null || exact.restorationOnly
                || exact.terminalObserved || slot != exact) return;
        pendingSelectedPhonePresence = false;
        exact.transport.selectedPhonePresent();
    }

    @Override public void persistSnapshot(String encodedSnapshot) {
        assertOnSerializedExecutor();
        store.persistSwitchSnapshot(encodedSnapshot);
    }

    @Override public void freezeSourceIngress(Owner source) {
        assertOnSerializedExecutor();
        Slot exact = findSlot(source);
        if (exact != null) {
            freezePreparedSlot(exact, source);
            return;
        }
        IphoneSwitchTransportV2 drain;
        try {
            drain = factory.create(mode(source.role()), androidInstallationId);
        } catch (RuntimeException error) {
            postFreezeFailed(source);
            return;
        }
        Slot restored = new Slot(drain, source, true);
        slot = restored;
        drain.prepareRestorationDrain(source,
                new IphoneSwitchTransportV2.RestorationDrainCompletion() {
                    @Override public void onPrepared(Owner exactOwner, boolean success) {
                        enqueue(() -> {
                            if (!isCurrentSlot(restored, exactOwner)) return;
                            if (!success) {
                                coordinator.onIngressFreezeFailed(exactOwner);
                                publishDualStatus("restoration drain prepare failed");
                                return;
                            }
                            freezePreparedSlot(restored, exactOwner);
                        });
                    }

                    @Override public void onLocalTerminal(Owner exactOwner) {
                        enqueue(() -> handleLocalTerminal(restored, exactOwner));
                    }
                });
    }

    private void freezePreparedSlot(Slot exact, Owner source) {
        exact.transport.freezeIngress(source, (exactOwner, result) -> enqueue(() -> {
            if (!isCurrentSlot(exact, exactOwner) || coordinator == null) return;
            if (result == IphoneSwitchTransportV2.FreezeResult.FAILED) {
                coordinator.onIngressFreezeFailed(exactOwner);
            } else if (result == IphoneSwitchTransportV2.FreezeResult.FROZEN_NO_REMOTE_OWNER) {
                coordinator.onIngressFrozenWithoutRemoteOwner(exactOwner, now());
            } else {
                coordinator.onIngressFrozen(exactOwner, now());
            }
            if (exact.terminalObserved && coordinator.state().phase() != Phase.FAILED
                    && coordinator.state().phase() != Phase.CLOSED) {
                coordinator.onLocalTerminal(exactOwner, now());
            }
            publishDualStatus("source ingress " + result);
        }));
    }

    private void postFreezeFailed(Owner source) {
        enqueue(() -> {
            if (coordinator != null) coordinator.onIngressFreezeFailed(source);
        });
    }

    @Override public void armStopTimeout(Owner source, long deadlineMillis) {
        replaceTimer(stopTimers, source, deadlineMillis,
                () -> coordinator.onStopTimeout(source, now()));
    }

    @Override public void stopLocalSource(Owner source) {
        assertOnSerializedExecutor();
        Slot exact = findSlot(source);
        if (exact == null) {
            enqueue(() -> coordinator.onLocalOwnerCount(source, 1, now()));
            return;
        }
        exact.transport.beginConfirmedModeSwitchStop(source);
        if (exact.terminalObserved) {
            enqueue(() -> handleLocalTerminal(exact, source));
        }
    }

    @Override public void transmitControl(ControlTransmit transmit) {
        assertOnSerializedExecutor();
        Slot exact = findSlot(transmit.owner());
        if (exact == null) {
            enqueue(() -> coordinator.onControlTransmitResult(
                    transmit, ControlTransmitResult.TERMINAL_FAILURE, now()));
            return;
        }
        exact.transport.transmitControl(transmit, (descriptor, result) -> enqueue(() -> {
            if (!isCurrentSlot(exact, descriptor.owner())) return;
            coordinator.onControlTransmitResult(descriptor, result, now());
            publishDualStatus("control " + descriptor.frame() + ": " + result);
        }));
    }

    @Override public void scheduleControlTransmitRetry(ControlTransmit transmit) {
        assertOnSerializedExecutor();
        Slot exact = findSlot(transmit.owner());
        if (exact == null) return;
        exact.transport.scheduleControlRetry(transmit, descriptor -> enqueue(() -> {
            if (isCurrentSlot(exact, descriptor.owner())) {
                coordinator.onControlTransmitRetry(descriptor, now());
            }
        }));
    }

    @Override public void cancelControlTransmitRetry(ControlTransmit transmit) {
        assertOnSerializedExecutor();
        Slot exact = findSlot(transmit.owner());
        if (exact != null) exact.transport.cancelControlRetry(transmit);
    }

    @Override public void verifyLocalOwners(Owner source) {
        assertOnSerializedExecutor();
        Slot exact = findSlot(source);
        int count = exact == null ? 1 : exact.transport.appOwnedOwnerCount(source);
        enqueue(() -> coordinator.onLocalOwnerCount(source, count, now()));
    }

    @Override public void cancelStopTimeout(Owner source) {
        cancelTimer(stopTimers, source);
    }

    @Override public void armDrainDeadline(Owner source, long deadlineMillis) {
        replaceTimer(drainTimers, source, deadlineMillis,
                () -> coordinator.onDrainDeadline(source, now()));
    }

    @Override public void cancelDrainDeadline(Owner source) {
        cancelTimer(drainTimers, source);
    }

    @Override public void quiescentReached(Owner target) {
        assertOnSerializedExecutor();
        closeSlot();
        if (suppressedProductionRouteB(target)) {
            // The persisted v2 state may predate HA1215 and name Route B as ACTIVE or as the
            // interrupted target. Its exact old owner has now crossed freeze, terminal, owner=0
            // and drain gates. Persist a logical B placeholder only, then immediately persist a
            // normal B→A drain transaction. No B adapter is started and no fake READY is
            // published; a crash between the two writes restores and repeats the same safe drain.
            enqueue(() -> migrateQuiescentRouteBToProductionA(target));
        } else {
            enqueue(() -> coordinator.beginTargetStart(target));
        }
    }

    private boolean suppressedProductionRouteB(Owner owner) {
        return owner != null && config != null && !config.allowExperimentalRouteB
                && owner.role() == Role.HELPER_CENTRAL_ANDROID_PERIPHERAL;
    }

    private void migrateQuiescentRouteBToProductionA(Owner quiescentTarget) {
        assertOnSerializedExecutor();
        if (closed || !suppressedProductionRouteB(quiescentTarget)) return;
        coordinator = BleRoleSwitchCoordinator.active(
                processNonce,
                quiescentTarget.role(),
                quiescentTarget.epoch(),
                quiescentTarget.generation(),
                this,
                this);
        config = config.withDesiredMode(IphoneBleMode.ANDROID_CENTRAL);
        requestModeOnSerialized(IphoneBleMode.ANDROID_CENTRAL);
        publishDualStatus("persisted Route B drained; production Route A migration started");
    }

    @Override public void startTarget(Owner target) {
        assertOnSerializedExecutor();
        // Every target attempt owns its own diagnostic lineage. A prior failed generation must
        // never leak its root cause into this generation's fail-closed status.
        fatalDetail = "";
        if (suppressedProductionRouteB(target)) {
            fatalDetail = "Route B target suppressed: explicit diagnostics disabled";
            listener.onError(new IphoneTransportErrorV2(
                    IphoneBleMode.ANDROID_PERIPHERAL,
                    routeEpoch(target),
                    IphoneTransportErrorV2.Kind.PEER_PROOF_REJECTED,
                    fatalDetail,
                    false));
            coordinator.onTargetStartFailed(target);
            publishDualStatus(fatalDetail);
            return;
        }
        if (!config.radioEnabled) {
            enqueue(() -> publishDualStatus("waiting for Bluetooth radio"));
            return;
        }
        if (slot != null) {
            fatalDetail = "target start rejected: prior route slot is still attached";
            enqueue(() -> coordinator.onTargetStartFailed(target));
            return;
        }
        final IphoneSwitchTransportV2 transport;
        try {
            transport = factory.create(mode(target.role()), androidInstallationId);
        } catch (RuntimeException error) {
            fatalDetail = "target factory failed: " + safeMessage(error);
            enqueue(() -> coordinator.onTargetStartFailed(target));
            return;
        }
        Slot fresh = new Slot(transport, target, false);
        slot = fresh;
        try {
            boolean enrolled = store.hasRouteAEnrollment(
                    config.selectedSystemBondAddress, androidInstallationId.toString());
            UUID helper = identities.learnedHelperIdentity();
            String helperId = enrolled || helper == null ? "" : helper.toString();
            // iOS moves service UUIDs into an Apple-only overflow area while a peripheral app is
            // backgrounded, so Android cannot make a filtered F201 scan a production bootstrap
            // prerequisite. The exact user-selected system bond is already the strict pre-GATT
            // identity gate; encrypted H then learns or confirms the Helper installation UUID.
            IphoneAcquisitionModeV2 acquisition = enrolled
                    ? IphoneAcquisitionModeV2.ENROLLED_LE_IDENTITY
                    : IphoneAcquisitionModeV2.SELECTED_BOND;
            IphoneTransportStartRequest request = new IphoneTransportStartRequest(
                    new BleRouteEpoch(target.processNonce(), target.generation().asBigInteger()),
                    config.selectedSystemBondAddress,
                    helperId,
                    config.radioEnabled,
                    now(),
                    acquisition
            );
            transport.start(request, new BoundListener(fresh));
            // startTarget itself is the attributable fresh-target boundary.  Do not depend on
            // an intermediate coordinator phase name when consuming a pre-start presence hint.
            forwardSelectedPhonePresenceTo(fresh);
        } catch (RuntimeException error) {
            fatalDetail = "target start failed: " + safeMessage(error);
            closeSlot();
            enqueue(() -> coordinator.onTargetStartFailed(target));
        }
    }

    @Override public void targetActive(Owner target) {
        assertOnSerializedExecutor();
        enqueue(() -> {
            publishDualStatus("target active");
            if (coordinator == null || coordinator.state().phase() != Phase.ACTIVE) return;
            if (role(config.desiredMode) != coordinator.state().activeRole()) {
                requestModeOnSerialized(config.desiredMode);
            }
        });
    }

    @Override public void failClosed(Owner owner, Failure failure) {
        assertOnSerializedExecutor();
        String rootCause = fatalDetail == null ? "" : fatalDetail.trim();
        String exactFailureDetail = failure == Failure.TARGET_START_FAILED && !rootCause.isEmpty()
                ? "switch failed closed: " + failure + "; " + rootCause
                : "switch failed closed: " + failure;
        fatalDetail = exactFailureDetail;
        enqueue(() -> publishDualStatus(exactFailureDetail));
    }

    @Override public void closeAll(Owner owner) {
        assertOnSerializedExecutor();
        cancelAllRuntimeTimers();
        closeSlot();
    }

    @Override public void assertOnSerializedExecutor() {
        if (!scheduler.isCurrent()) {
            throw new IllegalStateException("ANCS v2 runtime accessed off serialized executor");
        }
    }

    private final class BoundListener implements IphoneTransportSessionListenerV2 {
        private final Slot bound;

        BoundListener(Slot bound) {
            this.bound = bound;
        }

        @Override public void onPlatformDiagnostic(
                IphoneBleMode mode, BleRouteEpoch epoch, String detail) {
            enqueue(() -> {
                if (!isCurrentSlot(bound) || mode != bound.transport.mode()
                        || epoch == null || !routeEpoch(bound.routeOwner).equals(epoch)) return;
                listener.onPlatformDiagnostic(mode, epoch, detail);
            });
        }

        @Override public void onStatus(IphoneTransportStatusV2 status) {
            enqueue(() -> {
                if (!matchesBoundStatus(bound, status)) return;
                bound.status = status;
                if (status.lifecycle == IphoneTransportLifecycle.FAILED
                        || status.lifecycle == IphoneTransportLifecycle.STOPPED) {
                    bound.targetFailureDetail = "route "
                            + status.lifecycle.name().toLowerCase(java.util.Locale.ROOT)
                            + ": " + status.detail;
                }
                if (status.lifecycle == IphoneTransportLifecycle.READY
                        && coordinator != null && coordinator.state().phase() == Phase.STARTING) {
                    coordinator.onTargetActive(coordinator.targetOwner());
                } else if ((status.lifecycle == IphoneTransportLifecycle.FAILED
                        || status.lifecycle == IphoneTransportLifecycle.STOPPED)
                        && coordinator != null && coordinator.state().phase() == Phase.STARTING) {
                    failBoundTarget(bound);
                } else if (!config.externallyManagedRecovery
                        && (status.lifecycle == IphoneTransportLifecycle.FAILED
                        || status.lifecycle == IphoneTransportLifecycle.STOPPED)
                        && coordinator != null && coordinator.state().phase() == Phase.ACTIVE) {
                    // Route-local fresh-owner retries are bounded. Their terminal status is an
                    // exact top-level recovery signal, never a reason to strand ACTIVE over a
                    // dead adapter or spin another owner inside the same route epoch.
                    requestSameModeRecoveryOnSerialized();
                }
                // Externally managed FAILED/STOPPED remains an ACTIVE coordinator with its exact
                // terminal slot attached until the controller's bounded policy asks for a full
                // same-role drain. The status callback below is that typed recovery signal.
                listener.onStatus(status);
                publishDualStatus(status.detail);
            });
        }

        @Override public void onTelemetry(IphoneTelemetryV2 telemetry) {
            enqueue(() -> { if (isCurrentSlot(bound)) listener.onTelemetry(telemetry); });
        }

        @Override public void onStandardBatteryPercentage(int percentage, String source) {
            enqueue(() -> {
                if (isCurrentSlot(bound)) {
                    listener.onStandardBatteryPercentage(percentage, source);
                }
            });
        }

        @Override public void onNotificationEvent(IphoneNotificationEventV2 event) {
            enqueue(() -> {
                if (isCurrentSlot(bound)) listener.onNotificationEvent(event);
            });
        }

        @Override public void onNotification(IphoneNotificationV2 notification) {
            enqueue(() -> {
                if (isCurrentSlot(bound)) listener.onNotification(notification);
            });
        }

        @Override public void onAppName(IphoneAppNameV2 appName) {
            enqueue(() -> { if (isCurrentSlot(bound)) listener.onAppName(appName); });
        }

        @Override public void onHelperInstallationIdLearned(String helperInstallationId) {
            // Adapters offer identities through offerHelperInstallationId().  This post-commit
            // event is retained for defensive compatibility and never grants authorization.
        }

        @Override public void offerHelperInstallationId(
                String helperInstallationId,
                HelperIdentityCompletion completion) {
            Objects.requireNonNull(completion, "completion");
            enqueue(() -> {
                if (!isCurrentSlot(bound)) {
                    completion.onAccepted(false);
                    return;
                }
                try {
                    boolean explicit = config.explicitBootstrapRequested
                            || identities.learnedHelperIdentity() == null;
                    UUID accepted = identities.acceptHelperIdentity(
                            helperInstallationId, explicit);
                    listener.onHelperInstallationIdLearned(accepted.toString());
                    completion.onAccepted(true);
                } catch (RuntimeException error) {
                    completion.onAccepted(false);
                    // Identity conflict or failed durable commit is not a radio/link retry.
                    // Keep the prior valid BRS2 snapshot for a later explicit repair, but do not
                    // spin the selected bond through fresh owners.
                    poisonRuntime(error,
                            IphoneTransportErrorV2.Kind.PEER_PROOF_REJECTED);
                }
            });
        }

        @Override public void onRoleControl(IphoneRoleControlV2 control) {
            enqueue(() -> handleRoleControl(bound, control));
        }

        @Override public void onRoleControlWriteResult(
                IphoneRoleControlV2 control, boolean success) {
            enqueue(() -> {
                if (isCurrentSlot(bound)) listener.onRoleControlWriteResult(control, success);
            });
        }

        @Override public void onCarRemoteFrame(byte[] frame) {
            byte[] exact = frame == null ? null : frame.clone();
            enqueue(() -> {
                if (!isCurrentSlot(bound) || bound.status == null
                        || bound.status.lifecycle != IphoneTransportLifecycle.READY
                        || exact == null
                        || exact.length != IphoneCarRemoteProtocolV1.FRAME_BYTES) return;
                listener.onCarRemoteFrame(exact);
            });
        }

        @Override public void onLocalTerminal(IphoneBleMode mode, BleRouteEpoch epoch) {
            enqueue(() -> {
                if (!isCurrentSlot(bound) || mode != bound.transport.mode()
                        || !routeEpoch(bound.routeOwner).equals(epoch)) return;
                bound.terminalObserved = true;
                if (coordinator != null && coordinator.state().phase() == Phase.ACTIVE
                        && !config.externallyManagedRecovery) {
                    requestSameModeRecoveryOnSerialized();
                } else if (coordinator == null
                        || coordinator.state().phase() != Phase.ACTIVE) {
                    Owner source = coordinator == null ? null : coordinator.sourceOwner();
                    if (source != null) handleLocalTerminal(bound, source);
                }
                // Under externally managed recovery ACTIVE deliberately remains attached to the
                // terminal slot. The controller receives this typed signal and its bounded policy
                // requests the full same-role drain/fresh generation; no coordinator phase is
                // mutated prematurely and no second owner can overlap it.
                listener.onLocalTerminal(mode, epoch);
            });
        }

        @Override public void onError(IphoneTransportErrorV2 error) {
            enqueue(() -> {
                if (!isCurrentSlot(bound)) return;
                listener.onError(error);
                if (!error.retryable && coordinator != null
                        && coordinator.state().phase() == Phase.STARTING) {
                    bound.targetFailureDetail = "route " + error.kind + ": " + error.detail;
                    failBoundTarget(bound);
                }
            });
        }
    }

    private void handleRoleControl(Slot bound, IphoneRoleControlV2 control) {
        assertOnSerializedExecutor();
        if (!isCurrentSlot(bound) || coordinator == null || control == null) return;
        Owner receiving = coordinator.state().phase() == Phase.ACTIVE
                ? coordinator.targetOwner() : coordinator.sourceOwner();
        if (!bound.matchesRouteGeneration(receiving)) return;
        Role target = role(control.targetMode);
        if (control.targetMode == IphoneBleMode.ANDROID_PERIPHERAL
                && !config.allowExperimentalRouteB) {
            listener.onError(new IphoneTransportErrorV2(
                    bound.transport.mode(),
                    routeEpoch(bound.routeOwner),
                    IphoneTransportErrorV2.Kind.PEER_PROOF_REJECTED,
                    "Route B control rejected: explicit diagnostics disabled",
                    false));
            publishDualStatus("remote Route B rejected");
            return;
        }
        if (control.type == IphoneRoleControlV2.Type.CLOSE_REQUEST) {
            Outcome outcome = coordinator.requestSwitchFromRemote(
                    receiving,
                    target,
                    new WireSwitchToken(control.switchToken()),
                    now(),
                    stopTimeoutMillis,
                    drainDurationMillis
            );
            if (outcome == Outcome.APPLIED || outcome == Outcome.COALESCED) {
                config = config.withDesiredMode(control.targetMode);
            }
        } else {
            coordinator.onRemoteCloseAck(
                    receiving,
                    target,
                    control.switchToken(),
                    now()
            );
        }
        listener.onRoleControl(control);
        publishDualStatus("remote " + control.type);
    }

    private void failBoundTarget(Slot bound) {
        assertOnSerializedExecutor();
        if (!isCurrentSlot(bound) || bound.targetFailureReported || coordinator == null
                || coordinator.state().phase() != Phase.STARTING) return;
        Owner target = coordinator.targetOwner();
        if (!bound.matchesRouteGeneration(target)) return;
        bound.targetFailureReported = true;
        if (bound.targetFailureDetail != null && !bound.targetFailureDetail.trim().isEmpty()) {
            fatalDetail = bound.targetFailureDetail.trim();
        }
        closeSlot();
        coordinator.onTargetStartFailed(target);
    }

    private void handleLocalTerminal(Slot exact, Owner source) {
        assertOnSerializedExecutor();
        if (!isCurrentSlot(exact, source) || coordinator == null) return;
        exact.terminalObserved = true;
        coordinator.onLocalTerminal(source, now());
        publishDualStatus("source terminal");
    }

    private Slot findSlot(Owner owner) {
        return slot != null && slot.matchesRouteGeneration(owner) ? slot : null;
    }

    private boolean isCurrentSlot(Slot candidate) {
        return slot == candidate;
    }

    private boolean isCurrentSlot(Slot candidate, Owner owner) {
        return slot == candidate && candidate.matchesRouteGeneration(owner);
    }

    private boolean matchesBoundStatus(Slot bound, IphoneTransportStatusV2 status) {
        return isCurrentSlot(bound) && status != null
                && status.mode == bound.transport.mode()
                && status.epoch.equals(routeEpoch(bound.routeOwner));
    }

    private void closeSlot() {
        Slot previous = slot;
        slot = null;
        if (previous != null) previous.transport.close();
    }

    /** One containment boundary for every public command, timer, and raw adapter callback. */
    private void enqueue(Runnable action) {
        scheduler.execute(() -> {
            if (poisoned) return;
            try {
                action.run();
            } catch (RuntimeException error) {
                poisonRuntime(error);
            }
        });
    }

    /**
     * Persistence/effect failures are terminal for this process facade, but leave the previous
     * valid write-ahead snapshot intact so the next runtime can restoration-drain it.
     */
    private void poisonRuntime(RuntimeException error) {
        poisonRuntime(error, IphoneTransportErrorV2.Kind.TEARDOWN);
    }

    private void poisonRuntime(RuntimeException error, IphoneTransportErrorV2.Kind kind) {
        if (poisoned) return;
        poisoned = true;
        closed = true;
        fatalDetail = "v2 runtime failed closed: " + safeMessage(error);
        BleRouteEpoch failedEpoch = slot == null
                ? new BleRouteEpoch(processNonce, 1L)
                : routeEpoch(slot.routeOwner);
        IphoneBleMode failedMode = slot == null
                ? (config == null ? IphoneBleMode.ANDROID_CENTRAL : config.desiredMode)
                : slot.transport.mode();
        try {
            cancelAllRuntimeTimers();
        } catch (RuntimeException ignored) {
        }
        try {
            closeSlot();
        } catch (RuntimeException ignored) {
        }
        if (listener != null) {
            try {
                listener.onError(new IphoneTransportErrorV2(
                        failedMode,
                        failedEpoch,
                        kind,
                        fatalDetail,
                        false));
            } catch (RuntimeException ignored) {
            }
            try {
                listener.onDualTransportStatus(snapshotStatus(fatalDetail));
            } catch (RuntimeException ignored) {
            }
        }
    }

    private void replaceTimer(Map<Owner, Cancellable> timers, Owner owner,
                              long deadline, Runnable action) {
        assertOnSerializedExecutor();
        cancelTimer(timers, owner);
        Cancellable task = scheduler.scheduleAt(deadline, () -> enqueue(() -> {
            Cancellable current = timers.remove(owner);
            if (current != null && coordinator != null) action.run();
        }));
        timers.put(owner, task);
    }

    private static void cancelTimer(Map<Owner, Cancellable> timers, Owner owner) {
        Cancellable task = timers.remove(owner);
        if (task != null) task.cancel();
    }

    private void cancelAllRuntimeTimers() {
        for (Cancellable task : stopTimers.values()) task.cancel();
        for (Cancellable task : drainTimers.values()) task.cancel();
        stopTimers.clear();
        drainTimers.clear();
    }

    private void publishDualStatus(String detail) {
        if (listener != null && config != null) {
            listener.onDualTransportStatus(snapshotStatus(detail));
        }
    }

    private IphoneDualTransportStatusV2 snapshotStatus(String detail) {
        Phase phase = poisoned || coordinator == null
                ? Phase.FAILED : coordinator.state().phase();
        Failure failure = poisoned || coordinator == null
                ? Failure.INGRESS_FREEZE_FAILED : coordinator.state().failure();
        IphoneBleMode active = null;
        if (!poisoned && coordinator != null && phase == Phase.ACTIVE) {
            active = mode(coordinator.state().activeRole());
        }
        return new IphoneDualTransportStatusV2(
                config.desiredMode,
                active,
                phase,
                failure,
                slot == null ? null : slot.status,
                detail
        );
    }

    private void failInitialization(String detail) {
        fatalDetail = detail;
        if (listener != null && config != null) {
            listener.onError(new IphoneTransportErrorV2(
                    config.desiredMode,
                    new BleRouteEpoch(processNonce, 1L),
                    IphoneTransportErrorV2.Kind.TEARDOWN,
                    detail,
                    false
            ));
            publishDualStatus(detail);
        }
    }

    private WireSwitchToken newWireToken() {
        return new WireSwitchToken(IphoneBleControlProtocolV2.newSwitchToken(random));
    }

    private long now() {
        return scheduler.nowMillis();
    }

    private static BleRouteEpoch routeEpoch(Owner owner) {
        return new BleRouteEpoch(owner.processNonce(), owner.generation().asBigInteger());
    }

    private static Role role(IphoneBleMode mode) {
        return switch (Objects.requireNonNull(mode, "mode")) {
            case ANDROID_CENTRAL -> Role.HELPER_PERIPHERAL_ANDROID_CENTRAL;
            case ANDROID_PERIPHERAL -> Role.HELPER_CENTRAL_ANDROID_PERIPHERAL;
        };
    }

    private static IphoneBleMode mode(Role role) {
        return switch (Objects.requireNonNull(role, "role")) {
            case HELPER_PERIPHERAL_ANDROID_CENTRAL -> IphoneBleMode.ANDROID_CENTRAL;
            case HELPER_CENTRAL_ANDROID_PERIPHERAL -> IphoneBleMode.ANDROID_PERIPHERAL;
        };
    }

    private static long distinctMigrationNonce(long processNonce) {
        long candidate = processNonce ^ 0x9e3779b97f4a7c15L;
        if (candidate == 0L || candidate == processNonce) candidate = processNonce + 1L;
        if (candidate == 0L || candidate == processNonce) candidate = 1L;
        return candidate;
    }

    private static String safeMessage(Throwable error) {
        String message = error == null ? "" : error.getMessage();
        return message == null || message.trim().isEmpty()
                ? (error == null ? "unknown failure" : error.getClass().getSimpleName())
                : message.trim();
    }
}
