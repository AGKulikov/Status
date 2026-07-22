/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher.vehicle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure calculations shared by the vehicle HUD renderer and its local unit tests. */
public final class VehicleDerivedMetrics {
    public static final double DEFAULT_FUEL_CAPACITY_LITRES = 64d;
    public static final String REFILL_FUEL_ID = "Derived.refill_fuel";
    public static final String TURN_SIGNALS_ID = "Derived.turn_signals";
    public static final String SPEED_LIMIT_WARNING_ID = "Derived.speed_limit_warning";
    public static final String AUTO_HOLD_ID = "External.auto_hold";

    public static final int TURN_OFF = 0;
    public static final int TURN_LEFT = 1;
    public static final int TURN_RIGHT = 2;
    public static final int TURN_HAZARD = 3;

    private static final Pattern FIRST_NUMBER = Pattern.compile("[-+]?\\d+(?:[.,]\\d+)?");

    private VehicleDerivedMetrics() {}

    /** Fuel signal is millilitres on the ECARX firmware used by mHUD. */
    public static double fuelLitres(double rawFuel) {
        return rawFuel / 1_000d;
    }

    /** Defensive capacity normalization: AdaptAPI normally returns litres, some ports use ml. */
    public static double capacityLitres(double rawCapacity) {
        return rawCapacity > 500d ? rawCapacity / 1_000d : rawCapacity;
    }

    /** Uses the known Monjaro tank volume until optional ICarInfo capacity becomes available. */
    public static double capacityLitresOrDefault(@Nullable Double rawCapacity,
            double fallbackLitres) {
        double fallback = Double.isFinite(fallbackLitres) && fallbackLitres > 0d
                ? fallbackLitres : DEFAULT_FUEL_CAPACITY_LITRES;
        if (rawCapacity == null || !Double.isFinite(rawCapacity) || rawCapacity <= 0d) {
            return fallback;
        }
        double normalized = capacityLitres(rawCapacity);
        return Double.isFinite(normalized) && normalized > 0d ? normalized : fallback;
    }

    public static double refillLitres(double rawFuel, double rawCapacity) {
        return Math.max(0d, capacityLitres(rawCapacity) - fuelLitres(rawFuel));
    }

    public static boolean isPark(double rawGear) {
        long value = Math.round(rawGear);
        return value == 2_097_712L;
    }

    public static int turnState(double left, double right) {
        boolean leftOn = left >= .5d;
        boolean rightOn = right >= .5d;
        if (leftOn && rightOn) return TURN_HAZARD;
        if (leftOn) return TURN_LEFT;
        if (rightOn) return TURN_RIGHT;
        return TURN_OFF;
    }

    @NonNull
    public static String turnText(int state) {
        if (state == TURN_LEFT) return "←";
        if (state == TURN_RIGHT) return "→";
        if (state == TURN_HAZARD) return "↔";
        return "Выкл";
    }

    /** Reads values such as "60", "60 км/ч" and "60.0" without accepting garbage. */
    public static double parseSpeedLimit(@Nullable String text) {
        if (text == null) return Double.NaN;
        Matcher matcher = FIRST_NUMBER.matcher(text.trim());
        if (!matcher.find()) return Double.NaN;
        try {
            double value = Double.parseDouble(matcher.group().replace(',', '.'));
            return Double.isFinite(value) && value > 0d && value <= 300d ? value : Double.NaN;
        } catch (NumberFormatException ignored) {
            return Double.NaN;
        }
    }

    public static double speedKmh(double rawSpeed) {
        return rawSpeed * 3.72d;
    }

    /** Positive result is the amount above the configured allowed limit, otherwise zero. */
    public static double speedExcess(double rawSpeed, double speedLimit, int allowedDeltaKmh) {
        if (!Double.isFinite(rawSpeed) || !Double.isFinite(speedLimit)) return Double.NaN;
        double allowed = speedLimit + Math.max(0, allowedDeltaKmh);
        return Math.max(0d, speedKmh(rawSpeed) - allowed);
    }

    /** Exact permissive state contract used by the mHUD-compatible Auto Hold publisher. */
    @Nullable
    public static Boolean booleanState(@Nullable Object raw) {
        if (raw instanceof Boolean) return (Boolean) raw;
        if (raw instanceof Number) return ((Number) raw).longValue() != 0L;
        if (raw instanceof String) {
            String value = ((String) raw).trim().toLowerCase(Locale.ROOT);
            if ("1".equals(value) || "true".equals(value)) return true;
            if ("0".equals(value) || "false".equals(value)) return false;
        }
        return null;
    }
}
