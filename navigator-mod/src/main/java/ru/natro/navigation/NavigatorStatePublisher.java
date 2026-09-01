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
import java.util.LinkedHashSet;
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

        /** The exact automotive Navigation session used by Navigator 30.3.0. */
        void onNavigationRuntime(Object navigation);

        void onNavigationState(String snapshotJson, String routeJson, Object drivingRoute,
                               long routeEpoch, long jamFingerprint,
                               RoutePolylineStyler.JamStyle jamStyle,
                               NavigationFrame navigationFrame);

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

    /**
     * Primitive camera/progress sample consumed by both independent MapWindows. It bypasses JSON
     * completely and is deliberately cheaper than the text-rich HUD snapshot.
     */
    static final class NavigationFrame {
        final double latitude;
        final double longitude;
        final double bearingDegrees;
        final double speedKmh;
        final boolean routeActive;
        final boolean routeProgressValid;
        final int routeSegmentIndex;
        final double routeSegmentPosition;
        final Object currentRoutePoint;
        final List<TrafficLightFrame> trafficLights;
        final long trafficLightsSampleElapsedMs;
        final List<CameraDirectionFrame> cameraDirections;
        final long cameraDirectionsSampleElapsedMs;
        final LaneGuidanceFrame laneGuidance;
        final long laneGuidanceSampleElapsedMs;
        final List<RouteStreetLabelFrame> routeStreetLabels;
        final long routeStreetLabelsSampleElapsedMs;
        final List<RouteTurnFrame> routeTurns;
        final long routeTurnsSampleElapsedMs;

        NavigationFrame(double latitude, double longitude, double bearingDegrees,
                        double speedKmh, boolean routeActive,
                        boolean routeProgressValid, int routeSegmentIndex,
                        double routeSegmentPosition, Object currentRoutePoint) {
            this(latitude, longitude, bearingDegrees, speedKmh, routeActive,
                    routeProgressValid, routeSegmentIndex, routeSegmentPosition,
                    currentRoutePoint, Collections.emptyList(), 0L,
                    Collections.emptyList(), 0L, null, 0L,
                    Collections.emptyList(), 0L,
                    Collections.emptyList(), 0L);
        }

        NavigationFrame(double latitude, double longitude, double bearingDegrees,
                        double speedKmh, boolean routeActive,
                        boolean routeProgressValid, int routeSegmentIndex,
                        double routeSegmentPosition, Object currentRoutePoint,
                        List<TrafficLightFrame> trafficLights,
                        long trafficLightsSampleElapsedMs,
                        List<CameraDirectionFrame> cameraDirections,
                        long cameraDirectionsSampleElapsedMs,
                        LaneGuidanceFrame laneGuidance,
                        long laneGuidanceSampleElapsedMs,
                        List<RouteStreetLabelFrame> routeStreetLabels,
                        long routeStreetLabelsSampleElapsedMs,
                        List<RouteTurnFrame> routeTurns,
                        long routeTurnsSampleElapsedMs) {
            this.latitude = latitude;
            this.longitude = longitude;
            this.bearingDegrees = finite(bearingDegrees) ? bearingDegrees : 0d;
            this.speedKmh = finite(speedKmh) ? Math.max(0d, speedKmh) : 0d;
            this.routeActive = routeActive;
            this.routeProgressValid = routeProgressValid;
            this.routeSegmentIndex = Math.max(0, routeSegmentIndex);
            this.routeSegmentPosition = Math.max(0d, Math.min(1d, routeSegmentPosition));
            this.currentRoutePoint = currentRoutePoint;
            this.trafficLights = trafficLights == null
                    ? Collections.emptyList() : trafficLights;
            this.trafficLightsSampleElapsedMs = Math.max(0L, trafficLightsSampleElapsedMs);
            this.cameraDirections = cameraDirections == null
                    ? Collections.emptyList() : cameraDirections;
            this.cameraDirectionsSampleElapsedMs =
                    Math.max(0L, cameraDirectionsSampleElapsedMs);
            this.laneGuidance = laneGuidance;
            this.laneGuidanceSampleElapsedMs = Math.max(0L, laneGuidanceSampleElapsedMs);
            this.routeStreetLabels = routeStreetLabels == null
                    ? Collections.emptyList() : routeStreetLabels;
            this.routeStreetLabelsSampleElapsedMs =
                    Math.max(0L, routeStreetLabelsSampleElapsedMs);
            this.routeTurns = routeTurns == null ? Collections.emptyList() : routeTurns;
            this.routeTurnsSampleElapsedMs = Math.max(0L, routeTurnsSampleElapsedMs);
        }

        NavigationFrame withMapOverlays(List<TrafficLightFrame> trafficLightValues,
                                        long trafficLightsSampledAt,
                                        List<CameraDirectionFrame> cameraDirectionValues,
                                        long cameraDirectionsSampledAt,
                                        LaneGuidanceFrame laneGuidanceValue,
                                        long laneGuidanceSampledAt) {
            return new NavigationFrame(latitude, longitude, bearingDegrees, speedKmh,
                    routeActive, routeProgressValid, routeSegmentIndex,
                    routeSegmentPosition, currentRoutePoint,
                    trafficLightValues, trafficLightsSampledAt,
                    cameraDirectionValues, cameraDirectionsSampledAt,
                    laneGuidanceValue, laneGuidanceSampledAt,
                    routeStreetLabels, routeStreetLabelsSampleElapsedMs,
                    routeTurns, routeTurnsSampleElapsedMs);
        }

        NavigationFrame withRouteStreetLabels(List<RouteStreetLabelFrame> labels,
                                              long sampledAt) {
            return new NavigationFrame(latitude, longitude, bearingDegrees, speedKmh,
                    routeActive, routeProgressValid, routeSegmentIndex,
                    routeSegmentPosition, currentRoutePoint,
                    trafficLights, trafficLightsSampleElapsedMs,
                    cameraDirections, cameraDirectionsSampleElapsedMs,
                    laneGuidance, laneGuidanceSampleElapsedMs, labels, sampledAt,
                    routeTurns, routeTurnsSampleElapsedMs);
        }

        NavigationFrame withRouteTurns(List<RouteTurnFrame> turns, long sampledAt) {
            return new NavigationFrame(latitude, longitude, bearingDegrees, speedKmh,
                    routeActive, routeProgressValid, routeSegmentIndex,
                    routeSegmentPosition, currentRoutePoint,
                    trafficLights, trafficLightsSampleElapsedMs,
                    cameraDirections, cameraDirectionsSampleElapsedMs,
                    laneGuidance, laneGuidanceSampleElapsedMs,
                    routeStreetLabels, routeStreetLabelsSampleElapsedMs, turns, sampledAt);
        }

        boolean isValid() {
            return finite(latitude) && finite(longitude)
                    && latitude >= -90d && latitude <= 90d
                    && longitude >= -180d && longitude <= 180d;
        }
    }

    /** A name supplied by Guidance and anchored to a verified position on the active route. */
    static final class RouteStreetLabelFrame {
        final String id;
        final String text;
        final double latitude;
        final double longitude;
        final float bearingDegrees;

        RouteStreetLabelFrame(String id, String text, double latitude, double longitude,
                              float bearingDegrees) {
            this.id = id == null ? "" : id;
            this.text = text == null ? "" : text;
            this.latitude = latitude;
            this.longitude = longitude;
            this.bearingDegrees = normalizeBearing(bearingDegrees);
        }

        boolean hasContent() {
            return !id.isEmpty() && !text.isEmpty()
                    && finite(latitude) && latitude >= -90d && latitude <= 90d
                    && finite(longitude) && longitude >= -180d && longitude <= 180d;
        }
    }

    /** One upcoming Windshield manoeuvre anchored directly to the active route. */
    static final class RouteTurnFrame {
        final String id;
        final String action;
        final double latitude;
        final double longitude;
        final float bearingDegrees;

        RouteTurnFrame(String id, String action, double latitude, double longitude,
                       float bearingDegrees) {
            this.id = id == null ? "" : id;
            this.action = action == null ? "" : action;
            this.latitude = latitude;
            this.longitude = longitude;
            this.bearingDegrees = normalizeBearing(bearingDegrees);
        }

        boolean hasContent() {
            return !id.isEmpty() && !action.isEmpty()
                    && finite(latitude) && latitude >= -90d && latitude <= 90d
                    && finite(longitude) && longitude >= -180d && longitude <= 180d;
        }
    }

    /** One validated Windshield signal shared by JSON/HUD and both map renderers. */
    static final class TrafficLightFrame {
        final String id;
        final double latitude;
        final double longitude;
        final int distanceMeters;
        final int secondsLeft;
        final String signal;
        final String sectionType;
        final String arrow;

        TrafficLightFrame(String id, double latitude, double longitude,
                          int distanceMeters, int secondsLeft, String signal,
                          String sectionType, String arrow) {
            this.id = id == null ? "" : id;
            this.latitude = latitude;
            this.longitude = longitude;
            this.distanceMeters = Math.max(-1, distanceMeters);
            this.secondsLeft = Math.max(-1, secondsLeft);
            this.signal = signal == null ? "" : signal;
            this.sectionType = sectionType == null ? "" : sectionType;
            this.arrow = arrow == null ? "" : arrow;
        }

        boolean hasMapPosition() {
            return finite(latitude) && latitude >= -90d && latitude <= 90d
                    && finite(longitude) && longitude >= -180d && longitude <= 180d;
        }

        JSONObject toJson() throws JSONException {
            JSONObject result = new JSONObject()
                    .put("id", id)
                    .put("distanceMeters", distanceMeters)
                    .put("secondsLeft", secondsLeft)
                    .put("signal", signal)
                    .put("sectionType", sectionType)
                    .put("arrow", arrow);
            if (hasMapPosition()) {
                result.put("latitude", latitude).put("longitude", longitude);
            }
            return result;
        }
    }

    /** One active route camera plus the actual control direction supplied by Windshield. */
    static final class CameraDirectionFrame {
        final String id;
        final double latitude;
        final double longitude;
        final int distanceMeters;
        final float bearingDegrees;
        final boolean inFace;
        final boolean inBack;
        /** Positive source value only; -1 means MapKit did not publish a limit. */
        final int speedLimitKmh;
        /** Exact EventTag names supplied by MapKit, never inferred from the camera icon. */
        final List<String> controlTags;

        CameraDirectionFrame(String id, double latitude, double longitude,
                             int distanceMeters, float bearingDegrees,
                             boolean inFace, boolean inBack,
                             int speedLimitKmh, List<String> controlTags) {
            this.id = id == null ? "" : id;
            this.latitude = latitude;
            this.longitude = longitude;
            this.distanceMeters = Math.max(-1, distanceMeters);
            this.bearingDegrees = normalizeBearing(bearingDegrees);
            this.inFace = inFace;
            this.inBack = inBack;
            this.speedLimitKmh = speedLimitKmh > 0 ? speedLimitKmh : -1;
            this.controlTags = controlTags == null
                    ? Collections.emptyList() : Collections.unmodifiableList(
                    new ArrayList<>(controlTags));
        }

        boolean hasMapPosition() {
            return finite(latitude) && latitude >= -90d && latitude <= 90d
                    && finite(longitude) && longitude >= -180d && longitude <= 180d;
        }
    }

    /** A route-positioned lane sign rendered by both independent map layers. */
    static final class LaneGuidanceFrame {
        final String id;
        final double latitude;
        final double longitude;
        final int distanceMeters;
        final float bearingDegrees;
        /** Original MapKit object retained in-process for the stock Yandex renderer. */
        final Object laneSign;
        final List<LaneFrame> lanes;

        LaneGuidanceFrame(String id, double latitude, double longitude,
                          int distanceMeters, float bearingDegrees,
                          Object laneSign, List<LaneFrame> lanes) {
            this.id = id == null ? "" : id;
            this.latitude = latitude;
            this.longitude = longitude;
            this.distanceMeters = Math.max(-1, distanceMeters);
            this.bearingDegrees = normalizeBearing(bearingDegrees);
            this.laneSign = laneSign;
            this.lanes = lanes == null ? Collections.emptyList() : lanes;
        }

        boolean hasMapPosition() {
            return finite(latitude) && latitude >= -90d && latitude <= 90d
                    && finite(longitude) && longitude >= -180d && longitude <= 180d;
        }

        boolean hasContent() {
            if (!hasMapPosition() || laneSign == null || lanes.isEmpty()) return false;
            for (LaneFrame lane : lanes) {
                if (lane != null && !lane.directions.isEmpty()) return true;
            }
            return false;
        }
    }

    static final class LaneFrame {
        final String kind;
        final String highlightedDirection;
        final List<String> directions;

        LaneFrame(String kind, String highlightedDirection, List<String> directions) {
            this.kind = kind == null ? "" : kind;
            this.highlightedDirection = highlightedDirection == null
                    ? "" : highlightedDirection;
            this.directions = directions == null ? Collections.emptyList() : directions;
        }
    }

    private static final String TAG = "NatroNavigationState";
    private static final long CAMERA_INTERVAL_MS = 100L;
    /** Primitive camera extraction cadence; duplicate output frames are filtered by each map. */
    private static final long STATE_INTERVAL_MS = 33L;
    /** Text, lanes and traffic lights are human-facing and do not need the map's 30 Hz cadence. */
    private static final long SNAPSHOT_INTERVAL_MS = 100L;
    /** Signal phases change at one-second resolution; 2 Hz is ample and halves reflection work. */
    private static final long TRAFFIC_LIGHT_INTERVAL_MS = 500L;
    /** Street names change slowly; one verified route scan per second avoids needless reflection. */
    private static final long ROUTE_STREET_LABEL_INTERVAL_MS = 1_000L;
    /** MapKit defaults vary by host; request enough upcoming lights for both independent maps. */
    private static final int MAX_UPCOMING_TRAFFIC_LIGHTS = 8;
    /** Windshield returns only cameras which are active for the current route direction. */
    private static final int MAX_ACTIVE_SPEED_CAMERAS = 8;
    private static final int MAX_MAP_LANES = 8;
    private static final int MAX_ROUTE_STREET_LABELS = 6;
    private static final int MAX_ROUTE_TURNS = 10;
    private static final long MIN_RESOLVE_RETRY_MS = 250L;
    private static final long MAX_RESOLVE_RETRY_MS = 5_000L;
    private static final long ROUTE_RECONCILE_CONFIRM_MS = 250L;
    /** Hold the last map-matched point across a short RoutePosition publication gap. */
    private static final long ROUTE_MATCH_HOLD_MS = 2_500L;
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
    private Object navigation;
    private Object guidance;
    private Object guidanceListener;
    private Object windshield;
    private Object windshieldListener;

    private long sequence;
    private long routeEpoch;
    private long activeJamFingerprint;
    private RoutePolylineStyler.JamStyle activeJamStyle =
            RoutePolylineStyler.JamStyle.EMPTY;
    private long lastStateDispatchElapsedMs;
    private long lastSnapshotDispatchElapsedMs;
    private boolean statePublishScheduled;
    private boolean pendingForceRoute;
    private String activeRouteKey;
    private String activeRouteId = "";
    private Object activeRoute;
    private String encodedRoute = "";
    private long cachedDestinationEpoch = Long.MIN_VALUE;
    private String cachedDestination = "";
    // MapKit may report metadata weight for only the remaining route after position updates.
    // Freeze the first positive distance per route epoch so HUD trip progress has a stable base.
    private int activeRouteTotalDistanceMeters = -1;
    private List<TrafficLightFrame> activeTrafficLights = Collections.emptyList();
    private long activeTrafficLightsSampleElapsedMs;
    private List<CameraDirectionFrame> activeCameraDirections = Collections.emptyList();
    private long activeCameraDirectionsSampleElapsedMs;
    private LaneState activeLaneState = LaneState.EMPTY;
    private long activeLaneSampleElapsedMs;
    private List<RouteStreetLabelFrame> activeRouteStreetLabels = Collections.emptyList();
    private long activeRouteStreetLabelsSampleElapsedMs;
    private List<RouteTurnFrame> activeRouteTurns = Collections.emptyList();
    private long activeRouteTurnsSampleElapsedMs;
    private long lastRouteStreetLabelsReadElapsedMs;
    private long lastTrafficLightsReadElapsedMs;
    private double lastRouteMatchedLatitude = Double.NaN;
    private double lastRouteMatchedLongitude = Double.NaN;
    private double lastRouteMatchedBearing = Double.NaN;
    private long lastRouteMatchedElapsedMs;
    private Object activeRoutePolylineIndex;
    private long activeRoutePolylineIndexEpoch = Long.MIN_VALUE;
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
        runOnMain(() -> publishState(false, false, true));
    }

    void requestRoute() {
        runOnMain(() -> publishState(true, true, true));
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
        if (guidance == null || naviKitGuidance == null || navigation == null) {
            try {
                Object applicationComponent = invoke(activity.getApplication(), "c");
                Object nextNaviKitGuidance = invoke(applicationComponent, "getGuidance");
                Object nextNavigation = invoke(nextNaviKitGuidance, "navigation");
                Object nextGuidance = invoke(nextNavigation, "getGuidance");
                Object valid = invoke(nextGuidance, "isValid");
                if (valid instanceof Boolean && !((Boolean) valid)) {
                    throw new IllegalStateException("automotive Guidance is invalid");
                }
                naviKitGuidance = nextNaviKitGuidance;
                navigation = nextNavigation;
                guidance = nextGuidance;
                try {
                    invoke(nextGuidance, "setMaxNumberOfUpcomingTrafficLights",
                            new Class<?>[]{int.class}, MAX_UPCOMING_TRAFFIC_LIGHTS);
                    attachGuidanceListeners(nextGuidance);
                } catch (Throwable listenerFailure) {
                    detachGuidanceListeners();
                    naviKitGuidance = null;
                    navigation = null;
                    guidance = null;
                    throw listenerFailure;
                }
                sink.onNavigationRuntime(nextNavigation);
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
        if (guidance != null && naviKitGuidance != null && navigation != null
                && (resolvedSomething || primaryMap != null)) {
            publishState(true, true, true);
        }
        if (primaryMap == null || guidance == null || naviKitGuidance == null
                || navigation == null) {
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
            else scheduleStatePublish(false);
        });
        invoke(nextGuidance, "addListener", new Class<?>[]{guidanceListenerClass},
                guidanceListener);

        windshield = invoke(nextGuidance, "getWindshield");
        Class<?> windshieldListenerClass = Class.forName(
                "com.yandex.mapkit.navigation.automotive.WindshieldListener");
        windshieldListener = listenerProxy(windshieldListenerClass,
                methodName -> scheduleStatePublish(false));
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

    private void publishState(boolean routeMayHaveChanged, boolean forceRoute,
                              boolean forceSnapshot) {
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
            Object nextRoute = invoke(currentNaviKitGuidance, "route");
            String routeStatus = readRouteStatus(currentGuidance);
            // During onRouteFinished the Java wrapper may still expose the old user route for the
            // remainder of that native callback, so the terminal status is authoritative too.
            if ("ROUTE_FINISHED".equals(routeStatus)) nextRoute = null;
            boolean routeChanged = updateRoute(nextRoute, routeMayHaveChanged);
            if (routeChanged || forceRoute) {
                activeJamStyle = RoutePolylineStyler.readJamStyle(activeRoute);
                activeJamFingerprint = activeJamStyle.fingerprint;
            }
            SnapshotInputs inputs = readSnapshotInputs(currentGuidance, activeRoute);
            long elapsedNow = SystemClock.elapsedRealtime();
            boolean snapshotDue = forceSnapshot || routeChanged || forceRoute
                    || elapsedNow - lastSnapshotDispatchElapsedMs >= SNAPSHOT_INTERVAL_MS;
            if (snapshotDue) {
                Object engineRoute = invoke(currentGuidance, "getCurrentRoute");
                Object freeDriveRoute = invoke(currentNaviKitGuidance, "freeDriveRoute");
                publishRouteDiagnostic(routeStatus, engineRoute, freeDriveRoute);
                if (activeRoute == null) {
                    activeLaneState = LaneState.EMPTY;
                    activeLaneSampleElapsedMs = 0L;
                    activeRouteStreetLabels = Collections.emptyList();
                    activeRouteStreetLabelsSampleElapsedMs = 0L;
                    activeRouteTurns = Collections.emptyList();
                    activeRouteTurnsSampleElapsedMs = 0L;
                    lastRouteStreetLabelsReadElapsedMs = elapsedNow;
                } else {
                    activeLaneState = readLanes(inputs.routePosition,
                            inputs.frame.bearingDegrees);
                    activeLaneSampleElapsedMs = elapsedNow;
                    boolean streetLabelsDue = routeChanged || forceRoute
                            || elapsedNow - lastRouteStreetLabelsReadElapsedMs
                            >= ROUTE_STREET_LABEL_INTERVAL_MS;
                    if (streetLabelsDue) {
                        activeRouteStreetLabels = Collections.unmodifiableList(
                                readRouteStreetLabels(currentGuidance,
                                        inputs.routePosition, inputs.frame));
                        activeRouteStreetLabelsSampleElapsedMs = elapsedNow;
                        activeRouteTurns = Collections.unmodifiableList(
                                readRouteTurns(inputs.routePosition,
                                        inputs.frame.bearingDegrees));
                        activeRouteTurnsSampleElapsedMs = elapsedNow;
                        lastRouteStreetLabelsReadElapsedMs = elapsedNow;
                    }
                }
                boolean trafficSampleDue = routeChanged || forceRoute
                        || elapsedNow - lastTrafficLightsReadElapsedMs
                        >= TRAFFIC_LIGHT_INTERVAL_MS;
                if (activeRoute == null) {
                    activeTrafficLights = Collections.emptyList();
                    activeTrafficLightsSampleElapsedMs = 0L;
                    activeCameraDirections = Collections.emptyList();
                    activeCameraDirectionsSampleElapsedMs = 0L;
                    lastTrafficLightsReadElapsedMs = elapsedNow;
                } else if (trafficSampleDue) {
                    activeTrafficLights = Collections.unmodifiableList(
                            readTrafficLights(inputs.routePosition));
                    activeTrafficLightsSampleElapsedMs = elapsedNow;
                    activeCameraDirections = Collections.unmodifiableList(
                            readActiveSpeedCameras(activeRoute,
                                    inputs.frame.bearingDegrees));
                    activeCameraDirectionsSampleElapsedMs = elapsedNow;
                    lastTrafficLightsReadElapsedMs = elapsedNow;
                }
            }
            NavigationFrame navigationFrame = inputs.frame.withMapOverlays(
                    activeTrafficLights, activeTrafficLightsSampleElapsedMs,
                    activeCameraDirections, activeCameraDirectionsSampleElapsedMs,
                    activeLaneState.mapFrame, activeLaneSampleElapsedMs)
                    .withRouteStreetLabels(activeRouteStreetLabels,
                            activeRouteStreetLabelsSampleElapsedMs)
                    .withRouteTurns(activeRouteTurns, activeRouteTurnsSampleElapsedMs);
            String snapshot = snapshotDue
                    ? buildSnapshot(currentGuidance, activeRoute, inputs,
                            activeTrafficLights, activeLaneState).toString() : null;
            if (snapshotDue) lastSnapshotDispatchElapsedMs = elapsedNow;
            String route = routeChanged || forceRoute ? buildRoutePayload().toString() : null;
            sink.onNavigationState(snapshot, route, activeRoute, routeEpoch,
                    activeJamFingerprint, activeJamStyle, navigationFrame);
            lastStateDispatchElapsedMs = elapsedNow;
        } catch (Throwable failure) {
            Log.w(TAG, "Could not publish Navigator state", failure);
            detachGuidanceListeners();
            activeTrafficLights = Collections.emptyList();
            activeTrafficLightsSampleElapsedMs = 0L;
            activeCameraDirections = Collections.emptyList();
            activeCameraDirectionsSampleElapsedMs = 0L;
            activeLaneState = LaneState.EMPTY;
            activeLaneSampleElapsedMs = 0L;
            activeRouteStreetLabels = Collections.emptyList();
            activeRouteStreetLabelsSampleElapsedMs = 0L;
            activeRouteTurns = Collections.emptyList();
            activeRouteTurnsSampleElapsedMs = 0L;
            lastRouteStreetLabelsReadElapsedMs = 0L;
            lastTrafficLightsReadElapsedMs = 0L;
            naviKitGuidance = null;
            if (navigation != null) sink.onNavigationRuntime(null);
            navigation = null;
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

    /**
     * Guidance and Windshield often emit several callbacks for one native position update.
     * Collapse that burst into one extraction pass and retain an upgraded traffic refresh flag.
     */
    private void scheduleStatePublish(boolean forceRoute) {
        pendingForceRoute |= forceRoute;
        if (statePublishScheduled) return;
        statePublishScheduled = true;
        long elapsed = SystemClock.elapsedRealtime() - lastStateDispatchElapsedMs;
        long delay = Math.max(0L, STATE_INTERVAL_MS - elapsed);
        if (delay == 0L) main.post(dispatchState);
        else main.postDelayed(dispatchState, delay);
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
        if (activeRouteKey != null && nextRoute == activeRoute && !routeMayHaveChanged) {
            if (activeRouteTotalDistanceMeters <= 0 && nextRoute != null) {
                activeRouteTotalDistanceMeters = readRouteTotalDistance(nextRoute);
            }
            return false;
        }
        String routeId = nextRoute == null ? "" : text(invoke(nextRoute, "getRouteId"));
        if (!routeMayHaveChanged && nextRoute != null && !routeId.isEmpty()
                && routeId.equals(activeRouteId)) {
            // MapKit frequently returns a fresh Java DrivingRoute wrapper for the same native
            // route. Do not encode and hash its complete polyline on every Guidance pulse.
            activeRoute = nextRoute;
            if (activeRouteTotalDistanceMeters <= 0) {
                activeRouteTotalDistanceMeters = readRouteTotalDistance(nextRoute);
            }
            return false;
        }
        String nextEncoded = nextRoute == null ? "" : encodeRoute(nextRoute);
        String nextKey = nextRoute == null ? ""
                : routeId + ':' + nextEncoded.length() + ':' + nextEncoded.hashCode();
        boolean initial = activeRouteKey == null;
        boolean changed = !initial && !activeRouteKey.equals(nextKey);
        if ((initial || changed || routeMayHaveChanged) && conditionsRoute != nextRoute) {
            // Only a confirmed route lifecycle transition may replace the conditions listener.
            // Fresh wrappers of the same native route are common on every Guidance pulse.
            attachRouteConditions(nextRoute);
        }
        if (initial && nextRoute != null) routeEpoch = 1L;
        else if (changed) routeEpoch++;
        activeRoute = nextRoute;
        activeRouteId = routeId;
        activeRouteKey = nextKey;
        encodedRoute = nextEncoded;
        if (initial || changed) {
            cachedDestinationEpoch = Long.MIN_VALUE;
            cachedDestination = "";
            clearRouteMatchedPosition();
            activeRoutePolylineIndex = null;
            activeRoutePolylineIndexEpoch = Long.MIN_VALUE;
        }
        if (initial || changed || activeRouteTotalDistanceMeters <= 0) {
            activeRouteTotalDistanceMeters = readRouteTotalDistance(nextRoute);
        }
        return initial || changed;
    }

    /**
     * Reads location and route progress once for both independent maps. The previous renderer
     * path repeated getRoutePosition/getPosition reflection separately for HUD and cluster.
     */
    private SnapshotInputs readSnapshotInputs(Object currentGuidance, Object route)
            throws Exception {
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
        RouteProgressSample progress = readRouteProgress(route, routePosition);
        // Guidance.getLocation() is raw GNSS and can temporarily jump onto a parallel street.
        // The stock Navigator renders RoutePosition.getPoint(), which is map-matched to the active
        // route. Give both independent maps that same canonical point and heading while guidance is
        // active; raw GNSS remains the bounded fallback during a transient RoutePosition gap.
        if (routePosition != null) {
            try {
                double routeBearing = number(invoke(routePosition, "heading"), Double.NaN);
                if (finite(routeBearing)) bearing = routeBearing;
            } catch (Throwable unavailable) {
                // Some vendor builds omit heading(); position matching remains independently useful.
            }
        }
        long nowElapsedMs = SystemClock.elapsedRealtime();
        boolean matchedThisFrame = false;
        if (route != null && routePosition != null) {
            try {
                // getPoint() exists even during the short interval in which DrivingRoute.getPosition()
                // cannot yet be projected. Do not couple cursor matching to polyline trimming.
                Object matchedPoint = invoke(routePosition, "getPoint");
                double routeLatitude = number(
                        invoke(matchedPoint, "getLatitude"), Double.NaN);
                double routeLongitude = number(
                        invoke(matchedPoint, "getLongitude"), Double.NaN);
                if (finite(routeLatitude) && routeLatitude >= -90d && routeLatitude <= 90d
                        && finite(routeLongitude) && routeLongitude >= -180d
                        && routeLongitude <= 180d) {
                    latitude = routeLatitude;
                    longitude = routeLongitude;
                    lastRouteMatchedLatitude = routeLatitude;
                    lastRouteMatchedLongitude = routeLongitude;
                    lastRouteMatchedBearing = bearing;
                    lastRouteMatchedElapsedMs = nowElapsedMs;
                    matchedThisFrame = true;
                }
            } catch (Throwable unavailable) {
                // Fall through to the last fresh matched point, then finally raw GNSS.
            }
        }
        if (!matchedThisFrame && route != null && lastRouteMatchedElapsedMs > 0L
                && nowElapsedMs - lastRouteMatchedElapsedMs <= ROUTE_MATCH_HOLD_MS) {
            latitude = lastRouteMatchedLatitude;
            longitude = lastRouteMatchedLongitude;
            if (finite(lastRouteMatchedBearing)) bearing = lastRouteMatchedBearing;
        }
        NavigationFrame frame = new NavigationFrame(
                latitude, longitude, bearing, speedKmh, route != null,
                progress.valid, progress.segmentIndex, progress.segmentPosition,
                progress.currentPoint);
        return new SnapshotInputs(frame, routePosition);
    }

    private void clearRouteMatchedPosition() {
        lastRouteMatchedLatitude = Double.NaN;
        lastRouteMatchedLongitude = Double.NaN;
        lastRouteMatchedBearing = Double.NaN;
        lastRouteMatchedElapsedMs = 0L;
    }

    private RouteProgressSample readRouteProgress(Object route, Object routePosition) {
        if (route == null) return RouteProgressSample.INVALID;
        try {
            Object currentPoint = null;
            if (routePosition != null) {
                try {
                    currentPoint = invoke(routePosition, "getPoint");
                } catch (Throwable unavailable) {
                    // Progress can still fall back to DrivingRoute while the point is unavailable.
                }
            }

            // DrivingRoute.getPosition() describes completed guidance progress and can remain
            // ahead after a GNSS jump. Project the current RoutePosition first so trimming follows
            // the same reversible, map-matched point as the cursor.
            Object polylinePosition = null;
            if (routePosition != null) {
                try {
                    String routeId = String.valueOf(invoke(route, "getRouteId"));
                    polylinePosition = invoke(routePosition, "positionOnRoute",
                            new Class<?>[]{String.class}, routeId);
                } catch (Throwable unavailable) {
                    // The route can be between native wrappers for one guidance callback.
                }
            }
            if (polylinePosition == null && currentPoint != null) {
                try {
                    polylinePosition = closestPositionOnRoute(route, currentPoint);
                } catch (Throwable unavailable) {
                    // Keep the completed-progress value as the last-resort continuity fallback.
                }
            }
            if (polylinePosition == null) polylinePosition = invoke(route, "getPosition");
            if (polylinePosition == null) return RouteProgressSample.INVALID;
            int segmentIndex = ((Number) invoke(
                    polylinePosition, "getSegmentIndex")).intValue();
            double segmentPosition = ((Number) invoke(
                    polylinePosition, "getSegmentPosition")).doubleValue();
            return new RouteProgressSample(true, segmentIndex, segmentPosition, currentPoint);
        } catch (Throwable unavailable) {
            return RouteProgressSample.INVALID;
        }
    }

    /** Cached native PolylineIndex fallback for the rare positionOnRoute transition gap. */
    private Object closestPositionOnRoute(Object route, Object currentPoint) throws Exception {
        Object index = activeRoutePolylineIndex;
        if (index == null || activeRoutePolylineIndexEpoch != routeEpoch) {
            Object geometry = invoke(route, "getGeometry");
            Class<?> polylineClass = Class.forName("com.yandex.mapkit.geometry.Polyline");
            Class<?> utilsClass = Class.forName(
                    "com.yandex.mapkit.geometry.geo.PolylineUtils");
            Method create = ReflectMethods.publicMethod(utilsClass, "createPolylineIndex",
                    new Class<?>[]{polylineClass});
            index = create.invoke(null, geometry);
            activeRoutePolylineIndex = index;
            activeRoutePolylineIndexEpoch = routeEpoch;
        }
        Class<?> pointClass = Class.forName("com.yandex.mapkit.geometry.Point");
        Class<?> priorityClass = Class.forName(
                "com.yandex.mapkit.geometry.geo.PolylineIndex$Priority");
        Object priority = priorityClass.getField("CLOSEST_TO_RAW_POINT").get(null);
        return invoke(index, "closestPolylinePosition",
                new Class<?>[]{pointClass, priorityClass, double.class},
                currentPoint, priority, 1.0d);
    }

    private JSONObject buildSnapshot(Object currentGuidance, Object route,
                                     SnapshotInputs inputs,
                                     List<TrafficLightFrame> trafficLights,
                                     LaneState lanes)
            throws Exception {
        long now = System.currentTimeMillis();
        Object routePosition = inputs.routePosition;
        NavigationFrame frame = inputs.frame;
        boolean routeActive = route != null;

        int remainingDistance = !routeActive || routePosition == null ? -1 : nonNegativeInt(
                number(invoke(routePosition, "distanceToFinish"), -1d));
        int remainingDuration = !routeActive || routePosition == null ? -1 : nonNegativeInt(
                number(invoke(routePosition, "timeToFinish"), -1d));
        int routeTotalDistance = routeActive ? activeRouteTotalDistanceMeters : -1;
        long arrival = remainingDuration < 0 ? 0L
                : now + Math.min(31_536_000, remainingDuration) * 1_000L;

        Manoeuvre manoeuvre = routeActive && routePosition != null
                ? readManoeuvre(routePosition) : Manoeuvre.EMPTY;
        JSONArray trafficLightJson = new JSONArray();
        if (routeActive) {
            for (TrafficLightFrame light : trafficLights) trafficLightJson.put(light.toJson());
        }
        Object speedLimitValue = invoke(currentGuidance, "getSpeedLimit");
        int speedLimit = speedLimitValue == null ? 0 : nonNegativeInt(
                number(invoke(speedLimitValue, "getValue"), 0d) * 3.6d);

        JSONObject result = new JSONObject()
                .put("schema", 1)
                .put("sequence", ++sequence)
                .put("routeEpoch", routeEpoch)
                .put("sourceTimestampMs", now)
                .put("routeActive", routeActive)
                .put("maneuverType", manoeuvre.type)
                .put("maneuverTitle", manoeuvre.title)
                .put("maneuverSubtext", manoeuvre.subtext)
                .put("street", text(invoke(currentGuidance, "getRoadName")))
                .put("destination", routeActive ? destinationForRoute(route) : "")
                .put("maneuverDistanceMeters", manoeuvre.distanceMeters)
                .put("routeTotalDistanceMeters", routeTotalDistance)
                .put("remainingDistanceMeters", remainingDistance)
                .put("remainingDurationSeconds", remainingDuration)
                .put("arrivalEpochMs", arrival)
                .put("speedLimitKmh", Math.min(300, speedLimit))
                .put("laneDistanceMeters", lanes.distanceMeters)
                .put("lanesJson", lanes.values.toString())
                .put("trafficLightsJson", trafficLightJson.toString());
        if (finite(frame.latitude)) result.put("latitude", frame.latitude);
        if (finite(frame.longitude)) result.put("longitude", frame.longitude);
        if (finite(frame.bearingDegrees)) {
            result.put("bearingDegrees", normalizeBearing(frame.bearingDegrees));
        }
        if (finite(frame.speedKmh)) {
            result.put("speedKmh", Math.min(400d, frame.speedKmh));
        }
        return result;
    }

    private String destinationForRoute(Object route) {
        if (cachedDestinationEpoch == routeEpoch) return cachedDestination;
        cachedDestination = readDestination(route);
        cachedDestinationEpoch = routeEpoch;
        return cachedDestination;
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

    /**
     * Builds a small route-owned label set. Substrate labels are disabled by the map profile, so
     * names from neighbouring streets can never enter this layer. The current Guidance road and
     * upcoming manoeuvre toponyms are the only accepted sources.
     */
    private List<RouteStreetLabelFrame> readRouteStreetLabels(
            Object currentGuidance, Object routePosition, NavigationFrame frame)
            throws Exception {
        ArrayList<RouteStreetLabelFrame> result = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();

        try {
            String currentStreet = text(invoke(currentGuidance, "getRoadName"));
            double currentLatitude = frame.latitude;
            double currentLongitude = frame.longitude;
            // Put the name ahead of the cursor instead of directly under the route/cursor stack.
            // The anchor remains an actual point of the active route geometry.
            Object currentPoint = currentStreetLabelPoint(frame);
            if (currentPoint != null) {
                currentLatitude = number(invoke(currentPoint, "getLatitude"), currentLatitude);
                currentLongitude = number(invoke(currentPoint, "getLongitude"), currentLongitude);
            }
            float currentBearing = normalizeBearing((float) frame.bearingDegrees);
            try {
                currentBearing = normalizeBearing((float) number(
                        invoke(routePosition, "heading"), currentBearing));
            } catch (Throwable ignored) {}
            addRouteStreetLabel(result, seen,
                    "route-street:" + routeEpoch + ":current", currentStreet,
                    currentLatitude, currentLongitude, currentBearing);
        } catch (Throwable unavailable) {
            // Road-name support differs across automotive MapKit builds; upcoming names can remain.
        }

        List<?> manoeuvres;
        try {
            manoeuvres = windshield == null
                    ? Collections.emptyList() : invokeList(windshield, "getManoeuvres");
        } catch (Throwable unavailable) {
            manoeuvres = Collections.emptyList();
        }
        for (Object upcoming : manoeuvres) {
            if (result.size() >= MAX_ROUTE_STREET_LABELS) break;
            try {
                Object position = invoke(upcoming, "getPosition");
                double ahead = routePosition == null ? 0d : distance(routePosition, position);
                if (!finite(ahead) || ahead < -5d || ahead > 12_000d) continue;
                Object annotation = invoke(upcoming, "getAnnotation");
                if (annotation == null) continue;
                // DescriptionText may be an instruction; Toponym is the verified street name.
                String street = text(invoke(annotation, "getToponym"));
                Object point = position == null ? null : invoke(position, "getPoint");
                if (point == null) continue;
                double latitude = number(invoke(point, "getLatitude"), Double.NaN);
                double longitude = number(invoke(point, "getLongitude"), Double.NaN);
                float bearing = normalizeBearing((float) frame.bearingDegrees);
                try {
                    bearing = normalizeBearing((float) number(
                            invoke(position, "heading"), bearing));
                } catch (Throwable ignored) {}
                String id = "route-street:" + routeEpoch + ':'
                        + Math.round(latitude * 100_000d) + ':'
                        + Math.round(longitude * 100_000d);
                addRouteStreetLabel(result, seen, id, street, latitude, longitude, bearing);
            } catch (Throwable malformedItem) {
                // One incomplete manoeuvre must not remove the other labels or guidance itself.
            }
        }
        return result;
    }

    private Object currentStreetLabelPoint(NavigationFrame frame) {
        Object fallback = frame.currentRoutePoint;
        if (!frame.routeProgressValid || activeRoute == null) return fallback;
        try {
            Object geometry = invoke(activeRoute, "getGeometry");
            List<?> points = invokeList(geometry, "getPoints");
            if (points.isEmpty()) return fallback;
            int start = Math.max(0, Math.min(points.size() - 1, frame.routeSegmentIndex));
            Object previous = points.get(start);
            double previousLatitude = number(invoke(previous, "getLatitude"), Double.NaN);
            double previousLongitude = number(invoke(previous, "getLongitude"), Double.NaN);
            double travelled = 0d;
            Object candidate = previous;
            int limit = Math.min(points.size(), start + 96);
            for (int index = start + 1; index < limit; index++) {
                Object point = points.get(index);
                double latitude = number(invoke(point, "getLatitude"), Double.NaN);
                double longitude = number(invoke(point, "getLongitude"), Double.NaN);
                travelled += geoDistanceMeters(previousLatitude, previousLongitude,
                        latitude, longitude);
                candidate = point;
                previousLatitude = latitude;
                previousLongitude = longitude;
                if (travelled >= 90d) break;
            }
            return candidate;
        } catch (Throwable unavailable) {
            return fallback;
        }
    }

    private static double geoDistanceMeters(double fromLatitude, double fromLongitude,
                                            double toLatitude, double toLongitude) {
        if (!finite(fromLatitude) || !finite(fromLongitude)
                || !finite(toLatitude) || !finite(toLongitude)) return 0d;
        double latitudeDelta = Math.toRadians(toLatitude - fromLatitude);
        double longitudeDelta = Math.toRadians(toLongitude - fromLongitude);
        double from = Math.toRadians(fromLatitude);
        double to = Math.toRadians(toLatitude);
        double sinLatitude = Math.sin(latitudeDelta / 2d);
        double sinLongitude = Math.sin(longitudeDelta / 2d);
        double value = sinLatitude * sinLatitude
                + Math.cos(from) * Math.cos(to) * sinLongitude * sinLongitude;
        return 12_742_000d * Math.asin(Math.min(1d, Math.sqrt(value)));
    }

    private static void addRouteStreetLabel(List<RouteStreetLabelFrame> result,
                                            LinkedHashSet<String> seen,
                                            String id, String rawText,
                                            double latitude, double longitude,
                                            float bearingDegrees) {
        String value = rawText == null ? "" : rawText.trim();
        if (value.isEmpty() || value.length() > 96 || !finite(latitude)
                || latitude < -90d || latitude > 90d || !finite(longitude)
                || longitude < -180d || longitude > 180d) return;
        String key = value.toLowerCase(Locale.ROOT);
        if (!seen.add(key)) return;
        RouteStreetLabelFrame frame = new RouteStreetLabelFrame(id, value,
                latitude, longitude, bearingDegrees);
        if (frame.hasContent()) result.add(frame);
    }

    /** Uses the same Windshield manoeuvre positions as Navigator's own route turn overlay. */
    private List<RouteTurnFrame> readRouteTurns(Object routePosition,
                                                double fallbackBearingDegrees) {
        ArrayList<RouteTurnFrame> result = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<?> manoeuvres;
        try {
            manoeuvres = windshield == null
                    ? Collections.emptyList() : invokeList(windshield, "getManoeuvres");
        } catch (Throwable unavailable) {
            return result;
        }
        for (Object upcoming : manoeuvres) {
            if (result.size() >= MAX_ROUTE_TURNS) break;
            try {
                Object position = invoke(upcoming, "getPosition");
                double ahead = routePosition == null ? 0d : distance(routePosition, position);
                if (!finite(ahead) || ahead < -5d || ahead > 12_000d) continue;
                Object annotation = invoke(upcoming, "getAnnotation");
                String action = annotation == null ? ""
                        : enumName(invoke(annotation, "getAction"));
                if (!isVisibleRouteTurn(action)) continue;
                Object point = position == null ? null : invoke(position, "getPoint");
                if (point == null) continue;
                double latitude = number(invoke(point, "getLatitude"), Double.NaN);
                double longitude = number(invoke(point, "getLongitude"), Double.NaN);
                if (!finite(latitude) || !finite(longitude)) continue;
                float bearing = normalizeBearing((float) fallbackBearingDegrees);
                try {
                    bearing = normalizeBearing((float) number(
                            invoke(position, "heading"), bearing));
                } catch (Throwable ignored) {}
                String key = action + ':' + Math.round(latitude * 100_000d)
                        + ':' + Math.round(longitude * 100_000d);
                if (!seen.add(key)) continue;
                RouteTurnFrame frame = new RouteTurnFrame(
                        "route-turn:" + routeEpoch + ':' + key,
                        action, latitude, longitude, bearing);
                if (frame.hasContent()) result.add(frame);
            } catch (Throwable malformedItem) {
                // A single incomplete manoeuvre never interrupts route/camera publication.
            }
        }
        return result;
    }

    private static boolean isVisibleRouteTurn(String action) {
        return action != null && !action.isEmpty()
                && !"UNKNOWN".equals(action) && !"STRAIGHT".equals(action)
                && !"FINISH".equals(action) && !"WAYPOINT".equals(action)
                && !"BOARD_FERRY".equals(action) && !"LEAVE_FERRY".equals(action);
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

    private LaneState readLanes(Object routePosition, double fallbackBearingDegrees)
            throws Exception {
        JSONArray result = new JSONArray();
        if (routePosition == null) return new LaneState(result, -1, null);
        Object upcoming = nearest(invokeList(windshield, "getLaneSigns"),
                routePosition, "getPosition");
        if (upcoming == null) return new LaneState(result, -1, null);
        Object position = invoke(upcoming, "getPosition");
        int distanceMeters = nonNegativeInt(distance(routePosition, position));
        Object sign = invoke(upcoming, "getLaneSign");
        ArrayList<LaneFrame> mapLanes = new ArrayList<>();
        for (Object lane : invokeList(sign, "getLanes")) {
            JSONArray directions = new JSONArray();
            ArrayList<String> directionNames = new ArrayList<>();
            for (Object direction : invokeList(lane, "getDirections")) {
                String name = enumName(direction);
                directions.put(name);
                if (!name.isEmpty() && !"UNKNOWN_DIRECTION".equals(name)) {
                    directionNames.add(name);
                }
            }
            String kind = enumName(invoke(lane, "getLaneKind"));
            String highlighted = enumName(invoke(lane, "getHighlightedDirection"));
            result.put(new JSONObject()
                    .put("kind", kind)
                    .put("highlightedDirection", highlighted)
                    .put("directions", directions));
            if (mapLanes.size() < MAX_MAP_LANES && !directionNames.isEmpty()) {
                mapLanes.add(new LaneFrame(kind, highlighted,
                        Collections.unmodifiableList(directionNames)));
            }
        }
        if (mapLanes.isEmpty()) return new LaneState(result, distanceMeters, null);
        double latitude = Double.NaN;
        double longitude = Double.NaN;
        try {
            Object point = position == null ? null : invoke(position, "getPoint");
            if (point != null) {
                latitude = number(invoke(point, "getLatitude"), Double.NaN);
                longitude = number(invoke(point, "getLongitude"), Double.NaN);
            }
        } catch (Throwable ignored) {}
        float bearing = normalizeBearing((float) fallbackBearingDegrees);
        try {
            bearing = normalizeBearing((float) number(invoke(position, "heading"), bearing));
        } catch (Throwable ignored) {}
        String id = finite(latitude) && finite(longitude)
                ? "lane-sign:" + Math.round(latitude * 100_000d)
                        + ':' + Math.round(longitude * 100_000d)
                : "";
        LaneGuidanceFrame frame = new LaneGuidanceFrame(id, latitude, longitude,
                distanceMeters, bearing, sign, Collections.unmodifiableList(mapLanes));
        return new LaneState(result, distanceMeters, frame.hasContent() ? frame : null);
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

    private List<TrafficLightFrame> readTrafficLights(Object routePosition) throws Exception {
        ArrayList<TrafficLightFrame> result = new ArrayList<>();
        if (routePosition == null) return result;
        for (Object light : invokeList(windshield, "getTrafficLightsWithSignal")) {
            if (result.size() >= MAX_UPCOMING_TRAFFIC_LIGHTS) break;
            Object position = invoke(light, "getPosition");
            double rawDistance = distance(routePosition, position);
            if (!finite(rawDistance) || rawDistance < -5d) continue;
            int distanceMeters = nonNegativeInt(rawDistance);
            int secondsLeft = nullableInt(invoke(light, "getSecondsLeft"));
            String signal = enumName(invoke(light, "getSignal"));
            // UNKNOWN/empty Windshield shells are not navigation data. Keeping them was the
            // source of the grey traffic-light placeholder with blank values after a route.
            if (!validTrafficSignal(signal)) continue;
            double latitude = Double.NaN;
            double longitude = Double.NaN;
            try {
                Object point = position == null ? null : invoke(position, "getPoint");
                if (point != null) {
                    latitude = number(invoke(point, "getLatitude"), Double.NaN);
                    longitude = number(invoke(point, "getLongitude"), Double.NaN);
                }
            } catch (Throwable unavailable) {
                // The standalone widget can still use a valid signal/countdown. The map layer
                // independently filters entries which have no geographic point.
            }
            String id = text(invoke(light, "getId"));
            if (id.isEmpty() && finite(latitude) && finite(longitude)) {
                id = "point:" + Math.round(latitude * 100_000d)
                        + ':' + Math.round(longitude * 100_000d);
            }
            if (id.isEmpty()) id = "route-light-" + result.size();
            result.add(new TrafficLightFrame(id, latitude, longitude,
                    distanceMeters, secondsLeft, signal,
                    enumName(invoke(light, "getSectionType")),
                    enumName(invoke(light, "getArrow"))));
        }
        return result;
    }

    private static boolean validTrafficSignal(String signal) {
        return "RED".equals(signal) || "YELLOW".equals(signal)
                || "RED_AND_YELLOW".equals(signal) || "GREEN".equals(signal);
    }

    /**
     * Reads the direction-aware camera stream. RoadEventsLayer's StyleProvider never receives
     * CameraData in MapKit 30.3.0, so styling the ordinary SPEED_CONTROL pin cannot expose this
     * information. Windshield is the authoritative route-aware source used by Navigator itself.
     */
    private List<CameraDirectionFrame> readActiveSpeedCameras(
            Object route, double fallbackBearingDegrees) throws Exception {
        ArrayList<CameraDirectionFrame> result = new ArrayList<>();
        if (route == null || windshield == null) return result;
        List<?> routePoints = Collections.emptyList();
        try {
            Object geometry = invoke(route, "getGeometry");
            if (geometry != null) routePoints = invokeList(geometry, "getPoints");
        } catch (Throwable ignored) {
            // Guidance bearing below remains valid while route geometry is being refreshed.
        }
        List<?> cameras;
        try {
            cameras = invokeList(windshield, "getActiveSpeedCameras");
        } catch (Throwable unavailable) {
            // Direction arrows are optional map decoration. A transient Windshield update must
            // not detach the otherwise healthy Guidance session or interrupt route publishing.
            return result;
        }
        for (Object camera : cameras) {
            if (result.size() >= MAX_ACTIVE_SPEED_CAMERAS) break;
            try {
                Object event = invoke(camera, "getEvent");
                Object point = event == null ? null : invoke(event, "getLocation");
                if (point == null) continue;
                Object directions = invoke(camera, "getActiveDirections");
                boolean inFace = directions != null
                        && Boolean.TRUE.equals(invoke(directions, "getInFace"));
                boolean inBack = directions != null
                        && Boolean.TRUE.equals(invoke(directions, "getInBack"));
                double latitude = number(invoke(point, "getLatitude"), Double.NaN);
                double longitude = number(invoke(point, "getLongitude"), Double.NaN);
                if (!finite(latitude) || latitude < -90d || latitude > 90d
                        || !finite(longitude) || longitude < -180d
                        || longitude > 180d) {
                    continue;
                }
                String id = text(invoke(event, "getEventId"));
                if (id.isEmpty()) {
                    id = "speed-camera:" + Math.round(latitude * 100_000d)
                            + ':' + Math.round(longitude * 100_000d);
                }
                int distanceMeters = nonNegativeInt(
                        number(invoke(camera, "getDistanceToCamera"), -1d));
                float bearing = routeBearingAtEvent(
                        routePoints, event, fallbackBearingDegrees);
                int speedLimitKmh = CameraSpeedNormalizer.fromMapKitMetersPerSecond(
                        number(invoke(event, "getSpeedLimit"), Double.NaN));
                if (speedLimitKmh < 0) {
                    speedLimitKmh = CameraSpeedNormalizer.fromMapKitMetersPerSecond(number(
                            invoke(camera, "getEffectiveSpeedLimit"), Double.NaN));
                }
                ArrayList<String> controlTags = new ArrayList<>();
                for (Object tag : invokeList(event, "getTags")) {
                    String name = enumName(tag);
                    if (!name.isEmpty() && !controlTags.contains(name)) {
                        controlTags.add(name);
                    }
                }
                result.add(new CameraDirectionFrame(id, latitude, longitude,
                        distanceMeters, bearing, inFace, inBack,
                        speedLimitKmh, controlTags));
            } catch (Throwable malformedCamera) {
                // One vendor object can be invalid during route replacement. Keep every other
                // valid camera and, most importantly, keep the navigation stream alive.
            }
        }
        return result;
    }

    private static int positiveRounded(double value) {
        if (!finite(value) || value <= 0d || value > 400d) return -1;
        return Math.max(1, (int) Math.round(value));
    }

    private static float routeBearingAtEvent(List<?> routePoints, Object event,
                                             double fallbackBearingDegrees) {
        try {
            Object position = invoke(event, "getPolylinePosition");
            int segmentIndex = position == null ? -1
                    : ((Number) invoke(position, "getSegmentIndex")).intValue();
            if (segmentIndex >= 0 && routePoints.size() >= 2) {
                int index = Math.max(0, Math.min(routePoints.size() - 2, segmentIndex));
                Object from = routePoints.get(index);
                Object to = routePoints.get(index + 1);
                double fromLatitude = number(invoke(from, "getLatitude"), Double.NaN);
                double fromLongitude = number(invoke(from, "getLongitude"), Double.NaN);
                double toLatitude = number(invoke(to, "getLatitude"), Double.NaN);
                double toLongitude = number(invoke(to, "getLongitude"), Double.NaN);
                float calculated = initialBearing(fromLatitude, fromLongitude,
                        toLatitude, toLongitude);
                if (!Float.isNaN(calculated)) return calculated;
            }
        } catch (Throwable ignored) {
            // The current Guidance bearing is a safe fallback during a route geometry refresh.
        }
        return normalizeBearing((float) fallbackBearingDegrees);
    }

    private static float initialBearing(double fromLatitude, double fromLongitude,
                                        double toLatitude, double toLongitude) {
        if (!finite(fromLatitude) || !finite(fromLongitude)
                || !finite(toLatitude) || !finite(toLongitude)) return Float.NaN;
        double firstLatitude = Math.toRadians(fromLatitude);
        double secondLatitude = Math.toRadians(toLatitude);
        double longitudeDelta = Math.toRadians(toLongitude - fromLongitude);
        double y = Math.sin(longitudeDelta) * Math.cos(secondLatitude);
        double x = Math.cos(firstLatitude) * Math.sin(secondLatitude)
                - Math.sin(firstLatitude) * Math.cos(secondLatitude)
                * Math.cos(longitudeDelta);
        if (Math.abs(x) < 1e-12d && Math.abs(y) < 1e-12d) return Float.NaN;
        return normalizeBearing((float) Math.toDegrees(Math.atan2(y, x)));
    }

    private static float normalizeBearing(float value) {
        if (Float.isNaN(value) || Float.isInfinite(value)) return 0f;
        float result = value % 360f;
        if (result < 0f) result += 360f;
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
            // Progress in the snapshot is expressed in physical metres. Keep the congestion
            // ranges in the same coordinate system so a progress marker cannot drift merely
            // because one part of the polyline contains more geometry points than another.
            double[] cumulativeMeters = cumulativeRouteMeters(points);
            int stride = Math.max(1, (points.size() + MAX_POLYLINE_POINTS - 1)
                    / MAX_POLYLINE_POINTS);
            ArrayList<TrafficSample> samples = new ArrayList<>();
            int previousPoint = 0;
            for (int point = stride; point < points.size(); point += stride) {
                samples.add(sampleTraffic(segments, previousPoint, point,
                        cumulativeMeters[previousPoint], cumulativeMeters[point]));
                previousPoint = point;
            }
            int finalPoint = points.size() - 1;
            if (previousPoint != finalPoint) {
                samples.add(sampleTraffic(segments, previousPoint, finalPoint,
                        cumulativeMeters[previousPoint], cumulativeMeters[finalPoint]));
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
                        .put("from", roundedRouteMeters(samples.get(start).fromMeters))
                        .put("to", roundedRouteMeters(samples.get(index - 1).toMeters))
                        .put("fromMeters", roundedRouteMeters(
                                samples.get(start).fromMeters))
                        .put("toMeters", roundedRouteMeters(
                                samples.get(index - 1).toMeters))
                        .put("type", type);
                if (speedCount > 0) {
                    run.put("speedMps", Math.round(speedTotal / speedCount * 10d) / 10d);
                }
                if (runs.length() >= MAX_TRAFFIC_RUNS - 1 && index < samples.size()) {
                    runs.put(new JSONObject()
                            .put("from", roundedRouteMeters(
                                    samples.get(start).fromMeters))
                            .put("to", roundedRouteMeters(
                                    samples.get(samples.size() - 1).toMeters))
                            .put("fromMeters", roundedRouteMeters(
                                    samples.get(start).fromMeters))
                            .put("toMeters", roundedRouteMeters(
                                    samples.get(samples.size() - 1).toMeters))
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

    private static double[] cumulativeRouteMeters(List<?> points) throws Exception {
        double[] result = new double[points.size()];
        double previousLatitude = number(invoke(points.get(0), "getLatitude"), Double.NaN);
        double previousLongitude = number(invoke(points.get(0), "getLongitude"), Double.NaN);
        for (int index = 1; index < points.size(); index++) {
            Object point = points.get(index);
            double latitude = number(invoke(point, "getLatitude"), Double.NaN);
            double longitude = number(invoke(point, "getLongitude"), Double.NaN);
            result[index] = result[index - 1] + geoDistanceMeters(
                    previousLatitude, previousLongitude, latitude, longitude);
            previousLatitude = latitude;
            previousLongitude = longitude;
        }
        return result;
    }

    private static int roundedRouteMeters(double value) {
        if (!finite(value) || value <= 0d) return 0;
        return (int) Math.min(Integer.MAX_VALUE, Math.round(value));
    }

    private static TrafficSample sampleTraffic(List<?> segments, int from, int to,
                                               double fromMeters, double toMeters)
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
                speedCount == 0 ? Double.NaN : speedTotal / speedCount,
                fromMeters, toMeters);
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
                        runOnMain(() -> scheduleStatePublish(true));
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
        main.removeCallbacks(dispatchState);
        statePublishScheduled = false;
        pendingForceRoute = false;
        detachCameraListener();
        detachGuidanceListeners();
        activeTrafficLights = Collections.emptyList();
        activeTrafficLightsSampleElapsedMs = 0L;
        activeCameraDirections = Collections.emptyList();
        activeCameraDirectionsSampleElapsedMs = 0L;
        activeLaneState = LaneState.EMPTY;
        activeLaneSampleElapsedMs = 0L;
        activeRouteStreetLabels = Collections.emptyList();
        activeRouteStreetLabelsSampleElapsedMs = 0L;
        activeRouteTurns = Collections.emptyList();
        activeRouteTurnsSampleElapsedMs = 0L;
        lastRouteStreetLabelsReadElapsedMs = 0L;
        lastTrafficLightsReadElapsedMs = 0L;
        clearRouteMatchedPosition();
        activeRoutePolylineIndex = null;
        activeRoutePolylineIndexEpoch = Long.MIN_VALUE;
        activityReference = new WeakReference<>(null);
        pendingCamera = null;
        naviKitGuidance = null;
        if (navigation != null) sink.onNavigationRuntime(null);
        navigation = null;
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

    private final Runnable routeReconcile = () -> publishState(true, true, true);

    private final Runnable routeReconcileConfirmation = () -> publishState(true, true, true);

    private final Runnable dispatchState = () -> {
        statePublishScheduled = false;
        boolean forceRoute = pendingForceRoute;
        pendingForceRoute = false;
        publishState(false, forceRoute, forceRoute);
    };

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

    private static final class SnapshotInputs {
        final NavigationFrame frame;
        final Object routePosition;

        SnapshotInputs(NavigationFrame frame, Object routePosition) {
            this.frame = frame;
            this.routePosition = routePosition;
        }
    }

    private static final class RouteProgressSample {
        static final RouteProgressSample INVALID = new RouteProgressSample(
                false, 0, 0d, null);
        final boolean valid;
        final int segmentIndex;
        final double segmentPosition;
        final Object currentPoint;

        RouteProgressSample(boolean valid, int segmentIndex,
                            double segmentPosition, Object currentPoint) {
            this.valid = valid;
            this.segmentIndex = Math.max(0, segmentIndex);
            this.segmentPosition = Math.max(0d, Math.min(1d, segmentPosition));
            this.currentPoint = currentPoint;
        }
    }

    private static final class TrafficSample {
        final String type;
        final double speedMps;
        final double fromMeters;
        final double toMeters;

        TrafficSample(String type, double speedMps,
                      double fromMeters, double toMeters) {
            this.type = type;
            this.speedMps = speedMps;
            this.fromMeters = Math.max(0d, fromMeters);
            this.toMeters = Math.max(this.fromMeters, toMeters);
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
        static final LaneState EMPTY = new LaneState(new JSONArray(), -1, null);
        final JSONArray values;
        final int distanceMeters;
        final LaneGuidanceFrame mapFrame;

        LaneState(JSONArray values, int distanceMeters, LaneGuidanceFrame mapFrame) {
            this.values = values;
            this.distanceMeters = distanceMeters;
            this.mapFrame = mapFrame;
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
        Method method = ReflectMethods.publicMethod(target.getClass(), name, parameterTypes);
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
