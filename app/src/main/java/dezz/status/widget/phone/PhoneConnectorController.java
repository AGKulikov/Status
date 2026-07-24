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
import android.os.Handler;
import android.os.HandlerThread;
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
    private static final long ATTRIBUTE_TIMEOUT_MS = 8_000L;
    private static final long GATT_OPERATION_TIMEOUT_MS = 10_000L;
    private static final long DEVICE_RESCAN_MS = 15_000L;

    private static final String ACTION_HFP_CONNECTION =
            "android.bluetooth.headsetclient.profile.action.CONNECTION_STATE_CHANGED";
    private static final String ACTION_HFP_AG_EVENT =
            "android.bluetooth.headsetclient.profile.action.AG_EVENT";
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

    private static final UUID BATTERY_SERVICE =
            UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb");
    private static final UUID BATTERY_LEVEL =
            UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb");
    private static final UUID BATTERY_POWER_STATE =
            UUID.fromString("00002a1a-0000-1000-8000-00805f9b34fb");

    private static final String CHANNEL_GROUP_ID = "phone_mirror_group";
    private static final String CHANNEL_ID = "phone_mirror";
    private static final String NOTIFICATION_GROUP_KEY =
            "dezz.status.widget.phone.MIRRORED";
    private static final String BLUETOOTH_SENDER_PERMISSION =
            "android.permission.BLUETOOTH_PRIVILEGED";
    private static final int SUMMARY_NOTIFICATION_ID = 0x50484f4e;

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

    private long generation;
    private boolean running;
    private boolean lastPresence;
    private String signature = "";
    @Nullable private HandlerThread workerThread;
    @Nullable private Handler worker;
    @Nullable private BroadcastReceiver bluetoothReceiver;
    @Nullable private BluetoothGatt gatt;

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
    private boolean ancsReady;
    private boolean smsAvailable;
    private boolean hfpBatteryKnown;
    private boolean hfpChargingKnown;
    @Nullable private Integer batteryLevel;
    @Nullable private Boolean batteryCharging;
    @Nullable private Boolean networkAvailable;
    @Nullable private Integer networkSignal;
    @Nullable private Boolean networkRoaming;
    private String networkOperator = "";
    private String networkType = "";

    @Nullable private BluetoothGattCharacteristic ancsControlPoint;
    @Nullable private BluetoothGattCharacteristic ancsDataSource;
    @Nullable private BluetoothGattCharacteristic ancsNotificationSource;
    private boolean ancsDataSubscribed;
    private boolean ancsNotificationSubscribed;
    private final ArrayDeque<GattOperation> gattOperations = new ArrayDeque<>();
    @Nullable private GattOperation currentGattOperation;
    @Nullable private Runnable gattOperationTimeout;

    private final Map<Long, AncsProtocol.Event> pendingAncsEvents = new LinkedHashMap<>();
    private final ArrayDeque<Long> attributeRequests = new ArrayDeque<>();
    private final Set<Long> queuedAttributeUids = new LinkedHashSet<>();
    private final Set<Long> dirtyAttributeUids = new LinkedHashSet<>();
    private final Set<Long> removedAttributeUids = new LinkedHashSet<>();
    @Nullable private Long activeAttributeUid;
    @Nullable private AncsProtocol.AttributeAccumulator attributeAccumulator;
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

    private void stopLocked(@NonNull String reason) {
        generation++;
        running = false;

        Handler oldWorker = worker;
        HandlerThread oldThread = workerThread;
        BroadcastReceiver oldReceiver = bluetoothReceiver;
        BluetoothGatt oldGatt = gatt;
        worker = null;
        workerThread = null;
        bluetoothReceiver = null;
        gatt = null;
        config = null;

        if (oldWorker != null) oldWorker.removeCallbacksAndMessages(null);
        if (oldReceiver != null) {
            try {
                context.unregisterReceiver(oldReceiver);
            } catch (IllegalArgumentException | SecurityException ignored) {
                // Already unregistered or an OEM revoked Bluetooth access during shutdown.
            }
        }
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
        ancsStatus = next.ancsNeeded() ? "starting" : "disabled";
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
        lastError = "";
        ancsStatus = diagnostic;
        smsStatus = diagnostic;
        ancsReady = false;
        smsAvailable = false;
        hfpBatteryKnown = false;
        hfpChargingKnown = false;
        batteryLevel = null;
        batteryCharging = null;
        networkAvailable = null;
        networkSignal = null;
        networkRoaming = null;
        networkOperator = "";
        networkType = "";
        clearAncsRuntime();
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
            if (state == BluetoothDevice.BOND_BONDED) selectAndConnect(token);
            else if (state == BluetoothDevice.BOND_NONE) {
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
                hfpConnected = false;
                if (mapConnected) endMapSession("disconnected");
            }
            updateConnected(token);
        } else if (ACTION_HFP_CONNECTION.equals(action)) {
            int state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE,
                    BluetoothProfile.STATE_DISCONNECTED);
            hfpConnected = state == BluetoothProfile.STATE_CONNECTED;
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
        } else if (ACTION_DEVICE_BATTERY_LEVEL_CHANGED.equals(action)
                && !hfpBatteryKnown) {
            Integer raw = intExtra(intent, EXTRA_DEVICE_BATTERY_LEVEL, "battery_level");
            if (raw != null && raw >= 0 && raw <= 100) {
                batteryLevel = raw;
                publishSnapshot(token);
            }
        }
    }

    private void invalidateSelectedPhone(long token, @NonNull String status) {
        BluetoothGatt previous = gatt;
        gatt = null;
        closeGatt(previous);
        aclConnected = false;
        hfpConnected = false;
        mapConnected = false;
        gattConnected = false;
        updateConnected(token);
        selectedDevice = null;
        selectedAddress = "";
        selectedName = "";
        ancsStatus = status;
        smsStatus = config != null && config.messagesEnabled ? status : "disabled";
        publishSnapshot(token);
    }

    private void selectAndConnect(long token) {
        BluetoothAdapter adapter = bluetoothAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            ancsStatus = "bluetooth_off";
            smsStatus = config != null && config.messagesEnabled
                    ? "bluetooth_off" : smsStatus;
            publishSnapshot(token);
            scheduleDeviceRescan(token);
            return;
        }
        String configuredAddress = config == null ? "" : config.deviceAddress;
        if (configuredAddress.isEmpty()) {
            ancsStatus = "no_configured_phone";
            smsStatus = config != null && config.messagesEnabled
                    ? "no_configured_phone" : smsStatus;
            publishSnapshot(token);
            return;
        }
        BluetoothDevice selected = selectBondedPhone(adapter,
                configuredAddress);
        if (selected == null) {
            ancsStatus = "not_bonded";
            smsStatus = config != null && config.messagesEnabled
                    ? "not_bonded" : smsStatus;
            publishSnapshot(token);
            scheduleDeviceRescan(token);
            return;
        }
        selectedDevice = selected;
        selectedAddress = safeAddress(selected);
        selectedName = safeName(selected);
        updateConnected(token);
        queryInitialProfileState(token, adapter, BluetoothProfile.A2DP);
        queryInitialProfileState(token, adapter, PROFILE_HEADSET_CLIENT);
        queryInitialProfileState(token, adapter, PROFILE_MAP_CLIENT);
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
                            runIfCurrent(token, () -> {
                                hfpConnected = true;
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
        if (handler == null) return;
        handler.postDelayed(() -> {
            runIfCurrent(token, () -> {
                if (selectedDevice == null) selectAndConnect(token);
            });
        }, DEVICE_RESCAN_MS);
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
        if (!isCurrent(token) || selectedDevice == null || gatt != null) return;
        Config current = config;
        if (current == null) return;
        if (current.ancsNeeded()) ancsStatus = "connecting";
        try {
            BluetoothGatt created = selectedDevice.connectGatt(context, false,
                    new SessionGattCallback(token), BluetoothDevice.TRANSPORT_LE);
            synchronized (lifecycleLock) {
                if (!isCurrentLocked(token)) {
                    closeGatt(created);
                    return;
                }
                gatt = created;
            }
            if (created == null) scheduleGattReconnect(token, "connectGatt returned null");
        } catch (Throwable error) {
            // Some Android 9 vendor images expose a broken/hidden GATT bridge. Never crash the
            // foreground service for an optional phone capability.
            scheduleGattReconnect(token, "GATT connect: " + safeMessage(error));
        }
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
                            status == BluetoothGatt.GATT_SUCCESS);
                }
            });
        }

        @Override public void onCharacteristicWrite(BluetoothGatt callbackGatt,
                                                    BluetoothGattCharacteristic characteristic,
                                                    int status) {
            dispatch(() -> {
                if (callbackGatt == gatt) {
                    finishGattOperation(token, GattKind.CONTROL_WRITE,
                            status == BluetoothGatt.GATT_SUCCESS);
                }
            });
        }

        @Override public void onCharacteristicRead(BluetoothGatt callbackGatt,
                                                   BluetoothGattCharacteristic characteristic,
                                                   int status) {
            byte[] value = characteristic.getValue();
            dispatch(() -> {
                if (callbackGatt != gatt) return;
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    applyBatteryCharacteristic(token, characteristic.getUuid(), value);
                }
                finishGattOperation(token, GattKind.CHARACTERISTIC_READ,
                        status == BluetoothGatt.GATT_SUCCESS);
            });
        }

        @Override public void onCharacteristicChanged(BluetoothGatt callbackGatt,
                                                      BluetoothGattCharacteristic characteristic) {
            byte[] value = characteristic.getValue();
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
            gattConnected = true;
            if (config != null && config.ancsNeeded()) ancsStatus = "discovering";
            else reconnectAttempt = 0;
            updateConnected(token);
            try {
                if (!callbackGatt.discoverServices()) {
                    scheduleGattReconnect(token, "Service discovery did not start");
                }
            } catch (RuntimeException error) {
                scheduleGattReconnect(token, "Service discovery: " + safeMessage(error));
            }
            return;
        }
        gattConnected = false;
        resetAncsSession(token, "disconnected");
        updateConnected(token);
        scheduleGattReconnect(token, "GATT disconnected (" + status + ")");
    }

    private void handleServicesDiscovered(long token, @NonNull BluetoothGatt callbackGatt,
                                          int status) {
        if (callbackGatt != gatt || status != BluetoothGatt.GATT_SUCCESS) {
            scheduleGattReconnect(token, "Service discovery failed (" + status + ")");
            return;
        }
        resetGattOperationState();
        configureBatteryService(callbackGatt);
        if (config == null || !config.ancsNeeded()) {
            ancsStatus = "disabled";
            pumpGattOperations(token);
            publishSnapshot(token);
            return;
        }

        BluetoothGattService service = callbackGatt.getService(AncsProtocol.SERVICE);
        if (service == null) {
            ancsStatus = "service_unavailable";
            publishSnapshot(token);
            scheduleServiceRediscovery(token);
            pumpGattOperations(token);
            return;
        }
        ancsControlPoint = service.getCharacteristic(AncsProtocol.CONTROL_POINT);
        ancsDataSource = service.getCharacteristic(AncsProtocol.DATA_SOURCE);
        ancsNotificationSource = service.getCharacteristic(AncsProtocol.NOTIFICATION_SOURCE);
        if (ancsControlPoint == null || ancsDataSource == null
                || ancsNotificationSource == null) {
            ancsStatus = "characteristic_unavailable";
            publishSnapshot(token);
            scheduleServiceRediscovery(token);
            pumpGattOperations(token);
            return;
        }
        if (!queueNotificationSubscription(callbackGatt, ancsDataSource,
                GattTag.ANCS_DATA)
                || !queueNotificationSubscription(callbackGatt, ancsNotificationSource,
                GattTag.ANCS_NOTIFICATION)) {
            scheduleGattReconnect(token, "ANCS subscription is unsupported");
            return;
        }
        ancsStatus = "subscribing";
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
        BluetoothGattCharacteristic power = battery.getCharacteristic(BATTERY_POWER_STATE);
        if (power != null) {
            queueCharacteristicRead(power, GattTag.BATTERY_POWER_READ);
            queueOptionalNotificationSubscription(callbackGatt, power,
                    GattTag.BATTERY_POWER_SUBSCRIPTION);
        }
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
                    null, null));
            return true;
        } catch (RuntimeException error) {
            return false;
        }
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
                        null, null));
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
                operation.descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
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
            finishGattOperation(token, operation.kind, false);
            return;
        }
        Handler handler = worker;
        if (handler != null) {
            Runnable timeout = () -> runIfCurrent(token, () -> {
                if (currentGattOperation == operation) {
                    finishGattOperation(token, operation.kind, false);
                }
            });
            gattOperationTimeout = timeout;
            handler.postDelayed(timeout, GATT_OPERATION_TIMEOUT_MS);
        }
    }

    private void finishGattOperation(long token, @NonNull GattKind callbackKind,
                                     boolean success) {
        GattOperation operation = currentGattOperation;
        if (operation == null || operation.kind != callbackKind) return;
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
        } else if (operation.tag == GattTag.CONTROL) {
            if (success) scheduleAttributeTimeout(token, operation.uid);
            else {
                scheduleGattReconnect(token, "ANCS control-point write failed");
                return;
            }
        }
        if (!success && (operation.tag == GattTag.ANCS_DATA
                || operation.tag == GattTag.ANCS_NOTIFICATION)) {
            scheduleGattReconnect(token, "ANCS descriptor write failed");
            return;
        }
        maybeFinishAncsSetup(token);
        pumpGattOperations(token);
    }

    private void maybeFinishAncsSetup(long token) {
        if (!ancsReady && ancsDataSubscribed && ancsNotificationSubscribed) {
            ancsReady = true;
            reconnectAttempt = 0;
            ancsStatus = "ready";
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
        } else if (BATTERY_LEVEL.equals(uuid) || BATTERY_POWER_STATE.equals(uuid)) {
            applyBatteryCharacteristic(token, uuid, payload);
        }
    }

    private void handleAncsEvent(long token, @Nullable AncsProtocol.Event event) {
        if (event == null || !ancsReady) return;
        if (event.eventId == AncsProtocol.EVENT_REMOVED) {
            removeAncsNotification(token, event.uid);
            return;
        }
        removedAttributeUids.remove(event.uid);
        pendingAncsEvents.put(event.uid, event);
        if (activeAttributeUid != null && activeAttributeUid == event.uid) {
            dirtyAttributeUids.add(event.uid);
        } else if (queuedAttributeUids.add(event.uid)) {
            attributeRequests.add(event.uid);
        }
        pumpAttributeRequests(token);
    }

    private void pumpAttributeRequests(long token) {
        if (!ancsReady || activeAttributeUid != null || ancsControlPoint == null) return;
        Long uid = attributeRequests.poll();
        if (uid == null) return;
        queuedAttributeUids.remove(uid);
        activeAttributeUid = uid;
        attributeAccumulator = new AncsProtocol.AttributeAccumulator(uid);
        byte[] request = AncsProtocol.notificationAttributeRequest(uid,
                config != null && config.includeNotificationText);
        gattOperations.add(new GattOperation(GattKind.CONTROL_WRITE, GattTag.CONTROL,
                null, ancsControlPoint, request, uid));
        pumpGattOperations(token);
    }

    private void scheduleAttributeTimeout(long token, long uid) {
        Handler handler = worker;
        if (handler == null) {
            scheduleGattReconnect(token, "ANCS response worker unavailable");
            return;
        }
        Runnable timeout = () -> runIfCurrent(token, () -> {
            if (activeAttributeUid != null && activeAttributeUid.longValue() == uid) {
                scheduleGattReconnect(token, "ANCS attribute response timed out");
            }
        });
        attributeTimeout = timeout;
        handler.postDelayed(timeout, ATTRIBUTE_TIMEOUT_MS);
    }

    private void handleAncsData(long token, @Nullable byte[] payload) {
        Long uid = activeAttributeUid;
        AncsProtocol.AttributeAccumulator accumulator = attributeAccumulator;
        if (uid == null || accumulator == null || payload == null) return;
        if (!accumulator.append(payload)) {
            scheduleGattReconnect(token, "ANCS attribute response exceeded its limit");
            return;
        }
        AncsProtocol.Notification notification = accumulator.complete();
        if (notification == null) return;
        if (!removedAttributeUids.contains(uid)) {
            AncsProtocol.Event event = pendingAncsEvents.remove(uid);
            int categoryId = event == null ? 0 : event.categoryId;
            NotificationRecord record = new NotificationRecord(notification, categoryId);
            notificationCache.remove(uid);
            notificationCache.put(uid, record);
            trimNotificationCache();
            upsertAncsMessage(record);
            mirrorAncsNotification(token, record);
        }
        completeAttributeRequest(token, uid);
    }

    private void completeAttributeRequest(long token, long uid) {
        Runnable timeout = attributeTimeout;
        if (timeout != null && worker != null) worker.removeCallbacks(timeout);
        attributeTimeout = null;
        if (activeAttributeUid != null && activeAttributeUid == uid) {
            activeAttributeUid = null;
            attributeAccumulator = null;
        }
        if (dirtyAttributeUids.remove(uid) && !removedAttributeUids.contains(uid)
                && queuedAttributeUids.add(uid)) {
            attributeRequests.add(uid);
        }
        publishSnapshot(token);
        pumpAttributeRequests(token);
    }

    private void removeAncsNotification(long token, long uid) {
        removedAttributeUids.add(uid);
        pendingAncsEvents.remove(uid);
        dirtyAttributeUids.remove(uid);
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
            Integer notificationId = mirroredAncsIds.remove(uid);
            if (notificationId != null) cancelMirroredNotification(notificationId);
        }
    }

    private void applyBatteryCharacteristic(long token, @NonNull UUID uuid,
                                            @Nullable byte[] payload) {
        if (payload == null || payload.length == 0) return;
        int raw = payload[0] & 0xff;
        if (BATTERY_LEVEL.equals(uuid) && !hfpBatteryKnown && raw <= 100) {
            batteryLevel = raw;
        } else if (BATTERY_POWER_STATE.equals(uuid) && !hfpChargingKnown) {
            batteryCharging = PhoneConnectorPolicy.decodeBatteryPowerState(raw);
        }
        publishSnapshot(token);
    }

    private void applyHfpEvent(long token, @NonNull Intent intent) {
        Integer rawBattery = intExtra(intent, EXTRA_HFP_BATTERY, "battery_level",
                "batteryLevel");
        if (rawBattery != null) {
            Integer normalized = PhoneConnectorPolicy.normalizeHfpBattery(rawBattery);
            if (normalized != null) {
                hfpBatteryKnown = true;
                batteryLevel = normalized;
            }
        }
        Integer rawSignal = intExtra(intent, EXTRA_HFP_NETWORK_SIGNAL,
                "android.bluetooth.headsetclient.extra.NETWORK_SIGNAL",
                "network_signal", "signal", "signal_level");
        if (rawSignal != null) {
            networkSignal = PhoneConnectorPolicy.normalizeHfpSignal(rawSignal);
        }
        Integer status = intExtra(intent, EXTRA_HFP_NETWORK_STATUS, "network_status");
        if (status != null) {
            networkAvailable = status != 0;
            if (!networkAvailable) {
                networkSignal = null;
                networkRoaming = null;
                networkOperator = "";
                networkType = "";
            }
        }
        if (status == null || status != 0) {
            Boolean roaming = booleanExtra(intent, EXTRA_HFP_NETWORK_ROAMING,
                    "network_roaming", "roaming");
            if (roaming != null) networkRoaming = roaming;
            String operator = stringExtra(intent, EXTRA_HFP_OPERATOR, "operator_name",
                    "operator");
            if (operator != null) networkOperator = bounded(operator, 256);

            // Network generation is an optional vendor extension. Read known aliases, but do
            // not reflect into hidden Bluetooth classes or assume a particular OEM bundle.
            String type = stringExtra(intent,
                    "android.bluetooth.headsetclient.extra.NETWORK_TYPE",
                    "network_type", "networkType", "phone_network_type");
            if (type != null) networkType = bounded(type, 64);
        }
        Boolean charging = booleanExtra(intent,
                "android.bluetooth.headsetclient.extra.BATTERY_CHARGING",
                "battery_charging", "batteryCharging", "charging");
        if (charging != null) {
            hfpChargingKnown = true;
            batteryCharging = charging;
        }
        publishSnapshot(token);
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
        String handle = bounded(intent.getStringExtra(EXTRA_MAP_MESSAGE_HANDLE), 256);
        String text = bounded(intent.getStringExtra(Intent.EXTRA_TEXT), 4_096);
        if (handle.isEmpty() || text.isEmpty()) {
            lastError = "MAP message ignored: missing handle or text";
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
        row.put("sender", firstNonEmpty(senderName, senderUri));
        if (current.includeNotificationText) row.put("body", text);
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
        row.put("sender", firstNonEmpty(record.notification.title,
                record.notification.subtitle));
        if (current.includeNotificationText) {
            row.put("body", firstNonEmpty(record.notification.message,
                    record.notification.subtitle));
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
        List<Map<String, Object>> ordered = new ArrayList<>(mapMessageCache.values());
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
        batteryLevel = null;
        batteryCharging = null;
        hfpBatteryKnown = false;
        hfpChargingKnown = false;
        networkAvailable = null;
        networkSignal = null;
        networkRoaming = null;
        networkOperator = "";
        networkType = "";
        resetAncsSession(token, "disconnected");
        endMapSession("disconnected");
        cancelAllMirroredNotifications();
    }

    private void resetAncsSession(long token, @NonNull String status) {
        ancsReady = false;
        ancsStatus = config != null && config.ancsNeeded() ? status : "disabled";
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
        ancsDataSubscribed = false;
        ancsNotificationSubscribed = false;
        resetGattOperationState();
        pendingAncsEvents.clear();
        attributeRequests.clear();
        queuedAttributeUids.clear();
        dirtyAttributeUids.clear();
        removedAttributeUids.clear();
        activeAttributeUid = null;
        attributeAccumulator = null;
        Runnable timeout = attributeTimeout;
        if (timeout != null && worker != null) worker.removeCallbacks(timeout);
        attributeTimeout = null;
        notificationCache.clear();
        ancsMessageCache.clear();
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
        if (!isCurrent(token)) return;
        lastError = bounded(detail, 512);
        ancsStatus = "retrying";
        BluetoothGatt previous;
        synchronized (lifecycleLock) {
            previous = gatt;
            gatt = null;
        }
        closeGatt(previous);
        gattConnected = false;
        resetAncsSession(token, "retrying");
        updateConnected(token);
        long delay = PhoneConnectorPolicy.reconnectDelayMillis(reconnectAttempt++);
        Handler handler = worker;
        if (handler != null) handler.postDelayed(() -> {
            runIfCurrent(token, () -> ensureGatt(token));
        }, delay);
    }

    private void scheduleServiceRediscovery(long token) {
        long delay = PhoneConnectorPolicy.reconnectDelayMillis(reconnectAttempt++);
        Handler handler = worker;
        BluetoothGatt expected = gatt;
        if (handler == null) return;
        handler.postDelayed(() -> {
            runIfCurrent(token, () -> {
                BluetoothGatt current = gatt;
                if (current == null || current != expected) return;
                try {
                    if (!current.discoverServices()) {
                        scheduleGattReconnect(token, "ANCS rediscovery did not start");
                    }
                } catch (RuntimeException error) {
                    scheduleGattReconnect(token, "ANCS rediscovery: "
                            + safeMessage(error));
                }
            });
        }, delay);
    }

    private void mirrorAncsNotification(long token, @NonNull NotificationRecord record) {
        Config current = config;
        boolean appleMessage = isAppleMessagesApp(record.notification.appIdentifier);
        boolean allowed = current != null && (current.notificationsEnabled
                || current.messagesEnabled && appleMessage);
        if (!isCurrent(token) || !allowed) return;
        int notificationId = ancsNotificationId(record.notification.uid);
        Notification.Builder builder = baseNotificationBuilder()
                .setSubText(record.notification.appIdentifier)
                .setCategory(appleMessage
                        ? Notification.CATEGORY_MESSAGE : Notification.CATEGORY_STATUS);
        if (current.includeNotificationText) {
            String title = firstNonEmpty(record.notification.title,
                    AncsProtocol.categoryLabel(record.categoryId));
            String message = firstNonEmpty(record.notification.message,
                    record.notification.subtitle);
            builder.setContentTitle(title).setContentText(message)
                    .setStyle(new Notification.BigTextStyle().bigText(message));
        } else {
            builder.setContentTitle(AncsProtocol.categoryLabel(record.categoryId))
                    .setContentText(record.notification.appIdentifier);
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
        snapshot.add(value("battery.level", null, false, "number", "%", now));
        snapshot.add(value("battery.charging", null, false, "boolean", "", now));
        snapshot.add(value("network.available", null, false, "boolean", "", now));
        snapshot.add(value("network.operator", null, false, "string", "", now));
        snapshot.add(value("network.type", null, false, "string", "", now));
        snapshot.add(value("network.signal", null, false, "number", "%", now));
        snapshot.add(value("network.roaming", null, false, "boolean", "", now));
        snapshot.add(value("notifications.count", 0, false, "number", "", now));
        snapshot.add(value("notifications.latest", null, false, "object", "", now));
        snapshot.add(value("notifications.items", Collections.emptyList(), false,
                "list", "", now));
        snapshot.add(value("messages.unread", 0, false, "number", "", now));
        snapshot.add(value("messages.latest", null, false, "object", "", now));
        snapshot.add(value("diagnostics.device", Collections.emptyMap(), true,
                "object", "", now));
        snapshot.add(value("diagnostics.ancs", reason, true, "string", "", now));
        snapshot.add(value("diagnostics.sms", reason, true, "string", "", now));
        snapshot.add(value("diagnostics.last_error", "", true, "string", "", now));
        values.replaceSnapshot(ConnectorType.PHONE, CONNECTOR_ID, snapshot);
    }

    @NonNull
    private List<ConnectorValue> buildSnapshot(boolean active) {
        long now = System.currentTimeMillis();
        List<ConnectorValue> snapshot = new ArrayList<>();
        snapshot.add(value("connected", connected, active, "boolean", "", now));
        snapshot.add(value("battery.level", batteryLevel,
                connected && batteryLevel != null, "number", "%", now));
        snapshot.add(value("battery.charging", batteryCharging,
                connected && batteryCharging != null, "boolean", "", now));
        snapshot.add(value("network.available", networkAvailable,
                connected && networkAvailable != null, "boolean", "", now));
        snapshot.add(value("network.operator",
                networkOperator.isEmpty() ? null : networkOperator,
                connected && !networkOperator.isEmpty(), "string", "", now));
        snapshot.add(value("network.type", networkType.isEmpty() ? null : networkType,
                connected && !networkType.isEmpty(), "string", "", now));
        snapshot.add(value("network.signal", networkSignal,
                connected && networkSignal != null, "number", "%", now));
        snapshot.add(value("network.roaming", networkRoaming,
                connected && networkRoaming != null, "boolean", "", now));

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
        snapshot.add(value("diagnostics.device", device, !selectedAddress.isEmpty(),
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
            LinkedHashMap<String, Object> value = new LinkedHashMap<>();
            value.put("uid", item.notification.uid);
            value.put("app", item.notification.appIdentifier);
            value.put("category", AncsProtocol.categoryLabel(item.categoryId));
            value.put("date", item.notification.date);
            if (includeText) {
                value.put("title", item.notification.title);
                value.put("subtitle", item.notification.subtitle);
                value.put("message", item.notification.message);
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
                || normalized.startsWith("com.apple.mobilesms.");
    }

    private static long messageDate(@NonNull Map<String, Object> message) {
        Object raw = message.get("date");
        return raw instanceof Number ? Math.max(0L, ((Number) raw).longValue()) : 0L;
    }

    @NonNull
    private static String messageFingerprint(@NonNull Map<String, Object> message) {
        String sender = String.valueOf(message.get("sender")).trim().toLowerCase(Locale.ROOT);
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
        ANCS_DATA,
        ANCS_NOTIFICATION,
        BATTERY_LEVEL_READ,
        BATTERY_POWER_READ,
        BATTERY_LEVEL_SUBSCRIPTION,
        BATTERY_POWER_SUBSCRIPTION,
        CONTROL
    }

    private static final class GattOperation {
        @NonNull final GattKind kind;
        @NonNull final GattTag tag;
        @Nullable final BluetoothGattDescriptor descriptor;
        @Nullable final BluetoothGattCharacteristic characteristic;
        @Nullable final byte[] payload;
        final long uid;

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
        }
    }

    private static final class NotificationRecord {
        @NonNull final AncsProtocol.Notification notification;
        final int categoryId;

        NotificationRecord(@NonNull AncsProtocol.Notification notification, int categoryId) {
            this.notification = notification;
            this.categoryId = categoryId;
        }
    }

    private static final class Config {
        final boolean enabled;
        @NonNull final String deviceAddress;
        final boolean notificationsEnabled;
        final boolean messagesEnabled;
        final boolean includeNotificationText;

        Config(boolean enabled, @NonNull String deviceAddress, boolean notificationsEnabled,
               boolean messagesEnabled, boolean includeNotificationText) {
            this.enabled = enabled;
            this.deviceAddress = deviceAddress;
            this.notificationsEnabled = notificationsEnabled;
            this.messagesEnabled = messagesEnabled;
            this.includeNotificationText = includeNotificationText;
        }

        @NonNull
        static Config from(@NonNull Preferences prefs) {
            return new Config(prefs.phoneConnectorEnabled.get(),
                    bounded(prefs.phoneDeviceAddress.get(), 64),
                    prefs.phoneNotificationsEnabled.get(),
                    prefs.phoneMessagesEnabled.get(),
                    prefs.phoneIncludeNotificationText.get());
        }

        @NonNull
        String signature() {
            return enabled + "|" + deviceAddress + "|" + notificationsEnabled + "|"
                    + messagesEnabled + "|" + includeNotificationText;
        }

        boolean ancsNeeded() {
            return notificationsEnabled || messagesEnabled;
        }
    }
}
