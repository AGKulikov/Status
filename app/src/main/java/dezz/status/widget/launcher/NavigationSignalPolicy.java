/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Pure parsing/arbitration rules shared by the mHUD-compatible navigation transports. */
final class NavigationSignalPolicy {
    static final long DYNAMIC_TRAFFIC_STALE_MS = 4_000L;
    static final long STATIC_TRAFFIC_STALE_MS = 120_000L;
    private static final double JUNCTION_HANDOFF_METERS = 350.0d;
    private static final int MAX_LANE_RECORDS = 64;

    static final class LaneRecord {
        final int rawIndex;
        final String lanes;
        final double latitude;
        final double longitude;
        final double distanceMeters;
        final boolean alongRoute;

        LaneRecord(int rawIndex, String lanes, double latitude, double longitude,
                double distanceMeters, boolean alongRoute) {
            this.rawIndex = rawIndex;
            this.lanes = lanes;
            this.latitude = latitude;
            this.longitude = longitude;
            this.distanceMeters = distanceMeters;
            this.alongRoute = alongRoute;
        }

        boolean sameJunction(String previousLanes, double previousLatitude,
                double previousLongitude) {
            if (hasCoordinates() && validCoordinate(previousLatitude, previousLongitude)) {
                return coordinateE5(latitude) == coordinateE5(previousLatitude)
                        && coordinateE5(longitude) == coordinateE5(previousLongitude);
            }
            return !lanes.isEmpty() && lanes.equals(previousLanes);
        }

        boolean hasCoordinates() {
            return validCoordinate(latitude, longitude);
        }
    }

    static final class LaneSelection {
        final List<LaneRecord> records;
        final LaneRecord selected;

        LaneSelection(List<LaneRecord> records, LaneRecord selected) {
            this.records = Collections.unmodifiableList(new ArrayList<>(records));
            this.selected = selected;
        }
    }

    /**
     * Parses Yandex's parallel semicolon lists without discarding empty coordinate positions.
     * The mHUD receiver treats each lanes/lat/lon triplet as one junction key.
     */
    static LaneSelection selectLanes(String rawLanes, String rawLatitudes,
            String rawLongitudes, String rawDistances, String rawAlongRoute,
            String previousLanes, double previousLatitude, double previousLongitude,
            double fallbackDistanceMeters) {
        String[] lanes = split(rawLanes);
        String[] latitudes = split(rawLatitudes);
        String[] longitudes = split(rawLongitudes);
        String[] distances = split(rawDistances);
        String[] alongRoute = split(rawAlongRoute);
        List<LaneRecord> records = new ArrayList<>();
        for (int index = 0; index < lanes.length && records.size() < MAX_LANE_RECORDS; index++) {
            String laneValue = lanes[index].trim();
            if (laneValue.isEmpty()) continue;
            double latitude = numberAt(latitudes, index, Double.NaN);
            double longitude = numberAt(longitudes, index, Double.NaN);
            double distance = numberAt(distances, index, Double.NaN);
            // dist_m is scalar in the known contract and refers to the first/active record.
            if (index == 0 && !isDistance(distance) && isDistance(fallbackDistanceMeters)) {
                distance = fallbackDistanceMeters;
            }
            boolean explicitAlongRoute = booleanAt(alongRoute, index,
                    index == 0 && booleanAt(alongRoute, 0, false));
            // mHUD regards a geocoded LANE_SIGN record as route-associated even when the old
            // publisher omitted along_route. Explicit true records still win selection below.
            boolean routeAssociated = explicitAlongRoute || validCoordinate(latitude, longitude);
            records.add(new LaneRecord(index, laneValue, latitude, longitude, distance,
                    routeAssociated));
        }
        if (records.isEmpty()) return new LaneSelection(records, null);

        LaneRecord best = closestAlongRoute(records);
        if (best == null) best = closestWithDistance(records);
        if (best == null) best = records.get(0);

        // Yandex occasionally reorders a multi-junction list while still approaching the same
        // sign. mHUD keeps the current key until the handoff zone, avoiding UI jumps backwards.
        if (!isDistance(best.distanceMeters)
                || best.distanceMeters > JUNCTION_HANDOFF_METERS) {
            for (LaneRecord record : records) {
                if (record.sameJunction(previousLanes, previousLatitude, previousLongitude)) {
                    best = record;
                    break;
                }
            }
        }
        return new LaneSelection(records, best);
    }

    private static LaneRecord closestAlongRoute(List<LaneRecord> records) {
        LaneRecord first = null;
        LaneRecord closest = null;
        for (LaneRecord record : records) {
            if (!record.alongRoute) continue;
            if (first == null) first = record;
            if (isDistance(record.distanceMeters)
                    && (closest == null || record.distanceMeters < closest.distanceMeters)) {
                closest = record;
            }
        }
        return closest == null ? first : closest;
    }

    private static LaneRecord closestWithDistance(List<LaneRecord> records) {
        LaneRecord result = null;
        for (LaneRecord record : records) {
            if (isDistance(record.distanceMeters)
                    && (result == null || record.distanceMeters < result.distanceMeters)) {
                result = record;
            }
        }
        return result;
    }

    static boolean validTrafficColor(String color) {
        return "RED".equals(color) || "YELLOW".equals(color) || "GREEN".equals(color);
    }

    static int sourceRank(String source) {
        String normalized = normalizeSource(source);
        if ("yandex_windshield".equals(normalized)) return 30;
        if ("ui".equals(normalized)) return 10;
        return 20;
    }

    static boolean isUiSource(String source) {
        return "ui".equals(normalizeSource(source));
    }

    static boolean isWindshieldSource(String source) {
        return "yandex_windshield".equals(normalizeSource(source));
    }

    static boolean sourceMayReplace(String oldSource, String newSource) {
        int oldRank = sourceRank(oldSource);
        int newRank = sourceRank(newSource);
        return newRank > oldRank
                || (newRank == oldRank
                && normalizeSource(oldSource).equals(normalizeSource(newSource)));
    }

    static boolean sourceRequiresTransitionValidation(String oldSource, String newSource) {
        return sourceRank(oldSource) == sourceRank(newSource)
                && normalizeSource(oldSource).equals(normalizeSource(newSource));
    }

    static boolean sameSlot(String firstId, int firstPosition, String secondId,
            int secondPosition) {
        if (!trim(firstId).isEmpty() && !trim(secondId).isEmpty()) {
            return trim(firstId).equals(trim(secondId));
        }
        if (firstPosition >= 0 && secondPosition >= 0) return firstPosition == secondPosition;
        return trim(firstId).isEmpty() && trim(secondId).isEmpty()
                && firstPosition < 0 && secondPosition < 0;
    }

    /**
     * mHUD-compatible sticky phase validation. Countdown changes may drift by one second; large
     * jumps and impossible phase changes are rejected until the incumbent becomes stale.
     */
    static boolean acceptsTransition(String oldColor, String oldCountdown,
            String oldPhaseOrigin, long oldUpdatedAt, String newColor, String newCountdown,
            long now) {
        if (!validTrafficColor(newColor)) return false;
        if (!validTrafficColor(oldColor) || oldUpdatedAt <= 0L || now < oldUpdatedAt) return true;

        int oldSeconds = countdown(oldCountdown);
        int newSeconds = countdown(newCountdown);
        long age = now - oldUpdatedAt;
        long staleAfter = oldSeconds >= 0 ? DYNAMIC_TRAFFIC_STALE_MS
                : STATIC_TRAFFIC_STALE_MS;
        if (age >= staleAfter) return true;

        int expected = oldSeconds < 0 ? 0
                : Math.max(oldSeconds - (int) (age / 1_000L), 0);
        if (oldColor.equals(newColor)) {
            return newSeconds < 0 || expected <= 1 || Math.abs(newSeconds - expected) <= 1;
        }

        if ("YELLOW".equals(newColor)) {
            return expected <= 4 && age < DYNAMIC_TRAFFIC_STALE_MS;
        }
        if ("YELLOW".equals(oldColor)) {
            return age < DYNAMIC_TRAFFIC_STALE_MS
                    && opposite(oldPhaseOrigin, newColor);
        }
        return opposite(oldColor, newColor)
                && expected <= 4 && age < DYNAMIC_TRAFFIC_STALE_MS;
    }

    static String phaseOriginAfter(String oldColor, String oldPhaseOrigin, String newColor) {
        if ("YELLOW".equals(newColor) && !"YELLOW".equals(oldColor)
                && validTrafficColor(oldColor)) return oldColor;
        if ("YELLOW".equals(oldColor) && !"YELLOW".equals(newColor)) return "";
        return "YELLOW".equals(newColor) ? trim(oldPhaseOrigin).toUpperCase(Locale.ROOT) : "";
    }

    static boolean fresh(long updatedAt, String countdown, long now) {
        if (updatedAt <= 0L || now < updatedAt) return false;
        return now - updatedAt <= (countdown(countdown) >= 0
                ? DYNAMIC_TRAFFIC_STALE_MS : STATIC_TRAFFIC_STALE_MS);
    }

    static int countdown(String raw) {
        try {
            return Integer.parseInt(trim(raw));
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static boolean opposite(String first, String second) {
        return ("RED".equals(first) && "GREEN".equals(second))
                || ("GREEN".equals(first) && "RED".equals(second));
    }

    private static String normalizeSource(String source) {
        return trim(source).toLowerCase(Locale.ROOT);
    }

    private static boolean validCoordinate(double latitude, double longitude) {
        return Double.isFinite(latitude) && Double.isFinite(longitude)
                && latitude >= -90.0d && latitude <= 90.0d
                && longitude >= -180.0d && longitude <= 180.0d
                && latitude != 0.0d && longitude != 0.0d;
    }

    private static long coordinateE5(double value) {
        return Math.round(value * 100_000.0d);
    }

    private static boolean isDistance(double value) {
        return Double.isFinite(value) && value >= 0.0d;
    }

    private static String[] split(String raw) {
        return trim(raw).split(";", -1);
    }

    private static double numberAt(String[] values, int index, double fallback) {
        if (index < 0 || index >= values.length) return fallback;
        try {
            return Double.parseDouble(values[index].trim().replace(',', '.'));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static boolean booleanAt(String[] values, int index, boolean fallback) {
        if (index < 0 || index >= values.length) return fallback;
        String value = values[index].trim().toLowerCase(Locale.ROOT);
        if ("true".equals(value) || "1".equals(value) || "yes".equals(value)) return true;
        if ("false".equals(value) || "0".equals(value) || "no".equals(value)) return false;
        return fallback;
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private NavigationSignalPolicy() {}
}
