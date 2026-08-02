/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package dezz.status.widget.phone;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationChannelGroup;
import android.app.NotificationManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import dezz.status.widget.Preferences;
import dezz.status.widget.R;
import dezz.status.widget.integration.ConnectorType;
import dezz.status.widget.integration.ConnectorValue;
import dezz.status.widget.integration.ConnectorValueRegistry;
import dezz.status.widget.integration.SourceBinding;
import dezz.status.widget.phone.transport.IphoneAncsTransport;

/**
 * Best-effort Android 9 bridge for one explicitly selected, bonded iPhone.
 *
 * <p>Classic ACL/HFP supplies presence, battery and network state. ANCS is consumed over an
 * encrypted BLE GATT session and is deliberately serialized because Android permits only one
 * outstanding GATT operation. SMS comes only from device-qualified MAP broadcasts or the exact
 * iPhone's ANCS Messages notifications; global inbox providers are intentionally not trusted.</p>
 */
public final class PhoneConnectorController {
    private static final String TAG = "PhoneConnector";
    private static final String CONNECTOR_ID = SourceBinding.DEFAULT_CONNECTOR_ID;

    private static final int MAX_NOTIFICATIONS = 50;
    private static final int MAX_MAP_MESSAGES = 20;
    private static final int MAX_PENDING_ANCS_REQUESTS = 100;
    private static final int MAX_APP_DISPLAY_NAMES = 128;
    private static final long ATTRIBUTE_TIMEOUT_MS = 8_000L;
    private static final long GATT_OPERATION_TIMEOUT_MS = 10_000L;
    // Android's GATT client upgrades security after an ANCS CCCD rejects an unauthenticated
    // write. The iPhone prompt is user-driven, so the normal ten-second transport watchdog is
    // far too short for this first subscription.
    private static final long ANCS_AUTHORIZATION_OPERATION_TIMEOUT_MS = 90_000L;
    private static final long ANCS_SERVICE_PUBLICATION_RETRY_MS = 95_000L;
    private static final long GATT_CONNECT_TIMEOUT_MS = 20_000L;
    private static final long GATT_MTU_TIMEOUT_MS = 1_500L;
    private static final long GATT_DISCOVERY_TIMEOUT_MS = 15_000L;
    private static final long DEVICE_RESCAN_MS = 15_000L;
    private static final long ANCS_STABLE_READY_RESET_MS = 50_000L;
    private static final long ADAPTER_RECOVERY_WATCHDOG_MS = 40_000L;
    private static final long APP_DISPLAY_NAME_WAIT_TIMEOUT_MS = 15_000L;
    /** Helper emits every 20 s; three missed heartbeats invalidate helper-only fields. */
    private static final long HELPER_TELEMETRY_TIMEOUT_MS = 65_000L;
    private static final int DESIRED_GATT_MTU = 512;
    private static final int GATT_INSUFFICIENT_AUTHENTICATION = 5;
    private static final int GATT_INSUFFICIENT_AUTHORIZATION = 8;
    private static final int GATT_INSUFFICIENT_ENCRYPTION_KEY_SIZE = 12;
    private static final int GATT_INSUFFICIENT_ENCRYPTION = 15;
    private static final int ANCS_INVALID_PARAMETER = 0xA2;

    private static final String ACTION_HFP_CONNECTION =
            "android.bluetooth.headsetclient.profile.action.CONNECTION_STATE_CHANGED";
    private static final String ACTION_HFP_AG_EVENT =
            "android.bluetooth.headsetclient.profile.action.AG_EVENT";
    private static final String ACTION_HFP_AUDIO_STATE =
            "android.bluetooth.headsetclient.profile.action.AUDIO_STATE_CHANGED";
    private static final String ACTION_HFP_CALL_CHANGED =
            "android.bluetooth.headsetclient.profile.action.AG_CALL_CHANGED";
    private static final String ACTION_MAP_CONNECTION =
            "android.bluetooth.mapmce.profile.action.CONNECTION_STATE_CHANGED";
    private static final String ACTION_MAP_MESSAGE_RECEIVED =
            "android.bluetooth.mapmce.profile.action.MESSAGE_RECEIVED";
    private static final String ACTION_MAP_MESSAGE_READ_CHANGED =
            "android.bluetooth.mapmce.profile.action.MESSAGE_READ_STATUS_CHANGED";
    private static final String ACTION_MAP_MESSAGE_DELETED_CHANGED =
            "android.bluetooth.mapmce.profile.action.MESSAGE_DELETED_STATUS_CHANGED";
    private static final String EXTRA_MAP_MESSAGE_HANDLE =
            "android.bluetooth.mapmce.profile.extra.MESSAGE_HANDLE";
    private static final String EXTRA_MAP_MESSAGE_TIMESTAMP =
            "android.bluetooth.mapmce.profile.extra.MESSAGE_TIMESTAMP";
    private static final String EXTRA_MAP_MESSAGE_READ =
            "android.bluetooth.mapmce.profile.extra.MESSAGE_READ_STATUS";
    private static final String EXTRA_MAP_MESSAGE_DELETED =
            "android.bluetooth.mapmce.profile.extra.MESSAGE_DELETED_STATUS";
    private static final String EXTRA_MAP_SENDER_URI =
            "android.bluetooth.mapmce.profile.extra.SENDER_CONTACT_URI";
    private static final String EXTRA_MAP_SENDER_NAME =
            "android.bluetooth.mapmce.profile.extra.SENDER_CONTACT_NAME";
    private static final String ACTION_DEVICE_BATTERY_LEVEL_CHANGED =
            "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED";
    private static final String EXTRA_DEVICE_BATTERY_LEVEL =
            "android.bluetooth.device.extra.BATTERY_LEVEL";
    private static final String EXTRA_ACL_TRANSPORT =
            "android.bluetooth.device.extra.TRANSPORT";
    // Public BluetoothAdapter#getProfileProxy accepts an int, but Android 9 keeps these
    // automotive client constants out of the public SDK stub. Unsupported stacks fail closed.
    private static final int PROFILE_HEADSET_CLIENT = 16;
    private static final int PROFILE_MAP_CLIENT = 18;
    private static final String EXTRA_HFP_BATTERY =
            "android.bluetooth.headsetclient.extra.BATTERY_LEVEL";
    private static final String EXTRA_HFP_OPERATOR =
            "android.bluetooth.headsetclient.extra.OPERATOR_NAME";
    private static final String EXTRA_HFP_NETWORK_STATUS =
            "android.bluetooth.headsetclient.extra.NETWORK_STATUS";
    private static final String EXTRA_HFP_NETWORK_SIGNAL =
            "android.bluetooth.headsetclient.extra.NETWORK_SIGNAL_STRENGTH";
    private static final String EXTRA_HFP_NETWORK_ROAMING =
            "android.bluetooth.headsetclient.extra.NETWORK_ROAMING";
    private static final String EXTRA_HFP_VOICE_RECOGNITION =
            "android.bluetooth.headsetclient.extra.VOICE_RECOGNITION";
    private static final String EXTRA_HFP_IN_BAND_RING =
            "android.bluetooth.headsetclient.extra.IN_BAND_RING";
    private static final String EXTRA_HFP_AUDIO_WBS =
            "android.bluetooth.headsetclient.extra.AUDIO_WBS";
    private static final String EXTRA_HFP_CALL =
            "android.bluetooth.headsetclient.extra.CALL";
    private static final int HFP_AUDIO_DISCONNECTED = 0;
    private static final int HFP_AUDIO_CONNECTING = 1;
    private static final int HFP_AUDIO_CONNECTED = 2;
    private static final UUID BATTERY_SERVICE =
            UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb");
    private static final UUID BATTERY_LEVEL =
            UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb");
    private static final UUID BATTERY_LEVEL_STATUS =
            UUID.fromString("00002bed-0000-1000-8000-00805f9b34fb");
    private static final UUID GENERIC_ATTRIBUTE_SERVICE =
            UUID.fromString("00001801-0000-1000-8000-00805f9b34fb");
    private static final UUID SERVICE_CHANGED =
            UUID.fromString("00002a05-0000-1000-8000-00805f9b34fb");

    private static final String CHANNEL_GROUP_ID = "phone_mirror_group";
    private static final String CHANNEL_ID = "phone_mirror";
    private static final String NOTIFICATION_GROUP_KEY =
            "dezz.status.widget.phone.MIRRORED";
    private static final String BLUETOOTH_SENDER_PERMISSION =
            "android.permission.BLUETOOTH_PRIVILEGED";
    private static final int SUMMARY_NOTIFICATION_ID = 0x50484f4e;
    /** Process-wide cooldown survives controller reconfiguration inside the service process. */
    private static volatile long lastAdapterRecoveryElapsedMs;

    /** Decouples Bluetooth presence from an optional Sprut.hub runtime. */
    public interface PresenceSink {
        void onPhoneConnectionChanged(boolean connected);
    }

    private static final PresenceSink NO_PRESENCE_SINK = connected -> { };

    private final Context context;
    private final Preferences prefs;
    private final ConnectorValueRegistry values;
    private final PresenceSink presenceSink;
    private final Object lifecycleLock = new Object();
    private final Handler mainHandler;
    private final PhoneTelemetryStore telemetryStore;

    private long generation;
    private boolean running;
    private boolean lastPresence;
    private String signature = "";
    @Nullable private HandlerThread workerThread;
    @Nullable private volatile Handler worker;
    @Nullable private BroadcastReceiver bluetoothReceiver;
    @Nullable private volatile IphoneAncsTransport ancsTransport;
    private long nextAncsTransportSession;
    private volatile long activeAncsTransportSession;
    private volatile boolean ancsTransportStartPending;
    @Nullable private BluetoothGatt gatt;
    @Nullable private PhoneOemConnectionBridge.Observation oemPowerObservation;

    // The following fields are worker-thread owned. Publishing is additionally guarded by
    // lifecycleLock so an old callback can never overwrite the explicit stopped snapshot.
    @Nullable private Config config;
    @Nullable private BluetoothDevice selectedDevice;
    private String selectedAddress = "";
    private String selectedName = "";
    private boolean aclConnected;
    private boolean hfpConnected;
    private boolean mapConnected;
    private boolean gattConnected;
    private boolean connected;
    private int reconnectAttempt;
    private String lastError = "";
    private String ancsStatus = "stopped";
    private String smsStatus = "stopped";
    private String stockConnectionStatus = "stopped";
    private boolean ancsReady;
    private boolean ancsWasReadyThisSession;
    private boolean adapterRecoveryInProgress;
    private boolean smsAvailable;
    private boolean hfpBatteryKnown;
    private boolean basBatteryKnown;
    private boolean genericBatteryKnown;
    @Nullable private Integer hfpBatteryLevel;
    @Nullable private Integer basBatteryLevel;
    @Nullable private Integer genericBatteryLevel;
    private long hfpBatteryUpdatedAt;
    private long basBatteryUpdatedAt;
    private long genericBatteryUpdatedAt;
    @Nullable private Integer batteryLevel;
    @Nullable private Boolean batteryCharging;
    @Nullable private Boolean batteryChargingEstimated;
    @Nullable private Boolean batteryExternalPower;
    private String batteryLevelSource = "";
    private String batteryChargingSource = "";
    private String batteryChargeState = "";
    private String batteryChargeLevel = "";
    @Nullable private Integer helperBatteryLevel;
    @Nullable private Boolean helperExternalPower;
    private String helperChargeState = "";
    private String helperNetworkType = "";
    private long helperPowerUpdatedAtElapsed;
    private long helperNetworkUpdatedAtElapsed;
    @Nullable private Runnable helperTelemetryExpiryTask;
    @Nullable private PhoneTelemetryStore.Record retainedTelemetry;
    private boolean batteryLiveSeenThisConnection;
    private boolean networkLiveSeenThisConnection;
    private boolean telemetryStale;
    @Nullable private Boolean networkAvailable;
    @Nullable private Integer networkSignal;
    @Nullable private Boolean networkRoaming;
    private String networkOperator = "";
    @Nullable private Boolean voiceRecognitionActive;
    @Nullable private Boolean inBandRingSupported;
    @Nullable private Boolean callActive;
    @Nullable private Boolean callAudioConnected;
    @Nullable private Boolean callAudioWideband;
    private String callState = "";
    private String callAudioState = "";
    private String callDirection = "";
    @Nullable private Boolean callMultiparty;
    private final Map<String, CallRecord> calls = new LinkedHashMap<>();

    @Nullable private BluetoothGattCharacteristic ancsControlPoint;
    @Nullable private BluetoothGattCharacteristic ancsDataSource;
    @Nullable private BluetoothGattCharacteristic ancsNotificationSource;
    @Nullable private BluetoothGattCharacteristic serviceChangedCharacteristic;
    private boolean ancsDataSubscribed;
    private boolean ancsNotificationSubscribed;
    private boolean ancsNotificationListening;
    private boolean serviceChangedSubscribed;
    private boolean ancsAuthorizedThisRun;
    private boolean forceDirectGatt;
    private boolean serviceDiscoveryStarted;
    private boolean mtuPending;
    @Nullable private Runnable connectWatchdog;
    @Nullable private Runnable mtuWatchdog;
    @Nullable private Runnable discoveryWatchdog;
    @Nullable private Runnable deviceRescanTask;
    @Nullable private Runnable gattReconnectTask;
    @Nullable private Runnable ancsPublicationRetryTask;
    @Nullable private Runnable ancsStableReadyTask;
    @Nullable private Runnable ancsAdapterEscalationTask;
    @Nullable private Runnable adapterRecoveryWatchdog;
    @Nullable private Runnable stockConnectionTask;
    @Nullable private Runnable oemGattRefreshTask;
    private int ancsPublicationRetryCount;
    private int stockConnectionAttempt;
    private boolean stockConnectionRequestInProgress;
    private final ArrayDeque<GattOperation> gattOperations = new ArrayDeque<>();
    @Nullable private GattOperation currentGattOperation;
    @Nullable private Runnable gattOperationTimeout;

    private final Map<Long, AncsProtocol.Event> pendingAncsEvents = new LinkedHashMap<>();
    private final ArrayDeque<Long> attributeRequests = new ArrayDeque<>();
    private final Set<Long> queuedAttributeUids = new LinkedHashSet<>();
    private final Set<Long> dirtyAttributeUids = new LinkedHashSet<>();
    private final Set<Long> removedAttributeUids = new LinkedHashSet<>();
    private final Set<Long> fullTextAttributeUids = new LinkedHashSet<>();
    @Nullable private Long activeAttributeUid;
    @Nullable private AncsProtocol.AttributeAccumulator attributeAccumulator;
    private boolean activeAttributeIncludesText;
    private long activeAncsRequestSequence;
    private long nextAncsRequestSequence;
    private final ArrayDeque<String> appAttributeRequests = new ArrayDeque<>();
    private final Set<String> queuedAppIdentifiers = new LinkedHashSet<>();
    private final Map<String, String> appDisplayNames = new LinkedHashMap<>();
    @Nullable private String activeAppIdentifier;
    @Nullable private AncsProtocol.AppAttributeAccumulator appAttributeAccumulator;
    @Nullable private Runnable attributeTimeout;
    private final LinkedHashMap<Long, NotificationRecord> notificationCache =
            new LinkedHashMap<>();

    private final LinkedHashMap<String, Map<String, Object>> mapMessageCache =
            new LinkedHashMap<>();
    private final LinkedHashMap<Long, Map<String, Object>> ancsMessageCache =
            new LinkedHashMap<>();
    private final List<Map<String, Object>> smsItems = new ArrayList<>();
    private int smsUnread;
    @Nullable private Map<String, Object> latestSms;

    private final Set<Integer> mirroredNotificationIds = new LinkedHashSet<>();
    private final Map<Long, Integer> mirroredAncsIds = new LinkedHashMap<>();
    private final Map<String, Integer> mirroredSmsIds = new LinkedHashMap<>();
    private String lastAppIdentifier = "";
    private String lastAppName = "";
    private int lastAppCategoryId;
    private long lastNotificationAt;

    public PhoneConnectorController(@NonNull Context context, @NonNull Preferences prefs,
                                    @NonNull ConnectorValueRegistry values) {
        this(context, prefs, values, NO_PRESENCE_SINK);
    }

    public PhoneConnectorController(@NonNull Context context, @NonNull Preferences prefs,
                                    @NonNull ConnectorValueRegistry values,
                                    @Nullable PresenceSink presenceSink) {
        this.context = Objects.requireNonNull(context, "context").getApplicationContext();
        this.prefs = Objects.requireNonNull(prefs, "prefs");
        this.values = Objects.requireNonNull(values, "values");
        this.presenceSink = presenceSink == null ? NO_PRESENCE_SINK : presenceSink;
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.telemetryStore = new PhoneTelemetryStore(this.context);
    }

    /**
     * Idempotently applies all phone settings. Any transport-affecting change starts a new
     * generation; callbacks retained by Android from the previous GATT session become no-ops.
     */
    public void reconfigure() {
        Config next = Config.from(prefs);
        synchronized (lifecycleLock) {
            if (running && signature.equals(next.signature())) {
                Handler current = worker;
                long token = generation;
                if (current != null) current.post(() -> publishSnapshot(token));
                return;
            }
            stopLocked(next.enabled ? "reconfigured" : "disabled");
            signature = next.signature();
            if (!next.enabled) return;

            generation++;
            long token = generation;
            running = true;
            config = next;
            HandlerThread thread = new HandlerThread("StatusWidgetPhone");
            thread.start();
            workerThread = thread;
            worker = new Handler(thread.getLooper());
            worker.post(() -> runIfCurrent(token, () -> startSession(token, next)));
        }
    }

    /** Fully releases receiver, observer, GATT and worker resources; safe to call repeatedly. */
    public void stop() {
        synchronized (lifecycleLock) {
            signature = "";
            stopLocked("stopped");
        }
    }

    /**
     * Queues one user-initiated stock-connect request followed by a clean ANCS handshake without
     * changing the selected device.
     *
     * @return {@code true} only when a live controller accepted the diagnostic request
     */
    public boolean reconnectForDiagnostics() {
        synchronized (lifecycleLock) {
            if (!running || config == null || config.deviceAddress.isEmpty()) return false;
            Handler currentWorker = worker;
            long token = generation;
            if (currentWorker == null) return false;
            currentWorker.post(() -> runIfCurrent(token, () -> {
                String configuredAddress = config == null ? "" : config.deviceAddress;
                closeAncsTransport();
                BluetoothGatt previous = gatt;
                gatt = null;
                cancelGattWatchdogs();
                cancelGattReconnect();
                cancelAncsPublicationRetry();
                cancelStockConnectionRequest();
                refreshGattCache(previous);
                closeGatt(previous);
                gattConnected = false;
                persistCurrentTelemetry();
                clearBasData();
                resetAncsSession(token, "connecting");
                forceDirectGatt = true;
                reconnectAttempt = 0;
                ancsPublicationRetryCount = 0;
                lastError = "";
                updateConnected(token);
                if (selectedDevice == null
                        || !configuredAddress.equalsIgnoreCase(selectedAddress)) {
                    selectAndConnect(token);
                } else {
                    beginStockConnectionRequest(token, selectedAddress);
                }
            }));
            return true;
        }
    }

    private void stopLocked(@NonNull String reason) {
        generation++;
        running = false;

        Handler oldWorker = worker;
        HandlerThread oldThread = workerThread;
        BroadcastReceiver oldReceiver = bluetoothReceiver;
        IphoneAncsTransport oldAncsTransport = ancsTransport;
        BluetoothGatt oldGatt = gatt;
        PhoneOemConnectionBridge.Observation oldOemObservation = oemPowerObservation;
        worker = null;
        workerThread = null;
        bluetoothReceiver = null;
        ancsTransport = null;
        ancsTransportStartPending = false;
        activeAncsTransportSession = ++nextAncsTransportSession;
        gatt = null;
        oemPowerObservation = null;
        config = null;

        if (oldWorker != null) oldWorker.removeCallbacksAndMessages(null);
        if (oldReceiver != null) {
            try {
                context.unregisterReceiver(oldReceiver);
            } catch (IllegalArgumentException | SecurityException ignored) {
                // Already unregistered or an OEM revoked Bluetooth access during shutdown.
            }
        }
        closeOemObservation(oldOemObservation);
        closeAncsTransportOnMain(oldAncsTransport);
        closeGatt(oldGatt);
        cancelAllMirroredNotifications();
        clearRuntimeState(reason);
        updatePresenceLocked(false);
        publishOfflineSnapshotLocked(reason);
        if (oldThread != null) oldThread.quitSafely();
    }

    private void startSession(long token, @NonNull Config next) {
        if (!isCurrent(token)) return;
        clearRuntimeState("starting");
        config = next;
        ancsStatus = next.transportNeeded() ? "starting" : "disabled";
        smsStatus = next.messagesEnabled ? "waiting_for_map" : "disabled";
        ensureNotificationChannel();
        registerBluetoothReceiver(token);
        selectAndConnect(token);
        publishSnapshot(token);
    }

    private void clearRuntimeState(@NonNull String diagnostic) {
        selectedDevice = null;
        selectedAddress = "";
        selectedName = "";
        aclConnected = false;
        hfpConnected = false;
        mapConnected = false;
        gattConnected = false;
        connected = false;
        reconnectAttempt = 0;
        ancsPublicationRetryCount = 0;
        stockConnectionAttempt = 0;
        stockConnectionRequestInProgress = false;
        lastError = "";
        ancsStatus = diagnostic;
        smsStatus = diagnostic;
        stockConnectionStatus = diagnostic;
        ancsReady = false;
        ancsWasReadyThisSession = false;
        adapterRecoveryInProgress = false;
        smsAvailable = false;
        hfpBatteryKnown = false;
        basBatteryKnown = false;
        genericBatteryKnown = false;
        hfpBatteryLevel = null;
        basBatteryLevel = null;
        genericBatteryLevel = null;
        hfpBatteryUpdatedAt = 0L;
        basBatteryUpdatedAt = 0L;
        genericBatteryUpdatedAt = 0L;
        batteryLevel = null;
        batteryCharging = null;
        batteryChargingEstimated = null;
        batteryExternalPower = null;
        batteryLevelSource = "";
        batteryChargingSource = "";
        batteryChargeState = "";
        batteryChargeLevel = "";
        clearHelperTelemetry();
        retainedTelemetry = null;
        batteryLiveSeenThisConnection = false;
        networkLiveSeenThisConnection = false;
        telemetryStale = false;
        networkAvailable = null;
        networkSignal = null;
        networkRoaming = null;
        networkOperator = "";
        voiceRecognitionActive = null;
        inBandRingSupported = null;
        callActive = null;
        callAudioConnected = null;
        callAudioWideband = null;
        callState = "";
        callAudioState = "";
        callDirection = "";
        callMultiparty = null;
        calls.clear();
        ancsAuthorizedThisRun = false;
        forceDirectGatt = false;
        serviceDiscoveryStarted = false;
        mtuPending = false;
        cancelGattWatchdogs();
        cancelRetryTasks();
        clearAncsRuntime();
        appDisplayNames.clear();
        lastAppIdentifier = "";
        lastAppName = "";
        lastAppCategoryId = 0;
        lastNotificationAt = 0L;
        mapMessageCache.clear();
        smsItems.clear();
        smsUnread = 0;
        latestSms = null;
    }

    private void registerBluetoothReceiver(long token) {
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context ignored, Intent intent) {
                runIfCurrent(token, () -> handleBluetoothBroadcast(token, intent));
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        filter.addAction(ACTION_HFP_CONNECTION);
        filter.addAction(ACTION_HFP_AG_EVENT);
        filter.addAction(ACTION_HFP_AUDIO_STATE);
        filter.addAction(ACTION_HFP_CALL_CHANGED);
        filter.addAction(ACTION_MAP_CONNECTION);
        filter.addAction(ACTION_MAP_MESSAGE_RECEIVED);
        filter.addAction(ACTION_MAP_MESSAGE_READ_CHANGED);
        filter.addAction(ACTION_MAP_MESSAGE_DELETED_CHANGED);
        filter.addAction(ACTION_DEVICE_BATTERY_LEVEL_CHANGED);
        try {
            Handler callbackHandler = worker;
            if (callbackHandler == null) return;
            context.registerReceiver(receiver, filter, BLUETOOTH_SENDER_PERMISSION,
                    callbackHandler);
            synchronized (lifecycleLock) {
                if (!isCurrentLocked(token)) {
                    context.unregisterReceiver(receiver);
                    return;
                }
                bluetoothReceiver = receiver;
            }
        } catch (RuntimeException error) {
            recordError(token, "Bluetooth receiver: " + safeMessage(error));
        }
    }

    private void handleBluetoothBroadcast(long token, @NonNull Intent intent) {
        String action = intent.getAction();
        if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
            int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE,
                    BluetoothAdapter.STATE_OFF);
            if (adapterRecoveryInProgress) {
                handleAdapterRecoveryState(token, state);
                return;
            }
            if (state == BluetoothAdapter.STATE_ON && selectedDevice == null) {
                selectAndConnect(token);
            } else if (state != BluetoothAdapter.STATE_ON) {
                invalidateSelectedPhone(token, "bluetooth_off");
            }
            return;
        }
        if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(action)) {
            BluetoothDevice changed = parcelableDevice(intent);
            if (!matchesConfiguredAddress(changed)) return;
            int state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE,
                    BluetoothDevice.BOND_NONE);
            if (state == BluetoothDevice.BOND_BONDED) {
                if (gattConnected && gatt != null
                        && ("authorization_required".equals(ancsStatus)
                        || "service_not_published".equals(ancsStatus))) {
                    restartAncsAfterBond(token, gatt);
                } else {
                    selectAndConnect(token);
                }
            } else if (state == BluetoothDevice.BOND_NONE) {
                invalidateSelectedPhone(token, "not_bonded");
                scheduleDeviceRescan(token);
            }
            return;
        }
        BluetoothDevice device = parcelableDevice(intent);
        if (!isSelected(device)) return;
        if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(action)) {
            aclConnected = true;
            updateConnected(token);
            ensureGatt(token);
        } else if (BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(action)) {
            aclConnected = false;
            int transport = intent.getIntExtra(EXTRA_ACL_TRANSPORT,
                    BluetoothDevice.TRANSPORT_AUTO);
            if (transport != BluetoothDevice.TRANSPORT_LE) {
                persistCurrentTelemetry();
                hfpConnected = false;
                clearHfpData();
                clearGenericBatteryData();
                if (mapConnected) endMapSession("disconnected");
            }
            updateConnected(token);
            // Some ECARX Android 9 builds omit the LE transport extra and can also lose the
            // BluetoothGatt disconnect callback. The exact selected peer's LE/unknown ACL loss
            // therefore becomes an explicit retry signal. The scheduler is idempotent when the
            // ordinary GATT callback arrives too.
            if (config != null && config.transportNeeded()
                    && transport != BluetoothDevice.TRANSPORT_BREDR) {
                requestManagedAncsReconnect(token,
                        "Selected iPhone ACL link disconnected",
                        transport == BluetoothDevice.TRANSPORT_LE);
            }
        } else if (ACTION_HFP_CONNECTION.equals(action)) {
            int state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE,
                    BluetoothProfile.STATE_DISCONNECTED);
            hfpConnected = state == BluetoothProfile.STATE_CONNECTED;
            if (!hfpConnected) {
                persistCurrentTelemetry();
                clearHfpData();
            }
            updateConnected(token);
        } else if (ACTION_MAP_CONNECTION.equals(action)) {
            int state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE,
                    BluetoothProfile.STATE_DISCONNECTED);
            if (state == BluetoothProfile.STATE_CONNECTED && !mapConnected) {
                beginMapSession();
            } else if (state == BluetoothProfile.STATE_DISCONNECTED && mapConnected) {
                endMapSession("disconnected");
            }
            updateConnected(token);
        } else if (ACTION_MAP_MESSAGE_RECEIVED.equals(action)) {
            handleMapMessage(token, intent);
        } else if (ACTION_MAP_MESSAGE_READ_CHANGED.equals(action)
                || ACTION_MAP_MESSAGE_DELETED_CHANGED.equals(action)) {
            handleMapMessageStatus(token, intent,
                    ACTION_MAP_MESSAGE_DELETED_CHANGED.equals(action));
        } else if (ACTION_HFP_AG_EVENT.equals(action)) {
            hfpConnected = true;
            applyHfpEvent(token, intent);
            updateConnected(token);
        } else if (ACTION_HFP_AUDIO_STATE.equals(action)) {
            hfpConnected = true;
            applyHfpAudioState(token, intent.getIntExtra(
                    BluetoothProfile.EXTRA_STATE, HFP_AUDIO_DISCONNECTED),
                    booleanExtra(intent, EXTRA_HFP_AUDIO_WBS));
            updateConnected(token);
        } else if (ACTION_HFP_CALL_CHANGED.equals(action)) {
            hfpConnected = true;
            applyHfpCall(token, rawExtra(intent, EXTRA_HFP_CALL));
            updateConnected(token);
        } else if (ACTION_DEVICE_BATTERY_LEVEL_CHANGED.equals(action)) {
            Integer raw = intExtra(intent, EXTRA_DEVICE_BATTERY_LEVEL, "battery_level");
            if (raw != null && raw >= 0 && raw <= 100) {
                genericBatteryKnown = true;
                genericBatteryLevel = raw;
                genericBatteryUpdatedAt = SystemClock.elapsedRealtime();
                refreshBatteryValues();
                markTelemetryUpdated(true, false);
                publishSnapshot(token);
            }
        }
    }

    private void invalidateSelectedPhone(long token, @NonNull String status) {
        persistCurrentTelemetry();
        replaceOemPowerObservation(null);
        closeAncsTransport();
        BluetoothGatt previous = gatt;
        gatt = null;
        cancelGattWatchdogs();
        cancelStockConnectionRequest();
        closeGatt(previous);
        aclConnected = false;
        hfpConnected = false;
        mapConnected = false;
        gattConnected = false;
        clearBasData();
        clearHfpData();
        clearGenericBatteryData();
        updateConnected(token);
        selectedDevice = null;
        selectedAddress = "";
        selectedName = "";
        ancsStatus = status;
        stockConnectionStatus = status;
        smsStatus = config != null && config.messagesEnabled ? status : "disabled";
        publishSnapshot(token);
    }

    private void selectAndConnect(long token) {
        BluetoothAdapter adapter = bluetoothAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            ancsStatus = "bluetooth_off";
            stockConnectionStatus = "bluetooth_off";
            smsStatus = config != null && config.messagesEnabled
                    ? "bluetooth_off" : smsStatus;
            publishSnapshot(token);
            scheduleDeviceRescan(token);
            return;
        }
        String configuredAddress = config == null ? "" : config.deviceAddress;
        if (configuredAddress.isEmpty()) {
            ancsStatus = "no_configured_phone";
            stockConnectionStatus = "no_configured_phone";
            smsStatus = config != null && config.messagesEnabled
                    ? "no_configured_phone" : smsStatus;
            publishSnapshot(token);
            return;
        }
        BluetoothDevice selected = selectBondedPhone(adapter,
                configuredAddress);
        if (selected == null) {
            ancsStatus = "not_bonded";
            stockConnectionStatus = "not_bonded";
            smsStatus = config != null && config.messagesEnabled
                    ? "not_bonded" : smsStatus;
            publishSnapshot(token);
            scheduleDeviceRescan(token);
            return;
        }
        selectedDevice = selected;
        cancelDeviceRescan();
        selectedAddress = safeAddress(selected);
        selectedName = safeName(selected);
        restoreRetainedTelemetry(selectedAddress);
        startOemPowerObservation(token, selectedAddress);
        updateConnected(token);
        queryInitialProfileState(token, adapter, BluetoothProfile.A2DP);
        queryInitialProfileState(token, adapter, PROFILE_HEADSET_CLIENT);
        queryInitialProfileState(token, adapter, PROFILE_MAP_CLIENT);
        beginStockConnectionRequest(token, selectedAddress);
    }

    /**
     * Adds the battery source exposed by the ECARX Bluetooth owner. Some iPhone/firmware
     * combinations publish HFP network signal through Android broadcasts but keep headset power
     * exclusively in this vendor callback.
     */
    private void startOemPowerObservation(long token, @NonNull String address) {
        replaceOemPowerObservation(null);
        PhoneOemConnectionBridge.Observation created =
                PhoneOemConnectionBridge.observeHeadsetPower(context, address,
                        (callbackAddress, rawPower) -> {
                            Handler handler = worker;
                            if (handler != null) {
                                handler.post(() -> runIfCurrent(token, () ->
                                        applyOemHeadsetPower(
                                                token, callbackAddress, rawPower)));
                            }
                        }, (callbackAddress, change) -> {
                            Handler handler = worker;
                            if (handler != null) {
                                handler.post(() -> runIfCurrent(token, () ->
                                        handleOemDeviceStateChange(
                                                token, callbackAddress, change)));
                            }
                        });
        if (created == null) return;
        boolean accepted;
        synchronized (lifecycleLock) {
            accepted = isCurrentLocked(token)
                    && address.equalsIgnoreCase(selectedAddress);
            if (accepted) oemPowerObservation = created;
        }
        if (!accepted) closeOemObservation(created);
    }

    private void applyOemHeadsetPower(long token, @NonNull String address, int rawPower) {
        if (!address.equalsIgnoreCase(selectedAddress)) return;
        Integer normalized = PhoneConnectorPolicy.normalizeHfpBattery(rawPower);
        if (normalized == null) return;
        hfpBatteryKnown = true;
        hfpBatteryLevel = normalized;
        hfpBatteryUpdatedAt = SystemClock.elapsedRealtime();
        refreshBatteryValues();
        markTelemetryUpdated(true, false);
        publishSnapshot(token);
    }

    private void handleOemDeviceStateChange(
            long token,
            @NonNull String address,
            @NonNull PhoneOemConnectionBridge.DeviceStateChange change) {
        if (!address.equalsIgnoreCase(selectedAddress)
                || config == null || !config.transportNeeded() || ancsReady) return;
        Handler handler = worker;
        if (handler == null) return;
        Runnable previous = oemGattRefreshTask;
        if (previous != null) handler.removeCallbacks(previous);
        Runnable refresh = () -> runIfCurrent(token, () -> {
            oemGattRefreshTask = null;
            if (!address.equalsIgnoreCase(selectedAddress)
                    || config == null || !config.transportNeeded() || ancsReady) return;
            BluetoothGatt expected = gatt;
            forceDirectGatt = true;
            ancsPublicationRetryCount = 0;
            if (expected == null) {
                ancsStatus = "connecting";
                ensureGatt(token);
            } else {
                refreshGattCache(expected);
                scheduleGattReconnect(token,
                        "ECARX selected-phone state changed: " + change.name(),
                        "services_changed");
            }
            publishSnapshot(token);
        });
        oemGattRefreshTask = refresh;
        // Let the stock owner finish writing its paired-device/UUID database before reopening
        // Android 9's GATT client. Repeated callbacks collapse into this one clean refresh.
        handler.postDelayed(refresh, 900L);
    }

    private void replaceOemPowerObservation(
            @Nullable PhoneOemConnectionBridge.Observation replacement) {
        PhoneOemConnectionBridge.Observation previous;
        synchronized (lifecycleLock) {
            previous = oemPowerObservation;
            oemPowerObservation = replacement;
        }
        if (previous != replacement) closeOemObservation(previous);
    }

    private static void closeOemObservation(
            @Nullable PhoneOemConnectionBridge.Observation observation) {
        if (observation == null) return;
        try {
            observation.close();
        } catch (RuntimeException ignored) {
        }
    }

    /**
     * Serializes the vendor connection owner and this app's BLE client. The ECARX extension can
     * be present before its backing service is ready, so a rejected request gets two bounded
     * retries. An accepted request receives a short settle window for HFP/A2DP/MAP before GATT.
     */
    private void beginStockConnectionRequest(long token, @NonNull String address) {
        cancelStockConnectionRequest();
        if (!isCurrent(token) || selectedDevice == null || address.trim().isEmpty()) {
            stockConnectionStatus = "invalid_address";
            ensureGatt(token);
            return;
        }
        stockConnectionRequestInProgress = true;
        stockConnectionAttempt = 0;
        stockConnectionStatus = "requesting";
        publishSnapshot(token);
        performStockConnectionRequest(token, address);
    }

    private void performStockConnectionRequest(long token, @NonNull String address) {
        if (!isCurrent(token) || !stockConnectionRequestInProgress) return;
        PhoneOemConnectionBridge.RequestResult result =
                PhoneOemConnectionBridge.requestStockConnection(context, address);
        stockConnectionAttempt++;
        stockConnectionStatus = result.diagnosticCode();
        publishSnapshot(token);

        Handler handler = worker;
        if (handler == null) {
            stockConnectionRequestInProgress = false;
            return;
        }
        if (result.accepted()) {
            Runnable settle = () -> runIfCurrent(token, () -> {
                stockConnectionTask = null;
                stockConnectionRequestInProgress = false;
                startOemPowerObservation(token, address);
                ensureGatt(token);
            });
            stockConnectionTask = settle;
            handler.postDelayed(settle, PhoneConnectorPolicy.stockConnectionSettleMillis());
            return;
        }
        if (result.retryable()
                && stockConnectionAttempt < PhoneConnectorPolicy.stockConnectionMaxAttempts()) {
            int retryIndex = stockConnectionAttempt - 1;
            Runnable retry = () -> runIfCurrent(token, () -> {
                stockConnectionTask = null;
                performStockConnectionRequest(token, address);
            });
            stockConnectionTask = retry;
            handler.postDelayed(retry,
                    PhoneConnectorPolicy.stockConnectionRetryDelayMillis(retryIndex));
            return;
        }
        // ECARX is optional. A missing/rejected stock request must remain visible in diagnostics,
        // but it must not suppress the exact-device public GATT path.
        stockConnectionRequestInProgress = false;
        ensureGatt(token);
    }

    private void queryInitialProfileState(long token, @NonNull BluetoothAdapter adapter,
                                          int profileId) {
        try {
            BluetoothProfile.ServiceListener listener = new BluetoothProfile.ServiceListener() {
                @Override public void onServiceConnected(int connectedProfile,
                                                         BluetoothProfile proxy) {
                    try {
                        BluetoothDevice exactDevice = null;
                        List<BluetoothDevice> devices = proxy.getConnectedDevices();
                        if (devices != null) {
                            for (BluetoothDevice device : devices) {
                                if (isSelected(device)) {
                                    exactDevice = device;
                                    break;
                                }
                            }
                        }
                        if (exactDevice != null && profileId == PROFILE_MAP_CLIENT) {
                            runIfCurrent(token, () -> {
                                if (!mapConnected) beginMapSession();
                                updateConnected(token);
                            });
                            boolean requestBackfill;
                            synchronized (lifecycleLock) {
                                requestBackfill = isCurrentLocked(token) && mapConnected
                                        && config != null && config.messagesEnabled
                                        && isSelected(exactDevice);
                            }
                            if (requestBackfill) {
                                requestUnreadMapMessages(proxy, exactDevice);
                            }
                        } else if (exactDevice != null
                                && profileId == PROFILE_HEADSET_CLIENT) {
                            HfpInitialState initial =
                                    readInitialHfpState(proxy, exactDevice);
                            runIfCurrent(token, () -> {
                                hfpConnected = true;
                                applyInitialHfpState(token, initial);
                                updateConnected(token);
                            });
                        } else if (exactDevice != null
                                && profileId == BluetoothProfile.A2DP) {
                            runIfCurrent(token, () -> {
                                aclConnected = true;
                                updateConnected(token);
                            });
                        }
                    } catch (RuntimeException ignored) {
                        // Hidden automotive profiles are optional and may reject app callers.
                    } finally {
                        try {
                            adapter.closeProfileProxy(profileId, proxy);
                        } catch (RuntimeException ignored) {}
                    }
                }

                @Override public void onServiceDisconnected(int disconnectedProfile) {
                    // This reports loss of the local profile-proxy binder, not loss of the
                    // selected phone. We intentionally close this one-shot proxy above.
                }
            };
            adapter.getProfileProxy(context, listener, profileId);
        } catch (Throwable ignored) {
            // Unsupported profile id / permission denial is an explicit fail-closed result.
        }
    }

    @NonNull
    private static HfpInitialState readInitialHfpState(
            @NonNull BluetoothProfile proxy, @NonNull BluetoothDevice device) {
        Bundle agEvents = null;
        List<?> currentCalls = null;
        Integer audioState = null;
        try {
            Method method = proxy.getClass().getMethod(
                    "getCurrentAgEvents", BluetoothDevice.class);
            method.setAccessible(true);
            Object raw = method.invoke(proxy, device);
            if (raw instanceof Bundle) agEvents = new Bundle((Bundle) raw);
        } catch (Throwable ignored) {}
        try {
            Method method = proxy.getClass().getMethod(
                    "getCurrentCalls", BluetoothDevice.class);
            method.setAccessible(true);
            Object raw = method.invoke(proxy, device);
            if (raw instanceof List<?>) currentCalls = new ArrayList<>((List<?>) raw);
        } catch (Throwable ignored) {}
        try {
            Method method = proxy.getClass().getMethod(
                    "getAudioState", BluetoothDevice.class);
            method.setAccessible(true);
            Object raw = method.invoke(proxy, device);
            if (raw instanceof Number) audioState = ((Number) raw).intValue();
        } catch (Throwable ignored) {}
        return new HfpInitialState(agEvents, currentCalls, audioState);
    }

    private void applyInitialHfpState(long token, @NonNull HfpInitialState initial) {
        if (initial.agEvents != null) {
            Intent event = new Intent(ACTION_HFP_AG_EVENT);
            event.putExtras(initial.agEvents);
            applyHfpEvent(token, event);
        }
        if (initial.currentCalls != null) {
            calls.clear();
            for (Object call : initial.currentCalls) updateHfpCall(call);
            rebuildHfpCallSummary();
        }
        if (initial.audioState != null) {
            applyHfpAudioState(token, initial.audioState, null);
        }
    }

    private boolean requestUnreadMapMessages(@NonNull BluetoothProfile proxy,
                                             @Nullable BluetoothDevice exactDevice) {
        if (exactDevice == null || !isSelected(exactDevice)) return false;
        try {
            Method method = proxy.getClass().getMethod(
                    "getUnreadMessages", BluetoothDevice.class);
            method.setAccessible(true);
            Object result = method.invoke(proxy, exactDevice);
            return result instanceof Boolean && (Boolean) result;
        } catch (Throwable ignored) {
            // Hidden API enforcement or an OEM profile without backfill leaves live MAP events.
            return false;
        }
    }

    private void scheduleDeviceRescan(long token) {
        Handler handler = worker;
        if (handler == null || deviceRescanTask != null) return;
        Runnable retry = () -> runIfCurrent(token, () -> {
            deviceRescanTask = null;
            if (selectedDevice == null) selectAndConnect(token);
        });
        deviceRescanTask = retry;
        handler.postDelayed(retry, DEVICE_RESCAN_MS);
    }

    @Nullable
    private BluetoothDevice selectBondedPhone(@NonNull BluetoothAdapter adapter,
                                              @NonNull String requestedAddress) {
        final Set<BluetoothDevice> bonded;
        try {
            bonded = adapter.getBondedDevices();
        } catch (RuntimeException error) {
            lastError = "Bonded devices: " + safeMessage(error);
            return null;
        }
        if (bonded == null || bonded.isEmpty()) return null;
        String requested = requestedAddress.trim();
        if (requested.isEmpty()) return null;
        for (BluetoothDevice candidate : bonded) {
            String address = safeAddress(candidate);
            if (requested.equalsIgnoreCase(address)) return candidate;
        }
        return null;
    }

    private void ensureGatt(long token) {
        if (!isCurrent(token) || selectedDevice == null
                || stockConnectionRequestInProgress) return;
        cancelGattReconnect();
        Config current = config;
        if (current == null) return;
        if (!current.transportNeeded()) {
            ancsStatus = "disabled";
            ensureLegacyBatteryGatt(token);
            return;
        }
        synchronized (lifecycleLock) {
            if (!isCurrentLocked(token) || ancsTransport != null
                    || ancsTransportStartPending) return;
            ancsTransportStartPending = true;
            activeAncsTransportSession = ++nextAncsTransportSession;
        }
        ancsStatus = "connecting";
        publishSnapshot(token);

        final long transportSession = activeAncsTransportSession;
        final String address = current.ancsDeviceAddress;
        mainHandler.post(() -> startAncsTransportOnMain(
                token, transportSession, address));
    }

    /**
     * HA1122 opened a BLE GATT even when ANCS was disabled so BAS 0x180F could supplement the
     * classic HFP/OEM battery sources. Keep that exact path for battery-only configurations; it
     * is mutually exclusive with {@link IphoneAncsTransport}, which owns GATT whenever ANCS is
     * enabled.
     */
    private void ensureLegacyBatteryGatt(long token) {
        if (!isCurrent(token) || selectedDevice == null || gatt != null
                || stockConnectionRequestInProgress) return;
        boolean autoConnect = ancsAuthorizedThisRun && !forceDirectGatt;
        try {
            BluetoothGatt created = selectedDevice.connectGatt(context, autoConnect,
                    new SessionGattCallback(token), BluetoothDevice.TRANSPORT_LE);
            synchronized (lifecycleLock) {
                if (!isCurrentLocked(token)
                        || config == null || config.transportNeeded()) {
                    closeGatt(created);
                    return;
                }
                gatt = created;
            }
            if (created == null) {
                scheduleGattReconnect(token, "connectGatt returned null");
            } else {
                scheduleConnectWatchdog(token, created, autoConnect);
            }
        } catch (Throwable error) {
            scheduleGattReconnect(token, "BAS GATT connect: " + safeMessage(error));
        }
    }

    /**
     * Starts the proven Android-central saved-peer path on the main looper. The transport owns
     * the only live BluetoothGatt instance; the legacy GATT methods below remain solely for
     * source compatibility while the rest of the phone connector (HFP/A2DP/MAP/OEM data) stays
     * unchanged.
     */
    private void startAncsTransportOnMain(long token, long transportSession,
                                          @NonNull String address) {
        if (!isCurrent(token)
                || transportSession != activeAncsTransportSession) return;

        final IphoneAncsTransport created;
        try {
            created = new IphoneAncsTransport(context,
                    new AncsTransportListener(token, transportSession));
        } catch (Throwable error) {
            dispatchAncsTransport(token, transportSession, () ->
                    handleAncsTransportFailure(token,
                            "ANCS transport init: " + safeMessage(error)));
            return;
        }

        boolean accepted;
        synchronized (lifecycleLock) {
            accepted = isCurrentLocked(token)
                    && transportSession == activeAncsTransportSession
                    && ancsTransport == null;
            if (accepted) {
                ancsTransport = created;
                ancsTransportStartPending = false;
            }
        }
        if (!accepted) {
            created.close();
            return;
        }

        try {
            created.publishCapabilities();
            if (!created.connectSavedIphone(address)) {
                dispatchAncsTransport(token, transportSession, () ->
                        handleAncsTransportFailure(token,
                                "connectSavedIphone rejected " + maskedAddress(address)));
            }
        } catch (Throwable error) {
            dispatchAncsTransport(token, transportSession, () ->
                    handleAncsTransportFailure(token,
                            "ANCS saved-peer connect: " + safeMessage(error)));
        }
    }

    private final class AncsTransportListener implements IphoneAncsTransport.Listener {
        private final long token;
        private final long transportSession;

        AncsTransportListener(long token, long transportSession) {
            this.token = token;
            this.transportSession = transportSession;
        }

        @Override public void onState(String state) {
            dispatchAncsTransport(token, transportSession,
                    () -> handleAncsTransportState(token, state));
        }

        @Override public void onRetryRequired(String reason) {
            dispatchAncsTransport(token, transportSession,
                    () -> handleAncsTransportFailure(token,
                            reason == null || reason.trim().isEmpty()
                                    ? "ANCS transport disconnected" : reason));
        }

        @Override public void onLog(String line) {
            if (line != null && !line.trim().isEmpty()) {
                Log.d(TAG, "ANCS: " + redactedDiagnostic(line));
            }
        }

        @Override public void onCandidates(List<IphoneAncsTransport.Candidate> candidates) {
            // Daily saved-peer operation never scans. Candidate callbacks belong only to the
            // one-time Helper/bootstrap diagnostics and are intentionally not published here.
        }

        @Override public void onNotification(IphoneAncsTransport.NotificationItem item) {
            dispatchAncsTransport(token, transportSession,
                    () -> handleAncsTransportNotification(token, item));
        }

        @Override public void onAppName(String appIdentifier, String displayName) {
            dispatchAncsTransport(token, transportSession,
                    () -> handleAncsTransportAppName(
                            token, appIdentifier, displayName));
        }

        @Override public void onBatteryCharacteristic(UUID characteristicUuid, byte[] value) {
            byte[] copy = value == null ? null : value.clone();
            dispatchAncsTransport(token, transportSession,
                    () -> applyBatteryCharacteristic(token, characteristicUuid, copy));
        }

        @Override public void onHelperTelemetry(IphoneHelperTelemetry telemetry) {
            dispatchAncsTransport(token, transportSession,
                    () -> applyHelperTelemetry(token, telemetry));
        }
    }

    private void dispatchAncsTransport(long token, long transportSession,
                                       @NonNull Runnable action) {
        Handler handler = worker;
        if (handler == null) return;
        handler.post(() -> {
            if (!isCurrent(token)
                    || transportSession != activeAncsTransportSession) return;
            action.run();
        });
    }

    /** Applies only values delivered by the authenticated iPhone Helper channel. */
    private void applyHelperTelemetry(long token, @NonNull IphoneHelperTelemetry telemetry) {
        long now = SystemClock.elapsedRealtime();
        boolean hasPower = telemetry.kind == IphoneHelperTelemetry.Kind.POWER
                || telemetry.kind == IphoneHelperTelemetry.Kind.SNAPSHOT;
        boolean hasNetwork = telemetry.kind == IphoneHelperTelemetry.Kind.NETWORK
                || telemetry.kind == IphoneHelperTelemetry.Kind.SNAPSHOT;
        if (hasPower) {
            helperBatteryLevel = telemetry.batteryLevel;
            helperExternalPower = telemetry.externalPower;
            helperChargeState = telemetry.chargeState;
            helperPowerUpdatedAtElapsed = now;
            refreshBatteryValues();
        }
        if (hasNetwork) {
            helperNetworkType = telemetry.networkType;
            helperNetworkUpdatedAtElapsed = now;
        }
        markTelemetryUpdated(hasPower, hasNetwork);
        scheduleHelperTelemetryExpiry(token);
        publishSnapshot(token);
    }

    private void scheduleHelperTelemetryExpiry(long token) {
        Handler handler = worker;
        if (handler == null) return;
        Runnable previous = helperTelemetryExpiryTask;
        if (previous != null) handler.removeCallbacks(previous);
        long now = SystemClock.elapsedRealtime();
        long nextDeadline = Long.MAX_VALUE;
        if (helperPowerUpdatedAtElapsed > 0L) {
            nextDeadline = Math.min(nextDeadline,
                    helperPowerUpdatedAtElapsed + HELPER_TELEMETRY_TIMEOUT_MS);
        }
        if (helperNetworkUpdatedAtElapsed > 0L) {
            nextDeadline = Math.min(nextDeadline,
                    helperNetworkUpdatedAtElapsed + HELPER_TELEMETRY_TIMEOUT_MS);
        }
        if (nextDeadline == Long.MAX_VALUE) {
            helperTelemetryExpiryTask = null;
            return;
        }
        Runnable expiry = () -> runIfCurrent(token, () -> {
            helperTelemetryExpiryTask = null;
            long checkedAt = SystemClock.elapsedRealtime();
            boolean changed = false;
            if (helperPowerUpdatedAtElapsed > 0L
                    && checkedAt - helperPowerUpdatedAtElapsed >= HELPER_TELEMETRY_TIMEOUT_MS) {
                helperBatteryLevel = null;
                helperExternalPower = null;
                helperChargeState = "";
                helperPowerUpdatedAtElapsed = 0L;
                changed = true;
            }
            if (helperNetworkUpdatedAtElapsed > 0L
                    && checkedAt - helperNetworkUpdatedAtElapsed >= HELPER_TELEMETRY_TIMEOUT_MS) {
                helperNetworkType = "";
                helperNetworkUpdatedAtElapsed = 0L;
                changed = true;
            }
            if (changed) {
                refreshBatteryValues();
                publishSnapshot(token);
            }
            scheduleHelperTelemetryExpiry(token);
        });
        helperTelemetryExpiryTask = expiry;
        handler.postDelayed(expiry, Math.max(1L, nextDeadline - now));
    }

    private void clearHelperTelemetry() {
        Runnable task = helperTelemetryExpiryTask;
        Handler handler = worker;
        if (task != null && handler != null) handler.removeCallbacks(task);
        helperTelemetryExpiryTask = null;
        helperBatteryLevel = null;
        helperExternalPower = null;
        helperChargeState = "";
        helperNetworkType = "";
        helperPowerUpdatedAtElapsed = 0L;
        helperNetworkUpdatedAtElapsed = 0L;
    }

    private void handleAncsTransportState(long token, @Nullable String rawState) {
        String state = rawState == null ? "" : rawState.trim();
        if (state.contains("ANCS READY")) {
            gattConnected = true;
            ancsReady = true;
            ancsWasReadyThisSession = true;
            cancelAncsAdapterEscalation();
            ancsAuthorizedThisRun = true;
            ancsStatus = "ready";
            lastError = "";
            scheduleStableAncsReadyReset(token);
            rebuildMessageSnapshot();
            cancelSmsFallbackNotifications();
            updateMessageAvailability();
            updateConnected(token);
            return;
        }
        if (state.contains("IPHONE BLE CONNECTED")) {
            gattConnected = true;
            ancsReady = false;
            ancsStatus = "negotiating";
            updateConnected(token);
            return;
        }
        if (state.contains("RECOVERING") || state.contains("IDENTITY SCAN")) {
            scheduleAncsAdapterEscalation(token, state);
            cancelStableAncsReadyReset();
            gattConnected = false;
            ancsReady = false;
            ancsStatus = "retrying";
            updateMessageAvailability();
            updateConnected(token);
            return;
        }
        if (state.contains("AUTO · SERVICE CHANGED · RECONNECT")) {
            scheduleGattReconnect(token, "ANCS Service Changed", "services_changed");
            return;
        }
        if (isTerminalAncsTransportState(state)) {
            scheduleGattReconnect(token,
                    state.isEmpty() ? "ANCS transport failed" : state,
                    "retrying");
            return;
        }
        if (!state.isEmpty() && !"ОТКЛЮЧЕНО".equals(state)) {
            ancsStatus = normalizeAncsState(state);
            publishSnapshot(token);
        }
    }

    private static boolean isTerminalAncsTransportState(@NonNull String state) {
        return state.contains("CONNECT RETURNED NULL")
                || state.contains("CONNECT TIMEOUT")
                || state.contains("CONNECT EXCEPTION")
                || state.contains("SAVED PEER SCAN UNAVAILABLE")
                || state.contains("SAVED PEER SCAN FAILED")
                || state.contains("SAVED PEER CONFLICT")
                || state.contains("PEER CONFLICT")
                || state.contains("CONNECTION FAILED")
                || state.contains("GPS-STYLE FAILED")
                || state.contains("IPHONE DISCONNECTED")
                || state.contains("DISCOVERY_FAILED_")
                || state.contains("DISCOVERY_START_FAILED")
                || state.contains("DISCOVERY_TIMEOUT")
                || state.contains("ANCS_INCOMPLETE")
                || state.contains("SUBSCRIBE_EXCEPTION")
                || state.contains("SUBSCRIBE_LOCAL_FAILED")
                || state.contains("CCCD_START_FAILED")
                || state.contains("CCCD_WRITE_EXCEPTION")
                || state.contains("CCCD_WRITE_TIMEOUT")
                || state.contains("CCCD_FAILED_")
                || state.contains("ANCS DATA DESYNC")
                || state.contains("ANCS WAIT TIMEOUT")
                || state.contains("SECURE READ FAILED")
                || state.contains("BOND_START_FAILED")
                || state.contains("LE BOND TIMEOUT")
                || state.contains("LE BOND FAILED")
                || state.contains("ATTEMPTS EXHAUSTED")
                || state.contains("PAIRING FAILED")
                || state.contains("AUTH FAILED ПОСЛЕ BOND");
    }

    @NonNull
    private static String normalizeAncsState(@NonNull String state) {
        String normalized = state.trim().toLowerCase(Locale.ROOT)
                .replace('·', '_')
                .replace(' ', '_');
        while (normalized.contains("__")) normalized = normalized.replace("__", "_");
        return bounded(normalized, 128);
    }

    private void handleAncsTransportFailure(long token, @NonNull String detail) {
        synchronized (lifecycleLock) {
            if (isCurrentLocked(token)) ancsTransportStartPending = false;
        }
        scheduleAncsAdapterEscalation(token, detail);
        scheduleGattReconnect(token, detail, "retrying");
    }

    private void handleAncsTransportNotification(
            long token, @Nullable IphoneAncsTransport.NotificationItem item) {
        if (item == null || !ancsReady || config == null || !config.ancsNeeded()) return;
        if (item.eventId == dezz.status.widget.phone.transport.AncsProtocol.EVENT_REMOVED) {
            removeAncsNotification(token, item.uid);
            return;
        }

        String cleanAppIdentifier = bounded(item.appIdentifier, 512);
        String cleanAppName = bounded(item.appName, 256);
        PhoneAppIconStore.Observation iconObservation =
                PhoneAppIconStore.get(context).observe(
                        cleanAppIdentifier, cleanAppName, item.categoryId);
        boolean appleMessage = isAppleMessagesApp(item.appIdentifier);
        boolean allowed = config.notificationsEnabled
                || config.messagesEnabled && appleMessage;
        if (!allowed || !config.allowsNotification(
                item.appIdentifier, item.categoryId)) return;
        long observedAtElapsedMs = item.observedAtElapsedMs > 0L
                ? item.observedAtElapsedMs : SystemClock.elapsedRealtime();
        if (SystemClock.elapsedRealtime() - observedAtElapsedMs
                > APP_DISPLAY_NAME_WAIT_TIMEOUT_MS) {
            Log.w(TAG, "Dropping ANCS notification " + item.uid
                    + ": transport item exceeded real-time TTL");
            return;
        }

        if (!cleanAppIdentifier.isEmpty() && !cleanAppName.isEmpty()) {
            cacheAppDisplayName(cleanAppIdentifier, cleanAppName);
        }
        AncsProtocol.Notification notification = new AncsProtocol.Notification(
                item.uid,
                cleanAppIdentifier,
                bounded(item.title, 4096),
                "",
                bounded(item.message, 4096),
                bounded(item.date, 256));
        boolean hasDisplayName = !cleanAppName.isEmpty()
                || appDisplayNames.containsKey(cleanAppIdentifier);
        NotificationRecord record = new NotificationRecord(
                notification, item.categoryId, System.currentTimeMillis(), false,
                observedAtElapsedMs, iconObservation.iconWasCached);
        notificationCache.remove(item.uid);
        notificationCache.put(item.uid, record);
        trimNotificationCache();
        if (hasDisplayName) {
            presentAncsNotification(token, record, true);
        } else {
            scheduleUnresolvedNotificationExpiry(token, record);
        }
    }

    private void handleAncsTransportAppName(long token, @Nullable String rawAppIdentifier,
                                            @Nullable String rawDisplayName) {
        String appIdentifier = bounded(rawAppIdentifier, 512);
        String displayName = bounded(rawDisplayName, 256).trim();
        if (appIdentifier.isEmpty() || displayName.isEmpty()) return;
        cacheAppDisplayName(appIdentifier, displayName);
        PhoneAppIconStore.get(context).updateName(appIdentifier, displayName);
        boolean changed = false;
        Iterator<Map.Entry<Long, NotificationRecord>> iterator =
                notificationCache.entrySet().iterator();
        while (iterator.hasNext()) {
            NotificationRecord record = iterator.next().getValue();
            if (!appIdentifier.equals(record.notification.appIdentifier)) continue;
            if (!record.presented) {
                if (isUnresolvedNotificationExpired(record)) {
                    iterator.remove();
                    continue;
                }
                presentAncsNotification(token, record, false);
            } else {
                mirrorAncsNotification(token, record);
            }
            changed = true;
        }
        if (appIdentifier.equals(lastAppIdentifier)) {
            lastAppName = displayName;
            changed = true;
        }
        if (changed) publishSnapshot(token);
    }

    private void presentAncsNotification(long token, @NonNull NotificationRecord record,
                                         boolean publish) {
        if (record.presented) return;
        record.presented = true;
        lastAppIdentifier = bounded(record.notification.appIdentifier, 512);
        lastAppName = displayNameFor(record.notification.appIdentifier);
        lastAppCategoryId = record.categoryId;
        lastNotificationAt = record.receivedAt;
        upsertAncsMessage(record);
        mirrorAncsNotification(token, record);
        if (publish) publishSnapshot(token);
    }

    /**
     * A DisplayName response belongs to a live notification, not to an archive. If iOS never
     * completes that App Attributes request, discard the hidden record instead of allowing a
     * later notification for the same bundle to release an old burst.
     */
    private void scheduleUnresolvedNotificationExpiry(
            long token, @NonNull NotificationRecord expected) {
        Handler handler = worker;
        if (handler == null) return;
        long uid = expected.notification.uid;
        long age = Math.max(0L,
                SystemClock.elapsedRealtime() - expected.observedAtElapsedMs);
        long remaining = Math.max(0L, APP_DISPLAY_NAME_WAIT_TIMEOUT_MS - age);
        handler.postDelayed(() -> runIfCurrent(token, () -> {
            NotificationRecord current = notificationCache.get(uid);
            if (current != expected || current.presented) return;
            notificationCache.remove(uid);
            Log.w(TAG, "Dropping live ANCS notification " + uid
                    + ": App DisplayName unresolved after "
                    + APP_DISPLAY_NAME_WAIT_TIMEOUT_MS + " ms");
        }), remaining);
    }

    private static boolean isUnresolvedNotificationExpired(
            @NonNull NotificationRecord record) {
        return !record.presented
                && SystemClock.elapsedRealtime() - record.observedAtElapsedMs
                > APP_DISPLAY_NAME_WAIT_TIMEOUT_MS;
    }

    private final class SessionGattCallback extends BluetoothGattCallback {
        private final long token;

        SessionGattCallback(long token) {
            this.token = token;
        }

        private void dispatch(@NonNull Runnable action) {
            Handler handler = worker;
            if (handler != null) handler.post(() -> runIfCurrent(token, action));
        }

        @Override public void onConnectionStateChange(BluetoothGatt callbackGatt, int status,
                                                       int newState) {
            dispatch(() -> handleGattConnection(token, callbackGatt, status, newState));
        }

        @Override public void onServicesDiscovered(BluetoothGatt callbackGatt, int status) {
            dispatch(() -> handleServicesDiscovered(token, callbackGatt, status));
        }

        @Override public void onDescriptorWrite(BluetoothGatt callbackGatt,
                                                BluetoothGattDescriptor descriptor, int status) {
            dispatch(() -> {
                if (callbackGatt == gatt) {
                    finishGattOperation(token, GattKind.DESCRIPTOR,
                            descriptor, null, status);
                }
            });
        }

        @Override public void onCharacteristicWrite(BluetoothGatt callbackGatt,
                                                    BluetoothGattCharacteristic characteristic,
                                                    int status) {
            dispatch(() -> {
                if (callbackGatt == gatt) {
                    finishGattOperation(token, GattKind.CONTROL_WRITE,
                            null, characteristic, status);
                }
            });
        }

        @Override public void onCharacteristicRead(BluetoothGatt callbackGatt,
                                                   BluetoothGattCharacteristic characteristic,
                                                   int status) {
            byte[] rawValue = characteristic.getValue();
            byte[] value = rawValue == null ? null : rawValue.clone();
            dispatch(() -> {
                if (callbackGatt != gatt) return;
                if (!matchesGattOperation(GattKind.CHARACTERISTIC_READ,
                        null, characteristic)) return;
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    applyBatteryCharacteristic(token, characteristic.getUuid(), value);
                }
                finishGattOperation(token, GattKind.CHARACTERISTIC_READ,
                        null, characteristic, status);
            });
        }

        @Override public void onMtuChanged(BluetoothGatt callbackGatt, int mtu, int status) {
            dispatch(() -> {
                if (callbackGatt != gatt || !mtuPending) return;
                mtuPending = false;
                cancelMtuWatchdog();
                startServiceDiscovery(token, callbackGatt);
            });
        }

        @Override public void onCharacteristicChanged(BluetoothGatt callbackGatt,
                                                      BluetoothGattCharacteristic characteristic) {
            byte[] rawValue = characteristic.getValue();
            byte[] value = rawValue == null ? null : rawValue.clone();
            UUID uuid = characteristic.getUuid();
            dispatch(() -> {
                if (callbackGatt == gatt) {
                    handleCharacteristicChanged(token, uuid, value);
                }
            });
        }
    }

    private void handleGattConnection(long token, @NonNull BluetoothGatt callbackGatt, int status,
                                      int newState) {
        if (callbackGatt != gatt) {
            closeGatt(callbackGatt);
            return;
        }
        if (status == BluetoothGatt.GATT_SUCCESS
                && newState == BluetoothProfile.STATE_CONNECTED) {
            cancelConnectWatchdog();
            gattConnected = true;
            serviceDiscoveryStarted = false;
            if (config != null && config.ancsNeeded()) ancsStatus = "negotiating";
            else reconnectAttempt = 0;
            updateConnected(token);
            beginMtuNegotiation(token, callbackGatt);
            return;
        }
        cancelGattWatchdogs();
        gattConnected = false;
        persistCurrentTelemetry();
        clearBasData();
        resetAncsSession(token, "disconnected");
        updateConnected(token);
        scheduleGattReconnect(token, "GATT disconnected (" + status + ")");
    }

    private void beginMtuNegotiation(long token, @NonNull BluetoothGatt callbackGatt) {
        boolean requested = false;
        try {
            requested = callbackGatt.requestMtu(DESIRED_GATT_MTU);
        } catch (RuntimeException ignored) {
            // MTU enlargement is only an optimisation; fragmented ANCS responses remain valid.
        }
        if (!requested) {
            startServiceDiscovery(token, callbackGatt);
            return;
        }
        mtuPending = true;
        Handler handler = worker;
        if (handler == null) {
            mtuPending = false;
            startServiceDiscovery(token, callbackGatt);
            return;
        }
        Runnable timeout = () -> runIfCurrent(token, () -> {
            if (callbackGatt != gatt || !mtuPending) return;
            mtuPending = false;
            mtuWatchdog = null;
            startServiceDiscovery(token, callbackGatt);
        });
        mtuWatchdog = timeout;
        handler.postDelayed(timeout, GATT_MTU_TIMEOUT_MS);
    }

    private void startServiceDiscovery(long token, @NonNull BluetoothGatt callbackGatt) {
        if (!isCurrent(token) || callbackGatt != gatt || serviceDiscoveryStarted) return;
        cancelMtuWatchdog();
        mtuPending = false;
        serviceDiscoveryStarted = true;
        if (config != null && config.ancsNeeded()) ancsStatus = "discovering";
        try {
            if (!callbackGatt.discoverServices()) {
                serviceDiscoveryStarted = false;
                scheduleGattReconnect(token, "Service discovery did not start");
                return;
            }
            scheduleDiscoveryWatchdog(token, callbackGatt);
        } catch (RuntimeException error) {
            serviceDiscoveryStarted = false;
            scheduleGattReconnect(token, "Service discovery: " + safeMessage(error));
        }
    }

    private void handleServicesDiscovered(long token, @NonNull BluetoothGatt callbackGatt,
                                          int status) {
        if (callbackGatt != gatt) {
            closeGatt(callbackGatt);
            return;
        }
        if (!serviceDiscoveryStarted) return;
        cancelDiscoveryWatchdog();
        serviceDiscoveryStarted = false;
        if (status != BluetoothGatt.GATT_SUCCESS) {
            scheduleGattReconnect(token, "Service discovery failed (" + status + ")");
            return;
        }
        resetGattOperationState();
        if (config == null || !config.ancsNeeded()) {
            ancsStatus = "disabled";
            configureBatteryService(callbackGatt);
            pumpGattOperations(token);
            publishSnapshot(token);
            return;
        }
        BluetoothGattService service = callbackGatt.getService(AncsProtocol.SERVICE);
        if (service == null) {
            // No protected ANCS characteristic exists to provoke an authorization prompt in this
            // state. Stay connected and let Service Changed publish ANCS after stock pairing or
            // after the user enables notification sharing on the iPhone.
            ancsStatus = "service_not_published";
            if (!configureServiceChanged(callbackGatt)) {
                lastError = "ANCS is not published and GATT Service Changed is unavailable";
            }
            configureBatteryService(callbackGatt);
            publishSnapshot(token);
            pumpGattOperations(token);
            scheduleAncsPublicationRetry(token, callbackGatt);
            return;
        }
        cancelAncsPublicationRetry();
        ancsPublicationRetryCount = 0;
        ancsControlPoint = service.getCharacteristic(AncsProtocol.CONTROL_POINT);
        ancsDataSource = service.getCharacteristic(AncsProtocol.DATA_SOURCE);
        ancsNotificationSource = service.getCharacteristic(AncsProtocol.NOTIFICATION_SOURCE);
        if (ancsControlPoint == null || ancsDataSource == null
                || ancsNotificationSource == null) {
            ancsStatus = "characteristic_unavailable";
            publishSnapshot(token);
            scheduleGattReconnect(token, "ANCS characteristics are incomplete");
            return;
        }
        if (!queueNotificationSubscription(callbackGatt, ancsDataSource,
                GattTag.ANCS_DATA)
                || !queueNotificationSubscription(callbackGatt, ancsNotificationSource,
                GattTag.ANCS_NOTIFICATION)) {
            scheduleGattReconnect(token, "ANCS subscription is unsupported");
            return;
        }
        // Service Changed is a resilience subscription, not part of the ANCS authorization
        // handshake. Queue it only after the protected ANCS descriptors so an OEM Android 9
        // failure cannot prevent the iPhone permission request.
        if (!configureServiceChanged(callbackGatt)) {
            lastError = "GATT Service Changed subscription is unavailable";
        }
        ancsStatus = "subscribing";
        configureBatteryService(callbackGatt);
        publishSnapshot(token);
        pumpGattOperations(token);
    }

    private void configureBatteryService(@NonNull BluetoothGatt callbackGatt) {
        BluetoothGattService battery = callbackGatt.getService(BATTERY_SERVICE);
        if (battery == null) return;
        BluetoothGattCharacteristic level = battery.getCharacteristic(BATTERY_LEVEL);
        if (level != null) {
            queueCharacteristicRead(level, GattTag.BATTERY_LEVEL_READ);
            queueOptionalNotificationSubscription(callbackGatt, level,
                    GattTag.BATTERY_LEVEL_SUBSCRIPTION);
        }
        BluetoothGattCharacteristic status = battery.getCharacteristic(BATTERY_LEVEL_STATUS);
        if (status != null) {
            queueCharacteristicRead(status, GattTag.BATTERY_LEVEL_STATUS_READ);
            queueOptionalNotificationSubscription(callbackGatt, status,
                    GattTag.BATTERY_LEVEL_STATUS_SUBSCRIPTION);
        }
    }

    private boolean configureServiceChanged(@NonNull BluetoothGatt callbackGatt) {
        BluetoothGattService generic = callbackGatt.getService(GENERIC_ATTRIBUTE_SERVICE);
        if (generic == null) return false;
        BluetoothGattCharacteristic changed = generic.getCharacteristic(SERVICE_CHANGED);
        if (changed == null) return false;
        serviceChangedCharacteristic = changed;
        return queueIndicationSubscription(callbackGatt, changed, GattTag.SERVICE_CHANGED);
    }

    private boolean queueNotificationSubscription(@NonNull BluetoothGatt callbackGatt,
                                                  @NonNull BluetoothGattCharacteristic item,
                                                  @NonNull GattTag tag) {
        BluetoothGattDescriptor descriptor =
                item.getDescriptor(AncsProtocol.CLIENT_CONFIGURATION);
        if (descriptor == null) return false;
        try {
            if (!callbackGatt.setCharacteristicNotification(item, true)) return false;
            gattOperations.add(new GattOperation(GattKind.DESCRIPTOR, tag, descriptor,
                    null, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE));
            if (tag == GattTag.ANCS_NOTIFICATION) ancsNotificationListening = true;
            return true;
        } catch (RuntimeException error) {
            return false;
        }
    }

    private boolean queueIndicationSubscription(@NonNull BluetoothGatt callbackGatt,
                                                @NonNull BluetoothGattCharacteristic item,
                                                @NonNull GattTag tag) {
        int properties = item.getProperties();
        if ((properties & BluetoothGattCharacteristic.PROPERTY_INDICATE) == 0) return false;
        BluetoothGattDescriptor descriptor =
                item.getDescriptor(AncsProtocol.CLIENT_CONFIGURATION);
        if (descriptor == null) return false;
        try {
            if (callbackGatt.setCharacteristicNotification(item, true)) {
                gattOperations.add(new GattOperation(GattKind.DESCRIPTOR, tag, descriptor,
                        null, BluetoothGattDescriptor.ENABLE_INDICATION_VALUE));
                return true;
            }
        } catch (RuntimeException ignored) {
            // Service Changed is a resilience feature. ANCS may still remain stable for the
            // lifetime of this encrypted connection on stacks that hide the indication.
        }
        return false;
    }

    private void queueOptionalNotificationSubscription(
            @NonNull BluetoothGatt callbackGatt,
            @NonNull BluetoothGattCharacteristic item, @NonNull GattTag tag) {
        int properties = item.getProperties();
        if ((properties & BluetoothGattCharacteristic.PROPERTY_NOTIFY) == 0) return;
        BluetoothGattDescriptor descriptor =
                item.getDescriptor(AncsProtocol.CLIENT_CONFIGURATION);
        if (descriptor == null) return;
        try {
            if (callbackGatt.setCharacteristicNotification(item, true)) {
                gattOperations.add(new GattOperation(GattKind.DESCRIPTOR, tag, descriptor,
                        null, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE));
            }
        } catch (RuntimeException ignored) {
            // Standard battery service notifications are optional; the initial read still works.
        }
    }

    private void queueCharacteristicRead(@NonNull BluetoothGattCharacteristic characteristic,
                                         @NonNull GattTag tag) {
        if ((characteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_READ) != 0) {
            gattOperations.add(new GattOperation(GattKind.CHARACTERISTIC_READ, tag, null,
                    characteristic, null));
        }
    }

    private void pumpGattOperations(long token) {
        if (!isCurrent(token) || currentGattOperation != null || gatt == null) return;
        GattOperation operation = gattOperations.poll();
        if (operation == null) {
            maybeFinishAncsSetup(token);
            return;
        }
        currentGattOperation = operation;
        boolean started = false;
        try {
            if (operation.kind == GattKind.DESCRIPTOR && operation.descriptor != null) {
                operation.descriptor.setValue(operation.payload == null
                        ? BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE : operation.payload);
                started = gatt.writeDescriptor(operation.descriptor);
            } else if (operation.kind == GattKind.CHARACTERISTIC_READ
                    && operation.characteristic != null) {
                started = gatt.readCharacteristic(operation.characteristic);
            } else if (operation.kind == GattKind.CONTROL_WRITE
                    && operation.characteristic != null && operation.payload != null) {
                operation.characteristic.setValue(operation.payload);
                started = gatt.writeCharacteristic(operation.characteristic);
            }
        } catch (RuntimeException error) {
            lastError = "GATT operation: " + safeMessage(error);
        }
        if (!started) {
            finishGattOperation(token, operation.kind, operation.descriptor,
                    operation.characteristic, -1);
            return;
        }
        Handler handler = worker;
        if (handler != null) {
            Runnable timeout = () -> runIfCurrent(token, () -> {
                if (currentGattOperation == operation) {
                    if (operation.tag == GattTag.SERVICE_CHANGED) {
                        // Service Changed is optional. Completing its stuck write as a failure
                        // lets the bounded ANCS-publication recovery own the one allowed reconnect
                        // instead of creating a fresh unbounded 90-second reconnect loop.
                        finishGattOperation(token, operation.kind, operation.descriptor,
                                operation.characteristic, -1);
                    } else {
                        scheduleGattReconnect(token,
                                "GATT operation timed out: " + operation.tag.name());
                    }
                }
            });
            gattOperationTimeout = timeout;
            handler.postDelayed(timeout, gattOperationTimeoutMillis(operation));
        }
    }

    private long gattOperationTimeoutMillis(@NonNull GattOperation operation) {
        if (!ancsAuthorizedThisRun && operation.kind == GattKind.DESCRIPTOR
                && (operation.tag == GattTag.ANCS_DATA
                || operation.tag == GattTag.ANCS_NOTIFICATION
                || operation.tag == GattTag.SERVICE_CHANGED)) {
            return ANCS_AUTHORIZATION_OPERATION_TIMEOUT_MS;
        }
        return GATT_OPERATION_TIMEOUT_MS;
    }

    private void finishGattOperation(long token, @NonNull GattKind callbackKind,
                                     @Nullable BluetoothGattDescriptor callbackDescriptor,
                                     @Nullable BluetoothGattCharacteristic callbackCharacteristic,
                                     int status) {
        GattOperation operation = currentGattOperation;
        if (operation == null || operation.kind != callbackKind) return;
        if (callbackKind == GattKind.DESCRIPTOR
                && operation.descriptor != callbackDescriptor) return;
        if ((callbackKind == GattKind.CHARACTERISTIC_READ
                || callbackKind == GattKind.CONTROL_WRITE)
                && operation.characteristic != callbackCharacteristic) return;
        boolean success = status == BluetoothGatt.GATT_SUCCESS;
        Runnable operationTimeout = gattOperationTimeout;
        if (operationTimeout != null && worker != null) {
            worker.removeCallbacks(operationTimeout);
        }
        gattOperationTimeout = null;
        currentGattOperation = null;
        if (operation.tag == GattTag.ANCS_DATA) {
            ancsDataSubscribed = success;
        } else if (operation.tag == GattTag.ANCS_NOTIFICATION) {
            ancsNotificationSubscribed = success;
        } else if (operation.tag == GattTag.SERVICE_CHANGED) {
            serviceChangedSubscribed = success;
            if (success && ancsReady) {
                ancsStatus = "ready";
                lastError = "";
                publishSnapshot(token);
            } else if (!success) {
                lastError = "GATT Service Changed descriptor write failed (" + status + ")";
            }
        } else if (operation.tag == GattTag.CONTROL) {
            if (operation.requestSequence != activeAncsRequestSequence) {
                // This exact response already completed before Android delivered the write
                // callback. A newer request, even for the same UID, must own subsequent state.
                pumpGattOperations(token);
                return;
            }
            if (success) scheduleAttributeTimeout(token, operation);
            else if (status == ANCS_INVALID_PARAMETER) {
                abandonInvalidAncsRequest(token, operation);
                return;
            } else {
                scheduleGattReconnect(token, "ANCS control-point write failed (" + status + ")",
                        isAuthorizationFailure(status) ? "authorization_required" : "retrying");
                return;
            }
        }
        if (!success && (operation.tag == GattTag.ANCS_DATA
                || operation.tag == GattTag.ANCS_NOTIFICATION)) {
            if (isAuthorizationFailure(status)) {
                // Do not tear down the LE link while Android/iOS may still be completing their
                // user-driven security flow. Continue to the other descriptor and the optional
                // Service Changed subscription; a bond completion, service change or explicit
                // test can then retry the full ANCS setup on this exact device.
                lastError = "ANCS authorization is required (" + status + ")";
                ancsStatus = "authorization_required";
                publishSnapshot(token);
                pumpGattOperations(token);
                return;
            }
            scheduleGattReconnect(token, "ANCS descriptor write failed (" + status + ")",
                    "retrying");
            return;
        }
        maybeFinishAncsSetup(token);
        pumpGattOperations(token);
    }

    private boolean matchesGattOperation(@NonNull GattKind callbackKind,
                                         @Nullable BluetoothGattDescriptor callbackDescriptor,
                                         @Nullable BluetoothGattCharacteristic callbackCharacteristic) {
        GattOperation operation = currentGattOperation;
        if (operation == null || operation.kind != callbackKind) return false;
        if (callbackKind == GattKind.DESCRIPTOR) {
            return operation.descriptor == callbackDescriptor;
        }
        return operation.characteristic == callbackCharacteristic;
    }

    private void maybeFinishAncsSetup(long token) {
        if (!ancsReady && ancsDataSubscribed && ancsNotificationSubscribed) {
            ancsReady = true;
            ancsWasReadyThisSession = true;
            cancelAncsAdapterEscalation();
            ancsAuthorizedThisRun = true;
            forceDirectGatt = false;
            reconnectAttempt = 0;
            ancsStatus = serviceChangedSubscribed ? "ready" : "ready_degraded";
            if (serviceChangedSubscribed) lastError = "";
            rebuildMessageSnapshot();
            cancelSmsFallbackNotifications();
            updateMessageAvailability();
            publishSnapshot(token);
            pumpAttributeRequests(token);
        }
    }

    private void handleCharacteristicChanged(long token, @NonNull UUID uuid,
                                             @Nullable byte[] payload) {
        if (AncsProtocol.NOTIFICATION_SOURCE.equals(uuid)) {
            handleAncsEvent(token, AncsProtocol.parseEvent(payload));
        } else if (AncsProtocol.DATA_SOURCE.equals(uuid)) {
            handleAncsData(token, payload);
        } else if (SERVICE_CHANGED.equals(uuid)) {
            handleServiceChanged(token);
        } else if (BATTERY_LEVEL.equals(uuid) || BATTERY_LEVEL_STATUS.equals(uuid)) {
            applyBatteryCharacteristic(token, uuid, payload);
        }
    }

    private void handleAncsEvent(long token, @Nullable AncsProtocol.Event event) {
        Config current = config;
        if (event == null || current == null || !current.ancsNeeded()
                || ancsNotificationSource == null || !ancsNotificationListening) return;
        if (event.eventId == AncsProtocol.EVENT_REMOVED) {
            removeAncsNotification(token, event.uid);
            return;
        }
        if (!current.allowsCategory(event.categoryId)) return;
        removedAttributeUids.remove(event.uid);
        pendingAncsEvents.remove(event.uid);
        pendingAncsEvents.put(event.uid, event);
        if (activeAttributeUid != null && activeAttributeUid == event.uid) {
            dirtyAttributeUids.add(event.uid);
        } else if (queuedAttributeUids.add(event.uid)) {
            trimPendingNotificationRequests();
            attributeRequests.add(event.uid);
        }
        pumpAttributeRequests(token);
    }

    private void trimPendingNotificationRequests() {
        while (attributeRequests.size() >= MAX_PENDING_ANCS_REQUESTS) {
            Long dropped = attributeRequests.poll();
            if (dropped == null) break;
            queuedAttributeUids.remove(dropped);
            pendingAncsEvents.remove(dropped);
            dirtyAttributeUids.remove(dropped);
            fullTextAttributeUids.remove(dropped);
        }
    }

    private void handleServiceChanged(long token) {
        if (gatt == null || !gattConnected) return;
        // Android 9 vendor stacks can deliver late callbacks from the old attribute database.
        // Refreshing then reopening GATT gives the new database its own callback identity and
        // operation queue instead of rediscovering Android's stale cached service list.
        forceDirectGatt = true;
        refreshGattCache(gatt);
        scheduleGattReconnect(token, "GATT services changed", "services_changed");
    }

    private void restartAncsAfterBond(long token, @NonNull BluetoothGatt expected) {
        Handler handler = worker;
        if (handler == null) return;
        handler.postDelayed(() -> runIfCurrent(token, () -> {
            if (gatt != expected || !gattConnected) return;
            // Never overlap service discovery with a descriptor write that Android's security
            // manager may still be completing. Reopen one clean client after the bond settles.
            forceDirectGatt = true;
            scheduleGattReconnect(token, "Bluetooth LE bond completed", "negotiating");
        }), 750L);
    }

    private void pumpAttributeRequests(long token) {
        if (!ancsReady || activeAttributeUid != null || activeAppIdentifier != null
                || ancsControlPoint == null) return;
        Long uid = attributeRequests.poll();
        if (uid != null) {
            queuedAttributeUids.remove(uid);
            activeAttributeUid = uid;
            long requestSequence = ++nextAncsRequestSequence;
            activeAncsRequestSequence = requestSequence;
            boolean includeText = config != null && config.includeNotificationText
                    && (config.notificationsEnabled || fullTextAttributeUids.contains(uid));
            activeAttributeIncludesText = includeText;
            attributeAccumulator = new AncsProtocol.AttributeAccumulator(uid, includeText);
            byte[] request = AncsProtocol.notificationAttributeRequest(uid, includeText);
            gattOperations.add(new GattOperation(GattKind.CONTROL_WRITE, GattTag.CONTROL,
                    null, ancsControlPoint, request, uid, requestSequence));
            pumpGattOperations(token);
            return;
        }
        while (true) {
            String appIdentifier = appAttributeRequests.poll();
            if (appIdentifier == null) return;
            queuedAppIdentifiers.remove(appIdentifier);
            final AncsProtocol.AppAttributeAccumulator accumulator;
            final byte[] request;
            try {
                accumulator = new AncsProtocol.AppAttributeAccumulator(appIdentifier);
                request = AncsProtocol.appAttributeRequest(appIdentifier);
            } catch (IllegalArgumentException invalidIdentifier) {
                cacheAppDisplayName(appIdentifier,
                        PhoneAppCatalog.displayNameFallback(appIdentifier));
                continue;
            }
            activeAppIdentifier = appIdentifier;
            long requestSequence = ++nextAncsRequestSequence;
            activeAncsRequestSequence = requestSequence;
            appAttributeAccumulator = accumulator;
            gattOperations.add(new GattOperation(GattKind.CONTROL_WRITE, GattTag.CONTROL,
                    null, ancsControlPoint, request, appIdentifier, requestSequence));
            pumpGattOperations(token);
            return;
        }
    }

    private void scheduleAttributeTimeout(long token, @NonNull GattOperation operation) {
        Handler handler = worker;
        if (handler == null) {
            scheduleGattReconnect(token, "ANCS response worker unavailable");
            return;
        }
        long expectedSequence = operation.requestSequence;
        // The Data Source response is allowed to arrive before Android reports completion of the
        // Control Point write. If that already completed this exact request, there is no response
        // left to time out and the next serialized request owns any newly queued operation.
        if (expectedSequence == 0L || activeAncsRequestSequence != expectedSequence) return;
        Runnable timeout = () -> runIfCurrent(token, () -> {
            if (activeAncsRequestSequence == expectedSequence) {
                scheduleGattReconnect(token, "ANCS attribute response timed out");
            }
        });
        attributeTimeout = timeout;
        handler.postDelayed(timeout, ATTRIBUTE_TIMEOUT_MS);
    }

    private void handleAncsData(long token, @Nullable byte[] payload) {
        if (payload == null) return;
        if (activeAttributeUid != null && attributeAccumulator != null) {
            handleNotificationAttributes(token, payload);
            return;
        }
        if (activeAppIdentifier != null && appAttributeAccumulator != null) {
            handleAppAttributes(token, payload);
        }
    }

    private void handleNotificationAttributes(long token, @NonNull byte[] payload) {
        Long uid = activeAttributeUid;
        AncsProtocol.AttributeAccumulator accumulator = attributeAccumulator;
        long requestSequence = activeAncsRequestSequence;
        boolean responseIncludedText = activeAttributeIncludesText;
        if (uid == null || accumulator == null) return;
        if (!accumulator.append(payload)) {
            scheduleGattReconnect(token, "ANCS notification response exceeded its limit");
            return;
        }
        AncsProtocol.Notification notification = accumulator.complete();
        if (notification == null) return;
        Config current = config;
        AncsProtocol.Event pendingEvent = pendingAncsEvents.get(uid);
        int categoryId = pendingEvent == null ? 0 : pendingEvent.categoryId;
        PhoneAppIconStore.Observation iconObservation =
                PhoneAppIconStore.get(context).observe(
                        notification.appIdentifier,
                        displayNameFor(notification.appIdentifier),
                        categoryId);
        boolean appleMessage = isAppleMessagesApp(notification.appIdentifier);
        boolean allowed = current != null && (current.notificationsEnabled
                || current.messagesEnabled && appleMessage)
                && current.allowsNotification(
                notification.appIdentifier, categoryId);
        boolean needsMessageTextFollowUp = allowed && !responseIncludedText
                && current != null && !current.notificationsEnabled
                && current.messagesEnabled && current.includeNotificationText
                && appleMessage;
        if (needsMessageTextFollowUp && !removedAttributeUids.contains(uid)) {
            fullTextAttributeUids.add(uid);
            dirtyAttributeUids.add(uid);
            completeAttributeRequest(token, uid, requestSequence);
            return;
        }
        if (!allowed) {
            pendingAncsEvents.remove(uid);
            dirtyAttributeUids.remove(uid);
            fullTextAttributeUids.remove(uid);
        } else if (!removedAttributeUids.contains(uid)) {
            pendingAncsEvents.remove(uid);
            NotificationRecord record = new NotificationRecord(
                    notification, categoryId, System.currentTimeMillis(), true,
                    SystemClock.elapsedRealtime(), iconObservation.iconWasCached);
            notificationCache.remove(uid);
            notificationCache.put(uid, record);
            trimNotificationCache();
            queueAppDisplayName(notification.appIdentifier);
            lastAppIdentifier = bounded(notification.appIdentifier, 512);
            lastAppName = displayNameFor(notification.appIdentifier);
            lastAppCategoryId = categoryId;
            lastNotificationAt = record.receivedAt;
            upsertAncsMessage(record);
            mirrorAncsNotification(token, record);
        }
        completeAttributeRequest(token, uid, requestSequence);
    }

    private void handleAppAttributes(long token, @NonNull byte[] payload) {
        String appIdentifier = activeAppIdentifier;
        AncsProtocol.AppAttributeAccumulator accumulator = appAttributeAccumulator;
        long requestSequence = activeAncsRequestSequence;
        if (appIdentifier == null || accumulator == null) return;
        if (!accumulator.append(payload)) {
            scheduleGattReconnect(token, "ANCS app response exceeded its limit");
            return;
        }
        String displayName = accumulator.complete();
        if (displayName == null) return;
        String cleanName = bounded(displayName, 256);
        cacheAppDisplayName(appIdentifier, cleanName.isEmpty()
                ? PhoneAppCatalog.displayNameFallback(appIdentifier) : cleanName);
        PhoneAppIconStore.get(context).updateName(appIdentifier,
                cleanName.isEmpty()
                        ? PhoneAppCatalog.displayNameFallback(appIdentifier)
                        : cleanName);
        if (appIdentifier.equals(lastAppIdentifier)) {
            lastAppName = displayNameFor(appIdentifier);
        }
        for (NotificationRecord record : notificationCache.values()) {
            if (appIdentifier.equals(record.notification.appIdentifier)) {
                mirrorAncsNotification(token, record);
            }
        }
        completeAppAttributeRequest(token, appIdentifier, requestSequence);
    }

    private void queueAppDisplayName(@Nullable String rawAppIdentifier) {
        String appIdentifier = bounded(rawAppIdentifier, 512);
        if (appIdentifier.isEmpty() || appDisplayNames.containsKey(appIdentifier)
                || appIdentifier.equals(activeAppIdentifier)
                || !queuedAppIdentifiers.add(appIdentifier)) return;
        while (appAttributeRequests.size() >= MAX_PENDING_ANCS_REQUESTS) {
            String dropped = appAttributeRequests.poll();
            if (dropped == null) break;
            queuedAppIdentifiers.remove(dropped);
        }
        appAttributeRequests.add(appIdentifier);
    }

    private void cacheAppDisplayName(@NonNull String appIdentifier,
                                     @NonNull String displayName) {
        appDisplayNames.remove(appIdentifier);
        appDisplayNames.put(appIdentifier, displayName);
        while (appDisplayNames.size() > MAX_APP_DISPLAY_NAMES) {
            Iterator<String> iterator = appDisplayNames.keySet().iterator();
            if (!iterator.hasNext()) break;
            iterator.next();
            iterator.remove();
        }
    }

    private void completeAttributeRequest(long token, long uid, long requestSequence) {
        if (activeAncsRequestSequence != requestSequence
                || activeAttributeUid == null || activeAttributeUid != uid) return;
        cancelAttributeTimeout();
        activeAttributeUid = null;
        attributeAccumulator = null;
        activeAttributeIncludesText = false;
        activeAncsRequestSequence = 0L;
        boolean removed = removedAttributeUids.remove(uid);
        if (dirtyAttributeUids.remove(uid) && !removed
                && queuedAttributeUids.add(uid)) {
            attributeRequests.add(uid);
        }
        publishSnapshot(token);
        pumpAttributeRequests(token);
    }

    private void cancelAttributeTimeout() {
        Runnable timeout = attributeTimeout;
        if (timeout != null && worker != null) worker.removeCallbacks(timeout);
        attributeTimeout = null;
    }

    private void completeAppAttributeRequest(long token, @NonNull String appIdentifier,
                                             long requestSequence) {
        if (activeAncsRequestSequence != requestSequence
                || !appIdentifier.equals(activeAppIdentifier)) return;
        cancelAttributeTimeout();
        activeAppIdentifier = null;
        appAttributeAccumulator = null;
        activeAncsRequestSequence = 0L;
        publishSnapshot(token);
        pumpAttributeRequests(token);
    }

    /**
     * A notification may disappear between its Notification Source event and the serialized
     * Control Point write. Apple reports that normal race as Invalid Parameter (0xA2); dropping
     * only that request keeps the encrypted ANCS session alive and lets later events continue.
     */
    private void abandonInvalidAncsRequest(long token, @NonNull GattOperation operation) {
        if (operation.requestSequence != activeAncsRequestSequence) {
            pumpGattOperations(token);
            return;
        }
        if (operation.uid >= 0L && activeAttributeUid != null
                && activeAttributeUid.longValue() == operation.uid) {
            pendingAncsEvents.remove(operation.uid);
            fullTextAttributeUids.remove(operation.uid);
            completeAttributeRequest(token, operation.uid, operation.requestSequence);
            return;
        }
        String appIdentifier = operation.appIdentifier;
        if (appIdentifier != null && appIdentifier.equals(activeAppIdentifier)) {
            cacheAppDisplayName(appIdentifier,
                    PhoneAppCatalog.displayNameFallback(appIdentifier));
            if (appIdentifier.equals(lastAppIdentifier)) {
                lastAppName = displayNameFor(appIdentifier);
            }
            completeAppAttributeRequest(token, appIdentifier, operation.requestSequence);
            return;
        }
        pumpGattOperations(token);
    }

    private void removeAncsNotification(long token, long uid) {
        if (activeAttributeUid != null && activeAttributeUid.longValue() == uid) {
            removedAttributeUids.add(uid);
        } else {
            removedAttributeUids.remove(uid);
        }
        pendingAncsEvents.remove(uid);
        dirtyAttributeUids.remove(uid);
        fullTextAttributeUids.remove(uid);
        if (queuedAttributeUids.remove(uid)) attributeRequests.remove(uid);
        notificationCache.remove(uid);
        if (ancsMessageCache.remove(uid) != null) rebuildMessageSnapshot();
        Integer notificationId = mirroredAncsIds.remove(uid);
        if (notificationId != null) cancelMirroredNotification(notificationId);
        publishSnapshot(token);
    }

    private void trimNotificationCache() {
        while (notificationCache.size() > MAX_NOTIFICATIONS) {
            Iterator<Map.Entry<Long, NotificationRecord>> iterator =
                    notificationCache.entrySet().iterator();
            if (!iterator.hasNext()) break;
            long uid = iterator.next().getKey();
            iterator.remove();
            fullTextAttributeUids.remove(uid);
            Integer notificationId = mirroredAncsIds.remove(uid);
            if (notificationId != null) cancelMirroredNotification(notificationId);
        }
    }

    private void applyBatteryCharacteristic(long token, @NonNull UUID uuid,
                                            @Nullable byte[] payload) {
        if (payload == null || payload.length == 0) return;
        int raw = payload[0] & 0xff;
        boolean percentageUpdated = false;
        if (BATTERY_LEVEL.equals(uuid) && raw <= 100) {
            basBatteryKnown = true;
            basBatteryLevel = raw;
            basBatteryUpdatedAt = SystemClock.elapsedRealtime();
            percentageUpdated = true;
        } else if (BATTERY_LEVEL_STATUS.equals(uuid)) {
            Integer decodedLevel = PhoneConnectorPolicy.decodeBatteryLevelStatusLevel(payload);
            if (decodedLevel == null) return;
            basBatteryKnown = true;
            basBatteryLevel = decodedLevel;
            basBatteryUpdatedAt = SystemClock.elapsedRealtime();
            percentageUpdated = true;
        }
        // BAS/HFP remain optional percentage fallbacks only. Their charging bits are deliberately
        // ignored: the encrypted iPhone Helper frame is the sole power-state authority.
        if (!percentageUpdated) return;
        refreshBatteryValues();
        markTelemetryUpdated(true, false);
        publishSnapshot(token);
    }

    private void applyHfpEvent(long token, @NonNull Intent intent) {
        boolean batteryUpdated = false;
        boolean networkUpdated = false;
        Integer rawBattery = intExtra(intent, EXTRA_HFP_BATTERY, "battery_level",
                "batteryLevel");
        if (rawBattery != null) {
            Integer normalized = PhoneConnectorPolicy.normalizeHfpBattery(rawBattery);
            if (normalized != null) {
                hfpBatteryKnown = true;
                hfpBatteryLevel = normalized;
                hfpBatteryUpdatedAt = SystemClock.elapsedRealtime();
                batteryUpdated = true;
            }
        }
        Integer rawSignal = intExtra(intent, EXTRA_HFP_NETWORK_SIGNAL,
                "android.bluetooth.headsetclient.extra.NETWORK_SIGNAL",
                "network_signal", "signal", "signal_level");
        if (rawSignal != null) {
            networkSignal = PhoneConnectorPolicy.normalizeHfpSignal(rawSignal);
            networkUpdated = true;
        }
        Integer status = intExtra(intent, EXTRA_HFP_NETWORK_STATUS, "network_status");
        if (status != null) {
            networkAvailable = status != 0;
            networkUpdated = true;
            if (!networkAvailable) {
                networkSignal = null;
                networkRoaming = null;
                networkOperator = "";
            }
        }
        if (status == null || status != 0) {
            Boolean roaming = booleanExtra(intent, EXTRA_HFP_NETWORK_ROAMING,
                    "network_roaming", "roaming");
            if (roaming != null) {
                networkRoaming = roaming;
                networkUpdated = true;
            }
            String operator = stringExtra(intent, EXTRA_HFP_OPERATOR, "operator_name",
                    "operator");
            if (operator != null) {
                networkOperator = bounded(operator, 256);
                networkUpdated = true;
            }

        }
        Boolean voiceRecognition = booleanExtra(intent, EXTRA_HFP_VOICE_RECOGNITION,
                "voice_recognition", "voiceRecognition");
        if (voiceRecognition != null) voiceRecognitionActive = voiceRecognition;
        Boolean inBandRing = booleanExtra(intent, EXTRA_HFP_IN_BAND_RING,
                "in_band_ring", "inBandRing");
        if (inBandRing != null) inBandRingSupported = inBandRing;
        refreshBatteryValues();
        if (batteryUpdated || networkUpdated) {
            markTelemetryUpdated(batteryUpdated, networkUpdated);
        }
        publishSnapshot(token);
    }

    private void applyHfpAudioState(long token, int state, @Nullable Boolean wideband) {
        if (state == HFP_AUDIO_CONNECTED) {
            callAudioConnected = true;
            callAudioState = "connected";
            callAudioWideband = wideband;
        } else if (state == HFP_AUDIO_CONNECTING) {
            callAudioConnected = false;
            callAudioState = "connecting";
            callAudioWideband = null;
        } else {
            callAudioConnected = false;
            callAudioState = "disconnected";
            callAudioWideband = null;
        }
        publishSnapshot(token);
    }

    private void applyHfpCall(long token, @Nullable Object rawCall) {
        if (updateHfpCall(rawCall)) {
            rebuildHfpCallSummary();
            publishSnapshot(token);
        }
    }

    private boolean updateHfpCall(@Nullable Object rawCall) {
        if (rawCall == null) return false;
        Integer state = reflectedInt(rawCall, "getState");
        if (state == null || state < 0 || state > 7) return false;
        Object uuid = reflected(rawCall, "getUUID");
        Integer id = reflectedInt(rawCall, "getId");
        String key = uuid == null ? id == null ? "" : "id:" + id : "uuid:" + uuid;
        if (key.isEmpty()) return false;
        if (state == 7) {
            calls.remove(key);
            return true;
        }
        Boolean outgoing = reflectedBoolean(rawCall, "isOutgoing");
        Boolean multiparty = reflectedBoolean(rawCall, "isMultiParty");
        calls.put(key, new CallRecord(state,
                Boolean.TRUE.equals(outgoing) ? "outgoing" : "incoming",
                Boolean.TRUE.equals(multiparty)));
        return true;
    }

    private void rebuildHfpCallSummary() {
        if (calls.isEmpty()) {
            callActive = false;
            callState = "idle";
            callDirection = "";
            callMultiparty = false;
            return;
        }
        CallRecord selected = null;
        for (CallRecord candidate : calls.values()) {
            if (selected == null
                    || callStatePriority(candidate.state) < callStatePriority(selected.state)) {
                selected = candidate;
            }
        }
        callActive = true;
        callState = selected == null ? "" : callStateCode(selected.state);
        callDirection = selected == null ? "" : selected.direction;
        boolean multiparty = false;
        for (CallRecord item : calls.values()) multiparty |= item.multiparty;
        callMultiparty = multiparty;
    }

    private void clearHfpCallData() {
        calls.clear();
        callActive = null;
        callState = "";
        callDirection = "";
        callMultiparty = null;
        callAudioConnected = null;
        callAudioState = "";
        callAudioWideband = null;
    }

    @NonNull
    private static String callStateCode(int state) {
        switch (state) {
            case 0: return "active";
            case 1: return "held";
            case 2: return "dialing";
            case 3: return "alerting";
            case 4: return "incoming";
            case 5: return "waiting";
            case 6: return "held_by_response";
            default: return "";
        }
    }

    private static int callStatePriority(int state) {
        switch (state) {
            case 4: return 0;
            case 5: return 1;
            case 2: return 2;
            case 3: return 3;
            case 0: return 4;
            case 1:
            case 6: return 5;
            default: return 6;
        }
    }

    private void restoreRetainedTelemetry(@NonNull String address) {
        retainedTelemetry = telemetryStore.load(address, System.currentTimeMillis());
        batteryLiveSeenThisConnection = false;
        networkLiveSeenThisConnection = false;
        telemetryStale = retainedTelemetry != null;
    }

    private void markTelemetryUpdated(boolean battery, boolean network) {
        if (battery) batteryLiveSeenThisConnection = true;
        if (network) networkLiveSeenThisConnection = true;
        telemetryStale = false;
        persistCurrentTelemetry();
    }

    private void markTelemetryDisconnected() {
        batteryLiveSeenThisConnection = false;
        networkLiveSeenThisConnection = false;
        telemetryStale = retainedTelemetryFresh(System.currentTimeMillis());
    }

    private void persistCurrentTelemetry() {
        if (selectedAddress.isEmpty()) return;
        PhoneTelemetryStore.Record previous = retainedTelemetry;
        long now = System.currentTimeMillis();
        // Every power field is Helper-only live session data. BAS/HFP/OEM readings remain useful
        // in diagnostics, but are never persisted or substituted into the visible iPhone tile.
        Integer savedBatteryLevel = null;
        String savedBatteryLevelSource = "";
        Boolean savedCharging = null;
        Boolean savedChargingEstimated = null;
        String savedChargingSource = "";
        Boolean savedExternalPower = null;
        String savedChargeState = "";
        String savedChargeLevel = "";
        Boolean savedNetworkAvailable = networkLiveSeenThisConnection
                ? networkAvailable : previous == null ? null : previous.networkAvailable;
        Integer savedNetworkSignal = networkLiveSeenThisConnection
                ? networkSignal : previous == null ? null : previous.networkSignal;
        Boolean savedNetworkRoaming = networkLiveSeenThisConnection
                ? networkRoaming : previous == null ? null : previous.networkRoaming;
        String savedNetworkOperator = networkLiveSeenThisConnection
                ? networkOperator : previous == null ? "" : previous.networkOperator;
        String savedNetworkType = "";
        long batteryUpdatedAt = 0L;
        long networkUpdatedAt = networkLiveSeenThisConnection
                ? now : previous == null ? 0L : previous.networkUpdatedAtWallMs;
        PhoneTelemetryStore.Record next = new PhoneTelemetryStore.Record(
                selectedAddress, batteryUpdatedAt, networkUpdatedAt,
                savedBatteryLevel, savedBatteryLevelSource,
                savedCharging, savedChargingEstimated, savedChargingSource,
                savedExternalPower, savedChargeState, savedChargeLevel,
                savedNetworkAvailable, savedNetworkSignal, savedNetworkRoaming,
                savedNetworkOperator, savedNetworkType);
        if (!next.hasUsefulData()) return;
        retainedTelemetry = next;
        telemetryStore.save(next);
    }

    private boolean retainedTelemetryFresh(long nowWallMs) {
        return retainedTelemetry != null
                && PhoneTelemetryStore.isFresh(
                retainedTelemetry.updatedAtWallMs, nowWallMs);
    }

    private boolean retainedBatteryFresh(long nowWallMs) {
        return retainedTelemetry != null && PhoneTelemetryStore.isFresh(
                retainedTelemetry.batteryUpdatedAtWallMs, nowWallMs);
    }

    private boolean retainedNetworkFresh(long nowWallMs) {
        return retainedTelemetry != null && PhoneTelemetryStore.isFresh(
                retainedTelemetry.networkUpdatedAtWallMs, nowWallMs);
    }

    private void refreshBatteryValues() {
        if (helperPowerUpdatedAtElapsed > 0L && helperBatteryLevel != null) {
            batteryLevel = helperBatteryLevel;
            batteryLevelSource = "iphone_helper";
        } else {
            batteryLevel = null;
            batteryLevelSource = "";
        }
        // Charging and percentage share the exact same authenticated Helper heartbeat.
        if (helperPowerUpdatedAtElapsed > 0L) {
            batteryExternalPower = helperExternalPower;
            batteryChargeState = helperChargeState;
            batteryChargeLevel = "";
            if ("charging".equals(helperChargeState)) {
                batteryCharging = true;
            } else if ("full".equals(helperChargeState)
                    || "unplugged".equals(helperChargeState)) {
                batteryCharging = false;
            } else {
                batteryCharging = null;
            }
            batteryChargingEstimated = batteryCharging == null ? null : false;
            batteryChargingSource = "iphone_helper";
        } else {
            batteryCharging = null;
            batteryChargingEstimated = null;
            batteryChargingSource = "";
            batteryExternalPower = null;
            batteryChargeState = "";
            batteryChargeLevel = "";
        }
    }

    private void clearBasData() {
        basBatteryKnown = false;
        basBatteryLevel = null;
        basBatteryUpdatedAt = 0L;
        refreshBatteryValues();
        batteryLiveSeenThisConnection = hfpBatteryKnown || genericBatteryKnown
                || helperPowerUpdatedAtElapsed > 0L;
    }

    private void clearHfpData() {
        hfpBatteryKnown = false;
        hfpBatteryLevel = null;
        hfpBatteryUpdatedAt = 0L;
        networkAvailable = null;
        networkSignal = null;
        networkRoaming = null;
        networkOperator = "";
        voiceRecognitionActive = null;
        inBandRingSupported = null;
        clearHfpCallData();
        refreshBatteryValues();
        networkLiveSeenThisConnection = false;
        batteryLiveSeenThisConnection = basBatteryKnown || genericBatteryKnown
                || helperPowerUpdatedAtElapsed > 0L;
    }

    private void clearGenericBatteryData() {
        genericBatteryKnown = false;
        genericBatteryLevel = null;
        genericBatteryUpdatedAt = 0L;
        refreshBatteryValues();
        batteryLiveSeenThisConnection = basBatteryKnown || hfpBatteryKnown
                || helperPowerUpdatedAtElapsed > 0L;
    }

    private void beginMapSession() {
        mapConnected = true;
        clearMapMessages();
        updateMessageAvailability();
    }

    private void endMapSession(@NonNull String status) {
        mapConnected = false;
        clearMapMessages();
        updateMessageAvailability();
        if (!smsAvailable && config != null && config.messagesEnabled) smsStatus = status;
    }

    private void clearMapMessages() {
        mapMessageCache.clear();
        rebuildMessageSnapshot();
        cancelSmsFallbackNotifications();
    }

    private void handleMapMessage(long token, @NonNull Intent intent) {
        Config current = config;
        if (!mapConnected || current == null || !current.messagesEnabled) return;
        // MAP is the selected iPhone's Messages fallback. Apply the same app/category policy as
        // ANCS so an excluded Messages app cannot reappear through the second transport.
        if (!current.allowsNotification("com.apple.MobileSMS", 4)) return;
        String handle = bounded(intent.getStringExtra(EXTRA_MAP_MESSAGE_HANDLE), 256);
        String text = bounded(intent.getStringExtra(Intent.EXTRA_TEXT), 4_096);
        if (handle.isEmpty() || current.includeNotificationText && text.isEmpty()) {
            lastError = "MAP message ignored: missing handle"
                    + (current.includeNotificationText ? " or text" : "");
            smsStatus = "invalid_message";
            publishSnapshot(token);
            return;
        }
        String senderName = bounded(intent.getStringExtra(EXTRA_MAP_SENDER_NAME), 256);
        String senderUri = bounded(intent.getStringExtra(EXTRA_MAP_SENDER_URI), 512);
        boolean read = intent.getBooleanExtra(EXTRA_MAP_MESSAGE_READ, false);
        long timestamp = Math.max(0L, intent.getLongExtra(
                EXTRA_MAP_MESSAGE_TIMESTAMP, System.currentTimeMillis()));
        boolean newMessage = !mapMessageCache.containsKey(handle);

        LinkedHashMap<String, Object> row = new LinkedHashMap<>();
        row.put("id", handle);
        row.put("source", "map");
        row.put("app_name", "Сообщения");
        row.put("icon", "messages");
        if (current.includeNotificationText) {
            String sender = firstNonEmpty(senderName, senderUri);
            row.put("sender", sender);
            row.put("body", text);
            row.put("display", firstNonEmpty(sender, "Сообщения"));
        } else {
            row.put("display", "Сообщения");
        }
        row.put("date", timestamp);
        row.put("read", read);
        mapMessageCache.remove(handle);
        mapMessageCache.put(handle, Collections.unmodifiableMap(row));
        while (mapMessageCache.size() > MAX_MAP_MESSAGES) {
            Iterator<String> iterator = mapMessageCache.keySet().iterator();
            if (!iterator.hasNext()) break;
            String removed = iterator.next();
            iterator.remove();
            Integer notificationId = mirroredSmsIds.remove(removed);
            if (notificationId != null) cancelMirroredNotification(notificationId);
        }
        rebuildMessageSnapshot();
        smsAvailable = true;
        smsStatus = "ready";
        if (read) {
            Integer notificationId = mirroredSmsIds.remove(handle);
            if (notificationId != null) cancelMirroredNotification(notificationId);
        } else if (newMessage && !ancsReady) {
            mirrorSmsNotification(token, row);
        }
        publishSnapshot(token);
    }

    private void handleMapMessageStatus(long token, @NonNull Intent intent,
                                        boolean deletedEvent) {
        if (!mapConnected) return;
        String handle = bounded(intent.getStringExtra(EXTRA_MAP_MESSAGE_HANDLE), 256);
        Map<String, Object> existing = mapMessageCache.get(handle);
        if (handle.isEmpty() || existing == null) return;
        boolean deleted = deletedEvent
                && intent.getBooleanExtra(EXTRA_MAP_MESSAGE_DELETED, false);
        if (deleted) {
            mapMessageCache.remove(handle);
            Integer notificationId = mirroredSmsIds.remove(handle);
            if (notificationId != null) cancelMirroredNotification(notificationId);
        } else if (!deletedEvent) {
            LinkedHashMap<String, Object> changed = new LinkedHashMap<>(existing);
            boolean read = intent.getBooleanExtra(EXTRA_MAP_MESSAGE_READ, false);
            changed.put("read", read);
            mapMessageCache.put(handle, Collections.unmodifiableMap(changed));
            if (read) {
                Integer notificationId = mirroredSmsIds.remove(handle);
                if (notificationId != null) cancelMirroredNotification(notificationId);
            }
        }
        rebuildMessageSnapshot();
        publishSnapshot(token);
    }

    private void upsertAncsMessage(@NonNull NotificationRecord record) {
        if (!isAppleMessagesApp(record.notification.appIdentifier)) return;
        Config current = config;
        if (current == null || !current.messagesEnabled) return;
        LinkedHashMap<String, Object> row = new LinkedHashMap<>();
        row.put("id", "ancs:" + record.notification.uid);
        row.put("source", "ancs");
        row.put("app_name", displayNameFor(record.notification.appIdentifier));
        row.put("icon", PhoneAppCatalog.iconKey(
                record.notification.appIdentifier, record.categoryId));
        if (current.includeNotificationText) {
            String sender = firstNonEmpty(record.notification.title,
                    record.notification.subtitle);
            row.put("sender", sender);
            row.put("body", firstNonEmpty(record.notification.message,
                    record.notification.subtitle));
            row.put("display", firstNonEmpty(sender, "Сообщения"));
        } else {
            row.put("display", "Сообщения");
        }
        row.put("date", System.currentTimeMillis());
        row.put("read", false);
        ancsMessageCache.remove(record.notification.uid);
        ancsMessageCache.put(record.notification.uid, Collections.unmodifiableMap(row));
        while (ancsMessageCache.size() > MAX_MAP_MESSAGES) {
            Iterator<Long> iterator = ancsMessageCache.keySet().iterator();
            if (!iterator.hasNext()) break;
            iterator.next();
            iterator.remove();
        }
        rebuildMessageSnapshot();
        updateMessageAvailability();
    }

    private void rebuildMessageSnapshot() {
        smsItems.clear();
        List<Map<String, Object>> ordered = new ArrayList<>();
        if (!ancsReady) ordered.addAll(mapMessageCache.values());
        ordered.addAll(ancsMessageCache.values());
        ordered.sort((left, right) -> Long.compare(messageDate(right), messageDate(left)));
        LinkedHashSet<String> fingerprints = new LinkedHashSet<>();
        for (Map<String, Object> item : ordered) {
            String fingerprint = messageFingerprint(item);
            if (!fingerprints.add(fingerprint)) continue;
            smsItems.add(item);
            if (smsItems.size() >= MAX_MAP_MESSAGES) break;
        }
        smsUnread = 0;
        for (Map<String, Object> item : smsItems) {
            if (!Boolean.TRUE.equals(item.get("read"))) smsUnread++;
        }
        latestSms = smsItems.isEmpty() ? null : smsItems.get(0);
    }

    private void updateMessageAvailability() {
        Config current = config;
        if (current == null || !current.messagesEnabled) {
            smsAvailable = false;
            smsStatus = "disabled";
        } else if (connected && (mapConnected || ancsReady)) {
            smsAvailable = true;
            smsStatus = "ready";
        } else {
            smsAvailable = false;
            smsStatus = connected ? "message_access_unavailable" : "waiting_for_phone";
        }
    }

    private void updateConnected(long token) {
        boolean next = aclConnected || hfpConnected || mapConnected || gattConnected;
        if (next) {
            refreshBatteryValues();
        }
        if (connected == next) {
            publishSnapshot(token);
            return;
        }
        connected = next;
        synchronized (lifecycleLock) {
            if (isCurrentLocked(token)) updatePresenceLocked(next);
        }
        if (!next) {
            clearDisconnectedData(token);
        }
        updateMessageAvailability();
        publishSnapshot(token);
    }

    private void clearDisconnectedData(long token) {
        persistCurrentTelemetry();
        clearHelperTelemetry();
        clearBasData();
        clearHfpData();
        clearGenericBatteryData();
        batteryLevel = null;
        batteryCharging = null;
        batteryChargingEstimated = null;
        batteryExternalPower = null;
        batteryLevelSource = "";
        batteryChargingSource = "";
        batteryChargeState = "";
        batteryChargeLevel = "";
        markTelemetryDisconnected();
        resetAncsSession(token, "disconnected");
        endMapSession("disconnected");
        cancelAllMirroredNotifications();
    }

    private void resetAncsSession(long token, @NonNull String status) {
        cancelStableAncsReadyReset();
        ancsReady = false;
        ancsStatus = config != null && config.transportNeeded() ? status : "disabled";
        clearAncsRuntime();
        updateMessageAvailability();
        for (Integer id : new ArrayList<>(mirroredAncsIds.values())) {
            cancelMirroredNotification(id);
        }
        mirroredAncsIds.clear();
        publishSnapshot(token);
    }

    private void clearAncsRuntime() {
        ancsControlPoint = null;
        ancsDataSource = null;
        ancsNotificationSource = null;
        serviceChangedCharacteristic = null;
        ancsDataSubscribed = false;
        ancsNotificationSubscribed = false;
        ancsNotificationListening = false;
        serviceChangedSubscribed = false;
        resetGattOperationState();
        pendingAncsEvents.clear();
        attributeRequests.clear();
        queuedAttributeUids.clear();
        dirtyAttributeUids.clear();
        removedAttributeUids.clear();
        fullTextAttributeUids.clear();
        appAttributeRequests.clear();
        queuedAppIdentifiers.clear();
        activeAttributeUid = null;
        attributeAccumulator = null;
        activeAttributeIncludesText = false;
        activeAppIdentifier = null;
        appAttributeAccumulator = null;
        activeAncsRequestSequence = 0L;
        cancelAttributeTimeout();
        notificationCache.clear();
        ancsMessageCache.clear();
        appDisplayNames.clear();
        lastAppIdentifier = "";
        lastAppName = "";
        lastAppCategoryId = 0;
        lastNotificationAt = 0L;
        rebuildMessageSnapshot();
    }

    private void resetGattOperationState() {
        Runnable timeout = gattOperationTimeout;
        if (timeout != null && worker != null) worker.removeCallbacks(timeout);
        gattOperationTimeout = null;
        gattOperations.clear();
        currentGattOperation = null;
    }

    private void scheduleGattReconnect(long token, @NonNull String detail) {
        scheduleGattReconnect(token, detail, "retrying");
    }

    /**
     * Do not destroy a healthy ANCS owner merely because ECARX emitted ACL_DISCONNECTED. The
     * live transport knows the resolved BLE identity and can recover without a car-Bluetooth
     * power cycle. If that owner vanished concurrently, fall back to the normal outer restart.
     */
    private void requestManagedAncsReconnect(long token, @NonNull String detail,
                                             boolean confirmedLeLoss) {
        if (confirmedLeLoss) scheduleAncsAdapterEscalation(token, detail);
        IphoneAncsTransport current = ancsTransport;
        long transportSession = activeAncsTransportSession;
        if (current == null) {
            scheduleGattReconnect(token, detail, "retrying");
            return;
        }
        mainHandler.post(() -> {
            if (isCurrent(token) && current == ancsTransport
                    && transportSession == activeAncsTransportSession) {
                current.requestSavedPeerReconnect(detail, confirmedLeLoss);
                return;
            }
            Handler handler = worker;
            if (handler != null) {
                handler.post(() -> runIfCurrent(token,
                        () -> scheduleGattReconnect(token, detail, "retrying")));
            }
        });
    }

    private void scheduleGattReconnect(long token, @NonNull String detail,
                                       @NonNull String visibleStatus) {
        if (!isCurrent(token)) return;
        // Every controller-owned ANCS/GATT failure reaches this method. Arming here covers both
        // the modern saved-peer transport and the legacy in-process GATT path without depending
        // on one vendor-specific disconnect callback being delivered.
        scheduleAncsAdapterEscalation(token, detail);
        cancelStableAncsReadyReset();
        lastError = bounded(detail, 512);
        ancsStatus = visibleStatus;
        cancelAncsPublicationRetry();
        if (gattReconnectTask != null) {
            publishSnapshot(token);
            return;
        }
        closeAncsTransport();
        cancelGattWatchdogs();
        BluetoothGatt previous;
        synchronized (lifecycleLock) {
            previous = gatt;
            gatt = null;
        }
        closeGatt(previous);
        gattConnected = false;
        persistCurrentTelemetry();
        clearBasData();
        resetAncsSession(token, visibleStatus);
        updateConnected(token);
        long delay = PhoneConnectorPolicy.reconnectDelayMillis(reconnectAttempt++);
        Handler handler = worker;
        if (handler == null) return;
        Runnable retry = () -> runIfCurrent(token, () -> {
            gattReconnectTask = null;
            ensureGatt(token);
        });
        gattReconnectTask = retry;
        handler.postDelayed(retry, delay);
    }

    private void scheduleStableAncsReadyReset(long token) {
        cancelStableAncsReadyReset();
        Handler handler = worker;
        if (handler == null) return;
        Runnable stable = () -> runIfCurrent(token, () -> {
            ancsStableReadyTask = null;
            if (!ancsReady || ancsTransport == null) return;
            reconnectAttempt = 0;
            Log.d(TAG, "ANCS READY stable for " + ANCS_STABLE_READY_RESET_MS
                    + " ms; reconnect backoff reset");
        });
        ancsStableReadyTask = stable;
        handler.postDelayed(stable, ANCS_STABLE_READY_RESET_MS);
    }

    private void cancelStableAncsReadyReset() {
        Runnable stable = ancsStableReadyTask;
        if (stable != null && worker != null) worker.removeCallbacks(stable);
        ancsStableReadyTask = null;
    }

    private void closeAncsTransport() {
        IphoneAncsTransport previous;
        synchronized (lifecycleLock) {
            previous = ancsTransport;
            ancsTransport = null;
            ancsTransportStartPending = false;
            activeAncsTransportSession = ++nextAncsTransportSession;
        }
        closeAncsTransportOnMain(previous);
    }

    private void closeAncsTransportOnMain(@Nullable IphoneAncsTransport transport) {
        if (transport == null) return;
        mainHandler.post(() -> {
            try {
                transport.close();
            } catch (RuntimeException error) {
                Log.w(TAG, "ANCS transport close failed", error);
            }
        });
    }

    private void scheduleConnectWatchdog(long token, @NonNull BluetoothGatt expected,
                                         boolean autoConnect) {
        cancelConnectWatchdog();
        Handler handler = worker;
        if (handler == null) return;
        Runnable timeout = () -> runIfCurrent(token, () -> {
            if (gatt != expected || gattConnected) return;
            connectWatchdog = null;
            if (autoConnect) forceDirectGatt = true;
            scheduleGattReconnect(token, autoConnect
                    ? "Background GATT connection timed out; retrying directly"
                    : "Direct GATT connection timed out");
        });
        connectWatchdog = timeout;
        handler.postDelayed(timeout, GATT_CONNECT_TIMEOUT_MS);
    }

    private void scheduleDiscoveryWatchdog(long token, @NonNull BluetoothGatt expected) {
        cancelDiscoveryWatchdog();
        Handler handler = worker;
        if (handler == null) return;
        Runnable timeout = () -> runIfCurrent(token, () -> {
            if (gatt != expected || !serviceDiscoveryStarted) return;
            discoveryWatchdog = null;
            serviceDiscoveryStarted = false;
            scheduleGattReconnect(token, "GATT service discovery timed out");
        });
        discoveryWatchdog = timeout;
        handler.postDelayed(timeout, GATT_DISCOVERY_TIMEOUT_MS);
    }

    private void cancelGattWatchdogs() {
        cancelConnectWatchdog();
        cancelMtuWatchdog();
        cancelDiscoveryWatchdog();
        mtuPending = false;
        serviceDiscoveryStarted = false;
    }

    private void cancelRetryTasks() {
        cancelDeviceRescan();
        cancelGattReconnect();
        cancelAncsPublicationRetry();
        cancelStableAncsReadyReset();
        cancelStockConnectionRequest();
        cancelOemGattRefresh();
        cancelAncsAdapterEscalation();
        cancelAdapterRecoveryWatchdog();
    }

    /**
     * ECARX can leave its BLE controller wedged after an iPhone link loss: closing and reopening
     * GATT clients then never restores ANCS, while a manual radio off/on does so immediately.
     * Preserve the retained autoConnect owner and its complete fallback cycle first, then
     * automate that proven final step only
     * for a phone whose ANCS subscription was already healthy in this controller session.
     */
    private void scheduleAncsAdapterEscalation(long token, @NonNull String reason) {
        if (!ancsWasReadyThisSession || ancsReady || adapterRecoveryInProgress
                || ancsAdapterEscalationTask != null
                || config == null || !config.transportNeeded()) return;
        Handler handler = worker;
        if (handler == null) return;
        Runnable escalation = () -> runIfCurrent(token, () -> {
            ancsAdapterEscalationTask = null;
            long now = SystemClock.elapsedRealtime();
            if (!AncsAdapterRecoveryPolicy.mayReset(
                    ancsWasReadyThisSession, ancsReady, adapterRecoveryInProgress,
                    now, lastAdapterRecoveryElapsedMs)) return;
            startAdapterRecovery(token, reason);
        });
        ancsAdapterEscalationTask = escalation;
        handler.postDelayed(escalation, AncsAdapterRecoveryPolicy.ESCALATION_DELAY_MS);
        Log.w(TAG, "ANCS recovery watchdog armed: " + reason);
    }

    private void startAdapterRecovery(long token, @NonNull String reason) {
        BluetoothAdapter adapter = bluetoothAdapter();
        if (adapter == null || adapterRecoveryInProgress) return;
        adapterRecoveryInProgress = true;
        lastAdapterRecoveryElapsedMs = SystemClock.elapsedRealtime();
        lastError = bounded("Automatic Bluetooth stack recovery after ANCS loss: "
                + reason, 512);
        ancsStatus = "bluetooth_stack_recovery";
        closeAncsTransport();
        cancelGattWatchdogs();
        cancelGattReconnect();
        cancelAncsPublicationRetry();
        cancelDeviceRescan();
        cancelStockConnectionRequest();
        cancelOemGattRefresh();
        BluetoothGatt previous = gatt;
        gatt = null;
        refreshGattCache(previous);
        closeGatt(previous);
        gattConnected = false;
        resetAncsSession(token, "bluetooth_stack_recovery");
        updateConnected(token);
        Log.w(TAG, "ANCS did not recover; cycling the Android 9 Bluetooth adapter once");
        boolean accepted;
        try {
            accepted = adapter.isEnabled() ? adapter.disable() : adapter.enable();
        } catch (RuntimeException denied) {
            accepted = false;
            lastError = "Bluetooth stack recovery denied: " + safeMessage(denied);
        }
        if (!accepted) {
            adapterRecoveryInProgress = false;
            scheduleGattReconnect(token, lastError.isEmpty()
                    ? "Bluetooth stack recovery was rejected" : lastError, "retrying");
            return;
        }
        scheduleAdapterRecoveryWatchdog(token);
    }

    private void handleAdapterRecoveryState(long token, int state) {
        BluetoothAdapter adapter = bluetoothAdapter();
        if (state == BluetoothAdapter.STATE_OFF) {
            invalidateSelectedPhone(token, "bluetooth_stack_recovery");
            boolean accepted = false;
            try {
                accepted = adapter != null && adapter.enable();
            } catch (RuntimeException denied) {
                lastError = "Bluetooth restart denied: " + safeMessage(denied);
            }
            if (!accepted) {
                adapterRecoveryInProgress = false;
                scheduleDeviceRescan(token);
            }
            return;
        }
        if (state == BluetoothAdapter.STATE_ON) finishAdapterRecovery(token);
    }

    private void finishAdapterRecovery(long token) {
        if (!adapterRecoveryInProgress) return;
        adapterRecoveryInProgress = false;
        cancelAdapterRecoveryWatchdog();
        reconnectAttempt = 0;
        ancsStatus = "connecting_after_bluetooth_recovery";
        lastError = "";
        selectedDevice = null;
        selectedAddress = "";
        selectedName = "";
        selectAndConnect(token);
        Log.i(TAG, "Bluetooth adapter restarted; selected iPhone reconnect resumed");
    }

    private void scheduleAdapterRecoveryWatchdog(long token) {
        cancelAdapterRecoveryWatchdog();
        Handler handler = worker;
        if (handler == null) return;
        Runnable watchdog = () -> runIfCurrent(token, () -> {
            adapterRecoveryWatchdog = null;
            if (!adapterRecoveryInProgress) return;
            BluetoothAdapter adapter = bluetoothAdapter();
            if (adapter != null && adapter.isEnabled()) {
                finishAdapterRecovery(token);
                return;
            }
            boolean accepted = false;
            try {
                accepted = adapter != null && adapter.enable();
            } catch (RuntimeException denied) {
                lastError = "Bluetooth recovery timeout: " + safeMessage(denied);
            }
            if (accepted) {
                scheduleAdapterRecoveryWatchdog(token);
            } else {
                adapterRecoveryInProgress = false;
                ancsStatus = "bluetooth_recovery_failed";
                publishSnapshot(token);
                scheduleDeviceRescan(token);
            }
        });
        adapterRecoveryWatchdog = watchdog;
        handler.postDelayed(watchdog, ADAPTER_RECOVERY_WATCHDOG_MS);
    }

    private void cancelAncsAdapterEscalation() {
        Runnable task = ancsAdapterEscalationTask;
        if (task != null && worker != null) worker.removeCallbacks(task);
        ancsAdapterEscalationTask = null;
    }

    private void cancelAdapterRecoveryWatchdog() {
        Runnable task = adapterRecoveryWatchdog;
        if (task != null && worker != null) worker.removeCallbacks(task);
        adapterRecoveryWatchdog = null;
    }

    private void cancelDeviceRescan() {
        Runnable retry = deviceRescanTask;
        if (retry != null && worker != null) worker.removeCallbacks(retry);
        deviceRescanTask = null;
    }

    private void cancelGattReconnect() {
        Runnable retry = gattReconnectTask;
        if (retry != null && worker != null) worker.removeCallbacks(retry);
        gattReconnectTask = null;
    }

    private void cancelStockConnectionRequest() {
        Runnable task = stockConnectionTask;
        if (task != null && worker != null) worker.removeCallbacks(task);
        stockConnectionTask = null;
        stockConnectionAttempt = 0;
        stockConnectionRequestInProgress = false;
    }

    private void cancelOemGattRefresh() {
        Runnable task = oemGattRefreshTask;
        if (task != null && worker != null) worker.removeCallbacks(task);
        oemGattRefreshTask = null;
    }

    private void scheduleAncsPublicationRetry(long token,
                                              @NonNull BluetoothGatt expected) {
        cancelAncsPublicationRetry();
        Handler handler = worker;
        if (handler == null) return;
        Runnable retry = () -> runIfCurrent(token, () -> {
            ancsPublicationRetryTask = null;
            if (gatt != expected || !gattConnected
                    || !"service_not_published".equals(ancsStatus)) return;
            if (ancsPublicationRetryCount >= 1) {
                ancsStatus = "stock_pairing_required";
                lastError = "ANCS was not published by the iPhone after clean rediscovery";
                publishSnapshot(token);
                return;
            }
            ancsPublicationRetryCount++;
            forceDirectGatt = true;
            refreshGattCache(expected);
            scheduleGattReconnect(token,
                    serviceChangedSubscribed
                            ? "ANCS was not published; refreshing one clean GATT session"
                            : "ANCS was not published and Service Changed is unavailable; "
                            + "refreshing one clean GATT session",
                    "service_not_published");
        });
        ancsPublicationRetryTask = retry;
        handler.postDelayed(retry, ANCS_SERVICE_PUBLICATION_RETRY_MS);
    }

    private void cancelAncsPublicationRetry() {
        Runnable retry = ancsPublicationRetryTask;
        if (retry != null && worker != null) worker.removeCallbacks(retry);
        ancsPublicationRetryTask = null;
    }

    private void cancelConnectWatchdog() {
        Runnable timeout = connectWatchdog;
        if (timeout != null && worker != null) worker.removeCallbacks(timeout);
        connectWatchdog = null;
    }

    private void cancelMtuWatchdog() {
        Runnable timeout = mtuWatchdog;
        if (timeout != null && worker != null) worker.removeCallbacks(timeout);
        mtuWatchdog = null;
    }

    private void cancelDiscoveryWatchdog() {
        Runnable timeout = discoveryWatchdog;
        if (timeout != null && worker != null) worker.removeCallbacks(timeout);
        discoveryWatchdog = null;
    }

    private static boolean isAuthorizationFailure(int status) {
        return status == GATT_INSUFFICIENT_AUTHENTICATION
                || status == GATT_INSUFFICIENT_AUTHORIZATION
                || status == GATT_INSUFFICIENT_ENCRYPTION_KEY_SIZE
                || status == GATT_INSUFFICIENT_ENCRYPTION;
    }

    private void mirrorAncsNotification(long token, @NonNull NotificationRecord record) {
        Config current = config;
        boolean appleMessage = isAppleMessagesApp(record.notification.appIdentifier);
        boolean allowed = current != null && (current.notificationsEnabled
                || current.messagesEnabled && appleMessage);
        if (!isCurrent(token) || !allowed) return;
        int notificationId = ancsNotificationId(record.notification.uid);
        String appName = displayNameFor(record.notification.appIdentifier);
        Notification.Builder builder = baseNotificationBuilder()
                .setSmallIcon(PhoneAppCatalog.iconResource(
                        record.notification.appIdentifier, record.categoryId))
                .setSubText(appName)
                .setCategory(appleMessage
                        ? Notification.CATEGORY_MESSAGE : Notification.CATEGORY_STATUS);
        if (current.includeNotificationText) {
            // Preserve Apple's field semantics exactly:
            // DisplayName -> application/subText, Title -> topic/title,
            // Message -> text/body. Subtitle never replaces either field.
            builder.setContentTitle(record.notification.title)
                    .setContentText(record.notification.message)
                    .setStyle(new Notification.BigTextStyle()
                            .bigText(record.notification.message));
        } else {
            builder.setContentTitle(appName)
                    .setContentText(AncsProtocol.categoryLabel(record.categoryId));
        }
        if (notifySafely(notificationId, builder.build())) {
            mirroredAncsIds.put(record.notification.uid, notificationId);
            mirroredNotificationIds.add(notificationId);
            postMirrorSummary();
        }
    }

    private void mirrorSmsNotification(long token, @NonNull Map<String, Object> sms) {
        Config current = config;
        if (!isCurrent(token) || current == null || !current.notificationsEnabled
                || ancsReady) {
            return;
        }
        String id = String.valueOf(sms.get("id"));
        int notificationId = smsNotificationId(id);
        Notification.Builder builder = baseNotificationBuilder()
                .setCategory(Notification.CATEGORY_MESSAGE);
        if (current.includeNotificationText) {
            String address = String.valueOf(sms.get("sender"));
            String body = String.valueOf(sms.get("body"));
            builder.setContentTitle(address.isEmpty() ? "SMS" : address)
                    .setContentText(body)
                    .setStyle(new Notification.BigTextStyle().bigText(body));
        } else {
            builder.setContentTitle("Новое SMS")
                    .setContentText("Текст скрыт настройками приватности");
        }
        if (notifySafely(notificationId, builder.build())) {
            mirroredNotificationIds.add(notificationId);
            mirroredSmsIds.put(id, notificationId);
            postMirrorSummary();
        }
    }

    @NonNull
    private Notification.Builder baseNotificationBuilder() {
        return new Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_status_bt_connected)
                .setOnlyAlertOnce(true)
                .setAutoCancel(false)
                .setGroup(NOTIFICATION_GROUP_KEY)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setDefaults(0)
                .setSound(null);
    }

    private void ensureNotificationChannel() {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return;
        try {
            NotificationChannelGroup group =
                    new NotificationChannelGroup(CHANNEL_GROUP_ID, "Телефон");
            manager.createNotificationChannelGroup(group);
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Уведомления телефона", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Уведомления подключённого iPhone");
            channel.setGroup(CHANNEL_GROUP_ID);
            channel.setSound(null, null);
            channel.enableVibration(false);
            channel.enableLights(false);
            manager.createNotificationChannel(channel);
        } catch (RuntimeException error) {
            lastError = "Notification channel: " + safeMessage(error);
        }
    }

    private boolean notifySafely(int id, @NonNull Notification notification) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return false;
        try {
            manager.notify(id, notification);
            return true;
        } catch (RuntimeException error) {
            lastError = "Notification mirror: " + safeMessage(error);
            return false;
        }
    }

    private void postMirrorSummary() {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return;
        if (mirroredNotificationIds.isEmpty()) {
            manager.cancel(SUMMARY_NOTIFICATION_ID);
            return;
        }
        Notification summary = baseNotificationBuilder()
                .setContentTitle("Телефон")
                .setContentText("Уведомлений: " + mirroredNotificationIds.size())
                .setGroupSummary(true)
                .build();
        notifySafely(SUMMARY_NOTIFICATION_ID, summary);
    }

    private void cancelMirroredNotification(int id) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager != null) {
            try {
                manager.cancel(id);
            } catch (RuntimeException ignored) {
                // Notification permission/OEM service may disappear while Bluetooth disconnects.
            }
        }
        mirroredNotificationIds.remove(id);
        mirroredSmsIds.values().remove(id);
        postMirrorSummary();
    }

    private void cancelSmsFallbackNotifications() {
        for (Integer id : new ArrayList<>(mirroredSmsIds.values())) {
            cancelMirroredNotification(id);
        }
        mirroredSmsIds.clear();
    }

    private void cancelAllMirroredNotifications() {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager != null) {
            for (Integer id : new ArrayList<>(mirroredNotificationIds)) {
                try {
                    manager.cancel(id);
                } catch (RuntimeException ignored) {}
            }
            try {
                manager.cancel(SUMMARY_NOTIFICATION_ID);
            } catch (RuntimeException ignored) {}
        }
        mirroredNotificationIds.clear();
        mirroredAncsIds.clear();
        mirroredSmsIds.clear();
    }

    private void publishSnapshot(long token) {
        synchronized (lifecycleLock) {
            if (!isCurrentLocked(token)) return;
            values.replaceSnapshot(ConnectorType.PHONE, CONNECTOR_ID, buildSnapshot(true));
        }
    }

    private void publishOfflineSnapshotLocked(@NonNull String reason) {
        List<ConnectorValue> snapshot = new ArrayList<>();
        long now = System.currentTimeMillis();
        snapshot.add(value("connected", false, true, "boolean", "", now));
        snapshot.add(value("device.name", null, false, "string", "", now));
        snapshot.add(value("transport.classic.name", null, false,
                "string", "", now));
        snapshot.add(value("transport.ancs.local_name",
                IphoneAncsTransport.LOCAL_LOGICAL_NAME, false, "string", "", now));
        snapshot.add(value("transport.ancs.remote_name",
                IphoneAncsTransport.REMOTE_LOGICAL_NAME, false, "string", "", now));
        snapshot.add(value("profiles.hfp", null, false, "boolean", "", now));
        snapshot.add(value("profiles.map", null, false, "boolean", "", now));
        snapshot.add(value("profiles.ble", null, false, "boolean", "", now));
        snapshot.add(value("profiles.ancs", null, false, "boolean", "", now));
        snapshot.add(value("battery.level", null, false, "number", "%", now));
        snapshot.add(value("battery.level_source", null, false, "string", "", now));
        snapshot.add(value("battery.charging", null, false, "boolean", "", now));
        snapshot.add(value("battery.charging_estimated", null, false,
                "boolean", "", now));
        snapshot.add(value("battery.charging_source", null, false,
                "string", "", now));
        snapshot.add(value("battery.external_power", null, false,
                "boolean", "", now));
        snapshot.add(value("battery.charge_state", null, false, "string", "", now));
        snapshot.add(value("battery.charge_level", null, false, "string", "", now));
        snapshot.add(value("network.available", null, false, "boolean", "", now));
        snapshot.add(value("network.operator", null, false, "string", "", now));
        snapshot.add(value("network.type", null, false, "string", "", now));
        snapshot.add(value("network.signal", null, false, "number", "%", now));
        snapshot.add(value("network.roaming", null, false, "boolean", "", now));
        snapshot.add(value("telemetry.stale", null, false, "boolean", "", now));
        snapshot.add(value("telemetry.updated_at", null, false, "number", "ms", now));
        snapshot.add(value("call.active", null, false, "boolean", "", now));
        snapshot.add(value("call.state", null, false, "string", "", now));
        snapshot.add(value("call.direction", null, false, "string", "", now));
        snapshot.add(value("call.multiparty", null, false, "boolean", "", now));
        snapshot.add(value("call.audio", null, false, "boolean", "", now));
        snapshot.add(value("call.audio_state", null, false, "string", "", now));
        snapshot.add(value("call.audio_wideband", null, false, "boolean", "", now));
        snapshot.add(value("voice_assistant.active", null, false,
                "boolean", "", now));
        snapshot.add(value("ringtone.in_band", null, false, "boolean", "", now));
        snapshot.add(value("notifications.count", 0, false, "number", "", now));
        snapshot.add(value("notifications.latest", null, false, "object", "", now));
        snapshot.add(value("notifications.items", Collections.emptyList(), false,
                "list", "", now));
        snapshot.add(value("messages.unread", 0, false, "number", "", now));
        snapshot.add(value("messages.latest", null, false, "object", "", now));
        snapshot.add(value("diagnostics.device", Collections.emptyMap(), true,
                "object", "", now));
        snapshot.add(value("diagnostics.last_app", Collections.emptyMap(), true,
                "object", "", now));
        snapshot.add(value("diagnostics.ancs", reason, true, "string", "", now));
        snapshot.add(value("diagnostics.sms", reason, true, "string", "", now));
        snapshot.add(value("diagnostics.last_error", "", true, "string", "", now));
        values.replaceSnapshot(ConnectorType.PHONE, CONNECTOR_ID, snapshot);
    }

    @NonNull
    private List<ConnectorValue> buildSnapshot(boolean active) {
        long now = System.currentTimeMillis();
        PhoneTelemetryStore.Record retained = retainedTelemetry;
        boolean retainedNetworkAvailable = retainedNetworkFresh(now);

        Integer effectiveBatteryLevel = helperPowerUpdatedAtElapsed > 0L
                ? batteryLevel : null;
        String effectiveBatteryLevelSource = effectiveBatteryLevel == null
                ? "" : "iphone_helper";
        // Never restore power state: only a fresh authenticated helper heartbeat is authoritative.
        Boolean effectiveCharging = batteryCharging;
        Boolean effectiveChargingEstimated = batteryChargingEstimated;
        String effectiveChargingSource = batteryChargingSource;
        Boolean effectiveExternalPower = batteryExternalPower;
        String effectiveChargeState = batteryChargeState;
        String effectiveChargeLevel = batteryChargeLevel;

        // A current explicit "network unavailable" event invalidates all older carrier data.
        boolean networkExplicitlyUnavailable = networkLiveSeenThisConnection
                && Boolean.FALSE.equals(networkAvailable);
        boolean retainedNetworkAvailability = networkAvailable == null
                && retainedNetworkAvailable && retained.networkAvailable != null;
        Boolean effectiveNetworkAvailability = networkAvailable != null
                ? networkAvailable
                : retainedNetworkAvailability ? retained.networkAvailable : null;
        boolean retainedSignal = !networkExplicitlyUnavailable && networkSignal == null
                && retainedNetworkAvailable && retained.networkSignal != null;
        Integer effectiveSignal = networkSignal != null
                ? networkSignal : retainedSignal ? retained.networkSignal : null;
        boolean retainedRoaming = !networkExplicitlyUnavailable && networkRoaming == null
                && retainedNetworkAvailable && retained.networkRoaming != null;
        Boolean effectiveRoaming = networkRoaming != null
                ? networkRoaming : retainedRoaming ? retained.networkRoaming : null;
        boolean retainedOperator = !networkExplicitlyUnavailable && networkOperator.isEmpty()
                && retainedNetworkAvailable && !retained.networkOperator.isEmpty();
        String effectiveOperator = !networkOperator.isEmpty()
                ? networkOperator : retainedOperator ? retained.networkOperator : "";
        // Radio generation is helper-only and is intentionally not resurrected from disk/HFP.
        String effectiveNetworkType = helperNetworkUpdatedAtElapsed > 0L
                ? helperNetworkType : "";
        boolean staleTelemetryUsed = retainedNetworkAvailability || retainedSignal || retainedRoaming
                || retainedOperator;
        telemetryStale = staleTelemetryUsed;

        List<ConnectorValue> snapshot = new ArrayList<>();
        snapshot.add(value("connected", connected, active, "boolean", "", now));
        snapshot.add(value("device.name", selectedName.isEmpty() ? null : selectedName,
                !selectedName.isEmpty(), "string", "", now));
        snapshot.add(value("transport.classic.name",
                selectedName.isEmpty() ? null : selectedName,
                !selectedName.isEmpty(), "string", "", now));
        snapshot.add(value("transport.ancs.local_name",
                IphoneAncsTransport.LOCAL_LOGICAL_NAME,
                config != null && config.transportNeeded(), "string", "", now));
        snapshot.add(value("transport.ancs.remote_name",
                IphoneAncsTransport.REMOTE_LOGICAL_NAME,
                config != null && config.transportNeeded(), "string", "", now));
        snapshot.add(value("profiles.hfp", hfpConnected, active, "boolean", "", now));
        snapshot.add(value("profiles.map", mapConnected, active, "boolean", "", now));
        snapshot.add(value("profiles.ble", gattConnected, active, "boolean", "", now));
        snapshot.add(value("profiles.ancs", ancsReady, active, "boolean", "", now));
        snapshot.add(value("battery.level", effectiveBatteryLevel,
                effectiveBatteryLevel != null, "number", "%", now));
        snapshot.add(value("battery.level_source",
                effectiveBatteryLevelSource.isEmpty() ? null : effectiveBatteryLevelSource,
                !effectiveBatteryLevelSource.isEmpty(), "string", "", now));
        snapshot.add(value("battery.charging", effectiveCharging,
                effectiveCharging != null, "boolean", "", now));
        snapshot.add(value("battery.charging_estimated", effectiveChargingEstimated,
                effectiveChargingEstimated != null, "boolean", "", now));
        snapshot.add(value("battery.charging_source",
                effectiveChargingSource.isEmpty() ? null : effectiveChargingSource,
                !effectiveChargingSource.isEmpty(), "string", "", now));
        snapshot.add(value("battery.external_power", effectiveExternalPower,
                effectiveExternalPower != null, "boolean", "", now));
        snapshot.add(value("battery.charge_state",
                effectiveChargeState.isEmpty() ? null : effectiveChargeState,
                !effectiveChargeState.isEmpty(), "string", "", now));
        snapshot.add(value("battery.charge_level",
                effectiveChargeLevel.isEmpty() ? null : effectiveChargeLevel,
                !effectiveChargeLevel.isEmpty(), "string", "", now));
        snapshot.add(value("network.available", effectiveNetworkAvailability,
                effectiveNetworkAvailability != null, "boolean", "", now));
        snapshot.add(value("network.operator",
                effectiveOperator.isEmpty() ? null : effectiveOperator,
                !effectiveOperator.isEmpty(), "string", "", now));
        snapshot.add(value("network.type",
                effectiveNetworkType.isEmpty() ? null : effectiveNetworkType,
                !effectiveNetworkType.isEmpty(), "string", "", now));
        snapshot.add(value("network.signal", effectiveSignal,
                effectiveSignal != null, "number", "%", now));
        snapshot.add(value("network.roaming", effectiveRoaming,
                effectiveRoaming != null, "boolean", "", now));
        snapshot.add(value("telemetry.stale", staleTelemetryUsed,
                true, "boolean", "", now));
        snapshot.add(value("telemetry.updated_at",
                staleTelemetryUsed && retained != null ? retained.updatedAtWallMs : null,
                staleTelemetryUsed && retained != null, "number", "ms", now));
        snapshot.add(value("call.active", callActive,
                hfpConnected && callActive != null, "boolean", "", now));
        snapshot.add(value("call.state", callState.isEmpty() ? null : callState,
                hfpConnected && !callState.isEmpty(), "string", "", now));
        snapshot.add(value("call.direction",
                callDirection.isEmpty() ? null : callDirection,
                hfpConnected && !callDirection.isEmpty(), "string", "", now));
        snapshot.add(value("call.multiparty", callMultiparty,
                hfpConnected && callMultiparty != null, "boolean", "", now));
        snapshot.add(value("call.audio", callAudioConnected,
                hfpConnected && callAudioConnected != null, "boolean", "", now));
        snapshot.add(value("call.audio_state",
                callAudioState.isEmpty() ? null : callAudioState,
                hfpConnected && !callAudioState.isEmpty(), "string", "", now));
        snapshot.add(value("call.audio_wideband", callAudioWideband,
                hfpConnected && callAudioWideband != null, "boolean", "", now));
        snapshot.add(value("voice_assistant.active", voiceRecognitionActive,
                hfpConnected && voiceRecognitionActive != null,
                "boolean", "", now));
        snapshot.add(value("ringtone.in_band", inBandRingSupported,
                hfpConnected && inBandRingSupported != null,
                "boolean", "", now));

        boolean notificationsAvailable = connected && ancsReady
                && config != null && config.notificationsEnabled;
        List<Map<String, Object>> notificationItems = notificationsAvailable
                ? notificationMaps() : Collections.emptyList();
        snapshot.add(value("notifications.count", notificationItems.size(),
                notificationsAvailable, "number", "", now));
        snapshot.add(value("notifications.latest",
                notificationItems.isEmpty() ? null
                        : notificationItems.get(notificationItems.size() - 1),
                notificationsAvailable && !notificationItems.isEmpty(),
                "object", "", now));
        snapshot.add(value("notifications.items", notificationItems,
                notificationsAvailable, "list", "", now));

        boolean messagesAvailable = connected && smsAvailable
                && config != null && config.messagesEnabled;
        snapshot.add(value("messages.unread", smsUnread, messagesAvailable,
                "number", "", now));
        snapshot.add(value("messages.latest", latestSms,
                messagesAvailable && latestSms != null, "object", "", now));

        LinkedHashMap<String, Object> device = new LinkedHashMap<>();
        device.put("address", maskedAddress(selectedAddress));
        device.put("name", selectedName);
        device.put("classic_name", selectedName);
        device.put("ancs_local_name", IphoneAncsTransport.LOCAL_LOGICAL_NAME);
        device.put("ancs_remote_name", IphoneAncsTransport.REMOTE_LOGICAL_NAME);
        device.put("stock_connection", stockConnectionStatus);
        device.put("ancs_setup", config != null && config.transportNeeded()
                ? "dedicated_ble_v1" : "disabled");
        snapshot.add(value("diagnostics.device", device, !selectedAddress.isEmpty(),
                "object", "", now));
        LinkedHashMap<String, Object> lastApp = new LinkedHashMap<>();
        if (!lastAppIdentifier.isEmpty()) {
            lastApp.put("id", lastAppIdentifier);
            lastApp.put("name", firstNonEmpty(lastAppName,
                    PhoneAppCatalog.displayNameFallback(lastAppIdentifier)));
            lastApp.put("icon", PhoneAppCatalog.iconKey(
                    lastAppIdentifier, lastAppCategoryId));
            lastApp.put("received_at", lastNotificationAt);
        }
        snapshot.add(value("diagnostics.last_app", lastApp, !lastApp.isEmpty(),
                "object", "", now));
        snapshot.add(value("diagnostics.ancs", ancsStatus, true,
                "string", "", now));
        snapshot.add(value("diagnostics.sms", smsStatus, true,
                "string", "", now));
        snapshot.add(value("diagnostics.last_error", redactedDiagnostic(lastError), true,
                "string", "", now));
        return snapshot;
    }

    @NonNull
    private List<Map<String, Object>> notificationMaps() {
        List<Map<String, Object>> result = new ArrayList<>(notificationCache.size());
        boolean includeText = config != null && config.includeNotificationText;
        for (NotificationRecord item : notificationCache.values()) {
            if (!item.presented) continue;
            LinkedHashMap<String, Object> value = new LinkedHashMap<>();
            String application = displayNameFor(item.notification.appIdentifier);
            value.put("uid", item.notification.uid);
            value.put("app", item.notification.appIdentifier);
            value.put("app_id", item.notification.appIdentifier);
            value.put("app_name", application);
            value.put("application", application);
            value.put("icon", PhoneAppCatalog.iconKey(
                    item.notification.appIdentifier, item.categoryId));
            value.put("icon_cached", item.iconAvailableForPresentation);
            value.put("category_id", item.categoryId);
            value.put("category", AncsProtocol.categoryLabel(item.categoryId));
            value.put("date", item.notification.date);
            value.put("received_at", item.receivedAt);
            if (includeText) {
                value.put("title", item.notification.title);
                value.put("subtitle", item.notification.subtitle);
                value.put("message", item.notification.message);
                value.put("topic", item.notification.title);
                value.put("text", item.notification.message);
            }
            result.add(Collections.unmodifiableMap(value));
        }
        return Collections.unmodifiableList(result);
    }

    @NonNull
    private static ConnectorValue value(@NonNull String resourceId, @Nullable Object raw,
                                        boolean available, @NonNull String type,
                                        @NonNull String unit, long updatedAt) {
        return new ConnectorValue(ConnectorType.PHONE, CONNECTOR_ID, resourceId, raw,
                true, available, true, false, type, unit, Collections.emptyMap(), updatedAt);
    }

    private void recordError(long token, @NonNull String message) {
        lastError = bounded(message, 512);
        Log.w(TAG, lastError);
        publishSnapshot(token);
    }

    private boolean isCurrent(long token) {
        synchronized (lifecycleLock) {
            return isCurrentLocked(token);
        }
    }

    private boolean isCurrentLocked(long token) {
        return running && generation == token;
    }

    private void runIfCurrent(long token, @NonNull Runnable action) {
        synchronized (lifecycleLock) {
            if (!isCurrentLocked(token)) return;
            try {
                action.run();
            } catch (RuntimeException error) {
                lastError = bounded("Phone callback: " + safeMessage(error), 512);
                Log.w(TAG, lastError, error);
                try {
                    values.replaceSnapshot(ConnectorType.PHONE, CONNECTOR_ID,
                            buildSnapshot(true));
                } catch (RuntimeException publishError) {
                    Log.w(TAG, "Could not publish phone diagnostics", publishError);
                }
            }
        }
    }

    private void updatePresenceLocked(boolean value) {
        if (lastPresence == value) return;
        lastPresence = value;
        try {
            presenceSink.onPhoneConnectionChanged(value);
        } catch (RuntimeException error) {
            Log.w(TAG, "Phone presence sink failed", error);
        }
    }

    @Nullable
    private BluetoothAdapter bluetoothAdapter() {
        try {
            BluetoothManager manager = context.getSystemService(BluetoothManager.class);
            return manager == null ? BluetoothAdapter.getDefaultAdapter() : manager.getAdapter();
        } catch (RuntimeException error) {
            lastError = "Bluetooth adapter: " + safeMessage(error);
            return null;
        }
    }

    private boolean isSelected(@Nullable BluetoothDevice device) {
        if (device == null || selectedAddress.isEmpty()) return false;
        return selectedAddress.equalsIgnoreCase(safeAddress(device));
    }

    private boolean matchesConfiguredAddress(@Nullable BluetoothDevice device) {
        Config current = config;
        return device != null && current != null && !current.deviceAddress.isEmpty()
                && current.deviceAddress.equalsIgnoreCase(safeAddress(device));
    }

    @NonNull
    private static String safeAddress(@NonNull BluetoothDevice device) {
        try {
            String address = device.getAddress();
            return address == null ? "" : address;
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    @NonNull
    private static String safeName(@NonNull BluetoothDevice device) {
        try {
            String name = device.getName();
            return name == null ? "" : name;
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    @Nullable
    @SuppressWarnings("deprecation")
    private static BluetoothDevice parcelableDevice(@NonNull Intent intent) {
        try {
            return intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    @Nullable
    private static Integer intExtra(@NonNull Intent intent, @NonNull String... keys) {
        for (String key : keys) {
            try {
                if (!intent.hasExtra(key)) continue;
                Object value = intent.getExtras() == null ? null : intent.getExtras().get(key);
                if (value instanceof Number) return ((Number) value).intValue();
                if (value instanceof Boolean) return (Boolean) value ? 1 : 0;
                if (value != null) return Integer.parseInt(String.valueOf(value));
            } catch (RuntimeException ignored) {}
        }
        return null;
    }

    @Nullable
    private static Boolean booleanExtra(@NonNull Intent intent, @NonNull String... keys) {
        for (String key : keys) {
            try {
                if (!intent.hasExtra(key)) continue;
                Object value = intent.getExtras() == null ? null : intent.getExtras().get(key);
                if (value instanceof Boolean) return (Boolean) value;
                if (value instanceof Number) return ((Number) value).intValue() != 0;
                if (value != null) {
                    String text = String.valueOf(value).trim();
                    if ("true".equalsIgnoreCase(text) || "on".equalsIgnoreCase(text)
                            || "yes".equalsIgnoreCase(text) || "1".equals(text)) return true;
                    if ("false".equalsIgnoreCase(text) || "off".equalsIgnoreCase(text)
                            || "no".equalsIgnoreCase(text) || "0".equals(text)) return false;
                }
            } catch (RuntimeException ignored) {}
        }
        return null;
    }

    @Nullable
    private static String stringExtra(@NonNull Intent intent, @NonNull String... keys) {
        for (String key : keys) {
            try {
                if (!intent.hasExtra(key)) continue;
                Object value = intent.getExtras() == null ? null : intent.getExtras().get(key);
                if (value != null) return String.valueOf(value).trim();
            } catch (RuntimeException ignored) {}
        }
        return null;
    }

    @Nullable
    private static Object rawExtra(@NonNull Intent intent, @NonNull String key) {
        try {
            Bundle extras = intent.getExtras();
            return extras == null ? null : extras.get(key);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    @Nullable
    private static Object reflected(@NonNull Object target, @NonNull String methodName) {
        try {
            Method method = target.getClass().getMethod(methodName);
            method.setAccessible(true);
            return method.invoke(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Nullable
    private static Integer reflectedInt(@NonNull Object target, @NonNull String methodName) {
        Object raw = reflected(target, methodName);
        return raw instanceof Number ? ((Number) raw).intValue() : null;
    }

    @Nullable
    private static Boolean reflectedBoolean(@NonNull Object target,
                                               @NonNull String methodName) {
        Object raw = reflected(target, methodName);
        return raw instanceof Boolean ? (Boolean) raw : null;
    }

    private static int ancsNotificationId(long uid) {
        return 0x51000000 ^ (int) (uid ^ uid >>> 32);
    }

    private static int smsNotificationId(@NonNull String id) {
        return 0x52000000 ^ id.hashCode();
    }

    @NonNull
    private static String maskedAddress(@Nullable String raw) {
        String address = raw == null ? "" : raw.trim();
        String[] groups = address.split(":");
        if (groups.length == 6) {
            return "••:••:••:••:" + groups[4].toUpperCase(Locale.ROOT) + ":"
                    + groups[5].toUpperCase(Locale.ROOT);
        }
        return address.isEmpty() ? "" : "••:••:••:••:••:••";
    }

    @NonNull
    private String redactedDiagnostic(@Nullable String raw) {
        String result = bounded(raw, 512);
        String exact = selectedAddress;
        Config current = config;
        if (exact.isEmpty() && current != null) exact = current.deviceAddress;
        if (exact.isEmpty()) return result;
        String masked = maskedAddress(exact);
        return result.replace(exact, masked)
                .replace(exact.toUpperCase(Locale.ROOT), masked)
                .replace(exact.toLowerCase(Locale.ROOT), masked);
    }

    private static boolean isAppleMessagesApp(@Nullable String appIdentifier) {
        String normalized = appIdentifier == null ? ""
                : appIdentifier.trim().toLowerCase(Locale.ROOT);
        return "com.apple.mobilesms".equals(normalized)
                || normalized.startsWith("com.apple.mobilesms.")
                || "com.apple.messages".equals(normalized)
                || normalized.startsWith("com.apple.messages.");
    }

    @NonNull
    private String displayNameFor(@Nullable String rawAppIdentifier) {
        String appIdentifier = bounded(rawAppIdentifier, 512);
        String resolved = appDisplayNames.get(appIdentifier);
        if (resolved != null && !resolved.trim().isEmpty()) return resolved.trim();
        return PhoneAppCatalog.displayNameFallback(appIdentifier);
    }

    private static long messageDate(@NonNull Map<String, Object> message) {
        Object raw = message.get("date");
        return raw instanceof Number ? Math.max(0L, ((Number) raw).longValue()) : 0L;
    }

    @NonNull
    private static String messageFingerprint(@NonNull Map<String, Object> message) {
        Object senderValue = message.get("sender");
        String sender = senderValue == null ? ""
                : String.valueOf(senderValue).trim().toLowerCase(Locale.ROOT);
        Object bodyValue = message.get("body");
        String body = bodyValue == null ? "" : String.valueOf(bodyValue).trim();
        if (!sender.isEmpty() || !body.isEmpty()) {
            return sender + '\u0001' + body + '\u0001' + (messageDate(message) / 60_000L);
        }
        return String.valueOf(message.get("source")) + '\u0001'
                + String.valueOf(message.get("id"));
    }

    private static void closeGatt(@Nullable BluetoothGatt item) {
        if (item == null) return;
        try {
            item.disconnect();
        } catch (RuntimeException ignored) {}
        try {
            item.close();
        } catch (RuntimeException ignored) {}
    }

    /**
     * Android 9 caches a peripheral's attribute database across GATT clients. Its hidden refresh
     * hook is the only best-effort way for a target-28 app to see ANCS after iPhone publishes the
     * service without rebooting the head unit. Failure is harmless and the normal reconnect still
     * runs.
     */
    private static boolean refreshGattCache(@Nullable BluetoothGatt item) {
        if (item == null) return false;
        try {
            Method refresh = item.getClass().getMethod("refresh");
            Object result = refresh.invoke(item);
            return !(result instanceof Boolean) || (Boolean) result;
        } catch (Throwable unavailable) {
            Log.d(TAG, "Bluetooth GATT cache refresh is unavailable", unavailable);
            return false;
        }
    }

    @NonNull
    private static String firstNonEmpty(@Nullable String first, @NonNull String fallback) {
        return first == null || first.trim().isEmpty() ? fallback : first.trim();
    }

    @NonNull
    private static String bounded(@Nullable String raw, int maxLength) {
        String value = raw == null ? "" : raw.replace('\u0000', ' ').trim();
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    @NonNull
    private static String safeMessage(@NonNull Throwable error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName() : message.trim();
    }

    private enum GattKind {
        DESCRIPTOR,
        CHARACTERISTIC_READ,
        CONTROL_WRITE
    }

    private enum GattTag {
        SERVICE_CHANGED,
        ANCS_DATA,
        ANCS_NOTIFICATION,
        BATTERY_LEVEL_READ,
        BATTERY_LEVEL_STATUS_READ,
        BATTERY_LEVEL_SUBSCRIPTION,
        BATTERY_LEVEL_STATUS_SUBSCRIPTION,
        CONTROL
    }

    private static final class GattOperation {
        @NonNull final GattKind kind;
        @NonNull final GattTag tag;
        @Nullable final BluetoothGattDescriptor descriptor;
        @Nullable final BluetoothGattCharacteristic characteristic;
        @Nullable final byte[] payload;
        final long uid;
        @Nullable final String appIdentifier;
        final long requestSequence;

        GattOperation(@NonNull GattKind kind, @NonNull GattTag tag,
                      @Nullable BluetoothGattDescriptor descriptor,
                      @Nullable BluetoothGattCharacteristic characteristic,
                      @Nullable byte[] payload) {
            this(kind, tag, descriptor, characteristic, payload, -1L);
        }

        GattOperation(@NonNull GattKind kind, @NonNull GattTag tag,
                      @Nullable BluetoothGattDescriptor descriptor,
                      @Nullable BluetoothGattCharacteristic characteristic,
                      @Nullable byte[] payload, long uid) {
            this.kind = kind;
            this.tag = tag;
            this.descriptor = descriptor;
            this.characteristic = characteristic;
            this.payload = payload == null ? null : payload.clone();
            this.uid = uid;
            this.appIdentifier = null;
            this.requestSequence = 0L;
        }

        GattOperation(@NonNull GattKind kind, @NonNull GattTag tag,
                      @Nullable BluetoothGattDescriptor descriptor,
                      @Nullable BluetoothGattCharacteristic characteristic,
                      @Nullable byte[] payload, long uid, long requestSequence) {
            this.kind = kind;
            this.tag = tag;
            this.descriptor = descriptor;
            this.characteristic = characteristic;
            this.payload = payload == null ? null : payload.clone();
            this.uid = uid;
            this.appIdentifier = null;
            this.requestSequence = requestSequence;
        }

        GattOperation(@NonNull GattKind kind, @NonNull GattTag tag,
                      @Nullable BluetoothGattDescriptor descriptor,
                      @Nullable BluetoothGattCharacteristic characteristic,
                      @Nullable byte[] payload, @NonNull String appIdentifier,
                      long requestSequence) {
            this.kind = kind;
            this.tag = tag;
            this.descriptor = descriptor;
            this.characteristic = characteristic;
            this.payload = payload == null ? null : payload.clone();
            this.uid = -1L;
            this.appIdentifier = appIdentifier;
            this.requestSequence = requestSequence;
        }
    }

    private static final class NotificationRecord {
        @NonNull final AncsProtocol.Notification notification;
        final int categoryId;
        final long receivedAt;
        final long observedAtElapsedMs;
        final boolean iconAvailableForPresentation;
        boolean presented;

        NotificationRecord(@NonNull AncsProtocol.Notification notification, int categoryId,
                           long receivedAt) {
            this(notification, categoryId, receivedAt, true,
                    SystemClock.elapsedRealtime(), false);
        }

        NotificationRecord(@NonNull AncsProtocol.Notification notification, int categoryId,
                           long receivedAt, boolean presented) {
            this(notification, categoryId, receivedAt, presented,
                    SystemClock.elapsedRealtime(), false);
        }

        NotificationRecord(@NonNull AncsProtocol.Notification notification, int categoryId,
                           long receivedAt, boolean presented, long observedAtElapsedMs) {
            this(notification, categoryId, receivedAt, presented,
                    observedAtElapsedMs, false);
        }

        NotificationRecord(@NonNull AncsProtocol.Notification notification, int categoryId,
                           long receivedAt, boolean presented, long observedAtElapsedMs,
                           boolean iconAvailableForPresentation) {
            this.notification = notification;
            this.categoryId = categoryId;
            this.receivedAt = receivedAt;
            this.presented = presented;
            this.observedAtElapsedMs = observedAtElapsedMs;
            this.iconAvailableForPresentation = iconAvailableForPresentation;
        }
    }

    private static final class CallRecord {
        final int state;
        @NonNull final String direction;
        final boolean multiparty;

        CallRecord(int state, @NonNull String direction, boolean multiparty) {
            this.state = state;
            this.direction = direction;
            this.multiparty = multiparty;
        }
    }

    private static final class HfpInitialState {
        @Nullable final Bundle agEvents;
        @Nullable final List<?> currentCalls;
        @Nullable final Integer audioState;

        HfpInitialState(@Nullable Bundle agEvents, @Nullable List<?> currentCalls,
                        @Nullable Integer audioState) {
            this.agEvents = agEvents;
            this.currentCalls = currentCalls;
            this.audioState = audioState;
        }
    }

    private static final class Config {
        final boolean enabled;
        @NonNull final String deviceAddress;
        @NonNull final String ancsDeviceAddress;
        final boolean notificationsEnabled;
        final boolean messagesEnabled;
        final boolean includeNotificationText;
        @NonNull final Set<Integer> notificationCategoryIds;
        final int notificationAppFilterMode;
        @NonNull final Set<String> notificationAppFilterKeys;

        Config(boolean enabled, @NonNull String deviceAddress,
               @NonNull String ancsDeviceAddress, boolean notificationsEnabled,
               boolean messagesEnabled, boolean includeNotificationText,
               @NonNull Set<Integer> notificationCategoryIds,
               int notificationAppFilterMode,
               @NonNull Set<String> notificationAppFilterKeys) {
            this.enabled = enabled;
            this.deviceAddress = deviceAddress;
            this.ancsDeviceAddress = ancsDeviceAddress;
            this.notificationsEnabled = notificationsEnabled;
            this.messagesEnabled = messagesEnabled;
            this.includeNotificationText = includeNotificationText;
            this.notificationCategoryIds = notificationCategoryIds;
            this.notificationAppFilterMode =
                    PhoneNotificationFilter.normalizeMode(notificationAppFilterMode);
            this.notificationAppFilterKeys = notificationAppFilterKeys;
        }

        @NonNull
        static Config from(@NonNull Preferences prefs) {
            String classicAddress = bounded(prefs.phoneDeviceAddress.get(), 64);
            String ancsAddress = bounded(prefs.phoneAncsDeviceAddress.get(), 64);
            if (ancsAddress.trim().isEmpty()) ancsAddress = classicAddress;
            return new Config(prefs.phoneConnectorEnabled.get(),
                    classicAddress, ancsAddress,
                    prefs.phoneNotificationsEnabled.get(),
                    prefs.phoneMessagesEnabled.get(),
                    prefs.phoneIncludeNotificationText.get(),
                    PhoneNotificationFilter.parseCategoryIds(
                            prefs.phoneNotificationCategoryIds.get()),
                    prefs.phoneNotificationAppFilterMode.get(),
                    PhoneNotificationFilter.parseAppKeys(
                            prefs.phoneNotificationAppFilterKeys.get()));
        }

        @NonNull
        String signature() {
            return enabled + "|" + deviceAddress + "|" + ancsDeviceAddress
                    + "|" + notificationsEnabled + "|"
                    + messagesEnabled + "|" + includeNotificationText + "|"
                    + PhoneNotificationFilter.serializeCategoryIds(
                    notificationCategoryIds) + "|" + notificationAppFilterMode + "|"
                    + PhoneNotificationFilter.serializeAppKeys(notificationAppFilterKeys);
        }

        boolean ancsNeeded() {
            return notificationsEnabled || messagesEnabled;
        }

        /** The authenticated helper channel is required for power/network telemetry itself. */
        boolean transportNeeded() {
            return enabled;
        }

        boolean allowsCategory(int categoryId) {
            return PhoneNotificationFilter.allowsCategory(
                    notificationCategoryIds, categoryId);
        }

        boolean allowsNotification(@Nullable String appIdentifier, int categoryId) {
            return PhoneNotificationFilter.allows(notificationAppFilterMode,
                    notificationAppFilterKeys, notificationCategoryIds,
                    PhoneAppCatalog.filterKey(appIdentifier), categoryId);
        }
    }
}
