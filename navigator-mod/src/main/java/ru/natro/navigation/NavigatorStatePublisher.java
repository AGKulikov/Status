/* SPDX-License-Identifier: GPL-3.0-or-later */
package ru.natro.navigation;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.ImageView;
import android.widget.TextView;

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
    /** Navigator 30.3.0 does not select its traffic-jam StatusPanel below four minutes. */
    private static final long STOCK_TRAFFIC_JAM_MIN_DURATION_MS = 240_000L;
    interface Sink {
        void onPrimaryMap(Object mapWindow, Object map);

        void onPrimaryCamera(CameraState state);

        /** The exact automotive Navigation session used by Navigator 30.3.0. */
        void onNavigationRuntime(Object navigation);

        void onNavigationState(String snapshotJson, String routeJson, Object drivingRoute,
                               long routeEpoch, long jamFingerprint,
                               RoutePolylineStyler.JamStyle jamStyle,
                               NavigationFrame navigationFrame);

        /** Exact visible stock artwork; emitted only when its keyed maneuver changes. */
        void onManeuverArtwork(long sequence, String maneuverIdentity, Bitmap artwork);

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
                    routeTurns, routeTurnsSampleElapsedMs);
        }

        NavigationFrame withRouteTurns(List<RouteTurnFrame> turns, long sampledAt) {
            return new NavigationFrame(latitude, longitude, bearingDegrees, speedKmh,
                    routeActive, routeProgressValid, routeSegmentIndex,
                    routeSegmentPosition, currentRoutePoint,
                    trafficLights, trafficLightsSampleElapsedMs,
                    cameraDirections, cameraDirectionsSampleElapsedMs,
                    laneGuidance, laneGuidanceSampleElapsedMs,
                    turns, sampledAt);
        }

        boolean isValid() {
            return finite(latitude) && finite(longitude)
                    && latitude >= -90d && latitude <= 90d
                    && longitude >= -180d && longitude <= 180d;
        }
    }

    /** One upcoming Windshield manoeuvre anchored directly to the active route. */
    static final class RouteTurnFrame {
        final String id;
        final String action;
        final double latitude;
        final double longitude;
        final float bearingDegrees;
        final int routeSegmentIndex;
        final double routeSegmentPosition;

        RouteTurnFrame(String id, String action, double latitude, double longitude,
                       float bearingDegrees, int routeSegmentIndex,
                       double routeSegmentPosition) {
            this.id = id == null ? "" : id;
            this.action = action == null ? "" : action;
            this.latitude = latitude;
            this.longitude = longitude;
            this.bearingDegrees = normalizeBearing(bearingDegrees);
            this.routeSegmentIndex = routeSegmentIndex;
            this.routeSegmentPosition = routeSegmentPosition;
        }

        boolean hasContent() {
            return !id.isEmpty() && !action.isEmpty()
                    && finite(latitude) && latitude >= -90d && latitude <= 90d
                    && finite(longitude) && longitude >= -180d && longitude <= 180d
                    && routeSegmentIndex >= 0 && finite(routeSegmentPosition)
                    && routeSegmentPosition >= 0d && routeSegmentPosition <= 1d;
        }
    }

    /** One validated Windshield signal shared by JSON/HUD and both map renderers. */
    static final class TrafficLightFrame {
        final String id;
        final double latitude;
        final double longitude;
        final int routeSegmentIndex;
        final double routeSegmentPosition;
        final int distanceMeters;
        final int secondsLeft;
        final String signal;
        final String sectionType;
        final String arrow;

        TrafficLightFrame(String id, double latitude, double longitude,
                          int routeSegmentIndex, double routeSegmentPosition,
                          int distanceMeters, int secondsLeft, String signal,
                          String sectionType, String arrow) {
            this.id = id == null ? "" : id;
            this.latitude = latitude;
            this.longitude = longitude;
            this.routeSegmentIndex = routeSegmentIndex;
            this.routeSegmentPosition = routeSegmentPosition;
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
        final int routeSegmentIndex;
        final double routeSegmentPosition;
        /** Original MapKit object retained in-process for the stock Yandex renderer. */
        final Object laneSign;
        final List<LaneFrame> lanes;

        LaneGuidanceFrame(String id, double latitude, double longitude,
                          int distanceMeters, float bearingDegrees,
                          int routeSegmentIndex, double routeSegmentPosition,
                          Object laneSign, List<LaneFrame> lanes) {
            this.id = id == null ? "" : id;
            this.latitude = latitude;
            this.longitude = longitude;
            this.distanceMeters = Math.max(-1, distanceMeters);
            this.bearingDegrees = normalizeBearing(bearingDegrees);
            this.routeSegmentIndex = routeSegmentIndex;
            this.routeSegmentPosition = routeSegmentPosition;
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
    /** Upcoming manoeuvres change slowly; one verified route scan per second is sufficient. */
    private static final long ROUTE_TURN_INTERVAL_MS = 1_000L;
    /** MapKit defaults vary by host; request enough upcoming lights for both independent maps. */
    private static final int MAX_UPCOMING_TRAFFIC_LIGHTS = 16;
    /** Windshield returns only cameras which are active for the current route direction. */
    private static final int MAX_ACTIVE_SPEED_CAMERAS = 8;
    private static final int MAX_MAP_LANES = 8;
    private static final int MAX_ROUTE_TURNS = 10;
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
    private List<RouteTurnFrame> activeRouteTurns = Collections.emptyList();
    private long activeRouteTurnsSampleElapsedMs;
    private long lastRouteTurnsReadElapsedMs;
    private long lastTrafficLightsReadElapsedMs;
    private final NavigationPositionPolicy navigationPositionPolicy = new NavigationPositionPolicy();
    private String lastPositionDiagnostic = "";
    private Object activeRoutePolylineIndex;
    private long activeRoutePolylineIndexEpoch = Long.MIN_VALUE;
    private Object conditionsRoute;
    private Object conditionsListener;
    private long resolveRetryMs = MIN_RESOLVE_RETRY_MS;
    private String lastPrimaryMapFailure = "";
    private String lastGuidanceFailure = "";
    private String lastRouteDiagnostic = "";
    private boolean forceManeuverArtwork;
    private long builtManeuverArtworkSequence;
    private String builtManeuverArtworkIdentity = "";
    private Bitmap builtManeuverArtwork;
    private int builtManeuverArtworkSignature;
    private String lastSentManeuverArtworkIdentity = "";
    private int lastSentManeuverArtworkSignature;
    private final StockManeuverArtwork maneuverArtwork = new StockManeuverArtwork();

    private final Runnable dispatchManeuverCommands = () -> publishState(false, false, true);

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
        runOnMain(() -> {
            forceManeuverArtwork = true;
            publishState(false, false, true);
        });
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
        StockManeuverCommands.listen(() -> {
            main.removeCallbacks(dispatchManeuverCommands);
            main.post(dispatchManeuverCommands);
        });
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
            SnapshotInputs inputs = readSnapshotInputs(currentGuidance, activeRoute, routeStatus);
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
                    activeRouteTurns = Collections.emptyList();
                    activeRouteTurnsSampleElapsedMs = 0L;
                    lastRouteTurnsReadElapsedMs = elapsedNow;
                } else {
                    activeLaneState = readLanes(inputs.routePosition, activeRoute,
                            inputs.frame.bearingDegrees);
                    activeLaneSampleElapsedMs = elapsedNow;
                    boolean routeTurnsDue = routeChanged || forceRoute
                            || elapsedNow - lastRouteTurnsReadElapsedMs
                            >= ROUTE_TURN_INTERVAL_MS;
                    if (routeTurnsDue) {
                        activeRouteTurns = Collections.unmodifiableList(
                                readRouteTurns(inputs.routePosition,
                                        inputs.frame.bearingDegrees));
                        activeRouteTurnsSampleElapsedMs = elapsedNow;
                        lastRouteTurnsReadElapsedMs = elapsedNow;
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
                            readTrafficLights(inputs.routePosition, activeRoute));
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
                    .withRouteTurns(activeRouteTurns, activeRouteTurnsSampleElapsedMs);
            String snapshot = snapshotDue
                    ? buildSnapshot(currentGuidance, currentNaviKitGuidance,
                            activeRoute, inputs,
                            activeTrafficLights, activeLaneState).toString() : null;
            if (snapshotDue) lastSnapshotDispatchElapsedMs = elapsedNow;
            String route = routeChanged || forceRoute ? buildRoutePayload().toString() : null;
            sink.onNavigationState(snapshot, route, activeRoute, routeEpoch,
                    activeJamFingerprint, activeJamStyle, navigationFrame);
            if (snapshotDue) dispatchManeuverArtwork();
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
            activeRouteTurns = Collections.emptyList();
            activeRouteTurnsSampleElapsedMs = 0L;
            lastRouteTurnsReadElapsedMs = 0L;
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
            // Require a fresh native maneuver command after the route transition.
            StockManeuverCommands.reset(routeEpoch);
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
    private SnapshotInputs readSnapshotInputs(Object currentGuidance, Object route, String routeStatus)
            throws Exception {
        Object location = invoke(currentGuidance, "getLocation");
        Object routePosition = null;
        if (route != null) {
            try {
                routePosition = invoke(route, "getRoutePosition");
            } catch (Throwable unavailable) {
                // A route wrapper transition must not discard an available live location.
            }
        }
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
        // A retained RoutePosition.getPoint() is a progress point, not proof that the vehicle is
        // still on this route. Only confirmed on-route statuses allow matching (and its gap cache).
        // Otherwise both maps and their cameras receive Guidance's live location and live heading.
        // Keep route trimming independent: an off-route vehicle must not drag the old polyline.
        double routeLatitude = Double.NaN;
        double routeLongitude = Double.NaN;
        double routeBearing = Double.NaN;
        if (NavigationPositionPolicy.mayUseRoutePosition(route != null, routeStatus)
                && routePosition != null) {
            try {
                Object matchedPoint = invoke(routePosition, "getPoint");
                routeLatitude = number(invoke(matchedPoint, "getLatitude"), Double.NaN);
                routeLongitude = number(invoke(matchedPoint, "getLongitude"), Double.NaN);
            } catch (Throwable unavailable) {
                // A short matched-point gap may be bridged only while still confirmed on route.
            }
            try {
                routeBearing = number(invoke(routePosition, "heading"), Double.NaN);
            } catch (Throwable unavailable) {
                // Heading availability is independent of matched-point availability.
            }
        }
        NavigationPositionPolicy.Position position = navigationPositionPolicy.select(
                route != null, routeStatus, SystemClock.elapsedRealtime(),
                latitude, longitude, bearing, routeLatitude, routeLongitude, routeBearing);
        String positionDiagnostic = position.source + ", status=" + routeStatus;
        if (!positionDiagnostic.equals(lastPositionDiagnostic)) {
            lastPositionDiagnostic = positionDiagnostic;
            sink.onDiagnostic("Guidance vehicle position source=" + positionDiagnostic);
        }
        NavigationFrame frame = new NavigationFrame(
                position.latitude, position.longitude, position.heading, speedKmh, route != null,
                progress.valid, progress.segmentIndex, progress.segmentPosition,
                progress.currentPoint);
        return new SnapshotInputs(frame, routePosition);
    }

    private void clearRouteMatchedPosition() {
        navigationPositionPolicy.reset();
        lastPositionDiagnostic = "";
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
            // the reversible route progress point. Off-route cursor motion is independent.
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

    /** Exact active-route segment for a Windshield balloon; geographic nearest is only fallback. */
    private RouteProgressSample readEventRouteProgress(Object route, Object eventPosition) {
        if (route == null || eventPosition == null) return RouteProgressSample.INVALID;
        try {
            Object polylinePosition = null;
            try {
                String routeId = String.valueOf(invoke(route, "getRouteId"));
                polylinePosition = invoke(eventPosition, "positionOnRoute",
                        new Class<?>[]{String.class}, routeId);
            } catch (Throwable unavailable) {
                // Route wrappers can briefly change while the Windshield list is still valid.
            }
            Object point = null;
            try {
                point = invoke(eventPosition, "getPoint");
            } catch (Throwable unavailable) {
                // Exact positionOnRoute above is sufficient when the point wrapper is unavailable.
            }
            if (polylinePosition == null && point != null) {
                polylinePosition = closestPositionOnRoute(route, point);
            }
            if (polylinePosition == null) return RouteProgressSample.INVALID;
            int segmentIndex = ((Number) invoke(
                    polylinePosition, "getSegmentIndex")).intValue();
            double segmentPosition = ((Number) invoke(
                    polylinePosition, "getSegmentPosition")).doubleValue();
            return new RouteProgressSample(true, segmentIndex, segmentPosition, point);
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

    private JSONObject buildSnapshot(Object currentGuidance, Object currentNaviKitGuidance,
                                     Object route,
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
        TrafficJamForecast trafficJam = readTrafficJamForecast(
                currentNaviKitGuidance, routeActive);

        Manoeuvre manoeuvre = routeActive && routePosition != null
                ? readManoeuvre(routePosition) : Manoeuvre.EMPTY;
        JSONArray trafficLightJson = new JSONArray();
        if (routeActive) {
            for (TrafficLightFrame light : trafficLights) trafficLightJson.put(light.toJson());
        }
        Object speedLimitValue = invoke(currentGuidance, "getSpeedLimit");
        int speedLimit = speedLimitValue == null ? 0 : nonNegativeInt(
                number(invoke(speedLimitValue, "getValue"), 0d) * 3.6d);

        long snapshotSequence = ++sequence;
        builtManeuverArtworkSequence = snapshotSequence;
        builtManeuverArtworkIdentity = manoeuvre.identity;
        builtManeuverArtwork = manoeuvre.artwork;
        builtManeuverArtworkSignature = manoeuvre.artworkSignature;
        JSONObject result = new JSONObject()
                .put("schema", 1)
                .put("sequence", snapshotSequence)
                .put("routeEpoch", routeEpoch)
                .put("sourceTimestampMs", now)
                .put("routeActive", routeActive)
                .put("maneuverIdentity", manoeuvre.identity)
                .put("maneuverType", manoeuvre.type)
                .put("maneuverTitle", manoeuvre.title)
                .put("maneuverSubtext", manoeuvre.subtext)
                .put("maneuverNextRoad", manoeuvre.nextRoad)
                .put("maneuverDirectionSignsJson", manoeuvre.directionSigns.toString())
                .put("maneuverAuxiliaryType", manoeuvre.auxiliary.type)
                .put("maneuverAuxiliaryText", manoeuvre.auxiliary.text)
                .put("maneuverAuxiliaryManeuverType", manoeuvre.auxiliary.maneuverType)
                .put("maneuverAuxiliaryDistanceMeters",
                        manoeuvre.auxiliary.distanceMeters)
                .put("maneuverDisplayDistance", manoeuvre.displayDistance)
                .put("maneuverCardJson", StockManeuverCommands.snapshot(
                        activityReference.get(), routeEpoch, routeActive))
                .put("street", text(invoke(currentGuidance, "getRoadName")))
                .put("destination", routeActive ? destinationForRoute(route) : "")
                .put("maneuverDistanceMeters", manoeuvre.distanceMeters)
                .put("routeTotalDistanceMeters", routeTotalDistance)
                .put("remainingDistanceMeters", remainingDistance)
                .put("remainingDurationSeconds", remainingDuration)
                .put("trafficJamDurationSeconds", trafficJam.durationSeconds)
                .put("trafficJamDistanceMeters", trafficJam.distanceMeters)
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

    private void dispatchManeuverArtwork() {
        boolean forced = forceManeuverArtwork;
        forceManeuverArtwork = false;
        Bitmap artwork = builtManeuverArtwork;
        String identity = builtManeuverArtworkIdentity;
        if (artwork == null || artwork.isRecycled() || identity.isEmpty()) {
            if (identity.isEmpty()) {
                lastSentManeuverArtworkIdentity = "";
                lastSentManeuverArtworkSignature = 0;
            }
            return;
        }
        if (!forced && identity.equals(lastSentManeuverArtworkIdentity)
                && builtManeuverArtworkSignature == lastSentManeuverArtworkSignature) return;
        sink.onManeuverArtwork(builtManeuverArtworkSequence, identity, artwork);
        lastSentManeuverArtworkIdentity = identity;
        lastSentManeuverArtworkSignature = builtManeuverArtworkSignature;
    }

    /**
     * Reads the same nullable value that owns Navigator's "Пробка на …" card. Failure of this
     * optional API must hide only that module and must never detach the complete Guidance stream.
     */
    private TrafficJamForecast readTrafficJamForecast(
            Object currentNaviKitGuidance, boolean routeActive) {
        if (!routeActive || currentNaviKitGuidance == null) return TrafficJamForecast.EMPTY;
        try {
            Object forecast = invoke(currentNaviKitGuidance, "leftInTrafficJam");
            if (forecast == null) return TrafficJamForecast.EMPTY;
            double durationMillis = number(invoke(forecast, "getDuration"), -1d);
            // leftInTrafficJam() is a forecast source, not the StatusPanel visibility contract.
            // Navigator keeps returning a residual forecast after its own jam card has already
            // disappeared. Fail closed unless the exact stock StatusPanel currently owns visible
            // jam text; this also rejects priority messages which replace that text in-place.
            if (!finite(durationMillis)
                    || durationMillis < STOCK_TRAFFIC_JAM_MIN_DURATION_MS
                    || !isStockTrafficJamPanelVisible()) {
                return TrafficJamForecast.EMPTY;
            }
            int durationSeconds = !finite(durationMillis) || durationMillis < 0d ? -1
                    : (int) Math.min(Integer.MAX_VALUE,
                    Math.round(durationMillis) / 1_000L);
            int distanceMeters = nonNegativeInt(number(invoke(forecast, "getMeters"), -1d));
            return durationSeconds < 0 || distanceMeters < 0
                    ? TrafficJamForecast.EMPTY
                    : new TrafficJamForecast(durationSeconds, distanceMeters);
        } catch (Throwable unavailable) {
            return TrafficJamForecast.EMPTY;
        }
    }

    /** Structural visibility survives a background MapWindow while still following its presenter. */
    private boolean isStockTrafficJamPanelVisible() {
        Activity activity = activityReference.get();
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return false;
        try {
            int panelId = activity.getResources().getIdentifier(
                    "text_statuspanel", "id", activity.getPackageName());
            int textId = activity.getResources().getIdentifier(
                    "status_panel_text", "id", activity.getPackageName());
            if (panelId == 0 || textId == 0) return false;
            View panel = visibleViewById(activity, panelId);
            View textView = panel == null ? null : panel.findViewById(textId);
            if (!(textView instanceof TextView)
                    || !visibleThroughParents(panel)
                    || !visibleThroughParents(textView)) return false;
            String value = String.valueOf(((TextView) textView).getText())
                    .trim().toLowerCase(Locale.ROOT);
            // Russian is the target locale; the English forms keep the adapter fail-safe for a
            // temporarily changed system language without accepting arbitrary status messages.
            return value.contains("пробк") || value.contains("traffic jam")
                    || value.startsWith("jam ") || value.equals("jam");
        } catch (Throwable unavailable) {
            return false;
        }
    }

    private static boolean visibleThroughParents(View value) {
        // isShown() additionally rejects a stopped/hidden Navigator window. Plain VISIBLE flags
        // can stay set in its detached hierarchy and previously kept the HUD jam card alive.
        if (value == null || !value.isShown()) return false;
        View current = value;
        for (int depth = 0; depth < 64; depth++) {
            if (current.getVisibility() != View.VISIBLE || current.getAlpha() <= .01f) {
                return false;
            }
            ViewParent parent = current.getParent();
            if (!(parent instanceof View)) return parent != null;
            current = (View) parent;
        }
        return false;
    }

    private String destinationForRoute(Object route) {
        if (cachedDestinationEpoch == routeEpoch) return cachedDestination;
        cachedDestination = readDestination(route);
        cachedDestinationEpoch = routeEpoch;
        return cachedDestination;
    }

    private Manoeuvre readManoeuvre(Object routePosition) throws Exception {
        List<?> upcomingManoeuvres = invokeList(windshield, "getManoeuvres");
        Object upcoming = nearest(upcomingManoeuvres, routePosition, "getPosition");
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
        String identity = manoeuvreIdentity(type, position);
        // Native commands own the card. Windshield semantics remain available for other modules.
        StockManeuverCard stockCard = StockManeuverCard.EMPTY;
        return new Manoeuvre(identity, type, title, subtext,
                stockCard.nextRoad, stockCard.directionSigns, stockCard.auxiliary, distance,
                stockCard.displayDistance, stockCard.artwork, stockCard.artworkSignature);
    }

    /**
     * Reads the public content of the exact stock ContextManeuverView currently on screen.
     * Unlike a proximity guess, these values have already passed Navigator's own presenter rules,
     * including whether a road sign or the attached auxiliary row is actually selected.
     */
    private StockManeuverCard readStockManeuverCard(int expectedDistanceMeters,
                                                    String maneuverIdentity) {
        Activity activity = activityReference.get();
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            return StockManeuverCard.EMPTY;
        }
        try {
            View card = viewByName(activity, "contextmaneuverview");
            if (!visibleThroughParents(card)) return StockManeuverCard.EMPTY;
            String nextRoad = visibleText(card, activity, "text_nextstreet");
            JSONArray signs = new JSONArray();
            View signView = viewByName(card, activity, "roadsign_container");
            if (visibleThroughParents(signView)) {
                Object raw = invoke(signView, "getItems");
                if (raw instanceof List<?>) encodeDirectionSignItems((List<?>) raw, signs);
            }

            ManeuverAuxiliary auxiliary = ManeuverAuxiliary.EMPTY;
            View underBalloon = viewByName(card, activity, "under_balloon");
            if (visibleThroughParents(underBalloon)) {
                String exitNumber = visibleText(card, activity, "exit_number_text");
                if (!exitNumber.isEmpty()) {
                    auxiliary = new ManeuverAuxiliary(
                            "EXIT_NUMBER", exitNumber, "", -1);
                } else {
                    View nextGroup = viewByName(card, activity,
                            "under_balloon_next_maneuver_group");
                    if (visibleThroughParents(nextGroup)) {
                        String value = visibleText(card, activity,
                                "next_maneuver_distance_value");
                        String unit = visibleText(card, activity,
                                "next_maneuver_distance_unit");
                        String label = (value + (value.isEmpty() || unit.isEmpty() ? "" : " ")
                                + unit).trim();
                        if (!label.isEmpty()) {
                            auxiliary = new ManeuverAuxiliary(
                                    "NEXT_MANEUVER", label, "", -1);
                        }
                    }
                }
            }
            String distanceValue = visibleText(card, activity,
                    "text_maneuverballoon_distance");
            String distanceUnit = visibleText(card, activity,
                    "text_maneuverballoon_metrics");
            String displayDistance = joinDistanceLabel(distanceValue, distanceUnit);
            Bitmap artwork = null;
            int artworkSignature = 0;
            View image = viewByName(card, activity, "image_maneuverballoon_maneuver");
            if (image instanceof ImageView && visibleThroughParents(image)
                    && distanceMatches(displayDistance, expectedDistanceMeters)) {
                artwork = maneuverArtwork.capture((ImageView) image);
                artworkSignature = maneuverArtwork.revision();
            }
            if (nextRoad.isEmpty() && signs.length() == 0
                    && auxiliary == ManeuverAuxiliary.EMPTY && artwork == null) {
                return StockManeuverCard.EMPTY;
            }
            return new StockManeuverCard(nextRoad, signs, auxiliary,
                    artwork == null ? "" : displayDistance, artwork, artworkSignature);
        } catch (Throwable unavailable) {
            return StockManeuverCard.EMPTY;
        }
    }

    private static String joinDistanceLabel(String value, String unit) {
        String left = value == null ? "" : value.trim();
        String right = unit == null ? "" : unit.trim();
        if (left.isEmpty() || right.isEmpty()) return (left + right).trim();
        return left + ' ' + right;
    }

    private static boolean distanceMatches(String label, int expectedMeters) {
        if (label == null || label.isEmpty() || expectedMeters < 0) return false;
        String normalized = label.replace('\u00a0', ' ').trim().toLowerCase(Locale.ROOT);
        StringBuilder number = new StringBuilder();
        for (int index = 0; index < normalized.length(); index++) {
            char value = normalized.charAt(index);
            if ((value >= '0' && value <= '9') || value == '.' || value == ',') {
                number.append(value == ',' ? '.' : value);
            }
        }
        if (number.length() == 0) return false;
        try {
            double displayed = Double.parseDouble(number.toString());
            if (normalized.contains("км") || normalized.contains("km")) displayed *= 1_000d;
            double tolerance = Math.max(60d, expectedMeters * .2d);
            return Math.abs(displayed - expectedMeters) <= tolerance;
        } catch (NumberFormatException invalid) {
            return false;
        }
    }

    private static View viewByName(Activity activity, String name) {
        int id = activity.getResources().getIdentifier(
                name, "id", activity.getPackageName());
        return id == 0 ? null : visibleViewById(activity, id);
    }

    private static View viewByName(View root, Activity activity, String name) {
        int id = activity.getResources().getIdentifier(name, "id", activity.getPackageName());
        if (id == 0 || root == null) return null;
        View result = root.findViewById(id);
        return visibleThroughParents(result) ? result : null;
    }

    /** Chooses the actually displayed instance when portrait/landscape balloons share an id. */
    private static View visibleViewById(Activity activity, int id) {
        if (activity.getWindow() == null) return null;
        return visibleDescendant(activity.getWindow().getDecorView(), id);
    }

    private static View visibleDescendant(View value, int id) {
        if (value == null || !value.isShown()
                || value.getVisibility() != View.VISIBLE || value.getAlpha() <= .01f) {
            return null;
        }
        if (value.getId() == id && visibleThroughParents(value)) return value;
        if (!(value instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) value;
        for (int index = 0; index < group.getChildCount(); index++) {
            View match = visibleDescendant(group.getChildAt(index), id);
            if (match != null) return match;
        }
        return null;
    }

    private static String visibleText(Activity activity, String name) {
        View value = viewByName(activity, name);
        return value instanceof TextView && visibleThroughParents(value)
                ? String.valueOf(((TextView) value).getText()).trim() : "";
    }

    private static String visibleText(View root, Activity activity, String name) {
        View value = viewByName(root, activity, name);
        return value instanceof TextView && visibleThroughParents(value)
                ? String.valueOf(((TextView) value).getText()).trim() : "";
    }

    private static void encodeDirectionSignItems(List<?> values, JSONArray target)
            throws JSONException {
        int emitted = 0;
        for (Object item : values) {
            if (emitted >= 8) break;
            JSONObject encoded = encodeDirectionSignItem(item);
            if (encoded == null) continue;
            target.put(encoded);
            emitted++;
        }
    }

    /** One stable key owns action, text and every optional card region for the whole frame. */
    private String manoeuvreIdentity(String type, Object position) {
        RouteProgressSample progress = readEventRouteProgress(activeRoute, position);
        if (progress.valid) {
            return "maneuver:" + routeEpoch + ':' + progress.segmentIndex + ':'
                    + Math.round(progress.segmentPosition * 1_000_000d) + ':' + type;
        }
        try {
            Object point = position == null ? null : invoke(position, "getPoint");
            if (point != null) {
                double latitude = number(invoke(point, "getLatitude"), Double.NaN);
                double longitude = number(invoke(point, "getLongitude"), Double.NaN);
                if (finite(latitude) && finite(longitude)) {
                    return "maneuver:" + routeEpoch + ':'
                            + Math.round(latitude * 100_000d) + ':'
                            + Math.round(longitude * 100_000d) + ':' + type;
                }
            }
        } catch (Throwable ignored) {}
        return "maneuver:" + routeEpoch + ':' + type;
    }

    /** Mirrors DirectionSignItem order and its native colors instead of flattening it into road. */
    private JSONArray readDirectionSignItems(Object manoeuvrePosition) {
        JSONArray result = new JSONArray();
        try {
            Object best = null;
            double bestDistance = Double.MAX_VALUE;
            for (Object upcoming : invokeList(windshield, "getDirectionSigns")) {
                Object position = invoke(upcoming, "getPosition");
                double candidate = Math.abs(distance(manoeuvrePosition, position));
                if (finite(candidate) && candidate <= 300d && candidate < bestDistance) {
                    best = upcoming;
                    bestDistance = candidate;
                }
            }
            Object sign = best == null ? null : invoke(best, "getDirectionSign");
            if (sign == null) return result;
            encodeDirectionSignItems(invokeList(sign, "getItems"), result);
        } catch (Throwable unavailable) {
            // Direction signs are optional; the primary maneuver remains valid without them.
        }
        return result;
    }

    private static JSONObject encodeDirectionSignItem(Object item) throws JSONException {
        if (item == null) return null;
        Object value = tryInvoke(item, "getRoad");
        String kind = "ROAD";
        String label = value == null ? "" : text(tryInvoke(value, "getName"));
        if (value == null) {
            value = tryInvoke(item, "getToponym");
            kind = "TOPONYM";
            label = value == null ? "" : text(tryInvoke(value, "getText"));
        }
        if (value == null) {
            value = tryInvoke(item, "getExit");
            kind = "EXIT";
            label = value == null ? "" : text(tryInvoke(value, "getName"));
        }
        if (value == null) {
            value = tryInvoke(item, "getIcon");
            kind = "ICON";
            label = value == null ? "" : enumName(tryInvoke(value, "getImage"));
        }
        if (value == null || label.isEmpty()) return null;
        Object style = tryInvoke(value, "getStyle");
        return new JSONObject()
                .put("kind", kind)
                .put("text", label)
                .put("bgColor", colorString(tryInvoke(style, "getBgColor"), "#FF1478FF"))
                .put("textColor", colorString(
                        tryInvoke(style, "getTextColor"), "#FFFFFFFF"));
    }

    private ManeuverAuxiliary readManeuverAuxiliary(
            Object annotation, Object routePosition, List<?> upcomingManoeuvres, Object current) {
        try {
            Object metadata = tryInvoke(annotation, "getActionMetadata");
            Object roundabout = tryInvoke(metadata, "getLeaveRoundaboutMetadata");
            int number = positiveInt(tryInvoke(roundabout, "getExitNumber"));
            if (number > 0) {
                return new ManeuverAuxiliary("EXIT_NUMBER",
                        russianOrdinal(number, "съезд"), "", -1);
            }
            Object exit = tryInvoke(metadata, "getExitMetadata");
            number = positiveInt(tryInvoke(exit, "getSequentialNumber"));
            if (number > 0) {
                return new ManeuverAuxiliary("EXIT_NUMBER",
                        russianOrdinal(number, "съезд"), "", -1);
            }
            Object turn = tryInvoke(metadata, "getTurnMetadata");
            number = positiveInt(tryInvoke(turn, "getTurnNumber"));
            if (number > 0) {
                return new ManeuverAuxiliary("TURN_NUMBER",
                        russianOrdinal(number, "поворот"), "", -1);
            }
            if (!Boolean.TRUE.equals(tryInvoke(annotation, "getInSeriesWithNext"))) {
                return ManeuverAuxiliary.EMPTY;
            }
            Object next = nextManoeuvre(upcomingManoeuvres, routePosition, current);
            if (next == null) return ManeuverAuxiliary.EMPTY;
            Object nextPosition = invoke(next, "getPosition");
            Object nextAnnotation = invoke(next, "getAnnotation");
            if (nextAnnotation == null) return ManeuverAuxiliary.EMPTY;
            String nextType = enumName(invoke(nextAnnotation, "getAction"));
            String description = text(invoke(nextAnnotation, "getDescriptionText"));
            String toponym = text(invoke(nextAnnotation, "getToponym"));
            String label = description.isEmpty() ? toponym : description;
            if (label.isEmpty()) label = "Следующий манёвр";
            return new ManeuverAuxiliary("NEXT_MANEUVER", label, nextType,
                    nonNegativeInt(distance(routePosition, nextPosition)));
        } catch (Throwable unavailable) {
            return ManeuverAuxiliary.EMPTY;
        }
    }

    private static Object nextManoeuvre(List<?> values, Object routePosition, Object current)
            throws Exception {
        Object result = null;
        double resultDistance = Double.MAX_VALUE;
        double currentDistance = distance(routePosition, invoke(current, "getPosition"));
        for (Object value : values) {
            if (value == current) continue;
            double candidate = distance(routePosition, invoke(value, "getPosition"));
            if (finite(candidate) && candidate >= Math.max(0d, currentDistance + 1d)
                    && candidate < resultDistance) {
                result = value;
                resultDistance = candidate;
            }
        }
        return result;
    }

    private static int positiveInt(Object value) {
        return value instanceof Number ? Math.max(0, ((Number) value).intValue()) : 0;
    }

    private static String russianOrdinal(int number, String noun) {
        return number + "-й " + noun;
    }

    private static String colorString(Object value, String fallback) {
        return value instanceof Number
                ? String.format(Locale.ROOT, "#%08X", ((Number) value).intValue()) : fallback;
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
                int routeSegmentIndex = -1;
                double routeSegmentPosition = Double.NaN;
                try {
                    Object route = activeRoute;
                    String routeId = route == null ? ""
                            : String.valueOf(invoke(route, "getRouteId"));
                    Object polylinePosition = routeId.isEmpty() || position == null ? null
                            : invoke(position, "positionOnRoute",
                            new Class<?>[]{String.class}, routeId);
                    if (polylinePosition != null) {
                        routeSegmentIndex = ((Number) invoke(
                                polylinePosition, "getSegmentIndex")).intValue();
                        routeSegmentPosition = ((Number) invoke(
                                polylinePosition, "getSegmentPosition")).doubleValue();
                    }
                } catch (Throwable ignored) {}
                String key = action + ':' + Math.round(latitude * 100_000d)
                        + ':' + Math.round(longitude * 100_000d);
                if (!seen.add(key)) continue;
                RouteTurnFrame frame = new RouteTurnFrame(
                        "route-turn:" + routeEpoch + ':' + key,
                        action, latitude, longitude, bearing,
                        routeSegmentIndex, routeSegmentPosition);
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

    private LaneState readLanes(Object routePosition, Object route,
                                double fallbackBearingDegrees)
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
        RouteProgressSample eventProgress = readEventRouteProgress(route, position);
        LaneGuidanceFrame frame = new LaneGuidanceFrame(id, latitude, longitude,
                distanceMeters, bearing,
                eventProgress.valid ? eventProgress.segmentIndex : -1,
                eventProgress.valid ? eventProgress.segmentPosition : Double.NaN,
                sign, Collections.unmodifiableList(mapLanes));
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

    private List<TrafficLightFrame> readTrafficLights(Object routePosition, Object route)
            throws Exception {
        ArrayList<TrafficLightFrame> result = new ArrayList<>();
        int sourceIndex = 0;
        for (Object light : invokeList(windshield, "getTrafficLightsWithSignal")) {
            if (result.size() >= MAX_UPCOMING_TRAFFIC_LIGHTS) break;
            try {
                Object position = invoke(light, "getPosition");
                double rawDistance = routePosition == null
                        ? Double.NaN : distance(routePosition, position);
                if (finite(rawDistance) && rawDistance < -5d) continue;
                int distanceMeters = finite(rawDistance)
                        ? nonNegativeInt(rawDistance) : -1;
                int secondsLeft = nullableInt(invoke(light, "getSecondsLeft"));
                String signal = enumName(invoke(light, "getSignal"));
                // UNKNOWN/empty Windshield shells are not navigation data. Keeping them was the
                // source of the grey traffic-light placeholder with blank values after a route.
                if (!validTrafficSignal(signal)) continue;
                String sectionType = enumName(invoke(light, "getSectionType"));
                String arrow = enumName(invoke(light, "getArrow"));
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
                RouteProgressSample eventProgress = readEventRouteProgress(route, position);
                String sourceId = text(invoke(light, "getId"));
                String id = trafficLightIdentity(sourceId, sourceIndex,
                        latitude, longitude, eventProgress, sectionType, arrow);
                result.add(new TrafficLightFrame(id, latitude, longitude,
                        eventProgress.valid ? eventProgress.segmentIndex : -1,
                        eventProgress.valid ? eventProgress.segmentPosition : Double.NaN,
                        distanceMeters, secondsLeft, signal, sectionType, arrow));
            } catch (Throwable invalidLight) {
                // One malformed native wrapper must not erase the other valid lights in this
                // Windshield snapshot or detach the otherwise healthy Guidance session.
                Log.d(TAG, "Skipping one invalid traffic-light wrapper: "
                        + invalidLight.getClass().getSimpleName());
            } finally {
                sourceIndex++;
            }
        }
        return result;
    }

    /** Stable per-route key; neighbouring lights are never deduplicated by coordinates. */
    private String trafficLightIdentity(String sourceId, int sourceIndex,
                                        double latitude, double longitude,
                                        RouteProgressSample progress,
                                        String sectionType, String arrow) {
        StringBuilder result = new StringBuilder("route-light:")
                .append(routeEpoch).append(':');
        if (sourceId != null && !sourceId.isEmpty()) {
            result.append(sourceId);
        } else if (progress != null && progress.valid) {
            result.append("segment-").append(progress.segmentIndex).append('-')
                    .append(Math.round(progress.segmentPosition * 1_000_000d));
        } else if (finite(latitude) && finite(longitude)) {
            result.append("point-").append(Math.round(latitude * 1_000_000d))
                    .append('-').append(Math.round(longitude * 1_000_000d));
        } else {
            result.append("anonymous-").append(sourceIndex);
        }
        if (progress != null && progress.valid) {
            result.append(':').append(progress.segmentIndex).append(':')
                    .append(Math.round(progress.segmentPosition * 1_000_000d));
        } else if (finite(latitude) && finite(longitude)) {
            result.append(':').append(Math.round(latitude * 1_000_000d))
                    .append(':').append(Math.round(longitude * 1_000_000d));
        }
        return result.append(':').append(sectionType == null ? "" : sectionType)
                .append(':').append(arrow == null ? "" : arrow).toString();
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
        StockManeuverCommands.listen(null);
        StockManeuverCommands.reset(routeEpoch);
        main.removeCallbacks(dispatchManeuverCommands);
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
        activeRouteTurns = Collections.emptyList();
        activeRouteTurnsSampleElapsedMs = 0L;
        lastRouteTurnsReadElapsedMs = 0L;
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
        static final Manoeuvre EMPTY = new Manoeuvre("", "", "", "", "",
                new JSONArray(), ManeuverAuxiliary.EMPTY, -1, "", null, 0);
        final String identity;
        final String type;
        final String title;
        final String subtext;
        final String nextRoad;
        final JSONArray directionSigns;
        final ManeuverAuxiliary auxiliary;
        final int distanceMeters;
        final String displayDistance;
        final Bitmap artwork;
        final int artworkSignature;

        Manoeuvre(String identity, String type, String title, String subtext, String nextRoad,
                  JSONArray directionSigns, ManeuverAuxiliary auxiliary, int distanceMeters,
                  String displayDistance, Bitmap artwork, int artworkSignature) {
            this.identity = identity;
            this.type = type;
            this.title = title;
            this.subtext = subtext;
            this.nextRoad = nextRoad;
            this.directionSigns = directionSigns;
            this.auxiliary = auxiliary;
            this.distanceMeters = distanceMeters;
            this.displayDistance = displayDistance;
            this.artwork = artwork;
            this.artworkSignature = artworkSignature;
        }
    }

    private static final class ManeuverAuxiliary {
        static final ManeuverAuxiliary EMPTY = new ManeuverAuxiliary("", "", "", -1);
        final String type;
        final String text;
        final String maneuverType;
        final int distanceMeters;

        ManeuverAuxiliary(String type, String text, String maneuverType, int distanceMeters) {
            this.type = type;
            this.text = text;
            this.maneuverType = maneuverType;
            this.distanceMeters = distanceMeters;
        }
    }

    private static final class StockManeuverCard {
        static final StockManeuverCard EMPTY = new StockManeuverCard(
                "", new JSONArray(), ManeuverAuxiliary.EMPTY, "", null, 0);
        final String nextRoad;
        final JSONArray directionSigns;
        final ManeuverAuxiliary auxiliary;
        final String displayDistance;
        final Bitmap artwork;
        final int artworkSignature;

        StockManeuverCard(String nextRoad, JSONArray directionSigns,
                          ManeuverAuxiliary auxiliary, String displayDistance,
                          Bitmap artwork, int artworkSignature) {
            this.nextRoad = nextRoad;
            this.directionSigns = directionSigns;
            this.auxiliary = auxiliary;
            this.displayDistance = displayDistance;
            this.artwork = artwork;
            this.artworkSignature = artworkSignature;
        }
    }

    private static final class TrafficJamForecast {
        static final TrafficJamForecast EMPTY = new TrafficJamForecast(-1, -1);
        final int durationSeconds;
        final int distanceMeters;

        TrafficJamForecast(int durationSeconds, int distanceMeters) {
            this.durationSeconds = durationSeconds;
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

    private static Object tryInvoke(Object target, String name) {
        if (target == null) return null;
        try {
            return invoke(target, name);
        } catch (Throwable unavailable) {
            return null;
        }
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
