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
import com.ecarx.xui.adaptapi.car.base.ICarFunction;
import com.ecarx.xui.adaptapi.car.base.ICarInfo;
import com.ecarx.xui.adaptapi.car.hvac.IHvac;
import com.ecarx.xui.adaptapi.car.sensor.ISensor;
import com.ecarx.xui.adaptapi.car.vehicle.IDriveMode;
import com.ecarx.xui.adaptapi.car.vehicle.IVehicle;
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
    private static final int CONTROL_WRITE_ATTEMPTS = 6;
    private static final int NO_ZONE = Integer.MIN_VALUE;

    private final Context appContext;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Map<BrickType, Subscription> subscriptions = new EnumMap<>(BrickType.class);
    /** Main-thread desired brick subscriptions, including registrations waiting for Binder. */
    private final Set<BrickType> requestedBrickTypes = new HashSet<>();
    private final Object telemetryLock = new Object();
    private final Map<TelemetryListener, TelemetrySubscription> telemetrySubscriptions =
            new IdentityHashMap<>();
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

    @Nullable
    private volatile ISensor sensors;
    @Nullable
    private volatile ICar carApi;
    @Nullable
    private volatile ICarFunction carFunctions;

    /** Accessed only by telemetryWorker, except vendor callbacks which merely post deliveries. */
    @Nullable private ICarFunction controlWatcherSource;
    @Nullable private ICarFunction.IFunctionValueWatcher controlWatcher;
    private final Set<Integer> watchedControlFunctions = new HashSet<>();
    private boolean controlRefreshScheduled;
    private volatile int controlRetryAttempts;
    private final Runnable controlRefreshTask = () -> {
        controlRefreshScheduled = false;
        executeTelemetryTask(this::refreshControlRegistrationAndStates);
    };

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
                option(IHvac.FAN_SPEED_LEVEL_9, "9"),
                option(IHvac.FAN_SPEED_LEVEL_AUTO, "Auto"));
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
                    "defrost_front", CarControlDescriptor.Kind.ACTION,
                    IHvac.HVAC_FUNC_DEFROST_FRONT, NO_ZONE, false, Collections.emptyList(),
                    0, 1, 1, "", "#FF80DEEA"),
            new ControlDefinition("climate.defrost_rear", "Обогрев заднего стекла", "Климат",
                    "defrost_rear", CarControlDescriptor.Kind.ACTION,
                    IHvac.HVAC_FUNC_DEFROST_REAR, NO_ZONE, false, Collections.emptyList(),
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
            new ControlDefinition("climate.fan", "Скорость вентилятора", "Климат", "fan",
                    CarControlDescriptor.Kind.LEVELS, IHvac.HVAC_FUNC_FAN_SPEED, 8, false,
                    fanOptions(), 0, 9, 1, "", "#FF42A5F5"),
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

    @Nullable
    private static ControlDefinition controlDefinition(@NonNull String id) {
        for (ControlDefinition definition : CONTROL_DEFINITIONS) {
            if (definition.descriptor.id.equals(id)) return definition;
        }
        return null;
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
                CarControlDescriptor.Availability availability = source == null
                        ? CarControlDescriptor.Availability.UNKNOWN
                        : controlAvailability(source, definition);
                // A definitive unsupported result hides a control for this exact vehicle. During
                // the boot Binder window UNKNOWN remains visible and can be retried safely.
                if (availability != CarControlDescriptor.Availability.UNSUPPORTED) {
                    CarControlDescriptor descriptor = definition.descriptor;
                    if (source != null
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
            invalidateFunctionProxy(source);
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
            deliverUnavailableControls(demanded, "…");
            scheduleControlRetry();
            return;
        }
        Set<Integer> functionIds = new LinkedHashSet<>();
        for (String id : demanded) {
            ControlDefinition definition = controlDefinition(id);
            if (definition != null
                    && definition.descriptor.kind != CarControlDescriptor.Kind.ACTION) {
                functionIds.add(definition.functionId);
            }
        }
        if (!functionIds.isEmpty() && !ensureControlWatcher(source, functionIds)) {
            deliverUnavailableControls(demanded, "—");
            scheduleControlRetry();
            return;
        } else if (functionIds.isEmpty()) {
            detachControlWatcher();
        }

        boolean failed = false;
        for (String id : demanded) {
            ControlDefinition definition = controlDefinition(id);
            if (definition != null && !readAndDeliverControl(source, definition)) failed = true;
        }
        if (failed) {
            invalidateFunctionProxy(source);
            detachControlWatcher();
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
                deliverVendorControlValue(functionId, zone, value, true);
            }

            @Override public void onFunctionChanged(int functionId) {
                executeTelemetryTask(() -> readDemandedFunction(functionId));
            }

            @Override public void onFunctionValueChanged(int functionId, int zone, int value) {
                deliverVendorControlValue(functionId, zone, value, false);
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
        for (ControlDefinition definition : CONTROL_DEFINITIONS) {
            if (definition.functionId == functionId
                    && demanded.contains(definition.descriptor.id)) {
                readAndDeliverControl(source, definition);
            }
        }
    }

    private boolean readAndDeliverControl(ICarFunction source, ControlDefinition definition) {
        CarControlDescriptor.Availability availability = controlAvailability(source, definition);
        if (availability != CarControlDescriptor.Availability.SUPPORTED) {
            deliverControlState(new CarControlState(definition.descriptor.id, false, false,
                    Double.NaN, availability == CarControlDescriptor.Availability.UNKNOWN
                    ? "…" : "—", false, 0, null, System.currentTimeMillis()));
            return availability != CarControlDescriptor.Availability.UNKNOWN;
        }
        if (definition.descriptor.kind == CarControlDescriptor.Kind.ACTION) {
            deliverControlState(new CarControlState(definition.descriptor.id, true, false,
                    Double.NaN, "Готово", false, 0, null, System.currentTimeMillis()));
            return true;
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
            if (!isValidControlValue(definition, value)) return false;
            deliverControlState(normalizeControlState(definition, value));
            return true;
        } catch (Throwable t) {
            invalidateFunctionProxy(source);
            Log.w(TAG, "vehicle control read failed for " + definition.descriptor.id, t);
            return false;
        }
    }

    private void deliverVendorControlValue(int functionId, int zone, double value,
                                           boolean customFloat) {
        Set<String> demanded = demandedControlIds();
        for (ControlDefinition definition : CONTROL_DEFINITIONS) {
            if (definition.functionId != functionId || definition.customFloat != customFloat
                    || !demanded.contains(definition.descriptor.id)) continue;
            if (definition.zoned() && definition.zone != zone) continue;
            if (!isValidControlValue(definition, value)) continue;
            deliverControlState(normalizeControlState(definition, value));
        }
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

    private void deliverUnavailableControls(Set<String> ids, String label) {
        for (String id : ids) {
            deliverControlState(new CarControlState(id, false, false, Double.NaN, label,
                    false, 0, null, System.currentTimeMillis()));
        }
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
        executeTelemetryTask(() -> executeControlOnWorker(command, listener));
    }

    private void executeControlOnWorker(CarControlCommand command,
                                        ControlCommandListener listener) {
        ControlDefinition definition = controlDefinition(command.controlId);
        if (definition == null) {
            postCommandResult(listener, false, "Неизвестная функция автомобиля");
            return;
        }
        ICarFunction source = ensureCarFunctions();
        if (source == null) {
            postCommandResult(listener, false, "ECARX ещё не подключён");
            scheduleControlRetry();
            return;
        }
        if (controlAvailability(source, definition)
                != CarControlDescriptor.Availability.SUPPORTED) {
            postCommandResult(listener, false, "Функция не поддерживается автомобилем");
            return;
        }
        boolean pulse = definition.descriptor.kind == CarControlDescriptor.Kind.ACTION;
        Double current = pulse ? 0d : readControlValue(source, definition);
        if (!pulse && current == null) {
            postCommandResult(listener, false, "Не удалось прочитать текущее состояние");
            return;
        }
        List<CarControlDescriptor.Option> runtimeOptions = supportedOptions(source, definition);
        Double target = pulse ? 1d : commandTarget(definition, command, current, runtimeOptions);
        if (target == null) {
            postCommandResult(listener, false, "Недопустимое значение команды");
            return;
        }
        try {
            if (pulse) {
                // MConfig uses these functions as write-only pulses. They can return to zero
                // immediately, so requiring a pre-read or read-back would report a false error.
                boolean accepted = writeControlValue(source, definition, target);
                if (!accepted) {
                    pauseControlWorker(200L);
                    accepted = writeControlValue(source, definition, target);
                }
                postCommandResult(listener, accepted,
                        accepted ? null : "ECARX отклонил одноразовую команду");
                return;
            }

            // This firmware occasionally acknowledges a Binder write without applying it.
            // Follow the proven MConfig policy: re-read and re-send up to six times instead of
            // forcing the driver to tap the tile repeatedly.
            boolean acceptedAtLeastOnce = false;
            for (int attempt = 0; attempt < CONTROL_WRITE_ATTEMPTS; attempt++) {
                Double beforeWrite = attempt == 0 ? current : readControlValue(source, definition);
                if (beforeWrite != null && sameValue(beforeWrite, target)) {
                    deliverControlState(normalizeControlState(definition, beforeWrite));
                    postCommandResult(listener, true, null);
                    return;
                }
                acceptedAtLeastOnce |= writeControlValue(source, definition, target);
                if (!pauseControlWorker(200L + (attempt % 2) * 100L)) break;
                Double confirmed = readControlValue(source, definition);
                if (confirmed != null && sameValue(confirmed, target)) {
                    deliverControlState(normalizeControlState(definition, confirmed));
                    postCommandResult(listener, true, null);
                    return;
                }
            }
            postCommandResult(listener, false, acceptedAtLeastOnce
                    ? "Команда отправлена, но автомобиль её не подтвердил"
                    : "ECARX отклонил команду");
        } catch (Throwable t) {
            invalidateFunctionProxy(source);
            scheduleControlRetry();
            Log.w(TAG, "vehicle command failed for " + command.controlId, t);
            postCommandResult(listener, false, "Ошибка связи с ECARX");
        }
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

    private static boolean pauseControlWorker(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
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
            return isValidControlValue(definition, value) ? value : null;
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
        try {
            int[] values = definition.zoned()
                    ? source.getSupportedFunctionValue(definition.functionId, definition.zone)
                    : source.getSupportedFunctionValue(definition.functionId);
            if (values == null || values.length == 0) return definition.descriptor.options;
            Set<Integer> supported = new HashSet<>();
            for (int value : values) supported.add(value);
            List<CarControlDescriptor.Option> result = new ArrayList<>();
            for (CarControlDescriptor.Option option : definition.descriptor.options) {
                if (option.value == 0 || supported.contains((int) Math.round(option.value))) {
                    result.add(option);
                }
            }
            return result.isEmpty() ? definition.descriptor.options : result;
        } catch (Throwable ignored) {
            // Older firmware can reject discovery even though direct reads/writes work.
            return definition.descriptor.options;
        }
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

    private void postCommandResult(ControlCommandListener listener, boolean success,
                                   @Nullable String message) {
        mainHandler.post(() -> listener.onResult(success, message));
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
        List<ControlSubscription> controls;
        synchronized (controlsLock) {
            controls = new ArrayList<>(controlSubscriptions.values());
            controlSubscriptions.clear();
        }
        for (ControlSubscription subscription : controls) subscription.cancelled.set(true);
        mainHandler.removeCallbacks(controlRefreshTask);
        executeTelemetryTask(() -> {
            for (TelemetrySubscription subscription : telemetry) {
                unregisterTelemetryVendorListeners(subscription);
            }
            detachControlWatcher();
        });
        telemetryWorker.shutdown();
        availabilityChangedListener = null;
    }
}
