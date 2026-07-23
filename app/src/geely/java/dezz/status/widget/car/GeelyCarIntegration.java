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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.ecarx.xui.adaptapi.FunctionStatus;
import com.ecarx.xui.adaptapi.car.Car;
import com.ecarx.xui.adaptapi.car.ICar;
import com.ecarx.xui.adaptapi.car.base.ICarFunction;
import com.ecarx.xui.adaptapi.car.base.ICarInfo;
import com.ecarx.xui.adaptapi.car.hvac.IHvac;
import com.ecarx.xui.adaptapi.car.sensor.ISensor;
import com.ecarx.xui.adaptapi.car.vehicle.IBcm;
import com.ecarx.xui.adaptapi.car.vehicle.IDriveMode;
import com.ecarx.xui.adaptapi.car.vehicle.IVehicle;
import com.ecarx.xui.adaptapi.tpms.ITireState;
import com.ecarx.xui.adaptapi.tpms.TPMS;
import com.ecarx.xui.adaptapi.vehicle.VehicleSeat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
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
import dezz.status.widget.launcher.vehicle.VehicleDerivedMetrics;

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
    private static final long CONTROL_RETRY_MS = 2_000L;
    private static final long CONTROL_HEALTH_POLL_MS = 30_000L;
    /**
     * ECARX applies HVAC writes asynchronously. Poll often enough for a responsive panel, but do
     * not resend on every poll: flooding the slow Android 9 Binder is one of the reasons a write
     * can be acknowledged without ever reaching the vehicle ECU.
     */
    /** Limit active Binder writes; passive read-back polling continues to the full deadline. */
    private static final int CONTROL_WRITE_WINDOW_POLLS = 8;
    private static final int CONTROL_RESEND_EVERY_POLLS = 3;
    private static final long CONTROL_CONFIRM_POLL_MS = 140L;
    /** Hard upper bound for a command state-machine, excluding a Binder call already in flight. */
    private static final long CONTROL_COMMAND_TIMEOUT_MS = 5_000L;
    private static final int CONTROL_PULSE_ATTEMPTS = 2;
    private static final long CONTROL_PULSE_RETRY_MS = 200L;
    private static final int NO_ZONE = Integer.MIN_VALUE;
    private static final String FAN_CONTROL_ID = "climate.fan";
    /** ECARX front-row aggregate zone used by both manual and AUTO fan functions. */
    private static final int FRONT_FAN_ZONE = 8;
    /**
     * A short Binder failure may hide the AUTO bit while the ECU is still in AUTO. Reuse only a
     * recent confirmed mode; after this window an unresolved mode is UNKNOWN and no fan write is
     * routed at all.
     */
    private static final long FAN_MODE_CACHE_MAX_AGE_MS = 75_000L;
    private static final int AUTO_FAN_FAMILY_UNKNOWN = 0;
    private static final int AUTO_FAN_FAMILY_THREE_PROFILE = 3;
    private static final int AUTO_FAN_FAMILY_TWO_PROFILE = 2;

    /** Geely extension signals used by the instrument cluster but absent from this SDK's stubs. */
    private static final int SENSOR_TYPE_AVERAGE_CONSUMPTION = 4_194_560;
    private static final int SENSOR_TYPE_INSTANT_CONSUMPTION = 4_194_816;
    private static final int SENSOR_TYPE_AVERAGE_CONSUMPTION_ONE_IGNITION = 4_195_072;
    private static final long BCM_STATE_POLL_INTERVAL_MS = 300L;
    /** A dead/silent reflective callback must never suppress the supported AdaptAPI path forever. */
    private static final long LOW_LEVEL_PRIORITY_TTL_MS = 15_000L;
    /** Longer than a normal indicator's dark half-cycle, but still quick when it is cancelled. */
    private static final long TURN_SIGNAL_OFF_HOLD_MS = 1_000L;
    private static final String GEAR_ID = "ISensor.gear";
    private static final String LOW_LEVEL_GEAR_ACTUAL_ID = "ECarx.gear_actual";
    private static final String LOW_LEVEL_GEAR_MANUAL_ID = "ECarx.gear_manual_mode";
    private static final String HIGH_BEAM_ID = "IBcm.high_beam";

    private final Context appContext;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Map<BrickType, Subscription> subscriptions = new EnumMap<>(BrickType.class);
    /** Main-thread desired brick subscriptions, including registrations waiting for Binder. */
    private final Set<BrickType> requestedBrickTypes = new HashSet<>();
    private final Object telemetryLock = new Object();
    private final Map<TelemetryListener, TelemetrySubscription> telemetrySubscriptions =
            new IdentityHashMap<>();
    /** Worker-thread-only debounce state shared by all subscribers of the same physical lamp. */
    private final Map<String, Long> bcmLastOnMillis = new HashMap<>();
    private final Object controlsLock = new Object();
    private final Map<ControlStateListener, ControlSubscription> controlSubscriptions =
            new IdentityHashMap<>();
    /** Main-thread cache used by HOME tiles and by toggle/cycle command calculation. */
    private final Map<String, CarControlState> controlStateCache = new HashMap<>();
    /** Serialises the thirteen vendor registrations and initial Binder reads off the UI thread. */
    private final ExecutorService telemetryWorker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "ecarx-telemetry");
        thread.setDaemon(true);
        return thread;
    });
    /**
     * Climate reads/writes have their own serial lane. Sharing the telemetry worker made a tap
     * wait behind TPMS/BCM/navigation reads and, on weak head units, delayed it for seconds.
     */
    private final ExecutorService controlWorker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "ecarx-controls");
        thread.setDaemon(true);
        return thread;
    });
    /**
     * Worker-owned command state. Every vendor call still runs on {@link #controlWorker}, while
     * confirmation delays live on the main handler so one slow ECU transition cannot park that
     * serial lane or starve watcher refreshes and commands for other functions.
     */
    private final Map<String, ActiveControlCommand> activeControlCommands = new HashMap<>();
    private final Object controlCommandSubmissionLock = new Object();
    /** Latest caller intent is visible before its worker task reaches the serial queue. */
    private final Map<String, ControlCommandSubmission> latestControlCommandSubmissions =
            new HashMap<>();
    private long nextControlCommandGeneration;
    private volatile boolean controlsShuttingDown;
    /** Confirmed routing facts are published across the catalog and control workers. */
    @Nullable private volatile ConfirmedFanMode lastConfirmedClimateAutoMode;
    @Nullable private volatile Double lastConfirmedAutoFanProfile;
    @NonNull private volatile List<CarControlDescriptor.Option>
            lastConfirmedAutoFanRuntimeOptions = Collections.emptyList();

    @Nullable
    private volatile ISensor sensors;
    @Nullable
    private volatile ICar carApi;
    @Nullable
    private volatile ICarFunction carFunctions;
    private final EcarxSignalFallback signalFallback;
    /** Prefer the richer callback once it has produced a value; AdaptAPI remains the cold fallback. */
    private volatile boolean lowLevelGearKnown;
    private volatile boolean lowLevelHighBeamKnown;
    private volatile long lowLevelGearObservedMonoMillis;
    private volatile long lowLevelHighBeamObservedMonoMillis;

    /** Accessed only by controlWorker, except vendor callbacks which only enqueue work/delivery. */
    @Nullable private ICarFunction controlWatcherSource;
    @Nullable private ICarFunction.IFunctionValueWatcher controlWatcher;
    private final Set<Integer> watchedControlFunctions = new HashSet<>();
    private boolean controlRefreshScheduled;
    private boolean controlRefreshInFlight;
    private boolean controlRefreshAgain;
    private volatile int controlRetryAttempts;
    private final Runnable controlRefreshTask = () -> {
        controlRefreshScheduled = false;
        if (controlRefreshInFlight) {
            controlRefreshAgain = true;
            return;
        }
        controlRefreshInFlight = true;
        if (!executeControlTask(() -> {
            try {
                refreshControlRegistrationAndStates();
            } finally {
                mainHandler.post(this::finishControlRefresh);
            }
        })) {
            controlRefreshInFlight = false;
        }
    };
    /** Main-thread scheduler; the next tick is queued only after the previous Binder read ends. */
    private boolean bcmPollScheduled;
    private boolean bcmPollInFlight;
    private final Runnable bcmPollTask = () -> {
        bcmPollScheduled = false;
        if (!hasBcmTelemetryDemand()) return;
        bcmPollInFlight = true;
        executeTelemetryTask(() -> {
            try {
                pollBcmTelemetryOnce();
            } finally {
                mainHandler.post(() -> {
                    bcmPollInFlight = false;
                    scheduleNextBcmPoll();
                });
            }
        });
    };
    /** Internal wake-up after the exported manifest receiver persisted a current-boot state. */
    private final BroadcastReceiver autoHoldChangedReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (AutoHoldStateRepository.ACTION_CHANGED.equals(intent.getAction())) {
                deliverCurrentAutoHoldState();
            }
        }
    };
    private boolean autoHoldReceiverRegistered;

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
        final boolean eventOnly;
        final boolean probeWithoutSupport;
        final long staleAfterMillis;

        TelemetrySignal(String id, String label, int sensorType, String unitNote,
                        boolean boundedTemperature) {
            this(id, label, sensorType, unitNote, boundedTemperature ? "°C" : "raw",
                    boundedTemperature, false, false, 120_000L);
        }

        TelemetrySignal(String id, String label, int sensorType, String unitNote,
                        String telemetryUnit, boolean boundedTemperature, boolean eventOnly,
                        boolean probeWithoutSupport, long staleAfterMillis) {
            this.id = "ISensor." + id;
            this.label = label;
            this.sensorType = sensorType;
            this.unitNote = unitNote;
            this.boundedTemperature = boundedTemperature;
            this.telemetryUnit = telemetryUnit;
            this.eventOnly = eventOnly;
            this.probeWithoutSupport = probeWithoutSupport;
            this.staleAfterMillis = staleAfterMillis;
        }

        CarTelemetryDescriptor descriptor() {
            return new CarTelemetryDescriptor(id, label, telemetryUnit, true, staleAfterMillis);
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
                    ISensor.SENSOR_TYPE_EV_BATTERY_LEVEL, "raw", false),
            new TelemetrySignal("avg_fuel_consumption", "Средний расход",
                    SENSOR_TYPE_AVERAGE_CONSUMPTION,
                    "Расширенный сигнал Geely; литры на 100 км", "L/100 km",
                    false, false, true, 120_000L),
            new TelemetrySignal("instant_fuel_consumption", "Мгновенный расход",
                    SENSOR_TYPE_INSTANT_CONSUMPTION,
                    "Расширенный сигнал Geely; литры на 100 км", "L/100 km",
                    false, false, true, 30_000L),
            new TelemetrySignal("avg_fuel_consumption_ignition",
                    "Средний расход за текущую поездку",
                    SENSOR_TYPE_AVERAGE_CONSUMPTION_ONE_IGNITION,
                    "Расширенный сигнал Geely с момента включения зажигания; литры на 100 км",
                    "L/100 km", false, false, true, 120_000L),
            new TelemetrySignal("gear", "Передача", ISensor.SENSOR_TYPE_GEAR,
                    "Код ISensorEvent.GEAR_*", "", false, true, true, 0L),
            new TelemetrySignal("ignition_state", "Состояние зажигания",
                    ISensor.SENSOR_TYPE_IGNITION_STATE,
                    "Код ISensorEvent.IGNITION_STATE_*", "",
                    false, true, false, 0L)
    };

    private static final String FUEL_CAPACITY_ID = "ICarInfo.fuel_capacity";
    private static final String FUEL_CAPACITY_LABEL = "Объём бака — raw";
    private static final String FUEL_CAPACITY_UNIT_NOTE =
            "Единица AdaptAPI не указана; нужна сверка на автомобиле";
    private static final String FUEL_CAPACITY_TELEMETRY_UNIT = "raw";

    private static final class TireMetric {
        final String id;
        final String label;
        final int tireId;
        final boolean pressure;

        TireMetric(String positionId, String positionLabel, int tireId, boolean pressure) {
            this.id = "TPMS." + (pressure ? "pressure." : "temperature.") + positionId;
            this.label = (pressure ? "Давление" : "Температура") + " — " + positionLabel;
            this.tireId = tireId;
            this.pressure = pressure;
        }

        String unit() { return pressure ? "bar" : "°C"; }

        CarTelemetryDescriptor descriptor() {
            return new CarTelemetryDescriptor(id, label, unit(), true, 600_000L);
        }
    }

    private static final TireMetric[] TIRE_METRICS = {
            new TireMetric("front_left", "переднее левое", TPMS.TIRE_ID_LEFT_FRONT, true),
            new TireMetric("front_left", "переднее левое", TPMS.TIRE_ID_LEFT_FRONT, false),
            new TireMetric("front_right", "переднее правое", TPMS.TIRE_ID_RIGHT_FRONT, true),
            new TireMetric("front_right", "переднее правое", TPMS.TIRE_ID_RIGHT_FRONT, false),
            new TireMetric("rear_left", "заднее левое", TPMS.TIRE_ID_LEFT_REAR, true),
            new TireMetric("rear_left", "заднее левое", TPMS.TIRE_ID_LEFT_REAR, false),
            new TireMetric("rear_right", "заднее правое", TPMS.TIRE_ID_RIGHT_REAR, true),
            new TireMetric("rear_right", "заднее правое", TPMS.TIRE_ID_RIGHT_REAR, false)
    };

    private static final class BcmMetric {
        final String id;
        final String label;
        final int functionId;
        final boolean turnSignal;

        BcmMetric(String id, String label, int functionId, boolean turnSignal) {
            this.id = "IBcm." + id;
            this.label = label;
            this.functionId = functionId;
            this.turnSignal = turnSignal;
        }

        CarTelemetryDescriptor descriptor() {
            return new CarTelemetryDescriptor(id, label, "", true,
                    id.equals(HIGH_BEAM_ID) ? 5_000L : 1_500L);
        }
    }

    private static final BcmMetric[] BCM_METRICS = {
            new BcmMetric("high_beam", "Дальний свет",
                    IBcm.BCM_FUNC_LIGHT_MAIN_BEAM, false),
            new BcmMetric("turn_signal_left", "Левый указатель поворота",
                    IBcm.BCM_FUNC_LIGHT_LEFT_TRUN_SIGNAL, true),
            new BcmMetric("turn_signal_right", "Правый указатель поворота",
                    IBcm.BCM_FUNC_LIGHT_RIGHT_TRUN_SIGNAL, true)
    };

    private static final List<CarTelemetryDescriptor> TELEMETRY_CATALOG =
            buildTelemetryCatalog();

    private static final class TelemetrySubscription {
        final TelemetryListener listener;
        final Set<String> metricIds;
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        /** Accessed only by telemetryWorker. */
        final List<VendorRegistration> vendorListeners = new ArrayList<>();
        /** Worker-thread-only per-listener dedupe; new listeners still receive an initial state. */
        final Map<String, Integer> lastBcmValues = new HashMap<>();
        @Nullable TPMS tpmsSource;
        @Nullable TPMS.ITireStateMonitor tireStateMonitor;

        TelemetrySubscription(TelemetryListener listener, Set<String> metricIds) {
            this.listener = listener;
            this.metricIds = metricIds;
        }
    }

    private static final class ControlSubscription {
        final ControlStateListener listener;
        final Set<String> controlIds;
        final AtomicBoolean cancelled = new AtomicBoolean(false);

        ControlSubscription(ControlStateListener listener, Set<String> controlIds) {
            this.listener = listener;
            this.controlIds = controlIds;
        }
    }

    private static final class ControlDefinition {
        final CarControlDescriptor descriptor;
        final int functionId;
        final int zone;
        final boolean customFloat;

        ControlDefinition(String id, String label, String category, String icon,
                          CarControlDescriptor.Kind kind, int functionId, int zone,
                          boolean customFloat, List<CarControlDescriptor.Option> options,
                          double min, double max, double step, String unit, String color) {
            this.descriptor = new CarControlDescriptor(id, label, category, icon, kind,
                    CarControlDescriptor.Availability.UNKNOWN, options,
                    min, max, step, unit, color);
            this.functionId = functionId;
            this.zone = zone;
            this.customFloat = customFloat;
        }

        boolean zoned() { return zone != NO_ZONE; }

        CarControlDescriptor descriptorWithOptions(List<CarControlDescriptor.Option> options) {
            return new CarControlDescriptor(descriptor.id, descriptor.label, descriptor.category,
                    descriptor.iconKey, descriptor.kind, descriptor.availability, options,
                    descriptor.minimum, descriptor.maximum, descriptor.step, descriptor.unit,
                    descriptor.suggestedActiveColor);
        }
    }

    /**
     * One latest-wins command per logical control. Access is confined to {@link #controlWorker}.
     * Exact duplicates share the same result; a different request supersedes this object and all
     * delayed reads become harmless through the identity/generation check.
     */
    private static final class ActiveControlCommand {
        @NonNull final CarControlCommand command;
        @NonNull final String key;
        final long generation;
        final long deadlineElapsedMillis;
        @NonNull final List<ControlCommandListener> listeners = new ArrayList<>();
        @Nullable ControlDefinition definition;
        @Nullable Double lastConfirmed;
        double target = Double.NaN;
        boolean pulse;
        boolean acceptedAtLeastOnce;
        int pulseAttempts;

        ActiveControlCommand(@NonNull CarControlCommand command, @NonNull String key,
                             long generation, long deadlineElapsedMillis,
                             @NonNull ControlCommandListener listener) {
            this.command = command;
            this.key = key;
            this.generation = generation;
            this.deadlineElapsedMillis = deadlineElapsedMillis;
            listeners.add(listener);
        }
    }

    private static final class ControlCommandSubmission {
        @NonNull final String key;
        final long generation;

        ControlCommandSubmission(@NonNull String key, long generation) {
            this.key = key;
            this.generation = generation;
        }
    }

    private static final class ConfirmedFanMode {
        final boolean autoActive;
        final long observedElapsedMillis;

        ConfirmedFanMode(boolean autoActive, long observedElapsedMillis) {
            this.autoActive = autoActive;
            this.observedElapsedMillis = observedElapsedMillis;
        }
    }

    private static CarControlDescriptor.Option option(double value, String label) {
        return new CarControlDescriptor.Option(value, label);
    }

    private static List<CarControlDescriptor.Option> toggleOptions() {
        return Arrays.asList(option(0, "Выкл"), option(1, "Вкл"));
    }

    private static List<CarControlDescriptor.Option> heatOptions() {
        return Arrays.asList(option(IHvac.SEAT_HEATING_OFF, "Выкл"),
                option(IHvac.SEAT_HEATING_LEVEL_1, "1"),
                option(IHvac.SEAT_HEATING_LEVEL_2, "2"),
                option(IHvac.SEAT_HEATING_LEVEL_3, "3"),
                option(IHvac.SEAT_HEATING_LEVEL_AUTO, "Auto"));
    }

    private static List<CarControlDescriptor.Option> ventilationOptions() {
        return Arrays.asList(option(IHvac.SEAT_VENTILATION_OFF, "Выкл"),
                option(IHvac.SEAT_VENTILATION_LEVEL_1, "1"),
                option(IHvac.SEAT_VENTILATION_LEVEL_2, "2"),
                option(IHvac.SEAT_VENTILATION_LEVEL_3, "3"),
                option(IHvac.SEAT_VENTILATION_LEVEL_AUTO, "Auto"));
    }

    private static List<CarControlDescriptor.Option> wheelHeatOptions() {
        return Arrays.asList(option(IHvac.STEERING_WHEEL_HEAT_OFF, "Выкл"),
                option(IHvac.STEERING_WHEEL_HEAT_LOW, "1"),
                option(IHvac.STEERING_WHEEL_HEAT_MID, "2"),
                option(IHvac.STEERING_WHEEL_HEAT_HIGH, "3"),
                option(IHvac.STEERING_WHEEL_HEAT_AUTO, "Auto"));
    }

    private static List<CarControlDescriptor.Option> fanOptions() {
        return Arrays.asList(option(IHvac.FAN_SPEED_OFF, "Выкл"),
                option(IHvac.FAN_SPEED_LEVEL_1, "1"), option(IHvac.FAN_SPEED_LEVEL_2, "2"),
                option(IHvac.FAN_SPEED_LEVEL_3, "3"), option(IHvac.FAN_SPEED_LEVEL_4, "4"),
                option(IHvac.FAN_SPEED_LEVEL_5, "5"), option(IHvac.FAN_SPEED_LEVEL_6, "6"),
                option(IHvac.FAN_SPEED_LEVEL_7, "7"), option(IHvac.FAN_SPEED_LEVEL_8, "8"),
                option(IHvac.FAN_SPEED_LEVEL_9, "9"));
    }

    /**
     * AUTO fan intensity is a separate AdaptAPI function, not FAN_SPEED_LEVEL_AUTO. The ECARX
     * implementation maps these values to raw PA fan levels 10..14 while manual fan speed owns
     * raw 0..9. A vehicle normally exposes either the three named profiles or the two relative
     * profiles; runtime discovery filters this superset.
     */
    private static List<CarControlDescriptor.Option> autoFanOptions() {
        List<CarControlDescriptor.Option> result = new ArrayList<>();
        result.addAll(autoFanThreeProfileOptions());
        result.addAll(autoFanTwoProfileOptions());
        return Collections.unmodifiableList(result);
    }

    private static List<CarControlDescriptor.Option> autoFanThreeProfileOptions() {
        return Arrays.asList(
                option(IHvac.AUTO_FAN_SETTING_SILENT, "AUTO · тихо"),
                option(IHvac.AUTO_FAN_SETTING_NORMAL, "AUTO · обычно"),
                option(IHvac.AUTO_FAN_SETTING_HIGH, "AUTO · интенсивно"));
    }

    private static List<CarControlDescriptor.Option> autoFanTwoProfileOptions() {
        return Arrays.asList(
                option(IHvac.AUTO_FAN_SETTING_QUIETER, "AUTO · тише"),
                option(IHvac.AUTO_FAN_SETTING_HIGHER, "AUTO · выше"));
    }

    private static List<CarControlDescriptor.Option> driveModeOptions() {
        return Arrays.asList(
                option(IDriveMode.DRIVE_MODE_SELECTION_ECO, "Eco"),
                option(IDriveMode.DRIVE_MODE_SELECTION_COMFORT, "Comfort"),
                option(IDriveMode.DRIVE_MODE_SELECTION_DYNAMIC, "Dynamic"),
                option(IDriveMode.DRIVE_MODE_SELECTION_XC, "XC"),
                option(IDriveMode.DRIVE_MODE_SELECTION_HDC, "HDC"),
                option(IDriveMode.DRIVE_MODE_SELECTION_PURE, "Pure"),
                option(IDriveMode.DRIVE_MODE_SELECTION_HYBRID, "Hybrid"),
                option(IDriveMode.DRIVE_MODE_SELECTION_POWER, "Power"),
                option(IDriveMode.DRIVE_MODE_SELECTION_SNOW, "Snow"),
                option(IDriveMode.DRIVE_MODE_SELECTION_MUD, "Mud"),
                option(IDriveMode.DRIVE_MODE_SELECTION_ROCK, "Rock"),
                option(IDriveMode.DRIVE_MODE_SELECTION_PHEV, "PHEV"),
                option(IDriveMode.DRIVE_MODE_SELECTION_SAND, "Sand"),
                option(IDriveMode.DRIVE_MODE_SELECTION_AWD, "AWD"),
                option(IDriveMode.DRIVE_MODE_SELECTION_SAVE, "Save"),
                option(IDriveMode.DRIVE_MODE_SELECTION_ECO_HEV_PHEV, "Eco HEV"),
                option(IDriveMode.DRIVE_MODE_SELECTION_NORMAL, "Normal"),
                option(IDriveMode.DRIVE_MODE_SELECTION_eAWD, "eAWD"),
                option(IDriveMode.DRIVE_MODE_SELECTION_OFFROAD, "Offroad"),
                option(IDriveMode.DRIVE_MODE_SELECTION_ADAPTIVE, "Adaptive"),
                option(IDriveMode.DRIVE_MODE_SELECTION_CUSTOM, "Custom"));
    }

    private static final ControlDefinition FAN_DEFINITION =
            new ControlDefinition(FAN_CONTROL_ID, "Скорость вентилятора", "Климат", "fan",
                    CarControlDescriptor.Kind.LEVELS, IHvac.HVAC_FUNC_FAN_SPEED,
                    FRONT_FAN_ZONE, false, fanOptions(), 0, 9, 1, "", "#FF42A5F5");

    private static final ControlDefinition AUTO_FAN_DEFINITION =
            new ControlDefinition(FAN_CONTROL_ID, "Скорость вентилятора AUTO", "Климат", "fan",
                    CarControlDescriptor.Kind.LEVELS, IHvac.HVAC_FUNC_AUTO_FAN_SETTING,
                    FRONT_FAN_ZONE, false, autoFanOptions(), 0, 0, 0, "", "#FF42A5F5");

    private static final ControlDefinition[] CONTROL_DEFINITIONS = {
            new ControlDefinition("climate.power", "Климат", "Климат", "climate",
                    CarControlDescriptor.Kind.TOGGLE, IHvac.HVAC_FUNC_POWER, NO_ZONE, false,
                    toggleOptions(), 0, 1, 1, "", "#FF4FC3F7"),
            new ControlDefinition("climate.ac", "Кондиционер", "Климат", "climate_ac",
                    CarControlDescriptor.Kind.TOGGLE, IHvac.HVAC_FUNC_AC, NO_ZONE, false,
                    toggleOptions(), 0, 1, 1, "", "#FF4FC3F7"),
            new ControlDefinition("climate.auto", "Климат AUTO", "Климат", "climate_auto",
                    CarControlDescriptor.Kind.TOGGLE, IHvac.HVAC_FUNC_AUTO, NO_ZONE, false,
                    toggleOptions(), 0, 1, 1, "", "#FF66BB6A"),
            new ControlDefinition("climate.defrost_front", "Обогрев лобового", "Климат",
                    "defrost_front", CarControlDescriptor.Kind.TOGGLE,
                    IHvac.HVAC_FUNC_DEFROST_FRONT, NO_ZONE, false, toggleOptions(),
                    0, 1, 1, "", "#FF80DEEA"),
            new ControlDefinition("climate.defrost_rear", "Обогрев заднего стекла", "Климат",
                    "defrost_rear", CarControlDescriptor.Kind.TOGGLE,
                    IHvac.HVAC_FUNC_DEFROST_REAR, NO_ZONE, false, toggleOptions(),
                    0, 1, 1, "", "#FF80DEEA"),
            new ControlDefinition("climate.seat_heat_driver", "Подогрев сиденья водителя",
                    "Сиденья", "seat_heat", CarControlDescriptor.Kind.LEVELS,
                    IHvac.HVAC_FUNC_SEAT_HEATING, VehicleSeat.SEAT_ROW_1_LEFT, false,
                    heatOptions(), 0, 3, 1, "", "#FFFF9800"),
            new ControlDefinition("climate.seat_heat_passenger", "Подогрев сиденья пассажира",
                    "Сиденья", "seat_heat", CarControlDescriptor.Kind.LEVELS,
                    IHvac.HVAC_FUNC_SEAT_HEATING, VehicleSeat.SEAT_ROW_1_RIGHT, false,
                    heatOptions(), 0, 3, 1, "", "#FFFF9800"),
            new ControlDefinition("climate.seat_vent_driver", "Вентиляция сиденья водителя",
                    "Сиденья", "seat_vent", CarControlDescriptor.Kind.LEVELS,
                    IHvac.HVAC_FUNC_SEAT_VENTILATION, VehicleSeat.SEAT_ROW_1_LEFT, false,
                    ventilationOptions(), 0, 3, 1, "", "#FF29B6F6"),
            new ControlDefinition("climate.seat_vent_passenger", "Вентиляция сиденья пассажира",
                    "Сиденья", "seat_vent", CarControlDescriptor.Kind.LEVELS,
                    IHvac.HVAC_FUNC_SEAT_VENTILATION, VehicleSeat.SEAT_ROW_1_RIGHT, false,
                    ventilationOptions(), 0, 3, 1, "", "#FF29B6F6"),
            new ControlDefinition("climate.wheel_heat", "Подогрев руля", "Климат",
                    "wheel_heat", CarControlDescriptor.Kind.LEVELS,
                    IHvac.HVAC_FUNC_STEERING_WHEEL_HEAT, NO_ZONE, false,
                    wheelHeatOptions(), 0, 3, 1, "", "#FFFF9800"),
            FAN_DEFINITION,
            new ControlDefinition("climate.temp_driver", "Температура водителя", "Климат",
                    "temperature", CarControlDescriptor.Kind.RANGE, IHvac.HVAC_FUNC_TEMP,
                    VehicleSeat.SEAT_ROW_1_LEFT, true, Collections.emptyList(),
                    16, 30, .5, "°C", "#FF66BB6A"),
            new ControlDefinition("climate.temp_passenger", "Температура пассажира", "Климат",
                    "temperature", CarControlDescriptor.Kind.RANGE, IHvac.HVAC_FUNC_TEMP,
                    VehicleSeat.SEAT_ROW_1_RIGHT, true, Collections.emptyList(),
                    16, 30, .5, "°C", "#FF66BB6A"),
            new ControlDefinition("vehicle.drive_mode", "Режим движения", "Автомобиль",
                    "drive_mode", CarControlDescriptor.Kind.OPTIONS,
                    IDriveMode.DM_FUNC_DRIVE_MODE_SELECT, NO_ZONE, false,
                    driveModeOptions(), 0, 0, 0, "", "#FFFFC107"),
            new ControlDefinition("vehicle.wiper_service", "Сервисное положение дворников",
                    "Автомобиль", "wiper", CarControlDescriptor.Kind.ACTION,
                    IVehicle.SETTING_FUNC_WINDSCREEN_SERVICE_POSITION, NO_ZONE, false,
                    Collections.emptyList(), 0, 1, 1, "", "#FFFFFFFF"),
            new ControlDefinition("vehicle.auto_hold", "Auto Hold", "Автомобиль", "auto_hold",
                    CarControlDescriptor.Kind.TOGGLE, IVehicle.SETTING_FUNC_AUTO_HOLD,
                    NO_ZONE, false, toggleOptions(), 0, 1, 1, "", "#FF66BB6A"),
            new ControlDefinition("vehicle.start_stop", "Start/Stop", "Автомобиль", "start_stop",
                    CarControlDescriptor.Kind.TOGGLE, IVehicle.SETTING_FUNC_ENGINE_STOP_START,
                    NO_ZONE, false, toggleOptions(), 0, 1, 1, "", "#FF66BB6A"),
            new ControlDefinition("vehicle.fuel_save", "Экономия топлива", "Автомобиль",
                    "fuel_save", CarControlDescriptor.Kind.TOGGLE,
                    IVehicle.SETTING_FUNC_INTELLIGENT_FUEL_SAVE, NO_ZONE, false,
                    toggleOptions(), 0, 1, 1, "", "#FF8BC34A")
    };

    GeelyCarIntegration(@NonNull Context appContext) {
        this.appContext = appContext;
        signalFallback = new EcarxSignalFallback(appContext,
                new EcarxSignalFallback.Listener() {
                    @Override public void onGear(int adaptGear, int actualGear,
                                                 boolean manualMode) {
                        lowLevelGearKnown = true;
                        lowLevelGearObservedMonoMillis = monotonicMillis();
                        deliverLowLevelGear(adaptGear, actualGear, manualMode);
                    }

                    @Override public void onHighBeam(int enabled) {
                        lowLevelHighBeamKnown = true;
                        lowLevelHighBeamObservedMonoMillis = monotonicMillis();
                        deliverLowLevelHighBeam(enabled);
                    }

                    @Override public void onChannelLost() {
                        lowLevelGearKnown = false;
                        lowLevelHighBeamKnown = false;
                        lowLevelGearObservedMonoMillis = 0L;
                        lowLevelHighBeamObservedMonoMillis = 0L;
                    }
                });
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
        return value != -1 && value != Integer.MIN_VALUE && value != Integer.MAX_VALUE
                && isValidTelemetryValue(value, boundedTemperature);
    }

    /** TPMS implementations observed in the field return either bar or hundredths of a bar. */
    static float normalizeTirePressureBar(float rawPressure) {
        if (!Float.isFinite(rawPressure) || rawPressure <= 0f) return Float.NaN;
        float bar = rawPressure >= 40f ? rawPressure / 100f : rawPressure;
        return bar >= 0.1f && bar <= 10f ? bar : Float.NaN;
    }

    static boolean isValidTireTemperature(float celsius) {
        return Float.isFinite(celsius) && !isObviousFloatSentinel(celsius)
                && celsius >= -40f && celsius <= 150f;
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
        for (TireMetric metric : TIRE_METRICS) {
            if (requested.contains(metric.id)) selected.add(metric.id);
        }
        for (BcmMetric metric : BCM_METRICS) {
            if (requested.contains(metric.id)) selected.add(metric.id);
        }
        if (requested.contains(LOW_LEVEL_GEAR_ACTUAL_ID)) {
            selected.add(LOW_LEVEL_GEAR_ACTUAL_ID);
        }
        if (requested.contains(LOW_LEVEL_GEAR_MANUAL_ID)) {
            selected.add(LOW_LEVEL_GEAR_MANUAL_ID);
        }
        if (requested.contains(VehicleDerivedMetrics.AUTO_HOLD_ID)) {
            selected.add(VehicleDerivedMetrics.AUTO_HOLD_ID);
        }
        return Collections.unmodifiableSet(selected);
    }

    private static List<CarTelemetryDescriptor> buildTelemetryCatalog() {
        List<CarTelemetryDescriptor> catalog = new ArrayList<>();
        for (TelemetrySignal signal : TELEMETRY_SIGNALS) catalog.add(signal.descriptor());
        catalog.add(new CarTelemetryDescriptor(FUEL_CAPACITY_ID, FUEL_CAPACITY_LABEL,
                FUEL_CAPACITY_TELEMETRY_UNIT, false, 0L));
        for (TireMetric metric : TIRE_METRICS) catalog.add(metric.descriptor());
        for (BcmMetric metric : BCM_METRICS) catalog.add(metric.descriptor());
        catalog.add(new CarTelemetryDescriptor(LOW_LEVEL_GEAR_ACTUAL_ID,
                "Фактическая передача", "", true, LOW_LEVEL_PRIORITY_TTL_MS));
        catalog.add(new CarTelemetryDescriptor(LOW_LEVEL_GEAR_MANUAL_ID,
                "Ручной режим коробки", "", true, LOW_LEVEL_PRIORITY_TTL_MS));
        catalog.add(new CarTelemetryDescriptor(VehicleDerivedMetrics.AUTO_HOLD_ID,
                "Auto Hold", "", true, 5L * 60L * 1_000L));
        return Collections.unmodifiableList(catalog);
    }

    @Override
    public void requestTelemetryCatalog(@NonNull TelemetryCatalogListener listener) {
        mainHandler.post(() -> listener.onCatalog(TELEMETRY_CATALOG));
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

    /** Resolve the generic read/write function manager used by HVAC and vehicle controls. */
    @Nullable
    private synchronized ICarFunction ensureCarFunctions() {
        if (carFunctions != null) return carFunctions;
        try {
            ICar car = ensureCarApi();
            if (car == null) return null;
            carFunctions = car.getICarFunction();
            if (carFunctions == null) {
                Log.w(TAG, "eCarX function manager is not ready; will retry");
            }
        } catch (Throwable t) {
            carFunctions = null;
            carApi = null;
            Log.w(TAG, "eCarX function manager unavailable; will retry", t);
        }
        return carFunctions;
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
        carFunctions = null;
        carApi = null;
    }

    private synchronized void invalidateFunctionProxy(@Nullable ICarFunction failed) {
        if (failed == null || carFunctions == failed) {
            carFunctions = null;
            carApi = null;
        }
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
            addTireDiagnostics(values);
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
        if (isSupported(status) || signal.probeWithoutSupport) {
            try {
                if (signal.eventOnly) {
                    int latest = s.getSensorEvent(signal.sensorType);
                    raw = Integer.toString(latest);
                    if (isValidTelemetryEventValue(latest, signal.boundedTemperature)) {
                        numeric = (float) latest;
                    }
                } else {
                    float latest = s.getSensorLatestValue(signal.sensorType);
                    raw = Float.toString(latest);
                    if (isValidTelemetryValue(latest, signal.boundedTemperature)) numeric = latest;
                }
            } catch (Throwable t) {
                invalidateSensorProxy(s);
                raw = "error: " + t.getClass().getSimpleName();
            }
        }
        out.add(new CarDiagnosticValue(signal.id, signal.label, String.valueOf(status), raw,
                signal.unitNote + "; signal=" + signal.sensorType, numeric));
    }

    private void addTireDiagnostics(List<CarDiagnosticValue> out) {
        TPMS tpms;
        try {
            tpms = TPMS.create(appContext);
            if (tpms == null) throw new IllegalStateException("TPMS.create returned null");
        } catch (Throwable t) {
            for (TireMetric metric : TIRE_METRICS) {
                out.add(new CarDiagnosticValue(metric.id, metric.label, "error",
                        t.getClass().getSimpleName(), metric.unit()));
            }
            return;
        }
        for (TireMetric metric : TIRE_METRICS) {
            String raw = "—";
            Float numeric = null;
            try {
                ITireState state = tpms.getTireState(metric.tireId);
                if (state != null) {
                    float value = metric.pressure ? state.getPressure() : state.getTemperature();
                    raw = Float.toString(value);
                    if (metric.pressure) {
                        float bar = normalizeTirePressureBar(value);
                        if (Float.isFinite(bar)) numeric = bar;
                    } else if (isValidTireTemperature(value)) {
                        numeric = value;
                    }
                }
            } catch (Throwable t) {
                raw = "error: " + t.getClass().getSimpleName();
            }
            out.add(new CarDiagnosticValue(metric.id, metric.label, "unknown", raw,
                    metric.unit() + "; tire=" + metric.tireId, numeric));
        }
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
        mainHandler.post(this::reconcileBcmPolling);
        mainHandler.post(this::reconcileSignalFallback);
        mainHandler.post(this::reconcileAutoHoldReceiver);
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
        mainHandler.post(this::reconcileBcmPolling);
        mainHandler.post(this::reconcileSignalFallback);
        mainHandler.post(this::reconcileAutoHoldReceiver);
    }

    private void activateTelemetrySubscription(TelemetrySubscription subscription) {
        if (subscription.cancelled.get()) return;
        boolean needsSensors = false;
        boolean needsTires = false;
        for (TelemetrySignal signal : TELEMETRY_SIGNALS) {
            if (subscription.metricIds.contains(signal.id)) {
                needsSensors = true;
                break;
            }
        }
        for (TireMetric metric : TIRE_METRICS) {
            if (subscription.metricIds.contains(metric.id)) {
                needsTires = true;
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
        if (needsTires && !subscription.cancelled.get()) {
            registerTireTelemetry(subscription);
        }
    }

    private boolean hasBcmTelemetryDemand() {
        synchronized (telemetryLock) {
            for (TelemetrySubscription subscription : telemetrySubscriptions.values()) {
                if (subscription.cancelled.get()) continue;
                for (BcmMetric metric : BCM_METRICS) {
                    if (subscription.metricIds.contains(metric.id)) return true;
                }
            }
        }
        return false;
    }

    private boolean hasAutoHoldTelemetryDemand() {
        synchronized (telemetryLock) {
            for (TelemetrySubscription subscription : telemetrySubscriptions.values()) {
                if (!subscription.cancelled.get()
                        && subscription.metricIds.contains(VehicleDerivedMetrics.AUTO_HOLD_ID)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void reconcileSignalFallback() {
        boolean needsGear = false;
        boolean needsHighBeam = false;
        synchronized (telemetryLock) {
            for (TelemetrySubscription subscription : telemetrySubscriptions.values()) {
                if (subscription.cancelled.get()) continue;
                if (subscription.metricIds.contains(GEAR_ID)
                        || subscription.metricIds.contains(LOW_LEVEL_GEAR_ACTUAL_ID)
                        || subscription.metricIds.contains(LOW_LEVEL_GEAR_MANUAL_ID)) {
                    needsGear = true;
                }
                if (subscription.metricIds.contains(HIGH_BEAM_ID)) needsHighBeam = true;
            }
        }
        if (!needsGear) {
            lowLevelGearKnown = false;
            lowLevelGearObservedMonoMillis = 0L;
        }
        if (!needsHighBeam) {
            lowLevelHighBeamKnown = false;
            lowLevelHighBeamObservedMonoMillis = 0L;
        }
        signalFallback.updateDemand(needsGear, needsHighBeam);
    }

    private void deliverLowLevelGear(int adaptGear, int actualGear, boolean manualMode) {
        List<TelemetrySubscription> subscribers = currentTelemetrySubscribers();
        for (TelemetrySubscription subscription : subscribers) {
            if (subscription.metricIds.contains(GEAR_ID)) {
                deliverTelemetry(subscription, GEAR_ID, "Передача", "", adaptGear);
            }
            if (actualGear > 0
                    && subscription.metricIds.contains(LOW_LEVEL_GEAR_ACTUAL_ID)) {
                deliverTelemetry(subscription, LOW_LEVEL_GEAR_ACTUAL_ID,
                        "Фактическая передача", "", actualGear);
            }
            if (subscription.metricIds.contains(LOW_LEVEL_GEAR_MANUAL_ID)) {
                deliverTelemetry(subscription, LOW_LEVEL_GEAR_MANUAL_ID,
                        "Ручной режим коробки", "", manualMode ? 1 : 0);
            }
        }
    }

    private void deliverLowLevelHighBeam(int enabled) {
        for (TelemetrySubscription subscription : currentTelemetrySubscribers()) {
            if (subscription.metricIds.contains(HIGH_BEAM_ID)) {
                deliverTelemetry(subscription, HIGH_BEAM_ID, "Дальний свет", "", enabled);
            }
        }
    }

    private List<TelemetrySubscription> currentTelemetrySubscribers() {
        List<TelemetrySubscription> subscribers = new ArrayList<>();
        synchronized (telemetryLock) {
            for (TelemetrySubscription subscription : telemetrySubscriptions.values()) {
                if (!subscription.cancelled.get()) subscribers.add(subscription);
            }
        }
        return subscribers;
    }

    /** Main-thread demand registration; the external broadcast itself is manifest-received. */
    private void reconcileAutoHoldReceiver() {
        if (!hasAutoHoldTelemetryDemand()) {
            if (autoHoldReceiverRegistered) {
                try { appContext.unregisterReceiver(autoHoldChangedReceiver); }
                catch (IllegalArgumentException ignored) {}
                autoHoldReceiverRegistered = false;
            }
            return;
        }
        if (!autoHoldReceiverRegistered) {
            IntentFilter filter = new IntentFilter(AutoHoldStateRepository.ACTION_CHANGED);
            try {
                if (Build.VERSION.SDK_INT >= 33) {
                    appContext.registerReceiver(autoHoldChangedReceiver, filter,
                            Context.RECEIVER_NOT_EXPORTED);
                } else {
                    appContext.registerReceiver(autoHoldChangedReceiver, filter);
                }
                autoHoldReceiverRegistered = true;
            } catch (RuntimeException error) {
                Log.w(TAG, "Auto Hold state receiver registration failed", error);
            }
        }
        // The manifest receiver may have run while the launcher/process was not visible.
        deliverCurrentAutoHoldState();
    }

    private void deliverCurrentAutoHoldState() {
        AutoHoldStateRepository.Snapshot current = AutoHoldStateRepository.read(appContext);
        if (!current.available) return;
        List<TelemetrySubscription> subscribers = new ArrayList<>();
        synchronized (telemetryLock) {
            for (TelemetrySubscription subscription : telemetrySubscriptions.values()) {
                if (!subscription.cancelled.get()
                        && subscription.metricIds.contains(VehicleDerivedMetrics.AUTO_HOLD_ID)) {
                    subscribers.add(subscription);
                }
            }
        }
        TelemetryValue sample = new TelemetryValue(VehicleDerivedMetrics.AUTO_HOLD_ID,
                "Auto Hold", current.value ? 1d : 0d, "", current.observedAtMillis);
        for (TelemetrySubscription subscription : subscribers) {
            deliverTelemetry(subscription, sample);
        }
    }

    /** Main-thread only: start immediately on first demand and fully stop after the last one. */
    private void reconcileBcmPolling() {
        if (!hasBcmTelemetryDemand()) {
            mainHandler.removeCallbacks(bcmPollTask);
            bcmPollScheduled = false;
            executeTelemetryTask(bcmLastOnMillis::clear);
            return;
        }
        if (!bcmPollScheduled && !bcmPollInFlight) {
            bcmPollScheduled = true;
            mainHandler.post(bcmPollTask);
        }
    }

    private void scheduleNextBcmPoll() {
        if (!hasBcmTelemetryDemand()) {
            reconcileBcmPolling();
            return;
        }
        if (!bcmPollScheduled && !bcmPollInFlight) {
            bcmPollScheduled = true;
            mainHandler.postDelayed(bcmPollTask, BCM_STATE_POLL_INTERVAL_MS);
        }
    }

    private void pollBcmTelemetryOnce() {
        List<TelemetrySubscription> subscribers = new ArrayList<>();
        LinkedHashSet<String> demandedIds = new LinkedHashSet<>();
        synchronized (telemetryLock) {
            for (TelemetrySubscription subscription : telemetrySubscriptions.values()) {
                if (subscription.cancelled.get()) continue;
                boolean demanded = false;
                for (BcmMetric metric : BCM_METRICS) {
                    if (subscription.metricIds.contains(metric.id)) {
                        demandedIds.add(metric.id);
                        demanded = true;
                    }
                }
                if (demanded) subscribers.add(subscription);
            }
        }
        bcmLastOnMillis.keySet().retainAll(demandedIds);
        if (demandedIds.isEmpty()) return;

        ICarFunction source = ensureCarFunctions();
        if (source == null) return;
        long nowMillis = System.nanoTime() / 1_000_000L;
        for (BcmMetric metric : BCM_METRICS) {
            if (!demandedIds.contains(metric.id)) continue;
            // On firmware where IBcm reports a constant/default value, mHUD obtains the real
            // steady high-beam state from CarSignalManager. Once that path is known-good, do not
            // let the lower-fidelity poll overwrite it.
            if (metric.id.equals(HIGH_BEAM_ID) && isLowLevelHighBeamFresh()) continue;
            final int raw;
            try {
                raw = source.getFunctionValue(metric.functionId);
            } catch (UnsupportedOperationException ignored) {
                continue;
            } catch (Throwable t) {
                invalidateFunctionProxy(source);
                Log.w(TAG, "BCM telemetry read failed for " + metric.id, t);
                return;
            }
            int binary = normalizeBcmBinaryValue(raw);
            if (binary < 0) continue;
            int stable = binary;
            if (metric.turnSignal) {
                Long lastOn = bcmLastOnMillis.get(metric.id);
                if (binary == 1) {
                    bcmLastOnMillis.put(metric.id, nowMillis);
                } else {
                    stable = stabilizeTurnSignalValue(binary,
                            lastOn == null ? -1L : lastOn, nowMillis);
                }
            }
            for (TelemetrySubscription subscription : subscribers) {
                if (subscription.cancelled.get()
                        || !subscription.metricIds.contains(metric.id)) continue;
                Integer previous = subscription.lastBcmValues.put(metric.id, stable);
                if (previous != null && previous == stable) continue;
                deliverTelemetry(subscription, metric.id, metric.label, "", stable);
            }
        }
    }

    /** Only real off/on values are exposed; SDK unknown/error/default codes are ignored. */
    static int normalizeBcmBinaryValue(int raw) {
        if (raw == ICarFunction.COMMON_VALUE_OFF) return 0;
        if (raw == ICarFunction.COMMON_VALUE_ON) return 1;
        return -1;
    }

    /** Keep an active indicator lit across the lamp's normal dark half-cycle. */
    static int stabilizeTurnSignalValue(int binary, long lastOnMillis, long nowMillis) {
        if (binary == 1) return 1;
        if (binary != 0) return -1;
        return lastOnMillis >= 0L && nowMillis >= lastOnMillis
                && nowMillis - lastOnMillis <= TURN_SIGNAL_OFF_HOLD_MS ? 1 : 0;
    }

    private void registerTelemetrySignal(ISensor source, TelemetrySignal signal,
                                         TelemetrySubscription subscription) {
        FunctionStatus initialStatus = sensorSupportStatus(source, signal.sensorType);
        AtomicBoolean supported = new AtomicBoolean(isSupported(initialStatus));
        if (!signal.probeWithoutSupport && !supported.get()
                && (initialStatus == null || initialStatus == FunctionStatus.error)) {
            requestSensorRecovery(signal.sensorType);
        }
        ISensor.ISensorListener vendorListener = new ISensor.ISensorListener() {
            @Override public void onSensorEventChanged(int changedType, int value) {
                if (subscription.cancelled.get() || changedType != signal.sensorType
                        || !isValidTelemetryEventValue(value, signal.boundedTemperature)
                        || isLowLevelGearPreferred(signal)) return;
                supported.set(true);
                // Several nominally numeric AdaptAPI signals (notably fluid/oil levels) are
                // exposed through the integer event callback on some firmware revisions.
                deliverTelemetry(subscription, signal.id, signal.label,
                        signal.telemetryUnit, value);
            }

            @Override public void onSensorSupportChanged(int changedType, FunctionStatus status) {
                if (changedType != signal.sensorType || subscription.cancelled.get()) return;
                supported.set(isSupported(status));
                if (!isSupported(status)) {
                    if (!signal.probeWithoutSupport
                            && (status == null || status == FunctionStatus.error)) {
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
                        || signal.eventOnly
                        || !isValidTelemetryValue(value, signal.boundedTemperature)
                        || isLowLevelGearPreferred(signal)) return;
                supported.set(true);
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
        if (subscription.cancelled.get() || isLowLevelGearPreferred(signal)) return;
        FunctionStatus status = sensorSupportStatus(source, signal.sensorType);
        supported.set(isSupported(status));
        if (!isSupported(status) && !signal.probeWithoutSupport) {
            if (status == null || status == FunctionStatus.error) {
                requestSensorRecovery(signal.sensorType);
            }
            return;
        }
        try {
            if (signal.eventOnly) {
                int latest = source.getSensorEvent(signal.sensorType);
                if (isValidTelemetryEventValue(latest, signal.boundedTemperature)) {
                    supported.set(true);
                    deliverTelemetry(subscription, signal.id, signal.label,
                            signal.telemetryUnit, latest);
                } else if (!signal.probeWithoutSupport) {
                    requestSensorRecovery(signal.sensorType);
                }
            } else {
                float latest = source.getSensorLatestValue(signal.sensorType);
                if (isValidTelemetryValue(latest, signal.boundedTemperature)) {
                    supported.set(true);
                    deliverTelemetry(subscription, signal.id, signal.label,
                            signal.telemetryUnit, latest);
                } else if (isObviousFloatSentinel(latest) && !signal.probeWithoutSupport) {
                    requestSensorRecovery(signal.sensorType);
                }
            }
        } catch (Throwable t) {
            invalidateSensorProxy(source);
            Log.w(TAG, "telemetry initial read failed for " + signal.id, t);
            if (!signal.probeWithoutSupport) requestSensorRecovery(signal.sensorType);
        }
    }

    private boolean isLowLevelGearPreferred(TelemetrySignal signal) {
        return signal.id.equals(GEAR_ID) && isLowLevelGearFresh();
    }

    private boolean isLowLevelGearFresh() {
        return lowLevelGearKnown
                && isFreshLowLevelSample(lowLevelGearObservedMonoMillis, monotonicMillis());
    }

    private boolean isLowLevelHighBeamFresh() {
        return lowLevelHighBeamKnown
                && isFreshLowLevelSample(lowLevelHighBeamObservedMonoMillis, monotonicMillis());
    }

    static boolean isFreshLowLevelSample(long observedMonoMillis, long nowMonoMillis) {
        return observedMonoMillis > 0L && nowMonoMillis >= observedMonoMillis
                && nowMonoMillis - observedMonoMillis <= LOW_LEVEL_PRIORITY_TTL_MS;
    }

    private static long monotonicMillis() {
        return SystemClock.elapsedRealtime();
    }

    private void registerTireTelemetry(TelemetrySubscription subscription) {
        TPMS tpms;
        try {
            tpms = TPMS.create(appContext);
            if (tpms == null) throw new IllegalStateException("TPMS.create returned null");
        } catch (Throwable t) {
            Log.w(TAG, "TPMS service unavailable", t);
            return;
        }
        TPMS.ITireStateMonitor monitor = (tireId, state) -> {
            if (subscription.cancelled.get() || state == null) return;
            emitTireState(subscription, tireId, state);
        };
        try {
            if (tpms.registerTireStateMonitor(monitor)) {
                subscription.tpmsSource = tpms;
                subscription.tireStateMonitor = monitor;
            } else {
                Log.w(TAG, "TPMS monitor registration was rejected");
            }
        } catch (Throwable t) {
            Log.w(TAG, "TPMS subscription failed", t);
            try {
                tpms.unregisterTireStateMonitor(monitor);
            } catch (Throwable ignored) {
            }
        }
        // Some firmware exposes snapshots but rejects the monitor API. Initial values remain
        // useful in that case and will be refreshed by the next launcher subscription.
        for (int tireId : new int[] { TPMS.TIRE_ID_LEFT_FRONT, TPMS.TIRE_ID_RIGHT_FRONT,
                TPMS.TIRE_ID_LEFT_REAR, TPMS.TIRE_ID_RIGHT_REAR }) {
            if (subscription.cancelled.get()) break;
            try {
                ITireState state = tpms.getTireState(tireId);
                if (state != null) emitTireState(subscription, tireId, state);
            } catch (Throwable t) {
                Log.w(TAG, "Initial TPMS read failed for tire " + tireId, t);
            }
        }
    }

    private void emitTireState(TelemetrySubscription subscription, int tireId,
                               @NonNull ITireState state) {
        for (TireMetric metric : TIRE_METRICS) {
            if (metric.tireId != tireId || !subscription.metricIds.contains(metric.id)) continue;
            try {
                float value;
                if (metric.pressure) {
                    value = normalizeTirePressureBar(state.getPressure());
                    if (!Float.isFinite(value)) continue;
                } else {
                    value = state.getTemperature();
                    if (!isValidTireTemperature(value)) continue;
                }
                deliverTelemetry(subscription, metric.id, metric.label, metric.unit(), value);
            } catch (Throwable t) {
                Log.w(TAG, "TPMS value read failed for " + metric.id, t);
            }
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
        TPMS tpms = subscription.tpmsSource;
        TPMS.ITireStateMonitor monitor = subscription.tireStateMonitor;
        subscription.tpmsSource = null;
        subscription.tireStateMonitor = null;
        if (tpms != null && monitor != null) {
            try {
                tpms.unregisterTireStateMonitor(monitor);
            } catch (Throwable t) {
                Log.w(TAG, "TPMS monitor unregister failed", t);
            }
        }
    }

    private void executeTelemetryTask(Runnable task) {
        try {
            telemetryWorker.execute(task);
        } catch (RejectedExecutionException ignored) {
            // shutdown() makes the process-wide integration intentionally non-reusable.
        }
    }

    /** @return false only after process-wide shutdown rejected the task. */
    private boolean executeControlTask(Runnable task) {
        try {
            controlWorker.execute(task);
            return true;
        } catch (RejectedExecutionException ignored) {
            return false;
        }
    }

    /** Main-thread completion gate: at most one refresh may be queued behind an active refresh. */
    private void finishControlRefresh() {
        controlRefreshInFlight = false;
        if (!controlRefreshAgain) return;
        controlRefreshAgain = false;
        scheduleControlRefresh(0);
    }

    @Nullable
    private static ControlDefinition controlDefinition(@NonNull String id) {
        for (ControlDefinition definition : CONTROL_DEFINITIONS) {
            if (definition.descriptor.id.equals(id)) return definition;
        }
        return null;
    }

    private static boolean isFanDefinition(@NonNull ControlDefinition definition) {
        return FAN_CONTROL_ID.equals(definition.descriptor.id);
    }

    private static boolean isAutoFanDefinition(@NonNull ControlDefinition definition) {
        return definition.functionId == IHvac.HVAC_FUNC_AUTO_FAN_SETTING;
    }

    private static boolean isFanRelatedFunction(int functionId) {
        return functionId == IHvac.HVAC_FUNC_FAN_SPEED
                || functionId == IHvac.HVAC_FUNC_AUTO_FAN_SETTING
                || functionId == IHvac.HVAC_FUNC_AUTO;
    }

    static int fanFunctionIdForMode(boolean climateAutoActive) {
        return climateAutoActive
                ? IHvac.HVAC_FUNC_AUTO_FAN_SETTING : IHvac.HVAC_FUNC_FAN_SPEED;
    }

    /**
     * A missing fresh observation is not equivalent to MANUAL. A recent confirmed mode may bridge
     * a transient Binder gap; with no such fact the caller must treat routing as UNKNOWN.
     */
    @Nullable
    static Boolean conservativeFanAutoMode(@Nullable Boolean freshObservation,
                                           @Nullable Boolean lastConfirmed,
                                           long lastConfirmedAgeMillis) {
        if (freshObservation != null) return freshObservation;
        if (lastConfirmed == null || lastConfirmedAgeMillis < 0
                || lastConfirmedAgeMillis > FAN_MODE_CACHE_MAX_AGE_MS) {
            return null;
        }
        return lastConfirmed;
    }

    @Nullable
    private Boolean readConfirmedClimateAutoMode(@NonNull ICarFunction source) {
        ControlDefinition climateAuto = controlDefinition("climate.auto");
        Double observedValue = climateAuto == null ? null : readControlValue(source, climateAuto);
        Boolean fresh = observedValue == null ? null : observedValue != 0d;
        ConfirmedFanMode cached = lastConfirmedClimateAutoMode;
        long age = cached == null ? Long.MAX_VALUE
                : SystemClock.elapsedRealtime() - cached.observedElapsedMillis;
        return conservativeFanAutoMode(fresh,
                cached == null ? null : cached.autoActive, age);
    }

    @Nullable
    private ControlDefinition effectiveControlDefinition(@NonNull ICarFunction source,
                                                         @NonNull ControlDefinition requested) {
        if (!isFanDefinition(requested)) return requested;
        Boolean autoActive = readConfirmedClimateAutoMode(source);
        if (autoActive == null) return null;
        return fanFunctionIdForMode(autoActive) == IHvac.HVAC_FUNC_AUTO_FAN_SETTING
                ? AUTO_FAN_DEFINITION : FAN_DEFINITION;
    }

    @NonNull
    private static Set<String> selectKnownControlIds(@NonNull Set<String> requested) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (ControlDefinition definition : CONTROL_DEFINITIONS) {
            if (requested.contains(definition.descriptor.id)) {
                result.add(definition.descriptor.id);
            }
        }
        return Collections.unmodifiableSet(result);
    }

    @Override
    public void requestControlCatalog(@NonNull ControlCatalogListener listener) {
        executeTelemetryTask(() -> {
            ICarFunction source = ensureCarFunctions();
            List<CarControlDescriptor> values = new ArrayList<>();
            for (ControlDefinition definition : CONTROL_DEFINITIONS) {
                CarControlDescriptor.Availability availability;
                if (source == null) {
                    availability = CarControlDescriptor.Availability.UNKNOWN;
                } else if (isFanDefinition(definition)) {
                    availability = fanAvailability(source);
                } else {
                    availability = controlAvailability(source, definition);
                }
                // A definitive unsupported result hides a control for this exact vehicle. During
                // the boot Binder window UNKNOWN remains visible and can be retried safely.
                if (availability != CarControlDescriptor.Availability.UNSUPPORTED) {
                    CarControlDescriptor descriptor = definition.descriptor;
                    if (isFanDefinition(definition)) {
                        descriptor = definition.descriptorWithOptions(
                                fanCatalogOptions(source));
                    } else if (source != null
                            && availability == CarControlDescriptor.Availability.SUPPORTED
                            && descriptor.kind != CarControlDescriptor.Kind.ACTION
                            && !descriptor.options.isEmpty()) {
                        descriptor = definition.descriptorWithOptions(
                                supportedOptions(source, definition));
                    }
                    values.add(descriptor.withAvailability(availability));
                }
            }
            mainHandler.post(() -> listener.onCatalog(values));
        });
    }

    @NonNull
    static List<CarControlDescriptor.Option> safeAutoFanOptions(
            @NonNull List<CarControlDescriptor.Option> discovered,
            @Nullable Double confirmedProfile,
            @NonNull List<CarControlDescriptor.Option> lastConfirmedRuntimeOptions) {
        int confirmedFamily = confirmedProfile == null ? AUTO_FAN_FAMILY_UNKNOWN
                : autoFanProfileFamily(confirmedProfile);
        int discoveredFamily = autoFanFamily(discovered);
        int cachedFamily = autoFanFamily(lastConfirmedRuntimeOptions);
        int selectedFamily = confirmedFamily != AUTO_FAN_FAMILY_UNKNOWN
                ? confirmedFamily : discoveredFamily != AUTO_FAN_FAMILY_UNKNOWN
                ? discoveredFamily : cachedFamily;
        if (selectedFamily == AUTO_FAN_FAMILY_UNKNOWN) return Collections.emptyList();

        List<CarControlDescriptor.Option> source;
        if (discoveredFamily == selectedFamily) {
            source = discovered;
        } else if (confirmedFamily == selectedFamily
                && containsAutoFanFamily(discovered, selectedFamily)) {
            // AdaptAPI 1.0 advertises all five constants on some builds. A confirmed current
            // profile selects the actual two- or three-profile vehicle family.
            source = discovered;
        } else if (cachedFamily == selectedFamily) {
            source = lastConfirmedRuntimeOptions;
        } else {
            source = Collections.emptyList();
        }

        List<CarControlDescriptor.Option> canonical =
                autoFanOptionsForFamily(selectedFamily);
        boolean familyFallback = source.isEmpty()
                && confirmedFamily == selectedFamily;
        List<CarControlDescriptor.Option> result = new ArrayList<>();
        for (CarControlDescriptor.Option option : canonical) {
            if (familyFallback || containsOptionValue(source, option.value)
                    || (confirmedProfile != null
                    && sameValue(option.value, confirmedProfile))) {
                result.add(option);
            }
        }
        return result;
    }

    static int autoFanProfileFamily(double value) {
        if (sameValue(value, IHvac.AUTO_FAN_SETTING_SILENT)
                || sameValue(value, IHvac.AUTO_FAN_SETTING_NORMAL)
                || sameValue(value, IHvac.AUTO_FAN_SETTING_HIGH)) {
            return AUTO_FAN_FAMILY_THREE_PROFILE;
        }
        if (sameValue(value, IHvac.AUTO_FAN_SETTING_QUIETER)
                || sameValue(value, IHvac.AUTO_FAN_SETTING_HIGHER)) {
            return AUTO_FAN_FAMILY_TWO_PROFILE;
        }
        return AUTO_FAN_FAMILY_UNKNOWN;
    }

    private static int autoFanFamily(
            @NonNull List<CarControlDescriptor.Option> options) {
        int family = AUTO_FAN_FAMILY_UNKNOWN;
        for (CarControlDescriptor.Option option : options) {
            int optionFamily = autoFanProfileFamily(option.value);
            if (optionFamily == AUTO_FAN_FAMILY_UNKNOWN) continue;
            if (family != AUTO_FAN_FAMILY_UNKNOWN && family != optionFamily) {
                return AUTO_FAN_FAMILY_UNKNOWN;
            }
            family = optionFamily;
        }
        return family;
    }

    private static boolean containsAutoFanFamily(
            @NonNull List<CarControlDescriptor.Option> options, int family) {
        for (CarControlDescriptor.Option option : options) {
            if (autoFanProfileFamily(option.value) == family) return true;
        }
        return false;
    }

    private static boolean containsOptionValue(
            @NonNull List<CarControlDescriptor.Option> options, double value) {
        for (CarControlDescriptor.Option option : options) {
            if (sameValue(option.value, value)) return true;
        }
        return false;
    }

    @NonNull
    private static List<CarControlDescriptor.Option> autoFanOptionsForFamily(int family) {
        if (family == AUTO_FAN_FAMILY_THREE_PROFILE) {
            return autoFanThreeProfileOptions();
        }
        if (family == AUTO_FAN_FAMILY_TWO_PROFILE) {
            return autoFanTwoProfileOptions();
        }
        return Collections.emptyList();
    }

    private void rememberConfirmedRuntimeValue(@NonNull ControlDefinition definition,
                                               double value) {
        if (definition.functionId == IHvac.HVAC_FUNC_AUTO) {
            lastConfirmedClimateAutoMode =
                    new ConfirmedFanMode(value != 0d, SystemClock.elapsedRealtime());
            return;
        }
        if (!isAutoFanDefinition(definition)
                || autoFanProfileFamily(value) == AUTO_FAN_FAMILY_UNKNOWN) {
            return;
        }
        lastConfirmedAutoFanProfile = value;
        int cachedFamily = autoFanFamily(lastConfirmedAutoFanRuntimeOptions);
        int observedFamily = autoFanProfileFamily(value);
        if (cachedFamily != AUTO_FAN_FAMILY_UNKNOWN && cachedFamily != observedFamily) {
            lastConfirmedAutoFanRuntimeOptions = Collections.emptyList();
        }
    }

    private void rememberAutoFanRuntimeOptions(
            @NonNull List<CarControlDescriptor.Option> options) {
        if (options.isEmpty()
                || autoFanFamily(options) == AUTO_FAN_FAMILY_UNKNOWN) return;
        lastConfirmedAutoFanRuntimeOptions =
                Collections.unmodifiableList(new ArrayList<>(options));
    }

    @NonNull
    private List<CarControlDescriptor.Option> fanCatalogOptions(@Nullable ICarFunction source) {
        List<CarControlDescriptor.Option> result = new ArrayList<>();
        if (source == null) {
            result.addAll(FAN_DEFINITION.descriptor.options);
            result.addAll(safeAutoFanOptions(Collections.emptyList(),
                    lastConfirmedAutoFanProfile, lastConfirmedAutoFanRuntimeOptions));
            return result;
        }
        Boolean autoActive = readConfirmedClimateAutoMode(source);
        if (Boolean.TRUE.equals(autoActive)) {
            // A current profile is stronger evidence than AdaptAPI's five-value superset and
            // identifies whether this vehicle implements the 3-profile or 2-profile family.
            readControlValue(source, AUTO_FAN_DEFINITION);
        }
        result.addAll(supportedOptions(source, FAN_DEFINITION));
        result.addAll(supportedOptions(source, AUTO_FAN_DEFINITION));
        return result;
    }

    @NonNull
    private CarControlDescriptor.Availability fanAvailability(@NonNull ICarFunction source) {
        CarControlDescriptor.Availability manual =
                controlAvailability(source, FAN_DEFINITION);
        CarControlDescriptor.Availability automatic =
                controlAvailability(source, AUTO_FAN_DEFINITION);
        if (manual == CarControlDescriptor.Availability.SUPPORTED
                || automatic == CarControlDescriptor.Availability.SUPPORTED) {
            return CarControlDescriptor.Availability.SUPPORTED;
        }
        if (manual == CarControlDescriptor.Availability.UNSUPPORTED
                && automatic == CarControlDescriptor.Availability.UNSUPPORTED) {
            return CarControlDescriptor.Availability.UNSUPPORTED;
        }
        return CarControlDescriptor.Availability.UNKNOWN;
    }

    @NonNull
    private CarControlDescriptor.Availability controlAvailability(
            @NonNull ICarFunction source, @NonNull ControlDefinition definition) {
        try {
            FunctionStatus status = definition.zoned()
                    ? source.isFunctionSupported(definition.functionId, definition.zone)
                    : source.isFunctionSupported(definition.functionId);
            if (status == FunctionStatus.active) {
                return CarControlDescriptor.Availability.SUPPORTED;
            }
            if (status == FunctionStatus.notavailable) {
                return CarControlDescriptor.Availability.UNSUPPORTED;
            }
            return CarControlDescriptor.Availability.UNKNOWN;
        } catch (Throwable t) {
            // Some firmware throws UnsupportedOperationException for one optional function while
            // the shared ICarFunction Binder remains perfectly usable for the rest of climate.
            // A real read/write failure below still invalidates the dead proxy.
            Log.d(TAG, "vehicle function support is not ready: "
                    + definition.descriptor.id, t);
            return CarControlDescriptor.Availability.UNKNOWN;
        }
    }

    @Override
    public void subscribeControlStates(@NonNull Set<String> controlIds,
                                       @NonNull ControlStateListener listener) {
        ControlSubscription next = new ControlSubscription(listener,
                selectKnownControlIds(controlIds));
        ControlSubscription previous;
        synchronized (controlsLock) {
            previous = controlSubscriptions.put(listener, next);
        }
        if (previous != null) previous.cancelled.set(true);
        mainHandler.post(() -> {
            // Immediately replay confirmed states while the vendor worker refreshes them.
            for (String id : next.controlIds) {
                CarControlState cached = controlStateCache.get(id);
                if (cached != null && !next.cancelled.get()) listener.onControlState(cached);
            }
            controlRetryAttempts = 0;
            scheduleControlRefresh(0);
        });
    }

    @Override
    public void unsubscribeControlStates(@NonNull ControlStateListener listener) {
        ControlSubscription removed;
        synchronized (controlsLock) {
            removed = controlSubscriptions.remove(listener);
        }
        if (removed != null) removed.cancelled.set(true);
        scheduleControlRefresh(0);
    }

    private void scheduleControlRefresh(long delayMillis) {
        mainHandler.post(() -> {
            if (controlRefreshScheduled) {
                if (delayMillis > 0) return;
                mainHandler.removeCallbacks(controlRefreshTask);
            }
            controlRefreshScheduled = true;
            mainHandler.postDelayed(controlRefreshTask, Math.max(0, delayMillis));
        });
    }

    private void scheduleControlRetry() {
        long delay = controlRetryAttempts < AVAILABILITY_FAST_POLL_ATTEMPTS
                ? CONTROL_RETRY_MS : CONTROL_HEALTH_POLL_MS;
        if (controlRetryAttempts < AVAILABILITY_FAST_POLL_ATTEMPTS) controlRetryAttempts++;
        scheduleControlRefresh(delay);
    }

    @NonNull
    private Set<String> demandedControlIds() {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        synchronized (controlsLock) {
            for (ControlSubscription subscription : controlSubscriptions.values()) {
                if (!subscription.cancelled.get()) result.addAll(subscription.controlIds);
            }
        }
        return result;
    }

    private void refreshControlRegistrationAndStates() {
        Set<String> demanded = demandedControlIds();
        if (demanded.isEmpty()) {
            detachControlWatcher();
            return;
        }
        ICarFunction source = ensureCarFunctions();
        if (source == null) {
            // No fresh read exists yet. Do not replace a last confirmed value with a synthetic
            // offline dash; the panel already renders a genuine missing value as an ellipsis.
            scheduleControlRetry();
            return;
        }
        Set<Integer> functionIds = new LinkedHashSet<>();
        for (String id : demanded) {
            ControlDefinition definition = controlDefinition(id);
            if (definition != null
                    && definition.descriptor.kind != CarControlDescriptor.Kind.ACTION) {
                functionIds.add(definition.functionId);
                if (isFanDefinition(definition)) {
                    functionIds.add(IHvac.HVAC_FUNC_AUTO_FAN_SETTING);
                    functionIds.add(IHvac.HVAC_FUNC_AUTO);
                }
            }
        }
        boolean watcherReady = functionIds.isEmpty()
                || ensureControlWatcher(source, functionIds);
        if (functionIds.isEmpty()) {
            detachControlWatcher();
        }

        boolean retry = !watcherReady;
        boolean connectionFailed = false;
        for (String id : demanded) {
            ControlDefinition definition = controlDefinition(id);
            if (definition == null) continue;
            ControlReadResult result = readAndDeliverControl(source, definition);
            if (result == ControlReadResult.RETRY) retry = true;
            else if (result == ControlReadResult.CONNECTION_FAILED) {
                retry = true;
                connectionFailed = true;
            }
        }
        if (connectionFailed) {
            detachControlWatcher();
        }
        if (retry) {
            scheduleControlRetry();
        } else {
            controlRetryAttempts = 0;
            scheduleControlRefresh(CONTROL_HEALTH_POLL_MS);
        }
    }

    private boolean ensureControlWatcher(ICarFunction source, Set<Integer> functionIds) {
        if (source == controlWatcherSource && watchedControlFunctions.equals(functionIds)
                && controlWatcher != null) return true;
        detachControlWatcher();
        ICarFunction.IFunctionValueWatcher watcher = new ICarFunction.IFunctionValueWatcher() {
            @Override public void onCustomizeFunctionValueChanged(int functionId, int zone,
                                                                   float value) {
                executeControlTask(() ->
                        deliverVendorControlValue(functionId, zone, value, true));
            }

            @Override public void onFunctionChanged(int functionId) {
                executeControlTask(() -> readDemandedFunction(functionId));
            }

            @Override public void onFunctionValueChanged(int functionId, int zone, int value) {
                executeControlTask(() ->
                        deliverVendorControlValue(functionId, zone, value, false));
            }

            @Override public void onSupportedFunctionStatusChanged(int functionId, int zone,
                                                                    FunctionStatus status) {
                scheduleControlRefresh(0);
            }

            @Override public void onSupportedFunctionValueChanged(int functionId, int[] values) {
                scheduleControlRefresh(0);
            }
        };
        try {
            Set<Integer> registeredIds = new LinkedHashSet<>();
            // MConfig uses the single-ID overload on this firmware. Registering separately also
            // prevents one optional unsupported function from rejecting the entire batch.
            for (Integer id : functionIds) {
                if (source.registerFunctionValueWatcher(id, watcher)) registeredIds.add(id);
            }
            if (registeredIds.isEmpty()) {
                Log.w(TAG, "all registerFunctionValueWatcher calls were rejected");
                try { source.unregisterFunctionValueWatcher(watcher); }
                catch (Throwable ignored) {}
                return false;
            }
            controlWatcherSource = source;
            controlWatcher = watcher;
            watchedControlFunctions.addAll(registeredIds);
            return true;
        } catch (Throwable t) {
            try { source.unregisterFunctionValueWatcher(watcher); }
            catch (Throwable ignored) {}
            invalidateFunctionProxy(source);
            Log.w(TAG, "vehicle control watcher registration failed", t);
            return false;
        }
    }

    private void detachControlWatcher() {
        ICarFunction source = controlWatcherSource;
        ICarFunction.IFunctionValueWatcher watcher = controlWatcher;
        controlWatcherSource = null;
        controlWatcher = null;
        watchedControlFunctions.clear();
        if (source != null && watcher != null) {
            try {
                source.unregisterFunctionValueWatcher(watcher);
            } catch (Throwable t) {
                Log.w(TAG, "unregisterFunctionValueWatcher failed", t);
            }
        }
    }

    private void readDemandedFunction(int functionId) {
        ICarFunction source = ensureCarFunctions();
        if (source == null) {
            scheduleControlRetry();
            return;
        }
        Set<String> demanded = demandedControlIds();
        boolean routedFan = demanded.contains(FAN_CONTROL_ID)
                && isFanRelatedFunction(functionId);
        if (routedFan) {
            ControlReadResult result = readAndDeliverControl(source, FAN_DEFINITION);
            if (result == ControlReadResult.RETRY
                    || result == ControlReadResult.CONNECTION_FAILED) {
                scheduleControlRetry();
            }
        }
        for (ControlDefinition definition : CONTROL_DEFINITIONS) {
            if (definition.functionId == functionId
                    && !(routedFan && isFanDefinition(definition))
                    && demanded.contains(definition.descriptor.id)) {
                ControlReadResult result = readAndDeliverControl(source, definition);
                if (result == ControlReadResult.RETRY
                        || result == ControlReadResult.CONNECTION_FAILED) {
                    scheduleControlRetry();
                }
            }
        }
    }

    private enum ControlReadResult { CONFIRMED, UNSUPPORTED, RETRY, CONNECTION_FAILED }

    @NonNull
    private ControlReadResult readAndDeliverControl(ICarFunction source,
                                                    ControlDefinition definition) {
        ControlDefinition effective = effectiveControlDefinition(source, definition);
        if (effective == null) {
            // Do not reinterpret an unresolved AUTO fan as manual speed.
            return ControlReadResult.RETRY;
        }
        definition = effective;
        CarControlDescriptor.Availability availability = controlAvailability(source, definition);
        if (availability == CarControlDescriptor.Availability.UNSUPPORTED) {
            deliverControlState(new CarControlState(definition.descriptor.id, false, false,
                    Double.NaN, "Недоступно", false, 0, null, System.currentTimeMillis()));
            return ControlReadResult.UNSUPPORTED;
        }
        if (availability != CarControlDescriptor.Availability.SUPPORTED) {
            // UNKNOWN is a transient Binder/service state, not a new vehicle value.
            return ControlReadResult.RETRY;
        }
        if (definition.descriptor.kind == CarControlDescriptor.Kind.ACTION) {
            deliverControlState(new CarControlState(definition.descriptor.id, true, false,
                    Double.NaN, "Готово", false, 0, null, System.currentTimeMillis()));
            return ControlReadResult.CONFIRMED;
        }
        try {
            double value;
            if (definition.customFloat) {
                value = definition.zoned()
                        ? source.getCustomizeFunctionValue(definition.functionId, definition.zone)
                        : source.getCustomizeFunctionValue(definition.functionId);
            } else {
                value = definition.zoned()
                        ? source.getFunctionValue(definition.functionId, definition.zone)
                        : source.getFunctionValue(definition.functionId);
            }
            if (!isValidControlValue(definition, value)) {
                if (isReadableUnknownControlValue(definition, value)) {
                    deliverControlState(unknownControlState(definition, value));
                }
                return ControlReadResult.RETRY;
            }
            rememberConfirmedRuntimeValue(definition, value);
            deliverControlState(normalizeControlState(definition, value));
            confirmActiveControlCommandFromState(definition, value);
            return ControlReadResult.CONFIRMED;
        } catch (Throwable t) {
            invalidateFunctionProxy(source);
            Log.w(TAG, "vehicle control read failed for " + definition.descriptor.id, t);
            return ControlReadResult.CONNECTION_FAILED;
        }
    }

    private void deliverVendorControlValue(int functionId, int zone, double value,
                                           boolean customFloat) {
        if (!customFloat && functionId == IHvac.HVAC_FUNC_AUTO) {
            ControlDefinition climateAuto = controlDefinition("climate.auto");
            if (climateAuto != null && isValidControlValue(climateAuto, value)) {
                rememberConfirmedRuntimeValue(climateAuto, value);
            }
        } else if (!customFloat && functionId == IHvac.HVAC_FUNC_AUTO_FAN_SETTING
                && zone == FRONT_FAN_ZONE
                && isValidControlValue(AUTO_FAN_DEFINITION, value)) {
            rememberConfirmedRuntimeValue(AUTO_FAN_DEFINITION, value);
        }
        Set<String> demanded = demandedControlIds();
        if (!customFloat && demanded.contains(FAN_CONTROL_ID)
                && isFanRelatedFunction(functionId)) {
            // AUTO and manual fan values share one raw PA channel but use different public value
            // domains. Re-read through the current climate mode instead of interpreting a callback
            // against the definition that happened to register it.
            readDemandedFunction(functionId);
            return;
        }
        for (ControlDefinition definition : CONTROL_DEFINITIONS) {
            if (definition.functionId != functionId || definition.customFloat != customFloat
                    || !demanded.contains(definition.descriptor.id)) continue;
            if (definition.zoned() && definition.zone != zone) continue;
            if (!isValidControlValue(definition, value)) {
                if (isReadableUnknownControlValue(definition, value)) {
                    deliverControlState(unknownControlState(definition, value));
                }
                continue;
            }
            rememberConfirmedRuntimeValue(definition, value);
            deliverControlState(normalizeControlState(definition, value));
            confirmActiveControlCommandFromState(definition, value);
        }
    }

    @NonNull
    private static CarControlState unknownControlState(@NonNull ControlDefinition definition,
                                                       double value) {
        return new CarControlState(definition.descriptor.id, true, false, value,
                "Неизвестно", false, 0, null, System.currentTimeMillis());
    }

    @NonNull
    private CarControlState normalizeControlState(ControlDefinition definition, double value) {
        int level = 0;
        String label;
        boolean active;
        if (definition.customFloat) {
            label = String.format(java.util.Locale.ROOT, "%.1f%s", value,
                    definition.descriptor.unit);
            active = true;
        } else {
            label = Long.toString(Math.round(value));
            active = value != 0;
            for (int index = 0; index < definition.descriptor.options.size(); index++) {
                CarControlDescriptor.Option option = definition.descriptor.options.get(index);
                if (sameValue(option.value, value)) {
                    label = option.label;
                    level = index;
                    break;
                }
            }
        }
        String color = stateColor(definition, active, level, value);
        return new CarControlState(definition.descriptor.id, true, true, value, label,
                active, level, color, System.currentTimeMillis());
    }

    @Nullable
    private static String stateColor(ControlDefinition definition, boolean active, int level,
                                     double value) {
        if (!active) return null;
        String id = definition.descriptor.id;
        if (id.contains("seat_heat") || id.contains("wheel_heat")) {
            if (level >= 4) return definition.descriptor.suggestedActiveColor;
            if (level >= 3) return "#FFF44336";
            if (level == 2) return "#FFFF9800";
            return "#FFFFC107";
        }
        if (id.contains("seat_vent")) {
            if (level >= 4) return definition.descriptor.suggestedActiveColor;
            if (level >= 3) return "#FF0277BD";
            if (level == 2) return "#FF039BE5";
            return "#FF4FC3F7";
        }
        if (id.equals("vehicle.drive_mode")) {
            if (sameValue(value, IDriveMode.DRIVE_MODE_SELECTION_DYNAMIC)
                    || sameValue(value, IDriveMode.DRIVE_MODE_SELECTION_POWER)) {
                return "#FFF44336";
            }
            if (sameValue(value, IDriveMode.DRIVE_MODE_SELECTION_ECO)
                    || sameValue(value, IDriveMode.DRIVE_MODE_SELECTION_SAVE)) {
                return "#FF8BC34A";
            }
            if (sameValue(value, IDriveMode.DRIVE_MODE_SELECTION_SNOW)) return "#FF81D4FA";
        }
        return definition.descriptor.suggestedActiveColor;
    }

    private void deliverControlState(@NonNull CarControlState state) {
        mainHandler.post(() -> {
            controlStateCache.put(state.controlId, state);
            List<ControlSubscription> listeners;
            synchronized (controlsLock) {
                listeners = new ArrayList<>(controlSubscriptions.values());
            }
            for (ControlSubscription subscription : listeners) {
                if (!subscription.cancelled.get()
                        && subscription.controlIds.contains(state.controlId)) {
                    subscription.listener.onControlState(state);
                }
            }
        });
    }

    @Override
    public void executeControl(@NonNull CarControlCommand command,
                               @NonNull ControlCommandListener listener) {
        if (controlsShuttingDown) {
            postCommandResult(listener, false, "ECARX уже остановлен");
            return;
        }
        String key = controlCommandKey(command);
        long generation = reserveControlCommandSubmission(command.controlId, key);
        if (!executeControlTask(() -> enqueueControlCommandOnWorker(
                command, key, generation, listener))) {
            postCommandResult(listener, false, "ECARX уже остановлен");
        }
    }

    @NonNull
    static String controlCommandKey(@NonNull CarControlCommand command) {
        return command.controlId + '\u0000' + command.operation.name() + '\u0000'
                + Long.toHexString(Double.doubleToLongBits(command.value));
    }

    private void enqueueControlCommandOnWorker(@NonNull CarControlCommand command,
                                               @NonNull String key, long generation,
                                               @NonNull ControlCommandListener listener) {
        if (controlsShuttingDown) {
            postCommandResult(listener, false, "ECARX уже остановлен");
            return;
        }
        if (!isLatestControlCommandSubmission(command.controlId, key, generation)) {
            postCommandResult(listener, false, "Команда заменена более новой");
            return;
        }
        ActiveControlCommand previous = activeControlCommands.get(command.controlId);
        if (previous != null && previous.key.equals(key)
                && previous.generation == generation) {
            previous.listeners.add(listener);
            return;
        }
        if (previous != null) {
            cancelActiveControlCommand(previous, "Команда заменена более новой");
        }
        ActiveControlCommand active = new ActiveControlCommand(command, key, generation,
                SystemClock.elapsedRealtime() + CONTROL_COMMAND_TIMEOUT_MS, listener);
        activeControlCommands.put(command.controlId, active);
        startControlCommand(active);
    }

    private long reserveControlCommandSubmission(@NonNull String controlId,
                                                 @NonNull String key) {
        synchronized (controlCommandSubmissionLock) {
            ControlCommandSubmission previous = latestControlCommandSubmissions.get(controlId);
            if (previous != null && previous.key.equals(key)) return previous.generation;
            long generation = ++nextControlCommandGeneration;
            latestControlCommandSubmissions.put(controlId,
                    new ControlCommandSubmission(key, generation));
            return generation;
        }
    }

    private boolean isLatestControlCommandSubmission(@NonNull String controlId,
                                                     @NonNull String key, long generation) {
        synchronized (controlCommandSubmissionLock) {
            ControlCommandSubmission latest = latestControlCommandSubmissions.get(controlId);
            return latest != null && latest.key.equals(key)
                    && isLatestControlCommand(latest.generation, generation);
        }
    }

    private void startControlCommand(@NonNull ActiveControlCommand active) {
        if (!isCurrentControlCommand(active)) return;
        CarControlCommand command = active.command;
        ControlDefinition definition = controlDefinition(command.controlId);
        if (definition == null) {
            completeControlCommand(active, false, "Неизвестная функция автомобиля");
            return;
        }
        ICarFunction source = ensureCarFunctions();
        if (source == null) {
            completeControlCommand(active, false, "ECARX ещё не подключён");
            scheduleControlRetry();
            return;
        }
        ControlDefinition effective = effectiveControlDefinition(source, definition);
        if (effective == null) {
            completeControlCommand(active, false,
                    "Не удалось подтвердить режим AUTO. Команда не отправлена");
            scheduleControlRetry();
            return;
        }
        definition = effective;
        active.definition = definition;
        CarControlDescriptor.Availability availability = controlAvailability(source, definition);
        if (availability == CarControlDescriptor.Availability.UNKNOWN) {
            completeControlCommand(active, false,
                    "ECARX ещё синхронизирует эту функцию, повторите через секунду");
            scheduleControlRetry();
            return;
        }
        if (availability != CarControlDescriptor.Availability.SUPPORTED) {
            completeControlCommand(active, false, "Функция не поддерживается автомобилем");
            return;
        }
        active.pulse = definition.descriptor.kind == CarControlDescriptor.Kind.ACTION;
        Double current = active.pulse ? 0d : readControlValue(source, definition);
        if (!active.pulse && current == null) {
            completeControlCommand(active, false, "Не удалось прочитать текущее состояние");
            scheduleControlRetry();
            return;
        }
        List<CarControlDescriptor.Option> runtimeOptions = supportedOptions(source, definition);
        Double target = active.pulse ? 1d
                : commandTarget(definition, command, current, runtimeOptions);
        if (target == null) {
            completeControlCommand(active, false, "Недопустимое значение команды");
            return;
        }
        active.target = target;
        active.lastConfirmed = current;
        if (!active.pulse && isControlCommandConfirmed(current, target)) {
            deliverControlState(normalizeControlState(definition, current));
            completeControlCommand(active, true, null);
            return;
        }
        if (active.pulse) attemptPulseControl(active);
        else beginControlConfirmationPoll(active, 0);
    }

    /** One short pulse attempt; its optional retry is delayed without sleeping on controlWorker. */
    private void attemptPulseControl(@NonNull ActiveControlCommand active) {
        if (!isCurrentControlCommand(active)) return;
        if (controlCommandExpired(active)) {
            failControlCommandTimeout(active);
            return;
        }
        ControlDefinition definition = active.definition;
        ICarFunction source = ensureCarFunctions();
        if (definition == null || source == null) {
            completeControlCommand(active, false, "Ошибка связи с ECARX");
            scheduleControlRetry();
            return;
        }
        active.pulseAttempts++;
        try {
            if (writeControlValue(source, definition, active.target)) {
                completeControlCommand(active, true, null);
                return;
            }
        } catch (Throwable error) {
            invalidateFunctionProxy(source);
            scheduleControlRetry();
            Log.w(TAG, "vehicle pulse failed for " + active.command.controlId, error);
        }
        if (active.pulseAttempts >= CONTROL_PULSE_ATTEMPTS) {
            completeControlCommand(active, false, "ECARX отклонил одноразовую команду");
            return;
        }
        mainHandler.postDelayed(() -> executeControlTask(() -> attemptPulseControl(active)),
                CONTROL_PULSE_RETRY_MS);
    }

    /**
     * Starts one write/wait/read state-machine step. Only the Binder write occupies controlWorker;
     * the settling delay is posted to the main looper, allowing watcher reads and other controls
     * to pass through the same serial vendor lane in the meantime.
     */
    private void beginControlConfirmationPoll(@NonNull ActiveControlCommand active, int poll) {
        if (!isCurrentControlCommand(active)) return;
        if (controlCommandExpired(active)) {
            failControlCommandTimeout(active);
            return;
        }
        ControlDefinition definition = active.definition;
        ICarFunction source = ensureCarFunctions();
        if (definition == null) {
            completeControlCommand(active, false, "Неизвестная функция автомобиля");
            return;
        }
        if (source != null && shouldSendControlWrite(poll)) {
            try {
                active.acceptedAtLeastOnce |= writeControlValue(
                        source, definition, active.target);
            } catch (Throwable error) {
                invalidateFunctionProxy(source);
                scheduleControlRetry();
                Log.w(TAG, "vehicle command write failed for "
                        + active.command.controlId, error);
            }
        }
        long delay = controlConfirmDelayMillis(poll);
        mainHandler.postDelayed(() -> executeControlTask(() ->
                readControlConfirmation(active, poll)), delay);
    }

    private void readControlConfirmation(@NonNull ActiveControlCommand active, int poll) {
        if (!isCurrentControlCommand(active)) return;
        if (controlCommandExpired(active)) {
            failControlCommandTimeout(active);
            return;
        }
        ControlDefinition definition = active.definition;
        ICarFunction source = ensureCarFunctions();
        Double confirmed = definition == null || source == null
                ? null : readControlValue(source, definition);
        if (!isCurrentControlCommand(active)) return;
        if (confirmed != null) {
            active.lastConfirmed = confirmed;
            if (isControlCommandConfirmed(confirmed, active.target)) {
                deliverControlState(normalizeControlState(definition, confirmed));
                completeControlCommand(active, true, null);
                return;
            }
        }
        int nextPoll = poll + 1;
        beginControlConfirmationPoll(active, nextPoll);
    }

    /**
     * A vendor watcher/read may confirm a command before the scheduled poll. Only the latest
     * per-control generation is eligible; an old target can therefore never finish a replacement.
     */
    private void confirmActiveControlCommandFromState(@NonNull ControlDefinition definition,
                                                      double value) {
        ActiveControlCommand active = activeControlCommands.get(definition.descriptor.id);
        if (active == null || active.pulse || !isCurrentControlCommand(active)) return;
        if (controlCommandExpired(active)) {
            failControlCommandTimeout(active);
            return;
        }
        active.lastConfirmed = value;
        if (isControlCommandConfirmed(value, active.target)) {
            completeControlCommand(active, true, null);
        }
    }

    private boolean isCurrentControlCommand(@NonNull ActiveControlCommand candidate) {
        if (!isActiveControlCommand(candidate)) return false;
        return isLatestControlCommandSubmission(candidate.command.controlId,
                candidate.key, candidate.generation);
    }

    private boolean isActiveControlCommand(@NonNull ActiveControlCommand candidate) {
        ActiveControlCommand current = activeControlCommands.get(candidate.command.controlId);
        return current == candidate
                && isLatestControlCommand(current.generation, candidate.generation);
    }

    static boolean isLatestControlCommand(long activeGeneration, long candidateGeneration) {
        return activeGeneration > 0L && activeGeneration == candidateGeneration;
    }

    static long controlConfirmDelayMillis(int confirmationPoll) {
        return CONTROL_CONFIRM_POLL_MS + Math.max(0, confirmationPoll) * 10L;
    }

    private static boolean controlCommandExpired(@NonNull ActiveControlCommand active) {
        return SystemClock.elapsedRealtime() >= active.deadlineElapsedMillis;
    }

    private void failControlCommandTimeout(@NonNull ActiveControlCommand active) {
        restoreLastConfirmedControlState(active);
        scheduleControlRefresh(0);
        completeControlCommand(active, false, active.acceptedAtLeastOnce
                ? "Команда отправлена, но автомобиль не подтвердил её за 5 секунд"
                : "ECARX отклонил команду");
    }

    private void restoreLastConfirmedControlState(@NonNull ActiveControlCommand active) {
        if (active.definition != null && active.lastConfirmed != null) {
            deliverControlState(normalizeControlState(active.definition, active.lastConfirmed));
        }
    }

    private void completeControlCommand(@NonNull ActiveControlCommand active, boolean success,
                                        @Nullable String message) {
        if (!isCurrentControlCommand(active)) return;
        finishActiveControlCommand(active, success, message);
    }

    private void cancelActiveControlCommand(@NonNull ActiveControlCommand active,
                                            @NonNull String message) {
        if (!isActiveControlCommand(active)) return;
        finishActiveControlCommand(active, false, message);
    }

    private void finishActiveControlCommand(@NonNull ActiveControlCommand active, boolean success,
                                            @Nullable String message) {
        activeControlCommands.remove(active.command.controlId);
        List<ControlCommandListener> listeners = new ArrayList<>(active.listeners);
        active.listeners.clear();
        for (ControlCommandListener listener : listeners) {
            postCommandResult(listener, success, message);
        }
    }

    private void cancelActiveControlCommandsOnWorker(@NonNull String message) {
        List<ActiveControlCommand> active =
                new ArrayList<>(activeControlCommands.values());
        for (ActiveControlCommand command : active) {
            cancelActiveControlCommand(command, message);
        }
    }

    static boolean shouldSendControlWrite(int confirmationPoll) {
        return confirmationPoll >= 0
                && confirmationPoll < CONTROL_WRITE_WINDOW_POLLS
                && (confirmationPoll == 0
                || confirmationPoll % CONTROL_RESEND_EVERY_POLLS == 0);
    }

    static boolean isControlCommandConfirmed(@Nullable Double actual, double target) {
        return actual != null && sameValue(actual, target);
    }

    private boolean writeControlValue(ICarFunction source, ControlDefinition definition,
                                      double target) {
        if (definition.customFloat) {
            return definition.zoned()
                    ? source.setCustomizeFunctionValue(definition.functionId,
                    definition.zone, (float) target)
                    : source.setCustomizeFunctionValue(definition.functionId, (float) target);
        }
        int value = (int) Math.round(target);
        return definition.zoned()
                ? source.setFunctionValue(definition.functionId, definition.zone, value)
                : source.setFunctionValue(definition.functionId, value);
    }

    @Nullable
    private Double readControlValue(ICarFunction source, ControlDefinition definition) {
        try {
            double value;
            if (definition.customFloat) {
                value = definition.zoned()
                        ? source.getCustomizeFunctionValue(definition.functionId, definition.zone)
                        : source.getCustomizeFunctionValue(definition.functionId);
            } else {
                value = definition.zoned()
                        ? source.getFunctionValue(definition.functionId, definition.zone)
                        : source.getFunctionValue(definition.functionId);
            }
            if (!isValidControlValue(definition, value)) return null;
            rememberConfirmedRuntimeValue(definition, value);
            return value;
        } catch (Throwable t) {
            invalidateFunctionProxy(source);
            return null;
        }
    }

    @Nullable
    private static Double commandTarget(ControlDefinition definition, CarControlCommand command,
                                        double current,
                                        List<CarControlDescriptor.Option> availableOptions) {
        switch (command.operation) {
            case SET:
                if (definition.customFloat) {
                    double rounded = Math.round((command.value - definition.descriptor.minimum)
                            / definition.descriptor.step) * definition.descriptor.step
                            + definition.descriptor.minimum;
                    if (rounded < definition.descriptor.minimum - .001
                            || rounded > definition.descriptor.maximum + .001) return null;
                    return rounded;
                }
                for (CarControlDescriptor.Option option : availableOptions) {
                    if (sameValue(option.value, command.value)) return option.value;
                }
                return null;
            case ACTIVATE:
                for (CarControlDescriptor.Option option : definition.descriptor.options) {
                    if (option.value != 0) return option.value;
                }
                return 1d;
            case TOGGLE:
                if (current != 0) return 0d;
                for (CarControlDescriptor.Option option : definition.descriptor.options) {
                    if (option.value != 0) return option.value;
                }
                return 1d;
            case CYCLE:
                List<CarControlDescriptor.Option> options = availableOptions;
                if (options.isEmpty()) return null;
                for (int index = 0; index < options.size(); index++) {
                    if (sameValue(options.get(index).value, current)) {
                        return options.get((index + 1) % options.size()).value;
                    }
                }
                return options.get(0).value;
            default:
                return null;
        }
    }

    @NonNull
    private List<CarControlDescriptor.Option> supportedOptions(ICarFunction source,
                                                                ControlDefinition definition) {
        if (definition.customFloat || definition.descriptor.options.isEmpty()) {
            return definition.descriptor.options;
        }
        List<CarControlDescriptor.Option> discovered = Collections.emptyList();
        try {
            int[] values = definition.zoned()
                    ? source.getSupportedFunctionValue(definition.functionId, definition.zone)
                    : source.getSupportedFunctionValue(definition.functionId);
            if (values != null && values.length > 0) {
                Set<Integer> supported = new HashSet<>();
                for (int value : values) supported.add(value);
                List<CarControlDescriptor.Option> result = new ArrayList<>();
                for (CarControlDescriptor.Option option : definition.descriptor.options) {
                    if (option.value == 0
                            || supported.contains((int) Math.round(option.value))) {
                        result.add(option);
                    }
                }
                discovered = result;
            }
        } catch (Throwable ignored) {
            // The safe fallback below is definition-specific.
        }
        if (isAutoFanDefinition(definition)) {
            List<CarControlDescriptor.Option> safe = safeAutoFanOptions(discovered,
                    lastConfirmedAutoFanProfile, lastConfirmedAutoFanRuntimeOptions);
            rememberAutoFanRuntimeOptions(safe);
            return safe;
        }
        // Older firmware can reject discovery even though direct reads/writes work. The ordinary
        // toggle/manual domains do not contain mutually incompatible vehicle families.
        return discovered.isEmpty() ? definition.descriptor.options : discovered;
    }

    private static boolean sameValue(double left, double right) {
        return Math.abs(left - right) < .01d;
    }

    private static boolean isValidControlValue(ControlDefinition definition, double value) {
        if (!Double.isFinite(value) || value == -1 || value == Integer.MAX_VALUE
                || value == Integer.MIN_VALUE) return false;
        if (!definition.customFloat) {
            if (sameValue(value, ICarFunction.COMMON_VALUE_ERROR)
                    || sameValue(value, ICarFunction.COMMON_VALUE_NONE)
                    || sameValue(value, ICarFunction.COMMON_VALUE_UNKNOWN)) return false;
            if (!definition.descriptor.options.isEmpty()) {
                for (CarControlDescriptor.Option option : definition.descriptor.options) {
                    if (sameValue(option.value, value)) return true;
                }
                return false;
            }
            return definition.descriptor.kind == CarControlDescriptor.Kind.ACTION;
        }
        float asFloat = (float) value;
        return !isObviousFloatSentinel(asFloat)
                && value >= definition.descriptor.minimum - .01
                && value <= definition.descriptor.maximum + .01;
    }

    /**
     * A finite vendor extension is still a real observation even when this SDK revision has no
     * semantic label for it. Show an explicit unknown state and keep the control disabled instead
     * of collapsing it into the same silent retry path as Binder error sentinels.
     */
    private static boolean isReadableUnknownControlValue(@NonNull ControlDefinition definition,
                                                         double value) {
        if (definition.customFloat || definition.descriptor.options.isEmpty()
                || !Double.isFinite(value) || value == -1 || value == Integer.MAX_VALUE
                || value == Integer.MIN_VALUE
                || sameValue(value, ICarFunction.COMMON_VALUE_ERROR)
                || sameValue(value, ICarFunction.COMMON_VALUE_NONE)
                || sameValue(value, ICarFunction.COMMON_VALUE_UNKNOWN)) {
            return false;
        }
        for (CarControlDescriptor.Option option : definition.descriptor.options) {
            if (sameValue(option.value, value)) return false;
        }
        return true;
    }

    private void postCommandResult(ControlCommandListener listener, boolean success,
                                   @Nullable String message) {
        mainHandler.post(() -> {
            try {
                listener.onResult(success, message);
            } catch (RuntimeException error) {
                Log.w(TAG, "vehicle control result listener failed", error);
            }
        });
    }

    @Override
    public void shutdown() {
        controlsShuttingDown = true;
        synchronized (controlCommandSubmissionLock) {
            latestControlCommandSubmissions.clear();
        }
        for (BrickType type : BrickType.values()) {
            unsubscribe(type);
        }
        List<TelemetrySubscription> telemetry;
        synchronized (telemetryLock) {
            telemetry = new ArrayList<>(telemetrySubscriptions.values());
            telemetrySubscriptions.clear();
        }
        for (TelemetrySubscription subscription : telemetry) subscription.cancelled.set(true);
        List<ControlSubscription> controls;
        synchronized (controlsLock) {
            controls = new ArrayList<>(controlSubscriptions.values());
            controlSubscriptions.clear();
        }
        for (ControlSubscription subscription : controls) subscription.cancelled.set(true);
        mainHandler.removeCallbacks(controlRefreshTask);
        controlRefreshScheduled = false;
        controlRefreshAgain = false;
        mainHandler.removeCallbacks(bcmPollTask);
        bcmPollScheduled = false;
        if (autoHoldReceiverRegistered) {
            try { appContext.unregisterReceiver(autoHoldChangedReceiver); }
            catch (IllegalArgumentException ignored) {}
            autoHoldReceiverRegistered = false;
        }
        executeTelemetryTask(() -> {
            for (TelemetrySubscription subscription : telemetry) {
                unregisterTelemetryVendorListeners(subscription);
            }
            bcmLastOnMillis.clear();
        });
        executeControlTask(() -> {
            detachControlWatcher();
            cancelActiveControlCommandsOnWorker("ECARX остановлен");
        });
        signalFallback.shutdown();
        telemetryWorker.shutdown();
        controlWorker.shutdown();
        availabilityChangedListener = null;
    }
}
