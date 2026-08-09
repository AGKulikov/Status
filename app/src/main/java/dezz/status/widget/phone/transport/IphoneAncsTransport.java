/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.phone.transport;

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
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import dezz.status.widget.phone.IphoneHelperTelemetry;

/**
 * Production BLE/ANCS transport for one explicitly verified iPhone.
 *
 * <p>The listener receives only real-time notifications from the current ANCS session. iOS's
 * initial {@link AncsProtocol#EVENT_FLAG_PRE_EXISTING pre-existing} replay is rejected before a
 * Control Point request is queued, and a removal is emitted only for a UID that this transport
 * actually delivered during the same session.</p>
 */
public final class IphoneAncsTransport {
    public static final String LOCAL_LOGICAL_NAME = "Geely_ANCS";
    public static final String REMOTE_LOGICAL_NAME = "iPhone_ANCS";
    /**
     * Generation 4 deliberately replaces the long-lived F02 bootstrap namespace. Android 9 and
     * Core Bluetooth had both cached the old service database; iOS then rejected even an
     * unfiltered B2/B3 discovery with CBError.uuidNotAllowed before the PAIR exchange started.
     */
    private static final UUID DIAGNOSTIC_SERVICE =
            UUID.fromString("d2d9e4b0-47f1-4e44-a8bb-a932fd5a2f04");
    private static final UUID DIAGNOSTIC_CHARACTERISTIC =
            UUID.fromString("d2d9e4b1-47f1-4e44-a8bb-a932fd5a2f04");
    private static final UUID CONTROL_CHARACTERISTIC =
            UUID.fromString("d2d9e4b2-47f1-4e44-a8bb-a932fd5a2f04");
    private static final UUID SECURE_CHARACTERISTIC =
            UUID.fromString("d2d9e4b3-47f1-4e44-a8bb-a932fd5a2f04");
    /** Dedicated Helper telemetry endpoint on the current verified GATT-server connection. */
    private static final UUID TELEMETRY_CHARACTERISTIC =
            UUID.fromString("d2d9e4b4-47f1-4e44-a8bb-a932fd5a2f04");
    /**
     * Stable scan beacon for the iPhone-Central route. The actual Android GATT namespace is
     * rotated and announced in manufacturer/service data, so an iOS cache from a previous
     * Android process can never poison the next characteristic discovery.
     */
    private static final UUID MANAGED_INCOMING_BEACON_SERVICE =
            UUID.fromString("d2d9e4bf-47f1-4e44-a8bb-a932fd5affff");
    private static final int MANAGED_INCOMING_MANUFACTURER_ID = 0xFFFF;
    private static final int MANAGED_INCOMING_NAMESPACE_PROTOCOL = 1;
    private static final String MANAGED_INCOMING_NAMESPACE_PREFS =
            "iphone_ancs_dynamic_namespace";
    private static final String MANAGED_INCOMING_NAMESPACE_GENERATION = "generation";
    /**
     * iPhone-owned telemetry relay discovered by Android on the already-working ANCS owner.
     * Generation 5 is intentionally separate from Android's generation-4 bootstrap database:
     * Android 9 and Core Bluetooth otherwise reuse the opposite GATT role's stale B4 handle.
     */
    private static final UUID TELEMETRY_RELAY_SERVICE =
            UUID.fromString("d2d9e4b0-47f1-4e44-a8bb-a932fd5a2f05");
    private static final UUID TELEMETRY_RELAY_CHARACTERISTIC =
            UUID.fromString("d2d9e4b4-47f1-4e44-a8bb-a932fd5a2f05");
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
    private static final String LOG_TAG = "KX11ANCS";
    private static final int GATT_SUCCESS = BluetoothGatt.GATT_SUCCESS;
    /** ATT write-not-permitted. On ANCS Notification Source this means iOS has not authorized ANCS. */
    private static final int STATUS_WRITE_NOT_PERMITTED = 3;
    private static final int STATUS_INSUFFICIENT_AUTHENTICATION = 5;
    private static final int STATUS_INSUFFICIENT_AUTHORIZATION = 8;
    private static final int STATUS_INSUFFICIENT_KEY_SIZE = 12;
    private static final int STATUS_INSUFFICIENT_ENCRYPTION = 15;
    /** Android/AOSP GATT_AUTH_FAIL (0x89): SMP/encryption could not be completed. */
    private static final int STATUS_GATT_AUTH_FAIL = 0x89;
    private static final long REQUEST_TIMEOUT_MS = 10_000L;
    /** A direct attempt after a real advertisement may legitimately take longer on Android 9. */
    private static final long CONNECT_TIMEOUT_MS = 35_000L;
    /**
     * Any GATT client that has connected successfully is Android's durable registration for the
     * peer. BluetoothGatt.connect() re-arms that same client after a loss; the watchdog must never
     * close it merely because the iPhone stayed out of range for a fixed interval.
     */
    private static final long PERSISTENT_RECONNECT_WATCHDOG_MS = 30_000L;
    /** ECARX often emits an ACL loss without saying whether Classic or LE was affected. */
    private static final long AMBIGUOUS_ACL_GRACE_MS = 1_200L;
    private static final long LINK_PROBE_TIMEOUT_MS = 2_500L;
    private static final long GPS_CONNECT_TIMEOUT_MS = 15_000L;
    private static final long GPS_SCAN_TIMEOUT_MS = 30_000L;
    private static final long SAVED_PEER_SCAN_RESTART_MS = 30_000L;
    private static final long SAVED_PEER_SCAN_RESTART_DELAY_MS = 400L;
    /**
     * Package replacement kills the app process and its Binder-owned GATT client while the
     * system Bluetooth process and the iPhone's Classic profiles stay alive. Give Android 9 a
     * short quiescence window before registering the replacement background GATT owner.
     */
    private static final long COLD_BACKGROUND_ATTACH_DELAY_MS = 2_500L;
    private static final long AUTO_ANCS_WAIT_TIMEOUT_MS = 60_000L;
    private static final long GPS_POST_SECURE_DISCOVERY_DELAY_MS = 800L;
    private static final long DISCOVERY_TIMEOUT_MS = 15_000L;
    private static final long DESCRIPTOR_WRITE_TIMEOUT_MS = 15_000L;
    /** The first encrypted ANCS CCCD may wait while the user accepts iPhone pairing. */
    private static final long ANCS_DESCRIPTOR_WRITE_TIMEOUT_MS = 90_000L;
    private static final long BATTERY_OPERATION_TIMEOUT_MS = 5_000L;
    private static final long HELPER_TELEMETRY_READ_TIMEOUT_MS = 5_000L;
    /**
     * Notifications are the zero-delay path. A one-second read is the deterministic fallback
     * when iOS coalesces a public battery/CoreTelephony callback while the Helper is backgrounded.
     */
    private static final long HELPER_TELEMETRY_POLL_MS = 1_000L;
    /** Central mode: a tiny B4 notification wakes Core Bluetooth so Helper can push fresh data. */
    private static final long SERVER_TELEMETRY_WAKE_POLL_MS = 5_000L;
    private static final long HELPER_TELEMETRY_BUSY_RETRY_MS = 1_000L;
    private static final long BOND_TIMEOUT_MS = 90_000L;
    private static final long ANCS_REQUEST_GAP_MS = 120L;
    private static final long LIVE_NOTIFICATION_MAX_AGE_MS = 15_000L;
    private static final int MAX_PENDING_ANCS_REQUESTS = 24;
    private static final int MAX_EARLY_NOTIFICATION_SOURCE_FRAMES = 32;
    private static final long ANCS_PERMISSION_RETRY_MS = 5_000L;
    private static final int ANCS_PERMISSION_RETRY_LIMIT = 12;
    private static final long ANCS_SECOND_CCCD_DELAY_MS = 150L;
    private static final long SECURE_TO_CLIENT_CONNECT_DELAY_MS = 400L;
    private static final long DIRECT_FALLBACK_DELAY_MS = 500L;
    private static final int INCOMING_CLIENT_ATTACH_MAX_ATTEMPTS = 3;
    private static final long INCOMING_CLIENT_ATTACH_RETRY_MS = 1_500L;
    /**
     * A direct virtual open against an already-connected incoming peer must produce a callback
     * promptly. Unlike a cold {@code autoConnect=true} registration, this attempt is bounded and
     * only its client wrapper is replaced when Android never reports a result.
     */
    private static final long INCOMING_DIRECT_ATTACH_TIMEOUT_MS = 10_000L;
    private static final long CANDIDATE_UI_INTERVAL_MS = 500L;
    private static final int MAX_CANDIDATES = 150;

    public interface Listener {
        void onState(String state);
        /** Reconnect lifecycle is typed and must not depend only on parsing diagnostic text. */
        default void onRetryRequired(String reason) {}
        void onLog(String line);
        void onCandidates(List<Candidate> candidates);
        void onNotification(NotificationItem item);
        void onAppName(String appIdentifier, String displayName);
        void onBatteryCharacteristic(UUID characteristicUuid, byte[] value);
        /** Helper telemetry received from the peer verified on the current application channel. */
        default void onHelperTelemetry(IphoneHelperTelemetry telemetry) {}
        /** Stable BLE identity proved by PAIR plus a second operation on the same live ATT link. */
        default void onVerifiedPeerAddress(String address) {}
    }

    public static final class Candidate {
        public final BluetoothDevice device;
        public final String address;
        public final String name;
        public final int type;
        public final int bondState;
        public final int rssi;
        public final boolean ancsSolicitation;
        public final String rawAdvertisement;
        public final String origin;

        Candidate(BluetoothDevice device, String address, String name, int type,
                  int bondState, int rssi, boolean ancsSolicitation,
                  String rawAdvertisement, String origin) {
            this.device = device;
            this.address = address;
            this.name = name;
            this.type = type;
            this.bondState = bondState;
            this.rssi = rssi;
            this.ancsSolicitation = ancsSolicitation;
            this.rawAdvertisement = rawAdvertisement;
            this.origin = origin;
        }

        public String displayText() {
            StringBuilder value = new StringBuilder();
            if (ancsSolicitation) value.append("[ANCS] ");
            if (bondState == BluetoothDevice.BOND_BONDED) value.append("[BONDED] ");
            value.append(name.isEmpty() ? "(без имени)" : name)
                    .append("\n").append(address)
                    .append(" · type=").append(typeLabel(type));
            if (rssi > -127) value.append(" · RSSI ").append(rssi);
            value.append(" · ").append(origin);
            return value.toString();
        }
    }

    public static final class NotificationItem {
        public final long uid;
        public final int eventId;
        public final int categoryId;
        public final String appIdentifier;
        public final String appName;
        public final String title;
        public final String message;
        public final String date;
        public final long observedAtElapsedMs;

        NotificationItem(long uid, int eventId, int categoryId, String appIdentifier,
                         String appName, String title, String message, String date,
                         long observedAtElapsedMs) {
            this.uid = uid;
            this.eventId = eventId;
            this.categoryId = categoryId;
            this.appIdentifier = appIdentifier;
            this.appName = appName;
            this.title = title;
            this.message = message;
            this.date = date;
            this.observedAtElapsedMs = observedAtElapsedMs;
        }

        public String displayText() {
            String source = appName.isEmpty() ? appIdentifier : appName + " · " + appIdentifier;
            StringBuilder result = new StringBuilder(source)
                    .append("\n").append(AncsProtocol.categoryLabel(categoryId));
            if (!title.isEmpty()) result.append(" · ").append(title);
            if (!message.isEmpty()) result.append("\n").append(message);
            if (!date.isEmpty()) result.append("\n").append(date);
            return result.toString();
        }
    }

    private enum DescriptorStage {
        NONE,
        SERVICE_CHANGED,
        HELPER_TELEMETRY,
        DATA_SOURCE,
        NOTIFICATION_SOURCE,
        BATTERY_LEVEL,
        BATTERY_LEVEL_STATUS
    }

    private enum BatteryStage {
        NOT_STARTED,
        READ_LEVEL_STATUS,
        SUBSCRIBE_LEVEL_STATUS,
        READ_LEVEL,
        SUBSCRIBE_LEVEL,
        COMPLETE
    }

    private enum RequestKind {
        NOTIFICATION,
        APP_NAME
    }

    private static final class Request {
        final RequestKind kind;
        final long uid;
        final int eventId;
        final int categoryId;
        final String appIdentifier;
        long observedAtElapsedMs;

        private Request(RequestKind kind, long uid, int eventId,
                        int categoryId, String appIdentifier, long observedAtElapsedMs) {
            this.kind = kind;
            this.uid = uid;
            this.eventId = eventId;
            this.categoryId = categoryId;
            this.appIdentifier = appIdentifier;
            this.observedAtElapsedMs = observedAtElapsedMs;
        }

        static Request notification(AncsProtocol.Event event, long observedAtElapsedMs) {
            return new Request(RequestKind.NOTIFICATION, event.uid,
                    event.eventId, event.categoryId, "", observedAtElapsedMs);
        }

        static Request appName(String appIdentifier) {
            return new Request(RequestKind.APP_NAME, -1L, 0, 0, appIdentifier,
                    SystemClock.elapsedRealtime());
        }
    }

    /**
     * Pure session-local admission policy kept separate from Android callbacks so its boundaries
     * can be unit tested. A UID becomes live only after its complete notification has been handed
     * to the listener; consuming a removal makes duplicate removals harmless.
     */
    static final class RealtimeAdmission {
        private final Set<Long> liveSessionUids = new HashSet<>();

        boolean shouldRequest(AncsProtocol.Event event) {
            if (event == null || AncsProtocol.isPreExisting(event)) return false;
            return event.eventId == AncsProtocol.EVENT_ADDED
                    || event.eventId == AncsProtocol.EVENT_MODIFIED;
        }

        void markDelivered(long uid) {
            liveSessionUids.add(uid);
        }

        boolean consumeRemoval(long uid) {
            return liveSessionUids.remove(uid);
        }

        void clear() {
            liveSessionUids.clear();
        }

        boolean contains(long uid) {
            return liveSessionUids.contains(uid);
        }
    }

    /**
     * One peer observed by this app's GATT-server role. The exact BluetoothDevice delivered by
     * this callback is reused for the ANCS GATT-client registration on the same ATT link.
     */
    private static final class GattServerPeer {
        final long sessionGeneration;
        BluetoothDevice device;
        long connectedAtElapsedMs;
        boolean connected;
        boolean linkSecurityChallengeIssued;
        boolean telemetrySubscribed;

        GattServerPeer(long sessionGeneration, BluetoothDevice device) {
            this.sessionGeneration = sessionGeneration;
            this.device = device;
        }
    }

    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final BluetoothManager manager;
    private final BluetoothAdapter adapter;
    private final LinkedHashMap<String, Candidate> candidates = new LinkedHashMap<>();
    private final ArrayDeque<Request> requests = new ArrayDeque<>();
    /** Apple may emit Notification Source immediately after CCCD; retain it until DS is ready. */
    private final ArrayDeque<byte[]> earlyNotificationSourceFrames = new ArrayDeque<>();
    private final Map<Long, AncsProtocol.Event> events = new HashMap<>();
    private final Map<Long, Long> eventObservedAtElapsedMs = new HashMap<>();
    private final Map<String, String> appNames = new HashMap<>();
    private final Set<Long> queuedNotificationUids = new HashSet<>();
    private final Set<Long> dirtyNotificationUids = new HashSet<>();
    private final Set<String> queuedAppIdentifiers = new HashSet<>();
    private final RealtimeAdmission realtimeAdmission = new RealtimeAdmission();
    private final AncsSessionStateMachine sessionState = new AncsSessionStateMachine();

    private BluetoothLeScanner scanner;
    private BluetoothLeAdvertiser advertiser;
    private BluetoothGattServer gattServer;
    private BluetoothGatt gatt;
    private BluetoothDevice activeClientTarget;
    private BluetoothDevice savedPeerScanTarget;
    private BluetoothDevice managedSavedPeer;
    /** Last BLE identity that already passed the selected-phone gate in this live session. */
    private BluetoothDevice managedResolvedPeer;
    private final Object verifiedPeerLock = new Object();
    private BluetoothDevice verifiedPeer;
    private final LinkedHashMap<String, GattServerPeer> gattServerPeers =
            new LinkedHashMap<>();
    private long sessionGeneration;
    private boolean clientConnectInFlight;
    private boolean activeClientAutoConnect;
    private boolean backgroundAttachAttempted;
    private boolean directFallbackAttempted;
    /** Bounded direct virtual opens tried before one durable incoming-route owner is obtained. */
    private int incomingClientAttachAttempt;
    /** Exact bonded BluetoothDevice facade delivered by the current GATT-server link. */
    private BluetoothDevice incomingClientCandidate;
    /** Protocol authorization gate written by Helper only after RequiresANCS is active. */
    private boolean incomingAncsReadyGateOpen;
    /** Prevents duplicate discovery starts for the current connected client owner. */
    private boolean incomingDiscoveryStarted;
    /** Binder callbacks and the main state machine both read this phase-one proof. */
    private volatile boolean secureAttConfirmed;
    private boolean gattClientConnected;
    private boolean activeClientEstablished;
    private long activeClientGeneration;
    private long activeScanGeneration;
    private boolean iphonePeripheralMode;
    private boolean helperBootstrapMode;
    private boolean iphoneConnectStarted;
    private boolean iphonePairAttempted;
    private boolean iphonePairWritePending;
    private boolean iphoneSecureReadPending;
    private boolean iphoneSecureConfirmed;
    private boolean iphoneHelperTelemetrySubscriptionAttempted;
    private boolean iphoneHelperTelemetrySubscribed;
    private boolean iphoneHelperTelemetryReadPending;
    /** At least one complete battery+network TEL3 frame was transferred from Helper B4. */
    private boolean iphoneHelperValidTelemetryReceived;
    /** True after this client has attempted to prove both ANCS CCCDs to Helper v33. */
    private boolean helperAncsReadyProofAttempted;
    /** Serialized write of ANCS-SUBSCRIBED to the iPhone-owned B4 relay is in flight. */
    private boolean helperAncsReadyProofPending;
    /** Helper acknowledged the post-CCCD proof on the same B4 owner. */
    private boolean helperAncsReadyProofAcknowledged;
    private Runnable helperAncsReadyProofRetry;
    /** One deterministic B4 snapshot is read before any potentially encrypted ANCS CCCD. */
    private boolean iphoneHelperInitialReadAttempted;
    /** Service setup resumes only after that first snapshot read (or its bounded timeout). */
    private boolean iphoneServiceSetupDeferredForHelperRead;
    private boolean iphonePostSecureDiscoveryScheduled;
    private boolean iphoneAncsSeen;
    private boolean closing;
    private boolean retrySignalled;
    private boolean managedReconnectEnabled;
    /** True only for the opt-in route where iPhone initiates a link to Geely_ANCS. */
    private boolean managedIncomingMode;
    private boolean ancsRetryAfterBond;
    private boolean ancsAuthorizationFailureSeen;
    private boolean leBondAttemptObserved;
    private int ancsBondRetryCount;
    private Runnable ancsPermissionRetry;
    private int ancsPermissionRetryCount;
    private boolean scanning;
    private boolean advertising;
    private boolean advertisingDesired;
    private boolean advertisingPending;
    private boolean solicitationAdvertising;
    private boolean gattReady;
    private boolean discoveryPending;
    private DescriptorStage descriptorStage = DescriptorStage.NONE;
    private AdvertiseSettings preparedAdvertiseSettings;
    private AdvertiseData preparedAdvertiseData;
    private AdvertiseData preparedScanResponse;

    private BluetoothGattCharacteristic notificationSource;
    private BluetoothGattCharacteristic dataSource;
    private BluetoothGattCharacteristic controlPoint;
    private BluetoothGattCharacteristic serviceChanged;
    private BluetoothGattCharacteristic batteryLevel;
    private BluetoothGattCharacteristic batteryLevelStatus;
    private BluetoothGattCharacteristic iphoneSecureCharacteristic;
    private BluetoothGattCharacteristic iphoneTelemetryCharacteristic;
    /** UUIDs currently published by this Android GATT-server generation. */
    private UUID serverDiagnosticService = DIAGNOSTIC_SERVICE;
    private UUID serverDiagnosticCharacteristic = DIAGNOSTIC_CHARACTERISTIC;
    private UUID serverControlCharacteristic = CONTROL_CHARACTERISTIC;
    private UUID serverSecureCharacteristic = SECURE_CHARACTERISTIC;
    private UUID serverTelemetryCharacteristicUuid = TELEMETRY_CHARACTERISTIC;
    private int serverDiagnosticGeneration = 0x2F04;
    /** Android-owned B4 endpoint used only by the iPhone-Central route. */
    private BluetoothGattCharacteristic serverTelemetryCharacteristic;
    private Request activeRequest;
    private AncsProtocol.NotificationAccumulator notificationAccumulator;
    private AncsProtocol.AppNameAccumulator appNameAccumulator;
    private Runnable requestTimeout;
    private Runnable connectTimeout;
    private Runnable discoveryTimeout;
    private Runnable descriptorWriteTimeout;
    private Runnable batteryReadTimeout;
    private Runnable helperTelemetryReadTimeout;
    private Runnable helperTelemetryPoll;
    private Runnable serverTelemetryWakePoll;
    private long lastHelperTelemetrySuccessLogAt;
    @NonNull private String lastLoggedHelperTelemetry = "";
    private Runnable bondTimeout;
    private Runnable secureConnectStart;
    private Runnable nextClientAttempt;
    private Runnable scanTimeout;
    private Runnable ancsBondRetry;
    private Runnable autoAncsWaitTimeout;
    private Runnable coldBackgroundAttachTask;
    private Runnable managedReconnectTask;
    private Runnable ambiguousAclProbeTask;
    private Runnable linkProbeTimeout;
    private BluetoothGatt linkProbeGatt;
    private long linkProbeGeneration;
    private int managedReconnectAttempt;
    private UUID batteryReadPendingUuid;
    private BatteryStage batteryStage = BatteryStage.NOT_STARTED;
    private boolean candidatePublishScheduled;
    private long lastCandidatePublishAt;
    private final Runnable candidatePublisher = () -> {
        candidatePublishScheduled = false;
        lastCandidatePublishAt = android.os.SystemClock.uptimeMillis();
        publishCandidatesNow();
    };

    public IphoneAncsTransport(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        this.manager = (BluetoothManager) this.context.getSystemService(Context.BLUETOOTH_SERVICE);
        this.adapter = manager == null ? null : manager.getAdapter();
        IntentFilter bondFilter =
                new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        bondFilter.addAction(BluetoothDevice.ACTION_PAIRING_REQUEST);
        this.context.registerReceiver(bondReceiver, bondFilter);
    }

    public void publishCapabilities() {
        boolean feature = context.getPackageManager()
                .hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE);
        log("Android API: " + android.os.Build.VERSION.SDK_INT);
        log("FEATURE_BLUETOOTH_LE: " + feature);
        if (adapter == null) {
            state("NO_ADAPTER");
            log("BluetoothAdapter отсутствует");
            return;
        }
        log("Bluetooth включён: " + adapter.isEnabled());
        log("Multiple advertisement: " + adapter.isMultipleAdvertisementSupported());
        log("Offloaded filtering: " + adapter.isOffloadedFilteringSupported());
        log("Offloaded batching: " + adapter.isOffloadedScanBatchingSupported());
        scanner = adapter.getBluetoothLeScanner();
        advertiser = adapter.getBluetoothLeAdvertiser();
        log("BLE scanner: " + (scanner != null));
        log("BLE advertiser: " + (advertiser != null));
        log("Автоматический GPS-style путь использует только публичные BLE API");
        addBondedDevices();
        state(adapter.isEnabled() ? "ГОТОВО К ТЕСТУ" : "BLUETOOTH_OFF");
    }

    public void startScan() {
        if (!ensureAdapter()) return;
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            state("SCAN_UNAVAILABLE");
            log("BluetoothLeScanner недоступен");
            return;
        }
        if (scanning) {
            log("Сканирование уже запущено");
            return;
        }
        addBondedDevices();
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
                .build();
        try {
            scanner.startScan(Collections.emptyList(), settings, scanCallback);
            scanning = true;
            state("СКАНИРОВАНИЕ BLE");
            log("BLE scan запущен в balanced-режиме без фильтра; "
                    + "AD type 0x15 разбирается вручную");
        } catch (RuntimeException failure) {
            state("SCAN_EXCEPTION");
            log("startScan exception: " + failure);
        }
    }

    /**
     * GPSTether-style bootstrap: the iPhone advertises the diagnostic service and Android owns
     * the BLE central role from the first packet. GATT client and physical link are therefore
     * created by the same connectGatt call instead of trying to attach a client to an already
     * established incoming peripheral-role link.
     */
    public void startIphonePeripheralClientTest() {
        startIphoneHelperFallback();
    }

    /**
     * One-time bootstrap/recovery path. Daily automatic operation should use
     * {@link #connectSavedIphone(String)} and does not require the Helper to be running.
     */
    public void startIphoneHelperFallback() {
        if (!ensureAdapter()) return;
        stopScan();
        stopAdvertising();
        disconnect();
        resetVerifiedPeerSession();
        iphonePeripheralMode = true;
        helperBootstrapMode = true;

        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            iphonePeripheralMode = false;
            helperBootstrapMode = false;
            state("GPS-STYLE · SCAN UNAVAILABLE");
            log("BluetoothLeScanner недоступен");
            return;
        }

        ScanFilter filter = new ScanFilter.Builder()
                .setServiceUuid(new ParcelUuid(DIAGNOSTIC_SERVICE))
                .build();
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();
        try {
            scanner.startScan(Collections.singletonList(filter), settings, scanCallback);
            scanning = true;
            state("FALLBACK · ОТКРОЙТЕ IPHONE HELPER V4");
            log("v10 bootstrap: Android работает BLE central, как HWGPS/GPSTether");
            log("Фильтр scan: service " + DIAGNOSTIC_SERVICE
                    + "; этот scan нужен только для bootstrap/аварийного восстановления");
            scanTimeout = () -> {
                if (!iphonePeripheralMode || !scanning || iphoneConnectStarted) return;
                stopScan();
                state("IPHONE BLE НЕ НАЙДЕН");
                log("За " + GPS_SCAN_TIMEOUT_MS
                        + " ms реклама KX11-iPhone не найдена. "
                        + "Откройте Helper v4 и нажмите «Рекламировать iPhone по BLE»");
            };
            main.postDelayed(scanTimeout, GPS_SCAN_TIMEOUT_MS);
        } catch (RuntimeException failure) {
            iphonePeripheralMode = false;
            helperBootstrapMode = false;
            state("GPS-STYLE · SCAN EXCEPTION");
            log("startScan exception: " + failure);
        }
    }

    /**
     * Daily path for the selected bonded iPhone.
     *
     * <p>The cold path registers one LE background GATT client against the selected system bond.
     * This lets Android resolve iOS's rotating private address even when background advertising
     * hides the Helper UUID from Android scanners. After that owner has connected once it is
     * retained and re-armed indefinitely across radio loss. GPS-style scan/direct connect remains
     * an unbonded/bootstrap fallback, and two GATT clients are never active at once.</p>
     */
    public boolean connectSavedIphone(String address) {
        closing = false;
        retrySignalled = false;
        managedIncomingMode = false;
        if (!ensureAdapter()) return false;
        if (address == null || address.trim().isEmpty()) return false;
        final BluetoothDevice device;
        try {
            device = adapter.getRemoteDevice(address.trim());
        } catch (IllegalArgumentException invalidAddress) {
            log("Saved peer address invalid: `" + address + "`");
            return false;
        }
        boolean matchingGatt = gatt != null
                && activeClientTarget != null && sameDevice(activeClientTarget, device)
                && (clientConnectInFlight || gattClientConnected);
        boolean matchingScan = scanning && savedPeerScanTarget != null
                && sameDevice(savedPeerScanTarget, device);
        boolean matchingScheduledAttach = coldBackgroundAttachTask != null
                && managedSavedPeer != null && sameDevice(managedSavedPeer, device);
        if (iphonePeripheralMode && !helperBootstrapMode
                && (matchingGatt || matchingScan || matchingScheduledAttach)) {
            log("Saved-peer GATT уже активен для "
                    + safeAddress(device) + "; дубликат connectGatt не создаю"
                    + " connected=" + gattClientConnected
                    + " inFlight=" + clientConnectInFlight
                    + " scanning=" + matchingScan
                    + " scheduled=" + matchingScheduledAttach);
            state(gattReady
                    ? "ANCS READY · ОТПРАВЬТЕ УВЕДОМЛЕНИЕ"
                    : "АВТО · SAVED PEER УЖЕ ЗАРЕГИСТРИРОВАН");
            return true;
        }

        stopScan();
        stopAdvertising();
        disconnect();
        resetVerifiedPeerSession();
        managedReconnectEnabled = true;
        managedReconnectAttempt = 0;
        managedSavedPeer = device;
        managedResolvedPeer = null;
        iphonePeripheralMode = true;
        helperBootstrapMode = false;
        iphoneConnectStarted = false;
        if (!claimVerifiedPeer(device)) {
            managedReconnectEnabled = false;
            managedSavedPeer = null;
            iphonePeripheralMode = false;
            state("AUTO · SAVED PEER CONFLICT");
            return false;
        }
        // A bonded iPhone can hide the Helper UUID and local name while iOS is in the background.
        // Register one durable LE background owner against Android's saved bond/IRK instead of
        // waiting for an advertisement that Android is not allowed to see. The GPS-style scan is
        // retained only for an unbonded/bootstrap peer where identity resolution is unavailable.
        if (safeBondState(device) == BluetoothDevice.BOND_BONDED) {
            return scheduleColdBackgroundAttach(device,
                    "cold selected-phone attach after process start");
        }
        log("Saved iPhone peer не BOND_BONDED; background RPA resolution недоступен, "
                + "перехожу к Helper scan");
        return startSavedPeerScan(device);
    }

    /**
     * Opt-in reverse route: KX11 is the link-layer peripheral/GATT server and iPhone Helper is
     * the central that initiates the connection with the ANCS-required Core Bluetooth option.
     * Android subsequently registers its ANCS GATT client against the exact incoming peer; this
     * is a second GATT role on the same physical BLE link, not a second radio connection.
     */
    public boolean acceptIphoneCentral(String address) {
        return acceptIphoneCentral(address, address);
    }

    public boolean acceptIphoneCentral(String address, String classicAddress) {
        closing = false;
        retrySignalled = false;
        if (!ensureAdapter()) return false;
        if (address == null || address.trim().isEmpty()) return false;
        final BluetoothDevice selected;
        try {
            selected = adapter.getRemoteDevice(address.trim());
        } catch (IllegalArgumentException invalidAddress) {
            log("Saved peer address invalid: `" + address + "`");
            return false;
        }

        stopScan();
        stopAdvertising();
        disconnect();
        resetVerifiedPeerSession();
        managedReconnectEnabled = true;
        managedIncomingMode = true;
        managedReconnectAttempt = 0;
        managedSavedPeer = selected;
        boolean dedicatedIdentity = classicAddress != null
                && !address.trim().equalsIgnoreCase(classicAddress.trim());
        managedResolvedPeer = dedicatedIdentity ? selected : null;
        if (managedResolvedPeer != null) {
            // The saved value can be yesterday's resolvable private address.  It is only a hint
            // for diagnostics until this GATT-server generation receives PAIR from the actual
            // incoming link.  Pre-claiming it made every rotated iOS RPA look like a foreign
            // callback and returned ATT status 8 (insufficient authorization) forever.
            log("Reverse route сохранил прежнюю BLE identity только как hint "
                    + safeAddress(managedResolvedPeer)
                    + "; текущий incoming peer будет подтверждён заново через PAIR + SECURE");
        }
        iphonePeripheralMode = false;
        helperBootstrapMode = false;
        iphoneConnectStarted = false;
        state(LOCAL_LOGICAL_NAME + " · IPHONE CENTRAL MODE");
        log("Выбран обратный маршрут: KX11 peripheral/GATT server, "
                + "iPhone Helper central; Classic Bluetooth не изменяется");
        return startGeelyAncsAdvertising();
    }

    private boolean scheduleColdBackgroundAttach(@NonNull BluetoothDevice device,
                                                  @NonNull String reason) {
        if (closing || !managedReconnectEnabled || helperBootstrapMode) return false;
        if (coldBackgroundAttachTask != null) {
            log("Cold background attach уже запланирован; дубль пропущен");
            return true;
        }
        if (gatt != null || clientConnectInFlight || gattClientConnected || scanning) {
            log("Cold background attach не планируется: BLE owner/scan уже активен");
            return true;
        }
        long waitGeneration = sessionState.begin(AncsSessionStateMachine.Phase.RETRY_WAIT);
        state(REMOTE_LOGICAL_NAME + " · COLD START · STACK QUIESCENCE");
        coldBackgroundAttachTask = () -> {
            coldBackgroundAttachTask = null;
            if (closing || !managedReconnectEnabled || helperBootstrapMode
                    || managedSavedPeer == null
                    || !sameDevice(managedSavedPeer, device)
                    || !sessionState.isCurrent(waitGeneration)) return;
            if (!startManagedBackgroundAttach(device, reason)) {
                scheduleManagedReconnect("cold background attach could not start");
            }
        };
        main.postDelayed(coldBackgroundAttachTask, COLD_BACKGROUND_ATTACH_DELAY_MS);
        log("Один cold background GATT owner будет зарегистрирован через "
                + COLD_BACKGROUND_ATTACH_DELAY_MS + " ms · " + reason);
        return true;
    }

    /**
     * Registers the sole long-lived GATT client for the selected bonded iPhone.
     *
     * <p>There is deliberately no connection timeout here. {@code autoConnect=true} is a pending
     * background registration, not a direct attempt: iOS may remain silent for an arbitrary time
     * and Android must keep resolving its RPA from the system bond. The owner is replaced only
     * after an explicit terminal callback/exception, never merely because a timer elapsed.</p>
     */
    private boolean startManagedBackgroundAttach(@NonNull BluetoothDevice selected,
                                                 @NonNull String reason) {
        if (closing || !managedReconnectEnabled || helperBootstrapMode) return false;
        if (!ensureAdapter()) return false;
        if (gatt != null || clientConnectInFlight || gattClientConnected || scanning) {
            log("Background attach не запущен: другая BLE-операция уже активна");
            return true;
        }
        BluetoothDevice target = managedResolvedPeer != null
                ? managedResolvedPeer : selected;
        if (safeBondState(target) != BluetoothDevice.BOND_BONDED) {
            log("Background attach отклонён: target не BOND_BONDED · "
                    + safeAddress(target));
            return startSavedPeerScan(selected);
        }
        if (!claimVerifiedPeer(target)) {
            state("AUTO · SAVED PEER CONFLICT");
            return false;
        }

        cancelAmbiguousAclProbe();
        stopScan();
        clearAncsRuntime();
        clearIphonePeripheralRuntime(false);
        iphonePeripheralMode = true;
        helperBootstrapMode = false;
        iphoneConnectStarted = true;
        activeClientTarget = target;
        activeClientAutoConnect = true;
        activeClientEstablished = false;
        clientConnectInFlight = true;
        activeClientGeneration = sessionState.begin(
                AncsSessionStateMachine.Phase.BACKGROUND_CONNECT);
        state(REMOTE_LOGICAL_NAME + " · BACKGROUND ATTACH · PERSISTENT");
        log("connectGatt(autoConnect=true, TRANSPORT_LE) · target="
                + safeAddress(target) + " bond=" + bondLabel(safeBondState(target))
                + " · one durable cold-start owner · " + reason);
        try {
            BluetoothGatt created = target.connectGatt(context, true, gattCallback,
                    BluetoothDevice.TRANSPORT_LE);
            gatt = created;
            if (created == null) {
                clientConnectInFlight = false;
                activeClientTarget = null;
                activeClientAutoConnect = false;
                log("Background connectGatt вернул null");
                scheduleManagedReconnect("background connectGatt returned null");
            } else {
                log("Background GATT зарегистрирован без закрывающего тайм-аута; "
                        + "жду системное RPA/IRK reconnect-событие");
            }
            return true;
        } catch (RuntimeException failure) {
            clientConnectInFlight = false;
            activeClientTarget = null;
            activeClientAutoConnect = false;
            gatt = null;
            log("Background connectGatt exception: " + failure);
            scheduleManagedReconnect("background attach exception");
            return true;
        }
    }

    /**
     * Explicit recovery hook for ECARX builds that report ACL loss but omit the GATT callback.
     * Keeping the current transport alive preserves Android's resolved iOS BLE identity; closing
     * and recreating it here is exactly what made recovery depend on toggling car Bluetooth.
     */
    public void requestSavedPeerReconnect(@NonNull String reason) {
        requestSavedPeerReconnect(reason, true);
    }

    /**
     * Recovers the managed link without trusting an ambiguous ECARX ACL broadcast.
     *
     * @param confirmedLeLoss true only when Android explicitly identified the lost transport as
     *                        LE; false causes a non-destructive RSSI liveness probe first
     */
    public void requestSavedPeerReconnect(@NonNull String reason, boolean confirmedLeLoss) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(() -> requestSavedPeerReconnect(reason, confirmedLeLoss));
            return;
        }
        if (closing || !managedReconnectEnabled || managedSavedPeer == null) return;
        if (managedIncomingMode) {
            if (!confirmedLeLoss) {
                log("Неоднозначный/Classic ACL loss не управляет reverse BLE link · "
                        + reason);
                return;
            }
            preserveManagedIncomingPublicationAfterLinkLoss(reason.trim().isEmpty()
                    ? "confirmed incoming iPhone link loss" : reason);
            return;
        }
        BluetoothGatt pendingOwner = gatt;
        if (pendingOwner != null && activeClientAutoConnect && clientConnectInFlight
                && !activeClientEstablished
                && sessionState.is(activeClientGeneration,
                AncsSessionStateMachine.Phase.BACKGROUND_CONNECT)) {
            // The cold owner is already registered with Android and is waiting for the bonded
            // iPhone to become connectable. ACL broadcasts (including explicit LE loss) describe
            // exactly the absence this owner was created to survive; replacing it would discard
            // the controller's pending RPA/IRK resolution and reintroduce reconnect churn.
            state(REMOTE_LOGICAL_NAME + " · RECOVERING · BACKGROUND WAIT");
            log("Сохраняю ожидающий background GATT owner; ACL event не создаёт новый client · "
                    + reason);
            return;
        }
        if (!confirmedLeLoss && isAncsReady()) {
            scheduleAmbiguousAclProbe(reason.trim().isEmpty()
                    ? "ambiguous selected-phone ACL loss" : reason);
            return;
        }
        BluetoothGatt owner = gatt;
        if (owner != null && activeClientEstablished
                && sessionState.isCurrent(activeClientGeneration)) {
            if (gattClientConnected) {
                restartDiscoveryOnPersistentOwner(owner, activeClientGeneration,
                        reason.trim().isEmpty() ? "selected iPhone link refresh" : reason);
            } else {
                awaitPersistentGattReconnect(owner, activeClientGeneration,
                        reason.trim().isEmpty() ? "selected iPhone ACL link lost" : reason);
            }
        } else {
            scheduleManagedReconnect(reason.trim().isEmpty()
                    ? "selected iPhone ACL link lost" : reason);
        }
        state(REMOTE_LOGICAL_NAME + " · RECOVERING");
    }

    private void scheduleAmbiguousAclProbe(@NonNull String reason) {
        if (ambiguousAclProbeTask != null || linkProbeTimeout != null) {
            log("Неоднозначный ACL loss уже проверяется; дубль игнорирую");
            return;
        }
        BluetoothGatt expected = gatt;
        long expectedGeneration = activeClientGeneration;
        if (expected == null || !isAncsReady()
                || !sessionState.isCurrent(expectedGeneration)) {
            scheduleManagedReconnect(reason + "; live ANCS owner absent");
            state(REMOTE_LOGICAL_NAME + " · RECOVERING");
            return;
        }
        ambiguousAclProbeTask = () -> {
            ambiguousAclProbeTask = null;
            if (closing || expected != gatt
                    || !sessionState.isCurrent(expectedGeneration)) return;
            if (!isAncsReady()) {
                scheduleManagedReconnect(reason + "; ANCS lost during grace");
                state(REMOTE_LOGICAL_NAME + " · RECOVERING");
                return;
            }
            sessionState.move(expectedGeneration,
                    AncsSessionStateMachine.Phase.VERIFYING_LINK);
            boolean started;
            try {
                started = expected.readRemoteRssi();
            } catch (RuntimeException failure) {
                started = false;
                log("readRemoteRssi liveness probe exception: " + failure);
            }
            if (!started) {
                scheduleManagedReconnect(reason + "; liveness probe rejected");
                state(REMOTE_LOGICAL_NAME + " · RECOVERING");
                return;
            }
            linkProbeGatt = expected;
            linkProbeGeneration = expectedGeneration;
            linkProbeTimeout = () -> {
                linkProbeTimeout = null;
                linkProbeGatt = null;
                if (closing || expected != gatt
                        || !sessionState.isCurrent(expectedGeneration)) return;
                log("GATT liveness probe не дал callback за "
                        + LINK_PROBE_TIMEOUT_MS + " ms");
                scheduleManagedReconnect(reason + "; liveness probe timeout");
                state(REMOTE_LOGICAL_NAME + " · RECOVERING");
            };
            main.postDelayed(linkProbeTimeout, LINK_PROBE_TIMEOUT_MS);
            log("Тип ACL transport не указан; проверяю живой ANCS GATT, "
                    + "не закрывая его · " + reason);
        };
        main.postDelayed(ambiguousAclProbeTask, AMBIGUOUS_ACL_GRACE_MS);
    }

    private void cancelAmbiguousAclProbe() {
        if (ambiguousAclProbeTask != null) main.removeCallbacks(ambiguousAclProbeTask);
        if (linkProbeTimeout != null) main.removeCallbacks(linkProbeTimeout);
        ambiguousAclProbeTask = null;
        linkProbeTimeout = null;
        linkProbeGatt = null;
        linkProbeGeneration = 0L;
    }

    /**
     * Keeps the one successfully established Android GATT client alive across radio loss. This is
     * deliberately independent of the original autoConnect flag: AOSP BluetoothGatt.connect()
     * reuses the same registered client and changes it into the background reconnect owner.
     */
    private void awaitPersistentGattReconnect(@NonNull BluetoothGatt expected,
                                              long expectedGeneration,
                                              @NonNull String reason) {
        cancelConnectTimeout();
        cancelAmbiguousAclProbe();
        clearAncsRuntime();
        clearIphonePeripheralRuntime(false);
        gattClientConnected = false;
        clientConnectInFlight = true;
        activeClientAutoConnect = true;
        if (!sessionState.move(expectedGeneration,
                AncsSessionStateMachine.Phase.BACKGROUND_CONNECT)) return;
        state(REMOTE_LOGICAL_NAME + " · RECOVERING · PERSISTENT WAIT");
        rearmPersistentGattOwner(expected, expectedGeneration, reason, true);
    }

    /**
     * Reuses the already registered Android GATT owner indefinitely. Closing this object
     * unregisters the background listener; on KX11/Android 9 a fresh scan then often cannot see
     * the bonded iPhone until the user toggles Bluetooth. A watchdog therefore calls connect()
     * on the same owner and re-schedules itself without creating a competing GATT client.
     */
    private void rearmPersistentGattOwner(@NonNull BluetoothGatt expected,
                                         long expectedGeneration,
                                         @NonNull String reason,
                                         boolean immediate) {
        if (closing || gatt != expected || !clientConnectInFlight
                || !sessionState.is(expectedGeneration,
                AncsSessionStateMachine.Phase.BACKGROUND_CONNECT)) return;
        if (immediate) {
            boolean accepted;
            try {
                accepted = expected.connect();
            } catch (RuntimeException failure) {
                accepted = false;
                log("Persistent GATT owner connect() exception: " + failure);
            }
            log("Persistent GATT owner re-armed=" + accepted + " · " + reason);
        }
        cancelConnectTimeout();
        connectTimeout = () -> {
            connectTimeout = null;
            if (closing || gatt != expected || !clientConnectInFlight
                    || !sessionState.is(expectedGeneration,
                    AncsSessionStateMachine.Phase.BACKGROUND_CONNECT)) return;
            log("iPhone ещё не вернулся за " + PERSISTENT_RECONNECT_WATCHDOG_MS
                    + " ms; сохраняю единственного GATT owner и повторяю connect()");
            rearmPersistentGattOwner(expected, expectedGeneration, reason, true);
        };
        main.postDelayed(connectTimeout, PERSISTENT_RECONNECT_WATCHDOG_MS);
        log("Постоянный GATT owner сохранён без таймера закрытия · " + reason);
    }

    /** Re-discovers changed services on the same connected owner without touching the radio. */
    private void restartDiscoveryOnPersistentOwner(@NonNull BluetoothGatt expected,
                                                   long expectedGeneration,
                                                   @NonNull String reason) {
        if (closing || gatt != expected || !gattClientConnected
                || !activeClientEstablished
                || !sessionState.isCurrent(expectedGeneration)) return;
        if (managedReconnectTask != null) return;
        cancelAmbiguousAclProbe();
        clearAncsRuntime();
        clearIphonePeripheralRuntime(false);
        state(REMOTE_LOGICAL_NAME + " · RECOVERING · SAME GATT DISCOVERY");
        managedReconnectTask = () -> {
            managedReconnectTask = null;
            if (closing || gatt != expected || !gattClientConnected
                    || !activeClientEstablished
                    || !sessionState.isCurrent(expectedGeneration)) return;
            log("Повторяю service discovery на том же GATT owner · " + reason);
            discoverServices(expected);
        };
        main.postDelayed(managedReconnectTask, SAVED_PEER_SCAN_RESTART_DELAY_MS);
    }

    private boolean startSavedPeerScan(@NonNull BluetoothDevice device) {
        if (closing || !iphonePeripheralMode || helperBootstrapMode) return false;
        if (gatt != null || clientConnectInFlight || gattClientConnected) {
            log("Identity scan не запущен: GATT connect/session уже активен");
            return true;
        }
        scanner = adapter == null ? null : adapter.getBluetoothLeScanner();
        if (scanner == null) {
            savedPeerScanTarget = null;
            state("AUTO · SAVED PEER SCAN UNAVAILABLE");
            log("BluetoothLeScanner недоступен для saved-peer reconnect");
            return false;
        }
        String address = safeAddress(device);
        if (address.isEmpty()) {
            savedPeerScanTarget = null;
            state("AUTO · SAVED PEER SCAN FAILED · EMPTY ADDRESS");
            return false;
        }
        // Do not put the Classic/public address into a hardware scan filter. iOS rotates its BLE
        // private address, and several ECARX firmwares apply the filter before the controller has
        // resolved the bond/IRK. A broad software scan lets Android return the resolved
        // BluetoothDevice and we then apply the selected-phone gate ourselves.
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setReportDelay(0L)
                .build();
        savedPeerScanTarget = device;
        activeScanGeneration = sessionState.begin(AncsSessionStateMachine.Phase.SCANNING);
        long expectedGeneration = activeScanGeneration;
        try {
            scanner.startScan(Collections.emptyList(), settings, scanCallback);
            scanning = true;
            state(REMOTE_LOGICAL_NAME + " · IDENTITY SCAN");
            log("GPS-style autoscan: selected=" + address
                    + ", LOW_LATENCY/unfiltered; connectGatt начнётся только после "
                    + "private Helper UUID/selected identity gate");
            BluetoothDevice expected = device;
            scanTimeout = () -> {
                if (closing || helperBootstrapMode || iphoneConnectStarted
                        || !scanning || savedPeerScanTarget == null
                        || !sameDevice(savedPeerScanTarget, expected)
                        || !sessionState.is(expectedGeneration,
                        AncsSessionStateMachine.Phase.SCANNING)) return;
                log("Saved-peer autoscan работает " + SAVED_PEER_SCAN_RESTART_MS
                        + " ms без match; безопасно перерегистрирую один scan");
                stopScan();
                main.postDelayed(() -> {
                    if (!closing && managedReconnectEnabled
                            && iphonePeripheralMode && !helperBootstrapMode
                            && !iphoneConnectStarted) {
                        startSavedPeerScan(expected);
                    }
                }, SAVED_PEER_SCAN_RESTART_DELAY_MS);
            };
            main.postDelayed(scanTimeout, SAVED_PEER_SCAN_RESTART_MS);
            return true;
        } catch (RuntimeException failure) {
            scanning = false;
            savedPeerScanTarget = null;
            state("AUTO · SAVED PEER SCAN FAILED");
            log("saved-peer startScan exception: " + failure);
            return false;
        }
    }

    public void stopScan() {
        if (scanTimeout != null) main.removeCallbacks(scanTimeout);
        scanTimeout = null;
        boolean wasScanning = scanning;
        scanning = false;
        savedPeerScanTarget = null;
        if (!wasScanning || scanner == null) return;
        try {
            scanner.stopScan(scanCallback);
        } catch (RuntimeException failure) {
            log("stopScan exception: " + failure);
        }
        state("СКАНИРОВАНИЕ ОСТАНОВЛЕНО");
    }

    /**
     * Publishes the stable application-owned Geely_ANCS identity without renaming the system
     * Bluetooth adapter. Classic HFP/A2DP/PBAP therefore keep the stock Geely name.
     */
    private boolean startGeelyAncsAdvertising() {
        if (!ensureAdapter()) return false;
        advertiser = adapter.getBluetoothLeAdvertiser();
        if (advertiser == null) {
            log(LOCAL_LOGICAL_NAME + ": BluetoothLeAdvertiser недоступен; "
                    + "продолжаю Android-central recovery");
            return false;
        }
        if (advertising || advertisingPending || advertisingDesired) return true;

        // A production ANCS accessory exposes one stable GATT database. The iPhone-side helper
        // only uses the beacon as a link anchor and never discovers this Android service, so
        // rotating UUIDs cannot improve cache correctness and only creates reconnect deadlocks.
        useStaticDiagnosticNamespace();
        byte[] namespaceFrame = managedIncomingNamespaceFrame();

        preparedAdvertiseSettings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(true)
                .setTimeout(0)
                .build();
        preparedAdvertiseData = new AdvertiseData.Builder()
                .setIncludeTxPowerLevel(false)
                .addServiceUuid(new ParcelUuid(MANAGED_INCOMING_BEACON_SERVICE))
                .addManufacturerData(MANAGED_INCOMING_MANUFACTURER_ID, namespaceFrame)
                .build();
        // A service-data logical name is independent from BluetoothAdapter#getName(). It is
        // intentionally not setIncludeDeviceName(true), because that would expose the Classic
        // adapter name and make the two logical transports look like one connection again.
        preparedScanResponse = new AdvertiseData.Builder()
                .addServiceData(new ParcelUuid(MANAGED_INCOMING_BEACON_SERVICE),
                        appendBytes(namespaceFrame,
                                LOCAL_LOGICAL_NAME.getBytes(StandardCharsets.UTF_8)))
                .build();
        solicitationAdvertising = false;
        advertisingDesired = true;
        state(LOCAL_LOGICAL_NAME + " · STARTING");
        log("Публикую стабильный BLE link-anchor " + serverDiagnosticService
                + " как " + LOCAL_LOGICAL_NAME
                + "; fixed generation="
                + String.format(Locale.US, "%04X", serverDiagnosticGeneration)
                + " beacon=" + MANAGED_INCOMING_BEACON_SERVICE
                + "; системное имя Classic-адаптера не меняется");
        openGattServer();
        return gattServer != null;
    }

    /** Allocates one persistent namespace per Android GATT-server publication. */
    private void rotateManagedIncomingDiagnosticNamespace() {
        SharedPreferences preferences = context.getSharedPreferences(
                MANAGED_INCOMING_NAMESPACE_PREFS, Context.MODE_PRIVATE);
        int previous = preferences.getInt(MANAGED_INCOMING_NAMESPACE_GENERATION, 0x2F04);
        int generation = (previous + 1) & 0xFFFF;
        if (generation == 0 || generation == 0xFFFF) generation = 1;
        preferences.edit().putInt(MANAGED_INCOMING_NAMESPACE_GENERATION, generation).apply();
        serverDiagnosticGeneration = generation;
        serverDiagnosticService = managedIncomingUuid(0, generation);
        serverDiagnosticCharacteristic = managedIncomingUuid(1, generation);
        serverControlCharacteristic = managedIncomingUuid(2, generation);
        serverSecureCharacteristic = managedIncomingUuid(3, generation);
        serverTelemetryCharacteristicUuid = managedIncomingUuid(4, generation);
    }

    private void useStaticDiagnosticNamespace() {
        serverDiagnosticGeneration = 0x2F04;
        serverDiagnosticService = DIAGNOSTIC_SERVICE;
        serverDiagnosticCharacteristic = DIAGNOSTIC_CHARACTERISTIC;
        serverControlCharacteristic = CONTROL_CHARACTERISTIC;
        serverSecureCharacteristic = SECURE_CHARACTERISTIC;
        serverTelemetryCharacteristicUuid = TELEMETRY_CHARACTERISTIC;
    }

    private static UUID managedIncomingUuid(int kind, int generation) {
        return UUID.fromString(String.format(Locale.US,
                "d2d9e4b%d-47f1-4e44-a8bb-a932fd5a%04x",
                kind, generation & 0xFFFF));
    }

    private byte[] managedIncomingNamespaceFrame() {
        return new byte[]{
                (byte) MANAGED_INCOMING_NAMESPACE_PROTOCOL,
                (byte) ((serverDiagnosticGeneration >>> 8) & 0xFF),
                (byte) (serverDiagnosticGeneration & 0xFF)
        };
    }

    private static byte[] appendBytes(byte[] first, byte[] second) {
        byte[] result = new byte[first.length + second.length];
        System.arraycopy(first, 0, result, 0, first.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

    /** Legacy comparison test. It uses only a normal public diagnostic advertisement. */
    public void startIncomingConnectionTest() {
        if (!ensureAdapter()) return;
        stopAdvertising();
        disconnect();
        resetVerifiedPeerSession();
        useStaticDiagnosticNamespace();
        advertiser = adapter.getBluetoothLeAdvertiser();
        if (advertiser == null) {
            state("ADVERTISER_UNAVAILABLE");
            log("Контроллер/ECARX не предоставляет BluetoothLeAdvertiser");
            return;
        }

        AdvertiseData.Builder primary = new AdvertiseData.Builder()
                .setIncludeTxPowerLevel(false)
                .addServiceUuid(new ParcelUuid(DIAGNOSTIC_SERVICE));
        solicitationAdvertising = false;
        log("Запускаю обычную diagnostic-рекламу через публичный Android API");
        log("На iPhone откройте LightBlue, найдите UUID "
                + DIAGNOSTIC_SERVICE + " и нажмите Connect");
        log("В CONTROL " + CONTROL_CHARACTERISTIC
                + " запишите ASCII PAIR; только после этого peer будет подтверждён");

        preparedAdvertiseSettings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(true)
                .setTimeout(0)
                .build();
        preparedAdvertiseData = primary.build();
        preparedScanResponse = new AdvertiseData.Builder()
                .setIncludeDeviceName(true)
                .build();
        advertisingDesired = true;
        state("ЗАПУСК GATT SERVER");
        openGattServer();
    }

    public void stopAdvertising() {
        boolean shouldStopFramework =
                advertising || advertisingPending || advertisingDesired;
        advertisingDesired = false;
        advertising = false;
        advertisingPending = false;
        solicitationAdvertising = false;
        clearPreparedAdvertising();
        if (shouldStopFramework && advertiser != null) {
            try {
                advertiser.stopAdvertising(advertiseCallback);
            } catch (RuntimeException failure) {
                log("stopAdvertising exception: " + failure);
            }
        }
        closeGattServer();
    }

    private void connectToAdvertisingIphone(BluetoothDevice device) {
        if (!iphonePeripheralMode || iphoneConnectStarted || device == null) return;
        iphoneConnectStarted = true;
        stopScan();
        if (!claimVerifiedPeer(device)) {
            iphonePeripheralMode = false;
            state("GPS-STYLE · PEER CONFLICT");
            log("Найденный iPhone не совпал с peer текущей test-session");
            return;
        }
        connectIphonePeripheral(device, GPS_CONNECT_TIMEOUT_MS,
                "ПОДКЛЮЧАЮ IPHONE · HELPER FALLBACK",
                "Helper-filtered scan; Android создаёт bootstrap BLE link первым");
    }

    private void connectToSavedAdvertisingIphone(@NonNull BluetoothDevice device,
                                                  boolean solicitsAncs,
                                                  boolean advertisesHelperService) {
        BluetoothDevice expected = savedPeerScanTarget;
        if (!iphonePeripheralMode || helperBootstrapMode || iphoneConnectStarted
                || expected == null
                || !sessionState.is(activeScanGeneration,
                AncsSessionStateMachine.Phase.SCANNING)) return;
        // The scan callback already passed the selected-phone gate. Re-check it here because iOS
        // commonly rotates from the saved Classic/public address to a bonded BLE private address;
        // requiring sameDevice() a second time discarded the valid resolved peer just before
        // connectGatt and was the reason the phone never reached ANCS discovery.
        if (!matchesManagedSavedPeer(expected, device, solicitsAncs,
                advertisesHelperService)) return;
        iphoneConnectStarted = true;
        stopScan();
        // The scan callback has just passed the selected-phone + private service gate. Promote the
        // current RPA atomically; generic claimVerifiedPeer() intentionally cannot make this
        // protocol-specific decision on its own.
        synchronized (verifiedPeerLock) {
            verifiedPeer = device;
        }
        managedResolvedPeer = device;
        connectIphonePeripheral(device, CONNECT_TIMEOUT_MS,
                "GPS-STYLE · SAVED PEER CONNECTING",
                "selected identity resolved; one direct GATT after advertisement");
    }

    private void connectIphonePeripheral(BluetoothDevice device, long timeoutMs,
                                         String connectingState, String reason) {
        clearAncsRuntime();
        clearIphonePeripheralRuntime(false);
        iphonePeripheralMode = true;
        iphoneConnectStarted = true;
        activeClientTarget = device;
        activeClientAutoConnect = false;
        activeClientEstablished = false;
        clientConnectInFlight = true;
        activeClientGeneration = sessionState.begin(
                AncsSessionStateMachine.Phase.DIRECT_CONNECT);
        long expectedGeneration = activeClientGeneration;
        state(connectingState);
        log("iPhone target: " + safeName(device) + " " + safeAddress(device)
                + " type=" + typeLabel(safeType(device))
                + " bond=" + bondLabel(safeBondState(device)));
        log("connectGatt(autoConnect=false, TRANSPORT_LE) · " + reason);

        try {
            gatt = device.connectGatt(context, false, gattCallback,
                    BluetoothDevice.TRANSPORT_LE);
            if (gatt == null) {
                clientConnectInFlight = false;
                activeClientTarget = null;
                state("GPS-STYLE · CONNECT RETURNED NULL");
                log("connectGatt вернул null");
                return;
            }
            BluetoothGatt expected = gatt;
            connectTimeout = () -> {
                if (gatt != expected || !clientConnectInFlight
                        || !sessionState.is(expectedGeneration,
                        AncsSessionStateMachine.Phase.DIRECT_CONNECT)) return;
                clientConnectInFlight = false;
                log("Нет callback подключения за " + timeoutMs
                        + " ms · target=" + safeAddress(expected.getDevice())
                        + " autoConnect=false");
                closeClientGatt(expected);
                clearAncsRuntime();
                // state() owns the one serialized recovery transition. Publishing it only after
                // this never-established client is closed avoids closing the same GATT twice.
                state("GPS-STYLE · CONNECT TIMEOUT");
            };
            main.postDelayed(connectTimeout, timeoutMs);
        } catch (RuntimeException failure) {
            clientConnectInFlight = false;
            activeClientTarget = null;
            state("GPS-STYLE · CONNECT EXCEPTION");
            log("connectGatt exception: " + failure);
        }
    }

    public void connect(Candidate candidate) {
        if (!secureAttConfirmed) {
            log("Same-peer attach отложен: сначала нужен SECURE ATT OK от verified peer");
            return;
        }
        BluetoothGatt current = gatt;
        if (current != null && gattClientConnected) {
            discoverServices(current);
            return;
        }
        if (managedIncomingMode) {
            BluetoothGatt pendingOwner = gatt;
            if (pendingOwner != null) {
                if (gattClientConnected) {
                    log("Повторный discoverServices на текущем same-peer GATT owner");
                    discoverServices(pendingOwner);
                } else if (activeClientEstablished) {
                    awaitIncomingBackgroundOwner(pendingOwner, activeClientGeneration,
                            "ручной same-peer reconnect");
                } else {
                    log("Первичный direct attach уже ожидает callback; ручной дубль не создаю");
                }
                return;
            }
            if (nextClientAttempt != null || clientConnectInFlight) {
                log("Direct same-peer clientIf уже регистрируется или запланирован");
                return;
            }
            // A manual diagnostic retry resets only the bounded never-established attempts.
            incomingClientAttachAttempt = 0;
            startSamePeerAttach(false, "ручная direct-регистрация same-peer clientIf");
            return;
        }
        if (!backgroundAttachAttempted) {
            startSamePeerAttach(true, "ручной запуск");
        } else if (nextClientAttempt != null) {
            log("Единственный direct fallback уже запланирован после ошибки background attach");
        } else {
            log("Ручной повтор заблокирован: fallback запускается только автоматически "
                    + "после timeout/status failure первой попытки");
        }
    }

    public void requestBond() {
        BluetoothDevice device = getVerifiedPeer();
        if (device == null) {
            log("Нет активного verified peer: сначала подключите iPhone BLE");
            return;
        }
        if (safeBondState(device) == BluetoothDevice.BOND_BONDED) {
            log("Активный iPhone BLE peer уже BOND_BONDED");
        } else {
            requestBond(device);
        }
    }

    public void refreshAndReconnect() {
        BluetoothGatt current = gatt;
        if (current != null && gattClientConnected) {
            log("Повторный discoverServices на текущем same-peer GATT client");
            discoverServices(current);
            return;
        }
        if (!secureAttConfirmed) {
            log("Обновление GATT отложено: сначала нужен SECURE ATT OK");
            return;
        }
        connect(null);
    }

    public void disconnect() {
        iphonePeripheralMode = false;
        iphoneConnectStarted = false;
        cancelColdBackgroundAttach();
        cancelAmbiguousAclProbe();
        sessionState.begin(AncsSessionStateMachine.Phase.IDLE);
        clearIphonePeripheralRuntime(true);
        cancelClientAttemptCallbacks();
        clearAncsRuntime();
        gattClientConnected = false;
        clientConnectInFlight = false;
        activeClientTarget = null;
        activeClientEstablished = false;
        incomingClientCandidate = null;
        incomingAncsReadyGateOpen = false;
        incomingDiscoveryStarted = false;
        BluetoothGatt old = gatt;
        gatt = null;
        if (old != null) {
            try {
                old.disconnect();
            } catch (RuntimeException ignored) {
            }
            try {
                old.close();
            } catch (RuntimeException ignored) {
            }
        }
        state("ОТКЛЮЧЕНО");
    }

    public void close() {
        closing = true;
        managedReconnectEnabled = false;
        managedIncomingMode = false;
        managedSavedPeer = null;
        managedResolvedPeer = null;
        if (managedReconnectTask != null) main.removeCallbacks(managedReconnectTask);
        managedReconnectTask = null;
        cancelAmbiguousAclProbe();
        stopScan();
        stopAdvertising();
        disconnect();
        sessionState.close();
        resetVerifiedPeerSession();
        main.removeCallbacks(candidatePublisher);
        try {
            context.unregisterReceiver(bondReceiver);
        } catch (IllegalArgumentException ignored) {
        }
    }

    private void addBondedDevices() {
        if (adapter == null) return;
        Set<BluetoothDevice> bonded;
        try {
            bonded = adapter.getBondedDevices();
        } catch (SecurityException denied) {
            log("Нет доступа к bonded devices: " + denied);
            return;
        }
        for (BluetoothDevice device : bonded) {
            updateCandidate(device, -127, false, "", "bonded");
        }
    }

    private void resetVerifiedPeerSession() {
        cancelServerTelemetryWakePoll();
        synchronized (verifiedPeerLock) {
            verifiedPeer = null;
            secureAttConfirmed = false;
        }
        clearIphonePeripheralRuntime(true);
        cancelColdBackgroundAttach();
        cancelClientAttemptCallbacks();
        sessionGeneration++;
        synchronized (gattServerPeers) {
            gattServerPeers.clear();
        }
        activeClientTarget = null;
        activeClientAutoConnect = false;
        activeClientEstablished = false;
        backgroundAttachAttempted = false;
        directFallbackAttempted = false;
        incomingClientAttachAttempt = 0;
        incomingClientCandidate = null;
        incomingAncsReadyGateOpen = false;
        incomingDiscoveryStarted = false;
        clientConnectInFlight = false;
        gattClientConnected = false;
        log("Новая test-session=" + sessionGeneration
                + "; verified peer и runtime-состояние очищены");
    }

    private void clearIphonePeripheralRuntime(boolean clearMode) {
        cancelHelperTelemetryRecovery();
        if (helperAncsReadyProofRetry != null) {
            main.removeCallbacks(helperAncsReadyProofRetry);
            helperAncsReadyProofRetry = null;
        }
        iphonePairAttempted = false;
        iphonePairWritePending = false;
        iphoneSecureReadPending = false;
        iphoneSecureConfirmed = false;
        iphoneHelperTelemetrySubscriptionAttempted = false;
        iphoneHelperTelemetrySubscribed = false;
        iphoneHelperTelemetryReadPending = false;
        iphoneHelperValidTelemetryReceived = false;
        helperAncsReadyProofAttempted = false;
        helperAncsReadyProofPending = false;
        helperAncsReadyProofAcknowledged = false;
        iphoneHelperInitialReadAttempted = false;
        iphoneServiceSetupDeferredForHelperRead = false;
        iphonePostSecureDiscoveryScheduled = false;
        iphoneAncsSeen = false;
        ancsRetryAfterBond = false;
        ancsAuthorizationFailureSeen = false;
        leBondAttemptObserved = false;
        ancsBondRetryCount = 0;
        iphoneSecureCharacteristic = null;
        iphoneTelemetryCharacteristic = null;
        if (clearMode) {
            iphonePeripheralMode = false;
            helperBootstrapMode = false;
            iphoneConnectStarted = false;
        }
    }

    private BluetoothDevice getVerifiedPeer() {
        synchronized (verifiedPeerLock) {
            return verifiedPeer;
        }
    }

    public String getVerifiedPeerAddress() {
        return safeAddress(getVerifiedPeer());
    }

    public String getVerifiedPeerName() {
        return safeName(getVerifiedPeer());
    }

    public boolean isAncsReady() {
        if (!gattReady || !gattClientConnected || gatt == null) return false;
        return !managedIncomingMode
                || (iphoneHelperTelemetrySubscribed
                && iphoneHelperValidTelemetryReceived
                && helperAncsReadyProofAcknowledged);
    }

    /**
     * The first valid PAIR command fixes the peer for the whole test session. A later callback
     * from another device must never replace it.
     */
    private boolean claimVerifiedPeer(BluetoothDevice device) {
        if (device == null) return false;
        synchronized (verifiedPeerLock) {
            if (verifiedPeer == null) {
                verifiedPeer = device;
                return true;
            }
            if (sameDevice(verifiedPeer, device)) return true;
            // A bonded iPhone may reappear under an RPA. The custom incoming service and the
            // following encrypted SECURE characteristic are the primary proof; a unique bonded
            // name is only the tie-breaker that associates the resolved object with the selected
            // Classic phone.
            if (managedReconnectEnabled && managedSavedPeer != null
                    && safeBondState(managedSavedPeer) == BluetoothDevice.BOND_BONDED
                    && safeBondState(device) == BluetoothDevice.BOND_BONDED
                    && uniqueBondedNameMatch(managedSavedPeer, device)) {
                verifiedPeer = device;
                return true;
            }
            return false;
        }
    }

    /**
     * Adopts the exact bonded facade from the incoming link for one direct GATT virtual open.
     * Android 9 may first deliver an anonymous BOND_NONE facade, so that callback is retained only
     * as a server peer and never used for {@code connectGatt}. Starting the client registration is
     * independent from PAIR/B3/ANCS-READY: those protocol gates still control authorization and
     * service discovery, while the direct clientIf can bind to the physical link already in use.
     */
    private void attachAncsClientToIncomingOwner(BluetoothDevice device) {
        if (!managedIncomingMode || device == null || findConnectedServerPeer(device) == null) {
            return;
        }
        if (!isSelectedBondedIncomingDevice(device)) {
            state("REQUIRES_ANCS LINK · ЖДУ BONDED IDENTITY");
            log("Incoming facade не используется для client attach · objectId="
                    + System.identityHashCode(device)
                    + " address=" + safeAddress(device)
                    + " bond=" + bondLabel(safeBondState(device))
                    + "; жду exact BOND_BONDED facade выбранного iPhone");
            return;
        }
        adoptIncomingClientCandidate(device, "bonded incoming GATT-server callback");
    }

    private boolean isSelectedBondedIncomingDevice(@NonNull BluetoothDevice device) {
        if (safeBondState(device) != BluetoothDevice.BOND_BONDED
                || managedSavedPeer == null) return false;
        return sameDevice(managedSavedPeer, device)
                || sameDevice(managedResolvedPeer, device)
                || (safeBondState(managedSavedPeer) == BluetoothDevice.BOND_BONDED
                && uniqueBondedNameMatch(managedSavedPeer, device));
    }

    private void adoptIncomingClientCandidate(@NonNull BluetoothDevice device,
                                              @NonNull String reason) {
        if (!managedIncomingMode || !isSelectedBondedIncomingDevice(device)
                || findConnectedServerPeer(device) == null) return;
        BluetoothDevice previous = incomingClientCandidate;
        if (previous != null && !sameDevice(previous, device)) {
            log("Incoming direct candidate conflict: сохраняю первый exact bonded facade "
                    + safeAddress(previous) + ", отклоняю " + safeAddress(device));
            return;
        }
        incomingClientCandidate = device;
        managedResolvedPeer = device;
        state("REQUIRES_ANCS LINK · DIRECT CLIENT ATTACH");
        log("Exact bonded incoming facade принят для direct clientIf · objectId="
                + System.identityHashCode(device)
                + " address=" + safeAddress(device)
                + "; authorization: ЖДУ PAIR/B3, затем ANCS-READY; "
                + "это отдельные discovery gates · " + reason);
        if (gatt == null && !clientConnectInFlight && !gattClientConnected
                && nextClientAttempt == null) {
            startSamePeerAttach(false, "initial direct virtual open · " + reason);
        }
    }

    private boolean isVerifiedPeer(BluetoothDevice device) {
        return sameDevice(getVerifiedPeer(), device);
    }

    private static boolean sameDevice(BluetoothDevice first, BluetoothDevice second) {
        if (first == null || second == null) return false;
        if (first.equals(second)) return true;
        String firstAddress = safeAddress(first);
        String secondAddress = safeAddress(second);
        return !firstAddress.isEmpty()
                && firstAddress.equalsIgnoreCase(secondAddress);
    }

    private void handlePairCommand(BluetoothDevice device) {
        if (!isVerifiedPeer(device)) {
            log("PAIR callback проигнорирован: peer не совпадает с verified peer");
            return;
        }
        if (managedIncomingMode && isSelectedBondedIncomingDevice(device)) {
            adoptIncomingClientCandidate(device, "PAIR on exact incoming link");
        }
        state("VERIFIED PEER · CURRENT LINK CHALLENGE");
        log("PAIR принят. VERIFIED PEER: " + safeName(device)
                + " " + safeAddress(device)
                + " objectId=" + System.identityHashCode(device)
                + " type=" + typeLabel(safeType(device))
                + " bond=" + bondLabel(safeBondState(device)));
        if (safeBondState(device) == BluetoothDevice.BOND_BONDED) {
            log("PAIR: общий Classic/LE peer уже BOND_BONDED; первая B3 READ запросит "
                    + "security именно текущего LE link. Direct clientIf может уже ждать, "
                    + "но discovery заблокирован до ANCS-READY");
        } else {
            requestBond(device);
            log("connectGatt отложен до подтверждения текущего ATT link");
        }
    }

    /** Commits the current-link proof before ATT success is returned to Core Bluetooth. */
    @Nullable
    private Boolean markSecureAttConfirmed(BluetoothDevice device) {
        synchronized (verifiedPeerLock) {
            if (!sameDevice(verifiedPeer, device)) return null;
            boolean first = !secureAttConfirmed;
            secureAttConfirmed = true;
            return first;
        }
    }

    private void handleSecureAttSuccess(BluetoothDevice device, String operation) {
        Boolean first = markSecureAttConfirmed(device);
        if (first == null) {
            log("SECURE callback проигнорирован: это не verified peer");
            return;
        }
        finishSecureAttSuccess(device, operation, first);
    }

    private void finishSecureAttSuccess(BluetoothDevice device, String operation,
                                        boolean first) {
        if (!isVerifiedPeer(device)) {
            log("SECURE completion проигнорирован: verified session уже сменился");
            return;
        }
        if (managedIncomingMode) {
            managedResolvedPeer = device;
            listener.onVerifiedPeerAddress(safeAddress(device));
        }
        state("CURRENT LINK OK · SAME-PEER ATTACH");
        log("SECURE ATT OK · " + operation + " · peer=" + safeAddress(device)
                + (first ? " · current-link challenge confirmed" : " · повтор"));
        if (!first) {
            log("Повторный SECURE ATT OK не создаёт новую connectGatt-попытку");
            return;
        }
        if (findConnectedServerPeer(device) == null) {
            state("VERIFIED SERVER LINK LOST");
            log("Same-peer attach отменён: verified GATT-server link уже не активен");
            if (managedIncomingMode) {
                preserveManagedIncomingPublicationAfterLinkLoss(
                        "secure proof completed after server link loss");
            }
            return;
        }
        if (managedIncomingMode) {
            state("REQUIRES_ANCS LINK SECURE · ЖДУ HELPER READY");
            if (incomingClientCandidate == null && isSelectedBondedIncomingDevice(device)) {
                adoptIncomingClientCandidate(device, "B3 current-link proof");
            }
            log("Текущий ATT link прошёл B3; direct clientIf "
                    + (gattClientConnected ? "уже attached" : "подключается")
                    + ", discovery ждёт ANCS-READY без разрыва");
            return;
        }
        scheduleSecureClientStart();
    }

    private boolean canAcceptAncsReady(BluetoothDevice device) {
        return managedIncomingMode && secureAttConfirmed && isVerifiedPeer(device)
                && findConnectedServerPeer(device) != null;
    }

    /** Helper confirms that this one encrypted Central owner was opened with RequiresANCS. */
    private void confirmAncsReady(BluetoothDevice callbackDevice) {
        if (!canAcceptAncsReady(callbackDevice)) {
            log("ANCS-READY отклонён: нет защищённого exact incoming link");
            return;
        }
        GattServerPeer serverLink = findConnectedServerPeer(callbackDevice);
        BluetoothDevice exactIncomingDevice = serverLink.device;
        synchronized (verifiedPeerLock) {
            verifiedPeer = exactIncomingDevice;
        }
        managedResolvedPeer = exactIncomingDevice;
        listener.onVerifiedPeerAddress(safeAddress(exactIncomingDevice));
        incomingAncsReadyGateOpen = true;
        state("ONE REQUIRES_ANCS OWNER · CLIENT ATTACH");
        log("ANCS-READY принят без disconnect · exact incoming objectId="
                + System.identityHashCode(exactIncomingDevice)
                + " address=" + safeAddress(exactIncomingDevice)
                + "; discovery gate открыт");
        scheduleSecureClientStart();
    }

    private void scheduleSecureClientStart() {
        if (secureConnectStart != null || clientConnectInFlight) return;
        if (managedIncomingMode && nextClientAttempt != null) {
            log("ANCS-READY gate открыт; bounded direct retry уже запланирован");
            return;
        }
        BluetoothGatt current = gatt;
        if (current != null) {
            if (managedIncomingMode) {
                if (gattClientConnected && activeClientEstablished) {
                    maybeStartIncomingAncsDiscovery(current,
                            "ANCS-READY after direct client attach");
                } else if (activeClientEstablished) {
                    awaitIncomingBackgroundOwner(current, activeClientGeneration,
                            "ANCS-READY on previously established owner");
                } else {
                    log("ANCS-READY gate открыт; жду callback первичного direct clientIf");
                }
            } else if (gattClientConnected) {
                discoverServices(current);
            } else {
                awaitIncomingBackgroundOwner(current, activeClientGeneration,
                        "ANCS-READY на уже зарегистрированном owner");
            }
            return;
        }
        secureConnectStart = () -> {
            secureConnectStart = null;
            // AOSP/ESP-IDF use a direct GATT virtual open when adopting an already-connected
            // incoming peer. autoConnect=true only registers for a future advertiser and may
            // never emit a callback while this server-owned ACL is already alive.
            startSamePeerAttach(managedIncomingMode ? false : true,
                    "same-owner ANCS-READY + "
                    + SECURE_TO_CLIENT_CONNECT_DELAY_MS + " ms");
        };
        main.postDelayed(secureConnectStart, SECURE_TO_CLIENT_CONNECT_DELAY_MS);
        log("Same-peer ANCS client attach запланирован через "
                + SECURE_TO_CLIENT_CONNECT_DELAY_MS + " ms после ANCS-READY");
    }

    private void maybeStartIncomingAncsDiscovery(@NonNull BluetoothGatt expected,
                                                  @NonNull String reason) {
        if (!managedIncomingMode || expected != gatt || !secureAttConfirmed
                || !incomingAncsReadyGateOpen || !gattClientConnected
                || !activeClientEstablished) return;
        if (incomingDiscoveryStarted) {
            log("ANCS discovery уже стартовал на текущем direct clientIf · " + reason);
            return;
        }
        incomingDiscoveryStarted = true;
        state("DIRECT CLIENT ATTACHED + ANCS-READY · DISCOVERY");
        log("Оба независимых gate готовы: direct client attached + valid ANCS-READY · "
                + reason);
        discoverServices(expected);
    }

    private void startSamePeerAttach(boolean autoConnect, String reason) {
        if (!ensureAdapter()) return;
        if (managedIncomingMode) {
            if (autoConnect) {
                log("Reverse route отклоняет initial autoConnect=true: background open ждёт "
                        + "будущую рекламу и не adopts текущий server-owned ACL");
                return;
            }
            startIncomingDirectAttach(reason);
            return;
        }
        if (!secureAttConfirmed) {
            log("connectGatt не запущен: SECURE ATT ещё не подтверждён");
            return;
        }
        if (clientConnectInFlight || gattClientConnected || gatt != null) {
            log("connectGatt уже активен; новая попытка пропущена · " + reason);
            return;
        }
        BluetoothDevice verified = getVerifiedPeer();
        if (verified == null) {
            state("NO VERIFIED PEER");
            log("Same-peer attach отменён: verified peer отсутствует");
            return;
        }
        GattServerPeer serverLink = findConnectedServerPeer(verified);
        if (serverLink == null) {
            state("VERIFIED SERVER LINK LOST");
            log("Same-peer attach отменён: exact verified GATT-server link "
                    + safeAddress(verified) + " не активен");
            return;
        }
        // Do not resolve the address again through bonded-device aliases. Android 9 may return a
        // different BluetoothDevice wrapper for the same iPhone; connectGatt must use the exact
        // object delivered by this live GATT-server connection callback.
        BluetoothDevice device = serverLink.device;
        synchronized (verifiedPeerLock) {
            verifiedPeer = device;
        }
        if (autoConnect) {
            if (backgroundAttachAttempted) {
                log("Повтор autoConnect=true заблокирован");
                return;
            }
            backgroundAttachAttempted = true;
        } else {
            if (!backgroundAttachAttempted) {
                log("Direct fallback запрещён до единственной background attach-попытки");
                return;
            }
            if (directFallbackAttempted) {
                log("Повтор autoConnect=false заблокирован");
                return;
            }
            directFallbackAttempted = true;
        }
        clearAncsRuntime();
        gattClientConnected = false;
        activeClientTarget = device;
        activeClientAutoConnect = autoConnect;
        activeClientEstablished = false;
        clientConnectInFlight = true;
        activeClientGeneration = sessionState.begin(autoConnect
                ? AncsSessionStateMachine.Phase.BACKGROUND_CONNECT
                : AncsSessionStateMachine.Phase.DIRECT_CONNECT);
        long expectedGeneration = activeClientGeneration;
        String address = safeAddress(device);
        long linkAgeMs = Math.max(0L, android.os.SystemClock.elapsedRealtime()
                - serverLink.connectedAtElapsedMs);
        state(autoConnect
                ? "SAME-PEER ATTACH · BACKGROUND"
                : "SAME-PEER ATTACH · DIRECT FALLBACK");
        log("connectGatt(autoConnect=" + autoConnect + ", TRANSPORT_LE): "
                + safeName(device) + " " + address + " · " + reason
                + " · bond=" + bondLabel(safeBondState(device))
                + " · type=" + typeLabel(safeType(device))
                + " · objectId=" + System.identityHashCode(device)
                + " · verifiedServerLinkAgeMs=" + linkAgeMs
                + " · EXACT SAME VERIFIED BluetoothDevice");
        try {
            gatt = device.connectGatt(context, autoConnect, gattCallback,
                    BluetoothDevice.TRANSPORT_LE);
            if (gatt == null) {
                clientConnectInFlight = false;
                activeClientTarget = null;
                state("CONNECT_GATT_RETURNED_NULL");
                log("connectGatt вернул null");
                if (autoConnect) {
                    scheduleDirectFallback("connectGatt(autoConnect=true) returned null");
                } else {
                    state("V6 ATTEMPTS EXHAUSTED");
                }
            } else {
                BluetoothGatt expected = gatt;
                boolean expectedAutoConnect = autoConnect;
                connectTimeout = () -> {
                    if (gatt != expected || !clientConnectInFlight
                            || !sessionState.isCurrent(expectedGeneration)) return;
                    clientConnectInFlight = false;
                    state("CONNECT_TIMEOUT");
                    log("Нет callback успешного GATT-подключения за "
                            + CONNECT_TIMEOUT_MS + " ms · target="
                            + safeAddress(expected.getDevice())
                            + " autoConnect=" + expectedAutoConnect
                            + " transport=TRANSPORT_LE");
                    closeClientGatt(expected);
                    clearAncsRuntime();
                    if (expectedAutoConnect) {
                        scheduleDirectFallback("background attach timeout");
                    } else {
                        state("V6 ATTEMPTS EXHAUSTED");
                    }
                };
                main.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS);
            }
        } catch (RuntimeException failure) {
            clientConnectInFlight = false;
            activeClientTarget = null;
            state("CONNECT_EXCEPTION");
            log("connectGatt exception: " + failure);
            if (autoConnect) {
                scheduleDirectFallback("background attach exception");
            } else {
                state("V6 ATTEMPTS EXHAUSTED");
            }
        }
    }

    /**
     * Creates a clientIf directly on the exact bonded facade of the live incoming connection.
     * This mirrors the direct virtual-open used by production dual-role BLE implementations:
     * the registration may happen before application authorization, but discovery cannot.
     */
    private void startIncomingDirectAttach(@NonNull String reason) {
        if (closing || !managedReconnectEnabled || !managedIncomingMode) return;
        if (clientConnectInFlight || gattClientConnected || gatt != null) {
            log("Incoming direct clientIf уже активен; дубль пропущен · " + reason);
            return;
        }
        BluetoothDevice candidate = incomingClientCandidate;
        if (candidate == null || !isSelectedBondedIncomingDevice(candidate)) {
            state("REQUIRES_ANCS LINK · ЖДУ BONDED IDENTITY");
            log("Direct client attach отложен: exact bonded incoming facade отсутствует");
            return;
        }
        GattServerPeer serverLink = findConnectedServerPeer(candidate);
        if (serverLink == null) {
            preserveManagedIncomingPublicationAfterLinkLoss(
                    "exact incoming link missing before direct client attach");
            return;
        }
        if (incomingClientAttachAttempt >= INCOMING_CLIENT_ATTACH_MAX_ATTEMPTS) {
            state("SAME-PEER DIRECT ATTACH · LINK KEPT · RETRIES EXHAUSTED");
            log("Direct clientIf не attached после " + incomingClientAttachAttempt
                    + " попыток; GATT server/reconnect anchor остаётся опубликован");
            return;
        }

        // Always reuse the live callback object, never adapter.getRemoteDevice(savedAddress).
        BluetoothDevice device = serverLink.device;
        incomingClientCandidate = device;
        incomingClientAttachAttempt++;
        clearAncsRuntime();
        incomingDiscoveryStarted = false;
        gattClientConnected = false;
        activeClientTarget = device;
        activeClientAutoConnect = false;
        activeClientEstablished = false;
        clientConnectInFlight = true;
        activeClientGeneration = sessionState.begin(
                AncsSessionStateMachine.Phase.DIRECT_CONNECT);
        long expectedGeneration = activeClientGeneration;
        long linkAgeMs = Math.max(0L, SystemClock.elapsedRealtime()
                - serverLink.connectedAtElapsedMs);
        state("SAME-PEER DIRECT ATTACH #" + incomingClientAttachAttempt);
        log("connectGatt(autoConnect=false, TRANSPORT_LE): "
                + safeName(device) + " " + safeAddress(device)
                + " · exact bonded incoming objectId=" + System.identityHashCode(device)
                + " · serverLinkAgeMs=" + linkAgeMs
                + " · discoveryGate=" + incomingAncsReadyGateOpen
                + " · " + reason);
        try {
            BluetoothGatt created = device.connectGatt(context, false, gattCallback,
                    BluetoothDevice.TRANSPORT_LE);
            gatt = created;
            if (created == null) {
                clientConnectInFlight = false;
                activeClientTarget = null;
                state("SAME-PEER DIRECT ATTACH RETURNED NULL");
                scheduleIncomingClientAttachRetry("direct connectGatt returned null");
                return;
            }
            BluetoothGatt expected = created;
            connectTimeout = () -> {
                if (gatt != expected || !clientConnectInFlight
                        || activeClientEstablished
                        || !sessionState.is(expectedGeneration,
                        AncsSessionStateMachine.Phase.DIRECT_CONNECT)) return;
                log("Первичный direct clientIf не дал callback за "
                        + INCOMING_DIRECT_ATTACH_TIMEOUT_MS
                        + " ms; закрываю только never-established wrapper · target="
                        + safeAddress(expected.getDevice()));
                closeClientGatt(expected);
                clearAncsRuntime();
                incomingDiscoveryStarted = false;
                scheduleIncomingClientAttachRetry("direct attach callback timeout");
            };
            main.postDelayed(connectTimeout, INCOMING_DIRECT_ATTACH_TIMEOUT_MS);
        } catch (RuntimeException failure) {
            clientConnectInFlight = false;
            activeClientTarget = null;
            gatt = null;
            state("SAME-PEER DIRECT ATTACH EXCEPTION");
            log("Direct connectGatt exception: " + failure);
            scheduleIncomingClientAttachRetry("direct connectGatt exception");
        }
    }

    private void scheduleDirectFallback(String reason) {
        if (!secureAttConfirmed || !backgroundAttachAttempted || directFallbackAttempted
                || nextClientAttempt != null) return;
        nextClientAttempt = () -> {
            nextClientAttempt = null;
            startSamePeerAttach(false, "единственный fallback после " + reason);
        };
        main.postDelayed(nextClientAttempt, DIRECT_FALLBACK_DELAY_MS);
        log("Единственный direct fallback autoConnect=false через "
                + DIRECT_FALLBACK_DELAY_MS + " ms · " + reason);
    }

    /** Replaces only a never-established direct client wrapper, with a bounded attempt count. */
    private void scheduleIncomingClientAttachRetry(@NonNull String reason) {
        if (closing || !managedReconnectEnabled || !managedIncomingMode
                || nextClientAttempt != null) return;
        BluetoothDevice device = incomingClientCandidate;
        if (device == null || findConnectedServerPeer(device) == null) {
            preserveManagedIncomingPublicationAfterLinkLoss(
                    "client attach failed after physical link loss · " + reason);
            return;
        }
        if (incomingClientAttachAttempt >= INCOMING_CLIENT_ATTACH_MAX_ATTEMPTS) {
            state("SAME-PEER DIRECT ATTACH · LINK KEPT · RETRIES EXHAUSTED");
            log("Direct clientIf не attached после " + incomingClientAttachAttempt
                    + " попыток; Geely_ANCS server/link остаются активны · " + reason);
            return;
        }
        int nextAttempt = incomingClientAttachAttempt + 1;
        long delay = INCOMING_CLIENT_ATTACH_RETRY_MS * incomingClientAttachAttempt;
        nextClientAttempt = () -> {
            nextClientAttempt = null;
            if (closing || !managedIncomingMode) return;
            BluetoothDevice current = incomingClientCandidate;
            if (current == null || findConnectedServerPeer(current) == null) {
                preserveManagedIncomingPublicationAfterLinkLoss(
                        "incoming link disappeared before client retry");
                return;
            }
            startSamePeerAttach(false, "direct client attach #" + nextAttempt
                    + " after " + reason);
        };
        state("SAME-PEER DIRECT ATTACH · RETRY #" + nextAttempt + " · LINK KEPT");
        main.postDelayed(nextClientAttempt, delay);
        log("Direct client attach retry #" + nextAttempt + " через " + delay
                + " ms; GATT server не закрывается · " + reason);
    }

    /**
     * Recovers by phase: a never-established wrapper gets a bounded direct replacement, while an
     * owner that reached CONNECTED is retained and re-armed with {@link BluetoothGatt#connect()}.
     */
    private void recoverIncomingClientRole(@NonNull String reason) {
        if (closing || !managedIncomingMode) return;
        BluetoothDevice device = getVerifiedPeer();
        if (device == null || findConnectedServerPeer(device) == null) {
            preserveManagedIncomingPublicationAfterLinkLoss(
                    "client recovery observed physical link loss · " + reason);
            return;
        }
        BluetoothGatt owner = gatt;
        if (owner != null) {
            if (gattClientConnected && activeClientEstablished) {
                if (incomingAncsReadyGateOpen) {
                    restartDiscoveryOnPersistentOwner(owner, activeClientGeneration, reason);
                } else {
                    log("Established direct clientIf сохранён; recovery ждёт ANCS-READY · "
                            + reason);
                }
            } else if (activeClientEstablished) {
                awaitIncomingBackgroundOwner(owner, activeClientGeneration, reason);
            } else {
                closeClientGatt(owner);
                clearAncsRuntime();
                incomingDiscoveryStarted = false;
                scheduleIncomingClientAttachRetry(
                        "never-established direct owner recovery · " + reason);
            }
            return;
        }
        log("Android GATT clientIf отсутствует; создаю bounded direct owner · "
                + reason);
        scheduleIncomingClientAttachRetry(reason);
    }

    /** Keeps one incoming-route clientIf alive across status 133 and ordinary disconnects. */
    private void awaitIncomingBackgroundOwner(@NonNull BluetoothGatt expected,
                                              long expectedGeneration,
                                              @NonNull String reason) {
        if (closing || !managedIncomingMode || gatt != expected
                || !sessionState.isCurrent(expectedGeneration)) return;
        if (!activeClientEstablished) {
            log("gatt.connect() запрещён для never-established clientIf; "
                    + "закрываю только wrapper и планирую bounded direct retry · " + reason);
            closeClientGatt(expected);
            clearAncsRuntime();
            incomingDiscoveryStarted = false;
            scheduleIncomingClientAttachRetry(reason);
            return;
        }
        cancelConnectTimeout();
        cancelClientAttemptCallbacks();
        clearAncsRuntime();
        incomingDiscoveryStarted = false;
        gattClientConnected = false;
        clientConnectInFlight = true;
        activeClientAutoConnect = true;
        sessionState.move(expectedGeneration,
                AncsSessionStateMachine.Phase.BACKGROUND_CONNECT);
        state("SAME-PEER BACKGROUND OWNER · RETAINED");
        log("Повторно вооружаю тот же Android GATT owner; close/connectGatt не вызываются · "
                + reason);
        rearmPersistentGattOwner(expected, expectedGeneration, reason, true);
    }

    private void cancelClientAttemptCallbacks() {
        if (secureConnectStart != null) main.removeCallbacks(secureConnectStart);
        if (nextClientAttempt != null) main.removeCallbacks(nextClientAttempt);
        secureConnectStart = null;
        nextClientAttempt = null;
    }

    private void cancelColdBackgroundAttach() {
        if (coldBackgroundAttachTask != null) {
            main.removeCallbacks(coldBackgroundAttachTask);
        }
        coldBackgroundAttachTask = null;
    }

    private GattServerPeer findConnectedServerPeer(BluetoothDevice device) {
        synchronized (gattServerPeers) {
            for (GattServerPeer peer : gattServerPeers.values()) {
                if (peer.sessionGeneration == sessionGeneration
                        && peer.connected
                        && sameDevice(peer.device, device)) {
                    return peer;
                }
            }
        }
        return null;
    }

    private boolean hasConnectedServerPeer() {
        synchronized (gattServerPeers) {
            for (GattServerPeer peer : gattServerPeers.values()) {
                if (peer.sessionGeneration == sessionGeneration && peer.connected) return true;
            }
        }
        return false;
    }

    /**
     * Returns true exactly once for each physical incoming GATT link.  The first B3 read receives
     * ATT insufficient-authentication so Core Bluetooth can restore/start LE security even when
     * Android 9 incorrectly reports the shared Classic device as already BOND_BONDED.  B3 itself
     * deliberately has plain framework permissions: otherwise Fluoride rejects the request with
     * code 12 before this callback and the application can never break that stale-key loop.
     */
    private boolean issueCurrentLinkSecurityChallenge(BluetoothDevice device) {
        synchronized (gattServerPeers) {
            for (GattServerPeer peer : gattServerPeers.values()) {
                if (peer.sessionGeneration != sessionGeneration
                        || !peer.connected || !sameDevice(peer.device, device)) continue;
                if (peer.linkSecurityChallengeIssued) return false;
                peer.linkSecurityChallengeIssued = true;
                return true;
            }
        }
        // A read should follow the connection callback, but challenge an unexpectedly early Binder
        // request rather than accepting a link that has not reached the current session registry.
        return true;
    }

    private void recordGattServerPeer(BluetoothDevice device, int status, int newState) {
        if (device == null) return;
        long now = android.os.SystemClock.elapsedRealtime();
        String key = deviceKey(device);
        boolean preserveLogicalOwner = newState == BluetoothProfile.STATE_DISCONNECTED
                && exactClientRoleOwnsPhysicalLink(device);
        synchronized (gattServerPeers) {
            GattServerPeer peer = gattServerPeers.get(key);
            if (peer == null || peer.sessionGeneration != sessionGeneration) {
                peer = new GattServerPeer(sessionGeneration, device);
                gattServerPeers.put(key, peer);
            }
            peer.device = device;
            if (status == GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                if (!peer.connected) {
                    peer.connectedAtElapsedMs = now;
                    peer.linkSecurityChallengeIssued = false;
                }
                peer.connected = true;
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED
                    && !preserveLogicalOwner) {
                peer.connected = false;
                peer.linkSecurityChallengeIssued = false;
                peer.telemetrySubscribed = false;
            }
        }
        if (newState == BluetoothProfile.STATE_DISCONNECTED
                && !preserveLogicalOwner && !hasServerTelemetrySubscribers()) {
            cancelServerTelemetryWakePoll();
        }
        if (preserveLogicalOwner) {
            log("GATT-server facade disconnected during exact-device client attach; "
                    + "logical peer and B4 wake CCCD retained");
        }
    }

    private boolean hasServerTelemetrySubscribers() {
        synchronized (gattServerPeers) {
            for (GattServerPeer peer : gattServerPeers.values()) {
                if (peer.sessionGeneration == sessionGeneration
                        && peer.connected && peer.telemetrySubscribed) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isServerTelemetrySubscribed(BluetoothDevice device) {
        synchronized (gattServerPeers) {
            for (GattServerPeer peer : gattServerPeers.values()) {
                if (peer.sessionGeneration == sessionGeneration
                        && peer.connected && peer.telemetrySubscribed
                        && sameDevice(peer.device, device)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Android 9 may release only the GATT-server facade when the app registers its client role on
     * the exact same LE peer. The controller link is still owned by the client registration, so
     * dropping the peer/CCCD here would silently stop Helper background telemetry wake-ups.
     */
    private boolean exactClientRoleOwnsPhysicalLink(BluetoothDevice device) {
        return managedIncomingMode
                && incomingClientCandidate != null
                && sameDevice(incomingClientCandidate, device)
                && activeClientTarget != null
                && sameDevice(activeClientTarget, device)
                && gatt != null
                && (clientConnectInFlight || gattClientConnected || activeClientEstablished);
    }

    private void setServerTelemetrySubscription(BluetoothDevice device, boolean enabled) {
        boolean found = false;
        synchronized (gattServerPeers) {
            for (GattServerPeer peer : gattServerPeers.values()) {
                if (peer.sessionGeneration != sessionGeneration
                        || !peer.connected || !sameDevice(peer.device, device)) continue;
                peer.telemetrySubscribed = enabled;
                found = true;
                break;
            }
        }
        BluetoothGattCharacteristic telemetry = serverTelemetryCharacteristic;
        if (telemetry != null) {
            BluetoothGattDescriptor cccd = telemetry.getDescriptor(
                    AncsProtocol.CLIENT_CONFIGURATION);
            if (cccd != null) {
                cccd.setValue(enabled
                        ? BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        : BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE);
            }
        }
        log("B4 wake-poll subscription=" + enabled
                + " peer=" + safeAddress(device) + " registered=" + found);
        if (enabled && found) {
            scheduleServerTelemetryWakePoll(250L);
        } else if (!hasServerTelemetrySubscribers()) {
            cancelServerTelemetryWakePoll();
        }
    }

    private void cancelServerTelemetryWakePoll() {
        if (serverTelemetryWakePoll != null) {
            main.removeCallbacks(serverTelemetryWakePoll);
        }
        serverTelemetryWakePoll = null;
    }

    private void scheduleServerTelemetryWakePoll(long delayMs) {
        cancelServerTelemetryWakePoll();
        if (!hasServerTelemetrySubscribers()) return;
        serverTelemetryWakePoll = () -> {
            serverTelemetryWakePoll = null;
            sendServerTelemetryWakePoll();
        };
        main.postDelayed(serverTelemetryWakePoll, delayMs);
    }

    /**
     * The notification contains no phone data. Its only purpose is to give bluetooth-central a
     * Core Bluetooth event in the background; Helper then samples public iOS state and writes the
     * authenticated eight-byte B4 frame back with response on this same connection.
     */
    private void sendServerTelemetryWakePoll() {
        BluetoothGattServer server = gattServer;
        BluetoothGattCharacteristic telemetry = serverTelemetryCharacteristic;
        if (server == null || telemetry == null || !hasServerTelemetrySubscribers()) return;

        List<BluetoothDevice> targets = new ArrayList<>();
        synchronized (gattServerPeers) {
            for (GattServerPeer peer : gattServerPeers.values()) {
                if (peer.sessionGeneration == sessionGeneration
                        && peer.connected && peer.telemetrySubscribed) {
                    targets.add(peer.device);
                }
            }
        }
        telemetry.setValue(new byte[]{0x01});
        for (BluetoothDevice target : targets) {
            if (!isVerifiedPeer(target)) continue;
            boolean accepted;
            try {
                accepted = server.notifyCharacteristicChanged(target, telemetry, false);
            } catch (RuntimeException failure) {
                accepted = false;
                log("B4 wake-poll notify exception: " + failure);
            }
            if (!accepted) {
                log("B4 wake-poll notify rejected · peer=" + safeAddress(target));
            }
        }
        if (hasServerTelemetrySubscribers()) {
            scheduleServerTelemetryWakePoll(SERVER_TELEMETRY_WAKE_POLL_MS);
        }
    }

    private void handleVerifiedServerLinkDisconnected(BluetoothDevice device) {
        boolean samePhysicalLinkClientOwner = exactClientRoleOwnsPhysicalLink(device);
        if (samePhysicalLinkClientOwner) {
            state("SAME PHYSICAL LINK · ANCS CLIENT PRESERVED");
            log("GATT-server callback released after exact-device client registration: "
                    + safeAddress(device)
                    + "; Android ANCS client remains owner"
                    + " connected=" + gattClientConnected
                    + " inFlight=" + clientConnectInFlight);
            // Registering Android's GATT-client role on the exact incoming ATT peer can make the
            // Android 9 server API report STATE_DISCONNECTED even though the client callback is
            // already connected (or queued). Closing that client here created the v17 loop.
            // Its own bounded callback/timeout now owns success or recovery.
            return;
        }
        cancelClientAttemptCallbacks();
        state(managedReconnectEnabled
                ? REMOTE_LOGICAL_NAME + " · INCOMING LINK LOST"
                : "VERIFIED SERVER LINK DISCONNECTED");
        log("VERIFIED GATT SERVER LINK disconnected: " + safeAddress(device)
                + "; pending same-peer client attach остановлен");
        if (activeClientTarget != null && sameDevice(activeClientTarget, device)) {
            BluetoothGatt current = gatt;
            gatt = null;
            gattClientConnected = false;
            clientConnectInFlight = false;
            activeClientTarget = null;
            clearAncsRuntime();
            if (current != null) {
                try {
                    current.disconnect();
                } catch (RuntimeException ignored) {
                }
                try {
                    current.close();
                } catch (RuntimeException ignored) {
                }
            }
        }
        if (managedReconnectEnabled) {
            preserveManagedIncomingPublicationAfterLinkLoss("server callback disconnect");
        }
    }

    /** Clears only per-link state; the published service identity remains stable for reconnect. */
    private void preserveManagedIncomingPublicationAfterLinkLoss(@NonNull String reason) {
        if (!managedIncomingMode) return;
        cancelConnectTimeout();
        cancelClientAttemptCallbacks();
        clearAncsRuntime();
        BluetoothGatt oldClient = gatt;
        gatt = null;
        gattClientConnected = false;
        clientConnectInFlight = false;
        activeClientTarget = null;
        activeClientAutoConnect = false;
        activeClientEstablished = false;
        if (oldClient != null) {
            try {
                oldClient.close();
            } catch (RuntimeException ignored) {
            }
        }
        resetVerifiedPeerSession();
        managedIncomingMode = true;
        state(LOCAL_LOGICAL_NAME + " · ADVERTISING · ЖДУ RECONNECT");
        log("Обычный разрыв: GATT server, реклама и namespace "
                + String.format(Locale.US, "%04X", serverDiagnosticGeneration)
                + " сохранены; новый Central link пройдёт PAIR/B3 заново · " + reason);
    }

    private static String deviceKey(BluetoothDevice device) {
        String address = safeAddress(device);
        return address.isEmpty()
                ? "identity:" + System.identityHashCode(device)
                : "address:" + address.toUpperCase(Locale.US);
    }

    private void openGattServer() {
        if (gattServer != null) return;
        try {
            gattServer = manager.openGattServer(context, gattServerCallback);
        } catch (RuntimeException failure) {
            log("openGattServer exception: " + failure);
            gattServer = null;
        }
        if (gattServer == null) {
            state("GATT_SERVER_UNAVAILABLE");
            log("openGattServer вернул null");
            return;
        }
        BluetoothGattService service = new BluetoothGattService(
                serverDiagnosticService, BluetoothGattService.SERVICE_TYPE_PRIMARY);
        BluetoothGattCharacteristic information = new BluetoothGattCharacteristic(
                serverDiagnosticCharacteristic,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ);
        information.setValue((LOCAL_LOGICAL_NAME + "/3")
                .getBytes(StandardCharsets.UTF_8));

        BluetoothGattCharacteristic control = new BluetoothGattCharacteristic(
                serverControlCharacteristic,
                BluetoothGattCharacteristic.PROPERTY_WRITE
                        | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_WRITE);

        BluetoothGattCharacteristic secure = new BluetoothGattCharacteristic(
                serverSecureCharacteristic,
                BluetoothGattCharacteristic.PROPERTY_READ
                        | BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_READ
                        | BluetoothGattCharacteristic.PERMISSION_WRITE);
        secure.setValue("SECURE ATT OK".getBytes(StandardCharsets.UTF_8));

        BluetoothGattCharacteristic telemetry = new BluetoothGattCharacteristic(
                serverTelemetryCharacteristicUuid,
                BluetoothGattCharacteristic.PROPERTY_READ
                        | BluetoothGattCharacteristic.PROPERTY_WRITE
                        | BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ
                        | BluetoothGattCharacteristic.PERMISSION_WRITE);
        telemetry.setValue("TEL3;-;-;X;-;0".getBytes(StandardCharsets.UTF_8));
        BluetoothGattDescriptor telemetryCccd = new BluetoothGattDescriptor(
                AncsProtocol.CLIENT_CONFIGURATION,
                BluetoothGattDescriptor.PERMISSION_READ
                        | BluetoothGattDescriptor.PERMISSION_WRITE);
        telemetryCccd.setValue(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE);
        boolean telemetryDescriptorAdded = telemetry.addDescriptor(telemetryCccd);
        serverTelemetryCharacteristic = telemetry;

        boolean informationAdded = service.addCharacteristic(information);
        boolean controlAdded = service.addCharacteristic(control);
        boolean secureAdded = service.addCharacteristic(secure);
        boolean telemetryAdded = service.addCharacteristic(telemetry);
        log("Diagnostic characteristics: INFO=" + informationAdded
                + " CONTROL=" + controlAdded + " SECURE=" + secureAdded
                + " TELEMETRY=" + telemetryAdded
                + " TELEMETRY_CCCD=" + telemetryDescriptorAdded);
        if (!informationAdded || !controlAdded || !secureAdded || !telemetryAdded
                || !telemetryDescriptorAdded) {
            state("GATT_CHARACTERISTIC_ADD_FAILED");
            advertisingDesired = false;
            clearPreparedAdvertising();
            closeGattServer();
            return;
        }
        boolean accepted = gattServer.addService(service);
        log("GATT server открыт; add diagnostic service=" + accepted);
        if (!accepted) {
            state("GATT_SERVICE_ADD_START_FAILED");
            advertisingDesired = false;
            clearPreparedAdvertising();
            closeGattServer();
        } else {
            state("ЖДУ ДОБАВЛЕНИЯ GATT SERVICE");
        }
    }

    private void startPreparedAdvertising() {
        if (!advertisingDesired || advertiser == null
                || preparedAdvertiseSettings == null
                || preparedAdvertiseData == null
                || preparedScanResponse == null) {
            log("Запуск рекламы отменён: состояние уже изменилось");
            return;
        }
        advertisingPending = true;
        try {
            advertiser.startAdvertising(preparedAdvertiseSettings, preparedAdvertiseData,
                    preparedScanResponse, advertiseCallback);
            state(solicitationAdvertising
                    ? "SOLICITATION REQUESTED · ЗАПУСК РЕКЛАМЫ"
                    : "ЗАПУСК DIAGNOSTIC-РЕКЛАМЫ");
        } catch (RuntimeException failure) {
            advertisingPending = false;
            advertisingDesired = false;
            state("ADVERTISE_EXCEPTION");
            log("startAdvertising exception: " + failure);
            clearPreparedAdvertising();
            closeGattServer();
        }
    }

    private void clearPreparedAdvertising() {
        preparedAdvertiseSettings = null;
        preparedAdvertiseData = null;
        preparedScanResponse = null;
    }

    private void closeGattServer() {
        cancelServerTelemetryWakePoll();
        serverTelemetryCharacteristic = null;
        synchronized (gattServerPeers) {
            for (GattServerPeer peer : gattServerPeers.values()) {
                peer.telemetrySubscribed = false;
            }
        }
        BluetoothGattServer old = gattServer;
        gattServer = null;
        if (old != null) {
            try {
                old.close();
            } catch (RuntimeException ignored) {
            }
        }
    }

    private void discoverServices(BluetoothGatt callbackGatt) {
        if (callbackGatt != gatt) return;
        if (managedIncomingMode && !incomingAncsReadyGateOpen) {
            log("discoverServices заблокирован: direct clientIf может быть attached, "
                    + "но valid ANCS-READY ещё не получен");
            return;
        }
        long expectedGeneration = activeClientGeneration;
        if (!sessionState.isCurrent(expectedGeneration)) return;
        if (discoveryPending) {
            log("discoverServices уже выполняется");
            return;
        }
        discoveryPending = true;
        boolean accepted;
        try {
            accepted = callbackGatt.discoverServices();
        } catch (RuntimeException failure) {
            accepted = false;
            log("discoverServices exception: " + failure);
        }
        if (!accepted) {
            discoveryPending = false;
            state("DISCOVERY_START_FAILED");
            return;
        }
        sessionState.move(expectedGeneration, AncsSessionStateMachine.Phase.DISCOVERING);
        BluetoothGatt expected = callbackGatt;
        discoveryTimeout = () -> {
            if (gatt != expected || !discoveryPending
                    || !sessionState.isCurrent(expectedGeneration)) return;
            discoveryPending = false;
            state("DISCOVERY_TIMEOUT");
            log("onServicesDiscovered не получен за "
                    + DISCOVERY_TIMEOUT_MS + " ms");
        };
        main.postDelayed(discoveryTimeout, DISCOVERY_TIMEOUT_MS);
        state("GATT DISCOVERY");
        log("discoverServices accepted=" + accepted);
    }

    /**
     * Performs a tiny encrypted exchange with the iPhone app before looking for ANCS. This both
     * identifies the peer and gives SMP a reason to create/restore the LE bond on the exact link
     * that Android opened as central.
     */
    private boolean startIphonePeripheralSecurity(BluetoothGatt callbackGatt) {
        BluetoothGattService diagnostic = callbackGatt.getService(DIAGNOSTIC_SERVICE);
        if (diagnostic == null) {
            state("GPS-LINK · TEST SERVICE НЕ НАЙДЕН");
            log("Подключение состоялось, но service " + DIAGNOSTIC_SERVICE
                    + " отсутствует. Убедитесь, что запущен Helper v4");
            return true;
        }

        iphoneSecureCharacteristic =
                diagnostic.getCharacteristic(SECURE_CHARACTERISTIC);
        BluetoothGattCharacteristic pair =
                diagnostic.getCharacteristic(CONTROL_CHARACTERISTIC);
        if (iphoneSecureCharacteristic == null) {
            state("GPS-LINK · SECURE CHAR НЕ НАЙДЕН");
            log("Helper не опубликовал SECURE " + SECURE_CHARACTERISTIC);
            return true;
        }
        if (iphonePairWritePending || iphoneSecureReadPending) return true;

        if (!iphonePairAttempted && pair != null) {
            iphonePairAttempted = true;
            pair.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            pair.setValue("PAIR".getBytes(StandardCharsets.UTF_8));
            boolean started;
            try {
                started = callbackGatt.writeCharacteristic(pair);
            } catch (RuntimeException failure) {
                started = false;
                log("GPS-style PAIR write exception: " + failure);
            }
            iphonePairWritePending = started;
            log("GPS-style WRITE PAIR started=" + started);
            if (started) {
                state("GPS-LINK · ПОДТВЕРЖДАЮ IPHONE");
                return true;
            }
        }

        readIphoneSecure(callbackGatt);
        return true;
    }

    private void readIphoneSecure(BluetoothGatt callbackGatt) {
        if (!iphonePeripheralMode || callbackGatt != gatt
                || !gattClientConnected || iphoneSecureReadPending
                || iphoneSecureConfirmed || iphoneSecureCharacteristic == null) {
            return;
        }
        boolean started;
        try {
            started = callbackGatt.readCharacteristic(iphoneSecureCharacteristic);
        } catch (RuntimeException failure) {
            started = false;
            log("GPS-style SECURE read exception: " + failure);
        }
        iphoneSecureReadPending = started;
        log("GPS-style READ SECURE started=" + started
                + " bond=" + bondLabel(safeBondState(callbackGatt.getDevice())));
        if (started) {
            state("GPS-LINK · ПРОВЕРЯЮ ШИФРОВАНИЕ");
        } else {
            state("GPS-LINK · SECURE READ START FAILED");
        }
    }

    private void scheduleIphonePostSecureDiscovery(BluetoothGatt callbackGatt) {
        if (iphonePostSecureDiscoveryScheduled) return;
        iphonePostSecureDiscoveryScheduled = true;
        main.postDelayed(() -> {
            if (!iphonePeripheralMode || callbackGatt != gatt || !gattClientConnected) return;
            log("SECURE IPHONE OK; повторяю полный discovery и ищу ANCS 7905…");
            discoverServices(callbackGatt);
        }, GPS_POST_SECURE_DISCOVERY_DELAY_MS);
    }

    private void scheduleAutoAncsWaitTimeout(BluetoothGatt expected) {
        if (helperBootstrapMode || expected == null || expected != gatt
                || autoAncsWaitTimeout != null) {
            return;
        }
        autoAncsWaitTimeout = () -> {
            autoAncsWaitTimeout = null;
            if (helperBootstrapMode || expected != gatt || !gattClientConnected || gattReady) {
                return;
            }
            state("AUTO LINK OK · ANCS REDISCOVERY");
            log("ANCS/Service Changed пока не опубликованы за "
                    + AUTO_ANCS_WAIT_TIMEOUT_MS
                    + " ms; сохраняю живой encrypted link и повторяю discovery");
            discoverServices(expected);
        };
        main.postDelayed(autoAncsWaitTimeout, AUTO_ANCS_WAIT_TIMEOUT_MS);
        log("ANCS wait watchdog=" + AUTO_ANCS_WAIT_TIMEOUT_MS
                + " ms; Helper fallback внутри active daily link не запускается");
    }

    private void cancelAutoAncsWaitTimeout() {
        if (autoAncsWaitTimeout != null) main.removeCallbacks(autoAncsWaitTimeout);
        autoAncsWaitTimeout = null;
    }

    private void handleServices(BluetoothGatt callbackGatt, int status) {
        if (callbackGatt != gatt) return;
        cancelDiscoveryTimeout();
        discoveryPending = false;
        log("onServicesDiscovered status=" + status);
        if (status != GATT_SUCCESS) {
            state("DISCOVERY_FAILED_" + status);
            return;
        }
        List<BluetoothGattService> services = callbackGatt.getServices();
        log("GATT services: " + services.size());
        for (BluetoothGattService service : services) {
            log("SERVICE " + service.getUuid());
            for (BluetoothGattCharacteristic characteristic : service.getCharacteristics()) {
                log("  CHAR " + characteristic.getUuid()
                        + " props=0x" + Integer.toHexString(characteristic.getProperties()));
            }
        }
        if (helperTelemetryClientEnabled()) {
            BluetoothGattService relay = callbackGatt.getService(TELEMETRY_RELAY_SERVICE);
            BluetoothGattService helper = callbackGatt.getService(DIAGNOSTIC_SERVICE);
            iphoneSecureCharacteristic = iphonePeripheralMode && helper != null
                    ? helper.getCharacteristic(SECURE_CHARACTERISTIC) : null;
            BluetoothGattCharacteristic discoveredTelemetry = relay == null ? null
                    : relay.getCharacteristic(TELEMETRY_RELAY_CHARACTERISTIC);
            if (discoveredTelemetry == null && helper != null) {
                discoveredTelemetry = helper.getCharacteristic(TELEMETRY_CHARACTERISTIC);
            }
            UUID previousTelemetryUuid = iphoneTelemetryCharacteristic == null ? null
                    : iphoneTelemetryCharacteristic.getUuid();
            UUID discoveredTelemetryUuid = discoveredTelemetry == null ? null
                    : discoveredTelemetry.getUuid();
            if (!Objects.equals(previousTelemetryUuid, discoveredTelemetryUuid)) {
                iphoneHelperTelemetrySubscriptionAttempted = false;
                iphoneHelperTelemetrySubscribed = false;
            }
            iphoneTelemetryCharacteristic = discoveredTelemetry;
            log("Helper telemetry endpoint="
                    + (TELEMETRY_RELAY_CHARACTERISTIC.equals(discoveredTelemetryUuid)
                    ? "B4 relay generation 5 on ANCS owner"
                    : iphoneTelemetryCharacteristic != null
                    ? "bootstrap B4 generation 4" : "legacy TEL2/B3"));

            // Helper v9+ deliberately makes B4 readable before ANCS authorization. Read one
            // atomic snapshot before touching an encrypted ANCS CCCD: otherwise a pending
            // pairing callback can occupy Android's serialized GATT queue for up to 90 seconds
            // and make battery/network appear absent despite a healthy Helper service.
            if (iphoneTelemetryCharacteristic != null
                    && !iphoneHelperInitialReadAttempted && !gattReady) {
                iphoneHelperInitialReadAttempted = true;
                iphoneServiceSetupDeferredForHelperRead = true;
                if (startHelperTelemetryRead(callbackGatt)) {
                    log("Helper B4 initial snapshot started before ANCS subscriptions");
                    return;
                }
                iphoneServiceSetupDeferredForHelperRead = false;
                log("Helper B4 initial snapshot could not start; continuing ANCS setup");
            }

            // Enable the unencrypted B4 notification before any encrypted ANCS CCCD can occupy
            // Android 9's serialized GATT queue. Battery percentage, cable state and radio type
            // therefore stay live even while the first ANCS authorization is still pending.
            if (iphoneTelemetryCharacteristic != null
                    && iphonePeripheralMode
                    && !iphoneHelperTelemetrySubscribed
                    && !iphoneHelperTelemetrySubscriptionAttempted
                    && descriptorStage == DescriptorStage.NONE && !gattReady) {
                if (startOptionalHelperTelemetrySubscription(callbackGatt)) {
                    log("Helper B4 realtime subscription started before ANCS subscriptions");
                    return;
                }
            }
        }
        // HA1122 exposed BAS even while iOS had not published ANCS yet. Prepare the optional
        // battery work immediately, then serialize it behind any Service Changed/ANCS CCCD.
        prepareBatteryBootstrap(callbackGatt);

        BluetoothGattService ancs = callbackGatt.getService(AncsProtocol.SERVICE);
        if (ancs != null) {
            cancelAutoAncsWaitTimeout();
            iphoneAncsSeen = true;
            if (gattReady) {
                log("ANCS уже READY; проверяю появившийся Helper TEL3 без перезапуска ANCS");
                if (!startOptionalHelperTelemetrySubscription(callbackGatt)) {
                    scheduleHelperTelemetryRecovery(callbackGatt,
                            HELPER_TELEMETRY_BUSY_RETRY_MS);
                    sendNextRequest();
                }
                return;
            }
            if (descriptorStage != DescriptorStage.NONE) {
                log("ANCS-подписка уже выполняется: " + descriptorStage);
                return;
            }
            if (iphonePeripheralMode && !iphoneSecureConfirmed) {
                state("ANCS НАЙДЕН · SECURE TEST ПРОПУЩЕН");
                log("ANCS 7905… опубликован уже в первом discovery. "
                        + "PAIR/SECURE D2D…B3 не выполняются");
            }

            notificationSource = ancs.getCharacteristic(AncsProtocol.NOTIFICATION_SOURCE);
            dataSource = ancs.getCharacteristic(AncsProtocol.DATA_SOURCE);
            controlPoint = ancs.getCharacteristic(AncsProtocol.CONTROL_POINT);
            if (notificationSource == null || dataSource == null || controlPoint == null) {
                state("ANCS_INCOMPLETE");
                log("ANCS найден, но обязательные для теста характеристики отсутствуют"
                        + " NS=" + (notificationSource != null)
                        + " DS=" + (dataSource != null)
                        + " CP=" + (controlPoint != null));
                return;
            }
            earlyNotificationSourceFrames.clear();
            state("ANCS-FIRST · ПОДПИСКА NOTIFICATION SOURCE");
            log("ANCS найден. Сначала включаю обязательную Notification Source, "
                    + "затем Data Source; ранние события буферизуются");
            descriptorStage = DescriptorStage.NOTIFICATION_SOURCE;
            sessionState.move(activeClientGeneration,
                    AncsSessionStateMachine.Phase.SUBSCRIBING);
            if (!subscribe(callbackGatt, notificationSource, false)) {
                descriptorStage = DescriptorStage.NONE;
            }
            return;
        }

        if (iphonePeripheralMode && helperBootstrapMode && !iphoneSecureConfirmed) {
            log("ANCS в первом discovery отсутствует; только теперь запускаю "
                    + "fallback SECURE test Helper");
            startIphonePeripheralSecurity(callbackGatt);
            return;
        }

        if (iphonePeripheralMode && !helperBootstrapMode) {
            state("AUTO LINK OK · ЖДУ SERVICE CHANGED / ANCS");
            log("Helper B4 уже доступен на daily link; ANCS может появиться позднее через "
                    + "Service Changed. PAIR/SECURE bootstrap не запускаю");
            scheduleAutoAncsWaitTimeout(callbackGatt);
        } else {
            state(iphonePeripheralMode
                    ? "GPS-LINK OK · ANCS НЕ ОПУБЛИКОВАН"
                    : "CONNECTED · ANCS НЕ НАЙДЕН");
        }
        log("Сервис ANCS 7905… отсутствует на этом BLE link"
                + (iphonePeripheralMode
                ? " после прямого Android-central подключения"
                : ""));
        // ANCS may be published only after the current ACL becomes encrypted. Service Changed is
        // the protocol signal for that transition; polling discoverServices once per second only
        // re-reads Android 9's same cache and can overwrite the useful beginning of diagnostics.
        subscribeServiceChangedIfAvailable(callbackGatt);
        sendNextRequest();
    }

    private void subscribeServiceChangedIfAvailable(BluetoothGatt callbackGatt) {
        BluetoothGattService generic = callbackGatt.getService(GENERIC_ATTRIBUTE_SERVICE);
        serviceChanged = generic == null ? null : generic.getCharacteristic(SERVICE_CHANGED);
        if (serviceChanged == null) {
            log("Service Changed 0x2A05 отсутствует; остаюсь ждать ANCS");
            state(iphonePeripheralMode && !helperBootstrapMode
                    ? "AUTO LINK OK · ANCS/2A05 ПОКА НЕТ"
                    : iphonePeripheralMode
                    ? "GPS-LINK OK · ANCS/2A05 НЕТ"
                    : "ЖДУ ANCS НА SAME-PEER LINK");
            return;
        }
        descriptorStage = DescriptorStage.SERVICE_CHANGED;
        if (!subscribe(callbackGatt, serviceChanged, true)) {
            descriptorStage = DescriptorStage.NONE;
            state(iphonePeripheralMode && !helperBootstrapMode
                    ? "AUTO LINK OK · ЖДУ ANCS"
                    : iphonePeripheralMode
                    ? "GPS-LINK OK · ANCS НЕ ОПУБЛИКОВАН"
                    : "ЖДУ ANCS НА SAME-PEER LINK");
        }
    }

    private boolean subscribe(BluetoothGatt callbackGatt,
                              BluetoothGattCharacteristic characteristic,
                              boolean indication) {
        boolean optional = descriptorStage == DescriptorStage.SERVICE_CHANGED;
        optional = optional || descriptorStage == DescriptorStage.HELPER_TELEMETRY;
        String optionalName = descriptorStage == DescriptorStage.HELPER_TELEMETRY
                ? "Helper TEL3" : "Service Changed";
        boolean local;
        try {
            local = callbackGatt.setCharacteristicNotification(characteristic, true);
        } catch (RuntimeException failure) {
            descriptorStage = DescriptorStage.NONE;
            if (optional) {
                log("Optional " + optionalName
                        + " local subscription exception: " + failure);
            } else {
                state("SUBSCRIBE_EXCEPTION");
                log("setCharacteristicNotification exception: " + failure);
            }
            return false;
        }
        BluetoothGattDescriptor cccd =
                characteristic.getDescriptor(AncsProtocol.CLIENT_CONFIGURATION);
        log("setCharacteristicNotification " + shortUuid(characteristic.getUuid())
                + "=" + local + "; CCCD=" + (cccd != null));
        if (!local || cccd == null) {
            if (optional) {
                descriptorStage = DescriptorStage.NONE;
                log("Optional " + optionalName
                        + " unavailable locally; ANCS link remains alive");
            } else {
                state("SUBSCRIBE_LOCAL_FAILED");
            }
            return false;
        }
        cccd.setValue(indication
                ? BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                : BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
        boolean started;
        try {
            started = callbackGatt.writeDescriptor(cccd);
        } catch (RuntimeException failure) {
            descriptorStage = DescriptorStage.NONE;
            if (optional) {
                log("Optional " + optionalName + " CCCD exception: " + failure);
            } else {
                state("CCCD_WRITE_EXCEPTION");
                log("writeDescriptor exception: " + failure);
            }
            return false;
        }
        log("writeDescriptor " + shortUuid(characteristic.getUuid())
                + " started=" + started);
        if (!started) {
            descriptorStage = DescriptorStage.NONE;
            if (optional) {
                log("Optional " + optionalName
                        + " CCCD was rejected; ANCS link remains alive");
            } else {
                state("CCCD_START_FAILED");
            }
        } else {
            scheduleDescriptorWriteTimeout(callbackGatt, descriptorStage,
                    characteristic.getUuid());
        }
        return started;
    }

    private void handleDescriptorWrite(BluetoothGatt callbackGatt,
                                       BluetoothGattDescriptor descriptor, int status) {
        if (callbackGatt != gatt) return;
        UUID characteristicUuid = descriptor.getCharacteristic() == null
                ? null : descriptor.getCharacteristic().getUuid();
        if (!descriptorMatchesStage(descriptorStage, characteristicUuid)) {
            log("Игнорирую устаревший onDescriptorWrite "
                    + shortUuid(characteristicUuid) + " stage=" + descriptorStage);
            return;
        }
        cancelDescriptorWriteTimeout();
        log("onDescriptorWrite " + shortUuid(characteristicUuid)
                + " status=" + status + " stage=" + descriptorStage);
        if (isBatteryDescriptorStage(descriptorStage)) {
            DescriptorStage completedStage = descriptorStage;
            descriptorStage = DescriptorStage.NONE;
            if (status == GATT_SUCCESS) {
                log("BAS notification subscription enabled · " + completedStage);
            } else {
                log("BAS optional CCCD skipped · " + completedStage
                        + " status=" + status);
            }
            sendNextRequest();
            return;
        }
        if (descriptorStage == DescriptorStage.HELPER_TELEMETRY) {
            descriptorStage = DescriptorStage.NONE;
            iphoneHelperTelemetrySubscribed = status == GATT_SUCCESS;
            if (status != GATT_SUCCESS) {
                iphoneHelperTelemetrySubscriptionAttempted = false;
            }
            log(status == GATT_SUCCESS
                    ? "Helper telemetry notification subscription enabled"
                    : "Helper telemetry optional CCCD skipped · status=" + status);
            continueAfterHelperTelemetrySubscription(callbackGatt);
            return;
        }
        if (status != GATT_SUCCESS) {
            DescriptorStage failedStage = descriptorStage;
            descriptorStage = DescriptorStage.NONE;
            if (failedStage == DescriptorStage.SERVICE_CHANGED) {
                log("Optional Service Changed CCCD skipped, status=" + status
                        + "; mandatory ANCS path remains active");
                state(iphonePeripheralMode && !helperBootstrapMode
                        ? "AUTO LINK OK · ЖДУ ANCS"
                        : "ЖДУ ANCS БЕЗ SERVICE CHANGED");
                sendNextRequest();
                return;
            }
            boolean iphonePermissionDenied =
                    failedStage == DescriptorStage.NOTIFICATION_SOURCE
                            && status == STATUS_WRITE_NOT_PERMITTED;
            if (iphonePermissionDenied) {
                ancsAuthorizationFailureSeen = true;
                state("ANCS НЕ РАЗРЕШЕН · ВКЛЮЧИТЕ УВЕДОМЛЕНИЯ НА IPHONE");
                log("Notification Source CCCD отклонён ATT status=3: "
                        + "iOS не разрешил ANCS этому RequiresANCS owner. "
                        + "Физический link и BluetoothGatt owner сохраняются");
                scheduleAncsPermissionRetry(callbackGatt);
                return;
            }
            if (isAuthorizationError(status)) {
                ancsRetryAfterBond = true;
                ancsAuthorizationFailureSeen = true;
                int bondState = safeBondState(callbackGatt.getDevice());
                state("ANCS AUTH FAIL 0x"
                        + Integer.toHexString(status).toUpperCase(Locale.US)
                        + " · НУЖЕН LE BOND");
                log("ANCS CCCD " + failedStage + " требует authorization; status="
                        + status + " (0x"
                        + Integer.toHexString(status).toUpperCase(Locale.US)
                        + "), bond=" + bondLabel(bondState));
                if (bondState == BluetoothDevice.BOND_BONDED) {
                    scheduleAncsRetryAfterBond(callbackGatt,
                            "CCCD вернул auth error уже после BOND_BONDED");
                } else if (!leBondAttemptObserved) {
                    log("Системный стек ещё не сообщил BONDING; "
                            + "запускаю одну явную LE bond-попытку");
                    requestBond(callbackGatt.getDevice());
                } else {
                    log("LE bond уже запускался; автоматический цикл pairing не повторяю");
                }
            } else {
                state("CCCD_FAILED_" + status);
            }
            return;
        }

        if (descriptorStage == DescriptorStage.SERVICE_CHANGED) {
            descriptorStage = DescriptorStage.NONE;
            log("Service Changed indication включена");
            state(iphonePeripheralMode && !helperBootstrapMode
                    ? "AUTO LINK OK · ЖДУ SERVICE CHANGED / ANCS"
                    : iphonePeripheralMode
                    ? "GPS-LINK OK · ЖДУ SERVICE CHANGED"
                    : "ЖДУ SERVICE CHANGED / ANCS");
            sendNextRequest();
        } else if (descriptorStage == DescriptorStage.NOTIFICATION_SOURCE) {
            state("NOTIFICATION SOURCE OK · ПОДПИСКА DATA SOURCE");
            log("Notification Source CCCD включён; сериализованно включаю Data Source");
            descriptorStage = DescriptorStage.DATA_SOURCE;
            main.postDelayed(() -> {
                if (callbackGatt != gatt || !gattClientConnected
                        || descriptorStage != DescriptorStage.DATA_SOURCE) return;
                if (!subscribe(callbackGatt, dataSource, false)) {
                    descriptorStage = DescriptorStage.NONE;
                }
            }, ANCS_SECOND_CCCD_DELAY_MS);
        } else if (descriptorStage == DescriptorStage.DATA_SOURCE) {
            descriptorStage = DescriptorStage.NONE;
            gattReady = true;
            ancsPermissionRetryCount = 0;
            if (ancsPermissionRetry != null) {
                main.removeCallbacks(ancsPermissionRetry);
                ancsPermissionRetry = null;
            }
            sessionState.move(activeClientGeneration, AncsSessionStateMachine.Phase.READY);
            cancelAutoAncsWaitTimeout();
            flushEarlyNotificationSourceFrames();
            state(managedIncomingMode
                    ? "ANCS CCCD OK · ЖДУ B4 ДАННЫЕ"
                    : "ANCS READY · ОТПРАВЬТЕ УВЕДОМЛЕНИЕ");
            if (!startOptionalHelperTelemetrySubscription(callbackGatt)) {
                finishAncsReadySetup(callbackGatt);
            }
        }
    }

    private void flushEarlyNotificationSourceFrames() {
        while (gattReady && !earlyNotificationSourceFrames.isEmpty()) {
            byte[] frame = earlyNotificationSourceFrames.pollFirst();
            if (frame != null) handleNotificationSource(frame);
        }
    }

    private void handleCharacteristicChanged(BluetoothGatt callbackGatt,
                                             BluetoothGattCharacteristic characteristic) {
        if (callbackGatt != gatt) return;
        byte[] value = characteristic.getValue();
        UUID uuid = characteristic.getUuid();
        log("onCharacteristicChanged " + shortUuid(uuid)
                + " bytes=" + AdvertisementParser.hex(value, 80));
        if (BATTERY_LEVEL.equals(uuid) || BATTERY_LEVEL_STATUS.equals(uuid)) {
            if (gattClientConnected && value != null) {
                listener.onBatteryCharacteristic(uuid, value.clone());
            }
            return;
        }
        if ((TELEMETRY_CHARACTERISTIC.equals(uuid)
                || TELEMETRY_RELAY_CHARACTERISTIC.equals(uuid)
                || SECURE_CHARACTERISTIC.equals(uuid))
                && helperTelemetryClientEnabled()) {
            IphoneHelperTelemetry telemetry = IphoneHelperTelemetry.parse(value);
            if (telemetry != null) {
                acceptHelperTelemetryFrame(callbackGatt, telemetry, "notification");
                scheduleHelperTelemetryRecovery(callbackGatt, HELPER_TELEMETRY_POLL_MS);
            } else {
                log("Helper notification ignored: malformed TEL2/TEL3 frame");
            }
            return;
        }
        if (SERVICE_CHANGED.equals(uuid)) {
            if (helperTelemetryClientEnabled() && !helperBootstrapMode) {
                log("Рабочий ANCS GATT получил Service Changed; "
                        + "переоткрываю services на том же owner");
                restartDiscoveryOnPersistentOwner(callbackGatt, activeClientGeneration,
                        "SERVICE CHANGED indication");
                return;
            }
            log("Получен Service Changed; сбрасываю старые ANCS handles/очередь "
                    + "и повторяю discovery");
            clearAncsRuntime();
            main.postDelayed(() -> discoverServices(callbackGatt), 400L);
        } else if (AncsProtocol.NOTIFICATION_SOURCE.equals(uuid)) {
            if (gattReady) {
                handleNotificationSource(value);
            } else if (value != null
                    && (descriptorStage == DescriptorStage.NOTIFICATION_SOURCE
                    || descriptorStage == DescriptorStage.DATA_SOURCE)) {
                if (earlyNotificationSourceFrames.size()
                        >= MAX_EARLY_NOTIFICATION_SOURCE_FRAMES) {
                    earlyNotificationSourceFrames.pollFirst();
                }
                earlyNotificationSourceFrames.addLast(value.clone());
                log("Буферизую ранний Notification Source до обеих CCCD · pending="
                        + earlyNotificationSourceFrames.size());
            } else {
                log("Notification Source пришёл вне актуальной ANCS-подписки");
            }
        } else if (AncsProtocol.DATA_SOURCE.equals(uuid)) {
            if (gattReady) {
                handleDataSource(value);
            } else {
                log("Игнорирую Data Source до актуального ANCS READY");
            }
        }
    }

    private static boolean descriptorMatchesStage(DescriptorStage stage, UUID uuid) {
        if (stage == null || uuid == null) return false;
        switch (stage) {
            case SERVICE_CHANGED:
                return SERVICE_CHANGED.equals(uuid);
            case HELPER_TELEMETRY:
                return TELEMETRY_CHARACTERISTIC.equals(uuid)
                        || TELEMETRY_RELAY_CHARACTERISTIC.equals(uuid)
                        || SECURE_CHARACTERISTIC.equals(uuid);
            case DATA_SOURCE:
                return AncsProtocol.DATA_SOURCE.equals(uuid);
            case NOTIFICATION_SOURCE:
                return AncsProtocol.NOTIFICATION_SOURCE.equals(uuid);
            case BATTERY_LEVEL:
                return BATTERY_LEVEL.equals(uuid);
            case BATTERY_LEVEL_STATUS:
                return BATTERY_LEVEL_STATUS.equals(uuid);
            case NONE:
            default:
                return false;
        }
    }

    private static boolean isBatteryDescriptorStage(DescriptorStage stage) {
        return stage == DescriptorStage.BATTERY_LEVEL
                || stage == DescriptorStage.BATTERY_LEVEL_STATUS;
    }

    /**
     * Helper v8 publishes one atomic TEL3 snapshot on B4. Older Helper v7 builds can still notify
     * the split TEL2 frames on B3. Both endpoints share the already-working Android-central ANCS
     * link, so telemetry never needs a second BLE connection.
     */
    private boolean startOptionalHelperTelemetrySubscription(BluetoothGatt callbackGatt) {
        BluetoothGattCharacteristic telemetry = helperTelemetryEndpoint();
        if (!helperTelemetryClientEnabled() || callbackGatt != gatt || telemetry == null
                || iphoneHelperTelemetrySubscribed
                || iphoneHelperTelemetrySubscriptionAttempted
                || descriptorStage != DescriptorStage.NONE) return false;
        if ((telemetry.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) == 0) {
            log("Helper telemetry endpoint has no NOTIFY; periodic encrypted READ remains active");
            iphoneHelperTelemetrySubscriptionAttempted = true;
            scheduleHelperTelemetryRecovery(callbackGatt, HELPER_TELEMETRY_BUSY_RETRY_MS);
            return false;
        }
        iphoneHelperTelemetrySubscriptionAttempted = true;
        descriptorStage = DescriptorStage.HELPER_TELEMETRY;
        boolean started = subscribe(callbackGatt, telemetry, false);
        if (!started) {
            descriptorStage = DescriptorStage.NONE;
            iphoneHelperTelemetrySubscriptionAttempted = false;
            log("Helper telemetry optional subscription did not start");
            scheduleHelperTelemetryRecovery(callbackGatt, HELPER_TELEMETRY_BUSY_RETRY_MS);
        } else {
            state(gattReady ? "ANCS READY · ВКЛЮЧАЮ TEL3" : "GPS-LINK · ВКЛЮЧАЮ TEL3");
        }
        return started;
    }

    private BluetoothGattCharacteristic helperTelemetryEndpoint() {
        return iphoneTelemetryCharacteristic != null
                ? iphoneTelemetryCharacteristic : iphoneSecureCharacteristic;
    }

    /** Both client routes terminate on an iPhone-owned GATT database. */
    private boolean helperTelemetryClientEnabled() {
        return iphonePeripheralMode || managedIncomingMode;
    }

    private void continueAfterHelperTelemetrySubscription(BluetoothGatt callbackGatt) {
        scheduleHelperTelemetryRecovery(callbackGatt, 200L);
        if (gattReady) finishAncsReadySetup(callbackGatt);
        else if (managedIncomingMode) {
            // In the reverse route there is no Helper B3 phase on this client owner. Resume the
            // same discovery result immediately so ANCS subscriptions follow the optional B4
            // relay CCCD instead of waiting for the direct-route post-secure scheduler.
            handleServices(callbackGatt, GATT_SUCCESS);
        } else {
            scheduleIphonePostSecureDiscovery(callbackGatt);
        }
    }

    /** Records a real B4 payload, not merely service discovery or a CCCD callback. */
    private boolean acceptHelperTelemetryFrame(BluetoothGatt callbackGatt,
                                               @NonNull IphoneHelperTelemetry telemetry,
                                               String source) {
        boolean validForReady = telemetry.kind == IphoneHelperTelemetry.Kind.SNAPSHOT
                && telemetry.batteryLevel != null
                && !telemetry.networkType.trim().isEmpty();
        boolean firstFrame = validForReady && !iphoneHelperValidTelemetryReceived;
        if (validForReady) iphoneHelperValidTelemetryReceived = true;
        listener.onHelperTelemetry(telemetry);
        if (shouldLogHelperTelemetry(telemetry)) {
            log("Helper B4 " + source + " accepted: kind=" + telemetry.kind
                    + " battery=" + telemetry.batteryLevel
                    + " externalPower=" + telemetry.externalPower
                    + " chargeState=" + telemetry.chargeState
                    + " network=" + (telemetry.networkType.isEmpty()
                    ? "unknown" : telemetry.networkType)
                    + " locked=" + telemetry.phoneLocked
                    + " seq=" + telemetry.sequence);
        }
        if (!validForReady) {
            log("Helper B4 payload принят для диагностики, но READY запрещён: нужны "
                    + "валидные battery + network в одном SNAPSHOT");
            return false;
        }
        if (firstFrame) {
            log("Helper B4 battery+network proof confirmed");
        }
        if (!managedIncomingMode || !gattReady || helperAncsReadyProofAcknowledged) {
            return false;
        }
        boolean started = startHelperAncsReadyProof(callbackGatt);
        if (!started && !helperAncsReadyProofPending) {
            scheduleHelperAncsReadyProofRetry(callbackGatt,
                    "valid B4 arrived while GATT queue was busy");
        }
        return started;
    }

    private void finishAncsReadySetup(BluetoothGatt callbackGatt) {
        if (managedIncomingMode && !iphoneHelperValidTelemetryReceived) {
            log("Обе ANCS CCCD включены, но READY ждёт валидные battery + network B4");
            scheduleHelperTelemetryRecovery(callbackGatt, 200L);
        }
        if (startHelperAncsReadyProof(callbackGatt)) return;
        prepareBatteryBootstrap(callbackGatt);
        log("Обе ANCS-подписки включены; Helper telemetry="
                + (iphoneHelperTelemetrySubscribed ? "READY" : "UNAVAILABLE")
                + "; atomic B4 READ and BAS diagnostics use the serialized GATT queue");
        sendNextRequest();
    }

    /**
     * Completes the reverse-route proof on the same ATT owner. Core Bluetooth's didConnect and
     * an unencrypted B4 read only prove a BLE link; the ANCS session is usable only after both
     * Notification Source and Data Source CCCDs are enabled. The iPhone UI is therefore allowed
     * to turn green only after this post-CCCD write succeeds.
     */
    private boolean startHelperAncsReadyProof(BluetoothGatt callbackGatt) {
        if (!managedIncomingMode || callbackGatt != gatt || !gattReady
                || !iphoneHelperTelemetrySubscribed
                || !iphoneHelperValidTelemetryReceived
                || helperAncsReadyProofAcknowledged
                || helperAncsReadyProofAttempted || helperAncsReadyProofPending
                || discoveryPending || descriptorStage != DescriptorStage.NONE
                || activeRequest != null || iphoneHelperTelemetryReadPending
                || batteryReadPendingUuid != null) return false;
        BluetoothGattCharacteristic telemetry = iphoneTelemetryCharacteristic;
        if (telemetry == null
                || !TELEMETRY_RELAY_CHARACTERISTIC.equals(telemetry.getUuid())) return false;
        helperAncsReadyProofAttempted = true;
        if ((telemetry.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE) == 0) {
            log("Helper B4 relay не принимает ANCS-SUBSCRIBED; нужен matched Helper v33");
            return false;
        }
        telemetry.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        telemetry.setValue("ANCS-SUBSCRIBED".getBytes(StandardCharsets.UTF_8));
        boolean started;
        try {
            started = callbackGatt.writeCharacteristic(telemetry);
        } catch (RuntimeException failure) {
            started = false;
            log("ANCS-SUBSCRIBED write exception: " + failure);
        }
        helperAncsReadyProofPending = started;
        if (!started) helperAncsReadyProofAttempted = false;
        log("ANCS-SUBSCRIBED proof started=" + started
                + " after both ANCS CCCD + B4 CCCD + valid battery/network payload");
        if (started) {
            main.postDelayed(() -> {
                if (callbackGatt != gatt || !helperAncsReadyProofPending) return;
                helperAncsReadyProofPending = false;
                helperAncsReadyProofAttempted = false;
                log("ANCS-SUBSCRIBED callback timeout; повторяю proof на живом owner");
                scheduleHelperAncsReadyProofRetry(callbackGatt, "write callback timeout");
            }, 4_000L);
        }
        return started;
    }

    private void scheduleHelperAncsReadyProofRetry(BluetoothGatt expectedGatt, String reason) {
        if (helperAncsReadyProofAcknowledged || expectedGatt == null) return;
        if (helperAncsReadyProofRetry != null) {
            main.removeCallbacks(helperAncsReadyProofRetry);
        }
        helperAncsReadyProofRetry = () -> {
            helperAncsReadyProofRetry = null;
            if (expectedGatt != gatt || !gattClientConnected || !gattReady
                    || helperAncsReadyProofAcknowledged) return;
            if (startHelperAncsReadyProof(expectedGatt)) return;
            scheduleHelperTelemetryRecovery(expectedGatt, HELPER_TELEMETRY_BUSY_RETRY_MS);
            scheduleHelperAncsReadyProofRetry(expectedGatt, "GATT queue still busy");
        };
        log("ANCS-SUBSCRIBED retry через 1 с · " + reason);
        main.postDelayed(helperAncsReadyProofRetry, 1_000L);
    }

    /**
     * Keeps Helper telemetry alive independently of notification delivery. Notifications are the
     * low-latency path; a B4 read is the deterministic snapshot/recovery path. If the
     * Helper service was published after ANCS discovery, the same GATT owner periodically repeats
     * service discovery instead of opening a competing connection.
     */
    private void scheduleHelperTelemetryRecovery(BluetoothGatt expectedGatt, long delayMs) {
        if (!helperTelemetryClientEnabled() || expectedGatt == null || expectedGatt != gatt
                || !gattClientConnected) return;
        if (helperTelemetryPoll != null) main.removeCallbacks(helperTelemetryPoll);
        helperTelemetryPoll = () -> {
            helperTelemetryPoll = null;
            if (!helperTelemetryClientEnabled()
                    || expectedGatt != gatt || !gattClientConnected) return;

            BluetoothGattCharacteristic endpoint = helperTelemetryEndpoint();
            boolean busy = discoveryPending || descriptorStage != DescriptorStage.NONE
                    || activeRequest != null || batteryReadPendingUuid != null
                    || iphoneHelperTelemetryReadPending;
            if (endpoint == null) {
                log("Helper F05/B4 пока не найден; жду Service Changed на существующем owner");
                return;
            }

            boolean legacyWithoutNotify = iphoneTelemetryCharacteristic == null
                    && (endpoint.getProperties()
                    & BluetoothGattCharacteristic.PROPERTY_NOTIFY) == 0;
            if (legacyWithoutNotify) {
                log("Legacy Helper B3 не передаёт telemetry; жду F05 через Service Changed");
                return;
            }

            if (!iphoneHelperTelemetrySubscribed
                    && !iphoneHelperTelemetrySubscriptionAttempted
                    && !busy
                    && (endpoint.getProperties()
                    & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) {
                if (startOptionalHelperTelemetrySubscription(expectedGatt)) return;
            }

            if (!busy && startHelperTelemetryRead(expectedGatt)) return;
            scheduleHelperTelemetryRecovery(expectedGatt,
                    busy ? HELPER_TELEMETRY_BUSY_RETRY_MS : HELPER_TELEMETRY_POLL_MS);
        };
        main.postDelayed(helperTelemetryPoll, Math.max(1L, delayMs));
    }

    private boolean startHelperTelemetryRead(BluetoothGatt callbackGatt) {
        BluetoothGattCharacteristic telemetry = iphoneTelemetryCharacteristic;
        if (callbackGatt != gatt || telemetry == null || iphoneHelperTelemetryReadPending
                || discoveryPending || descriptorStage != DescriptorStage.NONE
                || activeRequest != null || batteryReadPendingUuid != null
                || (telemetry.getProperties()
                & BluetoothGattCharacteristic.PROPERTY_READ) == 0) {
            return false;
        }
        boolean started;
        try {
            started = callbackGatt.readCharacteristic(telemetry);
        } catch (RuntimeException failure) {
            started = false;
            log("Helper B4 atomic read exception: " + failure);
        }
        if (!started) {
            log("Helper B4 atomic read did not start");
            return false;
        }
        iphoneHelperTelemetryReadPending = true;
        scheduleHelperTelemetryReadTimeout(callbackGatt);
        return true;
    }

    private boolean shouldLogHelperTelemetry(@NonNull IphoneHelperTelemetry telemetry) {
        String fingerprint = telemetry.batteryLevel + "|" + telemetry.externalPower + "|"
                + telemetry.chargeState + "|" + telemetry.networkType + "|"
                + telemetry.phoneLocked;
        long now = SystemClock.elapsedRealtime();
        boolean changed = !Objects.equals(lastLoggedHelperTelemetry, fingerprint);
        if (!changed && now - lastHelperTelemetrySuccessLogAt < 30_000L) return false;
        lastLoggedHelperTelemetry = fingerprint;
        lastHelperTelemetrySuccessLogAt = now;
        return true;
    }

    private void scheduleHelperTelemetryReadTimeout(BluetoothGatt expectedGatt) {
        cancelHelperTelemetryReadTimeout();
        helperTelemetryReadTimeout = () -> {
            helperTelemetryReadTimeout = null;
            if (expectedGatt != gatt || !iphoneHelperTelemetryReadPending) return;
            iphoneHelperTelemetryReadPending = false;
            boolean resumeServiceSetup = iphoneServiceSetupDeferredForHelperRead;
            iphoneServiceSetupDeferredForHelperRead = false;
            log("Helper B4 read callback timeout; ANCS remains active");
            if (resumeServiceSetup) {
                handleServices(expectedGatt, GATT_SUCCESS);
                return;
            }
            scheduleHelperTelemetryRecovery(expectedGatt, HELPER_TELEMETRY_BUSY_RETRY_MS);
            sendNextRequest();
        };
        main.postDelayed(helperTelemetryReadTimeout, HELPER_TELEMETRY_READ_TIMEOUT_MS);
    }

    private void cancelHelperTelemetryReadTimeout() {
        if (helperTelemetryReadTimeout != null) {
            main.removeCallbacks(helperTelemetryReadTimeout);
        }
        helperTelemetryReadTimeout = null;
    }

    private void cancelHelperTelemetryRecovery() {
        if (helperTelemetryPoll != null) main.removeCallbacks(helperTelemetryPoll);
        helperTelemetryPoll = null;
        cancelHelperTelemetryReadTimeout();
        iphoneHelperTelemetryReadPending = false;
    }

    private void prepareBatteryBootstrap(BluetoothGatt callbackGatt) {
        if (callbackGatt != gatt || batteryStage != BatteryStage.NOT_STARTED) return;
        BluetoothGattService service = callbackGatt.getService(BATTERY_SERVICE);
        batteryLevel = service == null ? null : service.getCharacteristic(BATTERY_LEVEL);
        batteryLevelStatus =
                service == null ? null : service.getCharacteristic(BATTERY_LEVEL_STATUS);
        if (batteryLevel == null && batteryLevelStatus == null) {
            batteryStage = BatteryStage.COMPLETE;
            log("BAS 0x180F отсутствует; видимый процент ожидается только от Helper TEL3");
            return;
        }
        // Battery Level Status is retained only as an optional percentage source. Its charging
        // bits are ignored by the controller; TEL3 from iPhone Helper is authoritative.
        batteryStage = BatteryStage.READ_LEVEL_STATUS;
        log("BAS percentage probe: level=" + (batteryLevel != null)
                + " levelStatus=" + (batteryLevelStatus != null));
    }

    private void resetBatteryBootstrap() {
        cancelBatteryReadTimeout();
        batteryLevel = null;
        batteryLevelStatus = null;
        batteryReadPendingUuid = null;
        batteryStage = BatteryStage.NOT_STARTED;
    }

    /**
     * BAS is optional and uses the same Android GATT transaction gate as ANCS. A queued ANCS
     * request always wins; battery reads/subscriptions resume only while Control Point is idle.
     */
    private void advanceBatteryBootstrapIfIdle() {
        BluetoothGatt callbackGatt = gatt;
        if (!gattClientConnected || callbackGatt == null || activeRequest != null
                || !requests.isEmpty() || batteryReadPendingUuid != null
                || iphoneHelperTelemetryReadPending
                || descriptorStage != DescriptorStage.NONE) {
            return;
        }
        while (true) {
            switch (batteryStage) {
                case READ_LEVEL_STATUS:
                    batteryStage = BatteryStage.SUBSCRIBE_LEVEL_STATUS;
                    if (startOptionalBatteryRead(callbackGatt, batteryLevelStatus)) return;
                    break;
                case SUBSCRIBE_LEVEL_STATUS:
                    batteryStage = BatteryStage.READ_LEVEL;
                    if (startOptionalBatterySubscription(callbackGatt, batteryLevelStatus,
                            DescriptorStage.BATTERY_LEVEL_STATUS)) return;
                    break;
                case READ_LEVEL:
                    batteryStage = BatteryStage.SUBSCRIBE_LEVEL;
                    if (startOptionalBatteryRead(callbackGatt, batteryLevel)) return;
                    break;
                case SUBSCRIBE_LEVEL:
                    batteryStage = BatteryStage.COMPLETE;
                    if (startOptionalBatterySubscription(callbackGatt, batteryLevel,
                            DescriptorStage.BATTERY_LEVEL)) return;
                    break;
                case NOT_STARTED:
                case COMPLETE:
                default:
                    return;
            }
        }
    }

    private boolean startOptionalBatteryRead(BluetoothGatt callbackGatt,
                                             BluetoothGattCharacteristic characteristic) {
        if (characteristic == null
                || (characteristic.getProperties()
                & BluetoothGattCharacteristic.PROPERTY_READ) == 0) {
            return false;
        }
        UUID uuid = characteristic.getUuid();
        batteryReadPendingUuid = uuid;
        boolean started;
        try {
            started = callbackGatt.readCharacteristic(characteristic);
        } catch (RuntimeException failure) {
            started = false;
            log("BAS read exception " + shortUuid(uuid) + ": " + failure);
        }
        if (!started) {
            batteryReadPendingUuid = null;
            log("BAS read not started · " + shortUuid(uuid));
            return false;
        }
        scheduleBatteryReadTimeout(callbackGatt, uuid);
        log("BAS read started · " + shortUuid(uuid));
        return true;
    }

    private boolean startOptionalBatterySubscription(
            BluetoothGatt callbackGatt, BluetoothGattCharacteristic characteristic,
            DescriptorStage stage) {
        if (characteristic == null) return false;
        int properties = characteristic.getProperties();
        boolean indicate =
                (properties & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0;
        boolean notify =
                (properties & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0;
        if (!notify && !indicate) return false;
        BluetoothGattDescriptor cccd =
                characteristic.getDescriptor(AncsProtocol.CLIENT_CONFIGURATION);
        if (cccd == null) return false;
        boolean local;
        try {
            local = callbackGatt.setCharacteristicNotification(characteristic, true);
        } catch (RuntimeException failure) {
            log("BAS setCharacteristicNotification exception · "
                    + shortUuid(characteristic.getUuid()) + ": " + failure);
            return false;
        }
        if (!local) return false;
        cccd.setValue(indicate
                ? BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                : BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
        descriptorStage = stage;
        boolean started;
        try {
            started = callbackGatt.writeDescriptor(cccd);
        } catch (RuntimeException failure) {
            started = false;
            log("BAS CCCD exception · " + stage + ": " + failure);
        }
        if (!started) {
            descriptorStage = DescriptorStage.NONE;
            return false;
        }
        scheduleBatteryDescriptorTimeout(callbackGatt, stage,
                characteristic.getUuid());
        log("BAS CCCD started · " + stage);
        return true;
    }

    private void handleNotificationSource(byte[] value) {
        if (value == null) return;
        int accepted = 0;
        int dropped = 0;
        int preExistingDropped = 0;
        int removalsSuppressed = 0;
        for (int offset = 0; offset + 8 <= value.length; offset += 8) {
            AncsProtocol.Event event = AncsProtocol.parseEvent(value, offset);
            if (event == null) continue;
            long observedAtElapsedMs = SystemClock.elapsedRealtime();
            if (event.eventId == AncsProtocol.EVENT_REMOVED) {
                events.remove(event.uid);
                eventObservedAtElapsedMs.remove(event.uid);
                dirtyNotificationUids.remove(event.uid);
                cancelQueuedNotificationRequests(event.uid);
                if (realtimeAdmission.consumeRemoval(event.uid)) {
                    listener.onNotification(new NotificationItem(event.uid, event.eventId,
                            event.categoryId, "", "", "Удалено", "", "",
                            observedAtElapsedMs));
                    accepted++;
                } else {
                    removalsSuppressed++;
                }
            } else if (!realtimeAdmission.shouldRequest(event)) {
                preExistingDropped++;
            } else if (activeRequest != null
                    && activeRequest.kind == RequestKind.NOTIFICATION
                    && activeRequest.uid == event.uid) {
                // The response currently in flight may already have been formed by iOS. Mark
                // this UID dirty so that response is discarded and exactly one fresh request is
                // sent for the newest event.
                events.put(event.uid, event);
                eventObservedAtElapsedMs.put(event.uid, observedAtElapsedMs);
                dirtyNotificationUids.add(event.uid);
                accepted++;
            } else if (queuedNotificationUids.contains(event.uid)) {
                // Keep one queued Control Point request, but refresh both its metadata and
                // monotonic age to the latest Modified event.
                events.put(event.uid, event);
                eventObservedAtElapsedMs.put(event.uid, observedAtElapsedMs);
                updateQueuedNotificationAge(event.uid, observedAtElapsedMs);
                accepted++;
            } else if (requests.size() < MAX_PENDING_ANCS_REQUESTS) {
                events.put(event.uid, event);
                eventObservedAtElapsedMs.put(event.uid, observedAtElapsedMs);
                queuedNotificationUids.add(event.uid);
                requests.add(Request.notification(event, observedAtElapsedMs));
                accepted++;
            } else {
                dropped++;
            }
        }
        log("ANCS Notification Source: accepted=" + accepted
                + " dropped=" + dropped
                + " preExistingDropped=" + preExistingDropped
                + " removalsSuppressed=" + removalsSuppressed
                + " queue=" + requests.size()
                + " (только real-time; pre-existing replay не запрашивается)");
        sendNextRequest();
    }

    private void updateQueuedNotificationAge(long uid, long observedAtElapsedMs) {
        for (Request request : requests) {
            if (request.kind == RequestKind.NOTIFICATION && request.uid == uid) {
                request.observedAtElapsedMs = observedAtElapsedMs;
                return;
            }
        }
    }

    private void cancelQueuedNotificationRequests(long uid) {
        Iterator<Request> iterator = requests.iterator();
        while (iterator.hasNext()) {
            Request request = iterator.next();
            if (request.kind == RequestKind.NOTIFICATION && request.uid == uid) {
                iterator.remove();
                queuedNotificationUids.remove(uid);
            }
        }
    }

    private void sendNextRequest() {
        if (gatt == null || activeRequest != null) return;
        if (batteryReadPendingUuid != null || iphoneHelperTelemetryReadPending
                || descriptorStage != DescriptorStage.NONE) return;
        if (!gattReady || controlPoint == null) {
            advanceBatteryBootstrapIfIdle();
            return;
        }
        while (activeRequest == null) {
            Request candidate = requests.poll();
            if (candidate == null) {
                advanceBatteryBootstrapIfIdle();
                return;
            }
            if (candidate.kind == RequestKind.NOTIFICATION) {
                queuedNotificationUids.remove(candidate.uid);
                if (isExpiredNotification(candidate.observedAtElapsedMs)) {
                    discardNotificationState(candidate.uid);
                    log("ANCS notification UID " + candidate.uid
                            + " отброшен до Control Point: старше "
                            + LIVE_NOTIFICATION_MAX_AGE_MS + " ms");
                    continue;
                }
            }
            activeRequest = candidate;
        }
        byte[] payload;
        if (activeRequest.kind == RequestKind.NOTIFICATION) {
            notificationAccumulator =
                    new AncsProtocol.NotificationAccumulator(activeRequest.uid);
            appNameAccumulator = null;
            payload = AncsProtocol.notificationAttributeRequest(activeRequest.uid);
        } else {
            appNameAccumulator =
                    new AncsProtocol.AppNameAccumulator(activeRequest.appIdentifier);
            notificationAccumulator = null;
            payload = AncsProtocol.appDisplayNameRequest(activeRequest.appIdentifier);
        }
        controlPoint.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        controlPoint.setValue(payload);
        boolean started;
        try {
            started = gatt.writeCharacteristic(controlPoint);
        } catch (RuntimeException failure) {
            log("Control Point write exception: " + failure);
            finishRequest("write_exception");
            return;
        }
        log("Control Point " + activeRequest.kind + " started=" + started
                + " payload=" + AdvertisementParser.hex(payload, 80));
        if (!started) {
            finishRequest("write_not_started");
            return;
        }
        Request expected = activeRequest;
        requestTimeout = () -> {
            if (activeRequest == expected) {
                log("ANCS Data Source timeout: " + expected.kind);
                abortAncsRequestStream("timeout");
            }
        };
        main.postDelayed(requestTimeout, REQUEST_TIMEOUT_MS);
    }

    private void handleDataSource(byte[] fragment) {
        if (activeRequest == null) {
            log("Data Source fragment без активного запроса");
            return;
        }
        if (activeRequest.kind == RequestKind.NOTIFICATION) {
            if (!notificationAccumulator.append(fragment)) {
                log("Malformed Notification response after "
                        + notificationAccumulator.size() + " bytes: "
                        + notificationAccumulator.error());
                abortAncsRequestStream("notification_malformed");
                return;
            }
            log("Notification Data Source accumulated="
                    + notificationAccumulator.size() + " bytes");
            AncsProtocol.NotificationData result = notificationAccumulator.complete();
            if (result == null) return;
            if (dirtyNotificationUids.remove(result.uid)) {
                AncsProtocol.Event latestEvent = events.get(result.uid);
                Long latestObservedAt = eventObservedAtElapsedMs.get(result.uid);
                if (latestEvent != null && latestObservedAt != null
                        && !isExpiredNotification(latestObservedAt)) {
                    enqueuePriorityRequest(Request.notification(latestEvent, latestObservedAt));
                    log("Notification attributes discarded: UID " + result.uid
                            + " изменился во время запроса; запланирован один fresh refresh");
                    finishRequest("refresh_queued");
                } else {
                    discardNotificationState(result.uid);
                    log("Notification refresh UID " + result.uid
                            + " просрочен и отброшен");
                    finishRequest("expired_refresh");
                }
                return;
            }
            AncsProtocol.Event event = events.remove(result.uid);
            Long observedAtElapsedMs = eventObservedAtElapsedMs.remove(result.uid);
            if (event == null) {
                log("Notification attributes discarded: UID " + result.uid
                        + " больше не live в текущей ANCS-сессии");
                finishRequest("removed_before_delivery");
                return;
            }
            if (observedAtElapsedMs == null
                    || isExpiredNotification(observedAtElapsedMs)) {
                log("Notification attributes discarded: UID " + result.uid
                        + " старше real-time TTL " + LIVE_NOTIFICATION_MAX_AGE_MS + " ms");
                finishRequest("expired_before_delivery");
                return;
            }
            String appName = value(appNames, result.appIdentifier);
            listener.onNotification(new NotificationItem(result.uid,
                    event.eventId, event.categoryId,
                    result.appIdentifier, appName, result.title, result.message, result.date,
                    observedAtElapsedMs));
            realtimeAdmission.markDelivered(result.uid);
            log("Notification attributes: app=" + result.appIdentifier
                    + " title=" + result.title);
            if (!result.appIdentifier.isEmpty()
                    && !appNames.containsKey(result.appIdentifier)
                    && queuedAppIdentifiers.add(result.appIdentifier)) {
                enqueuePriorityRequest(Request.appName(result.appIdentifier));
            }
            finishRequest("complete");
        } else {
            if (!appNameAccumulator.append(fragment)) {
                log("Malformed App response after " + appNameAccumulator.size()
                        + " bytes: " + appNameAccumulator.error());
                abortAncsRequestStream("app_malformed");
                return;
            }
            log("App Data Source accumulated=" + appNameAccumulator.size() + " bytes");
            String displayName = appNameAccumulator.complete();
            if (displayName == null) return;
            appNames.put(activeRequest.appIdentifier, displayName);
            listener.onAppName(activeRequest.appIdentifier, displayName);
            log("App DisplayName: " + activeRequest.appIdentifier + " → " + displayName);
            finishRequest("complete");
        }
    }

    private void finishRequest(String reason) {
        if (requestTimeout != null) main.removeCallbacks(requestTimeout);
        log("ANCS request finished: " + reason);
        Request finished = activeRequest;
        if (finished != null && finished.kind == RequestKind.NOTIFICATION
                && !"complete".equals(reason)
                && !"refresh_queued".equals(reason)) {
            discardNotificationState(finished.uid);
        } else if (finished != null && finished.kind == RequestKind.APP_NAME) {
            queuedAppIdentifiers.remove(finished.appIdentifier);
        }
        activeRequest = null;
        notificationAccumulator = null;
        appNameAccumulator = null;
        requestTimeout = null;
        long delay = reason.contains("timeout") || reason.contains("malformed")
                ? 350L : ANCS_REQUEST_GAP_MS;
        main.postDelayed(this::sendNextRequest, delay);
    }

    private boolean isExpiredNotification(long observedAtElapsedMs) {
        return observedAtElapsedMs <= 0L
                || SystemClock.elapsedRealtime() - observedAtElapsedMs
                > LIVE_NOTIFICATION_MAX_AGE_MS;
    }

    private void discardNotificationState(long uid) {
        events.remove(uid);
        eventObservedAtElapsedMs.remove(uid);
        queuedNotificationUids.remove(uid);
        dirtyNotificationUids.remove(uid);
    }

    /**
     * App-name and dirty-refresh requests must run before older queued work, but the bounded
     * queue is a hard memory/back-pressure limit. Evicting a tail request also clears all of its
     * deduplication state so a later real-time event is not suppressed by a request that no longer
     * exists.
     */
    private void enqueuePriorityRequest(Request request) {
        while (requests.size() >= MAX_PENDING_ANCS_REQUESTS) {
            Request evicted = requests.pollLast();
            if (evicted == null) break;
            if (evicted.kind == RequestKind.NOTIFICATION) {
                discardNotificationState(evicted.uid);
            } else {
                queuedAppIdentifiers.remove(evicted.appIdentifier);
            }
            log("ANCS queue full: evicted tail " + evicted.kind);
        }
        if (request.kind == RequestKind.NOTIFICATION) {
            queuedNotificationUids.add(request.uid);
        }
        requests.addFirst(request);
    }

    /**
     * A timed-out or malformed Data Source response has no transaction identifier beyond its
     * command prefix. Continuing with the next queued request would let a late fragment from the
     * failed response corrupt the next accumulator, so fail the whole ANCS session closed and let
     * the owning controller reopen it.
     */
    private void abortAncsRequestStream(String reason) {
        log("ANCS Data Source stream desynchronized: " + reason
                + "; queued requests are discarded before reconnect");
        clearAncsRuntime();
        state("ANCS DATA DESYNC · RECONNECT · " + reason);
    }

    private void requestBond(BluetoothDevice device) {
        if (device == null) return;
        if (!isVerifiedPeer(device)) {
            log("Bonding отклонён: устройство не является verified peer текущей сессии");
            return;
        }
        int state = safeBondState(device);
        if (state == BluetoothDevice.BOND_BONDED) {
            log("Verified peer уже BOND_BONDED");
            return;
        }
        if (state == BluetoothDevice.BOND_BONDING) {
            log("LE bonding verified peer уже выполняется");
            scheduleBondTimeout(device);
            return;
        }
        boolean started = false;
        try {
            started = device.createBond();
        } catch (RuntimeException failure) {
            log("createBond exception: " + failure);
        }
        log("createBond() public API=" + started);
        if (started) {
            leBondAttemptObserved = true;
            scheduleBondTimeout(device);
        }
        state(started ? "BONDING · ПОДТВЕРДИТЕ НА IPHONE" : "BOND_START_FAILED");
    }

    private void scheduleAncsPermissionRetry(@NonNull BluetoothGatt expected) {
        if (expected != gatt || !gattClientConnected || gattReady
                || ancsPermissionRetry != null) return;
        if (ancsPermissionRetryCount >= ANCS_PERMISSION_RETRY_LIMIT) {
            state("ANCS НЕ РАЗРЕШЕН НА IPHONE · LINK СОХРАНЁН");
            log("ANCS permission всё ещё отсутствует; автоматические проверки остановлены, "
                    + "но тот же BluetoothGatt owner остаётся активным");
            return;
        }
        ancsPermissionRetryCount++;
        int attempt = ancsPermissionRetryCount;
        ancsPermissionRetry = () -> {
            ancsPermissionRetry = null;
            if (expected != gatt || !gattClientConnected || gattReady) return;
            descriptorStage = DescriptorStage.NONE;
            resetBatteryBootstrap();
            log("Повторяю ANCS discovery на том же owner после ожидания iPhone permission · #"
                    + attempt);
            discoverServices(expected);
        };
        main.postDelayed(ancsPermissionRetry, ANCS_PERMISSION_RETRY_MS);
        log("Проверка iPhone ANCS permission #" + attempt + " через "
                + ANCS_PERMISSION_RETRY_MS + " ms; link не закрывается");
    }

    private void scheduleAncsRetryAfterBond(BluetoothGatt expected, String reason) {
        if (!ancsRetryAfterBond || expected == null || expected != gatt
                || !gattClientConnected || ancsBondRetry != null) {
            return;
        }
        if (ancsBondRetryCount >= 1) {
            ancsRetryAfterBond = false;
            state("ANCS AUTH FAILED ПОСЛЕ BOND");
            log("Повтор ANCS-подписки уже использован; новый цикл не запускаю");
            return;
        }
        ancsBondRetryCount++;
        ancsBondRetry = () -> {
            ancsBondRetry = null;
            if (gatt != expected || !gattClientConnected) return;
            ancsRetryAfterBond = false;
            descriptorStage = DescriptorStage.NONE;
            // discoverServices() may replace characteristic wrapper objects on Android 9.
            // Refresh BAS handles together with ANCS after the encrypted-link retry.
            resetBatteryBootstrap();
            log("Повторяю discovery/ANCS-подписку после bond · " + reason);
            discoverServices(expected);
        };
        main.postDelayed(ancsBondRetry, 800L);
        log("ANCS retry #" + ancsBondRetryCount + " запланирован через 800 ms · " + reason);
    }

    private void clearAncsRuntime() {
        if (requestTimeout != null) main.removeCallbacks(requestTimeout);
        if (ancsBondRetry != null) main.removeCallbacks(ancsBondRetry);
        if (ancsPermissionRetry != null) main.removeCallbacks(ancsPermissionRetry);
        cancelAutoAncsWaitTimeout();
        cancelConnectTimeout();
        cancelDiscoveryTimeout();
        cancelDescriptorWriteTimeout();
        cancelHelperTelemetryRecovery();
        if (helperAncsReadyProofRetry != null) {
            main.removeCallbacks(helperAncsReadyProofRetry);
            helperAncsReadyProofRetry = null;
        }
        resetBatteryBootstrap();
        cancelBondTimeout();
        requestTimeout = null;
        ancsBondRetry = null;
        ancsPermissionRetry = null;
        ancsPermissionRetryCount = 0;
        requests.clear();
        earlyNotificationSourceFrames.clear();
        events.clear();
        eventObservedAtElapsedMs.clear();
        queuedNotificationUids.clear();
        dirtyNotificationUids.clear();
        queuedAppIdentifiers.clear();
        realtimeAdmission.clear();
        activeRequest = null;
        notificationAccumulator = null;
        appNameAccumulator = null;
        notificationSource = null;
        dataSource = null;
        controlPoint = null;
        serviceChanged = null;
        iphoneSecureCharacteristic = null;
        iphoneTelemetryCharacteristic = null;
        iphoneHelperTelemetrySubscriptionAttempted = false;
        iphoneHelperTelemetrySubscribed = false;
        iphoneHelperTelemetryReadPending = false;
        iphoneHelperValidTelemetryReceived = false;
        helperAncsReadyProofAttempted = false;
        helperAncsReadyProofPending = false;
        helperAncsReadyProofAcknowledged = false;
        iphoneHelperInitialReadAttempted = false;
        iphoneServiceSetupDeferredForHelperRead = false;
        descriptorStage = DescriptorStage.NONE;
        gattReady = false;
        discoveryPending = false;
    }

    private void cancelConnectTimeout() {
        if (connectTimeout != null) main.removeCallbacks(connectTimeout);
        connectTimeout = null;
    }

    private void cancelDiscoveryTimeout() {
        if (discoveryTimeout != null) main.removeCallbacks(discoveryTimeout);
        discoveryTimeout = null;
    }

    private void scheduleDescriptorWriteTimeout(BluetoothGatt expectedGatt,
                                                DescriptorStage expectedStage,
                                                UUID expectedCharacteristic) {
        cancelDescriptorWriteTimeout();
        descriptorWriteTimeout = () -> {
            descriptorWriteTimeout = null;
            if (gatt != expectedGatt
                    || descriptorStage != expectedStage
                    || !descriptorMatchesStage(expectedStage, expectedCharacteristic)) {
                return;
            }
            descriptorStage = DescriptorStage.NONE;
            if (expectedStage == DescriptorStage.SERVICE_CHANGED) {
                log("Optional Service Changed CCCD callback timeout; "
                        + "mandatory ANCS session is not reset");
                state(iphonePeripheralMode && !helperBootstrapMode
                        ? "AUTO LINK OK · ЖДУ ANCS"
                        : "ЖДУ ANCS БЕЗ SERVICE CHANGED");
                sendNextRequest();
                return;
            }
            if (expectedStage == DescriptorStage.HELPER_TELEMETRY) {
                iphoneHelperTelemetrySubscribed = false;
                iphoneHelperTelemetrySubscriptionAttempted = false;
                log("Helper telemetry optional CCCD callback timeout; ANCS link stays active");
                scheduleHelperTelemetryRecovery(expectedGatt,
                        HELPER_TELEMETRY_BUSY_RETRY_MS);
                continueAfterHelperTelemetrySubscription(expectedGatt);
                return;
            }
            gattReady = false;
            log("onDescriptorWrite не получен за "
                    + ANCS_DESCRIPTOR_WRITE_TIMEOUT_MS
                    + " ms · stage=" + expectedStage
                    + " characteristic=" + shortUuid(expectedCharacteristic));
            state("CCCD_WRITE_TIMEOUT · " + expectedStage);
        };
        long timeout = (expectedStage == DescriptorStage.SERVICE_CHANGED
                || expectedStage == DescriptorStage.HELPER_TELEMETRY)
                ? DESCRIPTOR_WRITE_TIMEOUT_MS : ANCS_DESCRIPTOR_WRITE_TIMEOUT_MS;
        main.postDelayed(descriptorWriteTimeout, timeout);
    }

    private void scheduleBatteryDescriptorTimeout(BluetoothGatt expectedGatt,
                                                  DescriptorStage expectedStage,
                                                  UUID expectedCharacteristic) {
        cancelDescriptorWriteTimeout();
        descriptorWriteTimeout = () -> {
            descriptorWriteTimeout = null;
            if (gatt != expectedGatt
                    || descriptorStage != expectedStage
                    || !descriptorMatchesStage(expectedStage, expectedCharacteristic)) {
                return;
            }
            descriptorStage = DescriptorStage.NONE;
            log("BAS CCCD callback не получен за " + BATTERY_OPERATION_TIMEOUT_MS
                    + " ms · " + expectedStage
                    + "; optional operation skipped, ANCS stays READY");
            sendNextRequest();
        };
        main.postDelayed(descriptorWriteTimeout, BATTERY_OPERATION_TIMEOUT_MS);
    }

    private void cancelDescriptorWriteTimeout() {
        if (descriptorWriteTimeout != null) main.removeCallbacks(descriptorWriteTimeout);
        descriptorWriteTimeout = null;
    }

    private void scheduleBatteryReadTimeout(BluetoothGatt expectedGatt, UUID expectedUuid) {
        cancelBatteryReadTimeout();
        batteryReadTimeout = () -> {
            batteryReadTimeout = null;
            if (gatt != expectedGatt || !expectedUuid.equals(batteryReadPendingUuid)) return;
            batteryReadPendingUuid = null;
            log("BAS read callback не получен за " + BATTERY_OPERATION_TIMEOUT_MS
                    + " ms · " + shortUuid(expectedUuid)
                    + "; optional operation skipped, ANCS stays READY");
            sendNextRequest();
        };
        main.postDelayed(batteryReadTimeout, BATTERY_OPERATION_TIMEOUT_MS);
    }

    private void cancelBatteryReadTimeout() {
        if (batteryReadTimeout != null) main.removeCallbacks(batteryReadTimeout);
        batteryReadTimeout = null;
    }

    private void scheduleBondTimeout(BluetoothDevice expectedDevice) {
        cancelBondTimeout();
        bondTimeout = () -> {
            bondTimeout = null;
            if (!isVerifiedPeer(expectedDevice)
                    || safeBondState(expectedDevice) == BluetoothDevice.BOND_BONDED) {
                return;
            }
            ancsRetryAfterBond = false;
            log("LE bonding не завершился за " + BOND_TIMEOUT_MS
                    + " ms · peer=" + safeAddress(expectedDevice));
            state("LE BOND TIMEOUT");
        };
        main.postDelayed(bondTimeout, BOND_TIMEOUT_MS);
    }

    private void cancelBondTimeout() {
        if (bondTimeout != null) main.removeCallbacks(bondTimeout);
        bondTimeout = null;
    }

    private void closeClientGatt(BluetoothGatt callbackGatt) {
        if (callbackGatt == null) return;
        if (gatt == callbackGatt) {
            gatt = null;
            gattClientConnected = false;
            clientConnectInFlight = false;
            activeClientTarget = null;
            activeClientAutoConnect = false;
            activeClientEstablished = false;
            incomingDiscoveryStarted = false;
        }
        try {
            callbackGatt.close();
        } catch (RuntimeException ignored) {
        }
    }

    private boolean ensureAdapter() {
        if (adapter == null) {
            state("NO_ADAPTER");
            return false;
        }
        if (!adapter.isEnabled()) {
            state("BLUETOOTH_OFF");
            log("Включите Bluetooth штатным интерфейсом");
            return false;
        }
        return true;
    }

    private void updateCandidate(BluetoothDevice device, int rssi,
                                 boolean ancsSolicitation, String raw, String origin) {
        String address = safeAddress(device);
        if (address.isEmpty()) address = "unknown-" + System.identityHashCode(device);
        String name = safeName(device);
        Candidate old = candidates.get(address);
        if (old == null && candidates.size() >= MAX_CANDIDATES) {
            String removable = null;
            for (Map.Entry<String, Candidate> entry : candidates.entrySet()) {
                if (entry.getValue().bondState != BluetoothDevice.BOND_BONDED) {
                    removable = entry.getKey();
                    break;
                }
            }
            if (removable != null) candidates.remove(removable);
        }
        Candidate candidate = new Candidate(device, address,
                name.isEmpty() && old != null ? old.name : name,
                safeType(device), safeBondState(device),
                rssi <= -127 && old != null ? old.rssi : rssi,
                ancsSolicitation || old != null && old.ancsSolicitation,
                raw.isEmpty() && old != null ? old.rawAdvertisement : raw,
                old != null && "bonded".equals(old.origin) ? old.origin : origin);
        candidates.put(address, candidate);
        publishCandidates();
    }

    private void publishCandidates() {
        long now = android.os.SystemClock.uptimeMillis();
        long remaining = CANDIDATE_UI_INTERVAL_MS - (now - lastCandidatePublishAt);
        if (remaining <= 0 && !candidatePublishScheduled) {
            lastCandidatePublishAt = now;
            publishCandidatesNow();
            return;
        }
        if (candidatePublishScheduled) return;
        candidatePublishScheduled = true;
        main.postDelayed(candidatePublisher, Math.max(1L, remaining));
    }

    private void publishCandidatesNow() {
        List<Candidate> snapshot = new ArrayList<>(candidates.values());
        snapshot.sort(Comparator
                .comparing((Candidate value) -> !value.ancsSolicitation)
                .thenComparing(value -> value.bondState != BluetoothDevice.BOND_BONDED)
                .thenComparing((Candidate value) -> value.rssi, Comparator.reverseOrder()));
        listener.onCandidates(snapshot);
    }

    private void state(String value) {
        if (value != null && value.contains("ANCS READY")) {
            managedReconnectAttempt = 0;
            incomingClientAttachAttempt = 0;
            if (managedReconnectTask != null) main.removeCallbacks(managedReconnectTask);
            managedReconnectTask = null;
        }
        if (!closing && managedReconnectEnabled && managedSavedPeer != null
                && requiresControllerRetry(value)) {
            scheduleManagedReconnect(value);
            String recovery = REMOTE_LOGICAL_NAME + " · RECOVERING";
            listener.onState(recovery);
            log("STATE: " + recovery + " · reason=" + value);
            return;
        }
        if (!closing && !retrySignalled && requiresControllerRetry(value)) {
            retrySignalled = true;
            // Deliver the typed lifecycle signal first. The controller closes this transport and
            // advances its session barrier while processing it, so a later diagnostic-state
            // callback cannot accidentally become the only owner of reconnection.
            listener.onRetryRequired(value);
        }
        listener.onState(value);
        log("STATE: " + value);
    }

    /**
     * Keeps transient status 133/discovery/CCCD failures inside one serialized transport owner.
     * The outer controller is notified only for unrecoverable setup failures.
     */
    private void scheduleManagedReconnect(@NonNull String reason) {
        if (closing || !managedReconnectEnabled || managedSavedPeer == null
                || managedReconnectTask != null || coldBackgroundAttachTask != null) return;

        if (managedIncomingMode) {
            BluetoothDevice verified = getVerifiedPeer();
            if (verified != null && findConnectedServerPeer(verified) != null) {
                recoverIncomingClientRole(reason);
            } else {
                preserveManagedIncomingPublicationAfterLinkLoss(reason);
            }
            return;
        }

        BluetoothGatt establishedOwner = gatt;
        if (establishedOwner != null && activeClientEstablished
                && sessionState.isCurrent(activeClientGeneration)) {
            if (gattClientConnected) {
                restartDiscoveryOnPersistentOwner(establishedOwner, activeClientGeneration,
                        reason);
            } else {
                awaitPersistentGattReconnect(establishedOwner, activeClientGeneration, reason);
            }
            return;
        }

        // A client that never established cannot be retained. Close only that explicitly failed
        // attempt, then register a fresh background owner against the same bonded identity.
        // Established owners take the early branch above and are never destroyed here.
        BluetoothDevice activeResolved = activeClientTarget != null
                ? activeClientTarget : gatt == null ? null : gatt.getDevice();
        if (activeResolved != null) managedResolvedPeer = activeResolved;

        cancelAmbiguousAclProbe();
        stopScan();
        cancelClientAttemptCallbacks();
        clearAncsRuntime();
        clearIphonePeripheralRuntime(false);
        iphonePeripheralMode = true;
        helperBootstrapMode = false;
        iphoneConnectStarted = false;
        gattClientConnected = false;
        clientConnectInFlight = false;
        activeClientTarget = null;
        BluetoothGatt previous = gatt;
        gatt = null;
        if (previous != null) {
            try {
                previous.disconnect();
            } catch (RuntimeException ignored) {
            }
            try {
                previous.close();
            } catch (RuntimeException ignored) {
            }
        }

        int attempt = managedReconnectAttempt++;
        long delay = AncsReconnectPolicy.retryDelayMillis(attempt);
        BluetoothDevice expected = managedSavedPeer;
        long waitGeneration = sessionState.begin(AncsSessionStateMachine.Phase.RETRY_WAIT);
        managedReconnectTask = () -> {
            managedReconnectTask = null;
            if (closing || !managedReconnectEnabled || expected != managedSavedPeer
                    || !sessionState.isCurrent(waitGeneration)) return;
            iphonePeripheralMode = true;
            helperBootstrapMode = false;
            iphoneConnectStarted = false;
            boolean started = safeBondState(expected) == BluetoothDevice.BOND_BONDED
                    ? startManagedBackgroundAttach(expected,
                    "cold-owner retry #" + (attempt + 1) + " after " + reason)
                    : startSavedPeerScan(expected);
            if (!started) {
                scheduleManagedReconnect("background attach/fallback scan could not start");
            }
        };
        main.postDelayed(managedReconnectTask, delay);
        log("Одна serialized cold-owner recovery " + REMOTE_LOGICAL_NAME
                + " #" + (attempt + 1) + " через " + delay + " ms · " + reason);
    }

    /** Re-publishes Geely_ANCS after a failed/lost incoming route without touching Classic. */
    private void scheduleManagedIncomingRestart(@NonNull String reason) {
        if (closing || !managedReconnectEnabled || !managedIncomingMode
                || managedSavedPeer == null || managedReconnectTask != null) return;

        cancelAmbiguousAclProbe();
        stopScan();
        cancelClientAttemptCallbacks();
        clearAncsRuntime();
        gattClientConnected = false;
        clientConnectInFlight = false;
        activeClientTarget = null;
        BluetoothGatt previous = gatt;
        gatt = null;
        if (previous != null) {
            try {
                previous.disconnect();
            } catch (RuntimeException ignored) {
            }
            try {
                previous.close();
            } catch (RuntimeException ignored) {
            }
        }
        BluetoothDevice resolvedPeer = managedResolvedPeer;
        stopAdvertising();
        resetVerifiedPeerSession();
        managedIncomingMode = true;
        managedResolvedPeer = resolvedPeer;
        if (resolvedPeer != null) {
            log("Новая Geely_ANCS session не pre-claim'ит старый RPA "
                    + safeAddress(resolvedPeer) + "; жду PAIR от текущего incoming callback");
        }
        iphonePeripheralMode = false;
        helperBootstrapMode = false;

        int attempt = managedReconnectAttempt++;
        long delay = AncsReconnectPolicy.retryDelayMillis(attempt);
        BluetoothDevice expected = managedSavedPeer;
        long waitGeneration = sessionState.begin(AncsSessionStateMachine.Phase.RETRY_WAIT);
        managedReconnectTask = () -> {
            managedReconnectTask = null;
            if (closing || !managedReconnectEnabled || !managedIncomingMode
                    || expected != managedSavedPeer
                    || !sessionState.isCurrent(waitGeneration)) return;
            iphonePeripheralMode = false;
            helperBootstrapMode = false;
            if (!startGeelyAncsAdvertising()) {
                scheduleManagedIncomingRestart("Geely_ANCS advertising could not restart");
            }
        };
        main.postDelayed(managedReconnectTask, delay);
        log("Одна serialized Geely_ANCS recovery #" + (attempt + 1)
                + " через " + delay + " ms · " + reason);
    }

    private static boolean requiresControllerRetry(@Nullable String value) {
        if (value == null) return false;
        return value.contains("CONNECT RETURNED NULL")
                || value.contains("CONNECT TIMEOUT")
                || value.contains("CONNECT EXCEPTION")
                || value.contains("SAVED PEER SCAN UNAVAILABLE")
                || value.contains("SAVED PEER SCAN FAILED")
                || value.contains("SAVED PEER CONFLICT")
                || value.contains("PEER CONFLICT")
                || value.contains("CONNECTION FAILED")
                || value.contains("GPS-STYLE FAILED")
                || value.contains("IPHONE DISCONNECTED")
                || value.contains("SERVICE CHANGED · RECONNECT")
                || value.contains("DISCOVERY_FAILED_")
                || value.contains("DISCOVERY_START_FAILED")
                || value.contains("DISCOVERY_TIMEOUT")
                || value.contains("ANCS_INCOMPLETE")
                || value.contains("SUBSCRIBE_EXCEPTION")
                || value.contains("SUBSCRIBE_LOCAL_FAILED")
                || value.contains("CCCD_START_FAILED")
                || value.contains("CCCD_WRITE_EXCEPTION")
                || value.contains("CCCD_WRITE_TIMEOUT")
                || value.contains("CCCD_FAILED_")
                || value.contains("ANCS DATA DESYNC")
                || value.contains("ANCS WAIT TIMEOUT")
                || value.contains("SECURE READ FAILED")
                || value.contains("BOND_START_FAILED")
                || value.contains("LE BOND TIMEOUT")
                || value.contains("LE BOND FAILED")
                || value.contains("ATTEMPTS EXHAUSTED")
                || value.contains("PAIRING FAILED")
                || value.contains("ADVERTISE_FAILED_")
                || value.contains("ADVERTISE_EXCEPTION")
                || value.contains("GATT_SERVER_UNAVAILABLE")
                || value.contains("GATT_SERVICE_ADD_FAILED_")
                || value.contains("GATT_SERVICE_ADD_START_FAILED")
                || value.contains("GATT_CHARACTERISTIC_ADD_FAILED")
                || value.contains("AUTH FAILED ПОСЛЕ BOND");
    }

    private void log(String message) {
        String time = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date());
        String line = time + "  " + message;
        Log.i(LOG_TAG, line);
        listener.onLog(line);
    }

    private static boolean isAuthorizationError(int status) {
        return status == STATUS_INSUFFICIENT_AUTHENTICATION
                || status == STATUS_INSUFFICIENT_AUTHORIZATION
                || status == STATUS_INSUFFICIENT_KEY_SIZE
                || status == STATUS_INSUFFICIENT_ENCRYPTION
                || status == STATUS_GATT_AUTH_FAIL;
    }

    private static String shortUuid(UUID uuid) {
        if (uuid == null) return "null";
        String value = uuid.toString();
        return value.length() > 8 ? value.substring(0, 8) : value;
    }

    private static String safeAddress(BluetoothDevice device) {
        try {
            String value = device.getAddress();
            return value == null ? "" : value;
        } catch (RuntimeException denied) {
            return "";
        }
    }

    private static String safeName(BluetoothDevice device) {
        try {
            String value = device.getName();
            return value == null ? "" : value;
        } catch (RuntimeException denied) {
            return "";
        }
    }

    private static int safeType(BluetoothDevice device) {
        try {
            return device.getType();
        } catch (RuntimeException denied) {
            return BluetoothDevice.DEVICE_TYPE_UNKNOWN;
        }
    }

    private static int safeBondState(BluetoothDevice device) {
        try {
            return device.getBondState();
        } catch (RuntimeException denied) {
            return BluetoothDevice.BOND_NONE;
        }
    }

    private static String typeLabel(int type) {
        switch (type) {
            case BluetoothDevice.DEVICE_TYPE_CLASSIC: return "classic";
            case BluetoothDevice.DEVICE_TYPE_LE: return "LE";
            case BluetoothDevice.DEVICE_TYPE_DUAL: return "dual";
            default: return "unknown";
        }
    }

    private static String bondLabel(int bond) {
        switch (bond) {
            case BluetoothDevice.BOND_BONDED: return "BONDED";
            case BluetoothDevice.BOND_BONDING: return "BONDING";
            default: return "NONE";
        }
    }

    private static String value(Map<String, String> values, String key) {
        String result = values.get(key);
        return result == null ? "" : result;
    }

    private static boolean advertisesService(ScanRecord record, UUID serviceUuid) {
        if (record == null) return false;
        List<ParcelUuid> values = record.getServiceUuids();
        if (values == null) return false;
        ParcelUuid expected = new ParcelUuid(serviceUuid);
        return values.contains(expected);
    }

    private boolean matchesManagedSavedPeer(@NonNull BluetoothDevice selected,
                                            @NonNull BluetoothDevice observed,
                                            boolean solicitsAncs,
                                            boolean advertisesHelperService) {
        return AncsReconnectPolicy.candidateMayBeSelected(
                safeAddress(selected), safeAddress(observed),
                safeBondState(selected) == BluetoothDevice.BOND_BONDED,
                safeBondState(observed) == BluetoothDevice.BOND_BONDED,
                solicitsAncs, uniqueBondedNameMatch(selected, observed),
                sameDevice(managedResolvedPeer, observed), advertisesHelperService,
                REMOTE_LOGICAL_NAME.equalsIgnoreCase(safeName(selected).trim()));
    }

    /**
     * Name is only a supporting tie-breaker after both bond and ANCS-service checks. It is never
     * accepted as the identity by itself.
     */
    private boolean uniqueBondedNameMatch(@NonNull BluetoothDevice selected,
                                          @NonNull BluetoothDevice observed) {
        String selectedName = safeName(selected).trim();
        String observedName = safeName(observed).trim();
        if (selectedName.isEmpty() || !selectedName.equals(observedName)
                || adapter == null) return false;
        Set<BluetoothDevice> bonded;
        try {
            bonded = adapter.getBondedDevices();
        } catch (RuntimeException denied) {
            return false;
        }
        int matchingNames = 0;
        if (bonded != null) {
            for (BluetoothDevice candidate : bonded) {
                if (selectedName.equals(safeName(candidate).trim())) matchingNames++;
            }
        }
        return matchingNames == 1;
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            main.post(() -> {
                ScanRecord record = result.getScanRecord();
                byte[] raw = record == null ? null : record.getBytes();
                AdvertisementParser.Parsed parsed = AdvertisementParser.parse(raw);
                boolean solicitsAncs = parsed.solicits(AncsProtocol.SERVICE);
                boolean helperService = advertisesService(record, DIAGNOSTIC_SERVICE);
                updateCandidate(result.getDevice(), result.getRssi(), solicitsAncs,
                        parsed.hex, helperService ? "iPhone_ANCS Helper service"
                                : solicitsAncs ? "ANCS solicitation" : "scan");
                BluetoothDevice savedTarget = savedPeerScanTarget;
                if (iphonePeripheralMode && !helperBootstrapMode
                        && savedTarget != null && !iphoneConnectStarted
                        && matchesManagedSavedPeer(
                        savedTarget, result.getDevice(), solicitsAncs, helperService)) {
                    log("Identity-resolved saved-peer match: RSSI=" + result.getRssi()
                            + " selected=" + safeAddress(savedTarget)
                            + " observed=" + safeAddress(result.getDevice())
                            + " helperService=" + helperService
                            + " ancsSolicitation=" + solicitsAncs
                            + " bond=" + bondLabel(safeBondState(result.getDevice())));
                    connectToSavedAdvertisingIphone(result.getDevice(), solicitsAncs,
                            helperService);
                    return;
                }
                if (iphonePeripheralMode
                        && helperBootstrapMode && helperService
                        && !iphoneConnectStarted) {
                    log("GPS-style scan match: KX11-iPhone RSSI=" + result.getRssi()
                            + " address=" + safeAddress(result.getDevice()));
                    connectToAdvertisingIphone(result.getDevice());
                    return;
                }
                if (solicitsAncs) {
                    log("Найдена ANCS solicitation: " + safeAddress(result.getDevice())
                            + " raw=" + parsed.hex);
                }
            });
        }

        @Override
        public void onBatchScanResults(List<ScanResult> results) {
            for (ScanResult result : results) onScanResult(0, result);
        }

        @Override
        public void onScanFailed(int errorCode) {
            main.post(() -> {
                if (scanTimeout != null) main.removeCallbacks(scanTimeout);
                scanTimeout = null;
                boolean savedPeerScan = savedPeerScanTarget != null
                        && iphonePeripheralMode && !helperBootstrapMode;
                scanning = false;
                savedPeerScanTarget = null;
                state(savedPeerScan
                        ? "AUTO · SAVED PEER SCAN FAILED_" + errorCode
                        : "SCAN_FAILED_" + errorCode);
                log("onScanFailed " + errorCode + ": " + scanError(errorCode));
            });
        }
    };

    private final AdvertiseCallback advertiseCallback = new AdvertiseCallback() {
        @Override
        public void onStartSuccess(AdvertiseSettings settingsInEffect) {
            main.post(() -> {
                advertisingPending = false;
                if (!advertisingDesired) {
                    if (advertiser != null) {
                        try {
                            advertiser.stopAdvertising(advertiseCallback);
                        } catch (RuntimeException ignored) {
                        }
                    }
                    advertising = false;
                    log("Поздний onStartSuccess остановлен: реклама уже была отменена");
                    return;
                }
                advertising = true;
                state(solicitationAdvertising
                        ? "ANCS SOLICITATION REQUESTED"
                        : managedReconnectEnabled
                        ? LOCAL_LOGICAL_NAME + " · ADVERTISING"
                        : "DIAGNOSTIC ADV ACTIVE");
                log("onStartSuccess mode=" + settingsInEffect.getMode()
                        + " tx=" + settingsInEffect.getTxPowerLevel()
                        + " connectable=" + settingsInEffect.isConnectable());
                if (solicitationAdvertising) {
                    log("Callback подтверждает запуск рекламы, но не AD type 0x15. "
                            + "Проверьте эфир вторым BLE-сканером");
                }
            });
        }

        @Override
        public void onStartFailure(int errorCode) {
            main.post(() -> {
                advertising = false;
                advertisingPending = false;
                advertisingDesired = false;
                solicitationAdvertising = false;
                state("ADVERTISE_FAILED_" + errorCode);
                log("onStartFailure " + errorCode + ": " + advertiseError(errorCode));
                clearPreparedAdvertising();
                closeGattServer();
                if (managedReconnectEnabled && !scanning
                        && !clientConnectInFlight && !gattClientConnected) {
                    scheduleManagedReconnect("advertising failed " + errorCode);
                }
            });
        }
    };

    private void sendGattServerResponse(BluetoothDevice device, int requestId,
                                        int status, int offset, byte[] value) {
        BluetoothGattServer server = gattServer;
        if (server == null) return;
        try {
            boolean sent = server.sendResponse(device, requestId, status, offset, value);
            if (!sent) {
                main.post(() -> log("GATT server sendResponse=false status=" + status
                        + " peer=" + safeAddress(device)));
            }
        } catch (RuntimeException failure) {
            main.post(() -> log("GATT server sendResponse exception: " + failure));
        }
    }

    private void sendGattReadResponse(BluetoothDevice device, int requestId,
                                      int offset, byte[] fullValue) {
        if (offset < 0 || offset > fullValue.length) {
            sendGattServerResponse(device, requestId,
                    BluetoothGatt.GATT_INVALID_OFFSET, 0, null);
            return;
        }
        byte[] response = offset == fullValue.length
                ? new byte[0]
                : AdvertisementParser.copyOfRange(fullValue, offset, fullValue.length);
        sendGattServerResponse(device, requestId,
                BluetoothGatt.GATT_SUCCESS, offset, response);
    }

    private static String asciiCommand(byte[] value) {
        if (value == null) return "";
        return new String(value, StandardCharsets.UTF_8)
                .trim()
                .toUpperCase(Locale.US);
    }

    private final BluetoothGattServerCallback gattServerCallback =
            new BluetoothGattServerCallback() {
                @Override
                public void onServiceAdded(int status, BluetoothGattService service) {
                    main.post(() -> {
                        log("GATT server service added status=" + status
                                + " uuid=" + service.getUuid());
                        if (!serverDiagnosticService.equals(service.getUuid())) return;
                        if (status != GATT_SUCCESS) {
                            advertisingDesired = false;
                            state("GATT_SERVICE_ADD_FAILED_" + status);
                            clearPreparedAdvertising();
                            closeGattServer();
                            return;
                        }
                        startPreparedAdvertising();
                    });
                }

                @Override
                public void onConnectionStateChange(BluetoothDevice device,
                                                    int status, int newState) {
                    main.post(() -> {
                        recordGattServerPeer(device, status, newState);
                        log("GATT SERVER LINK: session=" + sessionGeneration
                                + " peer=" + safeAddress(device)
                                + " objectId=" + System.identityHashCode(device)
                                + " status=" + status + " newState=" + newState
                                + " type=" + typeLabel(safeType(device))
                                + " bond=" + bondLabel(safeBondState(device)));
                        updateCandidate(device, -127, false, "", "gatt-server-link");
                        if (status == GATT_SUCCESS
                                && newState == BluetoothProfile.STATE_CONNECTED) {
                            state(managedReconnectEnabled
                                    ? REMOTE_LOGICAL_NAME + " · INCOMING LINK"
                                    : "GATT SERVER LINK · В LIGHTBLUE ЗАПИШИТЕ PAIR");
                            log(managedReconnectEnabled
                                    ? REMOTE_LOGICAL_NAME
                                    + " подключился к стабильному link-anchor "
                                    + LOCAL_LOGICAL_NAME
                                    + "; жду PAIR/B3 current-link proof"
                                    : "Peer станет verified только после ASCII PAIR в CONTROL "
                                    + serverControlCharacteristic);
                            if (managedIncomingMode) {
                                attachAncsClientToIncomingOwner(device);
                            } else {
                                log("Diagnostic link ждёт явный PAIR/B3 challenge");
                            }
                        } else if (newState == BluetoothProfile.STATE_DISCONNECTED
                                && isVerifiedPeer(device)) {
                            handleVerifiedServerLinkDisconnected(device);
                        } else if (newState == BluetoothProfile.STATE_DISCONNECTED
                                && managedIncomingMode && getVerifiedPeer() == null) {
                            // Keep the stable advertiser alive. The iPhone owns reconnect and will
                            // return to this same anchor; rotating the UUID deadlocked v35.
                            log("Incoming link закрылся до adoption; стабильная реклама сохранена");
                        }
                    });
                }

                @Override
                public void onCharacteristicReadRequest(BluetoothDevice device,
                                                        int requestId, int offset,
                                                        BluetoothGattCharacteristic characteristic) {
                    UUID uuid = characteristic == null ? null : characteristic.getUuid();
                    main.post(() -> log("GATT SERVER READ raw: session="
                            + sessionGeneration
                            + " peer=" + safeAddress(device)
                            + " requestId=" + requestId
                            + " offset=" + offset
                            + " uuid=" + uuid
                            + " type=" + typeLabel(safeType(device))
                            + " bond=" + bondLabel(safeBondState(device))));
                    if (serverDiagnosticCharacteristic.equals(uuid)) {
                        sendGattReadResponse(device, requestId, offset,
                                (LOCAL_LOGICAL_NAME + "/3")
                                        .getBytes(StandardCharsets.UTF_8));
                        return;
                    }
                    if (serverSecureCharacteristic.equals(uuid)) {
                        if (!isVerifiedPeer(device)) {
                            sendGattServerResponse(device, requestId,
                                    STATUS_INSUFFICIENT_AUTHORIZATION, 0, null);
                            main.post(() -> log("SECURE READ отклонён: peer не verified · "
                                    + safeAddress(device)));
                            return;
                        }
                        if (issueCurrentLinkSecurityChallenge(device)) {
                            sendGattServerResponse(device, requestId,
                                    STATUS_INSUFFICIENT_AUTHENTICATION, 0, null);
                            main.post(() -> log("CURRENT LINK SECURITY CHALLENGE · первая B3 READ "
                                    + "получила ATT status=5 · peer=" + safeAddress(device)));
                            return;
                        }
                        Boolean first = markSecureAttConfirmed(device);
                        if (first == null) {
                            sendGattServerResponse(device, requestId,
                                    STATUS_INSUFFICIENT_AUTHORIZATION, 0, null);
                            main.post(() -> log("SECURE READ потерял verified session до ответа"));
                            return;
                        }
                        // The proof must be visible before this success reaches Core Bluetooth:
                        // Helper writes ANCS-READY immediately on the same RequiresANCS owner.
                        sendGattReadResponse(device, requestId, offset,
                                "SECURE ATT OK".getBytes(StandardCharsets.UTF_8));
                        main.post(() -> finishSecureAttSuccess(
                                device, "READ", first.booleanValue()));
                        return;
                    }
                    if (serverTelemetryCharacteristicUuid.equals(uuid)) {
                        if (!isVerifiedPeer(device)) {
                            sendGattServerResponse(device, requestId,
                                    STATUS_INSUFFICIENT_AUTHORIZATION, 0, null);
                            return;
                        }
                        sendGattReadResponse(device, requestId, offset,
                                "TEL3;-;-;X;-;0".getBytes(StandardCharsets.UTF_8));
                        return;
                    }
                    sendGattServerResponse(device, requestId,
                            BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, 0, null);
                }

                @Override
                public void onDescriptorReadRequest(BluetoothDevice device, int requestId,
                                                    int offset,
                                                    BluetoothGattDescriptor descriptor) {
                    UUID descriptorUuid = descriptor == null ? null : descriptor.getUuid();
                    BluetoothGattCharacteristic characteristic = descriptor == null
                            ? null : descriptor.getCharacteristic();
                    UUID characteristicUuid = characteristic == null
                            ? null : characteristic.getUuid();
                    if (!AncsProtocol.CLIENT_CONFIGURATION.equals(descriptorUuid)
                            || !serverTelemetryCharacteristicUuid.equals(characteristicUuid)) {
                        sendGattServerResponse(device, requestId,
                                BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, 0, null);
                        return;
                    }
                    if (!isVerifiedPeer(device)) {
                        sendGattServerResponse(device, requestId,
                                STATUS_INSUFFICIENT_AUTHORIZATION, 0, null);
                        return;
                    }
                    byte[] value = isServerTelemetrySubscribed(device)
                            ? BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            : BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE;
                    sendGattReadResponse(device, requestId, offset, value);
                }

                @Override
                public void onDescriptorWriteRequest(BluetoothDevice device, int requestId,
                                                     BluetoothGattDescriptor descriptor,
                                                     boolean preparedWrite,
                                                     boolean responseNeeded,
                                                     int offset, byte[] value) {
                    UUID descriptorUuid = descriptor == null ? null : descriptor.getUuid();
                    BluetoothGattCharacteristic characteristic = descriptor == null
                            ? null : descriptor.getCharacteristic();
                    UUID characteristicUuid = characteristic == null
                            ? null : characteristic.getUuid();
                    byte[] rawValue = value == null ? null : value.clone();
                    boolean enable = Arrays.equals(rawValue,
                            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    boolean disable = Arrays.equals(rawValue,
                            BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE);
                    int status = BluetoothGatt.GATT_SUCCESS;
                    if (!AncsProtocol.CLIENT_CONFIGURATION.equals(descriptorUuid)
                            || !serverTelemetryCharacteristicUuid.equals(characteristicUuid)) {
                        status = BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED;
                    } else if (preparedWrite) {
                        status = BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED;
                    } else if (offset != 0) {
                        status = BluetoothGatt.GATT_INVALID_OFFSET;
                    } else if (!isVerifiedPeer(device)) {
                        status = STATUS_INSUFFICIENT_AUTHORIZATION;
                    } else if (!enable && !disable) {
                        status = BluetoothGatt.GATT_FAILURE;
                    }
                    if (responseNeeded) {
                        sendGattServerResponse(device, requestId, status, 0, null);
                    }
                    final int result = status;
                    main.post(() -> {
                        log("GATT SERVER B4 CCCD write: status=" + result
                                + " enable=" + enable
                                + " peer=" + safeAddress(device));
                        if (result == BluetoothGatt.GATT_SUCCESS) {
                            setServerTelemetrySubscription(device, enable);
                        }
                    });
                }

                @Override
                public void onNotificationSent(BluetoothDevice device, int status) {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        main.post(() -> log("B4 wake-poll notification status=" + status
                                + " peer=" + safeAddress(device)));
                    }
                }

                @Override
                public void onCharacteristicWriteRequest(
                        BluetoothDevice device, int requestId,
                        BluetoothGattCharacteristic characteristic,
                        boolean preparedWrite, boolean responseNeeded,
                        int offset, byte[] value) {
                    UUID uuid = characteristic == null ? null : characteristic.getUuid();
                    byte[] rawValue = value == null ? null : value.clone();
                    IphoneHelperTelemetry diagnosticTelemetry =
                            IphoneHelperTelemetry.parse(rawValue);
                    main.post(() -> log(diagnosticTelemetry == null
                            ? "GATT SERVER WRITE raw: session=" + sessionGeneration
                            + " peer=" + safeAddress(device)
                            + " requestId=" + requestId
                            + " offset=" + offset
                            + " prepared=" + preparedWrite
                            + " responseNeeded=" + responseNeeded
                            + " uuid=" + uuid
                            + " len=" + (rawValue == null ? 0 : rawValue.length)
                            + " hex=" + AdvertisementParser.hex(rawValue, 80)
                            + " ascii=`" + asciiCommand(rawValue) + "`"
                            + " type=" + typeLabel(safeType(device))
                            + " bond=" + bondLabel(safeBondState(device))
                            : "GATT SERVER WRITE TELEMETRY: kind=" + diagnosticTelemetry.kind
                            + " seq=" + diagnosticTelemetry.sequence
                            + " peer=" + safeAddress(device)));
                    int status = BluetoothGatt.GATT_SUCCESS;
                    Runnable successAction = null;

                    if (preparedWrite) {
                        status = BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED;
                    } else if (offset != 0) {
                        status = BluetoothGatt.GATT_INVALID_OFFSET;
                    } else if (serverControlCharacteristic.equals(uuid)) {
                        String command = asciiCommand(value);
                        if (!"PAIR".equals(command)) {
                            status = BluetoothGatt.GATT_FAILURE;
                            main.post(() -> log("CONTROL command отклонена: `" + command
                                    + "`; ожидается ASCII PAIR"));
                        } else if (!claimVerifiedPeer(device)) {
                            status = STATUS_INSUFFICIENT_AUTHORIZATION;
                            main.post(() -> log("PAIR отклонён: verified peer уже зафиксирован, "
                                    + "чужой callback " + safeAddress(device)
                                    + " его не заменит"));
                        } else {
                            successAction = () -> handlePairCommand(device);
                        }
                    } else if (serverSecureCharacteristic.equals(uuid)
                            || serverTelemetryCharacteristicUuid.equals(uuid)) {
                        String command = asciiCommand(value);
                        IphoneHelperTelemetry telemetry = IphoneHelperTelemetry.parse(value);
                        if (serverSecureCharacteristic.equals(uuid)
                                && "ANCS-READY".equals(command)) {
                            if (!canAcceptAncsReady(device)) {
                                status = STATUS_INSUFFICIENT_AUTHORIZATION;
                                main.post(() -> log("ANCS-READY отклонён: нет защищённого "
                                        + "exact incoming link · " + safeAddress(device)));
                            } else {
                                successAction = () -> confirmAncsReady(device);
                            }
                        } else if (!isVerifiedPeer(device)) {
                            status = STATUS_INSUFFICIENT_AUTHORIZATION;
                            main.post(() -> log("SECURE WRITE отклонён: peer не verified · "
                                    + safeAddress(device)));
                        } else if (telemetry != null) {
                            successAction = () -> {
                                listener.onHelperTelemetry(telemetry);
                                log("Helper telemetry accepted: kind=" + telemetry.kind
                                        + " seq=" + telemetry.sequence);
                            };
                        } else if (serverTelemetryCharacteristicUuid.equals(uuid)) {
                            status = BluetoothGatt.GATT_FAILURE;
                            main.post(() -> log("TELEMETRY write rejected: malformed TEL3/TEL2"));
                        } else if (!"ANCS".equals(command)) {
                            status = BluetoothGatt.GATT_FAILURE;
                            main.post(() -> log("SECURE command отклонена: `" + command
                                    + "`; ожидается ASCII ANCS, ANCS-READY "
                                    + "или TEL2/TEL3"));
                        } else {
                            successAction = () -> handleSecureAttSuccess(device, "WRITE");
                        }
                    } else {
                        status = BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED;
                    }

                    if (responseNeeded) {
                        sendGattServerResponse(device, requestId, status, 0, null);
                    }
                    if (status == BluetoothGatt.GATT_SUCCESS && successAction != null) {
                        main.post(successAction);
                    }
                }
            };

    private void handleIphonePeripheralConnectionState(BluetoothGatt callbackGatt,
                                                       int status, int newState) {
        long callbackGeneration = activeClientGeneration;
        if (!sessionState.isCurrent(callbackGeneration)) {
            log("Игнорирую GATT callback устаревшей session generation");
            closeClientGatt(callbackGatt);
            return;
        }
        if (managedReconnectEnabled && callbackGatt.getDevice() != null) {
            managedResolvedPeer = callbackGatt.getDevice();
        }
        boolean establishedOwner = activeClientEstablished;
        if (status != GATT_SUCCESS) {
            if (establishedOwner) {
                log("Established GATT callback status=" + status
                        + "; сохраняю owner и жду системный reconnect");
                awaitPersistentGattReconnect(callbackGatt, callbackGeneration,
                        "established GATT status=" + status);
                return;
            }
            cancelConnectTimeout();
            clientConnectInFlight = false;
            gattClientConnected = false;
            closeClientGatt(callbackGatt);
            clearAncsRuntime();
            if (status == 19 && ancsAuthorizationFailureSeen) {
                state("ANCS PAIRING FAILED · IPHONE CLOSED LINK");
                log("iPhone закрыл BLE link (status=19/0x13) после неуспешной "
                        + "ANCS authorization/SMP");
            } else {
                state("GPS-STYLE FAILED · status=" + status);
                log("Прямое Android-central подключение завершилось ошибкой " + status);
                if (managedReconnectEnabled) {
                    scheduleManagedReconnect("direct GATT status=" + status);
                }
            }
            return;
        }
        if (newState == BluetoothProfile.STATE_CONNECTED) {
            cancelConnectTimeout();
            cancelAmbiguousAclProbe();
            clientConnectInFlight = false;
            gattClientConnected = true;
            activeClientEstablished = true;
            state(activeClientAutoConnect
                    ? "IPHONE BLE CONNECTED · BACKGROUND"
                    : "IPHONE BLE CONNECTED · DIRECT");
            log("Android создал единственный BLE link; начинаю GATT discovery");
            discoverServices(callbackGatt);
        } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
            cancelConnectTimeout();
            cancelAmbiguousAclProbe();
            clientConnectInFlight = false;
            gattClientConnected = false;
            if (establishedOwner) {
                log("Established GATT link disconnected normally; "
                        + "не закрываю зарегистрированный owner");
                awaitPersistentGattReconnect(callbackGatt, callbackGeneration,
                        "normal established GATT disconnect");
                return;
            }
            closeClientGatt(callbackGatt);
            clearAncsRuntime();
            state("GPS-STYLE · IPHONE DISCONNECTED");
            log("Первичный direct GATT не установился; возвращаюсь к Helper scan");
            if (managedReconnectEnabled) {
                scheduleManagedReconnect("initial direct GATT disconnected");
            }
        }
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt callbackGatt,
                                            int status, int newState) {
            // A busy diagnostic UI must not let the bounded timeout overtake a callback
            // that the Bluetooth stack has already delivered.
            main.post(() -> {
                if (callbackGatt != gatt) return;
                log("onConnectionStateChange status=" + status
                        + " newState=" + newState
                        + " device=" + safeAddress(callbackGatt.getDevice())
                        + " objectId=" + System.identityHashCode(callbackGatt.getDevice())
                        + " autoConnect=" + activeClientAutoConnect
                        + " transport=TRANSPORT_LE");
                if (iphonePeripheralMode) {
                    handleIphonePeripheralConnectionState(callbackGatt, status, newState);
                    return;
                }
                if (status != GATT_SUCCESS) {
                    boolean attachWasInFlight = clientConnectInFlight;
                    boolean failedBackgroundAttach =
                            attachWasInFlight && activeClientAutoConnect;
                    cancelConnectTimeout();
                    gattClientConnected = false;
                    if (managedIncomingMode) {
                        if (!activeClientEstablished) {
                            // Before the first CONNECTED callback there is no durable clientIf to
                            // re-arm. Close only this wrapper and repeat the same direct virtual
                            // open with the exact bonded incoming facade.
                            clientConnectInFlight = false;
                            closeClientGatt(callbackGatt);
                            clearAncsRuntime();
                            incomingDiscoveryStarted = false;
                            scheduleIncomingClientAttachRetry(
                                    "initial direct attach status=" + status);
                        } else {
                            // Only a clientIf that has reached CONNECTED is durable. Re-arm that
                            // same object indefinitely after later radio loss/status 133.
                            awaitIncomingBackgroundOwner(callbackGatt,
                                    activeClientGeneration,
                                    "established same-peer GATT status=" + status);
                        }
                        return;
                    }
                    clientConnectInFlight = false;
                    closeClientGatt(callbackGatt);
                    clearAncsRuntime();
                    state("GATT CONNECTION FAILED · status=" + status);
                    if (failedBackgroundAttach) {
                        scheduleDirectFallback("background attach status=" + status);
                    } else if (attachWasInFlight) {
                        state("V6 ATTEMPTS EXHAUSTED");
                    }
                } else if (newState == BluetoothProfile.STATE_CONNECTED) {
                    cancelConnectTimeout();
                    clientConnectInFlight = false;
                    gattClientConnected = true;
                    activeClientEstablished = true;
                    if (managedIncomingMode) {
                        state(incomingAncsReadyGateOpen
                                ? "SAME-PEER DIRECT CLIENT ATTACHED · READY GATE OPEN"
                                : "SAME-PEER DIRECT CLIENT ATTACHED · ЖДУ ANCS-READY");
                        log("Direct GATT clientIf attached к exact bonded incoming peer; "
                                + (incomingAncsReadyGateOpen
                                ? "valid ANCS-READY уже получен"
                                : "service discovery намеренно не запускается до ANCS-READY"));
                        maybeStartIncomingAncsDiscovery(callbackGatt,
                                "onConnectionStateChange CONNECTED");
                    } else {
                        state("SAME-PEER GATT CONNECTED");
                        log("GATT client зарегистрирован на exact verified peer; "
                                + "discoverServices сразу, без requestMtu");
                        discoverServices(callbackGatt);
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    boolean attachWasInFlight = clientConnectInFlight;
                    boolean failedBackgroundAttach =
                            attachWasInFlight && activeClientAutoConnect;
                    boolean establishedOwner = activeClientEstablished;
                    cancelConnectTimeout();
                    gattClientConnected = false;
                    if (managedIncomingMode) {
                        if (establishedOwner) {
                            awaitIncomingBackgroundOwner(callbackGatt,
                                    activeClientGeneration,
                                    "established same-peer GATT disconnected");
                        } else {
                            clientConnectInFlight = false;
                            closeClientGatt(callbackGatt);
                            clearAncsRuntime();
                            incomingDiscoveryStarted = false;
                            scheduleIncomingClientAttachRetry(
                                    "initial direct attach disconnected");
                        }
                        return;
                    }
                    clientConnectInFlight = false;
                    closeClientGatt(callbackGatt);
                    clearAncsRuntime();
                    state("GATT DISCONNECTED · status=" + status);
                    if (failedBackgroundAttach) {
                        scheduleDirectFallback("background attach disconnected");
                    } else if (attachWasInFlight) {
                        state("V6 ATTEMPTS EXHAUSTED");
                    }
                }
            });
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt callbackGatt, int status) {
            main.post(() -> handleServices(callbackGatt, status));
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt callbackGatt,
                                      BluetoothGattDescriptor descriptor, int status) {
            main.post(() -> handleDescriptorWrite(callbackGatt, descriptor, status));
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt callbackGatt,
                                            BluetoothGattCharacteristic characteristic) {
            byte[] copy = characteristic.getValue() == null
                    ? null : characteristic.getValue().clone();
            main.post(() -> {
                characteristic.setValue(copy);
                handleCharacteristicChanged(callbackGatt, characteristic);
            });
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt callbackGatt,
                                          BluetoothGattCharacteristic characteristic,
                                          int status) {
            main.post(() -> {
                if (callbackGatt != gatt) return;
                log("onCharacteristicWrite " + shortUuid(characteristic.getUuid())
                        + " status=" + status);
                if (TELEMETRY_RELAY_CHARACTERISTIC.equals(characteristic.getUuid())
                        && helperAncsReadyProofPending) {
                    helperAncsReadyProofPending = false;
                    if (status == GATT_SUCCESS) {
                        helperAncsReadyProofAcknowledged = true;
                        if (helperAncsReadyProofRetry != null) {
                            main.removeCallbacks(helperAncsReadyProofRetry);
                            helperAncsReadyProofRetry = null;
                        }
                        log("Helper подтвердил ANCS-SUBSCRIBED после реального B4; "
                                + "reverse route полностью готов");
                        state("ANCS READY · B4 VERIFIED · ОТПРАВЬТЕ УВЕДОМЛЕНИЕ");
                        finishAncsReadySetup(callbackGatt);
                    } else {
                        helperAncsReadyProofAttempted = false;
                        log("Helper отклонил ANCS-SUBSCRIBED status=" + status
                                + "; повторяю на живом owner");
                        scheduleHelperAncsReadyProofRetry(callbackGatt,
                                "write status=" + status);
                        prepareBatteryBootstrap(callbackGatt);
                        sendNextRequest();
                    }
                    return;
                }
                if (iphonePeripheralMode
                        && CONTROL_CHARACTERISTIC.equals(characteristic.getUuid())) {
                    iphonePairWritePending = false;
                    if (status == GATT_SUCCESS) {
                        log("GPS-style PAIR принят iPhone helper");
                    } else {
                        log("GPS-style PAIR write status=" + status
                                + "; всё равно проверяю SECURE");
                    }
                    readIphoneSecure(callbackGatt);
                    return;
                }
                if (status != GATT_SUCCESS && activeRequest != null) {
                    if (isAuthorizationError(status)) requestBond(getVerifiedPeer());
                    finishRequest("write_status_" + status);
                }
            });
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt callbackGatt,
                                         BluetoothGattCharacteristic characteristic,
                                         int status) {
            byte[] copy = characteristic.getValue() == null
                    ? null : characteristic.getValue().clone();
            main.post(() -> {
                if (callbackGatt != gatt) return;
                UUID uuid = characteristic.getUuid();
                if ((TELEMETRY_CHARACTERISTIC.equals(uuid)
                        || TELEMETRY_RELAY_CHARACTERISTIC.equals(uuid))
                        && iphoneHelperTelemetryReadPending) {
                    cancelHelperTelemetryReadTimeout();
                    iphoneHelperTelemetryReadPending = false;
                    boolean resumeServiceSetup = iphoneServiceSetupDeferredForHelperRead;
                    iphoneServiceSetupDeferredForHelperRead = false;
                    IphoneHelperTelemetry telemetry = status == GATT_SUCCESS
                            ? IphoneHelperTelemetry.parse(copy) : null;
                    boolean proofStarted = false;
                    if (telemetry != null) {
                        proofStarted = acceptHelperTelemetryFrame(
                                callbackGatt, telemetry, "atomic read");
                    } else {
                        log("Helper B4 atomic read unavailable · status=" + status
                                + " value=" + AdvertisementParser.hex(copy, 80));
                    }
                    if (resumeServiceSetup) {
                        handleServices(callbackGatt, GATT_SUCCESS);
                        return;
                    }
                    if (proofStarted) return;
                    scheduleHelperTelemetryRecovery(callbackGatt, HELPER_TELEMETRY_POLL_MS);
                    sendNextRequest();
                    return;
                }
                if ((BATTERY_LEVEL.equals(uuid) || BATTERY_LEVEL_STATUS.equals(uuid))
                        && uuid.equals(batteryReadPendingUuid)) {
                    cancelBatteryReadTimeout();
                    batteryReadPendingUuid = null;
                    if (status == GATT_SUCCESS && copy != null) {
                        listener.onBatteryCharacteristic(uuid, copy);
                        log("BAS read complete · " + shortUuid(uuid)
                                + " value=" + AdvertisementParser.hex(copy, 16));
                    } else {
                        log("BAS optional read skipped · " + shortUuid(uuid)
                                + " status=" + status);
                    }
                    sendNextRequest();
                    return;
                }
                if (!iphonePeripheralMode
                        || !SECURE_CHARACTERISTIC.equals(uuid)) {
                    return;
                }
                iphoneSecureReadPending = false;
                String text = copy == null
                        ? ""
                        : new String(copy, StandardCharsets.UTF_8);
                log("GPS-style READ SECURE status=" + status
                        + " value=`" + text + "`"
                        + " bond=" + bondLabel(safeBondState(callbackGatt.getDevice())));
                if (status == GATT_SUCCESS) {
                    iphoneSecureConfirmed = true;
                    state("SECURE IPHONE OK · ИЩУ ANCS");
                    if (!startOptionalHelperTelemetrySubscription(callbackGatt)) {
                        scheduleIphonePostSecureDiscovery(callbackGatt);
                    }
                } else if (isAuthorizationError(status)) {
                    state("GPS-LINK · НУЖЕН LE BOND");
                    log("SECURE требует шифрование; запускаю bonding на текущем BLE link");
                    if (!leBondAttemptObserved) {
                        requestBond(callbackGatt.getDevice());
                    } else {
                        log("LE bond уже запускался; повтор SECURE pairing не запускаю");
                    }
                } else {
                    state("GPS-LINK · SECURE READ FAILED " + status);
                }
            });
        }

        @Override
        public void onReadRemoteRssi(BluetoothGatt callbackGatt, int rssi, int status) {
            main.post(() -> {
                if (callbackGatt != gatt || callbackGatt != linkProbeGatt
                        || !sessionState.isCurrent(linkProbeGeneration)) return;
                long generation = linkProbeGeneration;
                cancelAmbiguousAclProbe();
                if (status == GATT_SUCCESS && gattClientConnected && gattReady) {
                    sessionState.move(generation, AncsSessionStateMachine.Phase.READY);
                    log("GATT liveness probe OK, RSSI=" + rssi
                            + "; неоднозначный ACL loss не затронул ANCS");
                } else {
                    log("GATT liveness probe failed status=" + status);
                    scheduleManagedReconnect("ambiguous ACL liveness probe failed status="
                            + status);
                    state(REMOTE_LOGICAL_NAME + " · RECOVERING");
                }
            });
        }
    };

    private final BroadcastReceiver bondReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context receiverContext, Intent intent) {
            BluetoothDevice device =
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            if (device == null) return;
            if (!isVerifiedPeer(device)) {
                log("BOND event проигнорирован для non-verified peer: "
                        + safeAddress(device));
                return;
            }
            if (BluetoothDevice.ACTION_PAIRING_REQUEST.equals(intent.getAction())) {
                int variant = intent.getIntExtra(
                        BluetoothDevice.EXTRA_PAIRING_VARIANT, -1);
                int key = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_KEY, -1);
                leBondAttemptObserved = true;
                state("PAIRING REQUEST · " + pairingVariantLabel(variant));
                log("ACTION_PAIRING_REQUEST peer=" + safeAddress(device)
                        + " variant=" + variant + " (" + pairingVariantLabel(variant) + ")"
                        + (key >= 0 ? " key=" + String.format(Locale.US, "%06d", key) : ""));
                log("Подтвердите системный запрос на магнитоле и iPhone; "
                        + "приложение не перехватывает broadcast");
                return;
            }
            int state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE,
                    BluetoothDevice.BOND_NONE);
            int previous = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE,
                    BluetoothDevice.BOND_NONE);
            log("BOND " + safeAddress(device) + ": "
                    + bondLabel(previous) + " → " + bondLabel(state)
                    + (state == BluetoothDevice.BOND_NONE && previous == BluetoothDevice.BOND_BONDING
                    ? " · системный bond завершён без BOND_BONDED"
                    : ""));
            updateCandidate(device, -127, false, "", "bond event");
            if (state == BluetoothDevice.BOND_BONDING) {
                leBondAttemptObserved = true;
                scheduleBondTimeout(device);
                state(iphonePeripheralMode
                        ? "GPS-LINK · LE BONDING"
                        : "VERIFIED PEER · LE BONDING");
            } else if (state == BluetoothDevice.BOND_BONDED) {
                cancelBondTimeout();
                state(iphonePeripheralMode
                        ? "GPS-LINK · LE BOND BONDED"
                        : "VERIFIED PEER · LE BOND BONDED");
                BluetoothGatt current = gatt;
                if (iphonePeripheralMode) {
                    if (iphoneAncsSeen) {
                        log("BOND_BONDED подтверждён на ANCS-first link");
                        if (ancsRetryAfterBond && gattClientConnected && current != null) {
                            scheduleAncsRetryAfterBond(current,
                                    "получен BOND_BONDED");
                        }
                    } else {
                        log("BOND_BONDED подтверждён на fallback GPS-style link; "
                                + "повторяю encrypted SECURE read");
                        if (gattClientConnected && current != null) {
                            main.postDelayed(() -> {
                                if (gatt == current && gattClientConnected) {
                                    readIphoneSecure(current);
                                }
                            }, 800L);
                        }
                    }
                } else {
                    if (managedIncomingMode && findConnectedServerPeer(device) != null
                            && isSelectedBondedIncomingDevice(device)) {
                        adoptIncomingClientCandidate(device,
                                "BOND_BONDED on current incoming link");
                    }
                    log("BOND_BONDED подтверждён; direct clientIf может attach сейчас, "
                            + "но discovery всё ещё ждёт B3 + ANCS-READY");
                }
                if (!iphonePeripheralMode && gattClientConnected && current != null) {
                    main.postDelayed(() -> {
                        if (gatt == current && gattClientConnected) {
                            discoverServices(current);
                        }
                    }, 800L);
                }
            } else if (previous == BluetoothDevice.BOND_BONDING) {
                cancelBondTimeout();
                ancsRetryAfterBond = false;
                state(iphoneAncsSeen
                        ? "ANCS · LE BOND FAILED"
                        : "VERIFIED PEER · LE BOND FAILED");
                log("LE bonding завершился неуспешно");
            }
        }
    };

    private static String pairingVariantLabel(int variant) {
        switch (variant) {
            case 0: return "PIN";
            case 1: return "PASSKEY";
            case 2: return "PASSKEY CONFIRMATION";
            case 3: return "CONSENT";
            case 4: return "DISPLAY PASSKEY";
            case 5: return "DISPLAY PIN";
            case 6: return "OOB CONSENT";
            case 7: return "PIN 16 DIGITS";
            default: return "UNKNOWN " + variant;
        }
    }

    private static String scanError(int code) {
        switch (code) {
            case ScanCallback.SCAN_FAILED_ALREADY_STARTED: return "already started";
            case ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED:
                return "application registration failed";
            case ScanCallback.SCAN_FAILED_INTERNAL_ERROR: return "internal error";
            case ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED: return "feature unsupported";
            // Added to the public SDK after Android 9; the platform callback value is stable.
            case 5:
                return "out of hardware resources";
            default: return "unknown";
        }
    }

    private static String advertiseError(int code) {
        switch (code) {
            case AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE: return "data too large";
            case AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS:
                return "too many advertisers";
            case AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED: return "already started";
            case AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR: return "internal error";
            case AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED:
                return "feature unsupported";
            default: return "unknown";
        }
    }
}
