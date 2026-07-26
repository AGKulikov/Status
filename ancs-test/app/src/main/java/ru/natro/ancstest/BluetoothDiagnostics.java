package ru.natro.ancstest;

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
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Standalone BLE/ANCS diagnostic controller. It deliberately does not depend on Status Widget.
 */
public final class BluetoothDiagnostics {
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
    private static final String LOG_TAG = "KX11ANCS";
    private static final int GATT_SUCCESS = BluetoothGatt.GATT_SUCCESS;
    private static final int STATUS_INSUFFICIENT_AUTHENTICATION = 5;
    private static final int STATUS_INSUFFICIENT_AUTHORIZATION = 8;
    private static final int STATUS_INSUFFICIENT_KEY_SIZE = 12;
    private static final int STATUS_INSUFFICIENT_ENCRYPTION = 15;
    private static final long REQUEST_TIMEOUT_MS = 10_000L;
    private static final long CONNECT_TIMEOUT_MS = 5_000L;
    private static final long DISCOVERY_TIMEOUT_MS = 15_000L;
    private static final long SECURE_TO_CLIENT_CONNECT_DELAY_MS = 400L;
    private static final long DIRECT_FALLBACK_DELAY_MS = 500L;
    private static final long CANDIDATE_UI_INTERVAL_MS = 500L;
    private static final int MAX_CANDIDATES = 150;

    public interface Listener {
        void onState(String state);
        void onLog(String line);
        void onCandidates(List<Candidate> candidates);
        void onNotification(NotificationItem item);
        void onAppName(String appIdentifier, String displayName);
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

        NotificationItem(long uid, int eventId, int categoryId, String appIdentifier,
                         String appName, String title, String message, String date) {
            this.uid = uid;
            this.eventId = eventId;
            this.categoryId = categoryId;
            this.appIdentifier = appIdentifier;
            this.appName = appName;
            this.title = title;
            this.message = message;
            this.date = date;
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
        NOTIFICATION_SOURCE
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

        private Request(RequestKind kind, long uid, int eventId,
                        int categoryId, String appIdentifier) {
            this.kind = kind;
            this.uid = uid;
            this.eventId = eventId;
            this.categoryId = categoryId;
            this.appIdentifier = appIdentifier;
        }

        static Request notification(AncsProtocol.Event event) {
            return new Request(RequestKind.NOTIFICATION, event.uid,
                    event.eventId, event.categoryId, "");
        }

        static Request appName(String appIdentifier) {
            return new Request(RequestKind.APP_NAME, -1L, 0, 0, appIdentifier);
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
    private final Map<String, String> appNames = new HashMap<>();

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
    private Request activeRequest;
    private AncsProtocol.NotificationAccumulator notificationAccumulator;
    private AncsProtocol.AppNameAccumulator appNameAccumulator;
    private Runnable requestTimeout;
    private Runnable connectTimeout;
    private Runnable discoveryTimeout;
    private Runnable secureConnectStart;
    private Runnable nextClientAttempt;
    private boolean candidatePublishScheduled;
    private long lastCandidatePublishAt;
    private final Runnable candidatePublisher = () -> {
        candidatePublishScheduled = false;
        lastCandidatePublishAt = android.os.SystemClock.uptimeMillis();
        publishCandidatesNow();
    };

    public BluetoothDiagnostics(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        this.manager = (BluetoothManager) this.context.getSystemService(Context.BLUETOOTH_SERVICE);
        this.adapter = manager == null ? null : manager.getAdapter();
        this.context.registerReceiver(bondReceiver,
                new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED));
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
        log("Service Solicitation API/backport: " + hasSolicitationBuilderMethod());
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

    public void stopScan() {
        if (!scanning || scanner == null) return;
        try {
            scanner.stopScan(scanCallback);
        } catch (RuntimeException failure) {
            log("stopScan exception: " + failure);
        }
        scanning = false;
        state("СКАНИРОВАНИЕ ОСТАНОВЛЕНО");
    }

    /**
     * Opens a connectable GATT server. Android 9 first tries an OEM solicitation backport; when
     * absent it advertises a diagnostic service so LightBlue on iPhone can connect manually.
     */
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
                .setIncludeTxPowerLevel(false);
        solicitationAdvertising = addSolicitationWithReflection(primary);
        if (solicitationAdvertising) {
            log("Используется ANCS Service Solicitation через OEM/backport API");
        } else {
            primary.addServiceUuid(new ParcelUuid(DIAGNOSTIC_SERVICE));
            log("Android 9 не умеет AD type 0x15. Запускаю обычную diagnostic-рекламу");
            log("На iPhone откройте LightBlue, найдите UUID "
                    + DIAGNOSTIC_SERVICE + " и нажмите Connect");
            log("В CONTROL " + CONTROL_CHARACTERISTIC
                    + " запишите ASCII PAIR; только после этого peer будет подтверждён");
        }

        preparedAdvertiseSettings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(true)
                .setTimeout(0)
                .build();
        preparedAdvertiseData = primary.build();
        AdvertiseData.Builder scanResponse = new AdvertiseData.Builder();
        if (solicitationAdvertising) {
            // The iPhone helper scans for the diagnostic UUID. Keep it in the scan
            // response because ANCS solicitation plus another 128-bit UUID does not
            // fit in the 31-byte primary advertising packet.
            scanResponse.addServiceUuid(new ParcelUuid(DIAGNOSTIC_SERVICE));
        } else {
            scanResponse.setIncludeDeviceName(true);
        }
        preparedScanResponse = scanResponse.build();
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
            log("Нет verified peer: сначала запишите ASCII PAIR в CONTROL через LightBlue");
            return;
        }
        if (safeBondState(device) == BluetoothDevice.BOND_BONDED) {
            log("Verified peer уже BOND_BONDED. Проверьте SECURE characteristic; "
                    + "reverse connect до SECURE не запускается");
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
        log("Новая GATT-server session=" + sessionGeneration
                + "; verified peer и две v6-попытки очищены");
    }

    private BluetoothDevice getVerifiedPeer() {
        synchronized (verifiedPeerLock) {
            return verifiedPeer;
        }
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
        information.setValue("KX11 ANCS Test v6".getBytes(StandardCharsets.UTF_8));

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

    private boolean hasSolicitationBuilderMethod() {
        try {
            AdvertiseData.Builder.class.getMethod(
                    "addServiceSolicitationUuid", ParcelUuid.class);
            return true;
        } catch (NoSuchMethodException missing) {
            for (Method method : AdvertiseData.Builder.class.getDeclaredMethods()) {
                if ("addServiceSolicitationUuid".equals(method.getName())
                        && method.getParameterTypes().length == 1) return true;
            }
            return false;
        }
    }

    private boolean addSolicitationWithReflection(AdvertiseData.Builder builder) {
        ParcelUuid ancs = new ParcelUuid(AncsProtocol.SERVICE);
        try {
            Method method;
            try {
                method = AdvertiseData.Builder.class.getMethod(
                        "addServiceSolicitationUuid", ParcelUuid.class);
            } catch (NoSuchMethodException publicMethodMissing) {
                method = AdvertiseData.Builder.class.getDeclaredMethod(
                        "addServiceSolicitationUuid", ParcelUuid.class);
                method.setAccessible(true);
            }
            method.invoke(builder, ancs);
            return true;
        } catch (Throwable unavailable) {
            log("ANCS solicitation API отсутствует: "
                    + rootCause(unavailable).getClass().getSimpleName());
        }

        // Some OEM builds backport the field but keep the Builder method hidden.
        try {
            Field field = AdvertiseData.Builder.class
                    .getDeclaredField("mServiceSolicitationUuids");
            field.setAccessible(true);
            Object current = field.get(builder);
            if (current == null && List.class.isAssignableFrom(field.getType())) {
                current = new ArrayList<ParcelUuid>();
                field.set(builder, current);
            }
            if (current instanceof List) {
                @SuppressWarnings("unchecked")
                List<ParcelUuid> values = (List<ParcelUuid>) current;
                values.add(ancs);
                log("ANCS solicitation добавлена через OEM Builder field");
                return true;
            }
        } catch (Throwable unavailable) {
            log("OEM solicitation field отсутствует/заблокирован: "
                    + rootCause(unavailable).getClass().getSimpleName());
        }
        return false;
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

        BluetoothGattService ancs = callbackGatt.getService(AncsProtocol.SERVICE);
        if (ancs == null) {
            state("CONNECTED · ANCS НЕ НАЙДЕН");
            log("Сервис ANCS 7905… отсутствует на этом BLE link");
            subscribeServiceChangedIfAvailable(callbackGatt);
            return;
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
        log("ANCS найден. Подписываюсь Data Source → Notification Source");
        descriptorStage = DescriptorStage.DATA_SOURCE;
        subscribe(callbackGatt, dataSource, false);
    }

    private void subscribeServiceChangedIfAvailable(BluetoothGatt callbackGatt) {
        BluetoothGattService generic = callbackGatt.getService(GENERIC_ATTRIBUTE_SERVICE);
        serviceChanged = generic == null ? null : generic.getCharacteristic(SERVICE_CHANGED);
        if (serviceChanged == null) {
            log("Service Changed 0x2A05 отсутствует; остаюсь ждать ANCS");
            state("ЖДУ ANCS НА SAME-PEER LINK");
            return;
        }
        descriptorStage = DescriptorStage.SERVICE_CHANGED;
        if (!subscribe(callbackGatt, serviceChanged, true)) {
            descriptorStage = DescriptorStage.NONE;
            state("ЖДУ ANCS НА SAME-PEER LINK");
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
        }
        return started;
    }

    private void handleDescriptorWrite(BluetoothGatt callbackGatt,
                                       BluetoothGattDescriptor descriptor, int status) {
        if (callbackGatt != gatt) return;
        UUID characteristicUuid = descriptor.getCharacteristic() == null
                ? null : descriptor.getCharacteristic().getUuid();
        log("onDescriptorWrite " + shortUuid(characteristicUuid)
                + " status=" + status + " stage=" + descriptorStage);
        if (status != GATT_SUCCESS) {
            state("CCCD_FAILED_" + status);
            if (isAuthorizationError(status)) {
                log("Требуется LE bonding/шифрование и разрешение уведомлений на iPhone");
                requestBond(getVerifiedPeer());
            }
            return;
        }

        if (descriptorStage == DescriptorStage.SERVICE_CHANGED) {
            descriptorStage = DescriptorStage.NONE;
            log("Service Changed indication включена");
            state("ЖДУ SERVICE CHANGED / ANCS");
        } else if (descriptorStage == DescriptorStage.DATA_SOURCE) {
            descriptorStage = DescriptorStage.NOTIFICATION_SOURCE;
            subscribe(callbackGatt, notificationSource, false);
        } else if (descriptorStage == DescriptorStage.NOTIFICATION_SOURCE) {
            descriptorStage = DescriptorStage.NONE;
            gattReady = true;
            state("ANCS READY · ОТПРАВЬТЕ УВЕДОМЛЕНИЕ");
            log("Обе ANCS-подписки успешно включены");
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
        if (SERVICE_CHANGED.equals(uuid)) {
            log("Получен Service Changed; повторяю discovery");
            main.postDelayed(() -> discoverServices(callbackGatt), 400L);
        } else if (AncsProtocol.NOTIFICATION_SOURCE.equals(uuid)) {
            handleNotificationSource(value);
        } else if (AncsProtocol.DATA_SOURCE.equals(uuid)) {
            handleDataSource(value);
        }
    }

    private void handleNotificationSource(byte[] value) {
        if (value == null) return;
        for (int offset = 0; offset + 8 <= value.length; offset += 8) {
            AncsProtocol.Event event = AncsProtocol.parseEvent(value, offset);
            if (event == null) continue;
            events.put(event.uid, event);
            log("ANCS event id=" + event.eventId + " uid=" + event.uid
                    + " category=" + event.categoryId + " count=" + event.categoryCount);
            if (event.eventId == AncsProtocol.EVENT_REMOVED) {
                listener.onNotification(new NotificationItem(event.uid, event.eventId,
                        event.categoryId, "", "", "Удалено", "", ""));
            } else {
                requests.add(Request.notification(event));
            }
        }
        sendNextRequest();
    }

    private void sendNextRequest() {
        if (!gattReady || gatt == null || controlPoint == null || activeRequest != null) return;
        activeRequest = requests.poll();
        if (activeRequest == null) return;
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
                finishRequest("timeout");
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
                finishRequest("notification_malformed");
                return;
            }
            log("Notification Data Source accumulated="
                    + notificationAccumulator.size() + " bytes");
            AncsProtocol.NotificationData result = notificationAccumulator.complete();
            if (result == null) return;
            String appName = value(appNames, result.appIdentifier);
            listener.onNotification(new NotificationItem(result.uid,
                    activeRequest.eventId, activeRequest.categoryId,
                    result.appIdentifier, appName, result.title, result.message, result.date));
            log("Notification attributes: app=" + result.appIdentifier
                    + " title=" + result.title);
            if (!result.appIdentifier.isEmpty() && !appNames.containsKey(result.appIdentifier)) {
                requests.addFirst(Request.appName(result.appIdentifier));
            }
            finishRequest("complete");
        } else {
            if (!appNameAccumulator.append(fragment)) {
                log("Malformed App response after " + appNameAccumulator.size()
                        + " bytes: " + appNameAccumulator.error());
                finishRequest("app_malformed");
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
        activeRequest = null;
        notificationAccumulator = null;
        appNameAccumulator = null;
        requestTimeout = null;
        if (reason.contains("timeout") || reason.contains("malformed")) {
            main.postDelayed(this::sendNextRequest, 350L);
        } else {
            sendNextRequest();
        }
    }

    private void requestBond(BluetoothDevice device) {
        if (device == null) return;
        if (!isVerifiedPeer(device)) {
            log("Bonding отклонён: peer не подтверждён командой PAIR");
            return;
        }
        int state = safeBondState(device);
        if (state == BluetoothDevice.BOND_BONDED) {
            log("Verified peer уже BOND_BONDED. Проверьте SECURE characteristic");
            return;
        }
        if (state == BluetoothDevice.BOND_BONDING) {
            log("LE bonding verified peer уже выполняется");
            return;
        }
        boolean started = false;
        // Prefer explicit LE transport when an OEM Android 9 build exposes the hidden overload.
        try {
            Method method = BluetoothDevice.class.getDeclaredMethod("createBond", int.class);
            method.setAccessible(true);
            Object result = method.invoke(device, BluetoothDevice.TRANSPORT_LE);
            started = result instanceof Boolean && (Boolean) result;
            log("createBond(TRANSPORT_LE) via reflection=" + started);
        } catch (Throwable unavailable) {
            log("createBond(TRANSPORT_LE) недоступен: "
                    + rootCause(unavailable).getClass().getSimpleName());
        }
        if (!started) {
            try {
                started = device.createBond();
            } catch (RuntimeException failure) {
                log("createBond exception: " + failure);
            }
            log("createBond()=" + started);
        }
        state(started ? "BONDING · ПОДТВЕРДИТЕ НА IPHONE" : "BOND_START_FAILED");
    }

    private void clearAncsRuntime() {
        if (requestTimeout != null) main.removeCallbacks(requestTimeout);
        cancelConnectTimeout();
        cancelDiscoveryTimeout();
        requestTimeout = null;
        requests.clear();
        events.clear();
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

    private void closeClientGatt(BluetoothGatt callbackGatt) {
        if (callbackGatt == null) return;
        if (gatt == callbackGatt) {
            gatt = null;
            gattClientConnected = false;
            clientConnectInFlight = false;
            activeClientTarget = null;
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
        listener.onState(value);
        log("STATE: " + value);
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
                || status == STATUS_INSUFFICIENT_ENCRYPTION;
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

    private static Throwable rootCause(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private static String value(Map<String, String> values, String key) {
        String result = values.get(key);
        return result == null ? "" : result;
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
                                "KX11 ANCS Test v6".getBytes(StandardCharsets.UTF_8));
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

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt callbackGatt,
                                            int status, int newState) {
            // A busy diagnostic UI must not let the 5 s timeout overtake a connection callback
            // that the Bluetooth stack has already delivered.
            main.postAtFrontOfQueue(() -> {
                if (callbackGatt != gatt) return;
                log("onConnectionStateChange status=" + status
                        + " newState=" + newState
                        + " device=" + safeAddress(callbackGatt.getDevice())
                        + " objectId=" + System.identityHashCode(callbackGatt.getDevice())
                        + " autoConnect=" + activeClientAutoConnect
                        + " transport=TRANSPORT_LE");
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
                if (status != GATT_SUCCESS && activeRequest != null) {
                    if (isAuthorizationError(status)) requestBond(getVerifiedPeer());
                    finishRequest("write_status_" + status);
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
            int state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE,
                    BluetoothDevice.BOND_NONE);
            int previous = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE,
                    BluetoothDevice.BOND_NONE);
            log("BOND " + safeAddress(device) + ": "
                    + bondLabel(previous) + " → " + bondLabel(state));
            updateCandidate(device, -127, false, "", "bond event");
            if (state == BluetoothDevice.BOND_BONDING) {
                state("VERIFIED PEER · LE BONDING");
            } else if (state == BluetoothDevice.BOND_BONDED) {
                state("VERIFIED PEER · LE BOND BONDED");
                log("BOND_BONDED подтверждён; reverse connect всё ещё ждёт "
                        + "SECURE ATT OK");
                BluetoothGatt current = gatt;
                if (gattClientConnected && current != null) {
                    main.postDelayed(() -> {
                        if (gatt == current && gattClientConnected) {
                            discoverServices(current);
                        }
                    }, 800L);
                }
            } else if (previous == BluetoothDevice.BOND_BONDING) {
                state("VERIFIED PEER · LE BOND FAILED");
            }
        }
    };

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
