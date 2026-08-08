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
    /** Type-correct aggregate polling is diagnostic-only and must catch short button frames. */
    private static final long RECORDER_HEALTH_READ_MILLIS = 250L;

    interface Listener {
        void onAdasCaptureReady(int propertyCount, @NonNull String propertyIds);
        void onAdasSignal(int propertyId, @NonNull String signalName, int raw);
        void onAdasBinarySignal(int propertyId, @NonNull String signalName,
                                @NonNull byte[] raw);
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
    private volatile boolean adasRecorderDemand;
    private volatile boolean closed;

    /** Worker-thread-owned reflection state. */
    @Nullable private Object ecarxProxy;
    @Nullable private Object proxyMethodCallback;
    @Nullable private Object directEcarxCar;
    @Nullable private Object signalManager;
    @Nullable private Object signalCallback;
    private final Set<Integer> manualModeIds = new LinkedHashSet<>();
    private final Set<Integer> highBeamIds = new LinkedHashSet<>();
    private final Set<Integer> recorderDiscoveryIds = new LinkedHashSet<>();
    /** Runtime catalog entries whose generated getter proves an integer-compatible payload. */
    private final Set<Integer> typedRecorderDiscoveryIds = new LinkedHashSet<>();
    /** Runtime SDK IDs whose generated zero-argument getter returns a raw byte array. */
    private final Map<Integer, String> binaryRecorderGetterNames = new HashMap<>();
    private final Set<Integer> unsupportedRecorderIds = new LinkedHashSet<>();
    private final Set<Integer> activeRecorderIds = new LinkedHashSet<>();
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

    void updateDemand(boolean needsGear, boolean needsHighBeam, boolean needsAdasRecorder) {
        if (closed || (gearDemand == needsGear && highBeamDemand == needsHighBeam
                && adasRecorderDemand == needsAdasRecorder)) return;
        gearDemand = needsGear;
        highBeamDemand = needsHighBeam;
        adasRecorderDemand = needsAdasRecorder;
        execute(() -> {
            listener.onChannelLost();
            unregisterCallback();
            selectorRaw = null;
            actualGearRaw = null;
            manualMode = null;
            activeRecorderIds.clear();
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
        adasRecorderDemand = false;
        execute(() -> {
            unregisterCallback();
            releaseProxy();
        });
        worker.shutdown();
    }

    private boolean hasDemand() {
        return !closed && (gearDemand || highBeamDemand || adasRecorderDemand);
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
            scanPropertyIds(manager);
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
                scanPropertyIds(signalManager);
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

    private void scanPropertyIds(@Nullable Object manager) {
        manualModeIds.clear();
        highBeamIds.clear();
        recorderDiscoveryIds.clear();
        typedRecorderDiscoveryIds.clear();
        binaryRecorderGetterNames.clear();
        for (int propertyId : EcarxAdasSignalCatalog.discoveryFallbackPropertyIds()) {
            recorderDiscoveryIds.add(propertyId);
        }
        propertyNames.clear();
        Map<?, ?> names = propertyIdNames();
        Set<String> integerCallbackGetters = adasRecorderDemand
                ? discoverIntegerCallbackGetterNames(manager)
                : Collections.emptySet();
        Set<Integer> integerCallbackIds = adasRecorderDemand
                ? discoverIntegerCallbackPropertyIds(manager, integerCallbackGetters)
                : Collections.emptySet();
        Set<String> binaryCallbackGetters = adasRecorderDemand
                ? discoverBinaryCallbackGetterNames(manager)
                : Collections.emptySet();
        Map<Integer, String> binaryCallbackIds = adasRecorderDemand
                ? discoverBinaryCallbackPropertyGetters(manager, binaryCallbackGetters)
                : Collections.emptyMap();
        recorderDiscoveryIds.addAll(binaryCallbackIds.keySet());
        binaryRecorderGetterNames.putAll(binaryCallbackIds);
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
            if (EcarxAdasSignalCatalog.isDiscoveryPropertyName(name)) {
                recorderDiscoveryIds.add(id);
            }
            if (adasRecorderDemand && (integerCallbackIds.contains(id)
                    || isIntegerCallbackProperty(integerCallbackGetters, name))) {
                recorderDiscoveryIds.add(id);
                typedRecorderDiscoveryIds.add(id);
            }
        }
        Log.d(TAG, "Discovered manualMode=" + manualModeIds + ", highBeam=" + highBeamIds
                + ", vehicleControl=" + recorderPropertyIds()
                + ", runtimeTypedCallbackCount=" + typedRecorderDiscoveryIds.size()
                + ", runtimeBinaryGetterCount=" + binaryRecorderGetterNames.size());
    }

    /**
     * Uses the generated zero-argument CarSignalManager getter only as a type declaration. The
     * getter is never invoked here: runtime-discovered IDs remain callback-only, which prevents
     * the Integer-vs-byte[] health-poll flood observed on KX11 while still widening diagnostics
     * beyond guessed limiter names.
     */
    private static boolean isIntegerCallbackProperty(@NonNull Set<String> getterNames,
                                                     @Nullable String propertyName) {
        if (propertyName == null) return false;
        String trimmed = propertyName.trim();
        if (trimmed.isEmpty()) return false;
        String getterName = trimmed.regionMatches(true, 0, "get", 0, 3)
                ? trimmed
                : "get" + trimmed;
        return getterNames.contains(getterName.toLowerCase(Locale.ROOT));
    }

    /**
     * Indexes all integer-compatible generated getters once. The HA1187 implementation searched
     * the complete (very large) CarSignalManager method array once per property/field; on KX11
     * that kept the single fallback worker busy for the whole 21-second limiter recording and no
     * {@code ECARX_ADAS_CAPTURE_READY} event was ever emitted.
     */
    @NonNull
    private static Set<String> discoverIntegerCallbackGetterNames(@Nullable Object manager) {
        if (manager == null) return Collections.emptySet();
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (Method method : manager.getClass().getMethods()) {
            if (method.getParameterTypes().length == 0
                    && isIntegerReturnType(method.getReturnType())) {
                result.add(method.getName().toLowerCase(Locale.ROOT));
            }
        }
        return result;
    }

    /**
     * Builds the authoritative SDK ID-to-getter-type map without reading a signal. This covers
     * vendor PropertyIdString spellings that differ from the generated getter while still
     * excluding byte-array, floating-point and object payloads from the wide callback filter.
     */
    @NonNull
    private static Set<Integer> discoverIntegerCallbackPropertyIds(
            @Nullable Object manager, @NonNull Set<String> getterNames) {
        if (manager == null) return Collections.emptySet();
        LinkedHashSet<Integer> result = new LinkedHashSet<>();
        Class<?> managerClass = manager.getClass();
        for (Field field : managerClass.getFields()) {
            String fieldName = field.getName();
            if (!Modifier.isStatic(field.getModifiers()) || !fieldName.startsWith("SignalId_")) {
                continue;
            }
            String getterName = ("get" + fieldName.substring("SignalId_".length()))
                    .toLowerCase(Locale.ROOT);
            if (!getterNames.contains(getterName)) continue;
            try {
                Object rawId = field.get(null);
                if (rawId instanceof Number) result.add(((Number) rawId).intValue());
            } catch (IllegalAccessException | RuntimeException ignored) {
                // Public SDK constants normally succeed; a vendor-hidden alias is simply skipped.
            }
        }
        return result;
    }

    /** Indexes the vendor aggregate getters without invoking them during catalog discovery. */
    @NonNull
    private static Set<String> discoverBinaryCallbackGetterNames(@Nullable Object manager) {
        if (manager == null) return Collections.emptySet();
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (Method method : manager.getClass().getMethods()) {
            if (method.getParameterTypes().length == 0
                    && method.getReturnType() == byte[].class) {
                result.add(method.getName().toLowerCase(Locale.ROOT));
            }
        }
        return result;
    }

    /** Maps each byte-array getter back to its public SignalId constant and exact method name. */
    @NonNull
    private static Map<Integer, String> discoverBinaryCallbackPropertyGetters(
            @Nullable Object manager, @NonNull Set<String> getterNames) {
        if (manager == null || getterNames.isEmpty()) return Collections.emptyMap();
        Map<Integer, String> result = new HashMap<>();
        for (Field field : manager.getClass().getFields()) {
            String fieldName = field.getName();
            if (!Modifier.isStatic(field.getModifiers()) || !fieldName.startsWith("SignalId_")) {
                continue;
            }
            String getterName = "get" + fieldName.substring("SignalId_".length());
            if (!getterNames.contains(getterName.toLowerCase(Locale.ROOT))) continue;
            try {
                Object rawId = field.get(null);
                if (rawId instanceof Number) {
                    result.put(((Number) rawId).intValue(), getterName);
                }
            } catch (IllegalAccessException | RuntimeException ignored) {
                // A vendor-hidden alias cannot be polled safely and is left callback-only.
            }
        }
        return result;
    }

    private static boolean isIntegerReturnType(Class<?> type) {
        return type == Byte.TYPE || type == Byte.class
                || type == Short.TYPE || type == Short.class
                || type == Integer.TYPE || type == Integer.class
                || type == Long.TYPE || type == Long.class
                || Number.class.isAssignableFrom(type);
    }

    @NonNull
    private LinkedHashSet<Integer> recorderPropertyIds() {
        LinkedHashSet<Integer> ids = new LinkedHashSet<>();
        for (int propertyId : EcarxAdasSignalCatalog.propertyIds()) ids.add(propertyId);
        ids.addAll(recorderDiscoveryIds);
        return ids;
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
        LinkedHashSet<Integer> requiredIds = new LinkedHashSet<>();
        if (gearDemand) {
            requiredIds.add(EcarxSignalDecoder.PROPERTY_GEAR_ACTUAL);
            requiredIds.add(EcarxSignalDecoder.PROPERTY_GEAR_SELECTOR);
            requiredIds.addAll(manualModeIds);
        }
        if (highBeamDemand) requiredIds.addAll(highBeamIds);
        LinkedHashSet<Integer> recorderIds = new LinkedHashSet<>();
        if (adasRecorderDemand) {
            for (int propertyId : EcarxAdasSignalCatalog.propertyIds()) {
                requiredIds.add(propertyId);
                recorderIds.add(propertyId);
            }
        }
        // In a high-beam-only subscription an empty discovery result is not a successful
        // registration: retry while ecarxcar_service finishes publishing PropertyIdString.
        if (requiredIds.isEmpty()) return false;

        Class<?> filterClass = Class.forName("ecarx.car.hardware.signal.SignalFilter");
        Method add = findIntMethod(filterClass, "add");
        if (add == null) return false;

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
        Object methodProbeFilter = buildSignalFilter(filterClass, add, requiredIds);
        Registration registration = findRegistrationMethod(
                manager.getClass(), callback, methodProbeFilter);
        if (registration == null) return false;

        LinkedHashSet<Integer> ids = new LinkedHashSet<>(requiredIds);
        if (adasRecorderDemand) {
            ids.addAll(typedRecorderDiscoveryIds);
            recorderIds.addAll(typedRecorderDiscoveryIds);
            ids.addAll(binaryRecorderGetterNames.keySet());
            recorderIds.addAll(binaryRecorderGetterNames.keySet());
            Object passiveCallback = createPassiveSignalCallback(callbackClass);
            for (Integer propertyId : recorderDiscoveryIds) {
                if (requiredIds.contains(propertyId)
                        || typedRecorderDiscoveryIds.contains(propertyId)
                        || binaryRecorderGetterNames.containsKey(propertyId)
                        || unsupportedRecorderIds.contains(propertyId)) continue;
                if (probeRecorderProperty(manager, registration, filterClass, add,
                        passiveCallback, propertyId)) {
                    ids.add(propertyId);
                    recorderIds.add(propertyId);
                }
            }
        }

        Object filter = buildSignalFilter(filterClass, add, ids);
        try {
            invokeRegistration(manager, registration, callback, filter);
        } catch (Exception combinedFailure) {
            // A vendor service can accept a one-ID probe and still reject a mixed filter. Keep
            // the eleven confirmed IDs alive instead of allowing optional discovery to take the
            // whole recorder down.
            if (!adasRecorderDemand || ids.equals(requiredIds)) throw combinedFailure;
            Log.w(TAG, "Combined recorder filter rejected; using confirmed property IDs",
                    combinedFailure);
            ids.clear();
            ids.addAll(requiredIds);
            recorderIds.clear();
            for (int propertyId : EcarxAdasSignalCatalog.propertyIds()) {
                recorderIds.add(propertyId);
            }
            // Generated byte-array getters remain safe to poll even when the vendor rejects
            // their mixed callback filter. Keep them active for the type-correct health reader.
            recorderIds.addAll(binaryRecorderGetterNames.keySet());
            filter = buildSignalFilter(filterClass, add, ids);
            invokeRegistration(manager, registration, callback, filter);
        }
        signalCallback = callback;
        activeRecorderIds.clear();
        activeRecorderIds.addAll(recorderIds);
        Log.d(TAG, "Registered low-level signal fallback for " + ids);
        if (adasRecorderDemand) {
            listener.onAdasCaptureReady(recorderIds.size(), recorderIds.toString());
        }
        return true;
    }

    @NonNull
    private static Object buildSignalFilter(Class<?> filterClass, Method add,
                                            Collection<Integer> ids) throws Exception {
        Object filter = filterClass.getDeclaredConstructor().newInstance();
        for (Integer id : ids) add.invoke(filter, id);
        return filter;
    }

    @NonNull
    private static Object createPassiveSignalCallback(Class<?> callbackClass) {
        return Proxy.newProxyInstance(callbackClass.getClassLoader(),
                new Class<?>[] { callbackClass }, (proxy, method, args) -> {
                    String name = method.getName();
                    if (name.equals("toString")) return "StatusWidgetSignalProbe";
                    if (name.equals("hashCode")) return System.identityHashCode(proxy);
                    if (name.equals("equals")) return args != null && args.length == 1
                            && proxy == args[0];
                    return primitiveDefault(method.getReturnType());
                });
    }

    private boolean probeRecorderProperty(Object manager, Registration registration,
                                          Class<?> filterClass, Method add,
                                          Object passiveCallback, int propertyId) {
        boolean registered = false;
        try {
            Object filter = buildSignalFilter(filterClass, add,
                    Collections.singleton(propertyId));
            invokeRegistration(manager, registration, passiveCallback, filter);
            registered = true;
            return true;
        } catch (Throwable error) {
            if (isInvalidPropertyIdError(error)) {
                unsupportedRecorderIds.add(propertyId);
                Log.w(TAG, "Skipping unsupported recorder property id " + propertyId);
            } else {
                Log.w(TAG, "Recorder property probe failed for " + propertyId, error);
            }
            return false;
        } finally {
            if (registered) unregisterSpecificCallback(manager, passiveCallback);
        }
    }

    private static void invokeRegistration(Object manager, Registration registration,
                                           Object callback, Object filter) throws Exception {
        if (registration.callbackFirst) {
            registration.method.invoke(manager, callback, filter);
        } else {
            registration.method.invoke(manager, filter, callback);
        }
    }

    private static boolean isInvalidPropertyIdError(Throwable error) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < 8; depth++) {
            String message = current.getMessage();
            if (current instanceof IllegalArgumentException
                    || (message != null && message.toLowerCase(Locale.ROOT)
                    .contains("invalid property id"))) return true;
            current = current.getCause();
        }
        return false;
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
        ReadResult binaryRead = readBinaryCurrentValues(manager);
        List<Integer> ids = new ArrayList<>();
        if (gearDemand) {
            ids.add(EcarxSignalDecoder.PROPERTY_GEAR_ACTUAL);
            ids.add(EcarxSignalDecoder.PROPERTY_GEAR_SELECTOR);
            ids.addAll(manualModeIds);
        }
        if (highBeamDemand) ids.addAll(highBeamIds);
        if (adasRecorderDemand) {
            // Runtime-discovered ECARX properties are not guaranteed to be integer-valued.
            // Several valid callback sources on KX11 (for example 0x8207 and 0x820c) expose
            // byte[] payloads, and polling them through getSignalValue(int) floods the vendor log
            // with type errors. Keep discovery IDs callback-only; health reads cover the fixed,
            // confirmed integer ADAS catalog and still exercise the channel once per interval.
            for (int propertyId : EcarxAdasSignalCatalog.propertyIds()) {
                ids.add(propertyId);
            }
        }
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
            return new ReadResult(true, succeeded || binaryRead.invocationSucceeded);
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
        if (readerAvailable) {
            return new ReadResult(true, succeeded || binaryRead.invocationSucceeded);
        }
        if (binaryRead.readerAvailable) return binaryRead;
        // Callback-only firmware is valid. The freshness TTL in GeelyCarIntegration ensures a
        // silent/dead callback can never suppress AdaptAPI forever even without a read method.
        return new ReadResult(false, false);
    }

    /**
     * Reads every generated byte-array getter with its declared return type. Calling the generic
     * integer accessor for these IDs produced the repeated Integer-vs-byte[] exceptions seen in
     * the KX11 trace; the generated getter is both safe and sufficient for baseline/diff capture.
     */
    @NonNull
    private ReadResult readBinaryCurrentValues(@NonNull Object manager) {
        boolean readerAvailable = false;
        boolean succeeded = false;
        for (Map.Entry<Integer, String> entry : binaryRecorderGetterNames.entrySet()) {
            Method reader = findMethod(manager.getClass(), entry.getValue(), 0);
            if (reader == null || reader.getReturnType() != byte[].class) continue;
            readerAvailable = true;
            try {
                Object value = reader.invoke(manager);
                succeeded = true;
                if (value != null) handleEvent(value, entry.getKey());
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // One unavailable aggregate must not tear down the working integer callback.
            }
        }
        return new ReadResult(readerAvailable, succeeded);
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
        String adasGetter = EcarxAdasSignalCatalog.getterName(propertyId);
        if (adasGetter != null) names.add(adasGetter);
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
            }, adasRecorderDemand ? RECORDER_HEALTH_READ_MILLIS : HEALTH_READ_MILLIS,
                    TimeUnit.MILLISECONDS);
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
        if (raw != null) {
            handleSignal(propertyId, raw);
            return;
        }
        byte[] binary = EcarxSignalDecoder.coerceByteArray(value);
        if (binary != null) handleBinarySignal(propertyId, binary);
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
        if (adasRecorderDemand && (EcarxAdasSignalCatalog.contains(propertyId)
                || activeRecorderIds.contains(propertyId))) {
            String runtimeName = propertyNames.get(propertyId);
            listener.onAdasSignal(propertyId,
                    runtimeName == null || runtimeName.trim().isEmpty()
                            ? EcarxAdasSignalCatalog.signalName(propertyId) : runtimeName,
                    raw);
        }
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

    private void handleBinarySignal(int propertyId, @NonNull byte[] raw) {
        if (!adasRecorderDemand || (!EcarxAdasSignalCatalog.contains(propertyId)
                && !activeRecorderIds.contains(propertyId))) return;
        String runtimeName = propertyNames.get(propertyId);
        listener.onAdasBinarySignal(propertyId,
                runtimeName == null || runtimeName.trim().isEmpty()
                        ? EcarxAdasSignalCatalog.signalName(propertyId) : runtimeName,
                raw);
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
        activeRecorderIds.clear();
        if (manager == null || callback == null) return;
        unregisterSpecificCallback(manager, callback);
    }

    private static void unregisterSpecificCallback(Object manager, Object callback) {
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
