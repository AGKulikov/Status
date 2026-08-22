/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package dezz.status.widget.car;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import dezz.status.widget.BrickType;

/**
 * Adds the finite Passenger 1.14.0 reading-light and fragrance controls to the public AdaptAPI
 * catalog. Raw method names are contained here; callers still see only fixed, capability-gated
 * descriptors and can never send a reflective method name over BLE.
 */
final class GeelyPassengerControlIntegration implements CarIntegration {
    private static final String TAG = "NatroPassengerControls";
    private static final String[] RAW_IDS = {
            "comfort.reading_lamp_front_left", "comfort.reading_lamp_front_right",
            "comfort.reading_lamp_rear_left", "comfort.reading_lamp_rear_right",
            "comfort.reading_lamps_all", "comfort.fragrance_level"
    };
    private static final String[] LAMP_READ = {
            "getPA_ReadLightFrontLeft", "getPA_ReadLightFrontRight",
            "getPA_ReadLightSecondRowLeft", "getPA_ReadLightSecondRowRight"
    };
    private static final String[] LAMP_WRITE = {
            "CB_ReadLightFrontLeft", "CB_ReadLightFrontRight",
            "CB_ReadLightSecondRowLeft", "CB_ReadLightSecondRowRight"
    };
    private static final List<CarControlDescriptor.Option> SWITCH = Arrays.asList(
            option(0, "Выкл"), option(1, "Вкл"));
    private static final List<CarControlDescriptor.Option> FRAGRANCE = Arrays.asList(
            option(0, "Выкл"), option(1, "Слабый"), option(2, "Средний"),
            option(3, "Сильный"));

    private final CarIntegration delegate;
    private final RawBridge raw;
    @Nullable private Runnable availabilityListener;

    GeelyPassengerControlIntegration(@NonNull Context context,
                                     @NonNull CarIntegration delegate) {
        this.delegate = delegate;
        raw = new RawBridge(context.getApplicationContext(), this::rawAvailabilityChanged);
        raw.start();
    }

    private static CarControlDescriptor.Option option(double value, String label) {
        return new CarControlDescriptor.Option(value, label);
    }

    private static boolean rawId(String id) {
        for (String candidate : RAW_IDS) if (candidate.equals(id)) return true;
        return false;
    }

    private void rawAvailabilityChanged() {
        Runnable listener = availabilityListener;
        if (listener != null) listener.run();
    }

    @Override public boolean isBrickSupported(@NonNull BrickType type) {
        return delegate.isBrickSupported(type);
    }

    @Override public void setAvailabilityChangedListener(@Nullable Runnable listener) {
        availabilityListener = listener;
        delegate.setAvailabilityChangedListener(listener);
    }

    @Override public void subscribe(@NonNull BrickType type, @NonNull ValueListener listener) {
        delegate.subscribe(type, listener);
    }

    @Override public void unsubscribe(@NonNull BrickType type) { delegate.unsubscribe(type); }

    @Override public void subscribeTelemetry(@NonNull Set<String> ids,
                                             @NonNull TelemetryListener listener) {
        delegate.subscribeTelemetry(ids, listener);
    }

    @Override public void unsubscribeTelemetry(@NonNull TelemetryListener listener) {
        delegate.unsubscribeTelemetry(listener);
    }

    @Override public void requestTelemetryCatalog(@NonNull TelemetryCatalogListener listener) {
        delegate.requestTelemetryCatalog(listener);
    }

    @Override public void requestDiagnostics(@NonNull DiagnosticsListener listener) {
        delegate.requestDiagnostics(listener);
    }

    @Override public void requestControlCatalog(@NonNull ControlCatalogListener listener) {
        delegate.requestControlCatalog(base -> {
            ArrayList<CarControlDescriptor> merged = new ArrayList<>(base);
            merged.addAll(raw.descriptors());
            listener.onCatalog(Collections.unmodifiableList(merged));
        });
    }

    @Override public void subscribeControlStates(@NonNull Set<String> ids,
                                                 @NonNull ControlStateListener listener) {
        LinkedHashSet<String> standard = new LinkedHashSet<>();
        LinkedHashSet<String> passenger = new LinkedHashSet<>();
        for (String id : ids) (rawId(id) ? passenger : standard).add(id);
        delegate.subscribeControlStates(standard, listener);
        raw.subscribe(passenger, listener);
    }

    @Override public void unsubscribeControlStates(@NonNull ControlStateListener listener) {
        delegate.unsubscribeControlStates(listener);
        raw.unsubscribe(listener);
    }

    @Override public void executeControl(@NonNull CarControlCommand command,
                                         @NonNull ControlCommandListener listener) {
        if (rawId(command.controlId)) raw.execute(command, listener);
        else delegate.executeControl(command, listener);
    }

    @Override public void setStockHudCarHidden(boolean hidden,
                                               @NonNull ControlCommandListener listener) {
        delegate.setStockHudCarHidden(hidden, listener);
    }

    @Override public void setStockHudProfileMode(int mode, boolean repeat,
                                                 @NonNull ControlCommandListener listener) {
        delegate.setStockHudProfileMode(mode, repeat, listener);
    }

    @Override public void stopStockHudProfileModeAutoRepeat(
            @NonNull ControlCommandListener listener) {
        delegate.stopStockHudProfileModeAutoRepeat(listener);
    }

    @Override public void setStockHudDisplayCategory(@NonNull StockHudDisplayCategory category,
                                                     boolean enabled,
                                                     @NonNull ControlCommandListener listener) {
        delegate.setStockHudDisplayCategory(category, enabled, listener);
    }

    @Override public void shutdown() {
        raw.stop();
        delegate.shutdown();
    }

    private static final class RawBridge {
        private final Context context;
        private final Handler main = new Handler(Looper.getMainLooper());
        private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(
                task -> {
                    Thread thread = new Thread(task, "natro-passenger-controls");
                    thread.setDaemon(true);
                    return thread;
                });
        private final Map<ControlStateListener, Set<String>> subscribers =
                new IdentityHashMap<>();
        private final Map<String, CarControlState> states = new LinkedHashMap<>();
        private final Runnable availabilityChanged;
        @Nullable private volatile Object rawCar;
        @Nullable private volatile Object ambience;
        @Nullable private volatile Object air;
        private volatile boolean stopped;

        RawBridge(Context context, Runnable availabilityChanged) {
            this.context = context;
            this.availabilityChanged = availabilityChanged;
        }

        void start() {
            worker.execute(this::connect);
            worker.scheduleWithFixedDelay(this::pollSafely, 1, 2, TimeUnit.SECONDS);
        }

        void stop() {
            stopped = true;
            worker.shutdownNow();
            Object car = rawCar;
            rawCar = null;
            ambience = null;
            air = null;
            if (car != null) try { car.getClass().getMethod("disconnect").invoke(car); }
            catch (Throwable ignored) { }
            synchronized (subscribers) { subscribers.clear(); }
        }

        List<CarControlDescriptor> descriptors() {
            ArrayList<CarControlDescriptor> result = new ArrayList<>();
            String[] labels = {"Лампа спереди слева", "Лампа спереди справа",
                    "Лампа сзади слева", "Лампа сзади справа", "Все лампы",
                    "Ароматизатор"};
            for (int i = 0; i < RAW_IDS.length; i++) {
                CarControlState state;
                synchronized (states) { state = states.get(RAW_IDS[i]); }
                // The PA service commonly connects after the first catalog request. Keep a
                // disconnected entry UNKNOWN so C5 subscribes and can receive the later
                // capability result instead of freezing it unavailable for the whole session.
                CarControlDescriptor.Availability availability = state != null && state.available
                        ? CarControlDescriptor.Availability.SUPPORTED
                        : CarControlDescriptor.Availability.UNKNOWN;
                boolean fragrance = i == RAW_IDS.length - 1;
                result.add(new CarControlDescriptor(RAW_IDS[i], labels[i], "Комфорт",
                        fragrance ? "fragrance" : "reading_light",
                        fragrance ? CarControlDescriptor.Kind.LEVELS
                                : CarControlDescriptor.Kind.TOGGLE,
                        availability, fragrance ? FRAGRANCE : SWITCH,
                        0, fragrance ? 3 : 1, 1, "", "#FFFFC857"));
            }
            return result;
        }

        void subscribe(Set<String> ids, ControlStateListener listener) {
            synchronized (subscribers) {
                if (ids.isEmpty()) subscribers.remove(listener);
                else subscribers.put(listener, Collections.unmodifiableSet(
                        new LinkedHashSet<>(ids)));
            }
            main.post(() -> deliverCached(ids, listener));
            worker.execute(this::pollSafely);
        }

        void unsubscribe(ControlStateListener listener) {
            synchronized (subscribers) { subscribers.remove(listener); }
        }

        void execute(CarControlCommand command, ControlCommandListener listener) {
            worker.execute(() -> {
                try {
                    double current = 0;
                    synchronized (states) {
                        CarControlState state = states.get(command.controlId);
                        if (state != null && state.known) current = state.value;
                    }
                    int target = target(command, (int) Math.round(current));
                    write(command.controlId, target);
                    main.post(() -> listener.onResult(true, null));
                    worker.schedule(this::pollSafely, 180, TimeUnit.MILLISECONDS);
                } catch (Throwable error) {
                    Log.w(TAG, "raw passenger command failed for " + command.controlId, error);
                    main.post(() -> listener.onResult(false,
                            "Дополнительный ECARX отклонил команду"));
                }
            });
        }

        private static int target(CarControlCommand command, int current) {
            int maximum = "comfort.fragrance_level".equals(command.controlId) ? 3 : 1;
            switch (command.operation) {
                case SET: return Math.max(0, Math.min(maximum, (int) Math.round(command.value)));
                case TOGGLE: return current == 0 ? 1 : 0;
                case CYCLE: return (current + 1) % (maximum + 1);
                case ACTIVATE: return 1;
                default: throw new IllegalArgumentException("operation");
            }
        }

        private void write(String id, int target) throws Exception {
            int lamp = -1;
            for (int i = 0; i < 4; i++) if (RAW_IDS[i].equals(id)) lamp = i;
            if (lamp >= 0) {
                invokeInt(ambience, LAMP_WRITE[lamp], target == 0 ? 2 : 1);
            } else if (RAW_IDS[4].equals(id)) {
                invokeInt(ambience, "CB_ReadLightAllOnSwitch", target == 0 ? 0 : 1);
            } else if (RAW_IDS[5].equals(id)) {
                invokeInt(air, "CB_Fragra_LvlReq", target);
            } else {
                throw new IllegalArgumentException("unknown raw control");
            }
        }

        private void connect() {
            if (stopped || rawCar != null) return;
            try {
                Class<?> type = Class.forName("ecarx.car.ECarXCar", true,
                        context.getClassLoader());
                ServiceConnection connection = new ServiceConnection() {
                    @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                        worker.execute(RawBridge.this::acceptConnection);
                    }
                    @Override public void onServiceDisconnected(ComponentName name) {
                        ambience = null;
                        air = null;
                        rawCar = null;
                        publishUnavailable();
                        if (!stopped) worker.schedule(RawBridge.this::connect,
                                1, TimeUnit.SECONDS);
                    }
                };
                Object created = type.getMethod("createCar", Context.class,
                        ServiceConnection.class).invoke(null, context, connection);
                rawCar = created;
                if (created != null) created.getClass().getMethod("connect").invoke(created);
            } catch (Throwable error) {
                Log.d(TAG, "raw Passenger ECARX is not available yet", error);
                rawCar = null;
                publishUnavailable();
                if (!stopped) worker.schedule(this::connect, 2, TimeUnit.SECONDS);
            }
        }

        private void acceptConnection() {
            Object car = rawCar;
            if (stopped || car == null) return;
            try {
                Object pa = car.getClass().getMethod("getCarManager", String.class)
                        .invoke(car, "car_publicattribute");
                ambience = pa.getClass().getMethod("getECarXCarAmbliManager").invoke(pa);
                air = pa.getClass().getMethod("getECarXCarAirqlyandfragraManager").invoke(pa);
                pollSafely();
                main.post(availabilityChanged);
            } catch (Throwable error) {
                Log.w(TAG, "raw Passenger manager lookup failed", error);
                publishUnavailable();
            }
        }

        private void pollSafely() {
            if (stopped) return;
            Object lights = ambience;
            if (lights == null) { publishUnavailable(); return; }
            try {
                long now = System.currentTimeMillis();
                for (int i = 0; i < 4; i++) {
                    PaValue value = readPa(lights, LAMP_READ[i]);
                    publish(new CarControlState(RAW_IDS[i], value.supported,
                            value.supported && (value.data == 1 || value.data == 2),
                            value.data == 1 ? 1 : 0,
                            value.data == 1 ? "Вкл" : "Выкл", value.data == 1,
                            value.data == 1 ? 1 : 0, "#FFFFC857", now));
                }
                PaValue all = readOptionalPa(lights, "getPA_ReadLightAllOnSwitch");
                publish(new CarControlState(RAW_IDS[4], all.supported,
                        all.supported && (all.data == 0 || all.data == 1), all.data,
                        all.data == 1 ? "Вкл" : "Выкл", all.data == 1,
                        all.data == 1 ? 1 : 0, "#FFFFC857", now));
                PaValue scent = readOptionalPa(air, "getPA_Fragra_LvlReqSts");
                boolean known = scent.supported && scent.data >= 0 && scent.data <= 3;
                String[] labels = {"Выкл", "Слабый", "Средний", "Сильный"};
                publish(new CarControlState(RAW_IDS[5], scent.supported, known,
                        known ? scent.data : 0, known ? labels[scent.data] : "Неизвестно",
                        known && scent.data > 0, known ? scent.data : 0,
                        "#FFFFC857", now));
            } catch (Throwable error) {
                Log.d(TAG, "raw Passenger state read failed", error);
                publishUnavailable();
            }
        }

        private void publishUnavailable() {
            long now = System.currentTimeMillis();
            for (String id : RAW_IDS) publish(new CarControlState(id, false, false,
                    Double.NaN, "Дополнительный ECARX недоступен", false, 0, null, now));
        }

        private void publish(CarControlState state) {
            synchronized (states) { states.put(state.controlId, state); }
            main.post(() -> {
                Map<ControlStateListener, Set<String>> copy;
                synchronized (subscribers) { copy = new IdentityHashMap<>(subscribers); }
                for (Map.Entry<ControlStateListener, Set<String>> entry : copy.entrySet()) {
                    if (entry.getValue().contains(state.controlId)) {
                        entry.getKey().onControlState(state);
                    }
                }
            });
        }

        private void deliverCached(Set<String> ids, ControlStateListener listener) {
            synchronized (states) {
                for (String id : ids) {
                    CarControlState state = states.get(id);
                    if (state != null) listener.onControlState(state);
                }
            }
        }

        private static void invokeInt(Object manager, String name, int value) throws Exception {
            if (manager == null) throw new IllegalStateException("manager unavailable");
            Method method = manager.getClass().getMethod(name, Integer.TYPE);
            Object result = method.invoke(manager, value);
            if (method.getReturnType() != Void.TYPE && !apiSucceeded(result)) {
                throw new IllegalStateException(name + " rejected");
            }
        }

        private static boolean apiSucceeded(Object value) {
            if (value instanceof Number) return ((Number) value).intValue() == 0;
            if (value instanceof Enum) return "SUCCEED".equals(((Enum<?>) value).name());
            return value != null && "SUCCEED".equals(String.valueOf(value));
        }

        private static PaValue readOptionalPa(Object manager, String name) {
            if (manager == null) return new PaValue(false, -1);
            try { return readPa(manager, name); }
            catch (Throwable ignored) { return new PaValue(false, -1); }
        }

        private static PaValue readPa(Object manager, String name) throws Exception {
            Object property = manager.getClass().getMethod(name).invoke(manager);
            if (property == null) return new PaValue(false, -1);
            Object availability = property.getClass().getMethod("getAvailability").invoke(property);
            Object data = property.getClass().getMethod("getData").invoke(property);
            int available = availability instanceof Number ? ((Number) availability).intValue() : -1;
            int raw = data instanceof Number ? ((Number) data).intValue() : -1;
            return new PaValue(available == 1 || available == 2, raw);
        }

        private static final class PaValue {
            final boolean supported;
            final int data;
            PaValue(boolean supported, int data) { this.supported = supported; this.data = data; }
        }
    }
}
