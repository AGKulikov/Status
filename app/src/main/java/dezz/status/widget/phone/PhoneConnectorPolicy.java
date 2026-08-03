/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone;

import androidx.annotation.Nullable;

/** Android-independent normalisation rules shared by the phone transport and its unit tests. */
public final class PhoneConnectorPolicy {
    private static final long[] RETRY_DELAYS_MS = {
            2_000L, 5_000L, 10_000L, 20_000L, 30_000L, 60_000L
    };
    private static final long[] STOCK_CONNECT_RETRY_DELAYS_MS = {
            1_000L, 2_500L
    };
    private static final long STOCK_CONNECT_SETTLE_MS = 2_500L;

    private PhoneConnectorPolicy() {
    }

    /**
     * HFP defines the Apple/AG battery indication as six discrete levels, {@code 0..5}.
     * A few OEM Bluetooth stacks already expand it to {@code 0..100}; preserve those values.
     */
    @Nullable
    public static Integer normalizeHfpBattery(int raw) {
        if (raw < 0) return null;
        if (raw <= 5) return raw * 20;
        if (raw <= 100) return raw;
        return null;
    }

    /** HFP signal strength uses the same {@code 0..5} scale on Android's headset-client API. */
    @Nullable
    public static Integer normalizeHfpSignal(int raw) {
        if (raw < 0) return null;
        if (raw <= 5) return raw * 20;
        if (raw <= 100) return raw;
        return null;
    }

    /**
     * Extracts only the optional percentage from BAS 1.1 Battery Level Status (0x2BED).
     *
     * <p>The Power State bits are deliberately ignored. Charging and external-power state are
     * accepted exclusively from the authenticated iPhone Helper telemetry frame.</p>
     */
    @Nullable
    public static Integer decodeBatteryLevelStatusLevel(@Nullable byte[] payload) {
        if (payload == null || payload.length < 3) return null;
        int flags = payload[0] & 0xff;
        int offset = 3;
        if ((flags & 0x01) != 0) {
            if (payload.length < offset + 2) return null;
            offset += 2;
        }
        if ((flags & 0x02) == 0 || payload.length < offset + 1) return null;
        int level = payload[offset++] & 0xff;
        if ((flags & 0x04) != 0 && payload.length < offset + 1) return null;
        return level <= 100 ? level : null;
    }

    /** Unlimited reconnect schedule with a one-minute ceiling. */
    public static long reconnectDelayMillis(int attempt) {
        if (attempt <= 0) return RETRY_DELAYS_MS[0];
        return RETRY_DELAYS_MS[Math.min(attempt, RETRY_DELAYS_MS.length - 1)];
    }

    /**
     * The ECARX extension is backed by an asynchronously initialised service. Two quick retries
     * cover that startup window without postponing the public Android GATT fallback indefinitely.
     */
    public static int stockConnectionMaxAttempts() {
        return STOCK_CONNECT_RETRY_DELAYS_MS.length + 1;
    }

    /** Delay before the next ECARX connection request, indexed from zero. */
    public static long stockConnectionRetryDelayMillis(int retryIndex) {
        if (retryIndex <= 0) return STOCK_CONNECT_RETRY_DELAYS_MS[0];
        return STOCK_CONNECT_RETRY_DELAYS_MS[
                Math.min(retryIndex, STOCK_CONNECT_RETRY_DELAYS_MS.length - 1)];
    }

    /**
     * Gives the stock owner time to establish its automotive profiles before this app creates a
     * second, optional BLE GATT client for ANCS.
     */
    public static long stockConnectionSettleMillis() {
        return STOCK_CONNECT_SETTLE_MS;
    }
}
