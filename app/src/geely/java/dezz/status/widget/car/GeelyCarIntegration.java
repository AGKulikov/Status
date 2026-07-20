/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package dezz.status.widget.car;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.ecarx.xui.adaptapi.FunctionStatus;
import com.ecarx.xui.adaptapi.car.Car;
import com.ecarx.xui.adaptapi.car.ICar;
import com.ecarx.xui.adaptapi.car.base.ICarInfo;
import com.ecarx.xui.adaptapi.car.sensor.ISensor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import dezz.status.widget.BrickType;

/**
 * eCarX AdaptAPI backend for car-specific bricks: cabin ("indoor") and ambient ("outdoor")
 * temperature sensors.
 * <p>
 * All AdaptAPI calls are wrapped in {@code catch (Throwable)} — on vehicles without the eCarX
 * platform service the SDK can fail anywhere from class initialization to binder calls, and a
 * missing sensor must degrade to "brick not supported", never to a crash.
 * <p>
 * Boot race: the AdaptAPI proxy connects to the {@code ecarxcar_service} binder asynchronously.
 * Until it does, {@code isSensorSupported} returns {@link FunctionStatus#error} — indistinguishable
 * up front from a genuinely unsupported vehicle. Two mechanisms bridge that window:
 * <ul>
 *   <li>{@link #subscribe} registers listeners unconditionally — the SDK queues them locally and
 *       wires them up when the service connects, so no data is lost;</li>
 *   <li>a fast startup poll followed by a low-frequency recovery probe re-checks sensor support
 *       and fires the availability-changed callback when the answer flips, letting the widget
 *       re-evaluate brick visibility (see {@link #setAvailabilityChangedListener}).</li>
 * </ul>
 */
final class GeelyCarIntegration implements CarIntegration {

    private static final String TAG = "GeelyCarIntegration";

    /**
     * Sanity bounds for cabin/ambient readings — values outside are momentary CAN glitches or
     * sensor error sentinels, not real temperatures. Upper bound accommodates a sun-baked cabin.
     */
    private static final float MIN_PLAUSIBLE_TEMPERATURE_C = -40f;
    private static final float MAX_PLAUSIBLE_TEMPERATURE_C = 85f;

    /** Fast boot probes followed by a low-frequency, unbounded service-recovery probe. */
    private static final long AVAILABILITY_FAST_POLL_INTERVAL_MS = 2_000L;
    private static final int AVAILABILITY_FAST_POLL_ATTEMPTS = 30;   // first 60s
    private static final long AVAILABILITY_SLOW_POLL_INTERVAL_MS = 30_000L;

    private final Context appContext;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Map<BrickType, Subscription> subscriptions = new EnumMap<>(BrickType.class);
    /** Main-thread desired brick subscriptions, including registrations waiting for Binder. */
    private final Set<BrickType> requestedBrickTypes = new HashSet<>();
    private final Object telemetryLock = new Object();
    private final Map<TelemetryListener, TelemetrySubscription> telemetrySubscriptions =
            new IdentityHashMap<>();
    /** Serialises the thirteen vendor registrations and initial Binder reads off the UI thread. */
    private final ExecutorService telemetryWorker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "ecarx-telemetry");
        thread.setDaemon(true);
        return thread;
    });

    @Nullable
    private volatile ISensor sensors;
    @Nullable
    private volatile ICar carApi;

    @Nullable
    private Runnable availabilityChangedListener;
    private int availabilityPollAttempts = 0;
    private boolean availabilityPollScheduled = false;
    private final Runnable availabilityPollTask = this::runAvailabilityPoll;
    /** Main-thread-only exact probes which caused the current boot-recovery cycle. */
    private final Set<Integer> recoverySensorTypes = new HashSet<>();
    private boolean recoveryFuelCapacity = false;

    /** Pairs the vendor listener with a cancellation flag so queued main-thread deliveries of an
     *  already-unsubscribed listener can be dropped instead of overwriting the placeholder. */
    private static final class Subscription {
        final ISensor registrationSource;
        final ISensor.ISensorListener sensorListener;
        final AtomicBoolean cancelled;

        Subscription(ISensor registrationSource, ISensor.ISensorListener sensorListener,
                     AtomicBoolean cancelled) {
            this.registrationSource = registrationSource;
            this.sensorListener = sensorListener;
            this.cancelled = cancelled;
        }
    }

    /** A vendor listener must always be removed from the exact manager that registered it. */
    private static final class VendorRegistration {
        final ISensor source;
        final ISensor.ISensorListener listener;

        VendorRegistration(ISensor source, ISensor.ISensorListener listener) {
            this.source = source;
            this.listener = listener;
        }
    }

    private static final class TelemetrySignal {
        final String id;
        final String label;
        final int sensorType;
        final String unitNote;
        final String telemetryUnit;
        final boolean boundedTemperature;

        TelemetrySignal(String id, String label, int sensorType, String unitNote,
                        boolean boundedTemperature) {
            this.id = "ISensor." + id;
            this.label = label;
            this.sensorType = sensorType;
            this.unitNote = unitNote;
            this.boundedTemperature = boundedTemperature;
            // Streaming consumers need a compact machine-facing unit. Detailed uncertainty and
            // provenance remain in the diagnostics-only unitNote above.
            this.telemetryUnit = boundedTemperature ? "°C" : "raw";
        }
    }

    private static final TelemetrySignal[] TELEMETRY_SIGNALS = {
            new TelemetrySignal("fuel_level", "Остаток топлива — raw",
                    ISensor.SENSOR_TYPE_FUEL_LEVEL,
                    "Единица AdaptAPI не указана: это ещё не подтверждённые литры", false),
            new TelemetrySignal("range_fuel", "Запас хода на топливе — raw",
                    ISensor.SENSOR_TYPE_ENDURANCE_MILEAGE_FUEL,
                    "Единица SDK, вероятно расстояние", false),
            new TelemetrySignal("range_total", "Общий запас хода — raw",
                    ISensor.SENSOR_TYPE_ENDURANCE_MILEAGE,
                    "Единица SDK, вероятно расстояние", false),
            new TelemetrySignal("odometer", "Одометр — raw", ISensor.SENSOR_TYPE_ODOMETER,
                    "Единица SDK не нормализуется", false),
            new TelemetrySignal("speed", "Скорость — raw", ISensor.SENSOR_TYPE_CAR_SPEED,
                    "Единица SDK не нормализуется", false),
            new TelemetrySignal("rpm", "Обороты двигателя — raw", ISensor.SENSOR_TYPE_RPM,
                    "raw", false),
            new TelemetrySignal("coolant_temp", "Температура ОЖ — raw",
                    ISensor.SENSOR_TYPE_ENGINE_COOLANT_TEMPERATURE, "raw", false),
            new TelemetrySignal("coolant_level", "Уровень ОЖ — raw",
                    ISensor.SENSOR_TYPE_ENGINE_COOLANT_LEVEL, "raw", false),
            new TelemetrySignal("engine_oil_level", "Уровень масла — raw",
                    ISensor.SENSOR_TYPE_ENGINE_OIL_LEVEL, "raw", false),
            new TelemetrySignal("indoor_temp", "Температура салона",
                    ISensor.SENSOR_TYPE_TEMPERATURE_INDOOR,
                    "°C (источник штатного кирпичика)", true),
            new TelemetrySignal("ambient_temp", "Наружная температура",
                    ISensor.SENSOR_TYPE_TEMPERATURE_AMBIENT,
                    "°C (источник штатного кирпичика)", true),
            new TelemetrySignal("vehicle_weight", "Масса автомобиля — raw",
                    ISensor.SENSOR_TYPE_VEHICLE_WEIGHT, "raw", false),
            new TelemetrySignal("ev_battery_level", "Заряд тяговой батареи — raw",
                    ISensor.SENSOR_TYPE_EV_BATTERY_LEVEL, "raw", false)
    };

    private static final String FUEL_CAPACITY_ID = "ICarInfo.fuel_capacity";
    private static final String FUEL_CAPACITY_LABEL = "Объём бака — raw";
    private static final String FUEL_CAPACITY_UNIT_NOTE =
            "Единица AdaptAPI не указана; нужна сверка на автомобиле";
    private static final String FUEL_CAPACITY_TELEMETRY_UNIT = "raw";

    private static final class TelemetrySubscription {
        final TelemetryListener listener;
        final Set<String> metricIds;
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        /** Accessed only by telemetryWorker. */
        final List<VendorRegistration> vendorListeners = new ArrayList<>();

        TelemetrySubscription(TelemetryListener listener, Set<String> metricIds) {
            this.listener = listener;
            this.metricIds = metricIds;
        }
    }

    GeelyCarIntegration(@NonNull Context appContext) {
        this.appContext = appContext;
    }

    private static int sensorTypeFor(@NonNull BrickType type) {
        switch (type) {
            case INDOOR_TEMP:
                return ISensor.SENSOR_TYPE_TEMPERATURE_INDOOR;
            case OUTDOOR_TEMP:
                return ISensor.SENSOR_TYPE_TEMPERATURE_AMBIENT;
            default:
                return 0;
        }
    }

    private static boolean isPlausibleTemperature(float celsius) {
        // Float.MIN_VALUE is the SDK's "no data yet" sentinel (seen from getSensorLatestValue
        // before the platform service connects); it is numerically ~1.4e-45 and would otherwise
        // pass the range check and render as "0°".
        return isValidTelemetryValue(celsius, true);
    }

    /** Package-visible for pure validation tests; no Android/vendor service is touched here. */
    static boolean isValidTelemetryValue(float value, boolean boundedTemperature) {
        if (!Float.isFinite(value) || isObviousFloatSentinel(value)) return false;
        return !boundedTemperature || (value >= MIN_PLAUSIBLE_TEMPERATURE_C
                && value <= MAX_PLAUSIBLE_TEMPERATURE_C);
    }

    /** Integer-valued event channels use both extrema as their documented/observed no-data form. */
    static boolean isValidTelemetryEventValue(int value, boolean boundedTemperature) {
        return value != Integer.MIN_VALUE && value != Integer.MAX_VALUE
                && isValidTelemetryValue(value, boundedTemperature);
    }

    private static boolean isObviousFloatSentinel(float value) {
        float magnitude = Math.abs(value);
        return magnitude == Float.MIN_VALUE || magnitude == Float.MAX_VALUE;
    }

    /**
     * Keep only metric IDs this flavor actually knows. Besides being defensive at the public API
     * boundary, this makes it impossible for a stale mapping to cause an unrelated vendor read.
     */
    static Set<String> selectKnownTelemetryMetricIds(@NonNull Set<String> requested) {
        LinkedHashSet<String> selected = new LinkedHashSet<>();
        for (TelemetrySignal signal : TELEMETRY_SIGNALS) {
            if (requested.contains(signal.id)) selected.add(signal.id);
        }
        if (requested.contains(FUEL_CAPACITY_ID)) selected.add(FUEL_CAPACITY_ID);
        return Collections.unmodifiableSet(selected);
    }

    /** Resolve the AdaptAPI root service; an early null/exception is deliberately retryable. */
    @Nullable
    private synchronized ICar ensureCarApi() {
        if (carApi != null) return carApi;
        try {
            carApi = Car.create(appContext);
            if (carApi == null) Log.w(TAG, "eCarX Car.create returned null; will retry");
        } catch (Throwable t) {
            carApi = null;
            Log.w(TAG, "eCarX car service unavailable; will retry", t);
        }
        return carApi;
    }

    /** Resolve the AdaptAPI sensor service; an early null/exception is deliberately retryable. */
    @Nullable
    private synchronized ISensor ensureSensors() {
        if (sensors != null) return sensors;
        try {
            ICar car = ensureCarApi();
            if (car == null) return null;
            sensors = car.getSensorManager();
            if (sensors == null) Log.w(TAG, "eCarX sensor manager is not ready; will retry");
        } catch (Throwable t) {
            // Do not poison the process for the lifetime of the widget: on this platform the
            // service proxy can throw while its Binder is still coming up after ignition/boot.
            sensors = null;
            carApi = null;
            Log.w(TAG, "eCarX sensor manager unavailable; will retry", t);
        }
        return sensors;
    }

    /** Do not cache managers returned as null during the boot window. */
    @Nullable
    private ICarInfo ensureCarInfo() {
        try {
            ICar car = ensureCarApi();
            if (car == null) return null;
            ICarInfo info = car.getCarInfoManager();
            if (info == null) Log.w(TAG, "eCarX car info manager is not ready; will retry");
            return info;
        } catch (Throwable t) {
            invalidateCarServices();
            Log.w(TAG, "eCarX car info manager unavailable; will retry", t);
            return null;
        }
    }

    /**
     * Drop cached AdaptAPI proxies after a Binder failure. Car.create/getSensorManager are allowed
     * to return new wrappers on the next probe; retaining a dead non-null proxy would otherwise
     * make every future recovery attempt call the same disconnected Binder forever.
     */
    private synchronized void invalidateCarServices() {
        sensors = null;
        carApi = null;
    }

    /** Avoid clearing a newer proxy when an asynchronous call on an older one fails later. */
    private synchronized void invalidateSensorProxy(@Nullable ISensor failed) {
        if (failed == null || sensors == failed) {
            sensors = null;
            carApi = null;
        }
    }

    @Override
    public void requestDiagnostics(@NonNull DiagnosticsListener listener) {
        // Binder reads may block while the vehicle service wakes, so never run them on the UI.
        new Thread(() -> {
            List<CarDiagnosticValue> values = new ArrayList<>();
            ISensor s = ensureSensors();
            if (s == null) addUnavailableSensor(values, TELEMETRY_SIGNALS[0]);
            else addSensor(values, s, TELEMETRY_SIGNALS[0]);
            // ICarInfo is independent from ISensor on some firmware. Always probe it even when
            // the sensor manager is still unavailable, so fuel capacity can still be mapped.
            addCarInfo(values, "fuel_capacity", FUEL_CAPACITY_LABEL,
                    ICarInfo.FLT_INFO_FUEL_CAPACITY, FUEL_CAPACITY_UNIT_NOTE);
            for (int index = 1; index < TELEMETRY_SIGNALS.length; index++) {
                if (s == null) addUnavailableSensor(values, TELEMETRY_SIGNALS[index]);
                else addSensor(values, s, TELEMETRY_SIGNALS[index]);
            }
            mainHandler.post(() -> listener.onDiagnostics(values));
        }, "ecarx-diagnostics").start();
    }

    private static void addUnavailableSensor(List<CarDiagnosticValue> out,
                                             TelemetrySignal signal) {
        out.add(new CarDiagnosticValue(signal.id, signal.label, "unavailable", "—",
                signal.unitNote + "; signal=" + signal.sensorType
                        + "; sensor manager did not connect"));
    }

    private void addSensor(List<CarDiagnosticValue> out, ISensor s, TelemetrySignal signal) {
        FunctionStatus status;
        try {
            status = s.isSensorSupported(signal.sensorType);
        } catch (Throwable t) {
            invalidateSensorProxy(s);
            out.add(new CarDiagnosticValue(signal.id, signal.label, "error",
                    t.getClass().getSimpleName(), signal.unitNote));
            return;
        }
        String raw = "—";
        Float numeric = null;
        if (status == FunctionStatus.active || status == FunctionStatus.notactive) {
            try {
                float latest = s.getSensorLatestValue(signal.sensorType);
                raw = Float.toString(latest);
                if (isValidTelemetryValue(latest, signal.boundedTemperature)) numeric = latest;
            } catch (Throwable t) {
                invalidateSensorProxy(s);
                raw = "error: " + t.getClass().getSimpleName();
            }
        }
        out.add(new CarDiagnosticValue(signal.id, signal.label, String.valueOf(status), raw,
                signal.unitNote + "; signal=" + signal.sensorType, numeric));
    }

    private void addCarInfo(List<CarDiagnosticValue> out, String id, String label,
                            int infoType, String unitNote) {
        try {
            ICarInfo info = ensureCarInfo();
            if (info == null) {
                out.add(new CarDiagnosticValue("ICarInfo." + id, label, "error",
                        "manager unavailable", unitNote));
                return;
            }
            FunctionStatus status = info.isCarInfoSupported(infoType);
            String raw = "—";
            Float numeric = null;
            if (status == FunctionStatus.active || status == FunctionStatus.notactive) {
                float latest = info.getCarInfoFloat(infoType);
                raw = Float.toString(latest);
                if (isValidTelemetryValue(latest, false)) numeric = latest;
            }
            out.add(new CarDiagnosticValue("ICarInfo." + id, label, String.valueOf(status), raw,
                    unitNote + "; info=" + infoType, numeric));
        } catch (Throwable t) {
            invalidateCarServices();
            out.add(new CarDiagnosticValue("ICarInfo." + id, label, "error",
                    t.getClass().getSimpleName(), unitNote));
        }
    }

    @Nullable
    private FunctionStatus sensorSupportStatus(int sensorType) {
        ISensor s = ensureSensors();
        if (s == null) return null;
        return sensorSupportStatus(s, sensorType);
    }

    @Nullable
    private FunctionStatus sensorSupportStatus(ISensor source, int sensorType) {
        try {
            return source.isSensorSupported(sensorType);
        } catch (Throwable t) {
            invalidateSensorProxy(source);
            Log.w(TAG, "isSensorSupported failed for sensor " + sensorType, t);
            return null;
        }
    }

    @Nullable
    private FunctionStatus fuelCapacitySupportStatus() {
        try {
            ICarInfo info = ensureCarInfo();
            return info == null ? null
                    : info.isCarInfoSupported(ICarInfo.FLT_INFO_FUEL_CAPACITY);
        } catch (Throwable t) {
            invalidateCarServices();
            Log.w(TAG, "fuel capacity support query failed", t);
            return null;
        }
    }

    private static boolean isSupported(@Nullable FunctionStatus status) {
        return status == FunctionStatus.active || status == FunctionStatus.notactive;
    }

    private static boolean isDefinitive(@Nullable FunctionStatus status) {
        return status != null && status != FunctionStatus.error;
    }

    @Override
    public boolean isBrickSupported(@NonNull BrickType type) {
        int sensorType = sensorTypeFor(type);
        if (sensorType == 0) return false;
        FunctionStatus status = sensorSupportStatus(sensorType);
        // "notactive" still counts as supported: the sensor exists but is momentarily idle
        // (e.g. ignition state) — the brick should be offered and will update when it wakes.
        // "error" is what the SDK reports before its platform service has connected; the
        // availability poll below re-checks and notifies once the true answer is known.
        boolean supported = isSupported(status);
        if (!supported && status == FunctionStatus.error) {
            requestSensorRecovery(sensorType);
        } else if (status == null) {
            requestSensorRecovery(sensorType);
        }
        return supported;
    }

    @Override
    public void setAvailabilityChangedListener(@Nullable Runnable listener) {
        availabilityChangedListener = listener;
        if (listener == null) mainHandler.post(this::pruneRecoveryRequests);
    }

    private void requestSensorRecovery(int sensorType) {
        mainHandler.post(() -> {
            if (!isSensorRecoveryDemanded(sensorType)) return;
            recoverySensorTypes.add(sensorType);
            startAvailabilityRecoveryCycle();
        });
    }

    private void requestFuelCapacityRecovery() {
        mainHandler.post(() -> {
            if (!isMetricRecoveryDemanded(FUEL_CAPACITY_ID)) return;
            recoveryFuelCapacity = true;
            startAvailabilityRecoveryCycle();
        });
    }

    private void startAvailabilityRecoveryCycle() {
        scheduleAvailabilityPoll();
    }

    /** Called on main after a real value proves that this vendor path is healthy again. */
    private void markTelemetryRecovered(String metricId) {
        if (FUEL_CAPACITY_ID.equals(metricId)) {
            recoveryFuelCapacity = false;
        } else {
            for (TelemetrySignal signal : TELEMETRY_SIGNALS) {
                if (signal.id.equals(metricId)) {
                    markSensorRecovered(signal.sensorType);
                    break;
                }
            }
        }
        if (recoverySensorTypes.isEmpty() && !recoveryFuelCapacity
                && !availabilityPollScheduled) {
            availabilityPollAttempts = 0;
        }
    }

    private void markSensorRecovered(int sensorType) {
        recoverySensorTypes.remove(sensorType);
        if (recoverySensorTypes.isEmpty() && !recoveryFuelCapacity
                && !availabilityPollScheduled) {
            availabilityPollAttempts = 0;
        }
    }

    /**
     * Exact-probe loop for paths which actually failed. Boot gets quick probes for one minute;
     * unresolved paths then continue at a low frequency for the lifetime of the request. This is
     * deliberate: an ignition/Binder service can appear much later than Android startup.
     */
    private void scheduleAvailabilityPoll() {
        if (availabilityPollScheduled
                || (recoverySensorTypes.isEmpty() && !recoveryFuelCapacity)) {
            return;
        }
        availabilityPollScheduled = true;
        long delay = availabilityPollDelayMillis(availabilityPollAttempts);
        mainHandler.postDelayed(availabilityPollTask, delay);
    }

    private void runAvailabilityPoll() {
        availabilityPollScheduled = false;
        pruneRecoveryRequests();
        if (recoverySensorTypes.isEmpty() && !recoveryFuelCapacity) return;
        boolean slowProbe = availabilityPollAttempts >= AVAILABILITY_FAST_POLL_ATTEMPTS;
        if (slowProbe) {
            // A non-null proxy may belong to a service process which died. Force the slow
            // health probe through newly resolved managers instead of trusting that proxy.
            invalidateCarServices();
        }
        if (availabilityPollAttempts < AVAILABILITY_FAST_POLL_ATTEMPTS) {
            availabilityPollAttempts++;
        }
        boolean recovered = false;
        for (Integer sensorType : new ArrayList<>(recoverySensorTypes)) {
            FunctionStatus status = sensorSupportStatus(sensorType);
            if (isDefinitive(status)) {
                recoverySensorTypes.remove(sensorType);
                recovered = true;
            }
        }
        if (recoveryFuelCapacity && isDefinitive(fuelCapacitySupportStatus())) {
            recoveryFuelCapacity = false;
            recovered = true;
        }
        if (recovered && availabilityChangedListener != null) {
            availabilityChangedListener.run();
        }
        if (!recoverySensorTypes.isEmpty() || recoveryFuelCapacity) {
            scheduleAvailabilityPoll();
        } else {
            availabilityPollAttempts = 0;
        }
    }

    /** Remove failed paths after their last brick/export subscription disappears. */
    private void pruneRecoveryRequests() {
        for (Integer sensorType : new ArrayList<>(recoverySensorTypes)) {
            if (!isSensorRecoveryDemanded(sensorType)) recoverySensorTypes.remove(sensorType);
        }
        if (recoveryFuelCapacity && !isMetricRecoveryDemanded(FUEL_CAPACITY_ID)) {
            recoveryFuelCapacity = false;
        }
        if (recoverySensorTypes.isEmpty() && !recoveryFuelCapacity) {
            mainHandler.removeCallbacks(availabilityPollTask);
            availabilityPollScheduled = false;
            availabilityPollAttempts = 0;
        }
    }

    private boolean isSensorRecoveryDemanded(int sensorType) {
        for (BrickType type : requestedBrickTypes) {
            if (sensorTypeFor(type) == sensorType) return true;
        }
        for (TelemetrySignal signal : TELEMETRY_SIGNALS) {
            if (signal.sensorType == sensorType) return isMetricRecoveryDemanded(signal.id);
        }
        return false;
    }

    private boolean isMetricRecoveryDemanded(String metricId) {
        synchronized (telemetryLock) {
            for (TelemetrySubscription subscription : telemetrySubscriptions.values()) {
                if (!subscription.cancelled.get() && subscription.metricIds.contains(metricId)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Package-visible so the long-term recovery policy is covered without Android/Binder mocks. */
    static long availabilityPollDelayMillis(int completedFastAttempts) {
        return completedFastAttempts < AVAILABILITY_FAST_POLL_ATTEMPTS
                ? AVAILABILITY_FAST_POLL_INTERVAL_MS : AVAILABILITY_SLOW_POLL_INTERVAL_MS;
    }

    @Override
    public void subscribe(@NonNull BrickType type, @NonNull ValueListener listener) {
        int sensorType = sensorTypeFor(type);
        if (sensorType == 0) return;
        requestedBrickTypes.add(type);
        ISensor s = ensureSensors();
        if (s == null) {
            requestSensorRecovery(sensorType);
            return;
        }

        Subscription previous = subscriptions.get(type);

        // The listener closes over its own cancellation flag (not a map lookup — the vendor
        // callback arrives on a binder thread and the map is main-thread-only). The gate drops
        // deliveries already queued to the main handler when unsubscribe() wins the race —
        // otherwise a stale reading would overwrite the placeholder reset and resurface when
        // the brick is re-added later.
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        Subscription subscription = new Subscription(s, new ISensor.ISensorListener() {
            @Override
            public void onSensorEventChanged(int changedType, int value) {
            }

            @Override
            public void onSensorSupportChanged(int changedType, FunctionStatus status) {
                if (changedType != sensorType || cancelled.get()) return;
                if (isSupported(status)) {
                    mainHandler.post(() -> emitInitialBrickValue(
                            s, sensorType, type, listener, cancelled));
                } else if (status == null || status == FunctionStatus.error) {
                    requestSensorRecovery(sensorType);
                }
            }

            @Override
            public void onSensorValueChanged(int changedType, float value) {
                if (cancelled.get() || changedType != sensorType
                        || !isPlausibleTemperature(value)
                        || !isSensorCurrentlySupported(s, sensorType)) return;
                // AdaptAPI delivers on a binder thread; the contract is main-thread delivery.
                mainHandler.post(() -> {
                    if (cancelled.get()) return;
                    markSensorRecovered(sensorType);
                    listener.onValue(type, value);
                });
            }
        }, cancelled);

        // Register the replacement BEFORE dropping the old listener: if the vendor side
        // transiently rejects the registration we keep the previous, still-working
        // subscription instead of silently freezing the brick.
        try {
            if (!s.registerListener(subscription.sensorListener, sensorType)) {
                Log.w(TAG, "registerListener rejected for " + type
                        + (previous != null ? " — keeping previous subscription" : ""));
                requestSensorRecovery(sensorType);
                return;
            }
        } catch (Throwable t) {
            invalidateSensorProxy(s);
            Log.w(TAG, "registerListener failed for " + type
                    + (previous != null ? " — keeping previous subscription" : ""), t);
            requestSensorRecovery(sensorType);
            return;
        }
        if (previous != null) {
            previous.cancelled.set(true);
            try {
                previous.registrationSource.unregisterListener(previous.sensorListener);
            } catch (Throwable t) {
                Log.w(TAG, "unregisterListener (replace) failed for " + type, t);
            }
        }
        subscriptions.put(type, subscription);

        // Seed with the latest cached value so the brick shows a temperature immediately
        // instead of a placeholder until the sensor's next change event (which for slow-moving
        // ambient temperature can be minutes away).
        emitInitialBrickValue(s, sensorType, type, listener, cancelled);
    }

    private void emitInitialBrickValue(ISensor source, int sensorType, BrickType type,
                                       ValueListener listener, AtomicBoolean cancelled) {
        if (cancelled.get()) return;
        FunctionStatus status = sensorSupportStatus(source, sensorType);
        if (!isSupported(status)) {
            if (status == null || status == FunctionStatus.error) {
                requestSensorRecovery(sensorType);
            }
            return;
        }
        try {
            float latest = source.getSensorLatestValue(sensorType);
            if (isPlausibleTemperature(latest)) {
                mainHandler.post(() -> {
                    if (!cancelled.get()) {
                        markSensorRecovered(sensorType);
                        listener.onValue(type, latest);
                    }
                });
            } else if (isObviousFloatSentinel(latest)) {
                requestSensorRecovery(sensorType);
            }
        } catch (Throwable t) {
            invalidateSensorProxy(source);
            Log.w(TAG, "getSensorLatestValue failed for " + type, t);
            requestSensorRecovery(sensorType);
        }
    }

    /** Temperature brick events are infrequent; re-check support before exposing a value. */
    private boolean isSensorCurrentlySupported(ISensor source, int sensorType) {
        FunctionStatus status = sensorSupportStatus(source, sensorType);
        if (status == null || status == FunctionStatus.error) {
            requestSensorRecovery(sensorType);
        }
        return isSupported(status);
    }

    @Override
    public void unsubscribe(@NonNull BrickType type) {
        requestedBrickTypes.remove(type);
        Subscription subscription = subscriptions.remove(type);
        if (subscription != null) {
            subscription.cancelled.set(true);
            try {
                subscription.registrationSource.unregisterListener(subscription.sensorListener);
            } catch (Throwable t) {
                Log.w(TAG, "unregisterListener failed for " + type, t);
            }
        }
        mainHandler.post(this::pruneRecoveryRequests);
    }

    @Override
    public void subscribeTelemetry(@NonNull Set<String> metricIds,
                                   @NonNull TelemetryListener listener) {
        TelemetrySubscription next = new TelemetrySubscription(listener,
                selectKnownTelemetryMetricIds(metricIds));
        TelemetrySubscription previous;
        synchronized (telemetryLock) {
            previous = telemetrySubscriptions.put(listener, next);
        }
        if (previous != null) {
            previous.cancelled.set(true);
            mainHandler.post(this::pruneRecoveryRequests);
        }
        executeTelemetryTask(() -> {
            if (previous != null) unregisterTelemetryVendorListeners(previous);
            activateTelemetrySubscription(next);
        });
    }

    @Override
    public void unsubscribeTelemetry(@NonNull TelemetryListener listener) {
        TelemetrySubscription removed;
        synchronized (telemetryLock) {
            removed = telemetrySubscriptions.remove(listener);
        }
        if (removed == null) return;
        removed.cancelled.set(true);
        executeTelemetryTask(() -> unregisterTelemetryVendorListeners(removed));
        mainHandler.post(this::pruneRecoveryRequests);
    }

    private void activateTelemetrySubscription(TelemetrySubscription subscription) {
        if (subscription.cancelled.get()) return;
        boolean needsSensors = false;
        for (TelemetrySignal signal : TELEMETRY_SIGNALS) {
            if (subscription.metricIds.contains(signal.id)) {
                needsSensors = true;
                break;
            }
        }
        ISensor source = needsSensors ? ensureSensors() : null;
        if (source != null) {
            for (TelemetrySignal signal : TELEMETRY_SIGNALS) {
                if (subscription.cancelled.get()) break;
                if (subscription.metricIds.contains(signal.id)) {
                    registerTelemetrySignal(source, signal, subscription);
                }
            }
        } else if (needsSensors) {
            for (TelemetrySignal signal : TELEMETRY_SIGNALS) {
                if (subscription.metricIds.contains(signal.id)) {
                    requestSensorRecovery(signal.sensorType);
                }
            }
        }
        if (subscription.metricIds.contains(FUEL_CAPACITY_ID)) {
            emitInitialFuelCapacity(subscription);
        }
    }

    private void registerTelemetrySignal(ISensor source, TelemetrySignal signal,
                                         TelemetrySubscription subscription) {
        FunctionStatus initialStatus = sensorSupportStatus(source, signal.sensorType);
        AtomicBoolean supported = new AtomicBoolean(isSupported(initialStatus));
        if (!supported.get() && (initialStatus == null || initialStatus == FunctionStatus.error)) {
            requestSensorRecovery(signal.sensorType);
        }
        ISensor.ISensorListener vendorListener = new ISensor.ISensorListener() {
            @Override public void onSensorEventChanged(int changedType, int value) {
                if (subscription.cancelled.get() || changedType != signal.sensorType
                        || !isValidTelemetryEventValue(value, signal.boundedTemperature)
                        || !supported.get()) return;
                // Several nominally numeric AdaptAPI signals (notably fluid/oil levels) are
                // exposed through the integer event callback on some firmware revisions.
                deliverTelemetry(subscription, signal.id, signal.label,
                        signal.telemetryUnit, value);
            }

            @Override public void onSensorSupportChanged(int changedType, FunctionStatus status) {
                if (changedType != signal.sensorType || subscription.cancelled.get()) return;
                supported.set(isSupported(status));
                if (!isSupported(status)) {
                    if (status == null || status == FunctionStatus.error) {
                        requestSensorRecovery(signal.sensorType);
                    }
                    return;
                }
                // A listener can be registered while ecarxcar_service is still starting. Seed it
                // again when support becomes definitive so the initial snapshot is not lost just
                // because the first getSensorLatestValue returned the boot sentinel.
                executeTelemetryTask(() -> emitInitialSensorValue(
                        source, signal, subscription, supported));
            }

            @Override public void onSensorValueChanged(int changedType, float value) {
                if (subscription.cancelled.get() || changedType != signal.sensorType
                        || !isValidTelemetryValue(value, signal.boundedTemperature)
                        || !supported.get()) return;
                deliverTelemetry(subscription, signal.id, signal.label,
                        signal.telemetryUnit, value);
            }
        };
        try {
            if (!source.registerListener(vendorListener, signal.sensorType)) {
                Log.w(TAG, "telemetry registerListener rejected for " + signal.id);
                requestSensorRecovery(signal.sensorType);
                return;
            }
            subscription.vendorListeners.add(new VendorRegistration(source, vendorListener));
            if (subscription.cancelled.get()) return;
            emitInitialSensorValue(source, signal, subscription, supported);
        } catch (Throwable t) {
            invalidateSensorProxy(source);
            Log.w(TAG, "telemetry subscription failed for " + signal.id, t);
            requestSensorRecovery(signal.sensorType);
        }
    }

    private void emitInitialSensorValue(ISensor source, TelemetrySignal signal,
                                        TelemetrySubscription subscription,
                                        AtomicBoolean supported) {
        if (subscription.cancelled.get()) return;
        FunctionStatus status = sensorSupportStatus(source, signal.sensorType);
        supported.set(isSupported(status));
        if (!isSupported(status)) {
            if (status == null || status == FunctionStatus.error) {
                requestSensorRecovery(signal.sensorType);
            }
            return;
        }
        try {
            float latest = source.getSensorLatestValue(signal.sensorType);
            if (isValidTelemetryValue(latest, signal.boundedTemperature)) {
                deliverTelemetry(subscription, signal.id, signal.label,
                        signal.telemetryUnit, latest);
            } else if (isObviousFloatSentinel(latest)) {
                requestSensorRecovery(signal.sensorType);
            }
        } catch (Throwable t) {
            invalidateSensorProxy(source);
            Log.w(TAG, "telemetry initial read failed for " + signal.id, t);
            requestSensorRecovery(signal.sensorType);
        }
    }

    /** Fuel capacity is static car information, so it contributes an initial snapshot only. */
    private void emitInitialFuelCapacity(TelemetrySubscription subscription) {
        if (subscription.cancelled.get()) return;
        try {
            ICarInfo info = ensureCarInfo();
            if (info == null) {
                requestFuelCapacityRecovery();
                return;
            }
            FunctionStatus status = info.isCarInfoSupported(ICarInfo.FLT_INFO_FUEL_CAPACITY);
            if (!isSupported(status)) {
                if (status == null || status == FunctionStatus.error) {
                    requestFuelCapacityRecovery();
                }
                return;
            }
            float value = info.getCarInfoFloat(ICarInfo.FLT_INFO_FUEL_CAPACITY);
            if (isValidTelemetryValue(value, false)) {
                deliverTelemetry(subscription, FUEL_CAPACITY_ID, FUEL_CAPACITY_LABEL,
                        FUEL_CAPACITY_TELEMETRY_UNIT, value);
            } else {
                requestFuelCapacityRecovery();
            }
        } catch (Throwable t) {
            invalidateCarServices();
            Log.w(TAG, "fuel capacity telemetry unavailable", t);
            requestFuelCapacityRecovery();
        }
    }

    private void deliverTelemetry(TelemetrySubscription subscription, String id, String label,
                                  String unit, float value) {
        TelemetryValue sample = new TelemetryValue(id, label, value, unit,
                System.currentTimeMillis());
        deliverTelemetry(subscription, sample);
    }

    /** Integer vendor events must not pass through float's 24-bit mantissa. */
    private void deliverTelemetry(TelemetrySubscription subscription, String id, String label,
                                  String unit, int value) {
        TelemetryValue sample = new TelemetryValue(id, label, (double) value, unit,
                System.currentTimeMillis());
        deliverTelemetry(subscription, sample);
    }

    private void deliverTelemetry(TelemetrySubscription subscription, TelemetryValue sample) {
        mainHandler.post(() -> {
            if (!subscription.cancelled.get()) {
                markTelemetryRecovered(sample.id);
                subscription.listener.onTelemetry(sample);
            }
        });
    }

    private void unregisterTelemetryVendorListeners(TelemetrySubscription subscription) {
        for (VendorRegistration registration : subscription.vendorListeners) {
            try {
                registration.source.unregisterListener(registration.listener);
            } catch (Throwable t) {
                Log.w(TAG, "telemetry unregisterListener failed", t);
            }
        }
        subscription.vendorListeners.clear();
    }

    private void executeTelemetryTask(Runnable task) {
        try {
            telemetryWorker.execute(task);
        } catch (RejectedExecutionException ignored) {
            // shutdown() makes the process-wide integration intentionally non-reusable.
        }
    }

    @Override
    public void shutdown() {
        for (BrickType type : BrickType.values()) {
            unsubscribe(type);
        }
        List<TelemetrySubscription> telemetry;
        synchronized (telemetryLock) {
            telemetry = new ArrayList<>(telemetrySubscriptions.values());
            telemetrySubscriptions.clear();
        }
        for (TelemetrySubscription subscription : telemetry) subscription.cancelled.set(true);
        executeTelemetryTask(() -> {
            for (TelemetrySubscription subscription : telemetry) {
                unregisterTelemetryVendorListeners(subscription);
            }
        });
        telemetryWorker.shutdown();
        availabilityChangedListener = null;
    }
}
