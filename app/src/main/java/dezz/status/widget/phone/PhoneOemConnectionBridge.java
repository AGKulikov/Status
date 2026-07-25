/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package dezz.status.widget.phone;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;

/**
 * Best-effort hand-off to the ECARX Bluetooth owner before the app opens its optional GATT client.
 *
 * <p>The stock service owns discoverability, pairing and automotive Bluetooth profiles. Calling
 * its non-destructive connect request is materially different from pretending that
 * {@code BluetoothDevice.connectGatt()} performs pairing. Reflection keeps non-ECARX builds safe;
 * every missing class, unavailable binder or rejected request simply falls back to the public
 * Android path.</p>
 */
public final class PhoneOemConnectionBridge {
    private static final String TAG = "PhoneOemBridge";
    private static final Object LOCK = new Object();

    private static Object deviceApi;
    private static Object bluetoothExtension;

    private PhoneOemConnectionBridge() {
    }

    /** Receives exact-device battery updates from the stock Bluetooth owner. */
    public interface HeadsetPowerListener {
        void onHeadsetPower(@NonNull String address, int rawPower);
    }

    /** Registration handle for the vendor callback. Closing it is always safe and idempotent. */
    public interface Observation extends AutoCloseable {
        @Override
        void close();
    }

    /** Stable, non-sensitive states published by the phone connector diagnostics. */
    public enum RequestStatus {
        ACCEPTED("accepted", false),
        REJECTED("rejected", true),
        PHONE_NOT_REGISTERED("phone_not_registered", false),
        API_UNAVAILABLE("api_unavailable", true),
        INVALID_ADDRESS("invalid_address", false);

        @NonNull private final String diagnosticCode;
        private final boolean retryable;

        RequestStatus(@NonNull String diagnosticCode, boolean retryable) {
            this.diagnosticCode = diagnosticCode;
            this.retryable = retryable;
        }

        @NonNull
        public String diagnosticCode() {
            return diagnosticCode;
        }

        public boolean retryable() {
            return retryable;
        }
    }

    /** Result of one non-destructive request to the stock Bluetooth owner. */
    public static final class RequestResult {
        @NonNull public final RequestStatus status;

        private RequestResult(@NonNull RequestStatus status) {
            this.status = status;
        }

        public boolean accepted() {
            return status == RequestStatus.ACCEPTED;
        }

        public boolean retryable() {
            return status.retryable();
        }

        @NonNull
        public String diagnosticCode() {
            return status.diagnosticCode();
        }
    }

    /**
     * Asks the stock ECARX Bluetooth service to connect the already selected device.
     *
     * <p>Despite its vendor name, {@code reqBtPair()} delegates to
     * {@code PSDBluetoothManager.requestConnect()} in the bundled ECARX API. It therefore connects
     * an already registered phone; it neither pairs a new one nor creates iPhone ANCS
     * authorization. This method never unpairs a device and never treats an unavailable vendor
     * API as a fatal error.</p>
     */
    @NonNull
    public static RequestResult requestStockConnection(@NonNull Context context,
                                                       @NonNull String rawAddress) {
        String address = rawAddress.trim();
        if (address.isEmpty()) return result(RequestStatus.INVALID_ADDRESS);
        synchronized (LOCK) {
            try {
                Object extension = bluetoothExtension(context.getApplicationContext());
                if (extension == null) return result(RequestStatus.API_UNAVAILABLE);
                Boolean registered = stockServiceContains(extension, address);
                Method request = extension.getClass().getMethod("reqBtPair", String.class);
                Object rawResult = request.invoke(extension, address);
                if (rawResult instanceof Boolean && (Boolean) rawResult) {
                    return result(RequestStatus.ACCEPTED);
                }
                return result(Boolean.FALSE.equals(registered)
                        ? RequestStatus.PHONE_NOT_REGISTERED : RequestStatus.REJECTED);
            } catch (Throwable unavailable) {
                Log.d(TAG, "Stock ECARX Bluetooth connect request is unavailable", unavailable);
                bluetoothExtension = null;
                return result(RequestStatus.API_UNAVAILABLE);
            }
        }
    }

    /**
     * Subscribes to the ECARX headset-power callback for one exact phone.
     *
     * <p>Android's public iPhone HFP broadcasts are inconsistent on this head unit: network
     * strength may arrive while battery extras are omitted. The stock Bluetooth owner already
     * tracks that battery and exposes both an initial {@code getHeadsetPower()} read and live
     * {@code onDevicePowerUpdated()} callbacks. This bridge only reads that existing state; it
     * never changes pairing or profile ownership.</p>
     *
     * @return a closeable registration, or {@code null} when the ECARX API is unavailable
     */
    @Nullable
    public static Observation observeHeadsetPower(
            @NonNull Context context,
            @NonNull String rawAddress,
            @NonNull HeadsetPowerListener listener) {
        String address = rawAddress.trim();
        if (address.isEmpty()) return null;
        synchronized (LOCK) {
            try {
                Object extension = bluetoothExtension(context.getApplicationContext());
                if (extension == null) return null;
                Method register = oneArgumentMethod(
                        extension.getClass(), "registerBtCallback");
                Method unregister = oneArgumentMethod(
                        extension.getClass(), "unregisterBtCallback");
                Method getPower = oneArgumentMethod(extension.getClass(), "getHeadsetPower");
                if (register == null || unregister == null || getPower == null) return null;

                Class<?> callbackType = register.getParameterTypes()[0];
                Object device = findStockDevice(extension, address);
                // Even firmware builds that reject callback registration can still expose the
                // current exact-device value. Deliver that one-shot reading before registering.
                deliverCurrentPower(extension, getPower, address, device, listener);
                InvocationHandler callback = (proxy, method, args) -> {
                    String name = method.getName();
                    if ("toString".equals(name)) return "StatusWidgetBtExtensionCallback";
                    if ("hashCode".equals(name)) return System.identityHashCode(proxy);
                    if ("equals".equals(name)) {
                        return args != null && args.length == 1 && proxy == args[0];
                    }
                    if ("onDevicePowerUpdated".equals(name)
                            && args != null && args.length >= 2) {
                        deliverPower(address, args[0], args[1], listener);
                    } else if (("onDeviceUuidsUpdated".equals(name)
                            || "onDeviceBondStateChanged".equals(name))
                            && args != null && args.length >= 1) {
                        deliverCurrentPower(extension, getPower, address, args[0], listener);
                    } else if ("onPairedDevicesChanged".equals(name)
                            && args != null && args.length >= 1
                            && args[0] instanceof List) {
                        Object device = findStockDevice((List<?>) args[0], address);
                        deliverCurrentPower(extension, getPower, address, device, listener);
                    }
                    return defaultValue(method.getReturnType());
                };
                ClassLoader loader = callbackType.getClassLoader();
                if (loader == null) loader = PhoneOemConnectionBridge.class.getClassLoader();
                Object proxy = Proxy.newProxyInstance(
                        loader, new Class<?>[]{callbackType}, callback);
                Object accepted = register.invoke(extension, proxy);
                if (accepted instanceof Boolean && !((Boolean) accepted)) return null;

                return new VendorObservation(extension, unregister, proxy);
            } catch (Throwable unavailable) {
                Log.d(TAG, "Stock ECARX headset-power observer is unavailable", unavailable);
                bluetoothExtension = null;
                return null;
            }
        }
    }

    /**
     * Distinguishes an ECARX service that is still starting from an ECARX phone database that
     * definitely does not contain the selected Android bond. A null result means “unknown” and
     * must not block the public GATT fallback.
     */
    @Nullable
    private static Boolean stockServiceContains(@NonNull Object extension,
                                                @NonNull String address) {
        try {
            List<?> devices = stockDevices(extension);
            return devices == null ? null : findStockDevice(devices, address) != null;
        } catch (Throwable unavailable) {
            Log.d(TAG, "Stock ECARX paired-device diagnostics are unavailable", unavailable);
            return null;
        }
    }

    @Nullable
    private static Object findStockDevice(@NonNull Object extension,
                                          @NonNull String address) throws Exception {
        List<?> devices = stockDevices(extension);
        return devices == null ? null : findStockDevice(devices, address);
    }

    @Nullable
    private static List<?> stockDevices(@NonNull Object extension) throws Exception {
        Method paired = extension.getClass().getMethod("reqBtPairedDevices");
        Object rawDevices = paired.invoke(extension);
        return rawDevices instanceof List ? (List<?>) rawDevices : null;
    }

    @Nullable
    private static Object findStockDevice(@NonNull List<?> devices,
                                          @NonNull String address) {
        for (Object item : devices) {
            String itemAddress = deviceAddress(item);
            if (address.equalsIgnoreCase(itemAddress)) return item;
        }
        return null;
    }

    private static void deliverCurrentPower(
            @NonNull Object extension,
            @NonNull Method getPower,
            @NonNull String address,
            @Nullable Object device,
            @NonNull HeadsetPowerListener listener) {
        if (device == null || !address.equalsIgnoreCase(deviceAddress(device))) return;
        try {
            deliverPower(address, device, getPower.invoke(extension, device), listener);
        } catch (Throwable unavailable) {
            Log.d(TAG, "Could not read ECARX headset power", unavailable);
        }
    }

    private static void deliverPower(
            @NonNull String address,
            @Nullable Object device,
            @Nullable Object rawPower,
            @NonNull HeadsetPowerListener listener) {
        if (!address.equalsIgnoreCase(deviceAddress(device))
                || !(rawPower instanceof Number)) return;
        listener.onHeadsetPower(address, ((Number) rawPower).intValue());
    }

    @NonNull
    private static String deviceAddress(@Nullable Object device) {
        if (device == null) return "";
        try {
            Method getter = device.getClass().getMethod("getAddress");
            Object value = getter.invoke(device);
            return value == null ? "" : String.valueOf(value).trim();
        } catch (Throwable unavailable) {
            return "";
        }
    }

    @Nullable
    private static Method oneArgumentMethod(@NonNull Class<?> type, @NonNull String name) {
        for (Method method : type.getMethods()) {
            if (name.equals(method.getName()) && method.getParameterTypes().length == 1) {
                return method;
            }
        }
        return null;
    }

    @Nullable
    private static Object defaultValue(@NonNull Class<?> type) {
        if (!type.isPrimitive() || type == Void.TYPE) return null;
        if (type == Boolean.TYPE) return false;
        if (type == Character.TYPE) return '\0';
        if (type == Byte.TYPE) return (byte) 0;
        if (type == Short.TYPE) return (short) 0;
        if (type == Integer.TYPE) return 0;
        if (type == Long.TYPE) return 0L;
        if (type == Float.TYPE) return 0f;
        return 0d;
    }

    private static final class VendorObservation implements Observation {
        @NonNull private final Object extension;
        @NonNull private final Method unregister;
        @NonNull private final Object callback;
        private boolean closed;

        VendorObservation(@NonNull Object extension, @NonNull Method unregister,
                          @NonNull Object callback) {
            this.extension = extension;
            this.unregister = unregister;
            this.callback = callback;
        }

        @Override
        public synchronized void close() {
            if (closed) return;
            closed = true;
            try {
                unregister.invoke(extension, callback);
            } catch (Throwable unavailable) {
                Log.d(TAG, "Could not unregister ECARX Bluetooth callback", unavailable);
            }
        }
    }

    @NonNull
    private static RequestResult result(@NonNull RequestStatus status) {
        return new RequestResult(status);
    }

    private static Object bluetoothExtension(@NonNull Context context) throws Exception {
        if (bluetoothExtension != null) return bluetoothExtension;
        Class<?> deviceClass =
                Class.forName("com.ecarx.xui.adaptapi.device.Device");
        if (deviceApi == null) {
            Method create = deviceClass.getMethod("create", Context.class);
            deviceApi = create.invoke(null, context);
        }
        if (deviceApi == null) return null;
        Method getter = deviceClass.getMethod("getBtExtension");
        bluetoothExtension = getter.invoke(deviceApi);
        return bluetoothExtension;
    }
}
