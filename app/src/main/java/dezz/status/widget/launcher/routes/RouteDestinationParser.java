/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher.routes;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.math.BigDecimal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure-Java validation and normalization of the mNavi-compatible coordinate chain. */
public final class RouteDestinationParser {
    private static final Pattern POINT = Pattern.compile(
            "([-+]?\\d+(?:\\.\\d+)?)\\s*,\\s*([-+]?\\d+(?:\\.\\d+)?)");
    private static final Pattern SEPARATOR = Pattern.compile("[\\s;]*");

    private RouteDestinationParser() {}

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
        String input = clean(coordinates);
        if (input.isEmpty()) throw new IllegalArgumentException("Coordinates are empty");
        Matcher matcher = POINT.matcher(input);
        StringBuilder result = new StringBuilder();
        int end = 0;
        int count = 0;
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
            result.append('~').append(number(matcher.group(1)))
                    .append(',').append(number(matcher.group(2)));
            count++;
            end = matcher.end();
        }
        if (count == 0 || !SEPARATOR.matcher(input.substring(end)).matches()) {
            throw new IllegalArgumentException("Invalid coordinates");
        }
        return result.toString();
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
