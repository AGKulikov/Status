/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package dezz.status.widget.phone.transport.v2.android;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;

import dezz.status.widget.Preferences;
import dezz.status.widget.phone.PhoneConnectionJournal;
import dezz.status.widget.phone.transport.AncsProtocol;
import dezz.status.widget.phone.PhoneConnectorPolicy;
import dezz.status.widget.phone.transport.v2.AncsDeliveryTraceV2;
import dezz.status.widget.phone.transport.v2.AncsConsumerCoreV2;
import dezz.status.widget.phone.transport.v2.AncsConsumerEffectV2;
import dezz.status.widget.phone.transport.v2.AncsRequestTokenV2;
import dezz.status.widget.phone.transport.v2.AncsSessionTokenV2;
import dezz.status.widget.phone.transport.v2.AndroidCentralRoute;
import dezz.status.widget.phone.transport.v2.BlePeerRole;
import dezz.status.widget.phone.transport.v2.BleRouteEffect;
import dezz.status.widget.phone.transport.v2.BleRouteEpoch;
import dezz.status.widget.phone.transport.v2.BleRouteToken;
import dezz.status.widget.phone.transport.v2.BleRouteTransition;
import dezz.status.widget.phone.transport.v2.CarRemoteFrameQueueV1;
import dezz.status.widget.phone.transport.v2.ExactCallbackAttemptFenceV2;
import dezz.status.widget.phone.transport.v2.IphoneBleAdvertisement;
import dezz.status.widget.phone.transport.v2.IphoneBleControlProtocolV2;
import dezz.status.widget.phone.transport.v2.IphoneBleMode;
import dezz.status.widget.phone.transport.v2.IphoneBlePeerProof;
import dezz.status.widget.phone.transport.v2.IphoneBleProtocolV2;
import dezz.status.widget.phone.transport.v2.IphoneGattInventoryV2;
import dezz.status.widget.phone.transport.v2.IphoneAcquisitionModeV2;
import dezz.status.widget.phone.transport.v2.IphoneLeEnrollmentProtocolV2;
import dezz.status.widget.phone.transport.v2.IphoneLeEnrollmentRecordV2;
import dezz.status.widget.phone.transport.v2.IphoneTelemetryProtocolV2;
import dezz.status.widget.phone.transport.v2.IphoneTelemetryV2;
import dezz.status.widget.phone.transport.v2.IphoneTransportErrorV2;
import dezz.status.widget.phone.transport.v2.IphoneTransportLifecycle;
import dezz.status.widget.phone.transport.v2.IphoneTransportRecoveryStateV2;
import dezz.status.widget.phone.transport.v2.IphoneTransportSessionListenerV2;
import dezz.status.widget.phone.transport.v2.IphoneTransportStartRequest;
import dezz.status.widget.phone.transport.v2.IphoneTransportStatusV2;
import dezz.status.widget.phone.transport.v2.IphoneTransportStopReason;
import dezz.status.widget.phone.transport.v2.IphoneTransportV2;
import dezz.status.widget.phone.transport.v2.IphoneSwitchTransportV2;
import dezz.status.widget.phone.transport.v2.GattResultV2;
import dezz.status.widget.phone.transport.v2.MonotonicSessionCursorV2;
import dezz.status.widget.phone.transport.v2.IphoneRoleControlV2;
import dezz.status.widget.phone.transport.v2.SelectedBondIdentityResolverV2;
import dezz.status.widget.phone.transport.v2.SelectedBondLeCapabilityV2;
import dezz.status.widget.phone.transport.switching.BleRoleSwitchCoordinator.ControlTransmit;
import dezz.status.widget.phone.transport.switching.BleRoleSwitchCoordinator.Owner;
import dezz.status.widget.phone.transport.switching.BleRoleSwitchReducer.ControlFrame;
import dezz.status.widget.phone.transport.switching.BleRoleSwitchReducer.ControlTransmitResult;
import dezz.status.widget.phone.transport.switching.BleRoleSwitchReducer.Role;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;

/**
 * Android framework adapter for Route A (Helper Peripheral / Android Central).
 *
 * <p>All public calls, framework callbacks, reducer inputs, GATT operations, and listener calls
 * are serialized on the main looper.  There is at most one {@link BluetoothGatt} wrapper.  A
 * silent asynchronous client registration is retained and reasserted on that same wrapper; this
 * adapter never closes it and creates a second wrapper while registration is unprovable.</p>
 */
public final class AndroidCentralTransportV2 implements IphoneSwitchTransportV2 {
    private static final long CONTROL_RETRY_DELAY_MS = 150L;
    private static final long IDENTITY_COMMIT_TIMEOUT_MS = 5_000L;
    private static final long BATTERY_PROBE_RETRY_MS = 500L;
    private static final int BATTERY_PROBE_RETRY_LIMIT = 60;
    private static final int ROUTINE_REQUESTED_MTU = 185;
    private static final int ROUTINE_REQUIRED_MTU = 69;
    private static final long TELEMETRY_REFRESH_QUIET_MS = 30_000L;
    private static final long TELEMETRY_REFRESH_RETRY_MS = 5_000L;
    private static final long CAR_REMOTE_WRITE_TIMEOUT_MS = 5_000L;
    private static final long CAR_REMOTE_BACKOFF_MIN_MS = 2_000L;
    private static final long CAR_REMOTE_BACKOFF_MAX_MS = 30_000L;
    private static final int PLATFORM_DIAGNOSTIC_LIMIT = 256;
    private static final UUID GENERIC_ATTRIBUTE_SERVICE =
            UUID.fromString("00001801-0000-1000-8000-00805f9b34fb");
    private static final UUID SERVICE_CHANGED =
            UUID.fromString("00002a05-0000-1000-8000-00805f9b34fb");
    private static final UUID BATTERY_SERVICE =
            UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb");
    private static final UUID BATTERY_LEVEL =
            UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb");
    private static final UUID BATTERY_LEVEL_STATUS =
            UUID.fromString("00002bed-0000-1000-8000-00805f9b34fb");

    private enum RawOperation {
        DISCOVER,
        READ_PEER_PROOF,
        READ_BATTERY_STATUS,
        READ_BATTERY_LEVEL,
        SUBSCRIBE_BATTERY_STATUS,
        SUBSCRIBE_BATTERY_LEVEL,
        SUBSCRIBE_ROUTE_CONTROL,
        SUBSCRIBE_TELEMETRY,
        SUBSCRIBE_SERVICE_CHANGED,
        SUBSCRIBE_NOTIFICATION_SOURCE,
        SUBSCRIBE_DATA_SOURCE,
        WRITE_CONTROL_POINT,
        WRITE_ROUTE_CONTROL,
        /** Helper 52 R request; appended so historical operation ordinals stay stable. */
        REQUEST_TELEMETRY,
        /** Helper 53 C5 subscription and Android-to-Helper response write. */
        SUBSCRIBE_CAR_REMOTE,
        WRITE_CAR_REMOTE
    }

    private enum RoutineStage {
        NONE,
        HELLO_WRITE,
        PROOF_READ,
        CONFIRM_WRITE,
        ACK_READ,
        PROVEN
    }

    private static final class PendingGattOperation {
        final RawOperation type;
        final BleRouteToken routeToken;
        final AncsRequestTokenV2 ancsRequest;
        final BluetoothGattCharacteristic characteristic;
        final BluetoothGattDescriptor descriptor;
        final ControlTransmit controlTransmit;
        final ControlCompletion controlCompletion;
        final byte[] carRemoteFrame;

        PendingGattOperation(RawOperation type, BleRouteToken routeToken,
                             AncsRequestTokenV2 ancsRequest,
                             BluetoothGattCharacteristic characteristic,
                             BluetoothGattDescriptor descriptor) {
            this.type = type;
            this.routeToken = routeToken;
            this.ancsRequest = ancsRequest;
            this.characteristic = characteristic;
            this.descriptor = descriptor;
            this.controlTransmit = null;
            this.controlCompletion = null;
            this.carRemoteFrame = null;
        }

        PendingGattOperation(ControlTransmit controlTransmit,
                             ControlCompletion controlCompletion,
                             BluetoothGattCharacteristic characteristic) {
            this.type = RawOperation.WRITE_ROUTE_CONTROL;
            this.routeToken = null;
            this.ancsRequest = null;
            this.characteristic = characteristic;
            this.descriptor = null;
            this.controlTransmit = Objects.requireNonNull(controlTransmit, "controlTransmit");
            this.controlCompletion = Objects.requireNonNull(controlCompletion,
                    "controlCompletion");
            this.carRemoteFrame = null;
        }

        PendingGattOperation(byte[] carRemoteFrame,
                             BluetoothGattCharacteristic characteristic) {
            this.type = RawOperation.WRITE_CAR_REMOTE;
            this.routeToken = null;
            this.ancsRequest = null;
            this.characteristic = characteristic;
            this.descriptor = null;
            this.controlTransmit = null;
            this.controlCompletion = null;
            this.carRemoteFrame = Objects.requireNonNull(carRemoteFrame, "carRemoteFrame");
        }
    }

    private static final class GattOwner {
        final BleRouteToken ownerToken;
        final BluetoothDevice device;
        SelectedBondIdentityResolverV2.Candidate bondAttribution;
        BluetoothGatt gatt;
        long connectGattStartedAtMillis;
        boolean callbackObserved;
        boolean connected;
        boolean closing;
        boolean waitingForProcessGate;
        boolean quarantinedBeforeRegistration;

        GattOwner(BleRouteToken ownerToken, BluetoothDevice device,
                  SelectedBondIdentityResolverV2.Candidate bondAttribution) {
            this.ownerToken = ownerToken;
            this.device = device;
            this.bondAttribution = bondAttribution;
        }
    }

    private static final class PendingHelperIdentity {
        final BleRouteToken token;
        final IphoneBlePeerProof proof;
        final BleRouteTransition<AndroidCentralRoute.State> acceptedTransition;
        final IphoneTransportSessionListenerV2 sessionListener;
        Runnable deadline;

        PendingHelperIdentity(
                BleRouteToken token,
                IphoneBlePeerProof proof,
                BleRouteTransition<AndroidCentralRoute.State> acceptedTransition,
                IphoneTransportSessionListenerV2 sessionListener) {
            this.token = token;
            this.proof = proof;
            this.acceptedTransition = acceptedTransition;
            this.sessionListener = sessionListener;
        }
    }

    /** One immutable scan callback identity per explicit-bootstrap owner generation. */
    private final class ScanAttempt extends ScanCallback {
        final BleRouteToken token;
        final BluetoothLeScanner exactScanner;
        boolean retired;

        ScanAttempt(BleRouteToken token, BluetoothLeScanner exactScanner) {
            this.token = token;
            this.exactScanner = exactScanner;
        }

        @Override public void onScanResult(int callbackType, ScanResult result) {
            dispatchMain(() -> handleScanResult(this, result));
        }

        @Override public void onBatchScanResults(List<ScanResult> results) {
            if (results == null) return;
            for (ScanResult result : results) {
                dispatchMain(() -> handleScanResult(this, result));
            }
        }

        @Override public void onScanFailed(int errorCode) {
            dispatchMain(() -> handleScanFailure(this, errorCode));
        }
    }

    private final Context context;
    private final Preferences preferences;
    private final Handler main;
    private final BluetoothManager manager;
    private final BluetoothAdapter adapter;
    private final SelectedBondAttributionV2 bondAttribution;
    private final AncsConsumerCoreV2 ancs = new AncsConsumerCoreV2();
    private final AncsDeliveryTraceV2 ancsTrace = new AncsDeliveryTraceV2();
    private final SecureRandom enrollmentRandom = new SecureRandom();
    private final Map<BleRouteToken, Runnable> routeTimers = new HashMap<>();
    private final Map<AncsRequestTokenV2, Runnable> requestTimers = new HashMap<>();
    private final Map<ControlTransmit, Runnable> controlRetryTimers = new HashMap<>();
    private final ExactCallbackAttemptFenceV2<ScanAttempt> scanAttemptFence =
            new ExactCallbackAttemptFenceV2<>();

    private volatile AndroidCentralRoute.State state;
    private volatile IphoneTransportSessionListenerV2 listener;
    private IphoneTransportStartRequest startRequest;
    private BluetoothLeScanner scanner;
    private BleRouteToken scanToken;
    private boolean scanRunning;
    private ScanAttempt scanAttempt;
    private BluetoothDevice matchedBootstrapDevice;
    private SelectedBondIdentityResolverV2.Candidate matchedBootstrapAttribution;
    private volatile GattOwner owner;
    private PendingGattOperation pendingGatt;
    private BluetoothGattCharacteristic telemetryCharacteristic;
    private BleRouteToken telemetrySubscriptionToken;
    private BluetoothGattCharacteristic carRemoteCharacteristic;
    private BleRouteToken carRemoteSubscriptionToken;
    private final CarRemoteFrameQueueV1 carRemoteWrites = new CarRemoteFrameQueueV1();
    private Runnable carRemoteSubscribeTimer;
    private Runnable carRemoteDrainTimer;
    private Runnable carRemoteWriteWatchdog;
    private int carRemoteWriteTimeouts;
    private long carRemoteRetryNotBeforeMillis;
    private Runnable telemetryRefreshTimer;
    private AncsRequestTokenV2 deferredAncsRequest;
    private byte[] deferredAncsValue;
    private BluetoothGattCharacteristic batteryStatusCharacteristic;
    private BluetoothGattCharacteristic batteryLevelCharacteristic;
    private int batteryProbeStage;
    private int batteryProbeRetries;
    private Runnable batteryProbeTimer;
    private IphoneLeEnrollmentRecordV2 enrollmentRecord;
    private IphoneLeEnrollmentRecordV2 pendingEnrollmentRecord;
    private IphoneLeEnrollmentRecordV2 activeEnrollmentRecord;
    private boolean enrollmentRecordPending;
    private boolean clearStalePendingAfterActiveProof;
    private IphoneLeEnrollmentProtocolV2.RoutineSession routineSession;
    private byte[] routineHello;
    private byte[] routineConfirm;
    private BleRouteToken routineRouteToken;
    private BleRouteToken pendingMtuDiscoveryToken;
    private RoutineStage routineStage = RoutineStage.NONE;
    private boolean routineMtuReady;
    private AncsSessionTokenV2 ancsSession;
    private final MonotonicSessionCursorV2 ancsSessionCursor =
            new MonotonicSessionCursorV2();
    private Runnable replayQuietTimer;
    private long replayQuietGeneration;
    private boolean ingressFrozen;
    private IphoneRoleControlV2 lastInboundCloseRequest;
    private IphoneRoleControlV2 lastOutboundControl;
    private Owner restorationOwner;
    private RestorationDrainCompletion restorationCompletion;
    private boolean restorationTerminalReported;
    private BleRouteEpoch radioOffTerminalEpoch;
    private boolean radioResetProven;
    private BleRouteEpoch deferredStopTerminalEpoch;
    private final Object processGateDrainWaiter = new Object();
    private final Object restorationGateWaiter = new Object();
    private boolean processGateDrainRetained;
    private PendingHelperIdentity pendingHelperIdentity;
    private boolean selectedPhonePresencePending;
    private boolean closed;

    public AndroidCentralTransportV2(Context context) {
        this(context, new Preferences(context), SelectedBondAttributionV2.STRICT_PUBLIC_API);
    }

    public AndroidCentralTransportV2(Context context, Preferences preferences) {
        this(context, preferences, SelectedBondAttributionV2.STRICT_PUBLIC_API);
    }

    public AndroidCentralTransportV2(Context context,
                                     SelectedBondAttributionV2 bondAttribution) {
        this(context, new Preferences(context), bondAttribution);
    }

    public AndroidCentralTransportV2(Context context, Preferences preferences,
                                     SelectedBondAttributionV2 bondAttribution) {
        this.context = Objects.requireNonNull(context, "context").getApplicationContext();
        this.preferences = Objects.requireNonNull(preferences, "preferences");
        this.bondAttribution = Objects.requireNonNull(bondAttribution, "bondAttribution");
        this.main = new Handler(Looper.getMainLooper());
        this.manager =
                (BluetoothManager) this.context.getSystemService(Context.BLUETOOTH_SERVICE);
        this.adapter = manager == null ? null : manager.getAdapter();
    }

    @Override public IphoneBleMode mode() {
        return IphoneBleMode.ANDROID_CENTRAL;
    }

    @Override public void start(IphoneTransportStartRequest request,
                                IphoneTransportSessionListenerV2 listener) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(listener, "listener");
        main.post(() -> startOnMain(request, listener));
    }

    @Override public void stop(BleRouteEpoch epoch, IphoneTransportStopReason reason) {
        Objects.requireNonNull(epoch, "epoch");
        Objects.requireNonNull(reason, "reason");
        main.post(() -> {
            stopOnMain(epoch, reason);
        });
    }

    @Override public void sendCarRemoteFrame(byte[] frame) {
        if (frame == null || frame.length != 20) return;
        byte[] exact = frame.clone();
        main.post(() -> {
            if (closed || ingressFrozen || state == null || !state.isReady()) return;
            CarRemoteFrameQueueV1.OfferResult result = carRemoteWrites.offer(exact);
            if (result == CarRemoteFrameQueueV1.OfferResult.REJECTED_INVALID) return;
            if (result == CarRemoteFrameQueueV1.OfferResult.REJECTED_PRESSURE) {
                reportError(IphoneTransportErrorV2.Kind.PROTOCOL,
                        "C5 protected output queue reached its hard limit", true);
                return;
            }
            scheduleCarRemoteDrain(0L);
        });
    }

    @Override public void selectedPhonePresent() {
        main.post(() -> {
            if (closed || ingressFrozen || state == null) return;
            if (state.isReady()) return;
            selectedPhonePresencePending = true;
            applySelectedPhonePresenceIfPossible();
        });
    }

    /** Radio-off is terminal for this activation; radio-on must call start with a fresh epoch. */
    @Override public void radioOff(BleRouteEpoch epoch) {
        Objects.requireNonNull(epoch, "epoch");
        main.post(() -> {
            radioResetProven = true;
            ingressFrozen = true;
            selectedPhonePresencePending = false;
            cancelAllTimers();
            stopBootstrapScanForFreeze();
            ProcessGattRegistrationGateV2.radioReset();
            if (state != null && state.epoch.equals(epoch)) {
                BleRouteTransition<AndroidCentralRoute.State> transition =
                        AndroidCentralRoute.radioOff(state, epoch);
                if (transition.accepted) {
                    radioOffTerminalEpoch = epoch;
                    apply(transition);
                } else {
                    deferredStopTerminalEpoch = epoch;
                }
            }
            if (owner != null) finishGattClose();
            maybeCompleteTeardown();
        });
    }

    /** Called only from an explicit bond/authorization state change observation. */
    public void authorizationChanged(BleRouteEpoch epoch) {
        Objects.requireNonNull(epoch, "epoch");
        main.post(() -> {
            if (state != null && state.epoch.equals(epoch) && state.expected != null) {
                apply(AndroidCentralRoute.authorizationChanged(state, state.expected));
            }
        });
    }

    @Override public IphoneTransportStatusV2 status() {
        AndroidCentralRoute.State snapshot = state;
        if (snapshot == null) return null;
        return toStatus(snapshot);
    }

    @Override public void close() {
        main.post(() -> {
            closed = true;
            selectedPhonePresencePending = false;
            ProcessGattRegistrationGateV2.cancelWaiter(restorationGateWaiter);
            ProcessGattRegistrationGateV2.cancelWaiter(processGateDrainWaiter);
            processGateDrainRetained = false;
            if (state != null) {
                stopOnMain(state.epoch, IphoneTransportStopReason.APP_SHUTDOWN);
            } else {
                cancelAllTimers();
            }
        });
    }

    /** Number consumed by the role-switch coordinator's independent localOwnersZero gate. */
    public int appOwnedGattCount() {
        return owner == null && !ProcessGattRegistrationGateV2.isHeld() ? 0 : 1;
    }

    @Override public void prepareRestorationDrain(
            Owner source, RestorationDrainCompletion completion) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(completion, "completion");
        main.post(() -> {
            boolean success = source.role() == Role.HELPER_PERIPHERAL_ANDROID_CENTRAL
                    && state == null && owner == null && !scanRunning
                    && restorationOwner == null && !closed;
            if (success) {
                restorationOwner = source;
                restorationCompletion = completion;
                restorationTerminalReported = false;
                ingressFrozen = true;
                ProcessGattRegistrationGateV2.whenFreeForDrain(
                        restorationGateWaiter,
                        () -> dispatchMain(() -> completeRestorationPrepared(
                                source, completion)));
                return;
            }
            main.post(() -> completion.onPrepared(source, success));
        });
    }

    private void completeRestorationPrepared(
            Owner source, RestorationDrainCompletion completion) {
        if (!source.equals(restorationOwner) || restorationCompletion != completion
                || closed) return;
        if (!ProcessGattRegistrationGateV2.ownsDrainReservation(
                restorationGateWaiter)) return;
        main.post(() -> completion.onPrepared(source, true));
    }

    @Override public void freezeIngress(Owner source, FreezeCompletion completion) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(completion, "completion");
        main.post(() -> {
            FreezeResult result = FreezeResult.FAILED;
            if (ownsSwitchSource(source)) {
                ingressFrozen = true;
                cancelAllTimers();
                stopBootstrapScanForFreeze();
                clearTelemetrySubscription();
                clearCarRemoteChannel();
                closeAncsSession();
                if (owner != null && owner.connected
                        && state != null && state.isReady()) {
                    result = FreezeResult.FROZEN_WITH_REMOTE_CONTROL;
                } else {
                    // A disconnected/reconnecting wrapper is local drainable state, not a
                    // proven remote owner. Let same-role recovery retire it cleanly.
                    result = FreezeResult.FROZEN_NO_REMOTE_OWNER;
                }
            }
            FreezeResult exactResult = result;
            main.post(() -> completion.onFrozen(source, exactResult));
        });
    }

    @Override public void transmitControl(ControlTransmit transmit,
                                          ControlCompletion completion) {
        Objects.requireNonNull(transmit, "transmit");
        Objects.requireNonNull(completion, "completion");
        main.post(() -> transmitControlOnMain(transmit, completion));
    }

    @Override public void scheduleControlRetry(ControlTransmit transmit,
                                               RetryDue callback) {
        Objects.requireNonNull(transmit, "transmit");
        Objects.requireNonNull(callback, "callback");
        main.post(() -> {
            cancelControlRetryOnMain(transmit);
            long now = android.os.SystemClock.elapsedRealtime();
            long remaining = Math.max(0L, transmit.stopDeadlineMillis() - now);
            long delay = Math.min(CONTROL_RETRY_DELAY_MS, remaining);
            Runnable due = () -> {
                controlRetryTimers.remove(transmit);
                callback.onDue(transmit);
            };
            controlRetryTimers.put(transmit, due);
            main.postDelayed(due, delay);
        });
    }

    @Override public void cancelControlRetry(ControlTransmit transmit) {
        Objects.requireNonNull(transmit, "transmit");
        main.post(() -> cancelControlRetryOnMain(transmit));
    }

    @Override public void beginConfirmedModeSwitchStop(Owner source) {
        Objects.requireNonNull(source, "source");
        main.post(() -> {
            if (source.equals(restorationOwner) && restorationCompletion != null) {
                if (!restorationTerminalReported && owner == null && !scanRunning) {
                    restorationTerminalReported = true;
                    RestorationDrainCompletion exact = restorationCompletion;
                    main.post(() -> exact.onLocalTerminal(source));
                }
                return;
            }
            if (ownsSwitchSource(source) && state != null) {
                stopOnMain(state.epoch, IphoneTransportStopReason.MODE_SWITCH);
                maybeCompleteTeardown();
            }
        });
    }

    @Override public int appOwnedOwnerCount(Owner source) {
        return ownsSwitchSource(source) ? appOwnedGattCount() : 1;
    }

    private void stopOnMain(BleRouteEpoch epoch, IphoneTransportStopReason reason) {
        if (state == null || !state.epoch.equals(epoch)) return;
        BleRouteTransition<AndroidCentralRoute.State> transition =
                AndroidCentralRoute.stop(state, epoch, reason.name());
        apply(transition);
        if (!transition.accepted
                || transition.state.phase != AndroidCentralRoute.Phase.FAILED) return;

        // CONNECTING/WAIT_* may still have private clientIf==0. Keep the exact wrapper and the
        // process lease quarantined until its first callback proves close() can unregister it.
        deferredStopTerminalEpoch = epoch;
        cancelAllTimers();
        GattOwner exact = owner;
        if (exact == null) {
            maybeCompleteTeardown();
            return;
        }
        if (exact.waitingForProcessGate
                && !ProcessGattRegistrationGateV2.owns(exact)) {
            ProcessGattRegistrationGateV2.cancelWaiter(exact);
            owner = null;
            maybeCompleteTeardown();
            return;
        }
        exact.closing = true;
        if (!exact.callbackObserved) exact.quarantinedBeforeRegistration = true;
        if (exact.callbackObserved) retireRegisteredGattOwner(exact);
    }

    private void startOnMain(IphoneTransportStartRequest request,
                             IphoneTransportSessionListenerV2 newListener) {
        assertMain();
        if (closed) {
            newListener.onError(new IphoneTransportErrorV2(mode(), request.epoch,
                    IphoneTransportErrorV2.Kind.TEARDOWN,
                    "transport instance is closed", false));
            return;
        }
        if (restorationOwner != null) {
            newListener.onError(new IphoneTransportErrorV2(mode(), request.epoch,
                    IphoneTransportErrorV2.Kind.TEARDOWN,
                    "restoration drain owns this adapter instance", false));
            return;
        }
        if (processGateDrainRetained) {
            newListener.onError(new IphoneTransportErrorV2(mode(), request.epoch,
                    IphoneTransportErrorV2.Kind.TEARDOWN,
                    "terminal drain adapter must be disposed before a fresh activation", false));
            return;
        }
        boolean reusableTerminal = state == null
                || state.phase == AndroidCentralRoute.Phase.STOPPED
                || ((state.phase == AndroidCentralRoute.Phase.FAILED
                        || state.phase == AndroidCentralRoute.Phase.WAIT_RADIO)
                    && owner == null && !scanRunning);
        if (!reusableTerminal) {
            newListener.onError(new IphoneTransportErrorV2(mode(), request.epoch,
                    IphoneTransportErrorV2.Kind.PROTOCOL,
                    "start rejected while another route epoch owns the adapter", false));
            return;
        }
        this.listener = newListener;
        this.startRequest = request;
        resetRoutineAuthState();
        this.ingressFrozen = false;
        clearTelemetrySubscription();
        clearCarRemoteChannel();
        this.lastInboundCloseRequest = null;
        this.lastOutboundControl = null;
        this.radioOffTerminalEpoch = null;
        this.radioResetProven = false;
        this.deferredStopTerminalEpoch = null;
        this.selectedPhonePresencePending = false;
        cancelHelperIdentityCommit();
        apply(AndroidCentralRoute.start(request));
    }

    private void apply(BleRouteTransition<AndroidCentralRoute.State> transition) {
        assertMain();
        if (transition == null || !transition.accepted) return;
        AndroidCentralRoute.Phase previous = state == null ? null : state.phase;
        state = transition.state;
        journalTransition(previous, transition);
        publishStatus();
        for (BleRouteEffect effect : transition.effects) execute(effect);
        applySelectedPhonePresenceIfPossible();
    }

    private static void journalTransition(
            AndroidCentralRoute.Phase previous,
            BleRouteTransition<AndroidCentralRoute.State> transition) {
        StringBuilder effects = new StringBuilder();
        for (BleRouteEffect effect : transition.effects) {
            if (effects.length() > 0) effects.append(',');
            effects.append(effect.type);
            if (effect.delayMillis > 0) effects.append('@').append(effect.delayMillis).append("ms");
        }
        AndroidCentralRoute.State next = transition.state;
        PhoneConnectionJournal.append("route-a-step",
                "phase=" + String.valueOf(previous) + "→" + next.phase
                        + ", recovery=" + recoveryState(next.phase)
                        + ", failures=" + next.consecutiveFailures
                        + ", effects=" + (effects.length() == 0 ? "none" : effects)
                        + ", detail=" + next.detail);
    }

    /** Retains a pre-start Classic hint until the sole public wrapper can consume it. */
    private void applySelectedPhonePresenceIfPossible() {
        if (!selectedPhonePresencePending || state == null || closed || ingressFrozen) return;
        switch (state.phase) {
            case CONNECTING:
            case WAIT_REASSERT:
            case WAIT_SYSTEM_CONNECTION:
                selectedPhonePresencePending = false;
                apply(AndroidCentralRoute.selectedPhonePresent(state));
                break;
            case READY:
            case WAIT_RADIO:
            case STOPPING:
            case STOPPED:
            case FAILED:
                selectedPhonePresencePending = false;
                break;
            default:
                // STARTUP_QUIET and the bounded drain/retry phases retain the liveness hint.
                break;
        }
    }

    private void execute(BleRouteEffect effect) {
        switch (effect.type) {
            case START_SCAN:
                startBootstrapScan(effect.token);
                break;
            case STOP_SCAN:
                stopBootstrapScan(effect.token);
                break;
            case CONNECT_SELECTED_BOND:
                connectSelectedBond(effect.token);
                break;
            case CONNECT_GATT:
                connectMatchedBootstrap(effect.token);
                break;
            case REASSERT_SAME_GATT:
                reassertSameGatt(effect.token);
                break;
            case CLOSE_GATT:
                closeGattOwner(effect.token, effect.detail);
                break;
            case DISCOVER_SERVICES:
                discoverServices(effect.token);
                break;
            case READ_PEER_PROOF:
                readPeerProof(effect.token);
                break;
            case SUBSCRIBE_ROUTE_CONTROL:
                subscribe(effect.token, RawOperation.SUBSCRIBE_ROUTE_CONTROL,
                        IphoneBleProtocolV2.HELPER_PERIPHERAL_SERVICE,
                        IphoneBleProtocolV2.CONTROL_CHARACTERISTIC, true);
                break;
            case SUBSCRIBE_TELEMETRY:
                subscribe(effect.token, RawOperation.SUBSCRIBE_TELEMETRY,
                        IphoneBleProtocolV2.HELPER_PERIPHERAL_SERVICE,
                        IphoneBleProtocolV2.TELEMETRY_CHARACTERISTIC, false);
                break;
            case SUBSCRIBE_GATT_SERVICE_CHANGED:
                subscribe(effect.token, RawOperation.SUBSCRIBE_SERVICE_CHANGED,
                        GENERIC_ATTRIBUTE_SERVICE, SERVICE_CHANGED, true);
                break;
            case SUBSCRIBE_ANCS_NOTIFICATION_SOURCE:
                subscribe(effect.token, RawOperation.SUBSCRIBE_NOTIFICATION_SOURCE,
                        AncsProtocol.SERVICE, AncsProtocol.NOTIFICATION_SOURCE, false);
                break;
            case SUBSCRIBE_ANCS_DATA_SOURCE:
                subscribe(effect.token, RawOperation.SUBSCRIBE_DATA_SOURCE,
                        AncsProtocol.SERVICE, AncsProtocol.DATA_SOURCE, false);
                break;
            case ARM_ANCS_PARSER:
                beginAncsSession(effect.token);
                break;
            case RESET_SESSION_STATE:
                clearTelemetrySubscription();
                clearCarRemoteChannel();
                closeAncsSession();
                resetRoutineAuthState();
                if (pendingGatt == null
                        || pendingGatt.type != RawOperation.WRITE_ROUTE_CONTROL) {
                    pendingGatt = null;
                }
                break;
            case ARM_DEADLINE:
            case ARM_RETRY:
                armRouteTimer(effect.token, effect.delayMillis);
                break;
            case CANCEL_DEADLINE:
                cancelRouteTimer(effect.token);
                break;
            case REPORT_READY:
                if (ancsSession != null) {
                    applyAncsEffects(ancs.subscriptionsReady(ancsSession));
                }
                subscribeCarRemoteIfPresent(effect.token);
                scheduleStandardBatteryMonitoring(1_500L);
                break;
            case REPORT_HELPER_ID_LEARNED:
                if (listener != null) listener.onHelperInstallationIdLearned(effect.detail);
                break;
            case REPORT_LOCAL_TERMINAL:
                if (listener != null && state != null) {
                    listener.onLocalTerminal(mode(), state.epoch);
                }
                break;
            case REPORT_ERROR:
                reportError(IphoneTransportErrorV2.Kind.GATT, effect.detail, true);
                break;
            case REPORT_DOWN:
            case OPEN_GATT_SERVER:
            case ADD_V2_SERVER_SERVICE:
            case CLOSE_GATT_SERVER:
            case START_ADVERTISING:
            case STOP_ADVERTISING:
            case BIND_INBOUND_PEER:
            case DISCONNECT_INBOUND_PEER:
            case OBSERVE_REVERSE_CLIENT:
            case CLOSE_REVERSE_CLIENT:
            case DISCOVER_ANCS:
                // Not a Route-A framework operation.
                break;
        }
    }

    private void startBootstrapScan(BleRouteToken token) {
        if (ingressFrozen || !currentEpoch(token) || adapter == null
                || !adapter.isEnabled() || scanRunning) {
            postRouteDeadline(token);
            return;
        }
        BluetoothLeScanner exactScanner = adapter.getBluetoothLeScanner();
        if (exactScanner == null) {
            postRouteDeadline(token);
            return;
        }
        List<ScanFilter> filters = new ArrayList<>();
        filters.add(new ScanFilter.Builder()
                .setServiceUuid(new ParcelUuid(IphoneBleProtocolV2.HELPER_PERIPHERAL_SERVICE))
                .build());
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();
        ScanAttempt attempt = new ScanAttempt(token, exactScanner);
        if (!scanAttemptFence.begin(attempt)) {
            postRouteDeadline(token);
            return;
        }
        scanner = exactScanner;
        scanToken = token;
        scanAttempt = attempt;
        scanRunning = true;
        try {
            exactScanner.startScan(filters, settings, attempt);
        } catch (RuntimeException error) {
            if (scanAttempt == attempt) {
                retireScanAttempt(attempt);
                reportError(IphoneTransportErrorV2.Kind.GATT,
                        "bootstrap scan start failed: " + error.getClass().getSimpleName(), true);
                postRouteDeadline(token);
            }
        }
    }

    private void stopBootstrapScan(BleRouteToken token) {
        if (!scanRunning || scanToken == null) {
            maybeCompleteTeardown();
            return;
        }
        if (!scanToken.sameOwner(token)) return;
        ScanAttempt attempt = scanAttempt;
        try {
            if (attempt != null) attempt.exactScanner.stopScan(attempt);
        } catch (RuntimeException ignored) {
            // The exact scan owner is invalidated below even if the radio changed concurrently.
        }
        retireScanAttempt(attempt);
        maybeCompleteTeardown();
    }

    private void stopBootstrapScanForFreeze() {
        ScanAttempt attempt = scanAttempt;
        try {
            if (attempt != null) attempt.exactScanner.stopScan(attempt);
        } catch (RuntimeException ignored) {
            // The frozen generation will reject every late callback.
        }
        retireScanAttempt(attempt);
        matchedBootstrapDevice = null;
        matchedBootstrapAttribution = null;
    }

    private void connectSelectedBond(BleRouteToken token) {
        if (ingressFrozen || !currentEpoch(token) || startRequest == null || adapter == null
                || !adapter.isEnabled()) {
            postConnected(token, false);
            return;
        }
        // Use the exact facade exposed by Android's bonded-device inventory. Besides avoiding a
        // second synthetic wrapper, this keeps the public-address API boundary deterministic on
        // Android 9 (which rejects lower-case A-F in Bluetooth addresses).
        SelectedBondFacade selectedBond = selectedSystemBondFacade(
                startRequest.selectedSystemBondAddress);
        reportPlatformDiagnostic(token,
                "selected_bond unique=" + (selectedBond.matches == 1)
                        + ", matches=" + selectedBond.matches
                        + ", bonded=" + (selectedBond.device != null));
        BluetoothDevice selected = selectedBond.device;
        if (startRequest.acquisitionMode == IphoneAcquisitionModeV2.ENROLLED_LE_IDENTITY) {
            if (selected == null || selectedBond.matches != 1
                    || selected.getBondState() != BluetoothDevice.BOND_BONDED) {
                failEnrolledRoute(token, IphoneTransportErrorV2.Kind.PEER_PROOF_REJECTED,
                        "selected Classic bond is not uniquely present; enrollment blocked");
                return;
            }
            IphoneLeEnrollmentRecordV2 record = loadEnrollmentRecord(
                    startRequest.selectedSystemBondAddress,
                    startRequest.helperInstallationId);
            if (record == null) {
                failEnrolledRoute(token, IphoneTransportErrorV2.Kind.PEER_PROOF_REJECTED,
                        "no exact device-local LE enrollment record; explicit enrollment required");
                return;
            }
            try {
                BluetoothDevice enrolled = adapter.getRemoteDevice(record.leIdentityAddress);
                if (enrolled == null
                        || enrolled.getBondState() != BluetoothDevice.BOND_BONDED) {
                    failEnrolledRoute(token,
                            IphoneTransportErrorV2.Kind.BOND_TRANSPORT_UNAVAILABLE,
                            "saved post-bond LE facade is not bonded; explicit re-enroll required");
                    return;
                }
                reportPlatformDiagnostic(token,
                        "enrolled_le exact_record=true, pending_recovery="
                                + enrollmentRecordPending + ", direct_locator=true");
                // Android P/KX11 field evidence shows that autoConnect=true can retain a silent
                // wrapper forever after a live ANCS link fails.  Every retry therefore uses the
                // exact saved LE identity actively; the preceding registered owner is still
                // drained before this sole replacement is allocated.
                createGattOwner(token, enrolled, false, null);
                return;
            } catch (RuntimeException unavailable) {
                failEnrolledRoute(token, IphoneTransportErrorV2.Kind.PEER_PROOF_REJECTED,
                        "saved post-bond LE locator is unavailable; explicit re-enroll required");
                return;
            }
        }
        SelectedBondIdentityResolverV2.Candidate attribution = bondAttribution.begin(
                selected, startRequest.selectedSystemBondAddress,
                selectedBond.matches,
                startRequest.helperInstallationId);
        if (!attribution.mayProceedToEncryptedProof()) {
            reportError(IphoneTransportErrorV2.Kind.PEER_PROOF_REJECTED,
                    "selected-bond attribution failed: " + attribution.detail, false);
            postConnected(token, false);
            return;
        }
        SelectedBondLeCapabilityV2.Result capability;
        try {
            capability = SelectedBondLeCapabilityV2.classify(selected.getType());
        } catch (RuntimeException unknown) {
            capability = SelectedBondLeCapabilityV2.Result.UNKNOWN;
        }
        reportPlatformDiagnostic(token, SelectedBondLeCapabilityV2.diagnostic(capability));
        if (capability != SelectedBondLeCapabilityV2.Result.LE_CAPABLE) {
            String detail = SelectedBondLeCapabilityV2.terminalDetail(capability);
            reportError(IphoneTransportErrorV2.Kind.BOND_TRANSPORT_UNAVAILABLE,
                    detail, false);
            apply(AndroidCentralRoute.selectedBondLeUnavailable(state, token, detail));
            return;
        }
        // Android P/KX11 field captures showed the passive autoConnect=true registration staying
        // silent for 15-40 seconds.  The first exact selected-bond attempt is therefore active.
        // This is a directed platform fix, not a guarantee: the same wrapper is still retained
        // and reasserted if Android never supplies its first registration callback.
        createGattOwner(token, selected, false, attribution);
    }

    private void connectMatchedBootstrap(BleRouteToken token) {
        if (ingressFrozen) {
            matchedBootstrapDevice = null;
            matchedBootstrapAttribution = null;
            return;
        }
        BluetoothDevice device = matchedBootstrapDevice;
        SelectedBondIdentityResolverV2.Candidate attribution =
                matchedBootstrapAttribution;
        matchedBootstrapDevice = null;
        matchedBootstrapAttribution = null;
        boolean enrolled = state != null
                && state.acquisitionMode == IphoneAcquisitionModeV2.ENROLLED_LE_IDENTITY;
        if (device == null || (!enrolled && (attribution == null
                || !attribution.mayProceedToEncryptedProof()))) {
            postConnected(token, false);
            return;
        }
        createGattOwner(token, device, false, attribution);
    }

    private void createGattOwner(BleRouteToken token, BluetoothDevice device,
                                 boolean autoConnect,
                                 SelectedBondIdentityResolverV2.Candidate attribution) {
        if (ingressFrozen) return;
        if (owner != null) {
            reportError(IphoneTransportErrorV2.Kind.GATT,
                    "second BluetoothGatt wrapper forbidden", false);
            return;
        }
        GattOwner candidate = new GattOwner(token, device, attribution);
        owner = candidate;
        acquireProcessGateAndConnect(candidate, autoConnect);
    }

    private void acquireProcessGateAndConnect(GattOwner candidate, boolean autoConnect) {
        if (owner != candidate) return;
        if (ingressFrozen || closed || !currentEpoch(candidate.ownerToken)) {
            cancelWaitingGattOwner(candidate);
            return;
        }
        if (adapter == null || !adapter.isEnabled()) {
            cancelWaitingGattOwner(candidate);
            postConnected(candidate.ownerToken, false);
            return;
        }
        if (!ProcessGattRegistrationGateV2.tryAcquire(candidate)) {
            if (!candidate.waitingForProcessGate) {
                reportPlatformDiagnostic(candidate.ownerToken,
                        "process_gate result=queued");
            }
            candidate.waitingForProcessGate = true;
            ProcessGattRegistrationGateV2.whenFree(candidate,
                    () -> dispatchMain(
                            () -> acquireProcessGateAndConnect(candidate, autoConnect)));
            return;
        }
        candidate.waitingForProcessGate = false;
        reportPlatformDiagnostic(candidate.ownerToken,
                "process_gate result=acquired");
        candidate.connectGattStartedAtMillis = android.os.SystemClock.elapsedRealtime();
        try {
            candidate.gatt = candidate.device.connectGatt(
                    context, autoConnect, gattCallback, BluetoothDevice.TRANSPORT_LE);
        } catch (RuntimeException error) {
            reportPlatformDiagnostic(candidate.ownerToken,
                    "connect_gatt returned=exception, type="
                            + error.getClass().getSimpleName());
            owner = null;
            ProcessGattRegistrationGateV2.release(candidate);
            reportError(IphoneTransportErrorV2.Kind.GATT,
                    "connectGatt rejected: " + error.getClass().getSimpleName(), true);
            postConnected(candidate.ownerToken, false);
            return;
        }
        reportPlatformDiagnostic(candidate.ownerToken,
                "connect_gatt returned=" + (candidate.gatt == null ? "null" : "wrapper")
                        + ", autoConnect=" + autoConnect + ", transport=LE");
        if (candidate.gatt == null) {
            owner = null;
            ProcessGattRegistrationGateV2.release(candidate);
            reportError(IphoneTransportErrorV2.Kind.GATT,
                    "connectGatt returned null", true);
            postConnected(candidate.ownerToken, false);
        }
    }

    private void cancelWaitingGattOwner(GattOwner candidate) {
        if (candidate == null || owner != candidate || !candidate.waitingForProcessGate) return;
        ProcessGattRegistrationGateV2.cancelWaiter(candidate);
        candidate.waitingForProcessGate = false;
        owner = null;
        maybeCompleteTeardown();
    }

    private void reassertSameGatt(BleRouteToken token) {
        if (!owns(token) || owner.gatt == null || owner.closing) return;
        try {
            boolean accepted = owner.gatt.connect();
            reportPlatformDiagnostic(token,
                    "same_wrapper_reassert result=" + accepted);
            if (!accepted) {
                reportError(IphoneTransportErrorV2.Kind.GATT,
                        "same BluetoothGatt.connect() reassert returned false", true);
            }
        } catch (RuntimeException error) {
            reportPlatformDiagnostic(token,
                    "same_wrapper_reassert result=exception, type="
                            + error.getClass().getSimpleName());
            reportError(IphoneTransportErrorV2.Kind.GATT,
                    "same-owner reassert failed: " + error.getClass().getSimpleName(), true);
        }
    }

    private void closeGattOwner(BleRouteToken token, String reason) {
        if (owner == null) {
            dispatchMain(this::maybeCompleteTeardown);
            return;
        }
        if (!owner.ownerToken.sameOwner(token)) return;
        if (owner.waitingForProcessGate
                && !ProcessGattRegistrationGateV2.owns(owner)) {
            cancelWaitingGattOwner(owner);
            return;
        }
        if (!owner.callbackObserved && !radioResetProven
                && adapter != null && adapter.isEnabled()) {
            owner.closing = true;
            owner.quarantinedBeforeRegistration = true;
            failPendingRouteControl();
            closeAncsSession();
            reportError(IphoneTransportErrorV2.Kind.TEARDOWN,
                    "OWNER_UNPROVABLE retained; close/new-wrapper churn forbidden", false);
            return;
        }
        owner.closing = true;
        failPendingRouteControl();
        closeAncsSession();
        if (radioResetProven) {
            finishGattClose();
            return;
        }
        retireRegisteredGattOwner(owner);
    }

    private void retireRegisteredGattOwner(GattOwner exact) {
        if (owner != exact) return;
        if (exact.connected && exact.gatt != null && adapter != null && adapter.isEnabled()) {
            try {
                exact.gatt.disconnect();
                return;
            } catch (RuntimeException ignored) {
                // Close the now-invalid Java wrapper below.
            }
        }
        finishGattClose();
    }

    private void finishGattClose() {
        GattOwner closing = owner;
        if (closing == null) {
            dispatchMain(this::maybeCompleteTeardown);
            return;
        }
        owner = null;
        clearTelemetrySubscription();
        clearCarRemoteChannel();
        failPendingRouteControl();
        try {
            if (closing.gatt != null) closing.gatt.close();
        } catch (RuntimeException ignored) {
            // The Java owner is terminal; switch coordinator still performs an owner-count gate.
        }
        ProcessGattRegistrationGateV2.cancelWaiter(closing);
        ProcessGattRegistrationGateV2.release(closing);
        dispatchMain(this::maybeCompleteTeardown);
    }

    private void failPendingRouteControl() {
        PendingGattOperation pending = pendingGatt;
        pendingGatt = null;
        if (pending != null && pending.type == RawOperation.WRITE_ROUTE_CONTROL
                && pending.controlTransmit != null && pending.controlCompletion != null) {
            completeControlTransmit(pending.controlTransmit, pending.controlCompletion,
                    ControlTransmitResult.TERMINAL_FAILURE,
                    roleControl(pending.controlTransmit));
        }
    }

    private void discoverServices(BleRouteToken token) {
        if (!readyForGattOperation(token) || pendingGatt != null) {
            postServices(token, null);
            return;
        }
        if (state != null
                && state.acquisitionMode == IphoneAcquisitionModeV2.ENROLLED_LE_IDENTITY
                && !routineMtuReady) {
            pendingMtuDiscoveryToken = token;
            boolean requested;
            try {
                requested = owner.gatt.requestMtu(ROUTINE_REQUESTED_MTU);
            } catch (RuntimeException rejected) {
                requested = false;
            }
            if (requested) return;
            failRoutineTransport("routine C4 MTU request was not queued");
            return;
        }
        pendingGatt = new PendingGattOperation(
                RawOperation.DISCOVER, token, null, null, null);
        boolean started;
        try {
            started = owner.gatt.discoverServices();
        } catch (RuntimeException error) {
            started = false;
        }
        if (!started) {
            pendingGatt = null;
            postServices(token, null);
        }
    }

    private void readPeerProof(BleRouteToken token) {
        if (!readyForGattOperation(token) || pendingGatt != null) {
            postPeerProof(token, null);
            return;
        }
        BluetoothGattCharacteristic characteristic = characteristic(
                IphoneBleProtocolV2.HELPER_PERIPHERAL_SERVICE,
                IphoneBleProtocolV2.PEER_PROOF_CHARACTERISTIC);
        if (characteristic == null) {
            postPeerProof(token, null);
            return;
        }
        pendingGatt = new PendingGattOperation(
                RawOperation.READ_PEER_PROOF, token, null, characteristic, null);
        if (state != null
                && state.acquisitionMode == IphoneAcquisitionModeV2.ENROLLED_LE_IDENTITY) {
            beginRoutineAuthentication(token);
            return;
        }
        boolean started;
        try {
            started = owner.gatt.readCharacteristic(characteristic);
        } catch (RuntimeException error) {
            started = false;
        }
        if (!started) {
            pendingGatt = null;
            postPeerProof(token, null);
        }
    }

    private void beginRoutineAuthentication(BleRouteToken token) {
        BluetoothGattCharacteristic c4 = characteristic(
                IphoneBleProtocolV2.HELPER_PERIPHERAL_SERVICE,
                IphoneBleProtocolV2.ENROLLMENT_CHARACTERISTIC);
        if (enrollmentRecord == null || c4 == null || !readable(c4) || !writable(c4)) {
            pendingGatt = null;
            failEnrolledRoute(token, IphoneTransportErrorV2.Kind.PROTOCOL,
                    "Helper 52 routine C4 is unavailable; explicit re-enroll required");
            return;
        }
        try {
            routineHello = IphoneLeEnrollmentProtocolV2.encodeRoutineHello(
                    UUID.fromString(preferences.phoneBleV2AndroidInstallationId()),
                    IphoneLeEnrollmentProtocolV2.randomNonce(enrollmentRandom));
            routineRouteToken = token;
            routineStage = RoutineStage.HELLO_WRITE;
            if (writeRoutineC4(c4, routineHello)) return;
        } catch (RuntimeException invalidIdentity) {
            pendingGatt = null;
            failEnrolledRoute(token, IphoneTransportErrorV2.Kind.PROTOCOL,
                    "routine C4 hello construction failed; explicit re-enroll required");
            return;
        }
        failRoutineTransport("routine C4 hello write was not queued");
    }

    private boolean writeRoutineC4(BluetoothGattCharacteristic c4, byte[] frame) {
        if (owner == null || owner.gatt == null) return false;
        try {
            c4.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            c4.setValue(frame);
            return owner.gatt.writeCharacteristic(c4);
        } catch (RuntimeException rejected) {
            return false;
        }
    }

    private boolean readRoutineC4(BluetoothGattCharacteristic c4) {
        if (owner == null || owner.gatt == null) return false;
        try {
            return owner.gatt.readCharacteristic(c4);
        } catch (RuntimeException rejected) {
            return false;
        }
    }

    private void subscribe(BleRouteToken token, RawOperation operation,
                           UUID serviceUuid, UUID characteristicUuid, boolean indication) {
        if (!readyForGattOperation(token) || pendingGatt != null) {
            postSubscription(token, operation, GattResultV2.TRANSIENT_FAILURE);
            return;
        }
        BluetoothGattCharacteristic characteristic = characteristic(serviceUuid,
                characteristicUuid);
        BluetoothGattDescriptor descriptor = characteristic == null ? null
                : characteristic.getDescriptor(AncsProtocol.CLIENT_CONFIGURATION);
        if (characteristic == null || descriptor == null) {
            postSubscription(token, operation, GattResultV2.TRANSIENT_FAILURE);
            return;
        }
        boolean notificationSet;
        try {
            notificationSet = owner.gatt.setCharacteristicNotification(characteristic, true);
        } catch (RuntimeException error) {
            notificationSet = false;
        }
        if (!notificationSet) {
            postSubscription(token, operation, GattResultV2.TRANSIENT_FAILURE);
            return;
        }
        descriptor.setValue(indication
                ? BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                : BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
        pendingGatt = new PendingGattOperation(operation, token, null,
                characteristic, descriptor);
        boolean started;
        try {
            started = owner.gatt.writeDescriptor(descriptor);
        } catch (RuntimeException error) {
            started = false;
        }
        if (!started) {
            pendingGatt = null;
            postSubscription(token, operation, GattResultV2.TRANSIENT_FAILURE);
        }
    }

    private void armRouteTimer(BleRouteToken token, long delayMillis) {
        cancelRouteTimer(token);
        Runnable timer = () -> {
            routeTimers.remove(token);
            AndroidCentralRoute.State current = state;
            if (current == null || current.expected == null
                    || !current.expected.equals(token)) return;
            switch (current.phase) {
                case STARTUP_QUIET:
                    apply(AndroidCentralRoute.startupQuietElapsed(
                            current, token, radioEnabled()));
                    break;
                case WAIT_REASSERT:
                    apply(AndroidCentralRoute.sameOwnerReassertElapsed(current, token));
                    break;
                case RETRY_WAIT:
                    apply(AndroidCentralRoute.retryElapsed(current, token, radioEnabled()));
                    break;
                default:
                    apply(AndroidCentralRoute.deadline(current, token));
                    break;
            }
        };
        routeTimers.put(token, timer);
        main.postDelayed(timer, delayMillis);
    }

    private void cancelRouteTimer(BleRouteToken token) {
        Runnable timer = routeTimers.remove(token);
        if (timer != null) main.removeCallbacks(timer);
    }

    private void postRouteDeadline(BleRouteToken token) {
        dispatchMain(() -> {
            AndroidCentralRoute.State current = state;
            if (current != null) apply(AndroidCentralRoute.deadline(current, token));
        });
    }

    private void postConnected(BleRouteToken token, boolean success) {
        dispatchMain(() -> {
            AndroidCentralRoute.State current = state;
            if (current != null) apply(AndroidCentralRoute.connected(current, token, success));
        });
    }

    private void postServices(BleRouteToken token, IphoneGattInventoryV2 inventory) {
        dispatchMain(() -> {
            AndroidCentralRoute.State current = state;
            if (current != null) {
                apply(AndroidCentralRoute.servicesDiscovered(current, token, inventory));
            }
        });
    }

    private void postPeerProof(BleRouteToken token, IphoneBlePeerProof proof) {
        dispatchMain(() -> {
            AndroidCentralRoute.State current = state;
            if (current != null) apply(AndroidCentralRoute.peerProof(
                    current, token, proof, proof == null
                            ? GattResultV2.TRANSIENT_FAILURE : GattResultV2.SUCCESS));
        });
    }

    private void postSubscription(BleRouteToken token, RawOperation operation,
                                  GattResultV2 result) {
        dispatchMain(() -> completeSubscription(token, operation, result, null));
    }

    private void completeSubscription(BleRouteToken token, RawOperation operation,
                                      GattResultV2 result,
                                      BluetoothGattCharacteristic characteristic) {
        AndroidCentralRoute.State current = state;
        if (current == null) return;
        switch (operation) {
            case SUBSCRIBE_SERVICE_CHANGED:
                apply(AndroidCentralRoute.serviceChangedSubscribed(current, token, result));
                break;
            case SUBSCRIBE_ROUTE_CONTROL:
                apply(AndroidCentralRoute.routeControlSubscribed(current, token, result));
                break;
            case SUBSCRIBE_TELEMETRY:
                BleRouteTransition<AndroidCentralRoute.State> telemetry =
                        AndroidCentralRoute.telemetrySubscribed(current, token, result);
                if (telemetry.accepted && result == GattResultV2.SUCCESS
                        && characteristic != null) {
                    telemetryCharacteristic = characteristic;
                    telemetrySubscriptionToken = token;
                    scheduleTelemetryRefresh();
                }
                apply(telemetry);
                break;
            case SUBSCRIBE_CAR_REMOTE:
                if (result == GattResultV2.SUCCESS && characteristic != null) {
                    carRemoteCharacteristic = characteristic;
                    carRemoteSubscriptionToken = token;
                    drainDeferredAncsAfterGatt();
                    scheduleCarRemoteDrain(0L);
                } else {
                    drainDeferredAncsAfterGatt();
                    scheduleCarRemoteSubscribe(token, 250L);
                }
                break;
            case SUBSCRIBE_NOTIFICATION_SOURCE:
                apply(AndroidCentralRoute.notificationSourceSubscribed(current, token, result));
                break;
            case SUBSCRIBE_DATA_SOURCE:
                apply(AndroidCentralRoute.dataSourceSubscribed(current, token, result));
                break;
            default:
                break;
        }
    }

    private void beginAncsSession(BleRouteToken token) {
        closeAncsSession();
        long sessionId;
        try {
            sessionId = ancsSessionCursor.next();
        } catch (IllegalStateException exhausted) {
            reportError(IphoneTransportErrorV2.Kind.PROTOCOL,
                    exhausted.getMessage(), false);
            poisonCurrentAncsOwner(exhausted.getMessage());
            return;
        }
        ancsSession = new AncsSessionTokenV2(token.epoch, token.ownerId, sessionId);
        ancsTrace.begin(sessionId);
        ancs.begin(ancsSession);
    }

    private void closeAncsSession() {
        cancelHelperIdentityCommit();
        if (ancsSession != null) {
            applyAncsEffects(ancs.close(ancsSession));
            ancsSession = null;
        }
        // A raw write remains in the Android FIFO until its callback. C/A retries rather than
        // overlapping it after ingress freeze.
    }

    private void clearTelemetrySubscription() {
        cancelTelemetryRefresh();
        deferredAncsRequest = null;
        deferredAncsValue = null;
        if (pendingGatt != null && pendingGatt.type == RawOperation.REQUEST_TELEMETRY) {
            pendingGatt = null;
        }
        telemetryCharacteristic = null;
        telemetrySubscriptionToken = null;
        clearStandardBatteryMonitoring();
    }

    /** C5 is optional for Helper 52 compatibility and never participates in ANCS readiness. */
    private void subscribeCarRemoteIfPresent(BleRouteToken token) {
        long backoff = Math.max(0L, carRemoteRetryNotBeforeMillis
                - android.os.SystemClock.elapsedRealtime());
        if (backoff > 0L) {
            scheduleCarRemoteSubscribe(token, backoff);
            return;
        }
        BluetoothGattCharacteristic characteristic = characteristic(
                IphoneBleProtocolV2.HELPER_PERIPHERAL_SERVICE,
                IphoneBleProtocolV2.CAR_REMOTE_CHARACTERISTIC);
        if (characteristic == null || !writable(characteristic)
                || (!indicatable(characteristic) && !notifiable(characteristic))) return;
        if (pendingGatt != null) {
            scheduleCarRemoteSubscribe(token, 150L);
            return;
        }
        subscribe(token, RawOperation.SUBSCRIBE_CAR_REMOTE,
                IphoneBleProtocolV2.HELPER_PERIPHERAL_SERVICE,
                IphoneBleProtocolV2.CAR_REMOTE_CHARACTERISTIC,
                indicatable(characteristic));
    }

    private void scheduleCarRemoteSubscribe(BleRouteToken token, long delayMillis) {
        if (carRemoteSubscribeTimer != null || token == null) return;
        carRemoteSubscribeTimer = () -> {
            carRemoteSubscribeTimer = null;
            if (closed || ingressFrozen || state == null || !state.isReady()
                    || owner == null || !token.sameOwner(owner.ownerToken)
                    || carRemoteSubscriptionToken != null) return;
            subscribeCarRemoteIfPresent(token);
        };
        main.postDelayed(carRemoteSubscribeTimer, Math.max(0L, delayMillis));
    }

    private void clearCarRemoteChannel() {
        if (carRemoteSubscribeTimer != null) main.removeCallbacks(carRemoteSubscribeTimer);
        if (carRemoteDrainTimer != null) main.removeCallbacks(carRemoteDrainTimer);
        if (carRemoteWriteWatchdog != null) main.removeCallbacks(carRemoteWriteWatchdog);
        carRemoteSubscribeTimer = null;
        carRemoteDrainTimer = null;
        carRemoteWriteWatchdog = null;
        carRemoteWrites.clear();
        carRemoteCharacteristic = null;
        carRemoteSubscriptionToken = null;
        if (pendingGatt != null && (pendingGatt.type == RawOperation.WRITE_CAR_REMOTE
                || pendingGatt.type == RawOperation.SUBSCRIBE_CAR_REMOTE)) {
            pendingGatt = null;
        }
    }

    private void scheduleCarRemoteDrain(long delayMillis) {
        if (carRemoteDrainTimer != null) return;
        carRemoteDrainTimer = () -> {
            carRemoteDrainTimer = null;
            drainCarRemoteWrites();
        };
        main.postDelayed(carRemoteDrainTimer, Math.max(0L, delayMillis));
    }

    private void drainCarRemoteWrites() {
        GattOwner exactOwner = owner;
        BluetoothGattCharacteristic characteristic = carRemoteCharacteristic;
        BleRouteToken subscription = carRemoteSubscriptionToken;
        if (closed || ingressFrozen || exactOwner == null || exactOwner.gatt == null
                || !exactOwner.connected || state == null || !state.isReady()
                || characteristic == null || subscription == null
                || !subscription.sameOwner(exactOwner.ownerToken)) {
            carRemoteWrites.clear();
            return;
        }
        if (carRemoteWrites.isEmpty()) return;
        if (pendingGatt != null || !requestTimers.isEmpty()
                || !controlRetryTimers.isEmpty()) {
            scheduleCarRemoteDrain(75L);
            return;
        }
        byte[] frame = carRemoteWrites.poll();
        if (frame == null) return;
        characteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        characteristic.setValue(frame);
        PendingGattOperation pending = new PendingGattOperation(frame, characteristic);
        pendingGatt = pending;
        boolean started;
        try {
            started = exactOwner.gatt.writeCharacteristic(characteristic);
        } catch (RuntimeException rejected) {
            started = false;
        }
        if (!started) {
            pendingGatt = null;
            carRemoteWrites.offerFirst(frame);
            scheduleCarRemoteDrain(150L);
            return;
        }
        carRemoteWriteWatchdog = () -> {
            carRemoteWriteWatchdog = null;
            if (pendingGatt != pending || owner != exactOwner) return;
            // Clearing pendingGatt here used to let ANCS overlap an ATT write whose terminal
            // callback was still unknown.  The next Control Point request was then rejected and
            // the route fell into a passive reconnect.  Treat the FIFO as poisoned, drain this
            // registered owner, and suppress optional C5 resubscription for a bounded backoff.
            carRemoteWrites.clear();
            int shift = Math.max(0, Math.min(3, carRemoteWriteTimeouts));
            carRemoteWriteTimeouts = Math.min(Integer.MAX_VALUE,
                    carRemoteWriteTimeouts + 1);
            long backoff = Math.min(CAR_REMOTE_BACKOFF_MAX_MS,
                    CAR_REMOTE_BACKOFF_MIN_MS << shift);
            carRemoteRetryNotBeforeMillis = android.os.SystemClock.elapsedRealtime() + backoff;
            reportError(IphoneTransportErrorV2.Kind.GATT,
                    "C5 response write callback timed out; exact GATT owner reset", true);
            reportPlatformDiagnostic(exactOwner.ownerToken,
                    "c5_write_timeout owner_reset=true, backoffMs=" + backoff);
            resetCurrentOwner("C5 response write callback timed out; ATT queue unproven");
        };
        main.postDelayed(carRemoteWriteWatchdog, CAR_REMOTE_WRITE_TIMEOUT_MS);
    }

    private void cancelTelemetryRefresh() {
        Runnable timer = telemetryRefreshTimer;
        telemetryRefreshTimer = null;
        if (timer != null) main.removeCallbacks(timer);
    }

    private void scheduleTelemetryRefresh() {
        scheduleTelemetryRefreshAfter(TELEMETRY_REFRESH_QUIET_MS);
    }

    private void scheduleTelemetryRefreshAfter(long delayMillis) {
        cancelTelemetryRefresh();
        if (telemetryCharacteristic == null || telemetrySubscriptionToken == null) return;
        Runnable timer = this::runTelemetryRefresh;
        telemetryRefreshTimer = timer;
        main.postDelayed(timer, Math.max(1L, delayMillis));
    }

    /** Helper 52 refresh request; serialized behind ANCS and never treated as ANCS failure. */
    private void runTelemetryRefresh() {
        telemetryRefreshTimer = null;
        GattOwner exactOwner = owner;
        AndroidCentralRoute.State current = state;
        BleRouteToken subscription = telemetrySubscriptionToken;
        if (closed || ingressFrozen || exactOwner == null || current == null
                || subscription == null || telemetryCharacteristic == null
                || exactOwner.gatt == null || !exactOwner.connected
                || !subscription.sameOwner(exactOwner.ownerToken)
                || !AndroidCentralRoute.acceptsTelemetry(current, subscription)) return;
        if (pendingGatt != null) {
            if (pendingGatt.type == RawOperation.REQUEST_TELEMETRY) {
                pendingGatt = null;
                scheduleTelemetryRefreshAfter(TELEMETRY_REFRESH_RETRY_MS);
                drainDeferredAncsAfterGatt();
            } else {
                scheduleTelemetryRefreshAfter(TELEMETRY_REFRESH_RETRY_MS);
            }
            return;
        }
        if (!requestTimers.isEmpty() || !controlRetryTimers.isEmpty()) {
            scheduleTelemetryRefreshAfter(TELEMETRY_REFRESH_RETRY_MS);
            return;
        }
        BluetoothGattCharacteristic control = characteristic(
                IphoneBleProtocolV2.HELPER_PERIPHERAL_SERVICE,
                IphoneBleProtocolV2.CONTROL_CHARACTERISTIC);
        if (control == null || !writable(control)) {
            // Helper 51 has no R contract. Keep the route alive and retry only at quiet cadence.
            scheduleTelemetryRefresh();
            return;
        }
        byte[] request = new byte[20];
        request[0] = 0x52;
        request[1] = (byte) IphoneBleProtocolV2.VERSION;
        request[2] = 1; // Android-central route.
        byte[] token = IphoneBleControlProtocolV2.newSwitchToken(enrollmentRandom);
        System.arraycopy(token, 0, request, 4, token.length);
        control.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        control.setValue(request);
        PendingGattOperation pending = new PendingGattOperation(
                RawOperation.REQUEST_TELEMETRY, subscription, null, control, null);
        pendingGatt = pending;
        boolean started;
        try {
            started = exactOwner.gatt.writeCharacteristic(control);
        } catch (RuntimeException rejected) {
            started = false;
        }
        if (!started) pendingGatt = null;
        // This watchdog also handles old Helper 51 rejecting/ignoring R without harming ANCS.
        scheduleTelemetryRefreshAfter(TELEMETRY_REFRESH_RETRY_MS);
        if (!started) drainDeferredAncsAfterGatt();
    }

    private void drainDeferredAncsAfterGatt() {
        AncsRequestTokenV2 request = deferredAncsRequest;
        byte[] value = deferredAncsValue;
        deferredAncsRequest = null;
        deferredAncsValue = null;
        if (request != null && value != null) {
            writeControlPoint(request, value);
        } else if (telemetryRefreshTimer == null) {
            scheduleTelemetryRefresh();
        }
    }

    /**
     * Restores HA1159's standard Battery Service path after the v2 ANCS graph is ready.
     * ANCS remains strictly higher priority: this optional probe never starts while a control
     * request or another ATT operation is active.
     */
    private void scheduleStandardBatteryMonitoring(long delayMillis) {
        if (batteryProbeStage >= 4 || ingressFrozen || owner == null || owner.gatt == null) return;
        if (batteryProbeTimer != null) main.removeCallbacks(batteryProbeTimer);
        batteryProbeTimer = () -> {
            batteryProbeTimer = null;
            advanceStandardBatteryMonitoring();
        };
        main.postDelayed(batteryProbeTimer, Math.max(0L, delayMillis));
    }

    private void advanceStandardBatteryMonitoring() {
        if (ingressFrozen || owner == null || owner.gatt == null || !owner.connected
                || state == null || state.phase != AndroidCentralRoute.Phase.READY) return;
        if (batteryStatusCharacteristic == null && batteryLevelCharacteristic == null) {
            BluetoothGattService service = owner.gatt.getService(BATTERY_SERVICE);
            if (service != null) {
                batteryStatusCharacteristic = service.getCharacteristic(BATTERY_LEVEL_STATUS);
                batteryLevelCharacteristic = service.getCharacteristic(BATTERY_LEVEL);
            }
        }
        if (pendingGatt != null || !requestTimers.isEmpty()) {
            if (++batteryProbeRetries <= BATTERY_PROBE_RETRY_LIMIT) {
                scheduleStandardBatteryMonitoring(BATTERY_PROBE_RETRY_MS);
            }
            return;
        }
        batteryProbeRetries = 0;
        while (batteryProbeStage < 4) {
            RawOperation operation;
            BluetoothGattCharacteristic characteristic;
            boolean read;
            switch (batteryProbeStage) {
                case 0:
                    operation = RawOperation.READ_BATTERY_STATUS;
                    characteristic = batteryStatusCharacteristic;
                    read = true;
                    break;
                case 1:
                    operation = RawOperation.READ_BATTERY_LEVEL;
                    characteristic = batteryLevelCharacteristic;
                    read = true;
                    break;
                case 2:
                    operation = RawOperation.SUBSCRIBE_BATTERY_STATUS;
                    characteristic = batteryStatusCharacteristic;
                    read = false;
                    break;
                default:
                    operation = RawOperation.SUBSCRIBE_BATTERY_LEVEL;
                    characteristic = batteryLevelCharacteristic;
                    read = false;
                    break;
            }
            if (characteristic == null || (read ? !readable(characteristic)
                    : !notifiable(characteristic))) {
                batteryProbeStage++;
                continue;
            }
            boolean started = read
                    ? startStandardBatteryRead(operation, characteristic)
                    : startStandardBatterySubscription(operation, characteristic);
            if (started) return;
            batteryProbeStage++;
        }
    }

    private boolean startStandardBatteryRead(RawOperation operation,
                                             BluetoothGattCharacteristic characteristic) {
        PendingGattOperation pending = new PendingGattOperation(operation,
                owner.ownerToken, null, characteristic, null);
        pendingGatt = pending;
        boolean started;
        try {
            started = owner.gatt.readCharacteristic(characteristic);
        } catch (RuntimeException error) {
            started = false;
        }
        if (!started) {
            pendingGatt = null;
            return false;
        }
        armStandardBatteryOperationWatchdog(pending);
        return true;
    }

    private boolean startStandardBatterySubscription(
            RawOperation operation, BluetoothGattCharacteristic characteristic) {
        BluetoothGattDescriptor descriptor =
                characteristic.getDescriptor(AncsProtocol.CLIENT_CONFIGURATION);
        if (descriptor == null) return false;
        boolean notificationSet;
        try {
            notificationSet = owner.gatt.setCharacteristicNotification(characteristic, true);
        } catch (RuntimeException error) {
            notificationSet = false;
        }
        if (!notificationSet) return false;
        descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
        PendingGattOperation pending = new PendingGattOperation(operation,
                owner.ownerToken, null, characteristic, descriptor);
        pendingGatt = pending;
        boolean started;
        try {
            started = owner.gatt.writeDescriptor(descriptor);
        } catch (RuntimeException error) {
            started = false;
        }
        if (!started) {
            pendingGatt = null;
            return false;
        }
        armStandardBatteryOperationWatchdog(pending);
        return true;
    }

    private void armStandardBatteryOperationWatchdog(PendingGattOperation expected) {
        if (batteryProbeTimer != null) main.removeCallbacks(batteryProbeTimer);
        batteryProbeTimer = () -> {
            batteryProbeTimer = null;
            if (pendingGatt != expected || !isStandardBatteryOperation(expected.type)) return;
            pendingGatt = null;
            batteryProbeStage++;
            scheduleStandardBatteryMonitoring(BATTERY_PROBE_RETRY_MS);
        };
        main.postDelayed(batteryProbeTimer, 3_000L);
    }

    private void cancelStandardBatteryOperationWatchdog() {
        if (batteryProbeTimer != null) main.removeCallbacks(batteryProbeTimer);
        batteryProbeTimer = null;
    }

    private void clearStandardBatteryMonitoring() {
        cancelStandardBatteryOperationWatchdog();
        batteryStatusCharacteristic = null;
        batteryLevelCharacteristic = null;
        batteryProbeStage = 0;
        batteryProbeRetries = 0;
        if (pendingGatt != null && isStandardBatteryOperation(pendingGatt.type)) {
            pendingGatt = null;
        }
    }

    private static boolean isStandardBatteryOperation(RawOperation operation) {
        return operation == RawOperation.READ_BATTERY_STATUS
                || operation == RawOperation.READ_BATTERY_LEVEL
                || operation == RawOperation.SUBSCRIBE_BATTERY_STATUS
                || operation == RawOperation.SUBSCRIBE_BATTERY_LEVEL;
    }

    private void acceptStandardBatteryValue(BluetoothGattCharacteristic characteristic,
                                            byte[] value) {
        if (value == null || value.length == 0 || listener == null) return;
        Integer percentage = null;
        String source = "";
        if (characteristic == batteryStatusCharacteristic) {
            percentage = PhoneConnectorPolicy.decodeBatteryLevelStatusLevel(value);
            source = "ble_bas_level_status";
        } else if (characteristic == batteryLevelCharacteristic) {
            int raw = value[0] & 0xff;
            if (raw <= 100) percentage = raw;
            source = "ble_bas";
        }
        if (percentage != null) {
            listener.onStandardBatteryPercentage(percentage, source);
        }
    }

    private void beginHelperIdentityCommit(
            BleRouteToken token,
            IphoneBlePeerProof proof,
            BleRouteTransition<AndroidCentralRoute.State> acceptedTransition) {
        if (pendingHelperIdentity != null || listener == null) {
            AndroidCentralRoute.State current = state;
            if (current != null) {
                apply(AndroidCentralRoute.peerProof(
                        current, token, null, GattResultV2.SUCCESS));
            }
            return;
        }
        cancelRouteTimer(token);
        IphoneTransportSessionListenerV2 exactListener = listener;
        PendingHelperIdentity gate = new PendingHelperIdentity(
                token, proof, acceptedTransition, exactListener);
        gate.deadline = () -> finishHelperIdentityCommit(gate, false);
        pendingHelperIdentity = gate;
        main.postDelayed(gate.deadline, IDENTITY_COMMIT_TIMEOUT_MS);
        try {
            exactListener.offerHelperInstallationId(
                    proof.peerId,
                    accepted -> dispatchMain(
                            () -> finishHelperIdentityCommit(gate, accepted)));
        } catch (RuntimeException rejected) {
            finishHelperIdentityCommit(gate, false);
        }
    }

    private void finishHelperIdentityCommit(PendingHelperIdentity gate, boolean accepted) {
        if (pendingHelperIdentity != gate) return;
        pendingHelperIdentity = null;
        if (gate.deadline != null) main.removeCallbacks(gate.deadline);
        AndroidCentralRoute.State current = state;
        if (current == null || listener != gate.sessionListener || ingressFrozen
                || current.expected == null || !current.expected.equals(gate.token)) {
            return;
        }
        if (accepted) {
            apply(gate.acceptedTransition);
        } else {
            apply(AndroidCentralRoute.peerProof(
                    current, gate.token, null, GattResultV2.SUCCESS));
        }
    }

    private void cancelHelperIdentityCommit() {
        PendingHelperIdentity gate = pendingHelperIdentity;
        pendingHelperIdentity = null;
        if (gate != null && gate.deadline != null) main.removeCallbacks(gate.deadline);
    }

    private void applyAncsEffects(List<AncsConsumerEffectV2> effects) {
        if (effects == null) return;
        for (AncsConsumerEffectV2 effect : effects) {
            switch (effect.type) {
                case WRITE_CONTROL_POINT:
                    writeControlPoint(effect.request, effect.value);
                    break;
                case ARM_REQUEST_DEADLINE:
                    armRequestDeadline(effect.request);
                    break;
                case CANCEL_REQUEST_DEADLINE:
                    cancelRequestDeadline(effect.request);
                    break;
                case ARM_REPLAY_QUIET:
                    armReplayQuiet(effect.generation);
                    break;
                case CANCEL_REPLAY_QUIET:
                    cancelReplayQuiet();
                    break;
                case NOTIFICATION_EVENT:
                    if (listener != null) listener.onNotificationEvent(effect.event);
                    break;
                case NOTIFICATION:
                    if (listener != null) listener.onNotification(effect.notification);
                    break;
                case APP_NAME:
                    if (listener != null) listener.onAppName(effect.appName);
                    break;
                case TERMINATE_SESSION:
                    reportError(IphoneTransportErrorV2.Kind.PROTOCOL, effect.detail, true);
                    poisonCurrentAncsOwner(effect.detail);
                    break;
                case MALFORMED_SOURCE:
                case QUEUE_DROPPED:
                    reportError(IphoneTransportErrorV2.Kind.PROTOCOL, effect.detail, true);
                    break;
                case REPLAY_CHECKPOINT:
                case REPLAY_SUMMARY:
                    // Kept in the shared core for a journal adapter; not a user-visible error.
                    break;
            }
        }
    }

    private void writeControlPoint(AncsRequestTokenV2 request, byte[] value) {
        if (request == null || ancsSession == null || !request.session.equals(ancsSession)
                || owner == null || owner.gatt == null
                || ingressFrozen || state == null
                || state.phase != AndroidCentralRoute.Phase.READY) {
            if (request != null) {
                applyAncsEffects(ancs.controlPointWriteResult(request, false));
            }
            return;
        }
        if (pendingGatt != null) {
            // ANCS is the primary channel. A Notification Source edge may arrive while an
            // optional telemetry, battery or C5 operation owns the one Android ATT slot. Keep
            // the exact request pending behind that slot instead of falsely reporting a Control
            // Point rejection. Its existing 15-second request watchdog remains the hard bound.
            if (pendingGatt.type != RawOperation.WRITE_CONTROL_POINT
                    && pendingGatt.type != RawOperation.WRITE_ROUTE_CONTROL
                    && deferredAncsRequest == null) {
                deferredAncsRequest = request;
                deferredAncsValue = value == null ? null : value.clone();
                return;
            }
            applyAncsEffects(ancs.controlPointWriteResult(request, false));
            return;
        }
        BluetoothGattCharacteristic control = characteristic(
                AncsProtocol.SERVICE, AncsProtocol.CONTROL_POINT);
        if (control == null) {
            applyAncsEffects(ancs.controlPointWriteResult(request, false));
            return;
        }
        control.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        control.setValue(value);
        pendingGatt = new PendingGattOperation(RawOperation.WRITE_CONTROL_POINT,
                null, request, control, null);
        boolean started;
        try {
            started = owner.gatt.writeCharacteristic(control);
        } catch (RuntimeException error) {
            started = false;
        }
        if (!started) {
            pendingGatt = null;
            applyAncsEffects(ancs.controlPointWriteResult(request, false));
        } else {
            reportPlatformDiagnostic(owner.ownerToken,
                    ancsTrace.controlPointWrite(request.kind, true));
        }
    }

    private void transmitControlOnMain(ControlTransmit transmit,
                                       ControlCompletion completion) {
        if (!ownsSwitchSource(transmit.owner()) || !ingressFrozen
                || owner == null || owner.gatt == null || !owner.connected) {
            completeControlTransmit(transmit, completion,
                    ControlTransmitResult.TERMINAL_FAILURE, null);
            return;
        }
        if (pendingGatt != null) {
            completeControlTransmit(transmit, completion,
                    ControlTransmitResult.RETRYABLE_FAILURE, null);
            return;
        }
        BluetoothGattCharacteristic control = characteristic(
                IphoneBleProtocolV2.HELPER_PERIPHERAL_SERVICE,
                IphoneBleProtocolV2.CONTROL_CHARACTERISTIC);
        if (control == null || (control.getProperties()
                & BluetoothGattCharacteristic.PROPERTY_WRITE) == 0) {
            completeControlTransmit(transmit, completion,
                    ControlTransmitResult.TERMINAL_FAILURE, null);
            return;
        }
        IphoneRoleControlV2 roleControl = roleControl(transmit);
        if (roleControl == null) {
            completeControlTransmit(transmit, completion,
                    ControlTransmitResult.TERMINAL_FAILURE, null);
            return;
        }
        byte[] frame = roleControl.type == IphoneRoleControlV2.Type.CLOSE_REQUEST
                ? IphoneBleControlProtocolV2.encodeRoleClose(
                        roleControl.targetMode, roleControl.switchToken())
                : IphoneBleControlProtocolV2.encodeRoleCloseAck(
                        roleControl.targetMode, roleControl.switchToken());
        control.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        control.setValue(frame);
        pendingGatt = new PendingGattOperation(transmit, completion, control);
        boolean started;
        try {
            started = owner.gatt.writeCharacteristic(control);
        } catch (RuntimeException error) {
            started = false;
        }
        if (!started) {
            pendingGatt = null;
            completeControlTransmit(transmit, completion,
                    ControlTransmitResult.RETRYABLE_FAILURE, roleControl);
        } else {
            lastOutboundControl = roleControl;
        }
    }

    private void completeControlTransmit(ControlTransmit transmit,
                                         ControlCompletion completion,
                                         ControlTransmitResult result,
                                         IphoneRoleControlV2 roleControl) {
        main.post(() -> {
            if (roleControl != null && listener != null) {
                listener.onRoleControlWriteResult(
                        roleControl, result == ControlTransmitResult.ACCEPTED);
            }
            completion.onComplete(transmit, result);
        });
    }

    private void cancelControlRetryOnMain(ControlTransmit transmit) {
        Runnable timer = controlRetryTimers.remove(transmit);
        if (timer != null) main.removeCallbacks(timer);
    }

    private IphoneRoleControlV2 roleControl(ControlTransmit transmit) {
        IphoneBleMode target;
        if (transmit.desiredRole()
                == Role.HELPER_CENTRAL_ANDROID_PERIPHERAL) {
            target = IphoneBleMode.ANDROID_PERIPHERAL;
        } else if (transmit.desiredRole()
                == Role.HELPER_PERIPHERAL_ANDROID_CENTRAL) {
            target = IphoneBleMode.ANDROID_CENTRAL;
        } else {
            return null;
        }
        IphoneRoleControlV2.Type type = transmit.frame() == ControlFrame.CLOSE_REQUEST
                ? IphoneRoleControlV2.Type.CLOSE_REQUEST
                : IphoneRoleControlV2.Type.CLOSE_ACK;
        return new IphoneRoleControlV2(
                type, target, transmit.wireToken().bytes());
    }

    private void armRequestDeadline(AncsRequestTokenV2 request) {
        cancelRequestDeadline(request);
        Runnable timer = () -> {
            requestTimers.remove(request);
            applyAncsEffects(ancs.requestDeadline(request));
        };
        requestTimers.put(request, timer);
        main.postDelayed(timer, AncsConsumerCoreV2.REQUEST_TIMEOUT_MS);
    }

    private void cancelRequestDeadline(AncsRequestTokenV2 request) {
        Runnable timer = requestTimers.remove(request);
        if (timer != null) main.removeCallbacks(timer);
    }

    private void armReplayQuiet(long generation) {
        cancelReplayQuiet();
        replayQuietGeneration = generation;
        replayQuietTimer = () -> {
            replayQuietTimer = null;
            if (ancsSession != null) {
                applyAncsEffects(ancs.replayQuiet(ancsSession, generation));
            }
        };
        main.postDelayed(replayQuietTimer, AncsConsumerCoreV2.REPLAY_QUIET_MS);
    }

    private void cancelReplayQuiet() {
        if (replayQuietTimer != null) main.removeCallbacks(replayQuietTimer);
        replayQuietTimer = null;
        replayQuietGeneration = 0L;
    }

    private void poisonCurrentAncsOwner(String reason) {
        resetCurrentOwner("terminal ANCS stream: " + reason);
    }

    private void resetCurrentOwner(String reason) {
        AndroidCentralRoute.State current = state;
        if (current == null || owner == null) return;
        apply(AndroidCentralRoute.linkLost(current, owner.ownerToken, reason));
    }

    private BluetoothGattCharacteristic characteristic(UUID serviceUuid,
                                                        UUID characteristicUuid) {
        if (owner == null || owner.gatt == null) return null;
        BluetoothGattService service = owner.gatt.getService(serviceUuid);
        return service == null ? null : service.getCharacteristic(characteristicUuid);
    }

    private IphoneGattInventoryV2 inventory(BluetoothGatt gatt) {
        BluetoothGattService helper = gatt.getService(
                IphoneBleProtocolV2.HELPER_PERIPHERAL_SERVICE);
        BluetoothGattCharacteristic proof = helper == null ? null
                : helper.getCharacteristic(IphoneBleProtocolV2.PEER_PROOF_CHARACTERISTIC);
        BluetoothGattCharacteristic telemetry = helper == null ? null
                : helper.getCharacteristic(IphoneBleProtocolV2.TELEMETRY_CHARACTERISTIC);
        BluetoothGattCharacteristic routeControl = helper == null ? null
                : helper.getCharacteristic(IphoneBleProtocolV2.CONTROL_CHARACTERISTIC);
        BluetoothGattService ancsService = gatt.getService(AncsProtocol.SERVICE);
        BluetoothGattCharacteristic notificationSource = ancsService == null ? null
                : ancsService.getCharacteristic(AncsProtocol.NOTIFICATION_SOURCE);
        BluetoothGattCharacteristic controlPoint = ancsService == null ? null
                : ancsService.getCharacteristic(AncsProtocol.CONTROL_POINT);
        BluetoothGattCharacteristic dataSource = ancsService == null ? null
                : ancsService.getCharacteristic(AncsProtocol.DATA_SOURCE);
        BluetoothGattService generic = gatt.getService(GENERIC_ATTRIBUTE_SERVICE);
        BluetoothGattCharacteristic serviceChanged = generic == null ? null
                : generic.getCharacteristic(SERVICE_CHANGED);
        return new IphoneGattInventoryV2(
                helper != null,
                proof != null && readable(proof),
                telemetry != null && notifiable(telemetry),
                routeControl != null && writable(routeControl),
                routeControl != null && indicatable(routeControl),
                ancsService != null,
                notificationSource != null && notifiable(notificationSource),
                controlPoint != null && writable(controlPoint),
                dataSource != null && notifiable(dataSource),
                serviceChanged != null && indicatable(serviceChanged));
    }

    private IphoneBlePeerProof decodePeerProof(BluetoothGatt callbackGatt, byte[] value) {
        IphoneBleControlProtocolV2.Frame frame = IphoneBleControlProtocolV2.decode(value);
        if (frame == null || frame.type != IphoneBleControlProtocolV2.Type.PEER_PROOF
                || frame.mode != IphoneBleMode.ANDROID_CENTRAL || owner == null
                || owner.gatt != callbackGatt || !owner.connected
                || !ProcessGattRegistrationGateV2.owns(owner)
                || owner.device.getBondState() != BluetoothDevice.BOND_BONDED) {
            return null;
        }
        UUID installation = IphoneBleControlProtocolV2.installationUuid(frame);
        if (installation == null) return null;
        if (state != null
                && state.acquisitionMode == IphoneAcquisitionModeV2.ENROLLED_LE_IDENTITY) {
            if (routineStage != RoutineStage.PROVEN || enrollmentRecord == null
                    || startRequest == null
                    || selectedSystemBondMatchCount(
                    startRequest.selectedSystemBondAddress) != 1
                    || !samePublicAddress(owner.device.getAddress(),
                    enrollmentRecord.leIdentityAddress)
                    || !installation.equals(enrollmentRecord.helperInstallationId)) {
                return null;
            }
        }
        SelectedBondIdentityResolverV2.Decision attribution = bondAttribution.complete(
                owner.bondAttribution, installation.toString(),
                selectedSystemBondMatchCount(
                        owner.bondAttribution.selectedSystemBondAddress),
                1 /* exact connected callbackGatt plus the sole process-wide GATT lease */,
                true /* successful read of the Helper's encryption-required H attribute */);
        if (!attribution.proven) {
            reportError(IphoneTransportErrorV2.Kind.PEER_PROOF_REJECTED,
                    "selected-bond attribution failed: " + attribution.detail, false);
            return null;
        }
        return new IphoneBlePeerProof(IphoneBleProtocolV2.VERSION,
                IphoneBleMode.ANDROID_CENTRAL,
                BlePeerRole.IPHONE_HELPER_PERIPHERAL, attribution.helperInstallationId,
                frame.telemetrySupported(), frame.ancsSupported(), true);
    }

    private IphoneLeEnrollmentRecordV2 loadEnrollmentRecord(
            String selectedClassicAddress, String helperInstallationId) {
        String androidInstallationId = preferences.phoneBleV2AndroidInstallationId();
        IphoneLeEnrollmentRecordV2 pending = IphoneLeEnrollmentRecordV2.validForSelectedClassic(
                preferences.phoneBleV2PendingEnrollmentRecord(), selectedClassicAddress);
        if (!recordMatchesEnrollmentContext(pending, selectedClassicAddress,
                helperInstallationId, androidInstallationId)) pending = null;
        pendingEnrollmentRecord = pending;
        IphoneLeEnrollmentRecordV2 active = IphoneLeEnrollmentRecordV2.validForSelectedClassic(
                preferences.phoneBleV2EnrollmentRecord(), selectedClassicAddress);
        if (!recordMatchesEnrollmentContext(active, selectedClassicAddress,
                helperInstallationId, androidInstallationId)) active = null;
        activeEnrollmentRecord = active;
        enrollmentRecord = pending != null ? pending : active;
        enrollmentRecordPending = enrollmentRecord != null && enrollmentRecord == pending;
        clearStalePendingAfterActiveProof = false;
        return enrollmentRecord;
    }

    private static boolean recordMatchesEnrollmentContext(
            IphoneLeEnrollmentRecordV2 record, String selectedClassicAddress,
            String helperInstallationId, String androidInstallationId) {
        if (record == null || selectedClassicAddress == null || androidInstallationId == null
                || !record.selectedClassicAddress.equalsIgnoreCase(
                selectedClassicAddress.trim())) return false;
        try {
            if (!record.androidInstallationId.equals(
                    UUID.fromString(androidInstallationId.trim()))) return false;
            if (helperInstallationId == null || helperInstallationId.trim().isEmpty()) {
                return true;
            }
            return record.helperInstallationId.equals(
                    UUID.fromString(helperInstallationId.trim()));
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    private void failEnrolledRoute(BleRouteToken token,
                                   IphoneTransportErrorV2.Kind kind, String detail) {
        reportError(kind, detail, false);
        AndroidCentralRoute.State current = state;
        if (current != null) {
            apply(AndroidCentralRoute.enrolledLeUnavailable(current, token, detail));
        }
    }

    private void resetRoutineAuthState() {
        if (routineSession != null) routineSession.destroy();
        routineSession = null;
        routineHello = null;
        routineConfirm = null;
        routineRouteToken = null;
        routineStage = RoutineStage.NONE;
        pendingMtuDiscoveryToken = null;
        routineMtuReady = false;
        enrollmentRecord = null;
        pendingEnrollmentRecord = null;
        activeEnrollmentRecord = null;
        enrollmentRecordPending = false;
        clearStalePendingAfterActiveProof = false;
    }

    private static boolean samePublicAddress(String left, String right) {
        return left != null && right != null && left.equalsIgnoreCase(right);
    }

    private void maybeCompleteTeardown() {
        boolean ownsDrain = ProcessGattRegistrationGateV2.ownsDrainReservation(
                processGateDrainWaiter);
        if (processGateDrainRetained) return;
        if (scanRunning || owner != null || state == null) {
            if (ownsDrain) {
                ProcessGattRegistrationGateV2.releaseDrainReservation(
                        processGateDrainWaiter);
            }
            return;
        }
        if (!ownsDrain) {
            ProcessGattRegistrationGateV2.whenFreeForDrain(processGateDrainWaiter,
                    () -> dispatchMain(this::maybeCompleteTeardown));
            return;
        }
        if (radioOffTerminalEpoch != null && state.phase == AndroidCentralRoute.Phase.WAIT_RADIO) {
            BleRouteEpoch exact = radioOffTerminalEpoch;
            radioOffTerminalEpoch = null;
            if (listener != null) listener.onLocalTerminal(mode(), exact);
            retainOrReleaseProcessDrain();
            return;
        }
        if (deferredStopTerminalEpoch != null) {
            BleRouteEpoch exact = deferredStopTerminalEpoch;
            deferredStopTerminalEpoch = null;
            if (listener != null) listener.onLocalTerminal(mode(), exact);
            retainOrReleaseProcessDrain();
            return;
        }
        if (state.expected == null) {
            ProcessGattRegistrationGateV2.releaseDrainReservation(processGateDrainWaiter);
            return;
        }
        if (state.phase == AndroidCentralRoute.Phase.RETRY_DRAINING) {
            ProcessGattRegistrationGateV2.releaseDrainReservation(processGateDrainWaiter);
            apply(AndroidCentralRoute.attemptTeardownComplete(state, state.expected));
        } else if (state.phase == AndroidCentralRoute.Phase.STOPPING) {
            apply(AndroidCentralRoute.localTeardownComplete(state, state.expected));
            retainOrReleaseProcessDrain();
        } else {
            ProcessGattRegistrationGateV2.releaseDrainReservation(processGateDrainWaiter);
        }
    }

    /** Keep zero-owner proof across the coordinator callback until this source slot is disposed. */
    private void retainOrReleaseProcessDrain() {
        if (closed) {
            ProcessGattRegistrationGateV2.releaseDrainReservation(processGateDrainWaiter);
        } else {
            processGateDrainRetained = true;
        }
    }

    private void cancelAllTimers() {
        cancelHelperIdentityCommit();
        if (owner != null && owner.waitingForProcessGate
                && !ProcessGattRegistrationGateV2.owns(owner)) {
            cancelWaitingGattOwner(owner);
        }
        for (Runnable timer : routeTimers.values()) main.removeCallbacks(timer);
        routeTimers.clear();
        for (Runnable timer : requestTimers.values()) main.removeCallbacks(timer);
        requestTimers.clear();
        for (Runnable timer : controlRetryTimers.values()) main.removeCallbacks(timer);
        controlRetryTimers.clear();
        cancelReplayQuiet();
        cancelTelemetryRefresh();
        cancelStandardBatteryOperationWatchdog();
        resetRoutineAuthState();
    }

    private boolean readyForGattOperation(BleRouteToken token) {
        return !ingressFrozen && owns(token) && owner.gatt != null && owner.connected;
    }

    private boolean owns(BleRouteToken token) {
        return token != null && owner != null && owner.ownerToken.sameOwner(token)
                && currentEpoch(token);
    }

    private boolean currentEpoch(BleRouteToken token) {
        return token != null && token.mode == mode() && state != null
                && state.epoch.equals(token.epoch);
    }

    private boolean ownsSwitchSource(Owner source) {
        if (source != null && source.equals(restorationOwner)) return true;
        return source != null && state != null
                && source.role() == Role.HELPER_PERIPHERAL_ANDROID_CENTRAL
                && state.epoch.processNonce == source.processNonce()
                && state.epoch.sequence.equals(source.generation().asBigInteger());
    }

    private boolean radioEnabled() {
        return adapter != null && adapter.isEnabled();
    }

    /** Exact count is evidence: zero and non-unique selected bond records both fail closed. */
    private static final class SelectedBondFacade {
        final BluetoothDevice device;
        final int matches;

        SelectedBondFacade(BluetoothDevice device, int matches) {
            this.device = device;
            this.matches = matches;
        }
    }

    private SelectedBondFacade selectedSystemBondFacade(String selectedAddress) {
        if (adapter == null || selectedAddress == null) return new SelectedBondFacade(null, 0);
        try {
            java.util.Set<BluetoothDevice> bonded = adapter.getBondedDevices();
            if (bonded == null) return new SelectedBondFacade(null, 0);
            int matches = 0;
            BluetoothDevice selected = null;
            for (BluetoothDevice device : bonded) {
                if (device != null && device.getBondState() == BluetoothDevice.BOND_BONDED
                        && samePublicAddress(device.getAddress(), selectedAddress)) {
                    matches++;
                    if (selected == null) selected = device;
                }
            }
            return new SelectedBondFacade(selected, matches);
        } catch (RuntimeException unavailable) {
            return new SelectedBondFacade(null, 0);
        }
    }

    private int selectedSystemBondMatchCount(String selectedAddress) {
        return selectedSystemBondFacade(selectedAddress).matches;
    }

    private void publishStatus() {
        if (listener != null && state != null) listener.onStatus(toStatus(state));
    }

    private IphoneTransportStatusV2 toStatus(AndroidCentralRoute.State route) {
        return new IphoneTransportStatusV2(mode(), route.epoch, lifecycle(route.phase),
                route.selectedSystemBondAddress, route.helperInstallationId,
                route.detail, route.consecutiveFailures, recoveryState(route.phase));
    }

    private static IphoneTransportRecoveryStateV2 recoveryState(
            AndroidCentralRoute.Phase phase) {
        switch (phase) {
            case WAIT_RADIO:
                return IphoneTransportRecoveryStateV2.NO_OWNER;
            case WAIT_ANCS:
                return IphoneTransportRecoveryStateV2.WAIT_SERVICE_CHANGED;
            case WAIT_AUTHORIZATION:
                return IphoneTransportRecoveryStateV2.WAIT_AUTHORIZATION;
            case READY:
                return IphoneTransportRecoveryStateV2.READY;
            case NEEDS_FRESH_LINK:
            case STOPPED:
            case FAILED:
                return IphoneTransportRecoveryStateV2.OWNER_DOWN;
            default:
                return IphoneTransportRecoveryStateV2.PROGRESSING;
        }
    }

    private static IphoneTransportLifecycle lifecycle(AndroidCentralRoute.Phase phase) {
        switch (phase) {
            case WAIT_RADIO: return IphoneTransportLifecycle.WAIT_RADIO;
            case STARTUP_QUIET:
            case SCANNING: return IphoneTransportLifecycle.STARTING;
            case CONNECTING:
            case WAIT_REASSERT:
            case WAIT_SYSTEM_CONNECTION: return IphoneTransportLifecycle.CONNECTING;
            case DISCOVERING:
            case SUBSCRIBING_SERVICE_CHANGED:
            case VERIFYING_PEER:
            case WAIT_ANCS: return IphoneTransportLifecycle.AUTHENTICATING;
            case NEEDS_FRESH_LINK: return IphoneTransportLifecycle.FAILED;
            case WAIT_AUTHORIZATION: return IphoneTransportLifecycle.AUTHENTICATING;
            case SUBSCRIBING_ROUTE_CONTROL: return IphoneTransportLifecycle.SUBSCRIBING;
            case SUBSCRIBING_TELEMETRY: return IphoneTransportLifecycle.SUBSCRIBING;
            case SUBSCRIBING_NOTIFICATION_SOURCE:
            case SUBSCRIBING_DATA_SOURCE: return IphoneTransportLifecycle.SUBSCRIBING;
            case READY: return IphoneTransportLifecycle.READY;
            case RETRY_DRAINING:
            case RETRY_WAIT: return IphoneTransportLifecycle.RETRY_WAIT;
            case STOPPING: return IphoneTransportLifecycle.STOPPING;
            case STOPPED: return IphoneTransportLifecycle.STOPPED;
            case FAILED: return IphoneTransportLifecycle.FAILED;
            default: throw new AssertionError(phase);
        }
    }

    private void reportError(IphoneTransportErrorV2.Kind kind, String detail,
                             boolean retryable) {
        if (listener == null || state == null) return;
        listener.onError(new IphoneTransportErrorV2(
                mode(), state.epoch, kind, detail, retryable));
    }

    private void reportPlatformDiagnostic(BleRouteToken token, String detail) {
        if (listener == null || state == null || !currentEpoch(token)) return;
        String bounded = detail == null ? "" : detail.trim();
        if (bounded.length() > PLATFORM_DIAGNOSTIC_LIMIT) {
            bounded = bounded.substring(0, PLATFORM_DIAGNOSTIC_LIMIT);
        }
        listener.onPlatformDiagnostic(mode(), state.epoch, bounded);
    }

    private void assertMain() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            throw new IllegalStateException("Route-A adapter must run on main FIFO");
        }
    }

    /** Avoids placing callback body behind a deadline already queued on the main looper. */
    private void dispatchMain(Runnable callbackBody) {
        if (Looper.myLooper() == main.getLooper()) {
            callbackBody.run();
        } else {
            main.post(callbackBody);
        }
    }

    private boolean isCurrentScanAttempt(ScanAttempt attempt) {
        return attempt != null && !attempt.retired && scanAttempt == attempt
                && scanAttemptFence.owns(attempt)
                && scanner == attempt.exactScanner && scanRunning
                && scanToken != null && scanToken.equals(attempt.token)
                && currentEpoch(attempt.token);
    }

    private void retireScanAttempt(ScanAttempt attempt) {
        if (attempt != null) attempt.retired = true;
        scanAttemptFence.retire(attempt);
        if (scanAttempt == attempt) scanAttempt = null;
        scanRunning = false;
        scanToken = null;
        scanner = null;
    }

    private void handleScanFailure(ScanAttempt attempt, int errorCode) {
        if (!isCurrentScanAttempt(attempt)) return;
        BleRouteToken token = attempt.token;
        retireScanAttempt(attempt);
        reportError(IphoneTransportErrorV2.Kind.GATT,
                "bootstrap scan failed: " + errorCode, true);
        postRouteDeadline(token);
        maybeCompleteTeardown();
    }

    private void handleScanResult(ScanAttempt attempt, ScanResult result) {
        if (ingressFrozen || !isCurrentScanAttempt(attempt)
                || result == null || state == null) return;
        if (state.acquisitionMode == IphoneAcquisitionModeV2.ENROLLED_LE_IDENTITY) {
            IphoneTransportStartRequest request = startRequest;
            if (request == null) return;
            SelectedBondFacade selected = selectedSystemBondFacade(
                    request.selectedSystemBondAddress);
            if (selected.matches != 1 || selected.device == null
                    || selected.device.getBondState() != BluetoothDevice.BOND_BONDED) return;
            IphoneLeEnrollmentRecordV2 record = enrollmentRecord != null
                    ? enrollmentRecord : loadEnrollmentRecord(
                    request.selectedSystemBondAddress, request.helperInstallationId);
            BluetoothDevice device = result.getDevice();
            if (record == null || device == null
                    || !samePublicAddress(device.getAddress(), record.leIdentityAddress)
                    || device.getBondState() != BluetoothDevice.BOND_BONDED) return;
            reportPlatformDiagnostic(attempt.token,
                    "enrolled_recovery_scan exact_saved_identity=true, bonded=true, "
                            + "unique_selected_classic=true, advertisement_untrusted=true");
            IphoneBleAdvertisement advertisement = new IphoneBleAdvertisement(
                    IphoneBleProtocolV2.HELPER_PERIPHERAL_SERVICE,
                    IphoneBleProtocolV2.VERSION, BlePeerRole.IPHONE_HELPER_PERIPHERAL,
                    true, true);
            matchedBootstrapDevice = device;
            matchedBootstrapAttribution = null;
            BleRouteTransition<AndroidCentralRoute.State> transition =
                    AndroidCentralRoute.advertisement(state, attempt.token, advertisement);
            if (!transition.accepted) matchedBootstrapDevice = null;
            else apply(transition);
            return;
        }
        ScanRecord record = result.getScanRecord();
        if (record == null || record.getServiceUuids() == null
                || !record.getServiceUuids().contains(
                        new ParcelUuid(IphoneBleProtocolV2.HELPER_PERIPHERAL_SERVICE))) {
            return;
        }
        SelectedBondIdentityResolverV2.Candidate attribution = bondAttribution.begin(
                result.getDevice(), state.selectedSystemBondAddress,
                selectedSystemBondMatchCount(state.selectedSystemBondAddress),
                state.helperInstallationId);
        if (!attribution.mayProceedToEncryptedProof()) {
            if (attribution.failure
                    == SelectedBondIdentityResolverV2.Failure
                            .ROTATED_ADDRESS_BOOTSTRAP_UNPROVABLE
                    || attribution.failure
                    == SelectedBondIdentityResolverV2.Failure
                            .ROTATED_ADDRESS_PUBLIC_IDENTITY_UNPROVABLE
                    || attribution.failure
                    == SelectedBondIdentityResolverV2.Failure.SELECTED_BOND_MISSING
                    || attribution.failure
                    == SelectedBondIdentityResolverV2.Failure.SELECTED_BOND_AMBIGUOUS) {
                reportError(IphoneTransportErrorV2.Kind.PEER_PROOF_REJECTED,
                        "selected-bond attribution failed: " + attribution.detail, false);
            }
            return;
        }
        IphoneBleAdvertisement advertisement = new IphoneBleAdvertisement(
                IphoneBleProtocolV2.HELPER_PERIPHERAL_SERVICE,
                IphoneBleProtocolV2.VERSION, BlePeerRole.IPHONE_HELPER_PERIPHERAL,
                true, true);
        matchedBootstrapDevice = result.getDevice();
        matchedBootstrapAttribution = attribution;
        BleRouteTransition<AndroidCentralRoute.State> transition =
                AndroidCentralRoute.advertisement(state, attempt.token, advertisement);
        if (!transition.accepted) {
            matchedBootstrapDevice = null;
            matchedBootstrapAttribution = null;
            return;
        }
        apply(transition);
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt gatt, int status,
                                                       int newState) {
            dispatchMain(() -> handleConnectionState(gatt, status, newState));
        }

        @Override public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            dispatchMain(() -> handleServicesDiscovered(gatt, status));
        }

        @Override public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
            dispatchMain(() -> handleMtuChanged(gatt, mtu, status));
        }

        @Override public void onCharacteristicRead(BluetoothGatt gatt,
                                                    BluetoothGattCharacteristic characteristic,
                                                    int status) {
            byte[] value = characteristic == null || characteristic.getValue() == null
                    ? null : characteristic.getValue().clone();
            dispatchMain(() -> handleCharacteristicRead(gatt, characteristic, value, status));
        }

        @Override public void onCharacteristicRead(BluetoothGatt gatt,
                                                    BluetoothGattCharacteristic characteristic,
                                                    byte[] value, int status) {
            byte[] exactValue = value == null ? null : value.clone();
            dispatchMain(() -> handleCharacteristicRead(
                    gatt, characteristic, exactValue, status));
        }

        @Override public void onDescriptorWrite(BluetoothGatt gatt,
                                                 BluetoothGattDescriptor descriptor,
                                                 int status) {
            dispatchMain(() -> handleDescriptorWrite(gatt, descriptor, status));
        }

        @Override public void onCharacteristicWrite(BluetoothGatt gatt,
                                                     BluetoothGattCharacteristic characteristic,
                                                     int status) {
            dispatchMain(() -> handleCharacteristicWrite(gatt, characteristic, status));
        }

        @Override public void onCharacteristicChanged(BluetoothGatt gatt,
                                                       BluetoothGattCharacteristic characteristic) {
            byte[] value = characteristic == null || characteristic.getValue() == null
                    ? null : characteristic.getValue().clone();
            dispatchMain(() -> handleCharacteristicChanged(gatt, characteristic, value));
        }

        @Override public void onCharacteristicChanged(BluetoothGatt gatt,
                                                       BluetoothGattCharacteristic characteristic,
                                                       byte[] value) {
            byte[] exactValue = value == null ? null : value.clone();
            dispatchMain(() -> handleCharacteristicChanged(
                    gatt, characteristic, exactValue));
        }
    };

    private void handleConnectionState(BluetoothGatt callbackGatt, int status, int newState) {
        if (owner == null || owner.gatt != callbackGatt) return;
        GattOwner exact = owner;
        long elapsed = exact.connectGattStartedAtMillis <= 0L ? 0L
                : Math.max(0L, android.os.SystemClock.elapsedRealtime()
                        - exact.connectGattStartedAtMillis);
        reportPlatformDiagnostic(exact.ownerToken,
                "connect_gatt callback elapsedMs=" + elapsed + ", status=" + status
                        + ", newState=" + newState);
        owner.callbackObserved = true;
        owner.connected = newState == BluetoothProfile.STATE_CONNECTED;
        if (!ProcessGattRegistrationGateV2.owns(exact)) {
            // A process-wide radio reset already proved the old registration terminal.
            owner = null;
            try {
                callbackGatt.close();
            } catch (RuntimeException ignored) {
                // The reset fence, not this late callback, is terminal evidence.
            }
            maybeCompleteTeardown();
            return;
        }
        if (owner.closing) {
            if (owner.quarantinedBeforeRegistration
                    || newState == BluetoothProfile.STATE_DISCONNECTED) {
                finishGattClose();
            } else {
                retireRegisteredGattOwner(exact);
            }
            return;
        }
        if (ingressFrozen) return;
        AndroidCentralRoute.State current = state;
        if (current == null) return;
        if (current.acquisitionMode == IphoneAcquisitionModeV2.ENROLLED_LE_IDENTITY
                && status != BluetoothGatt.GATT_SUCCESS) {
            apply(AndroidCentralRoute.linkLost(current, owner.ownerToken,
                    "enrolled locator status failure; retry exact saved owner"));
            return;
        }
        if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
            apply(AndroidCentralRoute.connected(current, owner.ownerToken, true));
        } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
            if (current.phase == AndroidCentralRoute.Phase.CONNECTING
                    || current.phase == AndroidCentralRoute.Phase.WAIT_REASSERT
                    || current.phase == AndroidCentralRoute.Phase.WAIT_SYSTEM_CONNECTION) {
                apply(AndroidCentralRoute.connected(current, owner.ownerToken, false));
            } else {
                apply(AndroidCentralRoute.linkLost(current, owner.ownerToken,
                        "status=" + status));
            }
        }
    }

    private void handleServicesDiscovered(BluetoothGatt callbackGatt, int status) {
        if (ingressFrozen) return;
        PendingGattOperation pending = pendingGatt;
        if (owner == null || owner.gatt != callbackGatt || pending == null
                || pending.type != RawOperation.DISCOVER) return;
        pendingGatt = null;
        IphoneGattInventoryV2 discovered = status == BluetoothGatt.GATT_SUCCESS
                ? inventory(callbackGatt) : null;
        AndroidCentralRoute.State current = state;
        if (current != null) {
            apply(AndroidCentralRoute.servicesDiscovered(
                    current, pending.routeToken, discovered));
        }
    }

    private void handleMtuChanged(BluetoothGatt callbackGatt, int mtu, int status) {
        BleRouteToken token = pendingMtuDiscoveryToken;
        if (owner == null || owner.gatt != callbackGatt || token == null) return;
        pendingMtuDiscoveryToken = null;
        if (status != BluetoothGatt.GATT_SUCCESS) {
            failRoutineTransport("routine C4 MTU negotiation failed: status=" + status);
            return;
        }
        if (mtu < ROUTINE_REQUIRED_MTU) {
            failEnrolledRoute(token, IphoneTransportErrorV2.Kind.PROTOCOL,
                    "routine C4 requires negotiated MTU >=69 before H; "
                            + "explicit re-enroll required");
            return;
        }
        routineMtuReady = true;
        discoverServices(token);
    }

    private static boolean isRoutineC4(BluetoothGattCharacteristic characteristic) {
        return characteristic != null
                && IphoneBleProtocolV2.ENROLLMENT_CHARACTERISTIC.equals(
                characteristic.getUuid());
    }

    private void handleRoutineProofRead(BluetoothGatt callbackGatt,
                                        BluetoothGattCharacteristic c4,
                                        byte[] value, int status) {
        if (owner == null || owner.gatt != callbackGatt || enrollmentRecord == null
                || status != BluetoothGatt.GATT_SUCCESS || value == null) {
            failRoutineTransport("routine C4 Helper proof read failed: status=" + status);
            return;
        }
        IphoneLeEnrollmentProtocolV2.RoutineSession verified =
                verifyRoutineProofForRecord(value, pendingEnrollmentRecord);
        IphoneLeEnrollmentRecordV2 matching = verified == null
                ? null : pendingEnrollmentRecord;
        if (verified == null) {
            verified = verifyRoutineProofForRecord(value, activeEnrollmentRecord);
            matching = verified == null ? null : activeEnrollmentRecord;
        }
        if (verified == null || matching == null) {
            failRoutine("routine C4 Helper key/identity proof rejected");
            return;
        }
        routineSession = verified;
        enrollmentRecord = matching;
        enrollmentRecordPending = matching == pendingEnrollmentRecord;
        clearStalePendingAfterActiveProof = !enrollmentRecordPending
                && pendingEnrollmentRecord != null;
        try {
            routineConfirm = verified.encodeConfirm();
            routineStage = RoutineStage.CONFIRM_WRITE;
            if (writeRoutineC4(c4, routineConfirm)) return;
        } catch (RuntimeException | GeneralSecurityException rejected) {
            failRoutine("routine C4 confirmation construction failed");
            return;
        }
        failRoutineTransport("routine C4 confirmation write was not queued");
    }

    private IphoneLeEnrollmentProtocolV2.RoutineSession verifyRoutineProofForRecord(
            byte[] value, IphoneLeEnrollmentRecordV2 record) {
        if (record == null) return null;
        try {
            IphoneLeEnrollmentProtocolV2.RoutineSession verified =
                    IphoneLeEnrollmentProtocolV2.verifyRoutineProof(
                    routineHello, value, record.copyLongTermKey());
            if (record.helperInstallationId.equals(verified.helperInstallationId)) {
                return verified;
            }
            verified.destroy();
        } catch (RuntimeException | GeneralSecurityException rejected) {
            // Try the other durable record, if any.
        }
        return null;
    }

    private void handleRoutineAckRead(BluetoothGatt callbackGatt,
                                      BluetoothGattCharacteristic c4,
                                      byte[] value, int status) {
        if (owner == null || owner.gatt != callbackGatt
                || status != BluetoothGatt.GATT_SUCCESS || value == null) {
            failRoutineTransport("routine C4 Helper ACK read failed: status=" + status);
            return;
        }
        boolean verified = false;
        if (routineSession != null) {
            try {
                verified = routineSession.verifyAck(routineConfirm, value);
            } catch (RuntimeException | GeneralSecurityException rejected) {
                verified = false;
            }
        }
        if (!verified || enrollmentRecord == null || routineRouteToken == null
                || startRequest == null) {
            failRoutine("routine C4 Helper ACK rejected");
            return;
        }
        routineSession.destroy();
        routineSession = null;
        routineStage = RoutineStage.PROVEN;
        owner.bondAttribution = SelectedBondIdentityResolverV2.beginEnrolled(
                startRequest.selectedSystemBondAddress,
                selectedSystemBondMatchCount(startRequest.selectedSystemBondAddress),
                enrollmentRecord.leIdentityAddress,
                owner.device.getAddress(),
                owner.device.getBondState() == BluetoothDevice.BOND_BONDED,
                enrollmentRecord.helperInstallationId.toString(), true);
        if (!owner.bondAttribution.mayProceedToEncryptedProof()) {
            failRoutine("routine C4 succeeded but enrolled facade attribution failed");
            return;
        }
        PendingGattOperation pending = pendingGatt;
        BluetoothGattCharacteristic encryptedH = pending == null
                ? null : pending.characteristic;
        boolean started = false;
        if (encryptedH != null) {
            try {
                started = owner.gatt.readCharacteristic(encryptedH);
            } catch (RuntimeException rejected) {
                started = false;
            }
        }
        if (!started) {
            failRoutineTransport("encrypted H read was not queued after routine proof");
        }
    }

    private void failRoutine(String detail) {
        BleRouteToken token = routineRouteToken;
        routineStage = RoutineStage.NONE;
        if (routineSession != null) routineSession.destroy();
        routineSession = null;
        routineHello = null;
        routineConfirm = null;
        routineRouteToken = null;
        pendingGatt = null;
        if (token != null) {
            failEnrolledRoute(token, IphoneTransportErrorV2.Kind.PEER_PROOF_REJECTED,
                    detail + "; explicit re-enroll required");
        }
    }

    /**
     * A framework queue/callback failure is not evidence that the enrolled key was rejected.
     * Retire the exact GATT owner and let the reducer retry while preserving enrollment.
     */
    private void failRoutineTransport(String detail) {
        routineStage = RoutineStage.NONE;
        if (routineSession != null) routineSession.destroy();
        routineSession = null;
        routineHello = null;
        routineConfirm = null;
        routineRouteToken = null;
        pendingMtuDiscoveryToken = null;
        pendingGatt = null;
        reportError(IphoneTransportErrorV2.Kind.GATT, detail, true);
        resetCurrentOwner(detail);
    }

    private void handleCharacteristicRead(BluetoothGatt callbackGatt,
                                          BluetoothGattCharacteristic characteristic,
                                          byte[] value, int status) {
        if (ingressFrozen) return;
        if (isRoutineC4(characteristic) && routineStage == RoutineStage.PROOF_READ) {
            handleRoutineProofRead(callbackGatt, characteristic, value, status);
            return;
        }
        if (isRoutineC4(characteristic) && routineStage == RoutineStage.ACK_READ) {
            handleRoutineAckRead(callbackGatt, characteristic, value, status);
            return;
        }
        PendingGattOperation pending = pendingGatt;
        if (owner == null || owner.gatt != callbackGatt || pending == null
                || pending.characteristic != characteristic) return;
        if (pending.type == RawOperation.READ_BATTERY_STATUS
                || pending.type == RawOperation.READ_BATTERY_LEVEL) {
            cancelStandardBatteryOperationWatchdog();
            pendingGatt = null;
            if (status == BluetoothGatt.GATT_SUCCESS) {
                acceptStandardBatteryValue(characteristic, value);
            }
            batteryProbeStage++;
            drainDeferredAncsAfterGatt();
            scheduleStandardBatteryMonitoring(0L);
            return;
        }
        if (pending.type != RawOperation.READ_PEER_PROOF) return;
        pendingGatt = null;
        GattResultV2 result = GattResultV2.fromAndroidStatus(status);
        IphoneBlePeerProof proof = result == GattResultV2.SUCCESS
                ? decodePeerProof(callbackGatt, value) : null;
        if (proof != null && enrollmentRecordPending) {
            String pendingRecord = preferences.phoneBleV2PendingEnrollmentRecord();
            if (pendingRecord.isEmpty()
                    || !preferences.completePhoneBleV2EnrollmentCommit(pendingRecord)) {
                failEnrolledRoute(pending.routeToken,
                        IphoneTransportErrorV2.Kind.PEER_PROOF_REJECTED,
                        "pending routine/H succeeded but local binding promotion failed");
                return;
            }
            enrollmentRecordPending = false;
        } else if (proof != null && clearStalePendingAfterActiveProof) {
            if (!preferences.clearPhoneBleV2PendingEnrollmentRecord()) {
                failEnrolledRoute(pending.routeToken,
                        IphoneTransportErrorV2.Kind.PEER_PROOF_REJECTED,
                        "active routine/H succeeded but stale pending cleanup failed");
                return;
            }
            clearStalePendingAfterActiveProof = false;
        }
        AndroidCentralRoute.State current = state;
        if (current != null) {
            BleRouteTransition<AndroidCentralRoute.State> transition =
                    AndroidCentralRoute.peerProof(
                            current, pending.routeToken, proof, result);
            boolean newlyLearned = transition.accepted && proof != null
                    && current.helperInstallationId.isEmpty()
                    && !transition.state.helperInstallationId.isEmpty();
            if (newlyLearned) {
                beginHelperIdentityCommit(pending.routeToken, proof, transition);
            } else {
                apply(transition);
            }
        }
    }

    private void handleDescriptorWrite(BluetoothGatt callbackGatt,
                                       BluetoothGattDescriptor descriptor, int status) {
        if (ingressFrozen) return;
        PendingGattOperation pending = pendingGatt;
        if (owner == null || owner.gatt != callbackGatt || pending == null
                || pending.descriptor != descriptor) return;
        if (pending.type == RawOperation.SUBSCRIBE_BATTERY_STATUS
                || pending.type == RawOperation.SUBSCRIBE_BATTERY_LEVEL) {
            cancelStandardBatteryOperationWatchdog();
            pendingGatt = null;
            batteryProbeStage++;
            drainDeferredAncsAfterGatt();
            scheduleStandardBatteryMonitoring(0L);
            return;
        }
        if (pending.type != RawOperation.SUBSCRIBE_ROUTE_CONTROL
                && pending.type != RawOperation.SUBSCRIBE_TELEMETRY
                && pending.type != RawOperation.SUBSCRIBE_CAR_REMOTE
                && pending.type != RawOperation.SUBSCRIBE_SERVICE_CHANGED
                && pending.type != RawOperation.SUBSCRIBE_NOTIFICATION_SOURCE
                && pending.type != RawOperation.SUBSCRIBE_DATA_SOURCE) return;
        pendingGatt = null;
        completeSubscription(pending.routeToken, pending.type,
                GattResultV2.fromAndroidStatus(status), pending.characteristic);
    }

    private void handleCharacteristicWrite(BluetoothGatt callbackGatt,
                                            BluetoothGattCharacteristic characteristic,
                                            int status) {
        if (isRoutineC4(characteristic) && routineStage == RoutineStage.HELLO_WRITE) {
            if (owner != null && owner.gatt == callbackGatt
                    && status == BluetoothGatt.GATT_SUCCESS) {
                routineStage = RoutineStage.PROOF_READ;
                if (readRoutineC4(characteristic)) return;
                failRoutineTransport("routine C4 proof read was not queued");
                return;
            }
            failRoutineTransport("routine C4 hello write failed: status=" + status);
            return;
        }
        if (isRoutineC4(characteristic) && routineStage == RoutineStage.CONFIRM_WRITE) {
            if (owner != null && owner.gatt == callbackGatt
                    && status == BluetoothGatt.GATT_SUCCESS) {
                routineStage = RoutineStage.ACK_READ;
                if (readRoutineC4(characteristic)) return;
                failRoutineTransport("routine C4 ACK read was not queued");
                return;
            }
            failRoutineTransport("routine C4 confirm write failed: status=" + status);
            return;
        }
        PendingGattOperation pending = pendingGatt;
        if (owner == null || owner.gatt != callbackGatt || pending == null
                || pending.characteristic != characteristic) return;
        if (ingressFrozen && pending.type != RawOperation.WRITE_ROUTE_CONTROL) {
            if (pending.type == RawOperation.WRITE_CONTROL_POINT
                    || pending.type == RawOperation.REQUEST_TELEMETRY
                    || pending.type == RawOperation.WRITE_CAR_REMOTE) {
                pendingGatt = null;
                if (pending.type == RawOperation.REQUEST_TELEMETRY) {
                    cancelTelemetryRefresh();
                    deferredAncsRequest = null;
                    deferredAncsValue = null;
                }
            }
            return;
        }
        pendingGatt = null;
        if (pending.type == RawOperation.WRITE_CONTROL_POINT) {
            reportPlatformDiagnostic(owner.ownerToken,
                    ancsTrace.controlPointResult(
                    pending.ancsRequest == null ? null : pending.ancsRequest.kind,
                            status));
            applyAncsEffects(ancs.controlPointWriteResult(
                    pending.ancsRequest, status == BluetoothGatt.GATT_SUCCESS));
            if (telemetryRefreshTimer == null) scheduleTelemetryRefresh();
        } else if (pending.type == RawOperation.REQUEST_TELEMETRY) {
            // Keep the request as the one in-flight telemetry waiter until a fresh T arrives.
            if (deferredAncsRequest == null) {
                pendingGatt = pending;
            } else {
                scheduleTelemetryRefreshAfter(TELEMETRY_REFRESH_RETRY_MS);
                drainDeferredAncsAfterGatt();
            }
        } else if (pending.type == RawOperation.WRITE_ROUTE_CONTROL) {
            IphoneRoleControlV2 control = roleControl(pending.controlTransmit);
            completeControlTransmit(pending.controlTransmit, pending.controlCompletion,
                    status == BluetoothGatt.GATT_SUCCESS
                            ? ControlTransmitResult.ACCEPTED
                            : owner.connected
                                ? ControlTransmitResult.RETRYABLE_FAILURE
                                : ControlTransmitResult.TERMINAL_FAILURE,
                    control);
        } else if (pending.type == RawOperation.WRITE_CAR_REMOTE) {
            if (carRemoteWriteWatchdog != null) {
                main.removeCallbacks(carRemoteWriteWatchdog);
                carRemoteWriteWatchdog = null;
            }
            carRemoteWriteTimeouts = 0;
            carRemoteRetryNotBeforeMillis = 0L;
            if (status != BluetoothGatt.GATT_SUCCESS) {
                carRemoteWrites.offerFirst(pending.carRemoteFrame);
                reportError(IphoneTransportErrorV2.Kind.GATT,
                        "C5 response write failed: status=" + status, true);
            }
            drainDeferredAncsAfterGatt();
            scheduleCarRemoteDrain(status == BluetoothGatt.GATT_SUCCESS ? 0L : 150L);
        }
    }

    private void handleCharacteristicChanged(BluetoothGatt callbackGatt,
                                              BluetoothGattCharacteristic characteristic,
                                              byte[] value) {
        if (owner == null || owner.gatt != callbackGatt || characteristic == null
                || state == null) return;
        UUID uuid = characteristic.getUuid();
        if (characteristic == batteryStatusCharacteristic
                || characteristic == batteryLevelCharacteristic) {
            if (!ingressFrozen) acceptStandardBatteryValue(characteristic, value);
        } else if (SERVICE_CHANGED.equals(uuid)) {
            if (ingressFrozen) return;
            pendingGatt = null;
            apply(AndroidCentralRoute.serviceChanged(state, owner.ownerToken));
        } else if (AncsProtocol.NOTIFICATION_SOURCE.equals(uuid) && ancsSession != null) {
            if (ingressFrozen) return;
            reportPlatformDiagnostic(owner.ownerToken, ancsTrace.notificationSource(value));
            applyAncsEffects(ancs.notificationSource(
                    ancsSession, value, android.os.SystemClock.elapsedRealtime()));
        } else if (AncsProtocol.DATA_SOURCE.equals(uuid) && ancsSession != null) {
            if (ingressFrozen) return;
            reportPlatformDiagnostic(owner.ownerToken, ancsTrace.dataSource(value));
            applyAncsEffects(ancs.dataSource(ancsSession, value));
        } else if (IphoneBleProtocolV2.TELEMETRY_CHARACTERISTIC.equals(uuid)) {
            handleTelemetryChanged(characteristic, value);
        } else if (IphoneBleProtocolV2.CONTROL_CHARACTERISTIC.equals(uuid)) {
            handleInboundRoleControl(value);
        } else if (IphoneBleProtocolV2.CAR_REMOTE_CHARACTERISTIC.equals(uuid)) {
            handleInboundCarRemote(characteristic, value);
        }
    }

    private void handleInboundCarRemote(BluetoothGattCharacteristic characteristic,
                                        byte[] value) {
        if (ingressFrozen || state == null || !state.isReady()
                || owner == null || characteristic != carRemoteCharacteristic
                || carRemoteSubscriptionToken == null
                || !carRemoteSubscriptionToken.sameOwner(owner.ownerToken)
                || value == null || value.length != 20 || listener == null) return;
        listener.onCarRemoteFrame(value.clone());
    }

    private void handleTelemetryChanged(BluetoothGattCharacteristic characteristic,
                                        byte[] value) {
        AndroidCentralRoute.State current = state;
        GattOwner exactOwner = owner;
        BleRouteToken exactSubscription = telemetrySubscriptionToken;
        if (ingressFrozen || current == null || exactOwner == null
                || characteristic != telemetryCharacteristic
                || exactSubscription == null
                || !exactSubscription.sameOwner(exactOwner.ownerToken)
                || !AndroidCentralRoute.acceptsTelemetry(current, exactSubscription)) {
            return;
        }
        IphoneTelemetryV2 telemetry = IphoneTelemetryProtocolV2.decode(value);
        if (telemetry == null) {
            reportError(IphoneTransportErrorV2.Kind.PROTOCOL,
                    "malformed Route-A telemetry frame", true);
            return;
        }
        if (listener != null) listener.onTelemetry(telemetry);
        PendingGattOperation pending = pendingGatt;
        if (pending != null && pending.type == RawOperation.REQUEST_TELEMETRY) {
            pendingGatt = null;
            cancelTelemetryRefresh();
            drainDeferredAncsAfterGatt();
        } else {
            scheduleTelemetryRefresh();
        }
    }

    private void handleInboundRoleControl(byte[] value) {
        IphoneBleControlProtocolV2.Frame frame =
                IphoneBleControlProtocolV2.decode(value);
        if (frame == null || frame.type == IphoneBleControlProtocolV2.Type.PEER_PROOF
                || frame.mode != IphoneBleMode.ANDROID_PERIPHERAL
                || listener == null) return;
        IphoneRoleControlV2.Type type =
                frame.type == IphoneBleControlProtocolV2.Type.ROLE_CLOSE
                        ? IphoneRoleControlV2.Type.CLOSE_REQUEST
                        : IphoneRoleControlV2.Type.CLOSE_ACK;
        IphoneRoleControlV2 control = new IphoneRoleControlV2(
                type, frame.mode, frame.payload());
        if (ingressFrozen && !acceptsFrozenControl(control)) return;
        if (control.type == IphoneRoleControlV2.Type.CLOSE_REQUEST
                && lastInboundCloseRequest == null) {
            lastInboundCloseRequest = control;
        }
        listener.onRoleControl(control);
    }

    private boolean acceptsFrozenControl(IphoneRoleControlV2 control) {
        if (control.type == IphoneRoleControlV2.Type.CLOSE_REQUEST) {
            return lastInboundCloseRequest != null
                    && lastInboundCloseRequest.sameTransaction(control);
        }
        return lastOutboundControl != null
                && lastOutboundControl.type == IphoneRoleControlV2.Type.CLOSE_REQUEST
                && lastOutboundControl.sameTransaction(control);
    }

    private static boolean readable(BluetoothGattCharacteristic characteristic) {
        return (characteristic.getProperties()
                & BluetoothGattCharacteristic.PROPERTY_READ) != 0;
    }

    private static boolean writable(BluetoothGattCharacteristic characteristic) {
        return (characteristic.getProperties()
                & (BluetoothGattCharacteristic.PROPERTY_WRITE
                    | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0;
    }

    private static boolean notifiable(BluetoothGattCharacteristic characteristic) {
        return (characteristic.getProperties()
                & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0;
    }

    private static boolean indicatable(BluetoothGattCharacteristic characteristic) {
        return (characteristic.getProperties()
                & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0;
    }
}
