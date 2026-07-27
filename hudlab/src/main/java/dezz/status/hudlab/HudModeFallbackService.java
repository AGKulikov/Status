/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package dezz.status.hudlab;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;
import android.util.SparseIntArray;

import com.ecarx.xui.adaptapi.ECarXCarProxy;

import java.util.Locale;

import ecarx.car.ECarXCar;
import ecarx.car.hardware.ECarXCarPropertyValue;
import ecarx.car.hardware.annotation.ApiResult;
import ecarx.car.hardware.signal.CarSignalManager;
import ecarx.car.hardware.signal.SignalFilter;
import ecarx.car.hardware.vehicle.CarPAEventCallback;
import ecarx.car.hardware.vehicle.ECarXCarProfileManager;
import ecarx.car.hardware.vehicle.ECarXCarProfiletransferManager;
import ecarx.car.hardware.vehicle.ECarXCarSetManager;
import ecarx.car.hardware.vehicle.PATypes;

/**
 * Opt-in fallback which conservatively reapplies only ProfileTransfer method 01.
 *
 * <p>The primary solution remains the upstream per-element/mask path. This service is OFF by
 * default, never writes masks, never calls the generic save signal 29892, and never pulses the
 * undocumented ProfileTransfer reboot command. All writes are serialized and rate limited.</p>
 */
public final class HudModeFallbackService extends Service
        implements ECarXCarProxy.ECarXCarProxyMethod {
    private static final String TAG = "HudModeFallback";
    private static final String CHANNEL_ID = "hud_mode_fallback";
    private static final int NOTIFICATION_ID = 1701;
    private static final String ACTION_START =
            "dezz.status.hudlab.action.START_MODE_FALLBACK";
    private static final String ACTION_STOP =
            "dezz.status.hudlab.action.STOP_MODE_FALLBACK";
    private static final String EXTRA_REASON = "reason";
    private static final long CONNECT_SETTLE_MS = 1_500L;
    private static final long EVENT_DEBOUNCE_MS = 500L;
    private static final long PROFILE_SETTLE_MS = 900L;
    private static final long SPEED_SETTLE_MS = 600L;
    private static final long VERIFY_DELAY_MS = 600L;
    private static final long OWN_ECHO_MS = 1_000L;
    private static final long WATCHDOG_MS = 60_000L;
    private static final long ADAS_TRIGGER_COOLDOWN_MS = 15_000L;
    private static final long[] RETRY_MS = {1_000L, 3_000L, 10_000L};

    private HandlerThread thread;
    private Handler worker;
    private ECarXCarProxy proxy;
    private ECarXCar root;
    private CarSignalManager signals;
    private ECarXCarProfileManager profileManager;
    private ECarXCarProfiletransferManager profileTransfer;
    private boolean transferCallbackRegistered;
    private boolean profileCallbackRegistered;
    private boolean signalCallbackRegistered;
    private boolean connected;
    private boolean destroyed;
    private long ensureScheduledAt;
    private String pendingReason = "";
    private boolean pendingForce;
    private long lastWriteElapsed;
    private long lastAdasTriggerElapsed;
    private int retryIndex;

    private final HudModeEnforcementPolicy policy = new HudModeEnforcementPolicy();
    private final SparseIntArray lastSignalValues = new SparseIntArray();
    private final CarPAEventCallback paCallback = new CarPAEventCallback() {
        @Override
        public void onPA_HudDispModSetgReq(PATypes.PA_HudDispModSetgReq value) {
            post(() -> onModeFeedback(value));
        }

        @Override
        public void onPA_PSET_ActiveProfile(PATypes.PA_PSET_ActiveProfile value) {
            post(() -> {
                int profile = value == null ? Integer.MIN_VALUE : value.getData();
                if (recordChanged(ECarXCarProfileManager.ManagerId_papsetactiveprofile,
                        profile)) {
                    scheduleEnsure("active-profile=" + profile, true, PROFILE_SETTLE_MS);
                }
            });
        }
    };
    private final CarSignalManager.CarSignalEventCallback signalCallback =
            new CarSignalManager.CarSignalEventCallback() {
                @Override
                @SuppressWarnings("rawtypes")
                public void onChangeEvent(ECarXCarPropertyValue value) {
                    post(() -> onSignal(value));
                }

                @Override
                public void onErrorEvent(int propertyId, int areaId) {
                    post(() -> record("Signal callback error id="
                            + propertyId + ", area=" + areaId));
                }
            };
    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            if (destroyed) return;
            HudModeFallbackStore.Config config = HudModeFallbackStore.read(
                    HudModeFallbackService.this);
            if (!config.enabled) {
                stopImmediately("watchdog увидел OFF");
                return;
            }
            scheduleEnsure("watchdog", false, 0L);
            worker.postDelayed(this, WATCHDOG_MS);
        }
    };
    private final Runnable scheduledEnsure = () -> {
        ensureScheduledAt = 0L;
        String reason = pendingReason;
        boolean force = pendingForce;
        pendingReason = "";
        pendingForce = false;
        ensure(reason, force);
    };
    private final Runnable reconnect = () -> {
        if (destroyed || connected || !HudModeFallbackStore.read(this).enabled) return;
        cleanupProxy();
        startProxy();
    };

    static void start(Context context, String reason) {
        Intent intent = new Intent(context, HudModeFallbackService.class)
                .setAction(ACTION_START)
                .putExtra(EXTRA_REASON, reason == null ? "manual" : reason);
        startCompatible(context, intent);
    }

    static void enable(Context context, int target, int rollback) {
        HudModeFallbackStore.enable(context, target, rollback);
        start(context, "manual-enable");
    }

    static void disable(Context context) {
        // Persist OFF before dispatching the stop intent. Queued event handlers re-read this bit.
        HudModeFallbackStore.disable(context);
        Intent intent = new Intent(context, HudModeFallbackService.class)
                .setAction(ACTION_STOP);
        startCompatible(context, intent);
    }

    private static void startCompatible(Context context, Intent intent) {
        Context app = context.getApplicationContext();
        Context target = app == null ? context : app;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            target.startForegroundService(intent);
        } else {
            target.startService(intent);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        startForeground(NOTIFICATION_ID, notification("Резервный режим запускается…"));
        thread = new HandlerThread("hud-mode-fallback");
        thread.start();
        worker = new Handler(thread.getLooper());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        String reason = intent == null ? "sticky-restart"
                : intent.getStringExtra(EXTRA_REASON);
        post(() -> {
            if (ACTION_STOP.equals(action)) {
                stopImmediately("получена команда OFF");
                return;
            }
            HudModeFallbackStore.Config config = HudModeFallbackStore.read(this);
            if (!config.enabled || !config.isValid()) {
                if (config.enabled) HudModeFallbackStore.disable(this);
                stopImmediately("резервный режим OFF или конфигурация невалидна");
                return;
            }
            updateNotification("ВКЛ · " + HudModeFallbackStore.modeLabel(config.target));
            startProxy();
            worker.removeCallbacks(watchdog);
            worker.postDelayed(watchdog, WATCHDOG_MS);
            scheduleEnsure(reason == null ? "service-start" : reason,
                    true, CONNECT_SETTLE_MS);
        });
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        Handler currentWorker = worker;
        HandlerThread currentThread = thread;
        if (currentWorker != null) {
            currentWorker.removeCallbacksAndMessages(null);
            currentWorker.post(() -> {
                cleanupProxy();
                if (currentThread != null) currentThread.quitSafely();
            });
        } else if (currentThread != null) {
            currentThread.quitSafely();
        }
        super.onDestroy();
    }

    @Override
    public void onECarXCarServiceConnected(ECarXCar connectedRoot,
                                            CarSignalManager connectedSignals) {
        post(() -> acceptConnection(connectedRoot, connectedSignals));
    }

    @Override
    public void onECarXCarServiceDeath() {
        post(() -> {
            unregisterCallbacks();
            connected = false;
            root = null;
            signals = null;
            profileManager = null;
            profileTransfer = null;
            policy.resetSpeedLatch();
            lastSignalValues.clear();
            record("ecarxcar_service отключён; ожидается переподключение");
            worker.removeCallbacks(reconnect);
            worker.postDelayed(reconnect, 5_000L);
        });
    }

    private void startProxy() {
        if (destroyed || proxy != null || !HudModeFallbackStore.read(this).enabled) return;
        try {
            record("Подключение резервного режима к ecarxcar_service…");
            proxy = new ECarXCarProxy(getApplicationContext(), this);
            proxy.initECarXCar();
        } catch (Throwable failure) {
            record("Ошибка ECARX proxy: " + shortFailure(failure));
            cleanupProxy();
            worker.postDelayed(this::startProxy, 5_000L);
        }
    }

    private void acceptConnection(ECarXCar connectedRoot,
                                  CarSignalManager connectedSignals) {
        if (destroyed || connectedRoot == null || connectedSignals == null
                || !HudModeFallbackStore.read(this).enabled) {
            return;
        }
        unregisterCallbacks();
        try {
            Object paService = connectedRoot.getCarManager(ECarXCar.PA_SERVICE);
            if (!(paService instanceof ECarXCarSetManager)) {
                throw new IllegalStateException("PA_SERVICE != ECarXCarSetManager");
            }
            ECarXCarSetManager setManager = (ECarXCarSetManager) paService;
            root = connectedRoot;
            signals = connectedSignals;
            profileManager = setManager.getECarXCarProfileManager();
            profileTransfer = setManager.getECarXCarProfiletransferManager();
            if (profileManager == null || profileTransfer == null) {
                throw new IllegalStateException("Profile/ProfileTransfer manager=null");
            }
            registerCallbacks();
            connected = true;
            retryIndex = 0;
            policy.resetSpeedLatch();
            lastSignalValues.clear();
            worker.removeCallbacks(reconnect);
            record("ECARX готов; резервный режим слушает HUD/ADAS/profile");
            scheduleEnsure("ecarx-connected", true, CONNECT_SETTLE_MS);
        } catch (Throwable failure) {
            connected = false;
            unregisterCallbacks();
            root = null;
            signals = null;
            profileManager = null;
            profileTransfer = null;
            record("Ошибка инициализации ECARX: " + shortFailure(failure));
            worker.postDelayed(reconnect, 5_000L);
        }
    }

    private void registerCallbacks() throws Exception {
        SignalFilter transfer = new SignalFilter();
        transfer.add(ECarXCarProfiletransferManager.ManagerId_pahuddispmodsetgreq);
        profileTransfer.registerCallback(paCallback, transfer);
        transferCallbackRegistered = true;

        SignalFilter profile = new SignalFilter();
        profile.add(ECarXCarProfileManager.ManagerId_papsetactiveprofile);
        profileManager.registerCallback(paCallback, profile);
        profileCallbackRegistered = true;

        SignalFilter vehicle = new SignalFilter();
        vehicle.add(CarSignalManager.SignalId_ProfPenSts1);
        vehicle.add(CarSignalManager.SignalId_VehSpdLgtA);
        vehicle.add(CarSignalManager.SignalId_HudActvSts);
        vehicle.add(CarSignalManager.SignalId_HudSts);
        vehicle.add(CarSignalManager.SignalId_AsyLaneKeepAidSts);
        vehicle.add(CarSignalManager.SignalId_AsyALgtStsAsyALgtSts);
        vehicle.add(CarSignalManager.SignalId_AsyObjType);
        signals.registerCallback(signalCallback, vehicle);
        signalCallbackRegistered = true;
    }

    private void unregisterCallbacks() {
        if (transferCallbackRegistered) {
            transferCallbackRegistered = false;
            try {
                if (profileTransfer != null) profileTransfer.unregisterCallback(paCallback);
            } catch (Throwable failure) {
                Log.w(TAG, "transfer callback cleanup", failure);
            }
        }
        if (profileCallbackRegistered) {
            profileCallbackRegistered = false;
            try {
                if (profileManager != null) profileManager.unregisterCallback(paCallback);
            } catch (Throwable failure) {
                Log.w(TAG, "profile callback cleanup", failure);
            }
        }
        if (signalCallbackRegistered) {
            signalCallbackRegistered = false;
            try {
                if (signals != null) signals.unregisterCallback(signalCallback);
            } catch (Throwable failure) {
                Log.w(TAG, "signal callback cleanup", failure);
            }
        }
    }

    private void onModeFeedback(PATypes.PA_HudDispModSetgReq feedback) {
        HudModeFallbackStore.Config config = HudModeFallbackStore.read(this);
        if (!config.enabled) return;
        int actual = feedback == null ? HudModeFallbackStore.NO_MODE : feedback.getData();
        long sinceWrite = SystemClock.elapsedRealtime() - lastWriteElapsed;
        if (sinceWrite >= 0L && sinceWrite < OWN_ECHO_MS) return;
        if (actual == -1) {
            // This exact firmware commonly exposes PA33937 data=-1 with invalid availability.
            return;
        }
        if (actual != config.target) {
            scheduleEnsure("PA33937 drift " + actual + "→" + config.target,
                    true, EVENT_DEBOUNCE_MS);
        }
    }

    private void onSignal(ECarXCarPropertyValue<?> property) {
        if (property == null || !HudModeFallbackStore.read(this).enabled) return;
        Integer value = asInt(property.getValue());
        if (value == null) return;
        int id = property.getPropertyId();
        if (id == CarSignalManager.SignalId_VehSpdLgtA) {
            if (policy.onRawSpeed(value)) {
                scheduleEnsure(String.format(Locale.ROOT,
                                "speed %.1f km/h",
                                value * HudModeEnforcementPolicy.SPEED_SCALE_KMH),
                        true, SPEED_SETTLE_MS);
            }
            return;
        }
        boolean changed = recordChanged(id, value);
        if (!changed) return;
        if (id == CarSignalManager.SignalId_ProfPenSts1) {
            scheduleEnsure("profile/PEN signal30451=" + value,
                    true, PROFILE_SETTLE_MS);
        } else if (id == CarSignalManager.SignalId_HudActvSts
                || id == CarSignalManager.SignalId_HudSts) {
            if (value != 0) {
                scheduleEnsure("HUD state id=" + id + ", value=" + value,
                        true, EVENT_DEBOUNCE_MS);
            }
        } else if (id == CarSignalManager.SignalId_AsyLaneKeepAidSts
                || id == CarSignalManager.SignalId_AsyALgtStsAsyALgtSts
                || id == CarSignalManager.SignalId_AsyObjType) {
            long now = SystemClock.elapsedRealtime();
            if (value != 0 && now - lastAdasTriggerElapsed >= ADAS_TRIGGER_COOLDOWN_MS) {
                lastAdasTriggerElapsed = now;
                scheduleEnsure("ADAS transition id=" + id + ", value=" + value,
                        true, PROFILE_SETTLE_MS);
            }
        }
    }

    private boolean recordChanged(int id, int value) {
        int index = lastSignalValues.indexOfKey(id);
        if (index < 0) {
            lastSignalValues.put(id, value);
            return false;
        }
        int prior = lastSignalValues.valueAt(index);
        lastSignalValues.setValueAt(index, value);
        return prior != value;
    }

    private void scheduleEnsure(String reason, boolean force, long delayMs) {
        if (destroyed || worker == null || !HudModeFallbackStore.read(this).enabled) return;
        long targetAt = SystemClock.elapsedRealtime() + Math.max(0L, delayMs);
        pendingReason = pendingReason.isEmpty() ? reason : pendingReason + "+" + reason;
        pendingForce |= force;
        if (ensureScheduledAt != 0L && ensureScheduledAt <= targetAt) return;
        worker.removeCallbacks(scheduledEnsure);
        ensureScheduledAt = targetAt;
        worker.postDelayed(scheduledEnsure,
                Math.max(0L, targetAt - SystemClock.elapsedRealtime()));
    }

    private void ensure(String reason, boolean force) {
        HudModeFallbackStore.Config config = HudModeFallbackStore.read(this);
        if (!config.enabled) {
            stopImmediately("ensure увидел OFF");
            return;
        }
        if (!config.isValid()) {
            HudModeFallbackStore.disable(this);
            stopImmediately("невалидная конфигурация; запись заблокирована");
            return;
        }
        if (!connected || profileTransfer == null) {
            startProxy();
            record("Триггер " + reason + ": ECARX ещё не готов");
            return;
        }

        if (!force) {
            int actual = readFeedback();
            if (actual == config.target) {
                record("Watchdog: PA33937 подтверждает target=" + config.target);
                return;
            }
            if (actual == HudModeFallbackStore.NO_MODE || actual == -1) {
                record("Watchdog: PA33937 недоступен; слепая SDK-запись пропущена");
                return;
            }
        }

        long now = SystemClock.elapsedRealtime();
        long guardDelay = policy.delayBeforeWrite(now);
        if (guardDelay > 0L) {
            record("Защита от частых записей: отложено на " + guardDelay + " мс");
            scheduleEnsure(reason + "/rate-limit", true, guardDelay);
            return;
        }

        // A second read closes the common OFF race between event scheduling and the binder write.
        config = HudModeFallbackStore.read(this);
        if (!config.enabled) {
            stopImmediately("OFF перед записью");
            return;
        }
        int target = config.target;
        policy.recordWriteAttempt(now);
        lastWriteElapsed = now;
        try {
            ApiResult result = writeTarget(target);
            if (result != ApiResult.SUCCEED) {
                throw new IllegalStateException("CB33278 вернул " + result);
            }
            policy.recordWriteSuccess();
            retryIndex = 0;
            String status = "CB33278=" + target + " SUCCEED · "
                    + policy.writesInCurrentWindow(now) + "/"
                    + HudModeEnforcementPolicy.MAX_WRITES_PER_WINDOW + " за минуту";
            HudModeFallbackStore.recordWrite(this, reason, status);
            updateNotification("ВКЛ · " + status);
            worker.postDelayed(() -> verify(target), VERIFY_DELAY_MS);
        } catch (Throwable failure) {
            policy.recordWriteFailure(now);
            String status = "Ошибка CB33278=" + target + ": " + shortFailure(failure);
            HudModeFallbackStore.recordWrite(this, reason, status);
            updateNotification(status);
            long retry = RETRY_MS[Math.min(retryIndex, RETRY_MS.length - 1)];
            retryIndex = Math.min(retryIndex + 1, RETRY_MS.length);
            scheduleEnsure(reason + "/retry" + retryIndex, true, retry);
        }
    }

    private void verify(int expected) {
        if (!HudModeFallbackStore.read(this).enabled || !connected) return;
        int actual = readFeedback();
        if (actual == expected) {
            record("PA33937 подтвердил target=" + expected);
        } else if (actual != HudModeFallbackStore.NO_MODE && actual != -1) {
            scheduleEnsure("verify " + actual + "→" + expected,
                    true, EVENT_DEBOUNCE_MS);
        }
    }

    private int readFeedback() {
        try {
            PATypes.PA_HudDispModSetgReq value =
                    profileTransfer.getPA_HudDispModSetgReq();
            return value == null ? HudModeFallbackStore.NO_MODE : value.getData();
        } catch (Throwable ignored) {
            return HudModeFallbackStore.NO_MODE;
        }
    }

    private ApiResult writeTarget(int target) throws Exception {
        return profileTransfer.CB_HudDispModSetgReq(
                HudProfileTransferMode.requireSdkMode(target));
    }

    private void stopImmediately(String reason) {
        if (worker != null) worker.removeCallbacksAndMessages(null);
        pendingReason = "";
        pendingForce = false;
        ensureScheduledAt = 0L;
        record("Резервный режим остановлен: " + reason);
        cleanupProxy();
        stopSelf();
    }

    private void cleanupProxy() {
        unregisterCallbacks();
        connected = false;
        root = null;
        signals = null;
        profileManager = null;
        profileTransfer = null;
        ECarXCarProxy current = proxy;
        proxy = null;
        if (current != null) {
            try {
                current.stopReconnection();
                current.cleanup();
            } catch (Throwable failure) {
                Log.w(TAG, "proxy cleanup", failure);
            }
        }
    }

    private void post(Runnable runnable) {
        Handler target = worker;
        if (target != null && !destroyed) target.post(runnable);
    }

    private void record(String status) {
        HudModeFallbackStore.recordStatus(this, status);
        Log.i(TAG, status);
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "HUD fallback", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Резервный автоповтор подтверждённого режима HUD");
        manager.createNotificationChannel(channel);
    }

    private Notification notification(String text) {
        Intent open = new Intent(this, HudLabActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent content = PendingIntent.getActivity(this, 1701, open, flags);
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder
                .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
                .setContentTitle("HUD Lab · резервный автоповтор")
                .setContentText(text)
                .setContentIntent(content)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager manager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) manager.notify(NOTIFICATION_ID, notification(text));
    }

    private static Integer asInt(Object value) {
        return value instanceof Number ? ((Number) value).intValue() : null;
    }

    private static String shortFailure(Throwable failure) {
        String message = failure.getMessage();
        return failure.getClass().getSimpleName()
                + (message == null || message.isEmpty() ? "" : ": " + message);
    }
}
