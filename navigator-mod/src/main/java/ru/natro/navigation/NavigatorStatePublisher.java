/* SPDX-License-Identifier: GPL-3.0-or-later */
package ru.natro.navigation;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Clean-room adapter from the exact 30.3.0 Navigator runtime to Natro's versioned state stream.
 *
 * <p>The adapter deliberately uses public runtime APIs instead of walking obfuscated fields:
 * MapActivity.x() exposes the primary MapWithControlsView, while the application component's
 * getGuidance() exposes the single NaviKit Guidance session already owned by Navigator. That
 * session leads to automotive Guidance, RoutePosition and Windshield, which are the canonical
 * sources for route progress and HUD data.</p>
 */
final class NavigatorStatePublisher {
    interface Sink {
        void onPrimaryMap(Object mapWindow, Object map);

        void onPrimaryCamera(CameraState state);

        void onNavigationState(String snapshotJson, String routeJson, Object drivingRoute,
                               long routeEpoch);

        void onDiagnostic(String detail);
    }

    static final class CameraState {
        final double latitude;
        final double longitude;
        final float zoom;
        final float azimuth;
        final float tilt;

        CameraState(double latitude, double longitude, float zoom, float azimuth, float tilt) {
            this.latitude = latitude;
            this.longitude = longitude;
            this.zoom = zoom;
            this.azimuth = azimuth;
            this.tilt = tilt;
        }

        boolean isValid() {
            return finite(latitude) && latitude >= -90d && latitude <= 90d
                    && finite(longitude) && longitude >= -180d && longitude <= 180d
                    && finite(zoom) && finite(azimuth) && finite(tilt);
        }
    }

    private static final String TAG = "NatroNavigationState";
    private static final long CAMERA_INTERVAL_MS = 100L;
    private static final long MIN_RESOLVE_RETRY_MS = 250L;
    private static final long MAX_RESOLVE_RETRY_MS = 5_000L;
    private static final long ROUTE_RECONCILE_CONFIRM_MS = 250L;
    private static final int MAX_POLYLINE_POINTS = 16_000;
    private static final int MAX_ENCODED_POLYLINE_CHARS = 190_000;
    private static final int MAX_TRAFFIC_RUNS = 2_048;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Sink sink;
    private WeakReference<Activity> activityReference = new WeakReference<>(null);

    private Object primaryMap;
    private Object cameraListener;
    private WeakReference<Object> cameraListenerReference;
    private CameraState pendingCamera;
    private long lastCameraDispatchElapsedMs;

    private Object naviKitGuidance;
    private Object guidance;
    private Object guidanceListener;
    private Object windshield;
    private Object windshieldListener;

    private long sequence;
    private long routeEpoch;
    private String activeRouteKey;
    private Object activeRoute;
    private String encodedRoute = "";
    // MapKit may report metadata weight for only the remaining route after position updates.
    // Freeze the first positive distance per route epoch so HUD trip progress has a stable base.
    private int activeRouteTotalDistanceMeters = -1;
    private Object conditionsRoute;
    private Object conditionsListener;
    private long resolveRetryMs = MIN_RESOLVE_RETRY_MS;
    private String lastPrimaryMapFailure = "";
    private String lastGuidanceFailure = "";
    private String lastRouteDiagnostic = "";

    NavigatorStatePublisher(Sink sink) {
        this.sink = sink;
    }

    void attach(Activity activity) {
        runOnMain(() -> attachOnMain(activity));
    }

    void detach(Activity activity) {
        runOnMain(() -> {
            if (activityReference.get() == activity) detachOnMain();
        });
    }

    void requestSnapshot() {
        runOnMain(() -> publishState(false, false));
    }

    void requestRoute() {
        runOnMain(() -> publishState(true, true));
    }

    private void attachOnMain(Activity activity) {
        if (activity == null || activity.isFinishing()) return;
        if (activityReference.get() != activity) {
            detachOnMain();
            activityReference = new WeakReference<>(activity);
        }
        resolveRetryMs = MIN_RESOLVE_RETRY_MS;
        resolveBindings();
    }

    private void resolveBindings() {
        Activity activity = activityReference.get();
        if (activity == null || activity.isFinishing()) return;
        boolean resolvedSomething = false;
        if (primaryMap == null) {
            try {
                Object mapView = invoke(activity, "x");
                Object mapWindow = invoke(mapView, "getMapWindow");
                Object nextMap = invoke(mapWindow, "getMap");
                primaryMap = nextMap;
                try {
                    attachCameraListener(nextMap);
                    captureCamera(invoke(nextMap, "getCameraPosition"), true);
                } catch (Throwable bindingFailure) {
                    detachCameraListener();
                    throw bindingFailure;
                }
                sink.onPrimaryMap(mapWindow, nextMap);
                resolvedSomething = true;
                Log.i(TAG, "Primary Navigator MapWindow resolved");
                lastPrimaryMapFailure = "";
                sink.onDiagnostic("primary Navigator MapWindow resolved");
            } catch (Throwable failure) {
                String detail = shortMessage(failure);
                Log.d(TAG, "Primary map is not ready yet: " + detail);
                if (!detail.equals(lastPrimaryMapFailure)) {
                    lastPrimaryMapFailure = detail;
                    sink.onDiagnostic("primary MapWindow not ready: " + detail);
                }
            }
        }
        if (guidance == null || naviKitGuidance == null) {
            try {
                Object applicationComponent = invoke(activity.getApplication(), "c");
                Object nextNaviKitGuidance = invoke(applicationComponent, "getGuidance");
                Object navigation = invoke(nextNaviKitGuidance, "navigation");
                Object nextGuidance = invoke(navigation, "getGuidance");
                Object valid = invoke(nextGuidance, "isValid");
                if (valid instanceof Boolean && !((Boolean) valid)) {
                    throw new IllegalStateException("automotive Guidance is invalid");
                }
                naviKitGuidance = nextNaviKitGuidance;
                guidance = nextGuidance;
                try {
                    attachGuidanceListeners(nextGuidance);
                } catch (Throwable listenerFailure) {
                    detachGuidanceListeners();
                    naviKitGuidance = null;
                    guidance = null;
                    throw listenerFailure;
                }
                resolvedSomething = true;
                Log.i(TAG, "Active NaviKit Guidance session resolved");
                lastGuidanceFailure = "";
                sink.onDiagnostic("active NaviKit Guidance session resolved");
            } catch (Throwable failure) {
                String detail = shortMessage(failure);
                Log.d(TAG, "Guidance is not ready yet: " + detail);
                if (!detail.equals(lastGuidanceFailure)) {
                    lastGuidanceFailure = detail;
                    sink.onDiagnostic("Guidance not ready: " + detail);
                }
            }
        }
        if (guidance != null && naviKitGuidance != null
                && (resolvedSomething || primaryMap != null)) {
            publishState(true, true);
        }
        if (primaryMap == null || guidance == null || naviKitGuidance == null) {
            main.removeCallbacks(resolveRetry);
            main.postDelayed(resolveRetry, resolveRetryMs);
            resolveRetryMs = Math.min(MAX_RESOLVE_RETRY_MS, resolveRetryMs * 2L);
        }
    }

    private void attachCameraListener(Object nextMap) throws Exception {
        Class<?> listenerClass = Class.forName("com.yandex.mapkit.map.CameraListener");
        cameraListener = Proxy.newProxyInstance(listenerClass.getClassLoader(),
                new Class<?>[]{listenerClass}, (proxy, method, arguments) -> {
                    if (isObjectMethod(proxy, method, arguments)) {
                        return objectMethodResult(proxy, method, arguments);
                    }
                    if ("onCameraPositionChanged".equals(method.getName())
                            && arguments != null && arguments.length >= 4) {
                        Object eventMap = arguments[0];
                        Object cameraPosition = arguments[1];
                        boolean finished = Boolean.TRUE.equals(arguments[3]);
                        runOnMain(() -> {
                            if (primaryMap == eventMap) captureCamera(cameraPosition, finished);
                        });
                    }
                    return null;
                });
        cameraListenerReference = new WeakReference<>(cameraListener);
        invoke(nextMap, "addCameraListener", new Class<?>[]{WeakReference.class},
                cameraListenerReference);
    }

    private void attachGuidanceListeners(Object nextGuidance) throws Exception {
        Class<?> guidanceListenerClass = Class.forName(
                "com.yandex.mapkit.navigation.automotive.GuidanceListener");
        guidanceListener = listenerProxy(guidanceListenerClass, methodName -> {
            boolean routeMayHaveChanged = "onCurrentRouteChanged".equals(methodName)
                    || "onRouteFinished".equals(methodName)
                    || "onRouteLost".equals(methodName);
            if (routeMayHaveChanged) scheduleRouteReconcile(methodName);
            else publishState(false, false);
        });
        invoke(nextGuidance, "addListener", new Class<?>[]{guidanceListenerClass},
                guidanceListener);

        windshield = invoke(nextGuidance, "getWindshield");
        Class<?> windshieldListenerClass = Class.forName(
                "com.yandex.mapkit.navigation.automotive.WindshieldListener");
        windshieldListener = listenerProxy(windshieldListenerClass,
                methodName -> publishState(false, false));
        invoke(windshield, "addListener", new Class<?>[]{windshieldListenerClass},
                windshieldListener);
    }

    private Object listenerProxy(Class<?> listenerClass, Event event) {
        InvocationHandler handler = (proxy, method, arguments) -> {
            if (isObjectMethod(proxy, method, arguments)) {
                return objectMethodResult(proxy, method, arguments);
            }
            runOnMain(() -> event.onEvent(method.getName()));
            return null;
        };
        return Proxy.newProxyInstance(listenerClass.getClassLoader(),
                new Class<?>[]{listenerClass}, handler);
    }

    private void captureCamera(Object cameraPosition, boolean finished) {
        if (cameraPosition == null) return;
        try {
            Object target = invoke(cameraPosition, "getTarget");
            CameraState next = new CameraState(
                    number(invoke(target, "getLatitude"), Double.NaN),
                    number(invoke(target, "getLongitude"), Double.NaN),
                    (float) number(invoke(cameraPosition, "getZoom"), Float.NaN),
                    (float) number(invoke(cameraPosition, "getAzimuth"), 0d),
                    (float) number(invoke(cameraPosition, "getTilt"), 0d));
            if (!next.isValid()) return;
            pendingCamera = next;
            long now = SystemClock.elapsedRealtime();
            long delay = Math.max(0L,
                    CAMERA_INTERVAL_MS - (now - lastCameraDispatchElapsedMs));
            main.removeCallbacks(dispatchCamera);
            if (finished || delay == 0L) dispatchCamera.run();
            else main.postDelayed(dispatchCamera, delay);
        } catch (Throwable failure) {
            Log.w(TAG, "Could not read primary camera", failure);
        }
    }

    private void publishState(boolean routeMayHaveChanged, boolean forceRoute) {
        Object currentGuidance = guidance;
        Object currentNaviKitGuidance = naviKitGuidance;
        if (currentGuidance == null || currentNaviKitGuidance == null) {
            resolveBindings();
            return;
        }
        try {
            // Automotive Guidance.getCurrentRoute() is also populated by Navigator's automatic
            // free-drive guidance. Only NaviKit Guidance.route() represents a route explicitly
            // started by the user; freeDriveRoute() must remain ordinary background traffic.
            Object engineRoute = invoke(currentGuidance, "getCurrentRoute");
            Object freeDriveRoute = invoke(currentNaviKitGuidance, "freeDriveRoute");
            Object nextRoute = invoke(currentNaviKitGuidance, "route");
            String routeStatus = readRouteStatus(currentGuidance);
            // During onRouteFinished the Java wrapper may still expose the old user route for the
            // remainder of that native callback, so the terminal status is authoritative too.
            if ("ROUTE_FINISHED".equals(routeStatus)) nextRoute = null;
            boolean routeChanged = updateRoute(nextRoute, routeMayHaveChanged);
            publishRouteDiagnostic(routeStatus, engineRoute, freeDriveRoute);
            String snapshot = buildSnapshot(currentGuidance, activeRoute).toString();
            String route = routeChanged || forceRoute ? buildRoutePayload().toString() : null;
            sink.onNavigationState(snapshot, route, activeRoute, routeEpoch);
        } catch (Throwable failure) {
            Log.w(TAG, "Could not publish Navigator state", failure);
            detachGuidanceListeners();
            naviKitGuidance = null;
            guidance = null;
            main.removeCallbacks(resolveRetry);
            main.postDelayed(resolveRetry, MIN_RESOLVE_RETRY_MS);
        }
    }

    private void scheduleRouteReconcile(String eventName) {
        sink.onDiagnostic("Guidance route lifecycle event=" + eventName
                + "; deferring canonical route read");
        main.removeCallbacks(routeReconcile);
        main.removeCallbacks(routeReconcileConfirmation);
        // GuidanceListener is invoked from inside MapKit's state transition. Reading the route in
        // that same stack frame can return the route that has just been stopped. One main-loop turn
        // observes the committed value; the bounded confirmation catches vendor-side deferral.
        main.post(routeReconcile);
        main.postDelayed(routeReconcileConfirmation, ROUTE_RECONCILE_CONFIRM_MS);
    }

    private void publishRouteDiagnostic(String routeStatus, Object engineRoute,
                                        Object freeDriveRoute) {
        String detail = "userActive=" + (activeRoute != null)
                + ", engineActive=" + (engineRoute != null)
                + ", freeDriveActive=" + (freeDriveRoute != null)
                + ", status=" + routeStatus + ", epoch=" + routeEpoch;
        if (detail.equals(lastRouteDiagnostic)) return;
        lastRouteDiagnostic = detail;
        sink.onDiagnostic("Guidance route state " + detail);
    }

    private static String readRouteStatus(Object currentGuidance) {
        try {
            return enumName(invoke(currentGuidance, "getRouteStatus"));
        } catch (Throwable unavailable) {
            return "UNKNOWN";
        }
    }

    private boolean updateRoute(Object nextRoute, boolean routeMayHaveChanged) throws Exception {
        if (conditionsRoute != nextRoute) attachRouteConditions(nextRoute);
        if (activeRouteKey != null && nextRoute == activeRoute && !routeMayHaveChanged) {
            if (activeRouteTotalDistanceMeters <= 0 && nextRoute != null) {
                activeRouteTotalDistanceMeters = readRouteTotalDistance(nextRoute);
            }
            return false;
        }
        String nextEncoded = nextRoute == null ? "" : encodeRoute(nextRoute);
        String routeId = nextRoute == null ? "" : text(invoke(nextRoute, "getRouteId"));
        String nextKey = nextRoute == null ? ""
                : routeId + ':' + nextEncoded.length() + ':' + nextEncoded.hashCode();
        boolean initial = activeRouteKey == null;
        boolean changed = !initial && !activeRouteKey.equals(nextKey);
        if (initial && nextRoute != null) routeEpoch = 1L;
        else if (changed) routeEpoch++;
        activeRoute = nextRoute;
        activeRouteKey = nextKey;
        encodedRoute = nextEncoded;
        if (initial || changed || activeRouteTotalDistanceMeters <= 0) {
            activeRouteTotalDistanceMeters = readRouteTotalDistance(nextRoute);
        }
        return initial || changed;
    }

    private JSONObject buildSnapshot(Object currentGuidance, Object route) throws Exception {
        long now = System.currentTimeMillis();
        Object location = invoke(currentGuidance, "getLocation");
        Object routePosition = route == null ? null : invoke(route, "getRoutePosition");

        double latitude = Double.NaN;
        double longitude = Double.NaN;
        double bearing = Double.NaN;
        double speedKmh = Double.NaN;
        if (location != null) {
            Object point = invoke(location, "getPosition");
            if (point != null) {
                latitude = number(invoke(point, "getLatitude"), Double.NaN);
                longitude = number(invoke(point, "getLongitude"), Double.NaN);
            }
            bearing = number(invoke(location, "getHeading"), Double.NaN);
            double speedMps = number(invoke(location, "getSpeed"), Double.NaN);
            if (finite(speedMps)) speedKmh = Math.max(0d, speedMps * 3.6d);
        }
        if (!finite(bearing) && routePosition != null) {
            bearing = number(invoke(routePosition, "heading"), Double.NaN);
        }

        int remainingDistance = routePosition == null ? -1 : nonNegativeInt(
                number(invoke(routePosition, "distanceToFinish"), -1d));
        int remainingDuration = routePosition == null ? -1 : nonNegativeInt(
                number(invoke(routePosition, "timeToFinish"), -1d));
        int routeTotalDistance = activeRouteTotalDistanceMeters;
        long arrival = remainingDuration < 0 ? 0L
                : now + Math.min(31_536_000, remainingDuration) * 1_000L;

        Manoeuvre manoeuvre = readManoeuvre(routePosition);
        LaneState lanes = readLanes(routePosition);
        String trafficLights = readTrafficLights(routePosition).toString();
        Object speedLimitValue = invoke(currentGuidance, "getSpeedLimit");
        int speedLimit = speedLimitValue == null ? 0 : nonNegativeInt(
                number(invoke(speedLimitValue, "getValue"), 0d) * 3.6d);

        JSONObject result = new JSONObject()
                .put("schema", 1)
                .put("sequence", ++sequence)
                .put("routeEpoch", routeEpoch)
                .put("sourceTimestampMs", now)
                .put("routeActive", route != null)
                .put("maneuverType", manoeuvre.type)
                .put("maneuverTitle", manoeuvre.title)
                .put("maneuverSubtext", manoeuvre.subtext)
                .put("street", text(invoke(currentGuidance, "getRoadName")))
                .put("destination", readDestination(route))
                .put("maneuverDistanceMeters", manoeuvre.distanceMeters)
                .put("routeTotalDistanceMeters", routeTotalDistance)
                .put("remainingDistanceMeters", remainingDistance)
                .put("remainingDurationSeconds", remainingDuration)
                .put("arrivalEpochMs", arrival)
                .put("speedLimitKmh", Math.min(300, speedLimit))
                .put("laneDistanceMeters", lanes.distanceMeters)
                .put("lanesJson", lanes.values.toString())
                .put("trafficLightsJson", trafficLights);
        if (finite(latitude)) result.put("latitude", latitude);
        if (finite(longitude)) result.put("longitude", longitude);
        if (finite(bearing)) result.put("bearingDegrees", normalizeBearing(bearing));
        if (finite(speedKmh)) result.put("speedKmh", Math.min(400d, speedKmh));
        return result;
    }

    private Manoeuvre readManoeuvre(Object routePosition) throws Exception {
        Object upcoming = nearest(invokeList(windshield, "getManoeuvres"),
                routePosition, "getPosition");
        if (upcoming == null) return Manoeuvre.EMPTY;
        Object position = invoke(upcoming, "getPosition");
        Object annotation = invoke(upcoming, "getAnnotation");
        if (annotation == null) return Manoeuvre.EMPTY;
        String type = enumName(invoke(annotation, "getAction"));
        String description = text(invoke(annotation, "getDescriptionText"));
        String toponym = text(invoke(annotation, "getToponym"));
        String title = description.isEmpty() ? toponym : description;
        String subtext = title.equals(toponym) ? "" : toponym;
        int distance = nonNegativeInt(distance(routePosition, position));
        return new Manoeuvre(type, title, subtext, distance);
    }

    private String readDestination(Object route) {
        String fallback = "";
        try {
            for (Object upcoming : invokeList(windshield, "getManoeuvres")) {
                Object annotation = invoke(upcoming, "getAnnotation");
                if (annotation == null) continue;
                String action = enumName(invoke(annotation, "getAction"));
                if (!"FINISH".equals(action) && !"WAYPOINT".equals(action)) continue;
                String description = text(invoke(annotation, "getDescriptionText"));
                String toponym = text(invoke(annotation, "getToponym"));
                String candidate = description.isEmpty() ? toponym : description;
                if (!candidate.isEmpty()) fallback = candidate;
            }
        } catch (Throwable ignored) {}
        if (!fallback.isEmpty() || route == null) return fallback;
        try {
            List<?> points = invokeList(route, "getRequestPoints");
            for (int index = points.size() - 1; index >= 0; index--) {
                Object requestPoint = points.get(index);
                if (!"WAYPOINT".equals(enumName(invoke(requestPoint, "getType")))) continue;
                Object point = invoke(requestPoint, "getPoint");
                double latitude = number(invoke(point, "getLatitude"), Double.NaN);
                double longitude = number(invoke(point, "getLongitude"), Double.NaN);
                if (finite(latitude) && finite(longitude)) {
                    return String.format(Locale.ROOT, "%.5f, %.5f", latitude, longitude);
                }
            }
        } catch (Throwable ignored) {}
        return fallback;
    }

    private LaneState readLanes(Object routePosition) throws Exception {
        JSONArray result = new JSONArray();
        Object upcoming = nearest(invokeList(windshield, "getLaneSigns"),
                routePosition, "getPosition");
        if (upcoming == null) return new LaneState(result, -1);
        int distanceMeters = nonNegativeInt(distance(routePosition,
                invoke(upcoming, "getPosition")));
        Object sign = invoke(upcoming, "getLaneSign");
        for (Object lane : invokeList(sign, "getLanes")) {
            JSONArray directions = new JSONArray();
            for (Object direction : invokeList(lane, "getDirections")) {
                directions.put(enumName(direction));
            }
            result.put(new JSONObject()
                    .put("kind", enumName(invoke(lane, "getLaneKind")))
                    .put("highlightedDirection",
                            enumName(invoke(lane, "getHighlightedDirection")))
                    .put("directions", directions));
        }
        return new LaneState(result, distanceMeters);
    }

    private static int readRouteTotalDistance(Object route) {
        if (route == null) return -1;
        try {
            Object metadata = invoke(route, "getMetadata");
            Object weight = invoke(metadata, "getWeight");
            Object localizedDistance = invoke(weight, "getDistance");
            return nonNegativeInt(number(invoke(localizedDistance, "getValue"), -1d));
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private JSONArray readTrafficLights(Object routePosition) throws Exception {
        JSONArray result = new JSONArray();
        int count = 0;
        for (Object light : invokeList(windshield, "getTrafficLightsWithSignal")) {
            if (count++ >= 8) break;
            Object position = invoke(light, "getPosition");
            result.put(new JSONObject()
                    .put("id", text(invoke(light, "getId")))
                    .put("distanceMeters", nonNegativeInt(distance(routePosition, position)))
                    .put("secondsLeft", nullableInt(invoke(light, "getSecondsLeft")))
                    .put("signal", enumName(invoke(light, "getSignal")))
                    .put("sectionType", enumName(invoke(light, "getSectionType")))
                    .put("arrow", enumName(invoke(light, "getArrow"))));
        }
        return result;
    }

    private Object nearest(List<?> values, Object routePosition, String positionMethod)
            throws Exception {
        if (values.isEmpty()) return null;
        if (routePosition == null) return values.get(0);
        Object nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (Object value : values) {
            Object position = invoke(value, positionMethod);
            double distance = distance(routePosition, position);
            if (finite(distance) && distance >= -5d && distance < nearestDistance) {
                nearest = value;
                nearestDistance = distance;
            }
        }
        return nearest;
    }

    private JSONObject buildRoutePayload() throws JSONException {
        return new JSONObject()
                .put("schema", 1)
                .put("routeEpoch", routeEpoch)
                .put("encodedPolyline", encodedRoute)
                .put("trafficSegmentsJson", encodeTrafficSegments(activeRoute));
    }

    private static String encodeTrafficSegments(Object route) {
        if (route == null) return "[]";
        try {
            List<?> segments = invokeList(route, "getJamSegments");
            if (segments.isEmpty()) return "[]";
            Object geometry = invoke(route, "getGeometry");
            List<?> points = invokeList(geometry, "getPoints");
            if (points.size() < 2) return "[]";
            int stride = Math.max(1, (points.size() + MAX_POLYLINE_POINTS - 1)
                    / MAX_POLYLINE_POINTS);
            ArrayList<TrafficSample> samples = new ArrayList<>();
            int previousPoint = 0;
            for (int point = stride; point < points.size(); point += stride) {
                samples.add(sampleTraffic(segments, previousPoint, point));
                previousPoint = point;
            }
            int finalPoint = points.size() - 1;
            if (previousPoint != finalPoint) {
                samples.add(sampleTraffic(segments, previousPoint, finalPoint));
            }
            if (samples.isEmpty()) return "[]";

            JSONArray runs = new JSONArray();
            int start = 0;
            String type = samples.get(0).type;
            double speedTotal = finite(samples.get(0).speedMps)
                    ? samples.get(0).speedMps : 0d;
            int speedCount = finite(samples.get(0).speedMps) ? 1 : 0;
            for (int index = 1; index <= samples.size(); index++) {
                String nextType = index < samples.size() ? samples.get(index).type : "";
                if (index < samples.size() && type.equals(nextType)) {
                    double speed = samples.get(index).speedMps;
                    if (finite(speed)) {
                        speedTotal += speed;
                        speedCount++;
                    }
                    continue;
                }
                JSONObject run = new JSONObject()
                        .put("from", start)
                        .put("to", index)
                        .put("type", type);
                if (speedCount > 0) {
                    run.put("speedMps", Math.round(speedTotal / speedCount * 10d) / 10d);
                }
                if (runs.length() >= MAX_TRAFFIC_RUNS - 1 && index < samples.size()) {
                    runs.put(new JSONObject()
                            .put("from", start)
                            .put("to", samples.size())
                            .put("type", "UNKNOWN")
                            .put("partial", true));
                    break;
                }
                runs.put(run);
                if (index < samples.size()) {
                    start = index;
                    type = nextType;
                    double speed = samples.get(index).speedMps;
                    speedTotal = finite(speed) ? speed : 0d;
                    speedCount = finite(speed) ? 1 : 0;
                }
            }
            String result = runs.toString();
            return result.length() <= MAX_ENCODED_POLYLINE_CHARS ? result : "[]";
        } catch (Throwable ignored) {
            return "[]";
        }
    }

    private static TrafficSample sampleTraffic(List<?> segments, int from, int to)
            throws Exception {
        String type = "UNKNOWN";
        int priority = 0;
        double speedTotal = 0d;
        int speedCount = 0;
        int end = Math.min(to, segments.size());
        for (int index = Math.max(0, from); index < end; index++) {
            String candidate = jamType(segments.get(index));
            int candidatePriority = jamPriority(candidate);
            if (candidatePriority > priority) {
                priority = candidatePriority;
                type = candidate;
            }
            double speed = jamSpeed(segments.get(index));
            if (finite(speed)) {
                speedTotal += speed;
                speedCount++;
            }
        }
        return new TrafficSample(type,
                speedCount == 0 ? Double.NaN : speedTotal / speedCount);
    }

    private static int jamPriority(String type) {
        if ("BLOCKED".equals(type)) return 6;
        if ("VERY_HARD".equals(type)) return 5;
        if ("HARD".equals(type)) return 4;
        if ("LIGHT".equals(type)) return 3;
        if ("FREE".equals(type)) return 2;
        return 1;
    }

    private static String jamType(Object segment) throws Exception {
        Object value = invoke(segment, "getJamType");
        return value == null ? "UNKNOWN" : value.toString();
    }

    private static double jamSpeed(Object segment) throws Exception {
        return number(invoke(segment, "getSpeed"), Double.NaN);
    }

    private void attachRouteConditions(Object route) throws Exception {
        detachRouteConditions();
        if (route == null) return;
        Class<?> listenerClass = Class.forName(
                "com.yandex.mapkit.directions.driving.ConditionsListener");
        Object nextListener = Proxy.newProxyInstance(listenerClass.getClassLoader(),
                new Class<?>[]{listenerClass}, (proxy, method, arguments) -> {
                    if (isObjectMethod(proxy, method, arguments)) {
                        return objectMethodResult(proxy, method, arguments);
                    }
                    if ("onConditionsUpdated".equals(method.getName())
                            || "onConditionsOutdated".equals(method.getName())) {
                        runOnMain(() -> publishState(false, true));
                    }
                    return null;
                });
        invoke(route, "addConditionsListener", new Class<?>[]{listenerClass}, nextListener);
        conditionsRoute = route;
        conditionsListener = nextListener;
    }

    private void detachRouteConditions() {
        Object route = conditionsRoute;
        Object listener = conditionsListener;
        if (route != null && listener != null) {
            try {
                Class<?> listenerClass = Class.forName(
                        "com.yandex.mapkit.directions.driving.ConditionsListener");
                invoke(route, "removeConditionsListener",
                        new Class<?>[]{listenerClass}, listener);
            } catch (Throwable ignored) {}
        }
        conditionsRoute = null;
        conditionsListener = null;
    }

    private static String encodeRoute(Object route) throws Exception {
        Object geometry = invoke(route, "getGeometry");
        List<?> points = invokeList(geometry, "getPoints");
        if (points.isEmpty()) return "";
        int stride = Math.max(1, (points.size() + MAX_POLYLINE_POINTS - 1)
                / MAX_POLYLINE_POINTS);
        StringBuilder result = new StringBuilder(Math.min(
                MAX_ENCODED_POLYLINE_CHARS, points.size() * 8));
        long previousLatitude = 0L;
        long previousLongitude = 0L;
        int lastIndex = -1;
        for (int index = 0; index < points.size(); index += stride) {
            long latitude = Math.round(number(invoke(points.get(index), "getLatitude"), 0d)
                    * 100_000d);
            long longitude = Math.round(number(invoke(points.get(index), "getLongitude"), 0d)
                    * 100_000d);
            encodeSigned(result, latitude - previousLatitude);
            encodeSigned(result, longitude - previousLongitude);
            previousLatitude = latitude;
            previousLongitude = longitude;
            lastIndex = index;
            if (result.length() > MAX_ENCODED_POLYLINE_CHARS) return "";
        }
        int finalIndex = points.size() - 1;
        if (lastIndex != finalIndex) {
            long latitude = Math.round(number(
                    invoke(points.get(finalIndex), "getLatitude"), 0d) * 100_000d);
            long longitude = Math.round(number(
                    invoke(points.get(finalIndex), "getLongitude"), 0d) * 100_000d);
            encodeSigned(result, latitude - previousLatitude);
            encodeSigned(result, longitude - previousLongitude);
        }
        return result.length() <= MAX_ENCODED_POLYLINE_CHARS ? result.toString() : "";
    }

    private static void encodeSigned(StringBuilder target, long delta) {
        long value = delta < 0L ? ~(delta << 1) : delta << 1;
        while (value >= 0x20L) {
            target.append((char) ((0x20L | (value & 0x1fL)) + 63L));
            value >>= 5;
        }
        target.append((char) (value + 63L));
    }

    private void detachOnMain() {
        main.removeCallbacks(resolveRetry);
        main.removeCallbacks(dispatchCamera);
        main.removeCallbacks(routeReconcile);
        main.removeCallbacks(routeReconcileConfirmation);
        detachCameraListener();
        detachGuidanceListeners();
        activityReference = new WeakReference<>(null);
        pendingCamera = null;
        naviKitGuidance = null;
        guidance = null;
    }

    private void detachCameraListener() {
        Object map = primaryMap;
        WeakReference<Object> reference = cameraListenerReference;
        if (map != null && reference != null) {
            try {
                invoke(map, "removeCameraListener", new Class<?>[]{WeakReference.class},
                        reference);
            } catch (Throwable ignored) {}
        }
        if (map != null) sink.onPrimaryMap(null, null);
        primaryMap = null;
        cameraListener = null;
        cameraListenerReference = null;
    }

    private void detachGuidanceListeners() {
        detachRouteConditions();
        Object currentGuidance = guidance;
        Object currentGuidanceListener = guidanceListener;
        if (currentGuidance != null && currentGuidanceListener != null) {
            try {
                Class<?> type = Class.forName(
                        "com.yandex.mapkit.navigation.automotive.GuidanceListener");
                invoke(currentGuidance, "removeListener", new Class<?>[]{type},
                        currentGuidanceListener);
            } catch (Throwable ignored) {}
        }
        Object currentWindshield = windshield;
        Object currentWindshieldListener = windshieldListener;
        if (currentWindshield != null && currentWindshieldListener != null) {
            try {
                Class<?> type = Class.forName(
                        "com.yandex.mapkit.navigation.automotive.WindshieldListener");
                invoke(currentWindshield, "removeListener", new Class<?>[]{type},
                        currentWindshieldListener);
            } catch (Throwable ignored) {}
        }
        guidanceListener = null;
        windshield = null;
        windshieldListener = null;
    }

    private void runOnMain(Runnable action) {
        if (Looper.myLooper() == main.getLooper()) action.run();
        else main.post(action);
    }

    private final Runnable resolveRetry = this::resolveBindings;

    private final Runnable dispatchCamera = this::dispatchPendingCamera;

    private final Runnable routeReconcile = () -> publishState(true, true);

    private final Runnable routeReconcileConfirmation = () -> publishState(true, true);

    private void dispatchPendingCamera() {
        CameraState next = pendingCamera;
        pendingCamera = null;
        if (next == null) return;
        lastCameraDispatchElapsedMs = SystemClock.elapsedRealtime();
        sink.onPrimaryCamera(next);
    }

    private interface Event {
        void onEvent(String methodName);
    }

    private static final class TrafficSample {
        final String type;
        final double speedMps;

        TrafficSample(String type, double speedMps) {
            this.type = type;
            this.speedMps = speedMps;
        }
    }

    private static final class Manoeuvre {
        static final Manoeuvre EMPTY = new Manoeuvre("", "", "", -1);
        final String type;
        final String title;
        final String subtext;
        final int distanceMeters;

        Manoeuvre(String type, String title, String subtext, int distanceMeters) {
            this.type = type;
            this.title = title;
            this.subtext = subtext;
            this.distanceMeters = distanceMeters;
        }
    }

    private static final class LaneState {
        final JSONArray values;
        final int distanceMeters;

        LaneState(JSONArray values, int distanceMeters) {
            this.values = values;
            this.distanceMeters = distanceMeters;
        }
    }

    private static boolean isObjectMethod(Object proxy, Method method, Object[] arguments) {
        return method.getDeclaringClass() == Object.class;
    }

    private static Object objectMethodResult(Object proxy, Method method, Object[] arguments) {
        if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
        if ("equals".equals(method.getName())) {
            return arguments != null && arguments.length == 1 && proxy == arguments[0];
        }
        return "NatroListener(" + proxy.getClass().getInterfaces()[0].getSimpleName() + ')';
    }

    private static double distance(Object from, Object to) throws Exception {
        if (from == null || to == null) return Double.NaN;
        Class<?> routePositionClass = Class.forName("com.yandex.mapkit.navigation.RoutePosition");
        return number(invoke(from, "distanceTo", new Class<?>[]{routePositionClass}, to),
                Double.NaN);
    }

    @SuppressWarnings("unchecked")
    private static List<?> invokeList(Object target, String method) throws Exception {
        if (target == null) return Collections.emptyList();
        Object result = invoke(target, method);
        return result instanceof List ? (List<?>) result : Collections.emptyList();
    }

    private static Object invoke(Object target, String name) throws Exception {
        return invoke(target, name, new Class<?>[0]);
    }

    private static Object invoke(Object target, String name, Class<?>[] parameterTypes,
                                 Object... arguments) throws Exception {
        if (target == null) {
            throw new IllegalStateException("Cannot call " + name + " on null target");
        }
        Method method = target.getClass().getMethod(name, parameterTypes);
        method.setAccessible(true);
        return method.invoke(target, arguments);
    }

    private static String enumName(Object value) {
        return value instanceof Enum ? ((Enum<?>) value).name() : text(value);
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static int nullableInt(Object value) {
        return value instanceof Number ? ((Number) value).intValue() : -1;
    }

    private static int nonNegativeInt(double value) {
        if (!finite(value) || value < 0d) return -1;
        return (int) Math.min(Integer.MAX_VALUE, Math.round(value));
    }

    private static double number(Object value, double fallback) {
        return value instanceof Number ? ((Number) value).doubleValue() : fallback;
    }

    private static double normalizeBearing(double value) {
        double result = value % 360d;
        return result < 0d ? result + 360d : result;
    }

    private static boolean finite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }

    private static String shortMessage(Throwable failure) {
        Throwable value = failure;
        while (value.getCause() != null && value.getCause() != value) value = value.getCause();
        String detail = value.getMessage();
        String result = value.getClass().getSimpleName()
                + (detail == null || detail.isEmpty() ? "" : ": " + detail);
        return result.length() > 240 ? result.substring(0, 240) : result;
    }
}
