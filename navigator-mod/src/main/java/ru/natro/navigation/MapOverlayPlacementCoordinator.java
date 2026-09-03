/* SPDX-License-Identifier: GPL-3.0-or-later */
package ru.natro.navigation;

import android.graphics.RectF;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Chooses a stable screen-facing side for Natro balloons sharing one MapWindow.
 *
 * <p>The exact source coordinate never moves. Centred camera signs and the vehicle cursor reserve
 * their screen footprint. Movable lane/traffic-light cards then score all eight stock Yandex leg
 * positions against the viewport, those fixed objects, other cards and the projected active-route
 * corridor. Because route points are projected for the current MapWindow, camera azimuth, tilt and
 * zoom are accounted for without guessing a geographical left/right side.</p>
 */
final class MapOverlayPlacementCoordinator {
    static final String OWNER_LANES = "lanes";
    static final String OWNER_TRAFFIC_LIGHTS = "traffic_lights";
    static final String OWNER_CAMERAS = "cameras";
    private static final String OWNER_CURSOR = "cursor";

    private static final float VIEWPORT_MARGIN_PX = 6f;
    private static final float ITEM_MARGIN_PX = 8f;
    private static final float ROUTE_CLEARANCE_PX = 9f;
    /** Bounded approach scan protects a looping/S-shaped road without projecting a whole route. */
    private static final int ROUTE_APPROACH_SEGMENTS = 64;
    private static final int ROUTE_AFTER_EVENT_SEGMENTS = 48;
    private static final double OUT_OF_BOUNDS_WEIGHT = 50_000d;
    private static final double OVERLAP_WEIGHT = 200d;
    private static final double ROUTE_APPROACH_WEIGHT = 8_000d;
    private static final double ROUTE_FORWARD_WEIGHT = 12_000d;
    /** A short turn is more safety-critical than the same number of straight hidden pixels. */
    private static final double ROUTE_TURN_BONUS = 2.5d;
    /** Prevents one-pixel score noise from making a balloon alternate between two sides. */
    private static final double SLOT_CHANGE_PENALTY = 30_000d;
    private static final String[] PLACEMENT_LEG_NAMES = {
            "LEFT_CENTER", "RIGHT_CENTER", "BOTTOM_LEFT", "BOTTOM_RIGHT",
            "TOP_LEFT", "TOP_RIGHT", "BOTTOM_CENTER", "TOP_CENTER"
    };

    private final ArrayList<Reservation> reservations = new ArrayList<>();
    private final ArrayList<RoutePoint> routePoints = new ArrayList<>();
    private Object mapWindow;
    private int viewportWidth;
    private int viewportHeight;
    private long routeEpoch = Long.MIN_VALUE;
    private boolean routeActive;
    private int currentRouteSegmentIndex = -1;
    private double currentRouteSegmentPosition = Double.NaN;
    private double vehicleLatitude = Double.NaN;
    private double vehicleLongitude = Double.NaN;
    private int cursorFootprintPx;
    private float[][] projectedRoutePoints;

    void attach(Object nextMapWindow, int width, int height) {
        mapWindow = nextMapWindow;
        viewportWidth = Math.max(1, width);
        viewportHeight = Math.max(1, height);
        reservations.clear();
        projectedRoutePoints = null;
    }

    void detach() {
        reservations.clear();
        mapWindow = null;
        viewportWidth = 0;
        viewportHeight = 0;
        projectedRoutePoints = null;
    }

    /** Caches immutable route coordinates once per route epoch; no MapKit object is retained. */
    void updateRoute(long nextRouteEpoch, Object drivingRoute) {
        if (drivingRoute != null && nextRouteEpoch == routeEpoch && !routePoints.isEmpty()) return;
        routeEpoch = nextRouteEpoch;
        routePoints.clear();
        projectedRoutePoints = null;
        if (drivingRoute == null) return;
        try {
            Object geometry = invoke(drivingRoute, "getGeometry", new Class<?>[0]);
            Object rawPoints = geometry == null ? null
                    : invoke(geometry, "getPoints", new Class<?>[0]);
            if (!(rawPoints instanceof List)) return;
            for (Object point : (List<?>) rawPoints) {
                double latitude = Double.NaN;
                double longitude = Double.NaN;
                try {
                    latitude = ((Number) invoke(
                            point, "getLatitude", new Class<?>[0])).doubleValue();
                    longitude = ((Number) invoke(
                            point, "getLongitude", new Class<?>[0])).doubleValue();
                } catch (Throwable invalidPoint) {
                    // Keep a placeholder below so every later segment index still lines up.
                }
                // Preserve list indices even if one transient wrapper is invalid: Windshield
                // PolylinePosition.segmentIndex refers to this exact original point sequence.
                routePoints.add(new RoutePoint(latitude, longitude));
            }
        } catch (Throwable unavailable) {
            routePoints.clear();
        }
    }

    /** Supplies current progress and the real cursor footprint for collision protection. */
    void updateNavigationState(boolean active, boolean progressValid, int segmentIndex,
                               double segmentPosition, double latitude, double longitude,
                               int cursorSizePx) {
        routeActive = active;
        currentRouteSegmentIndex = active && progressValid ? Math.max(0, segmentIndex) : -1;
        currentRouteSegmentPosition = active && progressValid
                ? Math.max(0d, Math.min(1d, segmentPosition)) : Double.NaN;
        vehicleLatitude = latitude;
        vehicleLongitude = longitude;
        cursorFootprintPx = active && validCoordinate(latitude, longitude)
                ? Math.max(0, cursorSizePx) : 0;
    }

    /** Starts one deterministic high-to-low priority placement pass. */
    void beginLayout() {
        reservations.clear();
        projectedRoutePoints = routePoints.isEmpty()
                ? null : new float[routePoints.size()][];
        if (cursorFootprintPx <= 0 || !validCoordinate(vehicleLatitude, vehicleLongitude)) return;
        float[] screen = projectOrNull(vehicleLatitude, vehicleLongitude);
        if (screen == null) return;
        Candidate centered = new Candidate(.50f, .50f, "CENTER");
        RectF occupied = rect(screen[0], screen[1], cursorFootprintPx,
                cursorFootprintPx, centered);
        occupied.inset(-ITEM_MARGIN_PX, -ITEM_MARGIN_PX);
        reservations.add(new Reservation(OWNER_CURSOR, OWNER_CURSOR, occupied));
    }

    void clearOwner(String owner) {
        for (int index = reservations.size() - 1; index >= 0; index--) {
            if (owner.equals(reservations.get(index).owner)) reservations.remove(index);
        }
    }

    Placement reserve(String owner, String key, double latitude, double longitude,
                      int bitmapWidth, int bitmapHeight, boolean preferRight) {
        return reserve(owner, key, latitude, longitude, bitmapWidth, bitmapHeight,
                preferRight, -1, Double.NaN, null);
    }

    /** Scores every stock leg against the visible future route and the previous stable slot. */
    Placement reserve(String owner, String key, double latitude, double longitude,
                      int bitmapWidth, int bitmapHeight, boolean preferRight,
                      int routeSegmentIndex, double routeSegmentPosition,
                      Placement previous) {
        return reserve(owner, key, latitude, longitude, bitmapWidth, bitmapHeight,
                preferRight, routeSegmentIndex, routeSegmentPosition, previous, null);
    }

    /**
     * Scores the real per-leg bitmap bounds when a stock renderer exposes them. The fallback
     * width/height and anchors remain available for regional implementations without geometry.
     */
    Placement reserve(String owner, String key, double latitude, double longitude,
                      int bitmapWidth, int bitmapHeight, boolean preferRight,
                      int routeSegmentIndex, double routeSegmentPosition,
                      Placement previous, List<Footprint> footprints) {
        float[] screen = projectOrNull(latitude, longitude);
        int safeWidth = Math.max(1, bitmapWidth);
        int safeHeight = Math.max(1, bitmapHeight);
        Candidate[] candidates = candidates(preferRight);
        if (screen == null) {
            Candidate fallback = candidates[0];
            Footprint measured = footprintFor(footprints, fallback.legName);
            return placement(fallback, measured);
        }
        Candidate best = candidates[0];
        Footprint bestFootprint = footprintFor(footprints, best.legName);
        RectF bestRect = rect(screen[0], screen[1], safeWidth, safeHeight,
                best, bestFootprint);
        double bestScore = score(bestRect, best, 0, previous, screen,
                latitude, longitude, routeSegmentIndex, routeSegmentPosition);
        for (int index = 1; index < candidates.length; index++) {
            Candidate candidate = candidates[index];
            Footprint footprint = footprintFor(footprints, candidate.legName);
            RectF bounds = rect(screen[0], screen[1], safeWidth, safeHeight,
                    candidate, footprint);
            double score = score(bounds, candidate, index, previous, screen,
                    latitude, longitude, routeSegmentIndex, routeSegmentPosition);
            if (score < bestScore) {
                best = candidate;
                bestFootprint = footprint;
                bestRect = bounds;
                bestScore = score;
            }
        }
        RectF occupied = new RectF(bestRect);
        occupied.inset(-ITEM_MARGIN_PX, -ITEM_MARGIN_PX);
        reservations.add(new Reservation(owner, key, occupied));
        return placement(best, bestFootprint);
    }

    /** Reserves an immovable placemark whose visual centre must remain on its map coordinate. */
    Placement reserveCentered(String owner, String key, double latitude, double longitude,
                              int bitmapWidth, int bitmapHeight) {
        float[] screen = projectOrNull(latitude, longitude);
        int safeWidth = Math.max(1, bitmapWidth);
        int safeHeight = Math.max(1, bitmapHeight);
        Candidate centered = new Candidate(.50f, .50f, "CENTER");
        if (screen == null) {
            return new Placement(centered.anchorX, centered.anchorY, centered.legName);
        }
        RectF occupied = rect(screen[0], screen[1], safeWidth, safeHeight, centered);
        occupied.inset(-ITEM_MARGIN_PX, -ITEM_MARGIN_PX);
        reservations.add(new Reservation(owner, key, occupied));
        return new Placement(centered.anchorX, centered.anchorY, centered.legName);
    }

    private float[] projectOrNull(double latitude, double longitude) {
        Object currentWindow = mapWindow;
        if (currentWindow != null) {
            try {
                Class<?> pointClass = Class.forName("com.yandex.mapkit.geometry.Point");
                Object point = pointClass.getConstructor(double.class, double.class)
                        .newInstance(latitude, longitude);
                Method projection = ReflectMethods.publicMethod(currentWindow.getClass(),
                        "worldToScreen", new Class<?>[]{pointClass});
                Object screenPoint = projection.invoke(currentWindow, point);
                if (screenPoint != null) {
                    float x = ((Number) ReflectMethods.publicMethod(screenPoint.getClass(),
                            "getX", new Class<?>[0]).invoke(screenPoint)).floatValue();
                    float y = ((Number) ReflectMethods.publicMethod(screenPoint.getClass(),
                            "getY", new Class<?>[0]).invoke(screenPoint)).floatValue();
                    if (Float.isFinite(x) && Float.isFinite(y)) return new float[]{x, y};
                }
            } catch (Throwable ignored) {
                // Without a real projection the caller keeps a deterministic side but does not
                // reserve an invented centre point or score an invented route position.
            }
        }
        return null;
    }

    private float[] projectedRoutePoint(int index) {
        if (index < 0 || index >= routePoints.size()) return null;
        float[][] cache = projectedRoutePoints;
        if (cache == null || cache.length != routePoints.size()) {
            cache = new float[routePoints.size()][];
            projectedRoutePoints = cache;
        }
        float[] projected = cache[index];
        if (projected != null) return projected;
        RoutePoint point = routePoints.get(index);
        if (!validCoordinate(point.latitude, point.longitude)) return null;
        projected = projectOrNull(point.latitude, point.longitude);
        if (projected != null) cache[index] = projected;
        return projected;
    }

    private double score(RectF bounds, Candidate candidate, int preferenceIndex,
                         Placement previous, float[] sourceScreen,
                         double latitude, double longitude, int routeSegmentIndex,
                         double routeSegmentPosition) {
        float safeLeft = VIEWPORT_MARGIN_PX;
        float safeTop = VIEWPORT_MARGIN_PX;
        float safeRight = Math.max(safeLeft, viewportWidth - VIEWPORT_MARGIN_PX);
        float safeBottom = Math.max(safeTop, viewportHeight - VIEWPORT_MARGIN_PX);
        double outside = Math.max(0f, safeLeft - bounds.left)
                + Math.max(0f, bounds.right - safeRight)
                + Math.max(0f, safeTop - bounds.top)
                + Math.max(0f, bounds.bottom - safeBottom);
        double overlap = 0d;
        for (Reservation reservation : reservations) {
            overlap += overlapArea(bounds, reservation.bounds);
        }
        double routeOcclusion = routeOcclusion(bounds, sourceScreen,
                latitude, longitude, routeSegmentIndex, routeSegmentPosition);
        double stability = previous != null && !candidate.legName.equals(previous.legName)
                ? SLOT_CHANGE_PENALTY : 0d;
        return outside * OUT_OF_BOUNDS_WEIGHT
                + overlap * OVERLAP_WEIGHT
                + routeOcclusion
                + stability
                + preferenceIndex;
    }

    /** Measures screen pixels of route hidden by a candidate, weighted toward the future. */
    private double routeOcclusion(RectF bounds, float[] sourceScreen,
                                  double latitude, double longitude,
                                  int suppliedSegmentIndex, double suppliedSegmentPosition) {
        if (!routeActive || routePoints.size() < 2 || mapWindow == null) return 0d;
        int segmentIndex = suppliedSegmentIndex;
        double eventSegmentPosition = suppliedSegmentPosition;
        if (segmentIndex < 0 || segmentIndex >= routePoints.size() - 1
                || !Double.isFinite(eventSegmentPosition)
                || eventSegmentPosition < 0d || eventSegmentPosition > 1d) {
            segmentIndex = nearestRouteSegment(latitude, longitude);
            eventSegmentPosition = .5d;
        }
        if (segmentIndex < 0) return 0d;
        boolean progressBeforeEvent = currentRouteSegmentIndex >= 0
                && currentRouteSegmentIndex < routePoints.size() - 1
                && Double.isFinite(currentRouteSegmentPosition)
                && (currentRouteSegmentIndex < segmentIndex
                || currentRouteSegmentIndex == segmentIndex
                && currentRouteSegmentPosition <= eventSegmentPosition);
        int first = Math.max(0, segmentIndex - ROUTE_APPROACH_SEGMENTS);
        if (progressBeforeEvent) first = Math.max(first, currentRouteSegmentIndex);
        int last = Math.min(routePoints.size() - 2,
                segmentIndex + ROUTE_AFTER_EVENT_SEGMENTS);
        RectF corridor = new RectF(bounds);
        corridor.inset(-ROUTE_CLEARANCE_PX, -ROUTE_CLEARANCE_PX);
        float[] vehicleScreen = progressBeforeEvent
                ? projectOrNull(vehicleLatitude, vehicleLongitude) : null;
        double result = 0d;
        for (int index = first; index <= last; index++) {
            float[] from = index == currentRouteSegmentIndex && vehicleScreen != null
                    ? vehicleScreen : projectedRoutePoint(index);
            float[] to = projectedRoutePoint(index + 1);
            if (from == null || to == null) continue;
            double turnMultiplier = routeTurnMultiplier(index);
            if (index < segmentIndex) {
                result += clippedSegmentLength(corridor,
                        from[0], from[1], to[0], to[1])
                        * ROUTE_APPROACH_WEIGHT * turnMultiplier;
            } else if (index == segmentIndex) {
                // Split the exact event segment at the real source point. This protects both the
                // visible approach and the departure after a left/right turn instead of replacing
                // the whole segment by one guessed direction.
                result += clippedSegmentLength(corridor,
                        from[0], from[1], sourceScreen[0], sourceScreen[1])
                        * ROUTE_APPROACH_WEIGHT * turnMultiplier;
                result += clippedSegmentLength(corridor,
                        sourceScreen[0], sourceScreen[1], to[0], to[1])
                        * ROUTE_FORWARD_WEIGHT * turnMultiplier;
            } else {
                result += clippedSegmentLength(corridor,
                        from[0], from[1], to[0], to[1])
                        * ROUTE_FORWARD_WEIGHT * turnMultiplier;
            }
        }
        return result;
    }

    /** Weights route pixels around a bend so a short turn cannot lose to a long straight. */
    private double routeTurnMultiplier(int segmentIndex) {
        double atStart = projectedBend(segmentIndex - 1, segmentIndex,
                segmentIndex + 1);
        double atEnd = projectedBend(segmentIndex, segmentIndex + 1,
                segmentIndex + 2);
        return 1d + Math.max(atStart, atEnd) * ROUTE_TURN_BONUS;
    }

    /** Returns 0 for straight travel, 1 for a right angle and 2 for a U-turn. */
    private double projectedBend(int beforeIndex, int cornerIndex, int afterIndex) {
        float[] before = projectedRoutePoint(beforeIndex);
        float[] corner = projectedRoutePoint(cornerIndex);
        float[] after = projectedRoutePoint(afterIndex);
        if (before == null || corner == null || after == null) return 0d;
        double incomingX = corner[0] - before[0];
        double incomingY = corner[1] - before[1];
        double outgoingX = after[0] - corner[0];
        double outgoingY = after[1] - corner[1];
        double incomingLength = Math.hypot(incomingX, incomingY);
        double outgoingLength = Math.hypot(outgoingX, outgoingY);
        if (incomingLength < 1d || outgoingLength < 1d) return 0d;
        double cosine = (incomingX * outgoingX + incomingY * outgoingY)
                / (incomingLength * outgoingLength);
        return 1d - Math.max(-1d, Math.min(1d, cosine));
    }

    private int nearestRouteSegment(double latitude, double longitude) {
        if (!validCoordinate(latitude, longitude) || routePoints.size() < 2) return -1;
        int first = currentRouteSegmentIndex >= 0
                ? Math.max(0, Math.min(routePoints.size() - 2,
                currentRouteSegmentIndex - 1)) : 0;
        int bestIndex = -1;
        double bestDistance = Double.POSITIVE_INFINITY;
        double longitudeScale = Math.max(.1d, Math.cos(Math.toRadians(latitude)));
        for (int index = first; index < routePoints.size() - 1; index++) {
            RoutePoint from = routePoints.get(index);
            RoutePoint to = routePoints.get(index + 1);
            if (!validCoordinate(from.latitude, from.longitude)
                    || !validCoordinate(to.latitude, to.longitude)) continue;
            double distance = pointToSegmentSquared(
                    longitude * longitudeScale, latitude,
                    from.longitude * longitudeScale, from.latitude,
                    to.longitude * longitudeScale, to.latitude);
            if (distance < bestDistance) {
                bestDistance = distance;
                bestIndex = index;
            }
        }
        return bestIndex;
    }

    private static double pointToSegmentSquared(double px, double py,
                                                double x1, double y1,
                                                double x2, double y2) {
        double dx = x2 - x1;
        double dy = y2 - y1;
        double lengthSquared = dx * dx + dy * dy;
        if (lengthSquared <= 1e-20d) {
            double ox = px - x1;
            double oy = py - y1;
            return ox * ox + oy * oy;
        }
        double position = ((px - x1) * dx + (py - y1) * dy) / lengthSquared;
        position = Math.max(0d, Math.min(1d, position));
        double ox = px - (x1 + position * dx);
        double oy = py - (y1 + position * dy);
        return ox * ox + oy * oy;
    }

    /** Liang-Barsky clipping returns the exact segment length hidden by the inflated card. */
    static double clippedSegmentLength(RectF bounds, float x1, float y1,
                                       float x2, float y2) {
        double dx = x2 - x1;
        double dy = y2 - y1;
        double[] p = {-dx, dx, -dy, dy};
        double[] q = {x1 - bounds.left, bounds.right - x1,
                y1 - bounds.top, bounds.bottom - y1};
        double enter = 0d;
        double exit = 1d;
        for (int index = 0; index < p.length; index++) {
            if (Math.abs(p[index]) < 1e-9d) {
                if (q[index] < 0d) return 0d;
                continue;
            }
            double ratio = q[index] / p[index];
            if (p[index] < 0d) enter = Math.max(enter, ratio);
            else exit = Math.min(exit, ratio);
            if (enter > exit) return 0d;
        }
        return Math.hypot(dx, dy) * Math.max(0d, exit - enter);
    }

    private static double overlapArea(RectF first, RectF second) {
        float width = Math.min(first.right, second.right) - Math.max(first.left, second.left);
        float height = Math.min(first.bottom, second.bottom) - Math.max(first.top, second.top);
        return width > 0f && height > 0f ? (double) width * height : 0d;
    }

    private static RectF rect(float x, float y, int width, int height, Candidate candidate) {
        float left = x - candidate.anchorX * width;
        float top = y - candidate.anchorY * height;
        return new RectF(left, top, left + width, top + height);
    }

    private static RectF rect(float x, float y, int fallbackWidth, int fallbackHeight,
                              Candidate candidate, Footprint footprint) {
        if (footprint == null) return rect(x, y, fallbackWidth, fallbackHeight, candidate);
        float left = x - footprint.anchorX * footprint.width;
        float top = y - footprint.anchorY * footprint.height;
        return new RectF(left, top, left + footprint.width, top + footprint.height);
    }

    private static Placement placement(Candidate candidate, Footprint footprint) {
        return footprint == null
                ? new Placement(candidate.anchorX, candidate.anchorY, candidate.legName)
                : new Placement(footprint.anchorX, footprint.anchorY, candidate.legName);
    }

    private static Footprint footprintFor(List<Footprint> footprints, String legName) {
        if (footprints == null) return null;
        for (Footprint footprint : footprints) {
            if (footprint != null && footprint.isUsable()
                    && legName.equals(footprint.legName)) return footprint;
        }
        return null;
    }

    /** Fixed set understood by both stock lane and traffic-light renderers. */
    static String[] placementLegNames() {
        return PLACEMENT_LEG_NAMES.clone();
    }

    /** Side placements are preferred; diagonal and vertical slots resolve crowded junctions. */
    private static Candidate[] candidates(boolean preferRight) {
        Candidate right = new Candidate(-.08f, .50f, "LEFT_CENTER");
        Candidate left = new Candidate(1.08f, .50f, "RIGHT_CENTER");
        Candidate upperRight = new Candidate(-.05f, 1.05f, "BOTTOM_LEFT");
        Candidate upperLeft = new Candidate(1.05f, 1.05f, "BOTTOM_RIGHT");
        Candidate lowerRight = new Candidate(-.05f, -.05f, "TOP_LEFT");
        Candidate lowerLeft = new Candidate(1.05f, -.05f, "TOP_RIGHT");
        Candidate above = new Candidate(.50f, 1.08f, "BOTTOM_CENTER");
        Candidate below = new Candidate(.50f, -.08f, "TOP_CENTER");
        return preferRight
                ? new Candidate[]{right, left, upperRight, upperLeft,
                lowerRight, lowerLeft, above, below}
                : new Candidate[]{left, right, upperLeft, upperRight,
                lowerLeft, lowerRight, above, below};
    }

    private static boolean validCoordinate(double latitude, double longitude) {
        return Double.isFinite(latitude) && latitude >= -90d && latitude <= 90d
                && Double.isFinite(longitude) && longitude >= -180d && longitude <= 180d;
    }

    private static Object invoke(Object target, String name, Class<?>[] parameterTypes,
                                 Object... arguments) throws Exception {
        Method method = ReflectMethods.publicMethod(target.getClass(), name, parameterTypes);
        return method.invoke(target, arguments);
    }

    static final class Placement {
        final float anchorX;
        final float anchorY;
        final String legName;

        Placement(float anchorX, float anchorY, String legName) {
            this.anchorX = anchorX;
            this.anchorY = anchorY;
            this.legName = legName;
        }

        boolean sameSlot(Placement other) {
            return other != null && legName.equals(other.legName);
        }
    }

    /** Exact rendered bitmap geometry for one stock Yandex leg position. */
    static final class Footprint {
        final String legName;
        final int width;
        final int height;
        final float anchorX;
        final float anchorY;

        Footprint(String legName, int width, int height, float anchorX, float anchorY) {
            this.legName = legName == null ? "" : legName;
            this.width = width;
            this.height = height;
            this.anchorX = anchorX;
            this.anchorY = anchorY;
        }

        private boolean isUsable() {
            return width > 0 && height > 0
                    && Float.isFinite(anchorX) && Float.isFinite(anchorY);
        }
    }

    private static final class Candidate {
        final float anchorX;
        final float anchorY;
        final String legName;

        Candidate(float anchorX, float anchorY, String legName) {
            this.anchorX = anchorX;
            this.anchorY = anchorY;
            this.legName = legName;
        }
    }

    private static final class Reservation {
        final String owner;
        final String key;
        final RectF bounds;

        Reservation(String owner, String key, RectF bounds) {
            this.owner = owner;
            this.key = key;
            this.bounds = bounds;
        }
    }

    private static final class RoutePoint {
        final double latitude;
        final double longitude;

        RoutePoint(double latitude, double longitude) {
            this.latitude = latitude;
            this.longitude = longitude;
        }
    }
}
