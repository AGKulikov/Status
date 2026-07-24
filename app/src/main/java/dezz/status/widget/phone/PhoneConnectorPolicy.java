/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone;

import androidx.annotation.Nullable;

/** Android-independent normalisation rules shared by the phone transport and its unit tests. */
public final class PhoneConnectorPolicy {
    private static final long[] RETRY_DELAYS_MS = {
            2_000L, 5_000L, 10_000L, 20_000L, 30_000L, 60_000L
    };

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
     * Decodes the two-bit Charging State field of GATT Battery Power State (0x2A1A).
     *
     * <p>0 = unknown, 1 = not supported, 2 = not charging, 3 = charging.</p>
     */
    @Nullable
    public static Boolean decodeBatteryPowerState(int unsignedByte) {
        int chargingState = unsignedByte >>> 4 & 0x03;
        if (chargingState == 2) return false;
        if (chargingState == 3) return true;
        return null;
    }

    /** Unlimited reconnect schedule with a one-minute ceiling. */
    public static long reconnectDelayMillis(int attempt) {
        if (attempt <= 0) return RETRY_DELAYS_MS[0];
        return RETRY_DELAYS_MS[Math.min(attempt, RETRY_DELAYS_MS.length - 1)];
    }
}
