/* SPDX-License-Identifier: GPL-3.0-or-later */
package ru.natro.navigation;

import android.util.Log;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Native MapKit text placemarks sampled strictly from named active-route sections. */
final class RouteStreetLabelMapLayer {
    private static final String TAG = "NatroRouteLabels";
    private static final int MAX_LABELS = 192;
    private static final int MAX_NAME_CHARS = 72;
    private static final double REPEAT_DISTANCE_METERS = 600d;
    private static final double DUPLICATE_CLEARANCE_METERS = 260d;

    private Object map;
    private Object collection;
    private Object route;
    private long routeEpoch = Long.MIN_VALUE;
    private boolean enabled;
    private boolean night;
    private int scalePercent = 100;
    private int priority = 50;
    private long renderedFingerprint = Long.MIN_VALUE;

    void attach(Object nextMap) {
        if (map == nextMap) return;
        detachMap();
        map = nextMap;
        renderIfChanged(true);
    }

    void detachMap() {
        clear(collection);
        collection = null;
        map = null;
        renderedFingerprint = Long.MIN_VALUE;
    }

    void update(long nextRouteEpoch, Object nextRoute) {
        if (nextRouteEpoch < routeEpoch) return;
        routeEpoch = nextRouteEpoch;
        route = nextRoute;
        renderIfChanged(false);
    }

    void clearData() {
        route = null;
        renderIfChanged(true);
    }

    void apply(boolean nextEnabled, int nextScalePercent, boolean nextNight,
               int nextPriority) {
        enabled = nextEnabled;
        scalePercent = Math.max(50, Math.min(250, nextScalePercent));
        night = nextNight;
        priority = Math.max(0, Math.min(100, nextPriority));
        MapObjectLayerFactory.setZIndex(collection, NavigationMapProfile.layerZ(priority));
        renderIfChanged(false);
    }

    private void renderIfChanged(boolean force) {
        long fingerprint = fingerprint();
        if (!force && fingerprint == renderedFingerprint) return;
        renderedFingerprint = fingerprint;
        if (map == null) return;
        try {
            ensureCollection();
            clear(collection);
            if (!enabled || route == null) return;
            List<Label> labels = labels(route);
            if (labels.isEmpty()) return;
            Class<?> pointClass = Class.forName("com.yandex.mapkit.geometry.Point");
            Class<?> textStyleClass = Class.forName("com.yandex.mapkit.map.TextStyle");
            Class<?> placementClass = Class.forName(
                    "com.yandex.mapkit.map.TextStyle$Placement");
            @SuppressWarnings({"rawtypes", "unchecked"})
            Object center = Enum.valueOf((Class<? extends Enum>) placementClass, "CENTER");
            float size = 8f * scalePercent / 100f;
            int textColor = night ? 0xFFF0F3F6 : 0xFF46505B;
            int outlineColor = night ? 0xDD171A20 : 0xEFFFFFFF;
            Object style = textStyleClass.getConstructor(float.class, int.class,
                            float.class, int.class, placementClass, float.class,
                            boolean.class, boolean.class)
                    .newInstance(size, textColor, 1.5f, outlineColor, center,
                            0f, false, true);
            for (Label label : labels) {
                Object point = pointClass.getConstructor(double.class, double.class)
                        .newInstance(label.latitude, label.longitude);
                Object placemark = invoke(collection, "addPlacemark",
                        new Class<?>[]{pointClass}, point);
                invoke(placemark, "setText",
                        new Class<?>[]{String.class, textStyleClass}, label.name, style);
                invoke(placemark, "setVisible", new Class<?>[]{boolean.class}, true);
            }
        } catch (Throwable failure) {
            clear(collection);
            Log.w(TAG, "Route-only street labels could not be rendered", failure);
        }
    }

    private void ensureCollection() throws Exception {
        if (collection != null) return;
        // MINOR lets road events, signs, lane guidance and callouts remove an optional street name.
        collection = MapObjectLayerFactory.create(map,
                MapSublayerOrder.ROUTE_STREET_LABELS,
                MapObjectLayerFactory.MINOR,
                NavigationMapProfile.layerZ(priority));
    }

    private long fingerprint() {
        long result = routeEpoch * 1_000_003L;
        result = result * 131L + (route == null ? 0L : 1L);
        result = result * 131L + routeId(route).hashCode();
        result = result * 131L + (enabled ? 1L : 0L);
        result = result * 131L + (night ? 1L : 0L);
        result = result * 131L + scalePercent;
        return result * 131L + priority;
    }

    private static List<Label> labels(Object drivingRoute) {
        try {
            Object geometry = invoke(drivingRoute, "getGeometry", new Class<?>[0]);
            List<?> rawPoints = list(invoke(geometry, "getPoints", new Class<?>[0]));
            List<?> sections = list(invoke(drivingRoute, "getSections", new Class<?>[0]));
            if (rawPoints.size() < 2 || sections.isEmpty()) return Collections.emptyList();
            ArrayList<RoutePoint> points = new ArrayList<>(rawPoints.size());
            double[] cumulative = new double[rawPoints.size()];
            RoutePoint previous = null;
            for (int index = 0; index < rawPoints.size(); index++) {
                Object raw = rawPoints.get(index);
                RoutePoint point = new RoutePoint(number(invoke(raw, "getLatitude",
                        new Class<?>[0])), number(invoke(raw, "getLongitude",
                        new Class<?>[0])));
                if (!point.valid()) return Collections.emptyList();
                points.add(point);
                if (previous != null) {
                    cumulative[index] = cumulative[index - 1]
                            + distanceMeters(previous, point);
                }
                previous = point;
            }

            ArrayList<Label> result = new ArrayList<>();
            String previousName = "";
            double previousNamedDistance = Double.NEGATIVE_INFINITY;
            for (Object section : sections) {
                if (result.size() >= MAX_LABELS) break;
                Object metadata = invoke(section, "getMetadata", new Class<?>[0]);
                Object annotation = metadata == null ? null
                        : invoke(metadata, "getAnnotation", new Class<?>[0]);
                String name = annotation == null ? ""
                        : cleanName(text(invoke(annotation, "getToponym", new Class<?>[0])));
                if (name.isEmpty()) continue;
                Object range = invoke(section, "getGeometry", new Class<?>[0]);
                Object begin = range == null ? null
                        : invoke(range, "getBegin", new Class<?>[0]);
                Object end = range == null ? null
                        : invoke(range, "getEnd", new Class<?>[0]);
                double from = distanceAt(begin, cumulative);
                double to = distanceAt(end, cumulative);
                if (!Double.isFinite(from) || !Double.isFinite(to) || to <= from + 12d) {
                    continue;
                }
                int repeats = Math.max(1, (int) Math.ceil((to - from)
                        / REPEAT_DISTANCE_METERS));
                for (int repeat = 0; repeat < repeats && result.size() < MAX_LABELS; repeat++) {
                    double along = from + (to - from) * (repeat + .5d) / repeats;
                    boolean duplicateTooClose = name.equalsIgnoreCase(previousName)
                            && along - previousNamedDistance < DUPLICATE_CLEARANCE_METERS;
                    if (duplicateTooClose) continue;
                    RoutePoint point = pointAt(points, cumulative, along);
                    if (point == null) continue;
                    result.add(new Label(name, point.latitude, point.longitude));
                    previousName = name;
                    previousNamedDistance = along;
                }
            }
            return result;
        } catch (Throwable unavailable) {
            return Collections.emptyList();
        }
    }

    private static double distanceAt(Object position, double[] cumulative) throws Exception {
        if (position == null || cumulative.length < 2) return Double.NaN;
        int segment = ((Number) invoke(position, "getSegmentIndex",
                new Class<?>[0])).intValue();
        double fraction = number(invoke(position, "getSegmentPosition", new Class<?>[0]));
        segment = Math.max(0, Math.min(cumulative.length - 2, segment));
        fraction = Math.max(0d, Math.min(1d, fraction));
        return cumulative[segment]
                + (cumulative[segment + 1] - cumulative[segment]) * fraction;
    }

    private static RoutePoint pointAt(List<RoutePoint> points, double[] cumulative,
                                      double distance) {
        if (points.size() < 2 || !Double.isFinite(distance)) return null;
        double target = Math.max(0d, Math.min(cumulative[cumulative.length - 1], distance));
        int low = 0;
        int high = cumulative.length - 1;
        while (low + 1 < high) {
            int middle = (low + high) >>> 1;
            if (cumulative[middle] <= target) low = middle;
            else high = middle;
        }
        int segment = Math.min(points.size() - 2, low);
        double length = cumulative[segment + 1] - cumulative[segment];
        double fraction = length <= .001d ? 0d
                : (target - cumulative[segment]) / length;
        RoutePoint from = points.get(segment);
        RoutePoint to = points.get(segment + 1);
        return new RoutePoint(from.latitude + (to.latitude - from.latitude) * fraction,
                from.longitude + (to.longitude - from.longitude) * fraction);
    }

    private static double distanceMeters(RoutePoint from, RoutePoint to) {
        double firstLatitude = Math.toRadians(from.latitude);
        double secondLatitude = Math.toRadians(to.latitude);
        double latitudeDelta = secondLatitude - firstLatitude;
        double longitudeDelta = Math.toRadians(to.longitude - from.longitude);
        double sinLatitude = Math.sin(latitudeDelta / 2d);
        double sinLongitude = Math.sin(longitudeDelta / 2d);
        double value = sinLatitude * sinLatitude + Math.cos(firstLatitude)
                * Math.cos(secondLatitude) * sinLongitude * sinLongitude;
        return 6_371_000d * 2d * Math.atan2(Math.sqrt(value),
                Math.sqrt(Math.max(0d, 1d - value)));
    }

    private static String cleanName(String raw) {
        String value = raw.replaceAll("\\s+", " ").trim();
        if (value.length() > MAX_NAME_CHARS) {
            value = value.substring(0, MAX_NAME_CHARS - 1).trim() + "\u2026";
        }
        return value;
    }

    private static String routeId(Object value) {
        if (value == null) return "";
        try { return text(invoke(value, "getRouteId", new Class<?>[0])); }
        catch (Throwable ignored) { return ""; }
    }

    private static void clear(Object target) {
        if (target == null) return;
        try { invoke(target, "clear", new Class<?>[0]); }
        catch (Throwable ignored) {}
    }

    private static List<?> list(Object value) {
        return value instanceof List<?> ? (List<?>) value : Collections.emptyList();
    }

    private static double number(Object value) {
        return value instanceof Number ? ((Number) value).doubleValue() : Double.NaN;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static Object invoke(Object target, String name, Class<?>[] parameterTypes,
                                 Object... arguments) throws Exception {
        Method method = ReflectMethods.publicMethod(target.getClass(), name, parameterTypes);
        return method.invoke(target, arguments);
    }

    private static final class RoutePoint {
        final double latitude;
        final double longitude;

        RoutePoint(double latitude, double longitude) {
            this.latitude = latitude;
            this.longitude = longitude;
        }

        boolean valid() {
            return Double.isFinite(latitude) && latitude >= -90d && latitude <= 90d
                    && Double.isFinite(longitude) && longitude >= -180d && longitude <= 180d;
        }
    }

    private static final class Label {
        final String name;
        final double latitude;
        final double longitude;

        Label(String name, double latitude, double longitude) {
            this.name = name;
            this.latitude = latitude;
            this.longitude = longitude;
        }
    }
}
