/* SPDX-License-Identifier: GPL-3.0-or-later */
package ru.natro.navigation;

/** Selects a live vehicle position independently of a retained route's progress point. */
final class NavigationPositionPolicy {
    static final long ROUTE_MATCH_HOLD_MS = 2_500L;

    static final class Position {
        final double latitude, longitude, heading;
        final String source;

        Position(double latitude, double longitude, double heading, String source) {
            this.latitude = latitude;
            this.longitude = longitude;
            this.heading = heading;
            this.source = source;
        }
    }

    private Position lastMatched;
    private long lastMatchedAt;

    static boolean mayUseRoutePosition(boolean routeActive, String status) {
        return routeActive && ("ON_ROUTE".equals(status)
                || "RETURNED_TO_ROUTE".equals(status) || "WAY_POINT_REACHED".equals(status));
    }

    Position select(boolean routeActive, String status, long now,
                    double latitude, double longitude, double heading,
                    double routeLatitude, double routeLongitude, double routeHeading) {
        if (!mayUseRoutePosition(routeActive, status)) {
            // A retained DrivingRoute still exposes its last RoutePosition after onRouteLost.
            // Even a valid point and a recently refreshed hold cache must not freeze the car.
            reset();
            return new Position(latitude, longitude, heading, "GUIDANCE_LOCATION");
        }
        if (validPoint(routeLatitude, routeLongitude)) {
            lastMatched = new Position(routeLatitude, routeLongitude,
                    finite(routeHeading) ? routeHeading : heading, "ROUTE_POSITION");
            lastMatchedAt = now;
            return lastMatched;
        }
        if (lastMatched != null && now >= lastMatchedAt
                && now - lastMatchedAt <= ROUTE_MATCH_HOLD_MS) {
            return new Position(lastMatched.latitude, lastMatched.longitude,
                    lastMatched.heading, "ON_ROUTE_GAP");
        }
        reset();
        return new Position(latitude, longitude, heading, "GUIDANCE_LOCATION");
    }

    void reset() {
        lastMatched = null;
        lastMatchedAt = 0L;
    }

    private static boolean validPoint(double latitude, double longitude) {
        return finite(latitude) && latitude >= -90d && latitude <= 90d
                && finite(longitude) && longitude >= -180d && longitude <= 180d;
    }

    private static boolean finite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }
}
