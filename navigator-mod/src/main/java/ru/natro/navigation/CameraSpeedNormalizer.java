/* SPDX-License-Identifier: GPL-3.0-or-later */
package ru.natro.navigation;

import java.util.Locale;

/** Converts every camera source to the one unit rendered by Natro: whole km/h. */
final class CameraSpeedNormalizer {
    private static final double MPH_TO_KMH = 1.609344d;
    private static final double MPS_TO_KMH = 3.6d;
    private static final int MAX_REASONABLE_KMH = 400;

    private CameraSpeedNormalizer() {}

    /** Yandex MapKit publishes road-event and effective camera limits in metres per second. */
    static int fromMapKitMetersPerSecond(double metresPerSecond) {
        return roundedKmh(metresPerSecond * MPS_TO_KMH);
    }

    /**
     * HUD Speed publishes the numeric value together with its display unit. Older bridge frames
     * did not include the unit, so an absent/unknown value deliberately remains km/h for backward
     * compatibility instead of being multiplied heuristically.
     */
    static int fromExternal(double value, String rawUnit) {
        if (!Double.isFinite(value) || value <= 0d) return -1;
        String unit = rawUnit == null ? "" : rawUnit.trim().toUpperCase(Locale.ROOT);
        double kmh;
        switch (unit) {
            case "MPH":
                kmh = value * MPH_TO_KMH;
                break;
            case "MPS":
            case "M/S":
                kmh = value * MPS_TO_KMH;
                break;
            case "KPH":
            case "KMH":
            case "KM/H":
            case "":
            default:
                kmh = value;
                break;
        }
        return roundedKmh(kmh);
    }

    private static int roundedKmh(double value) {
        if (!Double.isFinite(value) || value <= 0d || value > MAX_REASONABLE_KMH + .5d) {
            return -1;
        }
        int rounded = (int) Math.round(value);
        return rounded > 0 && rounded <= MAX_REASONABLE_KMH ? rounded : -1;
    }
}
