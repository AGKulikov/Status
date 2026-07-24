/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package dezz.status.widget.phone;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;

import java.lang.reflect.Method;

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

    /**
     * Asks the stock ECARX Bluetooth service to connect the already selected device.
     *
     * <p>This method never unpairs a device and never treats an unavailable vendor API as a fatal
     * error.</p>
     */
    public static boolean requestStockConnection(@NonNull Context context,
                                                 @NonNull String rawAddress) {
        String address = rawAddress.trim();
        if (address.isEmpty()) return false;
        synchronized (LOCK) {
            try {
                Object extension = bluetoothExtension(context.getApplicationContext());
                if (extension == null) return false;
                Method request = extension.getClass().getMethod("reqBtPair", String.class);
                Object result = request.invoke(extension, address);
                return result instanceof Boolean && (Boolean) result;
            } catch (Throwable unavailable) {
                Log.d(TAG, "Stock ECARX Bluetooth connect request is unavailable", unavailable);
                bluetoothExtension = null;
                return false;
            }
        }
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
