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
import dezz.status.widget.phone.transport.v2.BleRouteEpoch;
import dezz.status.widget.phone.transport.v2.ClassicAncsRecoveryPolicy;
import dezz.status.widget.phone.transport.v2.IphoneAppNameV2;
import dezz.status.widget.phone.transport.v2.IphoneBleMode;
import dezz.status.widget.phone.transport.v2.IphoneDualTransportListenerV2;
import dezz.status.widget.phone.transport.v2.IphoneDualTransportRuntimeV2;
import dezz.status.widget.phone.transport.v2.IphoneDualTransportStatusV2;
import dezz.status.widget.phone.transport.v2.IphoneNotificationEventV2;
import dezz.status.widget.phone.transport.v2.IphoneNotificationV2;
import dezz.status.widget.phone.transport.v2.IphoneRoleControlV2;
import dezz.status.widget.phone.transport.v2.IphoneLeEnrollmentRecordV2;
import dezz.status.widget.phone.transport.v2.IphoneTelemetryV2;
import dezz.status.widget.phone.transport.v2.IphoneTransportErrorV2;
import dezz.status.widget.phone.transport.v2.IphoneTransportLifecycle;
import dezz.status.widget.phone.transport.v2.IphoneTransportRecoveryStateV2;
import dezz.status.widget.phone.transport.v2.IphoneTransportStatusV2;
import dezz.status.widget.phone.transport.v2.android.AndroidIphoneDualRuntimeV2;
import dezz.status.widget.phone.transport.v2.android.AndroidIphoneLeEnrollmentV2;

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
    private static final long APP_DISPLAY_NAME_WAIT_TIMEOUT_MS = 15_000L;
    /** Helper heartbeats remain 30 s; live notifications and one-second reads refresh sooner. */
    private static final long HELPER_TELEMETRY_TIMEOUT_MS = 65_000L;
    private static final int DESIRED_GATT_MTU = 512;
    private static final int GATT_INSUFFICIENT_AUTHENTICATION = 5;
    private static final int GATT_INSUFFICIENT_AUTHORIZATION = 8;
    private static final int GATT_INSUFFICIENT_ENCRYPTION_KEY_SIZE = 12;
    private static final int GATT_INSUFFICIENT_ENCRYPTION = 15;
    private static final int ANCS_INVALID_PARAMETER = 0xA2;

    private static final String ACTION_HFP_CONNECTION =
            "android.bluetooth.headsetclient.profile.action.CONNECTION_STATE_CHANGED";
    private static final String ACTION_A2DP_CONNECTION =
            "android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED";
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

    public interface LeEnrollmentListener {
        void onState(@NonNull AndroidIphoneLeEnrollmentV2.Snapshot snapshot);
    }
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

        /** Confirmed ANCS readiness is independent from Classic/ACL phone presence. */
        default void onAncsConnectionChanged(boolean connected) { }
    }

    private static final PresenceSink NO_PRESENCE_SINK = connected -> { };

    private final Context context;
    private final Preferences prefs;
    private final ConnectorValueRegistry values;
    private final PresenceSink presenceSink;
    private final Object lifecycleLock = new Object();
    private final Handler mainHandler;
    private final PhoneTelemetryStore telemetryStore;
    private final CarRemoteControllerV1 carRemote;

    private long generation;
    private boolean running;
    private boolean lastPresence;
    private boolean lastAncsPresence;
    private String signature = "";
    @Nullable private HandlerThread workerThread;
    @Nullable private volatile Handler worker;
    @Nullable private BroadcastReceiver bluetoothReceiver;
    /** Sole clean-room dual-role ANCS owner. */
    @Nullable private volatile IphoneDualTransportRuntimeV2 ancsRuntimeV2;
    /** Explicit, user-confirmed foreground LE enrollment owner; never overlaps normal ANCS. */
    @Nullable private volatile AndroidIphoneLeEnrollmentV2 leEnrollmentV2;
    private volatile boolean leEnrollmentActive;
    private volatile boolean enrollmentHfpActive;
    @NonNull private volatile String enrollmentClassicAddress = "";
    private boolean nonRetryableEnrollmentRequired;
    @NonNull private String nonRetryableEnrollmentAddress = "";
    private long nextAncsTransportSession;
    private volatile long activeAncsTransportSession;
    private volatile boolean ancsTransportStartPending;
    @Nullable private PhoneOemConnectionBridge.Observation oemPowerObservation;

    // The following fields are worker-thread owned. Publishing is additionally guarded by
    // lifecycleLock so an old callback can never overwrite the explicit stopped snapshot.
    @Nullable private Config config;
    @Nullable private BluetoothDevice selectedDevice;
    private String selectedAddress = "";
    private String selectedName = "";
    private boolean aclConnected;
    /** Confirmed BR/EDR ACL only; an unknown/LE ACL never triggers Classic→ANCS recovery. */
    private boolean bredrAclConnected;
    private boolean a2dpConnected;
    private boolean hfpConnected;
    private boolean mapConnected;
    private boolean gattConnected;
    private boolean connected;
    /** True from ingress freeze until the rewritten coordinator commits the fresh target. */
    private boolean v2SwitchInProgress;
    private int reconnectAttempt;
    private String lastError = "";
    private String lastTypedV2Error = "";
    private long lastTypedV2ErrorTransportSession = -1L;
    private String ancsStatus = "stopped";
    private String smsStatus = "stopped";
    private String stockConnectionStatus = "stopped";
    private boolean ancsReady;
    private boolean smsAvailable;
    private boolean hfpBatteryKnown;
    private boolean hfpBatteryPercentScale;
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
    @Nullable private Boolean helperPhoneLocked;
    private long helperPowerUpdatedAtElapsed;
    private long helperNetworkUpdatedAtElapsed;
    private long helperLockUpdatedAtElapsed;
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

    @Nullable private Runnable deviceRescanTask;
    @Nullable private Runnable ancsStableReadyTask;
    @Nullable private Runnable stockConnectionTask;
    @Nullable private Runnable oemGattRefreshTask;
    @Nullable private Runnable classicAncsRecoveryTask;
    private ClassicAncsRecoveryPolicy.State classicAncsRecovery =
            ClassicAncsRecoveryPolicy.State.initial();
    private IphoneTransportRecoveryStateV2 ancsRecoveryRoute =
            IphoneTransportRecoveryStateV2.NO_OWNER;
    private int stockConnectionAttempt;
    private boolean stockConnectionRequestInProgress;
    private final Map<String, String> appDisplayNames = new LinkedHashMap<>();
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
        this.carRemote = new CarRemoteControllerV1(this.context, frame -> {
            IphoneDualTransportRuntimeV2 current = ancsRuntimeV2;
            if (current != null) current.sendCarRemoteFrame(frame);
        });
        PhoneConnectionJournal.initialize(this.context);
        PhoneConnectionJournal.append("controller", "контроллер создан; foreground owner готов");
    }

    /**
     * Idempotently applies all phone settings. Any transport-affecting change starts a new
     * generation; callbacks retained by Android from the previous GATT session become no-ops.
     */
    public void reconfigure() {
        Config next = Config.from(prefs);
        PhoneConnectionJournal.append("controller", "применение настроек: enabled="
                + next.enabled + ", BLE/ANCS=" + next.transportNeeded()
                + ", role=" + PhoneBleRole.diagnosticName(next.bleRole)
                + ", iPhone=" + (next.deviceAddress.isEmpty()
                ? "не выбран" : maskedAddress(next.deviceAddress)));
        synchronized (lifecycleLock) {
            if (running && signature.equals(next.signature())) {
                Handler current = worker;
                long token = generation;
                if (current != null) current.post(() -> publishSnapshot(token));
                return;
            }
            if (running && config != null
                    && config.bleRole != next.bleRole
                    && config.signatureWithoutBleRole().equals(
                    next.signatureWithoutBleRole())) {
                signature = next.signature();
                config = next;
                IphoneDualTransportRuntimeV2 runtime = ancsRuntimeV2;
                Handler current = worker;
                long token = generation;
                if (current != null) current.post(() -> runIfCurrent(token, () -> {
                    // Helper telemetry is generation-scoped even though the outer controller is
                    // intentionally retained across an A/B role switch.
                    clearHelperTelemetry();
                    refreshBatteryValues();
                    if (runtime != null) runtime.requestMode(v2Mode(next.bleRole));
                    publishSnapshot(token);
                }));
                PhoneConnectionJournal.append("controller",
                        "v2 role switch retained controller generation=" + token);
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
        PhoneConnectionJournal.append("controller", "остановка контроллера запрошена");
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
            IphoneDualTransportRuntimeV2 runtime = ancsRuntimeV2;
            if (runtime != null) {
                Handler currentWorker = worker;
                long token = generation;
                if (currentWorker == null) return false;
                currentWorker.post(() -> runIfCurrent(token, () -> {
                    if (ancsRuntimeV2 != runtime) return;
                    if (ancsReady && !v2SwitchInProgress
                            && ancsRecoveryRoute == IphoneTransportRecoveryStateV2.READY) {
                        PhoneConnectionJournal.append("controller",
                                "v2: проверка подтверждает живой ANCS; исправный GATT "
                                        + "не перезапускаю");
                        reconcileClassicAncsRecovery(token);
                        publishSnapshot(token);
                        return;
                    }
                    PhoneConnectionJournal.append("controller",
                            "v2: ручной same-role recovery без cache refresh и сброса пары");
                    runtime.requestSameModeRecovery();
                    if (classicProfileConnected()) runtime.selectedPhonePresent();
                }));
                return true;
            }
            Handler currentWorker = worker;
            long token = generation;
            if (currentWorker == null) return false;
            currentWorker.post(() -> runIfCurrent(token, () -> {
                PhoneConnectionJournal.append("controller",
                        "ручное чистое переподключение без сброса пары");
                String configuredAddress = config == null ? "" : config.deviceAddress;
                closeAncsTransport();
                cancelStockConnectionRequest();
                gattConnected = false;
                persistCurrentTelemetry();
                resetAncsSession(token, "connecting");
                reconnectAttempt = 0;
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

    /** Starts the only permitted first-time LE identity enrollment: explicit, foreground, HFP-bound. */
    public boolean beginSecureLeEnrollment(@NonNull LeEnrollmentListener callback) {
        Objects.requireNonNull(callback, "callback");
        synchronized (lifecycleLock) {
            if (!running || config == null || config.deviceAddress.isEmpty()
                    || worker == null || leEnrollmentV2 != null || leEnrollmentActive) {
                return false;
            }
            long token = generation;
            worker.post(() -> runIfCurrent(token,
                    () -> beginSecureLeEnrollmentOnWorker(token, callback)));
            return true;
        }
    }

    private void beginSecureLeEnrollmentOnWorker(
            long token, @NonNull LeEnrollmentListener callback) {
        Config current = config;
        if (current == null || selectedDevice == null || !hfpConnected
                || !current.deviceAddress.equalsIgnoreCase(selectedAddress)) {
            mainHandler.post(() -> callback.onState(new AndroidIphoneLeEnrollmentV2.Snapshot(
                    0L, AndroidIphoneLeEnrollmentV2.Phase.FAILED,
                    AndroidIphoneLeEnrollmentV2.ErrorKind.PREREQUISITE, "",
                    "exact selected Classic bond and active HFP are required")));
            return;
        }
        closeAncsTransport();
        cancelClassicAncsRecoveryWakeup();
        leEnrollmentActive = true;
        enrollmentClassicAddress = current.deviceAddress;
        enrollmentHfpActive = true;
        ancsStatus = "explicit_le_enrollment";
        String storedAndroidId = prefs.phoneBleV2AndroidInstallationId().trim();
        final UUID androidId;
        try {
            androidId = storedAndroidId.isEmpty()
                    ? UUID.randomUUID() : UUID.fromString(storedAndroidId);
            if (storedAndroidId.isEmpty()
                    && !prefs.commitPhoneBleV2AndroidInstallationId(androidId.toString())) {
                throw new IllegalStateException("Android identity was not durable");
            }
        } catch (RuntimeException unavailable) {
            leEnrollmentActive = false;
            enrollmentHfpActive = false;
            mainHandler.post(() -> callback.onState(new AndroidIphoneLeEnrollmentV2.Snapshot(
                    0L, AndroidIphoneLeEnrollmentV2.Phase.FAILED,
                    AndroidIphoneLeEnrollmentV2.ErrorKind.PERSISTENCE, "",
                    "Android installation identity is unavailable")));
            return;
        }
        String selectedClassic = current.deviceAddress;
        mainHandler.post(() -> {
            if (!isCurrent(token) || !leEnrollmentActive) return;
            AndroidIphoneLeEnrollmentV2 enrollment = new AndroidIphoneLeEnrollmentV2(
                    context, prefs,
                    address -> enrollmentHfpActive
                            && address != null
                            && address.equalsIgnoreCase(enrollmentClassicAddress),
                    snapshot -> {
                        callback.onState(snapshot);
                        PhoneConnectionJournal.append("le-enrollment",
                                "phase=" + snapshot.phase + ", error=" + snapshot.error
                                        + ", detail="
                                        + redactedDiagnostic(snapshot.detail));
                        if (snapshot.terminal()) {
                            finishSecureLeEnrollment(token, snapshot);
                        }
                    });
            leEnrollmentV2 = enrollment;
            enrollment.start(selectedClassic, androidId);
        });
    }

    public boolean confirmSecureLeEnrollmentSas(boolean matches) {
        AndroidIphoneLeEnrollmentV2 enrollment = leEnrollmentV2;
        if (enrollment == null) return false;
        enrollment.confirmMatchingSas(matches);
        return true;
    }

    public void cancelSecureLeEnrollment() {
        AndroidIphoneLeEnrollmentV2 enrollment = leEnrollmentV2;
        if (enrollment != null) enrollment.close();
    }

    private void finishSecureLeEnrollment(
            long token, @NonNull AndroidIphoneLeEnrollmentV2.Snapshot snapshot) {
        AndroidIphoneLeEnrollmentV2 enrollment = leEnrollmentV2;
        leEnrollmentV2 = null;
        if (enrollment != null && snapshot.phase != AndroidIphoneLeEnrollmentV2.Phase.SUCCEEDED) {
            enrollment.close();
        }
        Handler currentWorker = worker;
        if (currentWorker == null) return;
        currentWorker.post(() -> runIfCurrent(token, () -> {
            leEnrollmentActive = false;
            enrollmentHfpActive = false;
            enrollmentClassicAddress = "";
            if (snapshot.phase == AndroidIphoneLeEnrollmentV2.Phase.SUCCEEDED) {
                nonRetryableEnrollmentRequired = false;
                nonRetryableEnrollmentAddress = "";
                lastError = "";
                ancsRecoveryRoute = IphoneTransportRecoveryStateV2.NO_OWNER;
                Handler handler = worker;
                if (handler != null) {
                    handler.postDelayed(() -> runIfCurrent(token, () -> ensureGatt(token)), 300L);
                }
            } else {
                ancsStatus = "explicit_le_enrollment_failed";
            }
            publishSnapshot(token);
        }));
    }

    /** Removes only Natro's encrypted enrollment record; the system Classic pairing is untouched. */
    public boolean forgetSecureLeEnrollment() {
        if (leEnrollmentV2 != null || leEnrollmentActive) {
            cancelSecureLeEnrollment();
            return false;
        }
        boolean cleared = prefs.clearPhoneBleV2EnrollmentRecord();
        if (!cleared) return false;
        nonRetryableEnrollmentRequired = true;
        nonRetryableEnrollmentAddress = prefs.phoneDeviceAddress.get().trim();
        signature = "";
        Handler currentWorker = worker;
        long token = generation;
        if (currentWorker != null) {
            currentWorker.post(() -> runIfCurrent(token, () -> {
                closeAncsTransport();
                ancsReady = false;
                gattConnected = false;
                ancsStatus = "le_enrollment_required";
                updateConnected(token);
            }));
        }
        return true;
    }

    private boolean hasExactEnrollmentRecord(@NonNull String selectedClassicAddress) {
        String androidId = prefs.phoneBleV2AndroidInstallationId().trim();
        return recordMatchesAndroid(IphoneLeEnrollmentRecordV2.validForSelectedClassic(
                        prefs.phoneBleV2PendingEnrollmentRecord(), selectedClassicAddress),
                androidId)
                || recordMatchesAndroid(IphoneLeEnrollmentRecordV2.validForSelectedClassic(
                        prefs.phoneBleV2EnrollmentRecord(), selectedClassicAddress), androidId);
    }

    private static boolean recordMatchesAndroid(
            @Nullable IphoneLeEnrollmentRecordV2 record, @NonNull String androidId) {
        if (record == null || androidId.isEmpty()) return false;
        try {
            return record.androidInstallationId.equals(UUID.fromString(androidId));
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    private void stopLocked(@NonNull String reason) {
        generation++;
        running = false;

        Handler oldWorker = worker;
        HandlerThread oldThread = workerThread;
        BroadcastReceiver oldReceiver = bluetoothReceiver;
        IphoneDualTransportRuntimeV2 oldV2Runtime = ancsRuntimeV2;
        AndroidIphoneLeEnrollmentV2 oldEnrollment = leEnrollmentV2;
        PhoneOemConnectionBridge.Observation oldOemObservation = oemPowerObservation;
        worker = null;
        workerThread = null;
        bluetoothReceiver = null;
        ancsRuntimeV2 = null;
        leEnrollmentV2 = null;
        leEnrollmentActive = false;
        enrollmentHfpActive = false;
        enrollmentClassicAddress = "";
        ancsTransportStartPending = false;
        activeAncsTransportSession = ++nextAncsTransportSession;
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
        if (oldV2Runtime != null) oldV2Runtime.close();
        carRemote.routeUnavailable();
        if (oldEnrollment != null) oldEnrollment.close();
        cancelAllMirroredNotifications();
        clearRuntimeState(reason);
        updatePresenceLocked(false);
        updateAncsPresenceLocked(false);
        publishOfflineSnapshotLocked(reason);
        if (oldThread != null) oldThread.quitSafely();
    }

    private void startSession(long token, @NonNull Config next) {
        if (!isCurrent(token)) return;
        PhoneConnectionJournal.append("controller", "запуск сессии generation=" + token);
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
        bredrAclConnected = false;
        a2dpConnected = false;
        hfpConnected = false;
        mapConnected = false;
        gattConnected = false;
        classicAncsRecovery = ClassicAncsRecoveryPolicy.State.initial();
        ancsRecoveryRoute = IphoneTransportRecoveryStateV2.NO_OWNER;
        v2SwitchInProgress = false;
        enrollmentHfpActive = false;
        enrollmentClassicAddress = "";
        connected = false;
        reconnectAttempt = 0;
        stockConnectionAttempt = 0;
        stockConnectionRequestInProgress = false;
        lastError = "";
        lastTypedV2Error = "";
        lastTypedV2ErrorTransportSession = -1L;
        ancsStatus = diagnostic;
        smsStatus = diagnostic;
        stockConnectionStatus = diagnostic;
        ancsReady = false;
        smsAvailable = false;
        hfpBatteryKnown = false;
        hfpBatteryPercentScale = false;
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
        filter.addAction(ACTION_A2DP_CONNECTION);
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
            boolean enabled = state == BluetoothAdapter.STATE_ON;
            IphoneDualTransportRuntimeV2 runtime = ancsRuntimeV2;
            if (runtime != null) runtime.radioChanged(enabled);
            if (enabled) {
                if (selectedDevice == null) selectAndConnect(token);
            } else {
                invalidateSelectedPhone(token, "bluetooth_off", runtime != null);
            }
            return;
        }
        if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(action)) {
            BluetoothDevice changed = parcelableDevice(intent);
            if (!matchesConfiguredAddress(changed)) return;
            int state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE,
                    BluetoothDevice.BOND_NONE);
            if (state == BluetoothDevice.BOND_BONDED) {
                IphoneDualTransportRuntimeV2 runtime = ancsRuntimeV2;
                if (runtime != null) {
                    if (selectedDevice == null) selectAndConnect(token);
                    else if (!ancsReady) runtime.requestSameModeRecovery();
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
            int transport = intent.getIntExtra(EXTRA_ACL_TRANSPORT,
                    BluetoothDevice.TRANSPORT_AUTO);
            if (transport == BluetoothDevice.TRANSPORT_BREDR) {
                bredrAclConnected = true;
            }
            updateConnected(token);
            ensureGatt(token);
        } else if (BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(action)) {
            aclConnected = false;
            int transport = intent.getIntExtra(EXTRA_ACL_TRANSPORT,
                    BluetoothDevice.TRANSPORT_AUTO);
            if (transport == BluetoothDevice.TRANSPORT_BREDR) {
                persistCurrentTelemetry();
                bredrAclConnected = false;
                a2dpConnected = false;
                hfpConnected = false;
                refreshEnrollmentHfpGate();
                clearHfpData();
                clearGenericBatteryData();
                if (mapConnected) endMapSession("disconnected");
            }
            updateConnected(token);
            // Some ECARX Android 9 builds omit the transport extra. Unknown is neither proof of
            // BR/EDR profile loss nor proof that the exact GATT owner died, so it only prompts a
            // typed reconciliation and never clears HFP/A2DP/MAP or forces owner replacement.
            if (config != null && config.transportNeeded()
                    && transport != BluetoothDevice.TRANSPORT_BREDR) {
                requestManagedAncsReconnect(token,
                        "Selected iPhone ACL link disconnected",
                        transport == BluetoothDevice.TRANSPORT_LE);
            }
        } else if (ACTION_A2DP_CONNECTION.equals(action)) {
            int state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE,
                    BluetoothProfile.STATE_DISCONNECTED);
            a2dpConnected = state == BluetoothProfile.STATE_CONNECTED;
            updateConnected(token);
        } else if (ACTION_HFP_CONNECTION.equals(action)) {
            int state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE,
                    BluetoothProfile.STATE_DISCONNECTED);
            hfpConnected = state == BluetoothProfile.STATE_CONNECTED;
            refreshEnrollmentHfpGate();
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
            refreshEnrollmentHfpGate();
            applyHfpEvent(token, intent);
            updateConnected(token);
        } else if (ACTION_HFP_AUDIO_STATE.equals(action)) {
            hfpConnected = true;
            refreshEnrollmentHfpGate();
            applyHfpAudioState(token, intent.getIntExtra(
                    BluetoothProfile.EXTRA_STATE, HFP_AUDIO_DISCONNECTED),
                    booleanExtra(intent, EXTRA_HFP_AUDIO_WBS));
            updateConnected(token);
        } else if (ACTION_HFP_CALL_CHANGED.equals(action)) {
            hfpConnected = true;
            refreshEnrollmentHfpGate();
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
        invalidateSelectedPhone(token, status, false);
    }

    /** Clears Classic/profile state while optionally retaining the v2 owner across radio-off. */
    private void invalidateSelectedPhone(long token, @NonNull String status,
                                         boolean retainV2Runtime) {
        persistCurrentTelemetry();
        replaceOemPowerObservation(null);
        if (!retainV2Runtime) closeAncsTransport();
        cancelStockConnectionRequest();
        aclConnected = false;
        bredrAclConnected = false;
        a2dpConnected = false;
        hfpConnected = false;
        refreshEnrollmentHfpGate();
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

    /** Live gate consumed by the foreground enrollment object before every SMP/H/commit step. */
    private void refreshEnrollmentHfpGate() {
        Config current = config;
        enrollmentHfpActive = leEnrollmentActive && hfpConnected && current != null
                && current.deviceAddress.equalsIgnoreCase(selectedAddress)
                && current.deviceAddress.equalsIgnoreCase(enrollmentClassicAddress);
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
        readInitialDeviceBattery(selected);
        startOemPowerObservation(token, selectedAddress);
        updateConnected(token);
        queryInitialProfileState(token, adapter, BluetoothProfile.A2DP);
        queryInitialProfileState(token, adapter, PROFILE_HEADSET_CLIENT);
        queryInitialProfileState(token, adapter, PROFILE_MAP_CLIENT);
        beginStockConnectionRequest(token, selectedAddress);
    }

    /**
     * Reads Android's cached remote-device percentage before the first change broadcast arrives.
     * The API exists as a privileged SystemApi on Android 9, so reflection is intentionally
     * best-effort: KX11 firmware that blocks it still receives the event broadcast and BAS read.
     */
    private void readInitialDeviceBattery(@NonNull BluetoothDevice device) {
        try {
            Method method = BluetoothDevice.class.getMethod("getBatteryLevel");
            method.setAccessible(true);
            Object value = method.invoke(device);
            if (!(value instanceof Number)) return;
            int level = ((Number) value).intValue();
            if (level < 0 || level > 100) return;
            genericBatteryKnown = true;
            genericBatteryLevel = level;
            genericBatteryUpdatedAt = SystemClock.elapsedRealtime();
            refreshBatteryValues();
        } catch (Throwable ignored) {
            // Direct BAS and ACTION_DEVICE_BATTERY_LEVEL_CHANGED remain the platform event paths.
        }
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
        hfpBatteryPercentScale = rawPower > 5;
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
            PhoneConnectionJournal.append("v2-recovery",
                    "ECARX selected-phone state changed: " + change.name()
                            + "; reconcile exact Classic/ANCS state");
            // A vendor callback is only a nudge.  The typed policy preserves a live owner in
            // WAIT_ANCS/WAIT_AUTHORIZATION and only replaces a route proven down.
            reconcileClassicAncsRecovery(token);
            publishSnapshot(token);
        });
        oemGattRefreshTask = refresh;
        // Let the stock owner finish writing its paired-device state before v2 acquisition.
        // Repeated callbacks collapse into this one serialized same-role recovery/start.
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
                                refreshEnrollmentHfpGate();
                                applyInitialHfpState(token, initial);
                                updateConnected(token);
                            });
                        } else if (exactDevice != null
                                && profileId == BluetoothProfile.A2DP) {
                            runIfCurrent(token, () -> {
                                a2dpConnected = true;
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
        Config current = config;
        if (current == null) return;
        if (leEnrollmentActive) {
            ancsStatus = "explicit_le_enrollment";
            return;
        }
        if (!current.transportNeeded()) {
            ancsStatus = "disabled";
            return;
        }
        // Active HFP is a prerequisite only for explicit first-time enrollment. Routine Route A
        // reconnect is already pinned to the exact saved LE identity and then proves the durable
        // encrypted H secret, so waiting for one particular Classic profile only strands valid
        // A2DP/BR-EDR-first automotive reconnects.
        if (nonRetryableEnrollmentRequired
                && !current.deviceAddress.equalsIgnoreCase(nonRetryableEnrollmentAddress)) {
            nonRetryableEnrollmentRequired = false;
            nonRetryableEnrollmentAddress = "";
        }
        if (nonRetryableEnrollmentRequired) {
            ancsStatus = "le_enrollment_required";
            return;
        }
        ensureV2Runtime(token, current);
    }

    private void ensureV2Runtime(long token, @NonNull Config current) {
        synchronized (lifecycleLock) {
            if (!isCurrentLocked(token) || ancsRuntimeV2 != null
                    || ancsTransportStartPending) return;
            ancsTransportStartPending = true;
            activeAncsTransportSession = ++nextAncsTransportSession;
        }
        lastTypedV2Error = "";
        lastTypedV2ErrorTransportSession = -1L;
        ancsStatus = "starting_v2";
        ancsRecoveryRoute = IphoneTransportRecoveryStateV2.PROGRESSING;
        publishSnapshot(token);
        long transportSession = activeAncsTransportSession;
        mainHandler.post(() -> startV2RuntimeOnMain(
                token,
                transportSession,
                current.deviceAddress,
                current.bleRole,
                current.experimentalRouteBEnabled));
    }

    private void startV2RuntimeOnMain(long token, long transportSession,
                                      @NonNull String selectedBondAddress, int bleRole,
                                      boolean allowExperimentalRouteB) {
        if (!isCurrent(token) || transportSession != activeAncsTransportSession) return;
        final IphoneDualTransportRuntimeV2 created;
        try {
            created = AndroidIphoneDualRuntimeV2.create(context, prefs);
        } catch (RuntimeException error) {
            dispatchAncsTransport(token, transportSession, () -> {
                synchronized (lifecycleLock) {
                    if (isCurrentLocked(token)) ancsTransportStartPending = false;
                }
                lastError = "ANCS v2 init: " + safeMessage(error);
                ancsStatus = "failed_closed";
                ancsRecoveryRoute = IphoneTransportRecoveryStateV2.OWNER_DOWN;
                reconcileClassicAncsRecovery(token);
                publishSnapshot(token);
            });
            return;
        }
        boolean mayStart;
        int startBleRole = bleRole;
        synchronized (lifecycleLock) {
            mayStart = isCurrentLocked(token)
                    && transportSession == activeAncsTransportSession
                    && ancsRuntimeV2 == null;
            // A settings callback can change only the desired role while construction is queued
            // on main.  Initialization must consume that latest durable intent.
            if (mayStart && config != null) startBleRole = config.bleRole;
        }
        if (!mayStart) {
            created.close();
            return;
        }
        BluetoothAdapter bluetooth = bluetoothAdapter();
        boolean initialRadioEnabled = bluetooth != null && bluetooth.isEnabled();
        created.start(new IphoneDualTransportRuntimeV2.Config(
                        selectedBondAddress,
                        v2Mode(startBleRole),
                        initialRadioEnabled,
                        prefs.phoneBleV2HelperInstallationId().trim().isEmpty(),
                        true,
                        allowExperimentalRouteB),
                new V2TransportListener(token, transportSession));

        boolean accepted;
        int latestBleRole = startBleRole;
        synchronized (lifecycleLock) {
            accepted = isCurrentLocked(token)
                    && transportSession == activeAncsTransportSession
                    && ancsRuntimeV2 == null;
            if (accepted) {
                ancsRuntimeV2 = created;
                ancsTransportStartPending = false;
                if (config != null) latestBleRole = config.bleRole;
            }
        }
        if (!accepted) {
            // start() and close() use the same FIFO, so a rejected construction can never expose
            // or overlap its restoration owner with the replacement runtime.
            created.close();
            return;
        }
        if (latestBleRole != startBleRole) {
            created.requestMode(v2Mode(latestBleRole));
        }
        // Re-read after publication.  A radio broadcast in the construction window saw no
        // runtime by design; this FIFO command closes that otherwise-lost boundary.
        BluetoothAdapter currentBluetooth = bluetoothAdapter();
        created.radioChanged(currentBluetooth != null && currentBluetooth.isEnabled());
    }

    private final class V2TransportListener implements IphoneDualTransportListenerV2 {
        private final long token;
        private final long transportSession;

        V2TransportListener(long token, long transportSession) {
            this.token = token;
            this.transportSession = transportSession;
        }

        @Override public void onDualTransportStatus(IphoneDualTransportStatusV2 status) {
            dispatchAncsTransport(token, transportSession,
                    () -> applyV2DualStatus(token, transportSession, status));
        }

        @Override public void onPlatformDiagnostic(
                IphoneBleMode mode, BleRouteEpoch epoch, String detail) {
            dispatchAncsTransport(token, transportSession, () ->
                    PhoneConnectionJournal.append("v2-platform",
                            "mode=" + mode + ", epoch=" + epoch + ", detail="
                                    + bounded(redactedDiagnostic(detail), 256)));
        }

        @Override public void onStatus(IphoneTransportStatusV2 status) {
            dispatchAncsTransport(token, transportSession, () -> {
                if (status == null
                        || status.lifecycle != IphoneTransportLifecycle.READY) {
                    carRemote.routeUnavailable();
                }
                applyV2RouteStatus(token, status);
            });
        }

        @Override public void onTelemetry(IphoneTelemetryV2 telemetry) {
            dispatchAncsTransport(token, transportSession,
                    () -> applyHelperTelemetryV2(token, telemetry));
        }

        @Override public void onStandardBatteryPercentage(int percentage, String source) {
            dispatchAncsTransport(token, transportSession,
                    () -> applyStandardBatteryPercentage(token, percentage, source));
        }

        @Override public void onNotificationEvent(IphoneNotificationEventV2 event) {
            if (event == null || event.eventId != IphoneNotificationEventV2.REMOVED) return;
            dispatchAncsTransport(token, transportSession,
                    () -> removeAncsNotification(token, event.uid));
        }

        @Override public void onNotification(IphoneNotificationV2 notification) {
            dispatchAncsTransport(token, transportSession,
                    () -> handleAncsTransportNotificationV2(token, notification));
        }

        @Override public void onAppName(IphoneAppNameV2 appName) {
            if (appName == null) return;
            dispatchAncsTransport(token, transportSession,
                    () -> handleAncsTransportAppName(
                            token, appName.appIdentifier, appName.appName));
        }

        @Override public void onHelperInstallationIdLearned(String helperInstallationId) {
            PhoneConnectionJournal.append("v2-identity",
                    "Helper installation identity accepted for selected bond");
        }

        @Override public void onRoleControl(IphoneRoleControlV2 control) {
            PhoneConnectionJournal.append("v2-switch",
                    "remote control " + (control == null ? "invalid" : control.type));
        }

        @Override public void onRoleControlWriteResult(
                IphoneRoleControlV2 control, boolean success) {
            PhoneConnectionJournal.append("v2-switch",
                    "control completion=" + success);
        }

        @Override public void onCarRemoteFrame(byte[] frame) {
            byte[] exact = frame == null ? null : frame.clone();
            dispatchAncsTransport(token, transportSession, () -> {
                if (exact != null) carRemote.accept(exact);
            });
        }

        @Override public void onLocalTerminal(IphoneBleMode mode, BleRouteEpoch epoch) {
            dispatchAncsTransport(token, transportSession,
                    () -> applyV2LocalTerminal(token, mode, epoch));
        }

        @Override public void onError(IphoneTransportErrorV2 error) {
            dispatchAncsTransport(token, transportSession, () -> {
                if (error == null) return;
                PhoneConnectionJournal.append("v2-error",
                        "mode=" + error.mode + ", epoch=" + error.epoch
                                + ", kind=" + error.kind + ", retryable="
                                + error.retryable + ", detail="
                                + redactedDiagnostic(error.detail));
                String typedError = bounded(error.kind + ": " + error.detail, 512);
                lastTypedV2Error = typedError;
                lastTypedV2ErrorTransportSession = transportSession;
                lastError = typedError;
                if (!error.retryable) {
                    ancsStatus = "failed_closed";
                    if (error.kind == IphoneTransportErrorV2.Kind.BOND_TRANSPORT_UNAVAILABLE
                            || error.kind == IphoneTransportErrorV2.Kind.PEER_PROOF_REJECTED) {
                        nonRetryableEnrollmentRequired = true;
                        nonRetryableEnrollmentAddress = config == null
                                ? "" : config.deviceAddress;
                        ancsStatus = "le_enrollment_required";
                        cancelClassicAncsRecoveryWakeup();
                    }
                }
                publishSnapshot(token);
            });
        }
    }

    private void applyV2DualStatus(long token, long transportSession,
                                   @Nullable IphoneDualTransportStatusV2 status) {
        if (status == null) return;
        PhoneConnectionJournal.append("v2-switch",
                "phase=" + status.switchPhase + ", desired=" + status.desiredMode
                        + ", active=" + status.activeMode + ", failure="
                        + status.switchFailure + ", detail="
                        + redactedDiagnostic(status.detail));
        boolean activePhase = status.switchPhase ==
                dezz.status.widget.phone.transport.switching.BleRoleSwitchReducer.Phase.ACTIVE;
        v2SwitchInProgress = !activePhase;
        if (!activePhase) {
            ancsRecoveryRoute = status.switchPhase ==
                    dezz.status.widget.phone.transport.switching.BleRoleSwitchReducer.Phase.FAILED
                    ? IphoneTransportRecoveryStateV2.OWNER_DOWN
                    : IphoneTransportRecoveryStateV2.PROGRESSING;
        }
        if (!activePhase) {
            // FREEZING is the generation boundary. Clear before any successor route can publish
            // so a prior Helper sample cannot survive switch, retry, link-loss, or FAILED.
            clearHelperTelemetry();
            refreshBatteryValues();
        }
        if (!activePhase && ancsReady) {
            resetAncsSession(token, "switching_"
                    + status.switchPhase.name().toLowerCase(Locale.ROOT));
        }
        if (!activePhase && status.switchPhase !=
                dezz.status.widget.phone.transport.switching.BleRoleSwitchReducer.Phase.FAILED) {
            ancsStatus = "switching_"
                    + status.switchPhase.name().toLowerCase(Locale.ROOT);
            updateMessageAvailability();
        }
        if (status.switchPhase !=
                dezz.status.widget.phone.transport.switching.BleRoleSwitchReducer.Phase.FAILED) {
            // desiredMode is the durable user/peer intent.  activeMode may be a deliberately
            // short intermediate role while a rapid A→B→A request is being drained.
            boolean diagnosticRouteB = config != null && config.experimentalRouteBEnabled;
            int storedRole = diagnosticRouteB
                    && status.desiredMode == IphoneBleMode.ANDROID_PERIPHERAL
                    ? PhoneBleRole.IPHONE_CENTRAL : PhoneBleRole.IPHONE_PERIPHERAL;
            if (prefs.phoneBleRole.get() != storedRole) prefs.phoneBleRole.set(storedRole);
        } else if (status.switchPhase ==
                dezz.status.widget.phone.transport.switching.BleRoleSwitchReducer.Phase.FAILED) {
            ancsReady = false;
            gattConnected = false;
            ancsStatus = "switch_failed_closed";
            lastError = preserveTypedV2Failure(transportSession, status.detail);
            updateAncsPresenceLocked(false);
        }
        reconcileClassicAncsRecovery(token);
        publishSnapshot(token);
    }

    @NonNull
    private String preserveTypedV2Failure(long transportSession, @Nullable String genericDetail) {
        String generic = bounded(genericDetail, 512);
        if (!generic.contains("TARGET_START_FAILED")
                || lastTypedV2ErrorTransportSession != transportSession
                || lastTypedV2Error.isEmpty()
                || generic.contains(lastTypedV2Error)) {
            return generic;
        }
        // Keep the typed route evidence first; TARGET_START_FAILED is only the coordinator's
        // generic terminal summary and must not erase the platform/root cause that preceded it.
        return bounded(lastTypedV2Error + "; " + generic, 512);
    }

    private void applyV2RouteStatus(long token, @Nullable IphoneTransportStatusV2 status) {
        if (status == null) return;
        IphoneTransportLifecycle lifecycle = status.lifecycle;
        ancsRecoveryRoute = status.recoveryState;
        boolean ready = lifecycle == IphoneTransportLifecycle.READY
                && !v2SwitchInProgress;
        boolean linkActive = ready
                || lifecycle == IphoneTransportLifecycle.AUTHENTICATING
                || lifecycle == IphoneTransportLifecycle.SUBSCRIBING;
        boolean hadSession = gattConnected || ancsReady;
        gattConnected = linkActive;
        ancsReady = ready;
        if (ready) {
            lastError = "";
            lastTypedV2Error = "";
            lastTypedV2ErrorTransportSession = -1L;
            reconnectAttempt = 0;
            scheduleStableAncsReadyReset(token);
            cancelSmsFallbackNotifications();
        } else if (!linkActive) {
            clearHelperTelemetry();
            clearBasData();
            if (hadSession) {
                resetAncsSession(token, lifecycle.name().toLowerCase(Locale.ROOT));
            }
        }
        if (status.recoveryState == IphoneTransportRecoveryStateV2.WAIT_SERVICE_CHANGED) {
            ancsStatus = "waiting_for_ancs_service_changed";
        } else if (status.recoveryState ==
                IphoneTransportRecoveryStateV2.WAIT_AUTHORIZATION) {
            ancsStatus = "authorization_required_on_iphone";
        } else {
            ancsStatus = lifecycle.name().toLowerCase(Locale.ROOT);
        }
        updateMessageAvailability();
        updateConnected(token);
    }

    private void applyV2LocalTerminal(long token, @Nullable IphoneBleMode mode,
                                      @Nullable BleRouteEpoch epoch) {
        PhoneConnectionJournal.append("v2-terminal",
                "local route terminal " + mode + " epoch=" + epoch);
        if (!v2SwitchInProgress) {
            ancsRecoveryRoute = IphoneTransportRecoveryStateV2.OWNER_DOWN;
            reconcileClassicAncsRecovery(token);
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

    /** Applies the fixed eight-byte v2 telemetry frame from either rewritten topology. */
    private void applyHelperTelemetryV2(long token, @Nullable IphoneTelemetryV2 telemetry) {
        if (telemetry == null) return;
        long now = SystemClock.elapsedRealtime();
        boolean powerChanged = helperPowerUpdatedAtElapsed <= 0L
                || !Objects.equals(helperBatteryLevel, telemetry.batteryPercent)
                || !Objects.equals(helperExternalPower, telemetry.externalPower)
                || !Objects.equals(helperChargeState, telemetry.chargeState);
        boolean networkChanged = helperNetworkUpdatedAtElapsed <= 0L
                || !Objects.equals(helperNetworkType, telemetry.networkType);
        boolean lockChanged = helperLockUpdatedAtElapsed <= 0L
                || !Objects.equals(helperPhoneLocked, telemetry.phoneLocked);
        helperBatteryLevel = telemetry.batteryPercent;
        helperExternalPower = telemetry.externalPower;
        helperChargeState = telemetry.chargeState;
        helperNetworkType = telemetry.networkType;
        helperPhoneLocked = telemetry.phoneLocked;
        helperPowerUpdatedAtElapsed = now;
        helperNetworkUpdatedAtElapsed = now;
        helperLockUpdatedAtElapsed = telemetry.phoneLocked == null ? 0L : now;
        refreshBatteryValues();
        if (powerChanged || networkChanged || lockChanged) {
            PhoneConnectionJournal.append("helper-telemetry-v2",
                    "battery=" + telemetry.batteryPercent
                            + ", externalPower=" + telemetry.externalPower
                            + ", chargeState=" + telemetry.chargeState
                            + ", network=" + telemetry.networkType
                            + ", locked=" + telemetry.phoneLocked
                            + ", sequence=" + telemetry.sequence);
            markTelemetryUpdated(true, true);
        } else {
            batteryLiveSeenThisConnection = true;
            networkLiveSeenThisConnection = true;
            telemetryStale = false;
        }
        scheduleHelperTelemetryExpiry(token);
        if (powerChanged || networkChanged || lockChanged) publishSnapshot(token);
    }

    /** Restores HA1159's exact standard-Bluetooth percentage on the rewritten v2 route. */
    private void applyStandardBatteryPercentage(long token, int percentage,
                                                @Nullable String source) {
        if (percentage < 0 || percentage > 100) return;
        basBatteryKnown = true;
        basBatteryLevel = percentage;
        basBatteryUpdatedAt = SystemClock.elapsedRealtime();
        refreshBatteryValues();
        markTelemetryUpdated(true, false);
        PhoneConnectionJournal.append("battery-standard",
                "exact percentage via " + bounded(source, 48));
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
        if (helperLockUpdatedAtElapsed > 0L) {
            nextDeadline = Math.min(nextDeadline,
                    helperLockUpdatedAtElapsed + HELPER_TELEMETRY_TIMEOUT_MS);
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
            if (helperLockUpdatedAtElapsed > 0L
                    && checkedAt - helperLockUpdatedAtElapsed >= HELPER_TELEMETRY_TIMEOUT_MS) {
                helperPhoneLocked = null;
                helperLockUpdatedAtElapsed = 0L;
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
        helperPhoneLocked = null;
        helperPowerUpdatedAtElapsed = 0L;
        helperNetworkUpdatedAtElapsed = 0L;
        helperLockUpdatedAtElapsed = 0L;
    }

    private void handleAncsTransportNotificationV2(
            long token, @Nullable IphoneNotificationV2 item) {
        if (item == null) return;
        handleAncsNotificationFields(token, item.eventId, item.uid, item.categoryId,
                item.appIdentifier, item.appName, item.title, item.message, item.date,
                item.observedAtElapsedMillis);
    }

    private void handleAncsNotificationFields(
            long token, int eventId, long uid, int categoryId,
            @Nullable String appIdentifier, @Nullable String appName,
            @Nullable String title, @Nullable String message, @Nullable String date,
            long observedAt) {
        if (!ancsReady || config == null || !config.ancsNeeded()) return;
        if (eventId == dezz.status.widget.phone.transport.AncsProtocol.EVENT_REMOVED) {
            removeAncsNotification(token, uid);
            return;
        }
        String cleanAppIdentifier = bounded(appIdentifier, 512);
        String cleanAppName = bounded(appName, 256);
        PhoneAppIconStore.Observation iconObservation =
                PhoneAppIconStore.get(context).observe(
                        cleanAppIdentifier, cleanAppName, categoryId);
        boolean appleMessage = isAppleMessagesApp(appIdentifier);
        boolean allowed = config.notificationsEnabled
                || config.messagesEnabled && appleMessage;
        if (!allowed || !config.allowsNotification(
                appIdentifier, categoryId)) return;
        long observedAtElapsedMs = observedAt > 0L
                ? observedAt : SystemClock.elapsedRealtime();
        if (SystemClock.elapsedRealtime() - observedAtElapsedMs
                > APP_DISPLAY_NAME_WAIT_TIMEOUT_MS) {
            Log.w(TAG, "Dropping ANCS notification " + uid
                    + ": transport item exceeded real-time TTL");
            return;
        }

        if (!cleanAppIdentifier.isEmpty() && !cleanAppName.isEmpty()) {
            cacheAppDisplayName(cleanAppIdentifier, cleanAppName);
        }
        AncsProtocol.Notification notification = new AncsProtocol.Notification(
                uid,
                cleanAppIdentifier,
                bounded(title, 4096),
                "",
                bounded(message, 4096),
                bounded(date, 256));
        boolean hasDisplayName = !cleanAppName.isEmpty()
                || appDisplayNames.containsKey(cleanAppIdentifier);
        NotificationRecord record = new NotificationRecord(
                notification, categoryId, System.currentTimeMillis(), false,
                observedAtElapsedMs, iconObservation.iconWasCached);
        notificationCache.remove(uid);
        notificationCache.put(uid, record);
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

    private void removeAncsNotification(long token, long uid) {
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

    private void applyHfpEvent(long token, @NonNull Intent intent) {
        boolean batteryUpdated = false;
        boolean networkUpdated = false;
        Integer rawBattery = intExtra(intent, EXTRA_HFP_BATTERY, "battery_level",
                "batteryLevel");
        if (rawBattery != null) {
            Integer normalized = PhoneConnectorPolicy.normalizeHfpBattery(rawBattery);
            if (normalized != null) {
                hfpBatteryKnown = true;
                hfpBatteryPercentScale = rawBattery > 5;
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
        // Power-state fields are Helper-only. Percentage may be direct Android/BAS data, but it
        // is intentionally not persisted: after reconnect the current cached value/read/event
        // must win instead of showing an old exact-looking number from disk.
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
        PhoneBatteryLevelPolicy.Reading reading = PhoneBatteryLevelPolicy.resolve(
                basBatteryKnown, basBatteryLevel, basBatteryUpdatedAt,
                genericBatteryKnown, genericBatteryLevel, genericBatteryUpdatedAt,
                helperPowerUpdatedAtElapsed > 0L ? helperBatteryLevel : null,
                hfpBatteryKnown, hfpBatteryLevel, hfpBatteryPercentScale);
        if (reading == null) {
            batteryLevel = null;
            batteryLevelSource = "";
        } else {
            batteryLevel = reading.level;
            batteryLevelSource = reading.source;
        }
        // Cable/charging state remains Helper-only. The percentage is deliberately independent:
        // Android/BAS direct 0..100 readings must never be overwritten by iOS's coarse public
        // UIDevice value from the same Helper heartbeat.
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
        hfpBatteryPercentScale = false;
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
        boolean next = aclConnected || a2dpConnected || hfpConnected
                || mapConnected || gattConnected;
        if (next) {
            refreshBatteryValues();
        }
        // Re-run on every exact profile edge, even when BLE already keeps aggregate presence
        // true. The pure policy remembers its armed wakeup/route condition, so duplicate profile
        // broadcasts cannot emit a second recovery command or recurse into owner construction.
        reconcileClassicAncsRecovery(token);
        if (connected == next) {
            publishSnapshot(token);
            return;
        }
        connected = next;
        PhoneConnectionJournal.append("connection", next
                ? "iPhone подключён; acl=" + aclConnected + ", hfp=" + hfpConnected
                + ", map=" + mapConnected + ", gatt=" + gattConnected
                : "iPhone отключён; очищаю только live-состояние");
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
        notificationCache.clear();
        ancsMessageCache.clear();
        appDisplayNames.clear();
        lastAppIdentifier = "";
        lastAppName = "";
        lastAppCategoryId = 0;
        lastNotificationAt = 0L;
        rebuildMessageSnapshot();
    }

    /**
     * Reconciles exact selected-phone Classic profiles with the typed ANCS route condition.
     * Unknown/LE ACL alone is deliberately excluded: only HFP, A2DP, MAP or a confirmed BR/EDR
     * ACL may assert the Classic side of the one-logical-phone invariant.
     */
    private void reconcileClassicAncsRecovery(long token) {
        if (!isCurrent(token)) return;
        boolean wasClassicConnected = classicAncsRecovery.classicConnected;
        boolean isClassicConnected = classicProfileConnected();
        ClassicAncsRecoveryPolicy.Transition transition =
                ClassicAncsRecoveryPolicy.observe(
                        classicAncsRecovery,
                        isClassicConnected,
                        ancsRecoveryRoute,
                        SystemClock.elapsedRealtime());
        applyClassicAncsRecoveryTransition(token, transition);
        if (!wasClassicConnected && isClassicConnected) {
            IphoneDualTransportRuntimeV2 runtime = ancsRuntimeV2;
            if (runtime != null) {
                PhoneConnectionJournal.append("classic-ancs",
                        "Exact Classic phone appeared; prompt sole Route-A GATT owner");
                runtime.selectedPhonePresent();
            }
        }
    }

    private boolean classicProfileConnected() {
        return bredrAclConnected || a2dpConnected || hfpConnected || mapConnected;
    }

    private void applyClassicAncsRecoveryTransition(
            long token, @NonNull ClassicAncsRecoveryPolicy.Transition transition) {
        if (!isCurrent(token)) return;
        classicAncsRecovery = transition.state;
        for (ClassicAncsRecoveryPolicy.Effect effect : transition.effects) {
            switch (effect.type) {
                case CANCEL_WAKEUP:
                    cancelClassicAncsRecoveryWakeup();
                    break;
                case ENSURE_ROUTE:
                    PhoneConnectionJournal.append("classic-ancs",
                            "Classic exact profile is up; ensure "
                                    + PhoneBleRole.diagnosticName(config == null
                                    ? PhoneBleRole.IPHONE_PERIPHERAL : config.bleRole)
                                    + " ANCS route without bond/radio mutation");
                    ancsStatus = "classic_connected_ancs_recovery";
                    IphoneDualTransportRuntimeV2 existing = ancsRuntimeV2;
                    if (existing != null) existing.requestSameModeRecovery();
                    else ensureGatt(token);
                    break;
                case REQUEST_SAME_ROUTE_RECOVERY:
                    PhoneConnectionJournal.append("classic-ancs",
                            "ANCS owner is down while Classic is up; fresh same-route generation"
                                    + " (command "
                                    + transition.state.recoveryCommands + ")");
                    ancsStatus = "classic_connected_ancs_recovery";
                    IphoneDualTransportRuntimeV2 runtime = ancsRuntimeV2;
                    if (runtime != null) {
                        runtime.requestSameModeRecovery();
                        runtime.selectedPhonePresent();
                    } else {
                        ancsRecoveryRoute = IphoneTransportRecoveryStateV2.NO_OWNER;
                        ensureGatt(token);
                    }
                    break;
                case SCHEDULE_WAKEUP:
                    scheduleClassicAncsRecoveryWakeup(token, effect.timerGeneration,
                            effect.deadlineMillis);
                    break;
                default:
                    throw new AssertionError(effect.type);
            }
        }
    }

    private void scheduleClassicAncsRecoveryWakeup(long token, long timerGeneration,
                                                    long deadlineMillis) {
        Handler handler = worker;
        if (handler == null) return;
        cancelClassicAncsRecoveryWakeup();
        Runnable wakeup = () -> runIfCurrent(token, () -> {
            classicAncsRecoveryTask = null;
            ClassicAncsRecoveryPolicy.Transition transition =
                    ClassicAncsRecoveryPolicy.wakeup(
                            classicAncsRecovery,
                            timerGeneration,
                            SystemClock.elapsedRealtime());
            applyClassicAncsRecoveryTransition(token, transition);
            publishSnapshot(token);
        });
        classicAncsRecoveryTask = wakeup;
        long delay = Math.max(0L,
                deadlineMillis - SystemClock.elapsedRealtime());
        handler.postDelayed(wakeup, delay);
    }

    private void cancelClassicAncsRecoveryWakeup() {
        Runnable task = classicAncsRecoveryTask;
        if (task != null && worker != null) worker.removeCallbacks(task);
        classicAncsRecoveryTask = null;
    }

    /**
     * Do not destroy a healthy ANCS owner merely because ECARX emitted ACL_DISCONNECTED. The
     * live transport knows the resolved BLE identity and can recover without a car-Bluetooth
     * power cycle. If that owner vanished concurrently, fall back to the normal outer restart.
     */
    private void requestManagedAncsReconnect(long token, @NonNull String detail,
                                             boolean confirmedLeLoss) {
        lastError = bounded(detail, 512);
        PhoneConnectionJournal.append("v2-recovery",
                redactedDiagnostic(detail) + ", confirmedLeLoss=" + confirmedLeLoss);
        // An unknown transport extra is not proof that the exact GATT owner died.  A confirmed
        // LE loss is; the Classic-aware policy then decides whether/when to allocate a fresh
        // same-topology generation.
        if (confirmedLeLoss) {
            ancsRecoveryRoute = IphoneTransportRecoveryStateV2.OWNER_DOWN;
        }
        reconcileClassicAncsRecovery(token);
        publishSnapshot(token);
    }

    private void scheduleStableAncsReadyReset(long token) {
        cancelStableAncsReadyReset();
        Handler handler = worker;
        if (handler == null) return;
        Runnable stable = () -> runIfCurrent(token, () -> {
            ancsStableReadyTask = null;
            if (!ancsReady || ancsRuntimeV2 == null) return;
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
        IphoneDualTransportRuntimeV2 previousV2;
        synchronized (lifecycleLock) {
            previousV2 = ancsRuntimeV2;
            ancsRuntimeV2 = null;
            ancsTransportStartPending = false;
            activeAncsTransportSession = ++nextAncsTransportSession;
        }
        lastTypedV2Error = "";
        lastTypedV2ErrorTransportSession = -1L;
        if (previousV2 != null) previousV2.close();
        carRemote.routeUnavailable();
        ancsRecoveryRoute = IphoneTransportRecoveryStateV2.NO_OWNER;
    }

    private void cancelRetryTasks() {
        cancelDeviceRescan();
        cancelStableAncsReadyReset();
        cancelStockConnectionRequest();
        cancelOemGattRefresh();
        cancelClassicAncsRecoveryWakeup();
    }

    private void cancelDeviceRescan() {
        Runnable retry = deviceRescanTask;
        if (retry != null && worker != null) worker.removeCallbacks(retry);
        deviceRescanTask = null;
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
            updateAncsPresenceLocked(ancsReady);
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
                null, false, "string", "", now));
        snapshot.add(value("transport.ancs.remote_name",
                null, false, "string", "", now));
        snapshot.add(value("profiles.hfp", null, false, "boolean", "", now));
        snapshot.add(value("profiles.map", null, false, "boolean", "", now));
        snapshot.add(value("profiles.ble", null, false, "boolean", "", now));
        snapshot.add(value("profiles.ancs", null, false, "boolean", "", now));
        snapshot.add(value("device.locked", null, false, "boolean", "", now));
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

        Integer effectiveBatteryLevel = batteryLevel;
        String effectiveBatteryLevelSource = effectiveBatteryLevel == null
                ? "" : batteryLevelSource;
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
        // v2 discovery is UUID-only.  These legacy connector keys remain for schema stability,
        // but no synthetic local name is put on air in either topology.
        snapshot.add(value("transport.ancs.local_name",
                null, false, "string", "", now));
        snapshot.add(value("transport.ancs.remote_name",
                null, false, "string", "", now));
        snapshot.add(value("profiles.hfp", hfpConnected, active, "boolean", "", now));
        snapshot.add(value("profiles.map", mapConnected, active, "boolean", "", now));
        snapshot.add(value("profiles.ble", gattConnected, active, "boolean", "", now));
        snapshot.add(value("profiles.ancs", ancsReady, active, "boolean", "", now));
        snapshot.add(value("device.locked", helperPhoneLocked,
                helperLockUpdatedAtElapsed > 0L, "boolean", "", now));
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
        device.put("ancs_local_name", null);
        device.put("ancs_remote_name", null);
        device.put("ancs_discovery_identity", "uuid_only");
        device.put("ancs_ble_role", config == null
                ? PhoneBleRole.diagnosticName(PhoneBleRole.IPHONE_PERIPHERAL)
                : PhoneBleRole.diagnosticName(config.bleRole));
        device.put("stock_connection", stockConnectionStatus);
        device.put("ancs_setup", config != null && config.transportNeeded()
                ? "dual_route_v2" : "disabled");
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
                    updateAncsPresenceLocked(ancsReady);
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

    private void updateAncsPresenceLocked(boolean value) {
        if (lastAncsPresence == value) return;
        lastAncsPresence = value;
        try {
            presenceSink.onAncsConnectionChanged(value);
        } catch (RuntimeException error) {
            Log.w(TAG, "ANCS presence sink failed", error);
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

    /** Settings name the iPhone role; the v2 runtime names the corresponding Android role. */
    @NonNull
    private static IphoneBleMode v2Mode(int bleRole) {
        return PhoneBleRole.isIphoneCentral(bleRole)
                ? IphoneBleMode.ANDROID_PERIPHERAL
                : IphoneBleMode.ANDROID_CENTRAL;
    }

    private static final class Config {
        final boolean enabled;
        @NonNull final String deviceAddress;
        final int bleRole;
        final boolean experimentalRouteBEnabled;
        final boolean notificationsEnabled;
        final boolean messagesEnabled;
        final boolean includeNotificationText;
        final boolean ancsPresenceEnabled;
        @NonNull final Set<Integer> notificationCategoryIds;
        final int notificationAppFilterMode;
        @NonNull final Set<String> notificationAppFilterKeys;

        Config(boolean enabled, @NonNull String deviceAddress,
               int bleRole, boolean experimentalRouteBEnabled,
               boolean notificationsEnabled,
               boolean messagesEnabled, boolean includeNotificationText,
               boolean ancsPresenceEnabled,
               @NonNull Set<Integer> notificationCategoryIds,
               int notificationAppFilterMode,
               @NonNull Set<String> notificationAppFilterKeys) {
            this.enabled = enabled;
            this.deviceAddress = deviceAddress;
            this.bleRole = PhoneBleRole.normalize(bleRole);
            this.experimentalRouteBEnabled = experimentalRouteBEnabled;
            this.notificationsEnabled = notificationsEnabled;
            this.messagesEnabled = messagesEnabled;
            this.includeNotificationText = includeNotificationText;
            this.ancsPresenceEnabled = ancsPresenceEnabled;
            this.notificationCategoryIds = notificationCategoryIds;
            this.notificationAppFilterMode =
                    PhoneNotificationFilter.normalizeMode(notificationAppFilterMode);
            this.notificationAppFilterKeys = notificationAppFilterKeys;
        }

        @NonNull
        static Config from(@NonNull Preferences prefs) {
            String classicAddress = bounded(prefs.phoneDeviceAddress.get(), 64);
            boolean diagnosticRouteB = prefs.phoneBleExperimentalRouteBEnabled.get();
            return new Config(prefs.phoneConnectorEnabled.get(),
                    classicAddress, productionBleRole(prefs.phoneBleRole.get(),
                            diagnosticRouteB), diagnosticRouteB,
                    prefs.phoneNotificationsEnabled.get(),
                    prefs.phoneMessagesEnabled.get(),
                    prefs.phoneIncludeNotificationText.get(),
                    prefs.phoneSprutAncsPresenceEnabled.get(),
                    PhoneNotificationFilter.parseCategoryIds(
                            prefs.phoneNotificationCategoryIds.get()),
                    prefs.phoneNotificationAppFilterMode.get(),
                    PhoneNotificationFilter.parseAppKeys(
                            prefs.phoneNotificationAppFilterKeys.get()));
        }

        /** Route B remains compiled for explicit diagnostics; production always starts Route A. */
        private static int productionBleRole(int storedRole, boolean diagnosticRouteB) {
            return diagnosticRouteB
                    ? PhoneBleRole.normalize(storedRole)
                    : PhoneBleRole.IPHONE_PERIPHERAL;
        }

        @NonNull
        String signature() {
            return enabled + "|" + deviceAddress
                    + "|" + bleRole + "|" + experimentalRouteBEnabled
                    + "|" + notificationsEnabled + "|"
                    + messagesEnabled + "|" + includeNotificationText + "|"
                    + ancsPresenceEnabled + "|"
                    + PhoneNotificationFilter.serializeCategoryIds(
                    notificationCategoryIds) + "|" + notificationAppFilterMode + "|"
                    + PhoneNotificationFilter.serializeAppKeys(notificationAppFilterKeys);
        }

        @NonNull
        String signatureWithoutBleRole() {
            return enabled + "|" + deviceAddress
                    + "|" + experimentalRouteBEnabled + "|"
                    + notificationsEnabled + "|" + messagesEnabled + "|"
                    + includeNotificationText + "|" + ancsPresenceEnabled + "|"
                    + PhoneNotificationFilter.serializeCategoryIds(
                    notificationCategoryIds) + "|" + notificationAppFilterMode + "|"
                    + PhoneNotificationFilter.serializeAppKeys(notificationAppFilterKeys);
        }

        boolean ancsNeeded() {
            return notificationsEnabled || messagesEnabled || ancsPresenceEnabled;
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
