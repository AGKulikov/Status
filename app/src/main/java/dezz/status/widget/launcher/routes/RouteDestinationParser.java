/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher.routes;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure-Java validation and normalization of the mNavi-compatible coordinate chain. */
public final class RouteDestinationParser {
    private static final Pattern POINT = Pattern.compile(
            "([-+]?\\d+(?:\\.\\d+)?)\\s*,\\s*([-+]?\\d+(?:\\.\\d+)?)");
    private static final Pattern SEPARATOR = Pattern.compile("[\\s;]*");

    private RouteDestinationParser() {}

    /** One validated point in the order entered by the user. */
    public static final class Coordinate {
        @NonNull public final String latitude;
        @NonNull public final String longitude;

        Coordinate(@NonNull String latitude, @NonNull String longitude) {
            this.latitude = latitude;
            this.longitude = longitude;
        }
    }

    /** Coordinates take priority; otherwise the trimmed address is returned. */
    @NonNull
    public static String routeText(@Nullable String address, @Nullable String coordinates) {
        String coordinateValue = clean(coordinates);
        if (!coordinateValue.isEmpty()) return coordinateRouteText(coordinateValue);
        return clean(address);
    }

    /** Returns `~lat,lon~lat,lon`, exactly as consumed by Yandex's `rtext`. */
    @NonNull
    public static String coordinateRouteText(@NonNull String coordinates) {
        StringBuilder result = new StringBuilder();
        for (Coordinate point : coordinatePoints(coordinates)) {
            result.append('~').append(point.latitude).append(',').append(point.longitude);
        }
        return result.toString();
    }

    /**
     * Parses the coordinate chain once for both the legacy Maps {@code rtext} link and the
     * official Navigator {@code build_route_on_map} parameters.
     */
    @NonNull
    public static List<Coordinate> coordinatePoints(@NonNull String coordinates) {
        String input = clean(coordinates);
        if (input.isEmpty()) throw new IllegalArgumentException("Coordinates are empty");
        Matcher matcher = POINT.matcher(input);
        List<Coordinate> result = new ArrayList<>();
        int end = 0;
        while (matcher.find()) {
            if (!SEPARATOR.matcher(input.substring(end, matcher.start())).matches()) {
                throw new IllegalArgumentException("Unexpected coordinate separator");
            }
            double latitude = Double.parseDouble(matcher.group(1));
            double longitude = Double.parseDouble(matcher.group(2));
            if (!Double.isFinite(latitude) || latitude < -90d || latitude > 90d) {
                throw new IllegalArgumentException("Latitude is out of range");
            }
            if (!Double.isFinite(longitude) || longitude < -180d || longitude > 180d) {
                throw new IllegalArgumentException("Longitude is out of range");
            }
            result.add(new Coordinate(number(matcher.group(1)), number(matcher.group(2))));
            end = matcher.end();
        }
        if (result.isEmpty() || !SEPARATOR.matcher(input.substring(end)).matches()) {
            throw new IllegalArgumentException("Invalid coordinates");
        }
        return Collections.unmodifiableList(result);
    }

    public static boolean hasDestination(@Nullable String address, @Nullable String coordinates) {
        if (!clean(coordinates).isEmpty()) {
            try { coordinateRouteText(coordinates); return true; }
            catch (IllegalArgumentException ignored) { return false; }
        }
        return !clean(address).isEmpty();
    }

    @NonNull private static String clean(@Nullable String value) {
        return value == null ? "" : value.trim();
    }

    @NonNull private static String number(@NonNull String value) {
        return new BigDecimal(value).stripTrailingZeros().toPlainString();
    }
}
