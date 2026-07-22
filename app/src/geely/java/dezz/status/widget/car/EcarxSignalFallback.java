/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.car;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Optional low-level ECARX signal channel used as a fallback for gear and high-beam telemetry.
 *
 * <p>The classes behind this API are absent on some firmware and are intentionally referenced by
 * name only. Every operation is guarded, performed off the UI thread, and failure leaves the
 * existing AdaptAPI listener/polling path untouched.</p>
 */
final class EcarxSignalFallback {
    private static final String TAG = "EcarxSignalFallback";
    private static final int FAST_RETRY_COUNT = 25;
    private static final long FAST_RETRY_MILLIS = 800L;
    private static final long SLOW_RETRY_MILLIS = 30_000L;
    private static final long HEALTH_READ_MILLIS = 1_000L;

    interface Listener {
        void onGear(int adaptGear, int actualGear, boolean manualMode);
        void onHighBeam(int enabled);
        void onChannelLost();
    }

    private final Context appContext;
    private final Listener listener;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(
            runnable -> {
                Thread thread = new Thread(runnable, "ecarx-signal-fallback");
                thread.setDaemon(true);
                return thread;
            });

    private volatile boolean gearDemand;
    private volatile boolean highBeamDemand;
    private volatile boolean closed;

    /** Worker-thread-owned reflection state. */
    @Nullable private Object ecarxProxy;
    @Nullable private Object proxyMethodCallback;
    @Nullable private Object directEcarxCar;
    @Nullable private Object signalManager;
    @Nullable private Object signalCallback;
    private final Set<Integer> manualModeIds = new LinkedHashSet<>();
    private final Set<Integer> highBeamIds = new LinkedHashSet<>();
    private final Map<Integer, String> propertyNames = new HashMap<>();
    @Nullable private Integer selectorRaw;
    @Nullable private Integer actualGearRaw;
    @Nullable private Boolean manualMode;
    private int retryAttempts;
    private boolean retryScheduled;
    private boolean highBeamDiscoveryRetryScheduled;

    EcarxSignalFallback(@NonNull Context context, @NonNull Listener listener) {
        this.appContext = context.getApplicationContext();
        this.listener = listener;
    }

    void updateDemand(boolean needsGear, boolean needsHighBeam) {
        if (closed || (gearDemand == needsGear && highBeamDemand == needsHighBeam)) return;
        gearDemand = needsGear;
        highBeamDemand = needsHighBeam;
        execute(() -> {
            listener.onChannelLost();
            unregisterCallback();
            selectorRaw = null;
            actualGearRaw = null;
            manualMode = null;
            retryAttempts = 0;
            retryScheduled = false;
            highBeamDiscoveryRetryScheduled = false;
            if (hasDemand()) {
                connectAndRegister();
            } else {
                signalManager = null;
                releaseProxy();
            }
        });
    }

    void shutdown() {
        closed = true;
        gearDemand = false;
        highBeamDemand = false;
        execute(() -> {
            unregisterCallback();
            releaseProxy();
        });
        worker.shutdown();
    }

    private boolean hasDemand() {
        return !closed && (gearDemand || highBeamDemand);
    }

    private void connectAndRegister() {
        retryScheduled = false;
        if (!hasDemand() || signalCallback != null) return;
        try {
            Object manager = resolveSignalManager();
            if (manager == null) {
                scheduleRetry();
                return;
            }
            signalManager = manager;
            scanPropertyIds();
            if (!registerCallback(manager)) {
                signalManager = null;
                if (gearDemand || !highBeamIds.isEmpty()) releaseProxy();
                scheduleRetry();
                return;
            }
            retryAttempts = 0;
            readCurrentValues(manager);
            scheduleHealthRead(manager, signalCallback);
            if (highBeamDemand && highBeamIds.isEmpty()) scheduleHighBeamDiscoveryRetry();
        } catch (Throwable error) {
            Log.d(TAG, "Low-level ECARX signal registration unavailable", error);
            unregisterCallback();
            signalManager = null;
            releaseProxy();
            scheduleRetry();
        }
    }

    private void scheduleRetry() {
        if (!hasDemand() || retryScheduled) return;
        retryScheduled = true;
        long delay = retryAttempts++ < FAST_RETRY_COUNT
                ? FAST_RETRY_MILLIS : SLOW_RETRY_MILLIS;
        try {
            worker.schedule(this::connectAndRegister, delay, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ignored) {
            retryScheduled = false;
        }
    }

    /** PropertyIdString can be populated after the car service finishes booting. */
    private void scheduleHighBeamDiscoveryRetry() {
        if (!hasDemand() || !highBeamDemand || !highBeamIds.isEmpty()
                || highBeamDiscoveryRetryScheduled) return;
        highBeamDiscoveryRetryScheduled = true;
        try {
            worker.schedule(() -> {
                highBeamDiscoveryRetryScheduled = false;
                if (!hasDemand() || !highBeamDemand) return;
                scanPropertyIds();
                if (highBeamIds.isEmpty()) {
                    scheduleHighBeamDiscoveryRetry();
                    return;
                }
                unregisterCallback();
                connectAndRegister();
            }, SLOW_RETRY_MILLIS, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ignored) {
            highBeamDiscoveryRetryScheduled = false;
        }
    }

    @Nullable
    private Object resolveSignalManager() {
        if (signalManager != null) return signalManager;
        Object manager = managerFromProxy();
        if (manager != null || ecarxProxy != null) return manager;
        return managerFromEcarxCar();
    }

    @Nullable
    private Object managerFromProxy() {
        try {
            if (ecarxProxy == null) {
                Class<?> proxyClass = Class.forName("com.ecarx.xui.adaptapi.ECarXCarProxy");
                Class<?> proxyMethod = findNestedClass(proxyClass, "ECarXCarProxyMethod");
                if (proxyMethod == null) return null;
                Object[] callbackHolder = new Object[1];
                Object callback = Proxy.newProxyInstance(proxyMethod.getClassLoader(),
                        new Class<?>[] { proxyMethod }, (proxy, method, args) -> {
                            String name = method.getName();
                            if (name.equals("toString")) return "StatusWidgetECarXProxyCallback";
                            if (name.equals("hashCode")) return System.identityHashCode(proxy);
                            if (name.equals("equals")) return args != null && args.length == 1
                                    && proxy == args[0];
                            if (name.equals("onECarXCarServiceConnected")) {
                                Object manager = args != null && args.length > 1 ? args[1] : null;
                                Object expectedCallback = callbackHolder[0];
                                if (manager != null && expectedCallback != null) {
                                    execute(() -> acceptConnectedManager(
                                            expectedCallback, manager));
                                }
                                return null;
                            }
                            if (name.equals("onECarXCarServiceDeath")) {
                                Object expectedCallback = callbackHolder[0];
                                if (expectedCallback != null) {
                                    execute(() -> recoverAfterServiceDeath(expectedCallback));
                                }
                                return null;
                            }
                            return primitiveDefault(method.getReturnType());
                        });
                callbackHolder[0] = callback;
                Constructor<?> constructor = findProxyConstructor(proxyClass, proxyMethod);
                if (constructor == null) return null;
                proxyMethodCallback = callback;
                ecarxProxy = constructor.newInstance(appContext, callback);
                invokeNoArgIfPresent(ecarxProxy, "initECarXCar");
            }
            Method getter = findMethod(ecarxProxy.getClass(), "getCarSignalManager", 0);
            return getter == null ? null : getter.invoke(ecarxProxy);
        } catch (Throwable error) {
            Log.d(TAG, "ECarXCarProxy path unavailable", error);
            releaseProxy();
            return null;
        }
    }

    /** Fallback used by mHUD when the AdaptAPI proxy class is not usable on a firmware build. */
    @Nullable
    private Object managerFromEcarxCar() {
        try {
            Class<?> rootClass = Class.forName("ecarx.car.ECarXCar");
            if (directEcarxCar == null) {
                directEcarxCar = rootClass.getConstructor(Context.class).newInstance(appContext);
                invokeNoArgIfPresent(directEcarxCar, "connect");
            }
            Object root = directEcarxCar;
            if (root == null) return null;
            String service = findSignalServiceName(rootClass);
            if (service == null) return null;
            Method getter = rootClass.getMethod("getCarManager", String.class);
            return getter.invoke(root, service);
        } catch (Throwable error) {
            Log.d(TAG, "ECarXCar.getCarManager fallback unavailable", error);
            return null;
        }
    }

    @Nullable
    private static Class<?> findNestedClass(Class<?> owner, String simpleName) {
        for (Class<?> candidate : owner.getClasses()) {
            if (candidate.getSimpleName().equals(simpleName)) return candidate;
        }
        try {
            return Class.forName(owner.getName() + "$" + simpleName);
        } catch (ClassNotFoundException ignored) {
            return null;
        }
    }

    @Nullable
    private static Constructor<?> findProxyConstructor(Class<?> proxyClass,
                                                       Class<?> proxyMethod) {
        for (Constructor<?> constructor : proxyClass.getConstructors()) {
            Class<?>[] parameters = constructor.getParameterTypes();
            if (parameters.length != 2 || !parameters[0].isAssignableFrom(Context.class)) {
                continue;
            }
            if (parameters[1].equals(proxyMethod)) return constructor;
        }
        return null;
    }

    @Nullable
    private static String findSignalServiceName(Class<?> rootClass) {
        try {
            Field exact = rootClass.getField("SIGNAL_SERVICE");
            Object value = exact.get(null);
            if (value instanceof String) return (String) value;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
        }
        for (Field field : rootClass.getFields()) {
            try {
                if (field.getType() == String.class && Modifier.isStatic(field.getModifiers())
                        && field.getName().toLowerCase(Locale.ROOT).contains("signal")) {
                    Object value = field.get(null);
                    if (value instanceof String) return (String) value;
                }
            } catch (IllegalAccessException | RuntimeException ignored) {
            }
        }
        return null;
    }

    @Nullable
    private static Method findMethod(Class<?> type, String name, int parameterCount) {
        for (Method method : type.getMethods()) {
            if (method.getName().equals(name)
                    && method.getParameterTypes().length == parameterCount) return method;
        }
        return null;
    }

    private static void invokeNoArgIfPresent(Object target, String methodName) {
        Method method = findMethod(target.getClass(), methodName, 0);
        if (method == null) return;
        try {
            method.invoke(target);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Some firmware exposes init but performs it automatically in the constructor.
        }
    }

    private void scanPropertyIds() {
        manualModeIds.clear();
        highBeamIds.clear();
        propertyNames.clear();
        Map<?, ?> names = propertyIdNames();
        for (Map.Entry<?, ?> entry : names.entrySet()) {
            Integer id = entry.getKey() instanceof Number
                    ? ((Number) entry.getKey()).intValue() : null;
            String name = entry.getValue() instanceof String ? (String) entry.getValue() : null;
            // A few vendor builds expose the inverse mapping.
            if (id == null && entry.getValue() instanceof Number
                    && entry.getKey() instanceof String) {
                id = ((Number) entry.getValue()).intValue();
                name = (String) entry.getKey();
            }
            if (id == null || name == null) continue;
            propertyNames.put(id, name);
            if (EcarxSignalDecoder.isManualModePropertyName(name)) manualModeIds.add(id);
            if (EcarxSignalDecoder.isHighBeamPropertyName(name)) highBeamIds.add(id);
        }
        Log.d(TAG, "Discovered manualMode=" + manualModeIds + ", highBeam=" + highBeamIds);
    }

    private static Map<?, ?> propertyIdNames() {
        try {
            Class<?> type = Class.forName("ecarx.car.hardware.property.PropertyIdString");
            Field field;
            try {
                field = type.getField("idToStringMap");
            } catch (NoSuchFieldException missingPublicField) {
                field = type.getDeclaredField("idToStringMap");
                field.setAccessible(true);
            }
            Object value = field.get(null);
            return value instanceof Map ? (Map<?, ?>) value : Collections.emptyMap();
        } catch (Throwable ignored) {
            return Collections.emptyMap();
        }
    }

    private boolean registerCallback(Object manager) throws Exception {
        LinkedHashSet<Integer> ids = new LinkedHashSet<>();
        if (gearDemand) {
            ids.add(EcarxSignalDecoder.PROPERTY_GEAR_ACTUAL);
            ids.add(EcarxSignalDecoder.PROPERTY_GEAR_SELECTOR);
            ids.addAll(manualModeIds);
        }
        if (highBeamDemand) ids.addAll(highBeamIds);
        // In a high-beam-only subscription an empty discovery result is not a successful
        // registration: retry while ecarxcar_service finishes publishing PropertyIdString.
        if (ids.isEmpty()) return false;

        Class<?> filterClass = Class.forName("ecarx.car.hardware.signal.SignalFilter");
        Object filter = filterClass.getDeclaredConstructor().newInstance();
        Method add = findIntMethod(filterClass, "add");
        if (add == null) return false;
        for (Integer id : ids) add.invoke(filter, id);

        Class<?> callbackClass = Class.forName(
                "ecarx.car.hardware.signal.CarSignalManager$CarSignalEventCallback");
        Object[] callbackHolder = new Object[1];
        InvocationHandler handler = (proxy, method, args) -> {
            String name = method.getName();
            if (name.equals("toString")) return "StatusWidgetCarSignalCallback";
            if (name.equals("hashCode")) return System.identityHashCode(proxy);
            if (name.equals("equals")) return args != null && args.length == 1
                    && proxy == args[0];
            if (name.equals("onChangeEvent")) {
                Object[] copy = args == null ? new Object[0] : args.clone();
                Object expectedCallback = callbackHolder[0];
                if (expectedCallback != null) {
                    execute(() -> handleCallbackArguments(expectedCallback, copy));
                }
                return null;
            }
            if (name.equals("onErrorEvent")) {
                Object expectedCallback = callbackHolder[0];
                if (expectedCallback != null) {
                    execute(() -> recoverAfterCallbackError(expectedCallback));
                }
                return null;
            }
            return primitiveDefault(method.getReturnType());
        };
        Object callback = Proxy.newProxyInstance(callbackClass.getClassLoader(),
                new Class<?>[] { callbackClass }, handler);
        callbackHolder[0] = callback;
        Registration registration = findRegistrationMethod(manager.getClass(), callback, filter);
        if (registration == null) return false;
        if (registration.callbackFirst) {
            registration.method.invoke(manager, callback, filter);
        } else {
            registration.method.invoke(manager, filter, callback);
        }
        signalCallback = callback;
        Log.d(TAG, "Registered low-level signal fallback for " + ids);
        return true;
    }

    @Nullable
    private static Method findIntMethod(Class<?> type, String name) {
        for (Method method : type.getMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if (method.getName().equals(name) && parameters.length == 1
                    && (parameters[0] == Integer.TYPE || parameters[0] == Integer.class)) {
                return method;
            }
        }
        return null;
    }

    @Nullable
    private static final class Registration {
        final Method method;
        final boolean callbackFirst;

        Registration(Method method, boolean callbackFirst) {
            this.method = method;
            this.callbackFirst = callbackFirst;
        }
    }

    @Nullable
    private static Registration findRegistrationMethod(Class<?> type, Object callback,
                                                       Object filter) {
        Method generic = null;
        for (Method method : type.getMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if (!method.getName().equals("registerCallback") || parameters.length != 2) continue;
            if (parameters[0].isInstance(callback) && parameters[1].isInstance(filter)) {
                return new Registration(method, true);
            }
            if (parameters[0].isInstance(filter) && parameters[1].isInstance(callback)) {
                return new Registration(method, false);
            }
            // mHUD falls back to the first two-argument overload on vendor builds whose
            // reflection metadata is too generic to pass the assignability check.
            if (generic == null) generic = method;
        }
        return generic == null ? null : new Registration(generic, true);
    }

    @Nullable
    private static Object primitiveDefault(Class<?> type) {
        if (!type.isPrimitive() || type == Void.TYPE) return null;
        if (type == Boolean.TYPE) return false;
        if (type == Character.TYPE) return '\0';
        if (type == Byte.TYPE) return (byte) 0;
        if (type == Short.TYPE) return (short) 0;
        if (type == Integer.TYPE) return 0;
        if (type == Long.TYPE) return 0L;
        if (type == Float.TYPE) return 0f;
        if (type == Double.TYPE) return 0d;
        return null;
    }

    private static final class ReadResult {
        final boolean readerAvailable;
        final boolean invocationSucceeded;

        ReadResult(boolean readerAvailable, boolean invocationSucceeded) {
            this.readerAvailable = readerAvailable;
            this.invocationSucceeded = invocationSucceeded;
        }
    }

    private ReadResult readCurrentValues(Object manager) {
        List<Integer> ids = new ArrayList<>();
        if (gearDemand) {
            ids.add(EcarxSignalDecoder.PROPERTY_GEAR_ACTUAL);
            ids.add(EcarxSignalDecoder.PROPERTY_GEAR_SELECTOR);
            ids.addAll(manualModeIds);
        }
        if (highBeamDemand) ids.addAll(highBeamIds);
        for (String methodName : new String[] {
                "getSignalValue", "getSignalLatestValue", "getCarPropertyValue", "getProperty"
        }) {
            Method reader = findIntMethod(manager.getClass(), methodName);
            if (reader == null) continue;
            boolean succeeded = false;
            for (Integer id : ids) {
                try {
                    Object value = reader.invoke(manager, id);
                    succeeded = true;
                    if (value != null) handleEvent(value, id);
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                }
            }
            return new ReadResult(true, succeeded);
        }
        boolean readerAvailable = false;
        boolean succeeded = false;
        for (Integer id : ids) {
            Method reader = findTypedSignalGetter(manager.getClass(), id,
                    propertyNames.get(id));
            if (reader == null) continue;
            readerAvailable = true;
            try {
                Object value = reader.invoke(manager);
                succeeded = true;
                if (value != null) handleEvent(value, id);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
            }
        }
        if (readerAvailable) return new ReadResult(true, succeeded);
        // Callback-only firmware is valid. The freshness TTL in GeelyCarIntegration ensures a
        // silent/dead callback can never suppress AdaptAPI forever even without a read method.
        return new ReadResult(false, false);
    }

    @Nullable
    private static Method findTypedSignalGetter(Class<?> managerClass, int propertyId,
                                                @Nullable String propertyName) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        if (propertyName != null && !propertyName.trim().isEmpty()) {
            String trimmed = propertyName.trim();
            names.add(trimmed.startsWith("get") ? trimmed : "get" + trimmed);
        }
        if (propertyId == EcarxSignalDecoder.PROPERTY_GEAR_ACTUAL) {
            names.add("getPtGearAct");
        } else if (propertyId == EcarxSignalDecoder.PROPERTY_GEAR_SELECTOR) {
            names.add("getGearLvrIndcn");
            names.add("getGearLvrPosn");
        }
        for (String name : names) {
            Method method = findMethod(managerClass, name, 0);
            if (method != null) return method;
        }
        return null;
    }

    private void scheduleHealthRead(Object expectedManager, @Nullable Object expectedCallback) {
        if (expectedCallback == null) return;
        try {
            worker.schedule(() -> {
                if (!hasDemand() || signalManager != expectedManager
                        || signalCallback != expectedCallback) return;
                ReadResult result = readCurrentValues(expectedManager);
                if (result.readerAvailable && !result.invocationSucceeded) {
                    recoverAfterCallbackError(expectedCallback);
                    return;
                }
                scheduleHealthRead(expectedManager, expectedCallback);
            }, HEALTH_READ_MILLIS, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ignored) {
        }
    }

    private void handleCallbackArguments(Object expectedCallback, Object[] args) {
        if (!hasDemand() || signalCallback != expectedCallback) return;
        if (args.length >= 2 && args[0] instanceof Number) {
            handleEvent(args[1], ((Number) args[0]).intValue());
            return;
        }
        if (args.length > 0) handleEvent(args[0], null);
    }

    private void handleEvent(@Nullable Object event, @Nullable Integer forcedPropertyId) {
        if (event == null) return;
        if (forcedPropertyId == null && event instanceof Collection) {
            for (Object item : (Collection<?>) event) handleEvent(item, null);
            return;
        }
        if (forcedPropertyId == null && event.getClass().isArray()) {
            int length = Array.getLength(event);
            for (int i = 0; i < length; i++) handleEvent(Array.get(event, i), null);
            return;
        }
        Integer propertyId = forcedPropertyId;
        Object value = event;
        if (propertyId == null) {
            propertyId = invokeIntegerGetter(event, "getPropertyId");
            if (propertyId == null) return;
            Object wrapped = invokeGetter(event, "getValue");
            if (wrapped != null) value = wrapped;
        } else {
            Object wrapped = invokeGetter(event, "getValue");
            if (wrapped != null) value = wrapped;
        }
        Integer raw = EcarxSignalDecoder.coerceInteger(value);
        if (raw != null) handleSignal(propertyId, raw);
    }

    @Nullable
    private static Object invokeGetter(Object target, String name) {
        Method method = findMethod(target.getClass(), name, 0);
        if (method == null) return null;
        try {
            return method.invoke(target);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    @Nullable
    private static Integer invokeIntegerGetter(Object target, String name) {
        return EcarxSignalDecoder.coerceInteger(invokeGetter(target, name));
    }

    private void handleSignal(int propertyId, int raw) {
        if (propertyId == EcarxSignalDecoder.PROPERTY_GEAR_SELECTOR && gearDemand) {
            selectorRaw = raw;
            if (manualModeIds.isEmpty()) manualMode = raw == 4;
            emitGear();
            return;
        }
        if (propertyId == EcarxSignalDecoder.PROPERTY_GEAR_ACTUAL && gearDemand) {
            actualGearRaw = raw;
            emitGear();
            return;
        }
        if (manualModeIds.contains(propertyId) && gearDemand) {
            manualMode = EcarxSignalDecoder.isManualModeValue(raw);
            emitGear();
            return;
        }
        if (highBeamIds.contains(propertyId) && highBeamDemand) {
            int normalized = EcarxSignalDecoder.normalizeHighBeam(raw);
            if (normalized >= 0) listener.onHighBeam(normalized);
        }
    }

    private void emitGear() {
        Integer composed = EcarxSignalDecoder.composeAdaptGear(
                selectorRaw, actualGearRaw, Boolean.TRUE.equals(manualMode));
        if (composed == null) return;
        int actual = actualGearRaw == null
                ? 0 : EcarxSignalDecoder.normalizeActualGear(actualGearRaw);
        listener.onGear(composed, actual, Boolean.TRUE.equals(manualMode));
    }

    /** ECarXCarProxy invokes this asynchronously after initECarXCar binds the vendor service. */
    private void acceptConnectedManager(Object expectedProxyCallback, Object manager) {
        if (!hasDemand() || proxyMethodCallback != expectedProxyCallback
                || ecarxProxy == null) return;
        if (signalManager == manager && signalCallback != null) return;
        unregisterCallback();
        signalManager = manager;
        retryScheduled = false;
        retryAttempts = 0;
        connectAndRegister();
    }

    private void recoverAfterCallbackError(Object expectedCallback) {
        if (signalCallback != expectedCallback) return;
        listener.onChannelLost();
        unregisterCallback();
        signalManager = null;
        if (hasDemand()) scheduleRetry();
    }

    private void recoverAfterServiceDeath(Object expectedProxyCallback) {
        if (proxyMethodCallback != expectedProxyCallback) return;
        listener.onChannelLost();
        unregisterCallback();
        signalManager = null;
        releaseProxy();
        if (hasDemand()) scheduleRetry();
    }

    private void unregisterCallback() {
        Object manager = signalManager;
        Object callback = signalCallback;
        signalCallback = null;
        if (manager == null || callback == null) return;
        for (Method method : manager.getClass().getMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if (!method.getName().equals("unregisterCallback") || parameters.length != 1
                    || !parameters[0].isInstance(callback)) continue;
            try {
                method.invoke(manager, callback);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
            }
            return;
        }
    }

    private void releaseProxy() {
        Object proxy = ecarxProxy;
        ecarxProxy = null;
        proxyMethodCallback = null;
        if (proxy != null) {
            for (String name : new String[] { "cleanup", "release", "destroy" }) {
                Method method = findMethod(proxy.getClass(), name, 0);
                if (method == null) continue;
                try {
                    method.invoke(proxy);
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                }
                break;
            }
        }
        releaseDirectEcarxCar();
    }

    private void releaseDirectEcarxCar() {
        Object root = directEcarxCar;
        directEcarxCar = null;
        if (root == null) return;
        invokeNoArgIfPresent(root, "disconnect");
    }

    private void execute(Runnable task) {
        try {
            worker.execute(task);
        } catch (RejectedExecutionException ignored) {
        }
    }
}
