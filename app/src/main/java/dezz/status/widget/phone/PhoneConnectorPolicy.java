/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.nio.charset.StandardCharsets;

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

    /**
     * Decodes the adopted BAS 1.1 Battery Level Status characteristic (0x2BED).
     *
     * <p>The mandatory prefix is one byte of flags followed by a little-endian 16-bit Power
     * State. Optional identifier, battery-level and additional-status fields follow in flag
     * order. A malformed or truncated payload fails closed.</p>
     */
    @Nullable
    public static BatteryLevelStatus decodeBatteryLevelStatus(@Nullable byte[] payload) {
        if (payload == null || payload.length < 3) return null;
        int flags = payload[0] & 0xff;
        int powerState = (payload[1] & 0xff) | (payload[2] & 0xff) << 8;
        int offset = 3;
        if ((flags & 0x01) != 0) {
            if (payload.length < offset + 2) return null;
            offset += 2;
        }
        Integer level = null;
        if ((flags & 0x02) != 0) {
            if (payload.length < offset + 1) return null;
            int rawLevel = payload[offset++] & 0xff;
            if (rawLevel <= 100) level = rawLevel;
        }
        if ((flags & 0x04) != 0 && payload.length < offset + 1) return null;

        int chargeState = powerState >>> 5 & 0x03;
        Boolean charging = chargeState == 1 ? Boolean.TRUE
                : chargeState == 2 || chargeState == 3 ? Boolean.FALSE : null;
        String state = chargeState == 1 ? "charging"
                : chargeState == 2 ? "discharging"
                : chargeState == 3 ? "idle" : "";

        int wiredPower = powerState >>> 1 & 0x03;
        int wirelessPower = powerState >>> 3 & 0x03;
        Boolean externalPower = externalPower(wiredPower, wirelessPower);

        int rawChargeLevel = powerState >>> 7 & 0x03;
        String chargeLevel = rawChargeLevel == 1 ? "good"
                : rawChargeLevel == 2 ? "low"
                : rawChargeLevel == 3 ? "critical" : "";
        return new BatteryLevelStatus(level, charging, externalPower, state, chargeLevel);
    }

    /**
     * A percentage trend can only estimate charging. It is deliberately separate from explicit
     * BAS/HFP state so callers can label the result and expire it instead of presenting a guess as
     * authoritative.
     */
    @Nullable
    public static Boolean inferChargingFromLevelTrend(@Nullable Integer previousLevel,
                                                       @Nullable Integer currentLevel) {
        if (previousLevel == null || currentLevel == null
                || previousLevel < 0 || previousLevel > 100
                || currentLevel < 0 || currentLevel > 100
                || previousLevel.equals(currentLevel)) {
            return null;
        }
        return currentLevel > previousLevel;
    }

    /**
     * Decodes Android's cached {@code BluetoothDevice.METADATA_MAIN_CHARGING} value.
     *
     * <p>The system API documents this metadata as a UTF-8 string stored in a byte array.
     * Different Bluetooth stacks have used boolean, numeric, and state words, so accept only
     * explicit known spellings and fail closed for every other value.</p>
     */
    @Nullable
    public static Boolean decodeBluetoothChargingMetadata(@Nullable byte[] payload) {
        if (payload == null || payload.length == 0 || payload.length > 32) return null;
        String value = new String(payload, StandardCharsets.UTF_8).trim();
        if ("true".equalsIgnoreCase(value) || "1".equals(value)
                || "charging".equalsIgnoreCase(value)
                || "on".equalsIgnoreCase(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value) || "0".equals(value)
                || "not_charging".equalsIgnoreCase(value)
                || "not charging".equalsIgnoreCase(value)
                || "off".equalsIgnoreCase(value)) {
            return false;
        }
        return null;
    }

    @Nullable
    private static Boolean externalPower(int wired, int wireless) {
        if (wired == 1 || wireless == 1) return true;
        if (wired == 0 && wireless == 0) return false;
        return null;
    }

    /** Parsed, privacy-safe fields of Battery Level Status. */
    public static final class BatteryLevelStatus {
        @Nullable public final Integer level;
        @Nullable public final Boolean charging;
        @Nullable public final Boolean externalPower;
        @NonNull public final String chargeState;
        @NonNull public final String chargeLevel;

        private BatteryLevelStatus(@Nullable Integer level, @Nullable Boolean charging,
                                   @Nullable Boolean externalPower,
                                   @NonNull String chargeState,
                                   @NonNull String chargeLevel) {
            this.level = level;
            this.charging = charging;
            this.externalPower = externalPower;
            this.chargeState = chargeState;
            this.chargeLevel = chargeLevel;
        }
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
