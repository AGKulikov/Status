/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package dezz.status.widget.phone;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Locale;

/**
 * Short-lived, device-protected snapshot of non-session phone telemetry.
 *
 * <p>Battery and network values survive a brief Bluetooth recovery or application update.
 * ANCS notification identifiers and partial Control Point responses are intentionally never
 * stored: Apple defines them as local to one ANCS session.</p>
 */
final class PhoneTelemetryStore {
    static final long RETENTION_MS = 10L * 60L * 1_000L;
    private static final String PREFS_SUFFIX = "_phone_telemetry";

    private final SharedPreferences prefs;
    private String lastFingerprint = "";

    PhoneTelemetryStore(@NonNull Context context) {
        Context app = context.getApplicationContext();
        Context device = app.createDeviceProtectedStorageContext();
        prefs = device.getSharedPreferences(app.getPackageName() + PREFS_SUFFIX,
                Context.MODE_PRIVATE);
    }

    @Nullable Record load(@NonNull String address, long nowWallMs) {
        String expected = normalizeAddress(address);
        if (expected.isEmpty()
                || !expected.equals(prefs.getString("address", ""))) return null;
        long legacyUpdatedAt = prefs.getLong("updated_at", 0L);
        long batteryUpdatedAt = prefs.getLong("battery_updated_at", legacyUpdatedAt);
        long networkUpdatedAt = prefs.getLong("network_updated_at", legacyUpdatedAt);
        boolean batteryFresh = isFresh(batteryUpdatedAt, nowWallMs);
        boolean networkFresh = isFresh(networkUpdatedAt, nowWallMs);
        if (!batteryFresh && !networkFresh) return null;
        Record value = new Record(expected,
                batteryFresh ? batteryUpdatedAt : 0L,
                networkFresh ? networkUpdatedAt : 0L,
                batteryFresh ? nullableInt(prefs.getInt("battery_level", -1)) : null,
                batteryFresh ? prefs.getString("battery_level_source", "") : "",
                batteryFresh
                        ? nullableBoolean(prefs.getInt("battery_charging", -1)) : null,
                batteryFresh ? nullableBoolean(
                        prefs.getInt("battery_charging_estimated", -1)) : null,
                batteryFresh ? prefs.getString("battery_charging_source", "") : "",
                batteryFresh
                        ? nullableBoolean(prefs.getInt("battery_external_power", -1)) : null,
                batteryFresh ? prefs.getString("battery_charge_state", "") : "",
                batteryFresh ? prefs.getString("battery_charge_level", "") : "",
                networkFresh
                        ? nullableBoolean(prefs.getInt("network_available", -1)) : null,
                networkFresh ? nullableInt(prefs.getInt("network_signal", -1)) : null,
                networkFresh
                        ? nullableBoolean(prefs.getInt("network_roaming", -1)) : null,
                networkFresh ? prefs.getString("network_operator", "") : "",
                networkFresh ? prefs.getString("network_type", "") : "");
        return value.hasUsefulData() ? value : null;
    }

    void save(@NonNull Record value) {
        if (!value.hasUsefulData() || value.address.isEmpty()) return;
        String fingerprint = value.fingerprint();
        if (fingerprint.equals(lastFingerprint)) return;
        lastFingerprint = fingerprint;
        prefs.edit()
                .putString("address", value.address)
                .putLong("updated_at", value.updatedAtWallMs)
                .putLong("battery_updated_at", value.batteryUpdatedAtWallMs)
                .putLong("network_updated_at", value.networkUpdatedAtWallMs)
                .putInt("battery_level", intValue(value.batteryLevel))
                .putString("battery_level_source", value.batteryLevelSource)
                .putInt("battery_charging", booleanValue(value.batteryCharging))
                .putInt("battery_charging_estimated",
                        booleanValue(value.batteryChargingEstimated))
                .putString("battery_charging_source", value.batteryChargingSource)
                .putInt("battery_external_power", booleanValue(value.batteryExternalPower))
                .putString("battery_charge_state", value.batteryChargeState)
                .putString("battery_charge_level", value.batteryChargeLevel)
                .putInt("network_available", booleanValue(value.networkAvailable))
                .putInt("network_signal", intValue(value.networkSignal))
                .putInt("network_roaming", booleanValue(value.networkRoaming))
                .putString("network_operator", value.networkOperator)
                .putString("network_type", value.networkType)
                .apply();
    }

    static boolean isFresh(long updatedAtWallMs, long nowWallMs) {
        return updatedAtWallMs > 0L && nowWallMs >= updatedAtWallMs
                && nowWallMs - updatedAtWallMs <= RETENTION_MS;
    }

    @NonNull private static String normalizeAddress(@Nullable String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.US);
    }

    private static int intValue(@Nullable Integer value) {
        return value == null ? -1 : value;
    }

    @Nullable private static Integer nullableInt(int value) {
        return value < 0 ? null : value;
    }

    private static int booleanValue(@Nullable Boolean value) {
        return value == null ? -1 : value ? 1 : 0;
    }

    @Nullable private static Boolean nullableBoolean(int value) {
        return value < 0 ? null : value != 0;
    }

    static final class Record {
        @NonNull final String address;
        final long updatedAtWallMs;
        final long batteryUpdatedAtWallMs;
        final long networkUpdatedAtWallMs;
        @Nullable final Integer batteryLevel;
        @NonNull final String batteryLevelSource;
        @Nullable final Boolean batteryCharging;
        @Nullable final Boolean batteryChargingEstimated;
        @NonNull final String batteryChargingSource;
        @Nullable final Boolean batteryExternalPower;
        @NonNull final String batteryChargeState;
        @NonNull final String batteryChargeLevel;
        @Nullable final Boolean networkAvailable;
        @Nullable final Integer networkSignal;
        @Nullable final Boolean networkRoaming;
        @NonNull final String networkOperator;
        @NonNull final String networkType;

        Record(@NonNull String address, long batteryUpdatedAtWallMs,
               long networkUpdatedAtWallMs,
               @Nullable Integer batteryLevel, @Nullable String batteryLevelSource,
               @Nullable Boolean batteryCharging,
               @Nullable Boolean batteryChargingEstimated,
               @Nullable String batteryChargingSource,
               @Nullable Boolean batteryExternalPower,
               @Nullable String batteryChargeState, @Nullable String batteryChargeLevel,
               @Nullable Boolean networkAvailable, @Nullable Integer networkSignal,
               @Nullable Boolean networkRoaming, @Nullable String networkOperator,
               @Nullable String networkType) {
            this.address = normalizeAddress(address);
            this.batteryUpdatedAtWallMs = batteryUpdatedAtWallMs;
            this.networkUpdatedAtWallMs = networkUpdatedAtWallMs;
            this.updatedAtWallMs = Math.max(batteryUpdatedAtWallMs, networkUpdatedAtWallMs);
            this.batteryLevel = batteryLevel;
            this.batteryLevelSource = text(batteryLevelSource);
            String chargingSource = text(batteryChargingSource);
            boolean calculatedCharging = Boolean.TRUE.equals(batteryChargingEstimated)
                    || chargingSource.endsWith("_trend");
            boolean explicitCharging = batteryCharging != null && !calculatedCharging;
            this.batteryCharging = explicitCharging ? batteryCharging : null;
            this.batteryChargingEstimated = explicitCharging ? Boolean.FALSE : null;
            this.batteryChargingSource = explicitCharging ? chargingSource : "";
            this.batteryExternalPower = batteryExternalPower;
            this.batteryChargeState = calculatedCharging ? "" : text(batteryChargeState);
            this.batteryChargeLevel = text(batteryChargeLevel);
            this.networkAvailable = networkAvailable;
            this.networkSignal = networkSignal;
            this.networkRoaming = networkRoaming;
            this.networkOperator = text(networkOperator);
            this.networkType = text(networkType);
        }

        boolean hasUsefulData() {
            return batteryLevel != null || batteryCharging != null
                    || batteryExternalPower != null || !batteryChargeState.isEmpty()
                    || !batteryChargeLevel.isEmpty() || networkAvailable != null
                    || networkSignal != null || networkRoaming != null
                    || !networkOperator.isEmpty()
                    || !networkType.isEmpty();
        }

        @NonNull String fingerprint() {
            return address + '|' + batteryUpdatedAtWallMs + '|'
                    + networkUpdatedAtWallMs + '|' + batteryLevel + '|'
                    + batteryLevelSource + '|' + batteryCharging + '|'
                    + batteryChargingEstimated + '|' + batteryChargingSource + '|'
                    + batteryExternalPower + '|' + batteryChargeState + '|'
                    + batteryChargeLevel + '|' + networkAvailable + '|'
                    + networkSignal + '|' + networkRoaming + '|'
                    + networkOperator + '|' + networkType;
        }

        @NonNull private static String text(@Nullable String value) {
            return value == null ? "" : value;
        }
    }
}
