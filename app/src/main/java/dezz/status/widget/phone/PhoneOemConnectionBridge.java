/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package dezz.status.widget.phone;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.reflect.Method;
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
     * Distinguishes an ECARX service that is still starting from an ECARX phone database that
     * definitely does not contain the selected Android bond. A null result means “unknown” and
     * must not block the public GATT fallback.
     */
    @Nullable
    private static Boolean stockServiceContains(@NonNull Object extension,
                                                @NonNull String address) {
        try {
            Method paired = extension.getClass().getMethod("reqBtPairedDevices");
            Object rawDevices = paired.invoke(extension);
            if (rawDevices == null) return null;
            if (!(rawDevices instanceof List)) return null;
            for (Object item : (List<?>) rawDevices) {
                if (item == null) continue;
                Method getAddress = item.getClass().getMethod("getAddress");
                Object rawAddress = getAddress.invoke(item);
                if (rawAddress != null
                        && address.equalsIgnoreCase(String.valueOf(rawAddress).trim())) {
                    return true;
                }
            }
            return false;
        } catch (Throwable unavailable) {
            Log.d(TAG, "Stock ECARX paired-device diagnostics are unavailable", unavailable);
            return null;
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
