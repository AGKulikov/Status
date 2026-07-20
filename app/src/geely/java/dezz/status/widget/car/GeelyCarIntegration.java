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
import java.util.List;
import java.util.EnumMap;
import java.util.Map;
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
 *   <li>a bounded status poll re-checks sensor support after startup and fires the
 *       availability-changed callback when the answer flips, letting the widget re-evaluate
 *       brick visibility (see {@link #setAvailabilityChangedListener}).</li>
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

    /** How often and for how long to re-check sensor support while the platform service boots. */
    private static final long AVAILABILITY_POLL_INTERVAL_MS = 2_000L;
    private static final int AVAILABILITY_POLL_MAX_ATTEMPTS = 30;   // 60s total

    private final Context appContext;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Map<BrickType, Subscription> subscriptions = new EnumMap<>(BrickType.class);

    @Nullable
    private ISensor sensors;
    @Nullable
    private ICar carApi;
    private boolean sensorsResolveAttempted = false;

    @Nullable
    private Runnable availabilityChangedListener;
    private int availabilityPollAttempts = 0;
    private boolean availabilityPollScheduled = false;

    /** Pairs the vendor listener with a cancellation flag so queued main-thread deliveries of an
     *  already-unsubscribed listener can be dropped instead of overwriting the placeholder. */
    private static final class Subscription {
        final ISensor.ISensorListener sensorListener;
        final AtomicBoolean cancelled;

        Subscription(ISensor.ISensorListener sensorListener, AtomicBoolean cancelled) {
            this.sensorListener = sensorListener;
            this.cancelled = cancelled;
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
        if (Float.isNaN(celsius) || celsius == Float.MIN_VALUE) return false;
        return celsius >= MIN_PLAUSIBLE_TEMPERATURE_C && celsius <= MAX_PLAUSIBLE_TEMPERATURE_C;
    }

    /** Resolve the AdaptAPI sensor service once; on any failure stay null (unsupported). */
    @Nullable
    private synchronized ISensor ensureSensors() {
        if (!sensorsResolveAttempted) {
            sensorsResolveAttempted = true;
            try {
                carApi = Car.create(appContext);
                sensors = carApi.getSensorManager();
            } catch (Throwable t) {
                Log.w(TAG, "eCarX sensor manager unavailable", t);
            }
        }
        return sensors;
    }

    @Override
    public void requestDiagnostics(@NonNull DiagnosticsListener listener) {
        // Binder reads may block while the vehicle service wakes, so never run them on the UI.
        new Thread(() -> {
            List<CarDiagnosticValue> values = new ArrayList<>();
            ISensor s = ensureSensors();
            if (s == null) {
                values.add(new CarDiagnosticValue("ecarx.sensor_manager", "eCarX sensor manager",
                        "unavailable", "—", "Vendor service did not connect"));
                mainHandler.post(() -> listener.onDiagnostics(values));
                return;
            }

            addSensor(values, s, "fuel_level", "Остаток топлива — raw",
                    ISensor.SENSOR_TYPE_FUEL_LEVEL,
                    "Единица AdaptAPI не указана: это ещё не подтверждённые литры");
            addCarInfo(values, "fuel_capacity", "Объём бака — raw",
                    ICarInfo.FLT_INFO_FUEL_CAPACITY,
                    "Единица AdaptAPI не указана; нужна сверка на автомобиле");
            addSensor(values, s, "range_fuel", "Запас хода на топливе — raw",
                    ISensor.SENSOR_TYPE_ENDURANCE_MILEAGE_FUEL, "Единица SDK, вероятно расстояние");
            addSensor(values, s, "range_total", "Общий запас хода — raw",
                    ISensor.SENSOR_TYPE_ENDURANCE_MILEAGE, "Единица SDK, вероятно расстояние");
            addSensor(values, s, "odometer", "Одометр — raw",
                    ISensor.SENSOR_TYPE_ODOMETER, "Единица SDK не нормализуется");
            addSensor(values, s, "speed", "Скорость — raw",
                    ISensor.SENSOR_TYPE_CAR_SPEED, "Единица SDK не нормализуется");
            addSensor(values, s, "rpm", "Обороты двигателя — raw",
                    ISensor.SENSOR_TYPE_RPM, "raw");
            addSensor(values, s, "coolant_temp", "Температура ОЖ — raw",
                    ISensor.SENSOR_TYPE_ENGINE_COOLANT_TEMPERATURE, "raw");
            addSensor(values, s, "coolant_level", "Уровень ОЖ — raw",
                    ISensor.SENSOR_TYPE_ENGINE_COOLANT_LEVEL, "raw");
            addSensor(values, s, "engine_oil_level", "Уровень масла — raw",
                    ISensor.SENSOR_TYPE_ENGINE_OIL_LEVEL, "raw");
            addSensor(values, s, "indoor_temp", "Температура салона",
                    ISensor.SENSOR_TYPE_TEMPERATURE_INDOOR, "°C (источник штатного кирпичика)");
            addSensor(values, s, "ambient_temp", "Наружная температура",
                    ISensor.SENSOR_TYPE_TEMPERATURE_AMBIENT, "°C (источник штатного кирпичика)");
            addSensor(values, s, "vehicle_weight", "Масса автомобиля — raw",
                    ISensor.SENSOR_TYPE_VEHICLE_WEIGHT, "raw");
            addSensor(values, s, "ev_battery_level", "Заряд тяговой батареи — raw",
                    ISensor.SENSOR_TYPE_EV_BATTERY_LEVEL, "raw");
            mainHandler.post(() -> listener.onDiagnostics(values));
        }, "ecarx-diagnostics").start();
    }

    private void addSensor(List<CarDiagnosticValue> out, ISensor s, String id, String label,
                           int sensorType, String unitNote) {
        FunctionStatus status;
        try {
            status = s.isSensorSupported(sensorType);
        } catch (Throwable t) {
            out.add(new CarDiagnosticValue("ISensor." + id, label, "error",
                    t.getClass().getSimpleName(), unitNote));
            return;
        }
        String raw = "—";
        if (status == FunctionStatus.active || status == FunctionStatus.notactive) {
            try {
                raw = Float.toString(s.getSensorLatestValue(sensorType));
            } catch (Throwable t) {
                raw = "error: " + t.getClass().getSimpleName();
            }
        }
        out.add(new CarDiagnosticValue("ISensor." + id, label, String.valueOf(status), raw,
                unitNote + "; signal=" + sensorType));
    }

    private void addCarInfo(List<CarDiagnosticValue> out, String id, String label,
                            int infoType, String unitNote) {
        ICarInfo info = null;
        try {
            if (carApi == null) carApi = Car.create(appContext);
            info = carApi.getCarInfoManager();
            FunctionStatus status = info.isCarInfoSupported(infoType);
            String raw = (status == FunctionStatus.active || status == FunctionStatus.notactive)
                    ? Float.toString(info.getCarInfoFloat(infoType)) : "—";
            out.add(new CarDiagnosticValue("ICarInfo." + id, label, String.valueOf(status), raw,
                    unitNote + "; info=" + infoType));
        } catch (Throwable t) {
            out.add(new CarDiagnosticValue("ICarInfo." + id, label, "error",
                    t.getClass().getSimpleName(), unitNote));
        }
    }

    @Nullable
    private FunctionStatus sensorSupportStatus(int sensorType) {
        ISensor s = ensureSensors();
        if (s == null) return null;
        try {
            return s.isSensorSupported(sensorType);
        } catch (Throwable t) {
            Log.w(TAG, "isSensorSupported failed for sensor " + sensorType, t);
            return null;
        }
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
        boolean supported = status == FunctionStatus.active || status == FunctionStatus.notactive;
        if (!supported && status == FunctionStatus.error) {
            scheduleAvailabilityPoll();
        }
        return supported;
    }

    @Override
    public void setAvailabilityChangedListener(@Nullable Runnable listener) {
        availabilityChangedListener = listener;
    }

    /**
     * Bounded re-check loop for the boot window where the platform service hasn't connected yet
     * and every support query returns {@code error}. Fires the availability callback as soon as
     * any car sensor reports a definitive status, then stops.
     */
    private void scheduleAvailabilityPoll() {
        if (availabilityPollScheduled || availabilityPollAttempts >= AVAILABILITY_POLL_MAX_ATTEMPTS) {
            return;
        }
        availabilityPollScheduled = true;
        mainHandler.postDelayed(() -> {
            availabilityPollScheduled = false;
            availabilityPollAttempts++;
            boolean anyDefinitive = false;
            for (BrickType type : BrickType.values()) {
                int sensorType = sensorTypeFor(type);
                if (sensorType == 0) continue;
                FunctionStatus status = sensorSupportStatus(sensorType);
                if (status != null && status != FunctionStatus.error) {
                    anyDefinitive = true;
                    break;
                }
            }
            if (anyDefinitive) {
                availabilityPollAttempts = AVAILABILITY_POLL_MAX_ATTEMPTS; // done for good
                if (availabilityChangedListener != null) {
                    availabilityChangedListener.run();
                }
            } else {
                scheduleAvailabilityPoll();
            }
        }, AVAILABILITY_POLL_INTERVAL_MS);
    }

    @Override
    public void subscribe(@NonNull BrickType type, @NonNull ValueListener listener) {
        int sensorType = sensorTypeFor(type);
        if (sensorType == 0) return;
        ISensor s = ensureSensors();
        if (s == null) return;

        Subscription previous = subscriptions.get(type);

        // The listener closes over its own cancellation flag (not a map lookup — the vendor
        // callback arrives on a binder thread and the map is main-thread-only). The gate drops
        // deliveries already queued to the main handler when unsubscribe() wins the race —
        // otherwise a stale reading would overwrite the placeholder reset and resurface when
        // the brick is re-added later.
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        Subscription subscription = new Subscription(new ISensor.ISensorListener() {
            @Override
            public void onSensorEventChanged(int changedType, int value) {
            }

            @Override
            public void onSensorSupportChanged(int changedType, FunctionStatus status) {
            }

            @Override
            public void onSensorValueChanged(int changedType, float value) {
                if (changedType != sensorType || !isPlausibleTemperature(value)) return;
                // AdaptAPI delivers on a binder thread; the contract is main-thread delivery.
                mainHandler.post(() -> {
                    if (cancelled.get()) return;
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
                return;
            }
        } catch (Throwable t) {
            Log.w(TAG, "registerListener failed for " + type
                    + (previous != null ? " — keeping previous subscription" : ""), t);
            return;
        }
        if (previous != null) {
            previous.cancelled.set(true);
            try {
                s.unregisterListener(previous.sensorListener);
            } catch (Throwable t) {
                Log.w(TAG, "unregisterListener (replace) failed for " + type, t);
            }
        }
        subscriptions.put(type, subscription);

        // Seed with the latest cached value so the brick shows a temperature immediately
        // instead of a placeholder until the sensor's next change event (which for slow-moving
        // ambient temperature can be minutes away).
        try {
            float latest = s.getSensorLatestValue(sensorType);
            if (isPlausibleTemperature(latest)) {
                mainHandler.post(() -> {
                    if (cancelled.get()) return;
                    listener.onValue(type, latest);
                });
            }
        } catch (Throwable t) {
            Log.w(TAG, "getSensorLatestValue failed for " + type, t);
        }
    }

    @Override
    public void unsubscribe(@NonNull BrickType type) {
        Subscription subscription = subscriptions.remove(type);
        if (subscription == null) return;
        subscription.cancelled.set(true);
        try {
            if (sensors != null) sensors.unregisterListener(subscription.sensorListener);
        } catch (Throwable t) {
            Log.w(TAG, "unregisterListener failed for " + type, t);
        }
    }

    @Override
    public void shutdown() {
        for (BrickType type : BrickType.values()) {
            unsubscribe(type);
        }
        availabilityChangedListener = null;
    }
}
