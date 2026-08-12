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
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;

import dezz.status.widget.phone.transport.AncsProtocol;
import dezz.status.widget.phone.transport.v2.AncsConsumerCoreV2;
import dezz.status.widget.phone.transport.v2.AncsConsumerEffectV2;
import dezz.status.widget.phone.transport.v2.AncsRequestTokenV2;
import dezz.status.widget.phone.transport.v2.AncsSessionTokenV2;
import dezz.status.widget.phone.transport.v2.AndroidPeripheralRoute;
import dezz.status.widget.phone.transport.v2.BlePeerRole;
import dezz.status.widget.phone.transport.v2.BleRouteEffect;
import dezz.status.widget.phone.transport.v2.BleRouteEpoch;
import dezz.status.widget.phone.transport.v2.BleRouteToken;
import dezz.status.widget.phone.transport.v2.BleRouteTransition;
import dezz.status.widget.phone.transport.v2.ExactCallbackAttemptFenceV2;
import dezz.status.widget.phone.transport.v2.GattResultV2;
import dezz.status.widget.phone.transport.v2.IphoneAppNameV2;
import dezz.status.widget.phone.transport.v2.IphoneBleControlProtocolV2;
import dezz.status.widget.phone.transport.v2.IphoneBleMode;
import dezz.status.widget.phone.transport.v2.IphoneBlePeerProof;
import dezz.status.widget.phone.transport.v2.IphoneBleProtocolV2;
import dezz.status.widget.phone.transport.v2.IphoneGattInventoryV2;
import dezz.status.widget.phone.transport.v2.IphoneRoleControlV2;
import dezz.status.widget.phone.transport.v2.IphoneTelemetryProtocolV2;
import dezz.status.widget.phone.transport.v2.IphoneTelemetryV2;
import dezz.status.widget.phone.transport.v2.IphoneTransportErrorV2;
import dezz.status.widget.phone.transport.v2.IphoneTransportLifecycle;
import dezz.status.widget.phone.transport.v2.IphoneTransportSessionListenerV2;
import dezz.status.widget.phone.transport.v2.IphoneTransportStartRequest;
import dezz.status.widget.phone.transport.v2.IphoneTransportStatusV2;
import dezz.status.widget.phone.transport.v2.IphoneTransportStopReason;
import dezz.status.widget.phone.transport.v2.IphoneTransportV2;
import dezz.status.widget.phone.transport.v2.IphoneSwitchTransportV2;
import dezz.status.widget.phone.transport.v2.MonotonicSessionCursorV2;
import dezz.status.widget.phone.transport.switching.BleRoleSwitchCoordinator.ControlTransmit;
import dezz.status.widget.phone.transport.switching.BleRoleSwitchCoordinator.Owner;
import dezz.status.widget.phone.transport.switching.BleRoleSwitchReducer.ControlFrame;
import dezz.status.widget.phone.transport.switching.BleRoleSwitchReducer.ControlTransmitResult;
import dezz.status.widget.phone.transport.switching.BleRoleSwitchReducer.Role;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Android framework adapter for Route B (Android Peripheral / Helper Central).
 *
 * <p>The public GATT server and UUID-only advertiser accept one inbound physical facade. The
 * injected reverse observer may adopt exactly one matching ANCS client owner; it must never open
 * a fallback owner. Every callback is serialized on the main FIFO and validated against the
 * reducer's exact epoch/owner/operation token.</p>
 */
public final class AndroidPeripheralTransportV2 implements IphoneSwitchTransportV2 {
    private static final long CONTROL_RETRY_DELAY_MS = 150L;
    private static final long IDENTITY_COMMIT_TIMEOUT_MS = 5_000L;
    private static final UUID GENERIC_ATTRIBUTE_SERVICE =
            UUID.fromString("00001801-0000-1000-8000-00805f9b34fb");
    private static final UUID SERVICE_CHANGED =
            UUID.fromString("00002a05-0000-1000-8000-00805f9b34fb");

    private enum ReverseOperation {
        DISCOVER,
        SUBSCRIBE_SERVICE_CHANGED,
        SUBSCRIBE_NOTIFICATION_SOURCE,
        SUBSCRIBE_DATA_SOURCE,
        WRITE_CONTROL_POINT
    }

    private static final class PendingReverseOperation {
        final ReverseOperation type;
        final BleRouteToken routeToken;
        final AncsRequestTokenV2 ancsRequest;
        final BluetoothGattCharacteristic characteristic;
        final BluetoothGattDescriptor descriptor;

        PendingReverseOperation(ReverseOperation type, BleRouteToken routeToken,
                                AncsRequestTokenV2 ancsRequest,
                                BluetoothGattCharacteristic characteristic,
                                BluetoothGattDescriptor descriptor) {
            this.type = type;
            this.routeToken = routeToken;
            this.ancsRequest = ancsRequest;
            this.characteristic = characteristic;
            this.descriptor = descriptor;
        }
    }

    private static final class PendingHelperIdentity {
        final BleRouteToken token;
        final BluetoothDevice physicalFacade;
        final int requestId;
        final boolean responseNeeded;
        final BleRouteTransition<AndroidPeripheralRoute.State> acceptedTransition;
        final IphoneTransportSessionListenerV2 sessionListener;
        Runnable deadline;

        PendingHelperIdentity(
                BleRouteToken token,
                BluetoothDevice physicalFacade,
                int requestId,
                boolean responseNeeded,
                BleRouteTransition<AndroidPeripheralRoute.State> acceptedTransition,
                IphoneTransportSessionListenerV2 sessionListener) {
            this.token = token;
            this.physicalFacade = physicalFacade;
            this.requestId = requestId;
            this.responseNeeded = responseNeeded;
            this.acceptedTransition = acceptedTransition;
            this.sessionListener = sessionListener;
        }
    }

    /** One immutable framework callback identity per advertiser owner generation. */
    private final class AdvertisingAttempt extends AdvertiseCallback {
        final BleRouteToken token;
        final BluetoothLeAdvertiser exactAdvertiser;
        boolean retired;

        AdvertisingAttempt(BleRouteToken token, BluetoothLeAdvertiser exactAdvertiser) {
            this.token = token;
            this.exactAdvertiser = exactAdvertiser;
        }

        @Override public void onStartSuccess(AdvertiseSettings settingsInEffect) {
            dispatchMain(() -> handleAdvertisingStartSuccess(this));
        }

        @Override public void onStartFailure(int errorCode) {
            dispatchMain(() -> handleAdvertisingStartFailure(this, errorCode));
        }
    }

    /** One immutable callback closure per GATT-server owner generation. */
    private final class ServerAttempt {
        final BleRouteToken ownerToken;
        final List<Runnable> beforeOpenReturned = new ArrayList<>();
        final BluetoothGattServerCallback callback = new BluetoothGattServerCallback() {
            @Override public void onServiceAdded(int status, BluetoothGattService service) {
                dispatch(() -> handleServiceAdded(ServerAttempt.this, status, service));
            }

            @Override public void onConnectionStateChange(
                    BluetoothDevice device, int status, int newState) {
                dispatch(() -> handleInboundConnection(
                        ServerAttempt.this, device, status, newState));
            }

            @Override public void onCharacteristicReadRequest(
                    BluetoothDevice device, int requestId, int offset,
                    BluetoothGattCharacteristic characteristic) {
                dispatch(() -> handleServerRead(
                        device, requestId, offset, characteristic));
            }

            @Override public void onCharacteristicWriteRequest(
                    BluetoothDevice device, int requestId,
                    BluetoothGattCharacteristic characteristic, boolean preparedWrite,
                    boolean responseNeeded, int offset, byte[] value) {
                byte[] exactValue = value == null ? null : value.clone();
                dispatch(() -> handleServerWrite(device, requestId, characteristic,
                        preparedWrite, responseNeeded, offset, exactValue));
            }

            @Override public void onDescriptorReadRequest(
                    BluetoothDevice device, int requestId, int offset,
                    BluetoothGattDescriptor descriptor) {
                dispatch(() -> handleDescriptorRead(
                        device, requestId, offset, descriptor));
            }

            @Override public void onDescriptorWriteRequest(
                    BluetoothDevice device, int requestId, BluetoothGattDescriptor descriptor,
                    boolean preparedWrite, boolean responseNeeded, int offset, byte[] value) {
                byte[] exactValue = value == null ? null : value.clone();
                dispatch(() -> handleDescriptorWrite(device, requestId, descriptor,
                        preparedWrite, responseNeeded, offset, exactValue));
            }

            @Override public void onNotificationSent(BluetoothDevice device, int status) {
                dispatch(() -> handleControlIndicationSent(
                        ServerAttempt.this, device, status));
            }
        };

        BluetoothGattServer exactServer;
        BleRouteToken pendingServiceAddToken;
        boolean openReturned;
        boolean retired;

        ServerAttempt(BleRouteToken ownerToken) {
            this.ownerToken = ownerToken;
        }

        void dispatch(Runnable callbackBody) {
            dispatchMain(() -> {
                if (retired) return;
                if (!openReturned) {
                    beforeOpenReturned.add(callbackBody);
                    return;
                }
                if (isCurrentServerAttempt(this)) callbackBody.run();
            });
        }

        void openCompleted(BluetoothGattServer opened) {
            exactServer = opened;
            openReturned = true;
            List<Runnable> deferred = new ArrayList<>(beforeOpenReturned);
            beforeOpenReturned.clear();
            if (!isCurrentServerAttempt(this)) return;
            for (Runnable callbackBody : deferred) {
                if (!isCurrentServerAttempt(this)) return;
                callbackBody.run();
            }
        }

        void retire() {
            retired = true;
            pendingServiceAddToken = null;
            beforeOpenReturned.clear();
        }
    }

    private final Context context;
    private final Handler main;
    private final BluetoothManager manager;
    private final BluetoothAdapter adapter;
    private final ReverseGattObserverV2 reverseObserver;
    private final UUID androidInstallationId;
    private final AncsConsumerCoreV2 ancs = new AncsConsumerCoreV2();
    private final MonotonicSessionCursorV2 ancsSessionCursor =
            new MonotonicSessionCursorV2();
    private final Map<BleRouteToken, Runnable> routeTimers = new HashMap<>();
    private final Map<AncsRequestTokenV2, Runnable> requestTimers = new HashMap<>();
    private final Map<ControlTransmit, Runnable> controlRetryTimers = new HashMap<>();
    private final ExactCallbackAttemptFenceV2<AdvertisingAttempt> advertisingAttemptFence =
            new ExactCallbackAttemptFenceV2<>();
    private final ExactCallbackAttemptFenceV2<ServerAttempt> serverAttemptFence =
            new ExactCallbackAttemptFenceV2<>();

    private volatile AndroidPeripheralRoute.State state;
    private volatile IphoneTransportSessionListenerV2 listener;
    private volatile BluetoothGattServer server;
    private volatile BluetoothGatt reverseGatt;
    private IphoneTransportStartRequest startRequest;
    private BleRouteToken serverOwnerToken;
    private ServerAttempt serverAttempt;
    private BluetoothLeAdvertiser advertiser;
    private boolean advertising;
    private BleRouteToken advertisingToken;
    private AdvertisingAttempt advertisingAttempt;
    private BluetoothDevice inboundPhysicalFacade;
    private BleRouteToken inboundOwnerToken;
    private BleRouteToken reverseGattOwnerToken;
    private boolean controlIndicationsEnabled;
    private boolean serverCloseRequested;
    private PendingReverseOperation pendingReverse;
    private BleRouteToken observingReverseToken;
    private IphoneRoleControlV2 pendingControlIndication;
    private ServerAttempt pendingControlServerAttempt;
    private ControlTransmit pendingControlTransmit;
    private ControlCompletion pendingControlCompletion;
    private Owner frozenSwitchOwner;
    private FreezeResult frozenSwitchResult;
    private Owner restorationOwner;
    private RestorationDrainCompletion restorationCompletion;
    private boolean restorationTerminalReported;
    private final Object processGateDrainWaiter = new Object();
    private final Object restorationGateWaiter = new Object();
    private boolean processGateDrainRetained;
    private BleRouteEpoch radioOffTerminalEpoch;
    private boolean radioResetProven;
    private PendingHelperIdentity pendingHelperIdentity;
    private AncsSessionTokenV2 ancsSession;
    private Runnable replayQuietTimer;
    private boolean ingressFrozen;
    private IphoneRoleControlV2 lastInboundCloseRequest;
    private IphoneRoleControlV2 lastOutboundControl;
    private boolean closed;

    public AndroidPeripheralTransportV2(Context context, UUID androidInstallationId,
                                        ReverseGattObserverV2 reverseObserver) {
        this.context = Objects.requireNonNull(context, "context").getApplicationContext();
        this.androidInstallationId = Objects.requireNonNull(
                androidInstallationId, "androidInstallationId");
        this.reverseObserver = Objects.requireNonNull(reverseObserver, "reverseObserver");
        this.main = new Handler(Looper.getMainLooper());
        this.manager = (BluetoothManager) this.context.getSystemService(
                Context.BLUETOOTH_SERVICE);
        this.adapter = manager == null ? null : manager.getAdapter();
    }

    @Override public IphoneBleMode mode() {
        return IphoneBleMode.ANDROID_PERIPHERAL;
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
            if (state == null) return;
            if (reason == IphoneTransportStopReason.MODE_SWITCH) {
                apply(AndroidPeripheralRoute.switchStop(state, epoch, reason.name()));
            } else {
                apply(AndroidPeripheralRoute.stop(state, epoch, reason.name()));
            }
        });
    }

    @Override public void radioOff(BleRouteEpoch epoch) {
        Objects.requireNonNull(epoch, "epoch");
        main.post(() -> {
            ingressFrozen = true;
            radioResetProven = true;
            cancelAllTimers();
            stopAdvertisingForFreeze();
            ProcessGattRegistrationGateV2.radioReset();
            if (state != null && state.epoch.equals(epoch)) {
                radioOffTerminalEpoch = epoch;
                BleRouteTransition<AndroidPeripheralRoute.State> transition =
                        AndroidPeripheralRoute.radioOff(state, epoch);
                if (transition.accepted) {
                    apply(transition);
                }
            }
            retireOwnersAfterRadioReset();
            maybeCompleteTeardown();
        });
    }

    public void authorizationChanged(BleRouteEpoch epoch) {
        Objects.requireNonNull(epoch, "epoch");
        main.post(() -> {
            if (state != null && state.epoch.equals(epoch) && state.expected != null) {
                apply(AndroidPeripheralRoute.authorizationChanged(state, state.expected));
            }
        });
    }

    @Override public IphoneTransportStatusV2 status() {
        AndroidPeripheralRoute.State snapshot = state;
        return snapshot == null ? null : toStatus(snapshot);
    }

    @Override public void close() {
        main.post(() -> {
            closed = true;
            ProcessGattRegistrationGateV2.cancelWaiter(restorationGateWaiter);
            ProcessGattRegistrationGateV2.cancelWaiter(processGateDrainWaiter);
            processGateDrainRetained = false;
            if (state == null) {
                cancelAllTimers();
            } else {
                apply(AndroidPeripheralRoute.stop(
                        state, state.epoch, IphoneTransportStopReason.APP_SHUTDOWN.name()));
            }
        });
    }

    /** Independent owner-count evidence for the switch coordinator. */
    public int appOwnedOwnerCount() {
        return server == null && !advertising && advertiser == null
                && serverAttempt == null && advertisingAttempt == null
                && inboundPhysicalFacade == null && reverseGatt == null
                && observingReverseToken == null
                && !ProcessGattRegistrationGateV2.isHeld() ? 0 : 1;
    }

    @Override public void prepareRestorationDrain(
            Owner source, RestorationDrainCompletion completion) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(completion, "completion");
        main.post(() -> {
            boolean success = source.role() == Role.HELPER_CENTRAL_ANDROID_PERIPHERAL
                    && state == null && server == null && serverAttempt == null
                    && advertiser == null && advertisingAttempt == null && !advertising
                    && inboundPhysicalFacade == null && reverseGatt == null
                    && observingReverseToken == null && restorationOwner == null && !closed;
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
                stopAdvertisingForFreeze();
                boolean exactRemoteControl = inboundPhysicalFacade != null
                        && controlIndicationsEnabled
                        && state != null && state.isReady();
                boolean exactNoRemoteOwner = inboundPhysicalFacade == null
                        && !controlIndicationsEnabled
                        && reverseGatt == null
                        && observingReverseToken == null
                        && pendingReverse == null
                        && pendingControlIndication == null
                        && pendingControlTransmit == null;
                if (exactRemoteControl) {
                    result = FreezeResult.FROZEN_WITH_REMOTE_CONTROL;
                } else if (exactNoRemoteOwner) {
                    result = FreezeResult.FROZEN_NO_REMOTE_OWNER;
                }
                if (result != FreezeResult.FAILED) {
                    frozenSwitchOwner = source;
                    frozenSwitchResult = result;
                    closeAncsSession();
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
                if (!restorationTerminalReported && appOwnedOwnerCount() == 0) {
                    restorationTerminalReported = true;
                    RestorationDrainCompletion exact = restorationCompletion;
                    main.post(() -> exact.onLocalTerminal(source));
                }
                return;
            }
            if (!ownsSwitchSource(source) || state == null
                    || !source.equals(frozenSwitchOwner)) return;
            if (frozenSwitchResult == FreezeResult.FROZEN_WITH_REMOTE_CONTROL) {
                apply(AndroidPeripheralRoute.switchStop(state, state.epoch,
                        IphoneTransportStopReason.MODE_SWITCH.name()));
            } else if (frozenSwitchResult == FreezeResult.FROZEN_NO_REMOTE_OWNER) {
                apply(AndroidPeripheralRoute.stop(state, state.epoch,
                        IphoneTransportStopReason.MODE_SWITCH.name()));
                maybeCompleteTeardown();
            }
        });
    }

    @Override public int appOwnedOwnerCount(Owner source) {
        return ownsSwitchSource(source) ? appOwnedOwnerCount() : 1;
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
                || state.phase == AndroidPeripheralRoute.Phase.STOPPED
                || ((state.phase == AndroidPeripheralRoute.Phase.FAILED
                        || state.phase == AndroidPeripheralRoute.Phase.WAIT_RADIO)
                    && appOwnedOwnerCount() == 0);
        if (!reusableTerminal) {
            newListener.onError(new IphoneTransportErrorV2(mode(), request.epoch,
                    IphoneTransportErrorV2.Kind.PROTOCOL,
                    "start rejected while another route epoch owns the adapter", false));
            return;
        }
        this.listener = newListener;
        this.startRequest = request;
        this.ingressFrozen = false;
        this.lastInboundCloseRequest = null;
        this.lastOutboundControl = null;
        this.radioOffTerminalEpoch = null;
        this.radioResetProven = false;
        this.frozenSwitchOwner = null;
        this.frozenSwitchResult = null;
        cancelHelperIdentityCommit(true);
        apply(AndroidPeripheralRoute.start(request));
    }

    private void apply(BleRouteTransition<AndroidPeripheralRoute.State> transition) {
        assertMain();
        if (transition == null || !transition.accepted) return;
        state = transition.state;
        publishStatus();
        for (BleRouteEffect effect : transition.effects) execute(effect);
    }

    private void execute(BleRouteEffect effect) {
        switch (effect.type) {
            case OPEN_GATT_SERVER:
                openServer(effect.token);
                break;
            case ADD_V2_SERVER_SERVICE:
                addServerService(effect.token);
                break;
            case START_ADVERTISING:
                startAdvertising(effect.token);
                break;
            case STOP_ADVERTISING:
                stopAdvertising(effect.token);
                break;
            case BIND_INBOUND_PEER:
                // The callback's BluetoothDevice is captured before the reducer emits this.
                break;
            case OBSERVE_REVERSE_CLIENT:
                observeReverseOwner(effect.token);
                break;
            case DISCOVER_ANCS:
                discoverAncs(effect.token);
                break;
            case SUBSCRIBE_GATT_SERVICE_CHANGED:
                subscribeReverse(effect.token, ReverseOperation.SUBSCRIBE_SERVICE_CHANGED,
                        GENERIC_ATTRIBUTE_SERVICE, SERVICE_CHANGED, true);
                break;
            case SUBSCRIBE_ANCS_NOTIFICATION_SOURCE:
                subscribeReverse(effect.token, ReverseOperation.SUBSCRIBE_NOTIFICATION_SOURCE,
                        AncsProtocol.SERVICE, AncsProtocol.NOTIFICATION_SOURCE, false);
                break;
            case SUBSCRIBE_ANCS_DATA_SOURCE:
                subscribeReverse(effect.token, ReverseOperation.SUBSCRIBE_DATA_SOURCE,
                        AncsProtocol.SERVICE, AncsProtocol.DATA_SOURCE, false);
                break;
            case ARM_ANCS_PARSER:
                beginAncsSession(effect.token);
                break;
            case RESET_SESSION_STATE:
                closeAncsSession();
                break;
            case DISCONNECT_INBOUND_PEER:
                disconnectInbound(effect.token);
                break;
            case CLOSE_REVERSE_CLIENT:
                closeReverseOwner(effect.token);
                break;
            case CLOSE_GATT_SERVER:
                closeServer(effect.token);
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
            case START_SCAN:
            case STOP_SCAN:
            case CONNECT_SELECTED_BOND:
            case CONNECT_GATT:
            case REASSERT_SAME_GATT:
            case CLOSE_GATT:
            case DISCOVER_SERVICES:
            case READ_PEER_PROOF:
            case SUBSCRIBE_TELEMETRY:
            case SUBSCRIBE_ROUTE_CONTROL:
                // Not a Route-B framework operation.
                break;
        }
    }

    private void openServer(BleRouteToken token) {
        if (ingressFrozen || !currentEpoch(token) || manager == null
                || !radioEnabled() || server != null || serverAttempt != null) {
            postServerOpened(token, false);
            return;
        }
        ServerAttempt attempt = new ServerAttempt(token);
        if (!serverAttemptFence.begin(attempt)) {
            postServerOpened(token, false);
            return;
        }
        serverAttempt = attempt;
        serverOwnerToken = token;
        BluetoothGattServer opened;
        try {
            opened = manager.openGattServer(context, attempt.callback);
        } catch (RuntimeException error) {
            opened = null;
        }
        if (serverAttempt != attempt) {
            try {
                if (opened != null) opened.close();
            } catch (RuntimeException ignored) {
                // The attempt was retired while the framework call was returning.
            }
            attempt.retire();
            serverAttemptFence.retire(attempt);
            return;
        }
        server = opened;
        if (opened == null) {
            serverAttempt = null;
            serverOwnerToken = null;
            attempt.retire();
            serverAttemptFence.retire(attempt);
            postServerOpened(token, false);
            return;
        }
        attempt.openCompleted(opened);
        postServerOpened(token, true);
    }

    private void addServerService(BleRouteToken token) {
        if (ingressFrozen || !ownsServer(token) || server == null) {
            postServiceAdded(token, false);
            return;
        }
        BluetoothGattService service = new BluetoothGattService(
                IphoneBleProtocolV2.ANDROID_PERIPHERAL_SERVICE,
                BluetoothGattService.SERVICE_TYPE_PRIMARY);
        BluetoothGattCharacteristic proof = new BluetoothGattCharacteristic(
                IphoneBleProtocolV2.PEER_PROOF_CHARACTERISTIC,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED);
        BluetoothGattCharacteristic control = new BluetoothGattCharacteristic(
                IphoneBleProtocolV2.CONTROL_CHARACTERISTIC,
                BluetoothGattCharacteristic.PROPERTY_WRITE
                        | BluetoothGattCharacteristic.PROPERTY_INDICATE,
                BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED);
        control.addDescriptor(new BluetoothGattDescriptor(
                AncsProtocol.CLIENT_CONFIGURATION,
                BluetoothGattDescriptor.PERMISSION_READ_ENCRYPTED
                        | BluetoothGattDescriptor.PERMISSION_WRITE_ENCRYPTED));
        BluetoothGattCharacteristic telemetry = new BluetoothGattCharacteristic(
                IphoneBleProtocolV2.TELEMETRY_CHARACTERISTIC,
                BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED);
        service.addCharacteristic(proof);
        service.addCharacteristic(control);
        service.addCharacteristic(telemetry);
        ServerAttempt attempt = serverAttempt;
        if (attempt == null || !isCurrentServerAttempt(attempt)) {
            postServiceAdded(token, false);
            return;
        }
        attempt.pendingServiceAddToken = token;
        boolean started;
        try {
            started = server.addService(service);
        } catch (RuntimeException error) {
            started = false;
        }
        if (!started && attempt.pendingServiceAddToken != null
                && attempt.pendingServiceAddToken.equals(token)) {
            attempt.pendingServiceAddToken = null;
            postServiceAdded(token, false);
        }
    }

    private void startAdvertising(BleRouteToken token) {
        if (ingressFrozen || !currentEpoch(token) || adapter == null || !adapter.isEnabled()
                || advertising || state == null || state.advertiserOwner == null
                || !state.advertiserOwner.equals(token)) {
            postAdvertisingStarted(token, false);
            return;
        }
        BluetoothLeAdvertiser exactAdvertiser = adapter.getBluetoothLeAdvertiser();
        if (exactAdvertiser == null) {
            postAdvertisingStarted(token, false);
            return;
        }
        AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setConnectable(true)
                .setTimeout(0)
                .build();
        AdvertiseData data = new AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .setIncludeTxPowerLevel(false)
                .addServiceUuid(new ParcelUuid(IphoneBleProtocolV2.ANDROID_PERIPHERAL_SERVICE))
                .build();
        AdvertisingAttempt attempt = new AdvertisingAttempt(token, exactAdvertiser);
        if (!advertisingAttemptFence.begin(attempt)) {
            postAdvertisingStarted(token, false);
            return;
        }
        advertiser = exactAdvertiser;
        advertisingToken = token;
        advertisingAttempt = attempt;
        try {
            exactAdvertiser.startAdvertising(settings, data, attempt);
        } catch (RuntimeException error) {
            if (advertisingAttempt == attempt) {
                retireAdvertisingAttempt(attempt);
                postAdvertisingStarted(token, false);
            }
        }
    }

    private void stopAdvertising(BleRouteToken token) {
        if (advertisingToken != null && !advertisingToken.sameOwner(token)) return;
        AdvertisingAttempt attempt = advertisingAttempt;
        try {
            if (attempt != null) attempt.exactAdvertiser.stopAdvertising(attempt);
        } catch (RuntimeException ignored) {
            // The adapter-owned advertiser slot is retired below.
        }
        retireAdvertisingAttempt(attempt);
        maybeCompleteTeardown();
    }

    private void stopAdvertisingForFreeze() {
        AdvertisingAttempt attempt = advertisingAttempt;
        try {
            if (attempt != null) attempt.exactAdvertiser.stopAdvertising(attempt);
        } catch (RuntimeException ignored) {
            // The frozen generation rejects any late advertiser callback.
        }
        retireAdvertisingAttempt(attempt);
    }

    private void handleAdvertisingStartSuccess(AdvertisingAttempt attempt) {
        if (!isCurrentAdvertisingAttempt(attempt) || ingressFrozen) return;
        advertising = true;
        AndroidPeripheralRoute.State current = state;
        if (current != null) {
            apply(AndroidPeripheralRoute.advertisingStarted(current, attempt.token, true));
        }
    }

    private void handleAdvertisingStartFailure(AdvertisingAttempt attempt, int errorCode) {
        if (!isCurrentAdvertisingAttempt(attempt)) return;
        BleRouteToken token = attempt.token;
        retireAdvertisingAttempt(attempt);
        if (ingressFrozen) {
            maybeCompleteTeardown();
            return;
        }
        reportError(IphoneTransportErrorV2.Kind.GATT,
                "advertising failed: " + errorCode, true);
        if (state != null) {
            apply(AndroidPeripheralRoute.advertisingStarted(state, token, false));
        }
    }

    private boolean isCurrentAdvertisingAttempt(AdvertisingAttempt attempt) {
        return attempt != null && !attempt.retired && advertisingAttempt == attempt
                && advertisingAttemptFence.owns(attempt)
                && advertiser == attempt.exactAdvertiser
                && advertisingToken != null && advertisingToken.equals(attempt.token)
                && currentEpoch(attempt.token);
    }

    private void retireAdvertisingAttempt(AdvertisingAttempt attempt) {
        if (attempt != null) attempt.retired = true;
        advertisingAttemptFence.retire(attempt);
        if (advertisingAttempt == attempt) advertisingAttempt = null;
        advertising = false;
        advertisingToken = null;
        advertiser = null;
    }

    private boolean isCurrentServerAttempt(ServerAttempt attempt) {
        return attempt != null && !attempt.retired && serverAttempt == attempt
                && serverAttemptFence.owns(attempt)
                && server != null && server == attempt.exactServer
                && serverOwnerToken != null
                && serverOwnerToken.equals(attempt.ownerToken)
                && currentEpoch(attempt.ownerToken);
    }

    private void handleServiceAdded(
            ServerAttempt attempt, int status, BluetoothGattService service) {
        if (ingressFrozen) return;
        if (!isCurrentServerAttempt(attempt)) return;
        BleRouteToken token = attempt.pendingServiceAddToken;
        attempt.pendingServiceAddToken = null;
        AndroidPeripheralRoute.State current = state;
        if (current == null || current.phase != AndroidPeripheralRoute.Phase.ADDING_SERVICE
                || current.expected == null || token == null
                || !current.expected.equals(token) || service == null
                || !IphoneBleProtocolV2.ANDROID_PERIPHERAL_SERVICE.equals(service.getUuid())) {
            return;
        }
        apply(AndroidPeripheralRoute.serviceAdded(
                current, token, status == BluetoothGatt.GATT_SUCCESS));
    }

    private void handleInboundConnection(
            ServerAttempt attempt, BluetoothDevice device, int status, int newState) {
        if (!isCurrentServerAttempt(attempt)
                || device == null || state == null || server == null) return;
        if (newState == BluetoothProfile.STATE_CONNECTED
                && status == BluetoothGatt.GATT_SUCCESS) {
            if (ingressFrozen) {
                cancelForeignConnection(device);
                return;
            }
            if (state.phase != AndroidPeripheralRoute.Phase.ADVERTISING
                    || inboundPhysicalFacade != null) {
                cancelForeignConnection(device);
                return;
            }
            inboundPhysicalFacade = device;
            controlIndicationsEnabled = false;
            BleRouteTransition<AndroidPeripheralRoute.State> transition =
                    AndroidPeripheralRoute.inboundConnected(state, state.serverOwner);
            if (transition.accepted && transition.state.inboundOwner != null) {
                inboundOwnerToken = transition.state.inboundOwner;
            } else {
                inboundPhysicalFacade = null;
                cancelForeignConnection(device);
            }
            apply(transition);
            return;
        }
        if (newState != BluetoothProfile.STATE_DISCONNECTED
                || inboundPhysicalFacade != device) return;
        inboundPhysicalFacade = null;
        BleRouteToken terminalToken = inboundOwnerToken;
        inboundOwnerToken = null;
        controlIndicationsEnabled = false;
        pendingControlIndication = null;
        failPendingControlTransmit(ControlTransmitResult.TERMINAL_FAILURE);
        AndroidPeripheralRoute.State current = state;
        if (current != null && current.inboundOwner != null && terminalToken != null
                && current.inboundOwner.sameOwner(terminalToken)) {
            apply(AndroidPeripheralRoute.inboundDisconnected(
                    current, terminalToken, "status=" + status));
        }
        if (serverCloseRequested) finishServerClose();
        maybeCompleteTeardown();
    }

    private void cancelForeignConnection(BluetoothDevice device) {
        try {
            if (server != null) server.cancelConnection(device);
        } catch (RuntimeException ignored) {
            // It never becomes the reducer's inbound owner.
        }
    }

    private void handleServerRead(BluetoothDevice device, int requestId, int offset,
                                  BluetoothGattCharacteristic characteristic) {
        if (ingressFrozen) {
            sendServerResponse(device, requestId, BluetoothGatt.GATT_READ_NOT_PERMITTED,
                    offset, null);
            return;
        }
        if (!ownsInbound(device) || characteristic == null
                || !IphoneBleProtocolV2.PEER_PROOF_CHARACTERISTIC.equals(
                        characteristic.getUuid())) {
            sendServerResponse(device, requestId, BluetoothGatt.GATT_READ_NOT_PERMITTED,
                    offset, null);
            return;
        }
        byte[] proof = IphoneBleControlProtocolV2.encodePeerProof(
                IphoneBleMode.ANDROID_PERIPHERAL, androidInstallationId, true, true);
        if (offset < 0 || offset > proof.length) {
            sendServerResponse(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET,
                    offset, null);
            return;
        }
        sendServerResponse(device, requestId, BluetoothGatt.GATT_SUCCESS,
                offset, Arrays.copyOfRange(proof, offset, proof.length));
    }

    private void handleServerWrite(BluetoothDevice device, int requestId,
                                   BluetoothGattCharacteristic characteristic,
                                   boolean preparedWrite, boolean responseNeeded,
                                   int offset, byte[] value) {
        if (!ownsInbound(device) || characteristic == null || preparedWrite || offset != 0) {
            if (responseNeeded) sendServerResponse(device, requestId,
                    BluetoothGatt.GATT_WRITE_NOT_PERMITTED, offset, null);
            return;
        }
        if (IphoneBleProtocolV2.CONTROL_CHARACTERISTIC.equals(characteristic.getUuid())) {
            handleControlWrite(device, requestId, responseNeeded, value);
        } else if (IphoneBleProtocolV2.TELEMETRY_CHARACTERISTIC.equals(
                characteristic.getUuid())) {
            handleTelemetryWrite(device, requestId, responseNeeded, value);
        } else if (responseNeeded) {
            sendServerResponse(device, requestId,
                    BluetoothGatt.GATT_WRITE_NOT_PERMITTED, offset, null);
        }
    }

    private void handleDescriptorRead(BluetoothDevice device, int requestId, int offset,
                                      BluetoothGattDescriptor descriptor) {
        if (ingressFrozen) {
            sendServerResponse(device, requestId, BluetoothGatt.GATT_READ_NOT_PERMITTED,
                    offset, null);
            return;
        }
        if (!ownsControlDescriptor(device, descriptor) || offset != 0) {
            sendServerResponse(device, requestId, BluetoothGatt.GATT_READ_NOT_PERMITTED,
                    offset, null);
            return;
        }
        byte[] value = controlIndicationsEnabled
                ? BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                : BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE;
        sendServerResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, value);
    }

    private void handleDescriptorWrite(BluetoothDevice device, int requestId,
                                       BluetoothGattDescriptor descriptor,
                                       boolean preparedWrite, boolean responseNeeded,
                                       int offset, byte[] value) {
        if (ingressFrozen) {
            if (responseNeeded) sendServerResponse(device, requestId,
                    BluetoothGatt.GATT_WRITE_NOT_PERMITTED, offset, null);
            return;
        }
        if (!ownsControlDescriptor(device, descriptor) || preparedWrite || offset != 0
                || !Arrays.equals(value, BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)
                || state == null || state.expected == null) {
            if (responseNeeded) sendServerResponse(device, requestId,
                    BluetoothGatt.GATT_WRITE_NOT_PERMITTED, offset, null);
            return;
        }
        BleRouteTransition<AndroidPeripheralRoute.State> transition =
                AndroidPeripheralRoute.routeControlSubscribed(
                        state, state.expected, GattResultV2.SUCCESS);
        if (!transition.accepted
                || transition.state.phase != AndroidPeripheralRoute.Phase.WAITING_PEER_PROOF) {
            if (responseNeeded) sendServerResponse(device, requestId,
                    BluetoothGatt.GATT_FAILURE, offset, null);
            return;
        }
        // Commit serialized ownership before acknowledging the CCCD write.
        state = transition.state;
        controlIndicationsEnabled = true;
        publishStatus();
        if (responseNeeded) sendServerResponse(device, requestId,
                BluetoothGatt.GATT_SUCCESS, 0, null);
        for (BleRouteEffect effect : transition.effects) execute(effect);
    }

    private void handleControlWrite(BluetoothDevice device, int requestId,
                                    boolean responseNeeded, byte[] value) {
        if (!controlIndicationsEnabled || state == null) {
            if (responseNeeded) sendServerResponse(device, requestId,
                    BluetoothGatt.GATT_WRITE_NOT_PERMITTED, 0, null);
            return;
        }
        IphoneBleControlProtocolV2.Frame frame =
                IphoneBleControlProtocolV2.decode(value);
        if (frame == null) {
            if (responseNeeded) sendServerResponse(device, requestId,
                    BluetoothGatt.GATT_FAILURE, 0, null);
            return;
        }
        if (frame.type == IphoneBleControlProtocolV2.Type.PEER_PROOF) {
            if (ingressFrozen) {
                if (responseNeeded) sendServerResponse(device, requestId,
                        BluetoothGatt.GATT_FAILURE, 0, null);
                return;
            }
            handleInboundPeerProof(device, requestId, responseNeeded, frame);
            return;
        }
        if (frame.mode != IphoneBleMode.ANDROID_CENTRAL
                || state.phase == AndroidPeripheralRoute.Phase.WAITING_CONTROL_SUBSCRIPTION
                || state.phase == AndroidPeripheralRoute.Phase.WAITING_PEER_PROOF
                || state.phase == AndroidPeripheralRoute.Phase.RETRY_DRAINING
                || state.phase == AndroidPeripheralRoute.Phase.RETRY_WAIT
                || state.phase == AndroidPeripheralRoute.Phase.STOPPED
                || state.phase == AndroidPeripheralRoute.Phase.FAILED) {
            if (responseNeeded) sendServerResponse(device, requestId,
                    BluetoothGatt.GATT_FAILURE, 0, null);
            return;
        }
        IphoneRoleControlV2.Type type =
                frame.type == IphoneBleControlProtocolV2.Type.ROLE_CLOSE
                        ? IphoneRoleControlV2.Type.CLOSE_REQUEST
                        : IphoneRoleControlV2.Type.CLOSE_ACK;
        IphoneRoleControlV2 control = new IphoneRoleControlV2(
                type, frame.mode, frame.payload());
        if (ingressFrozen && !acceptsFrozenControl(control)) {
            if (responseNeeded) sendServerResponse(device, requestId,
                    BluetoothGatt.GATT_FAILURE, 0, null);
            return;
        }
        if (control.type == IphoneRoleControlV2.Type.CLOSE_REQUEST
                && lastInboundCloseRequest == null) {
            lastInboundCloseRequest = control;
        }
        // Deliver the exact transaction on the serialized executor before ATT success.
        if (listener != null) listener.onRoleControl(control);
        if (responseNeeded) sendServerResponse(device, requestId,
                BluetoothGatt.GATT_SUCCESS, 0, null);
    }

    private void handleInboundPeerProof(
            BluetoothDevice device, int requestId, boolean responseNeeded,
            IphoneBleControlProtocolV2.Frame frame) {
        if (frame.mode != IphoneBleMode.ANDROID_PERIPHERAL
                || state == null
                || state.phase != AndroidPeripheralRoute.Phase.WAITING_PEER_PROOF
                || state.expected == null) {
            if (responseNeeded) sendServerResponse(device, requestId,
                    BluetoothGatt.GATT_FAILURE, 0, null);
            return;
        }
        UUID helperId = IphoneBleControlProtocolV2.installationUuid(frame);
        boolean encryptedBondOwner = helperId != null
                && device.getBondState() == BluetoothDevice.BOND_BONDED;
        IphoneBlePeerProof proof = helperId == null ? null : new IphoneBlePeerProof(
                IphoneBleProtocolV2.VERSION, IphoneBleMode.ANDROID_PERIPHERAL,
                BlePeerRole.IPHONE_HELPER_CENTRAL, helperId.toString(),
                frame.telemetrySupported(), frame.ancsSupported(), encryptedBondOwner);
        BleRouteToken proofToken = state.expected;
        AndroidPeripheralRoute.State before = state;
        BleRouteTransition<AndroidPeripheralRoute.State> transition =
                AndroidPeripheralRoute.peerProof(before, proofToken, proof);
        boolean accepted = transition.accepted
                && transition.state.phase == AndroidPeripheralRoute.Phase.WAITING_REVERSE_OWNER;
        if (!accepted) {
            if (responseNeeded) sendServerResponse(device, requestId,
                    BluetoothGatt.GATT_FAILURE, 0, null);
            apply(transition);
            return;
        }

        boolean newlyLearned = proof != null && before.helperInstallationId.isEmpty()
                && !transition.state.helperInstallationId.isEmpty();
        if (newlyLearned) {
            beginHelperIdentityCommit(device, requestId, responseNeeded,
                    proofToken, proof.peerId, transition);
            return;
        }

        commitAcceptedPeerProof(device, requestId, responseNeeded, transition);
    }

    private void commitAcceptedPeerProof(
            BluetoothDevice device,
            int requestId,
            boolean responseNeeded,
            BleRouteTransition<AndroidPeripheralRoute.State> transition) {
        // Serialize the accepted state and persistence callback before returning ATT success.
        state = transition.state;
        publishStatus();
        for (BleRouteEffect effect : transition.effects) {
            if (effect.type == BleRouteEffect.Type.REPORT_HELPER_ID_LEARNED) execute(effect);
        }
        if (responseNeeded) sendServerResponse(device, requestId,
                BluetoothGatt.GATT_SUCCESS, 0, null);
        for (BleRouteEffect effect : transition.effects) {
            if (effect.type != BleRouteEffect.Type.REPORT_HELPER_ID_LEARNED) execute(effect);
        }
    }

    private void beginHelperIdentityCommit(
            BluetoothDevice device,
            int requestId,
            boolean responseNeeded,
            BleRouteToken token,
            String helperInstallationId,
            BleRouteTransition<AndroidPeripheralRoute.State> acceptedTransition) {
        if (pendingHelperIdentity != null || listener == null) {
            if (responseNeeded) sendServerResponse(device, requestId,
                    BluetoothGatt.GATT_FAILURE, 0, null);
            AndroidPeripheralRoute.State current = state;
            if (current != null) {
                apply(AndroidPeripheralRoute.peerProof(current, token, null));
            }
            return;
        }
        cancelRouteTimer(token);
        IphoneTransportSessionListenerV2 exactListener = listener;
        PendingHelperIdentity gate = new PendingHelperIdentity(
                token, device, requestId, responseNeeded,
                acceptedTransition, exactListener);
        gate.deadline = () -> finishHelperIdentityCommit(gate, false);
        pendingHelperIdentity = gate;
        main.postDelayed(gate.deadline, IDENTITY_COMMIT_TIMEOUT_MS);
        try {
            exactListener.offerHelperInstallationId(
                    helperInstallationId,
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
        AndroidPeripheralRoute.State current = state;
        boolean exact = current != null && listener == gate.sessionListener && !ingressFrozen
                && inboundPhysicalFacade == gate.physicalFacade
                && current.expected != null && current.expected.equals(gate.token);
        if (!exact || !accepted) {
            if (gate.responseNeeded) sendServerResponse(
                    gate.physicalFacade, gate.requestId,
                    BluetoothGatt.GATT_FAILURE, 0, null);
            if (exact) {
                apply(AndroidPeripheralRoute.peerProof(current, gate.token, null));
            }
            return;
        }
        commitAcceptedPeerProof(gate.physicalFacade, gate.requestId,
                gate.responseNeeded, gate.acceptedTransition);
    }

    private void cancelHelperIdentityCommit(boolean respondFailure) {
        PendingHelperIdentity gate = pendingHelperIdentity;
        pendingHelperIdentity = null;
        if (gate == null) return;
        if (gate.deadline != null) main.removeCallbacks(gate.deadline);
        if (respondFailure && gate.responseNeeded) {
            sendServerResponse(gate.physicalFacade, gate.requestId,
                    BluetoothGatt.GATT_FAILURE, 0, null);
        }
    }

    private void handleTelemetryWrite(BluetoothDevice device, int requestId,
                                      boolean responseNeeded, byte[] value) {
        IphoneTelemetryV2 telemetry = IphoneTelemetryProtocolV2.decode(value);
        boolean accepted = telemetry != null && state != null
                && !ingressFrozen
                && AndroidPeripheralRoute.acceptsTelemetry(state, state.inboundOwner)
                && device.getBondState() == BluetoothDevice.BOND_BONDED;
        if (accepted && listener != null) listener.onTelemetry(telemetry);
        if (responseNeeded) sendServerResponse(device, requestId,
                accepted ? BluetoothGatt.GATT_SUCCESS : BluetoothGatt.GATT_FAILURE,
                0, null);
    }

    private boolean ownsInbound(BluetoothDevice device) {
        return device != null && device == inboundPhysicalFacade
                && state != null && state.inboundOwner != null && server != null;
    }

    private boolean ownsControlDescriptor(BluetoothDevice device,
                                          BluetoothGattDescriptor descriptor) {
        return ownsInbound(device) && descriptor != null
                && AncsProtocol.CLIENT_CONFIGURATION.equals(descriptor.getUuid())
                && descriptor.getCharacteristic() != null
                && IphoneBleProtocolV2.CONTROL_CHARACTERISTIC.equals(
                        descriptor.getCharacteristic().getUuid());
    }

    private void sendServerResponse(BluetoothDevice device, int requestId, int status,
                                    int offset, byte[] value) {
        try {
            if (server != null && device != null) {
                server.sendResponse(device, requestId, status, offset, value);
            }
        } catch (RuntimeException ignored) {
            // The exact inbound owner may have become terminal while this callback was serialized.
        }
    }

    private void observeReverseOwner(BleRouteToken token) {
        if (ingressFrozen || !currentEpoch(token) || inboundPhysicalFacade == null
                || reverseGatt != null || observingReverseToken != null) {
            postReverseObserved(token, false, false);
            return;
        }
        observingReverseToken = token;
        try {
            reverseObserver.observe(token, inboundPhysicalFacade, reverseGattCallback,
                    reverseListener);
        } catch (RuntimeException error) {
            observingReverseToken = null;
            reportError(IphoneTransportErrorV2.Kind.GATT,
                    "reverse observer rejected: " + error.getClass().getSimpleName(), true);
            postReverseObserved(token, false, false);
        }
    }

    private final ReverseGattObserverV2.Listener reverseListener =
            new ReverseGattObserverV2.Listener() {
        @Override public void onObserved(BleRouteToken token, BluetoothGatt gatt,
                                         boolean sameCapturedInboundPhysicalFacade,
                                         boolean exactlyOneOwner) {
            dispatchMain(() -> handleReverseObserved(token, gatt,
                    sameCapturedInboundPhysicalFacade, exactlyOneOwner));
        }

        @Override public void onUnavailable(BleRouteToken token, String detail) {
            dispatchMain(() -> {
                if (observingReverseToken == null
                        || !observingReverseToken.equals(token)) return;
                observingReverseToken = null;
                reportError(IphoneTransportErrorV2.Kind.GATT,
                        "reverse observer unavailable: " + safe(detail), true);
                postReverseObserved(token, false, false);
            });
        }
    };

    private void handleReverseObserved(BleRouteToken token, BluetoothGatt gatt,
                                       boolean sameCapturedInboundPhysicalFacade,
                                       boolean exactlyOneOwner) {
        if (ingressFrozen) {
            if (observingReverseToken != null && observingReverseToken.equals(token)) {
                observingReverseToken = null;
            }
            if (gatt != null) reverseObserver.closeOnly(token, gatt);
            return;
        }
        if (observingReverseToken == null || !observingReverseToken.equals(token)
                || state == null || state.expected == null
                || !state.expected.equals(token)) {
            if (gatt != null) reverseObserver.closeOnly(token, gatt);
            return;
        }
        observingReverseToken = null;
        if (gatt == null || !sameCapturedInboundPhysicalFacade || !exactlyOneOwner) {
            if (gatt != null) reverseObserver.closeOnly(token, gatt);
            postReverseObserved(token,
                    sameCapturedInboundPhysicalFacade, exactlyOneOwner);
            return;
        }
        reverseGatt = gatt;
        reverseGattOwnerToken = token;
        BleRouteTransition<AndroidPeripheralRoute.State> transition =
                AndroidPeripheralRoute.reverseOwnerObserved(state, token,
                        sameCapturedInboundPhysicalFacade, exactlyOneOwner);
        if (!transition.accepted) {
            reverseGatt = null;
            reverseGattOwnerToken = null;
            reverseObserver.closeOnly(token, gatt);
            return;
        }
        apply(transition);
    }

    private void postReverseObserved(BleRouteToken token,
                                     boolean samePhysicalFacade,
                                     boolean exactlyOneOwner) {
        dispatchMain(() -> {
            AndroidPeripheralRoute.State current = state;
            if (current != null) {
                apply(AndroidPeripheralRoute.reverseOwnerObserved(
                        current, token, samePhysicalFacade, exactlyOneOwner));
            }
        });
    }

    private void discoverAncs(BleRouteToken token) {
        if (!readyForReverseOperation(token) || pendingReverse != null) {
            postAncsDiscovered(token, null);
            return;
        }
        pendingReverse = new PendingReverseOperation(
                ReverseOperation.DISCOVER, token, null, null, null);
        boolean started;
        try {
            started = reverseGatt.discoverServices();
        } catch (RuntimeException error) {
            started = false;
        }
        if (!started) {
            pendingReverse = null;
            postAncsDiscovered(token, null);
        }
    }

    private void subscribeReverse(BleRouteToken token, ReverseOperation operation,
                                  UUID serviceUuid, UUID characteristicUuid,
                                  boolean indication) {
        if (!readyForReverseOperation(token) || pendingReverse != null) {
            postReverseSubscription(token, operation, GattResultV2.TRANSIENT_FAILURE);
            return;
        }
        BluetoothGattCharacteristic characteristic = reverseCharacteristic(
                serviceUuid, characteristicUuid);
        BluetoothGattDescriptor descriptor = characteristic == null ? null
                : characteristic.getDescriptor(AncsProtocol.CLIENT_CONFIGURATION);
        if (characteristic == null || descriptor == null) {
            postReverseSubscription(token, operation, GattResultV2.TRANSIENT_FAILURE);
            return;
        }
        boolean notificationSet;
        try {
            notificationSet = reverseGatt.setCharacteristicNotification(
                    characteristic, true);
        } catch (RuntimeException error) {
            notificationSet = false;
        }
        if (!notificationSet) {
            postReverseSubscription(token, operation, GattResultV2.TRANSIENT_FAILURE);
            return;
        }
        descriptor.setValue(indication
                ? BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                : BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
        pendingReverse = new PendingReverseOperation(
                operation, token, null, characteristic, descriptor);
        boolean started;
        try {
            started = reverseGatt.writeDescriptor(descriptor);
        } catch (RuntimeException error) {
            started = false;
        }
        if (!started) {
            pendingReverse = null;
            postReverseSubscription(token, operation, GattResultV2.TRANSIENT_FAILURE);
        }
    }

    private BluetoothGattCharacteristic reverseCharacteristic(UUID serviceUuid,
                                                               UUID characteristicUuid) {
        if (reverseGatt == null) return null;
        BluetoothGattService service = reverseGatt.getService(serviceUuid);
        return service == null ? null : service.getCharacteristic(characteristicUuid);
    }

    private IphoneGattInventoryV2 reverseInventory(BluetoothGatt gatt) {
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
                false, false, false, false, false,
                ancsService != null,
                notificationSource != null && notifiable(notificationSource),
                controlPoint != null && writable(controlPoint),
                dataSource != null && notifiable(dataSource),
                serviceChanged != null && indicatable(serviceChanged));
    }

    private void postAncsDiscovered(BleRouteToken token, IphoneGattInventoryV2 inventory) {
        dispatchMain(() -> {
            if (state != null) {
                apply(AndroidPeripheralRoute.ancsDiscovered(state, token, inventory));
            }
        });
    }

    private void postReverseSubscription(BleRouteToken token, ReverseOperation operation,
                                         GattResultV2 result) {
        dispatchMain(() -> completeReverseSubscription(token, operation, result));
    }

    private void completeReverseSubscription(BleRouteToken token,
                                             ReverseOperation operation,
                                             GattResultV2 result) {
        if (state == null) return;
        switch (operation) {
            case SUBSCRIBE_SERVICE_CHANGED:
                apply(AndroidPeripheralRoute.serviceChangedSubscribed(state, token, result));
                break;
            case SUBSCRIBE_NOTIFICATION_SOURCE:
                apply(AndroidPeripheralRoute.notificationSourceSubscribed(state, token, result));
                break;
            case SUBSCRIBE_DATA_SOURCE:
                apply(AndroidPeripheralRoute.dataSourceSubscribed(state, token, result));
                break;
            default:
                break;
        }
    }

    private final BluetoothGattCallback reverseGattCallback = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt gatt, int status,
                                                       int newState) {
            dispatchMain(() -> handleReverseConnection(gatt, status, newState));
        }

        @Override public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            dispatchMain(() -> handleReverseServices(gatt, status));
        }

        @Override public void onDescriptorWrite(BluetoothGatt gatt,
                                                 BluetoothGattDescriptor descriptor,
                                                 int status) {
            dispatchMain(() -> handleReverseDescriptorWrite(gatt, descriptor, status));
        }

        @Override public void onCharacteristicWrite(BluetoothGatt gatt,
                                                     BluetoothGattCharacteristic characteristic,
                                                     int status) {
            dispatchMain(() -> handleReverseCharacteristicWrite(
                    gatt, characteristic, status));
        }

        @Override public void onCharacteristicChanged(BluetoothGatt gatt,
                                                       BluetoothGattCharacteristic characteristic) {
            byte[] value = characteristic == null || characteristic.getValue() == null
                    ? null : characteristic.getValue().clone();
            dispatchMain(() -> handleReverseCharacteristicChanged(
                    gatt, characteristic, value));
        }

        @Override public void onCharacteristicChanged(BluetoothGatt gatt,
                                                       BluetoothGattCharacteristic characteristic,
                                                       byte[] value) {
            byte[] exactValue = value == null ? null : value.clone();
            dispatchMain(() -> handleReverseCharacteristicChanged(
                    gatt, characteristic, exactValue));
        }
    };

    private void handleReverseConnection(BluetoothGatt gatt, int status, int newState) {
        if (reverseGatt != gatt || newState != BluetoothProfile.STATE_DISCONNECTED) return;
        BluetoothGatt terminal = reverseGatt;
        reverseGatt = null;
        BleRouteToken ownerToken = reverseGattOwnerToken;
        reverseGattOwnerToken = null;
        pendingReverse = null;
        closeAncsSession();
        AndroidPeripheralRoute.State current = state;
        if (ownerToken != null) {
            reverseObserver.closeOnly(ownerToken, terminal);
            if (current != null && current.reverseOwner != null
                    && current.reverseOwner.sameOwner(ownerToken)) {
                apply(AndroidPeripheralRoute.reverseOwnerLost(
                        current, ownerToken, "status=" + status));
            }
        }
        maybeCompleteTeardown();
    }

    private void handleReverseServices(BluetoothGatt gatt, int status) {
        PendingReverseOperation pending = pendingReverse;
        if (reverseGatt != gatt || pending == null
                || pending.type != ReverseOperation.DISCOVER) return;
        pendingReverse = null;
        if (ingressFrozen) return;
        IphoneGattInventoryV2 inventory = status == BluetoothGatt.GATT_SUCCESS
                ? reverseInventory(gatt) : null;
        if (state != null) {
            apply(AndroidPeripheralRoute.ancsDiscovered(
                    state, pending.routeToken, inventory));
        }
    }

    private void handleReverseDescriptorWrite(BluetoothGatt gatt,
                                              BluetoothGattDescriptor descriptor,
                                              int status) {
        PendingReverseOperation pending = pendingReverse;
        if (reverseGatt != gatt || pending == null || pending.descriptor != descriptor
                || (pending.type != ReverseOperation.SUBSCRIBE_SERVICE_CHANGED
                    && pending.type != ReverseOperation.SUBSCRIBE_NOTIFICATION_SOURCE
                    && pending.type != ReverseOperation.SUBSCRIBE_DATA_SOURCE)) return;
        pendingReverse = null;
        if (ingressFrozen) return;
        completeReverseSubscription(pending.routeToken, pending.type,
                GattResultV2.fromAndroidStatus(status));
    }

    private void handleReverseCharacteristicWrite(
            BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
        PendingReverseOperation pending = pendingReverse;
        if (reverseGatt != gatt || pending == null
                || pending.type != ReverseOperation.WRITE_CONTROL_POINT
                || pending.characteristic != characteristic) return;
        pendingReverse = null;
        if (ingressFrozen) {
            // Freeze closes the ANCS session, but Android still owes this exact raw FIFO
            // callback. Consume the slot without feeding a result into the closed session.
            return;
        }
        applyAncsEffects(ancs.controlPointWriteResult(
                pending.ancsRequest, status == BluetoothGatt.GATT_SUCCESS));
    }

    private void handleReverseCharacteristicChanged(
            BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, byte[] value) {
        if (reverseGatt != gatt || characteristic == null || state == null) return;
        UUID uuid = characteristic.getUuid();
        if (SERVICE_CHANGED.equals(uuid)) {
            if (ingressFrozen) return;
            pendingReverse = null;
            apply(AndroidPeripheralRoute.serviceChanged(state, state.reverseOwner));
        } else if (AncsProtocol.NOTIFICATION_SOURCE.equals(uuid) && ancsSession != null) {
            if (ingressFrozen) return;
            applyAncsEffects(ancs.notificationSource(
                    ancsSession, value, android.os.SystemClock.elapsedRealtime()));
        } else if (AncsProtocol.DATA_SOURCE.equals(uuid) && ancsSession != null) {
            if (ingressFrozen) return;
            applyAncsEffects(ancs.dataSource(ancsSession, value));
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
            poisonReverseOwner(exhausted.getMessage());
            return;
        }
        ancsSession = new AncsSessionTokenV2(token.epoch, token.ownerId, sessionId);
        ancs.begin(ancsSession);
    }

    private void closeAncsSession() {
        cancelHelperIdentityCommit(true);
        if (ancsSession != null) {
            applyAncsEffects(ancs.close(ancsSession));
            ancsSession = null;
        }
        if (pendingReverse != null
                && pendingReverse.type == ReverseOperation.WRITE_CONTROL_POINT) {
            // Keep the raw slot until its framework callback; never overlap a GATT operation.
        }
        for (Runnable timer : requestTimers.values()) main.removeCallbacks(timer);
        requestTimers.clear();
        cancelReplayQuiet();
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
                    reportError(IphoneTransportErrorV2.Kind.PROTOCOL,
                            effect.detail, true);
                    poisonReverseOwner(effect.detail);
                    break;
                case MALFORMED_SOURCE:
                case QUEUE_DROPPED:
                    reportError(IphoneTransportErrorV2.Kind.PROTOCOL,
                            effect.detail, true);
                    break;
                case REPLAY_CHECKPOINT:
                case REPLAY_SUMMARY:
                    break;
            }
        }
    }

    private void writeControlPoint(AncsRequestTokenV2 request, byte[] value) {
        if (request == null || ancsSession == null || !request.session.equals(ancsSession)
                || ingressFrozen || reverseGatt == null || pendingReverse != null || state == null
                || state.phase != AndroidPeripheralRoute.Phase.READY) {
            if (request != null) {
                applyAncsEffects(ancs.controlPointWriteResult(request, false));
            }
            return;
        }
        BluetoothGattCharacteristic control = reverseCharacteristic(
                AncsProtocol.SERVICE, AncsProtocol.CONTROL_POINT);
        if (control == null) {
            applyAncsEffects(ancs.controlPointWriteResult(request, false));
            return;
        }
        control.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        control.setValue(value);
        pendingReverse = new PendingReverseOperation(
                ReverseOperation.WRITE_CONTROL_POINT, null, request, control, null);
        boolean started;
        try {
            started = reverseGatt.writeCharacteristic(control);
        } catch (RuntimeException error) {
            started = false;
        }
        if (!started) {
            pendingReverse = null;
            applyAncsEffects(ancs.controlPointWriteResult(request, false));
        }
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
    }

    private void poisonReverseOwner(String reason) {
        if (state == null || state.reverseOwner == null) return;
        apply(AndroidPeripheralRoute.reverseOwnerLost(
                state, state.reverseOwner, "terminal ANCS stream: " + safe(reason)));
    }

    private void armRouteTimer(BleRouteToken token, long delayMillis) {
        cancelRouteTimer(token);
        Runnable timer = () -> {
            routeTimers.remove(token);
            AndroidPeripheralRoute.State current = state;
            if (current == null || current.expected == null
                    || !current.expected.equals(token)) return;
            if (current.phase == AndroidPeripheralRoute.Phase.RETRY_WAIT) {
                apply(AndroidPeripheralRoute.retryElapsed(
                        current, token, radioEnabled()));
            } else {
                apply(AndroidPeripheralRoute.deadline(current, token));
            }
        };
        routeTimers.put(token, timer);
        main.postDelayed(timer, delayMillis);
    }

    private void cancelRouteTimer(BleRouteToken token) {
        Runnable timer = routeTimers.remove(token);
        if (timer != null) main.removeCallbacks(timer);
    }

    private void postServerOpened(BleRouteToken token, boolean success) {
        dispatchMain(() -> {
            if (!ingressFrozen && state != null) {
                apply(AndroidPeripheralRoute.serverOpened(state, token, success));
            }
        });
    }

    private void postServiceAdded(BleRouteToken token, boolean success) {
        dispatchMain(() -> {
            if (!ingressFrozen && state != null) {
                apply(AndroidPeripheralRoute.serviceAdded(state, token, success));
            }
        });
    }

    private void postAdvertisingStarted(BleRouteToken token, boolean success) {
        dispatchMain(() -> {
            if (!ingressFrozen && state != null) {
                apply(AndroidPeripheralRoute.advertisingStarted(state, token, success));
            }
        });
    }

    private void disconnectInbound(BleRouteToken token) {
        if (inboundOwnerToken == null || !inboundOwnerToken.sameOwner(token)) return;
        if (inboundPhysicalFacade == null) {
            inboundOwnerToken = null;
            maybeFinishDeferredServerClose();
            maybeCompleteTeardown();
            return;
        }
        try {
            if (server != null) server.cancelConnection(inboundPhysicalFacade);
        } catch (RuntimeException error) {
            reportError(IphoneTransportErrorV2.Kind.TEARDOWN,
                    "inbound disconnect request failed", false);
        }
    }

    private void closeReverseOwner(BleRouteToken token) {
        if (observingReverseToken != null && observingReverseToken.sameOwner(token)) {
            try {
                reverseObserver.cancel(observingReverseToken);
            } catch (RuntimeException ignored) {
                // The pending observer owner is retired below.
            }
            observingReverseToken = null;
        }
        BluetoothGatt closing = reverseGatt;
        if (closing != null && reverseGattOwnerToken != null
                && reverseGattOwnerToken.sameOwner(token)) {
            BleRouteToken ownerToken = reverseGattOwnerToken;
            reverseGatt = null;
            reverseGattOwnerToken = null;
            pendingReverse = null;
            closeAncsSession();
            reverseObserver.closeOnly(ownerToken, closing);
        }
        maybeCompleteTeardown();
    }

    private void closeServer(BleRouteToken token) {
        if (serverOwnerToken != null && !serverOwnerToken.sameOwner(token)) return;
        serverCloseRequested = true;
        if (inboundPhysicalFacade == null || radioResetProven) {
            inboundPhysicalFacade = null;
            inboundOwnerToken = null;
            finishServerClose();
        }
    }

    private void maybeFinishDeferredServerClose() {
        if (serverCloseRequested && inboundPhysicalFacade == null) finishServerClose();
    }

    private void finishServerClose() {
        BluetoothGattServer closing = server;
        ServerAttempt closingAttempt = serverAttempt;
        server = null;
        serverAttempt = null;
        serverOwnerToken = null;
        if (closingAttempt != null) closingAttempt.retire();
        serverAttemptFence.retire(closingAttempt);
        serverCloseRequested = false;
        controlIndicationsEnabled = false;
        pendingControlIndication = null;
        pendingControlServerAttempt = null;
        failPendingControlTransmit(ControlTransmitResult.TERMINAL_FAILURE);
        try {
            if (closing != null) closing.close();
        } catch (RuntimeException ignored) {
            // The app no longer owns the server object.
        }
        maybeCompleteTeardown();
    }

    private void retireOwnersAfterRadioReset() {
        if (observingReverseToken != null) {
            reverseObserver.cancel(observingReverseToken);
            observingReverseToken = null;
        }
        if (reverseGatt != null && reverseGattOwnerToken != null) {
            BluetoothGatt closing = reverseGatt;
            BleRouteToken token = reverseGattOwnerToken;
            reverseGatt = null;
            reverseGattOwnerToken = null;
            pendingReverse = null;
            reverseObserver.closeOnly(token, closing);
        }
        inboundPhysicalFacade = null;
        inboundOwnerToken = null;
        controlIndicationsEnabled = false;
        pendingControlIndication = null;
        failPendingControlTransmit(ControlTransmitResult.TERMINAL_FAILURE);
        if (server != null || serverAttempt != null) finishServerClose();
    }

    private void maybeCompleteTeardown() {
        boolean ownsDrain = ProcessGattRegistrationGateV2.ownsDrainReservation(
                processGateDrainWaiter);
        if (processGateDrainRetained) return;
        if (state == null || advertising || advertiser != null
                || advertisingAttempt != null || serverAttempt != null
                || inboundPhysicalFacade != null || reverseGatt != null
                || observingReverseToken != null || server != null) {
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
        if (radioOffTerminalEpoch != null
                && (state.phase == AndroidPeripheralRoute.Phase.WAIT_RADIO
                    || state.phase == AndroidPeripheralRoute.Phase.FAILED)) {
            BleRouteEpoch exact = radioOffTerminalEpoch;
            radioOffTerminalEpoch = null;
            if (listener != null) listener.onLocalTerminal(mode(), exact);
            retainOrReleaseProcessDrain();
            return;
        }
        if (state.expected == null) {
            ProcessGattRegistrationGateV2.releaseDrainReservation(processGateDrainWaiter);
            return;
        }
        if (state.phase == AndroidPeripheralRoute.Phase.RETRY_DRAINING) {
            ProcessGattRegistrationGateV2.releaseDrainReservation(processGateDrainWaiter);
            apply(AndroidPeripheralRoute.attemptTeardownComplete(state, state.expected));
        } else if (state.phase == AndroidPeripheralRoute.Phase.STOPPING) {
            apply(AndroidPeripheralRoute.localTeardownComplete(state, state.expected));
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
        cancelHelperIdentityCommit(true);
        for (Runnable timer : routeTimers.values()) main.removeCallbacks(timer);
        routeTimers.clear();
        for (Runnable timer : requestTimers.values()) main.removeCallbacks(timer);
        requestTimers.clear();
        for (Runnable timer : controlRetryTimers.values()) main.removeCallbacks(timer);
        controlRetryTimers.clear();
        cancelReplayQuiet();
    }

    private void transmitControlOnMain(ControlTransmit transmit,
                                       ControlCompletion completion) {
        if (!ownsSwitchSource(transmit.owner()) || !ingressFrozen) {
            completeControlTransmit(transmit, completion,
                    ControlTransmitResult.TERMINAL_FAILURE, null);
            return;
        }
        IphoneRoleControlV2 control = roleControl(transmit);
        if (control == null) {
            completeControlTransmit(transmit, completion,
                    ControlTransmitResult.TERMINAL_FAILURE, null);
            return;
        }
        sendControlIndication(control, transmit, completion);
    }

    private void sendControlIndication(IphoneRoleControlV2 control,
                                       ControlTransmit transmit,
                                       ControlCompletion completion) {
        ServerAttempt exactServerAttempt = serverAttempt;
        if (server == null || inboundPhysicalFacade == null || !controlIndicationsEnabled
                || pendingControlIndication != null
                || !isCurrentServerAttempt(exactServerAttempt)) {
            notifyControlWriteResult(control, false);
            if (transmit != null) completeControlTransmit(transmit, completion,
                    server == null || inboundPhysicalFacade == null
                            ? ControlTransmitResult.TERMINAL_FAILURE
                            : ControlTransmitResult.RETRYABLE_FAILURE,
                    null);
            return;
        }
        BluetoothGattService service = server.getService(
                IphoneBleProtocolV2.ANDROID_PERIPHERAL_SERVICE);
        BluetoothGattCharacteristic characteristic = service == null ? null
                : service.getCharacteristic(IphoneBleProtocolV2.CONTROL_CHARACTERISTIC);
        if (characteristic == null
                || (characteristic.getProperties()
                    & BluetoothGattCharacteristic.PROPERTY_INDICATE) == 0) {
            notifyControlWriteResult(control, false);
            if (transmit != null) completeControlTransmit(transmit, completion,
                    ControlTransmitResult.TERMINAL_FAILURE, null);
            return;
        }
        byte[] frame = control.type == IphoneRoleControlV2.Type.CLOSE_REQUEST
                ? IphoneBleControlProtocolV2.encodeRoleClose(
                        control.targetMode, control.switchToken())
                : IphoneBleControlProtocolV2.encodeRoleCloseAck(
                        control.targetMode, control.switchToken());
        characteristic.setValue(frame);
        pendingControlIndication = control;
        pendingControlServerAttempt = exactServerAttempt;
        pendingControlTransmit = transmit;
        pendingControlCompletion = completion;
        boolean queued;
        try {
            queued = exactServerAttempt.exactServer.notifyCharacteristicChanged(
                    inboundPhysicalFacade, characteristic, true);
        } catch (RuntimeException error) {
            queued = false;
        }
        if (!queued) {
            pendingControlIndication = null;
            pendingControlServerAttempt = null;
            pendingControlTransmit = null;
            pendingControlCompletion = null;
            notifyControlWriteResult(control, false);
            if (transmit != null) completeControlTransmit(transmit, completion,
                    ControlTransmitResult.RETRYABLE_FAILURE, null);
            return;
        }
        lastOutboundControl = control;
        // Exact ATT indication confirmation owns completion; queue return is not wire evidence.
    }

    private void handleControlIndicationSent(
            ServerAttempt attempt, BluetoothDevice device, int status) {
        if (!isCurrentServerAttempt(attempt) || pendingControlServerAttempt != attempt
                || device != inboundPhysicalFacade || pendingControlIndication == null) return;
        IphoneRoleControlV2 completed = pendingControlIndication;
        ControlTransmit transmit = pendingControlTransmit;
        ControlCompletion completion = pendingControlCompletion;
        pendingControlIndication = null;
        pendingControlServerAttempt = null;
        pendingControlTransmit = null;
        pendingControlCompletion = null;
        boolean success = status == BluetoothGatt.GATT_SUCCESS;
        notifyControlWriteResult(completed, success);
        if (transmit != null && completion != null) {
            completeControlTransmit(transmit, completion,
                    success ? ControlTransmitResult.ACCEPTED
                            : inboundPhysicalFacade != null && controlIndicationsEnabled
                                ? ControlTransmitResult.RETRYABLE_FAILURE
                                : ControlTransmitResult.TERMINAL_FAILURE,
                    null);
        }
        if (!success) {
            reportError(IphoneTransportErrorV2.Kind.GATT,
                    "control indication completion failed: " + status, true);
        }
    }

    private void failPendingControlTransmit(ControlTransmitResult result) {
        ControlTransmit transmit = pendingControlTransmit;
        ControlCompletion completion = pendingControlCompletion;
        pendingControlIndication = null;
        pendingControlServerAttempt = null;
        pendingControlTransmit = null;
        pendingControlCompletion = null;
        if (transmit != null && completion != null) {
            completeControlTransmit(transmit, completion, result, null);
        }
    }

    private void notifyControlWriteResult(IphoneRoleControlV2 control, boolean success) {
        main.post(() -> {
            if (listener != null) listener.onRoleControlWriteResult(control, success);
        });
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

    private void completeControlTransmit(ControlTransmit transmit,
                                         ControlCompletion completion,
                                         ControlTransmitResult result,
                                         IphoneRoleControlV2 ignored) {
        main.post(() -> completion.onComplete(transmit, result));
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

    private boolean ownsServer(BleRouteToken token) {
        return token != null && state != null && state.serverOwner != null
                && state.serverOwner.sameOwner(token) && currentEpoch(token);
    }

    private boolean readyForReverseOperation(BleRouteToken token) {
        return !ingressFrozen && reverseGatt != null && state != null
                && state.reverseOwner != null
                && state.reverseOwner.sameOwner(token) && currentEpoch(token);
    }

    private boolean currentEpoch(BleRouteToken token) {
        return token != null && token.mode == mode() && state != null
                && state.epoch.equals(token.epoch);
    }

    private boolean ownsSwitchSource(Owner source) {
        if (source != null && source.equals(restorationOwner)) return true;
        return source != null && state != null
                && source.role() == Role.HELPER_CENTRAL_ANDROID_PERIPHERAL
                && state.epoch.processNonce == source.processNonce()
                && state.epoch.sequence.equals(source.generation().asBigInteger());
    }

    private boolean radioEnabled() {
        return adapter != null && adapter.isEnabled();
    }

    private void publishStatus() {
        if (listener != null && state != null) listener.onStatus(toStatus(state));
    }

    private IphoneTransportStatusV2 toStatus(AndroidPeripheralRoute.State route) {
        return new IphoneTransportStatusV2(mode(), route.epoch, lifecycle(route.phase),
                route.selectedSystemBondAddress, route.helperInstallationId,
                route.detail, route.consecutiveFailures);
    }

    private static IphoneTransportLifecycle lifecycle(AndroidPeripheralRoute.Phase phase) {
        switch (phase) {
            case WAIT_RADIO:
                return IphoneTransportLifecycle.WAIT_RADIO;
            case OPENING_SERVER:
            case ADDING_SERVICE:
            case STARTING_ADVERTISEMENT:
            case ADVERTISING:
                return IphoneTransportLifecycle.STARTING;
            case WAITING_CONTROL_SUBSCRIPTION:
            case WAITING_PEER_PROOF:
            case WAITING_REVERSE_OWNER:
            case DISCOVERING_ANCS:
            case SUBSCRIBING_SERVICE_CHANGED:
            case WAIT_AUTHORIZATION:
            case WAIT_ANCS:
                return IphoneTransportLifecycle.AUTHENTICATING;
            case NEEDS_FRESH_LINK:
                return IphoneTransportLifecycle.FAILED;
            case SUBSCRIBING_NOTIFICATION_SOURCE:
            case SUBSCRIBING_DATA_SOURCE:
                return IphoneTransportLifecycle.SUBSCRIBING;
            case READY:
                return IphoneTransportLifecycle.READY;
            case RETRY_DRAINING:
            case RETRY_WAIT:
                return IphoneTransportLifecycle.RETRY_WAIT;
            case SWITCH_WAIT_INBOUND_TERMINAL:
            case STOPPING:
                return IphoneTransportLifecycle.STOPPING;
            case STOPPED:
                return IphoneTransportLifecycle.STOPPED;
            case FAILED:
                return IphoneTransportLifecycle.FAILED;
            default:
                throw new AssertionError(phase);
        }
    }

    private void reportError(IphoneTransportErrorV2.Kind kind, String detail,
                             boolean retryable) {
        if (listener == null || state == null) return;
        listener.onError(new IphoneTransportErrorV2(
                mode(), state.epoch, kind, detail, retryable));
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

    private void assertMain() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            throw new IllegalStateException("Route-B adapter must run on main FIFO");
        }
    }

    /** Inline on main prevents an already-queued watchdog from overtaking its callback body. */
    private void dispatchMain(Runnable callbackBody) {
        if (Looper.myLooper() == main.getLooper()) {
            callbackBody.run();
        } else {
            main.post(callbackBody);
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
