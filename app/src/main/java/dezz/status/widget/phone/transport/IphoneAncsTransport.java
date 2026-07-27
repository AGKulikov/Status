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

import androidx.annotation.Nullable;

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
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
import java.util.Set;
import java.util.UUID;

/**
 * Production BLE/ANCS transport for one explicitly verified iPhone.
 *
 * <p>The listener receives only real-time notifications from the current ANCS session. iOS's
 * initial {@link AncsProtocol#EVENT_FLAG_PRE_EXISTING pre-existing} replay is rejected before a
 * Control Point request is queued, and a removal is emitted only for a UID that this transport
 * actually delivered during the same session.</p>
 */
public final class IphoneAncsTransport {
    private static final UUID DIAGNOSTIC_SERVICE =
            UUID.fromString("d2d9e4b0-47f1-4e44-a8bb-a932fd5a2f01");
    private static final UUID DIAGNOSTIC_CHARACTERISTIC =
            UUID.fromString("d2d9e4b1-47f1-4e44-a8bb-a932fd5a2f01");
    private static final UUID CONTROL_CHARACTERISTIC =
            UUID.fromString("d2d9e4b2-47f1-4e44-a8bb-a932fd5a2f01");
    private static final UUID SECURE_CHARACTERISTIC =
            UUID.fromString("d2d9e4b3-47f1-4e44-a8bb-a932fd5a2f01");
    private static final UUID GENERIC_ATTRIBUTE_SERVICE =
            UUID.fromString("00001801-0000-1000-8000-00805f9b34fb");
    private static final UUID SERVICE_CHANGED =
            UUID.fromString("00002a05-0000-1000-8000-00805f9b34fb");
    private static final UUID BATTERY_SERVICE =
            UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb");
    private static final UUID BATTERY_LEVEL =
            UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb");
    private static final UUID BATTERY_POWER_STATE =
            UUID.fromString("00002a1a-0000-1000-8000-00805f9b34fb");
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
    private static final long CONNECT_TIMEOUT_MS = 5_000L;
    private static final long GPS_CONNECT_TIMEOUT_MS = 15_000L;
    private static final long GPS_SCAN_TIMEOUT_MS = 30_000L;
    private static final long AUTO_ANCS_WAIT_TIMEOUT_MS = 60_000L;
    private static final long GPS_POST_SECURE_DISCOVERY_DELAY_MS = 800L;
    private static final long DISCOVERY_TIMEOUT_MS = 15_000L;
    private static final long DESCRIPTOR_WRITE_TIMEOUT_MS = 15_000L;
    private static final long BATTERY_OPERATION_TIMEOUT_MS = 5_000L;
    private static final long BOND_TIMEOUT_MS = 90_000L;
    private static final long ANCS_REQUEST_GAP_MS = 120L;
    private static final long LIVE_NOTIFICATION_MAX_AGE_MS = 15_000L;
    private static final int MAX_PENDING_ANCS_REQUESTS = 24;
    private static final long SECURE_TO_CLIENT_CONNECT_DELAY_MS = 400L;
    private static final long DIRECT_FALLBACK_DELAY_MS = 500L;
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
        DATA_SOURCE,
        NOTIFICATION_SOURCE,
        BATTERY_LEVEL,
        BATTERY_POWER,
        BATTERY_LEVEL_STATUS
    }

    private enum BatteryStage {
        NOT_STARTED,
        READ_LEVEL,
        SUBSCRIBE_LEVEL,
        READ_POWER,
        SUBSCRIBE_POWER,
        READ_LEVEL_STATUS,
        SUBSCRIBE_LEVEL_STATUS,
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

    private BluetoothLeScanner scanner;
    private BluetoothLeAdvertiser advertiser;
    private BluetoothGattServer gattServer;
    private BluetoothGatt gatt;
    private BluetoothDevice activeClientTarget;
    private final Object verifiedPeerLock = new Object();
    private BluetoothDevice verifiedPeer;
    private final LinkedHashMap<String, GattServerPeer> gattServerPeers =
            new LinkedHashMap<>();
    private long sessionGeneration;
    private boolean clientConnectInFlight;
    private boolean activeClientAutoConnect;
    private boolean backgroundAttachAttempted;
    private boolean directFallbackAttempted;
    private boolean secureAttConfirmed;
    private boolean gattClientConnected;
    private boolean iphonePeripheralMode;
    private boolean helperBootstrapMode;
    private boolean iphoneConnectStarted;
    private boolean iphonePairAttempted;
    private boolean iphonePairWritePending;
    private boolean iphoneSecureReadPending;
    private boolean iphoneSecureConfirmed;
    private boolean iphonePostSecureDiscoveryScheduled;
    private boolean iphoneAncsSeen;
    private boolean closing;
    private boolean retrySignalled;
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
    private BluetoothGattCharacteristic batteryPower;
    private BluetoothGattCharacteristic batteryLevelStatus;
    private BluetoothGattCharacteristic iphoneSecureCharacteristic;
    private Request activeRequest;
    private AncsProtocol.NotificationAccumulator notificationAccumulator;
    private AncsProtocol.AppNameAccumulator appNameAccumulator;
    private Runnable requestTimeout;
    private Runnable connectTimeout;
    private Runnable discoveryTimeout;
    private Runnable descriptorWriteTimeout;
    private Runnable batteryReadTimeout;
    private Runnable bondTimeout;
    private Runnable secureConnectStart;
    private Runnable nextClientAttempt;
    private Runnable scanTimeout;
    private Runnable ancsBondRetry;
    private Runnable autoAncsWaitTimeout;
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
            log("v9 bootstrap: Android работает BLE central, как HWGPS/GPSTether");
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
     * Daily path after a successful bootstrap. It addresses the peer saved after ANCS READY and
     * opens one bounded direct GATT connection without scanning for the Helper service.
     *
     * <p>This deliberately mirrors the stable HWGPS/GPSTether lifecycle: never leave an
     * autoConnect registration behind after a clean disconnect. The owning controller closes
     * this transport and creates the next single GATT instance through its bounded backoff.</p>
     */
    public boolean connectSavedIphone(String address) {
        closing = false;
        retrySignalled = false;
        if (!ensureAdapter()) return false;
        if (address == null || address.trim().isEmpty()) return false;
        final BluetoothDevice device;
        try {
            device = adapter.getRemoteDevice(address.trim());
        } catch (IllegalArgumentException invalidAddress) {
            log("Saved peer address invalid: `" + address + "`");
            return false;
        }
        if (iphonePeripheralMode && !helperBootstrapMode && gatt != null
                && activeClientTarget != null && sameDevice(activeClientTarget, device)
                && (clientConnectInFlight || gattClientConnected)) {
            log("Saved-peer GATT уже активен для "
                    + safeAddress(device) + "; дубликат connectGatt не создаю"
                    + " connected=" + gattClientConnected
                    + " inFlight=" + clientConnectInFlight);
            state(gattReady
                    ? "ANCS READY · ОТПРАВЬТЕ УВЕДОМЛЕНИЕ"
                    : "АВТО · SAVED PEER УЖЕ ЗАРЕГИСТРИРОВАН");
            return true;
        }

        stopScan();
        stopAdvertising();
        disconnect();
        resetVerifiedPeerSession();
        iphonePeripheralMode = true;
        helperBootstrapMode = false;
        iphoneConnectStarted = true;
        if (!claimVerifiedPeer(device)) {
            iphonePeripheralMode = false;
            state("AUTO · SAVED PEER CONFLICT");
            return false;
        }
        connectIphonePeripheral(device, CONNECT_TIMEOUT_MS,
                "GPS-STYLE · SAVED PEER CONNECTING",
                "saved verified peer; direct connect без scan и Helper service");
        return gatt != null;
    }

    public void stopScan() {
        if (scanTimeout != null) main.removeCallbacks(scanTimeout);
        scanTimeout = null;
        if (!scanning || scanner == null) return;
        try {
            scanner.stopScan(scanCallback);
        } catch (RuntimeException failure) {
            log("stopScan exception: " + failure);
        }
        scanning = false;
        state("СКАНИРОВАНИЕ ОСТАНОВЛЕНО");
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

    private void connectIphonePeripheral(BluetoothDevice device, long timeoutMs,
                                         String connectingState, String reason) {
        clearAncsRuntime();
        clearIphonePeripheralRuntime(false);
        iphonePeripheralMode = true;
        iphoneConnectStarted = true;
        activeClientTarget = device;
        activeClientAutoConnect = false;
        clientConnectInFlight = true;
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
                if (gatt != expected || !clientConnectInFlight) return;
                clientConnectInFlight = false;
                state("GPS-STYLE · CONNECT TIMEOUT");
                log("Нет callback подключения за " + timeoutMs
                        + " ms · target=" + safeAddress(expected.getDevice())
                        + " autoConnect=false");
                closeClientGatt(expected);
                clearAncsRuntime();
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
        stopScan();
        stopAdvertising();
        disconnect();
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
        synchronized (verifiedPeerLock) {
            verifiedPeer = null;
        }
        clearIphonePeripheralRuntime(true);
        cancelClientAttemptCallbacks();
        sessionGeneration++;
        gattServerPeers.clear();
        activeClientTarget = null;
        activeClientAutoConnect = false;
        backgroundAttachAttempted = false;
        directFallbackAttempted = false;
        clientConnectInFlight = false;
        secureAttConfirmed = false;
        gattClientConnected = false;
        log("Новая test-session=" + sessionGeneration
                + "; verified peer и runtime-состояние очищены");
    }

    private void clearIphonePeripheralRuntime(boolean clearMode) {
        iphonePairAttempted = false;
        iphonePairWritePending = false;
        iphoneSecureReadPending = false;
        iphoneSecureConfirmed = false;
        iphonePostSecureDiscoveryScheduled = false;
        iphoneAncsSeen = false;
        ancsRetryAfterBond = false;
        ancsAuthorizationFailureSeen = false;
        leBondAttemptObserved = false;
        ancsBondRetryCount = 0;
        iphoneSecureCharacteristic = null;
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
            return sameDevice(verifiedPeer, device);
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
        state("VERIFIED PEER · ЗАПРОС LE BOND");
        log("PAIR принят. VERIFIED PEER: " + safeName(device)
                + " " + safeAddress(device)
                + " objectId=" + System.identityHashCode(device)
                + " type=" + typeLabel(safeType(device))
                + " bond=" + bondLabel(safeBondState(device)));
        if (safeBondState(device) == BluetoothDevice.BOND_BONDED) {
            log("PAIR: verified peer уже BOND_BONDED; ждём SECURE ATT OK. "
                    + "Ранний reverse connect запрещён");
        } else {
            requestBond(device);
            log("connectGatt отложен до SECURE ATT OK");
        }
    }

    private void handleSecureAttSuccess(BluetoothDevice device, String operation) {
        if (!isVerifiedPeer(device)) {
            log("SECURE callback проигнорирован: это не verified peer");
            return;
        }
        boolean first = !secureAttConfirmed;
        secureAttConfirmed = true;
        state("SECURE ATT OK · SAME-PEER ATTACH");
        log("SECURE ATT OK · " + operation + " · peer=" + safeAddress(device)
                + (first ? " · encrypted characteristic confirmed" : " · повтор"));
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
            startSamePeerAttach(true, "первый SECURE ATT OK + "
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
        clientConnectInFlight = true;
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
                    if (gatt != expected || !clientConnectInFlight) return;
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

    private void cancelClientAttemptCallbacks() {
        if (secureConnectStart != null) main.removeCallbacks(secureConnectStart);
        if (nextClientAttempt != null) main.removeCallbacks(nextClientAttempt);
        secureConnectStart = null;
        nextClientAttempt = null;
    }

    private GattServerPeer findConnectedServerPeer(BluetoothDevice device) {
        for (GattServerPeer peer : gattServerPeers.values()) {
            if (peer.sessionGeneration == sessionGeneration
                    && peer.connected
                    && sameDevice(peer.device, device)) {
                return peer;
            }
        }
        return null;
    }

    private void recordGattServerPeer(BluetoothDevice device, int status, int newState) {
        if (device == null) return;
        long now = android.os.SystemClock.elapsedRealtime();
        String key = deviceKey(device);
        GattServerPeer peer = gattServerPeers.get(key);
        if (peer == null || peer.sessionGeneration != sessionGeneration) {
            peer = new GattServerPeer(sessionGeneration, device);
            gattServerPeers.put(key, peer);
        }
        peer.device = device;
        if (status == GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
            if (!peer.connected) peer.connectedAtElapsedMs = now;
            peer.connected = true;
        } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
            peer.connected = false;
        }
    }

    private void handleVerifiedServerLinkDisconnected(BluetoothDevice device) {
        cancelClientAttemptCallbacks();
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
                DIAGNOSTIC_SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY);
        BluetoothGattCharacteristic information = new BluetoothGattCharacteristic(
                DIAGNOSTIC_CHARACTERISTIC,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ);
        information.setValue("KX11 ANCS Test v9".getBytes(StandardCharsets.UTF_8));

        BluetoothGattCharacteristic control = new BluetoothGattCharacteristic(
                CONTROL_CHARACTERISTIC,
                BluetoothGattCharacteristic.PROPERTY_WRITE
                        | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_WRITE);

        BluetoothGattCharacteristic secure = new BluetoothGattCharacteristic(
                SECURE_CHARACTERISTIC,
                BluetoothGattCharacteristic.PROPERTY_READ
                        | BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED
                        | BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED);
        secure.setValue("SECURE ATT OK".getBytes(StandardCharsets.UTF_8));

        boolean informationAdded = service.addCharacteristic(information);
        boolean controlAdded = service.addCharacteristic(control);
        boolean secureAdded = service.addCharacteristic(secure);
        log("Diagnostic characteristics: INFO=" + informationAdded
                + " CONTROL=" + controlAdded + " SECURE=" + secureAdded);
        if (!informationAdded || !controlAdded || !secureAdded) {
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
        BluetoothGatt expected = callbackGatt;
        discoveryTimeout = () -> {
            if (gatt != expected || !discoveryPending) return;
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
            state("AUTO · ANCS WAIT TIMEOUT");
            log("ANCS/Service Changed не появились за " + AUTO_ANCS_WAIT_TIMEOUT_MS
                    + " ms; закрываю daily GATT перед reconnect backoff");
            disconnect();
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
        // HA1122 exposed BAS even while iOS had not published ANCS yet. Prepare the optional
        // battery work immediately, then serialize it behind any Service Changed/ANCS CCCD.
        prepareBatteryBootstrap(callbackGatt);

        BluetoothGattService ancs = callbackGatt.getService(AncsProtocol.SERVICE);
        if (ancs != null) {
            cancelAutoAncsWaitTimeout();
            iphoneAncsSeen = iphonePeripheralMode;
            if (gattReady) {
                log("ANCS уже READY; повторный discovery проигнорирован");
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
            state("ANCS-FIRST · ПОДПИСКА DATA SOURCE");
            log("ANCS найден. Подписываюсь Data Source → Notification Source; "
                    + "это настоящая защищённая операция ANCS");
            descriptorStage = DescriptorStage.DATA_SOURCE;
            if (!subscribe(callbackGatt, dataSource, false)) {
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
            log("Saved-peer daily link не требует D2D diagnostic service. "
                    + "PAIR/SECURE Helper не запускаются; жду Service Changed");
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
        boolean local;
        try {
            local = callbackGatt.setCharacteristicNotification(characteristic, true);
        } catch (RuntimeException failure) {
            descriptorStage = DescriptorStage.NONE;
            state("SUBSCRIBE_EXCEPTION");
            log("setCharacteristicNotification exception: " + failure);
            return false;
        }
        BluetoothGattDescriptor cccd =
                characteristic.getDescriptor(AncsProtocol.CLIENT_CONFIGURATION);
        log("setCharacteristicNotification " + shortUuid(characteristic.getUuid())
                + "=" + local + "; CCCD=" + (cccd != null));
        if (!local || cccd == null) {
            state("SUBSCRIBE_LOCAL_FAILED");
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
            state("CCCD_WRITE_EXCEPTION");
            log("writeDescriptor exception: " + failure);
            return false;
        }
        log("writeDescriptor " + shortUuid(characteristic.getUuid())
                + " started=" + started);
        if (!started) {
            descriptorStage = DescriptorStage.NONE;
            state("CCCD_START_FAILED");
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
        if (status != GATT_SUCCESS) {
            DescriptorStage failedStage = descriptorStage;
            descriptorStage = DescriptorStage.NONE;
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
        } else if (descriptorStage == DescriptorStage.DATA_SOURCE) {
            state("DATA SOURCE OK · ПОДПИСКА NOTIFICATION SOURCE");
            log("Data Source CCCD включён; включаю Notification Source");
            descriptorStage = DescriptorStage.NOTIFICATION_SOURCE;
            if (!subscribe(callbackGatt, notificationSource, false)) {
                descriptorStage = DescriptorStage.NONE;
            }
        } else if (descriptorStage == DescriptorStage.NOTIFICATION_SOURCE) {
            descriptorStage = DescriptorStage.NONE;
            gattReady = true;
            prepareBatteryBootstrap(callbackGatt);
            cancelAutoAncsWaitTimeout();
            state("ANCS READY · ОТПРАВЬТЕ УВЕДОМЛЕНИЕ");
            log("Обе ANCS-подписки успешно включены; BAS fallback будет настроен "
                    + "в той же сериализованной GATT-очереди");
            sendNextRequest();
        }
    }

    private void handleCharacteristicChanged(BluetoothGatt callbackGatt,
                                             BluetoothGattCharacteristic characteristic) {
        if (callbackGatt != gatt) return;
        byte[] value = characteristic.getValue();
        UUID uuid = characteristic.getUuid();
        log("onCharacteristicChanged " + shortUuid(uuid)
                + " bytes=" + AdvertisementParser.hex(value, 80));
        if (BATTERY_LEVEL.equals(uuid) || BATTERY_POWER_STATE.equals(uuid)
                || BATTERY_LEVEL_STATUS.equals(uuid)) {
            if (gattClientConnected && value != null) {
                listener.onBatteryCharacteristic(uuid, value.clone());
            }
            return;
        }
        if (SERVICE_CHANGED.equals(uuid)) {
            if (iphonePeripheralMode && !helperBootstrapMode) {
                log("Daily GATT получил Service Changed; на ECARX безопаснее закрыть "
                        + "текущий client и выполнить новый saved-peer connect через backoff");
                state("AUTO · SERVICE CHANGED · RECONNECT");
                disconnect();
                return;
            }
            log("Получен Service Changed; сбрасываю старые ANCS handles/очередь "
                    + "и повторяю discovery");
            clearAncsRuntime();
            main.postDelayed(() -> discoverServices(callbackGatt), 400L);
        } else if (AncsProtocol.NOTIFICATION_SOURCE.equals(uuid)) {
            if (gattReady) {
                handleNotificationSource(value);
            } else {
                log("Игнорирую Notification Source до актуального ANCS READY");
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
            case DATA_SOURCE:
                return AncsProtocol.DATA_SOURCE.equals(uuid);
            case NOTIFICATION_SOURCE:
                return AncsProtocol.NOTIFICATION_SOURCE.equals(uuid);
            case BATTERY_LEVEL:
                return BATTERY_LEVEL.equals(uuid);
            case BATTERY_POWER:
                return BATTERY_POWER_STATE.equals(uuid);
            case BATTERY_LEVEL_STATUS:
                return BATTERY_LEVEL_STATUS.equals(uuid);
            case NONE:
            default:
                return false;
        }
    }

    private static boolean isBatteryDescriptorStage(DescriptorStage stage) {
        return stage == DescriptorStage.BATTERY_LEVEL
                || stage == DescriptorStage.BATTERY_POWER
                || stage == DescriptorStage.BATTERY_LEVEL_STATUS;
    }

    private void prepareBatteryBootstrap(BluetoothGatt callbackGatt) {
        if (callbackGatt != gatt || batteryStage != BatteryStage.NOT_STARTED) return;
        BluetoothGattService service = callbackGatt.getService(BATTERY_SERVICE);
        batteryLevel = service == null ? null : service.getCharacteristic(BATTERY_LEVEL);
        batteryPower = service == null ? null : service.getCharacteristic(BATTERY_POWER_STATE);
        batteryLevelStatus =
                service == null ? null : service.getCharacteristic(BATTERY_LEVEL_STATUS);
        if (batteryLevel == null && batteryPower == null && batteryLevelStatus == null) {
            batteryStage = BatteryStage.COMPLETE;
            log("BAS 0x180F отсутствует; остаются HFP/OEM/broadcast источники заряда");
            return;
        }
        batteryStage = BatteryStage.READ_LEVEL;
        log("BAS fallback найден: level=" + (batteryLevel != null)
                + " power=" + (batteryPower != null)
                + " levelStatus=" + (batteryLevelStatus != null));
    }

    private void resetBatteryBootstrap() {
        cancelBatteryReadTimeout();
        batteryLevel = null;
        batteryPower = null;
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
                || descriptorStage != DescriptorStage.NONE) {
            return;
        }
        while (true) {
            switch (batteryStage) {
                case READ_LEVEL:
                    batteryStage = BatteryStage.SUBSCRIBE_LEVEL;
                    if (startOptionalBatteryRead(callbackGatt, batteryLevel)) return;
                    break;
                case SUBSCRIBE_LEVEL:
                    batteryStage = BatteryStage.READ_POWER;
                    if (startOptionalBatterySubscription(callbackGatt, batteryLevel,
                            DescriptorStage.BATTERY_LEVEL)) return;
                    break;
                case READ_POWER:
                    batteryStage = BatteryStage.SUBSCRIBE_POWER;
                    if (startOptionalBatteryRead(callbackGatt, batteryPower)) return;
                    break;
                case SUBSCRIBE_POWER:
                    batteryStage = BatteryStage.READ_LEVEL_STATUS;
                    if (startOptionalBatterySubscription(callbackGatt, batteryPower,
                            DescriptorStage.BATTERY_POWER)) return;
                    break;
                case READ_LEVEL_STATUS:
                    batteryStage = BatteryStage.SUBSCRIBE_LEVEL_STATUS;
                    if (startOptionalBatteryRead(callbackGatt, batteryLevelStatus)) return;
                    break;
                case SUBSCRIBE_LEVEL_STATUS:
                    batteryStage = BatteryStage.COMPLETE;
                    if (startOptionalBatterySubscription(callbackGatt, batteryLevelStatus,
                            DescriptorStage.BATTERY_LEVEL_STATUS)) return;
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
        if (batteryReadPendingUuid != null || descriptorStage != DescriptorStage.NONE) return;
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
        cancelAutoAncsWaitTimeout();
        cancelConnectTimeout();
        cancelDiscoveryTimeout();
        cancelDescriptorWriteTimeout();
        resetBatteryBootstrap();
        cancelBondTimeout();
        requestTimeout = null;
        ancsBondRetry = null;
        requests.clear();
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
            gattReady = false;
            log("onDescriptorWrite не получен за " + DESCRIPTOR_WRITE_TIMEOUT_MS
                    + " ms · stage=" + expectedStage
                    + " characteristic=" + shortUuid(expectedCharacteristic));
            state("CCCD_WRITE_TIMEOUT · " + expectedStage);
        };
        main.postDelayed(descriptorWriteTimeout, DESCRIPTOR_WRITE_TIMEOUT_MS);
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
            gattReady = false;
            log("BAS CCCD callback не получен за " + BATTERY_OPERATION_TIMEOUT_MS
                    + " ms · " + expectedStage);
            state("BAS OPERATION TIMEOUT · " + expectedStage);
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
            gattReady = false;
            log("BAS read callback не получен за " + BATTERY_OPERATION_TIMEOUT_MS
                    + " ms · " + shortUuid(expectedUuid));
            state("BAS OPERATION TIMEOUT · READ " + shortUuid(expectedUuid));
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

    private static boolean requiresControllerRetry(@Nullable String value) {
        if (value == null) return false;
        return value.contains("CONNECT RETURNED NULL")
                || value.contains("CONNECT TIMEOUT")
                || value.contains("CONNECT EXCEPTION")
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
                || value.contains("BAS OPERATION TIMEOUT")
                || value.contains("ANCS DATA DESYNC")
                || value.contains("ANCS WAIT TIMEOUT")
                || value.contains("SECURE READ FAILED")
                || value.contains("BOND_START_FAILED")
                || value.contains("LE BOND TIMEOUT")
                || value.contains("LE BOND FAILED")
                || value.contains("ATTEMPTS EXHAUSTED")
                || value.contains("PAIRING FAILED")
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

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            main.post(() -> {
                ScanRecord record = result.getScanRecord();
                byte[] raw = record == null ? null : record.getBytes();
                AdvertisementParser.Parsed parsed = AdvertisementParser.parse(raw);
                boolean solicitsAncs = parsed.solicits(AncsProtocol.SERVICE);
                updateCandidate(result.getDevice(), result.getRssi(), solicitsAncs,
                        parsed.hex, solicitsAncs ? "ANCS solicitation" : "scan");
                if (iphonePeripheralMode
                        && advertisesService(record, DIAGNOSTIC_SERVICE)
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
                scanning = false;
                state("SCAN_FAILED_" + errorCode);
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
                        if (!DIAGNOSTIC_SERVICE.equals(service.getUuid())) return;
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
                            state("GATT SERVER LINK · В LIGHTBLUE ЗАПИШИТЕ PAIR");
                            log("Peer станет verified только после "
                                    + "ASCII PAIR в CONTROL " + CONTROL_CHARACTERISTIC);
                        } else if (newState == BluetoothProfile.STATE_DISCONNECTED
                                && isVerifiedPeer(device)) {
                            state("VERIFIED SERVER LINK DISCONNECTED");
                            handleVerifiedServerLinkDisconnected(device);
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
                    if (DIAGNOSTIC_CHARACTERISTIC.equals(uuid)) {
                        sendGattReadResponse(device, requestId, offset,
                            "KX11 ANCS Test v9".getBytes(StandardCharsets.UTF_8));
                        return;
                    }
                    if (SECURE_CHARACTERISTIC.equals(uuid)) {
                        if (!isVerifiedPeer(device)) {
                            sendGattServerResponse(device, requestId,
                                    STATUS_INSUFFICIENT_AUTHORIZATION, 0, null);
                            main.post(() -> log("SECURE READ отклонён: peer не verified · "
                                    + safeAddress(device)));
                            return;
                        }
                        sendGattReadResponse(device, requestId, offset,
                                "SECURE ATT OK".getBytes(StandardCharsets.UTF_8));
                        main.post(() -> handleSecureAttSuccess(device, "READ"));
                        return;
                    }
                    sendGattServerResponse(device, requestId,
                            BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, 0, null);
                }

                @Override
                public void onCharacteristicWriteRequest(
                        BluetoothDevice device, int requestId,
                        BluetoothGattCharacteristic characteristic,
                        boolean preparedWrite, boolean responseNeeded,
                        int offset, byte[] value) {
                    UUID uuid = characteristic == null ? null : characteristic.getUuid();
                    byte[] rawValue = value == null ? null : value.clone();
                    main.post(() -> log("GATT SERVER WRITE raw: session="
                            + sessionGeneration
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
                            + " bond=" + bondLabel(safeBondState(device))));
                    int status = BluetoothGatt.GATT_SUCCESS;
                    Runnable successAction = null;

                    if (preparedWrite) {
                        status = BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED;
                    } else if (offset != 0) {
                        status = BluetoothGatt.GATT_INVALID_OFFSET;
                    } else if (CONTROL_CHARACTERISTIC.equals(uuid)) {
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
                    } else if (SECURE_CHARACTERISTIC.equals(uuid)) {
                        String command = asciiCommand(value);
                        if (!isVerifiedPeer(device)) {
                            status = STATUS_INSUFFICIENT_AUTHORIZATION;
                            main.post(() -> log("SECURE WRITE отклонён: peer не verified · "
                                    + safeAddress(device)));
                        } else if (!"ANCS".equals(command)) {
                            status = BluetoothGatt.GATT_FAILURE;
                            main.post(() -> log("SECURE command отклонена: `" + command
                                    + "`; ожидается ASCII ANCS"));
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
        if (status != GATT_SUCCESS) {
            cancelConnectTimeout();
            clientConnectInFlight = false;
            gattClientConnected = false;
            if (status == 19 && ancsAuthorizationFailureSeen) {
                state("ANCS PAIRING FAILED · IPHONE CLOSED LINK");
                log("iPhone закрыл BLE link (status=19/0x13) после неуспешной "
                        + "ANCS authorization/SMP");
            } else {
                state("GPS-STYLE FAILED · status=" + status);
                log("Прямое Android-central подключение завершилось ошибкой " + status);
            }
            closeClientGatt(callbackGatt);
            clearAncsRuntime();
            return;
        }
        if (newState == BluetoothProfile.STATE_CONNECTED) {
            cancelConnectTimeout();
            clientConnectInFlight = false;
            gattClientConnected = true;
            state("IPHONE BLE CONNECTED · GPS-STYLE");
            log("Android сам создал BLE link как central; начинаю GATT discovery");
            discoverServices(callbackGatt);
        } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
            cancelConnectTimeout();
            clientConnectInFlight = false;
            gattClientConnected = false;
            closeClientGatt(callbackGatt);
            clearAncsRuntime();
            state("GPS-STYLE · IPHONE DISCONNECTED");
            log("STATE_DISCONNECTED: старый BluetoothGatt закрыт; "
                    + "следующую единственную direct-попытку создаст controller backoff");
        }
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt callbackGatt,
                                            int status, int newState) {
            // A busy diagnostic UI must not let the 5 s timeout overtake a connection callback
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
                    clientConnectInFlight = false;
                    gattClientConnected = false;
                    state("GATT CONNECTION FAILED · status=" + status);
                    closeClientGatt(callbackGatt);
                    clearAncsRuntime();
                    if (failedBackgroundAttach) {
                        scheduleDirectFallback("background attach status=" + status);
                    } else if (attachWasInFlight) {
                        state("V6 ATTEMPTS EXHAUSTED");
                    }
                } else if (newState == BluetoothProfile.STATE_CONNECTED) {
                    cancelConnectTimeout();
                    clientConnectInFlight = false;
                    gattClientConnected = true;
                    state("SAME-PEER GATT CONNECTED");
                    log("GATT client зарегистрирован на exact verified peer; "
                            + "discoverServices сразу, без requestMtu");
                    discoverServices(callbackGatt);
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    boolean attachWasInFlight = clientConnectInFlight;
                    boolean failedBackgroundAttach =
                            attachWasInFlight && activeClientAutoConnect;
                    cancelConnectTimeout();
                    clientConnectInFlight = false;
                    gattClientConnected = false;
                    state("GATT DISCONNECTED · status=" + status);
                    closeClientGatt(callbackGatt);
                    clearAncsRuntime();
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
                if ((BATTERY_LEVEL.equals(uuid) || BATTERY_POWER_STATE.equals(uuid)
                        || BATTERY_LEVEL_STATUS.equals(uuid))
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
                    scheduleIphonePostSecureDiscovery(callbackGatt);
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
                    log("BOND_BONDED подтверждён; reverse connect всё ещё ждёт "
                            + "SECURE ATT OK");
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
            case ScanCallback.SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES:
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
