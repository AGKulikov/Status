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
    private static final UUID DIAGNOSTIC_SERVICE =
            UUID.fromString("d2d9e4b0-47f1-4e44-a8bb-a932fd5a2f02");
    private static final UUID DIAGNOSTIC_CHARACTERISTIC =
            UUID.fromString("d2d9e4b1-47f1-4e44-a8bb-a932fd5a2f02");
    private static final UUID CONTROL_CHARACTERISTIC =
            UUID.fromString("d2d9e4b2-47f1-4e44-a8bb-a932fd5a2f02");
    private static final UUID SECURE_CHARACTERISTIC =
            UUID.fromString("d2d9e4b3-47f1-4e44-a8bb-a932fd5a2f02");
    /** Dedicated Helper telemetry endpoint on the current verified GATT-server connection. */
    private static final UUID TELEMETRY_CHARACTERISTIC =
            UUID.fromString("d2d9e4b4-47f1-4e44-a8bb-a932fd5a2f02");
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
    private static final long HELPER_SERVICE_REDISCOVERY_MS = 30_000L;
    private static final long BOND_TIMEOUT_MS = 90_000L;
    private static final long ANCS_REQUEST_GAP_MS = 120L;
    private static final long LIVE_NOTIFICATION_MAX_AGE_MS = 15_000L;
    private static final int MAX_PENDING_ANCS_REQUESTS = 24;
    private static final long SECURE_TO_CLIENT_CONNECT_DELAY_MS = 400L;
    private static final long DIRECT_FALLBACK_DELAY_MS = 500L;
    private static final int INCOMING_CLIENT_ATTACH_MAX_ATTEMPTS = 3;
    private static final long INCOMING_CLIENT_ATTACH_RETRY_MS = 1_500L;
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
    /** Direct GATT-client registrations tried while the verified incoming server link stays up. */
    private int incomingClientAttachAttempt;
    private boolean secureAttConfirmed;
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
            scheduleManagedIncomingRestart(reason.trim().isEmpty()
                    ? "incoming iPhone link lost" : reason);
            state(REMOTE_LOGICAL_NAME + " · RECOVERING · WAITING FOR IPHONE CENTRAL");
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

        preparedAdvertiseSettings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(true)
                .setTimeout(0)
                .build();
        preparedAdvertiseData = new AdvertiseData.Builder()
                .setIncludeTxPowerLevel(false)
                .addServiceUuid(new ParcelUuid(DIAGNOSTIC_SERVICE))
                .build();
        // A service-data logical name is independent from BluetoothAdapter#getName(). It is
        // intentionally not setIncludeDeviceName(true), because that would expose the Classic
        // adapter name and make the two logical transports look like one connection again.
        preparedScanResponse = new AdvertiseData.Builder()
                .addServiceData(new ParcelUuid(DIAGNOSTIC_SERVICE),
                        LOCAL_LOGICAL_NAME.getBytes(StandardCharsets.UTF_8))
                .build();
        solicitationAdvertising = false;
        advertisingDesired = true;
        state(LOCAL_LOGICAL_NAME + " · STARTING");
        log("Публикую отдельный BLE service " + DIAGNOSTIC_SERVICE
                + " как " + LOCAL_LOGICAL_NAME
                + "; системное имя Classic-адаптера не меняется");
        openGattServer();
        return gattServer != null;
    }

    /** Legacy comparison test. It uses only a normal public diagnostic advertisement. */
    public void startIncomingConnectionTest() {
        if (!ensureAdapter()) return;
        stopAdvertising();
        disconnect();
        resetVerifiedPeerSession();
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
            if (nextClientAttempt != null || clientConnectInFlight) {
                log("Direct same-peer attach уже выполняется или запланирован");
                return;
            }
            // A manual diagnostic retry starts a fresh bounded group but keeps the verified
            // incoming link and the published Geely_ANCS service intact.
            incomingClientAttachAttempt = 0;
            startSamePeerAttach(false, "ручной direct same-peer retry");
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
        backgroundAttachAttempted = false;
        directFallbackAttempted = false;
        incomingClientAttachAttempt = 0;
        clientConnectInFlight = false;
        secureAttConfirmed = false;
        gattClientConnected = false;
        log("Новая test-session=" + sessionGeneration
                + "; verified peer и runtime-состояние очищены");
    }

    private void clearIphonePeripheralRuntime(boolean clearMode) {
        cancelHelperTelemetryRecovery();
        iphonePairAttempted = false;
        iphonePairWritePending = false;
        iphoneSecureReadPending = false;
        iphoneSecureConfirmed = false;
        iphoneHelperTelemetrySubscriptionAttempted = false;
        iphoneHelperTelemetrySubscribed = false;
        iphoneHelperTelemetryReadPending = false;
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
        return gattReady && gattClientConnected && gatt != null;
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
        state("VERIFIED PEER · CURRENT LINK CHALLENGE");
        log("PAIR принят. VERIFIED PEER: " + safeName(device)
                + " " + safeAddress(device)
                + " objectId=" + System.identityHashCode(device)
                + " type=" + typeLabel(safeType(device))
                + " bond=" + bondLabel(safeBondState(device)));
        if (safeBondState(device) == BluetoothDevice.BOND_BONDED) {
            log("PAIR: общий Classic/LE peer уже BOND_BONDED; первая B3 READ запросит "
                    + "security именно текущего LE link. Ранний reverse connect запрещён");
        } else {
            requestBond(device);
            log("connectGatt отложен до подтверждения текущего ATT link");
        }
    }

    private void handleSecureAttSuccess(BluetoothDevice device, String operation) {
        if (!isVerifiedPeer(device)) {
            log("SECURE callback проигнорирован: это не verified peer");
            return;
        }
        boolean first = !secureAttConfirmed;
        secureAttConfirmed = true;
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
            return;
        }
        scheduleSecureClientStart();
    }

    private void scheduleSecureClientStart() {
        if (secureConnectStart != null || clientConnectInFlight || gattClientConnected) return;
        secureConnectStart = () -> {
            secureConnectStart = null;
            // The iPhone already owns the physical link. Register Android's GATT-client role
            // directly against that exact peer; autoConnect=true is a background/reconnect
            // request and made the ECARX Android 9 stack reject the dual-role attach.
            startSamePeerAttach(false, "первый SECURE ATT OK + "
                    + SECURE_TO_CLIENT_CONNECT_DELAY_MS + " ms");
        };
        main.postDelayed(secureConnectStart, SECURE_TO_CLIENT_CONNECT_DELAY_MS);
        log("Same-peer ANCS client attach запланирован через "
                + SECURE_TO_CLIENT_CONNECT_DELAY_MS + " ms после SECURE ATT OK");
    }

    private void startSamePeerAttach(boolean autoConnect, String reason) {
        if (!ensureAdapter()) return;
        if (!secureAttConfirmed) {
            log("connectGatt не запущен: SECURE ATT ещё не подтверждён");
            return;
        }
        if (clientConnectInFlight || gattClientConnected || gatt != null) {
            log("connectGatt уже активен; новая попытка пропущена · " + reason);
            return;
        }
        BluetoothDevice device = getVerifiedPeer();
        if (device == null) {
            state("NO VERIFIED PEER");
            log("Same-peer attach отменён: verified peer отсутствует");
            return;
        }
        GattServerPeer serverLink = findConnectedServerPeer(device);
        if (serverLink == null) {
            state("VERIFIED SERVER LINK LOST");
            log("Same-peer attach отменён: exact verified GATT-server link "
                    + safeAddress(device) + " не активен");
            return;
        }
        if (managedIncomingMode) {
            if (autoConnect) {
                log("Reverse route запрещает autoConnect=true на уже активном incoming link");
                return;
            }
            if (incomingClientAttachAttempt >= INCOMING_CLIENT_ATTACH_MAX_ATTEMPTS) {
                state("SAME-PEER ATTACH · LINK KEPT · RETRIES EXHAUSTED");
                log("Лимит direct same-peer attach исчерпан; Geely_ANCS и incoming link "
                        + "сохранены без перепубликации");
                return;
            }
            incomingClientAttachAttempt++;
        } else if (autoConnect) {
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
        state(managedIncomingMode
                ? "SAME-PEER ATTACH · DIRECT #" + incomingClientAttachAttempt
                : autoConnect
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
                if (managedIncomingMode) {
                    scheduleIncomingClientAttachRetry("connectGatt returned null");
                } else if (autoConnect) {
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
                    if (managedIncomingMode) {
                        scheduleIncomingClientAttachRetry("direct attach timeout");
                    } else if (expectedAutoConnect) {
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
            if (managedIncomingMode) {
                scheduleIncomingClientAttachRetry("direct attach exception");
            } else if (autoConnect) {
                scheduleDirectFallback("background attach exception");
            } else {
                state("V6 ATTEMPTS EXHAUSTED");
            }
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

    /**
     * A failed Android GATT-client registration is not proof that the incoming BLE link was
     * lost. Keep the GATT server and its service published while the exact verified server peer
     * is still connected; otherwise Core Bluetooth receives a false Service Changed event and
     * tears down the only working half of the reverse route.
     */
    private void scheduleIncomingClientAttachRetry(@NonNull String reason) {
        if (closing || !managedReconnectEnabled || !managedIncomingMode
                || nextClientAttempt != null) return;
        BluetoothDevice device = getVerifiedPeer();
        if (device == null || findConnectedServerPeer(device) == null) {
            state(REMOTE_LOGICAL_NAME + " · INCOMING LINK LOST");
            log("Direct same-peer attach не повторяется: verified GATT-server link уже потерян");
            scheduleManagedIncomingRestart("verified incoming link lost after " + reason);
            return;
        }
        if (incomingClientAttachAttempt >= INCOMING_CLIENT_ATTACH_MAX_ATTEMPTS) {
            state("SAME-PEER ATTACH · LINK KEPT · RETRIES EXHAUSTED");
            log("Direct same-peer attach не удался после " + incomingClientAttachAttempt
                    + " попыток; Geely_ANCS server/link остаются активны · " + reason);
            return;
        }
        int nextAttempt = incomingClientAttachAttempt + 1;
        long delay = INCOMING_CLIENT_ATTACH_RETRY_MS * incomingClientAttachAttempt;
        nextClientAttempt = () -> {
            nextClientAttempt = null;
            if (closing || !managedIncomingMode || !secureAttConfirmed) return;
            BluetoothDevice current = getVerifiedPeer();
            if (current == null || findConnectedServerPeer(current) == null) {
                scheduleManagedIncomingRestart(
                        "verified incoming link lost before direct retry");
                return;
            }
            startSamePeerAttach(false, "same incoming link retry #" + nextAttempt
                    + " after " + reason);
        };
        state("SAME-PEER ATTACH · RETRY #" + nextAttempt + " · LINK KEPT");
        main.postDelayed(nextClientAttempt, delay);
        log("Direct same-peer attach retry #" + nextAttempt + " через " + delay
                + " ms; GATT server не закрывается · " + reason);
    }

    /**
     * Restarts only Android's client registration. Calling disconnect() or closing the GATT
     * server here would remove Geely_ANCS from the still-connected iPhone and invalidate every
     * Core Bluetooth handle. BluetoothGatt.close() merely unregisters this failed client owner;
     * the independently verified GATT-server link remains the physical connection owner.
     */
    private void recoverIncomingClientRole(@NonNull String reason) {
        if (closing || !managedIncomingMode) return;
        BluetoothDevice device = getVerifiedPeer();
        if (device == null || findConnectedServerPeer(device) == null) {
            scheduleManagedIncomingRestart("incoming link lost during " + reason);
            return;
        }
        cancelConnectTimeout();
        cancelClientAttemptCallbacks();
        clearAncsRuntime();
        BluetoothGatt failedClient = gatt;
        gatt = null;
        gattClientConnected = false;
        clientConnectInFlight = false;
        activeClientTarget = null;
        activeClientAutoConnect = false;
        activeClientEstablished = false;
        if (failedClient != null) {
            try {
                failedClient.close();
            } catch (RuntimeException ignored) {
            }
        }
        log("Перезапускаю только Android GATT-client; Geely_ANCS server/link сохранены · "
                + reason);
        scheduleIncomingClientAttachRetry(reason);
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

    /**
     * Returns true exactly once for each physical incoming GATT link.  The first B3 read receives
     * ATT insufficient-authentication yӾ|����k�w��WF�fR����Р�&�fFR7FF�27G&��r&W6�W&6R�7G&��r&V�F�fR�F�&�w2W�6WF�����&WGW&�&VB�F�2�vWB�&"�'7&2"�&���"�'&W2"��&W6��fR�&V�F�fR���F�2�vWB�'7&2"�&���"�'&W2"��&W6��fR�&V�F�fR����Р�&�fFR7FF�27G&��r&VB�F�&��B�F��F�&�w2W�6WF�����F�f��R�f��W2�5&VwV�$f��R�&��B��&��B���&WGW&��Wr7G&��r�f��W2�&VD��'�FW2�f��R��7F�F&D6�'6WG2�UDe󂓰�Ч�